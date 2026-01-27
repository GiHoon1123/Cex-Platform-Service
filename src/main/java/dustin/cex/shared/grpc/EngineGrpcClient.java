package dustin.cex.shared.grpc;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dustin.cex.engine.grpc.CancelOrderRequest;
import dustin.cex.engine.grpc.CancelOrderResponse;
import dustin.cex.engine.grpc.EngineServiceGrpc;
import dustin.cex.engine.grpc.SubmitOrderRequest;
import dustin.cex.engine.grpc.SubmitOrderResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Rust 엔진 gRPC 클라이언트
 * Rust Engine gRPC Client
 * 
 * 역할:
 * - Rust 엔진과의 gRPC 통신
 * - 주문 제출, 취소 등 엔진 명령 전송
 * 
 * 처리 흐름:
 * 1. gRPC 채널 생성 및 연결
 * 2. 엔진에 주문 제출 (동기)
 * 3. 엔진 응답 대기 (< 5ms 목표)
 * 
 * 주의사항:
 * - 엔진 장애 시 주문 생성 실패 처리
 * - 타임아웃 설정 (5초)
 * - 연결 풀링 (채널 재사용)
 */
@Slf4j
@Component
public class EngineGrpcClient {
    
    @Value("${engine.grpc.host:localhost}")
    private String grpcHost;
    
    @Value("${engine.grpc.port:50051}")
    private int grpcPort;
    
    private ManagedChannel channel;
    private EngineServiceGrpc.EngineServiceBlockingStub blockingStub;
    
    /**
     * 서버 시작 시 gRPC 채널 초기화
     * Initialize gRPC channel on server startup
     */
    @PostConstruct
    public void init() {
        try {
            String target = grpcHost + ":" + grpcPort;
            // log.info("[EngineGrpcClient] gRPC 채널 초기화: {}", target);
            
            channel = ManagedChannelBuilder.forTarget(target)
                    .usePlaintext() // 개발 환경용 (프로덕션에서는 TLS 사용)
                    .build();
            
            blockingStub = EngineServiceGrpc.newBlockingStub(channel);
            
            // log.info("[EngineGrpcClient] gRPC 채널 초기화 완료");
            
        } catch (Exception e) {
            log.error("[EngineGrpcClient] gRPC 채널 초기화 실패", e);
            throw new RuntimeException("gRPC 채널 초기화 실패", e);
        }
    }
    
