package dustin.cex.domains.order.service;

import java.math.BigDecimal;
import dustin.cex.shared.model.ExactAmount;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.exception.OrderSubmissionUncertainException;
import dustin.cex.domains.order.model.OrderCommand;
import dustin.cex.domains.order.model.entity.OrderSubmission;
import dustin.cex.domains.order.repository.OrderSubmissionRepository;
import dustin.cex.domains.order.model.dto.OrderCreationResult;
import dustin.cex.domains.order.exception.OrderRequestConflictException;
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
 * - 엔진 전송 결과 불명 시 주문·예약을 보존하고 주문 ID로 조회 안내
 */
@Slf4j
@Service
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OrderSubmissionRepository submissionRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final EngineHttpClient engineHttpClient;
    private final KafkaEventProducer kafkaEventProducer;
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
            OrderSubmissionRepository submissionRepository,
            UserBalanceRepository userBalanceRepository,
            EngineHttpClient engineHttpClient,
            KafkaEventProducer kafkaEventProducer,
            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.submissionRepository = submissionRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.engineHttpClient = engineHttpClient;
        this.kafkaEventProducer = kafkaEventProducer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }
    
    /**
     * 주문과 자산 예약을 먼저 커밋한 다음 엔진을 호출한다.
     *
     * HTTP 실패는 엔진 미처리의 증거가 아니다. 요청 처리 후 응답만 유실될 수 있어
     * 이미 커밋한 주문·예약을 임의로 되돌리면 같은 자산을 재사용하게 된다.
     * 따라서 실패 시 상태를 보존하고, 주문 ID를 포함한 결과 불명 예외를 반환한다.
     * 확정 거부·취소에 따른 해제는 향후 영속적인 엔진 결과를 근거로 처리해야 한다.
     *
     * NEVER는 이 메서드를 호출한 쪽의 트랜잭션이 열려 있으면 진입을 거부한다.
     * 내부 TransactionTemplate이 외부 트랜잭션에 합류해 HTTP 이전에 커밋하지 못하는
     * 상황을 차단한다. 네트워크 호출 동안 DB 트랜잭션도 유지하지 않는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public OrderCreationResult createOrder(Long userId, UUID requestId, CreateOrderRequest request) {
        if (requestId == null || userId == null || userId <= 0) {
            throw new IllegalArgumentException("유효한 사용자와 Idempotency-Key가 필요합니다");
        }
        OrderCommand command = OrderCommand.from(request);
        var existing = submissionRepository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        Order savedOrder;
        try {
            savedOrder = transactionTemplate.execute(status -> {
                Order order = orderRepository.save(buildOrderEntity(userId, command));
                // 원본 명령·키·주문·예약을 같은 트랜잭션에 기록한다. 유일 제약 경쟁에서
                // 진 요청은 여기서 실패하며 주문 행도 롤백된다. 잔액을 먼저 변경하지 않는다.
                submissionRepository.saveAndFlush(new OrderSubmission(userId, requestId, order.getId(), command));
                lockBalance(userId, command.reservationMint(), command.reservationAmount());
                return order;
            });
        } catch (DataIntegrityViolationException exception) {
            // 제약 오류를 트랜잭션 안에서 잡으면 rollback-only 상태로 조회하게 된다.
            // execute가 롤백한 뒤 별도 조회한다. 경쟁한 키의 확정 기록이 있을 때만
            // 기존 접수로 처리하고, 다른 DB 제약 오류는 그대로 전파한다.
            var winner = submissionRepository.findByUserIdAndRequestId(userId, requestId);
            if (winner.isEmpty()) {
                throw exception;
            }
            return replay(winner.get(), command);
        }
        if (savedOrder == null || savedOrder.getId() == null) {
            throw new IllegalStateException("주문 저장 실패");
        }
        Long orderId = savedOrder.getId();

        // ============================================
        // 3. Rust 엔진에 주문 제출 (트랜잭션 커밋 후, HTTP 호출)
        // ============================================
        try {
            String priceStr = decimalString(command.price());
            String amountStr = decimalString(command.amount());
            String quoteAmountStr = decimalString(command.quoteAmount());
            String quoteMint = savedOrder.getQuoteMint();
            
            // DB에 저장된 주문 ID를 Rust 엔진에 전달
            boolean success = engineHttpClient.submitOrder(
                    orderId,
                    userId,
                    command.orderType(),
                    command.orderSide(),
                    command.baseMint(),
                    quoteMint,
                    priceStr,
                    amountStr,
                    quoteAmountStr
            );
            
            if (!success) {
                // boolean false에는 주문 ID와 연결된 영속적인 거부 근거가 없다.
                // HTTP 상태나 오류 문자열만으로 예약을 해제하지 않는다.
                throw new IllegalStateException("엔진 접수 응답을 확인하지 못했습니다");
            }
        } catch (RuntimeException e) {
            // 여기서는 DB 상태를 쓰지 않는다. 응답을 기다리는 동안 다른 처리자가
            // 주문을 filled/partial로 갱신했을 수 있으므로 pending으로도 덮어쓰지 않는다.
            // 예외 원인은 내부 진단에만 보존하고 HTTP 응답에 노출하지 않는다.
            log.warn("[Order] 엔진 제출 결과 불명: orderId={}, userId={}", orderId, userId, e);
            throw new OrderSubmissionUncertainException(orderId, e);
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
        return new OrderCreationResult(OrderResponse.builder()
                .order(orderDto).message("Order created successfully").build(), true);
    }

    /**
     * 재요청은 현재 저장된 주문을 조회할 뿐 엔진에 보내지 않는다. 최초 플랫폼 응답을
     * 잃었거나 플랫폼이 커밋 직후 종료돼도 같은 키로 기존 주문 ID를 찾을 수 있다.
     * 엔진 접수 여부는 이 기록만으로 확정할 수 없다. 응답 본문의 바이트 재생도 아니다.
     */
    private OrderCreationResult replay(OrderSubmission submission, OrderCommand command) {
        if (!submission.command().equals(command)) {
            throw new OrderRequestConflictException();
        }
        Order order = orderRepository.findByUserIdAndId(submission.getUserId(), submission.getOrderId())
                .orElseThrow(() -> new IllegalStateException("접수 기록에 연결된 주문을 찾을 수 없습니다"));
        return new OrderCreationResult(OrderResponse.builder().order(convertToDto(order))
                .message("Existing platform order; engine submission is not repeated").build(), false);
    }

    /** 원본 명령의 amount=null은 시장가 매수라는 의미다. 상태 테이블의 수량만 0으로 시작한다. */
    private Order buildOrderEntity(Long userId, OrderCommand command) {
        return Order.builder().userId(userId).orderType(command.orderType()).orderSide(command.orderSide())
                .baseMint(command.baseMint()).quoteMint(command.quoteMint()).price(command.price())
                .amount(command.amount() == null ? BigDecimal.ZERO : command.amount())
                .filledAmount(BigDecimal.ZERO).filledQuoteAmount(BigDecimal.ZERO).status("pending").build();
    }

    /** 소수의 지수 표기 대신 원래 값의 문자열을 보내며, 없는 수량을 0으로 바꾸지 않는다. */
    private String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
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
        
        // 손상 잔액을 0으로 보정하면 금융 기록의 불일치가 숨겨진다. 입력 금액뿐
        // 아니라 원본과 두 결과를 모두 검증한 뒤 엔티티를 수정한다. 예를 들어 큰
        // 정수에서 1e-9를 빼면 DB에는 들어가도 Rust의 96비트 계수를 넘을 수 있다.
        BigDecimal available = ExactAmount.requireNonNegative(balance.getAvailable(), "사용 가능 잔액");
        BigDecimal locked = ExactAmount.requireNonNegative(balance.getLocked(), "예약 잔액");
        if (available.compareTo(amount) < 0) {
            throw new RuntimeException(String.format(
                "잔고 부족: userId=%d, mint=%s, available=%s, required=%s",
                userId, mint, available, amount));
        }
        BigDecimal nextAvailable = ExactAmount.requireNonNegative(available.subtract(amount), "예약 후 사용 가능 잔액");
        BigDecimal nextLocked = ExactAmount.requireNonNegative(locked.add(amount), "예약 후 예약 잔액");
        balance.setAvailable(nextAvailable);
        balance.setLocked(nextLocked);
        userBalanceRepository.save(balance);
        
        log.info("[Balance] 잔고 lock: userId={}, mint={}, amount={}, available={} -> {}, locked={} -> {}", 
            userId, mint, amount, 
            balance.getAvailable().add(amount), balance.getAvailable(),
            balance.getLocked().subtract(amount), balance.getLocked());
    }
    
}
