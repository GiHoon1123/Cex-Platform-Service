package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.security.AuditService;

/** 노드 없이 실제 PG의 최초 결합·동시 시작·영속 감사·잘못된 체인 직접 삽입을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BitcoinNetworkIdentityIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);for(String key:List.of("rpc-url","rpc-user","rpc-password","wallet"))r.add("bitcoin."+key,()->"");r.add("bitcoin.network",()->"REGTEST");
    }
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean dustin.cex.domains.kyc.DiditClient didit;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AuditService audit;
    @Autowired BitcoinNetworkIdentity unconfigured;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    BitcoinSettings configured(String network){return new BitcoinSettings("http://127.0.0.1:1","isolated","isolated-network-password-at-least-32-bytes","watch",network);}
    BitcoinNetworkIdentity identity(BitcoinSettings settings,AuditService recorder){return new BitcoinNetworkIdentity(jdbc,settings,recorder,manager);}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_network_identity",Long.class);}
    @AfterEach void noFinancialDelivery(){verifyNoInteractions(engine);}
    @Test @Order(1) void emptyUnconfiguredDatabaseRemainsUnboundAndRejectsNewFacts(){
        assertEquals(0,count());unconfigured.verifyOrBind();assertEquals(0,count());
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("network-boundary@example.test").passwordHash("격리 미사용 해시").build());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','watch','격리 수신 주소',?)",UUID.randomUUID(),user.getId(),"0014"+"0".repeat(40)));
    }
    @Test @Order(2) void mandatoryBindingAuditFailureRollsBackIdentity(){
        var failed=mock(AuditService.class);doThrow(new IllegalStateException("격리 결합 감사 장애")).when(failed).record(any(),anyString(),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->identity(configured("REGTEST"),failed).verifyOrBind());assertEquals(0,count());
    }
    @Test @Order(3) void concurrentFirstConfigurationBindsExactlyOnceWithoutNodeCall()throws Exception{
        var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Void> bind=()->{assertTrue(ready.await(5,TimeUnit.SECONDS));identity(configured("REGTEST"),audit).verifyOrBind();return null;};
            var a=pool.submit(bind);var b=pool.submit(bind);ready.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,count());assertEquals("bitcoin-regtest",jdbc.queryForObject("SELECT network FROM bitcoin_network_identity",String.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_RUNTIME_NETWORK_BOUND'",Long.class));
    }
    @Test @Order(4) void wrongConfiguredOrOfflineNetworkCannotReuseBoundDatabase(){
        assertThrows(IllegalStateException.class,()->identity(configured("MAINNET"),audit).verifyOrBind());assertThrows(IllegalStateException.class,()->identity(new BitcoinSettings("","","","","MAINNET"),audit).verifyOrBind());
        unconfigured.verifyOrBind();assertEquals(1,count());
    }
    @Test @Order(5) void boundIdentityAndEverySourceTableRejectDirectNetworkBypass(){
        for(String sql:List.of("UPDATE bitcoin_network_identity SET network=network","DELETE FROM bitcoin_network_identity","TRUNCATE bitcoin_network_identity"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        String hash="0".repeat(64),id=UUID.randomUUID().toString();
        List<String> statements=List.of(
            "INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES('"+id+"',1,'bitcoin-mainnet','watch','격리 주소','0014"+"0".repeat(40)+"')",
            "INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) VALUES('bitcoin-mainnet','watch','"+hash+"','CONFIRMED',6,false,'"+hash+"',0,'"+hash+"',5,'[]',clock_timestamp())",
            "INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES('bitcoin-mainnet','watch','"+hash+"','[{}]')",
            "INSERT INTO bitcoin_block_scans(network,wallet,height,block_hash,tip_hash,tip_height,registry_id,received) VALUES('bitcoin-mainnet','watch',0,'"+hash+"','"+hash+"',0,1,'[]')",
            "INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id) VALUES('"+id+"','bitcoin-mainnet','"+hash+"',0,'"+id+"',1,'BTC',1,1)");
        for(String sql:statements){var error=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));assertTrue(error.getMostSpecificCause().getMessage().contains("네트워크"));}
        assertEquals(1,count());
    }
    @Test @Order(6) void sameNetworkStartupPreservesExistingFactAndBindingAudit(){
        long user=jdbc.queryForObject("SELECT id FROM users WHERE email='network-boundary@example.test'",Long.class);
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','watch','정상 결합 조회 주소',?)",UUID.randomUUID(),user,"0014"+"1".repeat(40));
        identity(configured("REGTEST"),audit).verifyOrBind();unconfigured.verifyOrBind();assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_addresses",Long.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_RUNTIME_NETWORK_BOUND'",Long.class));
    }
    @Test @Order(7) void ambientTransactionCannotOwnRuntimeBinding(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->unconfigured.verifyOrBind()));assertEquals(1,count());}
}
