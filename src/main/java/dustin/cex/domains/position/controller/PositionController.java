package dustin.cex.domains.position.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.position.model.dto.PositionResponse;
import dustin.cex.domains.position.service.PositionService;
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
 * 포지션 컨트롤러
 * Position Controller
 * 
 * 역할:
 * - 포지션 조회 REST API 엔드포인트 제공
 * - 사용자별 포지션 정보 조회
 * 
 * API 엔드포인트:
 * - GET /api/cex/positions - 모든 자산 포지션 조회
 * - GET /api/cex/positions/:mint - 특정 자산 포지션 조회
 */
@RestController
@RequestMapping("/api/cex/positions")
@RequiredArgsConstructor
@Tag(name = "Positions", description = "포지션 API 엔드포인트 (평균 매수가, 손익, 수익률)")
public class PositionController {
    
    private final PositionService positionService;
    
    /**
     * 모든 자산 포지션 조회
     * Get All Positions
     * 
     * 현재 로그인한 사용자의 모든 자산 포지션을 조회합니다.
     * 포지션 수량이 0이 아닌 자산만 조회됩니다.
     * 
     * 응답:
     * - 200: 포지션 목록 조회 성공
     * - 401: 인증 실패
     */
    @Operation(
            summary = "모든 자산 포지션 조회",
            description = "현재 로그인한 사용자의 모든 자산 포지션을 조회합니다. " +
                         "포지션 수량이 0이 아닌 자산만 조회됩니다.\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "[\n" +
                         "  {\n" +
                         "    \"mint\": \"SOL\",\n" +
                         "    \"currentBalance\": \"11.0\",\n" +
                         "    \"available\": \"10.0\",\n" +
                         "    \"locked\": \"1.0\",\n" +
                         "    \"averageEntryPrice\": 100.5,\n" +
                         "    \"currentMarketPrice\": 110.0,\n" +
                         "    \"currentValue\": 1210.0,\n" +
                         "    \"unrealizedPnl\": 100.0,\n" +
                         "    \"unrealizedPnlPercent\": 10.0,\n" +
                         "    \"tradeSummary\": {\n" +
                         "      \"totalBuyTrades\": 5,\n" +
                         "      \"totalSellTrades\": 2,\n" +
                         "      \"realizedPnl\": 50.0\n" +
                         "    }\n" +
                         "  }\n" +
                         "]\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "포지션 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = PositionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            )
    })
    @GetMapping
    public ResponseEntity<List<PositionResponse>> getAllPositions(
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        List<PositionResponse> positions = positionService.getAllPositions(userId);
        return ResponseEntity.ok(positions);
    }
    
    /**
     * 특정 자산 포지션 조회
     * Get Position for Specific Asset
     * 
     * 현재 로그인한 사용자의 특정 자산 포지션을 조회합니다.
     * 
     * 경로 파라미터:
     * - mint: 자산 식별자 (예: "SOL")
     * 
     * 쿼리 파라미터:
     * - quoteMint: 기준 통화 (선택, 기본값: "USDT")
     * 
     * 응답:
     * - 200: 포지션 조회 성공
     * - 401: 인증 실패
     * - 404: 포지션을 찾을 수 없음
     */
    @Operation(
            summary = "특정 자산 포지션 조회",
            description = "현재 로그인한 사용자의 특정 자산 포지션을 조회합니다.\n\n" +
                         "**경로 파라미터:**\n" +
                         "- `mint` (필수): 자산 식별자 (예: SOL, USDC)\n\n" +
                         "**쿼리 파라미터:**\n" +
                         "- `quoteMint` (선택): 기준 통화 (기본값: USDT)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"mint\": \"SOL\",\n" +
                         "  \"currentBalance\": \"11.0\",\n" +
                         "  \"available\": \"10.0\",\n" +
                         "  \"locked\": \"1.0\",\n" +
                         "  \"averageEntryPrice\": 100.5,\n" +
                         "  \"currentMarketPrice\": 110.0,\n" +
                         "  \"currentValue\": 1210.0,\n" +
                         "  \"unrealizedPnl\": 100.0,\n" +
                         "  \"unrealizedPnlPercent\": 10.0,\n" +
                         "  \"tradeSummary\": {\n" +
                         "    \"totalBuyTrades\": 5,\n" +
                         "    \"totalSellTrades\": 2,\n" +
                         "    \"realizedPnl\": 50.0\n" +
                         "  }\n" +
                         "}\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "포지션 조회 성공",
                    content = @Content(schema = @Schema(implementation = PositionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "포지션을 찾을 수 없음"
            )
    })
    @GetMapping("/{mint}")
    public ResponseEntity<PositionResponse> getPosition(
            @PathVariable String mint,
            @RequestParam(required = false, defaultValue = "USDT") String quoteMint,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        PositionResponse position = positionService.getPosition(userId, mint, quoteMint);
        
        if (position == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        return ResponseEntity.ok(position);
    }
}
