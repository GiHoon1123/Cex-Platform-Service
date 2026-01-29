package dustin.cex.domains.settlement.trade.controller;

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

import dustin.cex.domains.settlement.trade.scheduler.TradeSettlementScheduler;
import dustin.cex.domains.settlement.trade.service.TradeReportService;
import dustin.cex.domains.settlement.trade.service.TradeSettlementService;
import dustin.cex.domains.settlement.trade.service.TradeSettlementValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 컨트롤러
 * Trade Settlement Controller
 * 
 * 역할:
 * - 거래 정산 리포트 조회 REST API 엔드포인트 제공
 * - 수수료 수익 조회 API 제공
 * - 거래 정산 검증 API 제공
 * 
 * 하위 도메인 분리:
 * ================
 * 이 컨트롤러는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 관련된 API를 제공합니다:
 * - 거래 정산 리포트 ✅
 * - 입출금 정산 리포트: 별도 컨트롤러 (settlement/deposit/controller) ❌
 * - 이벤트 정산 리포트: 별도 컨트롤러 (settlement/event/controller) ❌
 * 
 * API 엔드포인트:
 * - GET /api/settlement/trade/revenue/daily - 일별 수수료 수익 조회
 * - GET /api/settlement/trade/revenue/monthly - 월별 수수료 수익 조회
 * - GET /api/settlement/trade/revenue/by-pair - 거래쌍별 수수료 수익 조회
 * - POST /api/settlement/trade/validate/{date} - 정산 검증 실행
 * - GET /api/settlement/trade/validation-status/{date} - 정산 검증 상태 조회
 * - GET /api/settlement/trade/report/daily/{date} - 일별 정산 리포트 조회
 * - GET /api/settlement/trade/report/monthly/{year}/{month} - 월별 정산 리포트 조회
 * - GET /api/settlement/trade/report/user/{userId}/{date} - 사용자별 일별 정산 리포트 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/settlement/trade")
@RequiredArgsConstructor
@Tag(name = "Trade Settlement", description = "거래 정산 API 엔드포인트")
public class TradeSettlementController {
    
    private final TradeReportService tradeReportService;
    private final TradeSettlementService tradeSettlementService;
    private final TradeSettlementValidator tradeSettlementValidator;
    private final TradeSettlementScheduler tradeSettlementScheduler;
    
