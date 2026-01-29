package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 정산 내역 엔티티
 * Trade Settlement Entity
 * 
 * 역할:
 * - 거래(Trade) 관련 일별/월별 정산 요약 정보 저장
 * - 거래소의 일별/월별 거래 성과를 요약하여 기록
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산만을 담당하며, 향후 입출금/이벤트/쿠폰 정산은 별도 하위 도메인에서 처리됩니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 거래소 수익 집계:
 *    - 일별/월별 총 거래 건수 및 거래량
 *    - 일별/월별 총 수수료 수익
 *    - 거래소가 벌어들인 수익을 한눈에 파악 가능
 * 
 * 2. 거래쌍별 성과 분석:
 *    - base_mint별로 집계하여 어떤 자산이 수익성이 높은지 분석
 *    - 예: SOL/USDT에서 1000달러 수익, BTC/USDT에서 500달러 수익
 * 
 * 3. 검증 상태 관리:
 *    - validation_status: 정산 검증 상태 ('pending', 'validated', 'failed')
 *    - 검증 실패 시 validation_error에 상세 에러 메시지 저장
 *    - 정산의 정확성을 보장하기 위한 필수 기능
 * 
 * 데이터 구조 설명:
 * =================
 * - settlement_date: 정산일 (YYYY-MM-DD)
 * - settlement_type: 'daily' (일별) 또는 'monthly' (월별)
 * - total_trades: 총 거래 건수
 * - total_volume: 총 거래량 (USDT 기준)
 * - total_fee_revenue: 총 수수료 수익 (USDT 기준) - 거래소가 벌어들인 수익
 * - total_users: 거래한 사용자 수
 * - base_mint: NULL이면 전체, 특정 자산이면 해당 자산만
 * - validation_status: 검증 상태 ('pending', 'validated', 'failed')
 * 
 * 예시:
 * =====
 * 일별 정산 (2026-01-28):
 * - total_trades: 1000건
 * - total_volume: 1,000,000 USDT
 * - total_fee_revenue: 200 USDT (0.01% 수수료율 기준)
 * - validation_status: 'validated'
 * 
 * 월별 정산 (2026-01):
 * - total_trades: 30,000건
 * - total_volume: 30,000,000 USDT
 * - total_fee_revenue: 6,000 USDT
 * - validation_status: 'validated'
 */
@Entity
@Table(name = "trade_settlements",
       indexes = {
           @Index(name = "idx_trade_settlements_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlements_type", columnList = "settlement_type"),
           @Index(name = "idx_trade_settlements_date_type", columnList = "settlement_date,settlement_type"),
           @Index(name = "idx_trade_settlements_base_mint", columnList = "base_mint")
       },
       uniqueConstraints = {
           @jakarta.persistence.UniqueConstraint(
               name = "uk_trade_settlements_date_type_mint",
               columnNames = {"settlement_date", "settlement_type", "base_mint", "quote_mint"}
           )
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlement {
    
    /**
     * 정산 내역 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
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
     * 
     * 의미:
     * - 'daily': 일별 정산 (매일 자정에 실행)
     * - 'monthly': 월별 정산 (매월 1일 자정에 실행)
     */
    @Column(name = "settlement_type", nullable = false, length = 20)
    private String settlementType; // 'daily' or 'monthly'
    
    /**
     * 총 거래 건수
     * 
     * 의미:
     * - 해당 기간 동안 발생한 모든 체결 건수
     * - 예: 일별 정산 시 전일 거래 건수
     */
    @Column(name = "total_trades", nullable = false)
    private Long totalTrades;
    
    /**
     * 총 거래량 (USDT 기준)
     * 
     * 의미:
     * - 해당 기간 동안 발생한 모든 거래의 총 금액
     * - 계산식: SUM(price × amount) for all trades
     * - 예: 1000 USDT 거래 10건 = 10,000 USDT
     */
    @Column(name = "total_volume", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalVolume;
    
    /**
     * 총 수수료 수익 (USDT 기준)
     * 
     * 의미:
     * - 해당 기간 동안 거래소가 벌어들인 총 수수료 수익
     * - 계산식: SUM(fee_amount) from trade_fees
     * - 예: 거래 1에서 0.2달러, 거래 2에서 20달러 → 총 20.2달러
     * 
     * 정산에서의 중요성:
     * - 거래소의 실제 수익을 나타냄
     * - 일별/월별 수익 추이 분석 가능
     */
    @Column(name = "total_fee_revenue", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalFeeRevenue;
    
    /**
     * 거래한 사용자 수
     * 
     * 의미:
     * - 해당 기간 동안 거래에 참여한 고유 사용자 수
     * - 예: 100명의 사용자가 거래에 참여
     */
    @Column(name = "total_users", nullable = false)
    private Long totalUsers;
    
    /**
     * 기준 자산 (NULL이면 전체, 특정 자산이면 해당 자산만)
     * 
     * 의미:
     * - NULL: 모든 거래쌍 포함
     * - "SOL": SOL/USDT 거래만 포함
     * - "BTC": BTC/USDT 거래만 포함
     * 
     * 정산 활용:
     * - 거래쌍별 성과 분석
     * - 예: SOL/USDT에서 1000달러 수익, BTC/USDT에서 500달러 수익
     */
    @Column(name = "base_mint", length = 255)
    private String baseMint;
    
    /**
     * 기준 통화 (기본값: USDT)
     * 
     * 의미:
     * - 대부분의 경우 "USDT"
     * - 향후 다른 기준 통화 지원 시 사용
     */
    @Column(name = "quote_mint", length = 255)
    private String quoteMint;
    
    /**
     * 검증 상태: 'pending', 'validated', 'failed'
     * 
     * 의미:
     * - 'pending': 아직 검증하지 않음
     * - 'validated': 검증 통과 (모든 검증 항목 통과)
     * - 'failed': 검증 실패 (검증 에러 발생)
     * 
     * 정산에서의 중요성:
     * - 정산의 정확성을 보장하기 위한 필수 기능
     * - 검증 실패 시 validation_error에 상세 에러 메시지 저장
     */
    @Column(name = "validation_status", length = 20)
    private String validationStatus; // 'pending', 'validated', 'failed'
    
    /**
     * 검증 실패 시 에러 메시지
     * 
     * 의미:
     * - validation_status가 'failed'일 때 상세 에러 메시지 저장
     * - 예: "거래 검증 실패: 매수자 지출(1000) != 매도자 수입(999.9), 차이=0.1"
     */
    @Column(name = "validation_error", columnDefinition = "TEXT")
    private String validationError;
    
    /**
     * 정산 데이터 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 검증 완료 시간
     * 
     * 의미:
     * - validation_status가 'validated' 또는 'failed'로 변경된 시간
     */
    @Column(name = "validated_at")
    private LocalDateTime validatedAt;
    
    /**
     * 엔티티 저장 전 자동으로 생성 시간 설정
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (validationStatus == null) {
            validationStatus = "pending";
        }
        if (quoteMint == null) {
            quoteMint = "USDT";
        }
    }
    
    /**
     * 엔티티 업데이트 전 자동으로 검증 시간 설정
     */
    @PreUpdate
    protected void onUpdate() {
        if (validatedAt == null && ("validated".equals(validationStatus) || "failed".equals(validationStatus))) {
            validatedAt = LocalDateTime.now();
        }
    }
}
