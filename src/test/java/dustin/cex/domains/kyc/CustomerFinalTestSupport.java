package dustin.cex.domains.kyc;
import dustin.cex.domains.edd.CustomerFinalReviewService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 고객 사유·내부 근거·회신·배정에서 최종 답변을 준비한다. */
abstract class CustomerFinalTestSupport extends CustomerWorkTestSupport {
    @Autowired CustomerFinalReviewService finals;
    CustomerFinalReviewService.Explanation explanation(){return new CustomerFinalReviewService.Explanation("제출한 자료와 재검토 사유를 확인했습니다. 해당 종료 개시는 현재 유지되며 추가 확인이 필요하면 고객센터에 재문의할 수 있습니다.");}
    dustin.cex.domains.security.OperatorMfaService.AuthorizationProof finalProof(CustomerFinalReviewService.Request r,boolean approve) throws Exception {
        finals.inspect(checker.user().getId(),checker.sid(),r.payload().id());var o=finals.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    CustomerFinalReviewService.Request finalDecide(CustomerFinalReviewService.Request r,boolean approve,dustin.cex.domains.security.OperatorMfaService.AuthorizationProof proof){return finals.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    CustomerFinalReviewService.Request approvedFinal() throws Exception {var r=draft();return finalDecide(r,true,finalProof(r,true));}
    CustomerFinalReviewService.Request draft(){return finals.propose(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),note.id(),explanation());}
}
