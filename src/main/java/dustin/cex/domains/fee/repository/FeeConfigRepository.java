package dustin.cex.domains.fee.repository;

import dustin.cex.domains.fee.model.entity.FeeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 수수료 설정 Repository
 * Fee Config Repository
 */
@Repository
public interface FeeConfigRepository extends JpaRepository<FeeConfig, Long> {
    
    /**
     * 활성화된 모든 수수료 설정 조회
     */
    List<FeeConfig> findByIsActiveTrue();
    
    /**
     * 거래쌍별 수수료 설정 조회 (가장 구체적인 것부터)
     * 우선순위:
     * 1. base_mint와 quote_mint가 정확히 일치하는 설정
     * 2. base_mint만 일치하는 설정 (quote_mint = NULL)
     * 3. quote_mint만 일치하는 설정 (base_mint = NULL)
     * 4. 모두 NULL인 기본 설정
     */
    @Query("""
        SELECT f FROM FeeConfig f 
        WHERE f.isActive = true 
        AND (
            (f.baseMint = :baseMint AND f.quoteMint = :quoteMint)
            OR (f.baseMint = :baseMint AND f.quoteMint IS NULL)
            OR (f.baseMint IS NULL AND f.quoteMint = :quoteMint)
            OR (f.baseMint IS NULL AND f.quoteMint IS NULL)
        )
        ORDER BY 
            CASE WHEN f.baseMint IS NOT NULL AND f.quoteMint IS NOT NULL THEN 1
                 WHEN f.baseMint IS NOT NULL AND f.quoteMint IS NULL THEN 2
                 WHEN f.baseMint IS NULL AND f.quoteMint IS NOT NULL THEN 3
                 ELSE 4 END
        LIMIT 1
        """)
    Optional<FeeConfig> findFeeConfigForPair(
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint
    );
}
