package dustin.cex.domains.settlement.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.settlement.service.SettlementReportService;
import dustin.cex.domains.settlement.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정산 컨트롤러
 * Settlement Controller
 * 
 * 역할:
 * - 정산 리포트 조회 REST API 엔드포인트 제공
 * - 수수료 수익 조회 API 제공
 * - 정산 검증 API 제공
 * 
 * API 엔드포인트:
 * - GET /api/settlement/revenue/daily - 일별 수수료 수익 조회
 * - GET /api/settlement/revenue/monthly - 월별 수수료 수익 조회
 * - GET /api/settlement/revenue/by-pair - 거래쌍별 수수료 수익 조회
 * - POST /api/settlement/validate/{date} - 정산 검증 실행
 * - GET /api/settlement/validation-status/{date} - 정산 검증 상태 조회
 * - GET /api/settlement/report/daily/{date} - 일별 정산 리포트 조회
 * - GET /api/settlement/report/monthly/{year}/{month} - 월별 정산 리포트 조회
 * - GET /api/settlement/report/user/{userId}/{date} - 사용자별 일별 정산 리포트 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/settlement")
@RequiredArgsConstructor
@Tag(name = "Settlement", description = "정산 API 엔드포인트")
public class SettlementController {
    
    private final SettlementReportService settlementReportService;
    private final SettlementService settlementService;
    
    /**
     * 일별 수수료 수익 조회
     * Get Daily Fee Revenue
     * 
     * GET /api/settlement/revenue/daily?date=2026-01-28
     * 
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 일별 총 수수료 수익 (USDT)
     */
    @Operation(
            summary = "일별 수수료 수익 조회",
            description = "특정 날짜의 일별 총 수수료 수익을 조회합니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `date` (필수): 날짜 (YYYY-MM-DD 형식, 예: 2026-01-28)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"date\": \"2026-01-28\",\n" +
                         "  \"totalFeeRevenue\": 200.5\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/revenue/daily")
    public ResponseEntity<Map<String, Object>> getDailyFeeRevenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[SettlementController] 일별 수수료 수익 조회 요청: date={}", date);
        
