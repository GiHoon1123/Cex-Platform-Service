package dustin.cex.domains.balance.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 사용자 잔고 엔티티
 * User Balance Entity
 * 
 * 역할:
 * - 사용자의 자산별 잔고 관리 (available, locked)
 * - Java 서버가 잔고의 Source of Truth
 * - Kafka 이벤트 처리 시마다 업데이트
 * 
 * 잔고 구조:
 * - available: 사용 가능한 잔고 (주문 가능)
 * - locked: 주문에 사용 중인 잔고 (체결 대기 중)
 * - total = available + locked
 */
@Entity
@Table(name = "user_balances",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "mint_address"}),
       indexes = {
           @Index(name = "idx_user_balances_user_id", columnList = "user_id"),
           @Index(name = "idx_user_balances_mint", columnList = "mint_address")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBalance {
    
    /**
     * 잔고 고유 ID (DB에서 자동 생성)
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
     * 자산 종류 (예: USDT, SOL)
     */
    @Column(name = "mint_address", nullable = false, length = 50)
    private String mintAddress;
    
    /**
     * 사용 가능한 잔고
     * 주문 가능한 금액
     */
    @Column(name = "available", nullable = false, precision = 30, scale = 9)
    @Builder.Default
    private BigDecimal available = BigDecimal.ZERO;
    
    /**
     * 잠금된 잔고
     * 주문에 사용 중인 금액 (체결 대기 중)
     */
    @Column(name = "locked", nullable = false, precision = 30, scale = 9)
    @Builder.Default
    private BigDecimal locked = BigDecimal.ZERO;
    
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
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    /**
     * 엔티티 업데이트 전 자동 실행
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
