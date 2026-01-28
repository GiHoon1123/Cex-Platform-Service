package dustin.cex.domains.order.controller;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/**
 * 주문 컨트롤러
 * Order Controller
 * 
 * 역할:
 * - 주문 관련 REST API 엔드포인트 제공
 * - 주문 생성, 조회, 취소 등의 HTTP 요청 처리
 * - JWT 인증 및 사용자 정보 추출
 * - Swagger 문서화
 * 
 * 처리 흐름:
 * HTTP Request → Controller → Service → Repository/Engine → Response
 * 
 * 인증:
 * - 모든 엔드포인트는 JWT 토큰 필요 (Bearer token)
 * - JWT 필터에서 사용자 정보 추출 후 Request Attribute에 저장
 * - Controller에서 userId 추출하여 사용
 * 
 * API 엔드포인트:
 * - POST /api/cex/orders - 주문 생성
 * - GET /api/cex/orders/{orderId} - 주문 조회
 * - GET /api/cex/orders/my - 내 주문 목록 조회
 * - DELETE /api/cex/orders/{orderId} - 주문 취소
 */
@RestController
@RequestMapping("/api/cex/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "주문 API 엔드포인트 (주문 생성, 조회, 취소)")
public class OrderController {
    
    private final OrderService orderService;
    
    /**
     * 주문 생성
     * Create Order
     * 
     * 새로운 주문을 생성하고 Rust 엔진에 제출합니다.
     * 
     * 처리 과정:
     * 1. JWT 토큰에서 사용자 ID 추출
     * 2. 주문 생성 요청 유효성 검증 (@Valid)
     * 3. OrderService를 통해 주문 생성
     *    - 주문 DB 저장
     *    - Rust 엔진에 주문 제출 (gRPC, 동기)
     *    - Kafka 이벤트 발행 (비동기, 로깅용)
     * 4. 생성된 주문 정보 반환
     * 
     * 인증:
     * - JWT 토큰 필요 (Bearer token)
     * - JWT 필터에서 사용자 정보 추출
     * 
     * 요청 본문:
     * - orderType: "buy" 또는 "sell" (필수)
     * - orderSide: "limit" 또는 "market" (필수)
     * - baseMint: 기준 자산 (필수, 예: "SOL")
     * - quoteMint: 기준 통화 (선택, 기본값: "USDT")
     * - price: 지정가 가격 (지정가 주문만 필수)
     * - amount: 주문 수량 (시장가 매수 제외 필수)
     * - quoteAmount: 금액 기반 주문 (시장가 매수만 필수)
     * 
     * 응답:
     * - 201: 주문 생성 성공
     * - 400: 잘못된 요청 (유효성 검증 실패)
     * - 401: 인증 실패
     * - 500: 서버 오류 (엔진 통신 실패 등)
     * 
     * 예시:
     * - 지정가 매수: {"orderType":"buy","orderSide":"limit","baseMint":"SOL","price":"100.0","amount":"1.0"}
     * - 시장가 매수: {"orderType":"buy","orderSide":"market","baseMint":"SOL","quoteAmount":"1000.0"}
     * - 시장가 매도: {"orderType":"sell","orderSide":"market","baseMint":"SOL","amount":"1.0"}
     */
    @Operation(
            summary = "주문 생성",
            description = "새로운 주문을 생성하고 Rust 엔진에 제출합니다. " +
                         "지정가 주문은 가격을 지정하고, 시장가 주문은 즉시 체결됩니다. " +
                         "시장가 매수는 quoteAmount를 사용하여 금액 기반으로 주문합니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "주문 생성 성공",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (유효성 검증 실패 또는 엔진 거부)"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (JWT 토큰 없음 또는 유효하지 않음)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류 (엔진 통신 실패 등)"
            )
    })
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest
    ) {
        // JWT 필터에서 설정한 사용자 정보 가져오기
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // 주문 생성
        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * 주문 조회
     * Get Order
     * 
     * 특정 주문의 상세 정보를 조회합니다.
     * 본인 주문만 조회 가능합니다.
     * 
     * 인증:
     * - JWT 토큰 필요
     * - 본인 주문만 조회 가능 (userId로 필터링)
     * 
     * 경로 파라미터:
     * - orderId: 조회할 주문 ID
     * 
     * 응답:
     * - 200: 주문 조회 성공
     * - 401: 인증 실패
     * - 404: 주문을 찾을 수 없음 (본인 주문이 아님)
     */
    @Operation(
            summary = "주문 조회",
            description = "특정 주문의 상세 정보를 조회합니다. 본인 주문만 조회 가능합니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "주문 조회 성공",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "주문을 찾을 수 없음"
            )
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable Long orderId,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        OrderResponse response = orderService.getOrder(userId, orderId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 내 주문 목록 조회
     * Get My Orders
     * 
     * 현재 로그인한 사용자의 주문 목록을 조회합니다.
     * 
     * 쿼리 파라미터:
     * - status: 주문 상태 필터 (optional, 'pending', 'partial', 'filled', 'cancelled')
     * - limit: 최대 조회 개수 (optional, 기본값: 50)
     * - offset: 페이지네이션 오프셋 (optional, 기본값: 0)
     * 
     * 응답:
     * - 200: 주문 목록 조회 성공
     * - 401: 인증 실패
     */
    @Operation(
            summary = "내 주문 목록 조회",
            description = "현재 로그인한 사용자의 주문 목록을 조회합니다. " +
                         "상태별 필터링 및 페이지네이션을 지원합니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "주문 목록 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패"
            )
    })
    @GetMapping("/my")
    public ResponseEntity<List<OrderResponse.OrderDto>> getMyOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        List<OrderResponse.OrderDto> orders = orderService.getMyOrders(userId, status, limit, offset);
        return ResponseEntity.ok(orders);
    }
    
    /**
     * 주문 취소
     * Cancel Order
     * 
     * 대기 중이거나 부분 체결된 주문을 취소합니다.
     * 본인 주문만 취소 가능합니다.
     * 
     * 처리 과정:
     * 1. 주문 조회 및 본인 확인
     * 2. 주문 상태 확인 (취소 가능한 상태인지)
     * 3. Rust 엔진에 취소 요청 (gRPC, 동기)
     * 4. 주문 상태를 'cancelled'로 업데이트
     * 5. Kafka 이벤트 발행 (비동기)
     * 
     * 인증:
     * - JWT 토큰 필요
     * - 본인 주문만 취소 가능
     * 
     * 경로 파라미터:
     * - orderId: 취소할 주문 ID
     * 
     * 응답:
     * - 200: 주문 취소 성공
     * - 400: 주문 취소 불가 (이미 체결됨 또는 이미 취소됨)
     * - 401: 인증 실패 또는 권한 없음
     * - 404: 주문을 찾을 수 없음
     */
    @Operation(
            summary = "주문 취소",
            description = "대기 중이거나 부분 체결된 주문을 취소합니다. " +
                         "이미 전량 체결된 주문은 취소할 수 없습니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "주문 취소 성공",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "주문 취소 불가 (이미 체결됨 또는 이미 취소됨)"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 또는 권한 없음"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "주문을 찾을 수 없음"
            )
    })
    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long orderId,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        try {
            OrderResponse response = orderService.cancelOrder(userId, orderId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // 주문을 찾을 수 없거나 취소 불가능한 경우
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OrderResponse.builder()
                            .message("주문 취소 실패: " + e.getMessage())
                            .build());
        }
    }
}
