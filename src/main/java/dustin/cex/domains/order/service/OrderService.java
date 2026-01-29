package dustin.cex.domains.order.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.model.dto.OrderbookResponse;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.shared.http.EngineHttpClient;
import dustin.cex.shared.kafka.KafkaEventProducer;
import lombok.extern.slf4j.Slf4j;


/**
 * 주문 서비스
 * Order Service
 * 
 * 역할:
 * - 주문 생성, 조회, 취소 등의 비즈니스 로직 처리
 * - 주문 유효성 검증
 * - Rust 엔진과의 통신 (HTTP)
 * - Kafka 이벤트 발행 (로깅용)
 * 
 * 처리 흐름:
 * 1. 주문 유효성 검증 (가격, 수량, 타입 등)
 * 2. 주문 엔티티 생성 및 DB 저장
 * 3. Rust 엔진에 주문 제출 (HTTP, 동기)
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
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final EngineHttpClient engineHttpClient;
    private final KafkaEventProducer kafkaEventProducer;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;
    
    /**
     * ObjectMapper 초기화 (JavaTimeModule 등록)
     * Initialize ObjectMapper with JavaTimeModule for LocalDateTime serialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) // LocalDateTime 직렬화 지원
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO 8601 형식 사용
    
    /**
     * 생성자: TransactionTemplate 초기화
     * Constructor: Initialize TransactionTemplate
     */
    public OrderService(
            OrderRepository orderRepository,
            UserBalanceRepository userBalanceRepository,
            EngineHttpClient engineHttpClient,
            KafkaEventProducer kafkaEventProducer,
            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.engineHttpClient = engineHttpClient;
        this.kafkaEventProducer = kafkaEventProducer;
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }
    
    /**
     * 주문 생성
     * Create Order
     * 
     * 새로운 주문을 생성하고 Rust 엔진에 제출합니다.
     * 
     * 처리 과정:
     * 1. 주문 유효성 검증
     * 2. DB에 주문 저장 (트랜잭션 내, status: "pending")
     * 3. Rust 엔진에 주문 제출 (트랜잭션 밖, HTTP 호출)
     *    - 성공: 주문은 "pending" 상태 유지
     *    - 실패: 주문 상태를 "rejected"로 업데이트 (보상 트랜잭션)
     * 4. Kafka 이벤트 발행 (비동기, 로깅용)
     * 5. 주문 정보 반환
     * 
     * 트랜잭션 분리 이유:
     * - DB 저장만 트랜잭션으로 묶어 커넥션 점유 시간 최소화
     * - HTTP 호출은 트랜잭션 밖에서 실행 (네트워크 지연 영향 최소화)
     * - HTTP 실패 시 보상 트랜잭션으로 "rejected" 상태 업데이트
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
        // 2. DB에 주문 저장 + 잔고 lock (트랜잭션 내, 명시적 커밋)
        // ============================================
        // TransactionTemplate을 사용하여 트랜잭션을 명시적으로 커밋
        // 이렇게 하면 Rust 엔진에 제출하기 전에 주문이 DB에 확실히 저장됨
        Order savedOrder = transactionTemplate.execute(status -> {
            // 2-1. 주문 저장
            Order order = buildOrderEntity(userId, request);
            Order saved = orderRepository.save(order);
            
            // 2-2. 잔고 lock (같은 트랜잭션 내)
            lockBalanceForOrder(userId, request);
            
            return saved;
        });
        
        // 트랜잭션이 커밋되었는지 확인 (주문이 DB에 저장되었는지)
        if (savedOrder == null || savedOrder.getId() == null) {
            throw new RuntimeException("주문 저장 실패");
        }
        
        // 트랜잭션 커밋 후 DB에서 실제로 조회하여 확인
        // 이렇게 하면 트랜잭션이 완전히 커밋되었는지 확인 가능
        Order verifiedOrder = orderRepository.findById(savedOrder.getId())
                .orElseThrow(() -> new RuntimeException("주문이 DB에 저장되지 않았습니다: orderId=" + savedOrder.getId()));
        
        // 저장된 주문 ID를 사용 (DB에서 조회한 주문 ID)
        Long orderId = verifiedOrder.getId();
        
        // ============================================
        // 3. Rust 엔진에 주문 제출 (트랜잭션 커밋 후, HTTP 호출)
        // ============================================
        try {
            String priceStr = verifiedOrder.getPrice() != null ? verifiedOrder.getPrice().toString() : null;
            String amountStr = verifiedOrder.getAmount().toString();
            String quoteAmountStr = request.getQuoteAmount() != null ? request.getQuoteAmount().toString() : null;
            String quoteMint = request.getQuoteMint() != null ? request.getQuoteMint() : "USDT";
            
            // DB에 저장된 주문 ID를 Rust 엔진에 전달
            boolean success = engineHttpClient.submitOrder(
                    orderId,
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
                // 엔진이 주문을 거부한 경우 (잔고 부족 등): 잔고 unlock
                rollbackOrderAndBalance(orderId, userId, request);
                throw new RuntimeException("엔진이 주문을 거부했습니다");
            }
            
            // 성공 시: 주문은 "pending" 상태 유지 (엔진이 처리 중)
            
        } catch (RuntimeException e) {
            // HTTP 통신 실패 또는 엔진 거부
            if (!"엔진이 주문을 거부했습니다".equals(e.getMessage())) {
                // 통신 실패인 경우: 잔고 unlock
                rollbackOrderAndBalance(orderId, userId, request);
            }
            throw e;
        } catch (Exception e) {
            // 예상치 못한 예외: 잔고 unlock
            rollbackOrderAndBalance(orderId, userId, request);
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
        
        // ============================================
        // 4. Kafka 이벤트 발행 (비동기, 로깅용)
        // ============================================
        try {
            // 최신 주문 정보 조회
            Order latestOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
            String orderJson = objectMapper.writeValueAsString(latestOrder);
            kafkaEventProducer.publishOrderCreated(orderJson);
        } catch (Exception e) {
            // Kafka 이벤트 발행 실패는 무시
        }
        
        // ============================================
        // 5. 주문 정보 반환
        // ============================================
        // 최신 주문 정보 조회하여 반환
        Order finalOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
        OrderResponse.OrderDto orderDto = convertToDto(finalOrder);
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
     * HTTP 호출 실패 시 주문 상태를 "rejected"로 업데이트합니다.
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
        } catch (Exception e) {
            // 보상 트랜잭션 실패는 무시 (이미 HTTP 실패 예외가 있음)
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
     * @param pageable 페이징 정보 (page, size)
     * @return 페이징된 주문 목록
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse.OrderDto> getMyOrders(Long userId, String status, Pageable pageable) {
        Page<Order> orders;
        if (status != null && !status.isEmpty()) {
            orders = orderRepository.findByUserIdAndStatus(userId, status, pageable);
        } else {
            orders = orderRepository.findByUserId(userId, pageable);
        }
        
        return orders.map(this::convertToDto);
    }
    
    /**
     * 오더북 조회
     * Get Orderbook
     * 
     * 특정 거래쌍의 오더북(호가창)을 조회합니다.
     * 매수 호가는 가격 내림차순, 매도 호가는 가격 오름차순으로 정렬됩니다.
     * 
     * @param baseMint 기준 자산 (예: "SOL")
     * @param quoteMint 기준 통화 (예: "USDT", 기본값: "USDT")
     * @param depth 조회할 가격 레벨 개수 (선택, null이면 모든 호가 조회)
     * @return 오더북 응답 (bids: 매수 호가, asks: 매도 호가)
     */
    @Transactional(readOnly = true)
    public OrderbookResponse getOrderbook(String baseMint, String quoteMint, Integer depth) {
        String quote = quoteMint != null && !quoteMint.isEmpty() ? quoteMint : "USDT";
        
        // depth가 지정되지 않았으면 기본값 50 사용
        int limit = depth != null && depth > 0 ? depth : 50;
        Pageable pageable = PageRequest.of(0, limit);
        
        // 매수 주문 조회 (가격 내림차순)
        List<Order> buyOrders = orderRepository.findBuyOrdersByTradingPair(baseMint, quote, pageable);
        
        // 매도 주문 조회 (가격 오름차순)
        List<Order> sellOrders = orderRepository.findSellOrdersByTradingPair(baseMint, quote, pageable);
        
        // DTO 변환
        List<OrderResponse.OrderDto> bids = buyOrders.stream()
                .map(this::convertToDto)
                .toList();
        
        List<OrderResponse.OrderDto> asks = sellOrders.stream()
                .map(this::convertToDto)
                .toList();
        
        return OrderbookResponse.builder()
                .bids(bids)
                .asks(asks)
                .build();
    }
    
    /**
     * 주문 취소
     * Cancel Order
     * 
     * 대기 중이거나 부분 체결된 주문을 취소합니다.
     * 
     * 처리 과정:
     * 1. 주문 조회 및 본인 확인 (트랜잭션 내, 비관적 락)
     * 2. 주문 상태 확인 (취소 가능한 상태인지)
     * 3. 트랜잭션 커밋 (락 해제)
     * 4. Rust 엔진에 취소 요청 (트랜잭션 밖, HTTP 호출)
     *    - 성공: 주문 상태를 'cancelled'로 업데이트 (별도 트랜잭션)
     *    - 실패: 예외 발생 (주문 상태는 변경되지 않음)
     * 5. Kafka 이벤트 발행 (비동기)
     * 
     * 트랜잭션 분리 이유:
     * - 주문 조회만 트랜잭션으로 묶어 커넥션 점유 시간 최소화
     * - HTTP 호출은 트랜잭션 밖에서 실행 (네트워크 지연 영향 최소화)
     * - 성공 시 별도 트랜잭션으로 상태 업데이트
     * 
     * @param userId 사용자 ID (본인 확인용)
     * @param orderId 취소할 주문 ID
     * @return 취소된 주문 정보
     * @throws RuntimeException 주문을 찾을 수 없거나 취소 불가능한 상태일 때
     */
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        // ============================================
        // 1. 주문 조회 및 본인 확인 (트랜잭션 내, 비관적 락)
        // ============================================
        Order order = transactionTemplate.execute(status -> {
            return orderRepository.findByUserIdAndIdForUpdate(userId, orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
        });
        
        // ============================================
        // 2. 주문 상태 확인 (취소 가능한 상태인지)
        // ============================================
        if ("filled".equals(order.getStatus())) {
            throw new RuntimeException("이미 전량 체결된 주문은 취소할 수 없습니다");
        }
        if ("cancelled".equals(order.getStatus())) {
            throw new RuntimeException("이미 취소된 주문입니다");
        }
        
        // 주문 정보 저장 (트랜잭션 종료 후 사용)
        String baseMint = order.getBaseMint();
        String quoteMint = order.getQuoteMint();
        
        // ============================================
        // 3. 트랜잭션 커밋 (락 해제)
        // ============================================
        // findOrderForCancellation의 트랜잭션이 여기서 종료됨
        
        // ============================================
        // 4. Rust 엔진에 취소 요청 (트랜잭션 밖, HTTP 호출)
        // ============================================
        try {
            String tradingPair = baseMint + "/" + quoteMint;
            boolean success = engineHttpClient.cancelOrder(orderId, userId, tradingPair);
            
            if (!success) {
                throw new RuntimeException("엔진이 주문 취소를 거부했습니다");
            }
            
            // 성공 시: 주문 상태를 'cancelled'로 업데이트 (별도 트랜잭션)
            updateOrderStatusCancelled(orderId);
            
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("엔진 취소 요청 실패: " + e.getMessage(), e);
        }
        
        // ============================================
        // 5. Kafka 이벤트 발행 (비동기)
        // ============================================
        try {
            // 취소된 주문 정보 조회 (최신 상태)
            Order cancelledOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
            
            String orderJson = objectMapper.writeValueAsString(cancelledOrder);
            kafkaEventProducer.publishOrderCancelled(orderJson);
        } catch (Exception e) {
            // Kafka 이벤트 발행 실패는 무시
        }
        
        // ============================================
        // 6. 취소된 주문 정보 반환
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
     * 주문 조회 (취소용, 비관적 락 사용)
     * Find Order for Cancellation (with Pessimistic Lock)
     * 
     * 취소할 주문을 조회하고 본인 확인을 수행합니다.
     * 비관적 락을 사용하여 동시성 문제를 방지합니다.
     * 
     * 동시성 제어:
     * - SELECT FOR UPDATE로 락을 걸어 다른 트랜잭션의 동시 수정을 방지
     * - Kafka Consumer가 체결 이벤트를 처리하는 동안 취소 요청이 들어오는 경우 방지
     * - 주문 상태가 변경되는 동안 취소 요청이 들어오는 경우 방지
     * 
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 주문 엔티티
     * @throws RuntimeException 주문을 찾을 수 없거나 본인 주문이 아닐 때
     */
    @Transactional
    private Order findOrderForCancellation(Long userId, Long orderId) {
        return orderRepository.findByUserIdAndIdForUpdate(userId, orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: orderId=" + orderId));
    }
    
    /**
     * 주문 상태를 "cancelled"로 업데이트 (별도 트랜잭션)
     * Update Order Status to Cancelled (Separate Transaction)
     * 
     * HTTP 취소 요청 성공 후 주문 상태를 "cancelled"로 업데이트합니다.
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
            throw new RuntimeException("주문 취소 상태 업데이트 실패", e);
        }
    }
    
    /**
     * 주문에 필요한 잔고 lock
     * Lock balance for order
     * 
     * @param userId 사용자 ID
     * @param request 주문 요청
     */
    private void lockBalanceForOrder(Long userId, CreateOrderRequest request) {
        if ("buy".equals(request.getOrderSide())) {
            // 매수 주문: quote_mint lock
            BigDecimal lockAmount = calculateQuoteAmount(request);
            lockBalance(userId, request.getQuoteMint(), lockAmount);
        } else {
            // 매도 주문: base_mint lock
            lockBalance(userId, request.getBaseMint(), request.getAmount());
        }
    }
    
    /**
     * quote_amount 계산
     * Calculate quote amount for buy order
     */
    private BigDecimal calculateQuoteAmount(CreateOrderRequest request) {
        if (request.getQuoteAmount() != null) {
            return request.getQuoteAmount();
        }
        // quoteAmount가 없으면 price * amount 계산
        if (request.getPrice() != null && request.getAmount() != null) {
            return request.getPrice().multiply(request.getAmount());
        }
        throw new RuntimeException(String.format(
            "매수 주문의 경우 quoteAmount 또는 (price와 amount)가 필요합니다: price=%s, amount=%s", 
            request.getPrice(), request.getAmount()
        ));
    }
    
    /**
     * 잔고 lock (비관적 락 사용)
     * Lock balance with pessimistic lock
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param amount lock할 금액
     */
    private void lockBalance(Long userId, String mint, BigDecimal amount) {
        // null 체크
        if (amount == null) {
            throw new IllegalArgumentException(String.format("lock할 금액이 null입니다: userId=%d, mint=%s", userId, mint));
        }
        
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        if (balanceOpt.isEmpty()) {
            throw new RuntimeException(String.format("잔고가 없습니다: userId=%d, mint=%s", userId, mint));
        }
        
        UserBalance balance = balanceOpt.get();
        
        // available null 체크 및 기본값 설정
        BigDecimal available = balance.getAvailable();
        if (available == null) {
            log.warn("[Balance] available이 null입니다. BigDecimal.ZERO로 설정: userId={}, mint={}", userId, mint);
            available = BigDecimal.ZERO;
            balance.setAvailable(BigDecimal.ZERO);
        }
        
        // 잔고 확인
        if (available.compareTo(amount) < 0) {
            throw new RuntimeException(String.format(
                "잔고 부족: userId=%d, mint=%s, available=%s, required=%s", 
                userId, mint, available, amount
            ));
        }
        
        // 잔고 lock
        BigDecimal locked = balance.getLocked();
        if (locked == null) {
            locked = BigDecimal.ZERO;
        }
        
        balance.setAvailable(available.subtract(amount));
        balance.setLocked(locked.add(amount));
        userBalanceRepository.save(balance);
        
        log.info("[Balance] 잔고 lock: userId={}, mint={}, amount={}, available={} -> {}, locked={} -> {}", 
            userId, mint, amount, 
            balance.getAvailable().add(amount), balance.getAvailable(),
            balance.getLocked().subtract(amount), balance.getLocked());
    }
    
    /**
     * Rust 엔진 거부 시 롤백
     * Rollback order and balance when engine rejects order
     * 
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param request 주문 요청
     */
    private void rollbackOrderAndBalance(Long orderId, Long userId, CreateOrderRequest request) {
        transactionTemplate.execute(status -> {
            // 주문 상태 변경
            updateOrderStatusRejected(orderId, "엔진이 주문을 거부했습니다");
            
            // 잔고 unlock
            unlockBalanceForOrder(userId, request);
            
            return null;
        });
    }
    
    /**
     * 주문 취소 시 잔고 unlock
     * Unlock balance for order cancellation
     * 
     * @param userId 사용자 ID
     * @param request 주문 요청
     */
    private void unlockBalanceForOrder(Long userId, CreateOrderRequest request) {
        if ("buy".equals(request.getOrderSide())) {
            BigDecimal unlockAmount = calculateQuoteAmount(request);
            unlockBalance(userId, request.getQuoteMint(), unlockAmount);
        } else {
            unlockBalance(userId, request.getBaseMint(), request.getAmount());
        }
    }
    
    /**
     * 잔고 unlock
     * Unlock balance
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param amount unlock할 금액
     */
    private void unlockBalance(Long userId, String mint, BigDecimal amount) {
        // null 체크
        if (amount == null) {
            throw new IllegalArgumentException(String.format("unlock할 금액이 null입니다: userId=%d, mint=%s", userId, mint));
        }
        
        var balanceOpt = userBalanceRepository.findByUserIdAndMintAddressForUpdate(userId, mint);
        
        if (balanceOpt.isPresent()) {
            UserBalance balance = balanceOpt.get();
            
            // null 체크 및 기본값 설정
            BigDecimal locked = balance.getLocked();
            if (locked == null) {
                log.warn("[Balance] locked이 null입니다. BigDecimal.ZERO로 설정: userId={}, mint={}", userId, mint);
                locked = BigDecimal.ZERO;
            }
            
            BigDecimal available = balance.getAvailable();
            if (available == null) {
                log.warn("[Balance] available이 null입니다. BigDecimal.ZERO로 설정: userId={}, mint={}", userId, mint);
                available = BigDecimal.ZERO;
            }
            
            balance.setLocked(locked.subtract(amount));
            balance.setAvailable(available.add(amount));
            userBalanceRepository.save(balance);
            
            log.info("[Balance] 잔고 unlock: userId={}, mint={}, amount={}", userId, mint, amount);
        }
    }
}
