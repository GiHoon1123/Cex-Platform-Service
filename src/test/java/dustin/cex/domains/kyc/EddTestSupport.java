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
abstract class EddTestSupport {
    static final UUID APP=UUID.fromString("11111111-2222-3333-4444-555555555555"),WORKFLOW=UUID.fromString("66666666-7777-8888-9999-000000000000");
    static final String SECRET="isolated-edd-webhook-secret-at-least-32-bytes";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("customer-mail.dispatch-enabled",()->false);
        r.add("edd.evidence-key",()->"AwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
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
        for(int page=0;page<r.payload().dossier().pageCount();page++)service.inspect(a.user().getId(),a.sid(),r.payload().id(),page);
        var o=service.options(a.user().getId(),a.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(o.challengeId(),a.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    EddService.Report decide(EddService.Report r,boolean approve,OperatorMfaService.AuthorizationProof p) {return service.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,p);}
    void allowed() {new TransactionTemplate(manager).executeWithoutResult(s->trading.requireNewOrder(customer.getId()));}
    void blocked() {assertThrows(ResponseStatusException.class,this::allowed);}
}
