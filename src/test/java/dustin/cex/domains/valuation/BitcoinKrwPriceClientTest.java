package dustin.cex.domains.valuation;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실제 소유 HTTP에서 고정 페어·정확한 십진수·시간/크기/오류 경계를 검증한다. 실제 업비트 접속은 없다. */
class BitcoinKrwPriceClientTest {
    final JsonMapper json=JsonMapper.builder().build();HttpServer server;ExecutorService threads;BitcoinKrwPriceClient client;
    String mode="NORMAL";AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> handlerFailure=new AtomicReference<>();CountDownLatch release=new CountDownLatch(1);
    @BeforeEach void start()throws Exception{threads=Executors.newVirtualThreadPerTaskExecutor();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);server.createContext("/v1/ticker",e->{try{calls.incrementAndGet();assertEquals("GET",e.getRequestMethod());assertEquals("markets=KRW-BTC",e.getRequestURI().getRawQuery());assertEquals(0,e.getRequestBody().readAllBytes().length);if(mode.equals("ERROR")){send(e,429,"공급사 민감 오류 원문");return;}if(mode.equals("REDIRECT")){e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/different");send(e,302,"이동");return;}if(mode.equals("SLOW")){try(e){e.sendResponseHeaders(200,0);e.getResponseBody().write('[');e.getResponseBody().flush();release.await(10,TimeUnit.SECONDS);}return;}
        long now=Instant.now().toEpochMilli();var ticker=json.createObjectNode().put("market","KRW-BTC").put("trade_price",new BigDecimal("123456789.12345678")).put("trade_timestamp",now-10).put("timestamp",now);
        switch(mode){case "OTHER_MARKET"->ticker.put("market","KRW-ETH");case "OLD_TRADE"->ticker.put("trade_timestamp",now-121000);case "FUTURE"->ticker.put("timestamp",now+12000);case "REVERSED"->ticker.put("trade_timestamp",now+1);case "STRING_TIME"->ticker.put("timestamp",Long.toString(now));case "ZERO"->ticker.put("trade_price",0);case "STRING_PRICE"->ticker.put("trade_price","1234");case "INEXACT"->ticker.put("trade_price",new BigDecimal("0.000000001"));case "HUGE_PRICE"->ticker.put("trade_price",new BigDecimal("1e31"));}
        String body=json.writeValueAsString(List.of(ticker));switch(mode){case "TRAILING"->body+=" {}";case "DUPLICATE"->body=body.replace("\"market\":\"KRW-BTC\"","\"market\":\"KRW-BTC\",\"market\":\"KRW-BTC\"");case "EMPTY"->body="[]";case "MULTIPLE"->body="["+json.writeValueAsString(ticker)+","+json.writeValueAsString(ticker)+"]";case "LARGE"->body=body.substring(0,body.length()-2)+",\"extra\":["+String.join(",",Collections.nCopies(40000,"0"))+"]}]";}
        if(mode.equals("INVALID_UTF8")){try(e){byte[] invalid={(byte)'[',(byte)'"',(byte)0xc3,(byte)0x28,(byte)'"',(byte)']'};e.sendResponseHeaders(200,invalid.length);e.getResponseBody().write(invalid);}return;}send(e,200,body);
    }catch(java.io.IOException closed){if(!mode.equals("SLOW"))handlerFailure.compareAndSet(null,closed);}catch(Throwable failed){handlerFailure.compareAndSet(null,failed);send(e,500,"격리 대역 실패");}});server.start();client=new BitcoinKrwPriceClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));}
    void send(HttpExchange e,int status,String body)throws java.io.IOException{byte[] data=body.getBytes(StandardCharsets.UTF_8);try(e){e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);}}
    @AfterEach void close(){release.countDown();client.close();server.stop(0);threads.close();assertNull(handlerFailure.get());}
    void rejected(){var failure=assertThrows(ResponseStatusException.class,client::read);assertEquals(503,failure.getStatusCode().value());assertNull(failure.getCause());assertFalse(failure.toString().contains("민감"));}
    @Test void exactPriceAndBothSourceTimesArePreserved()throws Exception{var q=client.read();assertEquals(new BigDecimal("123456789.12345678"),q.price());assertTrue(q.tradeAt().isBefore(q.providerAt()));assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(q.payload().getBytes(StandardCharsets.UTF_8))),q.payloadHash());assertFalse(q.toString().contains(q.payload()));}
    @Test void wrongOrAmbiguousMarketNeverBecomesQuote(){for(String bad:List.of("OTHER_MARKET","EMPTY","MULTIPLE")){mode=bad;rejected();}}
    @Test void staleFutureReversedAndNonIntegralSourceTimesAreRejected(){for(String bad:List.of("OLD_TRADE","FUTURE","REVERSED","STRING_TIME")){mode=bad;rejected();}}
    @Test void invalidPricesNeverBecomeRoundedOrZeroValues(){for(String bad:List.of("ZERO","STRING_PRICE","INEXACT","HUGE_PRICE")){mode=bad;rejected();}}
    @Test void duplicateTrailingAndOversizedBodiesAreRejected(){for(String bad:List.of("DUPLICATE","TRAILING","LARGE")){mode=bad;rejected();}}
    @Test void invalidUtf8CannotChangeOriginalByteFingerprint(){mode="INVALID_UTF8";rejected();}
    @Test void errorsAndRedirectsAreNotRetriedOrFollowed(){mode="ERROR";rejected();assertEquals(1,calls.get());mode="REDIRECT";rejected();assertEquals(2,calls.get());}
    @Test void stalledBodyHasAbsoluteDeadline(){mode="SLOW";long begin=System.nanoTime();rejected();long elapsed=Duration.ofNanos(System.nanoTime()-begin).toSeconds();assertTrue(elapsed>=4&&elapsed<8);assertEquals(1,calls.get());}
    @Test void ambientTransactionCannotCallProvider(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,client::read);}finally{TransactionSynchronizationManager.clear();}assertEquals(0,calls.get());}
    @Test void arbitraryRemoteOrUnsafeTestOriginCannotOverrideProductSource(){for(String unsafe:List.of("https://example.invalid","http://example.invalid","http://127.0.0.1/other","http://user@127.0.0.1"))assertThrows(IllegalArgumentException.class,()->new BitcoinKrwPriceClient(URI.create(unsafe)));assertEquals(0,calls.get());}
}
