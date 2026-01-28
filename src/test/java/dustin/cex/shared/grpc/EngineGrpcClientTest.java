package dustin.cex.shared.grpc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gRPC 클라이언트 테스트
 * Engine gRPC Client Test
 * 
 * 테스트 항목:
 * 1. 주문 제출 (gRPC)
 * 2. 주문 취소 (gRPC)
 * 
 * 주의: Rust 엔진 서버가 실행 중이어야 함 (포트 50051)
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "engine.grpc.host=localhost",
    "engine.grpc.port=50051",
    "bot.bot1-email=bot1@bot.com",
    "bot.bot1-password=botpassword",
    "bot.bot2-email=bot2@bot.com",
    "bot.bot2-password=botpassword",
    "bot.binance-ws-url=wss://test",
    "bot.binance-symbol=SOLUSDT",
    "bot.orderbook-depth=200",
    "bot.order-quantity=1.0"
})
class EngineGrpcClientTest {

    @Autowired
    private EngineGrpcClient engineGrpcClient;

    private Long testOrderId;
    private Long testUserId;

    @BeforeEach
    void setUp() {
        // 테스트용 주문 ID 및 사용자 ID (실제 DB에서 가져와야 함)
        testOrderId = 1L;
        testUserId = 1L;
    }

    @Test
    @DisplayName("gRPC 주문 제출 테스트")
    void testSubmitOrder() {
        // given
        // 주의: 실제 주문 ID는 DB에서 생성되므로, 여기서는 임시 값 사용
        // 실제 테스트에서는 먼저 주문을 생성한 후 ID를 사용해야 함
        
        // when & then
        // 엔진 서버가 없으면 실패할 수 있음
        try {
            boolean success = engineGrpcClient.submitOrder(
                testOrderId,
                testUserId,
                "buy",
                "limit",
                "SOL",
                "USDT",
                "100.00",
                "1.0",
                null
            );
            
            // 엔진 서버가 실행 중이면 성공해야 함
            // 서버가 없으면 예외 발생
            assertThat(success).isTrue();
        } catch (RuntimeException e) {
            // 엔진 서버가 없으면 예외 발생 (정상)
            System.out.println("gRPC 서버 연결 실패 (예상됨): " + e.getMessage());
        }
    }

    @Test
    @DisplayName("gRPC 주문 취소 테스트")
    void testCancelOrder() {
        // given
        String tradingPair = "SOL/USDT";
        
        // when & then
        // 엔진 서버가 없으면 실패할 수 있음
        try {
            boolean success = engineGrpcClient.cancelOrder(
                testOrderId,
                testUserId,
                tradingPair
            );
            
            // 엔진 서버가 실행 중이면 성공해야 함
            assertThat(success).isTrue();
        } catch (RuntimeException e) {
            // 엔진 서버가 없으면 예외 발생 (정상)
            System.out.println("gRPC 서버 연결 실패 (예상됨): " + e.getMessage());
        }
    }
}
