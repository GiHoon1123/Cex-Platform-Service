package dustin.cex.domains.market;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class BinanceReferenceCandleServiceTest {
    private HttpServer server;private AtomicInteger requests;private String body;
    @BeforeEach void start() throws Exception {
        requests=new AtomicInteger();body="[[60000,\"60000.10\",\"60100.20\",\"59900.30\",\"60050.40\",\"12.50\"],[120000,\"60050.40\",\"60200.00\",\"60000.00\",\"60150.00\",\"8.25\"]]";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/klines",exchange->{requests.incrementAndGet();byte[] value=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,value.length);exchange.getResponseBody().write(value);exchange.close();});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    private BinanceReferenceCandleService service(){return new BinanceReferenceCandleService(JsonMapper.builder().build(),"http://127.0.0.1:"+server.getAddress().getPort());}

    @Test void bitcoinAndEthereumUseTheValidatedBinanceContract() {
        var service=service();var bitcoin=service.candles("BTC","USDT");var ethereum=service.candles("ETH","USDT");
        assertEquals("BINANCE",bitcoin.source());assertEquals("BTC/USDT",bitcoin.market());assertEquals("1m",bitcoin.interval());
        assertEquals("60000.10",bitcoin.candles().getFirst().open());assertEquals("8.25",ethereum.candles().getLast().volume());
    }

    @Test void tenSecondCacheCombinesRequestsPerMarketWithoutSharingMarkets() {
        var service=service();service.candles("BTC","USDT");service.candles("BTC","USDT");service.candles("ETH","USDT");
        assertEquals(2,requests.get());
    }

    @Test void unsupportedMarketAndMalformedRowsFailClosed() {
        var service=service();assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.candles("SOL","USDT")).getStatusCode().value());
        body="[[60000,\"0\",\"1\",\"1\",\"1\",\"1\"]]";
        assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.candles("BTC","USDT")).getStatusCode().value());
    }

    @Test void insecureRemoteEndpointIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new BinanceReferenceCandleService(JsonMapper.builder().build(),"http://example.com"));
    }
}
