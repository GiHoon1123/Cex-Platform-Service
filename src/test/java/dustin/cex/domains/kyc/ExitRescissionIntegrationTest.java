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
class ExitRescissionIntegrationTest extends EddTestSupport {
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
    @Autowired ExitRescissionService rescissions;
    RelationshipExitService.Review original;Account independent;
    ExitRescissionService.Facts withdrawalFacts() {return new ExitRescissionService.Facts(true,"원래 종료 개시의 잘못된 판단을 재검토하고 별도 효과만 철회할 필요가 있음");}
    @BeforeEach void rescissionSetup() throws Exception {independent=account(UserRole.APPROVER);}
    void initiated() throws Exception {original=prepared();reportDecide(original,true,reportProof(checker,original,true));}
    ExitRescissionService.Request withdraw(UUID key) {return rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),independent.user().getId(),key,withdrawalFacts());}
    void withdrawalViews(Account actor,ExitRescissionService.Request r) {
        for(int page=0;page<r.payload().current().dossier().pageCount();page++)rescissions.inspect(actor.user().getId(),actor.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,page);
        if(!r.payload().originalDossier().digest().equals(r.payload().current().dossier().digest()))for(int page=0;page<r.payload().originalDossier().pageCount();page++)rescissions.inspect(actor.user().getId(),actor.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,page);
    }
    OperatorMfaService.AuthorizationProof withdrawalProof(Account actor,ExitRescissionService.Request r,boolean approve) throws Exception {
        withdrawalViews(actor,r);var o=rescissions.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(o.challengeId(),actor.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    ExitRescissionService.Request withdrawDecide(ExitRescissionService.Request r,boolean approve,OperatorMfaService.AuthorizationProof proof) {return rescissions.decide(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    void approveWithdrawal(ExitRescissionService.Request r) throws Exception {withdrawDecide(r,true,withdrawalProof(independent,r,true));}
    void clearedAfterExit() throws Exception {corrected();var correction=propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY);decide(correction,true,proof(checker,correction,true));assertEquals(Boolean.TRUE,new TransactionTemplate(manager).execute(t->cdd.permitted(customer.getId())));blocked();}
    long active() {return jdbc.queryForObject("SELECT count(*) FROM relationship_active_exits WHERE user_id=?",Long.class,customer.getId());}
    @Test void withdrawalRemovesOnlyExitHoldAndPreservesOriginalApprovedRecord() throws Exception {
        initiated();long revision=service.status(customer.getId()).revision();String originalHash=original.payloadHash();var r=withdraw(UUID.randomUUID());approveWithdrawal(r);
        assertEquals(0,active());assertEquals("EXIT_RESCINDED",reporting.customerStatus(customer.getId()).state());assertEquals("APPROVED",reporting.list(maker.user().getId(),maker.sid(),0).getFirst().status());assertEquals(originalHash,reporting.list(maker.user().getId(),maker.sid(),0).getFirst().payloadHash());assertEquals(revision,service.status(customer.getId()).revision());blocked();
    }
    @Test void actualCddClearanceThenIndependentWithdrawalPermitsOrders() throws Exception {
        initiated();clearedAfterExit();var r=withdraw(UUID.randomUUID());assertEquals("STANDARD",r.payload().current().outcome());assertEquals("CLOSED",r.payload().current().state());approveWithdrawal(r);allowed();
    }
    @Test void generalTradingRestrictionStillAppliesAfterWithdrawal() throws Exception {
        initiated();clearedAfterExit();var u=users.findById(customer.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);approveWithdrawal(withdraw(UUID.randomUUID()));assertEquals(0,active());blocked();assertTrue(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void rejectedOrUnapprovedExitCannotBeWithdrawn() throws Exception {
        original=prepared();assertThrows(ResponseStatusException.class,()->withdraw(UUID.randomUUID()));reportDecide(original,false,reportProof(checker,original,false));assertThrows(ResponseStatusException.class,()->withdraw(UUID.randomUUID()));assertEquals("NO_EXIT_DECISION",reporting.customerStatus(customer.getId()).state());
    }
    @Test void currentExitParticipantsCannotReviewTheirOwnOriginalDecision() throws Exception {
        initiated();assertThrows(ResponseStatusException.class,()->rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),checker.user().getId(),UUID.randomUUID(),withdrawalFacts()));var alias=account(UserRole.APPROVER);var u=users.findById(alias.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),alias.user().getId(),UUID.randomUUID(),withdrawalFacts()));
    }
    @Test void outsiderCannotSelfAssignCorrectionToReadPrivateOriginalReason() throws Exception {
        initiated();var outsider=account(UserRole.OPERATOR);assertThrows(ResponseStatusException.class,()->rescissions.propose(outsider.user().getId(),outsider.sid(),original.payload().id(),independent.user().getId(),UUID.randomUUID(),withdrawalFacts()));
    }
    @Test void designatedThirdPersonReadsOriginalOnlyThroughAuditedCorrectionPurpose() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertThrows(ResponseStatusException.class,()->reporting.inspect(independent.user().getId(),independent.sid(),original.payload().id(),0));var view=rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,0);assertEquals(facts(),view.originalFacts());assertEquals(withdrawalFacts(),view.facts());
        var outsider=account(UserRole.APPROVER);assertThrows(ResponseStatusException.class,()->rescissions.inspect(outsider.user().getId(),outsider.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0));
    }
    @Test void noPersonalViewsMeansNoOptionsAndMakerViewsCannotSubstitute() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));withdrawalViews(maker,r);assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));
    }
    @Test void identicalContentUsesOneSetOfPagesDespiteDifferentSnapshotIds() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertNotEquals(r.payload().originalDossier().id(),r.payload().current().dossier().id());assertEquals(r.payload().originalDossier().digest(),r.payload().current().dossier().digest());
        for(int page=0;page<r.payload().current().dossier().pageCount();page++)rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,page);assertNotNull(rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));
    }
    @Test void differentOriginalAndCurrentContentRequireBothCompleteSets() throws Exception {
        initiated();evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());var r=withdraw(UUID.randomUUID());assertNotEquals(r.payload().originalDossier().digest(),r.payload().current().dossier().digest());
        rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0);assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,0);assertNotNull(rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));
    }
    @Test void newEvidenceAfterSignatureInvalidatesWithdrawalWithoutConsumingProof() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());assertThrows(ResponseStatusException.class,()->withdrawDecide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));assertEquals(1,active());
    }
    @Test void newDeclarationBlocksStaleDecisionButHistoryRemainsReadable() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->withdrawDecide(r,true,p));assertEquals(withdrawalFacts(),rescissions.inspect(maker.user().getId(),maker.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0).facts());assertThrows(ResponseStatusException.class,()->withdraw(UUID.randomUUID()));
    }
    @Test void customerVersionChangeInvalidatesAlreadySignedWithdrawal() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);var u=users.findById(customer.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->withdrawDecide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
    }
    @Test void makerOrCheckerVersionChangeInvalidatesRequest() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);var u=users.findById(maker.user().getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->withdrawDecide(r,true,p));
    }
    @Test void checkerVersionAndOldSessionViewsCannotBeReused() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());withdrawalViews(independent,r);var u=users.findById(independent.user().getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));
    }
    @Test void sameFactsAndKeyReplayButChangedReasonOrCheckerConflicts() throws Exception {
        initiated();UUID key=UUID.randomUUID();var r=withdraw(key);assertEquals(r.payload().id(),withdraw(key).payload().id());assertThrows(ResponseStatusException.class,()->rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),independent.user().getId(),key,new ExitRescissionService.Facts(true,"바뀐 사유")));var other=account(UserRole.APPROVER);assertThrows(ResponseStatusException.class,()->rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),other.user().getId(),key,withdrawalFacts()));
    }
    @Test void concurrentSameKeyProducesOneRequest() throws Exception {
        initiated();UUID key=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var fs=new ArrayList<Future<UUID>>();for(int i=0;i<2;i++)fs.add(pool.submit(()->{gate.await();return withdraw(key).payload().id();}));gate.countDown();assertEquals(fs.get(0).get(12,TimeUnit.SECONDS),fs.get(1).get(12,TimeUnit.SECONDS));}
    }
    @Test void rejectionPreservesHoldAndAllowsNewCorrectionRequest() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertThrows(ResponseStatusException.class,()->withdraw(UUID.randomUUID()));withdrawDecide(r,false,withdrawalProof(independent,r,false));assertEquals(1,active());assertNotEquals(r.payload().id(),withdraw(UUID.randomUUID()).payload().id());
    }
    @Test void concurrentApprovalAndRejectionCommitExactlyOneDecision() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var yes=withdrawalProof(independent,r,true);var no=withdrawalProof(independent,r,false);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var fs=new ArrayList<Future<Boolean>>();for(boolean approve:new boolean[]{true,false})fs.add(pool.submit(()->{gate.await();try{withdrawDecide(r,approve,approve?yes:no);return true;}catch(ResponseStatusException e){return false;}}));gate.countDown();int ok=0;for(var f:fs)if(f.get(12,TimeUnit.SECONDS))ok++;assertEquals(1,ok);}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescission_decisions WHERE request_id=?",Long.class,r.payload().id()));
    }
    @Test void oppositeDecisionCannotUseApprovalProof() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);assertThrows(ResponseStatusException.class,()->withdrawDecide(r,false,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
    }
    @Test void anotherBusinessKindCannotAuthorizeWithdrawal() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());withdrawalViews(independent,r);UUID other=UUID.randomUUID();new TransactionTemplate(manager).executeWithoutResult(t->mfa.bindApproval(OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,other,maker.user().getId(),r.payload().requesterVersion(),r.payloadHash(),r.payload().expiresAt()));var o=new TransactionTemplate(manager).execute(t->mfa.approvalOptions(independent.user().getId(),independent.sid(),OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,other,r.payloadHash(),true));var p=new OperatorMfaService.AuthorizationProof(o.challengeId(),independent.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));assertThrows(ResponseStatusException.class,()->withdrawDecide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
    }
    @Test void auditFailureRollsBackProposalSnapshotCipherAndBinding() throws Exception {
        initiated();long before=jdbc.queryForObject("SELECT count(*) FROM edd_case_dossiers WHERE user_id=?",Long.class,customer.getId());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EXIT_RESCISSION_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->withdraw(UUID.randomUUID()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescissions WHERE exit_id=?",Long.class,original.payload().id()));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossiers WHERE user_id=?",Long.class,customer.getId()));
    }
    @Test void auditFailureReturnsNoReasonAndRollsBackViews() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EXIT_RESCISSION_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescission_views WHERE request_id=?",Long.class,r.payload().id()));
    }
    @Test void decisionAuditFailurePreservesHoldAndProofThenSameProofRetries() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=withdrawalProof(independent,r,true);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EXIT_RESCISSION_DECIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->withdrawDecide(r,true,p));assertEquals(1,active());assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));reset(audit);withdrawDecide(r,true,p);assertEquals(0,active());
    }
    @Test void newExitRequiresNewReferralAndOldWithdrawalReplayCannotClearIt() throws Exception {
        initiated();UUID key=UUID.randomUUID();var r=withdraw(key);approveWithdrawal(r);assertThrows(ResponseStatusException.class,()->request(UUID.randomUUID(),facts()));
        referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));var next=request(UUID.randomUUID(),facts());reportDecide(next,true,reportProof(checker,next,true));assertEquals(1,active());assertEquals("APPROVED",withdraw(key).status());assertEquals("EXIT_PENDING",reporting.customerStatus(customer.getId()).state());assertThrows(ResponseStatusException.class,()->withdraw(UUID.randomUUID()));
    }
    @Test void allNewRecordsRejectUpdateDeleteAndTruncate() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());approveWithdrawal(r);for(String table:List.of("relationship_exit_rescissions","relationship_exit_rescission_views","relationship_exit_rescission_decisions")){String column=table.endsWith("views")?"digest":table.endsWith("decisions")?"approved":"payload";for(String sql:List.of("UPDATE "+table+" SET "+column+"="+column,"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));}assertEquals(0,active());
    }
    @Test void reasonIsAbsentFromMetadataCipherAndAudit() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());String payload=jdbc.queryForObject("SELECT payload FROM relationship_exit_rescissions WHERE id=?",String.class,r.payload().id());String cipher=new String(jdbc.queryForObject("SELECT reason_cipher FROM relationship_exit_rescissions WHERE id=?",byte[].class,r.payload().id()),StandardCharsets.UTF_8);String audits=jdbc.queryForList("SELECT action,detail FROM security_audit WHERE target=?","exit-rescission:"+r.payload().id()).toString();for(String text:List.of(payload,cipher,audits,json.writeValueAsString(rescissions.list(maker.user().getId(),maker.sid(),0))))assertFalse(text.contains(withdrawalFacts().rationale()));
    }
    @Test void staleMfaInvalidSessionAndBadPageDoNotProvideOriginal() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertThrows(ResponseStatusException.class,()->rescissions.inspect(independent.user().getId(),maker.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,0));assertThrows(ResponseStatusException.class,()->rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,-1));var token=tokens.findBySessionId(independent.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);assertThrows(ResponseStatusException.class,()->rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,0));
    }
    @Test void decisionOutcomeCanBeReadAfterResponseLossWithoutReapplyingIt() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());approveWithdrawal(r);assertEquals("APPROVED",rescissions.status(maker.user().getId(),maker.sid(),r.payload().id()).status());assertEquals("APPROVED",rescissions.status(independent.user().getId(),independent.sid(),r.payload().id()).status());assertEquals(0,active());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescission_decisions WHERE request_id=?",Long.class,r.payload().id()));
    }
    @Test void originalReviewerWhoIsNotDesignatedCannotReadWithdrawalReason() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());assertTrue(rescissions.list(checker.user().getId(),checker.sid(),0).isEmpty());assertThrows(ResponseStatusException.class,()->rescissions.status(checker.user().getId(),checker.sid(),r.payload().id()));assertThrows(ResponseStatusException.class,()->rescissions.inspect(checker.user().getId(),checker.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0));
    }
    @Test void oldElevenKindSchemaAndLegacyPermanentIndexAreRejected() throws Exception {
        initiated();String sql=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(StandardCharsets.UTF_8);assertThrows(DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW','EDD_ADDRESS_REVIEW','EDD_FUNDS_REVIEW','EDD_WEALTH_REVIEW','STR_REVIEW','STR_CUSTODY','RELATIONSHIP_EXIT')) NOT VALID");jdbc.execute(sql);}));
        String exitSchema=new org.springframework.core.io.ClassPathResource("edd/relationship-exit-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        // 이름이 남은 옛 스키마 표본이다. 여러 고객의 불변 이력을 지워 과거 유일 인덱스를 합성하지 않는다.
        assertThrows(DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("CREATE INDEX relationship_exit_one_approved ON relationship_exit_decisions(user_id)");jdbc.execute(exitSchema);}));assertEquals(1,active());
    }
    @Test void actualHttpIndependentWithdrawalAndCustomerSafeStatus() throws Exception {
        initiated();String url="/api/admin/compliance/exit-rescissions";String body=json.writeValueAsString(new ExitRescissionController.Proposal(original.payload().id(),independent.user().getId(),withdrawalFacts()));
        var r=json.readValue(mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),ExitRescissionService.Request.class);
        for(int page=0;page<r.payload().current().dossier().pageCount();page++)mvc.perform(post(url+"/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+independent.access()).param("basis","CURRENT").param("page",Integer.toString(page))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        var o=json.readValue(mvc.perform(post(url+"/"+r.payload().id()+"/approve/options").header("Authorization","Bearer "+independent.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ExitRescissionController.Confirmation(r.payloadHash())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),ExitRescissionService.Confirmation.class).authentication();var p=new OperatorMfaService.AuthorizationProof(o.challengeId(),independent.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
        mvc.perform(post(url+"/"+r.payload().id()+"/approve").header("Authorization","Bearer "+independent.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ExitRescissionController.Decision(r.payloadHash(),p)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));mvc.perform(get("/api/customer/relationship").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EXIT_RESCINDED"));blocked();
    }
    @Test void customerAndAuditorCannotUseCorrectionRoutes() throws Exception {
        initiated();var auditor=account(UserRole.AUDITOR);for(String token:List.of(customerAccess,auditor.access()))mvc.perform(get("/api/admin/compliance/exit-rescissions").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void databaseGuardSerializesDirectConcurrentApprovals() throws Exception {
        original=prepared();var first=original.payload();var raw=(tools.jackson.databind.node.ObjectNode)json.valueToTree(first);UUID copy=UUID.randomUUID();raw.put("id",copy.toString());var second=json.readValue(json.writeValueAsString(raw),RelationshipExitService.Payload.class);String hash=verifier.digest(raw);
        byte[] cipher=reportingCrypto.encrypt("RELATIONSHIP_EXIT:"+copy+":"+second.userId()+":"+second.caseId()+":"+second.referralId()+":"+second.factsMac(),json.writeValueAsBytes(facts()));
        // 데이터베이스 제약의 직접 삽입 경합 표본이다. 실제 업무 서명을 승인한 결과로 간주하지 않는다.
        jdbc.update("INSERT INTO relationship_exit_requests(id,case_id,user_id,referral_id,requester_id,checker_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",copy,second.caseId(),second.userId(),second.referralId(),second.requesterId(),second.checkerId(),UUID.randomUUID(),json.writeValueAsString(raw),hash,cipher,java.sql.Timestamp.from(second.expiresAt()));
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var fs=new ArrayList<Future<Boolean>>();for(UUID id:List.of(first.id(),copy))fs.add(pool.submit(()->{gate.await();try{new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO relationship_exit_decisions(review_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,true)",id,customer.getId(),checker.user().getId(),checker.user().getOperatorPersonId()));return true;}catch(DataAccessException e){return false;}}));gate.countDown();int ok=0;for(var f:fs)if(f.get(12,TimeUnit.SECONDS))ok++;assertEquals(1,ok);}assertEquals(1,active());
    }
    @Test void rescissionCannotBeDuplicatedByDirectDatabaseInsertion() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());approveWithdrawal(r);
        // 이미 승인된 원래 요청과 같은 표본 행으로 다시 결정을 넣어도 단일 철회 제약을 넘지 못한다.
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO relationship_exit_rescission_decisions(request_id,exit_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?,true)",r.payload().id(),original.payload().id(),customer.getId(),independent.user().getId(),independent.user().getOperatorPersonId()));assertEquals(0,active());
    }
    @Test void withdrawalPreservesMoneyAndReopensOnlyFutureAdmissionAfterAllChecks() throws Exception {
        verify(false);allowed();if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();
        balances.saveAndFlush(dustin.cex.domains.balance.model.entity.UserBalance.builder().userId(customer.getId()).mintAddress("USDT").available(new java.math.BigDecimal("1000")).locked(java.math.BigDecimal.ZERO).build());
        new TransactionTemplate(manager).executeWithoutResult(t->ledger.record("test-rescission-seed:"+UUID.randomUUID(),"TEST_FIXTURE",List.of(dustin.cex.domains.ledger.LedgerService.p(customer.getId(),"AVAILABLE","USDT",new java.math.BigDecimal("1000")),dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","USDT",new java.math.BigDecimal("-1000")))));
        when(feePolicies.getFeeConfig("BTC","USDT")).thenReturn(dustin.cex.domains.fee.model.entity.FeeConfig.builder().feeRate(java.math.BigDecimal.ZERO).feeType("both").isActive(true).build());when(engine.submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(true);
        var command=dustin.cex.domains.order.model.dto.CreateOrderRequest.builder().baseMint("BTC").quoteMint("USDT").orderType("buy").orderSide("limit").price(new java.math.BigDecimal("10000")).amount(new java.math.BigDecimal("0.001")).build();UUID key=UUID.randomUUID();var old=orders.createOrder(customer.getId(),key,command);assertTrue(old.created());
        cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));initiated();clearedAfterExit();var before=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId());
        var r=withdraw(UUID.randomUUID());approveWithdrawal(r);var after=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();assertEquals(before.getAvailable(),after.getAvailable());assertEquals(before.getLocked(),after.getLocked());assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId()));assertFalse(orders.createOrder(customer.getId(),key,command).created());
        mvc.perform(post("/api/cex/orders").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command))).andExpect(status().isCreated());
        org.mockito.Mockito.verify(engine,times(2)).submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any());
    }

    @Autowired ReportingReviewService reports;@Autowired ReportingFollowupService followups;
    @Test void withdrawalNeverRemovesReportingDutyOrGrantsReportingPlaintextAccess() throws Exception {
        initiated();var report=reports.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),UUID.randomUUID(),new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,"철회와 별개로 남아야 하는 가상 보고 검토 근거"));for(int page=0;page<report.payload().dossier().pageCount();page++)reports.inspect(checker.user().getId(),checker.sid(),report.payload().id(),page);
        var o=reports.options(checker.user().getId(),checker.sid(),report.payload().id(),report.payloadHash(),true).authentication();reports.decide(checker.user().getId(),checker.sid(),report.payload().id(),report.payloadHash(),true,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
        approveWithdrawal(withdraw(UUID.randomUUID()));assertTrue(followups.queue(maker.user().getId(),maker.sid(),null).items().stream().anyMatch(i->i.reviewId().equals(report.payload().id())));assertThrows(ResponseStatusException.class,()->reports.inspect(independent.user().getId(),independent.sid(),report.payload().id(),0));blocked();
    }
    @Test void receiptFromDifferentSessionCannotAuthorizeWithdrawal() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());withdrawalViews(independent,r);var old=tokens.findBySessionId(independent.sid()).orElseThrow();var session=tokens.saveAndFlush(RefreshToken.builder().userId(independent.user().getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).mfaVerifiedAt(old.getMfaVerifiedAt()).mfaCredentialFingerprint(old.getMfaCredentialFingerprint()).build());assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),session.getSessionId(),r.payload().id(),r.payloadHash(),true));
    }
    ExitRescissionService.Request historicalCopy(ExitRescissionService.Request originalRequest,boolean damaged) {
        // 만료와 다른 문맥의 암호문을 별도 표본으로 추가한다. 원래 요청·결정·감사는 갱신하지 않는다.
        var raw=(tools.jackson.databind.node.ObjectNode)json.valueToTree(originalRequest.payload());UUID id=UUID.randomUUID();raw.put("id",id.toString()).put("expiresAt",Instant.now().minusSeconds(1).toString());var p=json.readValue(json.writeValueAsString(raw),ExitRescissionService.Payload.class);String hash=verifier.digest(raw);byte[] cipher=damaged?jdbc.queryForObject("SELECT reason_cipher FROM relationship_exit_rescissions WHERE id=?",byte[].class,originalRequest.payload().id()):reportingCrypto.encrypt("EXIT_RESCISSION:"+id+":"+p.exitId()+":"+p.exitHash()+":"+p.userId()+":"+p.factsMac(),json.writeValueAsBytes(withdrawalFacts()));
        jdbc.update("INSERT INTO relationship_exit_rescissions(id,exit_id,user_id,requester_id,checker_id,request_key,payload,payload_hash,reason_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,p.exitId(),p.userId(),p.requesterId(),p.checkerId(),UUID.randomUUID(),json.writeValueAsString(raw),hash,cipher,java.sql.Timestamp.from(p.expiresAt()));return new ExitRescissionService.Request(p,hash,"EXPIRED");
    }
    @Test void expiredRequestIsReadableWithoutRevivingItsDecisionAuthority() throws Exception {
        initiated();var r=historicalCopy(withdraw(UUID.randomUUID()),false);assertEquals(withdrawalFacts(),rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0).facts());assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));assertEquals(1,active());
    }
    @Test void copiedCipherCannotReturnReasonOrCreateViews() throws Exception {
        initiated();var r=historicalCopy(withdraw(UUID.randomUUID()),true);var error=assertThrows(IllegalStateException.class,()->rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,0));assertNull(error.getCause());assertFalse(error.toString().contains(withdrawalFacts().rationale()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescission_views WHERE request_id=?",Long.class,r.payload().id()));
    }
    @Test void oldViewAloneCannotAuthorizeNewOptions() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());var p=r.payload();var summary=p.current().dossier();jdbc.update("INSERT INTO relationship_exit_rescission_views(id,request_id,dossier_id,actor_id,actor_person,actor_version,session_id,page,digest,payload_hash,viewed_at) VALUES(?,?,?,?,?,?,?,?,?,?,clock_timestamp()-interval '6 minutes')",UUID.randomUUID(),p.id(),summary.id(),independent.user().getId(),p.checkerPerson(),p.checkerVersion(),independent.sid(),0,summary.digest(),r.payloadHash());assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),p.id(),r.payloadHash(),true));
    }
    @Test void bothDifferentBundlesRequireLastPartialPage() throws Exception {
        reviewed();var c=service.status(customer.getId());
        // 페이지 경계용 과거 자료 요청만 추가한다. 미검증 자료를 진위 확인이나 금융 확정으로 만들지 않는다.
        for(int i=0;i<28;i++)jdbc.update("INSERT INTO edd_evidence_requests(id,case_id,user_id,assessment_id,purpose,requester_id,requester_person,request_key,expires_at,created_at) VALUES(?,?,?,?,'SOURCE_OF_FUNDS',?,?,?,clock_timestamp()-interval '2 days',clock_timestamp()-interval '3 days')",UUID.randomUUID(),c.id(),customer.getId(),c.latestAssessment(),maker.user().getId(),maker.user().getOperatorPersonId(),UUID.randomUUID());
        referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));original=request(UUID.randomUUID(),facts());reportDecide(original,true,reportProof(checker,original,true));
        evidence.request(maker.user().getId(),maker.sid(),customer.getId(),c.latestAssessment(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());var r=withdraw(UUID.randomUUID());assertEquals(2,r.payload().originalDossier().pageCount());assertEquals(2,r.payload().current().dossier().pageCount());
        for(int page=0;page<2;page++)rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,page);rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,0);assertThrows(ResponseStatusException.class,()->rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));assertEquals(3,rescissions.inspect(independent.user().getId(),independent.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,1).dossier().items().size());assertNotNull(rescissions.options(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),true));
    }

    @Test void unsupportedIsolationCannotApplyExitOrRescissionEffects() throws Exception {
        original=prepared();var isolated=new TransactionTemplate(manager);isolated.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(DataAccessException.class,()->isolated.executeWithoutResult(t->jdbc.update("INSERT INTO relationship_exit_decisions(review_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,true)",original.payload().id(),customer.getId(),checker.user().getId(),checker.user().getOperatorPersonId())));assertEquals(0,active());
        reportDecide(original,true,reportProof(checker,original,true));var r=withdraw(UUID.randomUUID());
        assertThrows(DataAccessException.class,()->isolated.executeWithoutResult(t->jdbc.update("INSERT INTO relationship_exit_rescission_decisions(request_id,exit_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?,true)",r.payload().id(),original.payload().id(),customer.getId(),independent.user().getId(),independent.user().getOperatorPersonId())));assertEquals(1,active());
    }

}
