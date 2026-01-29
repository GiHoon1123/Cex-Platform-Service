package dustin.cex.domains.balance.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.balance.model.dto.BalanceResponse;
import dustin.cex.domains.balance.service.BalanceService;
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
 * 잔고 컨트롤러
 * Balance Controller
 * 
 * 역할:
 * - 잔고 조회 REST API 엔드포인트 제공
 * - 사용자별 잔고 정보 조회
 * 
 * API 엔드포인트:
 * - GET /api/cex/balances - 모든 잔고 조회
 * - GET /api/cex/balances/:mint - 특정 자산 잔고 조회
 */
@RestController
@RequestMapping("/api/cex/balances")
@RequiredArgsConstructor
@Tag(name = "Balances", description = "잔고 API 엔드포인트")
public class BalanceController {
    
    private final BalanceService balanceService;
    
    /**
     * 모든 잔고 조회
     * Get All Balances
     * 
     * 현재 로그인한 사용자의 모든 자산 잔고를 조회합니다.
     * 
     * 응답:
     * - 200: 잔고 목록 조회 성공
     * - 401: 인증 실패
     */
    @Operation(
            summary = "모든 잔고 조회",
            description = "현재 로그인한 사용자의 모든 자산 잔고를 조회합니다.\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "[\n" +
                         "  {\n" +
                         "    \"userId\": 1,\n" +
                         "    \"mintAddress\": \"SOL\",\n" +
                         "    \"available\": 10.0,\n" +
                         "    \"locked\": 1.0,\n" +
                         "    \"total\": 11.0\n" +
                         "  },\n" +
                         "  {\n" +
                         "    \"userId\": 1,\n" +
                         "    \"mintAddress\": \"USDT\",\n" +
                         "    \"available\": 1000.0,\n" +
                         "    \"locked\": 100.0,\n" +
                         "    \"total\": 1100.0\n" +
                         "  }\n" +
                         "]\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "잔고 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = BalanceResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            )
    })
    @GetMapping
    public ResponseEntity<List<BalanceResponse>> getAllBalances(
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        List<BalanceResponse> balances = balanceService.getAllBalances(userId);
        return ResponseEntity.ok(balances);
    }
    
    /**
     * 특정 자산 잔고 조회
     * Get Balance for Specific Asset
     * 
     * 현재 로그인한 사용자의 특정 자산 잔고를 조회합니다.
     * 
     * 경로 파라미터:
     * - mint: 자산 종류 (예: "SOL")
     * 
     * 응답:
     * - 200: 잔고 조회 성공
     * - 401: 인증 실패
     * - 404: 잔고를 찾을 수 없음
     */
    @Operation(
            summary = "특정 자산 잔고 조회",
            description = "현재 로그인한 사용자의 특정 자산 잔고를 조회합니다.\n\n" +
                         "**경로 파라미터:**\n" +
                         "- `mint` (필수): 자산 종류 (예: SOL, USDT)\n\n" +
                         "**응답 예시:**\n" +
                         "```json\n" +
                         "{\n" +
                         "  \"userId\": 1,\n" +
                         "  \"mintAddress\": \"SOL\",\n" +
                         "  \"available\": 10.0,\n" +
                         "  \"locked\": 1.0,\n" +
                         "  \"total\": 11.0\n" +
                         "}\n" +
                         "```",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "잔고 조회 성공",
                    content = @Content(schema = @Schema(implementation = BalanceResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "잔고를 찾을 수 없음"
            )
    })
    @GetMapping("/{mint}")
    public ResponseEntity<BalanceResponse> getBalance(
            @PathVariable String mint,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        BalanceResponse balance = balanceService.getBalance(userId, mint);
        
        if (balance == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        return ResponseEntity.ok(balance);
    }
}
