package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.trade.model.entity.Trade;
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
 * 거래별 수수료 내역 엔티티
 * Trade Fee Entity
 * 
 * 역할:
 * - 각 거래(trade)에서 발생한 수수료를 상세히 기록
 * - 정산 시 수수료 수익 집계의 기초 데이터
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 수수료만을 담당하며, 향후 입출금 수수료/이벤트 수수료는 별도 하위 도메인에서 처리됩니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 수수료 수익 집계:
 *    - 거래소가 벌어들인 총 수수료 수익을 계산하기 위해 필요
 *    - 예: 1000달러 거래 → 0.1달러 수수료, 100000달러 거래 → 10달러 수수료
 *    - 각 거래마다 실제 수수료 금액이 다르므로 기록 필요
 * 
 * 2. 사용자별 수수료 납부 내역:
 *    - 사용자가 지불한 총 수수료를 집계하여 리포트 제공
 *    - 세금 신고용 데이터로 활용 가능
 * 
 * 3. 거래쌍별 수수료 분석:
 *    - 어떤 거래쌍에서 수수료 수익이 많이 발생하는지 분석
 *    - 예: SOL/USDT에서 100달러, BTC/USDT에서 50달러
 * 
 * 데이터 구조:
 * ============
 * - trade_id: 어떤 거래에서 발생한 수수료인지
 * - user_id: 누가 수수료를 납부했는지 (매수자 또는 매도자)
 * - fee_type: 'buyer' (매수자) 또는 'seller' (매도자)
 * - fee_rate: 적용된 수수료율 (예: 0.0001 = 0.01%)
 * - fee_amount: 실제 수수료 금액 (예: 0.1달러 또는 10달러)
 * - fee_mint: 수수료가 차감된 자산 (SOL 또는 USDT)
 * - trade_value: 거래 금액 (수수료 계산 기준)
 * 
 * 예시:
 * =====
 * 거래 1: 1000 USDT로 10 SOL 구매
 * - buyerFee: fee_rate=0.0001, fee_amount=0.1, trade_value=1000
 * - sellerFee: fee_rate=0.0001, fee_amount=0.1, trade_value=1000
 * - 거래소 수익: 0.1 + 0.1 = 0.2 USDT
 * 
 * 거래 2: 100000 USDT로 1000 SOL 구매
 * - buyerFee: fee_rate=0.0001, fee_amount=10, trade_value=100000
 * - sellerFee: fee_rate=0.0001, fee_amount=10, trade_value=100000
 * - 거래소 수익: 10 + 10 = 20 USDT
 * 
 * 일별 정산 시:
 * - SELECT SUM(fee_amount) FROM trade_fees WHERE created_at BETWEEN '2026-01-28' AND '2026-01-29'
 * - 결과: 0.2 + 20 = 20.2 USDT (하루 총 수수료 수익)
 */
@Entity
@Table(name = "trade_fees",
       indexes = {
           @Index(name = "idx_trade_fees_trade_id", columnList = "trade_id"),
           @Index(name = "idx_trade_fees_user_id", columnList = "user_id"),
           @Index(name = "idx_trade_fees_created_at", columnList = "created_at"),
           @Index(name = "idx_trade_fees_fee_mint", columnList = "fee_mint")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeFee {
    
    /**
     * 수수료 내역 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 거래 ID (어떤 거래에서 발생한 수수료인지)
     * 
     * 관계:
     * - 하나의 거래(Trade)에서 두 개의 수수료가 발생 (매수자 수수료 + 매도자 수수료)
     * - 예: trade_id=100 → buyerFee, sellerFee 두 레코드
     */
    @ManyToOne
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;
    
    /**
     * 사용자 ID (누가 수수료를 납부했는지)
     * 
     * 관계:
     * - 매수자 또는 매도자 중 한 명
     * - 각 거래마다 두 명의 사용자가 수수료를 납부
     */
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * 수수료 유형: 'buyer' (매수자) 또는 'seller' (매도자)
     * 
     * 의미:
     * - 'buyer': 매수 주문을 낸 사용자가 납부한 수수료
     * - 'seller': 매도 주문을 낸 사용자가 납부한 수수료
     * 
     * 정산 활용:
     * - 사용자별 총 수수료 납부액 계산 시 사용
     * - 예: user_id=1의 buyerFee 합계 = 해당 사용자가 매수 시 납부한 총 수수료
     */
    @Column(name = "fee_type", nullable = false, length = 20)
    private String feeType; // 'buyer' or 'seller'
    
    /**
     * 적용된 수수료율 (소수점 형식)
     * 
     * 예시:
     * - 0.0001 = 0.01% (만분의 일)
     * - 0.001 = 0.1% (천분의 일)
     * - 0.01 = 1% (백분의 일)
     * 
     * 중요성:
     * - 수수료 정책이 변경되어도 과거 거래의 수수료율을 추적 가능
     * - 정산 시 어떤 수수료율이 적용되었는지 확인 가능
     */
    @Column(name = "fee_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal feeRate;
    
    /**
     * 실제 수수료 금액 (거래 금액 × 수수료율)
     * 
     * 계산식:
     * fee_amount = trade_value × fee_rate
     * 
     * 예시:
     * - trade_value=1000, fee_rate=0.0001 → fee_amount=0.1
     * - trade_value=100000, fee_rate=0.0001 → fee_amount=10
     * 
     * 정산 활용:
     * - 일별/월별 총 수수료 수익 = SUM(fee_amount)
     * - 거래소가 실제로 벌어들인 수익을 정확히 계산 가능
     */
    @Column(name = "fee_amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal feeAmount;
    
    /**
     * 수수료가 차감된 자산 종류
     * 
     * 값:
     * - 'USDT': 대부분의 경우 USDT에서 수수료 차감
     * - 'SOL': 특정 거래쌍에서는 SOL에서 수수료 차감 가능
     * 
     * 정산 활용:
     * - 자산별 수수료 수익 집계
     * - 예: USDT 수수료 수익 1000달러, SOL 수수료 수익 10 SOL
     */
    @Column(name = "fee_mint", nullable = false, length = 255)
    private String feeMint;
    
    /**
     * 거래 금액 (수수료 계산의 기준이 된 금액)
     * 
     * 의미:
     * - 체결 가격 × 체결 수량 = 거래 금액
     * - 예: 100 USDT × 10 SOL = 1000 USDT
     * 
     * 정산 활용:
     * - 거래 금액별 수수료 수익 분석
     * - 예: 소액 거래(<1000)에서 수수료 수익 100달러, 대액 거래(>10000)에서 수수료 수익 1000달러
     */
    @Column(name = "trade_value", nullable = false, precision = 30, scale = 9)
    private BigDecimal tradeValue;
    
    /**
     * 수수료 기록 생성 시간
     * 
     * 정산 활용:
     * - 일별/월별 수수료 수익 집계 시 날짜 필터링에 사용
     * - 예: WHERE created_at BETWEEN '2026-01-28' AND '2026-01-29'
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 엔티티 저장 전 자동으로 생성 시간 설정
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
