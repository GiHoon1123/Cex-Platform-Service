package dustin.cex.domains.kyc;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import dustin.cex.shared.http.LimitedResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 고정 공급사로만 통신한다. 리다이렉트·숨은 POST 재시도·응답 원문 로그를 허용하지 않는다. */
@Component
public class DiditClient {
    private final DiditSettings settings;private final ObjectMapper json;private final URI base;private final HttpClient http;
    @Autowired public DiditClient(DiditSettings settings,ObjectMapper json) {
        this(settings,json,URI.create("https://verification.didit.me"));
    }
    // 테스트는 실행 소유 루프백 HTTP 대역을 전달한다. 외부 요청으로 공급사 주소를 바꾸는 API는 없다.
    DiditClient(DiditSettings settings,ObjectMapper json,URI base) {
        this.settings=settings;this.json=json;this.base=base;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    JsonNode create(UUID attempt) {
        return call("/v3/session/",json.writeValueAsString(Map.of("workflow_id",settings.workflowId.toString(),
            "vendor_data",attempt.toString(),"callback",settings.callback.toString(),"callback_method","both")),201);
    }
    JsonNode find(UUID attempt) {
        // 결과 불명 POST는 반복하지 않는다. 외부의 정확 일치 검색으로 원래 시도를 확인한다.
        var page=call("/v3/sessions/?vendor_data="+attempt+"&workflow_id="+settings.workflowId+"&session_kind=user&limit=2",null,200);
        if(!page.path("count").isIntegralNumber() || !page.path("count").canConvertToInt() || page.path("count").intValue()!=1 || !page.path("results").isArray()
                || page.path("results").size()!=1 || !page.path("next").isNull()) throw unavailable();
        return page.path("results").get(0);
    }
    // 결정은 현재 상태 비교에만 사용한다. 생성 시각을 상태 변경 시각으로 바꾸지 않는다.
    JsonNode decision(UUID session) {
        return call("/v3/session/"+session+"/decision/",null,200);
    }
    // 외부 URL을 따라가지 않고 서버가 결합한 세션의 공식 전송 이력만 읽는다.
    JsonNode deliveries(UUID session) {
        return call("/v3/session/"+session+"/webhooks/",null,200);
    }
    private JsonNode call(String path,String payload,int expected) {
        if(!settings.configured()) throw unavailable();
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("x-api-key",settings.apiKey).header("Accept","application/json");
            if(payload!=null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));else request.GET();
            var pending=http.sendAsync(request.build(),info->new LimitedResponseBody(1048576));
            try {
                // 입력 스트림의 반환은 본문 수신 완료가 아니다. 본문 전체를 포함해 절대 시간을 제한한다.
                var result=pending.get(10,TimeUnit.SECONDS);
                if(result.statusCode()==429) throw new RateLimited(retryAfter(result));
                if(result.statusCode()!=expected) throw unavailable();
                return json.readTree(result.body());
            } finally {if(!pending.isDone()) pending.cancel(true);}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw unavailable();}
        catch(ResponseStatusException e) {throw e;}
        catch(ExecutionException | TimeoutException | RuntimeException e) {throw unavailable();}
    }
    /** 공급사 호출 제한은 다른 고객의 대사에도 적용되므로 대기 시간을 별도로 전달한다. */
    static final class RateLimited extends ResponseStatusException {
        final int seconds;
        RateLimited(int seconds) {
            super(HttpStatus.SERVICE_UNAVAILABLE,"고객 확인 공급사 호출 제한");
            this.seconds=seconds;
        }
    }
    private int retryAfter(HttpResponse<?> response) {
        // 공식 명세의 정수 초를 받는다. 잘못된 헤더는 60초, 비정상적으로 긴 값은 최대 하루로 제한한다.
        try {
            long seconds=Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"));
            return (int)Math.max(60,Math.min(86400,seconds));
        } catch(NumberFormatException e) {return 60;}
    }
    @jakarta.annotation.PreDestroy void stop() {http.shutdownNow();}
    private ResponseStatusException unavailable() {return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"KYC 공급사 결과를 확인할 수 없습니다");}
}
