package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import dustin.cex.domains.auth.model.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자별 거래 정산 내역 엔티티
 * Trade User Settlement Entity
 * 
 * 역할:
 * - 사용자별 일별/월별 거래 및 수수료 내역 요약
 * - 사용자 리포트 생성의 기초 데이터
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산만을 담당하며, 향후 입출금/이벤트/쿠폰 정산은 별도 하위 도메인에서 처리됩니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 사용자별 거래 리포트:
 *    - 사용자가 얼마나 거래했는지, 얼마나 수수료를 납부했는지 파악
 *    - 사용자별 거래량, 거래 건수, 수수료 납부액 집계
 * 
 * 2. VIP 등급 산정:
 *    - 거래량 기준으로 VIP 등급 결정
 *    - 예: 월 거래량 100만 달러 이상 → VIP 등급
 * 
 * 3. 세금 신고용 데이터:
 *    - 사용자가 지불한 총 수수료를 세금 신고에 활용
 *    - 거래 내역 요약 제공
 * 
 * 데이터 구조 설명:
 * =================
 * - user_id: 사용자 ID
 * - settlement_date: 정산일
 * - settlement_type: 'daily' (일별) 또는 'monthly' (월별)
 * - total_trades: 사용자 총 거래 건수
 * - total_volume: 사용자 총 거래량 (USDT 기준)
 * - total_fees_paid: 사용자가 지불한 총 수수료
 * - base_mint: NULL이면 전체, 특정 자산이면 해당 자산만
 * 
 * 예시:
 * =====
 * 사용자 1의 일별 정산 (2026-01-28):
 * - total_trades: 10건
 * - total_volume: 10,000 USDT
 * - total_fees_paid: 2 USDT (0.01% 수수료율 기준)
 * 
 * 사용자 1의 월별 정산 (2026-01):
 * - total_trades: 300건
 * - total_volume: 300,000 USDT
 * - total_fees_paid: 60 USDT
 */
@Entity
@Table(name = "trade_user_settlements",
       indexes = {
           @Index(name = "idx_trade_user_settlements_user_id", columnList = "user_id"),
           @Index(name = "idx_trade_user_settlements_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_user_settlements_user_date", columnList = "user_id,settlement_date"),
           @Index(name = "idx_trade_user_settlements_type", columnList = "settlement_type")
       },
       uniqueConstraints = {
           @jakarta.persistence.UniqueConstraint(
               name = "uk_trade_user_settlements_user_date_type_mint",
               columnNames = {"user_id", "settlement_date", "settlement_type", "base_mint", "quote_mint"}
           )
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeUserSettlement {
    
    /**
     * 사용자별 정산 내역 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 사용자 ID (누구의 정산 내역인지)
     */
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * 정산일 (YYYY-MM-DD)
     * 
     * 의미:
     * - 일별 정산: 해당 날짜 (예: 2026-01-28)
     * - 월별 정산: 해당 월의 첫 날 (예: 2026-01-01)
     */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;
    
    /**
     * 정산 유형: 'daily' (일별) 또는 'monthly' (월별)
     */
    @Column(name = "settlement_type", nullable = false, length = 20)
    private String settlementType; // 'daily' or 'monthly'
    
    /**
     * 사용자 총 거래 건수
     * 
     * 의미:
     * - 해당 기간 동안 사용자가 참여한 모든 체결 건수
     * - 매수자 또는 매도자로 참여한 거래 모두 포함
     */
    @Column(name = "total_trades", nullable = false)
    private Long totalTrades;
    
    /**
     * 사용자 총 거래량 (USDT 기준)
     * 
     * 의미:
     * - 해당 기간 동안 사용자가 거래한 총 금액
     * - 계산식: SUM(price × amount) for user's trades
     * - 예: 1000 USDT 거래 10건 = 10,000 USDT
     */
    @Column(name = "total_volume", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalVolume;
    
    /**
     * 사용자가 지불한 총 수수료
     * 
     * 의미:
     * - 해당 기간 동안 사용자가 납부한 모든 수수료의 합계
     * - 계산식: SUM(fee_amount) from trade_fees where user_id = ...
     * - 예: 거래 1에서 0.1달러, 거래 2에서 0.2달러 → 총 0.3달러
     * 
     * 정산 활용:
     * - 사용자별 수수료 납부 내역 리포트
     * - 세금 신고용 데이터 제공
     */
    @Column(name = "total_fees_paid", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalFeesPaid;
    
    /**
     * 기준 자산 (NULL이면 전체, 특정 자산이면 해당 자산만)
     * 
     * 의미:
     * - NULL: 사용자의 모든 거래쌍 포함
     * - "SOL": 사용자의 SOL/USDT 거래만 포함
     * - "BTC": 사용자의 BTC/USDT 거래만 포함
     */
    @Column(name = "base_mint", length = 255)
    private String baseMint;
    
    /**
     * 기준 통화 (기본값: USDT)
     */
    @Column(name = "quote_mint", length = 255)
    private String quoteMint;
    
    /**
     * 정산 데이터 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 엔티티 저장 전 자동으로 생성 시간 설정
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (quoteMint == null) {
            quoteMint = "USDT";
        }
    }
}
