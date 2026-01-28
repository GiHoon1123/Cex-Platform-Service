package dustin.cex.integration;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.order.service.OrderService;
import dustin.cex.domains.trade.repository.TradeRepository;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 주문-체결-취소 전체 플로우 통합 테스트
 * Order-Trade-Cancellation Full Flow Integration Test
 * 
 * 테스트 항목:
 * 1. 주문 생성 → 체결 이벤트 처리 → 주문 상태 업데이트
 * 2. 주문 생성 → 취소 이벤트 처리 → 주문 상태 업데이트
 * 3. 전체 플로우 정합성 확인
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
class OrderTradeCancellationFlowTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private Long buyerId;
    private Long sellerId;

    @BeforeEach
    void setUp() {
        // 테스트용 유저 생성
        User buyer = User.builder()
                .email("buyer@test.com")
                .passwordHash("hashed_password")
                .username("Buyer")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        buyerId = userRepository.save(buyer).getId();

        User seller = User.builder()
                .email("seller@test.com")
                .passwordHash("hashed_password")
                .username("Seller")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sellerId = userRepository.save(seller).getId();
    }

    @Test
    @DisplayName("전체 플로우: 주문 생성 → 체결 이벤트 처리 → 주문 상태 업데이트")
    void testFullFlowOrderToTrade() throws Exception {
        // 1. 주문 생성
        CreateOrderRequest buyRequest = CreateOrderRequest.builder()
                .orderType("buy")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .build();
        var buyOrderResponse = orderService.createOrder(buyerId, buyRequest);
        Long buyOrderId = Long.parseLong(buyOrderResponse.getOrder().getId());

        CreateOrderRequest sellRequest = CreateOrderRequest.builder()
                .orderType("sell")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .build();
        var sellOrderResponse = orderService.createOrder(sellerId, sellRequest);
        Long sellOrderId = Long.parseLong(sellOrderResponse.getOrder().getId());

        // 주문이 생성되었는지 확인
        Optional<Order> createdBuyOrder = orderRepository.findById(buyOrderId);
        assertThat(createdBuyOrder).isPresent();
        assertThat(createdBuyOrder.get().getStatus()).isEqualTo("pending");

        // 2. 체결 이벤트 발행
        String tradeEventJson = String.format(
                "{\n" +
                "  \"trade_id\": 1,\n" +
                "  \"buy_order_id\": %d,\n" +
                "  \"sell_order_id\": %d,\n" +
                "  \"buyer_id\": %d,\n" +
                "  \"seller_id\": %d,\n" +
                "  \"price\": \"100.00\",\n" +
                "  \"amount\": \"5.00\",\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                buyOrderId,
                sellOrderId,
                buyerId,
                sellerId
        );
        kafkaTemplate.send("trade-executed-sol", tradeEventJson).get();

        // 3. 체결 이벤트 처리 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // Trade 저장 확인
            List<dustin.cex.domains.trade.model.entity.Trade> trades = tradeRepository.findAll();
            assertThat(trades).hasSize(1);

            // 주문 상태 업데이트 확인
            Optional<Order> updatedBuyOrder = orderRepository.findById(buyOrderId);
            assertThat(updatedBuyOrder).isPresent();
            assertThat(updatedBuyOrder.get().getStatus()).isEqualTo("partial");
            assertThat(updatedBuyOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));

            Optional<Order> updatedSellOrder = orderRepository.findById(sellOrderId);
            assertThat(updatedSellOrder).isPresent();
            assertThat(updatedSellOrder.get().getStatus()).isEqualTo("partial");
            assertThat(updatedSellOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        });
    }

    @Test
    @DisplayName("전체 플로우: 주문 생성 → 취소 이벤트 처리 → 주문 상태 업데이트")
    void testFullFlowOrderToCancellation() throws Exception {
        // 1. 주문 생성
        CreateOrderRequest request = CreateOrderRequest.builder()
                .orderType("buy")
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(new BigDecimal("100.00"))
                .amount(new BigDecimal("10.00"))
                .build();
        var orderResponse = orderService.createOrder(buyerId, request);
        Long orderId = Long.parseLong(orderResponse.getOrder().getId());

        // 주문이 생성되었는지 확인
        Optional<Order> createdOrder = orderRepository.findById(orderId);
        assertThat(createdOrder).isPresent();
        assertThat(createdOrder.get().getStatus()).isEqualTo("pending");

        // 2. 취소 이벤트 발행
        String cancelEventJson = String.format(
                "{\n" +
                "  \"order_id\": %d,\n" +
                "  \"user_id\": %d,\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                orderId,
                buyerId
        );
        kafkaTemplate.send("order-cancelled-sol", cancelEventJson).get();

        // 3. 취소 이벤트 처리 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<Order> cancelledOrder = orderRepository.findById(orderId);
            assertThat(cancelledOrder).isPresent();
            assertThat(cancelledOrder.get().getStatus()).isEqualTo("cancelled");
        });
    }
}
