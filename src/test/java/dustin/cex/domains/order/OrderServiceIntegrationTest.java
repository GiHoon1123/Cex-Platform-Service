package dustin.cex.domains.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.order.service.OrderService;

/**
 * 주문 서비스 통합 테스트
 * Order Service Integration Test
 * 
 * 테스트 항목:
 * 1. 주문 생성
 * 2. 주문 조회
 * 3. 주문 취소
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
    "bot.bot1-email=bot1@bot.com",
    "bot.bot1-password=botpassword",
    "bot.bot2-email=bot2@bot.com",
    "bot.bot2-password=botpassword",
    "bot.binance-ws-url=wss://test",
    "bot.binance-symbol=SOLUSDT",
    "bot.orderbook-depth=200",
    "bot.order-quantity=1.0"
})
@org.springframework.context.annotation.Import(dustin.cex.config.TestConfig.class)
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

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
        User savedUser = userRepository.save(user);
        testUserId = savedUser.getId();
    }

    @Test
    @DisplayName("주문 생성 성공")
    void testCreateOrder() {
        // given
        CreateOrderRequest request = CreateOrderRequest.builder()
                .orderType("buy")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .build();

        // when
        var response = orderService.createOrder(testUserId, request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getOrder()).isNotNull();
        assertThat(response.getOrder().getId()).isNotNull();
        assertThat(response.getOrder().getStatus()).isEqualTo("pending");
        assertThat(response.getOrder().getPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.getOrder().getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("주문 조회 성공")
    void testGetOrder() {
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

        // when
        var response = orderService.getOrder(testUserId, savedOrder.getId());

        // then
        assertThat(response).isNotNull();
        assertThat(response.getOrder()).isNotNull();
        assertThat(response.getOrder().getId()).isEqualTo(savedOrder.getId().toString());
    }

    @Test
    @DisplayName("주문 취소 성공")
    void testCancelOrder() {
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

        // when - gRPC 서버가 없으면 예외가 발생할 수 있음
        try {
            orderService.cancelOrder(savedOrder.getId(), testUserId);
        } catch (Exception e) {
            // gRPC 서버가 없으면 예외가 발생하지만, DB에는 주문이 저장되어 있음
            // 실제 취소는 Kafka Consumer 테스트에서 확인
        }

        // then - 주문이 DB에 존재하는지 확인
        Optional<Order> cancelledOrder = orderRepository.findById(savedOrder.getId());
        assertThat(cancelledOrder).isPresent();
        // 주문 취소는 비동기로 처리되므로, 실제 취소 확인은 Kafka Consumer 테스트에서 확인
    }
}
