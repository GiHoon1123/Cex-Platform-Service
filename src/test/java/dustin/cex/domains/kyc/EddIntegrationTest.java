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

/** 실제 신고·웹훅 HMAC·소프트웨어 보안키 서명·PG 잠금으로 검토 사건부터 독립 결정과 주문 조건을 검증한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EddIntegrationTest {
    static final UUID APP=UUID.fromString("11111111-2222-3333-4444-555555555555"),WORKFLOW=UUID.fromString("66666666-7777-8888-9999-000000000000");
    static final String SECRET="isolated-edd-webhook-secret-at-least-32-bytes";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("didit.api-key",()->"isolated-api-key-at-least-16");r.add("didit.webhook-secret",()->SECRET);r.add("didit.application-id",APP::toString);
        r.add("didit.workflow-id",WORKFLOW::toString);r.add("didit.workflow-version",()->4);r.add("didit.environment",()->"live");
        r.add("didit.callback",()->"https://exchange.example.test/kyc/return");r.add("didit.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired EddService service;@Autowired CddService cdd;@Autowired KycService kyc;@Autowired DiditWebhookVerifier verifier;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository tokens;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired JwtService jwt;@Autowired OperatorMfaService mfa;
    @Autowired TradingAccessPolicy trading;@Autowired PlatformTransactionManager manager;@Autowired OperatorRoleService roles;@Autowired MockMvc mvc;
    @MockitoSpyBean AuditService audit;
    @MockitoBean DiditClient client;
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    record Account(User user,SoftwareAuthenticator key,UUID sid,String access) {}
    Account maker,checker;User customer;UUID sid;String customerAccess,hash;
    final Map<UUID,UUID> created=new ConcurrentHashMap<>();final AtomicLong counter=new AtomicLong(1);
    @BeforeAll void password() {hash=passwords.hash("security-test-password");}
    @BeforeEach void setup() throws Exception {
        created.clear();counter.set(1);maker=account(UserRole.OPERATOR);checker=account(UserRole.APPROVER);
        customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
        // 유료 세션 생성 HTTP만 대역으로 둔다. 반환된 실제 시도에 대한 HMAC 검증과 판정·DB·서명 검증은 제품 코드다.
        when(client.create(any())).thenAnswer(call->{UUID id=call.getArgument(0);UUID session=created.computeIfAbsent(id,x->UUID.randomUUID());
            return json.valueToTree(Map.of("session_id",session,"vendor_data",id.toString(),"workflow_id",WORKFLOW,"workflow_version",4,"url","https://verify.didit.me/session/"+session));});
    }
    Account account(UserRole role) throws Exception {
        var u=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).role(role).operatorPersonId(UUID.randomUUID()).operatorAccessExpiresAt(Instant.now().plusSeconds(86400)).build());
        String enrollment="isolated-enrollment-256-bit-test-secret-at-least-43-characters";
        jdbc.update("INSERT INTO operator_enrollment(user_id,token_hash,expires_at) VALUES(?,?,clock_timestamp()+interval '10 minutes')",u.getId(),sha(enrollment));
        var login=auth.signin(new SigninRequest(u.getEmail(),"security-test-password"));UUID session=UUID.fromString(jwt.verifyAccessToken(login.getAccessToken()).get("sid",String.class));
        var key=new SoftwareAuthenticator();var registration=mfa.registrationOptions(u.getId(),session,enrollment);
        mfa.register(u.getId(),session,registration.challengeId(),key.registration(registration,"http://localhost:3003",true));
        var option=mfa.authenticationOptions(u.getId(),session);var verified=mfa.authenticate(u.getId(),session,option.challengeId(),key.assertion(option,"http://localhost:3003",true,1,null));
        return new Account(u,key,UUID.fromString(jwt.verifyAccessToken(verified.accessToken()).get("sid",String.class)),verified.accessToken());
    }
    String sha(String text) {try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    CustomerDeclaration declaration(CustomerDeclaration.Pep pep) {return new CustomerDeclaration("가상 심사 고객 "+customer.getId(),LocalDate.of(1990,1,1),"KOR","KOR","가상 주소",
        CustomerDeclaration.Occupation.EMPLOYED,CustomerDeclaration.Purpose.PERSONAL_INVESTMENT,CustomerDeclaration.SourceOfFunds.SALARY,java.math.BigDecimal.ONE,true,pep);}
    UUID verify(boolean mismatch) throws Exception {return verify(mismatch,false);}
    UUID verify(boolean mismatch,boolean amlHit) throws Exception {
        UUID id=kyc.start(customer.getId(),sid,UUID.randomUUID()).status().attemptId();long now=Instant.now().getEpochSecond();
        String name=declaration(CustomerDeclaration.Pep.NO).fullName()+(mismatch?" 불일치":"");
        var decision=Map.of("session_id",created.get(id),"status","Approved","id_verifications",List.of(Map.of("status","Approved","verification_method","document","assurance","documentary","full_name",name,"date_of_birth","1990-01-01","nationality","KOR")),
            "liveness_checks",List.of(Map.of("status","Approved")),"face_matches",List.of(Map.of("status","Approved")),"aml_screenings",List.of(Map.of("status","Approved","total_hits",0,"entity_type","person","score",0,"hits",List.of(),"warnings",List.of(),"screened_data",Map.of("full_name",name,"date_of_birth","1990-01-01","nationality","KR"))));
        var result=new HashMap<String,Object>(decision);
        if(amlHit) {var screening=new HashMap<String,Object>((Map<String,Object>)((List<?>)decision.get("aml_screenings")).getFirst());screening.put("total_hits",1);result.put("aml_screenings",List.of(screening));}
        var event=new HashMap<String,Object>();event.put("event_id",UUID.randomUUID());event.put("vendor_data",id.toString());event.put("session_id",created.get(id));
        event.put("application_id",APP);event.put("workflow_id",WORKFLOW);event.put("workflow_version",4);event.put("environment","live");event.put("webhook_type","status.updated");
        event.put("status","Approved");event.put("decision",result);event.put("timestamp",now);event.put("created_at",now-1);
        byte[] raw=json.writeValueAsBytes(event);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(verifier.canonical(verifier.json.readTree(raw)).getBytes(StandardCharsets.UTF_8)));
        kyc.webhook(raw,signature,Long.toString(now));return id;
    }
    void reviewed() throws Exception {verify(true);assertEquals("REVIEW_REQUIRED",cdd.status(customer.getId()).outcome());blocked();}
    void corrected() throws Exception {cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));verify(false);assertEquals("STANDARD",cdd.status(customer.getId()).outcome());blocked();}
    EddService.Report propose(EddService.Finding finding) {return service.propose(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),UUID.randomUUID(),finding,
        finding==EddService.Finding.CORRECTED_SIGNED_IDENTITY?Instant.now().plusSeconds(3600):null);}
    EddService.Report closure() throws Exception {reviewed();corrected();return propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY);}
    OperatorMfaService.AuthorizationProof proof(Account a,EddService.Report r,boolean approve) throws Exception {
        var o=service.options(a.user().getId(),a.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(o.challengeId(),a.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    EddService.Report decide(EddService.Report r,boolean approve,OperatorMfaService.AuthorizationProof p) {return service.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,p);}
    void allowed() {new TransactionTemplate(manager).executeWithoutResult(s->trading.requireNewOrder(customer.getId()));}
    void blocked() {assertThrows(ResponseStatusException.class,this::allowed);}
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
