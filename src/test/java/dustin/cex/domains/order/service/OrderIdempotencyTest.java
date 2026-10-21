package dustin.cex.domains.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.exception.OrderRequestConflictException;
import dustin.cex.domains.order.exception.OrderSubmissionUncertainException;
import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.order.repository.OrderSubmissionRepository;
import dustin.cex.shared.http.EngineHttpClient;
import dustin.cex.shared.kafka.KafkaEventProducer;

/**
 * DB 기록을 근거로 재요청을 처리하는지 확인한다. 제품 PostgreSQL 동시성은 별도
 * 프로세스 검증으로 보완하고, 여기서는 H2의 실제 유일 제약·커밋·롤백을 사용한다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = OrderSubmissionTest.Persistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderIdempotencyTest {
    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired UserBalanceRepository balances;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean OrderSubmissionRepository submissions;
    @MockBean EngineHttpClient engine;
    @MockBean KafkaEventProducer kafka;

    @BeforeEach
    void seed() {
        submissions.deleteAll();
        orders.deleteAll();
        balances.deleteAll();
        for (long user : new long[]{101, 102}) {
            balances.save(UserBalance.builder().userId(user).mintAddress("USDT")
                    .available(new BigDecimal("1000")).locked(new BigDecimal("25")).build());
        }
        when(engine.submitOrder(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void sameKeyReturnsSameOrderWithoutAnotherReservationOrSubmission() {
        var key = UUID.randomUUID();
        var first = service.createOrder(101L, key, limit());
        var repeated = service.createOrder(101L, key, limit());
        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.response().getOrder().getId()).isEqualTo(first.response().getOrder().getId());
        assertSingleReservation();
        verify(kafka, times(1)).publishOrderCreated(anyString());
    }

    @Test
    void retryAfterLostResponseFindsStoredOrderWithoutResending() {
        when(engine.submitOrder(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenThrow(new ResourceAccessException("lost response"));
        var key = UUID.randomUUID();
        var failure = catchThrowable(() -> service.createOrder(101L, key, limit()));
        assertThat(failure).isInstanceOf(OrderSubmissionUncertainException.class);
        var repeated = service.createOrder(101L, key, limit());
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.response().getOrder().getId())
                .isEqualTo(((OrderSubmissionUncertainException) failure).getOrderId().toString());
        assertSingleReservation();
        verifyNoInteractions(kafka);
    }

    static Stream<Consumer<CreateOrderRequest>> changedCommands() {
        return Stream.of(r -> r.setOrderType("sell"), r -> r.setBaseMint("BTC"),
                r -> r.setQuoteMint("USDC"), r -> r.setPrice(new BigDecimal("99")),
                r -> r.setAmount(new BigDecimal("3")),
                r -> { r.setOrderSide("market"); r.setPrice(null); r.setAmount(null); r.setQuoteAmount(BigDecimal.TEN); });
    }

    @ParameterizedTest
    @MethodSource("changedCommands")
    void sameKeyWithDifferentIntentIsRejected(Consumer<CreateOrderRequest> change) {
        var key = UUID.randomUUID();
        service.createOrder(101L, key, limit());
        var changed = limit();
        change.accept(changed);
        assertThatThrownBy(() -> service.createOrder(101L, key, changed))
                .isInstanceOf(OrderRequestConflictException.class);
        assertSingleReservation();
    }

    @Test
    void equivalentDecimalAndDefaultQuoteAreTheSameIntent() {
        var key = UUID.randomUUID();
        service.createOrder(101L, key, limit());
        var equivalent = limit();
        equivalent.setQuoteMint(null);
        equivalent.setPrice(new BigDecimal("100.00"));
        equivalent.setAmount(new BigDecimal("2.000"));
        assertThat(service.createOrder(101L, key, equivalent).created()).isFalse();
        assertSingleReservation();
    }

    @Test
    void keyIsScopedToAuthenticatedUser() {
        var key = UUID.randomUUID();
        var first = service.createOrder(101L, key, limit());
        var second = service.createOrder(102L, key, limit());
        assertThat(first.response().getOrder().getId()).isNotEqualTo(second.response().getOrder().getId());
        assertThat(orders.count()).isEqualTo(2);
        assertThat(submissions.count()).isEqualTo(2);
    }

    @Test
    void differentKeyCreatesIndependentOrder() {
        service.createOrder(101L, UUID.randomUUID(), limit());
        service.createOrder(101L, UUID.randomUUID(), limit());
        assertThat(orders.count()).isEqualTo(2);
        assertThat(balance().getAvailable()).isEqualByComparingTo("600");
        assertThat(balance().getLocked()).isEqualByComparingTo("425");
    }

    @Test
    void failedLocalReservationRollsBackTheKeyToo() {
        var key = UUID.randomUUID();
        var large = limit();
        large.setAmount(new BigDecimal("20"));
        assertThatThrownBy(() -> service.createOrder(101L, key, large)).isInstanceOf(RuntimeException.class);
        assertThat(orders.count()).isZero();
        assertThat(submissions.count()).isZero();
        var toppedUp = balance();
        toppedUp.setAvailable(new BigDecimal("3000"));
        balances.save(toppedUp);
        assertThat(service.createOrder(101L, key, large).created()).isTrue();
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void marketBudgetIsPreservedApartFromChangingOrderState() {
        var key = UUID.randomUUID();
        var market = limit();
        market.setOrderSide("market");
        market.setPrice(null);
        market.setAmount(null);
        market.setQuoteAmount(new BigDecimal("123.250000001"));
        service.createOrder(101L, key, market);
        var order = orders.findAll().getFirst();
        order.setAmount(new BigDecimal("9"));
        order.setStatus("filled");
        orders.save(order);
        var snapshot = submissions.findByUserIdAndRequestId(101L, key).orElseThrow();
        assertThat(snapshot.getAmount()).isNull();
        assertThat(snapshot.getQuoteAmount()).isEqualByComparingTo("123.250000001");
        var replay = service.createOrder(101L, key, market);
        assertThat(replay.created()).isFalse();
        assertThat(replay.response().getOrder().getStatus()).isEqualTo("filled");
        market.setQuoteAmount(new BigDecimal("124"));
        assertThatThrownBy(() -> service.createOrder(101L, key, market))
                .isInstanceOf(OrderRequestConflictException.class);
        verify(engine, times(1)).submitOrder(anyLong(), eq(101L), eq("buy"), eq("market"),
                eq("SOL"), eq("USDT"), isNull(), isNull(), eq("123.250000001"));
    }

    @Test
    void newServiceInstanceUsesDatabaseRatherThanMemoryForReplay() {
        var key = UUID.randomUUID();
        service.createOrder(101L, key, limit());
        // 메모리 상태가 없는 새 서비스 인스턴스도 DB의 같은 접수 기록을 사용한다.
        var fresh = new OrderService(orders, submissions, balances, engine, kafka, transactions);
        assertThat(fresh.createOrder(101L, key, limit()).created()).isFalse();
        assertSingleReservation();
    }

    @Test
    void duplicateWhileFirstHttpIsInFlightDoesNotWaitForOrRepeatHttp() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(engine.submitOrder(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenAnswer(invocation -> {
                    started.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return true;
                });
        var key = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.createOrder(101L, key, limit()));
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                var repeated = executor.submit(() -> service.createOrder(101L, key, limit()));
                assertThat(repeated.get(2, TimeUnit.SECONDS).created()).isFalse();
                assertSingleReservation();
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS).created()).isTrue();
        }
    }

    @Test
    void uniqueConstraintRaceRollsBackCandidateAndReadsWinnerAfterRollback() {
        var key = UUID.randomUUID();
        service.createOrder(101L, key, limit());
        var winner = submissions.findByUserIdAndRequestId(101L, key).orElseThrow();
        // 최초 조회 직후 다른 요청이 커밋한 상황을 재현한다. 최초 조회만 빈 값으로
        // 만들고 실제 중복 INSERT·유일 제약·후보 주문 롤백은 H2가 수행하게 한다.
        doReturn(Optional.empty()).doReturn(Optional.of(winner))
                .when(submissions).findByUserIdAndRequestId(101L, key);
        assertThat(service.createOrder(101L, key, limit()).created()).isFalse();
        assertSingleReservation();
    }

    @Test
    void unrelatedIntegrityErrorWithoutCommittedKeyIsNotHiddenAsReplay() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("unrelated constraint");
        doThrow(failure).when(submissions).saveAndFlush(any(dustin.cex.domains.order.model.entity.OrderSubmission.class));
        assertThat(catchThrowable(() -> service.createOrder(101L, UUID.randomUUID(), limit()))).isSameAs(failure);
        assertThat(orders.count()).isZero();
        assertThat(submissions.count()).isZero();
        assertThat(balance().getAvailable()).isEqualByComparingTo("1000");
        verify(engine, never()).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    private void assertSingleReservation() {
        assertThat(orders.count()).isEqualTo(1);
        assertThat(submissions.count()).isEqualTo(1);
        assertThat(balance().getAvailable()).isEqualByComparingTo("800");
        assertThat(balance().getLocked()).isEqualByComparingTo("225");
        verify(engine, times(1)).submitOrder(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    private UserBalance balance() {
        return balances.findByUserIdAndMintAddress(101L, "USDT").orElseThrow();
    }

    private CreateOrderRequest limit() {
        return CreateOrderRequest.builder().orderType("buy").orderSide("limit").baseMint("SOL")
                .quoteMint("USDT").price(new BigDecimal("100")).amount(new BigDecimal("2")).build();
    }
}
