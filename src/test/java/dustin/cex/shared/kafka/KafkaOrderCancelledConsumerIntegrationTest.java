package dustin.cex.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;

/**
 * Kafka 주문 취소 이벤트 Consumer 통합 테스트
 * Kafka Order Cancelled Event Consumer Integration Test
 * 
 * 테스트 항목:
 * 1. 취소 이벤트 수신 및 주문 상태 업데이트
 * 2. 중복 취소 방지
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "bot.bot1-email=bot1@bot.com",
    "bot.bot1-password=botpassword",
    "bot.bot2-email=bot2@bot.com",
    "bot.bot2-password=botpassword",
    "bot.binance-ws-url=wss://test",
    "bot.binance-symbol=SOLUSDT",
    "bot.orderbook-depth=200",
    "bot.order-quantity=1.0",
    "spring.kafka.consumer.group-id=cex-consumer-group"
})
@org.springframework.context.annotation.Import(dustin.cex.config.TestConfig.class)
class KafkaOrderCancelledConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KafkaOrderCancelledConsumer kafkaOrderCancelledConsumer;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        // 테스트용 유저 생성 (각 테스트마다 고유한 이메일 사용)
        String uniqueId = String.valueOf(System.currentTimeMillis());
        User user = User.builder()
                .email("test" + uniqueId + "@test.com")
                .passwordHash("hashed_password")
                .username("Test User " + uniqueId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        testUserId = userRepository.save(user).getId();
    }

    @Test
    @DisplayName("주문 취소 이벤트 처리 - 주문 상태가 cancelled로 변경")
    void testConsumeOrderCancelled() throws Exception {
        // given
        Order order = Order.builder()
                .userId(testUserId)
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
                "  \"user_id\": %d,\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedOrder.getId(),
                testUserId
        );

        // when - Consumer를 직접 호출하여 테스트
        kafkaOrderCancelledConsumer.consumeOrderCancelled(cancelEventJson);

        // then
        Optional<Order> updatedOrder = orderRepository.findById(savedOrder.getId());
        assertThat(updatedOrder).isPresent();
        assertThat(updatedOrder.get().getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("중복 취소 방지 - 이미 취소된 주문은 다시 처리하지 않음")
    void testDuplicateCancellation() throws Exception {
        // given
        Order order = Order.builder()
                .userId(testUserId)
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
                "  \"user_id\": %d,\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedOrder.getId(),
                testUserId
        );

        // when - 첫 번째 취소
        kafkaOrderCancelledConsumer.consumeOrderCancelled(cancelEventJson);
        
        // 첫 번째 취소 확인
        Optional<Order> orderOpt = orderRepository.findById(savedOrder.getId());
        assertThat(orderOpt).isPresent();
        assertThat(orderOpt.get().getStatus()).isEqualTo("cancelled");

        // 두 번째 취소 시도 (중복)
        kafkaOrderCancelledConsumer.consumeOrderCancelled(cancelEventJson);

        // then - 여전히 cancelled 상태 유지
        Optional<Order> finalOrder = orderRepository.findById(savedOrder.getId());
        assertThat(finalOrder).isPresent();
        assertThat(finalOrder.get().getStatus()).isEqualTo("cancelled");
    }
}
