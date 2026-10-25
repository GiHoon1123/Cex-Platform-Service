package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

/** 실제 운영 인증·PG·인증 암호화·HMAC·접근 기록을 검증한다. 손상은 읽은 행의 복사본에만 주입하고 원본/불변 제약은 건드리지 않는다. */
class CodeVaspTransferInspectionIntegrationTest extends CodeVaspTransferTestSupport {
    @Autowired CodeVaspTransferInspection inspection;
    @Autowired CodeVaspTransferEvidence evidence;
    @Autowired BitcoinDepositOperatorAccess access;
    @Autowired BitcoinSettings bitcoin;
    CodeVaspTransferExecution.State received;
    String name(){return declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName();}
    @BeforeEach void receive(){
        var intent=intents.reserve(search.id());var person=new CodeVaspTransferDataClient.Person(name(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));
        when(transfers.exchange(any())).thenReturn(new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),person,person,"b".repeat(64))));
        received=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments).execute(intent.id());clearInvocations(transfers);
    }
    CodeVaspTransferInspection.Inspection inspect(){return inspection.inspect(maker.user().getId(),maker.sid(),receipt.id(),received.attemptId());}
    long views(){return jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_inspections WHERE attempt_id=?",Long.class,received.attemptId());}
    CodeVaspTransferInspection service(CodeVaspTransferEvidence reader){return new CodeVaspTransferInspection(access,bitcoin,directory,reader,jdbc,audit,manager);}
    CodeVaspTransferInspection corrupted(Consumer<Map<String,Object>> corruption){
        var guarded=spy(jdbc);
        doAnswer(call->{Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);var rows=jdbc.queryForList(call.getArgument(0,String.class),args);var copy=new HashMap<>(rows.getFirst());corruption.accept(copy);return List.of(copy);}).when(guarded).queryForList(anyString(),any(Object[].class));
        return service(new CodeVaspTransferEvidence(guarded,crypto,json));
    }
    void unavailable(CodeVaspTransferInspection target){var failure=assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),received.attemptId()));assertEquals(503,failure.getStatusCode().value());assertEquals(0,views());}
    @Test void operatorAndApproverCanInspectVerifiedDetailsThroughHttpWithNoStore()throws Exception{
        for(var actor:List.of(maker,checker))mvc.perform(post("/api/admin/deposits/BTC/"+receipt.id()+"/origin-transfer/"+received.attemptId()+"/inspect").header("Authorization","Bearer "+actor.access()).header("X-Forwarded-For","203.0.113.77").with(request->{request.setRemoteAddr("192.0.2.42");return request;}))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.expected.fullName").value(name())).andExpect(jsonPath("$.expected.dateOfBirth").value("1990-01-01"))
            .andExpect(jsonPath("$.beneficiary.primaryName").value(name())).andExpect(jsonPath("$.comparison.status").value("CONSISTENT"))
            .andExpect(jsonPath("$.assurance").value("INSPECTION_ONLY_NOT_FINANCIAL_APPROVAL"));
        assertEquals(2,views());
        // 전달 헤더의 주장 대신 실제 접속지와 담당자·대상·업무가 같은 접근 증적으로 연결되는지 확인한다.
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_inspections v JOIN security_audit a ON a.target='codevasp-inspection:'||v.id::text AND a.actor_id=v.actor_id WHERE v.attempt_id=? AND a.action='CODE_TRANSFER_EVIDENCE_INSPECTED' AND a.source_address='192.0.2.42'",Long.class,received.attemptId()));
        org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);
    }
    @Test void eachInspectionHasDistinctImmutableAccessReceiptAndMinimalAudit(){var first=inspect();var second=inspect();assertNotEquals(first.id(),second.id());assertEquals(2,views());assertFalse(first.toString().contains(name()));for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action='CODE_TRANSFER_EVIDENCE_INSPECTED'"))for(String secret:List.of(name(),"1990-01-01",receipt.address()))assertFalse(row.get("detail").toString().contains(secret));}
    @Test void auditorKeepsMetadataAccessButCannotDecrypt()throws Exception{
        var auditor=account(UserRole.AUDITOR);mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk());
        for(UUID id:List.of(receipt.id(),UUID.randomUUID()))mvc.perform(post("/api/admin/deposits/BTC/"+id+"/origin-transfer/"+received.attemptId()+"/inspect").header("Authorization","Bearer "+auditor.access())).andExpect(status().isForbidden());assertEquals(0,views());
    }
    @Test void customerAndAnonymousCannotReadExistingOrMissingEvidence()throws Exception{for(UUID id:List.of(receipt.id(),UUID.randomUUID())){String path="/api/admin/deposits/BTC/"+id+"/origin-transfer/"+received.attemptId()+"/inspect";mvc.perform(post(path)).andExpect(status().isUnauthorized());mvc.perform(post(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());}assertEquals(0,views());}
    @Test void staleOperatorMfaIsRejectedBeforeDecryption(){var guarded=spy(crypto);var target=service(new CodeVaspTransferEvidence(jdbc,guarded,json));jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=mfa_verified_at-interval '6 minutes' WHERE session_id=?",maker.sid());assertEquals(403,assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),received.attemptId())).getStatusCode().value());org.mockito.Mockito.verify(guarded,never()).decrypt(anyLong(),any(),any());assertEquals(0,views());}
    @Test void revokedOperatorCannotReadPastEvidence(){jdbc.update("UPDATE users SET enabled=false WHERE id=?",maker.user().getId());assertEquals(403,assertThrows(ResponseStatusException.class,this::inspect).getStatusCode().value());assertEquals(0,views());}
    @Test void anotherReceiptCannotBorrowReceivedAttempt(){var other=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());assertEquals(404,assertThrows(ResponseStatusException.class,()->inspection.inspect(maker.user().getId(),maker.sid(),other.id(),received.attemptId())).getStatusCode().value());assertEquals(0,views());}
    @Test void changedConnectionAuthorityCannotReadOldPersonalEvidence(){var settings=new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2","other-inspection-authority",KEY);var other=new CodeVaspDirectoryService(settings,directoryClient,jdbc,json,audit,manager);var target=new CodeVaspTransferInspection(access,bitcoin,other,evidence,jdbc,audit,manager);assertEquals(404,assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),received.attemptId())).getStatusCode().value());assertEquals(0,views());}
    @Test void damagedRequestCiphertextIsRejectedWithoutChangingOriginal(){unavailable(corrupted(row->{byte[] changed=((byte[])row.get("request_cipher")).clone();changed[changed.length-1]^=1;row.put("request_cipher",changed);}));assertEquals("CONSISTENT",inspect().comparison().status());}
    @Test void wrongResponseMacIsRejected(){unavailable(corrupted(row->row.put("response_mac","0".repeat(64))));}
    @Test void mismatchedStoredClassificationIsRecomputedAndRejected(){unavailable(corrupted(row->{row.put("assessment_status","REVIEW_REQUIRED");row.put("assessment_reason","BENEFICIARY_NAME_MISMATCH");}));}
    @Test void unsupportedKeyEpochDoesNotFallBackToAnotherKey(){unavailable(corrupted(row->row.put("response_key","0".repeat(64))));}
    @Test void failedMandatoryAuditRollsBackAccessReceipt(){doThrow(new IllegalStateException("격리 개인정보 접근 감사 장애")).when(audit).record(any(),eq("CODE_TRANSFER_EVIDENCE_INSPECTED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::inspect);assertEquals(0,views());assertEquals("DATA_RECEIVED",jdbc.queryForObject("SELECT status FROM codevasp_transfer_results WHERE attempt_id=?",String.class,received.attemptId()));}
    @Test void accessReceiptsCannotBeUpdatedDeletedTruncatedOrForged(){var view=inspect();for(String sql:List.of("UPDATE codevasp_transfer_inspections SET actor_version=actor_version+1","DELETE FROM codevasp_transfer_inspections","TRUNCATE codevasp_transfer_inspections"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_transfer_inspections(id,attempt_id,receipt_id,actor_id,actor_person,actor_version,session_id,key_id,request_mac,response_mac) SELECT ?,attempt_id,receipt_id,actor_id,actor_person,actor_version+1,session_id,key_id,request_mac,response_mac FROM codevasp_transfer_inspections WHERE id=?",UUID.randomUUID(),view.id()));assertEquals(1,views());}
    @Test void restrictedCustomerCanStillBeInvestigatedAndInternalReaderNeedsTransaction(){jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertEquals("CONSISTENT",inspect().comparison().status());assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->evidence.load(receipt.id(),received.attemptId(),customer.getId(),search.sourceId()));}
    @Test void verifiedMismatchCanBeInspectedWithoutCallingItTransportFailure(){
        receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);pending.recognize(receipt.id());search=searches.reserve(receipt.id());found(search);var intent=intents.reserve(search.id());
        var sender=new CodeVaspTransferDataClient.Person(name(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));var receiver=new CodeVaspTransferDataClient.Person("다른 수취인",null,LocalDate.of(1990,1,1),List.of(receipt.address()));
        doReturn(new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),sender,receiver,"b".repeat(64)))).when(transfers).exchange(any());received=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments).execute(intent.id());clearInvocations(transfers);
        var view=inspect();assertEquals("REVIEW_REQUIRED",view.comparison().status());assertEquals("BENEFICIARY_NAME_MISMATCH",view.comparison().reason());assertEquals("다른 수취인",view.beneficiary().primaryName());assertEquals(name(),view.expected().fullName());assertEquals(1,views());
    }
    @Test void otherMethodsAndUnregisteredSubpathsRemainDenied()throws Exception{
        String path="/api/admin/deposits/BTC/"+receipt.id()+"/origin-transfer/"+received.attemptId()+"/inspect";
        mvc.perform(get(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());mvc.perform(put(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());mvc.perform(post(path+"/extra").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());assertEquals(0,views());
    }
    @Test void silentlyOmittedAuditCannotCommitInspectionReceipt(){
        doNothing().when(audit).record(any(),eq("CODE_TRANSFER_EVIDENCE_INSPECTED"),anyString(),anyString(),anyString());var failure=assertThrows(RuntimeException.class,this::inspect);assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("필수 감사가 필요합니다"));assertEquals(0,views());
    }
}
