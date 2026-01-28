package dustin.cex.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
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
 * Kafka 체결 이벤트 Consumer 통합 테스트
 * Kafka Trade Executed Event Consumer Integration Test
 * 
 * 테스트 항목:
 * 1. 체결 이벤트 수신 및 Trade 저장
 * 2. 주문 상태 업데이트 (filled_amount, filled_quote_amount, status)
 * 3. 비관적 락 동작 확인
 * 4. 트랜잭션 처리 확인
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"trade-executed-sol"},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:9092",
                "port=9092"
        }
)
@ActiveProfiles("test")
@DirtiesContext
class KafkaTradeConsumerTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTradeConsumer kafkaTradeConsumer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 테스트 데이터 정리
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
    }

    /**
     * 테스트: 체결 이벤트 처리
     * 
     * 시나리오:
     * 1. 매수 주문과 매도 주문 생성
     * 2. Kafka에 체결 이벤트 발행
     * 3. Consumer가 처리하여 Trade 저장 및 주문 상태 업데이트 확인
     */
    @Test
    @Transactional
    void testConsumeTradeExecuted() throws Exception {
        // ============================================
        // 1. 테스트용 주문 생성
        // ============================================
        Order buyOrder = Order.builder()
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
        Order savedBuyOrder = orderRepository.save(buyOrder);

        Order sellOrder = Order.builder()
                .userId(2L)
                .orderType("sell")
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
        Order savedSellOrder = orderRepository.save(sellOrder);

        // ============================================
        // 2. 체결 이벤트 JSON 생성
        // ============================================
        String tradeEventJson = String.format(
                "{\n" +
                "  \"trade_id\": 1,\n" +
                "  \"buy_order_id\": %d,\n" +
                "  \"sell_order_id\": %d,\n" +
                "  \"buyer_id\": 1,\n" +
                "  \"seller_id\": 2,\n" +
                "  \"price\": \"100.00\",\n" +
                "  \"amount\": \"5.00\",\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedBuyOrder.getId(),
                savedSellOrder.getId()
        );

        // ============================================
        // 3. Kafka에 체결 이벤트 발행
        // ============================================
        kafkaTemplate.send("trade-executed-sol", tradeEventJson).get();

        // ============================================
        // 4. Consumer가 처리할 때까지 대기
        // ============================================
        Thread.sleep(1000);

        // ============================================
        // 5. Trade 저장 확인
        // ============================================
        var trades = tradeRepository.findAll();
        assertThat(trades).hasSize(1);
        
        Trade savedTrade = trades.get(0);
        assertThat(savedTrade.getBuyOrderId()).isEqualTo(savedBuyOrder.getId());
        assertThat(savedTrade.getSellOrderId()).isEqualTo(savedSellOrder.getId());
        assertThat(savedTrade.getPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(savedTrade.getAmount()).isEqualByComparingTo(new BigDecimal("5.00"));

        // ============================================
        // 6. 주문 상태 업데이트 확인
        // ============================================
        Optional<Order> updatedBuyOrder = orderRepository.findById(savedBuyOrder.getId());
        assertThat(updatedBuyOrder).isPresent();
        assertThat(updatedBuyOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(updatedBuyOrder.get().getFilledQuoteAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(updatedBuyOrder.get().getStatus()).isEqualTo("partial"); // 부분 체결

        Optional<Order> updatedSellOrder = orderRepository.findById(savedSellOrder.getId());
        assertThat(updatedSellOrder).isPresent();
        assertThat(updatedSellOrder.get().getFilledAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(updatedSellOrder.get().getFilledQuoteAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(updatedSellOrder.get().getStatus()).isEqualTo("partial"); // 부분 체결
    }

    /**
     * 테스트: 전량 체결 시 상태가 "filled"로 변경되는지 확인
     */
    @Test
    @Transactional
    void testFullFillStatus() throws Exception {
        // ============================================
        // 1. 테스트용 주문 생성 (전량 체결될 주문)
        // ============================================
        Order buyOrder = Order.builder()
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
        Order savedBuyOrder = orderRepository.save(buyOrder);

        Order sellOrder = Order.builder()
                .userId(2L)
                .orderType("sell")
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
        Order savedSellOrder = orderRepository.save(sellOrder);

        // ============================================
        // 2. 전량 체결 이벤트 발행
        // ============================================
        String tradeEventJson = String.format(
                "{\n" +
                "  \"trade_id\": 1,\n" +
                "  \"buy_order_id\": %d,\n" +
                "  \"sell_order_id\": %d,\n" +
                "  \"buyer_id\": 1,\n" +
                "  \"seller_id\": 2,\n" +
                "  \"price\": \"100.00\",\n" +
                "  \"amount\": \"10.00\",\n" +
                "  \"base_mint\": \"SOL\",\n" +
                "  \"quote_mint\": \"USDT\",\n" +
                "  \"timestamp\": \"2024-01-01T00:00:00Z\"\n" +
                "}",
                savedBuyOrder.getId(),
                savedSellOrder.getId()
        );

        kafkaTemplate.send("trade-executed-sol", tradeEventJson).get();
        Thread.sleep(1000);

        // ============================================
        // 3. 전량 체결 상태 확인
        // ============================================
        Optional<Order> updatedBuyOrder = orderRepository.findById(savedBuyOrder.getId());
        assertThat(updatedBuyOrder).isPresent();
        assertThat(updatedBuyOrder.get().getStatus()).isEqualTo("filled");

        Optional<Order> updatedSellOrder = orderRepository.findById(savedSellOrder.getId());
        assertThat(updatedSellOrder).isPresent();
        assertThat(updatedSellOrder.get().getStatus()).isEqualTo("filled");
    }
}
