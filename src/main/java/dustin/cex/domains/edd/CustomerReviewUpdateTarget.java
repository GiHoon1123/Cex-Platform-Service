package dustin.cex.domains.edd;
import java.time.*;

/** 최초 답변 뒤의 후속 공개 회신도 점검한다. 새 회신은 다음 목표만 바꾸고 과거 지연은 지우지 않는다. */
public final class CustomerReviewUpdateTarget {
    public static final String POLICY="PERIODIC_PUBLIC_UPDATE_24H_V1";
    public enum State { WAITING, OVERDUE, CLOSED, TIME_REVIEW_REQUIRED }
    public record Assessment(String policy,Instant latestPublicAt,Instant targetAt,State state,long breachedGaps) {}
    private CustomerReviewUpdateTarget() {}
    public static Assessment assess(Instant received,Instant latestPublic,Instant finalized,long gaps,boolean invalidOrder,Instant observed){
        if(received==null||observed==null||gaps<0)throw new IllegalArgumentException("후속 회신의 시각·간격 근거가 필요합니다");
        Instant anchor=latestPublic==null?received:latestPublic,target=anchor.plus(Duration.ofHours(24));
        boolean invalid=invalidOrder||observed.isBefore(received)||anchor.isBefore(received)||anchor.isAfter(observed)
            ||(finalized!=null&&(finalized.isBefore(anchor)||finalized.isAfter(observed)));
        return new Assessment(POLICY,latestPublic,target,invalid?State.TIME_REVIEW_REQUIRED:finalized!=null?State.CLOSED:observed.isBefore(target)?State.WAITING:State.OVERDUE,gaps);
    }
}
