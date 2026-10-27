package dustin.cex.domains.market;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;

/** 공개 소켓의 유한 연결 수와 명령 우회 금지를 포트 없이 검증한다. */
class MarketSocketTest {
    WebSocketSession session(String id) {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn(id);when(session.isOpen()).thenReturn(true);when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/market/BTC/USDT"));return session;
    }
    @Test void connectionLimitReleasesExactlyOneSlotAfterClose() throws Exception {
        var socket=new MarketSocket(mock(MarketService.class),new ObjectMapper());
        try {
            WebSocketSession first=session("first");socket.afterConnectionEstablished(first);
            for(int i=1;i<100;i++)socket.afterConnectionEstablished(session("client-"+i));
            var rejected=session("rejected");socket.afterConnectionEstablished(rejected);verify(rejected).close(CloseStatus.POLICY_VIOLATION);
            socket.afterConnectionClosed(first,CloseStatus.NORMAL);
            var replacement=session("replacement");socket.afterConnectionEstablished(replacement);verify(replacement,never()).close(any(CloseStatus.class));
            var extra=session("extra");socket.afterConnectionEstablished(extra);verify(extra).close(CloseStatus.POLICY_VIOLATION);
        } finally {socket.stop();}
    }
    @Test void publicSocketRejectsOrderMessages() throws Exception {
        var service=mock(MarketService.class);var socket=new MarketSocket(service,new ObjectMapper());var client=session("client");
        try {
            socket.afterConnectionEstablished(client);socket.handleTextMessage(client,new TextMessage("{\"orderType\":\"buy\"}"));
            verify(client).close(CloseStatus.POLICY_VIOLATION);verifyNoInteractions(service);
        } finally {socket.stop();}
    }
    @Test void eachConnectionReadsOnlyItsRequestedMarket() throws Exception {
        var service=mock(MarketService.class);var socket=new MarketSocket(service,new ObjectMapper());var eth=session("eth");when(eth.getUri()).thenReturn(URI.create("ws://localhost/ws/market/ETH/USDT"));
        try {socket.afterConnectionEstablished(eth);socket.publish();verify(service).view("ETH","USDT");verify(service,never()).view("BTC","USDT");}
        finally {socket.stop();}
    }
    @Test void malformedMarketPathIsRejectedBeforeCapacityUse() throws Exception {
        var socket=new MarketSocket(mock(MarketService.class),new ObjectMapper());var invalid=session("invalid");when(invalid.getUri()).thenReturn(URI.create("ws://localhost/ws/market/eth/USDT"));
        try {socket.afterConnectionEstablished(invalid);verify(invalid).close(CloseStatus.POLICY_VIOLATION);}
        finally {socket.stop();}
    }
}
