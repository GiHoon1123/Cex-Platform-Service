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

/** 합성 자료에 대한 두 운영 인물의 관찰 입력과 실제 서명·현재 근거·DB 원자성을 검증한다. OCR/발급기관 연동은 없다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class AddressReviewIntegrationTest extends EddTestSupport {
    @Autowired AddressReviewService reviews;@Autowired EvidenceService evidence;
    record Prepared(UUID evidence,UUID view,AddressReviewService.Context context,AddressFacts facts) {}
    record Approval(UUID view,OperatorMfaService.AuthorizationProof proof) {}
    byte[] png() throws Exception {var b=new BufferedImage(100,50,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(b,"PNG",out);return out.toByteArray();}
    UUID file(EvidenceService.Purpose purpose) throws Exception {
        var r=evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),purpose,UUID.randomUUID());return evidence.submit(customer.getId(),sid,r.id(),png()).id();
    }
    UUID view(Account a,UUID id) {return evidence.view(a.user().getId(),a.sid(),id,a.user().getRole()==UserRole.OPERATOR?EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW:EvidenceService.AccessReason.INDEPENDENT_REVIEW).viewId();}
    Prepared prepared() throws Exception {
        reviewed();UUID file=file(EvidenceService.Purpose.RESIDENTIAL_ADDRESS),view=view(maker,file);
        var context=reviews.context(maker.user().getId(),maker.sid(),file,view);
        return new Prepared(file,view,context,new AddressFacts(AddressFacts.DocumentType.UTILITY_BILL,context.declaredName(),context.declaredAddress(),"가상 기관",LocalDate.now(ZoneId.of("Asia/Seoul"))));
    }
    AddressReviewService.Review propose(Prepared p,UUID key,AddressFacts facts) {return reviews.propose(maker.user().getId(),maker.sid(),p.evidence(),p.view(),p.context().assessmentId(),p.context().caseRevision(),key,facts);}
    AddressReviewService.Review propose(Prepared p) {return propose(p,UUID.randomUUID(),p.facts());}
    Approval authorization(AddressReviewService.Review r,boolean approve,AddressFacts observed) throws Exception {
        UUID view=view(checker,r.payload().evidenceId());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),view,approve,observed).authentication();
        return new Approval(view,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
    }
    AddressReviewService.Review decide(AddressReviewService.Review r,Approval a,boolean approve,AddressFacts observed) {return reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),a.view(),approve,observed,a.proof());}
    @Test void contextExposesOnlyNameAndAddressAfterOwnedFreshView() throws Exception {
        var p=prepared();assertEquals(declaration(CustomerDeclaration.Pep.NO).fullName().toUpperCase(Locale.ROOT),p.context().declaredName());
        String encoded=json.writeValueAsString(p.context());assertFalse(encoded.contains("dateOfBirth"));assertFalse(p.context().toString().contains("가상"));assertFalse(p.facts().toString().contains("가상"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='EDD_ADDRESS_CONTEXT_READ'",Long.class,maker.user().getId()));
    }
    @Test void independentTypedObservationAndSignatureApproveOnlyContentReview() throws Exception {
        var p=prepared();var r=propose(p);assertTrue(r.payload().reasons().isEmpty());var a=authorization(r,true,p.facts());assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
        blocked();assertEquals("OPEN",service.status(customer.getId()).state());assertEquals("RECEIVED_UNVERIFIED",evidence.customerRequests(customer.getId(),sid).getFirst().status());
    }
    @Test void negativeObservationCanBeIndependentlyConfirmedWithoutOpeningTrading() throws Exception {
        var p=prepared();var wrong=new AddressFacts(p.facts().documentType(),"다른 고객","다른 주소",p.facts().issuer(),p.facts().issuedOn().minusDays(91));var r=propose(p,UUID.randomUUID(),wrong);
        assertEquals(List.of(AddressFacts.Reason.NAME_MISMATCH,AddressFacts.Reason.ADDRESS_MISMATCH,AddressFacts.Reason.DOCUMENT_OUTDATED),r.payload().reasons());assertEquals("APPROVED",decide(r,authorization(r,true,wrong),true,wrong).status());blocked();
    }
    @Test void factsAreEncryptedAndOnlyIndependentInspectionReturnsThem() throws Exception {
        var p=prepared();var r=propose(p);var row=jdbc.queryForMap("SELECT payload,facts_cipher FROM edd_address_reviews WHERE id=?",r.payload().id());
        assertFalse(row.get("payload").toString().contains("가상"));assertFalse(Arrays.equals(json.writeValueAsBytes(p.facts()),(byte[])row.get("facts_cipher")));
        UUID cv=view(checker,p.evidence());var inspected=reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),cv);
        assertEquals(p.facts().validated(),inspected.facts());assertFalse(inspected.toString().contains("가상"));
    }
    @Test void sourceOfFundsEvidenceIsNotProcessedAsAddress() throws Exception {
        reviewed();UUID id=file(EvidenceService.Purpose.SOURCE_OF_FUNDS),v=view(maker,id);assertEquals(409,assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),id,v)).getStatusCode().value());blocked();
    }
    @Test void missingReceiptAndAnotherPersonsReceiptCannotAuthorizeReview() throws Exception {
        var p=prepared();UUID cv=view(checker,p.evidence());assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),cv));
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.evidence(),UUID.randomUUID(),p.context().assessmentId(),p.context().caseRevision(),UUID.randomUUID(),p.facts()));
        var r=propose(p);assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),p.view(),true,p.facts()));
    }
    @Test void approverMustSubmitIndependentMatchingFields() throws Exception {
        var p=prepared();var r=propose(p);UUID cv=view(checker,p.evidence());
        assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,null));
        var wrong=new AddressFacts(p.facts().documentType(),p.facts().holderName(),"다르게 읽은 주소",p.facts().issuer(),p.facts().issuedOn());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,wrong));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_authorization_challenges WHERE user_id=?",Long.class,checker.user().getId()));
    }
    @Test void alteredObservationAfterOptionsDoesNotConsumeProof() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var wrong=new AddressFacts(p.facts().documentType(),p.facts().holderName(),p.facts().address(),"다른 기관",p.facts().issuedOn());
        assertThrows(ResponseStatusException.class,()->decide(r,a,true,wrong));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
    }
    @Test void rejectionRequiresNoInventedObservationAndPreservesHold() throws Exception {
        var p=prepared();var r=propose(p);UUID cv=view(checker,p.evidence());assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,false,p.facts()));
        var a=authorization(r,false,null);assertEquals("REJECTED",decide(r,a,false,null).status());blocked();
    }
    @Test void samePersonDifferentAccountCannotIndependentlyInspect() throws Exception {
        var p=prepared();var r=propose(p);var u=users.findById(checker.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);
        assertThrows(ResponseStatusException.class,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),p.view()));blocked();
    }
    @Test void requestKeyBindsExactObservationAndCurrentFacts() throws Exception {
        var p=prepared();UUID key=UUID.randomUUID();var r=propose(p,key,p.facts());assertEquals(r,propose(p,key,p.facts()));
        var wrong=new AddressFacts(p.facts().documentType(),"다른 고객",p.facts().address(),p.facts().issuer(),p.facts().issuedOn());assertThrows(ResponseStatusException.class,()->propose(p,key,wrong));assertThrows(ResponseStatusException.class,()->propose(p));
    }
    @Test void newDeclarationInvalidatesOptionsAndOldReceipt() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
        assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),p.view()));blocked();
    }
    @Test void newSignedAssessmentCannotReuseOldDocumentReview() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());corrected();assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));blocked();
    }
    @Test void newEvidenceInvalidatesPendingReviewAndRequiresFreshView() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());file(EvidenceService.Purpose.SOURCE_OF_WEALTH);
        assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));assertThrows(ResponseStatusException.class,()->propose(p));
        UUID fresh=view(maker,p.evidence());var c=reviews.context(maker.user().getId(),maker.sid(),p.evidence(),fresh);var next=new Prepared(p.evidence(),fresh,c,p.facts());assertNotEquals(r.payload().id(),propose(next).payload().id());
    }
    @Test void requesterVersionChangeInvalidatesPendingReview() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var makerUser=users.findById(maker.user().getId()).orElseThrow();makerUser.setTradingRestricted(true);users.saveAndFlush(makerUser);assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));
    }
    @Test void oldSessionCannotReadCustomerContextOrApprove() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));
        jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),p.view()));
    }
    @Test void proposalAuditFailureRollsBackCipherBindingAndReview() throws Exception {
        var p=prepared();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_ADDRESS_PROPOSED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->propose(p));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_address_reviews WHERE user_id=?",Long.class,customer.getId()));reset(audit);assertNotNull(propose(p));
    }
    @Test void viewAuditFailureRollsBackNewReceipt() throws Exception {
        var p=prepared();long before=jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views WHERE evidence_id=?",Long.class,p.evidence());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_EVIDENCE_VIEWED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->view(checker,p.evidence()));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views WHERE evidence_id=?",Long.class,p.evidence()));
    }
    @Test void inspectionAuditFailureNeverReturnsPrivateFacts() throws Exception {
        var p=prepared();var r=propose(p);UUID cv=view(checker,p.evidence());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_ADDRESS_FACTS_READ"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id(),cv));
    }
    @Test void decisionAuditFailureRollsBackChallengeCounterDecisionAndRevision() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());long revision=service.status(customer.getId()).revision();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_ADDRESS_DECIDED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->decide(r,a,true,p.facts()));assertEquals(revision,service.status(customer.getId()).revision());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_address_decisions WHERE review_id=?",Long.class,r.payload().id()));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));reset(audit);assertEquals("APPROVED",decide(r,a,true,p.facts()).status());blocked();
    }
    @Test void approvalProofCannotAuthorizeRejectionOrReplay() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,false,null));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));
    }
    @Test void otherEddNamespaceCannotConsumeAddressSignature() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
        assertThrows(ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->mfa.authorizeApproval(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.EDD_REVIEW,r.payload().id(),r.payloadHash(),true,a.proof())));
        assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
    }
    @Test void concurrentApprovalAndRejectionCommitOneConclusion() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());var other=account(UserRole.APPROVER);UUID otherView=view(other,p.evidence());var option=reviews.options(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),otherView,false,null).authentication();
        var reject=new OperatorMfaService.AuthorizationProof(option.challengeId(),other.key().assertion(option,"http://localhost:3003",true,counter.incrementAndGet(),null));
        try(var pool=Executors.newFixedThreadPool(2)) {var go=new CountDownLatch(1);var first=pool.submit(()->{go.await();try{decide(r,a,true,p.facts());return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});var second=pool.submit(()->{go.await();try{reviews.decide(other.user().getId(),other.sid(),r.payload().id(),r.payloadHash(),otherView,false,null,reject);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});go.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_address_decisions WHERE review_id=?",Long.class,r.payload().id()));blocked();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentProposalKeysNeverCreateTwoPendingReports(boolean same) throws Exception {
        var p=prepared();UUID a=UUID.randomUUID(),b=same?a:UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {var go=new CountDownLatch(1);var first=pool.submit(()->{go.await();try{return propose(p,a,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});var second=pool.submit(()->{go.await();try{return propose(p,b,p.facts()).payload().id();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});go.countDown();UUID one=first.get(15,TimeUnit.SECONDS),two=second.get(15,TimeUnit.SECONDS);if(same)assertEquals(one,two);else assertTrue(one==null ^ two==null);}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_address_reviews WHERE user_id=?",Long.class,customer.getId()));
    }
    @Test void immutableViewsReportsAndDecisionsRejectMutation() throws Exception {
        var p=prepared();var r=propose(p);decide(r,authorization(r,true,p.facts()),true,p.facts());
        for(String table:List.of("edd_evidence_views","edd_address_reviews","edd_address_decisions"))for(String sql:List.of("UPDATE "+table+" SET "+(table.equals("edd_address_decisions")?"approved=approved":"id=id"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
    }
    @Test void auditorSeesMetadataButCannotReadFactsAndCustomerHasNoAdminAccess() throws Exception {
        var p=prepared();propose(p);var auditor=account(UserRole.AUDITOR);String path="/api/admin/edd/customers/"+customer.getId()+"/address-reviews";
        mvc.perform(get(path).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(jsonPath("$[0].facts").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/admin/edd/evidence/"+p.evidence()+"/address-review/context").header("Authorization","Bearer "+auditor.access()).contentType("application/json").content(json.writeValueAsString(new AddressReviewController.Viewed(p.view())))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void completeHttpReviewUsesStoredPayloadAndOwnObservation() throws Exception {
        var p=prepared();var created=mvc.perform(post("/api/admin/edd/evidence/"+p.evidence()+"/address-reviews").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(new AddressReviewController.Proposal(p.view(),p.context().assessmentId(),p.context().caseRevision(),p.facts())))).andExpect(status().isOk()).andReturn();
        var r=json.readValue(created.getResponse().getContentAsString(),AddressReviewService.Review.class);UUID cv=view(checker,p.evidence());var confirm=new AddressReviewController.Confirmation(r.payloadHash(),cv,p.facts());
        var encoded=mvc.perform(post("/api/admin/edd/address-reviews/"+r.payload().id()+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(confirm))).andExpect(status().isOk()).andReturn();
        var o=json.readValue(encoded.getResponse().getContentAsString(),AddressReviewService.Confirmation.class).authentication();var proof=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));var decision=new AddressReviewController.Decision(r.payloadHash(),cv,p.facts(),proof);
        mvc.perform(post("/api/admin/edd/address-reviews/"+r.payload().id()+"/approve").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(decision))).andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED")).andExpect(header().string("Cache-Control","no-store"));blocked();
    }
    @Test void expiredViewIsRejectedEvenWithFreshMfa() throws Exception {
        var p=prepared();UUID old=UUID.randomUUID();
        jdbc.update("INSERT INTO edd_evidence_views(id,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,viewed_at) SELECT ?,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id,clock_timestamp()-interval '6 minutes' FROM edd_evidence_views WHERE id=?",old,p.view());
        assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),old));
    }
    @Test void customerKycChangeCannotLeaveOldReviewExecutable() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());jdbc.update("UPDATE kyc_profiles SET status_digest='changed-after-review' WHERE user_id=?",customer.getId());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void invalidOriginOrMissingUserVerificationDoesNotConsumeDecision(boolean badOrigin) throws Exception {
        var p=prepared();var r=propose(p);UUID cv=view(checker,p.evidence());var o=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts()).authentication();
        var invalid=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,badOrigin?"https://attacker.invalid":"http://localhost:3003",badOrigin,counter.incrementAndGet(),null));
        assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),invalid));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,o.challengeId()));
        var valid=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));assertEquals("APPROVED",reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),valid).status());
    }
    @Test void expiredImmutableReviewCannotIssueOptions() throws Exception {
        var p=prepared();var r=propose(p);UUID cv=view(checker,p.evidence()),id=UUID.randomUUID();
        // 승인된 과거 상태를 수정하지 않고, 만료 판정만 검사하는 과거 메타데이터 표본을 추가한다.
        var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(r.payload());tree.put("id",id.toString());tree.put("expiresAt",Instant.now().minusSeconds(1).toString());var payload=json.treeToValue(tree,AddressReviewService.Payload.class);String digest=verifier.digest(tree);
        jdbc.update("INSERT INTO edd_address_reviews(id,case_id,evidence_id,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) SELECT ?,case_id,evidence_id,user_id,requester_id,?, ?,?,facts_cipher,? FROM edd_address_reviews WHERE id=?",id,UUID.randomUUID(),json.writeValueAsString(payload),digest,java.sql.Timestamp.from(payload.expiresAt()),r.payload().id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->reviews.options(checker.user().getId(),checker.sid(),id,digest,cv,true,p.facts())).getStatusCode().value());
        assertTrue(reviews.list(maker.user().getId(),maker.sid(),customer.getId(),0).stream().anyMatch(x->x.payload().id().equals(id)&&x.status().equals("EXPIRED")));
    }
    @Test void previousFiveKindSchemaFailsWithoutRewritingAnyHistory() throws Exception {
        prepared();long before=jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class);
        String sql=new org.springframework.core.io.ClassPathResource("security/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.execute("ALTER TABLE operator_approval_bindings DROP CONSTRAINT operator_approval_bindings_kind_check");
            jdbc.execute("ALTER TABLE operator_approval_bindings ADD CONSTRAINT operator_approval_bindings_kind_check CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW')) NOT VALID");jdbc.execute(sql);
        }));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("현재 승인 스키마"));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM edd_evidence_views",Long.class));
        assertTrue(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='operator_approval_bindings'::regclass AND conname='operator_approval_bindings_kind_check'",String.class).contains("EDD_ADDRESS_REVIEW"));
    }
    @Test void anotherEvidenceReceiptCannotSubstituteForReviewedImage() throws Exception {
        var p=prepared();UUID other=file(EvidenceService.Purpose.SOURCE_OF_FUNDS),otherView=view(maker,other),fresh=view(maker,p.evidence());
        assertThrows(ResponseStatusException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),otherView));assertNotNull(reviews.context(maker.user().getId(),maker.sid(),p.evidence(),fresh));
    }
    @Test void differentHashCannotConsumeValidSignature() throws Exception {
        var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
        assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),a.view(),true,p.facts(),a.proof()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
    }
    @Test void contextAuditFailureDoesNotReturnDeclaredPersonalData() throws Exception {
        var p=prepared();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_ADDRESS_CONTEXT_READ"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->reviews.context(maker.user().getId(),maker.sid(),p.evidence(),p.view()));
    }
    @Test void sameRequestKeyCannotBeReboundToAnotherView() throws Exception {
        var p=prepared();UUID key=UUID.randomUUID();propose(p,key,p.facts());UUID later=view(maker,p.evidence());
        assertThrows(ResponseStatusException.class,()->reviews.propose(maker.user().getId(),maker.sid(),p.evidence(),later,p.context().assessmentId(),p.context().caseRevision(),key,p.facts()));
    }
    @Test void httpViewReceiptEnablesOnlyMinimalAuditedContextAndInspection() throws Exception {
        var p=prepared();var r=propose(p);
        var response=mvc.perform(post("/api/admin/edd/evidence/"+p.evidence()+"/view").header("Authorization","Bearer "+checker.access()).contentType("application/json").content("{\"reason\":\"INDEPENDENT_REVIEW\"}")).andExpect(status().isOk()).andExpect(header().exists("X-Evidence-View-Id")).andReturn();
        UUID receipt=UUID.fromString(response.getResponse().getHeader("X-Evidence-View-Id"));String body=json.writeValueAsString(new AddressReviewController.Viewed(receipt));
        mvc.perform(post("/api/admin/edd/evidence/"+p.evidence()+"/address-review/context").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("declaredName").value(p.context().declaredName())).andExpect(jsonPath("dateOfBirth").doesNotExist());
        mvc.perform(post("/api/admin/edd/address-reviews/"+r.payload().id()+"/inspect").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("facts.address").value(p.facts().address())).andExpect(header().string("Cache-Control","no-store"));
    }

    @Test void existingEddSignatureCannotApproveAddressReviewOrBeConsumedThere() throws Exception {
        var p=prepared();var r=propose(p);var old=propose(EddService.Finding.AWAITING_EVIDENCE);var oldProof=proof(checker,old,true);UUID cv=view(checker,p.evidence());
        assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),cv,true,p.facts(),oldProof));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,oldProof.challengeId()));assertEquals("APPROVED",decide(old,true,oldProof).status());blocked();
    }

    @Test void allowedFrontendCanReadReceiptHeaderAndForeignOriginCannot() throws Exception {
        var p=prepared();String url="/api/admin/edd/evidence/"+p.evidence()+"/view";
        mvc.perform(post(url).header("Origin","http://localhost:3003").header("Authorization","Bearer "+checker.access()).contentType("application/json").content("{\"reason\":\"INDEPENDENT_REVIEW\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3003")).andExpect(header().string("Access-Control-Expose-Headers","X-Evidence-View-Id"));
        mvc.perform(post(url).header("Origin","https://attacker.invalid").header("Authorization","Bearer "+checker.access()).contentType("application/json").content("{\"reason\":\"INDEPENDENT_REVIEW\"}")).andExpect(status().isForbidden());
    }

    @Test void approvedAddressReviewCannotBypassEvidenceHoldOnStandardCustomer() throws Exception {
        reviewed();corrected();UUID id=file(EvidenceService.Purpose.RESIDENTIAL_ADDRESS),v=view(maker,id);var context=reviews.context(maker.user().getId(),maker.sid(),id,v);
        var facts=new AddressFacts(AddressFacts.DocumentType.GOVERNMENT_RESIDENCE,context.declaredName(),context.declaredAddress(),"가상 기관",LocalDate.now(ZoneId.of("Asia/Seoul")));var prepared=new Prepared(id,v,context,facts);var r=propose(prepared);
        assertEquals("APPROVED",decide(r,authorization(r,true,facts),true,facts).status());assertEquals("STANDARD",cdd.status(customer.getId()).outcome());
        assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }

}
