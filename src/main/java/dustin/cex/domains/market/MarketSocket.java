package dustin.cex.domains.market;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 금융 소비자는 디비 커밋만 수행한다. 느린 브라우저 전송은 유한 별도 실행기로 분리한다. */
@Component @RequiredArgsConstructor
public class MarketSocket extends TextWebSocketHandler {
    private final MarketService service;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String,Client> clients=new ConcurrentHashMap<>();
    private final Semaphore capacity=new Semaphore(100);
    private final ThreadPoolExecutor sends=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),new ThreadPoolExecutor.AbortPolicy());
    private static final class Client {
        final WebSocketSession session;
        final AtomicLong started=new AtomicLong();
        volatile String last="";
        volatile long sent;
        Client(WebSocketSession session) {this.session=new ConcurrentWebSocketSessionDecorator(session,10000,1048576);}
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 공개 시세 연결만 허용한다. 계정·주문 구독은 이 채널에 추가하지 않는다.
        if(!capacity.tryAcquire()) {session.close(CloseStatus.POLICY_VIOLATION);return;}
        clients.put(session.getId(),new Client(session));
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {if(clients.remove(session.getId())!=null)capacity.release();}
    @Override public void handleTransportError(WebSocketSession session,Throwable error) throws Exception {var client=clients.get(session.getId());if(client!=null)close(session.getId(),client);}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        // 주문은 인증된 동기 HTTP에서만 받는다. 시세 소켓으로 명령을 우회하지 못한다.
        session.close(CloseStatus.POLICY_VIOLATION);
    }
    @Scheduled(fixedDelay=250)
    public void publish() {
        if(clients.isEmpty()) return;
        String payload;
        try {payload=mapper.writeValueAsString(service.view("BTC","USDT"));}
        catch(Exception unavailable) {payload="{\"status\":\"waiting\"}";}
        final String frame=payload;
        long now=System.currentTimeMillis();
        for(var entry:clients.entrySet()) {
            Client client=entry.getValue();
            long started=client.started.get();
            if(started!=0) {
                if(now-started>10000) close(entry.getKey(),client);
                continue;
            }
            // 전체 스냅샷이므로 느린 화면에 중간 프레임을 쌓지 않아도 최신 상태로
            // 동기화된다. 최근 체결의 영속 원본은 REST와 차트 API에서 다시 읽는다.
            boolean heartbeat=now-client.sent>=15000;
            if(frame.equals(client.last) && !heartbeat) continue;
            if(!client.started.compareAndSet(0,now)) continue;
            try {sends.execute(()-> {
                try {client.session.sendMessage(new TextMessage(frame));client.last=frame;client.sent=System.currentTimeMillis();}
                catch(Exception error) {close(entry.getKey(),client);}
                finally {client.started.set(0);}
            });} catch(RejectedExecutionException full) {client.started.set(0);close(entry.getKey(),client);}
        }
    }
    private void close(String id,Client client) {
        if(clients.remove(id,client))capacity.release();
        try {client.session.close(CloseStatus.SESSION_NOT_RELIABLE);} catch(Exception ignored) {}
    }
    @PreDestroy public void stop() {clients.forEach(this::close);sends.shutdownNow();}
}
