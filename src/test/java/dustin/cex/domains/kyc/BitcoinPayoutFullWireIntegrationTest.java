package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.broadcaster.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.shared.http.EngineWithdrawalClient;
import dustin.confirmer.*;
import dustin.signer.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.*;

/** 실제 Core 거래와 Rust WAL·Kafka·플랫폼 원장을 한 출금 식별자로 끝까지 연결한다. */
@EnabledIfEnvironmentVariable(named="CEX_PAYOUT_WIRE_CORE_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinPayoutFullWireIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    static final String SERVICE="isolated-payout-wire-service-identity-32-bytes",AUDIT="isolated-payout-wire-audit-identity-32-bytes";
    static int port(){try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    static final int ENGINE_PORT=port();
    @DynamicPropertySource static void wire(DynamicPropertyRegistry r){
        r.add("engine.http.url",()->"http://127.0.0.1:"+ENGINE_PORT);r.add("engine.service.token",()->SERVICE);r.add("engine.audit.token",()->AUDIT);r.add("engine.http.timeout",()->10000);
        r.add("spring.kafka.bootstrap-servers",()->System.getenv("CEX_PAYOUT_WIRE_KAFKA"));r.add("spring.kafka.listener.auto-startup",()->false);
    }
    @Autowired dustin.cex.domains.reconciliation.EngineAuditClient actualAudit;
    @Autowired dustin.cex.domains.reconciliation.ReconciliationService reconciliation;
    @Autowired BitcoinSignerEnvelopeCrypto signerCrypto;
    @Autowired KafkaListenerEndpointRegistry listeners;
    Process engineProcess;Path directory,engineLog;EngineWithdrawalClient actualEngine;
    tools.jackson.databind.JsonNode fixture;

    @Override String withdrawalAddress(){return fixture().path("address").asString();}
    @Override String withdrawalScript(){return fixture().path("script").asString();}
    tools.jackson.databind.JsonNode fixture(){if(fixture==null)fixture=json.readTree(System.getenv("CEX_PAYOUT_WIRE_FIXTURE"));return fixture;}

    /** 합성 예약 행을 만들지 않고 실제 Rust 코어에 같은 UUID와 총액을 영속시킨 뒤 그 저널 이벤트만 반영한다. */
    @Override void afterCustomerFunding()throws Exception{
        jdbc.execute(Files.readString(Path.of(System.getenv("CEX_PAYOUT_WIRE_ENGINE_ROOT"),"schema/order_receipts.sql")));
        directory=Files.createTempDirectory("cex-payout-full-wire-");engineLog=Path.of(System.getenv("CEX_PAYOUT_WIRE_LOG_DIR"),"engine.log");startEngine();
        actualEngine=new EngineWithdrawalClient("http://127.0.0.1:"+ENGINE_PORT,10000,SERVICE);
    }
    @Override void reserveOriginal(){
        try{
            assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,actualEngine.execute(new EngineWithdrawalClient.Command(withdrawal,customer.getId(),"BTC",GROSS,EngineWithdrawalClient.Action.RESERVE)));
            var page=actualAudit.page(0L,null,null);reserveEngine=UUID.fromString(page.path("engine_id").asString());assertEquals(1,page.path("events").size());events.consumeOrderEvent(page.path("events").get(0).asString());
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    void startEngine()throws Exception{
        var launch=new ProcessBuilder(Path.of(System.getenv("CEX_PAYOUT_WIRE_ENGINE_ROOT"),"target/debug/cex-engine").toString()).directory(Path.of(System.getenv("CEX_PAYOUT_WIRE_ENGINE_ROOT")).toFile());
        launch.environment().putAll(Map.of("DATABASE_URL","postgresql://root:1234@"+System.getenv("CEX_PAYOUT_WIRE_DATABASE_HOST")+"/cex","ENGINE_DATA_DIR",directory.toString(),"ENGINE_HTTP_ADDRESS","127.0.0.1:"+ENGINE_PORT,"ENGINE_SERVICE_TOKEN",SERVICE,"ENGINE_AUDIT_TOKEN",AUDIT,"KAFKA_BOOTSTRAP_SERVERS",System.getenv("CEX_PAYOUT_WIRE_KAFKA")));
        launch.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(engineLog.toFile()));engineProcess=launch.start();
        try(var health=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofMillis(200)).build()){await(()->{assertTrue(engineProcess.isAlive(),"소유 엔진이 종료됐다");try{return health.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ENGINE_PORT+"/api/health")).timeout(Duration.ofMillis(300)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception waiting){return false;}},20);}
    }
    static void await(BooleanSupplier ready,int seconds)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!ready.getAsBoolean()){if(System.nanoTime()>=deadline)fail("전체 출금 연결 검증 시간 초과");Thread.sleep(25);}}

    @BeforeEach void actualPreparedTransaction(){
        var f=fixture();doAnswer(call->{var command=call.getArgument(0,BitcoinPayoutClient.Command.class);var inputs=new ArrayList<BitcoinPayoutClient.Input>();for(var i:f.path("inputs"))inputs.add(new BitcoinPayoutClient.Input(i.path("txid").asString(),i.path("vout").asInt(),i.path("address").asString(),i.path("script").asString(),i.path("amount").asString()));return new BitcoinPayoutClient.Prepared(command.withdrawalId(),BitcoinPayoutClient.POLICY,"bitcoin-regtest","watch",f.path("psbt").asString(),f.path("txid").asString(),command.amount().toPlainString(),f.path("fee").asString(),f.path("changeAddress").asString(),f.path("changeScript").asString(),f.path("changeAmount").asString(),inputs,fee(),Instant.now());}).when(node).prepare(any(),any());
    }

    @Test void realCoreToRustKafkaAndLedgerCompletesOneFixedWithdrawal()throws Exception{
        var candidate=readyNative(approved.payload().id());payouts.commit(approved.payload().id());assertEquals(fixture().path("txid").asString(),candidate.payload().prepared().unsignedTxid());
        try(var signer=new BitcoinSignerRpcClient(new BitcoinSignerSettings(core(),user("SIGNER"),password("SIGNER"),"keys","REGTEST"),json)){
            assertEquals("SIGNED",new BitcoinSigningService(jdbc,signerCrypto,json,audit,signer,manager).processNext().orElseThrow().status());
        }
        try(var broadcaster=new BitcoinBroadcastRpcClient(new BitcoinBroadcastSettings(core(),user("BROADCASTER"),password("BROADCASTER"),"watch","REGTEST"),json)){
            assertEquals("ACCEPTED",new BitcoinBroadcastService(jdbc,signerCrypto,audit,broadcaster,manager).processNext().orElseThrow().status());
        }
        mineSixBlocks();
        try(var confirmer=new BitcoinConfirmationRpcClient(new BitcoinConfirmationSettings(core(),user("CONFIRMER"),password("CONFIRMER"),"REGTEST",6),json)){
            assertEquals("CONFIRMED",new BitcoinConfirmationService(jdbc,audit,confirmer,manager).processNext().orElseThrow().status());
        }
        var completion=new BitcoinPayoutCompletionDispatches(jdbc,users,audit,actualEngine,manager);listeners.start();
        assertEquals(EngineWithdrawalClient.Outcome.DURABLY_COMPLETED,completion.processNext().orElseThrow().transport());
        await(()->count("bitcoin_withdrawal_completion_events")==1,20);amount(new BigDecimal("0.00999900"),BigDecimal.ZERO);
        assertEquals("MATCHED",reconciliation.run().status());assertEquals(2,actualAudit.page(0L,null,null).path("events").size());
    }
    String core(){return System.getenv("CEX_PAYOUT_WIRE_CORE_URL");}
    String user(String role){return System.getenv("CEX_PAYOUT_WIRE_"+role+"_USER");}
    String password(String role){return System.getenv("CEX_PAYOUT_WIRE_"+role+"_PASSWORD");}
    void mineSixBlocks(){try(var rpc=new BitcoinJsonRpcClient(URI.create(core()),user("MINER"),password("MINER"),json)){rpc.call("generatetoaddress",List.of(6,System.getenv("CEX_PAYOUT_WIRE_MINING_ADDRESS")));}}

    /** 상위 시험의 합성 지급 금지 단언 대신 실제 완료 뒤의 자금·대사 단언을 사용한다. */
    @AfterEach @Override void neverPayOrDiscloseInAudit(){}
    @AfterEach void cleanupWire()throws Exception{
        listeners.stop();if(actualEngine!=null)actualEngine.close();if(engineProcess!=null){engineProcess.destroy();if(!engineProcess.waitFor(15,TimeUnit.SECONDS)){engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));}}
        if(directory!=null)try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        verifyNoInteractions(engineWithdrawal,engine,producer);
    }
}
