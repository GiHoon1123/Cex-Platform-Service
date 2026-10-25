package dustin.cex.domains.travelrule;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 현재 호출이 게이트웨이 전송 단계에 도달했는지를 구분한다. 같은 ID의 과거 호출 결과까지 증명하지 않는다. */
public final class CodeVaspSubmissionFailure extends ResponseStatusException {
    public enum Admission { NOT_SENT, OUTCOME_UNKNOWN }
    private final Admission admission;
    public CodeVaspSubmissionFailure(Admission admission){
        super(HttpStatus.SERVICE_UNAVAILABLE,"이전거래 공급사 요청을 완료할 수 없습니다");
        this.admission=java.util.Objects.requireNonNull(admission);
    }
    public Admission admission(){return admission;}
}
