package dustin.cex.shared.kafka;

import java.math.BigDecimal;
import static dustin.cex.domains.ledger.LedgerService.p;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.order.repository.OrderSubmissionRepository;
import dustin.cex.domains.position.model.entity.UserPosition;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.settlement.trade.model.entity.TradeFee;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import dustin.cex.shared.model.ExactAmount;
import lombok.RequiredArgsConstructor;

/**
 * 한 엔진의 영속 결과를 한 파티션·한 소비 순서로 반영한다.
 * 수신 키·주문·잔고·수수료를 같은 디비 트랜잭션에 기록하고 실패는 전체 롤백한다.
 * 절대 잔고 스냅샷으로 덮어쓰지 않아, 이미 예약한 후속 주문의 자산을 보존한다.
 */
@Component
@RequiredArgsConstructor
public class KafkaOrderEventConsumer {
    private final dustin.cex.domains.ledger.LedgerService ledger;
    private final EngineEventInbox engineEventInbox;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final UserPositionRepository userPositionRepository;
    private final OrderSubmissionRepository submissions;
    private final TradeFeeRepository tradeFeeRepository;
    private final UserRepository userRepository;
    private final dustin.cex.domains.order.repository.OrderDispatchRepository dispatches;
    private final dustin.cex.domains.bitcoin.BitcoinCreditedEvents deposits;
    private final dustin.cex.domains.bitcoin.BitcoinWithdrawalEvents withdrawals;
    private final ObjectMapper mapper = new ObjectMapper();

