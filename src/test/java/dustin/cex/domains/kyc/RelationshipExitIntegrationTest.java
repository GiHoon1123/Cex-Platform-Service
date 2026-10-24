package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 회부·별도 업무 서명·현재 근거·역할 및 인물 배정·감사 원자성을 격리 PostgreSQL에서 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class RelationshipExitIntegrationTest extends EddTestSupport {
    @Autowired dustin.cex.domains.order.service.OrderService orders;
    @Autowired dustin.cex.domains.balance.repository.UserBalanceRepository balances;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.fee.service.FeeConfigService feePolicies;
    @Autowired ComplianceReferralBasis referralBasis;
    @Autowired EvidenceCrypto reportingCrypto;@Autowired RelationshipExitService reporting;@Autowired EvidenceService evidence;
    EddService.Report referral;
    RelationshipExitService.Facts facts() {return new RelationshipExitService.Facts(true,"요청한 확인 자료로 고객확인을 완료할 수 없어 관계 종료 절차를 개시할 필요가 있음");}
    void referred() throws Exception {reviewed();referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));assertEquals("REFERRED",service.status(customer.getId()).state());}
    RelationshipExitService.Review request(UUID key,RelationshipExitService.Facts f) {return reporting.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),key,f);}
    RelationshipExitService.Review prepared() throws Exception {referred();return request(UUID.randomUUID(),facts());}
    void inspectAll(Account actor,RelationshipExitService.Review r) {for(int page=0;page<r.payload().dossier().pageCount();page++)reporting.inspect(actor.user().getId(),actor.sid(),r.payload().id(),page);}
    OperatorMfaService.AuthorizationProof reportProof(Account actor,RelationshipExitService.Review r,boolean approve) throws Exception {
        inspectAll(actor,r);var o=reporting.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();return new OperatorMfaService.AuthorizationProof(o.challengeId(),actor.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    RelationshipExitService.Review reportDecide(RelationshipExitService.Review r,boolean approve,OperatorMfaService.AuthorizationProof p) {return reporting.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,p);}
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+(table.equals("relationship_exit_decisions")?"review_id IN (SELECT id FROM relationship_exit_requests WHERE user_id=?)":"user_id=?"),Long.class,customer.getId());}
    @Test void approvedExitInitiatesWithoutMarkingClosedOrChangingOriginalCase() throws Exception {
        var r=prepared();long revision=service.status(customer.getId()).revision();
        reportDecide(r,true,reportProof(checker,r,true));assertEquals("EXIT_PENDING",reporting.customerStatus(customer.getId()).state());
        assertEquals(revision,service.status(customer.getId()).revision());assertEquals("REFERRED",service.status(customer.getId()).state());
        assertTrue(users.findById(customer.getId()).orElseThrow().isEnabled());blocked();
    }
    @Test void unapprovedOrNonReferralCannotCreateReview() throws Exception {
        reviewed();referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),facts()));assertEquals(0,count("relationship_exit_requests"));blocked();
    }
    @Test void awaitingEvidenceDecisionIsNotExitReferral() throws Exception {reviewed();referral=propose(EddService.Finding.AWAITING_EVIDENCE);decide(referral,true,proof(checker,referral,true));assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),facts()));}
    @Test void anotherOperatorsAssignedReviewIsNotVisibleEvenWithCorrectRole() throws Exception {
        var r=prepared();for(var other:List.of(account(UserRole.OPERATOR),account(UserRole.APPROVER))){assertTrue(reporting.list(other.user().getId(),other.sid(),0).isEmpty());assertEquals(404,assertThrows(ResponseStatusException.class,()->reporting.inspect(other.user().getId(),other.sid(),r.payload().id(),0)).getStatusCode().value());assertThrows(ResponseStatusException.class,()->reporting.options(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),true));}
    }
    @Test void requesterCannotApproveByChangingRole() throws Exception {var r=prepared();var user=users.findById(maker.user().getId()).orElseThrow();user.setRole(UserRole.APPROVER);users.saveAndFlush(user);assertThrows(ResponseStatusException.class,()->reporting.options(user.getId(),maker.sid(),r.payload().id(),r.payloadHash(),true));blocked();}
    @Test void sameHumanDifferentAccountsCannotBeMakerAndChecker() throws Exception {referred();var other=account(UserRole.APPROVER);var u=users.findById(other.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->reporting.propose(maker.user().getId(),maker.sid(),referral.payload().id(),u.getId(),UUID.randomUUID(),facts()));}
    @Test void assignedMetadataNeverIncludesRationaleOrUnableJudgment() throws Exception {var r=prepared();for(var a:List.of(maker,checker)){String encoded=json.writeValueAsString(reporting.list(a.user().getId(),a.sid(),0));assertTrue(encoded.contains(r.payload().id().toString()));assertFalse(encoded.contains(facts().rationale()));assertFalse(encoded.contains("dueDiligenceUnable"));}String payload=jdbc.queryForObject("SELECT payload FROM relationship_exit_requests WHERE id=?",String.class,r.payload().id());assertFalse(payload.contains(facts().rationale()));assertFalse(new String(jdbc.queryForObject("SELECT facts_cipher FROM relationship_exit_requests WHERE id=?",byte[].class,r.payload().id()),StandardCharsets.UTF_8).contains(facts().rationale()));}
    @Test void noPageInspectionCannotIssueApprovalOptions() throws Exception {var r=prepared();assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void makersInspectionCannotReplaceCheckersOwnReceipt() throws Exception {var r=prepared();inspectAll(maker,r);assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void receiptFromAnotherSessionIsNotUsable() throws Exception {var r=prepared();inspectAll(checker,r);var old=tokens.findBySessionId(checker.sid()).orElseThrow();var session=tokens.saveAndFlush(RefreshToken.builder().userId(checker.user().getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).mfaVerifiedAt(old.getMfaVerifiedAt()).mfaCredentialFingerprint(old.getMfaCredentialFingerprint()).build());assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),session.getSessionId(),r.payload().id(),r.payloadHash(),true));}
    @Test void oldReceiptCannotAuthorizeDecision() throws Exception {
        var r=prepared();var p=r.payload();
        // 현재 요청·계정·세션의 과거 제공 표본만 추가한다. 기존 영수증을 바꾸거나 신선한 영수증을 섞지 않는다.
        jdbc.update("INSERT INTO relationship_exit_views(id,review_id,actor_id,actor_person,actor_version,session_id,page,payload_hash,viewed_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()-interval '6 minutes')",UUID.randomUUID(),p.id(),checker.user().getId(),p.checkerPerson(),p.checkerVersion(),checker.sid(),0,r.payloadHash());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),p.id(),r.payloadHash(),true)).getStatusCode().value());
    }
    @Test void newEvidenceAfterSignatureRejectsDecisionWithoutConsumingProof() throws Exception {var r=prepared();var p=reportProof(checker,r,true);evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());assertThrows(ResponseStatusException.class,()->reportDecide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));}
    @Test void newDeclarationMakesOldReferralUnusableButHistoryStaysReadable() throws Exception {var r=prepared();var p=reportProof(checker,r,true);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->reportDecide(r,true,p));assertEquals(facts(),reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0).facts());blocked();}
    @Test void newCaseConclusionInvalidatesOldExitReview() throws Exception {var r=prepared();var p=reportProof(checker,r,true);var reopen=propose(EddService.Finding.AWAITING_EVIDENCE);decide(reopen,true,proof(checker,reopen,true));assertThrows(ResponseStatusException.class,()->reportDecide(r,true,p));}
    @Test void changedMakerVersionInvalidatesBoundRequest() throws Exception {var r=prepared();var p=reportProof(checker,r,true);var u=users.findById(maker.user().getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->reportDecide(r,true,p));}
    @Test void changedCheckerVersionInvalidatesOptionsAndOldReceipts() throws Exception {var r=prepared();inspectAll(checker,r);var u=users.findById(checker.user().getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void staleMfaCannotReadOrDecide() throws Exception {var r=prepared();var session=tokens.findBySessionId(checker.sid()).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);assertThrows(ResponseStatusException.class,()->reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0));}
    @Test void sameKeyReplaysExactTargetAndFactsButChangedReasonConflicts() throws Exception {referred();UUID key=UUID.randomUUID();var r=request(key,facts());assertEquals(r.payload().id(),request(key,facts()).payload().id());assertThrows(ResponseStatusException.class,()->request(key,new RelationshipExitService.Facts(true,"다른 사유")));assertEquals(1,count("relationship_exit_requests"));}
    @Test void simultaneousSameKeyCreatesOneReview() throws Exception {referred();UUID key=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var fs=new ArrayList<Future<UUID>>();for(int i=0;i<2;i++)fs.add(pool.submit(()->{gate.await();return request(key,facts()).payload().id();}));gate.countDown();assertEquals(fs.get(0).get(12,TimeUnit.SECONDS),fs.get(1).get(12,TimeUnit.SECONDS));}assertEquals(1,count("relationship_exit_requests"));}
    @Test void oneLiveRequestAndOneApprovedDecisionPerCustomer() throws Exception {var r=prepared();assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),facts()));reportDecide(r,true,reportProof(checker,r,true));assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),new RelationshipExitService.Facts(true,"옛 보고 필요를 지우려는 사유")));}
    @Test void rejectionPreservesReasonAndAllowsNewRequest() throws Exception {var r=prepared();assertEquals("REJECTED",reportDecide(r,false,reportProof(checker,r,false)).status());assertEquals(facts(),reporting.inspect(maker.user().getId(),maker.sid(),r.payload().id(),0).facts());assertNotEquals(r.payload().id(),request(UUID.randomUUID(),facts()).payload().id());blocked();}
    @Test void proposalAuditFailureRollsBackCipherAndBinding() throws Exception {referred();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("RELATIONSHIP_EXIT_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->request(UUID.randomUUID(),facts()));assertEquals(0,count("relationship_exit_requests"));}
    @Test void inspectionAuditFailureRollsBackReceiptAndReturnsNoReason() throws Exception {var r=prepared();long before=jdbc.queryForObject("SELECT count(*) FROM relationship_exit_views WHERE review_id=?",Long.class,r.payload().id());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("RELATIONSHIP_EXIT_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_views WHERE review_id=?",Long.class,r.payload().id()));}
    @Test void decisionAuditFailureRollsBackProofAndDecisionThenRetrySucceeds() throws Exception {var r=prepared();var p=reportProof(checker,r,true);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("RELATIONSHIP_EXIT_DECIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->reportDecide(r,true,p));assertEquals(0,count("relationship_exit_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));reset(audit);assertEquals("APPROVED",reportDecide(r,true,p).status());}
    @Test void oppositeDecisionCannotUseApprovalSignature() throws Exception {var r=prepared();var p=reportProof(checker,r,true);assertThrows(ResponseStatusException.class,()->reportDecide(r,false,p));assertEquals(0,count("relationship_exit_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));}
    @Test void actualApproveRejectRaceCommitsExactlyOneDecision() throws Exception {var r=prepared();var yes=reportProof(checker,r,true);var no=reportProof(checker,r,false);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var fs=new ArrayList<Future<Boolean>>();for(boolean approve:new boolean[]{true,false})fs.add(pool.submit(()->{gate.await();try{reportDecide(r,approve,approve?yes:no);return true;}catch(ResponseStatusException e){return false;}}));gate.countDown();int successes=0;for(var f:fs)if(f.get(12,TimeUnit.SECONDS))successes++;assertEquals(1,successes);}assertEquals(1,count("relationship_exit_decisions"));blocked();}
    @Test void auditAndGeneralDossierDoNotCopyDecisionOrReason() throws Exception {var r=prepared();reportDecide(r,true,reportProof(checker,r,true));String all=jdbc.queryForList("SELECT action,detail FROM security_audit WHERE target=?", "relationship-exit:"+r.payload().id()).toString();assertFalse(all.contains(facts().rationale()));assertFalse(all.contains("dueDiligenceUnable"));String bundle=jdbc.queryForObject("SELECT items FROM edd_case_dossiers WHERE id=?",String.class,r.payload().dossier().id());assertFalse(bundle.contains(r.payload().id().toString()));assertFalse(bundle.contains("dueDiligenceUnable"));}
    @Test void newTablesRejectAllMutationStatements() throws Exception {var r=prepared();reportDecide(r,true,reportProof(checker,r,true));for(String table:List.of("relationship_exit_requests","relationship_exit_views","relationship_exit_decisions")){for(String sql:List.of("UPDATE "+table+" SET "+(table.equals("relationship_exit_requests")?"payload=payload":table.equals("relationship_exit_views")?"page=page":"approved=approved"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));}assertEquals(1,count("relationship_exit_decisions"));}
    @Test void oldTenKindSchemaFailsWithoutTransformingExistingHistory() throws Exception {var r=prepared();String sql=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(StandardCharsets.UTF_8);assertThrows(DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW','EDD_ADDRESS_REVIEW','EDD_FUNDS_REVIEW','EDD_WEALTH_REVIEW','STR_REVIEW','STR_CUSTODY')) NOT VALID");jdbc.execute(sql);}));assertEquals(1,count("relationship_exit_requests"));assertTrue(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='operator_approval_bindings_kind_check'",String.class).contains("RELATIONSHIP_EXIT"));}
    @Test void laterActualCddAndEddClearanceCannotUndoApprovedExit() throws Exception {
        var r=prepared();reportDecide(r,true,reportProof(checker,r,true));
        corrected();var correction=propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY);decide(correction,true,proof(checker,correction,true));
        assertEquals(Boolean.TRUE,new TransactionTemplate(manager).execute(t->cdd.permitted(customer.getId())));
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());blocked();
        assertEquals("EXIT_PENDING",reporting.customerStatus(customer.getId()).state());
    }
    @Test void unauthorizedCustomerAndAuditorCannotReadAnyReviewEndpoint() throws Exception {var r=prepared();var auditor=account(UserRole.AUDITOR);for(String token:List.of(customerAccess,auditor.access())){mvc.perform(get("/api/admin/compliance/relationship-exits").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(post("/api/admin/compliance/relationship-exits/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());}}
    @Test void realHttpProposalInspectionOptionsAndBoundSignatureUseStoredFacts() throws Exception {
        referred();String body=json.writeValueAsString(new RelationshipExitController.Proposal(referral.payload().id(),checker.user().getId(),facts()));String raw=mvc.perform(post("/api/admin/compliance/relationship-exits").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();var r=json.readValue(raw,RelationshipExitService.Review.class);
        mvc.perform(post("/api/admin/compliance/relationship-exits/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.facts.rationale").value(facts().rationale()));
        var o=json.readValue(mvc.perform(post("/api/admin/compliance/relationship-exits/"+r.payload().id()+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new RelationshipExitController.Confirmation(r.payloadHash())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),RelationshipExitService.Confirmation.class).authentication();
        var p=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));mvc.perform(post("/api/admin/compliance/relationship-exits/"+r.payload().id()+"/approve").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new RelationshipExitController.Decision(r.payloadHash(),p)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));blocked();
    }
    @Test void everyFrozenPageIsRequiredIncludingFinalPartialPage() throws Exception {
        reviewed();var c=service.status(customer.getId());
        // 페이지 경계를 검증하는 과거 자료 요청 표본만 추가한다. 내용 확인 완료나 외부 진위를 합성하지 않는다.
        for(int i=0;i<28;i++)jdbc.update("INSERT INTO edd_evidence_requests(id,case_id,user_id,assessment_id,purpose,requester_id,requester_person,request_key,expires_at,created_at) VALUES(?,?,?,?,'SOURCE_OF_FUNDS',?,?,?,clock_timestamp()-interval '2 days',clock_timestamp()-interval '3 days')",UUID.randomUUID(),c.id(),customer.getId(),c.latestAssessment(),maker.user().getId(),maker.user().getOperatorPersonId(),UUID.randomUUID());
        referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));var r=request(UUID.randomUUID(),facts());assertEquals(2,r.payload().dossier().pageCount());
        assertEquals(25,reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0).dossier().items().size());assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));
        assertEquals(3,reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),1).dossier().items().size());assertNotNull(reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));
    }
    RelationshipExitService.Review datedCopy(RelationshipExitService.Review original,boolean damaged) {
        // 만료·다른 문맥의 암호문을 별도 과거 표본으로 추가한다. 실제 요청이나 불변 결정을 갱신하지 않는다.
        var raw=(tools.jackson.databind.node.ObjectNode)json.valueToTree(original.payload());UUID id=UUID.randomUUID();raw.put("id",id.toString()).put("expiresAt",Instant.now().minusSeconds(1).toString());
        var p=json.readValue(json.writeValueAsString(raw),RelationshipExitService.Payload.class);String hash=verifier.digest(raw);
        byte[] cipher=damaged?jdbc.queryForObject("SELECT facts_cipher FROM relationship_exit_requests WHERE id=?",byte[].class,original.payload().id()):reportingCrypto.encrypt("RELATIONSHIP_EXIT:"+id+":"+p.userId()+":"+p.caseId()+":"+p.referralId()+":"+p.factsMac(),json.writeValueAsBytes(facts()));
        jdbc.update("INSERT INTO relationship_exit_requests(id,case_id,user_id,referral_id,requester_id,checker_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,p.caseId(),p.userId(),p.referralId(),p.requesterId(),p.checkerId(),UUID.randomUUID(),json.writeValueAsString(raw),hash,cipher,java.sql.Timestamp.from(p.expiresAt()));return new RelationshipExitService.Review(p,hash,"EXPIRED");
    }
    @Test void expiredRequestIsReadableButCannotIssueNewDecision() throws Exception {
        var r=datedCopy(prepared(),false);assertEquals(facts(),reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0).facts());assertEquals(409,assertThrows(ResponseStatusException.class,()->reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true)).getStatusCode().value());
    }
    @Test void copiedCipherFromAnotherReviewFailsWithoutPlaintextOrReceipt() throws Exception {
        var r=datedCopy(prepared(),true);var error=assertThrows(IllegalStateException.class,()->reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),0));assertNull(error.getCause());assertFalse(error.toString().contains(facts().rationale()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_views WHERE review_id=?",Long.class,r.payload().id()));
    }
    @Test void signatureForDifferentBusinessKindCannotApproveExitReview() throws Exception {
        var r=prepared();inspectAll(checker,r);UUID other=UUID.randomUUID();
        // 다른 업무의 유효한 인증 결합만 만든다. 허구의 금융 확정·외부 보고 성공을 생성하지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(t->mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_REVIEW,other,maker.user().getId(),r.payload().requesterVersion(),r.payloadHash(),r.payload().expiresAt()));
        var o=new TransactionTemplate(manager).execute(t->mfa.approvalOptions(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.EDD_REVIEW,other,r.payloadHash(),true));
        var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));assertThrows(ResponseStatusException.class,()->reportDecide(r,true,proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));assertEquals(0,count("relationship_exit_decisions"));
    }

    @Test void changedCustomerVersionInvalidatesSignatureWithoutConsumingIt() throws Exception {
        var r=prepared();var proof=reportProof(checker,r,true);var u=users.findById(customer.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);
        assertThrows(ResponseStatusException.class,()->reportDecide(r,true,proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));
    }
    @Test void rejectionAddsNoExitHoldAfterActualCddClearance() throws Exception {
        var r=prepared();reportDecide(r,false,reportProof(checker,r,false));corrected();var correction=propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY);decide(correction,true,proof(checker,correction,true));
        allowed();assertEquals("NO_EXIT_DECISION",reporting.customerStatus(customer.getId()).state());
    }
    @Test void explicitUnableJudgmentIsRequiredInsteadOfTreatingAnyRiskAsTermination() throws Exception {
        referred();assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),new RelationshipExitService.Facts(false,"고위험이라는 이유만으로는 확인 불가를 단정하지 않는다")));assertEquals(0,count("relationship_exit_requests"));
    }
    @Test void reportingAndExitJudgmentsUseIndependentDecisionsAndNeitherErasesOther() throws Exception {
        referred();var reportingReview=reports.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),UUID.randomUUID(),new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,"별도로 보고 필요를 검토할 가상 근거"));
        for(int page=0;page<reportingReview.payload().dossier().pageCount();page++)reports.inspect(checker.user().getId(),checker.sid(),reportingReview.payload().id(),page);
        var o=reports.options(checker.user().getId(),checker.sid(),reportingReview.payload().id(),reportingReview.payloadHash(),true).authentication();
        reports.decide(checker.user().getId(),checker.sid(),reportingReview.payload().id(),reportingReview.payloadHash(),true,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
        var r=request(UUID.randomUUID(),facts());reportDecide(r,true,reportProof(checker,r,true));
        assertTrue(followups.queue(maker.user().getId(),maker.sid(),null).items().stream().anyMatch(i->i.reviewId().equals(reportingReview.payload().id())));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM compliance_review_decisions WHERE review_id=? AND approved",Long.class,reportingReview.payload().id()));
    }
    @Autowired ReportingReviewService reports;@Autowired ReportingFollowupService followups;
    @Test void customerHttpStatusOmitsInternalReasonAndOtherIdentities() throws Exception {
        var r=prepared();reportDecide(r,true,reportProof(checker,r,true));
        // 프레임워크의 기본 복합 헤더를 허용하면서 실제 저장 금지 지시가 있는지 검증한다.
        var response=mvc.perform(get("/api/customer/relationship").header("Authorization","Bearer "+customerAccess))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.state").value("EXIT_PENDING"));
        String body=response.andReturn().getResponse().getContentAsString();
        assertEquals(Set.of("state"),json.readTree(body).propertyNames());assertFalse(body.contains(facts().rationale()));
        mvc.perform(get("/api/customer/relationship").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
        mvc.perform(get("/api/customer/relationship")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk());
    }
    @Test void customerStatusAuditFailureDoesNotReturnState() throws Exception {
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("RELATIONSHIP_STATUS_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->reporting.customerStatus(customer.getId()));
    }
    @Test void commonReferralVerifierRequiresBusinessTransaction() {
        assertThrows(IllegalStateException.class,()->referralBasis.read(UUID.randomUUID()));
    }
    @Test void existingReservationReplayAndCancellationSurviveWhileNewOrderIsDenied() throws Exception {
        // 최초 반입을 다른 테스트가 이미 검증해 기록했다면 보존한다. 이번 고객 자산만 균형 분개로 추가한다.
        verify(false);allowed();if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();
        balances.saveAndFlush(dustin.cex.domains.balance.model.entity.UserBalance.builder().userId(customer.getId()).mintAddress("USDT").available(new java.math.BigDecimal("1000")).locked(java.math.BigDecimal.ZERO).build());
        new TransactionTemplate(manager).executeWithoutResult(t->ledger.record("test-exit-seed:"+UUID.randomUUID(),"TEST_FIXTURE",List.of(dustin.cex.domains.ledger.LedgerService.p(customer.getId(),"AVAILABLE","USDT",new java.math.BigDecimal("1000")),dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","USDT",new java.math.BigDecimal("-1000")))));
        // 공통 테스트 설정의 수수료 서비스는 대역이다. 예약 검증에 사용할 확정 적용률을 명시한다.
        when(feePolicies.getFeeConfig("BTC","USDT")).thenReturn(dustin.cex.domains.fee.model.entity.FeeConfig.builder().feeRate(java.math.BigDecimal.ZERO).feeType("both").isActive(true).build());
        when(engine.submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(true);when(engine.cancelOrder(anyLong(),anyLong(),anyString())).thenReturn(true);
        var command=dustin.cex.domains.order.model.dto.CreateOrderRequest.builder().baseMint("BTC").quoteMint("USDT").orderType("buy").orderSide("limit").price(new java.math.BigDecimal("10000")).amount(new java.math.BigDecimal("0.001")).build();UUID key=UUID.randomUUID();
        var accepted=orders.createOrder(customer.getId(),key,command);long order=Long.parseLong(accepted.response().getOrder().getId());
        var before=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId());
        // 유효한 KYC를 불필요하게 재시도하지 않는다. 새 신고 뒤 다시 확인해야 하는 실제 고객 흐름으로 위험 표본을 만든다.
        cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
        var r=prepared();reportDecide(r,true,reportProof(checker,r,true));
        assertFalse(orders.createOrder(customer.getId(),key,command).created());
        assertThrows(ResponseStatusException.class,()->orders.createOrder(customer.getId(),UUID.randomUUID(),command));
        mvc.perform(post("/api/cex/orders").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cex/orders").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command))).andExpect(status().isOk());
        mvc.perform(get("/api/cex/orders/"+order).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk());
        mvc.perform(delete("/api/cex/orders/"+order).header("Authorization","Bearer "+customerAccess)).andExpect(status().isAccepted());
        var after=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();assertEquals(before.getAvailable(),after.getAvailable());assertEquals(before.getLocked(),after.getLocked());assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId()));
        assertTrue(after.getLocked().signum()>0);org.mockito.Mockito.verify(engine,times(1)).submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any());
        // 취소 HTTP는 전송 성공만 대역이다. 실제 엔진 결과를 소비하기 전 예약을 풀거나 취소 완료로 덮어쓰지 않는다.
    }
}
