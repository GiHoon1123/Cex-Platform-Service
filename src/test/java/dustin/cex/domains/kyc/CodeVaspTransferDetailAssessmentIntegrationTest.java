package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** 원본 수신·정보 비교·감사·지연 제약을 실제 PG에서 검증한다. 외부 정보 교환만 대역이다. */
class CodeVaspTransferDetailAssessmentIntegrationTest extends CodeVaspTransferTestSupport {
    CodeVaspTransferIntents.Intent intent;
    CodeVaspTransferExecution exchange;
    @BeforeEach void prepared(){intent=intents.reserve(search.id());exchange=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments);}
    String name(){return declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName();}
    CodeVaspTransferDataClient.Person person(){return new CodeVaspTransferDataClient.Person(name(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));}
    CodeVaspTransferDataClient.Result normal(CodeVaspTransferDataClient.Person sender,CodeVaspTransferDataClient.Person receiver){return new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),sender,receiver,"b".repeat(64)));}
    Map<String,Object> assessment(UUID attempt){return jdbc.queryForMap("SELECT * FROM codevasp_transfer_detail_assessments WHERE attempt_id=?",attempt);}
    CodeVaspTransferExecution.State receive(CodeVaspTransferDataClient.Person sender,CodeVaspTransferDataClient.Person receiver){when(transfers.exchange(any())).thenReturn(normal(sender,receiver));var state=exchange.execute(intent.id());assertEquals("DATA_RECEIVED",state.status());org.mockito.Mockito.verify(transfers,times(1)).exchange(any());clearInvocations(transfers);return state;}
    @Test void consistentDetailsAndEncryptedReplyCommitTogetherWithMinimalAudit(){
        var state=receive(person(),person());var a=assessment(state.attemptId());assertEquals("CONSISTENT",a.get("status"));assertEquals("DETAILS_CONSISTENT",a.get("reason"));assertEquals(CodeVaspTransferDetailAssessment.POLICY,a.get("policy_id"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_results WHERE attempt_id=? AND status='DATA_RECEIVED'",Long.class,state.attemptId()));
        var detail=jdbc.queryForObject("SELECT detail FROM security_audit WHERE action='CODE_TRANSFER_DETAILS_ASSESSED' AND target=?",String.class,"codevasp-attempt:"+state.attemptId());
        for(String secret:List.of(name(),"1990-01-01",receipt.address()))assertFalse(detail.contains(secret));
    }
    @Test void differentBeneficiaryNamePreservesReplyAndRequiresReviewWithoutResend(){
        var receiver=new CodeVaspTransferDataClient.Person("다른 수취인",null,LocalDate.of(1990,1,1),List.of(receipt.address()));var state=receive(person(),receiver);
        assertEquals("REVIEW_REQUIRED",assessment(state.attemptId()).get("status"));assertEquals("BENEFICIARY_NAME_MISMATCH",assessment(state.attemptId()).get("reason"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verifyNoInteractions(transfers);
    }
    @Test void differentOriginatorBirthIsReviewRatherThanTransportFailure(){
        var sender=new CodeVaspTransferDataClient.Person(name(),null,LocalDate.of(1991,1,1),List.of(receipt.address()));var state=receive(sender,person());assertEquals("REVIEW_REQUIRED",assessment(state.attemptId()).get("status"));assertEquals("ORIGINATOR_BIRTH_DATE_MISMATCH",assessment(state.attemptId()).get("reason"));
    }
    @Test void protocolOptionalBeneficiaryBirthCannotBecomeAutomaticConsistency(){
        var receiver=new CodeVaspTransferDataClient.Person(name(),null,null,List.of(receipt.address()));var state=receive(person(),receiver);assertEquals("BENEFICIARY_BIRTH_DATE_MISSING",assessment(state.attemptId()).get("reason"));
    }
    @Test void splitNamesAreNotAutomaticallyReordered(){
        var receiver=new CodeVaspTransferDataClient.Person("가상","고객",LocalDate.of(1990,1,1),List.of(receipt.address()));var state=receive(person(),receiver);assertEquals("BENEFICIARY_STRUCTURED_NAME",assessment(state.attemptId()).get("reason"));
    }
    @Test void assessmentAuditFailureLeavesUnresolvedStartAndNoAutomaticResend(){
        when(transfers.exchange(any())).thenReturn(normal(person(),person()));doThrow(new IllegalStateException("격리 정보 비교 감사 장애")).when(audit).record(isNull(),eq("CODE_TRANSFER_DETAILS_ASSESSED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->exchange.execute(intent.id()));var state=exchange.current(intent.id());assertEquals("RUNNING",state.status());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_results WHERE attempt_id=?",Long.class,state.attemptId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_detail_assessments WHERE attempt_id=?",Long.class,state.attemptId()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());clearInvocations(transfers);
    }
    @Test void omittedAssessmentPreventsReceivedResultCommit(){
        when(transfers.exchange(any())).thenReturn(normal(person(),person()));var omitted=mock(CodeVaspTransferDetailAssessment.class);var broken=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,omitted);
        var failure=assertThrows(RuntimeException.class,()->broken.execute(intent.id()));assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("같은 트랜잭션의 일관성 분류"));assertEquals("RUNNING",exchange.current(intent.id()).status());org.mockito.Mockito.verify(transfers,times(1)).exchange(any());clearInvocations(transfers);
    }
    @Test void classificationsCannotBeChangedDeletedOrInventedWithoutSource(){
        var state=receive(person(),person());for(String sql:List.of("UPDATE codevasp_transfer_detail_assessments SET status='REVIEW_REQUIRED',reason='PARTIES_INCOMPLETE'","DELETE FROM codevasp_transfer_detail_assessments","TRUNCATE codevasp_transfer_detail_assessments"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_transfer_detail_assessments(attempt_id,policy_id,status,reason) VALUES(?,'BTC_REQUEST_DETAILS_V1','CONSISTENT','DETAILS_CONSISTENT')",UUID.randomUUID()));assertEquals("CONSISTENT",assessment(state.attemptId()).get("status"));
    }
    @Test void recordingRequiresBusinessTransaction(){assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->assessments.record(UUID.randomUUID(),null,null));}
    @Test void consistentHistoricalDetailsDoNotEraseRestrictionChangedDuringIo(){
        when(transfers.exchange(any())).thenAnswer(call->{jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());return normal(person(),person());});var state=exchange.execute(intent.id());assertEquals("DATA_RECEIVED",state.status());assertEquals("CONSISTENT",assessment(state.attemptId()).get("status"));assertTrue(users.findById(customer.getId()).orElseThrow().isTradingRestricted());org.mockito.Mockito.verify(transfers,times(1)).exchange(any());clearInvocations(transfers);
    }
}
