package dustin.cex.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.ObjectMapper;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.auth.service.JwtService;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.bot.service.BotService;
import dustin.cex.domains.market.MarketService;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.fee.repository.FeeConfigRepository;
import dustin.cex.domains.fee.model.entity.FeeConfig;
import dustin.cex.domains.fee.service.FeeConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

/** 이 검증의 PostgreSQL·Kafka·Rust 프로세스만 사용해 공개 경로를 끝까지 실행한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"bot.bot-count=0","spring.kafka.listener.auto-startup=true","spring.jpa.hibernate.ddl-auto=update","jwt.secret=isolated-market-verification-secret-key-only"})
@EnabledIfEnvironmentVariable(named="CEX_MARKET_E2E",matches="1")
class MarketPathTest {
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));
        registry.add("spring.kafka.bootstrap-servers",()->System.getenv("CEX_TEST_KAFKA"));
        registry.add("engine.http.url",()->System.getenv("CEX_TEST_ENGINE_URL"));
    }
    @LocalServerPort int port;
    @Autowired UserRepository users; @Autowired UserBalanceRepository balances; @Autowired OrderRepository orders;
    @Autowired BotService bots; @Autowired MarketService market; @Autowired JwtService jwt;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer financial;
    @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired FeeConfigRepository fees; @Autowired FeeConfigService feeService;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    Process engine;
    void startEngine(boolean append) throws Exception {
        var builder=new ProcessBuilder(System.getenv("CEX_ENGINE_BINARY"));
        var env=builder.environment();
        env.put("DATABASE_HOST",System.getenv("CEX_TEST_DATABASE_URL").replace("jdbc:postgresql://","").replace("/cex",""));
        env.put("ENGINE_HTTP_ADDRESS",URI.create(System.getenv("CEX_TEST_ENGINE_URL")).getAuthority());
        env.put("ENGINE_DATA_DIR",System.getenv("CEX_ENGINE_DATA_DIR"));env.put("KAFKA_BOOTSTRAP_SERVERS",System.getenv("CEX_TEST_KAFKA"));
        var log=new java.io.File(System.getenv("CEX_ENGINE_LOG"));
        builder.redirectErrorStream(true).redirectOutput(append?ProcessBuilder.Redirect.appendTo(log):ProcessBuilder.Redirect.to(log));
        engine=builder.start();
        await().atMost(Duration.ofSeconds(30)).until(()-> {
            if(!engine.isAlive()) throw new AssertionError("격리 엔진 시작 실패: "+log);
            try {return http.send(HttpRequest.newBuilder(URI.create(System.getenv("CEX_TEST_ENGINE_URL")+"/api/health")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}
            catch(Exception starting){return false;}
        });
    }
    void stopEngine() throws Exception {if(engine!=null && engine.isAlive()){engine.destroy();if(!engine.waitFor(10,TimeUnit.SECONDS)){engine.destroyForcibly();engine.waitFor();}}}
    HttpResponse<String> request(String path,String method,String body,String token,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(key!=null) builder.header("Idempotency-Key",key);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String socketSnapshot() throws Exception {
        var received=new CompletableFuture<String>();
        var listener=new WebSocket.Listener() {
            final StringBuilder text=new StringBuilder();
            public void onOpen(WebSocket socket){socket.request(1);}
            public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){text.append(data);if(last) received.complete(text.toString());socket.request(1);return null;}
            public void onError(WebSocket socket,Throwable error){received.completeExceptionally(error);}
        };
        var socket=http.newWebSocketBuilder().header("Origin","http://localhost:3003").buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/market/BTC/USDT"),listener).get(10,TimeUnit.SECONDS);
        try {return received.get(10,TimeUnit.SECONDS);} finally {socket.sendClose(WebSocket.NORMAL_CLOSURE,"검증 완료").join();}
    }
    @Test void botOrderHttpAcceptanceDurableKafkaSnapshotCancelAndReconnect() throws Exception {
        // 실제 비영 수수료·정책 변경·연속 부분 체결을 전체 전달 경로에서 검증한다.
        fees.saveAndFlush(FeeConfig.builder().baseMint("BTC").quoteMint("USDT").feeRate(new BigDecimal("0.001")).feeType("both").isActive(true).build());feeService.loadFeeConfigs();
        User seller=users.saveAndFlush(User.builder().email("isolated-seller@local.test").passwordHash("로그인에 사용하지 않는 격리 검증 계정").build());
        User buyer=users.saveAndFlush(User.builder().email("isolated-buyer@local.test").passwordHash("로그인에 사용하지 않는 격리 검증 계정").build());
        balances.saveAndFlush(UserBalance.builder().userId(seller.getId()).mintAddress("BTC").available(new BigDecimal("1")).locked(BigDecimal.ZERO).build());
        balances.saveAndFlush(UserBalance.builder().userId(buyer.getId()).mintAddress("USDT").available(new BigDecimal("1000")).locked(BigDecimal.ZERO).build());
        jdbc.execute(Files.readString(Path.of(System.getenv("CEX_ENGINE_BINARY")).getParent().getParent().getParent().resolve("schema/order_receipts.sql")));
        try {
            startEngine(false);
            var sell=bots.createLimitSellOrder(seller.getId(),"BTC",new BigDecimal("60000"),new BigDecimal("0.002"));
            var extraSell=bots.createLimitSellOrder(seller.getId(),"BTC",new BigDecimal("60000"),new BigDecimal("0.001"));
            await().atMost(Duration.ofSeconds(30)).ignoreException(org.springframework.web.server.ResponseStatusException.class).untilAsserted(()->assertEquals("0.003",market.view("BTC","USDT").book().asks().getFirst().get(1)));
            // 이미 접수한 매도 주문은 기존 적용률을 유지하고 이후 매수 주문만 새 적용률을 쓴다.
            var current = fees.findByIsActiveTrue().getFirst(); current.setFeeRate(new BigDecimal("0.002")); fees.saveAndFlush(current); feeService.loadFeeConfigs();
            String body="{\"orderType\":\"buy\",\"orderSide\":\"limit\",\"baseMint\":\"BTC\",\"quoteMint\":\"USDT\",\"price\":\"60000\",\"amount\":\"0.001\"}";
            String key=UUID.randomUUID().toString(),token=jwt.generateAccessToken(buyer.getId(),buyer.getEmail());
            var accepted=request("/api/cex/orders","POST",body,token,key);assertEquals(201,accepted.statusCode(),accepted.body());
            await().atMost(Duration.ofSeconds(30)).ignoreException(org.springframework.web.server.ResponseStatusException.class).untilAsserted(()-> {
                var view=market.view("BTC","USDT");assertEquals("0.002",view.book().asks().getFirst().get(1));assertEquals(1,view.trades().size());
                assertEquals("filled",orders.findById(Long.parseLong(mapper.readTree(accepted.body()).path("order").path("id").asText())).orElseThrow().getStatus());
            });
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(0, balances.findByUserIdAndMintAddress(buyer.getId(),"BTC").orElseThrow().getAvailable().compareTo(new BigDecimal("0.000998")));
                assertEquals(0, balances.findByUserIdAndMintAddress(seller.getId(),"USDT").orElseThrow().getAvailable().compareTo(new BigDecimal("59.94")));
            });
            var second = request("/api/cex/orders","POST",body.replace("0.001", "0.0015"),token,UUID.randomUUID().toString());
            assertEquals(201,second.statusCode(),second.body());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(0, balances.findByUserIdAndMintAddress(buyer.getId(),"BTC").orElseThrow().getAvailable().compareTo(new BigDecimal("0.002495")));
                assertEquals(0, balances.findByUserIdAndMintAddress(seller.getId(),"USDT").orElseThrow().getAvailable().compareTo(new BigDecimal("149.85")));
                assertEquals(0, balances.findByUserIdAndMintAddress(buyer.getId(),"USDT").orElseThrow().getAvailable().compareTo(new BigDecimal("850")));
                assertEquals(3,market.view("BTC","USDT").trades().size());
            });
            // 실제 브로커 원본을 같은 소비 서비스에 재전달해 수신 키가 재차감을 막는지 확인한다.
            var properties = new java.util.Properties();
            properties.put("bootstrap.servers",System.getenv("CEX_TEST_KAFKA"));
            properties.put("group.id","fee-observer-"+UUID.randomUUID()); properties.put("auto.offset.reset","earliest");
            properties.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            properties.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            try (var observer = new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(properties)) {
                observer.subscribe(java.util.List.of("order-events"));
                var originals = new java.util.ArrayList<String>();
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while (originals.size()<3 && System.nanoTime()<deadline) observer.poll(Duration.ofMillis(250)).forEach(record -> originals.add(record.value()));
                assertTrue(originals.size()>=3);
                for (var original : originals) financial.consumeOrderEvent(original);
            }
            assertEquals(0, balances.findByUserIdAndMintAddress(buyer.getId(),"BTC").orElseThrow().getAvailable().compareTo(new BigDecimal("0.002495")));
            assertEquals(200,request("/api/cex/orders","POST",body,token,key).statusCode());
            var frame=mapper.readTree(socketSnapshot());assertEquals("BTC",frame.path("book").path("base_mint").asText());assertEquals(3,frame.path("trades").size());
            assertEquals(1,market.candles("BTC","USDT").size());
            var cancel=request("/api/cex/orders/"+extraSell.getOrder().getId(),"DELETE",null,jwt.generateAccessToken(seller.getId(),seller.getEmail()),null);assertEquals(202,cancel.statusCode(),cancel.body());
            await().atMost(Duration.ofSeconds(30)).ignoreException(org.springframework.web.server.ResponseStatusException.class).untilAsserted(()-> {
                assertTrue(market.view("BTC","USDT").book().asks().isEmpty());
                assertEquals("cancelled",orders.findById(Long.parseLong(extraSell.getOrder().getId())).orElseThrow().getStatus());
                assertEquals(0,balances.findByUserIdAndMintAddress(seller.getId(),"BTC").orElseThrow().getLocked().signum());
            });
            String sequence=market.view("BTC","USDT").book().sequence();stopEngine();startEngine(true);
            var reconnected=mapper.readTree(socketSnapshot());assertEquals(sequence,reconnected.path("book").path("sequence").asText());assertTrue(reconnected.path("book").path("asks").isEmpty());assertEquals(3,market.view("BTC","USDT").trades().size());
        } finally {stopEngine();}
    }
}
