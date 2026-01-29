package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeFee;

/**
 * 거래별 수수료 내역 Repository
 * Trade Fee Repository
 * 
 * 역할:
 * - trade_fees 테이블에 대한 데이터베이스 접근 제공
 * - 정산 시 수수료 수익 집계를 위한 쿼리 메서드 제공
 * 
 * 하위 도메인 분리:
 * ================
 * 이 Repository는 settlement.trade 하위 도메인에 속합니다.
 * 거래 수수료만을 담당하며, 향후 입출금 수수료/이벤트 수수료는 별도 하위 도메인에서 처리됩니다.
 * 
 * 정산 활용:
 * ==========
 * 1. 일별 수수료 수익 집계:
 *    - calculateTotalFeeRevenueByMint() 메서드 사용
 *    - 전일 모든 거래의 수수료 합계 계산
 * 
 * 2. 사용자별 수수료 납부 내역:
 *    - findByUserIdAndCreatedAtBetween() 메서드 사용
 *    - 특정 사용자가 지불한 총 수수료 계산
 * 
 * 3. 거래쌍별 수수료 수익 분석:
 *    - findByFeeMintAndCreatedAtBetween() 메서드 사용
 *    - USDT 수수료 수익 vs SOL 수수료 수익 비교
 */
@Repository
public interface TradeFeeRepository extends JpaRepository<TradeFee, Long> {
    
    /**
     * 특정 거래의 모든 수수료 내역 조회
     * 
     * 사용 예시:
     * - 하나의 거래에서 매수자 수수료와 매도자 수수료를 모두 조회
     * - 거래소가 해당 거래에서 벌어들인 총 수수료 = buyerFee + sellerFee
     * 
     * @param tradeId 거래 ID
     * @return 해당 거래의 모든 수수료 내역 (보통 2개: buyerFee, sellerFee)
     */
    @Query("SELECT tf FROM TradeFee tf WHERE tf.trade.id = :tradeId")
    List<TradeFee> findByTradeId(@Param("tradeId") Long tradeId);
    
    /**
     * 특정 사용자의 수수료 내역 조회 (날짜 범위 지정)
     * 
     * 사용 예시:
     * - 사용자별 일별 수수료 납부 내역 계산
     * - 사용자별 월별 수수료 납부 내역 계산
     * 
     * @param userId 사용자 ID
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 해당 기간 동안 사용자가 납부한 모든 수수료 내역
     */
    @Query("SELECT tf FROM TradeFee tf WHERE tf.user.id = :userId AND tf.createdAt BETWEEN :startDate AND :endDate ORDER BY tf.createdAt DESC")
    List<TradeFee> findByUserIdAndCreatedAtBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * 특정 자산의 수수료 내역 조회 (날짜 범위 지정)
     * 
     * 사용 예시:
     * - USDT 수수료 수익 집계: findByFeeMintAndCreatedAtBetween("USDT", ...)
     * - SOL 수수료 수익 집계: findByFeeMintAndCreatedAtBetween("SOL", ...)
     * 
     * @param feeMint 자산 종류 (예: "USDT", "SOL")
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 해당 기간 동안 해당 자산으로 납부된 모든 수수료 내역
     */
    @Query("SELECT tf FROM TradeFee tf WHERE tf.feeMint = :feeMint AND tf.createdAt BETWEEN :startDate AND :endDate")
    List<TradeFee> findByFeeMintAndCreatedAtBetween(
        @Param("feeMint") String feeMint,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * 일별 총 수수료 수익 계산 (USDT 기준)
     * 
     * 사용 예시:
     * - 일별 정산 시 거래소가 벌어들인 총 수수료 수익 계산
     * - SELECT SUM(fee_amount) FROM trade_fees WHERE fee_mint = 'USDT' AND created_at BETWEEN ...
     * 
     * @param feeMint 자산 종류 (보통 "USDT")
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 총 수수료 수익 (USDT)
     */
    @Query("SELECT COALESCE(SUM(tf.feeAmount), 0) FROM TradeFee tf WHERE tf.feeMint = :feeMint AND tf.createdAt BETWEEN :startDate AND :endDate")
    java.math.BigDecimal calculateTotalFeeRevenueByMint(
        @Param("feeMint") String feeMint,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * 특정 사용자의 총 수수료 납부액 계산 (날짜 범위 지정)
     * 
     * 사용 예시:
     * - 사용자별 일별/월별 수수료 납부액 계산
     * - 사용자 리포트 생성 시 사용
     * 
     * @param userId 사용자 ID
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 사용자가 납부한 총 수수료 금액
     */
    @Query("SELECT COALESCE(SUM(tf.feeAmount), 0) FROM TradeFee tf WHERE tf.user.id = :userId AND tf.createdAt BETWEEN :startDate AND :endDate")
    java.math.BigDecimal calculateTotalFeeByUser(
        @Param("userId") Long userId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * 특정 날짜 범위의 모든 수수료 내역 조회
     * 
     * 사용 예시:
     * - 일별 정산 시 전일 모든 수수료 내역 조회
     * - 월별 정산 시 전월 모든 수수료 내역 조회
     * 
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 해당 기간의 모든 수수료 내역
     */
    @Query("SELECT tf FROM TradeFee tf WHERE tf.createdAt BETWEEN :startDate AND :endDate ORDER BY tf.createdAt ASC")
    List<TradeFee> findByCreatedAtBetween(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}
