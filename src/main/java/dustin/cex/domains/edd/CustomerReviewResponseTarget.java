package dustin.cex.domains.edd;

import java.time.Duration;
import java.time.Instant;

/** 접수 이후 첫 공개 답변의 내부 점검 목표다. 법정 기한·영업일·최종 종결을 계산하지 않는다. */
public final class CustomerReviewResponseTarget {
    public static final String POLICY="FIRST_PUBLIC_RESPONSE_24H_V1";
    public enum State { WAITING, OVERDUE, RESPONDED_ON_TIME, RESPONDED_LATE, TIME_REVIEW_REQUIRED }
    public record Assessment(String policy,Instant targetAt,Instant firstPublicResponseAt,State state) {}
    private CustomerReviewResponseTarget() {}
    public static Assessment assess(Instant received,Instant firstStatusReply,Instant finalDecided,Instant observed) {
        if(received==null||observed==null)throw new IllegalArgumentException("접수·관측 시각이 필요합니다");
        // 내부 메모·직원 열람·고객 확인은 공개 답변이 아니다. 실제 공개 회신/최종 승인 중 먼저 확정된 시각만 쓴다.
        Instant first=firstStatusReply==null?finalDecided:finalDecided==null?firstStatusReply:
            (firstStatusReply.isBefore(finalDecided)?firstStatusReply:finalDecided);
        Instant target=received.plus(Duration.ofHours(24));
        // 시계 역행이나 서로 다른 호스트 시각 오차를 성공으로 보정하지 않는다. 운영자가 시각 근거를 확인해야 한다.
        if(observed.isBefore(received)||(first!=null&&(first.isBefore(received)||first.isAfter(observed))))
            return new Assessment(POLICY,target,first,State.TIME_REVIEW_REQUIRED);
        State state=first==null?(observed.isBefore(target)?State.WAITING:State.OVERDUE):
            (first.isAfter(target)?State.RESPONDED_LATE:State.RESPONDED_ON_TIME);
        return new Assessment(POLICY,target,first,state);
    }
}
