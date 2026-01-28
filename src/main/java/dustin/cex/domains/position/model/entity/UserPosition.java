package dustin.cex.domains.position.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 사용자 포지션 엔티티
 * User Position Entity
 * 
 * 역할:
 * - 사용자의 실시간 포지션 및 미실현 손익(PnL) 관리
 * - 체결 이벤트마다 업데이트되어 실시간 포지션 추적
 * 
 * 포지션 계산:
 * - 매수: position_amount 증가 (양수)
 * - 매도: position_amount 감소 (음수 가능, 숏 포지션)
 * - 평균 진입 가격: 가중 평균으로 계산
 * - 미실현 손익: (현재가 - 평균 진입가) * position_amount
 * 
 * 업데이트 시점:
 * - trade_executed 이벤트 처리 시마다 즉시 업데이트
 */
@Entity
@Table(name = "user_positions", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "base_mint", "quote_mint"}),
       indexes = {
           @Index(name = "idx_user_positions_user_id", columnList = "user_id"),
           @Index(name = "idx_user_positions_base_quote", columnList = "base_mint,quote_mint")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPosition {
    
    /**
     * 포지션 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 사용자 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 기준 자산 (예: SOL)
     */
    @Column(name = "base_mint", nullable = false, length = 50)
    private String baseMint;
    
    /**
     * 기준 통화 (예: USDT)
     */
    @Column(name = "quote_mint", nullable = false, length = 50)
    private String quoteMint;
    
    /**
     * 포지션 수량
     * - 양수: 롱 포지션 (보유)
     * - 음수: 숏 포지션 (공매도)
     * - 0: 포지션 없음
     */
    @Column(name = "position_amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal positionAmount;
    
    /**
     * 평균 진입 가격
     * 가중 평균으로 계산: (기존 포지션 * 기존 가격 + 신규 체결량 * 체결가) / 총 포지션
     */
    @Column(name = "avg_entry_price", nullable = false, precision = 30, scale = 9)
    private BigDecimal avgEntryPrice;
    
    /**
     * 현재 가격 (마지막 체결가 또는 시장가)
     * 실시간으로 업데이트되어 미실현 손익 계산에 사용
     */
    @Column(name = "current_price", nullable = false, precision = 30, scale = 9)
    private BigDecimal currentPrice;
    
    /**
     * 미실현 손익 (Unrealized PnL)
     * 계산식: (current_price - avg_entry_price) * position_amount
     * - 양수: 수익
     * - 음수: 손실
     */
    @Column(name = "unrealized_pnl", nullable = false, precision = 30, scale = 9)
    private BigDecimal unrealizedPnl;
    
    /**
     * 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 최종 업데이트 시간
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    /**
     * 엔티티 저장 전 자동 실행
     * createdAt과 updatedAt 자동 설정
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    /**
     * 엔티티 업데이트 전 자동 실행
     * updatedAt 자동 갱신
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
