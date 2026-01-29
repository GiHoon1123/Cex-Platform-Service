package dustin.cex.domains.trade.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.trade.model.dto.TradeResponse;
import dustin.cex.domains.trade.service.TradeService;
import dustin.cex.shared.model.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 체결 내역 컨트롤러
 * Trade Controller
 * 
 * 역할:
 * - 체결 내역 조회 REST API 엔드포인트 제공
 * - 거래쌍별 체결 내역 조회
 * - 사용자별 체결 내역 조회
 * 
 * API 엔드포인트:
 * - GET /api/cex/trades - 거래쌍별 체결 내역 조회
 * - GET /api/cex/trades/my - 내 체결 내역 조회
 */
@RestController
@RequestMapping("/api/cex/trades")
@RequiredArgsConstructor
@Tag(name = "Trades", description = "체결 내역 API 엔드포인트")
public class TradeController {
    
    private final TradeService tradeService;
    
    /**
     * 거래쌍별 체결 내역 조회
     * Get Trades by Trading Pair
     * 
     * 특정 거래쌍의 최근 체결 내역을 조회합니다.
     * 
     * 쿼리 파라미터:
     * - baseMint: 기준 자산 (필수, 예: "SOL")
     * - quoteMint: 기준 통화 (선택, 기본값: "USDT")
     * - page: 페이지 번호 (선택, 기본값: 0)
     * - size: 페이지 크기 (선택, 기본값: 100)
     * - sort: 정렬 기준 (선택, 예: createdAt,desc)
     * 
     * 응답:
     * - 200: 체결 내역 조회 성공 (페이징 정보 포함)
     * - 400: 잘못된 요청 (baseMint 없음)
     */
    @Operation(
            summary = "거래쌍별 체결 내역 조회",
            description = "특정 거래쌍의 최근 체결 내역을 조회합니다. 최신 체결 내역부터 정렬됩니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `baseMint` (필수): 기준 자산 (예: SOL, USDC)\n" +
                         "- `quoteMint` (선택): 기준 통화 (기본값: USDT)\n" +
                         "- `page` (선택): 페이지 번호 (0부터 시작, 기본값: 0)\n" +
                         "- `size` (선택): 페이지 크기 (기본값: 100)\n" +
                         "- `sort` (선택): 정렬 기준 (예: createdAt,desc 또는 createdAt,asc, 기본값: createdAt,desc)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"content\": [\n" +
                         "    {\n" +
                         "      \"id\": 456,\n" +
                         "      \"buyOrderId\": 123,\n" +
                         "      \"sellOrderId\": 124,\n" +
                         "      \"buyerId\": 1,\n" +
                         "      \"sellerId\": 2,\n" +
                         "      \"baseMint\": \"SOL\",\n" +
                         "      \"quoteMint\": \"USDT\",\n" +
                         "      \"price\": 100.5,\n" +
                         "      \"amount\": 1.0,\n" +
                         "      \"createdAt\": \"2026-01-29T10:30:00\"\n" +
                         "    }\n" +
                         "  ],\n" +
                         "  \"page\": 0,\n" +
                         "  \"size\": 100,\n" +
                         "  \"totalElements\": 500,\n" +
                         "  \"totalPages\": 5,\n" +
                         "  \"first\": true,\n" +
                         "  \"last\": false,\n" +
                         "  \"empty\": false\n" +
                         "}\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "체결 내역 조회 성공",
                    content = @Content(schema = @Schema(implementation = PageResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (baseMint가 없음)"
            )
    })
    @GetMapping
    public ResponseEntity<PageResponse<TradeResponse>> getTrades(
            @RequestParam String baseMint,
            @RequestParam(required = false, defaultValue = "USDT") String quoteMint,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "100") Integer size,
            @RequestParam(required = false, defaultValue = "createdAt,desc") String sort
    ) {
        Sort sortObj = parseSort(sort);
        Pageable pageable = PageRequest.of(page, size, sortObj);
        
        Page<TradeResponse> trades = tradeService.getTrades(baseMint, quoteMint, pageable);
        PageResponse<TradeResponse> response = PageResponse.of(trades, trades.getContent());
        return ResponseEntity.ok(response);
    }
    
    /**
     * 내 체결 내역 조회
     * Get My Trades
     * 
     * 현재 로그인한 사용자가 참여한 모든 체결 내역을 조회합니다.
     * 특정 자산(mint)을 지정하면 해당 자산의 거래 내역만 필터링합니다.
     * 
     * 쿼리 파라미터:
     * - mint: 자산 식별자 (선택, 특정 자산만 필터링)
     * - page: 페이지 번호 (선택, 기본값: 0)
     * - size: 페이지 크기 (선택, 기본값: 100)
     * - sort: 정렬 기준 (선택, 예: createdAt,desc)
     * 
     * 응답:
     * - 200: 체결 내역 조회 성공 (페이징 정보 포함)
     * - 401: 인증 실패
     */
    @Operation(
            summary = "내 체결 내역 조회",
            description = "현재 로그인한 사용자가 참여한 모든 체결 내역을 조회합니다. " +
                         "매수자 또는 매도자로 참여한 모든 거래가 포함됩니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `mint` (선택): 자산 식별자 (예: SOL, 특정 자산만 필터링)\n" +
                         "- `page` (선택): 페이지 번호 (0부터 시작, 기본값: 0)\n" +
                         "- `size` (선택): 페이지 크기 (기본값: 100)\n" +
                         "- `sort` (선택): 정렬 기준 (예: createdAt,desc 또는 createdAt,asc, 기본값: createdAt,desc)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"content\": [\n" +
                         "    {\n" +
                         "      \"id\": 456,\n" +
                         "      \"buyOrderId\": 123,\n" +
                         "      \"sellOrderId\": 124,\n" +
                         "      \"buyerId\": 1,\n" +
                         "      \"sellerId\": 2,\n" +
                         "      \"baseMint\": \"SOL\",\n" +
                         "      \"quoteMint\": \"USDT\",\n" +
                         "      \"price\": 100.5,\n" +
                         "      \"amount\": 1.0,\n" +
                         "      \"createdAt\": \"2026-01-29T10:30:00\"\n" +
                         "    }\n" +
                         "  ],\n" +
                         "  \"page\": 0,\n" +
                         "  \"size\": 100,\n" +
                         "  \"totalElements\": 250,\n" +
                         "  \"totalPages\": 3,\n" +
                         "  \"first\": true,\n" +
                         "  \"last\": false,\n" +
                         "  \"empty\": false\n" +
                         "}\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "체결 내역 조회 성공",
                    content = @Content(schema = @Schema(implementation = PageResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            )
    })
    @GetMapping("/my")
    public ResponseEntity<PageResponse<TradeResponse>> getMyTrades(
            @RequestParam(required = false) String mint,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "100") Integer size,
            @RequestParam(required = false, defaultValue = "createdAt,desc") String sort,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        Sort sortObj = parseSort(sort);
        Pageable pageable = PageRequest.of(page, size, sortObj);
        
        Page<TradeResponse> trades = tradeService.getMyTrades(userId, mint, pageable);
        PageResponse<TradeResponse> response = PageResponse.of(trades, trades.getContent());
        return ResponseEntity.ok(response);
    }
    
    /**
     * 정렬 문자열을 Sort 객체로 변환
     * Parse sort string to Sort object
     * 
     * @param sort 정렬 문자열 (예: "createdAt,desc" 또는 "createdAt,asc")
     * @return Sort 객체
     */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        
        return Sort.by(direction, property);
    }
}
