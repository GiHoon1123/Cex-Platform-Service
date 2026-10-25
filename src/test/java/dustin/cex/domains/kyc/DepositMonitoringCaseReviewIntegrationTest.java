package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.security.*;
import dustin.cex.domains.valuation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 새 PG에서 실제 증빙 열람·내용 결합 보안키 서명·불변 요청/결정을 검증한다. 판단을 금융 허가로 적용하지 않는다. */
class DepositMonitoringCaseReviewIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-case-review-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);r.add("valuation.upbit.enabled",()->true);}
    @Autowired DepositMonitoringCaseReviews reviews;
    @Autowired DepositMonitoringCases cases;
    @Autowired DepositMonitoringService monitoring;
    @Autowired DepositMonitoringInvestigations investigations;
    @Autowired CaseReviewEvidence evidence;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired EvidenceCrypto evidenceCrypto;
    @Autowired CddCrypto declarationCrypto;
    @MockitoBean BitcoinKrwPriceClient price;
    @MockitoBean BitcoinDepositReader reader;
    @MockitoBean(name="monitoringReviewClock") Clock reviewClock;
    final AtomicReference<Instant> time=new AtomicReference<>();
    UUID caseId,assessment;BitcoinDepositFixtures.Seed receipt;
    @BeforeEach void input(){
        time.set(Instant.now());when(reviewClock.instant()).thenAnswer(call->time.get());ledger.initializeOpening();
        when(price.read()).thenAnswer(call->{Instant now=Instant.ofEpochMilli(Instant.now().toEpochMilli());String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",1700000,"trade_timestamp",now.toEpochMilli(),"timestamp",now.toEpochMilli())));return new BitcoinKrwPriceClient.Quote(new BigDecimal("1700000"),now,now,now,now,payload,sha(payload));});assertEquals("SUCCESS",prices.refresh().status());clearInvocations(price);
        for(int i=0;i<5;i++)add();caseId=jdbc.queryForObject("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=?",UUID.class,customer.getId());note(assessment,receipt.id(),"분할 송금의 소명 자료 검토");
    }
    void add(){receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);pending.recognize(receipt.id());assessment=monitoring.processNext().orElseThrow().id();}
    void note(UUID id,UUID deposit,String text){var view=investigations.inspect(maker.user().getId(),maker.sid(),deposit,id);investigations.record(maker.user().getId(),maker.sid(),deposit,id,new DepositMonitoringInvestigations.Write(view.viewId(),UUID.randomUUID(),DepositMonitoringInvestigations.Finding.EXPLAINED_ACTIVITY,text));}
    CaseReviewEvidence.Position scope(){return reviews.context(maker.user().getId(),maker.sid(),caseId,null);}
    DepositMonitoringCaseReviews.Facts facts(){return new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"확인한 거래 구간과 송금 자료의 설명을 검토했다");}
    void viewEvidence(Account actor,CaseReviewEvidence.Position scope){
        Long before=Long.parseLong(scope.throughCursor())+1;
        do{var page=cases.members(actor.user().getId(),actor.sid(),caseId,before);for(var member:page.items()){
            investigations.inspect(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId());
            for(var note:investigations.observations(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),0))investigations.inspectObservation(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),note.id());
        }before=page.nextBefore()==null?null:Long.parseLong(page.nextBefore());}while(before!=null);
    }
    DepositMonitoringCaseReviews.Review propose(){var scope=scope();viewEvidence(maker,scope);return reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,facts());}
    OperatorMfaService.AuthorizationProof proof(DepositMonitoringCaseReviews.Review r,boolean approve)throws Exception{
        viewEvidence(checker,r.payload().scope());reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id());var options=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    DepositMonitoringCaseReviews.Review decide(DepositMonitoringCaseReviews.Review r,boolean approve,OperatorMfaService.AuthorizationProof proof){return reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void rejects(int status,Runnable work){assertEquals(status,assertThrows(ResponseStatusException.class,work::run).getStatusCode().value());}
    void cause(String text,Runnable work){Throwable failed=assertThrows(RuntimeException.class,work::run);while(failed.getCause()!=null)failed=failed.getCause();assertTrue(failed.getMessage().contains(text),failed.toString());}
    DepositMonitoringCaseReviews service(JdbcTemplate database,EvidenceCrypto crypto){return new DepositMonitoringCaseReviews(database,users,operatorSessions(),evidence,crypto,declarationCrypto,json,mfa,audit,reviewClock,manager);}
    @Autowired OperatorSessionPolicy sessions;
    OperatorSessionPolicy operatorSessions(){return sessions;}
    @AfterEach void noFinancialEffects(){verifyNoInteractions(engine,producer,reader,price);assertEquals(0,count("user_balances"));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",BigDecimal.class).signum());assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());assertEquals("OPEN",cases.list(maker.user().getId(),maker.sid(),0).getFirst().status());}
    @Test void assignedPeopleCompleteAnActualContentBoundDecision()throws Exception{
        var scope=scope();viewEvidence(maker,scope);String body=json.writeValueAsString(new DepositMonitoringCaseReviewController.Proposal(caseId,checker.user().getId(),scope,facts()));
        var response=mvc.perform(post("/api/admin/monitoring/case-reviews").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.payload.assurance").value(DepositMonitoringCaseReviews.ASSURANCE)).andExpect(jsonPath("$.facts").doesNotExist()).andReturn();
        var r=json.readValue(response.getResponse().getContentAsString(),DepositMonitoringCaseReviews.Review.class);var proof=proof(r,true);
        mvc.perform(post("/api/admin/monitoring/case-reviews/"+r.payload().id()+"/approve").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(new DepositMonitoringCaseReviewController.Decision(r.payloadHash(),proof)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(1,count("deposit_monitoring_case_review_decisions"));assertFalse(reviews.inspect(maker.user().getId(),maker.sid(),r.payload().id()).toString().contains(facts().rationale()));
    }
    @Test void rejectionKeepsHistoryAndAllowsANewRequest()throws Exception{var r=propose();assertEquals("REJECTED",decide(r,false,proof(r,false)).status());var next=propose();assertNotEquals(r.payload().id(),next.payload().id());assertEquals(2,count("deposit_monitoring_case_reviews"));}
    @Test void proposalNeedsTheMakersActualSourceAndNoteViews(){var scope=scope();rejects(409,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,facts()));viewEvidence(maker,scope);assertEquals("PENDING",reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,facts()).status());}
    @Test void approvalRequiresEvidenceAndTheExactPrivateReason(){var r=propose();rejects(409,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));viewEvidence(checker,r.payload().scope());rejects(409,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id());assertNotNull(reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void laterSignalIsNotSilentlyIncludedInTheSignedScope()throws Exception{var r=propose();String through=r.payload().scope().throughCursor();add();assertEquals(2,scope().signals());assertEquals("APPROVED",decide(r,true,proof(r,true)).status());assertEquals(1,r.payload().scope().signals());assertEquals(through,r.payload().scope().throughCursor());assertEquals(2,cases.members(maker.user().getId(),maker.sid(),caseId,null).investigation().signalCount());}
    @Test void newOpinionInsideTheScopeInvalidatesPreparedSignature()throws Exception{var r=propose();var proof=proof(r,true);note(assessment,receipt.id(),"기존 범위에서 새로 확인한 의견");rejects(409,()->decide(r,true,proof));assertEquals(0,count("deposit_monitoring_case_review_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));}
    @Test void newOpinionOutsideTheScopeDoesNotChangeItsFacts()throws Exception{var r=propose();add();note(assessment,receipt.id(),"뒤에 들어온 별도 범위 의견");assertEquals(2,scope().opinions());assertEquals("APPROVED",decide(r,true,proof(r,true)).status());assertEquals(1,r.payload().scope().opinions());}
    @Test void wrongScopeAndAnotherCaseCannotCreateARequest(){var p=scope();viewEvidence(maker,p);var forged=new CaseReviewEvidence.Position(p.caseId(),p.customerId(),p.policy(),p.signals(),p.throughCursor(),p.opinions(),"0".repeat(64));rejects(409,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),forged,facts()));rejects(400,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),new CaseReviewEvidence.Position(UUID.randomUUID(),p.customerId(),p.policy(),p.signals(),p.throughCursor(),p.opinions(),p.version()),facts()));assertEquals(0,count("deposit_monitoring_case_reviews"));}
    @Test void anotherPersonsViewsDoNotAuthorizeTheMaker(){var p=scope();viewEvidence(checker,p);rejects(409,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),p,facts()));}
    @Test void samePersonCannotActAsTwoDifferentAccounts(){var person=users.findById(checker.user().getId()).orElseThrow();person.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(person);var p=scope();viewEvidence(maker,p);rejects(403,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),p,facts()));}
    @Test void sameRequestIsIdempotentButDifferentFactsConflict(){var p=scope();viewEvidence(maker,p);UUID key=UUID.randomUUID();var r=reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),key,p,facts());assertEquals(r.payload().id(),reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),key,p,facts()).payload().id());rejects(409,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),key,p,new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.REQUIRES_FURTHER_REVIEW,"다른 판단 사유")));assertEquals(1,count("deposit_monitoring_case_reviews"));}
    @Test void oneScopeCannotHaveMultiplePendingOrApprovedRequests()throws Exception{var r=propose();rejects(409,this::propose);decide(r,true,proof(r,true));rejects(409,this::propose);assertEquals(1,count("deposit_monitoring_case_reviews"));}
    @Test void exactExpiryBoundaryBlocksOptionsWithoutRewritingTheRequest(){var r=propose();time.set(r.payload().expiresAt());rejects(409,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));assertEquals("EXPIRED",reviews.list(maker.user().getId(),maker.sid(),0).getFirst().status());assertEquals(0,count("deposit_monitoring_case_review_decisions"));}
    @Test void wrongHashCannotGetOrUseAnAuthorization()throws Exception{var r=propose();viewEvidence(checker,r.payload().scope());reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id());rejects(409,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),true));var proof=proof(r,true);rejects(409,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),true,proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));}
    @Test void approvalSignatureCannotAuthorizeRejection()throws Exception{var r=propose();var proof=proof(r,true);rejects(403,()->decide(r,false,proof));assertEquals("APPROVED",decide(r,true,proof).status());}
    @Test void consumedSignatureCannotCreateAnotherDecision()throws Exception{var r=propose();var proof=proof(r,true);decide(r,true,proof);rejects(409,()->decide(r,true,proof));assertEquals(1,count("deposit_monitoring_case_review_decisions"));}
    @Test void decisionAuditFailureRollsBackSignatureAndDecision()throws Exception{var r=propose();var proof=proof(r,true);doThrow(new IllegalStateException("격리 판단 감사 실패")).when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_DECIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->decide(r,true,proof));assertEquals(0,count("deposit_monitoring_case_review_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));doCallRealMethod().when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_DECIDED"),anyString(),anyString(),anyString());assertEquals("APPROVED",decide(r,true,proof).status());}
    @Test void omittedDecisionAuditCannotCommit()throws Exception{var r=propose();var proof=proof(r,true);doNothing().when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_DECIDED"),anyString(),anyString(),anyString());cause("거래 사건 판단 업무에는 해당 인물의 필수 감사가 필요합니다",()->decide(r,true,proof));assertEquals(0,count("deposit_monitoring_case_review_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));}
    @Test void proposalAuditFailureCannotLeaveAnApprovalBinding(){doThrow(new IllegalStateException("격리 제안 감사 실패")).when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::propose);assertEquals(0,count("deposit_monitoring_case_reviews"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_approval_bindings WHERE kind='TM_CASE_REVIEW'",Long.class));}
    @Test void omittedProposalAuditCannotCommit(){doNothing().when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_PROPOSED"),anyString(),anyString(),anyString());cause("거래 사건 판단 업무에는 해당 인물의 필수 감사가 필요합니다",this::propose);assertEquals(0,count("deposit_monitoring_case_reviews"));}
    @Test void privateReasonAuditFailureDoesNotLeaveAView(){var r=propose();doThrow(new IllegalStateException("격리 사유 열람 감사 실패")).when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id()));assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void omittedPrivateReasonAuditCannotCommit(){var r=propose();doNothing().when(audit).record(anyLong(),eq("MONITORING_CASE_REVIEW_VIEWED"),anyString(),anyString(),anyString());cause("거래 사건 판단 업무에는 해당 인물의 필수 감사가 필요합니다",()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id()));assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void unassignedPeopleCannotReadThePrivateFacts()throws Exception{var r=propose();for(var actor:List.of(account(UserRole.OPERATOR),account(UserRole.APPROVER))){rejects(404,()->reviews.inspect(actor.user().getId(),actor.sid(),r.payload().id()));assertTrue(reviews.list(actor.user().getId(),actor.sid(),0).isEmpty());}var auditor=account(UserRole.AUDITOR);mvc.perform(post("/api/admin/monitoring/case-reviews/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+auditor.access())).andExpect(status().isForbidden());mvc.perform(post("/api/admin/monitoring/case-reviews/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void staleMfaAndRevokedPeopleCannotReuseAnAssignedReview(){var r=propose();jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=mfa_verified_at-interval '6 minutes' WHERE session_id=?",checker.sid());rejects(403,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id()));jdbc.update("UPDATE users SET enabled=false WHERE id=?",checker.user().getId());rejects(403,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void changedMakerVersionBlocksTheOldRequest(){var r=propose();var makerUser=users.findById(maker.user().getId()).orElseThrow();makerUser.setOperatorAccessExpiresAt(Instant.now().plusSeconds(3600));users.saveAndFlush(makerUser);rejects(403,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void changedCheckerVersionBlocksTheOldRequest(){var r=propose();var checkerUser=users.findById(checker.user().getId()).orElseThrow();checkerUser.setOperatorAccessExpiresAt(Instant.now().plusSeconds(3600));users.saveAndFlush(checkerUser);rejects(403,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));}
    @Test void aDifferentSessionCannotBorrowPriorViewReceipts()throws Exception{var r=propose();viewEvidence(checker,r.payload().scope());reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id());var login=auth.signin(new dustin.cex.domains.auth.model.dto.SigninRequest(checker.user().getEmail(),"security-test-password"));UUID sid=UUID.fromString(jwt.verifyAccessToken(login.getAccessToken()).get("sid",String.class));var options=mfa.authenticationOptions(checker.user().getId(),sid);var verified=mfa.authenticate(checker.user().getId(),sid,options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));UUID fresh=UUID.fromString(jwt.verifyAccessToken(verified.accessToken()).get("sid",String.class));rejects(409,()->reviews.options(checker.user().getId(),fresh,r.payload().id(),r.payloadHash(),true));}
    @Test void damagedCipherIsRejectedBeforePrivateResponse(){var r=propose();var crypto=spy(evidenceCrypto);doAnswer(call->{byte[] cipher=call.getArgument(2,byte[].class).clone();cipher[cipher.length-1]^=1;return evidenceCrypto.decrypt(call.getArgument(0,String.class),call.getArgument(1,String.class),cipher);}).when(crypto).decrypt(anyString(),anyString(),any(byte[].class));rejects(503,()->service(jdbc,crypto).inspect(checker.user().getId(),checker.sid(),r.payload().id()));assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void newPublicHashCannotAuthenticateATamperedPayload(){var r=propose();var database=spy(jdbc);String sql="SELECT r.payload,r.payload_hash,d.approved FROM deposit_monitoring_case_reviews r LEFT JOIN deposit_monitoring_case_review_decisions d ON d.review_id=r.id WHERE r.id=?";doAnswer(call->{Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);var row=new HashMap<>(jdbc.queryForList(sql,args).getFirst());var node=(tools.jackson.databind.node.ObjectNode)json.readTree((String)row.get("payload"));node.put("expiresAt",r.payload().expiresAt().plusSeconds(1).toString());String raw=json.writeValueAsString(node);row.put("payload",raw);row.put("payload_hash",sha(raw));return List.of(row);}).when(database).queryForList(eq(sql),any(Object[].class));rejects(503,()->service(database,evidenceCrypto).inspect(checker.user().getId(),checker.sid(),r.payload().id()));assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void unsupportedFactKeysCannotFallBack(){var r=propose();var other=new EvidenceCrypto("BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","","","");rejects(503,()->service(jdbc,other).inspect(checker.user().getId(),checker.sid(),r.payload().id()));assertEquals(0,count("deposit_monitoring_case_review_views"));}
    @Test void privateHistoriesCannotBeUpdatedDeletedOrTruncated()throws Exception{var r=propose();decide(r,true,proof(r,true));for(String table:List.of("deposit_monitoring_case_reviews","deposit_monitoring_case_review_views","deposit_monitoring_case_review_decisions")){String column=table.endsWith("decisions")?"review_id":"id";assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE "+table+" SET "+column+"="+column));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));}assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE deposit_monitoring_case_review_views,deposit_monitoring_case_review_decisions,deposit_monitoring_case_reviews"));assertEquals(1,count("deposit_monitoring_case_reviews"));}
    @Test void kindExpansionPreservesExistingImmutableBindings()throws Exception{UUID id=UUID.randomUUID();new TransactionTemplate(manager).executeWithoutResult(t->mfa.bindApproval(OperatorMfaService.ApprovalKind.ROLE,id,maker.user().getId(),maker.user().getVersion(),"c".repeat(64),Instant.now().plusSeconds(600)));var original=jdbc.queryForMap("SELECT * FROM operator_approval_bindings WHERE kind='ROLE' AND request_id=?",id);String allowed=Arrays.stream(OperatorMfaService.ApprovalKind.values()).filter(k->k!=OperatorMfaService.ApprovalKind.TM_CASE_REVIEW).map(k->"'"+k.name()+"'").collect(java.util.stream.Collectors.joining(","));jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ("+allowed+"))");audit.schema();assertEquals(original,jdbc.queryForMap("SELECT * FROM operator_approval_bindings WHERE kind='ROLE' AND request_id=?",id));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE operator_approval_bindings SET payload_hash=? WHERE request_id=?","d".repeat(64),id));var r=propose();assertNotNull(r.payload().id());}
    @Test void aDifferentApprovalKindCannotConsumeTheSignature()throws Exception{var r=propose();var proof=proof(r,true);rejects(403,()->new TransactionTemplate(manager).executeWithoutResult(t->mfa.authorizeApproval(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.TRADING_RESTRICTION,r.payload().id(),r.payloadHash(),true,proof)));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));assertEquals("APPROVED",decide(r,true,proof).status());}
    @Test void unsupportedMethodsAndAmbientTransactionsStayDenied()throws Exception{var r=propose();for(var request:List.of(get("/api/admin/monitoring/case-reviews/"+r.payload().id()+"/inspect"),post("/api/admin/monitoring/case-reviews/"+r.payload().id()+"/inspect/extra")))mvc.perform(request.header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->reviews.list(maker.user().getId(),maker.sid(),0)));}
    @Test void aCaseWithoutAnyOpinionCannotBecomeAReviewFromCountsAlone(){
        var owner=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        for(int i=0;i<5;i++){var r=BitcoinDepositFixtures.seed(jdbc,json,owner.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);pending.recognize(r.id());monitoring.processNext().orElseThrow();}
        UUID id=jdbc.queryForObject("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=?",UUID.class,owner.getId());var p=reviews.context(maker.user().getId(),maker.sid(),id,null);assertEquals(0,p.opinions());
        rejects(409,()->reviews.propose(maker.user().getId(),maker.sid(),id,checker.user().getId(),UUID.randomUUID(),p,facts()));assertEquals(0,count("deposit_monitoring_case_reviews"));
    }

    @Test void malformedFactsAndScopeFieldsAreRejectedBeforeStorage(){
        var p=scope();viewEvidence(maker,p);
        for(var facts:List.of(new DepositMonitoringCaseReviews.Facts(null,"사유"),new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,null),new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY," "),new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"x".repeat(2001)),new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"숨김"+(char)0x202e+"문자")))rejects(400,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),p,facts));
        for(var bad:List.of(new CaseReviewEvidence.Position(p.caseId(),p.customerId(),p.policy(),p.signals(),p.throughCursor(),p.opinions(),null),new CaseReviewEvidence.Position(p.caseId(),p.customerId(),p.policy(),p.signals(),null,p.opinions(),p.version())))rejects(400,()->reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),bad,facts()));
        rejects(400,()->reviews.context(maker.user().getId(),maker.sid(),caseId,0L));assertEquals(0,count("deposit_monitoring_case_reviews"));
    }

    @Test void discardingConsumedAuthorizationDoesNotEraseThePermanentDecision()throws Exception{
        var first=propose();var original=proof(first,true);decide(first,true,original);note(assessment,receipt.id(),"새 자료를 확인한 추가 의견");
        var second=propose();var fresh=proof(second,true);assertNotEquals(original.challengeId(),fresh.challengeId());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_authorization_challenges WHERE id=?",Long.class,original.challengeId()));
        assertEquals(original.challengeId(),jdbc.queryForObject("SELECT authorization_id FROM deposit_monitoring_case_review_decisions WHERE review_id=?",UUID.class,first.payload().id()));
        assertEquals("APPROVED",decide(second,true,fresh).status());assertEquals(2,count("deposit_monitoring_case_review_decisions"));
    }
    @Test void concurrentReuseOfOneSignatureCommitsOnlyOneDecision()throws Exception{
        var r=propose();var proof=proof(r,true);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> call=()->{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));try{return decide(r,true,proof).status();}catch(ResponseStatusException conflict){return Integer.toString(conflict.getStatusCode().value());}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(Set.of("APPROVED","409"),Set.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS)));
        }
        assertEquals(1,count("deposit_monitoring_case_review_decisions"));assertTrue(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));
    }

    @Test void revocationAfterReadingTheRequestIsCheckedUnderTheUserLock(){
        var r=propose();var database=spy(jdbc);var crypto=spy(evidenceCrypto);String sql="SELECT r.payload,r.payload_hash,d.approved FROM deposit_monitoring_case_reviews r LEFT JOIN deposit_monitoring_case_review_decisions d ON d.review_id=r.id WHERE r.id=?";
        doAnswer(call->{Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);var rows=jdbc.queryForList(sql,args);
            var other=new TransactionTemplate(manager);other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(t->{var account=users.lockById(checker.user().getId()).orElseThrow();account.setEnabled(false);users.saveAndFlush(account);});return rows;
        }).when(database).queryForList(eq(sql),any(Object[].class));
        rejects(403,()->service(database,crypto).inspect(checker.user().getId(),checker.sid(),r.payload().id()));org.mockito.Mockito.verify(crypto,never()).decrypt(anyString(),anyString(),any(byte[].class));assertEquals(0,count("deposit_monitoring_case_review_views"));
    }

}
