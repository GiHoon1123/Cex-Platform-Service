package dustin.cex.shared.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.shared.kafka.model.OrderCancelledEvent;
import lombok.extern.slf4j.Slf4j;


/**
 * 주문 취소 이벤트 Consumer
 * Order Cancelled Event Consumer
 * 
 * 역할:
 * - Rust 엔진에서 발행한 order-cancelled-* 토픽 메시지 수신 (자산별 토픽)
 * - 주문 상태를 cancelled로 업데이트
 * 
 * 처리 과정:
 * 1. Kafka에서 order-cancelled-* 이벤트 수신 (자산별 토픽)
 * 2. 이벤트 파싱 (JSON → OrderCancelledEvent)
 * 3. DB에서 주문 조회
 * 4. 주문 상태를 cancelled로 업데이트
 * 
 * 자산별 토픽 구독:
 * - order-cancelled-sol: SOL/USDT 주문 취소 이벤트
 * - order-cancelled-usdc: USDC/USDT 주문 취소 이벤트
 * - 확장성: 새로운 자산 추가 시 자동으로 구독
 */
@Slf4j
@Component
public class KafkaOrderCancelledConsumer {
    
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * Kafka Consumer 생성자
     * ObjectMapper를 명시적으로 초기화하여 LocalDateTime 직렬화 지원
     */
    public KafkaOrderCancelledConsumer(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * 주문 취소 이벤트 수신 및 처리
     * 
     * 자산별 토픽 구독:
     * - order-cancelled-sol: SOL/USDT 주문 취소 이벤트
     * - order-cancelled-usdc: USDC/USDT 주문 취소 이벤트
     * - 확장성: 새로운 자산 추가 시 자동으로 구독
     * 
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(
            topicPattern = "order-cancelled*",
            groupId = "cex-consumer-group"
    )
    @Transactional
    public void consumeOrderCancelled(String message) {
        try {
            // log.info("[KafkaOrderCancelledConsumer] 주문 취소 이벤트 수신: {}", message);
            
            // 1. JSON 파싱 (두 가지 형식 지원)
            // 형식 1: OrderCancelledEvent (Rust 엔진에서 발행)
            // 형식 2: Order 엔티티 전체 JSON (Java에서 발행, 로깅용)
            Long orderId = null;
            
            try {
                // 먼저 OrderCancelledEvent 형식으로 파싱 시도
                OrderCancelledEvent event = objectMapper.readValue(message, OrderCancelledEvent.class);
                orderId = event.getOrderId();
            } catch (Exception e) {
                // OrderCancelledEvent 형식이 아니면 Order 엔티티 형식으로 파싱 시도
                try {
                    com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(message);
                    // Order 엔티티에는 "id" 필드가 있음
                    if (jsonNode.has("id")) {
                        orderId = jsonNode.get("id").asLong();
                    } else if (jsonNode.has("orderId")) {
                        orderId = jsonNode.get("orderId").asLong();
                    } else {
                        log.warn("[KafkaOrderCancelledConsumer] 주문 ID를 찾을 수 없음: {}", message);
                        return;
                    }
                } catch (Exception e2) {
                    log.error("[KafkaOrderCancelledConsumer] JSON 파싱 실패: {}", message, e2);
                    return;
                }
            }
            
            if (orderId == null) {
                log.warn("[KafkaOrderCancelledConsumer] 주문 ID가 null입니다: {}", message);
                return;
            }
            
            // 2. DB에서 주문 조회
            var orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                log.warn("[KafkaOrderCancelledConsumer] 주문을 찾을 수 없음: orderId={}", orderId);
                return;
            }
            
            var order = orderOpt.get();
            
            // 3. 이미 취소된 주문인지 확인
            if ("cancelled".equals(order.getStatus())) {
                // log.info("[KafkaOrderCancelledConsumer] 주문이 이미 취소됨: orderId={}", orderId);
                return;
            }
            
            // 4. 주문 상태를 cancelled로 업데이트
            order.setStatus("cancelled");
            orderRepository.save(order);
            
            // log.info("[KafkaOrderCancelledConsumer] 주문 취소 처리 완료: orderId={}", orderId);
            
        } catch (Exception e) {
            log.error("[KafkaOrderCancelledConsumer] 주문 취소 이벤트 처리 실패: {}", message, e);
            // 에러 발생 시에도 계속 처리 (다른 메시지 처리에 영향 없음)
        }
    }
}
