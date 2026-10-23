package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 합성 발생·수취 자료를 실제 암호화·두 사람의 서명·격리 PostgreSQL로 끝까지 검증한다. 외부 은행 진위 검증은 없다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class FundsReviewIntegrationTest extends EddTestSupport {
    @Autowired FundsReviewService reviews;@Autowired EvidenceService evidence;@Autowired FundsTradeLink trades;
    record Prepared(UUID origin,UUID receipt,FundsReviewService.Views views,FundsReviewService.Context context,FundsFacts facts) {}
    record Approval(FundsReviewService.Views views,OperatorMfaService.AuthorizationProof proof) {}
    static final FundsTradeLink.Target ONBOARDING=new FundsTradeLink.Target(FundsTradeLink.Scope.ONBOARDING,null);
    byte[] png() throws Exception {var b=new BufferedImage(100,50,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,(int)counter.incrementAndGet());var out=new ByteArrayOutputStream();ImageIO.write(b,"PNG",out);return out.toByteArray();}
    UUID file(EvidenceService.Purpose purpose) throws Exception {return file(purpose,png());}
    UUID file(EvidenceService.Purpose purpose,byte[] bytes) {
        var r=evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),purpose,UUID.randomUUID());return evidence.submit(customer.getId(),sid,r.id(),bytes).id();
    }
    UUID view(Account a,UUID id) {return evidence.view(a.user().getId(),a.sid(),id,a.user().getRole()==UserRole.OPERATOR?EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW:EvidenceService.AccessReason.INDEPENDENT_REVIEW).viewId();}
    FundsReviewService.Views views(Account a,UUID origin,UUID receipt) {return new FundsReviewService.Views(view(a,origin),view(a,receipt));}
    FundsFacts facts(String name,CustomerDeclaration.SourceOfFunds source) {
        var d=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        return new FundsFacts(new FundsFacts.Origin(source,name,"가상 고용주","가상 발생 기관",d.minusDays(30),d.minusDays(2),d,new BigDecimal("1000"),"USDT"),new FundsFacts.Receipt(name,"가상 고용주","가상 수취 기관","REFERENCE-PRIVATE-1",d,d,new BigDecimal("1000"),"USDT")).validated();
    }
    Prepared prepared() throws Exception {reviewed();return pair(ONBOARDING);}
    Prepared pair(FundsTradeLink.Target target) throws Exception {
        UUID origin=file(EvidenceService.Purpose.SOURCE_OF_FUNDS),receipt=file(EvidenceService.Purpose.SOURCE_OF_FUNDS);var v=views(maker,origin,receipt);var c=reviews.context(maker.user().getId(),maker.sid(),origin,receipt,v,target);
        return new Prepared(origin,receipt,v,c,facts(c.declaredName(),c.declaredSource()));
    }
    FundsReviewService.Review propose(Prepared p,UUID key,FundsFacts f) {return reviews.propose(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),p.views(),p.context().assessmentId(),p.context().caseRevision(),key,f,p.context().target());}
    FundsReviewService.Review propose(Prepared p) {return propose(p,UUID.randomUUID(),p.facts());}
    Approval authorization(FundsReviewService.Review r,boolean approve,FundsFacts observed) throws Exception {
        var v=views(checker,r.payload().originEvidence(),r.payload().receiptEvidence());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),v,approve,observed).authentication();
        return new Approval(v,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
    }
    FundsReviewService.Review decide(FundsReviewService.Review r,Approval a,boolean approve,FundsFacts observed) {return reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),a.views(),approve,observed,a.proof());}
    @Test void minimalContextExcludesAddressBirthdayAndWealth() throws Exception {
        var p=prepared();String text=json.writeValueAsString(p.context());assertFalse(text.contains("declaredAddress"));assertFalse(text.contains("dateOfBirth"));assertFalse(text.contains("wealth"));assertEquals(FundsReviewService.ASSURANCE,p.context().assurance());assertNull(p.context().trade());assertFalse(p.context().toString().contains(p.context().declaredName()));
    }
    @Test void independentSignatureConfirmsContentButNeverReleasesTrading() throws Exception {
        var p=prepared();var r=propose(p);assertTrue(r.payload().reasons().isEmpty());assertEquals("APPROVED",decide(r,authorization(r,true,p.facts()),true,p.facts()).status());blocked();assertEquals("OPEN",service.status(customer.getId()).state());assertTrue(evidence.customerRequests(customer.getId(),sid).stream().allMatch(x->x.status().equals("RECEIVED_UNVERIFIED")));
    }
    @Test void mismatchReasonsSurviveApproval() throws Exception {
        var p=prepared();var f=facts("다른 사람",CustomerDeclaration.SourceOfFunds.OTHER);var r=propose(p,UUID.randomUUID(),f);assertTrue(r.payload().reasons().contains(FundsFacts.Reason.DECLARED_SOURCE_DIFFERENCE));assertEquals(r.payload().reasons(),decide(r,authorization(r,true,f),true,f).payload().reasons());blocked();
    }
    @Test void factsReferenceAndNamesAreEncryptedAndAuditedInspectionOnly() throws Exception {
        var p=prepared();var r=propose(p);String payload=jdbc.queryForObject("SELECT payload FROM edd_funds_reviews WHERE id=?",String.class,r.payload().id());assertFalse(payload.contains("가상"));assertFalse(payload.contains("REFERENCE-PRIVATE"));
        assertFalse(Arrays.equals(json.writeValueAsBytes(p.facts()),jdbc.queryForObject("SELECT facts_cipher FROM edd_funds_reviews WHERE id=?",byte[].class,r.payload().id())));
        var inspected=reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),views(checker,p.origin(),p.receipt()));assertEquals(p.facts(),inspected.facts());assertFalse(inspected.toString().contains("REFERENCE-PRIVATE"));
    }
    @Test void singleEvidenceCannotPlayBothRoles() throws Exception {var p=prepared();assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.origin(),p.origin(),p.views(),ONBOARDING));}
    @Test void duplicateOriginalCannotBecomeIndependentSecondDocument() throws Exception {
        reviewed();byte[] b=png();UUID a=file(EvidenceService.Purpose.SOURCE_OF_FUNDS,b),z=file(EvidenceService.Purpose.SOURCE_OF_FUNDS,b);var v=views(maker,a,z);assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),a,z,v,ONBOARDING));
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_OF_WEALTH","RESIDENTIAL_ADDRESS"})
    void differentEvidencePurposeCannotSubstituteForFunds(String purpose) throws Exception {reviewed();UUID a=file(EvidenceService.Purpose.SOURCE_OF_FUNDS),b=file(EvidenceService.Purpose.valueOf(purpose));var v=views(maker,a,b);assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),a,b,v,ONBOARDING));}
    @Test void pairViewsCannotBeSwappedOrBorrowed() throws Exception {var p=prepared();var cv=views(checker,p.origin(),p.receipt());for(var v:List.of(new FundsReviewService.Views(p.views().receipt(),p.views().origin()),cv,new FundsReviewService.Views(p.views().origin(),null)))assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),v,ONBOARDING));}
    @Test void missingIndependentObservationCreatesNoChallenge() throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.origin(),p.receipt());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,null));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_authorization_challenges WHERE user_id=?",Long.class,checker.user().getId()));}
    @Test void changedObservationCannotConsumeSignedChallenge() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,true,facts("다르게 읽은 사람",p.context().declaredSource())));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());}
    @Test void rejectionRequiresNoInventedObservationAndKeepsHold() throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.origin(),p.receipt());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,false,p.facts()));assertEquals("REJECTED",decide(r,authorization(r,false,null),false,null).status());blocked();}
    @Test void requestKeyBindsBothDocumentsViewsTargetAndFacts() throws Exception {
        var p=prepared();UUID key=UUID.randomUUID();var r=propose(p,key,p.facts());assertEquals(r,propose(p,key,p.facts()));assertThrows(ResponseStatusException.class,()->propose(p,key,facts("다른 이름",p.context().declaredSource())));
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),views(maker,p.origin(),p.receipt()),p.context().assessmentId(),p.context().caseRevision(),key,p.facts(),ONBOARDING));
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),p.views(),p.context().assessmentId(),p.context().caseRevision(),key,p.facts(),new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,UUID.randomUUID()+":1:0")));assertThrows(ResponseStatusException.class,()->propose(p));
    }
    @Test void newDeclarationInvalidatesOldPairAndSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));blocked();}
    @Test void newUploadInvalidatesBothOldViewsAndSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());file(EvidenceService.Purpose.RESIDENTIAL_ADDRESS);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void customerKycChangeInvalidatesReview() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE kyc_profiles SET status_digest='changed' WHERE user_id=?",customer.getId());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void requesterVersionChangeInvalidatesPendingReview() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var u=users.findById(maker.user().getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void samePersonDifferentAccountCannotInspect() throws Exception {var p=prepared();var r=propose(p);var u=users.findById(checker.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),p.views()));}
    @Test void staleMfaAndRevokedSessionCannotReadOrDecide() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),p.views(),ONBOARDING));}
    @Test void proposalAuditFailureRollsBackReviewCipherAndBinding() throws Exception {var p=prepared();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_FUNDS_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->propose(p));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_funds_reviews WHERE user_id=?",Long.class,customer.getId()));reset(audit);assertNotNull(propose(p));}
    @ParameterizedTest @ValueSource(strings={"EDD_FUNDS_CONTEXT_READ","EDD_FUNDS_FACTS_READ"})
    void privateReadAuditFailureReturnsNoPersonalData(String action) throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.origin(),p.receipt());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq(action),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->{if(action.endsWith("CONTEXT_READ"))reviews.context(checker.user().getId(),checker.sid(),p.origin(),p.receipt(),cv,ONBOARDING);else reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),cv);});}
    @Test void decisionAuditFailureRollsBackProofCounterRevisionAndConclusion() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
        long before=service.status(customer.getId()).revision();
        // 최종 감사 저장에서 실패시킨다. 이미 검증한 서명도 이 거래와 함께 취소돼야 한다.
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_FUNDS_DECIDED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->decide(r,a,true,p.facts()));
        assertEquals(before,service.status(customer.getId()).revision());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_funds_decisions WHERE review_id=?",Long.class,r.payload().id()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));
        // 같은 실제 서명을 다시 사용해 정상 확정한다. 인증 소비가 남았다면 이 재시도는 실패한다.
        reset(audit);assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
    }
    @Test void directionReplayAndDifferentHashCannotConsumeProof() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,false,null));assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),a.views(),true,p.facts(),a.proof()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void otherNamespaceCannotConsumeFundsSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());for(var kind:List.of(OperatorMfaService.ApprovalKind.EDD_REVIEW,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW))assertThrows(ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->mfa.authorizeApproval(checker.user().getId(),checker.sid(),kind,r.payload().id(),r.payloadHash(),true,a.proof())));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());}
    @Test void caseSignatureCannotAuthorizeFundsOrBeConsumedThere() throws Exception {var p=prepared();var r=propose(p);var old=propose(EddService.Finding.AWAITING_EVIDENCE);var proof=proof(checker,old,true);var cv=views(checker,p.origin(),p.receipt());assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));assertEquals("APPROVED",decide(old,true,proof).status());}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void invalidOriginOrMissingUserVerificationKeepsProofUnused(boolean badOrigin) throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.origin(),p.receipt());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts()).authentication();var bad=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,badOrigin?"https://attacker.invalid":"http://localhost:3003",badOrigin,counter.incrementAndGet(),null));assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),bad));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,o.challengeId()));}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentProposalKeysProduceOnePendingReport(boolean same) throws Exception {var p=prepared();UUID a=UUID.randomUUID(),b=same?a:UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var go=new CountDownLatch(1);var first=pool.submit(()->{go.await();try{return propose(p,a,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});var second=pool.submit(()->{go.await();try{return propose(p,b,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});go.countDown();UUID one=first.get(15,TimeUnit.SECONDS),two=second.get(15,TimeUnit.SECONDS);if(same)assertEquals(one,two);else assertTrue(one==null ^ two==null);}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_funds_reviews WHERE user_id=?",Long.class,customer.getId()));}
    @Test void simultaneousApproveRejectCommitOneDecision() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var other=account(UserRole.APPROVER);var cv=views(other,p.origin(),p.receipt());var o=reviews.options(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),cv,false,null).authentication();var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),other.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));try(var pool=Executors.newFixedThreadPool(2)){var go=new CountDownLatch(1);var one=pool.submit(()->{go.await();try{decide(r,a,true,p.facts());return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});var two=pool.submit(()->{go.await();try{reviews.decide(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),cv,false,null,proof);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});go.countDown();assertNotEquals(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_funds_decisions WHERE review_id=?",Long.class,r.payload().id()));}
    @Test void immutableReportsAndDecisionsRejectMutation() throws Exception {var p=prepared();var r=propose(p);decide(r,authorization(r,true,p.facts()),true,p.facts());for(String table:List.of("edd_funds_reviews","edd_funds_decisions"))for(String sql:List.of("UPDATE "+table+" SET "+(table.endsWith("decisions")?"approved=approved":"id=id"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));}
    @Test void auditorGetsMetadataOnlyAndCustomerHasNoAdminAccess() throws Exception {var p=prepared();propose(p);var auditor=account(UserRole.AUDITOR);String url="/api/admin/edd/customers/"+customer.getId()+"/funds-reviews";mvc.perform(get(url).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(jsonPath("$[0].facts").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post("/api/admin/edd/funds-reviews/context").header("Authorization","Bearer "+auditor.access()).contentType("application/json").content(json.writeValueAsString(new FundsReviewController.ContextRequest(p.origin(),p.receipt(),p.views(),ONBOARDING)))).andExpect(status().isForbidden());mvc.perform(get(url).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());}
    @Test void completeHttpPairReviewUsesOwnViewsAndRealSignature() throws Exception {var p=prepared();var body=new FundsReviewController.Proposal(p.origin(),p.receipt(),p.views(),p.context().assessmentId(),p.context().caseRevision(),p.facts(),ONBOARDING);var response=mvc.perform(post("/api/admin/edd/funds-reviews").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn();var r=json.readValue(response.getResponse().getContentAsString(),FundsReviewService.Review.class);var cv=views(checker,p.origin(),p.receipt());var confirm=new FundsReviewController.Confirmation(r.payloadHash(),cv,p.facts());var options=mvc.perform(post("/api/admin/edd/funds-reviews/"+r.payload().id()+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(confirm))).andExpect(status().isOk()).andReturn();var o=json.readValue(options.getResponse().getContentAsString(),FundsReviewService.Confirmation.class).authentication();var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));mvc.perform(post("/api/admin/edd/funds-reviews/"+r.payload().id()+"/approve").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(new FundsReviewController.Decision(r.payloadHash(),cv,p.facts(),proof)))).andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED")).andExpect(header().string("Cache-Control","no-store"));blocked();}
    @Test void previousSixKindSchemaFailsWithoutChangingHistory() throws Exception {prepared();long before=jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class);String sql=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW','EDD_ADDRESS_REVIEW')) NOT VALID");jdbc.execute(sql);}));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class));assertTrue(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='operator_approval_bindings'::regclass AND conname='operator_approval_bindings_kind_check'",String.class).contains("EDD_FUNDS_REVIEW"));}
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer consumer;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.balance.repository.UserBalanceRepository balances;
    @Autowired dustin.cex.domains.order.repository.OrderRepository orders;
    @Autowired dustin.cex.domains.order.repository.OrderSubmissionRepository submissions;
    @Autowired dustin.cex.domains.order.repository.OrderDispatchRepository dispatches;
    long historicalOrder(long user,String side,String price,String rate) {
        var row=orders.saveAndFlush(dustin.cex.domains.order.model.entity.Order.builder().userId(user).orderType(side).orderSide("limit").baseMint("BTC").quoteMint("USDT").price(new BigDecimal(price)).amount(new BigDecimal("2")).filledAmount(BigDecimal.ZERO).filledQuoteAmount(BigDecimal.ZERO).status("pending").build());
        var command=new dustin.cex.domains.order.model.OrderCommand(side,"limit","BTC","USDT",new BigDecimal(price),new BigDecimal("2"),null);
        submissions.saveAndFlush(new dustin.cex.domains.order.model.entity.OrderSubmission(user,UUID.randomUUID(),row.getId(),command,new BigDecimal(rate)));dispatches.saveAndFlush(new dustin.cex.domains.order.model.entity.OrderDispatch(row));return row.getId();
    }
    void historicalBalance(long user,String asset,String available,String locked) {
        balances.saveAndFlush(dustin.cex.domains.balance.model.entity.UserBalance.builder().userId(user).mintAddress(asset).available(new BigDecimal(available)).locked(new BigDecimal(locked)).build());
    }
    String recordedTrade() {
        // 과거 거래를 재현할 자산은 테스트 전용 균형 분개로 준비한다. 외부 입금 확인으로 표시하지 않는다.
        if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();
        long buyer=customer.getId(),seller=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build()).getId();
        historicalBalance(buyer,"USDT","750","250");historicalBalance(seller,"BTC","8","2");
        new TransactionTemplate(manager).executeWithoutResult(t->ledger.record("test-seed:"+UUID.randomUUID(),"TEST_FIXTURE",List.of(
            dustin.cex.domains.ledger.LedgerService.p(buyer,"AVAILABLE","USDT",new BigDecimal("750")),dustin.cex.domains.ledger.LedgerService.p(buyer,"LOCKED","USDT",new BigDecimal("250")),dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","USDT",new BigDecimal("-1000")),
            dustin.cex.domains.ledger.LedgerService.p(seller,"AVAILABLE","BTC",new BigDecimal("8")),dustin.cex.domains.ledger.LedgerService.p(seller,"LOCKED","BTC",new BigDecimal("2")),dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","BTC",new BigDecimal("-10")))));
        long buy=historicalOrder(buyer,"buy","100","0.001"),sell=historicalOrder(seller,"sell","90","0.002");String engine=UUID.randomUUID().toString(),event=engine+":1:0";
        consumer.consumeOrderEvent(json.writeValueAsString(Map.ofEntries(Map.entry("event_type","trade_executed"),Map.entry("engine_id",engine),Map.entry("event_id",event),Map.entry("buy_order_id",buy),Map.entry("sell_order_id",sell),Map.entry("buyer_id",buyer),Map.entry("seller_id",seller),Map.entry("base_mint","BTC"),Map.entry("quote_mint","USDT"),Map.entry("price","90"),Map.entry("amount","0.5"),Map.entry("buyer_fee_rate","0.001"),Map.entry("seller_fee_rate","0.002"),Map.entry("buyer_fee","0.0005"),Map.entry("seller_fee","0.09"),Map.entry("timestamp",Instant.now().toString()))));return event;
    }
    @Test void actualConsumerLedgerLinksNetSpendAndIndependentApproval() throws Exception {
        reviewed();String event=recordedTrade();var target=new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,event);var p=pair(target);assertEquals(0,new BigDecimal("45").compareTo(p.context().trade().spentAmount()));assertEquals("USDT",p.context().trade().spentAsset());
        var r=propose(p);assertEquals("APPROVED",decide(r,authorization(r,true,p.facts()),true,p.facts()).status());blocked();
        // 매수 예약 50에서 반환 5를 뺀 실제 지출 45다. 매도자에게는 BTC 0.5가 지출이다.
        long seller=jdbc.queryForObject("SELECT seller_id FROM trades WHERE engine_event_id=?",Long.class,event);assertEquals("BTC",trades.read(seller,target).spentAsset());assertEquals(0,new BigDecimal("0.5").compareTo(trades.read(seller,target).spentAmount()));
    }
    @Test void unknownOtherCustomerAndOpeningAreNeverTradeFallbacks() throws Exception {
        reviewed();String event=recordedTrade();assertThrows(ResponseStatusException.class,()->trades.read(maker.user().getId(),new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,event)));
        assertThrows(ResponseStatusException.class,()->trades.read(customer.getId(),new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,UUID.randomUUID()+":1:0")));
        assertThrows(ResponseStatusException.class,()->trades.read(customer.getId(),new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,"opening")));
    }
    @Test void changedTradeProjectionCannotUseOldBoundSignature() throws Exception {
        reviewed();String event=recordedTrade();var p=pair(new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,event));var r=propose(p);var a=authorization(r,true,p.facts());
        jdbc.update("UPDATE trades SET buyer_id=? WHERE engine_event_id=?",maker.user().getId(),event);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));
    }
    @Test void expiredSecondViewPreventsPairContextDespiteFreshFirstView() throws Exception {
        var p=prepared();UUID old=UUID.randomUUID();jdbc.update("INSERT INTO edd_evidence_views(id,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,viewed_at) SELECT ?,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,clock_timestamp()-interval '6 minutes' FROM edd_evidence_views WHERE id=?",old,p.views().receipt());
        assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.origin(),p.receipt(),new FundsReviewService.Views(p.views().origin(),old),ONBOARDING));
    }
    @Test void standardCddStillCannotBypassEvidenceHoldAfterFundsApproval() throws Exception {
        reviewed();corrected();var p=pair(ONBOARDING);var r=propose(p);assertEquals("APPROVED",decide(r,authorization(r,true,p.facts()),true,p.facts()).status());assertEquals("STANDARD",cdd.status(customer.getId()).outcome());assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }

    @ParameterizedTest @ValueSource(strings={"1000.000000000","0.000000001","10000.000000001"})
    void tradeLinkSerializationPreservesNumericIdentity(String amount) {
        var link=new FundsTradeLink.Link(UUID.randomUUID()+":1:0","engine:test","USDT",new BigDecimal(amount),Instant.now(),"0".repeat(64),"1".repeat(64));
        var restored=json.readValue(json.writeValueAsString(link),FundsTradeLink.Link.class);
        assertEquals(link,restored);assertEquals(link.spentAmount().stripTrailingZeros(),restored.spentAmount());
    }

}
