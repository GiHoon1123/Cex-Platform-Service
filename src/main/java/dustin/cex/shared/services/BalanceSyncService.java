package dustin.cex.shared.services;

import dustin.cex.shared.http.EngineHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 잔고 동기화 서비스
 * Balance Synchronization Service
 * 
 * 역할:
 * - Java API 서버의 잔고 업데이트를 엔진에 동기화
 * - 입금/출금 시 엔진 메모리 잔고 동기화
 * 
 * 사용 시나리오:
 * 1. 입금 발생 시: syncBalance(userId, mint, +amount)
 * 2. 출금 발생 시: syncBalance(userId, mint, -amount)
 * 3. 서버 시작 시: syncAllBalances() (향후 구현)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceSyncService {
    
    private final EngineHttpClient engineHttpClient;
    
    /**
     * 잔고 동기화
     * Sync balance to engine
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류 (예: "SOL", "USDT")
     * @param availableDelta available 증감량 (양수: 입금, 음수: 출금)
     */
    public void syncBalance(Long userId, String mint, BigDecimal availableDelta) {
        try {
            log.info("[BalanceSyncService] 잔고 동기화 시작: userId={}, mint={}, delta={}", 
                    userId, mint, availableDelta);
            
            boolean success = engineHttpClient.syncBalance(userId, mint, availableDelta);
            
            if (success) {
                log.info("[BalanceSyncService] 잔고 동기화 성공: userId={}, mint={}, delta={}", 
                        userId, mint, availableDelta);
            } else {
                log.error("[BalanceSyncService] 잔고 동기화 실패: userId={}, mint={}, delta={}", 
                        userId, mint, availableDelta);
                throw new RuntimeException("잔고 동기화 실패");
            }
        } catch (Exception e) {
            log.error("[BalanceSyncService] 잔고 동기화 중 예외 발생: userId={}, mint={}, delta={}, error={}", 
                    userId, mint, availableDelta, e.getMessage(), e);
            throw new RuntimeException("잔고 동기화 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 입금 동기화
     * Sync deposit to engine
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param amount 입금 금액 (양수)
     */
    public void syncDeposit(Long userId, String mint, BigDecimal amount) {
        syncBalance(userId, mint, amount);
    }
    
    /**
     * 출금 동기화
     * Sync withdrawal to engine
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param amount 출금 금액 (양수, 내부에서 음수로 변환)
     */
    public void syncWithdrawal(Long userId, String mint, BigDecimal amount) {
        syncBalance(userId, mint, amount.negate());
    }
    
    /**
     * 서버 시작 시 전체 잔고 동기화
     * Sync all balances on server startup
     * 
     * @deprecated BalanceInitializationService에서 처리하도록 변경됨
     * 이 메서드는 호환성을 위해 유지하지만 실제로는 사용되지 않음
     */
    @Deprecated
    public void syncAllBalances() {
        log.warn("[BalanceSyncService] syncAllBalances()는 더 이상 사용되지 않습니다. BalanceInitializationService를 사용하세요.");
    }
}