        try {
            BigDecimal revenue = settlementReportService.getDailyFeeRevenue(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", date);
            response.put("totalFeeRevenue", revenue);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SettlementController] 일별 수수료 수익 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 월별 수수료 수익 조회
     * Get Monthly Fee Revenue
     * 
     * GET /api/settlement/revenue/monthly?year=2026&month=1
     * 
     * @param year 연도
     * @param month 월 (1-12)
     * @return 월별 총 수수료 수익 (USDT)
     */
    @Operation(
            summary = "월별 수수료 수익 조회",
            description = "특정 월의 월별 총 수수료 수익을 조회합니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `year` (필수): 연도 (예: 2026)\n" +
                         "- `month` (필수): 월 (1-12, 예: 1)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"year\": 2026,\n" +
                         "  \"month\": 1,\n" +
                         "  \"totalFeeRevenue\": 6000.0\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/revenue/monthly")
    public ResponseEntity<Map<String, Object>> getMonthlyFeeRevenue(
            @RequestParam int year,
            @RequestParam int month) {
        
        log.info("[SettlementController] 월별 수수료 수익 조회 요청: year={}, month={}", year, month);
        
        try {
            BigDecimal revenue = settlementReportService.getMonthlyFeeRevenue(year, month);
            
            Map<String, Object> response = new HashMap<>();
            response.put("year", year);
            response.put("month", month);
            response.put("totalFeeRevenue", revenue);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SettlementController] 월별 수수료 수익 조회 실패: year={}, month={}", year, month, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 거래쌍별 수수료 수익 조회
     * Get Fee Revenue by Trading Pair
     * 
     * GET /api/settlement/revenue/by-pair?startDate=2026-01-01&endDate=2026-01-31
     * 
     * @param startDate 시작 날짜 (YYYY-MM-DD 형식)
     * @param endDate 종료 날짜 (YYYY-MM-DD 형식)
     * @return 거래쌍별 수수료 수익 맵
     */
    @Operation(
            summary = "거래쌍별 수수료 수익 조회",
            description = "특정 기간 동안의 거래쌍별 수수료 수익을 조회합니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `startDate` (필수): 시작 날짜 (YYYY-MM-DD 형식)\n" +
                         "- `endDate` (필수): 종료 날짜 (YYYY-MM-DD 형식)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"SOL\": 1000.5,\n" +
                         "  \"BTC\": 500.2,\n" +
                         "  \"ALL\": 1500.7\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content)
    })
    @GetMapping("/revenue/by-pair")
    public ResponseEntity<Map<String, BigDecimal>> getFeeRevenueByPair(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        log.info("[SettlementController] 거래쌍별 수수료 수익 조회 요청: startDate={}, endDate={}", startDate, endDate);
        
        try {
            Map<String, BigDecimal> revenueByPair = settlementReportService.getFeeRevenueByPair(startDate, endDate);
            
            return ResponseEntity.ok(revenueByPair);
        } catch (Exception e) {
            log.error("[SettlementController] 거래쌍별 수수료 수익 조회 실패: startDate={}, endDate={}", startDate, endDate, e);
            
            Map<String, BigDecimal> errorResponse = new HashMap<>();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 정산 검증 실행
     * Execute Settlement Validation
     * 
     * POST /api/settlement/validate/2026-01-28
     * 
     * @param date 검증할 날짜 (YYYY-MM-DD 형식)
     * @return 검증 결과
     */
    @Operation(
            summary = "정산 검증 실행",
            description = "특정 날짜의 정산 데이터에 대한 복식부기 검증을 실행합니다.\n\n" +
                         "**경로 변수:**\n" +
                         "- `date` (필수): 검증할 날짜 (YYYY-MM-DD 형식)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"date\": \"2026-01-28\",\n" +
                         "  \"status\": \"validated\",\n" +
                         "  \"totalTrades\": 1000,\n" +
                         "  \"totalTradeVolume\": 1000000.0,\n" +
                         "  \"totalFeeRevenue\": 200.0,\n" +
                         "  \"errors\": []\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 완료"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content)
    })
    @PostMapping("/validate/{date}")
    public ResponseEntity<Map<String, Object>> validateSettlement(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[SettlementController] 정산 검증 실행 요청: date={}", date);
        
        try {
            SettlementService.ValidationResult result = settlementService.validateDoubleEntryBookkeeping(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", result.getDate());
            response.put("status", result.getStatus());
            response.put("totalTrades", result.getTotalTrades());
            response.put("totalTradeVolume", result.getTotalTradeVolume());
            response.put("totalFeeRevenue", result.getTotalFeeRevenue());
            response.put("totalUserBalances", result.getTotalUserBalances());
            response.put("errors", result.getErrors());
            response.put("startTime", result.getStartTime());
            response.put("endTime", result.getEndTime());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SettlementController] 정산 검증 실행 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 정산 검증 상태 조회
     * Get Settlement Validation Status
     * 
     * GET /api/settlement/validation-status/2026-01-28
     * 
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 검증 상태
     */
    @Operation(
            summary = "정산 검증 상태 조회",
            description = "특정 날짜의 정산 데이터 검증 상태를 조회합니다.\n\n" +
                         "**경로 변수:**\n" +
                         "- `date` (필수): 날짜 (YYYY-MM-DD 형식)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"date\": \"2026-01-28\",\n" +
                         "  \"validationStatus\": \"validated\",\n" +
                         "  \"validationError\": null,\n" +
                         "  \"validatedAt\": \"2026-01-29T00:05:00\"\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/validation-status/{date}")
    public ResponseEntity<Map<String, Object>> getValidationStatus(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[SettlementController] 정산 검증 상태 조회 요청: date={}", date);
        
        try {
            dustin.cex.domains.settlement.model.entity.Settlement settlement = 
                    settlementService.getSettlementByDate(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", settlement.getSettlementDate());
            response.put("validationStatus", settlement.getValidationStatus());
            response.put("validationError", settlement.getValidationError());
            response.put("validatedAt", settlement.getValidatedAt());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SettlementController] 정산 검증 상태 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 일별 정산 리포트 조회
     * Get Daily Settlement Report
     * 
     * GET /api/settlement/report/daily/2026-01-28
     * 
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 일별 정산 리포트
     */
    @Operation(
            summary = "일별 정산 리포트 조회",
            description = "특정 날짜의 일별 정산 리포트를 조회합니다.\n\n" +
                         "**경로 변수:**\n" +
                         "- `date` (필수): 날짜 (YYYY-MM-DD 형식)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"date\": \"2026-01-28\",\n" +
                         "  \"settlementType\": \"daily\",\n" +
                         "  \"totalTrades\": 1000,\n" +
                         "  \"totalVolume\": 1000000.0,\n" +
                         "  \"totalFeeRevenue\": 200.0,\n" +
                         "  \"totalUsers\": 100,\n" +
                         "  \"validationStatus\": \"validated\",\n" +
                         "  \"validationError\": null\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/report/daily/{date}")
    public ResponseEntity<Map<String, Object>> getDailyReport(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[SettlementController] 일별 정산 리포트 조회 요청: date={}", date);
        
        try {
            Map<String, Object> report = settlementReportService.generateDailyReport(date);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[SettlementController] 일별 정산 리포트 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 월별 정산 리포트 조회
     * Get Monthly Settlement Report
     * 
     * GET /api/settlement/report/monthly/2026/1?includeDailyDetails=true
     * 
     * @param year 연도
     * @param month 월 (1-12)
     * @param includeDailyDetails 일별 상세 내역 포함 여부 (선택, 기본값: false)
     * @return 월별 정산 리포트
     */
    @Operation(
            summary = "월별 정산 리포트 조회",
            description = "특정 월의 월별 정산 리포트를 조회합니다.\n\n" +
                         "**경로 변수:**\n" +
                         "- `year` (필수): 연도 (예: 2026)\n" +
                         "- `month` (필수): 월 (1-12, 예: 1)\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `includeDailyDetails` (선택): 일별 상세 내역 포함 여부 (기본값: false)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"year\": 2026,\n" +
                         "  \"month\": 1,\n" +
                         "  \"totalTrades\": 30000,\n" +
                         "  \"totalVolume\": 30000000.0,\n" +
                         "  \"totalFeeRevenue\": 6000.0,\n" +
                         "  \"totalUsers\": 500,\n" +
                         "  \"validationStatus\": \"validated\"\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/report/monthly/{year}/{month}")
    public ResponseEntity<Map<String, Object>> getMonthlyReport(
            @PathVariable int year,
            @PathVariable int month,
            @RequestParam(required = false, defaultValue = "false") boolean includeDailyDetails) {
        
        log.info("[SettlementController] 월별 정산 리포트 조회 요청: year={}, month={}, includeDailyDetails={}", 
                year, month, includeDailyDetails);
        
        try {
            Map<String, Object> report = settlementReportService.generateMonthlyReport(year, month, includeDailyDetails);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[SettlementController] 월별 정산 리포트 조회 실패: year={}, month={}", year, month, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 사용자별 일별 정산 리포트 조회
     * Get User Daily Settlement Report
     * 
     * GET /api/settlement/report/user/1/2026-01-28
     * 
     * @param userId 사용자 ID
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 사용자별 일별 정산 리포트
     */
    @Operation(
            summary = "사용자별 일별 정산 리포트 조회",
            description = "특정 사용자의 특정 날짜 일별 정산 리포트를 조회합니다.\n\n" +
                         "**경로 변수:**\n" +
                         "- `userId` (필수): 사용자 ID\n" +
                         "- `date` (필수): 날짜 (YYYY-MM-DD 형식)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"userId\": 1,\n" +
                         "  \"date\": \"2026-01-28\",\n" +
                         "  \"totalTrades\": 10,\n" +
                         "  \"totalVolume\": 10000.0,\n" +
                         "  \"totalFeesPaid\": 2.0\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산 데이터 없음", content = @Content)
    })
    @GetMapping("/report/user/{userId}/{date}")
    public ResponseEntity<Map<String, Object>> getUserDailyReport(
            @PathVariable Long userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[SettlementController] 사용자별 일별 정산 리포트 조회 요청: userId={}, date={}", userId, date);
        
        try {
            Map<String, Object> report = settlementReportService.generateUserDailyReport(userId, date);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[SettlementController] 사용자별 일별 정산 리포트 조회 실패: userId={}, date={}", userId, date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
}
