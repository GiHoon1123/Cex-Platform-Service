package dustin.cex.domains.market;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.*;

/** 기존 웹 서버와 같은 포트를 사용한다. 출처는 배포할 프론트 주소로 명시한다. */
@Configuration @EnableWebSocket @RequiredArgsConstructor
public class MarketWebConfig implements WebSocketConfigurer {
    private final MarketSocket socket;
    @Value("${frontend.origin:http://localhost:3003}") private String origin;
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket,"/ws/market/*/*").setAllowedOrigins(origin);
    }
}