    /**
     * 서버 종료 시 gRPC 채널 종료
     * Shutdown gRPC channel on server shutdown
     */
    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
                // log.info("[EngineGrpcClient] gRPC 채널 종료 완료");
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
                log.error("[EngineGrpcClient] gRPC 채널 종료 중 인터럽트", e);
            }
        }
    }
    
    /**
     * 주문 제출
     * Submit Order
     * 
     * Rust 엔진에 주문을 제출합니다.
     * 
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param orderType 주문 타입 ("buy" 또는 "sell")
     * @param orderSide 주문 방식 ("limit" 또는 "market")
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param price 지정가 가격 (지정가 주문만)
     * @param amount 주문 수량
     * @param quoteAmount 시장가 매수 금액 (시장가 매수만)
     * @return 성공 여부
     * @throws RuntimeException 엔진 통신 실패 시
     */
    public boolean submitOrder(
            Long orderId,
            Long userId,
            String orderType,
            String orderSide,
            String baseMint,
            String quoteMint,
            String price,
            String amount,
            String quoteAmount
    ) {
        try {
            // 채널이 shutdown되었는지 확인하고 재연결
            if (channel.isShutdown() || channel.isTerminated()) {
                log.warn("[EngineGrpcClient] gRPC 채널이 종료됨, 재연결 시도...");
                reconnect();
            }
            
            SubmitOrderRequest request = SubmitOrderRequest.newBuilder()
                    .setOrderId(orderId)
                    .setUserId(userId)
                    .setOrderType(orderType)
                    .setOrderSide(orderSide)
                    .setBaseMint(baseMint)
                    .setQuoteMint(quoteMint)
                    .setPrice(price != null ? price : "")
                    .setAmount(amount)
                    .setQuoteAmount(quoteAmount != null ? quoteAmount : "")
                    .build();
            
            SubmitOrderResponse response = blockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .submitOrder(request);
            
            if (!response.getSuccess()) {
                throw new RuntimeException("엔진 주문 제출 실패: " + response.getErrorMessage());
            }
            
            // log.debug("[EngineGrpcClient] 주문 제출 성공: orderId={}", orderId);
            return true;
        } catch (Exception e) {
            log.error("[EngineGrpcClient] 주문 제출 실패: orderId={}, error={}", orderId, e.getMessage());
            
            // 채널 종료 에러인 경우 재연결 시도
            if (e.getMessage() != null && e.getMessage().contains("Channel shutdown")) {
                log.warn("[EngineGrpcClient] 채널 종료 감지, 재연결 시도...");
                try {
                    reconnect();
                    // 재연결 후 재시도
                    SubmitOrderRequest retryRequest = SubmitOrderRequest.newBuilder()
                            .setOrderId(orderId)
                            .setUserId(userId)
                            .setOrderType(orderType)
                            .setOrderSide(orderSide)
                            .setBaseMint(baseMint)
                            .setQuoteMint(quoteMint)
                            .setPrice(price != null ? price : "")
                            .setAmount(amount)
                            .setQuoteAmount(quoteAmount != null ? quoteAmount : "")
                            .build();
                    
                    SubmitOrderResponse retryResponse = blockingStub
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .submitOrder(retryRequest);
                    
                    if (retryResponse.getSuccess()) {
                        // log.info("[EngineGrpcClient] 재연결 후 주문 제출 성공: orderId={}", orderId);
                        return true;
                    }
                } catch (Exception retryException) {
                    log.error("[EngineGrpcClient] 재연결 후 주문 제출 실패: orderId={}, error={}", orderId, retryException.getMessage());
                }
            }
            
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * gRPC 채널 재연결
     * Reconnect gRPC channel
     */
    private synchronized void reconnect() {
        try {
            // 기존 채널 종료
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
            
            // 새 채널 생성
            String target = grpcHost + ":" + grpcPort;
            // log.info("[EngineGrpcClient] gRPC 채널 재연결: {}", target);
            
            channel = ManagedChannelBuilder.forTarget(target)
                    .usePlaintext()
                    .build();
            
            blockingStub = EngineServiceGrpc.newBlockingStub(channel);
            
            // log.info("[EngineGrpcClient] gRPC 채널 재연결 완료");
        } catch (Exception e) {
            log.error("[EngineGrpcClient] gRPC 채널 재연결 실패", e);
            throw new RuntimeException("gRPC 채널 재연결 실패", e);
        }
    }
    
    /**
     * 주문 취소
     * Cancel Order
     * 
     * Rust 엔진에 주문 취소를 요청합니다.
     * 
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param tradingPair 거래쌍 (예: "SOL/USDT")
     * @return 성공 여부
     * @throws RuntimeException 엔진 통신 실패 시
     */
    public boolean cancelOrder(Long orderId, Long userId, String tradingPair) {
        try {
            CancelOrderRequest request = CancelOrderRequest.newBuilder()
                    .setOrderId(orderId)
                    .setUserId(userId)
                    .setTradingPair(tradingPair)
                    .build();
            
            CancelOrderResponse response = blockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .cancelOrder(request);
            
            if (!response.getSuccess()) {
                throw new RuntimeException("엔진 주문 취소 실패: " + response.getErrorMessage());
            }
            
            // log.debug("[EngineGrpcClient] 주문 취소 성공: orderId={}", orderId);
            return true;
        } catch (Exception e) {
            log.error("[EngineGrpcClient] 주문 취소 실패: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
    }
}
