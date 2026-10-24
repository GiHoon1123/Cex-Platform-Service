package dustin.cex.domains.edd;
import java.time.*;

/** 원래 접수 이후 미종결 감독 기준이다. 회신으로 초기화하거나 금융 결론을 자동 생성하지 않는다. */
public final class CustomerReviewCompletionTarget {
    public static final String POLICY="OPEN_CASE_REVIEW_24H_V1";
    public enum State { OPEN_WAITING, OPEN_REVIEW_DUE, CLOSED, TIME_REVIEW_REQUIRED }
    public record Assessment(String policy,Instant targetAt,State state,boolean reviewTargetExceeded) {}
    private CustomerReviewCompletionTarget() {}
    public static Assessment assess(Instant received,Instant finalized,Instant observed){
        if(received==null||observed==null)throw new IllegalArgumentException("미종결 감독의 접수·관측 시각이 필요합니다");
        Instant target=received.plus(Duration.ofHours(24));
        boolean invalid=observed.isBefore(received)||(finalized!=null&&(finalized.isBefore(received)||finalized.isAfter(observed)));
        boolean late=finalized==null?!observed.isBefore(target):finalized.isAfter(target);
        return new Assessment(POLICY,target,invalid?State.TIME_REVIEW_REQUIRED:finalized!=null?State.CLOSED:late?State.OPEN_REVIEW_DUE:State.OPEN_WAITING,late);
    }
}
