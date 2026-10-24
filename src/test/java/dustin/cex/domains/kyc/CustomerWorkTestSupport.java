package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.OperatorMfaService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** 고객 접수·내부 기록·공개 회신이 실제로 존재하는 상태에서 현재 담당자 인계를 검증한다. */
abstract class CustomerWorkTestSupport extends ExitTestSupport {
    @Autowired CustomerExitReviewService requests;@Autowired CustomerReviewNoteService notes;
    @Autowired CustomerReviewReplyService replies;@Autowired CustomerWorkCustodyService handoffs;
    @Autowired CustomerReviewSupervisionService supervision;
    CustomerExitReviewService.Receipt receipt;CustomerReviewNoteService.Note note;CustomerReviewReplyService.Reply reply;
    Account nextPrimary,nextChecker;
    @BeforeEach void customerWork() throws Exception {
        initiated();receipt=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("인계 후에도 보존할 고객 사유"));
        requests.inspect(maker.user().getId(),maker.sid(),receipt.id());
        note=notes.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"기존 담당자의 비공개 처리 기록"));
        reply=replies.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),note.id());
        nextPrimary=account(UserRole.OPERATOR);nextChecker=account(UserRole.APPROVER);
    }
    CustomerWorkCustodyService.Context context(Account actor){return handoffs.context(actor.user().getId(),actor.sid(),receipt.id());}
    CustomerWorkCustodyService.Request proposal(Account actor,UUID key){var c=context(actor);return handoffs.propose(actor.user().getId(),actor.sid(),receipt.id(),c.stamp(),nextPrimary.user().getId(),nextChecker.user().getId(),key,"고객 재검토 후속 담당자의 독립 교대");}
    OperatorMfaService.AuthorizationProof handoffProof(Account actor,CustomerWorkCustodyService.Request r,boolean approve) throws Exception {
        handoffs.inspect(actor.user().getId(),actor.sid(),r.payload().id());var options=handoffs.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),actor.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    CustomerWorkCustodyService.Request decideHandoff(Account actor,CustomerWorkCustodyService.Request r,boolean approve,OperatorMfaService.AuthorizationProof proof){return handoffs.decide(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    void transfer() throws Exception {var r=proposal(maker,UUID.randomUUID());decideHandoff(independent,r,true,handoffProof(independent,r,true));}
    void disable(Account a){var user=users.findById(a.user().getId()).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);}
}
