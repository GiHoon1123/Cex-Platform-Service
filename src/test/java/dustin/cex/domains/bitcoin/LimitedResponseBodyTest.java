package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.shared.http.LimitedResponseBody;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** 제한 바로 아래/경계/초과 묶음과 늦은 종료 신호를 확인한다. */
class LimitedResponseBodyTest {
    static final class Subscription implements Flow.Subscription{boolean cancelled;public void request(long n){}public void cancel(){cancelled=true;}}
    @Test void exactLimitIsAccepted(){var body=new LimitedResponseBody(3);var upstream=new Subscription();body.onSubscribe(upstream);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1}),ByteBuffer.wrap(new byte[]{2,3})));body.onComplete();assertArrayEquals(new byte[]{1,2,3},body.getBody().toCompletableFuture().join());assertFalse(upstream.cancelled);}
    @Test void overflowCancelsAndLateSignalsCannotTurnFailureIntoSuccess(){var body=new LimitedResponseBody(2);var upstream=new Subscription();body.onSubscribe(upstream);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4})));body.onComplete();assertTrue(upstream.cancelled);assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());}
    @Test void invalidLimitIsRejected(){assertThrows(IllegalArgumentException.class,()->new LimitedResponseBody(0));}
}
