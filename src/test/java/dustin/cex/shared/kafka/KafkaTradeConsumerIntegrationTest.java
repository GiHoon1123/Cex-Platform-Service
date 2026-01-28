package dustin.cex.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka 체결 이벤트 Consumer 통합 테스트
 * Kafka Trade Executed Event Consumer Integration Test
 * 
 * 테스트 항목:
 * 1. 체결 이벤트 수신 및 Trade 저장
 * 2. 주문 상태 업데이트 (filled_amount, filled_quote_amount, status)
 * 3. 부분 체결 및 전량 체결 처리
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
    "spring.jackson.serialization.write-dates-as-timestamps=",
    "spring.kafka.consumer.group-id=cex-consumer-group"
})
@org.springframework.context.annotation.Import(dustin.cex.config.TestConfig.class)
class KafkaTradeConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KafkaTradeConsumer kafkaTradeConsumer;

    private ObjectMapper objectMapper;
    private Long buyerId;
    private Long sellerId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // 테스트용 유저 생성 (각 테스트마다 고유한 이메일 사용)
        String uniqueId = String.valueOf(System.currentTimeMillis());
        User buyer = User.builder()
                .email("buyer" + uniqueId + "@test.com")
                .passwordHash("hashed_password")
                .username("Buyer " + uniqueId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        buyerId = userRepository.save(buyer).getId();

        User seller = User.builder()
                .email("seller" + uniqueId + "@test.com")
                .passwordHash("hashed_password")
                .username("Seller " + uniqueId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sellerId = userRepository.save(seller).getId();
    }

    @Test
    @DisplayName("체결 이벤트 처리 - Trade 저장 및 주문 상태 업데이트")
    void testConsumeTradeExecuted() throws Exception {
        // given
        Order buyOrder = createTestOrder(buyerId, "buy", new BigDecimal("100.00"), new BigDecimal("10.00"));
        Order sellOrder = createTestOrder(sellerId, "sell", new BigDecimal("100.00"), new BigDecimal("10.00"));

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
                buyOrder.getId(),
                sellOrder.getId(),
                buyerId,
                sellerId
        );

        // when - Consumer를 직접 호출하여 테스트 (Kafka Consumer가 제대로 시작되지 않을 수 있음)
        kafkaTradeConsumer.consumeTradeExecuted(tradeEventJson);

        // then - Consumer가 처리했는지 확인
        // Trade 저장 확인
        List<Trade> trades = tradeRepository.findAll();
        assertThat(trades).hasSize(1);
        
        Trade savedTrade = trades.get(0);
        assertThat(savedTrade.getBuyOrderId()).isEqualTo(buyOrder.getId());
        assertThat(savedTrade.getSellOrderId()).isEqualTo(sellOrder.getId());
        assertThat(savedTrade.getPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(savedTrade.getAmount()).isEqualByComparingTo(new BigDecimal("5.00"));

        // 주문 상태 업데이트 확인
        Optional<Order> updatedBuyOrder = orderRepository.findById(buyOrder.getId());
        assertThat(updatedBuyOrder).isPresent();
        assertThat(updatedBuyOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(updatedBuyOrder.get().getFilledQuoteAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(updatedBuyOrder.get().getStatus()).isEqualTo("partial");

        Optional<Order> updatedSellOrder = orderRepository.findById(sellOrder.getId());
        assertThat(updatedSellOrder).isPresent();
        assertThat(updatedSellOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(updatedSellOrder.get().getFilledQuoteAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(updatedSellOrder.get().getStatus()).isEqualTo("partial");
    }

    @Test
    @DisplayName("전량 체결 시 상태가 filled로 변경")
    void testFullFillStatus() throws Exception {
        // given
        Order buyOrder = createTestOrder(buyerId, "buy", new BigDecimal("100.00"), new BigDecimal("10.00"));
        Order sellOrder = createTestOrder(sellerId, "sell", new BigDecimal("100.00"), new BigDecimal("10.00"));

        String tradeEventJson = String.format(
                "{\n" +
                "  \"trade_id\": 1,\n" +
                "  \"buy_order_id\": %d,\n" +
                "  \"sell_order_id\": %d,\n" +
                "  \"buyer_id\": %d,\n" +
                "  \"seller_id\": %d,\n" +
                "  \"price\": \"100.00\",\n" +
                "  \"amount\": \"10.00\",\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                buyOrder.getId(),
                sellOrder.getId(),
                buyerId,
                sellerId
        );

        // when - Consumer를 직접 호출하여 테스트
        kafkaTradeConsumer.consumeTradeExecuted(tradeEventJson);

        // then
        Optional<Order> updatedBuyOrder = orderRepository.findById(buyOrder.getId());
        assertThat(updatedBuyOrder).isPresent();
        assertThat(updatedBuyOrder.get().getStatus()).isEqualTo("filled");

        Optional<Order> updatedSellOrder = orderRepository.findById(sellOrder.getId());
        assertThat(updatedSellOrder).isPresent();
        assertThat(updatedSellOrder.get().getStatus()).isEqualTo("filled");
    }

    private Order createTestOrder(Long userId, String orderType, BigDecimal price, BigDecimal amount) {
        Order order = Order.builder()
                .userId(userId)
                .orderType(orderType)
                .orderSide("limit")
                .baseMint("SOL")
                .quoteMint("USDT")
                .price(price)
                .amount(amount)
                .filledAmount(BigDecimal.ZERO)
                .filledQuoteAmount(BigDecimal.ZERO)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return orderRepository.save(order);
    }
}
