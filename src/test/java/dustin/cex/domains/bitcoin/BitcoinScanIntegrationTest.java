package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.security.AuditService;

/** 소유한 실제 노드·PG에서 블록 완료/재시작/포화된 주소 묶음/동시 확정/원본 보존을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinScanIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);
        r.add("bitcoin.rpc-url",()->System.getenv("CEX_BITCOIN_TEST_URL"));r.add("bitcoin.rpc-user",()->System.getenv("CEX_BITCOIN_TEST_USER"));
        r.add("bitcoin.rpc-password",()->System.getenv("CEX_BITCOIN_TEST_PASSWORD"));r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean dustin.cex.domains.kyc.DiditClient didit;
    @MockitoSpyBean BitcoinBlockDiscovery discovery;
    @MockitoSpyBean BitcoinRpcClient rpc;
    @MockitoSpyBean AuditService audit;
    @Autowired BitcoinScanService scans;
    @Autowired BitcoinSettings config;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_block_scans",Long.class);}
    long head(){return jdbc.queryForObject("SELECT scan_id FROM bitcoin_scan_heads",Long.class);}
    long height(){return jdbc.queryForObject("SELECT height FROM bitcoin_block_scans WHERE id=?",Long.class,head());}
    void address(String script,String address){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",
            UUID.randomUUID(),user.getId(),address,script);
    }
    @Test @org.junit.jupiter.api.Order(1) void noAddressDoesNotAdvanceThenGenesisCommitsSnapshotSourceHeadAndAudit(){
        assertTrue(scans.scanNext().isEmpty());assertEquals(0,count());
        address(System.getenv("CEX_BITCOIN_TEST_SCRIPT"),System.getenv("CEX_BITCOIN_TEST_ADDRESS"));
        var first=scans.scanNext().orElseThrow();assertEquals(0,first.height());assertEquals(first.id(),head());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_registries",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_BLOCK_SCANNED' AND detail=?",Long.class,"SCAN:"+first.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(2) void freshServiceResumesCommittedNextBlockAndSharesRegistry(){
        var fresh=new BitcoinScanService(rpc,discovery,config,jdbc,json,audit,manager);
        var next=fresh.scanNext().orElseThrow();assertEquals(1,next.height());assertEquals(next.id(),head());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_registries",Long.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT s.parent_hash=p.block_hash FROM bitcoin_block_scans s JOIN bitcoin_block_scans p ON p.id=s.previous_scan_id WHERE s.id=?",Boolean.class,next.id())));
    }
    @Test @org.junit.jupiter.api.Order(3) void secondPageFailureNeverSkipsAddressesOrCommitsPartialBlock(){
        // 201개 주소는 두 번의 실제 블록 판독을 요구한다. 추가 조건은 시험 전용 가상 주소이며 실제 서명/송금은 하지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(t->{for(int i=1;i<=200;i++)address("0014"+String.format("%040x",i),"isolated-unused-"+i);});
        long previous=head(),before=count();var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)throw BitcoinRpcClient.unavailable();return call.callRealMethod();}).when(discovery).scan(anyLong(),anyList());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->scans.scanNext()).getStatusCode().value());
        assertEquals(2,calls.get());assertEquals(previous,head());assertEquals(before,count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_registries",Long.class));
        reset(discovery);var next=scans.scanNext().orElseThrow();assertEquals(2,next.height());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_registries",Long.class));
        assertEquals(201,jdbc.queryForObject("SELECT jsonb_array_length(addresses) FROM bitcoin_scan_registries ORDER BY id DESC LIMIT 1",Integer.class));
    }
    @Test @org.junit.jupiter.api.Order(4) void auditFailureRollsBackCompletedSourceAndHead(){
        long previous=head(),before=count();
        doThrow(new IllegalStateException("격리 감사 장애")).when(audit).record(isNull(),eq("BTC_BLOCK_SCANNED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->scans.scanNext());assertEquals(previous,head());assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(5) void concurrentScansHaveOneWinnerAndLoserCannotOverwriteHead()throws Exception{
        long before=count();var arrived=new CountDownLatch(2);var firstPageCalls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{var value=call.callRealMethod();if(firstPageCalls.incrementAndGet()<=2){arrived.countDown();assertTrue(arrived.await(10,TimeUnit.SECONDS));}return value;}).when(discovery).scan(anyLong(),anyList());
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> run=()->{try{scans.scanNext();return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}};
            var a=pool.submit(run);var b=pool.submit(run);assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(before+1,count());
    }
    @Test @org.junit.jupiter.api.Order(6) void wrongParentOrPageTipNeverChangesCheckpoint(){
        long previous=head(),before=count();
        for(boolean parent:List.of(true,false)){
            doAnswer(call->{var b=(BitcoinBlockDiscovery.Block)call.callRealMethod();return new BitcoinBlockDiscovery.Block(b.network(),b.height(),b.hash(),parent?"11".repeat(32):b.previousHash(),parent?b.tipHash():"22".repeat(32),b.tipHeight(),b.received());}).when(discovery).scan(anyLong(),anyList());
            assertEquals(503,assertThrows(ResponseStatusException.class,()->scans.scanNext()).getStatusCode().value());assertEquals(previous,head());assertEquals(before,count());
        }
    }
    @Test @org.junit.jupiter.api.Order(7) void surroundingDatabaseTransactionIsRejectedBeforeNode(){
        clearInvocations(rpc);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->scans.scanNext()));verifyNoInteractions(rpc);
    }
    @Test @org.junit.jupiter.api.Order(8) void originalRegistriesBlocksAndCurrentPointerCannotBeChangedDeletedOrReactivated(){
        long previous=head();
        for(String table:List.of("bitcoin_scan_registries","bitcoin_block_scans","bitcoin_scan_heads")){
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_scan_registries SET wallet='other'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_block_scans SET height=height+1"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_scan_heads SET scan_id=(SELECT min(id) FROM bitcoin_block_scans)"));
        assertEquals(previous,head());
    }
    @Test @org.junit.jupiter.api.Order(9) void allRemainingRealBlocksFindOutpointAndTipIsIdleWithoutDuplicateSource(){
        int completed=0;while(scans.scanNext().isPresent()){assertTrue(++completed<200);}
        assertEquals(104,height());long previous=head(),before=count();assertTrue(scans.scanNext().isEmpty());assertEquals(before,count());assertEquals(previous,head());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) r
             WHERE r->>'txid'=? AND (r->>'amount')::numeric=0.12345678
            """,Long.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(10) void changedCanonicalHashStopsWithoutReplacingOriginalCheckpoint(){
        long previous=head(),before=count(),at=height();
        doReturn(json.readTree("\""+"33".repeat(32)+"\"")).when(rpc).call("getblockhash",List.of(at));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->scans.scanNext()).getStatusCode().value());assertEquals(before,count());assertEquals(previous,head());
    }
    @Test @org.junit.jupiter.api.Order(11) void unavailableNodeDoesNotProduceIdleSuccess(){
        long previous=head();doThrow(BitcoinRpcClient.unavailable()).when(rpc).ready();
        assertEquals(503,assertThrows(ResponseStatusException.class,()->scans.scanNext()).getStatusCode().value());assertEquals(previous,head());
    }
    @Test @org.junit.jupiter.api.Order(12) void databaseGuardRejectsFreshSourceThatSkipsBlock(){
        long previous=head(),before=count();
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            long wrong=jdbc.queryForObject("""
                INSERT INTO bitcoin_block_scans(network,wallet,previous_scan_id,height,block_hash,parent_hash,tip_hash,tip_height,registry_id,received)
                 SELECT network,wallet,id,height+2,block_hash,block_hash,tip_hash,tip_height+2,registry_id,'[]'::jsonb
                  FROM bitcoin_block_scans WHERE id=? RETURNING id
                """,Long.class,previous);
            jdbc.update("UPDATE bitcoin_scan_heads SET scan_id=?",wrong);
        }));
        assertEquals(previous,head());assertEquals(before,count());
    }
}