    /**
     * 일별 수수료 수익 조회
     * Get Daily Fee Revenue
     * 
     * GET /api/settlement/trade/revenue/daily?date=2026-01-28
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
        
        log.info("[TradeSettlementController] 일별 수수료 수익 조회 요청: date={}", date);
        
        try {
            BigDecimal revenue = tradeReportService.getDailyFeeRevenue(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", date);
            response.put("totalFeeRevenue", revenue);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 일별 수수료 수익 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 월별 수수료 수익 조회
     * Get Monthly Fee Revenue
     * 
     * GET /api/settlement/trade/revenue/monthly?year=2026&month=1
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
        
        log.info("[TradeSettlementController] 월별 수수료 수익 조회 요청: year={}, month={}", year, month);
        
        try {
            BigDecimal revenue = tradeReportService.getMonthlyFeeRevenue(year, month);
            
            Map<String, Object> response = new HashMap<>();
            response.put("year", year);
            response.put("month", month);
            response.put("totalFeeRevenue", revenue);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 월별 수수료 수익 조회 실패: year={}, month={}", year, month, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 거래쌍별 수수료 수익 조회
     * Get Fee Revenue by Trading Pair
     * 
     * GET /api/settlement/trade/revenue/by-pair?startDate=2026-01-01&endDate=2026-01-31
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
        
        log.info("[TradeSettlementController] 거래쌍별 수수료 수익 조회 요청: startDate={}, endDate={}", startDate, endDate);
        
        try {
            Map<String, BigDecimal> revenueByPair = tradeReportService.getFeeRevenueByPair(startDate, endDate);
            
            return ResponseEntity.ok(revenueByPair);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 거래쌍별 수수료 수익 조회 실패: startDate={}, endDate={}", startDate, endDate, e);
            
            Map<String, BigDecimal> errorResponse = new HashMap<>();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 정산 검증 실행
     * Execute Settlement Validation
     * 
     * POST /api/settlement/trade/validate/2026-01-28
     * 
     * @param date 검증할 날짜 (YYYY-MM-DD 형식)
     * @return 검증 결과
     */
    @Operation(
            summary = "거래 정산 검증 실행",
            description = "특정 날짜의 거래 정산 데이터에 대한 복식부기 검증을 실행합니다.\n\n" +
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
        
        log.info("[TradeSettlementController] 거래 정산 검증 실행 요청: date={}", date);
        
        try {
            TradeSettlementValidator.ValidationResult result = tradeSettlementValidator.validateDoubleEntryBookkeeping(date);
            
            // 검증 결과를 DB에 저장 (상태를 대문자로 변환)
            String errorMessage = result.getErrors().isEmpty() ? null : String.join("; ", result.getErrors());
            String finalStatus = "validated".equals(result.getStatus()) ? "VALIDATED" : "FAILED";
            tradeSettlementService.updateValidationStatus(date, finalStatus, errorMessage);
            
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
            log.error("[TradeSettlementController] 거래 정산 검증 실행 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 정산 검증 상태 조회
     * Get Settlement Validation Status
     * 
     * GET /api/settlement/trade/validation-status/2026-01-28
     * 
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 검증 상태
     */
    @Operation(
            summary = "거래 정산 검증 상태 조회",
            description = "특정 날짜의 거래 정산 데이터 검증 상태를 조회합니다.\n\n" +
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
        
        log.info("[TradeSettlementController] 거래 정산 검증 상태 조회 요청: date={}", date);
        
        try {
            dustin.cex.domains.settlement.trade.model.entity.TradeSettlement settlement = 
                    tradeSettlementService.getSettlementByDate(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", settlement.getSettlementDate());
            response.put("validationStatus", settlement.getValidationStatus());
            response.put("validationError", settlement.getValidationError());
            response.put("validatedAt", settlement.getValidatedAt());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 거래 정산 검증 상태 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 일별 정산 리포트 조회
     * Get Daily Settlement Report
     * 
     * GET /api/settlement/trade/report/daily/2026-01-28
     * 
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 일별 정산 리포트
     */
    @Operation(
            summary = "일별 거래 정산 리포트 조회",
            description = "특정 날짜의 일별 거래 정산 리포트를 조회합니다.\n\n" +
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
        
        log.info("[TradeSettlementController] 일별 거래 정산 리포트 조회 요청: date={}", date);
        
        try {
            Map<String, Object> report = tradeReportService.generateDailyReport(date);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 일별 거래 정산 리포트 조회 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 월별 정산 리포트 조회
     * Get Monthly Settlement Report
     * 
     * GET /api/settlement/trade/report/monthly/2026/1?includeDailyDetails=true
     * 
     * @param year 연도
     * @param month 월 (1-12)
     * @param includeDailyDetails 일별 상세 내역 포함 여부 (선택, 기본값: false)
     * @return 월별 정산 리포트
     */
    @Operation(
            summary = "월별 거래 정산 리포트 조회",
            description = "특정 월의 월별 거래 정산 리포트를 조회합니다.\n\n" +
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
        
        log.info("[TradeSettlementController] 월별 거래 정산 리포트 조회 요청: year={}, month={}, includeDailyDetails={}", 
                year, month, includeDailyDetails);
        
        try {
            Map<String, Object> report = tradeReportService.generateMonthlyReport(year, month, includeDailyDetails);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 월별 거래 정산 리포트 조회 실패: year={}, month={}", year, month, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 사용자별 일별 정산 리포트 조회
     * Get User Daily Settlement Report
     * 
     * GET /api/settlement/trade/report/user/1/2026-01-28
     * 
     * @param userId 사용자 ID
     * @param date 날짜 (YYYY-MM-DD 형식)
     * @return 사용자별 일별 정산 리포트
     */
    @Operation(
            summary = "사용자별 일별 거래 정산 리포트 조회",
            description = "특정 사용자의 특정 날짜 일별 거래 정산 리포트를 조회합니다.\n\n" +
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
        
        log.info("[TradeSettlementController] 사용자별 일별 거래 정산 리포트 조회 요청: userId={}, date={}", userId, date);
        
        try {
            Map<String, Object> report = tradeReportService.generateUserDailyReport(userId, date);
            
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 사용자별 일별 거래 정산 리포트 조회 실패: userId={}, date={}", userId, date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * 수동 일별 정산 실행 (테스트용)
     * Manual Daily Settlement Execution (for Testing)
     * 
     * POST /api/settlement/trade/manual/daily?date=2026-01-30
     * 
     * @param date 정산할 날짜 (전일 데이터 정산)
     * @return 정산 결과
     */
    @Operation(
            summary = "수동 일별 정산 실행",
            description = "특정 날짜의 일별 정산을 수동으로 실행합니다. (테스트용)"
    )
    @PostMapping("/manual/daily")
    public ResponseEntity<Map<String, Object>> manualDailySettlement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("[TradeSettlementController] 수동 일별 정산 실행 요청: date={}", date);
        
        try {
            // 정산 실행 (서비스를 직접 호출)
            tradeSettlementService.createDailySettlement(date);
            
            // 정산 결과 조회
            dustin.cex.domains.settlement.trade.model.entity.TradeSettlement settlement = tradeSettlementService.getSettlementByDate(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("date", date);
            response.put("settlementId", settlement.getId());
            response.put("validationStatus", settlement.getValidationStatus());
            response.put("codeVersion", settlement.getCodeVersion());
            response.put("policyVersion", settlement.getPolicyVersion());
            response.put("totalTrades", settlement.getTotalTrades());
            response.put("totalVolume", settlement.getTotalVolume());
            response.put("totalFeeRevenue", settlement.getTotalFeeRevenue());
            response.put("message", "정산이 성공적으로 실행되었습니다.");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[TradeSettlementController] 수동 일별 정산 실행 실패: date={}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * 정산 상세 정보 조회 (감사 로그, 아이템 포함)
     * Get Settlement Details (with Audit Logs and Items)
     * 
     * GET /api/settlement/trade/details/{date}
     */
    @GetMapping("/details/{date}")
    public ResponseEntity<Map<String, Object>> getSettlementDetails(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        try {
            dustin.cex.domains.settlement.trade.model.entity.TradeSettlement settlement = tradeSettlementService.getSettlementByDate(date);
            
            Map<String, Object> response = new HashMap<>();
            response.put("settlementId", settlement.getId());
            response.put("settlementDate", settlement.getSettlementDate());
            response.put("settlementType", settlement.getSettlementType());
            response.put("validationStatus", settlement.getValidationStatus());
            response.put("codeVersion", settlement.getCodeVersion());
            response.put("policyVersion", settlement.getPolicyVersion());
            response.put("totalTrades", settlement.getTotalTrades());
            response.put("totalVolume", settlement.getTotalVolume());
            response.put("totalFeeRevenue", settlement.getTotalFeeRevenue());
            response.put("totalUsers", settlement.getTotalUsers());
            response.put("createdAt", settlement.getCreatedAt());
            response.put("validatedAt", settlement.getValidatedAt());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
}
