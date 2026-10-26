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

import tools.jackson.databind.ObjectMapper;

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
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
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
    private final OrderCancellationService cancellations;
    private final KafkaEventProducer kafkaEventProducer;
    private final dustin.cex.domains.fee.service.FeeConfigService feeConfigService;
    private final TransactionTemplate transactionTemplate;
    private final dustin.cex.domains.order.repository.OrderDispatchRepository dispatches;
    private final OrderDispatchService dispatch;
    private final dustin.cex.domains.ledger.LedgerService ledger;
    private final dustin.cex.domains.security.TradingAccessPolicy access;
    
    // Jackson 3에는 시간 타입 지원이 기본 포함된다. JSON 설정을 빌더에서 확정해 공유한다.
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    
    /**
     * 주입한 의존성과 주문 접수용 트랜잭션을 초기화한다.
     */
    public OrderService(
            OrderRepository orderRepository,
            OrderSubmissionRepository submissionRepository,
            UserBalanceRepository userBalanceRepository,
            OrderCancellationService cancellations,
            KafkaEventProducer kafkaEventProducer,
            PlatformTransactionManager transactionManager,
            dustin.cex.domains.fee.service.FeeConfigService feeConfigService,
            dustin.cex.domains.order.repository.OrderDispatchRepository dispatches, OrderDispatchService dispatch, dustin.cex.domains.ledger.LedgerService ledger, dustin.cex.domains.security.TradingAccessPolicy access) {
        this.ledger=ledger;this.access=access;
        this.dispatches=dispatches; this.dispatch=dispatch;
        this.feeConfigService = feeConfigService;
        this.orderRepository = orderRepository;
        this.submissionRepository = submissionRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.cancellations = cancellations;
        this.kafkaEventProducer = kafkaEventProducer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // 접수와 권한 변경은 같은 고객 행으로 순서를 정한다. 대기 후의 권한 조회가 새 커밋을 읽도록 고정한다.
        this.transactionTemplate.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    
    /**
     * 주문과 자산 예약을 먼저 커밋한 다음 엔진을 호출한다.
     *
     * HTTP 실패는 엔진 미처리의 증거가 아니다. 요청 처리 후 응답만 유실될 수 있어
     * 이미 커밋한 주문·예약을 임의로 되돌리면 같은 자산을 재사용하게 된다.
     * 따라서 실패 시 상태를 보존하고, 주문 ID를 포함한 결과 불명 예외를 반환한다.
     * 확정 거절·취소에 따른 해제는 영속적인 엔진 결과 소비에서만 처리한다.
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
                // 제한 변경과 신규 예약이 사용자 행에서 순서를 정한다. 기존 접수 재조회는 허용한다.
                access.requireNewOrder(userId);
                Order order = orderRepository.save(buildOrderEntity(userId, command));
                // 원본 명령·키·주문·예약을 같은 트랜잭션에 기록한다. 유일 제약 경쟁에서
                // 진 요청은 여기서 실패하며 주문 행도 롤백된다. 잔액을 먼저 변경하지 않는다.
                submissionRepository.saveAndFlush(new OrderSubmission(userId, requestId, order.getId(), command,
                        feeConfigService.getFeeConfig(command.baseMint(), command.quoteMint()).getFeeRate()));
                lockBalance(userId, command.reservationMint(), command.reservationAmount());
                // 주문·예약·전송 기록과 원장 이동은 함께 커밋하거나 함께 롤백한다.
                ledger.record("reserve:"+order.getId(),"RESERVE",java.util.List.of(
                    dustin.cex.domains.ledger.LedgerService.p(userId,"AVAILABLE",command.reservationMint(),command.reservationAmount().negate()),
                    dustin.cex.domains.ledger.LedgerService.p(userId,"LOCKED",command.reservationMint(),command.reservationAmount())));
                dispatches.save(new dustin.cex.domains.order.model.entity.OrderDispatch(order));
                access.recordReservation(userId,order.getId());
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
            // 정상 경로는 지금처럼 즉시 HTTP로 전달한다. 실패·종료 이후에는 동일 원본의
            // 영속 전송 기록을 별도 워커가 이어받는다.
            boolean success=dispatch.submitImmediately(orderId);

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
                // 예산 기반 주문에만 불변 원본을 읽는다. 수량 주문의 목록 조회에 추가 조회를 만들지 않는다.
                .quoteAmount("market".equals(order.getOrderSide()) && "buy".equals(order.getOrderType())
                        ? submissionRepository.findByOrderId(order.getId()).orElseThrow().getQuoteAmount() : null)
                .filledAmount(order.getFilledAmount())
                .filledQuoteAmount(order.getFilledQuoteAmount())
                .status(order.getStatus())
                .submissionStatus(dispatches.findById(order.getId()).orElseThrow().getConfirmedAt()==null ? "pending" : "recorded")
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
     * 본인 주문을 확인한 뒤 엔진에 취소 명령을 보낸다. 디비 락은 조회 트랜잭션에만
     * 유지하고 네트워크 대기는 밖에서 수행한다. 최종 주문 상태·예약금 해제는
     * 순서가 보장된 엔진 결과 소비자가 함께 확정한다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        // 영속 취소 요청이 먼저 확정된다. HTTP 응답 유실은 재확인 대기이며 주문 종료/예약 해제의 근거가 아니다.
        cancellations.request(userId,orderId);
        Order latest=orderRepository.findByUserIdAndId(userId,orderId).orElseThrow();
        return OrderResponse.builder().order(convertToDto(latest))
            .message("취소 요청 접수. 주문 상태와 예약 해제는 엔진 결과 수신 후 반영됩니다.").build();
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
