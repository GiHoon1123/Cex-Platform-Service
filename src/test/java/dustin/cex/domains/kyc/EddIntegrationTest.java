package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;
import dustin.cex.domains.security.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 기존 사건 검증은 공통 실제 신원·보안키 준비 경로를 그대로 사용한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class EddIntegrationTest extends EddTestSupport {
    @Test void ordinaryVerifiedCustomerDoesNotNeedEddCase() throws Exception {verify(false);allowed();assertNull(service.status(customer.getId()));}
    @Test void reviewCreatesCaseAndNewDeclarationCannotRemoveHold() throws Exception {
        reviewed();var first=service.status(customer.getId());corrected();var next=service.status(customer.getId());
        assertEquals(first.id(),next.id());assertTrue(next.revision()>first.revision());assertEquals(List.of("IDENTITY_MISMATCH"),next.reviewReasons());blocked();
    }
    @Test void freshSignedReverificationAndIndependentSignatureCloseOnlyMismatch() throws Exception {
        var r=closure();assertEquals("PENDING",r.status());assertEquals("APPROVED",decide(r,true,proof(checker,r,true)).status());
        assertEquals("CLOSED",service.status(customer.getId()).state());allowed();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_decisions WHERE report_id=?",Long.class,r.payload().id()));
    }
    @Test void rejectionPreservesHoldAndAllowsNewReport() throws Exception {
        var r=closure();assertEquals("REJECTED",decide(r,false,proof(checker,r,false)).status());blocked();
        assertNotEquals(r.payload().id(),propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY).payload().id());
    }
    @Test void complianceReferralRecordsConclusionWithoutOpeningTrading() throws Exception {
        reviewed();var r=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(r,true,proof(checker,r,true));assertEquals("REFERRED",service.status(customer.getId()).state());blocked();
    }
    @Test void awaitingEvidenceApprovalDoesNotPretendToFinishEdd() throws Exception {
        reviewed();var r=propose(EddService.Finding.AWAITING_EVIDENCE);decide(r,true,proof(checker,r,true));assertEquals("OPEN",service.status(customer.getId()).state());blocked();
    }
    @Test void historicalPepCannotBeErasedByNoPepRedeclaration() throws Exception {
        cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.YES));verify(false);
        corrected();assertTrue(service.status(customer.getId()).reviewReasons().contains("PEP_REVIEW_REQUIRED"));
        assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }
    @Test void oldOrUnverifiedAssessmentCannotBeClosedByHuman() throws Exception {reviewed();assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();}
    @Test void disclosureChangeAfterOptionsInvalidatesSignature() throws Exception {
        var r=closure();var p=proof(checker,r,true);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
        assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_decisions WHERE report_id=?",Long.class,r.payload().id()));
    }
    @Test void newAssessmentInvalidatesOldReportAndDoesNotBlockNewProposal() throws Exception {
        var r=closure();var p=proof(checker,r,true);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));verify(false);
        assertThrows(ResponseStatusException.class,()->decide(r,true,p));assertNotEquals(r.payload().id(),propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY).payload().id());blocked();
    }
    @Test void closedCaseReopensOnNewVerificationFact() throws Exception {
        var r=closure();decide(r,true,proof(checker,r,true));allowed();
        cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));verify(false);assertEquals("OPEN",service.status(customer.getId()).state());blocked();
    }
    @Test void closedDispositionExpiresWithoutBackgroundWorker() throws Exception {
        var r=closure();decide(r,true,proof(checker,r,true));allowed();jdbc.update("UPDATE edd_cases SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());blocked();
    }
    @Test void currentKycFreshnessStillRequiredForClosure() throws Exception {
        reviewed();corrected();jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=?",kyc.status(customer.getId()).attemptId());
        assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }
    @Test void invalidClosureLifetimeAndRetentionFindingLifetimeAreRejected() throws Exception {
        reviewed();corrected();
        for(Instant until:Arrays.asList(null,Instant.now().minusSeconds(1),Instant.now().plusSeconds(86401))) assertThrows(ResponseStatusException.class,()->service.propose(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),UUID.randomUUID(),EddService.Finding.CORRECTED_SIGNED_IDENTITY,until));
        assertThrows(ResponseStatusException.class,()->service.propose(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),UUID.randomUUID(),EddService.Finding.AWAITING_EVIDENCE,Instant.now().plusSeconds(100)));
    }
    @Test void proposalKeyIsBoundToExactCurrentBodyAndOnePendingReport() throws Exception {
        reviewed();UUID key=UUID.randomUUID();var assessment=cdd.status(customer.getId()).assessmentId();
        var r=service.propose(maker.user().getId(),maker.sid(),customer.getId(),assessment,key,EddService.Finding.AWAITING_EVIDENCE,null);
        assertEquals(r,service.propose(maker.user().getId(),maker.sid(),customer.getId(),assessment,key,EddService.Finding.AWAITING_EVIDENCE,null));
        assertThrows(ResponseStatusException.class,()->service.propose(maker.user().getId(),maker.sid(),customer.getId(),assessment,key,EddService.Finding.COMPLIANCE_ESCALATION,null));
        assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.AWAITING_EVIDENCE));
    }
    @Test void samePersonDifferentApproverAccountCannotSelfApprove() throws Exception {
        var r=closure();var a=users.findById(checker.user().getId()).orElseThrow();a.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(a);
        assertThrows(ResponseStatusException.class,()->proof(checker,r,true));blocked();
    }
    @Test void requesterRoleVersionChangeInvalidatesPendingReport() throws Exception {
        var r=closure();var p=proof(checker,r,true);var a=users.findById(maker.user().getId()).orElseThrow();a.setTradingRestricted(true);users.saveAndFlush(a);
        assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();
    }
    @Test void sessionRevocationAndStaleMfaPreventApproval() throws Exception {
        var r=closure();var p=proof(checker,r,true);jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();
    }
    @Test void approvalProofCannotAuthorizeRejectionOrDifferentPayload() throws Exception {
        var r=closure();var p=proof(checker,r,true);assertThrows(ResponseStatusException.class,()->decide(r,false,p));
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),true,p));decide(r,true,p);allowed();
    }
    @Test void proofCannotBeReplayedAfterCommittedDecision() throws Exception {
        var r=closure();var p=proof(checker,r,true);decide(r,true,p);assertThrows(ResponseStatusException.class,()->decide(r,true,p));allowed();
    }
    @Test void approvalAuditFailureRollsBackCaseDecisionChallengeAndCounter() throws Exception {
        var r=closure();var p=proof(checker,r,true);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_DECIDED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->decide(r,true,p));blocked();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_decisions WHERE report_id=?",Long.class,r.payload().id()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));reset(audit);decide(r,true,p);allowed();
    }
    @Test void proposalAuditFailureRollsBackReportAndApprovalBinding() throws Exception {
        reviewed();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_PROPOSED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->propose(EddService.Finding.AWAITING_EVIDENCE));assertTrue(service.reports(customer.getId(),0).isEmpty());blocked();
    }
    @Test void immutableReportAndDecisionRejectMutation() throws Exception {
        var r=closure();decide(r,true,proof(checker,r,true));
        for(String table:List.of("edd_reports","edd_decisions")) {
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM "+table));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE edd_reports SET payload='{}' WHERE id=?",r.payload().id()));allowed();
    }
    @Test void concurrentApprovalAndRejectionHaveExactlyOneConclusion() throws Exception {
        var r=closure();var approve=proof(checker,r,true);var other=account(UserRole.APPROVER);var reject=proof(other,r,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();try{decide(r,true,approve);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});
            var b=pool.submit(()->{gate.await();try{service.decide(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),false,reject);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});
            gate.countDown();assertNotEquals(a.get(12,TimeUnit.SECONDS),b.get(12,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_decisions WHERE report_id=?",Long.class,r.payload().id()));
    }
    @Test void customerAndAuditorCannotWriteAndResponsesContainOnlyBoundEvidence() throws Exception {
        reviewed();mvc.perform(get("/api/admin/edd/customers/"+customer.getId()).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
        var auditor=account(UserRole.AUDITOR);
        mvc.perform(get("/api/admin/edd/customers/"+customer.getId()).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        var response=mvc.perform(post("/api/admin/edd/customers/"+customer.getId()+"/reports").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assessmentId",cdd.status(customer.getId()).assessmentId(),"finding","AWAITING_EVIDENCE")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(declaration(CustomerDeclaration.Pep.NO).fullName()));assertFalse(response.contains("1990-01-01"));
        mvc.perform(post("/api/admin/edd/customers/"+customer.getId()+"/reports").header("Authorization","Bearer "+auditor.access()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test void historicalAmlRiskCannotBeErasedByLaterCleanScreening() throws Exception {
        verify(false,true);assertTrue(service.status(customer.getId()).reviewReasons().contains("AML_REVIEW_REQUIRED"));corrected();
        assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }
    @Test void freshnessLostAfterOptionsCannotBeRestoredByOldSignature() throws Exception {
        var r=closure();var p=proof(checker,r,true);jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=?",r.payload().attemptId());
        assertThrows(ResponseStatusException.class,()->decide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));blocked();
    }
    @Test void oldApprovalSchemaFailsWithoutModifyingExistingHistory() throws Exception {
        reviewed();long count=jdbc.queryForObject("SELECT count(*) FROM cdd_assessments",Long.class);
        String schema=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");
            jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION')) NOT VALID");
            jdbc.execute(schema);
        }));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("현재 승인 스키마"));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM cdd_assessments",Long.class));
        assertTrue(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='operator_approval_bindings'::regclass AND conname='operator_approval_bindings_kind_check'",String.class).contains("EDD_REVIEW"));
    }
    @Test void expiredImmutableReportCannotIssueOptions() throws Exception {
        var r=closure();var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(r.payload());UUID id=UUID.randomUUID();
        tree.put("id",id.toString());tree.put("expiresAt",Instant.now().minusSeconds(1).toString());var p=json.treeToValue(tree,EddService.Payload.class);String digest=verifier.digest(tree);
        jdbc.update("INSERT INTO edd_reports(id,case_id,user_id,requester_id,request_key,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,p.caseId(),p.userId(),p.requesterId(),UUID.randomUUID(),json.writeValueAsString(p),digest,java.sql.Timestamp.from(p.expiresAt()));
        assertThrows(ResponseStatusException.class,()->service.options(checker.user().getId(),checker.sid(),id,digest,true));
        assertTrue(service.reports(customer.getId(),0).stream().anyMatch(x->x.status().equals("EXPIRED")));blocked();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void concurrentProposalKeysNeverCreateTwoPendingReports(boolean sameKey) throws Exception {
        reviewed();UUID key=UUID.randomUUID(),other=sameKey?key:UUID.randomUUID();UUID assessment=cdd.status(customer.getId()).assessmentId();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<UUID>>();
            for(UUID request:List.of(key,other)) results.add(pool.submit(()->{gate.await();try{return service.propose(maker.user().getId(),maker.sid(),customer.getId(),assessment,request,EddService.Finding.AWAITING_EVIDENCE,null).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}}));
            gate.countDown();var ids=new HashSet<UUID>();int successes=0;
            for(var f:results) {UUID id=f.get(12,TimeUnit.SECONDS);if(id!=null){ids.add(id);successes++;}}
            assertEquals(1,ids.size());assertEquals(sameKey?2:1,successes);
        }
        assertEquals(1,service.reports(customer.getId(),0).size());
    }
    @Test void invalidOriginOrMissingUserVerificationNeverConsumesBusinessDecision() throws Exception {
        var r=closure();
        service.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0);
        for(boolean userVerified:new boolean[]{false,true}) {
            var o=service.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true).authentication();
            var p=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,userVerified?"https://wrong.test":"http://localhost:3003",userVerified,counter.incrementAndGet(),null));
            assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();
        }
        decide(r,true,proof(checker,r,true));allowed();
    }
    @Test void roleAndEddProofsAreNotInterchangeable() throws Exception {
        var r=closure();var target=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var role=roles.request(maker.user().getId(),maker.sid(),target.getId(),target.getVersion(),UUID.randomUUID(),UserRole.OPERATOR,Instant.now().plusSeconds(3600),"별도 직무 검토","IDENTITY_100","NOTICE_100");
        var options=roles.options(checker.user().getId(),checker.sid(),role.payload().id(),role.payloadHash(),true).authentication();
        var roleProof=new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
        assertThrows(ResponseStatusException.class,()->decide(r,true,roleProof));
        var eddProof=proof(checker,r,true);
        assertThrows(ResponseStatusException.class,()->roles.decide(checker.user().getId(),checker.sid(),role.payload().id(),role.payloadHash(),true,eddProof));
        decide(r,true,eddProof);allowed();assertEquals(UserRole.CUSTOMER,users.findById(target.getId()).orElseThrow().getRole());
    }
    @Test void completeApprovalHttpUsesStoredPayloadAndActualSignature() throws Exception {
        var r=closure();String prefix="/api/admin/edd/reports/"+r.payload().id();
        mvc.perform(post(prefix+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk());
        var response=mvc.perform(post(prefix+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("payloadHash",r.payloadHash())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var node=json.readTree(response).path("authentication");var options=new OperatorMfaService.Options(UUID.fromString(node.path("challengeId").asText()),node.path("publicKey"));
        var p=new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
        String body=json.writeValueAsString(Map.of("payloadHash",r.payloadHash(),"proof",p));
        mvc.perform(post(prefix+"/approve").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());allowed();
        mvc.perform(post(prefix+"/approve").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }
    @Test void caseAuditFailureRollsBackIdentityAndAssessmentTogether() throws Exception {
        doThrow(new IllegalStateException("격리 사건 감사 실패")).when(audit).record(any(),eq("EDD_CASE_OPENED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->verify(true));assertNull(service.status(customer.getId()));
        assertEquals("PENDING",kyc.status(customer.getId()).identityStatus());assertEquals("PENDING_IDENTITY",cdd.status(customer.getId()).outcome());blocked();
    }
}
