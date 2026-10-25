package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.domains.travelrule.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 별도 Rust 프로세스·HTTP·저널·브로커·금융 수신을 연결한다. 체인/외부 사업자 자료만 합성 원본이다. */
@EnabledIfEnvironmentVariable(named="CEX_WIRE_KAFKA",matches="127\\.0\\.0\\.1:[0-9]+")
class BitcoinCreditWireIntegrationTest extends CodeVaspTransferTestSupport {
    static final String APPROVAL="""
        [{"entityId":"origin","legalName":"가상 송신 사업자","reviewReference":"LOCAL-ONLY","evidenceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","validFrom":"2026-01-01T00:00:00Z","validUntil":"2030-01-01T00:00:00Z"}]
        """;
    static final String SERVICE="isolated-wire-service-identity-at-least-32-bytes",AUDIT="isolated-wire-audit-identity-at-least-32-bytes";
    static int port(){try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    static final int ENGINE_PORT=port(),PROXY_PORT=port();
    @DynamicPropertySource static void wire(DynamicPropertyRegistry r){
        r.add("codevasp.approved-counterparties",()->APPROVAL);r.add("codevasp.processing.enabled",()->false);r.add("codevasp.private-transfer.enabled",()->false);
        r.add("engine.http.url",()->"http://127.0.0.1:"+PROXY_PORT);r.add("engine.service.token",()->SERVICE);r.add("engine.audit.token",()->AUDIT);r.add("engine.http.timeout",()->10000);
        r.add("spring.kafka.bootstrap-servers",()->System.getenv("CEX_WIRE_KAFKA"));
        // 제품 금융 소비 팩터리를 사용하되 시험이 시작 시점을 소유한다. HTTP 확정 전후의 PENDING 경계를 실제로 관찰한다.
        r.add("spring.kafka.listener.auto-startup",()->false);
    }
    @Override CodeVaspCounterpartyPolicy policy(){return new CodeVaspCounterpartyPolicy(APPROVAL);}
    @MockitoBean BitcoinDepositReader node;
    @Autowired dustin.cex.domains.monitoring.DepositMonitoringService monitoring;
    @Autowired BitcoinDepositReconciliationService reconciliation;
    @Autowired BitcoinCreditProcessing processing;
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired EngineDepositClient actualEngine;
    @Autowired dustin.cex.domains.reconciliation.EngineAuditClient actualAudit;
    @Autowired dustin.cex.domains.reconciliation.ReconciliationService financialReconciliation;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String,String> kafka;
    Process engineProcess;Path directory,engineLog;HttpServer proxy;ExecutorService proxyThreads;HttpClient forward;
    final AtomicBoolean loseResponse=new AtomicBoolean();
    @BeforeEach void preparedWire()throws Exception{
        assertFalse(org.mockito.Mockito.mockingDetails(actualEngine).isMock());assertFalse(org.mockito.Mockito.mockingDetails(actualAudit).isMock());
        assertNotEquals("127.0.0.1:9092",System.getenv("CEX_WIRE_KAFKA"));assertTrue(System.getenv("CEX_TEST_DATABASE_URL").endsWith("/cex"));
        var intent=intents.reserve(search.id());var person=new CodeVaspTransferDataClient.Person(declaration(CustomerDeclaration.Pep.NO).validated().fullName(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));
        when(transfers.exchange(any())).thenReturn(new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),person,person,"b".repeat(64))));
        new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments).execute(intent.id());
        assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());
        var o=jdbc.queryForMap("SELECT o.* FROM bitcoin_transaction_observations o JOIN bitcoin_observation_heads h ON h.observation_id=o.id WHERE o.txid=?",receipt.txid());
        when(node.inspect(receipt.txid())).thenReturn(new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),BitcoinDepositReader.Status.CONFIRMED,6,false,false,(String)o.get("block_hash"),((Number)o.get("block_height")).longValue(),(String)o.get("tip_hash"),((Number)o.get("tip_height")).longValue(),List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal("0.12345678")))));
        assertEquals(BitcoinDepositReconciliationService.Status.MATCHED,reconciliation.reconcile(receipt.id()).status());clearInvocations(transfers,node);
        // 현행 최초 검증이 읽는 과거 접수 표의 빈 스키마만 소유 시험 DB에 만든다.
        jdbc.execute(Files.readString(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT"),"schema/order_receipts.sql")));
        directory=Files.createTempDirectory("cex-credit-wire-");engineLog=Path.of(System.getenv("CEX_WIRE_LOG_DIR"),"engine.log");
        startEngine();forward=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",PROXY_PORT),0);proxyThreads=Executors.newSingleThreadExecutor();proxy.setExecutor(proxyThreads);
        proxy.createContext("/",exchange->{
            // 실제 HTTP 성공 뒤에만 불명 응답으로 바꾼다. 엔진·WAL·Kafka는 대역으로 바꾸지 않는다.
            try{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ENGINE_PORT+exchange.getRequestURI())).timeout(Duration.ofSeconds(10));
                for(String name:List.of("Content-Type","x-engine-service-token","x-engine-audit-token")){String value=exchange.getRequestHeaders().getFirst(name);if(value!=null)request.header(name,value);}
                var response=forward.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
                boolean lost=exchange.getRequestURI().getPath().equals("/api/cex/deposits/credits")&&loseResponse.get();
                if(lost)assertEquals(200,response.statusCode());
                byte[] body=lost?json.writeValueAsBytes(Map.of("code","OUTCOME_UNKNOWN","deposit_id",receipt.id())):response.body();
                exchange.getResponseHeaders().set("Content-Type",response.headers().firstValue("Content-Type").orElse("application/json"));exchange.sendResponseHeaders(lost?504:response.statusCode(),body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){throw new IllegalStateException(failure);}finally{exchange.close();}
        });proxy.start();
    }
    void startEngine()throws Exception{
        var launch=new ProcessBuilder(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT"),"target/debug/cex-engine").toString()).directory(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT")).toFile());
        launch.environment().putAll(Map.of("DATABASE_HOST",System.getenv("CEX_WIRE_DATABASE_HOST"),"ENGINE_DATA_DIR",directory.toString(),"ENGINE_HTTP_ADDRESS","127.0.0.1:"+ENGINE_PORT,"ENGINE_SERVICE_TOKEN",SERVICE,"ENGINE_AUDIT_TOKEN",AUDIT,"KAFKA_BOOTSTRAP_SERVERS",System.getenv("CEX_WIRE_KAFKA")));
        launch.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(engineLog.toFile()));engineProcess=launch.start();
        try(var health=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofMillis(200)).build()){
            await(()->{assertTrue(engineProcess.isAlive(),"소유 엔진이 종료됐다. 격리 로그를 확인한다");try{return health.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ENGINE_PORT+"/api/health")).timeout(Duration.ofMillis(300)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception waiting){return false;}},20);
        }
    }
    static void await(BooleanSupplier ready,int seconds)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!ready.getAsBoolean()){if(System.nanoTime()>=end)fail("격리 연결 검증 시간 초과");Thread.sleep(25);}}
    long rows(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    BigDecimal money(String bucket){return jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=? AND asset='BTC' AND bucket=?",BigDecimal.class,customer.getId(),bucket);}
    void amount(String expected,String bucket){assertEquals(0,new BigDecimal(expected).compareTo(money(bucket)));}
    String originalEvent(){var page=actualAudit.page(0,null,null);assertEquals(1,page.path("events").size());return page.path("events").get(0).asText();}
    void pendingOnly(){assertEquals(0,rows("bitcoin_credited_deposits"));assertEquals(0,rows("engine_event_receipts"));amount("0.12345678","PENDING");amount("0","AVAILABLE");}
    void settled()throws Exception{await(()->rows("bitcoin_credited_deposits")==1,20);amount("0","PENDING");amount("0.12345678","AVAILABLE");assertEquals(1,rows("engine_event_receipts"));assertEquals("MATCHED",financialReconciliation.run().status());}
    @AfterEach void cleanupWire()throws Exception{
        listeners.stop();if(proxy!=null)proxy.stop(0);if(proxyThreads!=null)proxyThreads.shutdownNow();if(forward!=null)forward.shutdownNow();
        if(engineProcess!=null){engineProcess.destroy();if(!engineProcess.waitFor(15,TimeUnit.SECONDS)){engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));}}
        if(directory!=null)try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
    @Override @AfterEach void noExternalOrFinancialRelease(){
        verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer,priceClient);
        var total=jdbc.queryForObject("SELECT sum(r.amount) FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE r.user_id=?",BigDecimal.class,customer.getId());assertEquals(0,total.compareTo(money("PENDING").add(money("AVAILABLE"))));
    }
    @Test void actualHttpAcknowledgementWaitsForKafkaAndDuplicateDeliveryDoesNotCreditTwice()throws Exception{
        var result=processing.processNext().orElseThrow();assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,result.transportOutcome());assertEquals("WAITING_EVENT",result.state());pendingOnly();
        String raw=originalEvent();listeners.start();settled();
        var duplicate=kafka.send("order-events",0,"same-original",raw).get(10,TimeUnit.SECONDS).getRecordMetadata();
        // 실제 그룹의 커밋 위치까지 기다려 재발행이 소비되었음을 확인한다. 단순히 잔액이 잠시 같다는 관찰로 대체하지 않는다.
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("CEX_WIRE_KAFKA")))){await(()->{try{var offset=admin.listConsumerGroupOffsets("cex-consumer-group").partitionsToOffsetAndMetadata().get(2,TimeUnit.SECONDS).get(new TopicPartition("order-events",0));return offset!=null&&offset.offset()>duplicate.offset();}catch(Exception waiting){return false;}},20);}
        settled();assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));assertEquals(1,rows("bitcoin_credit_authorizations"));assertEquals(raw,originalEvent());
    }
    @Test void lostHttpSuccessAndForcedEngineRestartPreserveTheSameDepositAndEvent()throws Exception{
        loseResponse.set(true);assertEquals(EngineDepositClient.Outcome.OUTCOME_UNKNOWN,processing.processNext().orElseThrow().transportOutcome());pendingOnly();String raw=originalEvent();
        // WAL 확정 뒤 프로세스를 강제 종료하고 같은 경로에서 복원한다. 새 ID나 DB 잔액으로 재초기화하지 않는다.
        engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));startEngine();loseResponse.set(false);
        Thread.sleep(5100);assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,dispatch.execute(receipt.id()).orElseThrow().outcome());pendingOnly();assertEquals(raw,originalEvent());
        listeners.start();settled();assertEquals(2,rows("bitcoin_credit_dispatch_attempts"));assertEquals(1,rows("bitcoin_credit_authorizations"));assertEquals(1,rows("bitcoin_credit_intents"));
    }
    @Test void requiredAuditFailureStopsTheRealConsumerAndReplaysWithoutLosingFunds()throws Exception{
        assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,processing.processNext().orElseThrow().transportOutcome());pendingOnly();
        var attempted=new AtomicBoolean();
        doAnswer(call->{attempted.set(true);throw new IllegalStateException("격리 필수 감사 기록 실패");}).when(audit).record(isNull(),eq("BTC_DEPOSIT_CREDITED"),anyString(),anyString(),anyString());listeners.start();
        await(()->attempted.get()&&listeners.getListenerContainers().stream().anyMatch(c->!c.isRunning()),25);pendingOnly();
        doCallRealMethod().when(audit).record(isNull(),eq("BTC_DEPOSIT_CREDITED"),anyString(),anyString(),anyString());listeners.start();settled();assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));
    }
}
