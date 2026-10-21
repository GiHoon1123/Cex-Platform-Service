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
 *
 * 시작 시 잔액 복원은 이 서비스의 역할이 아니다. 엔진은 시작할 때 DB를 읽으므로
 * 기존 available을 다시 delta로 전송하면 플랫폼 재시작마다 잔액이 중복 적립된다.
 * 여기서는 호출자가 명시적으로 요청한 증감만 전달하고, 생명주기 이벤트에는 연결하지 않는다.
 *
 * 현재 봇 초기화 코드에서도 참조한다. 봇은 실행 설정으로 비활성화하며,
 * 이 증감 API는 거래 ID에 의한 중복 방지를 제공하지 않으므로 실제 입출금의
 * 최종 구현이나 장애 후 자동 재시도 수단으로 사용해서는 안 된다.
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
    
}
