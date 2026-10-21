package dustin.cex.shared.kafka;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final EngineEventInbox engineEventInbox;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final UserPositionRepository userPositionRepository;
    private final OrderSubmissionRepository submissions;
    private final TradeFeeRepository tradeFeeRepository;
    private final UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @KafkaListener(topics = "order-events", groupId = "cex-consumer-group", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumeOrderEvent(String message) {
        final JsonNode event;
        try { event = mapper.readTree(message); }
        catch (Exception invalid) { throw new IllegalArgumentException("금융 이벤트 파싱 실패", invalid); }
        String type = event.path("event_type").asText();
        if (!List.of("trade_executed", "order_cancelled").contains(type)) {
            throw new IllegalArgumentException("지원하지 않는 금융 이벤트입니다");
        }
        if (!engineEventInbox.claim(event.path("event_id").asText(), event.path("engine_id").asText(), message)) return;
        if (type.equals("trade_executed")) trade(event); else cancel(event);
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
        var saved = tradeRepository.save(Trade.builder().buyOrderId(buy.getId()).sellOrderId(sell.getId())
                .buyerId(buy.getUserId()).sellerId(sell.getUserId()).baseMint(base).quoteMint(quote)
                .price(price).amount(quantity).createdAt(time).build());
        fill(buy, quantity, value);
        fill(sell, quantity, value);
        // 지정가 매수는 해당 수량의 예약 대금 전체를 해제하고 실제 지출 차이만 반환한다.
        // 후속 주문 예약은 건드리지 않는다. 시장가 잔여 예산 종료는 다음 사이클에서 완성한다.
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
        BigDecimal filled = ExactAmount.requireNonNegative(order.getFilledAmount().add(quantity), "누적 체결량");
        // 시장가 매수의 수량 0·예산 기반 최종 상태는 별도 작업 항목이다. 잘못된 상태를 확정하지 않는다.
        if (filled.compareTo(order.getAmount()) > 0) throw new IllegalStateException("원본 주문 수량을 초과했습니다");
        order.setFilledAmount(filled);
        order.setFilledQuoteAmount(ExactAmount.requireNonNegative(order.getFilledQuoteAmount().add(value), "누적 체결 대금"));
        order.setStatus(filled.compareTo(order.getAmount()) == 0 ? "filled" : "partial");
        orderRepository.save(order);
    }

    private void cancel(JsonNode event) {
        Order order = order(event.path("order_id").asLong());
        verifyOrder(order, event.path("user_id").asLong(), order.getOrderType(), event.path("base_mint").asText(), event.path("quote_mint").asText());
        BigDecimal remaining = ExactAmount.requirePositive(order.getAmount().subtract(order.getFilledAmount()), "취소 잔량");
        boolean buy = order.getOrderType().equals("buy");
        BigDecimal refund = buy ? ExactAmount.requirePositive(remaining.multiply(order.getPrice()), "취소 예약금") : remaining;
        change(order.getUserId(), buy ? order.getQuoteMint() : order.getBaseMint(), refund, refund.negate());
        order.setStatus("cancelled");
        orderRepository.save(order);
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
