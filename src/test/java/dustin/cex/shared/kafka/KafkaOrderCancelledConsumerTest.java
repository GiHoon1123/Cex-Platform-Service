package dustin.cex.shared.kafka;

import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 주문 취소 이벤트 Consumer 통합 테스트
 * Kafka Order Cancelled Event Consumer Integration Test
 * 
 * 테스트 항목:
 * 1. 취소 이벤트 수신 및 주문 상태 업데이트
 * 2. 중복 취소 방지
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"order-cancelled-sol"},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:9092",
                "port=9092"
        }
)
@ActiveProfiles("test")
@DirtiesContext
class KafkaOrderCancelledConsumerTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaOrderCancelledConsumer kafkaOrderCancelledConsumer;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        orderRepository.deleteAll();
    }

    /**
     * 테스트: 주문 취소 이벤트 처리
     * 
     * 시나리오:
     * 1. 주문 생성 (pending 상태)
     * 2. Kafka에 취소 이벤트 발행
     * 3. Consumer가 처리하여 주문 상태가 cancelled로 변경되는지 확인
     */
    @Test
    @Transactional
    void testConsumeOrderCancelled() throws Exception {
        // ============================================
        // 1. 테스트용 주문 생성
        // ============================================
        Order order = Order.builder()
                .userId(1L)
                .orderType("buy")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .filledAmount(BigDecimal.ZERO)
                .filledQuoteAmount(BigDecimal.ZERO)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Order savedOrder = orderRepository.save(order);

        // ============================================
        // 2. 취소 이벤트 JSON 생성
        // ============================================
        String cancelEventJson = String.format(
                "{\n" +
                "  \"order_id\": %d,\n" +
                "  \"user_id\": 1,\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedOrder.getId()
        );

        // ============================================
        // 3. Kafka에 취소 이벤트 발행
        // ============================================
        kafkaTemplate.send("order-cancelled-sol", cancelEventJson).get();

        // ============================================
        // 4. Consumer가 처리할 때까지 대기
        // ============================================
        Thread.sleep(1000);

        // ============================================
        // 5. 주문 상태가 cancelled로 변경되었는지 확인
        // ============================================
        Optional<Order> updatedOrder = orderRepository.findById(savedOrder.getId());
        assertThat(updatedOrder).isPresent();
        assertThat(updatedOrder.get().getStatus()).isEqualTo("cancelled");
    }

    /**
     * 테스트: 중복 취소 방지
     * 
     * 시나리오:
     * 1. 주문 생성 및 취소
     * 2. 같은 주문에 대해 다시 취소 이벤트 발행
     * 3. 이미 취소된 주문은 다시 처리되지 않아야 함
     */
    @Test
    @Transactional
    void testDuplicateCancellation() throws Exception {
        // ============================================
        // 1. 주문 생성 및 취소
        // ============================================
        Order order = Order.builder()
                .userId(1L)
                .orderType("buy")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .filledAmount(BigDecimal.ZERO)
                .filledQuoteAmount(BigDecimal.ZERO)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Order savedOrder = orderRepository.save(order);

        String cancelEventJson = String.format(
                "{\n" +
                "  \"order_id\": %d,\n" +
                "  \"user_id\": 1,\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedOrder.getId()
        );

        // 첫 번째 취소
        kafkaTemplate.send("order-cancelled-sol", cancelEventJson).get();
        Thread.sleep(1000);

        // ============================================
        // 2. 두 번째 취소 시도 (중복)
        // ============================================
        kafkaTemplate.send("order-cancelled-sol", cancelEventJson).get();
        Thread.sleep(1000);

        // ============================================
        // 3. 주문 상태 확인 (여전히 cancelled)
        // ============================================
        Optional<Order> updatedOrder = orderRepository.findById(savedOrder.getId());
        assertThat(updatedOrder).isPresent();
        assertThat(updatedOrder.get().getStatus()).isEqualTo("cancelled");
    }
}
