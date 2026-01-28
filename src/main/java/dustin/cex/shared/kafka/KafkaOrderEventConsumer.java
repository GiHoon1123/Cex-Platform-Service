package dustin.cex.shared.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Kafka 주문 이벤트 통합 Consumer
 * Kafka Order Event Unified Consumer
 * 
 * 역할:
 * - Rust 엔진에서 발행한 주문 관련 이벤트를 통합 수신
 * - 같은 토픽으로 통합하여 순서 보장
 * - 파티션 키(orderId)로 같은 주문의 이벤트는 같은 파티션으로 보장
 * 
 * 처리 흐름:
 * 1. Kafka에서 'order-events-*' 토픽 메시지 수신 (자산별 토픽)
 * 2. event_type에 따라 분기 처리:
 *    - "trade_executed": 체결 이벤트 처리
 *    - "order_cancelled": 취소 이벤트 처리
 * 
 * 순서 보장:
 * - 같은 토픽 사용: order-events-{asset}
 * - 파티션 키: orderId (같은 주문의 이벤트는 같은 파티션)
 * - 같은 파티션 내에서 순서 보장됨
 * 
 * 트랜잭션 처리:
 * - 각 이벤트 타입별로 트랜잭션 처리
 * - 실패 시 롤백 (Kafka Consumer가 재시도)
 * 
 * 동시성 제어:
 * - 비관적 락 (PESSIMISTIC_WRITE) 사용
 * - 주문 ID 순서로 락 획득하여 데드락 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOrderEventConsumer {
    
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[KafkaOrderEventConsumer] Consumer 초기화 완료 - topicPattern: order-events-*, groupId: cex-consumer-group");
    }
    
    /**
     * 주문 이벤트 통합 수신 및 처리
     * Consume unified order events
     * 
     * 자산별 토픽 구독:
     * - order-events-sol: SOL/USDT 주문 이벤트
     * - order-events-usdc: USDC/USDT 주문 이벤트
     * - 확장성: 새로운 자산 추가 시 자동으로 구독
     * 
     * 처리 과정:
     * 1. JSON 파싱 및 event_type 확인
     * 2. event_type에 따라 분기 처리:
     *    - "trade_executed": 체결 이벤트 처리
     *    - "order_cancelled": 취소 이벤트 처리
     * 
     * 트랜잭션:
     * - 각 이벤트 타입별로 트랜잭션 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     * 
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(topicPattern = "order-events-*", groupId = "cex-consumer-group")
    @Transactional
    public void consumeOrderEvent(String message) {
        log.info("[KafkaOrderEventConsumer] 메시지 수신: {}", message);
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            
            // event_type 확인
            String eventType = jsonNode.get("event_type").asText();
            log.info("[KafkaOrderEventConsumer] 이벤트 타입: {}", eventType);
            
            switch (eventType) {
                case "trade_executed":
                    log.info("[KafkaOrderEventConsumer] 체결 이벤트 처리 시작");
                    handleTradeExecuted(jsonNode);
                    log.info("[KafkaOrderEventConsumer] 체결 이벤트 처리 완료");
                    break;
                case "order_cancelled":
                    log.info("[KafkaOrderEventConsumer] 취소 이벤트 처리 시작");
                    handleOrderCancelled(jsonNode);
                    log.info("[KafkaOrderEventConsumer] 취소 이벤트 처리 완료");
                    break;
                default:
                    log.warn("[KafkaOrderEventConsumer] 알 수 없는 이벤트 타입: eventType={}, message={}", eventType, message);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[KafkaOrderEventConsumer] JSON 파싱 실패: {}", message, e);
            throw new RuntimeException("JSON 파싱 실패", e);
        } catch (Exception e) {
            log.error("[KafkaOrderEventConsumer] 주문 이벤트 처리 실패: {}", message, e);
            throw e; // 트랜잭션 롤백을 위해 예외 재발생
        }
    }
    
    /**
     * 체결 이벤트 처리
     * Handle trade executed event
     * 
     * 처리 과정:
     * 1. Trade 저장
     * 2. 매수 주문 업데이트 (비관적 락)
     * 3. 매도 주문 업데이트 (비관적 락)
     * 
     * 트랜잭션:
     * - 모든 작업을 하나의 트랜잭션으로 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     */
    private void handleTradeExecuted(JsonNode jsonNode) {
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
        
        // Trade 저장
        Trade savedTrade = tradeRepository.save(trade);
        
        // 매수 주문 업데이트 (비관적 락, 주문 ID 순서로 락 획득)
        updateOrderForTrade(buyOrderId, sellOrderId, price, amount, savedTrade.getId());
        
        // 매도 주문 업데이트 (비관적 락, 주문 ID 순서로 락 획득)
        updateOrderForTrade(sellOrderId, buyOrderId, price, amount, savedTrade.getId());
    }
    
    /**
     * 주문 업데이트 (체결 이벤트용)
     * Update order for trade execution
     * 
     * 처리 과정:
     * 1. 주문 조회 (비관적 락)
     * 2. filled_amount, filled_quote_amount 업데이트
     * 3. 주문 상태 업데이트 (partial/filled)
     * 
     * 동시성 제어:
     * - 비관적 락 사용
     * - 주문 ID 순서로 락 획득하여 데드락 방지
     */
    private void updateOrderForTrade(Long orderId, Long otherOrderId, BigDecimal price, BigDecimal amount, Long tradeId) {
        // 데드락 방지: 주문 ID 순서로 락 획득
        Long firstOrderId = orderId < otherOrderId ? orderId : otherOrderId;
        Long secondOrderId = orderId < otherOrderId ? otherOrderId : orderId;
        
        // 첫 번째 주문 업데이트
        updateOrderWithLock(firstOrderId, amount, price.multiply(amount));
        
        // 두 번째 주문 업데이트 (같은 체결이면 두 주문 모두 업데이트)
        if (!firstOrderId.equals(secondOrderId)) {
            updateOrderWithLock(secondOrderId, amount, price.multiply(amount));
        }
    }
    
    /**
     * 주문 상태 업데이트 (비관적 락 사용)
     * Update Order Status with Pessimistic Lock
     * 
     * 체결된 수량과 금액을 누적하여 주문 상태를 업데이트합니다.
     * 비관적 락을 사용하여 동시성 문제를 방지합니다.
     */
    private void updateOrderWithLock(Long orderId, BigDecimal filledAmount, BigDecimal filledQuoteAmount) {
        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        if (orderOpt.isEmpty()) {
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
        
        order.setFilledAmount(newFilledAmount);
        order.setFilledQuoteAmount(newFilledQuoteAmount);
        
        // 상태 계산
        if (newFilledAmount.compareTo(order.getAmount()) >= 0) {
            // 전량 체결
            order.setStatus("filled");
        } else if (newFilledAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 부분 체결
            order.setStatus("partial");
        }
        
        orderRepository.save(order);
    }
    
    /**
     * 취소 이벤트 처리
     * Handle order cancelled event
     * 
     * 처리 과정:
     * 1. 주문 조회 (비관적 락)
     * 2. 주문 상태를 cancelled로 업데이트
     * 
     * 트랜잭션:
     * - 모든 작업을 하나의 트랜잭션으로 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     */
    private void handleOrderCancelled(JsonNode jsonNode) {
        Long orderId = jsonNode.get("order_id").asLong();
        
        // 주문 조회 (비관적 락)
        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("[KafkaOrderEventConsumer] 주문을 찾을 수 없음: orderId={}", orderId);
            return;
        }
        
        Order order = orderOpt.get();
        
        // 이미 취소된 주문인지 확인
        if ("cancelled".equals(order.getStatus())) {
            return;
        }
        
        // 주문 상태를 cancelled로 업데이트
        order.setStatus("cancelled");
        orderRepository.save(order);
    }
    
    /**
     * 타임스탬프 파싱
     * Parse timestamp
     */
    private LocalDateTime parseTimestamp(JsonNode timestampNode) {
        if (timestampNode == null || timestampNode.isNull()) {
            return LocalDateTime.now();
        }
        
        // ISO 8601 형식 또는 Unix timestamp 지원
        if (timestampNode.isTextual()) {
            String timestampStr = timestampNode.asText();
            try {
                return LocalDateTime.parse(timestampStr);
            } catch (Exception e) {
                // Unix timestamp로 시도
                long timestamp = Long.parseLong(timestampStr);
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
            }
        } else if (timestampNode.isNumber()) {
            long timestamp = timestampNode.asLong();
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
        }
        
        return LocalDateTime.now();
    }
}
