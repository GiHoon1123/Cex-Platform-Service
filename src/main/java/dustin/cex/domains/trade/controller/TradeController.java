package dustin.cex.domains.trade.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.trade.model.dto.TradeResponse;
import dustin.cex.domains.trade.service.TradeService;
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
     * - limit: 최대 조회 개수 (선택, 기본값: 100)
     * 
     * 응답:
     * - 200: 체결 내역 조회 성공
     * - 400: 잘못된 요청 (baseMint 없음)
     */
    @Operation(
            summary = "거래쌍별 체결 내역 조회",
            description = "특정 거래쌍의 최근 체결 내역을 조회합니다. 최신 체결 내역부터 정렬됩니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `baseMint` (필수): 기준 자산 (예: SOL, USDC)\n" +
                         "- `quoteMint` (선택): 기준 통화 (기본값: USDT)\n" +
                         "- `limit` (선택): 최대 조회 개수 (기본값: 100)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "[\n" +
                         "  {\n" +
                         "    \"id\": 456,\n" +
                         "    \"buyOrderId\": 123,\n" +
                         "    \"sellOrderId\": 124,\n" +
                         "    \"buyerId\": 1,\n" +
                         "    \"sellerId\": 2,\n" +
                         "    \"baseMint\": \"SOL\",\n" +
                         "    \"quoteMint\": \"USDT\",\n" +
                         "    \"price\": 100.5,\n" +
                         "    \"amount\": 1.0,\n" +
                         "    \"createdAt\": \"2026-01-29T10:30:00\"\n" +
                         "  }\n" +
                         "]\n" +
                         "```"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "체결 내역 조회 성공",
                    content = @Content(schema = @Schema(implementation = TradeResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (baseMint가 없음)"
            )
    })
    @GetMapping
    public ResponseEntity<List<TradeResponse>> getTrades(
            @RequestParam String baseMint,
            @RequestParam(required = false, defaultValue = "USDT") String quoteMint,
            @RequestParam(required = false) Integer limit
    ) {
        List<TradeResponse> trades = tradeService.getTrades(baseMint, quoteMint, limit);
        return ResponseEntity.ok(trades);
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
     * - limit: 최대 조회 개수 (선택, 기본값: 100)
     * - offset: 페이지네이션 오프셋 (선택, 기본값: 0)
     * 
     * 응답:
     * - 200: 체결 내역 조회 성공
     * - 401: 인증 실패
     */
    @Operation(
            summary = "내 체결 내역 조회",
            description = "현재 로그인한 사용자가 참여한 모든 체결 내역을 조회합니다. " +
                         "매수자 또는 매도자로 참여한 모든 거래가 포함됩니다.\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `mint` (선택): 자산 식별자 (예: SOL, 특정 자산만 필터링)\n" +
                         "- `limit` (선택): 최대 조회 개수 (기본값: 100)\n" +
                         "- `offset` (선택): 페이지네이션 오프셋 (기본값: 0)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "[\n" +
                         "  {\n" +
                         "    \"id\": 456,\n" +
                         "    \"buyOrderId\": 123,\n" +
                         "    \"sellOrderId\": 124,\n" +
                         "    \"buyerId\": 1,\n" +
                         "    \"sellerId\": 2,\n" +
                         "    \"baseMint\": \"SOL\",\n" +
                         "    \"quoteMint\": \"USDT\",\n" +
                         "    \"price\": 100.5,\n" +
                         "    \"amount\": 1.0,\n" +
                         "    \"createdAt\": \"2026-01-29T10:30:00\"\n" +
                         "  }\n" +
                         "]\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "체결 내역 조회 성공",
                    content = @Content(schema = @Schema(implementation = TradeResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            )
    })
    @GetMapping("/my")
    public ResponseEntity<List<TradeResponse>> getMyTrades(
            @RequestParam(required = false) String mint,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        List<TradeResponse> trades = tradeService.getMyTrades(userId, mint, limit, offset);
        return ResponseEntity.ok(trades);
    }
}
