package dustin.cex.domains.order.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.shared.grpc.EngineGrpcClient;
import dustin.cex.shared.kafka.KafkaEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

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
    private final EngineGrpcClient engineGrpcClient;
    private final KafkaEventProducer kafkaEventProducer;
    
    /**
     * ObjectMapper 초기화 (JavaTimeModule 등록)
     * Initialize ObjectMapper with JavaTimeModule for LocalDateTime serialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) // LocalDateTime 직렬화 지원
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO 8601 형식 사용
    
    /**
     * 주문 생성
     * Create Order
     * 
     * 새로운 주문을 생성하고 Rust 엔진에 제출합니다.
     * 
     * 처리 과정:
     * 1. 주문 유효성 검증
     * 2. DB에 주문 저장 (트랜잭션 내, status: "pending")
     * 3. Rust 엔진에 주문 제출 (트랜잭션 밖, gRPC 호출)
     *    - 성공: 주문은 "pending" 상태 유지
     *    - 실패: 주문 상태를 "rejected"로 업데이트 (보상 트랜잭션)
     * 4. Kafka 이벤트 발행 (비동기, 로깅용)
     * 5. 주문 정보 반환
     * 
     * 트랜잭션 분리 이유:
     * - DB 저장만 트랜잭션으로 묶어 커넥션 점유 시간 최소화
     * - gRPC 호출은 트랜잭션 밖에서 실행 (네트워크 지연 영향 최소화)
     * - gRPC 실패 시 보상 트랜잭션으로 "rejected" 상태 업데이트
     * 
     * @param userId 주문을 생성하는 사용자 ID (JWT 토큰에서 추출)
     * @param request 주문 생성 요청 (가격, 수량, 타입 등)
     * @return 생성된 주문 정보
     * @throws IllegalArgumentException 주문 유효성 검증 실패 시
     * @throws RuntimeException 엔진 통신 실패 시
     */
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {
        // ============================================
        // 1. 주문 유효성 검증
        // ============================================
        validateOrderRequest(request);
        
        // ============================================
        // 2. DB에 주문 저장 (트랜잭션 내)
        // ============================================
        Order savedOrder = saveOrderInTransaction(userId, request);
        
        // ============================================
        // 3. Rust 엔진에 주문 제출 (트랜잭션 밖, gRPC 호출)
        // ============================================
        try {
            String priceStr = savedOrder.getPrice() != null ? savedOrder.getPrice().toString() : null;
            String amountStr = savedOrder.getAmount().toString();
            String quoteAmountStr = request.getQuoteAmount() != null ? request.getQuoteAmount().toString() : null;
            String quoteMint = request.getQuoteMint() != null ? request.getQuoteMint() : "USDT";
            
            boolean success = engineGrpcClient.submitOrder(
                    savedOrder.getId(),
                    userId,
                    request.getOrderType(),
                    request.getOrderSide(),
                    request.getBaseMint(),
                    quoteMint,
                    priceStr,
                    amountStr,
                    quoteAmountStr
            );
            
            if (!success) {
                // 엔진이 주문을 거부한 경우 (잔고 부족 등)
                log.error("[OrderService] 엔진이 주문을 거부: orderId={}", savedOrder.getId());
                updateOrderStatusRejected(savedOrder.getId(), "엔진이 주문을 거부했습니다");
                throw new RuntimeException("엔진이 주문을 거부했습니다");
            }
            
            // 성공 시: 주문은 "pending" 상태 유지 (엔진이 처리 중)
            
        } catch (RuntimeException e) {
            // gRPC 통신 실패 또는 엔진 거부
            if (!"엔진이 주문을 거부했습니다".equals(e.getMessage())) {
                // 통신 실패인 경우에만 rejected 상태로 업데이트
                log.error("[OrderService] 엔진 통신 실패: orderId={}, error={}", savedOrder.getId(), e.getMessage());
                updateOrderStatusRejected(savedOrder.getId(), "엔진 통신 실패: " + e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            // 예상치 못한 예외
            log.error("[OrderService] 엔진 통신 중 예외 발생: orderId={}, error={}", savedOrder.getId(), e.getMessage());
            updateOrderStatusRejected(savedOrder.getId(), "엔진 통신 실패: " + e.getMessage());
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
        
        // ============================================
        // 4. Kafka 이벤트 발행 (비동기, 로깅용)
        // ============================================
        try {
            String orderJson = objectMapper.writeValueAsString(savedOrder);
            kafkaEventProducer.publishOrderCreated(orderJson);
        } catch (Exception e) {
            log.error("[OrderService] Kafka 이벤트 발행 실패: orderId={}, error={}", 
                     savedOrder.getId(), e.getMessage());
        }
        
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
     * DB에 주문 저장 (트랜잭션 내)
     * Save Order in Transaction
     * 
     * 주문 엔티티를 생성하고 DB에 저장합니다.
     * 트랜잭션으로 묶여 있어서 실패 시 롤백됩니다.
     * 
     * @param userId 사용자 ID
     * @param request 주문 생성 요청
     * @return 저장된 주문 엔티티
     */
    @Transactional
    private Order saveOrderInTransaction(Long userId, CreateOrderRequest request) {
        Order order = buildOrderEntity(userId, request);
        return orderRepository.save(order);
    }
    
    /**
     * 주문 상태를 "rejected"로 업데이트 (보상 트랜잭션)
     * Update Order Status to Rejected (Compensating Transaction)
     * 
     * gRPC 호출 실패 시 주문 상태를 "rejected"로 업데이트합니다.
     * 별도 트랜잭션으로 실행되어 DB 커넥션 점유 시간을 최소화합니다.
     * 
     * @param orderId 주문 ID
     * @param reason 거부 사유
     */
    @Transactional
    private void updateOrderStatusRejected(Long orderId, String reason) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
            
            order.setStatus("rejected");
            orderRepository.save(order);
            
            log.error("[OrderService] 주문 거부 처리 완료: orderId={}, reason={}", orderId, reason);
        } catch (Exception e) {
            log.error("[OrderService] 주문 거부 상태 업데이트 실패: orderId={}, error={}", orderId, e.getMessage());
            // 보상 트랜잭션 실패는 로그만 남기고 예외를 던지지 않음 (이미 gRPC 실패 예외가 있음)
        }
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
     * 1. 주문 조회 및 본인 확인 (트랜잭션 내)
     * 2. 주문 상태 확인 (취소 가능한 상태인지)
     * 3. Rust 엔진에 취소 요청 (트랜잭션 밖, gRPC 호출)
     *    - 성공: 주문 상태를 'cancelled'로 업데이트 (별도 트랜잭션)
     *    - 실패: 예외 발생 (주문 상태는 변경되지 않음)
     * 4. Kafka 이벤트 발행 (비동기)
     * 
     * 트랜잭션 분리 이유:
     * - 주문 조회만 트랜잭션으로 묶어 커넥션 점유 시간 최소화
     * - gRPC 호출은 트랜잭션 밖에서 실행 (네트워크 지연 영향 최소화)
     * - 성공 시 별도 트랜잭션으로 상태 업데이트
     * 
     * @param userId 사용자 ID (본인 확인용)
     * @param orderId 취소할 주문 ID
     * @return 취소된 주문 정보
     * @throws RuntimeException 주문을 찾을 수 없거나 취소 불가능한 상태일 때
     */
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        // ============================================
        // 1. 주문 조회 및 본인 확인 (트랜잭션 내)
        // ============================================
        Order order = findOrderForCancellation(userId, orderId);
        
        // ============================================
        // 2. 주문 상태 확인 (취소 가능한 상태인지)
        // ============================================
        if ("filled".equals(order.getStatus())) {
            throw new RuntimeException("이미 전량 체결된 주문은 취소할 수 없습니다");
        }
        if ("cancelled".equals(order.getStatus())) {
            throw new RuntimeException("이미 취소된 주문입니다");
        }
        
        // ============================================
        // 3. Rust 엔진에 취소 요청 (트랜잭션 밖, gRPC 호출)
        // ============================================
        try {
            String tradingPair = order.getBaseMint() + "/" + order.getQuoteMint();
            boolean success = engineGrpcClient.cancelOrder(orderId, userId, tradingPair);
            
            if (!success) {
                log.error("[OrderService] 엔진이 주문 취소를 거부: orderId={}", orderId);
                throw new RuntimeException("엔진이 주문 취소를 거부했습니다");
            }
            
            // 성공 시: 주문 상태를 'cancelled'로 업데이트 (별도 트랜잭션)
            updateOrderStatusCancelled(orderId);
            
        } catch (RuntimeException e) {
            log.error("[OrderService] 엔진 취소 요청 실패: orderId={}, error={}", orderId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[OrderService] 엔진 취소 요청 중 예외 발생: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("엔진 취소 요청 실패: " + e.getMessage(), e);
        }
        
        // ============================================
        // 4. Kafka 이벤트 발행 (비동기)
        // ============================================
        try {
            // 취소된 주문 정보 조회 (최신 상태)
            Order cancelledOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
            
            String orderJson = objectMapper.writeValueAsString(cancelledOrder);
            kafkaEventProducer.publishOrderCancelled(orderJson);
        } catch (Exception e) {
            log.error("[OrderService] Kafka 이벤트 발행 실패: orderId={}, error={}", 
                     orderId, e.getMessage());
        }
        
        // ============================================
        // 5. 취소된 주문 정보 반환
        // ============================================
        Order cancelledOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
        
        OrderResponse.OrderDto orderDto = convertToDto(cancelledOrder);
        return OrderResponse.builder()
                .order(orderDto)
                .message("Order cancelled successfully")
                .build();
    }
    
    /**
     * 주문 조회 (취소용, 트랜잭션 내)
     * Find Order for Cancellation (in Transaction)
     * 
     * 취소할 주문을 조회하고 본인 확인을 수행합니다.
     * 
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 주문 엔티티
     * @throws RuntimeException 주문을 찾을 수 없거나 본인 주문이 아닐 때
     */
    @Transactional(readOnly = true)
    private Order findOrderForCancellation(Long userId, Long orderId) {
        return orderRepository.findByUserIdAndId(userId, orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
    }
    
    /**
     * 주문 상태를 "cancelled"로 업데이트 (별도 트랜잭션)
     * Update Order Status to Cancelled (Separate Transaction)
     * 
     * gRPC 취소 요청 성공 후 주문 상태를 "cancelled"로 업데이트합니다.
     * 별도 트랜잭션으로 실행되어 DB 커넥션 점유 시간을 최소화합니다.
     * 
     * @param orderId 주문 ID
     */
    @Transactional
    private void updateOrderStatusCancelled(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
            
            order.setStatus("cancelled");
            orderRepository.save(order);
        } catch (Exception e) {
            log.error("[OrderService] 주문 취소 상태 업데이트 실패: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("주문 취소 상태 업데이트 실패", e);
        }
    }
}
