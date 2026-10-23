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

/** 합성 시작·증감·종료 자료를 실제 암호화·두 사람의 서명·격리 PostgreSQL로 끝까지 검증한다. 외부 발급기관·전체 재산의 진위 검증은 없다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class WealthReviewIntegrationTest extends EddTestSupport {
    @Autowired WealthReviewService reviews;@Autowired EvidenceService evidence;
    record Prepared(UUID opening,UUID changes,UUID closing,WealthReviewService.Views views,WealthReviewService.Context context,WealthFacts facts) {}
    record Approval(WealthReviewService.Views views,OperatorMfaService.AuthorizationProof proof) {}
    byte[] png() throws Exception {var b=new BufferedImage(100,50,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,(int)counter.incrementAndGet());var out=new ByteArrayOutputStream();ImageIO.write(b,"PNG",out);return out.toByteArray();}
    UUID file(EvidenceService.Purpose purpose) throws Exception {return file(purpose,png());}
    UUID file(EvidenceService.Purpose purpose,byte[] bytes) {
        var r=evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),purpose,UUID.randomUUID());return evidence.submit(customer.getId(),sid,r.id(),bytes).id();
    }
    UUID view(Account a,UUID id) {return evidence.view(a.user().getId(),a.sid(),id,a.user().getRole()==UserRole.OPERATOR?EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW:EvidenceService.AccessReason.INDEPENDENT_REVIEW).viewId();}
    WealthReviewService.Views views(Account a,UUID opening,UUID changes,UUID closing) {return new WealthReviewService.Views(view(a,opening),view(a,changes),view(a,closing));}
    WealthFacts facts(String name) {
        var d=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(2);
        return new WealthFacts(new WealthFacts.Balance(name,"가상 시작 기관",d.minusYears(2),d,new BigDecimal("1000"),new BigDecimal("200"),"KRW"),
            new WealthFacts.Changes(name,"가상 증감 기관",d.minusYears(2),d,d,List.of(new WealthFacts.Movement(d.minusYears(1),WealthFacts.Kind.EMPLOYMENT_NET_INCOME,"PRIVATE-REFERENCE-1",new BigDecimal("500"),"KRW"),new WealthFacts.Movement(d,WealthFacts.Kind.CONSUMPTION,"PRIVATE-REFERENCE-2",new BigDecimal("100"),"KRW"))),
            new WealthFacts.Balance(name,"가상 종료 기관",d,d,new BigDecimal("1500"),new BigDecimal("300"),"KRW")).validated();
    }
    Prepared prepared() throws Exception {reviewed();return group();}
    Prepared group() throws Exception {
        UUID opening=file(EvidenceService.Purpose.SOURCE_OF_WEALTH),changes=file(EvidenceService.Purpose.SOURCE_OF_WEALTH),closing=file(EvidenceService.Purpose.SOURCE_OF_WEALTH);var v=views(maker,opening,changes,closing);
        var c=reviews.context(maker.user().getId(),maker.sid(),opening,changes,closing,v);return new Prepared(opening,changes,closing,v,c,facts(c.declaredName()));
    }
    WealthReviewService.Review propose(Prepared p,UUID key,WealthFacts f) {return reviews.propose(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.closing(),p.views(),p.context().assessmentId(),p.context().caseRevision(),key,f);}
    WealthReviewService.Review propose(Prepared p) {return propose(p,UUID.randomUUID(),p.facts());}
    Approval authorization(WealthReviewService.Review r,boolean approve,WealthFacts observed) throws Exception {
        var v=views(checker,r.payload().openingEvidence(),r.payload().changesEvidence(),r.payload().closingEvidence());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),v,approve,observed).authentication();
        return new Approval(v,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
    }
    WealthReviewService.Review decide(WealthReviewService.Review r,Approval a,boolean approve,WealthFacts observed) {return reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),a.views(),approve,observed,a.proof());}
    @Test void minimalContextReturnsNameWithoutInventingWealthDeclaration() throws Exception {
        var p=prepared();String text=json.writeValueAsString(p.context());assertFalse(text.contains("declaredAddress"));assertFalse(text.contains("dateOfBirth"));assertFalse(text.contains("declaredSource"));assertEquals(WealthReviewService.ASSURANCE,p.context().assurance());assertFalse(p.context().toString().contains(p.context().declaredName()));
    }
    @Test void independentSignatureConfirmsContentButNeverReleasesTrading() throws Exception {
        var p=prepared();var r=propose(p);assertTrue(r.payload().reasons().isEmpty());assertEquals("APPROVED",decide(r,authorization(r,true,p.facts()),true,p.facts()).status());blocked();assertEquals("OPEN",service.status(customer.getId()).state());assertTrue(evidence.customerRequests(customer.getId(),sid).stream().allMatch(x->x.status().equals("RECEIVED_UNVERIFIED")));
    }
    @Test void mismatchReasonsSurviveApproval() throws Exception {
        var p=prepared();var f=facts("다른 사람");var r=propose(p,UUID.randomUUID(),f);assertTrue(r.payload().reasons().contains(WealthFacts.Reason.OPENING_HOLDER_MISMATCH));assertEquals(r.payload().reasons(),decide(r,authorization(r,true,f),true,f).payload().reasons());blocked();
    }
    @Test void factsReferenceAndNamesAreEncryptedAndAuditedInspectionOnly() throws Exception {
        var p=prepared();var r=propose(p);String payload=jdbc.queryForObject("SELECT payload FROM edd_wealth_reviews WHERE id=?",String.class,r.payload().id());assertFalse(payload.contains("가상"));assertFalse(payload.contains("PRIVATE-REFERENCE"));
        assertFalse(Arrays.equals(json.writeValueAsBytes(p.facts()),jdbc.queryForObject("SELECT facts_cipher FROM edd_wealth_reviews WHERE id=?",byte[].class,r.payload().id())));
        var inspected=reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),views(checker,p.opening(),p.changes(),p.closing()));assertEquals(p.facts(),inspected.facts());assertFalse(inspected.toString().contains("PRIVATE-REFERENCE"));
    }
    @Test void singleEvidenceCannotPlayMultipleRoles() throws Exception {var p=prepared();assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.opening(),p.views()));}
    @Test void duplicateOriginalCannotBecomeIndependentThirdDocument() throws Exception {
        reviewed();byte[] b=png();UUID a=file(EvidenceService.Purpose.SOURCE_OF_WEALTH,b),z=file(EvidenceService.Purpose.SOURCE_OF_WEALTH,b),third=file(EvidenceService.Purpose.SOURCE_OF_WEALTH);var v=views(maker,a,z,third);assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),a,z,third,v));
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_OF_FUNDS","RESIDENTIAL_ADDRESS"})
    void differentEvidencePurposeCannotSubstituteForWealth(String purpose) throws Exception {reviewed();UUID a=file(EvidenceService.Purpose.SOURCE_OF_WEALTH),b=file(EvidenceService.Purpose.valueOf(purpose)),third=file(EvidenceService.Purpose.SOURCE_OF_WEALTH);var v=views(maker,a,b,third);assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),a,b,third,v));}
    @Test void threeViewsCannotBeSwappedOrBorrowed() throws Exception {var p=prepared();var cv=views(checker,p.opening(),p.changes(),p.closing());for(var v:List.of(new WealthReviewService.Views(p.views().closing(),p.views().changes(),p.views().opening()),cv,new WealthReviewService.Views(p.views().opening(),p.views().changes(),null)))assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.closing(),v));}
    @Test void missingIndependentObservationCreatesNoChallenge() throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.opening(),p.changes(),p.closing());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,null));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_authorization_challenges WHERE user_id=?",Long.class,checker.user().getId()));}
    @Test void changedObservationCannotConsumeSignedChallenge() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,true,facts("다르게 읽은 사람")));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());}
    @Test void rejectionRequiresNoInventedObservationAndKeepsHold() throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.opening(),p.changes(),p.closing());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,false,p.facts()));assertEquals("REJECTED",decide(r,authorization(r,false,null),false,null).status());blocked();}
    @Test void requestKeyBindsThreeDocumentsViewsAndFacts() throws Exception {
        var p=prepared();UUID key=UUID.randomUUID();var r=propose(p,key,p.facts());assertEquals(r,propose(p,key,p.facts()));assertThrows(ResponseStatusException.class,()->propose(p,key,facts("다른 이름")));
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.closing(),views(maker,p.opening(),p.changes(),p.closing()),p.context().assessmentId(),p.context().caseRevision(),key,p.facts()));
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.closing(),p.changes(),p.opening(),p.views(),p.context().assessmentId(),p.context().caseRevision(),key,p.facts()));assertThrows(ResponseStatusException.class,()->propose(p));
    }
    @Test void newDeclarationInvalidatesThreeDocumentsAndSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));blocked();}
    @Test void newUploadInvalidatesThreeOldViewsAndSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());file(EvidenceService.Purpose.RESIDENTIAL_ADDRESS);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void customerKycChangeInvalidatesReview() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE kyc_profiles SET status_digest='changed' WHERE user_id=?",customer.getId());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void requesterVersionChangeInvalidatesPendingReview() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var u=users.findById(maker.user().getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void samePersonDifferentAccountCannotInspect() throws Exception {var p=prepared();var r=propose(p);var u=users.findById(checker.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),p.views()));}
    @Test void staleMfaAndRevokedSessionCannotReadOrDecide() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.closing(),p.views()));}
    @Test void proposalAuditFailureRollsBackReviewCipherAndBinding() throws Exception {var p=prepared();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_WEALTH_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->propose(p));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_wealth_reviews WHERE user_id=?",Long.class,customer.getId()));reset(audit);assertNotNull(propose(p));}
    @ParameterizedTest @ValueSource(strings={"EDD_WEALTH_CONTEXT_READ","EDD_WEALTH_FACTS_READ"})
    void privateReadAuditFailureReturnsNoPersonalData(String action) throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.opening(),p.changes(),p.closing());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq(action),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->{if(action.endsWith("CONTEXT_READ"))reviews.context(checker.user().getId(),checker.sid(),p.opening(),p.changes(),p.closing(),cv);else reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),cv);});}
    @Test void decisionAuditFailureRollsBackProofCounterRevisionAndConclusion() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
        long before=service.status(customer.getId()).revision();
        // 최종 감사 저장에서 실패시킨다. 이미 검증한 서명도 이 거래와 함께 취소돼야 한다.
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_WEALTH_DECIDED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->decide(r,a,true,p.facts()));
        assertEquals(before,service.status(customer.getId()).revision());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_wealth_decisions WHERE review_id=?",Long.class,r.payload().id()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));
        // 같은 실제 서명을 다시 사용해 정상 확정한다. 인증 소비가 남았다면 이 재시도는 실패한다.
        reset(audit);assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
    }
    @Test void directionReplayAndDifferentHashCannotConsumeProof() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,false,null));assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),a.views(),true,p.facts(),a.proof()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
    @Test void otherNamespaceCannotConsumeWealthSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());for(var kind:List.of(OperatorMfaService.ApprovalKind.EDD_REVIEW,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW))assertThrows(ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->mfa.authorizeApproval(checker.user().getId(),checker.sid(),kind,r.payload().id(),r.payloadHash(),true,a.proof())));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());}
    @Test void caseSignatureCannotAuthorizeWealthOrBeConsumedThere() throws Exception {var p=prepared();var r=propose(p);var old=propose(EddService.Finding.AWAITING_EVIDENCE);var proof=proof(checker,old,true);var cv=views(checker,p.opening(),p.changes(),p.closing());assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));assertEquals("APPROVED",decide(old,true,proof).status());}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void invalidOriginOrMissingUserVerificationKeepsProofUnused(boolean badOrigin) throws Exception {var p=prepared();var r=propose(p);var cv=views(checker,p.opening(),p.changes(),p.closing());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts()).authentication();var bad=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,badOrigin?"https://attacker.invalid":"http://localhost:3003",badOrigin,counter.incrementAndGet(),null));assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),bad));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,o.challengeId()));}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentProposalKeysProduceOnePendingReport(boolean same) throws Exception {var p=prepared();UUID a=UUID.randomUUID(),b=same?a:UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var go=new CountDownLatch(1);var first=pool.submit(()->{go.await();try{return propose(p,a,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});var second=pool.submit(()->{go.await();try{return propose(p,b,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});go.countDown();UUID one=first.get(15,TimeUnit.SECONDS),two=second.get(15,TimeUnit.SECONDS);if(same)assertEquals(one,two);else assertTrue(one==null ^ two==null);}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_wealth_reviews WHERE user_id=?",Long.class,customer.getId()));}
    @Test void simultaneousApproveRejectCommitOneDecision() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var other=account(UserRole.APPROVER);var cv=views(other,p.opening(),p.changes(),p.closing());var o=reviews.options(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),cv,false,null).authentication();var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),other.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));try(var pool=Executors.newFixedThreadPool(2)){var go=new CountDownLatch(1);var one=pool.submit(()->{go.await();try{decide(r,a,true,p.facts());return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});var two=pool.submit(()->{go.await();try{reviews.decide(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),cv,false,null,proof);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});go.countDown();assertNotEquals(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_wealth_decisions WHERE review_id=?",Long.class,r.payload().id()));}
    @Test void immutableReportsAndDecisionsRejectMutation() throws Exception {var p=prepared();var r=propose(p);decide(r,authorization(r,true,p.facts()),true,p.facts());for(String table:List.of("edd_wealth_reviews","edd_wealth_decisions"))for(String sql:List.of("UPDATE "+table+" SET "+(table.endsWith("decisions")?"approved=approved":"id=id"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));}
    @Test void auditorGetsMetadataOnlyAndCustomerHasNoAdminAccess() throws Exception {var p=prepared();propose(p);var auditor=account(UserRole.AUDITOR);String url="/api/admin/edd/customers/"+customer.getId()+"/wealth-reviews";mvc.perform(get(url).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(jsonPath("$[0].facts").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post("/api/admin/edd/wealth-reviews/context").header("Authorization","Bearer "+auditor.access()).contentType("application/json").content(json.writeValueAsString(new WealthReviewController.ContextRequest(p.opening(),p.changes(),p.closing(),p.views())))).andExpect(status().isForbidden());mvc.perform(get(url).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());}
    @Test void completeHttpWealthReviewUsesOwnViewsAndRealSignature() throws Exception {var p=prepared();var body=new WealthReviewController.Proposal(p.opening(),p.changes(),p.closing(),p.views(),p.context().assessmentId(),p.context().caseRevision(),p.facts());var response=mvc.perform(post("/api/admin/edd/wealth-reviews").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn();var r=json.readValue(response.getResponse().getContentAsString(),WealthReviewService.Review.class);var cv=views(checker,p.opening(),p.changes(),p.closing());var confirm=new WealthReviewController.Confirmation(r.payloadHash(),cv,p.facts());var options=mvc.perform(post("/api/admin/edd/wealth-reviews/"+r.payload().id()+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(confirm))).andExpect(status().isOk()).andReturn();var o=json.readValue(options.getResponse().getContentAsString(),WealthReviewService.Confirmation.class).authentication();var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));mvc.perform(post("/api/admin/edd/wealth-reviews/"+r.payload().id()+"/approve").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(new WealthReviewController.Decision(r.payloadHash(),cv,p.facts(),proof)))).andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED")).andExpect(header().string("Cache-Control","no-store"));blocked();}
    @Test void previousSevenKindSchemaFailsWithoutChangingHistory() throws Exception {prepared();long before=jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class);String sql=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW','EDD_ADDRESS_REVIEW','EDD_FUNDS_REVIEW')) NOT VALID");jdbc.execute(sql);}));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class));assertTrue(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='operator_approval_bindings'::regclass AND conname='operator_approval_bindings_kind_check'",String.class).contains("EDD_WEALTH_REVIEW"));}

    @Test void comparisonIsPrivateAndReflectsAssetsLiabilitiesIncomeAndConsumption() throws Exception {
        var p=prepared();var r=propose(p);var observed=reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),views(checker,p.opening(),p.changes(),p.closing())).comparison();
        assertEquals(0,new BigDecimal("800").compareTo(observed.openingNet()));
        assertEquals(0,new BigDecimal("1200").compareTo(observed.expectedClosingNet()));assertEquals(0,observed.unexplainedDifference().signum());
        assertFalse(json.writeValueAsString(r.payload()).contains("openingNet"));assertFalse(observed.toString().contains("1200"));
    }
    @Test void unexplainedDifferenceSurvivesIndependentApprovalAndTradingRemainsBlocked() throws Exception {
        var p=prepared();var c=p.facts().closing();var f=new WealthFacts(p.facts().opening(),p.facts().changes(),new WealthFacts.Balance(c.holderName(),c.issuer(),c.asOf(),c.issuedOn(),new BigDecimal("1600"),c.liabilities(),c.currency()));
        var r=propose(p,UUID.randomUUID(),f);assertTrue(r.payload().reasons().contains(WealthFacts.Reason.UNEXPLAINED_NET_WORTH_DIFFERENCE));
        assertEquals(r.payload().reasons(),decide(r,authorization(r,true,f),true,f).payload().reasons());blocked();
    }
    @Test void approvedWealthObservationCannotBypassEvidenceHoldOnStandardCustomer() throws Exception {
        reviewed();corrected();var p=group();var r=propose(p);assertEquals("APPROVED",decide(r,authorization(r,true,p.facts()),true,p.facts()).status());
        assertEquals("STANDARD",cdd.status(customer.getId()).outcome());assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }
    @Test void expiredClosingViewPreventsContextDespiteFreshOtherViews() throws Exception {
        var p=prepared();UUID old=UUID.randomUUID();jdbc.update("INSERT INTO edd_evidence_views(id,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,viewed_at) SELECT ?,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,clock_timestamp()-interval '6 minutes' FROM edd_evidence_views WHERE id=?",old,p.views().closing());
        assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.opening(),p.changes(),p.closing(),new WealthReviewService.Views(p.views().opening(),p.views().changes(),old)));
    }
    @Test void expiredImmutableReportCannotIssueOptions() throws Exception {
        var p=prepared();var r=propose(p);var cv=views(checker,p.opening(),p.changes(),p.closing());UUID id=UUID.randomUUID();
        // 과거 이력을 수정하지 않고 만료 분기만 검증할 별도 표본을 추가한다. 오래된 암호문은 읽히면 안 된다.
        var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(r.payload());tree.put("id",id.toString());tree.put("expiresAt",Instant.now().minusSeconds(1).toString());var payload=json.treeToValue(tree,WealthReviewService.Payload.class);String hash=verifier.digest(tree);
        jdbc.update("INSERT INTO edd_wealth_reviews(id,case_id,opening_evidence,changes_evidence,closing_evidence,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) SELECT ?,case_id,opening_evidence,changes_evidence,closing_evidence,user_id,requester_id,?,?,?,facts_cipher,? FROM edd_wealth_reviews WHERE id=?",id,UUID.randomUUID(),json.writeValueAsString(payload),hash,java.sql.Timestamp.from(payload.expiresAt()),r.payload().id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),id,hash,cv,true,p.facts())).getStatusCode().value());
    }

}
