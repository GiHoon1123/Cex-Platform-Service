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
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 자료 묶음의 동결·전체 페이지 제공·현재 사실 결합을 실제 PostgreSQL·보안키·감사 트랜잭션으로 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class EddDossierIntegrationTest extends EddTestSupport {
    @Autowired EvidenceService evidence;@Autowired AddressReviewService addresses;@Autowired FundsReviewService funds;@Autowired WealthReviewService wealth;@Autowired EddDossier dossiers;
    byte[] png() throws Exception {var b=new BufferedImage(100,50,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,(int)counter.incrementAndGet());var out=new ByteArrayOutputStream();ImageIO.write(b,"PNG",out);return out.toByteArray();}
    UUID file(EvidenceService.Purpose purpose) throws Exception {var r=evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),purpose,UUID.randomUUID());return evidence.submit(customer.getId(),sid,r.id(),png()).id();}
    UUID view(Account a,UUID file) {return evidence.view(a.user().getId(),a.sid(),file,a.user().getRole()==UserRole.OPERATOR?EvidenceService.AccessReason.DUE_DILIGENCE_REVIEW:EvidenceService.AccessReason.INDEPENDENT_REVIEW).viewId();}
    EddService.Report referral() {return propose(EddService.Finding.COMPLIANCE_ESCALATION);}
    EddService.Inspection inspect(EddService.Report r,int page) {return service.inspect(checker.user().getId(),checker.sid(),r.payload().id(),page);}
    EddService.Confirmation options(EddService.Report r) {return service.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true);}
    void seedExpiredRequests(int count) {
        var c=service.status(customer.getId());
        // 페이지 경계만 검증하는 과거 합성 요청을 추가한다. 기존 이력을 변경하거나 외부 확인 완료로 표시하지 않는다.
        for(int i=0;i<count;i++)jdbc.update("INSERT INTO edd_evidence_requests(id,case_id,user_id,assessment_id,purpose,requester_id,requester_person,request_key,expires_at,created_at) VALUES(?,?,?,?,'SOURCE_OF_FUNDS',?,?,?,clock_timestamp()-interval '2 days',clock_timestamp()-interval '3 days')",UUID.randomUUID(),c.id(),customer.getId(),c.latestAssessment(),maker.user().getId(),maker.user().getOperatorPersonId(),UUID.randomUUID());
    }
    @Test void emptyBundleStillRequiresIndependentPageZeroAndDoesNotClaimExternalVerification() throws Exception {
        reviewed();var r=referral();assertEquals(0,r.payload().dossier().itemCount());assertEquals(1,r.payload().dossier().pageCount());assertEquals(EddDossier.ASSURANCE,r.payload().dossier().assurance());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->options(r)).getStatusCode().value());assertTrue(inspect(r,0).dossier().items().isEmpty());assertNotNull(options(r));
        assertEquals("APPROVED",decide(r,true,proof(checker,r,true)).status());assertEquals("REFERRED",service.status(customer.getId()).state());blocked();
    }
    @Test void evidenceRequestsAndReceivedFilesAreDistinctAndContainNoPrivateContent() throws Exception {
        reviewed();UUID file=file(EvidenceService.Purpose.SOURCE_OF_FUNDS);evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_WEALTH,UUID.randomUUID());
        var r=referral();var items=inspect(r,0).dossier().items();assertEquals(2,items.size());assertTrue(items.stream().anyMatch(x->x.path("status").asText().equals("RECEIVED_UNVERIFIED")&&x.path("evidenceIds").get(0).asText().equals(file.toString())));assertTrue(items.stream().anyMatch(x->x.path("status").asText().equals("REQUESTED")));
        String body=json.writeValueAsString(items);for(String secret:List.of("가상","1990-01-01","facts_cipher","original_cipher","view_cipher","fullName","available"))assertFalse(body.contains(secret));
    }
    @ParameterizedTest @ValueSource(strings={"ADDRESS","FUNDS","WEALTH"})
    void pendingContentReviewChangesDigestWithoutChangingCaseRevision(String kind) throws Exception {
        reviewed();var ids=new ArrayList<UUID>();int count=kind.equals("ADDRESS")?1:kind.equals("FUNDS")?2:3;
        var purpose=kind.equals("ADDRESS")?EvidenceService.Purpose.RESIDENTIAL_ADDRESS:kind.equals("FUNDS")?EvidenceService.Purpose.SOURCE_OF_FUNDS:EvidenceService.Purpose.SOURCE_OF_WEALTH;
        for(int i=0;i<count;i++)ids.add(file(purpose));var r=referral();var signed=proof(checker,r,true);long revision=service.status(customer.getId()).revision();var views=ids.stream().map(id->view(maker,id)).toList();var d=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);String name=declaration(CustomerDeclaration.Pep.NO).fullName();
        if(kind.equals("ADDRESS")) {var c=addresses.context(maker.user().getId(),maker.sid(),ids.getFirst(),views.getFirst());addresses.propose(maker.user().getId(),maker.sid(),ids.getFirst(),views.getFirst(),c.assessmentId(),c.caseRevision(),UUID.randomUUID(),new AddressFacts(AddressFacts.DocumentType.UTILITY_BILL,c.declaredName(),c.declaredAddress(),"가상 기관",d));}
        else if(kind.equals("FUNDS")) {var v=new FundsReviewService.Views(views.get(0),views.get(1));var t=new FundsTradeLink.Target(FundsTradeLink.Scope.ONBOARDING,null);var c=funds.context(maker.user().getId(),maker.sid(),ids.get(0),ids.get(1),v,t);funds.propose(maker.user().getId(),maker.sid(),ids.get(0),ids.get(1),v,c.assessmentId(),c.caseRevision(),UUID.randomUUID(),new FundsFacts(new FundsFacts.Origin(CustomerDeclaration.SourceOfFunds.SALARY,name,"가상 지급인","가상 기관",d.minusDays(30),d,d,BigDecimal.ONE,"KRW"),new FundsFacts.Receipt(name,"가상 지급인","가상 기관","PRIVATE-REFERENCE",d,d,BigDecimal.ONE,"KRW")),t);}
        else {var v=new WealthReviewService.Views(views.get(0),views.get(1),views.get(2));var c=wealth.context(maker.user().getId(),maker.sid(),ids.get(0),ids.get(1),ids.get(2),v);wealth.propose(maker.user().getId(),maker.sid(),ids.get(0),ids.get(1),ids.get(2),v,c.assessmentId(),c.caseRevision(),UUID.randomUUID(),new WealthFacts(new WealthFacts.Balance(name,"가상 기관",d.minusYears(1),d,BigDecimal.ONE,BigDecimal.ZERO,"KRW"),new WealthFacts.Changes(name,"가상 기관",d.minusYears(1),d,d,List.of()),new WealthFacts.Balance(name,"가상 기관",d,d,BigDecimal.ONE,BigDecimal.ZERO,"KRW")));}
        assertEquals(revision,service.status(customer.getId()).revision());assertThrows(ResponseStatusException.class,()->decide(r,true,signed));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,signed.challengeId()));
        var old=service.dossier(checker.user().getId(),checker.sid(),r.payload().id(),0);assertEquals(count,old.dossier().items().size());assertFalse(old.dossier().items().stream().anyMatch(x->x.path("kind").asText().equals(kind)));var fresh=referral();assertNotEquals(r.payload().dossier().digest(),fresh.payload().dossier().digest());decide(fresh,true,proof(checker,fresh,true));assertEquals("REFERRED",service.status(customer.getId()).state());blocked();
    }
    @Test void allPagesAreRequiredAndRepeatingFirstPageCannotCompleteInspection() throws Exception {
        reviewed();seedExpiredRequests(28);var r=referral();assertEquals(2,r.payload().dossier().pageCount());
        assertEquals(25,inspect(r,0).dossier().items().size());
        // 첫 페이지를 반복 제공해도 서로 다른 모든 페이지를 제공한 것으로 세지 않는다.
        inspect(r,0);assertThrows(ResponseStatusException.class,()->options(r));
        assertEquals(3,inspect(r,1).dossier().items().size());assertNotNull(options(r));
        decide(r,true,proof(checker,r,true));blocked();
    }
    @Test void frozenHistorySurvivesNewRequestButOldSignatureCannotDecide() throws Exception {
        reviewed();var r=referral();var signed=proof(checker,r,true);evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());
        assertThrows(ResponseStatusException.class,()->decide(r,true,signed));assertEquals(0,service.dossier(checker.user().getId(),checker.sid(),r.payload().id(),0).dossier().items().size());blocked();
    }
    @Test void uploadChangesCurrentCaseAndCannotReuseOldInspection() throws Exception {reviewed();var r=referral();var proof=proof(checker,r,true);file(EvidenceService.Purpose.SOURCE_OF_FUNDS);assertThrows(ResponseStatusException.class,()->decide(r,true,proof));}
    @Test void historicalGetDoesNotCountAsIndependentInspection() throws Exception {reviewed();var r=referral();service.dossier(checker.user().getId(),checker.sid(),r.payload().id(),0);assertThrows(ResponseStatusException.class,()->options(r));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossier_views WHERE report_id=?",Long.class,r.payload().id()));}
    @Test void anotherPersonInspectionCannotAuthorizeCurrentApprover() throws Exception {reviewed();var r=referral();var other=account(UserRole.APPROVER);service.inspect(other.user().getId(),other.sid(),r.payload().id(),0);assertThrows(ResponseStatusException.class,()->options(r));}
    @Test void invalidPageNeverLeavesViewReceipt() throws Exception {reviewed();var r=referral();for(int page:List.of(-1,1,Integer.MAX_VALUE))assertThrows(ResponseStatusException.class,()->inspect(r,page));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossier_views WHERE report_id=?",Long.class,r.payload().id()));}
    @Test void inspectionAuditFailureRollsBackReceiptAndDoesNotReturnBundle() throws Exception {reviewed();var r=referral();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_DOSSIER_INSPECTED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->inspect(r,0));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossier_views WHERE report_id=?",Long.class,r.payload().id()));reset(audit);assertThrows(ResponseStatusException.class,()->options(r));}
    @Test void proposalAuditFailureRollsBackSnapshotReportAndBindingTogether() throws Exception {reviewed();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_PROPOSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::referral);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossiers WHERE user_id=?",Long.class,customer.getId()));assertTrue(service.reports(customer.getId(),0).isEmpty());}
    @Test void historicalReadAuditFailureReturnsNoMetadata() throws Exception {reviewed();var r=referral();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_DOSSIER_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->service.dossier(checker.user().getId(),checker.sid(),r.payload().id(),0));}
    @Test void staleSessionMfaAndSamePersonCannotInspect() throws Exception {reviewed();var r=referral();jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",checker.sid());assertThrows(ResponseStatusException.class,()->inspect(r,0));var other=account(UserRole.APPROVER);var u=users.findById(other.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->service.inspect(u.getId(),other.sid(),r.payload().id(),0));}
    @Test void completedDossierAndViewsRejectUpdateDeleteAndTruncate() throws Exception {reviewed();var r=referral();inspect(r,0);for(String table:List.of("edd_case_dossiers","edd_case_dossier_views"))for(String sql:List.of("UPDATE "+table+" SET id=id","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));}
    @Test void auditorReadsHistoricalMetadataButCannotCreateApprovalReceipt() throws Exception {reviewed();var r=referral();var a=account(UserRole.AUDITOR);String path="/api/admin/edd/reports/"+r.payload().id();mvc.perform(get(path+"/dossier").header("Authorization","Bearer "+a.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post(path+"/inspect").header("Authorization","Bearer "+a.access())).andExpect(status().isForbidden());mvc.perform(get(path+"/dossier").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());}
    @Test void oldReportPolicyCanBeReadButCannotInspectOrExecute() throws Exception {reviewed();var r=referral();UUID id=UUID.randomUUID();var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(r.payload());tree.put("id",id.toString());tree.remove("reviewPolicy");tree.remove("dossier");String digest=verifier.digest(tree);jdbc.update("INSERT INTO edd_reports(id,case_id,user_id,requester_id,request_key,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,r.payload().caseId(),customer.getId(),maker.user().getId(),UUID.randomUUID(),json.writeValueAsString(tree),digest,java.sql.Timestamp.from(r.payload().expiresAt()));assertTrue(service.reports(customer.getId(),0).stream().anyMatch(x->x.payload().id().equals(id)));assertThrows(ResponseStatusException.class,()->service.inspect(checker.user().getId(),checker.sid(),id,0));}
    @Test void componentCannotCaptureWithoutBusinessTransaction() {assertThrows(IllegalStateException.class,()->dossiers.capture(UUID.randomUUID(),customer.getId()));}
    @Test void receiptOlderThanFiveMinutesCannotReplaceFreshInspection() throws Exception {
        reviewed();var r=referral();var user=users.findById(checker.user().getId()).orElseThrow();
        jdbc.update("INSERT INTO edd_case_dossier_views(id,report_id,dossier_id,actor_id,actor_person,actor_version,session_id,page,digest,viewed_at) VALUES(?,?,?,?,?,?,?,0,?,clock_timestamp()-interval '6 minutes')",UUID.randomUUID(),r.payload().id(),r.payload().dossier().id(),user.getId(),user.getOperatorPersonId(),user.getVersion(),checker.sid(),r.payload().dossier().digest());
        assertThrows(ResponseStatusException.class,()->options(r));inspect(r,0);assertNotNull(options(r));
    }
    @Test void snapshotDigestDoesNotDependOnDatabaseSessionTimeZone() throws Exception {
        reviewed();file(EvidenceService.Purpose.SOURCE_OF_FUNDS);var r=referral();
        new TransactionTemplate(manager).executeWithoutResult(t->{
            users.lockById(customer.getId());jdbc.execute("SET LOCAL TIME ZONE 'Asia/Seoul'");
            assertEquals(r.payload().dossier().digest(),dossiers.liveDigest(r.payload().caseId(),customer.getId()));
            jdbc.execute("SET LOCAL TIME ZONE 'UTC'");assertEquals(r.payload().dossier().digest(),dossiers.liveDigest(r.payload().caseId(),customer.getId()));
        });
    }

    @Test void approvedNegativeAddressObservationIsCarriedIntoReferralWithoutResolvingRisk() throws Exception {
        reviewed();UUID id=file(EvidenceService.Purpose.RESIDENTIAL_ADDRESS),mv=view(maker,id);var c=addresses.context(maker.user().getId(),maker.sid(),id,mv);
        var observed=new AddressFacts(AddressFacts.DocumentType.UTILITY_BILL,"다른 명의","다른 주소","가상 기관",LocalDate.now(ZoneId.of("Asia/Seoul")));
        var a=addresses.propose(maker.user().getId(),maker.sid(),id,mv,c.assessmentId(),c.caseRevision(),UUID.randomUUID(),observed);UUID cv=view(checker,id);
        var o=addresses.options(checker.user().getId(),checker.sid(),a.payload().id(),a.payloadHash(),cv,true,observed).authentication();var signed=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
        addresses.decide(checker.user().getId(),checker.sid(),a.payload().id(),a.payloadHash(),cv,true,observed,signed);
        var r=referral();var item=inspect(r,0).dossier().items().stream().filter(x->x.path("kind").asText().equals("ADDRESS")).findFirst().orElseThrow();
        assertEquals("APPROVED",item.path("status").asText());assertEquals(2,item.path("reasons").size());assertFalse(json.writeValueAsString(item).contains("다른 명의"));
        decide(r,true,proof(checker,r,true));assertEquals("REFERRED",service.status(customer.getId()).state());blocked();
    }
    @Test void pendingDuplicateProposalRollsBackUnreferencedSnapshot() throws Exception {
        reviewed();referral();assertThrows(ResponseStatusException.class,this::referral);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_case_dossiers WHERE user_id=?",Long.class,customer.getId()));
    }

}
