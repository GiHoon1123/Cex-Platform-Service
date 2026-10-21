package dustin.cex.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

/**
 * Mockito로 HTTP 자체를 대체하지 않고 임의 루프백 포트의 JDK 서버로 검증한다.
 * 실제 엔진·Kafka에 접근하지 않으며 응답 유실과 HTTP 오류 때 전송 횟수도 확인한다.
 */
class EngineHttpClientTest {
    private static final String VALID_RECEIPT = """
            {"order_id":"123","user_id":"101","receipt_status":"accepted","accepted_at":"2026-10-21T00:00:00Z"}
            """;

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonpositiveTimeoutIsRejectedAtInitialization(int timeout) {
        var client = new EngineHttpClient();
        ReflectionTestUtils.setField(client, "timeoutMs", timeout);
        assertThatThrownBy(client::init).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 201, 400, 500, 503})
    void responseStatusIsNotAutomaticallyRetried(int status) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/cex/orders", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = VALID_RECEIPT.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = client(server, 2000);
            if (status == 200 || status == 202) {
                assertThat(submit(client)).isTrue();
            } else {
                assertThatThrownBy(() -> submit(client)).isInstanceOf(RuntimeException.class);
            }
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void receivedRequestWithoutResponseTimesOutWithoutResubmitting() throws Exception {
        var calls = new AtomicInteger();
        var releaseResponse = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/cex/orders", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            try {
                // 서버는 요청을 받았지만 응답을 보내지 않는다. 클라이언트의 읽기 제한이
                // 작동해야 하며, 테스트 종료 시 latch를 열어 서버 스레드를 정리한다.
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = client(server, 250);
            long start = System.nanoTime();
            assertThatThrownBy(() -> submit(client)).hasRootCauseInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "wrong-order", "wrong-user", "numeric-id", "missing-time", "invalid-time", "wrong-state"})
    void successfulHttpWithoutMatchingReceiptIsNotAcceptance(String defect) throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(VALID_RECEIPT);
        var receipt = (com.fasterxml.jackson.databind.node.ObjectNode) node;
        switch (defect) {
            case "wrong-order" -> receipt.put("order_id", "124");
            case "wrong-user" -> receipt.put("user_id", "102");
            case "numeric-id" -> receipt.put("order_id", 123);
            case "missing-time" -> receipt.remove("accepted_at");
            case "invalid-time" -> receipt.put("accepted_at", "not-a-timestamp");
            case "wrong-state" -> receipt.put("receipt_status", "processed");
            default -> receipt.removeAll();
        }
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/cex/orders", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] body = receipt.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> submit(client(server, 2000))).isInstanceOf(RuntimeException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private EngineHttpClient client(HttpServer server, int timeout) {
        var client = new EngineHttpClient();
        ReflectionTestUtils.setField(client, "engineBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "timeoutMs", timeout);
        client.init();
        return client;
    }

    private boolean submit(EngineHttpClient client) {
        return client.submitOrder(123L, 101L, "buy", "limit", "SOL", "USDT", "100", "2", null);
    }
}
