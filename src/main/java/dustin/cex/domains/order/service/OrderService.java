package dustin.cex.domains.order.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 서비스
 * Order Service
 * 
 * 역할:
 * - 주문 생성, 조회, 취소 등의 비즈니스 로직 처리
 * - 주문 유효성 검증
 * - Rust 엔진과의 통신 (gRPC, 향후 구현)
 * - Kafka 이벤트 발행 (로깅용, 향후 구현)
 * 
 * 처리 흐름:
 * 1. 주문 유효성 검증 (가격, 수량, 타입 등)
 * 2. 주문 엔티티 생성 및 DB 저장
 * 3. Rust 엔진에 주문 제출 (gRPC, 동기)
 * 4. Kafka 이벤트 발행 (비동기, 로깅용)
 * 5. 주문 정보 반환
 * 
 * 주의사항:
 * - 주문 생성은 동기 처리 (엔진 응답 대기)
 * - Kafka 이벤트는 비동기 발행 (논블로킹)
 * - 엔진 장애 시 주문 생성 실패 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    // TODO: Rust 엔진 gRPC Client (향후 구현)
    // private final EngineGrpcClient engineClient;
    // TODO: Kafka Producer (향후 구현)
    // private final KafkaProducer kafkaProducer;
    
    /**
     * 주문 생성
     * Create Order
     * 
     * 새로운 주문을 생성하고 Rust 엔진에 제출합니다.
     * 
     * 처리 과정:
     * 1. 주문 유효성 검증
     *    - 주문 타입/방식 검증
     *    - 가격/수량 검증 (지정가 주문은 가격 필수, 시장가 매수는 quoteAmount 필수)
     *    - 잔고 확인 (향후 구현)
     * 
     * 2. 주문 엔티티 생성 및 DB 저장
     *    - Order 엔티티 생성
     *    - 초기 상태: 'pending'
     *    - DB에 저장
     * 
     * 3. Rust 엔진에 주문 제출 (gRPC, 동기)
     *    - 엔진에 주문 전송
     *    - 엔진 응답 대기 (< 5ms 목표)
     *    - 엔진 장애 시 주문 생성 실패 처리
     * 
     * 4. Kafka 이벤트 발행 (비동기, 로깅용)
     *    - 'order-created' 이벤트 발행
     *    - 논블로킹 (엔진 응답과 독립적)
     * 
     * 5. 주문 정보 반환
     * 
     * @param userId 주문을 생성하는 사용자 ID (JWT 토큰에서 추출)
     * @param request 주문 생성 요청 (가격, 수량, 타입 등)
     * @return 생성된 주문 정보
     * @throws IllegalArgumentException 주문 유효성 검증 실패 시
     * @throws RuntimeException 엔진 통신 실패 시
     * 
     * 예시:
     * - 지정가 매수: orderType='buy', orderSide='limit', price=100.0, amount=1.0
     * - 시장가 매수: orderType='buy', orderSide='market', quoteAmount=1000.0
     * - 시장가 매도: orderType='sell', orderSide='market', amount=1.0
     */
    @Transactional
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {
        log.info("[OrderService] 주문 생성 시작: userId={}, orderType={}, orderSide={}, baseMint={}", 
                 userId, request.getOrderType(), request.getOrderSide(), request.getBaseMint());
        
        // ============================================
        // 1. 주문 유효성 검증
        // ============================================
        validateOrderRequest(request);
        
        // ============================================
        // 2. 주문 엔티티 생성 및 DB 저장
        // ============================================
        Order order = buildOrderEntity(userId, request);
        Order savedOrder = orderRepository.save(order);
        log.info("[OrderService] 주문 DB 저장 완료: orderId={}", savedOrder.getId());
        
        // ============================================
        // 3. Rust 엔진에 주문 제출 (gRPC, 동기)
        // ============================================
        // TODO: Rust 엔진 gRPC Client 구현 후 활성화
        /*
        try {
            // 엔진에 주문 전송 (동기, 응답 대기)
            EngineOrderResponse engineResponse = engineClient.submitOrder(savedOrder);
            
            if (!engineResponse.isSuccess()) {
                // 엔진이 주문을 거부한 경우 (잔고 부족 등)
                log.warn("[OrderService] 엔진이 주문을 거부: orderId={}, reason={}", 
                         savedOrder.getId(), engineResponse.getReason());
                throw new OrderRejectedException("엔진이 주문을 거부했습니다: " + engineResponse.getReason());
            }
            
            log.info("[OrderService] 엔진에 주문 제출 완료: orderId={}", savedOrder.getId());
        } catch (Exception e) {
            // 엔진 통신 실패 시 주문 생성 실패 처리
            log.error("[OrderService] 엔진 통신 실패: orderId={}, error={}", savedOrder.getId(), e.getMessage());
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
        */
        
        // ============================================
        // 4. Kafka 이벤트 발행 (비동기, 로깅용)
        // ============================================
        // TODO: Kafka Producer 구현 후 활성화
        /*
        try {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId(savedOrder.getId())
                    .userId(userId)
                    .orderType(request.getOrderType())
                    .orderSide(request.getOrderSide())
                    .baseMint(request.getBaseMint())
                    .quoteMint(request.getQuoteMint() != null ? request.getQuoteMint() : "USDT")
                    .price(request.getPrice())
                    .amount(request.getAmount())
                    .timestamp(Instant.now())
                    .build();
            
            // 비동기 발행 (논블로킹)
            kafkaProducer.sendAsync("order-created", savedOrder.getId().toString(), event);
            log.debug("[OrderService] Kafka 이벤트 발행 완료: orderId={}", savedOrder.getId());
        } catch (Exception e) {
            // Kafka 발행 실패는 로그만 남기고 계속 진행 (주문 생성은 성공)
            log.warn("[OrderService] Kafka 이벤트 발행 실패 (무시): orderId={}, error={}", 
                     savedOrder.getId(), e.getMessage());
        }
        */
        
        // ============================================
        // 5. 주문 정보 반환
        // ============================================
        OrderResponse.OrderDto orderDto = convertToDto(savedOrder);
        return OrderResponse.builder()
                .order(orderDto)
                .message("Order created successfully")
                .build();
    }
    
    /**
     * 주문 유효성 검증
     * Validate Order Request
     * 
     * 주문 생성 요청의 유효성을 검증합니다.
     * 
     * 검증 항목:
     * 1. 주문 타입/방식 검증 (이미 @Valid로 처리됨)
     * 2. 지정가 주문: 가격 필수, 양수여야 함
     * 3. 시장가 매수: quoteAmount 필수, 양수여야 함
     * 4. 시장가 매도: amount 필수, 양수여야 함
     * 5. 지정가 매수: amount 필수, 양수여야 함
     * 
     * @param request 주문 생성 요청
     * @throws IllegalArgumentException 유효성 검증 실패 시
     */
    private void validateOrderRequest(CreateOrderRequest request) {
        // 지정가 주문: 가격 필수
        if ("limit".equals(request.getOrderSide())) {
            if (request.getPrice() == null) {
                throw new IllegalArgumentException("지정가 주문은 가격이 필수입니다");
            }
            if (request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("가격은 0보다 커야 합니다");
            }
        }
        
        // 시장가 매수: quoteAmount 필수
        if ("buy".equals(request.getOrderType()) && "market".equals(request.getOrderSide())) {
            if (request.getQuoteAmount() == null) {
                throw new IllegalArgumentException("시장가 매수 주문은 quoteAmount가 필수입니다");
            }
            if (request.getQuoteAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("quoteAmount는 0보다 커야 합니다");
            }
        } else {
            // 지정가 매수 또는 모든 매도: amount 필수
            if (request.getAmount() == null) {
                throw new IllegalArgumentException("주문 수량(amount)이 필수입니다");
            }
            if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("주문 수량은 0보다 커야 합니다");
            }
        }
    }
    
    /**
     * 주문 엔티티 생성
     * Build Order Entity
     * 
     * CreateOrderRequest를 Order 엔티티로 변환합니다.
     * 
     * @param userId 사용자 ID
     * @param request 주문 생성 요청
     * @return Order 엔티티
     */
    private Order buildOrderEntity(Long userId, CreateOrderRequest request) {
        // quoteMint 기본값 설정
        String quoteMint = request.getQuoteMint() != null && !request.getQuoteMint().isEmpty() 
                ? request.getQuoteMint() 
                : "USDT";
        
        // 시장가 매수의 경우 amount 계산 (quoteAmount를 사용하여 추정)
        // 실제로는 엔진에서 매칭 후 정확한 amount 계산됨
        BigDecimal amount = request.getAmount();
        if ("buy".equals(request.getOrderType()) && "market".equals(request.getOrderSide())) {
            // 시장가 매수: quoteAmount만 있고 amount는 없음
            // 엔진에서 매칭 후 정확한 amount 계산되므로, 일단 0으로 설정
            amount = BigDecimal.ZERO;
        }
        
        return Order.builder()
                .userId(userId)
                .orderType(request.getOrderType())
                .orderSide(request.getOrderSide())
                .baseMint(request.getBaseMint())
                .quoteMint(quoteMint)
                .price(request.getPrice())
                .amount(amount)
                .filledAmount(BigDecimal.ZERO)
                .filledQuoteAmount(BigDecimal.ZERO)
                .status("pending")
                .build();
    }
    
    /**
     * Order 엔티티를 DTO로 변환
     * Convert Order Entity to DTO
     * 
     * @param order Order 엔티티
     * @return OrderDto
     */
    private OrderResponse.OrderDto convertToDto(Order order) {
        return OrderResponse.OrderDto.builder()
                .id(order.getId().toString()) // JavaScript 정밀도 손실 방지
                .userId(order.getUserId())
                .orderType(order.getOrderType())
                .orderSide(order.getOrderSide())
                .baseMint(order.getBaseMint())
                .quoteMint(order.getQuoteMint())
                .price(order.getPrice())
                .amount(order.getAmount())
                .filledAmount(order.getFilledAmount())
                .filledQuoteAmount(order.getFilledQuoteAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
    
    /**
     * 주문 조회
     * Get Order
     * 
     * 사용자가 자신의 주문을 조회합니다.
     * 
     * @param userId 사용자 ID (본인 확인용)
     * @param orderId 주문 ID
     * @return 주문 정보
     * @throws RuntimeException 주문을 찾을 수 없거나 본인 주문이 아닐 때
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, Long orderId) {
        Order order = orderRepository.findByUserIdAndId(userId, orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
        
        OrderResponse.OrderDto orderDto = convertToDto(order);
        return OrderResponse.builder()
                .order(orderDto)
                .build();
    }
    
    /**
     * 내 주문 목록 조회
     * Get My Orders
     * 
     * 현재 로그인한 사용자의 주문 목록을 조회합니다.
     * 
     * @param userId 사용자 ID
     * @param status 주문 상태 필터 (optional, 'pending', 'partial', 'filled', 'cancelled')
     * @param limit 최대 조회 개수 (optional, 기본값: 50)
     * @param offset 페이지네이션 오프셋 (optional, 기본값: 0)
     * @return 주문 목록
     */
    @Transactional(readOnly = true)
    public List<OrderResponse.OrderDto> getMyOrders(Long userId, String status, Integer limit, Integer offset) {
        int pageSize = limit != null && limit > 0 ? limit : 50;
        int pageNumber = offset != null && offset >= 0 ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        
        Page<Order> orders;
        if (status != null && !status.isEmpty()) {
            orders = orderRepository.findByUserIdAndStatus(userId, status, pageable);
        } else {
            orders = orderRepository.findByUserId(userId, pageable);
        }
        
        return orders.getContent().stream()
                .map(this::convertToDto)
                .toList();
    }
    
    /**
     * 주문 취소
     * Cancel Order
     * 
     * 대기 중이거나 부분 체결된 주문을 취소합니다.
     * 
     * 처리 과정:
     * 1. 주문 조회 및 본인 확인
     * 2. 주문 상태 확인 (취소 가능한 상태인지)
     * 3. Rust 엔진에 취소 요청 (gRPC, 동기)
     * 4. 주문 상태를 'cancelled'로 업데이트
     * 5. Kafka 이벤트 발행 (비동기)
     * 
     * @param userId 사용자 ID (본인 확인용)
     * @param orderId 취소할 주문 ID
     * @return 취소된 주문 정보
     * @throws RuntimeException 주문을 찾을 수 없거나 취소 불가능한 상태일 때
     */
    @Transactional
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        log.info("[OrderService] 주문 취소 시작: userId={}, orderId={}", userId, orderId);
        
        // 1. 주문 조회 및 본인 확인
        Order order = orderRepository.findByUserIdAndId(userId, orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
        
        // 2. 주문 상태 확인 (취소 가능한 상태인지)
        if ("filled".equals(order.getStatus())) {
            throw new RuntimeException("이미 전량 체결된 주문은 취소할 수 없습니다");
        }
        if ("cancelled".equals(order.getStatus())) {
            throw new RuntimeException("이미 취소된 주문입니다");
        }
        
        // 3. Rust 엔진에 취소 요청 (gRPC, 동기)
        // TODO: Rust 엔진 gRPC Client 구현 후 활성화
        /*
        try {
            engineClient.cancelOrder(orderId);
            log.info("[OrderService] 엔진에서 주문 취소 완료: orderId={}", orderId);
        } catch (Exception e) {
            log.error("[OrderService] 엔진 취소 요청 실패: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("엔진 취소 요청 실패: " + e.getMessage(), e);
        }
        */
        
        // 4. 주문 상태를 'cancelled'로 업데이트
        order.setStatus("cancelled");
        Order cancelledOrder = orderRepository.save(order);
        log.info("[OrderService] 주문 취소 완료: orderId={}", orderId);
        
        // 5. Kafka 이벤트 발행 (비동기)
        // TODO: Kafka Producer 구현 후 활성화
        
        OrderResponse.OrderDto orderDto = convertToDto(cancelledOrder);
        return OrderResponse.builder()
                .order(orderDto)
                .message("Order cancelled successfully")
                .build();
    }
}
