package dustin.cex.domains.trade.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.trade.model.dto.TradeResponse;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 체결 내역 서비스
 * Trade Service
 * 
 * 역할:
 * - 체결 내역 조회 비즈니스 로직 처리
 * - 거래쌍별 체결 내역 조회
 * - 사용자별 체결 내역 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {
    
    private final TradeRepository tradeRepository;
    
    /**
     * 거래쌍별 체결 내역 조회
     * Get trades by trading pair
     * 
     * @param baseMint 기준 자산 (예: "SOL")
     * @param quoteMint 기준 통화 (예: "USDT", 기본값: "USDT")
     * @param pageable 페이징 정보 (page, size)
     * @return 페이징된 체결 내역
     */
    @Transactional(readOnly = true)
    public Page<TradeResponse> getTrades(String baseMint, String quoteMint, Pageable pageable) {
        String quote = quoteMint != null && !quoteMint.isEmpty() ? quoteMint : "USDT";
        
        Page<Trade> trades = tradeRepository.findByBaseMintAndQuoteMintOrderByCreatedAtDesc(
                baseMint, quote, pageable);
        
        return trades.map(this::convertToDto);
    }
    
    /**
     * 내 체결 내역 조회
     * Get my trades
     * 
     * 현재 로그인한 사용자가 참여한 모든 체결 내역을 조회합니다.
     * 특정 자산(mint)을 지정하면 해당 자산의 거래 내역만 필터링합니다.
     * 
     * @param userId 사용자 ID
     * @param mint 자산 식별자 (선택, 특정 자산만 필터링)
     * @param pageable 페이징 정보 (page, size)
     * @return 페이징된 체결 내역
     */
    @Transactional(readOnly = true)
    public Page<TradeResponse> getMyTrades(Long userId, String mint, Pageable pageable) {
        Page<Trade> trades;
        if (mint != null && !mint.isEmpty()) {
            // 특정 자산만 필터링
            trades = tradeRepository.findByUserIdAndBaseMintOrderByCreatedAtDesc(userId, mint, pageable);
        } else {
            // 모든 자산
            trades = tradeRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        
        return trades.map(this::convertToDto);
    }
    
    /**
     * Trade 엔티티를 TradeResponse DTO로 변환
     * Convert Trade entity to TradeResponse DTO
     */
    private TradeResponse convertToDto(Trade trade) {
        return TradeResponse.builder()
                .id(trade.getId())
                .buyOrderId(trade.getBuyOrderId())
                .sellOrderId(trade.getSellOrderId())
                .buyerId(trade.getBuyerId())
                .sellerId(trade.getSellerId())
                .baseMint(trade.getBaseMint())
                .quoteMint(trade.getQuoteMint())
                .price(trade.getPrice())
                .amount(trade.getAmount())
                .createdAt(trade.getCreatedAt())
                .build();
    }
}
