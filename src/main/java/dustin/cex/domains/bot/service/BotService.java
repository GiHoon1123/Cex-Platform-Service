package dustin.cex.domains.bot.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 봇 서비스
 * Bot Service
 * 
 * 역할:
 * - 봇 계정을 통한 주문 생성
 * - 봇 주문 관리 (생성, 취소)
 * - 주문 API를 사용하여 거래 처리
 * 
 * 처리 흐름:
 * 1. 봇 계정 정보 확인
 * 2. 주문 생성 요청 생성
 * 3. OrderService를 통해 주문 생성
 * 4. 주문 결과 반환
 * 
 * 봇 계정:
 * - bot1: 매수 전용 봇 (bot1@bot.com)
 * - bot2: 매도 전용 봇 (bot2@bot.com)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotService {
    
    private final OrderService orderService;
    private final dustin.cex.domains.order.repository.OrderRepository orders;
    
    /**
     * 봇 주문 생성
     * Create Bot Order
     * 
     * 봇 계정을 통해 주문을 생성합니다.
     * 
     * @param botUserId 봇 사용자 ID (bot1 또는 bot2)
     * @param orderType 주문 유형 ("buy" 또는 "sell")
     * @param orderSide 주문 방식 ("limit" 또는 "market")
     * @param baseMint 기준 자산 (예: "SOL")
     * @param quoteMint 기준 통화 (예: "USDT", 기본값: "USDT")
     * @param price 지정가 가격 (지정가 주문만 필수)
     * @param amount 주문 수량 (시장가 매수 제외 필수)
     * @param quoteAmount 금액 기반 주문 (시장가 매수만 필수)
     * @return 생성된 주문 정보
     */
    public OrderResponse createBotOrder(
            Long botUserId,
            String orderType,
            String orderSide,
            String baseMint,
            String quoteMint,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal quoteAmount
    ) {
        // log.debug("[BotService] 봇 주문 생성 시작: botUserId={}, orderType={}, orderSide={}, baseMint={}",
        //         botUserId, orderType, orderSide, baseMint);
        
        // 자동 주문의 자산·건별 수량·예산을 고정된 시연 범위로 제한한다.
        // 고객 주문 검증과 예약은 아래 일반 주문 서비스에서 다시 수행한다.
        if (!"BTC".equals(baseMint) || !"USDT".equals(quoteMint)
                || (amount != null && (amount.signum() <= 0 || amount.compareTo(new BigDecimal("0.01")) > 0))
                || (quoteAmount != null && (quoteAmount.signum() <= 0 || quoteAmount.compareTo(new BigDecimal("100")) > 0))
                || (price != null && amount != null && price.multiply(amount).compareTo(new BigDecimal("1000")) > 0)) {
            throw new IllegalArgumentException("봇 주문 한도를 벗어났습니다");
        }
        var active=java.util.List.of("pending","partial");
        if (orders.countByUserIdAndStatusIn(botUserId,active)>=20) {
            var oldest=orders.findFirstByUserIdAndStatusInOrderByCreatedAtAsc(botUserId,active).orElseThrow();
            orderService.cancelOrder(botUserId,oldest.getId());
            // 취소 결과의 비동기 정산을 기다리는 동안 새 주문을 추가하지 않는다.
            throw new IllegalStateException("가장 오래된 봇 주문의 취소 반영을 기다립니다");
        }
        // 주문 생성 요청 생성
        CreateOrderRequest request = new CreateOrderRequest();
        request.setOrderType(orderType);
        request.setOrderSide(orderSide);
        request.setBaseMint(baseMint);
        request.setQuoteMint(quoteMint != null ? quoteMint : "USDT");
        request.setPrice(price);
        request.setAmount(amount);
        request.setQuoteAmount(quoteAmount);
        
        // 새 봇 주문 의도마다 키를 생성한다. 실패한 의도를 재시도하는 경로는 추가하지 않는다.
        // 외부 봇 HTTP API는 제공하지 않는다. 같은 의도의 불확실한 결과를 새 키로 재시도하지 않는다.
        OrderResponse response = orderService.createOrder(botUserId, java.util.UUID.randomUUID(), request).response();
        
        // log.debug("[BotService] 봇 주문 생성 완료: orderId={}, botUserId={}", 
        //          response.getOrder().getId(), botUserId);
        
        return response;
    }
    
    /**
     * 봇 지정가 매수 주문 생성
     * Create Bot Limit Buy Order
     * 
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param price 지정가 가격
     * @param amount 주문 수량
     * @return 생성된 주문 정보
     */
    public OrderResponse createLimitBuyOrder(Long botUserId, String baseMint, BigDecimal price, BigDecimal amount) {
        return createBotOrder(botUserId, "buy", "limit", baseMint, "USDT", price, amount, null);
    }
    
    /**
     * 봇 지정가 매도 주문 생성
     * Create Bot Limit Sell Order
     * 
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param price 지정가 가격
     * @param amount 주문 수량
     * @return 생성된 주문 정보
     */
    public OrderResponse createLimitSellOrder(Long botUserId, String baseMint, BigDecimal price, BigDecimal amount) {
        return createBotOrder(botUserId, "sell", "limit", baseMint, "USDT", price, amount, null);
    }
    
    /**
     * 봇 시장가 매수 주문 생성
     * Create Bot Market Buy Order
     * 
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param quoteAmount 금액 기반 주문 (USDT 기준)
     * @return 생성된 주문 정보
     */
    public OrderResponse createMarketBuyOrder(Long botUserId, String baseMint, BigDecimal quoteAmount) {
        return createBotOrder(botUserId, "buy", "market", baseMint, "USDT", null, null, quoteAmount);
    }
    
    /**
     * 봇 시장가 매도 주문 생성
     * Create Bot Market Sell Order
     * 
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param amount 주문 수량
     * @return 생성된 주문 정보
     */
    public OrderResponse createMarketSellOrder(Long botUserId, String baseMint, BigDecimal amount) {
        return createBotOrder(botUserId, "sell", "market", baseMint, "USDT", null, amount, null);
    }
    
    /**
     * 봇 주문 취소
     * Cancel Bot Order
     * 
     * 봇 계정의 주문을 취소합니다.
     * 
     * @param botUserId 봇 사용자 ID
     * @param orderId 취소할 주문 ID
     * @return 취소된 주문 정보
     */
    public OrderResponse cancelBotOrder(Long botUserId, Long orderId) {
        // log.debug("[BotService] 봇 주문 취소 시작: botUserId={}, orderId={}", botUserId, orderId);
        
        // OrderService를 통해 주문 취소
        OrderResponse response = orderService.cancelOrder(botUserId, orderId);
        
        // log.debug("[BotService] 봇 주문 취소 완료: orderId={}, botUserId={}", orderId, botUserId);
        
        return response;
    }
}
