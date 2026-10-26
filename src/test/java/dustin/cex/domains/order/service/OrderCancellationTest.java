package dustin.cex.domains.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import dustin.cex.domains.order.model.entity.*;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.*;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.shared.http.EngineHttpClient;

/** H2 실제 커밋과 새 서비스 인스턴스로 요청/재시도 보존을 검증한다. PG의 필수 감사/불변 트리거는 별도 통합 검증이다. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes=OrderSubmissionTest.Persistence.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class OrderCancellationTest {
    @Autowired OrderService orders;@Autowired OrderCancellationService service;@Autowired OrderRepository rows;
    @Autowired OrderSubmissionRepository submissions;@Autowired OrderDispatchRepository dispatches;@Autowired OrderCancellationRepository cancellations;
    @Autowired UserBalanceRepository balances;@Autowired PlatformTransactionManager manager;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService feeService;
    @MockitoBean dustin.cex.domains.ledger.LedgerService ledger;
    @MockitoBean dustin.cex.domains.security.TradingAccessPolicy access;
    @MockitoBean dustin.cex.domains.security.AuditService cancelAudit;
    @MockitoBean(name="orderCancellationSchemaInitializer") OrderCancellationSchemaInitializer cancelSchema;
    @MockitoBean EngineHttpClient engine;@MockitoBean dustin.cex.shared.kafka.KafkaEventProducer kafka;
    Order order;
    @BeforeEach void fixture(){
        cancellations.deleteAll();dispatches.deleteAll();submissions.deleteAll();rows.deleteAll();balances.deleteAll();
        order=rows.saveAndFlush(Order.builder().userId(101L).baseMint("BTC").quoteMint("USDT").orderType("sell").orderSide("limit").price(new BigDecimal("10000")).amount(BigDecimal.ONE).build());
        submissions.saveAndFlush(new OrderSubmission(101L,UUID.randomUUID(),order.getId(),new dustin.cex.domains.order.model.OrderCommand("sell","limit","BTC","USDT",new BigDecimal("10000"),BigDecimal.ONE,null),BigDecimal.ZERO));
        dispatches.saveAndFlush(new OrderDispatch(order));balances.saveAndFlush(UserBalance.builder().userId(101L).mintAddress("BTC").available(BigDecimal.TEN).locked(new BigDecimal("2")).build());
    }
    @AfterEach void financialStateIsNotRewritten(){assertThat(balances.findByUserIdAndMintAddress(101L,"BTC").orElseThrow().getLocked()).isEqualByComparingTo("2");verifyNoInteractions(kafka);}
    @Test void requestAndClaimAreCommittedBeforeTheExternalCancellation(){
        when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var stored=cancellations.findById(order.getId()).orElseThrow();assertThat(stored.getAttempts()).isEqualTo(1);assertThat(stored.getAcknowledgedAt()).isNull();return true;});
        assertThat(orders.cancelOrder(101L,order.getId()).getOrder().getStatus()).isEqualTo("pending");assertThat(cancellations.findById(order.getId()).orElseThrow().getAcknowledgedAt()).isNotNull();assertThat(rows.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("pending");
    }
    @Test void aFreshDispatchServiceRetriesTheSamePersistedUnknownCancellation(){
        when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenThrow(new IllegalStateException("응답 유실")).thenReturn(true);orders.cancelOrder(101L,order.getId());
        var restarted=new OrderCancellationService(rows,submissions,cancellations,engine,access,cancelAudit,manager);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(()->{restarted.retryNext();assertThat(cancellations.findById(order.getId()).orElseThrow().getAcknowledgedAt()).isNotNull();});
        verify(engine,times(2)).cancelOrder(order.getId(),101L,"BTC/USDT");assertThat(cancellations.count()).isEqualTo(1);assertThat(rows.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("pending");
    }
    @Test void repeatedAcceptedCancellationDoesNotSendAnotherFinancialCommand(){when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenReturn(true);orders.cancelOrder(101L,order.getId());orders.cancelOrder(101L,order.getId());assertThat(cancellations.count()).isEqualTo(1);verify(engine,times(1)).cancelOrder(order.getId(),101L,"BTC/USDT");}
    @Test void anAmbientTransactionIsRejectedBeforeCreatingAnIntent(){assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(t->orders.cancelOrder(101L,order.getId()))).isInstanceOf(IllegalTransactionStateException.class);assertThat(cancellations.count()).isZero();verifyNoInteractions(engine);}
    @Test void anotherCustomerCannotCreateOrSendTheCancellation(){assertThatThrownBy(()->orders.cancelOrder(102L,order.getId())).isInstanceOf(IllegalArgumentException.class);assertThat(cancellations.count()).isZero();verifyNoInteractions(engine);}
    @ParameterizedTest @ValueSource(strings={"filled","rejected","expired","cancelled"})
    void terminalOrdersDoNotCreateNewCancellationWork(String status){order.setStatus(status);rows.saveAndFlush(order);if(status.equals("cancelled"))assertThat(orders.cancelOrder(101L,order.getId()).getOrder().getStatus()).isEqualTo(status);else assertThatThrownBy(()->orders.cancelOrder(101L,order.getId())).isInstanceOf(IllegalArgumentException.class);assertThat(cancellations.count()).isZero();verifyNoInteractions(engine);}
    @Test void requestAuditFailurePreventsEveryExternalCall(){doThrow(new IllegalStateException("격리 취소 요청 감사 실패")).when(access).recordCancellationIntent(101L,order.getId());assertThatThrownBy(()->orders.cancelOrder(101L,order.getId())).isInstanceOf(IllegalStateException.class);assertThat(cancellations.count()).isZero();verifyNoInteractions(engine);}
    @Test void claimAuditFailureLeavesTheCommittedIntentRetryableWithoutSending(){
        doThrow(new IllegalStateException("격리 취소 시도 감사 실패")).when(cancelAudit).record(isNull(),eq("ORDER_CANCEL_ATTEMPT_STARTED"),anyString(),anyString(),anyString());assertThat(orders.cancelOrder(101L,order.getId()).getOrder().getStatus()).isEqualTo("pending");assertThat(cancellations.findById(order.getId()).orElseThrow().getAttempts()).isZero();verifyNoInteractions(engine);
        doNothing().when(cancelAudit).record(isNull(),eq("ORDER_CANCEL_ATTEMPT_STARTED"),anyString(),anyString(),anyString());when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenReturn(true);orders.cancelOrder(101L,order.getId());assertThat(cancellations.findById(order.getId()).orElseThrow().getAcknowledgedAt()).isNotNull();
    }
    @Test void anOldResponseCannotOverwriteANewerLeasedAttempt(){var initial=Instant.parse("2026-01-01T00:00:00Z");var original=submissions.findByOrderId(order.getId()).orElseThrow();var queue=new OrderCancellation(order,original,initial);assertThat(queue.claim(initial,true)).isTrue();assertThat(queue.claim(initial.plusSeconds(31),false)).isTrue();queue.finish(1,true,initial.plusSeconds(32));assertThat(queue.getAcknowledgedAt()).isNull();queue.finish(2,true,initial.plusSeconds(33));assertThat(queue.getAcknowledgedAt()).isEqualTo(initial.plusSeconds(33));}
    @Test void concurrentCustomerRequestsHaveOneProducerAndOneOriginal()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return true;});
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->orders.cancelOrder(101L,order.getId()));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            try{assertThat(orders.cancelOrder(101L,order.getId()).getOrder().getStatus()).isEqualTo("pending");}finally{release.countDown();}assertThat(first.get(5,TimeUnit.SECONDS).getOrder().getStatus()).isEqualTo("pending");
        }assertThat(cancellations.count()).isEqualTo(1);verify(engine,times(1)).cancelOrder(order.getId(),101L,"BTC/USDT");
    }
    @Test void theOutputWorkerMayClaimTheNewIntentBeforeTheApiWithoutASecondHttpCall()throws Exception{
        // 커밋 직후 별도 스레드가 먼저 선점해도 API가 같은 명령을 중복 전송하지 않아야 한다.
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var task=new java.util.concurrent.atomic.AtomicReference<Future<Boolean>>();
        try(var pool=Executors.newSingleThreadExecutor()){
            when(engine.cancelOrder(order.getId(),101L,"BTC/USDT")).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return true;});
            doAnswer(call->{TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){task.set(pool.submit(service::retryNext));try{assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("격리 취소 선점 검증 중단",e);}}
            });return null;}).when(access).recordCancellationIntent(101L,order.getId());
            try{assertThat(orders.cancelOrder(101L,order.getId()).getOrder().getStatus()).isEqualTo("pending");assertThat(cancellations.findById(order.getId()).orElseThrow().getAttempts()).isEqualTo(1);}finally{release.countDown();}
            assertThat(task.get().get(5,TimeUnit.SECONDS)).isTrue();
        }finally{release.countDown();}
        assertThat(cancellations.count()).isEqualTo(1);assertThat(cancellations.findById(order.getId()).orElseThrow().getAcknowledgedAt()).isNotNull();verify(engine,times(1)).cancelOrder(order.getId(),101L,"BTC/USDT");
    }

}
