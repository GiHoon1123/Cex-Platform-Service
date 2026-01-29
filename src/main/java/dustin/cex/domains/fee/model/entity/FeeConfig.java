package dustin.cex.domains.fee.model.entity;

import java.math.BigDecimal;
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
 * 수수료 설정 엔티티
 * Fee Config Entity
 * 
 * 역할:
 * - 거래쌍별 수수료율 관리
 * - 서버 시작 시 메모리에 로드되어 사용
 */
@Entity
@Table(name = "fee_configs",
       indexes = {
           @Index(name = "idx_fee_configs_pair", columnList = "base_mint,quote_mint,is_active"),
           @Index(name = "idx_fee_configs_active", columnList = "is_active")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeeConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 기준 자산 (NULL이면 모든 거래쌍에 적용)
     */
    @Column(name = "base_mint", length = 255)
    private String baseMint;
    
    /**
     * 기준 통화 (NULL이면 모든 거래쌍에 적용)
     */
    @Column(name = "quote_mint", length = 255)
    private String quoteMint;
    
    /**
     * 수수료율 (소수점, 예: 0.0001 = 0.01%)
     */
    @Column(name = "fee_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal feeRate;
    
    /**
     * 수수료 유형: 'taker', 'maker', 'both'
     */
    @Column(name = "fee_type", nullable = false, length = 20)
    private String feeType;
    
    /**
     * 활성화 여부
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
