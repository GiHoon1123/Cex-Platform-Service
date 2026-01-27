package dustin.cex.shared.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka 체결 이벤트 Consumer
 * Kafka Trade Executed Event Consumer
 * 
 * 역할:
 * - Rust 엔진에서 발행한 체결 이벤트를 수신
 * - 체결 내역을 DB에 저장
 * - 주문 상태 업데이트 (filled_amount, filled_quote_amount, status)
 * 
 * 처리 흐름:
 * 1. Kafka에서 'trade-executed-*' 토픽 메시지 수신 (자산별 토픽)
 * 2. JSON 파싱
 * 3. Trade 엔티티 생성 및 DB 저장
 * 4. 매수 주문 업데이트 (비관적 락 사용)
 * 5. 매도 주문 업데이트 (비관적 락 사용)
 * 
 * 트랜잭션 처리:
 * - Trade 저장 + 주문 상태 업데이트를 하나의 트랜잭션으로 처리
 * - 잔고 업데이트는 별도 처리 (정산 도메인, 향후 구현)
 * 
 * 동시성 제어:
 * - 비관적 락 (PESSIMISTIC_WRITE) 사용
 * - 주문 ID 순서로 락 획득하여 데드락 방지
 * 
 * 주의사항:
 * - 비동기 처리 (논블로킹)
 * - 실패해도 재시도 (Kafka Consumer 자동 처리)
 * - 중복 체결 방지 (idempotency key 사용, 향후 구현)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaTradeConsumer {
    
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 체결 이벤트 수신 및 처리
     * Consume trade executed event
     * 
     * 자산별 토픽 구독:
     * - trade-executed-sol: SOL/USDT 체결 이벤트
     * - trade-executed-usdc: USDC/USDT 체결 이벤트
     * - 확장성: 새로운 자산 추가 시 자동으로 구독
     * 
     * 처리 과정:
     * 1. Trade 저장
     * 2. 매수 주문 업데이트 (비관적 락)
     * 3. 매도 주문 업데이트 (비관적 락)
     * 
     * 트랜잭션:
     * - 모든 작업을 하나의 트랜잭션으로 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     * 
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(topicPattern = "trade-executed-*", groupId = "cex-consumer-group")
    @Transactional
    public void consumeTradeExecuted(String message) {
        try {
            // ============================================
            // 1. JSON 파싱 및 Trade 엔티티 생성
            // ============================================
            JsonNode jsonNode = objectMapper.readTree(message);
            
            Long buyOrderId = jsonNode.get("buy_order_id").asLong();
            Long sellOrderId = jsonNode.get("sell_order_id").asLong();
            BigDecimal price = new BigDecimal(jsonNode.get("price").asText());
            BigDecimal amount = new BigDecimal(jsonNode.get("amount").asText());
            
            Trade trade = Trade.builder()
                    .buyOrderId(buyOrderId)
                    .sellOrderId(sellOrderId)
                    .buyerId(jsonNode.get("buyer_id").asLong())
                    .sellerId(jsonNode.get("seller_id").asLong())
                    .baseMint(jsonNode.get("base_mint").asText())
                    .quoteMint(jsonNode.get("quote_mint").asText())
                    .price(price)
                    .amount(amount)
                    .createdAt(parseTimestamp(jsonNode.get("timestamp")))
                    .build();
            
            // ============================================
            // 2. Trade 저장
            // ============================================
            Trade savedTrade = tradeRepository.save(trade);
            
            // ============================================
            // 3. 주문 상태 업데이트 (비관적 락 사용)
            // ============================================
            // 데드락 방지: 주문 ID 순서로 락 획득
            BigDecimal quoteAmount = price.multiply(amount);
            
            if (buyOrderId < sellOrderId) {
                // 매수 주문 먼저 업데이트
                updateOrderWithLock(buyOrderId, amount, quoteAmount);
                updateOrderWithLock(sellOrderId, amount, quoteAmount);
            } else {
                // 매도 주문 먼저 업데이트
                updateOrderWithLock(sellOrderId, amount, quoteAmount);
                updateOrderWithLock(buyOrderId, amount, quoteAmount);
            }
            
            // 트랜잭션 커밋 완료 ✅
            
        } catch (Exception e) {
            log.error("[KafkaTradeConsumer] 체결 이벤트 처리 실패: message={}, error={}", message, e.getMessage(), e);
            // Kafka Consumer가 자동으로 재시도 처리
            throw new RuntimeException("체결 이벤트 처리 실패", e);
        }
    }
    
    /**
     * 주문 상태 업데이트 (비관적 락 사용)
     * Update Order Status with Pessimistic Lock
     * 
     * 체결된 수량과 금액을 누적하여 주문 상태를 업데이트합니다.
     * 비관적 락을 사용하여 동시성 문제를 방지합니다.
     * 
     * 처리 과정:
     * 1. 비관적 락으로 주문 조회 (SELECT FOR UPDATE)
     * 2. filled_amount 누적 (기존 + 체결 수량)
     * 3. filled_quote_amount 누적 (기존 + 체결 금액)
     * 4. 상태 계산 (partial/filled)
     * 5. 주문 업데이트
     * 
     * 주문 상태 계산:
     * - filled_amount >= amount → "filled" (전량 체결)
     * - filled_amount > 0 && filled_amount < amount → "partial" (부분 체결)
     * - filled_amount == 0 → "pending" (체결 안 됨, 이미 체결된 경우는 없어야 함)
     * 
     * @param orderId 주문 ID
     * @param filledAmount 체결된 수량 (누적할 값)
     * @param filledQuoteAmount 체결된 금액 (누적할 값, price * amount)
     */
    private void updateOrderWithLock(Long orderId, BigDecimal filledAmount, BigDecimal filledQuoteAmount) {
        // 비관적 락으로 주문 조회 (SELECT FOR UPDATE)
        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        
        if (orderOpt.isEmpty()) {
            // 주문이 없으면 스킵 (이미 취소되었거나 삭제된 경우)
            // 로그는 필요시만 남기기 (너무 많은 로그 방지)
            return;
        }
        
        Order order = orderOpt.get();
        
        // 이미 취소된 주문이면 스킵
        if ("cancelled".equals(order.getStatus()) || "rejected".equals(order.getStatus())) {
            return;
        }
        
        // filled_amount 누적
        BigDecimal newFilledAmount = order.getFilledAmount().add(filledAmount);
        BigDecimal newFilledQuoteAmount = order.getFilledQuoteAmount().add(filledQuoteAmount);
        
        // 상태 계산
        String newStatus;
        if (newFilledAmount.compareTo(order.getAmount()) >= 0) {
            // 전량 체결: filled_amount >= amount
            newStatus = "filled";
        } else if (newFilledAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 부분 체결: filled_amount > 0 && filled_amount < amount
            newStatus = "partial";
        } else {
            // 체결 안 됨 (이미 체결된 경우는 없어야 함)
            newStatus = "pending";
        }
        
        // 주문 업데이트
        order.setFilledAmount(newFilledAmount);
        order.setFilledQuoteAmount(newFilledQuoteAmount);
        order.setStatus(newStatus);
        
        orderRepository.save(order);
    }
    
    /**
     * 타임스탬프 파싱
     * Parse timestamp from JSON
     * 
     * Rust에서 보낸 timestamp는 ISO 8601 형식 또는 Unix timestamp (milliseconds)
     */
    private LocalDateTime parseTimestamp(JsonNode timestampNode) {
        if (timestampNode == null || timestampNode.isNull()) {
            return LocalDateTime.now();
        }
        
        // Unix timestamp (milliseconds) 형식인 경우
        if (timestampNode.isNumber()) {
            long timestampMs = timestampNode.asLong();
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.systemDefault());
        }
        
        // ISO 8601 문자열 형식인 경우
        if (timestampNode.isTextual()) {
            String timestampStr = timestampNode.asText();
            try {
                // ISO 8601 파싱 시도
                Instant instant = Instant.parse(timestampStr);
                return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            } catch (Exception e) {
                log.warn("[KafkaTradeConsumer] 타임스탬프 파싱 실패, 현재 시간 사용: {}", timestampStr);
                return LocalDateTime.now();
            }
        }
        
        return LocalDateTime.now();
    }
}
