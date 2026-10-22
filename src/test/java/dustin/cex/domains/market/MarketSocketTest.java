package dustin.cex.domains.market;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

/** 공개 소켓의 유한 연결 수와 명령 우회 금지를 포트 없이 검증한다. */
class MarketSocketTest {
    WebSocketSession session(String id) {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn(id);when(session.isOpen()).thenReturn(true);return session;
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
}
