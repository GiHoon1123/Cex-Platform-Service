// =====================================================
// Executor - 체결 실행 엔진
// =====================================================
// 역할: Matcher가 반환한 MatchResult를 받아서 실제 체결 처리
// 
// 핵심 책임:
// 1. 잔고 업데이트 (메모리)
// 2. DB/WAL 제외 (벤치마크용)
//
// 처리 흐름:
// MatchResult → 잔고 업데이트
// 
// 성능:
// - 잔고 이체: O(1) (HashMap 조회/업데이트)
// - Rust와 유사한 성능 (단순 HashMap 연산)
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 체결 실행 엔진
 * 
 * 구성 요소:
 * - balanceCache: 메모리 잔고 관리
 * 
 * 벤치마크 모드:
 * - DB 저장 없음: 메모리만 사용
 * - WAL 쓰기 없음: 디스크 I/O 제외
 */
public class Executor {
    /**
     * 메모리 잔고 캐시
     */
    private final BalanceCache balanceCache;
    
    /**
     * 새 Executor 생성
     */
    public Executor() {
        this.balanceCache = new BalanceCache();
    }
    
    /**
     * 새 Executor 생성 (용량 지정)
     * 
     * @param capacity 초기 용량
     */
    public Executor(int capacity) {
        this.balanceCache = new BalanceCache(capacity);
    }
    
    /**
     * 체결 실행
     * 
     * @param matchResult Matcher가 생성한 매칭 결과
     * @throws IllegalArgumentException 잔고 부족 시
     * 
     * 처리 과정:
     * 1. 잔고 확인 (locked 확인)
     * 2. 잔고 이체 (locked → available)
     * 
     * 안전성:
     * - 잔고 부족 시 에러 반환 (체결 취소)
     * 
     * 성능:
     * - 잔고 이체: O(1) (HashMap 조회/업데이트)
     */
    /**
     * BigDecimal 정규화 (소수점 이하 18자리로 제한, 금융 계산용)
     */
    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        // 소수점 이하 18자리로 제한, HALF_UP 반올림
        return value.setScale(18, RoundingMode.HALF_UP);
    }
    
    public void executeTrade(MatchResult matchResult) {
        // 계산: 총 거래 금액 (정규화하여 정밀도 문제 방지)
        BigDecimal totalValue = normalize(matchResult.getPrice().multiply(matchResult.getAmount()));
        
        // 1. 매수자: USDT 이체 (locked → 매도자 available)
        balanceCache.transfer(
            matchResult.getBuyerId(),
            matchResult.getSellerId(),
            matchResult.getQuoteMint(),  // USDT
            totalValue,
            true  // locked에서 차감
        );
        
        // 2. 매도자: 기준 자산 이체 (locked → 매수자 available)
        balanceCache.transfer(
            matchResult.getSellerId(),
            matchResult.getBuyerId(),
            matchResult.getBaseMint(),  // SOL 등
            normalize(matchResult.getAmount()),
            true  // locked에서 차감
        );
    }
    
    /**
     * 잔고 캐시 접근
     * 
     * @return 잔고 캐시
     */
    public BalanceCache getBalanceCache() {
        return balanceCache;
    }
}

