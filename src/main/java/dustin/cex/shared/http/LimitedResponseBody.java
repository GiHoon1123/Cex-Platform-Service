package dustin.cex.shared.http;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** JDK의 본문 수신·완료 처리를 그대로 사용하고 메모리에 모을 바이트 수만 제한한다. */
public final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private Flow.Subscription upstream;
    private long received;
    private boolean terminated;
    public LimitedResponseBody(long limit){
        if(limit<=0)throw new IllegalArgumentException("응답 크기 제한은 양수여야 합니다");
        this.limit=limit;
    }
    @Override public CompletionStage<byte[]> getBody(){return delegate.getBody();}
    @Override public void onSubscribe(Flow.Subscription subscription){upstream=subscription;delegate.onSubscribe(subscription);}
    @Override public void onNext(List<ByteBuffer> buffers){
        if(terminated)return;
        for(var buffer:buffers){
            // 덧셈의 범위 초과도 피한다. 초과 묶음은 하위 수신기에 넘기기 전에 취소한다.
            if(buffer.remaining()>limit-received){
                terminated=true;upstream.cancel();delegate.onError(new java.io.IOException("외부 응답 크기 초과"));return;
            }
            received+=buffer.remaining();
        }
        delegate.onNext(buffers);
    }
    @Override public void onError(Throwable error){if(!terminated){terminated=true;delegate.onError(error);}}
    @Override public void onComplete(){if(!terminated){terminated=true;delegate.onComplete();}}
}
