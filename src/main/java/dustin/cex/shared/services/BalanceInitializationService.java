package dustin.cex.shared.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 잔고 초기화 서비스
 * Balance Initialization Service
 * 
 * 역할:
 * - 서버 시작 시 user_balances 테이블의 모든 잔고를 엔진에 동기화
 * - ApplicationReadyEvent로 엔진이 준비된 후 실행
 * 
 * 처리 흐름:
 * 1. 서버 시작 완료 후 (ApplicationReadyEvent)
 * 2. user_balances 테이블에서 모든 잔고 조회
 * 3. 각 잔고를 엔진에 동기화 (available 값만)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceInitializationService {
    
    private final JdbcTemplate jdbcTemplate;
    private final BalanceSyncService balanceSyncService;
    
    /**
     * 서버 시작 완료 후 전체 잔고 동기화
     * Sync all balances after server startup
     * 
     * ApplicationReadyEvent: Spring Boot 애플리케이션이 완전히 시작된 후 실행
     * 엔진이 준비된 상태에서 실행되므로 안전하게 동기화 가능
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void syncAllBalancesOnStartup() {
        log.info("[BalanceInitializationService] 서버 시작 후 전체 잔고 동기화 시작...");
        
        try {
            // user_balances 테이블에서 모든 잔고 조회
            String sql = """
                SELECT user_id, mint_address, available, locked
                FROM user_balances
                WHERE available > 0 OR locked > 0
                ORDER BY user_id, mint_address
                """;
            
            List<Map<String, Object>> balances = jdbcTemplate.queryForList(sql);
            
            log.info("[BalanceInitializationService] 조회된 잔고 수: {}", balances.size());
            
            int successCount = 0;
            int failCount = 0;
            
            // 각 잔고를 엔진에 동기화
            for (Map<String, Object> balance : balances) {
                try {
                    Long userId = ((Number) balance.get("user_id")).longValue();
                    String mint = (String) balance.get("mint_address");
                    BigDecimal available = ((java.math.BigDecimal) balance.get("available"));
                    BigDecimal locked = ((java.math.BigDecimal) balance.get("locked"));
                    
                    // available이 있으면 동기화 (입금으로 처리)
                    if (available.compareTo(BigDecimal.ZERO) > 0) {
                        balanceSyncService.syncDeposit(userId, mint, available);
                        successCount++;
                    }
                    
                    // locked는 주문에 사용 중인 잔고이므로 동기화하지 않음
                    // (주문이 체결되거나 취소되면 자동으로 처리됨)
                    
                } catch (Exception e) {
                    log.error("[BalanceInitializationService] 잔고 동기화 실패: userId={}, mint={}, error={}", 
                            balance.get("user_id"), balance.get("mint_address"), e.getMessage());
                    failCount++;
                }
            }
            
            log.info("[BalanceInitializationService] 전체 잔고 동기화 완료: 성공={}, 실패={}", 
                    successCount, failCount);
            
        } catch (Exception e) {
            log.error("[BalanceInitializationService] 전체 잔고 동기화 중 예외 발생", e);
            // 서버 시작은 계속 진행 (경고만)
        }
    }
}
