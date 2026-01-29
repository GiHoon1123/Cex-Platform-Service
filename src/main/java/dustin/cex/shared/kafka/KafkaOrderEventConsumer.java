package dustin.cex.shared.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.fee.model.entity.FeeConfig;
import dustin.cex.domains.fee.service.FeeConfigService;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.position.model.entity.UserPosition;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.settlement.model.entity.TradeFee;
import dustin.cex.domains.settlement.repository.TradeFeeRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka 주문 이벤트 통합 Consumer
 * Kafka Order Event Unified Consumer
 * 
 * 역할:
 * - Rust 엔진에서 발행한 주문 관련 이벤트를 통합 수신
 * - 단일 토픽으로 통합하여 순서 보장
 * - 파티션 키(user_id)로 같은 유저의 이벤트는 같은 파티션으로 보장
 * 
 * 처리 흐름:
 * 1. Kafka에서 'order-events' 토픽 메시지 수신 (단일 토픽, 파티션 12개)
 * 2. event_type에 따라 분기 처리:
 *    - "trade_executed": 체결 이벤트 처리 (buyer_id 기준 파티션)
 *    - "order_cancelled": 취소 이벤트 처리 (user_id 기준 파티션)
 * 
 * 순서 보장:
 * - 단일 토픽 사용: order-events (파티션 12개)
 * - 파티션 키: user_id (같은 유저의 이벤트는 같은 파티션)
 * - 같은 파티션 내에서 순서 보장됨
 * - 가상 스레드: 파티션 하나당 하나의 가상 스레드가 담당
 * - 정산용: trade_executed, order_cancelled만 처리
 * 
 * 트랜잭션 처리:
 * - 각 이벤트 타입별로 트랜잭션 처리
 * - 실패 시 롤백 (Kafka Consumer가 재시도)
 * 
 * 동시성 제어:
 * - 비관적 락 (PESSIMISTIC_WRITE) 사용
 * - 주문 ID 순서로 락 획득하여 데드락 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOrderEventConsumer {
    
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final UserPositionRepository userPositionRepository;
    private final FeeConfigService feeConfigService;
    private final TradeFeeRepository tradeFeeRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @PostConstruct
    public void init() {
        // Consumer 초기화 완료
    }
    
    /**
     * 주문 이벤트 통합 수신 및 처리
     * Consume unified order events
     * 
     * 단일 토픽 구독:
     * - order-events: 모든 자산의 주문 이벤트 (파티션 12개)
     * - 파티션 키: user_id (같은 유저의 이벤트는 같은 파티션)
     * - 정산용: trade_executed, order_cancelled만 처리
     * 
     * 처리 과정:
     * 1. JSON 파싱 및 event_type 확인
     * 2. event_type에 따라 분기 처리:
     *    - "trade_executed": 체결 이벤트 처리 (buyer_id 기준 파티션)
     *    - "order_cancelled": 취소 이벤트 처리 (user_id 기준 파티션)
     * 
     * 트랜잭션:
     * - 각 이벤트 타입별로 트랜잭션 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     * 
     * @param message Kafka 메시지 (JSON 문자열)
     */
    @KafkaListener(topics = "order-events", groupId = "cex-consumer-group", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumeOrderEvent(String message) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            
            // event_type 확인
            String eventType = jsonNode.get("event_type").asText();
            
            switch (eventType) {
                case "trade_executed":
                    log.info("[Kafka] 체결 이벤트 수신: {}", message);
                    handleTradeExecuted(jsonNode);
                    log.info("[Kafka] 체결 이벤트 처리 완료");
                    break;
                case "order_cancelled":
                    log.debug("[Kafka] 취소 이벤트 수신: {}", message);
                    handleOrderCancelled(jsonNode);
                    log.debug("[Kafka] 취소 이벤트 처리 완료");
                    break;
                default:
                    // 알 수 없는 이벤트 타입은 무시
                    break;
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 파싱 실패", e);
        } catch (Exception e) {
            throw e; // 트랜잭션 롤백을 위해 예외 재발생
        }
    }
    
    /**
     * 체결 이벤트 처리
     * Handle trade executed event
     * 
     * 처리 과정:
     * 1. Trade 저장
     * 2. 매수 주문 업데이트 (비관적 락)
     * 3. 매도 주문 업데이트 (비관적 락)
     * 4. 매수자 잔고 업데이트 (user_balances)
     * 5. 매도자 잔고 업데이트 (user_balances)
     * 6. 매수자 포지션 업데이트 (user_positions)
     * 7. 매도자 포지션 업데이트 (user_positions)
     * 
     * 트랜잭션:
     * - 모든 작업을 하나의 트랜잭션으로 처리
     *   * Trade 저장
     *   * 주문 업데이트 (매수/매도)
     *   * 잔고 업데이트 (매수자/매도자)
     *   * 포지션 업데이트 (매수자/매도자)
     * - 하나라도 실패하면 전체 롤백 (Kafka Consumer가 재시도)
     * - 데이터 일관성 보장
     */
    private void handleTradeExecuted(JsonNode jsonNode) {
        // 하위 호환성: 새 필드가 있으면 스냅샷 방식, 없으면 기존 Delta 방식
        if (jsonNode.has("buyer_base_available")) {
            handleTradeExecutedWithSnapshot(jsonNode);
        } else {
            handleTradeExecutedWithDelta(jsonNode);
        }
    }
    
    /**
     * 스냅샷 방식으로 체결 처리 (새 방식)
     * Handle trade executed with balance snapshot
     */
    private void handleTradeExecutedWithSnapshot(JsonNode jsonNode) {
        Long buyOrderId;
        Long sellOrderId;
        Long buyerId;
        Long sellerId;
        BigDecimal price;
        BigDecimal amount;
        String baseMint;
        String quoteMint;
        
        // 잔고 스냅샷
        BigDecimal buyerBaseAvailable;
        BigDecimal buyerBaseLocked;
        BigDecimal buyerQuoteAvailable;
        BigDecimal buyerQuoteLocked;
        BigDecimal sellerBaseAvailable;
        BigDecimal sellerBaseLocked;
        BigDecimal sellerQuoteAvailable;
        BigDecimal sellerQuoteLocked;
        
        try {
            buyOrderId = jsonNode.get("buy_order_id").asLong();
            sellOrderId = jsonNode.get("sell_order_id").asLong();
            buyerId = jsonNode.get("buyer_id").asLong();
            sellerId = jsonNode.get("seller_id").asLong();
            price = new BigDecimal(jsonNode.get("price").asText());
            amount = new BigDecimal(jsonNode.get("amount").asText());
            baseMint = jsonNode.get("base_mint").asText();
            quoteMint = jsonNode.get("quote_mint").asText();
            
            // 잔고 스냅샷 파싱
            buyerBaseAvailable = new BigDecimal(jsonNode.get("buyer_base_available").asText());
            buyerBaseLocked = new BigDecimal(jsonNode.get("buyer_base_locked").asText());
            buyerQuoteAvailable = new BigDecimal(jsonNode.get("buyer_quote_available").asText());
            buyerQuoteLocked = new BigDecimal(jsonNode.get("buyer_quote_locked").asText());
            sellerBaseAvailable = new BigDecimal(jsonNode.get("seller_base_available").asText());
            sellerBaseLocked = new BigDecimal(jsonNode.get("seller_base_locked").asText());
            sellerQuoteAvailable = new BigDecimal(jsonNode.get("seller_quote_available").asText());
            sellerQuoteLocked = new BigDecimal(jsonNode.get("seller_quote_locked").asText());
        } catch (Exception e) {
            throw new RuntimeException("JSON 파싱 실패", e);
        }
        
        BigDecimal totalValue = price.multiply(amount);
        
        Trade trade;
        try {
            trade = Trade.builder()
                    .buyOrderId(buyOrderId)
                    .sellOrderId(sellOrderId)
                    .buyerId(buyerId)
                    .sellerId(sellerId)
                    .baseMint(baseMint)
                    .quoteMint(quoteMint)
                    .price(price)
                    .amount(amount)
                    .createdAt(parseTimestamp(jsonNode.get("timestamp")))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Trade 엔티티 생성 실패", e);
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 모든 작업을 하나의 트랜잭션으로 처리
        // Trade 저장, 주문 업데이트, 잔고 업데이트, 포지션 업데이트 모두 원자적으로 처리
        // 하나라도 실패하면 전체 롤백 (Kafka Consumer가 재시도)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 주문 존재 여부 확인 (Foreign Key 제약 조건 때문에 필요)
        // 트랜잭션 내에서 확인하므로 최신 상태를 보장
        boolean buyOrderExists = orderRepository.existsById(buyOrderId);
        boolean sellOrderExists = orderRepository.existsById(sellOrderId);
        
        if (!buyOrderExists || !sellOrderExists) {
            // 주문이 없으면 스킵 (이미 처리된 체결이거나 오래된 메시지일 수 있음)
            log.warn("[Kafka] 주문이 존재하지 않아 체결 처리 스킵: buyOrderId={} (exists={}), sellOrderId={} (exists={})", 
                    buyOrderId, buyOrderExists, sellOrderId, sellOrderExists);
            return;
        }
        
        // 중복 체결 방지: 주문의 현재 filled_amount 확인 (비관적 락)
        var buyOrderOpt = orderRepository.findByIdForUpdate(buyOrderId);
        var sellOrderOpt = orderRepository.findByIdForUpdate(sellOrderId);
        
        if (buyOrderOpt.isEmpty() || sellOrderOpt.isEmpty()) {
            log.warn("[Kafka] 주문 조회 실패 (락 획득 실패): buyOrderId={}, sellOrderId={}", buyOrderId, sellOrderId);
            return;
        }
        
        Order buyOrder = buyOrderOpt.get();
        Order sellOrder = sellOrderOpt.get();
        
        // 이미 전량 체결된 주문이면 스킵 (중복 체결 방지)
        if ("filled".equals(buyOrder.getStatus()) || "filled".equals(sellOrder.getStatus())) {
            log.warn("[Kafka] 이미 전량 체결된 주문으로 인한 중복 체결 이벤트 스킵: buyOrderId={} (status={}), sellOrderId={} (status={})", 
                    buyOrderId, buyOrder.getStatus(), sellOrderId, sellOrder.getStatus());
            return;
        }
        
        // 체결 후 예상 filled_amount가 주문 amount를 초과하는지 확인
        BigDecimal buyOrderNewFilledAmount = buyOrder.getFilledAmount().add(amount);
        BigDecimal sellOrderNewFilledAmount = sellOrder.getFilledAmount().add(amount);
        
        if (buyOrderNewFilledAmount.compareTo(buyOrder.getAmount()) > 0) {
            log.error("[Kafka] 매수 주문 체결량 초과: buyOrderId={}, currentFilled={}, amount={}, newFilled={}", 
                    buyOrderId, buyOrder.getFilledAmount(), buyOrder.getAmount(), buyOrderNewFilledAmount);
            return;
        }
        
        if (sellOrderNewFilledAmount.compareTo(sellOrder.getAmount()) > 0) {
            log.error("[Kafka] 매도 주문 체결량 초과: sellOrderId={}, currentFilled={}, amount={}, newFilled={}", 
                    sellOrderId, sellOrder.getFilledAmount(), sellOrder.getAmount(), sellOrderNewFilledAmount);
            return;
        }
        
        // Trade 저장
        Trade savedTrade = tradeRepository.save(trade);
        
        // 매수 주문 업데이트 (이미 락을 획득했으므로 직접 업데이트)
        updateOrderWithLock(buyOrderId, amount, price.multiply(amount));
        
        // 매도 주문 업데이트 (이미 락을 획득했으므로 직접 업데이트)
        updateOrderWithLock(sellOrderId, amount, price.multiply(amount));
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 수수료 계산 및 기록
        // Fee Calculation and Recording
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 정산에서의 중요성:
        // ==================
        // 1. 수수료 수익 집계:
        //    - 거래소가 벌어들인 총 수수료 수익을 계산하기 위해 필요
        //    - 거래 금액에 따라 수수료 금액이 다름 (1000달러 → 0.1달러, 100000달러 → 10달러)
        //    - 각 거래마다 실제 수수료 금액을 기록해야 총 수수료 수익을 집계할 수 있음
        // 
        // 2. 사용자별 수수료 납부 내역:
        //    - 사용자가 지불한 총 수수료를 집계하여 리포트 제공
        //    - 세금 신고용 데이터로 활용 가능
        // 
        // 3. 거래쌍별 수수료 분석:
        //    - 어떤 거래쌍에서 수수료 수익이 많이 발생하는지 분석
        // 
        // 처리 과정:
        // ==========
        // 1. FeeConfig 조회: 실제 적용된 수수료율 가져오기
        // 2. 수수료 계산: 거래 금액 × 수수료율
        // 3. TradeFee 엔티티 생성: 매수자 수수료 + 매도자 수수료
        // 4. trade_fees 테이블에 저장: 정산 시 집계 가능하도록 기록
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 실제 적용된 수수료 설정 조회 (수수료율 기록을 위해)
        FeeConfig feeConfig = feeConfigService.getFeeConfig(baseMint, quoteMint);
        BigDecimal feeRate = feeConfig.getFeeRate(); // 실제 적용된 수수료율 (예: 0.0001 = 0.01%)
        
        // 수수료 계산 (거래 금액 × 수수료율)
        BigDecimal buyerFee = totalValue.multiply(feeRate);
        BigDecimal sellerFee = totalValue.multiply(feeRate);
        
        log.info("[Kafka] 수수료 계산 (스냅샷 방식): buyerFee={}, sellerFee={}, feeRate={}, totalValue={}, baseMint={}, quoteMint={}", 
                buyerFee, sellerFee, feeRate, totalValue, baseMint, quoteMint);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 수수료 기록 (trade_fees 테이블에 저장)
        // Fee Recording (Save to trade_fees table)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 목적:
        // - 각 거래에서 발생한 수수료를 상세히 기록
        // - 정산 시 수수료 수익 집계의 기초 데이터로 활용
        // 
        // 기록 내용:
        // - trade_id: 어떤 거래에서 발생한 수수료인지
        // - user_id: 누가 수수료를 납부했는지 (매수자 또는 매도자)
        // - fee_type: 'buyer' (매수자) 또는 'seller' (매도자)
        // - fee_rate: 적용된 수수료율 (예: 0.0001)
        // - fee_amount: 실제 수수료 금액 (예: 0.1달러 또는 10달러)
        // - fee_mint: 수수료가 차감된 자산 (보통 USDT)
        // - trade_value: 거래 금액 (수수료 계산 기준)
        // 
        // 정산 활용 예시:
        // - 일별 총 수수료 수익: SELECT SUM(fee_amount) FROM trade_fees WHERE created_at BETWEEN ...
        // - 사용자별 수수료 납부액: SELECT SUM(fee_amount) FROM trade_fees WHERE user_id = ...
        // - 거래쌍별 수수료 수익: SELECT SUM(fee_amount) FROM trade_fees WHERE fee_mint = 'USDT' AND ...
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 사용자 엔티티 조회 (TradeFee 엔티티에서 참조하기 위해 필요)
        User buyer = userRepository.findById(buyerId)
                .orElseThrow(() -> new RuntimeException("매수자 사용자를 찾을 수 없습니다: userId=" + buyerId));
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new RuntimeException("매도자 사용자를 찾을 수 없습니다: userId=" + sellerId));
        
        // 매수자 수수료 기록
        TradeFee buyerTradeFee = TradeFee.builder()
                .trade(savedTrade)                    // 어떤 거래에서 발생한 수수료인지
                .user(buyer)                          // 누가 수수료를 납부했는지 (매수자)
                .feeType("buyer")                     // 수수료 유형: 매수자
                .feeRate(feeRate)                     // 실제 적용된 수수료율 (예: 0.0001)
                .feeAmount(buyerFee)                  // 실제 수수료 금액 (예: 0.1달러 또는 10달러)
                .feeMint(quoteMint)                   // 수수료가 차감된 자산 (보통 USDT)
                .tradeValue(totalValue)                // 거래 금액 (수수료 계산 기준)
                .build();
        
        // 매도자 수수료 기록
        TradeFee sellerTradeFee = TradeFee.builder()
                .trade(savedTrade)                    // 어떤 거래에서 발생한 수수료인지
                .user(seller)                         // 누가 수수료를 납부했는지 (매도자)
                .feeType("seller")                    // 수수료 유형: 매도자
                .feeRate(feeRate)                     // 실제 적용된 수수료율 (예: 0.0001)
                .feeAmount(sellerFee)                  // 실제 수수료 금액 (예: 0.1달러 또는 10달러)
                .feeMint(quoteMint)                   // 수수료가 차감된 자산 (보통 USDT)
                .tradeValue(totalValue)                // 거래 금액 (수수료 계산 기준)
                .build();
        
        // trade_fees 테이블에 저장 (배치 INSERT로 성능 최적화)
        tradeFeeRepository.saveAll(java.util.List.of(buyerTradeFee, sellerTradeFee));
        
        log.info("[Kafka] 수수료 기록 완료: tradeId={}, buyerFee={}, sellerFee={}, totalFeeRevenue={}", 
                savedTrade.getId(), buyerFee, sellerFee, buyerFee.add(sellerFee));
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 잔고 업데이트 (스냅샷 적용 + 수수료 차감)
        // Balance Update (Apply snapshot and deduct fee)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 처리 과정:
        // 1. base_mint (예: SOL): 스냅샷 그대로 적용 (수수료 없음)
        //    - 매수자: SOL 잔고 증가 (구매한 SOL)
        //    - 매도자: SOL 잔고 감소 (판매한 SOL)
        // 
        // 2. quote_mint (예: USDT): 스냅샷 적용 후 수수료 차감
        //    - 매수자: USDT 잔고 감소 (구매 비용) - 수수료 차감
        //    - 매도자: USDT 잔고 증가 (판매 대금) - 수수료 차감
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // base_mint: 스냅샷 그대로 적용 (수수료 없음)
        updateBalanceFromSnapshot(buyerId, baseMint, buyerBaseAvailable, buyerBaseLocked);
        updateBalanceFromSnapshot(sellerId, baseMint, sellerBaseAvailable, sellerBaseLocked);
        
        // quote_mint: 스냅샷 적용 후 수수료 차감
        updateBalanceFromSnapshotWithFee(buyerId, quoteMint, buyerQuoteAvailable, buyerQuoteLocked, buyerFee);
        updateBalanceFromSnapshotWithFee(sellerId, quoteMint, sellerQuoteAvailable, sellerQuoteLocked, sellerFee);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 포지션 업데이트
        // Position Update
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 포지션(Position)이란?
        // - 사용자가 보유한 자산의 평균 매수가, 손익, 수익률 등을 계산한 정보
        // - 예: 100 USDT에 SOL을 구매했다면 평균 매수가는 100 USDT
        // 
        // 처리 과정:
        // - 매수자: 포지션 증가 (구매한 SOL 추가)
        // - 매도자: 포지션 감소 (판매한 SOL 차감)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 매수자 포지션 업데이트 (구매한 SOL 추가)
        updatePositionForTrade(buyerId, baseMint, quoteMint, amount, price);
        
        // 매도자 포지션 업데이트 (매도는 포지션 감소)
        updatePositionForTrade(sellerId, baseMint, quoteMint, amount.negate(), price);
        
        log.info("[Kafka] 체결 처리 완료 (스냅샷): buyOrderId={}, sellOrderId={}, price={}, amount={}, tradeId={}, totalFeeRevenue={}", 
                buyOrderId, sellOrderId, price, amount, savedTrade.getId(), buyerFee.add(sellerFee));
    }
    
    /**
     * Delta 방식으로 체결 처리 (기존 방식, 하위 호환성)
     * Handle trade executed with delta calculation
     */
    private void handleTradeExecutedWithDelta(JsonNode jsonNode) {
        Long buyOrderId;
        Long sellOrderId;
        Long buyerId;
        Long sellerId;
        BigDecimal price;
        BigDecimal amount;
        String baseMint;
        String quoteMint;
        
        try {
            buyOrderId = jsonNode.get("buy_order_id").asLong();
            sellOrderId = jsonNode.get("sell_order_id").asLong();
            buyerId = jsonNode.get("buyer_id").asLong();
            sellerId = jsonNode.get("seller_id").asLong();
            price = new BigDecimal(jsonNode.get("price").asText());
            amount = new BigDecimal(jsonNode.get("amount").asText());
            baseMint = jsonNode.get("base_mint").asText();
            quoteMint = jsonNode.get("quote_mint").asText();
        } catch (Exception e) {
            throw new RuntimeException("JSON 파싱 실패", e);
        }
        
        BigDecimal totalValue = price.multiply(amount);
        
        Trade trade;
        try {
            trade = Trade.builder()
                    .buyOrderId(buyOrderId)
                    .sellOrderId(sellOrderId)
                    .buyerId(buyerId)
                    .sellerId(sellerId)
                    .baseMint(baseMint)
                    .quoteMint(quoteMint)
                    .price(price)
                    .amount(amount)
                    .createdAt(parseTimestamp(jsonNode.get("timestamp")))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Trade 엔티티 생성 실패", e);
        }
        
        // 주문 존재 여부 확인
        boolean buyOrderExists = orderRepository.existsById(buyOrderId);
        boolean sellOrderExists = orderRepository.existsById(sellOrderId);
        
        if (!buyOrderExists || !sellOrderExists) {
            log.warn("[Kafka] 주문이 존재하지 않아 체결 처리 스킵: buyOrderId={} (exists={}), sellOrderId={} (exists={})", 
                    buyOrderId, buyOrderExists, sellOrderId, sellOrderExists);
            return;
        }
        
        // 중복 체결 방지: 주문의 현재 filled_amount 확인 (비관적 락)
        var buyOrderOpt = orderRepository.findByIdForUpdate(buyOrderId);
        var sellOrderOpt = orderRepository.findByIdForUpdate(sellOrderId);
        
        if (buyOrderOpt.isEmpty() || sellOrderOpt.isEmpty()) {
            log.warn("[Kafka] 주문 조회 실패 (락 획득 실패): buyOrderId={}, sellOrderId={}", buyOrderId, sellOrderId);
            return;
        }
        
        Order buyOrder = buyOrderOpt.get();
        Order sellOrder = sellOrderOpt.get();
        
        // 이미 전량 체결된 주문이면 스킵 (중복 체결 방지)
        if ("filled".equals(buyOrder.getStatus()) || "filled".equals(sellOrder.getStatus())) {
            log.warn("[Kafka] 이미 전량 체결된 주문으로 인한 중복 체결 이벤트 스킵: buyOrderId={} (status={}), sellOrderId={} (status={})", 
                    buyOrderId, buyOrder.getStatus(), sellOrderId, sellOrder.getStatus());
            return;
        }
        
        // 체결 후 예상 filled_amount가 주문 amount를 초과하는지 확인
        BigDecimal buyOrderNewFilledAmount = buyOrder.getFilledAmount().add(amount);
        BigDecimal sellOrderNewFilledAmount = sellOrder.getFilledAmount().add(amount);
        
        if (buyOrderNewFilledAmount.compareTo(buyOrder.getAmount()) > 0) {
            log.error("[Kafka] 매수 주문 체결량 초과: buyOrderId={}, currentFilled={}, amount={}, newFilled={}", 
                    buyOrderId, buyOrder.getFilledAmount(), buyOrder.getAmount(), buyOrderNewFilledAmount);
            return;
        }
        
        if (sellOrderNewFilledAmount.compareTo(sellOrder.getAmount()) > 0) {
            log.error("[Kafka] 매도 주문 체결량 초과: sellOrderId={}, currentFilled={}, amount={}, newFilled={}", 
                    sellOrderId, sellOrder.getFilledAmount(), sellOrder.getAmount(), sellOrderNewFilledAmount);
            return;
        }
        
        // Trade 저장
        Trade savedTrade = tradeRepository.save(trade);
        
        // 매수 주문 업데이트 (이미 락을 획득했으므로 직접 업데이트)
        updateOrderWithLock(buyOrderId, amount, price.multiply(amount));
        
        // 매도 주문 업데이트 (이미 락을 획득했으므로 직접 업데이트)
        updateOrderWithLock(sellOrderId, amount, price.multiply(amount));
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 수수료 계산 및 기록 (Delta 방식)
        // Fee Calculation and Recording (Delta Method)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // Delta 방식에서도 수수료 기록은 동일하게 수행
        // 정산 시 수수료 수익 집계를 위해 반드시 필요
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 실제 적용된 수수료 설정 조회 (수수료율 기록을 위해)
        FeeConfig feeConfig = feeConfigService.getFeeConfig(baseMint, quoteMint);
        BigDecimal feeRate = feeConfig.getFeeRate(); // 실제 적용된 수수료율 (예: 0.0001 = 0.01%)
        
        // 수수료 계산 (거래 금액 × 수수료율)
        BigDecimal buyerFee = totalValue.multiply(feeRate);
        BigDecimal sellerFee = totalValue.multiply(feeRate);
        
        log.info("[Kafka] 수수료 계산 (Delta 방식): buyerFee={}, sellerFee={}, feeRate={}, totalValue={}, baseMint={}, quoteMint={}", 
                buyerFee, sellerFee, feeRate, totalValue, baseMint, quoteMint);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 수수료 기록 (trade_fees 테이블에 저장)
        // Fee Recording (Save to trade_fees table)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // Delta 방식에서도 수수료 기록은 필수
        // 정산 시 모든 거래의 수수료를 집계하기 위해 필요
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 사용자 엔티티 조회 (TradeFee 엔티티에서 참조하기 위해 필요)
        User buyer = userRepository.findById(buyerId)
                .orElseThrow(() -> new RuntimeException("매수자 사용자를 찾을 수 없습니다: userId=" + buyerId));
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new RuntimeException("매도자 사용자를 찾을 수 없습니다: userId=" + sellerId));
        
        // 매수자 수수료 기록
        TradeFee buyerTradeFee = TradeFee.builder()
                .trade(savedTrade)
                .user(buyer)
                .feeType("buyer")
                .feeRate(feeRate)
                .feeAmount(buyerFee)
                .feeMint(quoteMint)
                .tradeValue(totalValue)
                .build();
        
        // 매도자 수수료 기록
        TradeFee sellerTradeFee = TradeFee.builder()
                .trade(savedTrade)
                .user(seller)
                .feeType("seller")
                .feeRate(feeRate)
                .feeAmount(sellerFee)
                .feeMint(quoteMint)
                .tradeValue(totalValue)
                .build();
        
        // trade_fees 테이블에 저장 (배치 INSERT로 성능 최적화)
        tradeFeeRepository.saveAll(java.util.List.of(buyerTradeFee, sellerTradeFee));
        
        log.info("[Kafka] 수수료 기록 완료 (Delta): tradeId={}, buyerFee={}, sellerFee={}, totalFeeRevenue={}", 
                savedTrade.getId(), buyerFee, sellerFee, buyerFee.add(sellerFee));
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 매수자 잔고 업데이트 (Delta 방식)
        // Buyer Balance Update (Delta Method)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // Delta 방식의 잔고 업데이트:
        // - base_mint: available 증가 (체결로 받은 SOL)
        // - quote_mint: locked 감소 (주문에 사용했던 금액 + 수수료)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 매수자 잔고 업데이트
        // - base_mint: available 증가 (체결로 받음)
        // - quote_mint: locked 감소 (주문에 사용했던 금액 + 수수료)
        updateBalanceForTrade(buyerId, baseMint, amount, BigDecimal.ZERO); // base_mint available 증가
        BigDecimal buyerTotalDeduction = totalValue.add(buyerFee); // 거래 금액 + 수수료
        updateBalanceForTrade(buyerId, quoteMint, BigDecimal.ZERO, buyerTotalDeduction.negate()); // quote_mint locked 감소 (거래금액 + 수수료)
        
        // 매도자 잔고 업데이트
        // - base_mint: locked 감소 (주문에 사용했던 수량)
        // - quote_mint: available 증가 (체결로 받은 금액 - 수수료)
        updateBalanceForTrade(sellerId, baseMint, BigDecimal.ZERO, amount.negate()); // base_mint locked 감소
        BigDecimal sellerNetAmount = totalValue.subtract(sellerFee); // 거래 금액 - 수수료
        updateBalanceForTrade(sellerId, quoteMint, sellerNetAmount, BigDecimal.ZERO); // quote_mint available 증가 (거래금액 - 수수료)
        
        // 매수자 포지션 업데이트
        updatePositionForTrade(buyerId, baseMint, quoteMint, amount, price);
        
        // 매도자 포지션 업데이트 (매도는 포지션 감소)
        updatePositionForTrade(sellerId, baseMint, quoteMint, amount.negate(), price);
        
        log.info("[Kafka] 체결 처리 완료 (Delta): buyOrderId={}, sellOrderId={}, price={}, amount={}, tradeId={}, totalFeeRevenue={}", 
                buyOrderId, sellOrderId, price, amount, savedTrade.getId(), buyerFee.add(sellerFee));
    }
    
    /**
     * 주문 업데이트 (체결 이벤트용)
     * Update order for trade execution
     * 
     * 처리 과정:
     * 1. 주문 조회 (비관적 락)
     * 2. filled_amount, filled_quote_amount 업데이트
     * 3. 주문 상태 업데이트 (partial/filled)
     * 
     * 동시성 제어:
     * - 비관적 락 사용
     * - 주문 ID 순서로 락 획득하여 데드락 방지
     */
    private void updateOrderForTrade(Long orderId, Long otherOrderId, BigDecimal price, BigDecimal amount, Long tradeId) {
        // 데드락 방지: 주문 ID 순서로 락 획득
        Long firstOrderId = orderId < otherOrderId ? orderId : otherOrderId;
        Long secondOrderId = orderId < otherOrderId ? otherOrderId : orderId;
        
        // 첫 번째 주문 업데이트
        updateOrderWithLock(firstOrderId, amount, price.multiply(amount));
        
        // 두 번째 주문 업데이트 (같은 체결이면 두 주문 모두 업데이트)
        if (!firstOrderId.equals(secondOrderId)) {
            updateOrderWithLock(secondOrderId, amount, price.multiply(amount));
        }
    }
    
    /**
     * 주문 상태 업데이트 (비관적 락 사용)
     * Update Order Status with Pessimistic Lock
     * 
     * 체결된 수량과 금액을 누적하여 주문 상태를 업데이트합니다.
     * 비관적 락을 사용하여 동시성 문제를 방지합니다.
     */
    private void updateOrderWithLock(Long orderId, BigDecimal filledAmount, BigDecimal filledQuoteAmount) {
        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        if (orderOpt.isEmpty()) {
            return;
        }
        
        Order order = orderOpt.get();
        
        // 이미 취소된 주문이면 스킵
        if ("cancelled".equals(order.getStatus()) || "rejected".equals(order.getStatus())) {
            return;
        }
        
        // filled_amount 누적
        BigDecimal newFilledAmount = order.getFilledAmount().add(filledAmount);
        BigDecimal newFilledQuoteAmount = order.getFilledQuoteAmount().add(filledQuoteAmount);
        
        order.setFilledAmount(newFilledAmount);
        order.setFilledQuoteAmount(newFilledQuoteAmount);
        
        // 상태 계산
        if (newFilledAmount.compareTo(order.getAmount()) >= 0) {
            // 전량 체결
            order.setStatus("filled");
        } else if (newFilledAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 부분 체결
            order.setStatus("partial");
        }
        
        orderRepository.save(order);
    }
    
    /**
     * 취소 이벤트 처리
     * Handle order cancelled event
     * 
     * 처리 과정:
     * 1. 주문 조회 (비관적 락)
     * 2. 주문 상태를 cancelled로 업데이트
     * 3. 잠금된 잔고 해제 (locked → available)
     * 
     * 트랜잭션:
     * - 모든 작업을 하나의 트랜잭션으로 처리
     * - 실패 시 롤백 (Kafka Consumer가 재시도)
     */
    private void handleOrderCancelled(JsonNode jsonNode) {
        Long orderId = jsonNode.get("order_id").asLong();
        Long userId = jsonNode.get("user_id").asLong();
        String baseMint = jsonNode.get("base_mint").asText();
        String quoteMint = jsonNode.get("quote_mint").asText();
        
        // 주문 조회 (비관적 락)
        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        if (orderOpt.isEmpty()) {
            return;
        }
        
        Order order = orderOpt.get();
        
        // 이미 취소된 주문인지 확인
        if ("cancelled".equals(order.getStatus())) {
            return;
        }
        
        // 주문 상태를 cancelled로 업데이트
        order.setStatus("cancelled");
        orderRepository.save(order);
        
        // 잠금된 잔고 해제
        // 주문 타입에 따라 다른 자산의 잔고를 해제
        if ("buy".equals(order.getOrderType())) {
            // 매수 주문 취소: quote_mint의 locked → available
            BigDecimal lockedAmount = order.getAmount().subtract(order.getFilledAmount()).multiply(order.getPrice());
            updateBalanceForCancel(userId, quoteMint, lockedAmount);
        } else if ("sell".equals(order.getOrderType())) {
            // 매도 주문 취소: base_mint의 locked → available
            BigDecimal lockedAmount = order.getAmount().subtract(order.getFilledAmount());
            updateBalanceForCancel(userId, baseMint, lockedAmount);
        }
        
        log.debug("[Kafka] 취소 처리 완료: orderId={}, userId={}", orderId, userId);
    }
    
    /**
     * 체결 이벤트에 대한 잔고 업데이트
     * Update balance for trade execution
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param availableDelta available 증감량
     * @param lockedDelta locked 증감량
     */
    private void updateBalanceForTrade(Long userId, String mint, BigDecimal availableDelta, BigDecimal lockedDelta) {
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        UserBalance balance;
        BigDecimal currentAvailable = BigDecimal.ZERO;
        BigDecimal currentLocked = BigDecimal.ZERO;
        
        if (balanceOpt.isPresent()) {
            balance = balanceOpt.get();
            currentAvailable = balance.getAvailable();
            currentLocked = balance.getLocked();
            
            log.debug("[Balance] 업데이트 전 - userId={}, mint={}, available={}, locked={}, availableDelta={}, lockedDelta={}", 
                    userId, mint, currentAvailable, currentLocked, availableDelta, lockedDelta);
            
            // lockedDelta가 음수인 경우 (locked 감소)
            if (lockedDelta.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal requiredLocked = lockedDelta.abs(); // 필요한 locked 양
                BigDecimal newLocked = currentLocked.add(lockedDelta); // lockedDelta는 이미 음수
                
                if (newLocked.compareTo(BigDecimal.ZERO) < 0) {
                    // locked가 부족한 경우: available에서 차감
                    BigDecimal shortage = newLocked.abs(); // 부족한 양
                    BigDecimal availableAfterLocked = currentAvailable.subtract(shortage);
                    
                    if (availableAfterLocked.compareTo(BigDecimal.ZERO) < 0) {
                        // available도 부족하면 에러
                        log.error("[Balance] 잔고 부족 - userId={}, mint={}, currentAvailable={}, currentLocked={}, requiredLocked={}, shortage={}", 
                                userId, mint, currentAvailable, currentLocked, requiredLocked, shortage);
                        throw new RuntimeException(String.format(
                                "잔고가 부족합니다: userId=%d, mint=%s, available=%s, locked=%s, requiredLocked=%s", 
                                userId, mint, currentAvailable, currentLocked, requiredLocked));
                    }
                    
                    // available에서 부족한 만큼 차감하고 locked는 0으로 설정
                    balance.setAvailable(availableAfterLocked);
                    balance.setLocked(BigDecimal.ZERO);
                    log.warn("[Balance] locked 부족으로 available에서 차감 - userId={}, mint={}, available={} -> {}, locked={} -> 0", 
                            userId, mint, currentAvailable, availableAfterLocked);
                } else {
                    // locked가 충분한 경우: 정상 처리
                    balance.setAvailable(currentAvailable.add(availableDelta));
                    balance.setLocked(newLocked);
                }
            } else {
                // lockedDelta가 양수이거나 0인 경우: 정상 처리
                balance.setAvailable(currentAvailable.add(availableDelta));
                balance.setLocked(currentLocked.add(lockedDelta));
            }
        } else {
            // 잔고가 없으면 생성
            // lockedDelta가 음수이면 에러 (잔고가 없는데 차감할 수 없음)
            if (lockedDelta.compareTo(BigDecimal.ZERO) < 0) {
                log.error("[Balance] 잔고 없음 - userId={}, mint={}, lockedDelta={} (음수)", userId, mint, lockedDelta);
                throw new RuntimeException(String.format(
                        "잔고가 없습니다: userId=%d, mint=%s, lockedDelta=%s", 
                        userId, mint, lockedDelta));
            }
            
            balance = UserBalance.builder()
                    .userId(userId)
                    .mintAddress(mint)
                    .available(availableDelta)
                    .locked(lockedDelta)
                    .build();
        }
        
        // 최종 검증
        if (balance.getAvailable().compareTo(BigDecimal.ZERO) < 0 || balance.getLocked().compareTo(BigDecimal.ZERO) < 0) {
            log.error("[Balance] 최종 검증 실패 - userId={}, mint={}, available={}, locked={}", 
                    userId, mint, balance.getAvailable(), balance.getLocked());
            throw new RuntimeException(String.format(
                    "잔고가 음수가 될 수 없습니다: userId=%d, mint=%s, available=%s, locked=%s", 
                    userId, mint, balance.getAvailable(), balance.getLocked()));
        }
        
        userBalanceRepository.save(balance);
        log.debug("[Balance] 업데이트 완료 - userId={}, mint={}, available={}, locked={}", 
                userId, mint, balance.getAvailable(), balance.getLocked());
    }
    
    /**
     * 취소 이벤트에 대한 잔고 업데이트 (locked → available)
     * Update balance for order cancellation
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param unlockAmount 해제할 금액
     */
    private void updateBalanceForCancel(Long userId, String mint, BigDecimal unlockAmount) {
        if (unlockAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return; // 해제할 금액이 없으면 스킵
        }
        
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        if (balanceOpt.isEmpty()) {
            return;
        }
        
        UserBalance balance = balanceOpt.get();
        balance.setLocked(balance.getLocked().subtract(unlockAmount));
        balance.setAvailable(balance.getAvailable().add(unlockAmount));
        
        // 잔고가 음수가 되지 않도록 검증
        if (balance.getAvailable().compareTo(BigDecimal.ZERO) < 0 || balance.getLocked().compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("잔고가 음수가 될 수 없습니다");
        }
        
        userBalanceRepository.save(balance);
    }
    
    /**
     * 스냅샷을 그대로 적용 (수수료 없음)
     * Apply balance snapshot without fee
     * base_mint에 사용
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param available 스냅샷의 available 값
     * @param locked 스냅샷의 locked 값
     */
    private void updateBalanceFromSnapshot(
        Long userId, String mint, 
        BigDecimal available, BigDecimal locked
    ) {
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        if (balanceOpt.isPresent()) {
            UserBalance balance = balanceOpt.get();
            balance.setAvailable(available);
            balance.setLocked(locked);
            userBalanceRepository.save(balance);
            
            log.debug("[Balance] 스냅샷 적용: userId={}, mint={}, available={}, locked={}", 
                    userId, mint, available, locked);
        } else {
            // 없으면 생성
            UserBalance balance = UserBalance.builder()
                    .userId(userId)
                    .mintAddress(mint)
                    .available(available)
                    .locked(locked)
                    .build();
            userBalanceRepository.save(balance);
            
            log.info("[Balance] 잔고 생성 (스냅샷): userId={}, mint={}, available={}, locked={}", 
                    userId, mint, available, locked);
        }
    }
    
    /**
     * 스냅샷 적용 + 수수료 차감 (단순화)
     * Apply balance snapshot and deduct fee
     * quote_mint에 사용
     * 
     * 로직:
     * 1. 스냅샷 그대로 적용
     * 2. available에서 수수료 차감
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param snapshotAvailable 스냅샷의 available 값
     * @param snapshotLocked 스냅샷의 locked 값
     * @param fee 수수료
     */
    private void updateBalanceFromSnapshotWithFee(
        Long userId, String mint, 
        BigDecimal snapshotAvailable, BigDecimal snapshotLocked,
        BigDecimal fee
    ) {
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        if (balanceOpt.isPresent()) {
            UserBalance balance = balanceOpt.get();
            
            // 1. 스냅샷 그대로 적용
            balance.setAvailable(snapshotAvailable);
            balance.setLocked(snapshotLocked);
            
            // 2. available에서 수수료 차감 (단순!)
            balance.setAvailable(balance.getAvailable().subtract(fee));
            
            userBalanceRepository.save(balance);
            
            log.debug("[Balance] 스냅샷 적용 + 수수료 차감: userId={}, mint={}, available={} -> {}, locked={}, fee={}", 
                    userId, mint, snapshotAvailable, balance.getAvailable(), snapshotLocked, fee);
        } else {
            // 없으면 생성
            UserBalance balance = UserBalance.builder()
                    .userId(userId)
                    .mintAddress(mint)
                    .available(snapshotAvailable.subtract(fee))
                    .locked(snapshotLocked)
                    .build();
            userBalanceRepository.save(balance);
            
            log.info("[Balance] 잔고 생성 (스냅샷 + 수수료): userId={}, mint={}, available={}, locked={}, fee={}", 
                    userId, mint, balance.getAvailable(), snapshotLocked, fee);
        }
    }
    
    /**
     * 체결 이벤트에 대한 포지션 업데이트
     * Update position for trade execution
     * 
     * 포지션 계산:
     * - 매수: position_amount 증가 (양수)
     * - 매도: position_amount 감소 (음수)
     * - 평균 진입 가격: 가중 평균으로 계산
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param amount 포지션 증감량 (양수: 매수, 음수: 매도)
     * @param price 체결 가격
     */
    private void updatePositionForTrade(Long userId, String baseMint, String quoteMint, BigDecimal amount, BigDecimal price) {
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
                // 새로운 평균 가격 = (기존 포지션 * 기존 가격 + 신규 체결량 * 체결가) / 총 포지션
                BigDecimal totalCost = currentPosition.multiply(position.getAvgEntryPrice())
                        .add(amount.multiply(price));
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
                    .avgEntryPrice(price)
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
    
    /**
     * 타임스탬프 파싱
     * Parse timestamp
     */
    private LocalDateTime parseTimestamp(JsonNode timestampNode) {
        if (timestampNode == null || timestampNode.isNull()) {
            return LocalDateTime.now();
        }
        
        // ISO 8601 형식 또는 Unix timestamp 지원
        if (timestampNode.isTextual()) {
            String timestampStr = timestampNode.asText();
            try {
                // ISO 8601 형식 (UTC 포함) 파싱 시도
                if (timestampStr.endsWith("Z") || timestampStr.contains("+") || timestampStr.contains("-")) {
                    // UTC 또는 타임존이 포함된 경우 Instant로 파싱
                    Instant instant = Instant.parse(timestampStr);
                    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                } else {
                    // 일반 ISO 8601 형식 (타임존 없음)
                    return LocalDateTime.parse(timestampStr);
                }
            } catch (Exception e) {
                try {
                    // Unix timestamp로 시도
                    long timestamp = Long.parseLong(timestampStr);
                    return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
                } catch (NumberFormatException nfe) {
                    return LocalDateTime.now();
                }
            }
        } else if (timestampNode.isNumber()) {
            long timestamp = timestampNode.asLong();
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
        }
        
        return LocalDateTime.now();
    }
    
}