    @KafkaListener(topics = "order-events", groupId = "cex-consumer-group", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumeOrderEvent(String message) {
        final JsonNode event;
        try { event = mapper.readTree(message); }
        catch (Exception invalid) { throw new IllegalArgumentException("금융 이벤트 파싱 실패", invalid); }
        String type = event.path("event_type").asText();
        if (!List.of("trade_executed", "order_cancelled", "market_order_completed", "order_rejected", "deposit_credited", "withdrawal_reserved", "withdrawal_rejected").contains(type)) {
            throw new IllegalArgumentException("지원하지 않는 금융 이벤트입니다");
        }
        if (!engineEventInbox.claim(event.path("event_id").asText(), event.path("engine_id").asText(), message)) return;
        switch (type) {
            case "deposit_credited" -> deposits.apply(message);
            case "withdrawal_reserved", "withdrawal_rejected" -> withdrawals.apply(message);
            case "trade_executed" -> trade(event);
            case "market_order_completed" -> completeMarketOrder(event);
            case "order_cancelled" -> cancel(event);
            case "order_rejected" -> reject(event);
            default -> throw new IllegalArgumentException("지원하지 않는 금융 이벤트입니다");
        }
    }

    private void trade(JsonNode event) {
        Order buy = order(event.path("buy_order_id").asLong());
        Order sell = order(event.path("sell_order_id").asLong());
        String base = event.path("base_mint").asText(), quote = event.path("quote_mint").asText();
        if (buy.getId().equals(sell.getId()) || buy.getUserId().equals(sell.getUserId())) {
            throw new IllegalArgumentException("동일 주문 또는 자전거래 결과입니다");
        }
        verifyOrder(buy, event.path("buyer_id").asLong(), "buy", base, quote);
        verifyOrder(sell, event.path("seller_id").asLong(), "sell", base, quote);
        BigDecimal price = positive(event, "price"), quantity = positive(event, "amount");
        BigDecimal value = ExactAmount.requirePositive(price.multiply(quantity), "체결 대금");
        if ((buy.getPrice() != null && price.compareTo(buy.getPrice()) > 0)
                || (sell.getPrice() != null && price.compareTo(sell.getPrice()) < 0)) {
            throw new IllegalArgumentException("원본 지정가 범위를 벗어난 체결입니다");
        }
        BigDecimal buyerRate = number(event, "buyer_fee_rate"), sellerRate = number(event, "seller_fee_rate");
        BigDecimal buyerFee = verifiedFee(buy, quantity, buyerRate, number(event, "buyer_fee"));
        BigDecimal sellerFee = verifiedFee(sell, value, sellerRate, number(event, "seller_fee"));
        // 시각 누락·손상을 현재 시각으로 숨기면 재발행 결과가 달라진다.
        LocalDateTime time = LocalDateTime.ofInstant(Instant.parse(event.path("timestamp").asText()), ZoneId.of("Asia/Seoul"));
        var saved = tradeRepository.save(Trade.builder().engineEventId(event.path("event_id").asText()).buyOrderId(buy.getId()).sellOrderId(sell.getId())
                .buyerId(buy.getUserId()).sellerId(sell.getUserId()).baseMint(base).quoteMint(quote)
                .price(price).amount(quantity).createdAt(time).build());
        fill(buy, quantity, value);
        fill(sell, quantity, value);
        // 지정가 매수는 해당 수량의 예약 대금 전체를 해제하고 실제 지출 차이만 반환한다.
        // 후속 주문 예약은 건드리지 않는다. 시장가의 미사용 예산은 종료 이벤트에서만 반환한다.
        BigDecimal reserved = buy.getPrice() == null ? value : ExactAmount.requirePositive(buy.getPrice().multiply(quantity), "사용 예약금");
        change(buy.getUserId(), quote, reserved.subtract(value), reserved.negate());
        change(buy.getUserId(), base, quantity.subtract(buyerFee), BigDecimal.ZERO);
        change(sell.getUserId(), base, BigDecimal.ZERO, quantity.negate());
        change(sell.getUserId(), quote, value.subtract(sellerFee), BigDecimal.ZERO);
        var buyer = userRepository.findById(buy.getUserId()).orElseThrow();
        var seller = userRepository.findById(sell.getUserId()).orElseThrow();
        // 서로 다른 자산의 수수료를 더해 단일 수익액으로 표시하지 않는다.
        tradeFeeRepository.saveAll(List.of(
                TradeFee.builder().trade(saved).user(buyer).feeType("buyer").feeRate(buyerRate)
                        .feeAmount(buyerFee).feeMint(base).tradeValue(value).createdAt(time).build(),
                TradeFee.builder().trade(saved).user(seller).feeType("seller").feeRate(sellerRate)
                        .feeAmount(sellerFee).feeMint(quote).tradeValue(value).createdAt(time).build()));
        // 고객 간 이동과 수수료 귀속을 자산별로 균형 있게 기록한다. 원장은 잔고 반영과 같은 트랜잭션이다.
        ledger.record("engine:"+event.path("event_id").asText(),"TRADE",List.of(
            p(buy.getUserId(),"AVAILABLE",quote,reserved.subtract(value)),p(buy.getUserId(),"LOCKED",quote,reserved.negate()),
            p(buy.getUserId(),"AVAILABLE",base,quantity.subtract(buyerFee)),p(sell.getUserId(),"LOCKED",base,quantity.negate()),
            p(sell.getUserId(),"AVAILABLE",quote,value.subtract(sellerFee)),p(0,"FEE",base,buyerFee),p(0,"FEE",quote,sellerFee)));
        updatePositionForTrade(buy.getUserId(), base, quote, quantity.subtract(buyerFee), price, value);
        updatePositionForTrade(sell.getUserId(), base, quote, quantity.negate(), price, BigDecimal.ZERO);
    }

    /** 계산 결과를 새로 정하는 함수가 아니다. 원본 적용률과 계약된 절삭을 검증해 잘못된 이벤트를 차단한다. */
    private BigDecimal verifiedFee(Order order, BigDecimal received, BigDecimal rate, BigDecimal fee) {
        var original = submissions.findByOrderId(order.getId()).orElseThrow();
        dustin.cex.domains.fee.service.FeeConfigService.requireRate(rate);
        BigDecimal expected = received.multiply(rate).setScale(9, RoundingMode.DOWN);
        if (rate.compareTo(original.getFeeRate()) != 0 || fee.compareTo(expected) != 0) {
            throw new IllegalArgumentException("엔진 수수료가 원본 적용률 또는 차감 계약과 다릅니다");
        }
        return fee;
    }

    private Order order(long id) {
        if (id <= 0) throw new IllegalArgumentException("잘못된 주문 식별자입니다");
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> new IllegalStateException("금융 결과의 원본 주문이 없습니다"));
        if (!List.of("pending", "partial").contains(order.getStatus())) throw new IllegalStateException("종료된 주문의 신규 결과입니다");
        return order;
    }

