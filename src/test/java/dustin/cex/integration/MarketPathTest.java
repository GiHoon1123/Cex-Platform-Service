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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"order.dispatch.enabled=false","bot.bot-count=0","spring.kafka.listener.auto-startup=true","spring.jpa.hibernate.ddl-auto=update","jwt.secret=isolated-market-verification-secret-key-only"})
@EnabledIfEnvironmentVariable(named="CEX_MARKET_E2E",matches="1")
class MarketPathTest {
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));
        registry.add("spring.kafka.bootstrap-servers",()->System.getenv("CEX_TEST_KAFKA"));
        registry.add("engine.audit.token",()->System.getenv("CEX_TEST_AUDIT_TOKEN"));
        registry.add("engine.http.url",()->System.getenv("CEX_TEST_ENGINE_URL"));
    }
    @LocalServerPort int port;
    @Autowired UserRepository users; @Autowired UserBalanceRepository balances; @Autowired OrderRepository orders;
    @Autowired BotService bots; @Autowired MarketService market; @Autowired JwtService jwt;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer financial;
    @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.reconciliation.ReconciliationService reconciliation;
    @Autowired FeeConfigRepository fees; @Autowired FeeConfigService feeService;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    Process engine;
    void startEngine(boolean append) throws Exception {
        var builder=new ProcessBuilder(System.getenv("CEX_ENGINE_BINARY"));
        var env=builder.environment();
        env.put("DATABASE_HOST",System.getenv("CEX_TEST_DATABASE_URL").replace("jdbc:postgresql://","").replace("/cex",""));
        env.put("ENGINE_HTTP_ADDRESS",URI.create(System.getenv("CEX_TEST_ENGINE_URL")).getAuthority());
        env.put("ENGINE_AUDIT_TOKEN",System.getenv("CEX_TEST_AUDIT_TOKEN"));
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

    long place(User user, String body) throws Exception {
        var response=request("/api/cex/orders","POST",body,jwt.generateAccessToken(user.getId(),user.getEmail()),UUID.randomUUID().toString());
        assertEquals(201,response.statusCode(),response.body());
        return Long.parseLong(mapper.readTree(response.body()).path("order").path("id").asText());
    }
    String limitBody(boolean buy,String price,String quantity) {
        return "{\"orderType\":\""+(buy?"buy":"sell")+"\",\"orderSide\":\"limit\",\"baseMint\":\"BTC\",\"quoteMint\":\"USDT\",\"price\":\""+price+"\",\"amount\":\""+quantity+"\"}";
    }
    long marketOrder(User user,boolean buy,String value) throws Exception {
        return place(user,"{\"orderType\":\""+(buy?"buy":"sell")+"\",\"orderSide\":\"market\",\"baseMint\":\"BTC\",\"quoteMint\":\"USDT\",\""+(buy?"quoteAmount":"amount")+"\":\""+value+"\"}");
    }
    void completed(long id,String status,String quantity,String quote) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()-> {
            var order=orders.findById(id).orElseThrow(); assertEquals(status,order.getStatus());
            assertEquals(0,order.getFilledAmount().compareTo(new BigDecimal(quantity)));
            assertEquals(0,order.getFilledQuoteAmount().compareTo(new BigDecimal(quote)));
        });
    }
    BigDecimal available(User user,String asset) {return balances.findByUserIdAndMintAddress(user.getId(),asset).orElseThrow().getAvailable();}
    void noReservation(User user,String asset) {assertEquals(0,balances.findByUserIdAndMintAddress(user.getId(),asset).orElseThrow().getLocked().signum());}

    /** 빈 호가·부분·전량·잔여 최소 단위와 실제 재발행을 동일 제품 경로에서 검증한다. */
    void verifyMarketIoc(User buyer,User seller) throws Exception {
        BigDecimal before=available(buyer,"USDT");
        long emptyBuy=marketOrder(buyer,true,"10"); completed(emptyBuy,"expired","0","0");
        assertEquals(0,available(buyer,"USDT").compareTo(before)); noReservation(buyer,"USDT");
        before=available(seller,"BTC");
        long emptySell=marketOrder(seller,false,"0.001"); completed(emptySell,"expired","0","0");
        assertEquals(0,available(seller,"BTC").compareTo(before)); noReservation(seller,"BTC");

        place(seller,limitBody(false,"60000","0.001"));
        before=available(buyer,"USDT"); BigDecimal receivedBefore=available(buyer,"BTC");
        long partialBuy=marketOrder(buyer,true,"100"); completed(partialBuy,"expired","0.001","60");
        assertEquals(0,available(buyer,"USDT").compareTo(before.subtract(new BigDecimal("60"))));
        assertEquals(0,available(buyer,"BTC").compareTo(receivedBefore.add(new BigDecimal("0.000998")))); noReservation(buyer,"USDT");
        var detail=request("/api/cex/orders/"+partialBuy,"GET",null,jwt.generateAccessToken(buyer.getId(),buyer.getEmail()),null);
        assertEquals(200,detail.statusCode());
        assertEquals(0,new BigDecimal(mapper.readTree(detail.body()).path("order").path("quoteAmount").asText()).compareTo(new BigDecimal("100")));
        assertEquals(0,new BigDecimal(mapper.readTree(detail.body()).path("order").path("amount").asText()).signum());

        place(buyer,limitBody(true,"60000","0.001")); before=available(seller,"BTC");
        long partialSell=marketOrder(seller,false,"0.002"); completed(partialSell,"expired","0.001","60");
        assertEquals(0,available(seller,"BTC").compareTo(before.subtract(new BigDecimal("0.001")))); noReservation(seller,"BTC");
        place(seller,limitBody(false,"60000","0.001"));
        long fullBuy=marketOrder(buyer,true,"60"); completed(fullBuy,"filled","0.001","60"); noReservation(buyer,"USDT");
        place(buyer,limitBody(true,"60000","0.001"));
        long fullSell=marketOrder(seller,false,"0.001"); completed(fullSell,"filled","0.001","60"); noReservation(seller,"BTC");

        long dustMaker=place(seller,limitBody(false,"3","0.5")); before=available(buyer,"USDT");
        long dust=marketOrder(buyer,true,"1"); completed(dust,"expired","0.333333333","0.999999999");
        assertEquals(0,available(buyer,"USDT").compareTo(before.subtract(new BigDecimal("0.999999999")))); noReservation(buyer,"USDT");
        var cancel=request("/api/cex/orders/"+dustMaker,"DELETE",null,jwt.generateAccessToken(seller.getId(),seller.getEmail()),null);
        assertEquals(202,cancel.statusCode());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertEquals("cancelled",orders.findById(dustMaker).orElseThrow().getStatus()));
        noReservation(seller,"BTC");

        // 서로 다른 가격의 두 체결을 합산해 예산 소진·종료 건수·수수료를 함께 검증한다.
        place(seller,limitBody(false,"100","0.001"));
        long secondLevel=place(seller,limitBody(false,"200","0.001"));
        before=available(buyer,"USDT"); receivedBefore=available(buyer,"BTC");
        long multiLevel=marketOrder(buyer,true,"0.25"); completed(multiLevel,"filled","0.00175","0.25");
        assertEquals(0,available(buyer,"USDT").compareTo(before.subtract(new BigDecimal("0.25"))));
        assertEquals(0,available(buyer,"BTC").compareTo(receivedBefore.add(new BigDecimal("0.0017465")))); noReservation(buyer,"USDT");
        var cancelLevel=request("/api/cex/orders/"+secondLevel,"DELETE",null,jwt.generateAccessToken(seller.getId(),seller.getEmail()),null);
        assertEquals(202,cancelLevel.statusCode());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertEquals("cancelled",orders.findById(secondLevel).orElseThrow().getStatus()));
        noReservation(seller,"BTC");

        // 이 가격과 수량은 각각 유효하지만 체결 대금은 최소 단위로 표현되지 않는다.
        // 실제 코어가 후보 전체를 거절하고 WAL 확정 결과로만 플랫폼 예약을 반환해야 한다.
        long rejectionMaker=place(seller,limitBody(false,"3.000000002","0.5"));
        before=available(buyer,"USDT");
        long rejected=place(buyer,limitBody(true,"4","0.000000001"));
        completed(rejected,"rejected","0","0");
        assertEquals(0,available(buyer,"USDT").compareTo(before)); noReservation(buyer,"USDT");
        assertEquals(0,orders.findById(rejectionMaker).orElseThrow().getFilledAmount().signum());
        assertEquals(202,request("/api/cex/orders/"+rejectionMaker,"DELETE",null,jwt.generateAccessToken(seller.getId(),seller.getEmail()),null).statusCode());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertEquals("cancelled",orders.findById(rejectionMaker).orElseThrow().getStatus()));
        noReservation(seller,"BTC");

        var expected=java.util.List.of(available(buyer,"BTC"),available(buyer,"USDT"),available(seller,"BTC"),available(seller,"USDT"));
        var properties=new java.util.Properties(); properties.put("bootstrap.servers",System.getenv("CEX_TEST_KAFKA"));
        properties.put("group.id","ioc-observer-"+UUID.randomUUID()); properties.put("auto.offset.reset","earliest");
        properties.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        int completions=0, rejections=0;
        try(var observer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(properties)) {
            observer.subscribe(java.util.List.of("order-events")); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while((completions<8 || rejections<1) && System.nanoTime()<deadline) {
                for(var record:observer.poll(Duration.ofMillis(250))) {
                    if(mapper.readTree(record.value()).path("event_type").asText().equals("market_order_completed")) completions++;
                    if(mapper.readTree(record.value()).path("event_type").asText().equals("order_rejected")) rejections++;
                    financial.consumeOrderEvent(record.value());
                }
            }
        }
        assertEquals(8,completions); assertEquals(1,rejections);
        // 전달 커서만 잃은 장애를 주입한다. 금융 기록은 바꾸지 않으며 실제 WAL 복구·Kafka
        // 재발행·플랫폼 수신 키가 함께 재차감과 이중 반환을 막는지 검사한다.
        stopEngine();
        var reset=new ProcessBuilder("python3","-c","import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('UPDATE delivery SET sequence=0'); c.commit(); c.close()",Path.of(System.getenv("CEX_ENGINE_DATA_DIR"),"journal.sqlite3").toString()).inheritIO().start();
        assertEquals(0,reset.waitFor()); startEngine(true);
        assertEquals(expected,java.util.List.of(available(buyer,"BTC"),available(buyer,"USDT"),available(seller,"BTC"),available(seller,"USDT")));
        assertEquals("rejected",orders.findById(rejected).orElseThrow().getStatus());
        assertEquals("expired",orders.findById(emptyBuy).orElseThrow().getStatus());
        assertEquals("expired",orders.findById(partialSell).orElseThrow().getStatus());
        noReservation(buyer,"USDT"); noReservation(seller,"BTC");
        var frame=mapper.readTree(socketSnapshot()); assertTrue(frame.path("book").path("asks").isEmpty()); assertTrue(frame.path("book").path("bids").isEmpty());
    }

    @Test void botOrderHttpAcceptanceDurableKafkaSnapshotCancelAndReconnect() throws Exception {
        // 실제 비영 수수료·정책 변경·연속 부분 체결을 전체 전달 경로에서 검증한다.
        fees.saveAndFlush(FeeConfig.builder().baseMint("BTC").quoteMint("USDT").feeRate(new BigDecimal("0.001")).feeType("both").isActive(true).build());feeService.loadFeeConfigs();
        User seller=users.saveAndFlush(User.builder().email("isolated-seller@local.test").passwordHash("로그인에 사용하지 않는 격리 검증 계정").build());
        User buyer=users.saveAndFlush(User.builder().email("isolated-buyer@local.test").passwordHash("로그인에 사용하지 않는 격리 검증 계정").build());
        balances.saveAndFlush(UserBalance.builder().userId(seller.getId()).mintAddress("BTC").available(new BigDecimal("1")).locked(BigDecimal.ZERO).build());
        balances.saveAndFlush(UserBalance.builder().userId(buyer.getId()).mintAddress("USDT").available(new BigDecimal("1000")).locked(BigDecimal.ZERO).build());
        jdbc.execute(Files.readString(Path.of(System.getenv("CEX_ENGINE_BINARY")).getParent().getParent().getParent().resolve("schema/order_receipts.sql")));
        ledger.initializeOpening();
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
            verifyMarketIoc(buyer,seller);
            var report=reconciliation.run(); assertEquals("MATCHED",report.status(),()->jdbc.queryForList("SELECT * FROM reconciliation_issues WHERE run_id=?",report.id()).toString());
            // 실행 소유 잔고의 조회값만 손상시켜 원본 원장과 독립 비교하는지 확인한다.
            jdbc.update("UPDATE user_balances SET available=available-1 WHERE user_id=? AND mint_address='USDT'",buyer.getId());
            var damaged=reconciliation.run(); assertEquals("MISMATCH",damaged.status());
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category='BALANCE_AVAILABLE'",Long.class,damaged.id())>0);
            // 제품 대사는 잔고를 고치지 않는다. 테스트에서만 주입한 손상을 원복한다.
            jdbc.update("UPDATE user_balances SET available=available+1 WHERE user_id=? AND mint_address='USDT'",buyer.getId());
            assertEquals("MATCHED",reconciliation.run().status());
            var feeId=jdbc.queryForObject("SELECT min(id) FROM trade_fees",Long.class);
            jdbc.update("UPDATE trade_fees SET fee_amount=fee_amount+1 WHERE id=?",feeId);
            var wrongFee=reconciliation.run();assertEquals("MISMATCH",wrongFee.status());
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category='FEE_VALUE'",Long.class,wrongFee.id())>0);
            jdbc.update("UPDATE trade_fees SET fee_amount=fee_amount-1 WHERE id=?",feeId);
            var tradeId=jdbc.queryForObject("SELECT min(id) FROM trades",Long.class);
            jdbc.update("UPDATE trades SET base_mint='WRONG' WHERE id=?",tradeId);
            var wrongAsset=reconciliation.run();assertEquals("MISMATCH",wrongAsset.status());
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category='TRADE_ASSET'",Long.class,wrongAsset.id())>0);
            jdbc.update("UPDATE trades SET base_mint='BTC' WHERE id=?",tradeId);
            // 실제 금융 소비만 중지해 전달 지연을 만든다. 원장은 맞더라도 미수신 확정 결과가 있으면 완료가 아니다.
            var financialContainers=listeners.getAllListenerContainers().stream().filter(container->java.util.Arrays.asList(container.getContainerProperties().getTopics()).contains("order-events")).toList();
            assertEquals(1,financialContainers.size());financialContainers.forEach(org.springframework.kafka.listener.MessageListenerContainer::stop);
            long delayed;
            try {
                delayed=marketOrder(buyer,true,"1");
                assertEquals("INCOMPLETE",reconciliation.run().status());
            } finally {financialContainers.forEach(org.springframework.kafka.listener.MessageListenerContainer::start);}
            completed(delayed,"expired","0","0");
            assertEquals("MATCHED",reconciliation.run().status());
            // 새 JAR의 일회성 운영 진입점까지 실행하고 해당 프로세스만 정상 종료되는지 확인한다.
            long reportsBefore=jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs",Long.class);
            var cliLog=Path.of(System.getenv("CEX_ENGINE_LOG")).resolveSibling("ledger-reconciliation-cli.log").toFile();
            var cli=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","build/libs/cex-0.0.1-SNAPSHOT.jar",
                "--reconciliation.run=true","--server.port=0","--server.address=127.0.0.1","--bot.bot-count=0","--order.dispatch.enabled=false",
                "--spring.kafka.listener.auto-startup=false","--spring.kafka.bootstrap-servers="+System.getenv("CEX_TEST_KAFKA"),
                "--spring.datasource.url="+System.getenv("CEX_TEST_DATABASE_URL"),"--spring.jpa.hibernate.ddl-auto=validate",
                "--engine.http.url="+System.getenv("CEX_TEST_ENGINE_URL"));
            cli.environment().put("ENGINE_AUDIT_TOKEN",System.getenv("CEX_TEST_AUDIT_TOKEN"));
            var checker=cli.redirectErrorStream(true).redirectOutput(cliLog).start();
            try { assertTrue(checker.waitFor(40,TimeUnit.SECONDS),"대사 명령 종료 시간 초과"); assertEquals(0,checker.exitValue(),()->{try{return Files.readString(cliLog.toPath());}catch(Exception e){return e.toString();}}); }
            finally {if(checker.isAlive()){checker.destroyForcibly();checker.waitFor();}}
            assertEquals(reportsBefore+1,jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs",Long.class));
        } finally {stopEngine();}
    }
}
