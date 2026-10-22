package dustin.cex.domains.kyc;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
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
    private JsonNode call(String path,String payload,int expected) {
        if(!settings.configured()) throw unavailable();
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("x-api-key",settings.apiKey).header("Accept","application/json");
            if(payload!=null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));else request.GET();
            var pending=http.sendAsync(request.build(),info->new LimitedBody());
            try {
                // 입력 스트림의 반환은 본문 수신 완료가 아니다. 본문 전체를 포함해 절대 시간을 제한한다.
                var result=pending.get(10,TimeUnit.SECONDS);
                if(result.statusCode()!=expected) throw unavailable();
                return json.readTree(result.body());
            } finally {if(!pending.isDone()) pending.cancel(true);}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException | TimeoutException | RuntimeException e) {throw unavailable();}
    }
    /** JDK 수신기에 크기 제한만 추가한다. JSON·HTTP 전송·연결 풀 자체를 재구현하지 않는다. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription upstream;private long received;private boolean terminated;
        @Override public CompletionStage<byte[]> getBody() {return delegate.getBody();}
        @Override public void onSubscribe(Flow.Subscription subscription) {upstream=subscription;delegate.onSubscribe(subscription);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(terminated) return;
            for(var buffer:buffers) received+=buffer.remaining();
            if(received>1048576) {
                terminated=true;upstream.cancel();delegate.onError(new java.io.IOException("KYC 공급사 응답 크기 초과"));return;
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) {if(!terminated){terminated=true;delegate.onError(error);}}
        @Override public void onComplete() {if(!terminated){terminated=true;delegate.onComplete();}}
    }
    @jakarta.annotation.PreDestroy void stop() {http.shutdownNow();}
    private ResponseStatusException unavailable() {return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"KYC 공급사 결과를 확인할 수 없습니다");}
}