    private void verifyOrder(Order order, long user, String side, String base, String quote) {
        if (!order.getUserId().equals(user) || !order.getOrderType().equals(side)
                || !order.getBaseMint().equals(base) || !order.getQuoteMint().equals(quote)) {
            throw new IllegalStateException("체결 결과가 원본 주문과 다릅니다");
        }
    }

    private void fill(Order order, BigDecimal quantity, BigDecimal value) {
        var original = submissions.findByOrderId(order.getId()).orElseThrow().command();
        BigDecimal filled = ExactAmount.requireNonNegative(order.getFilledAmount().add(quantity), "누적 체결량");
        BigDecimal spent = ExactAmount.requireNonNegative(order.getFilledQuoteAmount().add(value), "누적 체결 대금");
        boolean market = original.orderSide().equals("market");
        boolean budgetBuy = market && original.orderType().equals("buy");
        // 시장가 매수의 원본 수량은 미정이다. 상태 테이블의 0을 수량 상한으로 해석하지 않는다.
        // 그 대신 원본 결제 예산을 검사하며, 수량 주문은 원본 수량을 검사한다.
        if (budgetBuy ? spent.compareTo(original.quoteAmount()) > 0 : filled.compareTo(original.amount()) > 0) {
            throw new IllegalStateException("원본 주문의 예산 또는 수량을 초과했습니다");
        }
        order.setFilledAmount(filled);
        order.setFilledQuoteAmount(spent);
        // 시장가는 마지막 체결 뒤에도 종료·미사용 예약 반환이 남는다. 수량을 모두
        // 채웠더라도 종료 이벤트 이전에는 완료로 표시하지 않아 상태·예약의 경계를 맞춘다.
        order.setStatus(market ? "partial" : (filled.compareTo(original.amount()) == 0 ? "filled" : "partial"));
        orderRepository.save(order);
    }

