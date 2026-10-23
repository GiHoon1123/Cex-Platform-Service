package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.auth.model.entity.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** 가상 PNG만 제출한다. 실제 고객 정보·공급사 계정 없이 암호화·실제 보안 체인·PG 트랜잭션을 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class EddEvidenceIntegrationTest extends EddTestSupport {
    @Autowired EvidenceService evidence;@Autowired EvidenceCrypto encryption;
    byte[] png() throws Exception {var b=new BufferedImage(30,20,BufferedImage.TYPE_INT_RGB);b.setRGB(1,1,0xffffff);var out=new ByteArrayOutputStream();ImageIO.write(b,"PNG",out);return out.toByteArray();}
    EvidenceService.Request request() throws Exception {reviewed();return ask();}
    EvidenceService.Request ask() {return evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());}
    EvidenceService.Receipt upload(EvidenceService.Request r,byte[] b) {return evidence.submit(customer.getId(),sid,r.id(),b);}
    EvidenceService.View view(UUID id) {return evidence.view(maker.user().getId(),maker.sid(),id,EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW);}
    @Test void authenticatedCustomerSubmitsOnlyRequestedEvidenceAndRemainsBlocked() throws Exception {
        var r=request();assertEquals("REQUESTED",r.status());var result=upload(r,png());assertEquals("RECEIVED_UNVERIFIED",result.status());blocked();
        assertEquals(result.id(),evidence.customerRequests(customer.getId(),sid).getFirst().evidenceId());assertEquals("OPEN",service.status(customer.getId()).state());
        byte[] response=view(result.id()).bytes();assertNotNull(ImageIO.read(new ByteArrayInputStream(response)));
        assertEquals(0xffffff,ImageIO.read(new ByteArrayInputStream(response)).getRGB(1,1)&0xffffff);
    }
    @Test void separateEncryptionPreservesOriginalAndRemovesTrailingPayloadFromView() throws Exception {
        var r=request();byte[] clean=png(),marker="고객 가상 숨김 자료".getBytes(StandardCharsets.UTF_8);byte[] raw=Arrays.copyOf(clean,clean.length+marker.length);System.arraycopy(marker,0,raw,clean.length,marker.length);
        var receipt=upload(r,raw);var row=jdbc.queryForMap("SELECT * FROM edd_evidence WHERE id=?",receipt.id());var req=jdbc.queryForMap("SELECT * FROM edd_evidence_requests WHERE id=?",r.id());
        String context=receipt.id()+":"+customer.getId()+":"+req.get("case_id")+":"+r.id()+":"+r.assessmentId()+":"+r.purpose();
        assertArrayEquals(raw,encryption.decrypt((String)row.get("key_id"),context+":ORIGINAL",(byte[])row.get("original_cipher")));
        assertFalse(Arrays.equals(raw,(byte[])row.get("original_cipher")));assertFalse(new String(view(receipt.id()).bytes(),StandardCharsets.ISO_8859_1).contains(new String(marker,StandardCharsets.ISO_8859_1)));
        assertFalse(evidence.customerRequests(customer.getId(),sid).toString().contains("cipher"));
    }
    @Test void exactReplayDoesNotIncrementRevisionOrStoreTwice() throws Exception {
        var r=request();byte[] raw=png();var first=upload(r,raw);long revision=service.status(customer.getId()).revision();assertEquals(first,upload(r,raw));assertEquals(revision,service.status(customer.getId()).revision());
        var different=raw.clone();different[different.length-1]^=1;assertEquals(409,assertThrows(ResponseStatusException.class,()->upload(r,different)).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_evidence WHERE request_id=?",Long.class,r.id()));
    }
    @Test void requestKeyCannotBeReusedForDifferentPurposeAndPendingPurposeIsUnique() throws Exception {
        reviewed();UUID key=UUID.randomUUID(),assessment=cdd.status(customer.getId()).assessmentId();
        var r=evidence.request(maker.user().getId(),maker.sid(),customer.getId(),assessment,EvidenceService.Purpose.SOURCE_OF_FUNDS,key);
        assertEquals(r,evidence.request(maker.user().getId(),maker.sid(),customer.getId(),assessment,EvidenceService.Purpose.SOURCE_OF_FUNDS,key));
        assertThrows(ResponseStatusException.class,()->evidence.request(maker.user().getId(),maker.sid(),customer.getId(),assessment,EvidenceService.Purpose.SOURCE_OF_WEALTH,key));assertThrows(ResponseStatusException.class,this::ask);
    }
    @Test void unrelatedCustomerCannotSubmitOrEnumerateRequest() throws Exception {
        var r=request();var another=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var t=tokens.saveAndFlush(dustin.cex.domains.auth.model.entity.RefreshToken.builder().userId(another.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        assertTrue(evidence.customerRequests(another.getId(),t.getSessionId()).isEmpty());assertEquals(404,assertThrows(ResponseStatusException.class,()->evidence.submit(another.getId(),t.getSessionId(),r.id(),new byte[]{1})).getStatusCode().value());
    }
    @Test void oldAssessmentCannotAcceptFileAndCustomerSeesStaleRequest() throws Exception {
        var r=request();corrected();assertEquals("STALE",evidence.customerRequests(customer.getId(),sid).getFirst().status());assertThrows(ResponseStatusException.class,()->upload(r,png()));blocked();
    }
    @Test void newDeclarationWithoutAssessmentInvalidatesRequest() throws Exception {
        var r=request();cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->upload(r,png()));assertEquals("STALE",evidence.customerRequests(customer.getId(),sid).getFirst().status());
    }
    @Test void revokedAndForeignCustomerSessionsCannotWrite() throws Exception {
        var r=request();assertThrows(ResponseStatusException.class,()->evidence.submit(customer.getId(),maker.sid(),r.id(),png()));jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",sid);assertThrows(ResponseStatusException.class,()->upload(r,png()));
    }
    @Test void requestAlonePreventsBypassingUnreviewedMaterialsWithIdentityClosure() throws Exception {
        reviewed();corrected();ask();assertThrows(ResponseStatusException.class,()->propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY));blocked();
    }
    @Test void uploadInvalidatesEarlierReportProofAndDoesNotOpenTrading() throws Exception {
        reviewed();var request=ask();var report=propose(EddService.Finding.AWAITING_EVIDENCE);var proof=proof(checker,report,true);upload(request,png());
        assertThrows(ResponseStatusException.class,()->decide(report,true,proof));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));blocked();
    }
    @Test void auditorAndUnassignedOperatorHaveNoContentAccess() throws Exception {
        var r=request();var file=upload(r,png());var auditor=account(UserRole.AUDITOR);var other=account(UserRole.OPERATOR);
        assertEquals(1,evidence.operatorRequests(auditor.user().getId(),auditor.sid(),customer.getId()).size());
        assertThrows(ResponseStatusException.class,()->evidence.view(auditor.user().getId(),auditor.sid(),file.id(),EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW));
        assertThrows(ResponseStatusException.class,()->evidence.view(other.user().getId(),other.sid(),file.id(),EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW));
    }
    @Test void independentApproverMustGiveCorrectReasonAndBeDifferentPerson() throws Exception {
        var r=request();var file=upload(r,png());assertThrows(ResponseStatusException.class,()->evidence.view(checker.user().getId(),checker.sid(),file.id(),EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW));
        assertNotNull(evidence.view(checker.user().getId(),checker.sid(),file.id(),EvidenceService.AccessReason.INDEPENDENT_REVIEW));
        var u=users.findById(checker.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);
        assertThrows(ResponseStatusException.class,()->evidence.view(checker.user().getId(),checker.sid(),file.id(),EvidenceService.AccessReason.INDEPENDENT_REVIEW));
    }
    @Test void revokedAndStaleMfaNeverReturnsImage() throws Exception {
        var r=request();var file=upload(r,png());jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->view(file.id()));
        jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->evidence.view(checker.user().getId(),checker.sid(),file.id(),EvidenceService.AccessReason.INDEPENDENT_REVIEW));
    }
    @Test void requestAuditFailureRollsBackRequestAndRetryKeyRemainsUsable() throws Exception {
        reviewed();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_EVIDENCE_REQUESTED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,this::ask);assertTrue(evidence.customerRequests(customer.getId(),sid).isEmpty());reset(audit);assertNotNull(ask());
    }
    @Test void submissionAuditFailureRollsBackCipherAndCaseRevision() throws Exception {
        var r=request();long before=service.status(customer.getId()).revision();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_EVIDENCE_SUBMITTED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->upload(r,png()));assertEquals(before,service.status(customer.getId()).revision());assertEquals("REQUESTED",evidence.customerRequests(customer.getId(),sid).getFirst().status());reset(audit);assertNotNull(upload(r,png()));
    }
    @Test void readAuditFailureDoesNotReleaseContentOrConsumeQuota() throws Exception {
        var r=request();var file=upload(r,png());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_EVIDENCE_VIEWED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->view(file.id()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='EDD_EVIDENCE_VIEWED'",Long.class,maker.user().getId()));reset(audit);assertNotNull(view(file.id()));
    }
    @Test void immutableRequestsAndFilesRejectUpdateDeleteTruncate() throws Exception {
        upload(request(),png());for(String table:List.of("edd_evidence_requests","edd_evidence")) for(String sql:List.of("UPDATE "+table+" SET user_id=user_id","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE")) assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
    }
    @Test void concurrentDuplicateSubmissionHasOneFileAndOneRevisionChange() throws Exception {
        var r=request();byte[] bytes=png();long before=service.status(customer.getId()).revision();
        try(var pool=Executors.newFixedThreadPool(2)) {var go=new CountDownLatch(1);var a=pool.submit(()->{go.await();return upload(r,bytes);});var b=pool.submit(()->{go.await();return upload(r,bytes);});go.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}
        assertEquals(before+1,service.status(customer.getId()).revision());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_evidence WHERE request_id=?",Long.class,r.id()));
    }
    @Test void readQuotaIsPersistedAndCustomerStillHasNoContentEndpoint() throws Exception {
        var r=request();var file=upload(r,png());for(int i=0;i<20;i++)view(file.id());assertEquals(429,assertThrows(ResponseStatusException.class,()->view(file.id())).getStatusCode().value());
        mvc.perform(post("/api/admin/edd/evidence/"+file.id()+"/view").header("Authorization","Bearer "+customerAccess).contentType("application/json").content("{\"reason\":\"DUE_DILIGENCE_REVIEW\"}")).andExpect(status().isForbidden());
    }
    @Test void actualHttpUploadAndPrivateAttachmentHaveNoCacheAndNoOriginalFilename() throws Exception {
        var r=request();var uploaded=mvc.perform(post("/api/kyc/edd-evidence/requests/"+r.id()).header("Authorization","Bearer "+customerAccess).contentType("image/png").content(png())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        UUID id=UUID.fromString(json.readTree(uploaded.getResponse().getContentAsString()).path("id").asString());
        mvc.perform(post("/api/admin/edd/evidence/"+id+"/view").header("Authorization","Bearer "+maker.access()).contentType("application/json").content("{\"reason\":\"DUE_DILIGENCE_REVIEW\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type","image/png")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Content-Disposition","attachment; filename=\""+id+".png\""));
        mvc.perform(post("/api/kyc/edd-evidence/requests/"+r.id()).header("Authorization","Bearer "+customerAccess).contentType("application/pdf").content("%PDF")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/admin/edd/evidence/"+id+"/view").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
    }
    @Test void malformedAndOversizedHttpBodyAreRejectedWithoutSaving() throws Exception {
        var r=request();mvc.perform(post("/api/kyc/edd-evidence/requests/"+r.id()).header("Authorization","Bearer "+customerAccess).contentType("image/png").content("<svg onload='alert(1)'/>"))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/kyc/edd-evidence/requests/"+r.id()).header("Authorization","Bearer "+customerAccess).contentType("image/png").content(new byte[EvidenceImage.MAX_INPUT+1])).andExpect(status().isPayloadTooLarge());
        assertNull(evidence.customerRequests(customer.getId(),sid).getFirst().evidenceId());
    }
    @Test void expiredRequestIsNeverAcceptedOrDeleted() throws Exception {
        var r=request();UUID expired=UUID.randomUUID();
        // 불변 이력을 변경하지 않는다. 테스트 전용 과거 요청 행을 추가해 실제 만료 조건을 재현한다.
        jdbc.update("INSERT INTO edd_evidence_requests(id,case_id,user_id,assessment_id,purpose,requester_id,requester_person,request_key,expires_at) SELECT ?,case_id,user_id,assessment_id,purpose,requester_id,requester_person,?,clock_timestamp()-interval '1 second' FROM edd_evidence_requests WHERE id=?",expired,UUID.randomUUID(),r.id());
        assertEquals("EXPIRED",evidence.customerRequests(customer.getId(),sid).stream().filter(x->x.id().equals(expired)).findFirst().orElseThrow().status());
        assertThrows(ResponseStatusException.class,()->evidence.submit(customer.getId(),sid,expired,png()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_evidence_requests WHERE id=?",Long.class,expired));
    }
    @Test void requestedAndReceivedLimitsDoNotPermitUnlimitedCollection() throws Exception {
        reviewed();for(int i=0;i<5;i++)upload(ask(),png());
        assertEquals(429,assertThrows(ResponseStatusException.class,this::ask).getStatusCode().value());
        assertEquals(5,evidence.customerRequests(customer.getId(),sid).size());blocked();
    }
    @Test void enabledCustomerWithoutReviewCaseCannotBeAskedForEvidence() throws Exception {
        verify(false);assertThrows(ResponseStatusException.class,this::ask);allowed();assertTrue(evidence.customerRequests(customer.getId(),sid).isEmpty());
    }
    @Test void metadataAccessAlsoRequiresFreshMfaAndNeverHasOriginalBytes() throws Exception {
        var r=request();upload(r,png());var auditor=account(UserRole.AUDITOR);
        mvc.perform(get("/api/admin/edd/customers/"+customer.getId()+"/evidence-requests").header("Authorization","Bearer "+auditor.access()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$[0].status").value("RECEIVED_UNVERIFIED")).andExpect(jsonPath("$[0].originalCipher").doesNotExist());
        jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",auditor.sid());
        mvc.perform(get("/api/admin/edd/customers/"+customer.getId()+"/evidence-requests").header("Authorization","Bearer "+auditor.access())).andExpect(status().isForbidden());
    }
    @Test void actualRequestApiRequiresOperatorAndBindsRequestKey() throws Exception {
        reviewed();String path="/api/admin/edd/customers/"+customer.getId()+"/evidence-requests";
        String body=json.writeValueAsString(new EvidenceController.Proposal(cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS));UUID key=UUID.randomUUID();
        mvc.perform(post(path).header("Authorization","Bearer "+checker.access()).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("status").value("REQUESTED"));
        mvc.perform(post(path).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(1,evidence.customerRequests(customer.getId(),sid).size());
        mvc.perform(post("/api/kyc/edd-evidence/requests/"+UUID.randomUUID()).contentType("image/png").content(png())).andExpect(status().isUnauthorized());
    }

}
