package dustin.cex.domains.ethereum;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 실제 로컬 HTTP 경계에서 JSON-RPC 식별자와 오류 응답을 엄격히 검사한다. */
class EthereumJsonRpcClientTest {
    private HttpServer server;
    private EthereumJsonRpcClient client;

    @AfterEach
    void closeResources() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsOnlyResultBoundToTheRequestIdentifier() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String id = request.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
            byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":\"0x1\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var settings = new EthereumSettings("http://127.0.0.1:" + server.getAddress().getPort(), "1", "0", 1, "");
        client = new EthereumJsonRpcClient(settings);

        assertEquals("0x1", client.call("eth_chainId", List.of()).asString());
    }

    @Test
    void rejectsRpcErrorsInsteadOfTreatingThemAsEmptyResults() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "{\"jsonrpc\":\"2.0\",\"id\":\"wrong\",\"error\":{\"code\":-1}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var settings = new EthereumSettings("http://127.0.0.1:" + server.getAddress().getPort(), "1", "0", 1, "");
        client = new EthereumJsonRpcClient(settings);

        assertThrows(IllegalStateException.class, () -> client.call("eth_chainId", List.of()));
    }
}