    /** 체결 0건도 종료 결과가 필요하다. 합계·건수·원본 예약을 확인한 뒤 상태와 반환을 함께 확정한다. */
    private void completeMarketOrder(JsonNode event) {
        Order order = order(event.path("order_id").asLong());
        verifyOrder(order, event.path("user_id").asLong(), event.path("order_type").asText(),
                event.path("base_mint").asText(), event.path("quote_mint").asText());
        var original = submissions.findByOrderId(order.getId()).orElseThrow().command();
        if (!"market".equals(original.orderSide()) || !"market".equals(order.getOrderSide())) {
            throw new IllegalArgumentException("시장가 이외 주문의 종료 결과입니다");
        }
        BigDecimal quantity = number(event, "filled_amount"), spent = number(event, "filled_quote_amount");
        BigDecimal reserved = number(event, "reserved_amount"), refund = number(event, "refund_amount");
        boolean buy = original.orderType().equals("buy");
        BigDecimal used = buy ? spent : quantity;
        BigDecimal expectedRefund = ExactAmount.requireNonNegative(original.reservationAmount().subtract(used), "시장가 미사용 예약");
        long actualTrades = buy ? tradeRepository.countByBuyOrderId(order.getId()) : tradeRepository.countBySellOrderId(order.getId());
        JsonNode count = event.path("trade_count");
        String status = refund.signum() == 0 ? "filled" : "expired";
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() != actualTrades
                || quantity.compareTo(order.getFilledAmount()) != 0 || spent.compareTo(order.getFilledQuoteAmount()) != 0
                || reserved.compareTo(original.reservationAmount()) != 0 || refund.compareTo(expectedRefund) != 0
                || !status.equals(event.path("status").asText())
                || (quantity.signum() == 0) != (spent.signum() == 0)) {
            throw new IllegalArgumentException("시장가 종료가 원본 예약 또는 반영한 체결과 다릅니다");
        }
        // 잔여 예약만 반환한다. 동일 종료의 재전달은 수신 키가 앞에서 막고,
        // 다른 식별자로 종료를 반복하면 활성 상태 검사가 거부한다.
        if (refund.signum() > 0) change(order.getUserId(), original.reservationMint(), refund, refund.negate());
        refundLedger(event,order,original.reservationMint(),refund,"MARKET_COMPLETED");
        order.setStatus(status);
        orderRepository.save(order);
    }

    /** 전체 명령 거절만 예약을 전액 반환한다. HTTP 오류나 무응답은 이 경로로 들어오지 않는다. */
    private void reject(JsonNode event) {
        Order order=order(event.path("order_id").asLong());
        verifyOrder(order,event.path("user_id").asLong(),event.path("order_type").asText(),
                event.path("base_mint").asText(),event.path("quote_mint").asText());
        var submission=submissions.findByOrderId(order.getId()).orElseThrow();
        var original=submission.command();
        if(!"pending".equals(order.getStatus()) || order.getFilledAmount().signum()!=0 || order.getFilledQuoteAmount().signum()!=0
                || !original.orderType().equals(event.path("order_type").asText())
                || !original.baseMint().equals(event.path("base_mint").asText())
                || !original.quoteMint().equals(event.path("quote_mint").asText())
                || !submission.getUserId().equals(order.getUserId())
                || !original.orderSide().equals(event.path("order_side").asText())
                || !sameOptional(event,"price",original.price()) || !sameOptional(event,"amount",original.amount())
                || !sameOptional(event,"quote_amount",original.quoteAmount())
                || number(event,"fee_rate").compareTo(submission.getFeeRate())!=0
                || !"ENGINE_EXECUTION_REJECTED".equals(event.path("reason_code").asText())) {
            throw new IllegalArgumentException("거절 결과가 미체결 원본과 다릅니다");
        }
        boolean buy=original.orderType().equals("buy");
        long trades=buy ? tradeRepository.countByBuyOrderId(order.getId()) : tradeRepository.countBySellOrderId(order.getId());
        if(trades!=0) throw new IllegalStateException("이미 체결된 주문의 전체 거절입니다");
        BigDecimal refund=original.reservationAmount();
        change(order.getUserId(),original.reservationMint(),refund,refund.negate());
        refundLedger(event,order,original.reservationMint(),refund,"REJECTED");
        order.setStatus("rejected"); orderRepository.save(order);
        // 전송 확인과 거절 결과의 경쟁에서도 이미 확인된 기록을 다시 대기로 돌리지 않는다.
        dispatches.findForUpdate(order.getId()).orElseThrow().confirm(Instant.now());
    }
    private boolean sameOptional(JsonNode event,String field,BigDecimal expected) {
        // 누락을 null로 추측하지 않는다. 시장가 원본의 명시적 null과 실제 수량을 구분한다.
        if(!event.has(field)) return false;
        return expected==null ? event.get(field).isNull() : number(event,field).compareTo(expected)==0;
    }

    private void cancel(JsonNode event) {
        Order order = order(event.path("order_id").asLong());
        verifyOrder(order, event.path("user_id").asLong(), order.getOrderType(), event.path("base_mint").asText(), event.path("quote_mint").asText());
        BigDecimal remaining = ExactAmount.requirePositive(order.getAmount().subtract(order.getFilledAmount()), "취소 잔량");
        boolean buy = order.getOrderType().equals("buy");
        BigDecimal refund = buy ? ExactAmount.requirePositive(remaining.multiply(order.getPrice()), "취소 예약금") : remaining;
        change(order.getUserId(), buy ? order.getQuoteMint() : order.getBaseMint(), refund, refund.negate());
        refundLedger(event,order,buy ? order.getQuoteMint() : order.getBaseMint(),refund,"CANCELLED");
        order.setStatus("cancelled");
        orderRepository.save(order);
    }

    private void refundLedger(JsonNode event,Order order,String asset,BigDecimal refund,String kind) {
        // 반환 0도 결과를 확인한 업무 기록은 남긴다. 불필요한 금액 0 항목은 저장하지 않는다.
        ledger.record("engine:"+event.path("event_id").asText(),kind,List.of(
            p(order.getUserId(),"AVAILABLE",asset,refund),p(order.getUserId(),"LOCKED",asset,refund.negate())));
    }

    /** 기존 행은 비관적 락으로 갱신하고, 수취 계정만 없을 때 생성한다. 음수·NULL을 복구값으로 숨기지 않는다. */
    private void change(long user, String asset, BigDecimal availableDelta, BigDecimal lockedDelta) {
        UserBalance balance = userBalanceRepository.findByUserIdAndMintAddressForUpdate(user, asset).orElseGet(() ->
                UserBalance.builder().userId(user).mintAddress(asset).available(BigDecimal.ZERO).locked(BigDecimal.ZERO).build());
        BigDecimal available = ExactAmount.requireNonNegative(balance.getAvailable(), "기존 사용 가능 잔액");
        BigDecimal locked = ExactAmount.requireNonNegative(balance.getLocked(), "기존 예약 잔액");
        BigDecimal nextAvailable = ExactAmount.requireNonNegative(available.add(availableDelta), "반영 후 사용 가능 잔액");
        BigDecimal nextLocked = ExactAmount.requireNonNegative(locked.add(lockedDelta), "반영 후 예약 잔액");
        balance.setAvailable(nextAvailable);
        balance.setLocked(nextLocked);
        userBalanceRepository.save(balance);
    }

    private BigDecimal number(JsonNode event, String key) {
        if (!event.path(key).isTextual()) throw new IllegalArgumentException("금융 값은 문자열이어야 합니다: " + key);
        return ExactAmount.requireNonNegative(new BigDecimal(event.path(key).textValue()), key);
    }
    private BigDecimal positive(JsonNode event, String key) { return ExactAmount.requirePositive(number(event, key), key); }

    private void updatePositionForTrade(Long userId, String baseMint, String quoteMint, BigDecimal amount, BigDecimal price, BigDecimal purchaseCost) {
        var positionOpt = userPositionRepository.findByUserIdAndBaseMintAndQuoteMintForUpdate(userId, baseMint, quoteMint);
        
        UserPosition position;
        if (positionOpt.isPresent()) {
            position = positionOpt.get();
            
            // 기존 포지션이 있는 경우: 가중 평균으로 평균 진입 가격 계산
            BigDecimal currentPosition = position.getPositionAmount();
            BigDecimal newPosition = currentPosition.add(amount);
            
            if (newPosition.compareTo(BigDecimal.ZERO) == 0) {
                // 포지션이 0이 되면 평균 진입 가격 초기화
                position.setAvgEntryPrice(price);
            } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
                // 매수: 가중 평균 계산
                // 수수료 차감 후 순수취 수량에 실제 지출 대금을 나눈다. 체결가를 그대로 쓰면
                // 기준 자산으로 낸 수수료의 취득 원가가 사라지므로 대금 원본을 더한다.
                BigDecimal totalCost = currentPosition.multiply(position.getAvgEntryPrice())
                        .add(purchaseCost);
                position.setAvgEntryPrice(totalCost.divide(newPosition, 9, java.math.RoundingMode.HALF_UP));
            }
            // 매도는 평균 진입 가격 변경 없음 (기존 가격 유지)
            
            position.setPositionAmount(newPosition);
        } else {
            // 포지션이 없으면 생성 (매수만 가능)
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                // 매도인데 포지션이 없으면 스킵 (숏 포지션 미지원)
                return;
            }
            
            position = UserPosition.builder()
                    .userId(userId)
                    .baseMint(baseMint)
                    .quoteMint(quoteMint)
                    .positionAmount(amount)
                    .avgEntryPrice(purchaseCost.divide(amount, 9, RoundingMode.HALF_UP))
                    .currentPrice(price) // 초기에는 체결가를 현재가로 설정
                    .unrealizedPnl(BigDecimal.ZERO) // 초기에는 손익 없음
                    .build();
        }
        
        // 현재가 업데이트 (체결가로)
        position.setCurrentPrice(price);
        
        // 미실현 손익 계산: (현재가 - 평균 진입가) * 포지션 수량
        BigDecimal pnl = position.getCurrentPrice()
                .subtract(position.getAvgEntryPrice())
                .multiply(position.getPositionAmount());
        position.setUnrealizedPnl(pnl);
        
        userPositionRepository.save(position);
    }
    

}
