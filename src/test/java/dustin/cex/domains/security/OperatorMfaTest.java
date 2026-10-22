package dustin.cex.domains.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;

/** 실행 소유 PostgreSQL·실제 보안 필터·실제 공개키 검증으로 운영 인증의 우회 경로를 검사한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorMfaTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository tokens;@Autowired JwtService jwt;
    @Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired OperatorMfaService mfa;
    @MockitoSpyBean AuditService audit;
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    String hash;User user;SigninResponse pending;UUID sid;SoftwareAuthenticator key;
    final String enrollment="isolated-enrollment-256-bit-test-secret-at-least-43-characters";
    @BeforeAll void password() {hash=passwords.hash("security-test-password");}
    @BeforeEach void setup() throws Exception {
        user=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash)
            .role(UserRole.OPERATOR).operatorPersonId(UUID.randomUUID()).build());
        pending=auth.signin(new SigninRequest(user.getEmail(),"security-test-password"));sid=sid(pending.getAccessToken());
        jdbc.update("INSERT INTO operator_enrollment(user_id,token_hash,expires_at) VALUES(?,?,clock_timestamp()+interval '10 minutes')",user.getId(),OperatorMfaService.sha256Hex(enrollment));
        key=new SoftwareAuthenticator();
    }
    UUID sid(String access) {return UUID.fromString(jwt.verifyAccessToken(access).get("sid",String.class));}
    OperatorMfaService.Options register() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"http://localhost:3003",true));return options;
    }
    OperatorMfaService.Verified verified() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        return mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",true,1,null));
    }
    @Test void passwordSessionCannotUseOperatorApisOrRefresh() throws Exception {
        assertTrue(pending.isMfaRequired());
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+pending.getAccessToken())).andExpect(status().isForbidden());
        assertThrows(RuntimeException.class,()->auth.refreshAccessToken(pending.getRefreshToken()));
        assertNull(tokens.findBySessionId(sid).orElseThrow().getMfaVerifiedAt());
    }
    @Test void actualRegistrationAndSignatureRotateSessionThenOpenAdmin() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        assertEquals("required",options.publicKey().path("authenticatorSelection").path("userVerification").asText());
        mvc.perform(post("/api/auth/operator-mfa/register").header("Authorization","Bearer "+pending.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("challengeId",options.challengeId(),"publicKey",key.registration(options,"http://localhost:3003",true)))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+pending.getAccessToken())).andExpect(status().isForbidden());
        var challenge=mfa.authenticationOptions(user.getId(),sid);
        assertEquals("required",challenge.publicKey().path("userVerification").asText());
        var response=mvc.perform(post("/api/auth/operator-mfa/authenticate").header("Authorization","Bearer "+pending.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("challengeId",challenge.challengeId(),"publicKey",key.assertion(challenge,"http://localhost:3003",true,1,null)))))
            .andExpect(status().isOk()).andReturn();
        var value=json.readTree(response.getResponse().getContentAsString());String access=value.path("accessToken").asText();
        assertNotEquals(sid,sid(access));assertTrue(tokens.findBySessionId(sid).orElseThrow().getRevoked());
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+pending.getAccessToken())).andExpect(status().isUnauthorized());
        assertFalse(value.path("refreshToken").asText().isEmpty());
        String used=jdbc.queryForObject("SELECT detail FROM security_audit WHERE actor_id=? AND action='MFA_AUTHENTICATE' AND outcome='SUCCESS'",String.class,user.getId());
        assertEquals("USER_VERIFIED:sha256="+OperatorMfaService.sha256Hex(key.id()),used);
    }
    @Test void enrollmentNeedsSeparateUnexpiredOneTimeGrant() throws Exception {
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,"x".repeat(43)));
        var first=mfa.registrationOptions(user.getId(),sid,enrollment);
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_mfa_challenges WHERE id=?",Boolean.class,first.challengeId()));
    }
    @Test void expiredEnrollmentGrantCannotRegister() {
        jdbc.update("UPDATE operator_enrollment SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=?",user.getId());
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
    }
    @Test void registrationRejectsWrongOriginAndMissingUserVerification() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"https://evil.example",true)));
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"http://localhost:3003",false)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
    }
    @Test void authenticationRejectsWrongOriginAndMissingUserVerification() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"https://evil.example",true,1,null)));
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",false,1,null)));
        assertNull(tokens.findBySessionId(sid).orElseThrow().getMfaVerifiedAt());
    }
    @Test void wrongKeySignatureAndUserHandleAreRejected() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);var other=new SoftwareAuthenticator();
        var bad=other.assertion(options,"http://localhost:3003",true,1,null).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)bad).put("id",key.id()).put("rawId",key.id());
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),bad));
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",true,1,"d3Jvbmc")));
    }
    @Test void changedChallengeAndRpHashAreRejected() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        var changed=new OperatorMfaService.Options(options.challengeId(),options.publicKey().deepCopy());
        ((tools.jackson.databind.node.ObjectNode)changed.publicKey()).put("challenge","Y2hhbmdlZA");
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(changed,"http://localhost:3003",true,1,null)));
        ((tools.jackson.databind.node.ObjectNode)changed.publicKey()).put("challenge",options.publicKey().path("challenge").asText()).put("rpId","evil.example");
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(changed,"http://localhost:3003",true,1,null)));
    }
    @Test void expiredOrWrongPurposeChallengeAndOldSessionAreRejected() throws Exception {
        var registration=register();
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,registration.challengeId(),json.createObjectNode()));
        var options=mfa.authenticationOptions(user.getId(),sid);
        jdbc.update("UPDATE operator_mfa_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",options.challengeId());
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",true,1,null)));
        auth.signin(new SigninRequest(user.getEmail(),"security-test-password"));
        assertThrows(ResponseStatusException.class,()->mfa.authenticationOptions(user.getId(),sid));
    }
    @Test void concurrentSignatureConsumptionHasOneWinner() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);var proof=key.assertion(options,"http://localhost:3003",true,1,null);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try {mfa.authenticate(user.getId(),sid,options.challengeId(),proof);return true;}catch(ResponseStatusException e){return false;}};
            var first=pool.submit(work);var second=pool.submit(work);gate.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='MFA_AUTHENTICATE' AND outcome='SUCCESS'",Long.class,user.getId()));
    }
    @Test void auditFailureRollsBackCredentialCounterChallengeAndSession() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(user.getId()),eq("MFA_AUTHENTICATE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",true,1,null)));
        assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
        assertEquals(0,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_mfa_challenges WHERE id=?",Boolean.class,options.challengeId()));
    }
    @Test void refreshPreservesMfaTimeAndCannotExtendTwelveHourBoundary() throws Exception {
        var verified=verified();var old=tokens.findBySessionId(sid(verified.accessToken())).orElseThrow();
        var rotated=auth.refreshAccessToken(verified.refreshToken());var next=tokens.findBySessionId(sid(rotated.getAccessToken())).orElseThrow();
        assertEquals(old.getMfaVerifiedAt(),next.getMfaVerifiedAt());assertEquals(old.getExpiresAt(),next.getExpiresAt());
        next.setMfaVerifiedAt(LocalDateTime.now().minusHours(13));tokens.saveAndFlush(next);
        assertThrows(RuntimeException.class,()->auth.refreshAccessToken(rotated.getRefreshToken()));
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+rotated.getAccessToken())).andExpect(status().isForbidden());
    }
    @Test void staleMfaCanReadButCannotRequestOperatorChange() throws Exception {
        var verified=verified();var session=tokens.findBySessionId(sid(verified.accessToken())).orElseThrow();
        session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+verified.accessToken())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/trading-restrictions").header("Authorization","Bearer "+verified.accessToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void failedVerificationIsRateLimitedAndAuditedWithoutProofData() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        for(int i=0;i<5;i++) assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",false,1,null)));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->mfa.authenticationOptions(user.getId(),sid)).getStatusCode().value());
        String details=jdbc.queryForList("SELECT detail FROM security_audit WHERE actor_id=?",user.getId()).toString();
        assertFalse(details.contains(enrollment));assertFalse(details.contains("signature"));assertFalse(details.contains(pending.getAccessToken()));
    }
    @Test void customersAndDisabledOperatorsCannotEnroll() throws Exception {
        user.setRole(UserRole.CUSTOMER);user=users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
        user.setRole(UserRole.OPERATOR);user.setEnabled(false);user=users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
    }
    @Test void malformedProofIsDeniedWithoutServerError() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        mvc.perform(post("/api/auth/operator-mfa/register").header("Authorization","Bearer "+pending.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("challengeId",options.challengeId(),"publicKey",Map.of())))).andExpect(status().isForbidden());
    }
    @Test void initialRegistrationCannotBeRepeatedOrSilentlyReplaceExistingKey() throws Exception {
        var registration=register();
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,registration.challengeId(),key.registration(registration,"http://localhost:3003",true)));
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
    }
    @Test void backupKeyNeedsFreshMfaAndCanAuthenticateAfterFreshSignin() throws Exception {
        var verified=verified();UUID full=sid(verified.accessToken());var second=new SoftwareAuthenticator();
        var options=mfa.registrationOptions(user.getId(),full,null);mfa.register(user.getId(),full,options.challengeId(),second.registration(options,"http://localhost:3003",true));
        var login=auth.signin(new SigninRequest(user.getEmail(),"security-test-password"));UUID loginSid=sid(login.getAccessToken());
        var challenge=mfa.authenticationOptions(user.getId(),loginSid);
        assertNotNull(mfa.authenticate(user.getId(),loginSid,challenge.challengeId(),second.assertion(challenge,"http://localhost:3003",true,1,null)));
    }
    @Test void counterRollbackIsRejectedAcrossDifferentChallenges() throws Exception {
        var verified=verified();UUID full=sid(verified.accessToken());var next=mfa.authenticationOptions(user.getId(),full);
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(user.getId(),full,next.challengeId(),key.assertion(next,"http://localhost:3003",true,1,null)));
        assertEquals(1,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
    }
    @Test void zeroCounterAuthenticatorCanSignTwoDistinctChallenges() throws Exception {
        register();var first=mfa.authenticationOptions(user.getId(),sid);
        var verified=mfa.authenticate(user.getId(),sid,first.challengeId(),key.assertion(first,"http://localhost:3003",true,0,null));
        UUID full=sid(verified.accessToken());var next=mfa.authenticationOptions(user.getId(),full);
        assertNotNull(mfa.authenticate(user.getId(),full,next.challengeId(),key.assertion(next,"http://localhost:3003",true,0,null)));
    }
    @Test void anotherSessionCannotUseOwnersChallenge() throws Exception {
        register();var options=mfa.authenticationOptions(user.getId(),sid);
        var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash)
            .role(UserRole.OPERATOR).operatorPersonId(UUID.randomUUID()).build());
        var login=auth.signin(new SigninRequest(other.getEmail(),"security-test-password"));
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(other.getId(),sid(login.getAccessToken()),options.challengeId(),key.assertion(options,"http://localhost:3003",true,1,null)));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_mfa_challenges WHERE id=?",Boolean.class,options.challengeId()));
    }
    @Test void registrationAuditFailureRollsBackKeyAndChallenge() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        doThrow(new IllegalStateException("등록 감사 장애 주입")).when(audit).record(eq(user.getId()),eq("MFA_REGISTER"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"http://localhost:3003",true)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_mfa_challenges WHERE id=?",Boolean.class,options.challengeId()));
    }
    @Test void registrationOptionsAuditFailureDoesNotConsumeGrant() {
        doThrow(new IllegalStateException("챌린지 감사 장애 주입")).when(audit).record(eq(user.getId()),eq("MFA_CHALLENGE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->mfa.registrationOptions(user.getId(),sid,enrollment));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_enrollment WHERE user_id=?",Boolean.class,user.getId()));
    }
    @Test void backupRegistrationIsLimitedToFiveKeysAndNeedsFreshVerification() throws Exception {
        var verified=verified();UUID full=sid(verified.accessToken());
        for(int i=0;i<4;i++) {
            var backup=new SoftwareAuthenticator();var options=mfa.registrationOptions(user.getId(),full,null);
            mfa.register(user.getId(),full,options.challengeId(),backup.registration(options,"http://localhost:3003",true));
        }
        assertEquals(5,mfa.authenticationOptions(user.getId(),full).publicKey().path("allowCredentials").size());
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),full,null));
        var session=tokens.findBySessionId(full).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);
        assertThrows(ResponseStatusException.class,()->mfa.registrationOptions(user.getId(),full,null));
    }
    @Test void pendingSessionExpiresEvenWhileAccessJwtStillHasTime() throws Exception {
        var session=tokens.findBySessionId(sid).orElseThrow();session.setExpiresAt(LocalDateTime.now().minusSeconds(1));tokens.saveAndFlush(session);
        mvc.perform(post("/api/auth/operator-mfa/authenticate/options").header("Authorization","Bearer "+pending.getAccessToken())).andExpect(status().isUnauthorized());
    }
    @Test void optionsEndpointsReturnBrowserContractWithoutLoginPage() throws Exception {
        var result=mvc.perform(post("/api/auth/operator-mfa/register/options").header("Authorization","Bearer "+pending.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enrollmentToken",enrollment))))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Location")).andReturn();
        var value=json.readTree(result.getResponse().getContentAsString());var options=new OperatorMfaService.Options(UUID.fromString(value.path("challengeId").asText()),value.path("publicKey"));
        mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"http://localhost:3003",true));
        mvc.perform(post("/api/auth/operator-mfa/authenticate/options").header("Authorization","Bearer "+pending.getAccessToken()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.publicKey.userVerification").value("required"));
    }
    @Test void crossOriginIframeAndMismatchedCredentialIdAreRejected() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        var proof=key.registration(options,"http://localhost:3003",true).deepCopy();
        var response=(tools.jackson.databind.node.ObjectNode)proof.path("credential").path("response");
        var client=(tools.jackson.databind.node.ObjectNode)json.readTree(Base64.getUrlDecoder().decode(response.path("clientDataJSON").asText()));
        client.put("crossOrigin",true);response.put("clientDataJSON",SoftwareAuthenticator.encoded(json.writeValueAsBytes(client)));
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,options.challengeId(),proof));
        var mismatched=key.registration(options,"http://localhost:3003",true).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)mismatched.path("credential")).put("id","ZGlmZmVyZW50");
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,options.challengeId(),mismatched));
    }

    @Test void realHttpServerCompletesPasswordRegistrationAndSignatureFlow() throws Exception {
        // 임의 포트의 실제 내장 서버를 호출한다. MockMvc 검증과 네트워크 API 검증을 구분한다.
        var login=call("POST","/api/auth/signin",null,Map.of("email",user.getEmail(),"password","security-test-password"));
        assertEquals(200,login.statusCode());var signed=json.readTree(login.body());String pendingToken=signed.path("accessToken").asText();
        assertTrue(signed.path("mfaRequired").asBoolean());
        assertEquals(403,call("GET","/api/admin/audit",pendingToken,null).statusCode());
        var registration=call("POST","/api/auth/operator-mfa/register/options",pendingToken,Map.of("enrollmentToken",enrollment));
        assertEquals(200,registration.statusCode());var value=json.readTree(registration.body());
        var options=new OperatorMfaService.Options(UUID.fromString(value.path("challengeId").asText()),value.path("publicKey"));
        assertEquals(200,call("POST","/api/auth/operator-mfa/register",pendingToken,Map.of("challengeId",options.challengeId(),"publicKey",key.registration(options,"http://localhost:3003",true))).statusCode());
        var challenge=call("POST","/api/auth/operator-mfa/authenticate/options",pendingToken,Map.of());assertEquals(200,challenge.statusCode());
        value=json.readTree(challenge.body());var assertionOptions=new OperatorMfaService.Options(UUID.fromString(value.path("challengeId").asText()),value.path("publicKey"));
        var completed=call("POST","/api/auth/operator-mfa/authenticate",pendingToken,Map.of("challengeId",assertionOptions.challengeId(),"publicKey",key.assertion(assertionOptions,"http://localhost:3003",true,1,null)));
        assertEquals(200,completed.statusCode());String verified=json.readTree(completed.body()).path("accessToken").asText();
        assertEquals(200,call("GET","/api/admin/audit",verified,null).statusCode());
        assertEquals(401,call("GET","/api/auth/me",pendingToken,null).statusCode());
    }
    java.net.http.HttpResponse<String> call(String method,String path,String access,Object body) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(access!=null) request.header("Authorization","Bearer "+access);
        if(body==null) request.method(method,java.net.http.HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type","application/json").method(method,java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try(var client=java.net.http.HttpClient.newHttpClient()) {return client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());}
    }

    @Test void rawCredentialIdMustMatchAttestedIdAndRollbackUntrustedRecord() throws Exception {
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);
        var proof=key.registration(options,"http://localhost:3003",true).deepCopy();
        var credential=(tools.jackson.databind.node.ObjectNode)proof.path("credential");
        String different=SoftwareAuthenticator.encoded(new byte[32]);credential.put("id",different).put("rawId",different);
        assertThrows(ResponseStatusException.class,()->mfa.register(user.getId(),sid,options.challengeId(),proof));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,different));
    }

}
