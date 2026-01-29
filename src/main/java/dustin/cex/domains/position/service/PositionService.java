package dustin.cex.domains.position.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.position.model.dto.PositionResponse;
import dustin.cex.domains.position.model.entity.UserPosition;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 포지션 서비스
 * Position Service
 * 
 * 역할:
 * - 포지션 조회 비즈니스 로직 처리
 * - 사용자별 포지션 정보 조회
 * - 포지션 정보 계산 (평균 매수가, 손익, 수익률 등)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {
    
    private final UserPositionRepository userPositionRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final TradeRepository tradeRepository;
    
    /**
     * 특정 자산 포지션 조회
     * Get position for specific asset
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산 (예: "SOL")
     * @param quoteMint 기준 통화 (예: "USDT", 기본값: "USDT")
     * @return 포지션 정보 (없으면 null)
     */
    @Transactional(readOnly = true)
    public PositionResponse getPosition(Long userId, String baseMint, String quoteMint) {
        String quote = quoteMint != null && !quoteMint.isEmpty() ? quoteMint : "USDT";
        
        // 포지션 조회
        Optional<UserPosition> positionOpt = userPositionRepository.findByUserIdAndBaseMintAndQuoteMint(
                userId, baseMint, quote);
        
        if (positionOpt.isEmpty()) {
            return null;
        }
        
        UserPosition position = positionOpt.get();
        
        // 잔고 조회
        Optional<UserBalance> balanceOpt = userBalanceRepository.findByUserIdAndMintAddress(userId, baseMint);
        BigDecimal available = balanceOpt.map(UserBalance::getAvailable).orElse(BigDecimal.ZERO);
        BigDecimal locked = balanceOpt.map(UserBalance::getLocked).orElse(BigDecimal.ZERO);
        BigDecimal currentBalance = available.add(locked);
        
        // 거래 요약 정보 집계
        PositionResponse.TradeSummary tradeSummary = calculateTradeSummary(userId, baseMint, quote);
        
        // 현재 평가액 계산
        BigDecimal currentValue = null;
        BigDecimal unrealizedPnl = null;
        BigDecimal unrealizedPnlPercent = null;
        
        if (position.getCurrentPrice() != null && currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            currentValue = position.getCurrentPrice().multiply(currentBalance);
            
            // 미실현 손익 계산: (현재가 - 평균 진입가) × 포지션 수량
            if (position.getAvgEntryPrice() != null && position.getPositionAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal priceDiff = position.getCurrentPrice().subtract(position.getAvgEntryPrice());
                unrealizedPnl = priceDiff.multiply(position.getPositionAmount());
                
                // 미실현 수익률 계산: (미실현 손익 / 총 매수 금액) × 100
                BigDecimal totalCost = position.getAvgEntryPrice().multiply(position.getPositionAmount());
                if (totalCost.compareTo(BigDecimal.ZERO) != 0) {
                    unrealizedPnlPercent = unrealizedPnl
                            .divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
            }
        }
        
        return PositionResponse.builder()
                .mint(baseMint)
                .currentBalance(currentBalance)
                .available(available)
                .locked(locked)
                .averageEntryPrice(position.getAvgEntryPrice())
                .currentMarketPrice(position.getCurrentPrice())
                .currentValue(currentValue)
                .unrealizedPnl(unrealizedPnl != null ? unrealizedPnl : position.getUnrealizedPnl())
                .unrealizedPnlPercent(unrealizedPnlPercent)
                .tradeSummary(tradeSummary)
                .build();
    }
    
    /**
     * 모든 자산 포지션 조회
     * Get all positions for user
     * 
     * @param userId 사용자 ID
     * @return 포지션 목록
     */
    @Transactional(readOnly = true)
    public List<PositionResponse> getAllPositions(Long userId) {
        // 활성 포지션만 조회 (포지션 수량이 0이 아닌 것만)
        List<UserPosition> positions = userPositionRepository.findActivePositionsByUserId(userId);
        
        List<PositionResponse> result = new ArrayList<>();
        for (UserPosition position : positions) {
            try {
                PositionResponse pos = getPosition(userId, position.getBaseMint(), position.getQuoteMint());
                if (pos != null) {
                    result.add(pos);
                }
            } catch (Exception e) {
                log.error("포지션 조회 실패: userId={}, baseMint={}, quoteMint={}", 
                        userId, position.getBaseMint(), position.getQuoteMint(), e);
            }
        }
        return result;
    }
    
    /**
     * 거래 요약 정보 계산
     * Calculate trade summary
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @return 거래 요약 정보
     */
    private PositionResponse.TradeSummary calculateTradeSummary(Long userId, String baseMint, String quoteMint) {
        // 사용자의 해당 자산 거래 내역 조회
        List<Trade> trades = tradeRepository.findByUserIdAndBaseMintOrderByCreatedAtDesc(
                userId, baseMint, org.springframework.data.domain.PageRequest.of(0, 10000)).getContent();
        
        long totalBuyTrades = 0;
        long totalSellTrades = 0;
        BigDecimal realizedPnl = BigDecimal.ZERO;
        
        // 평균 매수가 계산을 위한 변수
        BigDecimal totalBoughtAmount = BigDecimal.ZERO;
        BigDecimal totalBoughtCost = BigDecimal.ZERO;
        
        for (Trade trade : trades) {
            if (trade.getBuyerId().equals(userId)) {
                // 매수 거래
                totalBuyTrades++;
                totalBoughtAmount = totalBoughtAmount.add(trade.getAmount());
                totalBoughtCost = totalBoughtCost.add(trade.getPrice().multiply(trade.getAmount()));
            } else if (trade.getSellerId().equals(userId)) {
                // 매도 거래
                totalSellTrades++;
                
                // 실현 손익 계산: (매도가 - 평균 매수가) × 매도 수량
                if (totalBoughtAmount.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal avgBuyPrice = totalBoughtCost.divide(totalBoughtAmount, 9, RoundingMode.HALF_UP);
                    BigDecimal pnl = trade.getPrice().subtract(avgBuyPrice).multiply(trade.getAmount());
                    realizedPnl = realizedPnl.add(pnl);
                    
                    // 매도 후 총 매수 수량 및 금액 조정
                    totalBoughtAmount = totalBoughtAmount.subtract(trade.getAmount());
                    if (totalBoughtAmount.compareTo(BigDecimal.ZERO) < 0) {
                        totalBoughtAmount = BigDecimal.ZERO;
                    }
                    totalBoughtCost = avgBuyPrice.multiply(totalBoughtAmount);
                }
            }
        }
        
        return PositionResponse.TradeSummary.builder()
                .totalBuyTrades(totalBuyTrades)
                .totalSellTrades(totalSellTrades)
                .realizedPnl(realizedPnl)
                .build();
    }
}
