package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.security.CustomerWithdrawalAuthorization;
import dustin.cex.shared.http.EngineWithdrawalClient;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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

/** 고객 실제 패스키·플랫폼 금융 원본·별도 Rust/HTTP/WAL/Kafka를 연결한다. 실제 체인 지급 시험은 아니다. */
@EnabledIfEnvironmentVariable(named="CEX_WIRE_KAFKA",matches="127\\.0\\.0\\.1:[0-9]+")
class BitcoinWithdrawalWireIntegrationTest extends BitcoinWithdrawalTestSupport {
    static final String SERVICE="isolated-wire-service-identity-at-least-32-bytes",AUDIT="isolated-wire-audit-identity-at-least-32-bytes";
    static int port(){try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    static final int ENGINE_PORT=port(),PROXY_PORT=port();
    @DynamicPropertySource static void wire(DynamicPropertyRegistry r){
        r.add("engine.http.url",()->"http://127.0.0.1:"+PROXY_PORT);r.add("engine.service.token",()->SERVICE);r.add("engine.audit.token",()->AUDIT);r.add("engine.http.timeout",()->10000);
        r.add("spring.kafka.bootstrap-servers",()->System.getenv("CEX_WIRE_KAFKA"));r.add("spring.kafka.listener.auto-startup",()->false);
    }
    @Autowired CustomerWithdrawalAuthorization authorization;
    @Autowired dustin.cex.domains.reconciliation.EngineAuditClient actualAudit;
    @Autowired dustin.cex.domains.reconciliation.ReconciliationService financialReconciliation;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String,String> kafka;
    EngineWithdrawalClient actual;
    BitcoinWithdrawalRequests realRequests;
    BitcoinWithdrawalCancellations realCancellations;
    Process engineProcess;Path directory,engineLog;HttpServer proxy;ExecutorService proxyThreads;HttpClient forward;
    final AtomicReference<String> loseAction=new AtomicReference<>("");

    @BeforeEach void preparedWire()throws Exception {
        assertFalse(org.mockito.Mockito.mockingDetails(actualAudit).isMock());
        assertNotEquals("127.0.0.1:9092",System.getenv("CEX_WIRE_KAFKA"));
        assertTrue(System.getenv("CEX_TEST_DATABASE_URL").endsWith("/cex"));
        jdbc.execute(Files.readString(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT"),"schema/order_receipts.sql")));
        directory=Files.createTempDirectory("cex-withdrawal-wire-");engineLog=Path.of(System.getenv("CEX_WIRE_LOG_DIR"),"engine.log");
        startEngine();forward=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",PROXY_PORT),0);proxyThreads=Executors.newSingleThreadExecutor();proxy.setExecutor(proxyThreads);
        proxy.createContext("/",exchange -> {
            try {
                byte[] input=exchange.getRequestBody().readAllBytes();
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ENGINE_PORT+exchange.getRequestURI())).timeout(Duration.ofSeconds(10));
                for(String name:List.of("Content-Type","x-engine-service-token","x-engine-audit-token")) {
                    String value=exchange.getRequestHeaders().getFirst(name);if(value!=null)request.header(name,value);
                }
                var response=forward.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(input)).build(),HttpResponse.BodyHandlers.ofByteArray());
                // 실제 WAL 성공을 받은 뒤 응답만 잃는다. 엔진 계산·영속 기록·Kafka 공개는 대역으로 바꾸지 않는다.
                boolean lost=exchange.getRequestURI().getPath().equals("/api/cex/withdrawals/reservations")
                    &&loseAction.get().equals(json.readTree(input).path("action").asText());
                if(lost)assertEquals(200,response.statusCode());
                byte[] body=lost?json.writeValueAsBytes(Map.of("code","OUTCOME_UNKNOWN")):response.body();
                response.headers().firstValue("Content-Type").ifPresent(v -> exchange.getResponseHeaders().set("Content-Type",v));
                exchange.sendResponseHeaders(lost?504:response.statusCode(),body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){throw new IllegalStateException(failure);}finally{exchange.close();}
        });proxy.start();
        // 시험 기반의 HTTP 대역을 우회하고 제품의 실제 클라이언트·예약·해제 서비스를 직접 연결한다.
        actual=new EngineWithdrawalClient("http://127.0.0.1:"+PROXY_PORT,10000,SERVICE);
        var dispatch=new BitcoinWithdrawalDispatches(jdbc,users,audit,actual,manager);
        realRequests=new BitcoinWithdrawalRequests(jdbc,quotes,authorization,balances,ledger,audit,dispatch,withdrawalValuations,manager);
        realCancellations=new BitcoinWithdrawalCancellations(jdbc,users,quotes,realRequests,actual,audit,manager);
        assertFalse(org.mockito.Mockito.mockingDetails(actual).isMock());
    }

