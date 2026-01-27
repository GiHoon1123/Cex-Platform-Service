package dustin.cex.shared.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Kafka 체결 이벤트 Consumer
 * Kafka Trade Executed Event Consumer
 * 
 * 역할:
 * - Rust 엔진에서 발행한 체결 이벤트를 수신
 * - 체결 내역을 DB에 저장
 * - 주문 상태 업데이트 (향후 구현)
 * 
 * 처리 흐름:
 * 1. Kafka에서 'trade-executed' 토픽 메시지 수신
 * 2. JSON 파싱
 * 3. Trade 엔티티 생성 및 DB 저장
 * 4. 주문 상태 업데이트 (향후 구현)
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
    private final ObjectMapper objectMapper = new ObjectMapper(); // 직접 생성 (Spring Boot 자동 빈 사용 안 함, final 제거)
    
    /**
     * 체결 이벤트 수신 및 처리
     * Consume trade executed event
     * 
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(topics = "trade-executed", groupId = "cex-consumer-group")
    public void consumeTradeExecuted(String message) {
        try {
            log.debug("[KafkaTradeConsumer] 체결 이벤트 수신: {}", message);
            
            // JSON 파싱
            JsonNode jsonNode = objectMapper.readTree(message);
            
            // Trade 엔티티 생성
            Trade trade = Trade.builder()
                    .buyOrderId(jsonNode.get("buy_order_id").asLong())
                    .sellOrderId(jsonNode.get("sell_order_id").asLong())
                    .buyerId(jsonNode.get("buyer_id").asLong())
                    .sellerId(jsonNode.get("seller_id").asLong())
                    .baseMint(jsonNode.get("base_mint").asText())
                    .quoteMint(jsonNode.get("quote_mint").asText())
                    .price(new BigDecimal(jsonNode.get("price").asText()))
                    .amount(new BigDecimal(jsonNode.get("amount").asText()))
                    .createdAt(parseTimestamp(jsonNode.get("timestamp")))
                    .build();
            
            // DB 저장
            Trade savedTrade = tradeRepository.save(trade);
            
            log.info("[KafkaTradeConsumer] 체결 내역 저장 완료: tradeId={}, buyOrderId={}, sellOrderId={}, price={}, amount={}",
                    savedTrade.getId(), savedTrade.getBuyOrderId(), savedTrade.getSellOrderId(),
                    savedTrade.getPrice(), savedTrade.getAmount());
            
            // TODO: 주문 상태 업데이트 (filled_amount, filled_quote_amount 등)
            // TODO: 잔고 업데이트 (user_balances 테이블)
            
        } catch (Exception e) {
            log.error("[KafkaTradeConsumer] 체결 이벤트 처리 실패: message={}, error={}", message, e.getMessage(), e);
            // Kafka Consumer가 자동으로 재시도 처리
            throw new RuntimeException("체결 이벤트 처리 실패", e);
        }
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
