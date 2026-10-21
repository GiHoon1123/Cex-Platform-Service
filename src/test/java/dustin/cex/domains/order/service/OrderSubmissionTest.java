package dustin.cex.domains.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.exception.OrderSubmissionUncertainException;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.shared.http.EngineHttpClient;
import dustin.cex.shared.kafka.KafkaEventProducer;

/**
 * 실제 JPA 저장과 커밋을 검증하면서 외부 엔진·Kafka를 격리한다.
 * 테스트 전체를 트랜잭션으로 감싸면 서비스의 커밋 전 HTTP 호출 버그를 숨기므로
 * NOT_SUPPORTED로 테스트 트랜잭션을 끈다. H2에 만들어진 두 테이블만 사용한다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = OrderSubmissionTest.Persistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderSubmissionTest {
    @Configuration
    @EntityScan(basePackageClasses = {Order.class, UserBalance.class})
    @EnableJpaRepositories(basePackageClasses = {OrderRepository.class, UserBalanceRepository.class})
    @Import(OrderService.class)
    static class Persistence {}

    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired dustin.cex.domains.order.repository.OrderSubmissionRepository submissions;
    @Autowired UserBalanceRepository balances;
    @Autowired PlatformTransactionManager transactions;
    @MockBean EngineHttpClient engine;
    @MockBean KafkaEventProducer kafka;

    @BeforeEach
    void seedIsolatedBalances() {
        submissions.deleteAll();
        orders.deleteAll();
        balances.deleteAll();
        balances.save(UserBalance.builder().userId(101L).mintAddress("USDT")
                .available(new BigDecimal("1000")).locked(new BigDecimal("25")).build());
        balances.save(UserBalance.builder().userId(101L).mintAddress("SOL")
                .available(new BigDecimal("10")).locked(new BigDecimal("2")).build());
    }

    static Stream<RuntimeException> unknownFailures() {
        return Stream.of(
                new ResourceAccessException("response lost", new SocketTimeoutException()),
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR),
                new HttpClientErrorException(HttpStatus.BAD_REQUEST),
                new IllegalStateException("unexpected client failure"));
    }

    @ParameterizedTest
    @MethodSource("unknownFailures")
    void uncertainSubmissionPreservesCommittedOrderAndReservation(RuntimeException failure) {
        whenSubmit().thenThrow(failure);

        var error = catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit()));
        assertThat(error).isInstanceOf(OrderSubmissionUncertainException.class).hasCause(failure);
        assertThat(((OrderSubmissionUncertainException) error).getOrderId())
                .isEqualTo(orders.findAll().getFirst().getId());

        // 초기 locked=25도 보존해야 한다. 이번 주문 200만 더하고 돌려주지 않는다.
        assertPendingReservation("USDT", "800", "225");
        assertOneSubmissionAndNoCreatedEvent();
    }

    @Test
    void falseResponseDoesNotProveRejectionOrReleaseFunds() {
        whenSubmit().thenReturn(false);
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit()))).isNotNull();
        assertPendingReservation("USDT", "800", "225");
        assertOneSubmissionAndNoCreatedEvent();
    }

    @Test
    void sellTimeoutPreservesBaseAssetReservation() {
        whenSubmit().thenThrow(new ResourceAccessException("timeout"));
        var request = buyLimit();
        request.setOrderType("sell");
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), request))).isNotNull();
        assertPendingReservation("SOL", "8", "4");
    }

    @Test
    void marketBuyTimeoutPreservesExactQuoteReservation() {
        whenSubmit().thenThrow(new ResourceAccessException("timeout"));
        var request = buyLimit();
        request.setOrderSide("market");
        request.setPrice(null);
        request.setAmount(null);
        request.setQuoteAmount(new BigDecimal("123.250000001"));
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), request))).isNotNull();
        assertPendingReservation("USDT", "876.749999999", "148.250000001");
    }

    @Test
    void engineCallObservesCommittedReservationOutsideTransaction() {
        whenSubmit().thenAnswer(invocation -> {
            // 엔진 호출 시 열린 서비스 트랜잭션이 없어야 한다. 이후 조회는 별도 DB 트랜잭션이다.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertPendingReservation("USDT", "800", "225");
            return true;
        });
        var response = service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit());
        assertThat(response.response().getOrder().getId()).isEqualTo(orders.findAll().getFirst().getId().toString());
        verify(kafka).publishOrderCreated(anyString());
    }

    @Test
    void lateResponseFailureDoesNotOverwriteAlreadyFilledOrder() {
        whenSubmit().thenAnswer(invocation -> {
            // 엔진이 처리했고 결과 소비자가 먼저 DB 상태를 바꾼 뒤 응답만 유실된 상황이다.
            var order = orders.findById(invocation.getArgument(0, Long.class)).orElseThrow();
            order.setStatus("filled");
            order.setFilledAmount(order.getAmount());
            orders.save(order);
            throw new ResourceAccessException("response lost after processing");
        });
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit()))).isNotNull();
        assertThat(orders.findAll().getFirst().getStatus()).isEqualTo("filled");
        assertBalance("USDT", "800", "225");
        assertOneSubmissionAndNoCreatedEvent();
    }

    @Test
    void insufficientBalanceRollsBackOrderBeforeSending() {
        var request = buyLimit();
        request.setAmount(new BigDecimal("100"));
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), request))).isNotNull();
        assertThat(orders.count()).isZero();
        assertBalance("USDT", "1000", "25");
        verify(engine, never()).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void ambientTransactionIsRejectedBeforeAnyFinancialWrite() {
        // 외부 트랜잭션에 합류하면 HTTP 이전 커밋 보장이 깨지므로 호출 경계를 거부한다.
        // 외부 롤백 때문에 엔진에 전송한 주문 기록이 사라지는 상황을 막는다.
        var error = catchThrowable(() -> new TransactionTemplate(transactions).execute(status ->
                service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit())));
        assertThat(error).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(orders.count()).isZero();
        assertBalance("USDT", "1000", "25");
        verify(engine, never()).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void omittedQuoteUsesSameAssetForStorageReservationAndSubmission() {
        whenSubmit().thenReturn(true);
        var request = buyLimit();
        request.setQuoteMint(null);
        var response = service.createOrder(101L, java.util.UUID.randomUUID(), request);
        assertThat(response.response().getOrder().getQuoteMint()).isEqualTo("USDT");
        assertPendingReservation("USDT", "800", "225");
        verify(engine).submitOrder(anyLong(), eq(101L), eq("buy"), eq("limit"), eq("SOL"),
                eq("USDT"), eq("100"), eq("2"), isNull());
    }

    @Test
    void createdEventFailureDoesNotUndoOrderOrRepeatSubmission() {
        whenSubmit().thenReturn(true);
        doThrow(new IllegalStateException("broker unavailable")).when(kafka).publishOrderCreated(anyString());
        var response = service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit());
        assertThat(response.response().getOrder().getStatus()).isEqualTo("pending");
        assertPendingReservation("USDT", "800", "225");
        verify(engine, times(1)).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void unknownOrderCannotBeReadByAnotherOwner() {
        whenSubmit().thenThrow(new ResourceAccessException("response lost"));
        catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit()));
        var id = orders.findAll().getFirst().getId();
        assertThat(service.getOrder(101L, id).getOrder().getId()).isEqualTo(id.toString());
        assertThat(catchThrowable(() -> service.getOrder(102L, id))).isNotNull();
    }

    @Test
    void nextOrderCannotSpendFundsReservedByUnknownSubmission() {
        whenSubmit().thenThrow(new ResourceAccessException("response lost"));
        catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), buyLimit()));
        var next = buyLimit();
        next.setAmount(new BigDecimal("9"));
        // 200을 먼저 예약했으므로 남은 800으로 900짜리 주문을 만들 수 없어야 한다.
        assertThat(catchThrowable(() -> service.createOrder(101L, java.util.UUID.randomUUID(), next))).isNotNull();
        assertPendingReservation("USDT", "800", "225");
        assertOneSubmissionAndNoCreatedEvent();
    }

    private org.mockito.stubbing.OngoingStubbing<Boolean> whenSubmit() {
        return when(engine.submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any()));
    }

    private void assertOneSubmissionAndNoCreatedEvent() {
        verify(engine, times(1)).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(kafka);
    }

    private void assertPendingReservation(String mint, String available, String locked) {
        assertThat(orders.findAll()).singleElement().satisfies(order ->
                assertThat(order.getStatus()).isEqualTo("pending"));
        assertBalance(mint, available, locked);
    }

    private void assertBalance(String mint, String available, String locked) {
        var balance = balances.findByUserIdAndMintAddress(101L, mint).orElseThrow();
        assertThat(balance.getAvailable()).isEqualByComparingTo(available);
        assertThat(balance.getLocked()).isEqualByComparingTo(locked);
    }

    private CreateOrderRequest buyLimit() {
        return CreateOrderRequest.builder().orderType("buy").orderSide("limit")
                .baseMint("SOL").quoteMint("USDT").price(new BigDecimal("100"))
                .amount(new BigDecimal("2")).build();
    }
}
