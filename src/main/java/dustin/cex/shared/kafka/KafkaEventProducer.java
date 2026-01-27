package dustin.cex.shared.kafka;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka 이벤트 발행자
 * Kafka Event Producer
 * 
 * 역할:
 * - 주문 생성, 체결 등 이벤트를 Kafka로 발행
 * - 비동기 처리 (논블로킹)
 * - 로깅 및 데이터 분석용
 * 
 * 주의사항:
 * - 이벤트 발행은 비동기로 처리됨 (논블로킹)
 * - 엔진 응답과 독립적으로 동작
 * - 실패해도 주문 생성에는 영향 없음 (로깅만)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProducer {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    /**
     * 주문 생성 이벤트 발행
     * Publish order created event
     * 
     * @param orderJson 주문 정보 (JSON 문자열)
     */
    public void publishOrderCreated(String orderJson) {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    kafkaTemplate.send("order-created", orderJson);
                    // log.debug("[KafkaEventProducer] 주문 생성 이벤트 발행 완료");
                } catch (Exception e) {
                    log.error("[KafkaEventProducer] 주문 생성 이벤트 발행 실패: {}", e.getMessage());
                }
            });
            
            // 비동기 처리 (논블로킹)
            future.exceptionally(ex -> {
                log.error("[KafkaEventProducer] 주문 생성 이벤트 발행 중 예외 발생: {}", ex.getMessage());
                return null;
            });
            
        } catch (Exception e) {
            log.error("[KafkaEventProducer] 주문 생성 이벤트 발행 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 주문 취소 이벤트 발행
     * Publish order cancelled event
     * 
     * @param orderJson 주문 정보 (JSON 문자열)
     */
    public void publishOrderCancelled(String orderJson) {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    kafkaTemplate.send("order-cancelled", orderJson);
                    // log.debug("[KafkaEventProducer] 주문 취소 이벤트 발행 완료");
                } catch (Exception e) {
                    log.error("[KafkaEventProducer] 주문 취소 이벤트 발행 실패: {}", e.getMessage());
                }
            });
            
            // 비동기 처리 (논블로킹)
            future.exceptionally(ex -> {
                log.error("[KafkaEventProducer] 주문 취소 이벤트 발행 중 예외 발생: {}", ex.getMessage());
                return null;
            });
            
        } catch (Exception e) {
            log.error("[KafkaEventProducer] 주문 취소 이벤트 발행 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 체결 이벤트 발행 (엔진에서 발행, Java는 소비만)
     * Publish trade executed event
     * 
     * 주의: 이 메서드는 향후 엔진에서 직접 발행할 예정
     * 현재는 Java에서 발행하지 않음
     */
    @Deprecated
    public void publishTradeExecuted(String tradeJson) {
        // 향후 엔진에서 직접 발행할 예정
        log.warn("[KafkaEventProducer] publishTradeExecuted는 사용하지 않습니다 (엔진에서 발행)");
    }
}