    void startEngine()throws Exception {
        var launch=new ProcessBuilder(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT"),"target/debug/cex-engine").toString())
            .directory(Path.of(System.getenv("CEX_WIRE_ENGINE_ROOT")).toFile());
        launch.environment().putAll(Map.of("DATABASE_URL","postgresql://root:1234@"+System.getenv("CEX_WIRE_DATABASE_HOST")+"/cex","ENGINE_DATA_DIR",directory.toString(),
            "ENGINE_HTTP_ADDRESS","127.0.0.1:"+ENGINE_PORT,"ENGINE_SERVICE_TOKEN",SERVICE,"ENGINE_AUDIT_TOKEN",AUDIT,"KAFKA_BOOTSTRAP_SERVERS",System.getenv("CEX_WIRE_KAFKA")));
        launch.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(engineLog.toFile()));engineProcess=launch.start();
        try(var health=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofMillis(200)).build()) {
            await(() -> {assertTrue(engineProcess.isAlive(),"소유 엔진이 종료됐다. 격리 로그를 확인한다");try {
                return health.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ENGINE_PORT+"/api/health")).timeout(Duration.ofMillis(300)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;
            }catch(Exception waiting){return false;}},20);
        }
    }
    static void await(BooleanSupplier ready,int seconds)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!ready.getAsBoolean()){if(System.nanoTime()>=deadline)fail("격리 출금 연결 검증 시간 초과");Thread.sleep(25);}
    }
    void stopListeners()throws Exception {listeners.stop();await(()->listeners.getListenerContainers().stream().noneMatch(c->c.isRunning()),10);}
    void restartEngine()throws Exception {engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));startEngine();}
    UUID signedReserve()throws Exception {
        UUID id=price().payload().id();var options=realRequests.options(customer.getId(),sid,id);
        var result=realRequests.submit(customer.getId(),sid,id,proof(options));assertEquals("PENDING_ENGINE",result.state());
        assertEquals("0.01000000",result.amount());assertEquals("0.00000100",result.fee());assertEquals("0.01000100",result.total());
        return id;
    }
    void held(){amount(new BigDecimal("0.00999900"),GROSS);}
    String durable(int index){return actualAudit.page(0,null,null).path("events").get(index).asText();}
    void receivedReserve(UUID id)throws Exception {
        await(()->count("bitcoin_withdrawal_reservation_events")==1,20);held();
        assertEquals("RESERVED",realRequests.status(customer.getId(),sid,id).state());
        assertEquals("MATCHED",financialReconciliation.run().status());
    }
    void cancelled(UUID id)throws Exception {
        await(()->count("bitcoin_withdrawal_release_events")==1,20);amount(FUNDS,BigDecimal.ZERO);
        assertEquals("CANCELLED",realRequests.status(customer.getId(),sid,id).state());
        assertEquals("MATCHED",financialReconciliation.run().status());
    }
    void consumeDuplicate(String raw)throws Exception {
        var duplicate=kafka.send("order-events",0,"same-withdrawal",raw).get(10,TimeUnit.SECONDS).getRecordMetadata();
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("CEX_WIRE_KAFKA")))) {
            // 실제 그룹의 커밋 위치로 재소비 완료를 확인한다. 잔액이 잠시 같다는 관찰에 의존하지 않는다.
            await(()->{try {
                var offset=admin.listConsumerGroupOffsets("cex-consumer-group").partitionsToOffsetAndMetadata().get(2,TimeUnit.SECONDS).get(new TopicPartition("order-events",0));
                return offset!=null&&offset.offset()>duplicate.offset();
            }catch(Exception waiting){return false;}},20);
        }
    }
    @AfterEach void cleanupWire()throws Exception {
        listeners.stop();if(actual!=null)actual.close();if(proxy!=null)proxy.stop(0);if(proxyThreads!=null)proxyThreads.shutdownNow();if(forward!=null)forward.shutdownNow();
        if(engineProcess!=null){engineProcess.destroy();if(!engineProcess.waitFor(15,TimeUnit.SECONDS)){engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));}}
        if(directory!=null)try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        org.mockito.Mockito.verifyNoInteractions(engineWithdrawal,engine,producer);
    }

    @Test void realSignedReservationAndCancellationWaitForKafkaAndConsumeDuplicatesOnce()throws Exception {
        UUID id=signedReserve();held();assertEquals(0,count("engine_event_receipts"));String reserve=durable(0);
        assertEquals("DURABLY_RESERVED",jdbc.queryForObject("SELECT outcome FROM bitcoin_withdrawal_dispatch_results",String.class));
        assertEquals(1,actualAudit.page(0,null,null).path("events").size());
        listeners.start();receivedReserve(id);consumeDuplicate(reserve);receivedReserve(id);stopListeners();
        assertEquals("CANCELLATION_PENDING",realCancellations.cancel(customer.getId(),sid,id).state());held();
        assertEquals("DURABLY_RELEASED",jdbc.queryForObject("SELECT outcome FROM bitcoin_withdrawal_release_results",String.class));
        assertEquals(0,count("bitcoin_withdrawal_release_events"));String release=durable(1);
        listeners.start();cancelled(id);consumeDuplicate(release);cancelled(id);
        assertEquals(1,count("bitcoin_withdrawal_dispatch_attempts"));assertEquals(1,count("bitcoin_withdrawal_release_attempts"));
        assertEquals(2,count("engine_event_receipts"));assertEquals(2,actualAudit.page(0,null,null).path("events").size());
    }
    @Test void lostReserveResponseAndRestartPreserveEarlyCancellationAndTheOriginalEvents()throws Exception {
        loseAction.set("reserve");UUID id=signedReserve();held();String reserve=durable(0);
        assertEquals("OUTCOME_UNKNOWN",jdbc.queryForObject("SELECT outcome FROM bitcoin_withdrawal_dispatch_results",String.class));
        assertEquals("CANCELLATION_PENDING",realCancellations.cancel(customer.getId(),sid,id).state());
        assertEquals(0,count("bitcoin_withdrawal_release_attempts"));restartEngine();loseAction.set("");assertEquals(reserve,durable(0));
        listeners.start();await(()->count("bitcoin_withdrawal_reservation_events")==1,20);
        assertEquals("CANCELLATION_PENDING",realRequests.status(customer.getId(),sid,id).state());held();
        assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RELEASED,realCancellations.processNext().orElseThrow().transport());cancelled(id);
        consumeDuplicate(reserve);cancelled(id);assertEquals(2,count("engine_event_receipts"));
        assertEquals(1,count("bitcoin_withdrawals"));assertEquals(1,count("customer_withdrawal_authorizations"));
        assertEquals(1,count("bitcoin_withdrawal_dispatch_attempts"));assertEquals(1,count("bitcoin_withdrawal_release_attempts"));
    }
    @Test void lostReleaseResponseAndRestartKeepFundsLockedUntilTheSameDurableKafkaEvent()throws Exception {
        UUID id=signedReserve();listeners.start();receivedReserve(id);stopListeners();loseAction.set("release");
        realCancellations.cancel(customer.getId(),sid,id);held();String release=durable(1);
        assertEquals("OUTCOME_UNKNOWN",jdbc.queryForObject("SELECT outcome FROM bitcoin_withdrawal_release_results",String.class));
        assertEquals(0,count("bitcoin_withdrawal_release_events"));restartEngine();loseAction.set("");assertEquals(release,durable(1));
        // 응답 불명을 새 출금이나 시각 변조로 우회하지 않는다. 원래 총액/ID의 실제 재확인 대기를 지킨다.
        Thread.sleep(5100);assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RELEASED,realCancellations.execute(id).orElseThrow().transport());held();
        assertEquals(2,count("bitcoin_withdrawal_release_attempts"));assertEquals(0,count("bitcoin_withdrawal_release_events"));assertEquals(release,durable(1));
        listeners.start();cancelled(id);consumeDuplicate(release);cancelled(id);
        assertEquals(2,count("engine_event_receipts"));assertEquals(2,actualAudit.page(0,null,null).path("events").size());
    }
}
