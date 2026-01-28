package dustin.cex.domains.settlement.controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.settlement.scheduler.SettlementScheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정산 테스트 컨트롤러
 * Settlement Test Controller
 * 
 * 역할:
 * - 스냅샷 생성 기능을 수동으로 테스트하기 위한 엔드포인트
 * - 개발/테스트 환경에서만 사용
 */
@Slf4j
@RestController
@RequestMapping("/api/settlement/test")
@RequiredArgsConstructor
public class SettlementTestController {
    
    private final SettlementScheduler settlementScheduler;
    
    /**
     * 스냅샷 생성 테스트 엔드포인트
     * Test endpoint for creating snapshots
     * 
     * GET /api/settlement/test/create-snapshot?date=2025-01-28
     * GET /api/settlement/test/create-snapshot (오늘 날짜 사용)
     * 
     * @param date 스냅샷 날짜 (선택사항, 없으면 오늘 날짜 사용)
     * @return 생성 결과
     */
    @GetMapping("/create-snapshot")
    public ResponseEntity<Map<String, Object>> createSnapshot(
            @RequestParam(required = false) String date) {
        
        log.info("[SettlementTestController] 스냅샷 생성 요청: date={}", date);
        
        try {
            LocalDate snapshotDate = date != null ? LocalDate.parse(date) : null;
            int count = settlementScheduler.createDailySnapshotsManually(snapshotDate);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("snapshotDate", snapshotDate != null ? snapshotDate.toString() : LocalDate.now().toString());
            response.put("snapshotCount", count);
            response.put("message", "스냅샷 생성 완료");
            
            log.info("[SettlementTestController] 스냅샷 생성 완료: date={}, count={}", snapshotDate, count);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SettlementTestController] 스냅샷 생성 실패", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
