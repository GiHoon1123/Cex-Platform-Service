package dustin.cex.domains.bot.controller;

import java.math.BigDecimal;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.bot.model.dto.CreateBotOrderRequest;
import dustin.cex.domains.bot.service.BotManagerService;
import dustin.cex.domains.bot.service.BotService;
import dustin.cex.domains.order.model.dto.OrderResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/**
 * 봇 컨트롤러
 * Bot Controller
 * 
 * 역할:
 * - 봇 주문 생성 REST API 엔드포인트 제공
 * - 봇 계정을 통한 주문 생성
 * - 주문 API 테스트용
 * 
 * 처리 흐름:
 * HTTP Request → Controller → BotService → OrderService → Response
 * 
 * 봇 계정:
 * - bot1: 매수 전용 봇 (bot1@bot.com)
 * - bot2: 매도 전용 봇 (bot2@bot.com)
 * 
 * 주의사항:
 * - 실제 운영 환경에서는 봇 계정 인증 필요
 * - 현재는 테스트용으로 간단하게 구현
 */
@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
@Tag(name = "Bot", description = "봇 주문 API 엔드포인트")
public class BotController {
    
    private final BotService botService;
    private final BotManagerService botManagerService;
    
    /**
     * 봇 주문 생성
     * Create Bot Order
     * 
     * 봇 계정을 통해 주문을 생성합니다.
     * 
     * 처리 과정:
     * 1. 봇 계정 확인 (bot1 또는 bot2)
     * 2. 주문 생성 요청 유효성 검증
     * 3. BotService를 통해 주문 생성
     *    - OrderService 호출
     *    - 주문 DB 저장
     *    - Rust 엔진에 주문 제출 (향후 구현)
     * 4. 생성된 주문 정보 반환
     * 
     * 경로 파라미터:
     * - botId: 봇 ID (1 또는 2)
     *   - 1: bot1@bot.com (매수 전용)
     *   - 2: bot2@bot.com (매도 전용)
     * 
     * 요청 본문:
     * - orderType: "buy" 또는 "sell"
     * - orderSide: "limit" 또는 "market"
     * - baseMint: 기준 자산 (예: "SOL")
     * - price: 지정가 가격 (지정가 주문만 필수)
     * - amount: 주문 수량 (시장가 매수 제외 필수)
     * - quoteAmount: 금액 기반 주문 (시장가 매수만 필수)
     * 
     * 응답:
     * - 201: 주문 생성 성공
     * - 400: 잘못된 요청
     * - 404: 봇 계정을 찾을 수 없음
     * - 500: 서버 오류
     */
    @Operation(
            summary = "봇 주문 생성",
            description = "봇 계정을 통해 주문을 생성합니다. " +
                         "bot1은 매수 전용, bot2는 매도 전용입니다.",
            security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "주문 생성 성공",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "봇 계정을 찾을 수 없음"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류"
            )
    })
    @PostMapping("/orders/{botId}")
    public ResponseEntity<OrderResponse> createBotOrder(
            @PathVariable Integer botId,
            @Valid @RequestBody CreateBotOrderRequest request
    ) {
        // 봇 계정 조회
        Long botUserId = getBotUserId(botId);
        if (botUserId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OrderResponse.builder()
                            .message("봇 계정을 찾을 수 없습니다: botId=" + botId)
                            .build());
        }
        
        // 주문 생성
        OrderResponse response = botService.createBotOrder(
                botUserId,
                request.getOrderType(),
                request.getOrderSide(),
                request.getBaseMint(),
                request.getQuoteMint(),
                request.getPrice(),
                request.getAmount(),
                request.getQuoteAmount()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * 봇 지정가 매수 주문 생성 (간편 API)
     * Create Bot Limit Buy Order (Convenience API)
     * 
     * @param botId 봇 ID (1 또는 2)
     * @param baseMint 기준 자산
     * @param price 지정가 가격
     * @param amount 주문 수량
     * @return 생성된 주문 정보
     */
    @Operation(
            summary = "봇 지정가 매수 주문 생성",
            description = "봇 계정을 통해 지정가 매수 주문을 생성합니다."
    )
    @PostMapping("/orders/{botId}/buy/limit")
    public ResponseEntity<OrderResponse> createLimitBuyOrder(
            @PathVariable Integer botId,
            @RequestParam String baseMint,
            @RequestParam BigDecimal price,
            @RequestParam BigDecimal amount
    ) {
        Long botUserId = getBotUserId(botId);
        if (botUserId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OrderResponse.builder()
                            .message("봇 계정을 찾을 수 없습니다: botId=" + botId)
                            .build());
        }
        OrderResponse response = botService.createLimitBuyOrder(botUserId, baseMint, price, amount);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * 봇 지정가 매도 주문 생성 (간편 API)
     * Create Bot Limit Sell Order (Convenience API)
     * 
     * @param botId 봇 ID (1 또는 2)
     * @param baseMint 기준 자산
     * @param price 지정가 가격
     * @param amount 주문 수량
     * @return 생성된 주문 정보
     */
    @Operation(
            summary = "봇 지정가 매도 주문 생성",
            description = "봇 계정을 통해 지정가 매도 주문을 생성합니다."
    )
    @PostMapping("/orders/{botId}/sell/limit")
    public ResponseEntity<OrderResponse> createLimitSellOrder(
            @PathVariable Integer botId,
            @RequestParam String baseMint,
            @RequestParam BigDecimal price,
            @RequestParam BigDecimal amount
    ) {
        Long botUserId = getBotUserId(botId);
        if (botUserId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OrderResponse.builder()
                            .message("봇 계정을 찾을 수 없습니다: botId=" + botId)
                            .build());
        }
        OrderResponse response = botService.createLimitSellOrder(botUserId, baseMint, price, amount);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * 봇 주문 취소
     * Cancel Bot Order
     * 
     * 봇 계정의 주문을 취소합니다.
     * 
     * @param botId 봇 ID (1 또는 2)
     * @param orderId 취소할 주문 ID
     * @return 취소된 주문 정보
     */
    @Operation(
            summary = "봇 주문 취소",
            description = "봇 계정의 주문을 취소합니다."
    )
    @DeleteMapping("/orders/{botId}/{orderId}")
    public ResponseEntity<OrderResponse> cancelBotOrder(
            @PathVariable Integer botId,
            @PathVariable Long orderId
    ) {
        Long botUserId = getBotUserId(botId);
        if (botUserId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OrderResponse.builder()
                            .message("봇 계정을 찾을 수 없습니다: botId=" + botId)
                            .build());
        }
        
        try {
            OrderResponse response = botService.cancelBotOrder(botUserId, orderId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OrderResponse.builder()
                            .message("주문 취소 실패: " + e.getMessage())
                            .build());
        }
    }
    
    /**
     * 봇 사용자 ID 가져오기
     * Get bot user ID
     * 
     * @param botId 봇 ID (1 또는 2)
     * @return 봇 사용자 ID (없으면 null)
     */
    private Long getBotUserId(Integer botId) {
        if (botId == 1) {
            return botManagerService.getBot1UserId();
        } else if (botId == 2) {
            return botManagerService.getBot2UserId();
        }
        return null;
    }
}
