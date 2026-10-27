package dustin.cex.domains.market;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class BinanceReferenceCandleServiceTest {
    private HttpServer server;private AtomicInteger requests;private AtomicReference<String> query;private String body;
    @BeforeEach void start() throws Exception {
        requests=new AtomicInteger();query=new AtomicReference<>();body="[[60000,\"60000.10\",\"60100.20\",\"59900.30\",\"60050.40\",\"12.50\"],[120000,\"60050.40\",\"60200.00\",\"60000.00\",\"60150.00\",\"8.25\"]]";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/klines",exchange->{requests.incrementAndGet();query.set(exchange.getRequestURI().getQuery());byte[] value=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,value.length);exchange.getResponseBody().write(value);exchange.close();});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    private BinanceReferenceCandleService service(){return new BinanceReferenceCandleService(JsonMapper.builder().build(),"http://127.0.0.1:"+server.getAddress().getPort());}

    @Test void bitcoinAndEthereumUseTheValidatedBinanceContract() {
        var service=service();var bitcoin=service.candles("BTC","USDT","1m");var ethereum=service.candles("ETH","USDT","1h");
        assertEquals("BINANCE",bitcoin.source());assertEquals("BTC/USDT",bitcoin.market());assertEquals("1m",bitcoin.interval());
        assertEquals("60000.10",bitcoin.candles().getFirst().open());assertEquals("8.25",ethereum.candles().getLast().volume());
        assertEquals("symbol=ETHUSDT&interval=1h&limit=500",query.get());
    }

    @Test void tenSecondCacheCombinesIdenticalRequestsWithoutSharingConditions() {
        var service=service();service.candles("BTC","USDT","1m");service.candles("BTC","USDT","1m");
        service.candles("BTC","USDT","1d");service.candles("ETH","USDT","1m");
        assertEquals(3,requests.get());
    }

    @Test void unsupportedMarketAndMalformedRowsFailClosed() {
        var service=service();assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.candles("SOL","USDT","1m")).getStatusCode().value());
        assertEquals(400,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.candles("BTC","USDT","2m")).getStatusCode().value());
        body="[[60000,\"0\",\"1\",\"1\",\"1\",\"1\"]]";
        assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.candles("BTC","USDT","5m")).getStatusCode().value());
    }

    @Test void insecureRemoteEndpointIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new BinanceReferenceCandleService(JsonMapper.builder().build(),"http://example.com"));
    }
}
