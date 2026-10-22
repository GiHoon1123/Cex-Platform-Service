package dustin.cex.domains.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 고객 경로에 노출하지 않는 내부 조회다. 조회 실패를 빈 결과로 바꾸지 않는다. */
@Component
public class EngineAuditClient {
    private final String baseUrl, token;
    private final RestTemplate http;
    public EngineAuditClient(@Value("${engine.http.url:http://127.0.0.1:3002}") String baseUrl,
            @Value("${engine.audit.token:}") String token) {
        this.baseUrl=baseUrl; this.token=token;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(2000);factory.setReadTimeout(5000);
        this.http=new RestTemplate(factory);
    }
    public JsonNode page(long after,Long through,String engineId) {
        if(token.length()<32) throw new IllegalStateException("엔진 감사 조회 키가 필요합니다");
        var uri=UriComponentsBuilder.fromHttpUrl(baseUrl).path("/internal/audit/results").queryParam("after",after).queryParam("limit",50);
        if(through!=null) uri.queryParam("through",through);
        if(engineId!=null) uri.queryParam("engine_id",engineId);
        var headers=new HttpHeaders();headers.set("x-engine-audit-token",token);
        return http.exchange(uri.build().toUri(),HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody();
    }
}
