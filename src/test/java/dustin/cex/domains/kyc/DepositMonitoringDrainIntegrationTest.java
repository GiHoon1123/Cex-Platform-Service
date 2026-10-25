package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.monitoring.DepositMonitoringService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 장수 워커가 여러 금융 입력을 순서대로 확정하는지 새 PG에서 검증한다. 노드·엔진·Kafka는 호출하지 않는다. */
class DepositMonitoringDrainIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-monitoring-drain-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);}
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinSettings bitcoin;
    @Autowired DepositMonitoringService monitoring;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @MockitoBean BitcoinDepositReader reader;
    @BeforeEach void input(){
        ledger.initializeOpening();for(int i=0;i<10;i++){var r=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);pending.recognize(r.id());}
        assertEquals(0,count());
    }
    long count(){return jdbc.queryForObject("SELECT count(*) FROM deposit_monitoring_assessments WHERE user_id=?",Long.class,customer.getId());}
    AnnotationConfigApplicationContext worker(){
        var ctx=new AnnotationConfigApplicationContext();ctx.getEnvironment().setActiveProfiles("production");ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("격리 워커 설정",Map.of("bitcoin.processing.enabled","true")));
        // 부모 시험 맥락에서 실제 스키마 초기화를 완료했다. 자식은 같은 실제 서비스에 생산 프로필 워커만 연결한다.
        ctx.registerBean("depositMonitoringSchemaInitializer",Object.class,Object::new);ctx.registerBean(BitcoinSettings.class,()->bitcoin);ctx.registerBean(DepositMonitoringService.class,()->monitoring);ctx.register(BitcoinDepositMonitoringWorker.class);ctx.refresh();return ctx;
    }
    void complete(){
        await().atMost(Duration.ofSeconds(5)).until(()->count()==10);
        var original=jdbc.queryForList("SELECT input_cursor FROM deposit_monitoring_inputs WHERE user_id=? ORDER BY input_cursor",Long.class,customer.getId());
        var analyzed=jdbc.queryForList("SELECT input_cursor FROM deposit_monitoring_assessments WHERE user_id=? ORDER BY input_cursor",Long.class,customer.getId());assertEquals(original,analyzed);
        assertTrue(jdbc.queryForObject("SELECT target_cursor=observed_cursor AND lease_id IS NULL FROM deposit_monitoring_jobs WHERE user_id=?",Boolean.class,customer.getId()));
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='DEPOSIT_MONITORING_ASSESSED'",Long.class));
    }
    @AfterEach void noFinancialOrExternalEffect(){verifyNoInteractions(engine,producer,reader);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",BigDecimal.class).signum());await().atMost(Duration.ofSeconds(2)).until(()->Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("bitcoin-deposit-monitoring-")));}
    @Test void actualWorkerDrainsTenReadyInputsWithoutPerInputIdleDelay(){try(var context=worker()){complete();}}
    @Test void midDrainFailureKeepsConfirmedInputsAndResumesFromTheFailedPosition(){
        var attempts=new AtomicInteger();doAnswer(call->{if(attempts.incrementAndGet()==3)throw new IllegalStateException("격리 세 번째 탐지 감사 실패");return call.callRealMethod();}).when(audit).record(isNull(),eq("DEPOSIT_MONITORING_ASSESSED"),anyString(),anyString(),anyString());
        try(var context=worker()){
            await().atMost(Duration.ofSeconds(3)).until(()->attempts.get()==3&&count()==2);
            var confirmed=jdbc.queryForList("SELECT id FROM deposit_monitoring_assessments ORDER BY input_cursor",UUID.class);assertEquals(2,confirmed.size());
            // 금융 원본·확정 평가를 건드리지 않고 운영 임대만 만료시켜 실제 재시작 위치를 검증한다.
            jdbc.update("UPDATE deposit_monitoring_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());complete();
            assertEquals(confirmed,jdbc.queryForList("SELECT id FROM deposit_monitoring_assessments ORDER BY input_cursor LIMIT 2",UUID.class));assertEquals(11,attempts.get());
        }
    }
}
