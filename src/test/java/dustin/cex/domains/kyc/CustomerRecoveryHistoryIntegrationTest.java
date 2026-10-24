package dustin.cex.domains.kyc;

import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 불변 복구 원본을 실제 고객 경계에서 읽고 같은 시각의 페이지 누락·비밀 노출·감사 실패를 검사한다. */
class CustomerRecoveryHistoryIntegrationTest extends CustomerRecoveryTestSupport {
    @Autowired CustomerRecoveryHistory history;
    void seed(int count,Instant at){
        // 이력 순회만을 위한 격리 원본이다. 기존 행 수정/트리거 해제 없이 새 사실을 삽입한다.
        // 인증용 코드를 시험하는 fixture가 아니므로 소금 포함 해시 하나를 이력 행에 공유한다.
        String hash=passwords.hash(token());
        for(int i=0;i<count;i++){
            UUID code=UUID.randomUUID();jdbc.update("INSERT INTO customer_recovery_codes(id,user_id,code_hash) VALUES(?,?,?)",code,customer.getId(),hash);
            jdbc.update("INSERT INTO customer_recovery_events(id,user_id,session_id,kind,code_id,created_at) VALUES(?,?,?,'CODE_REPLACED',?,?)",UUID.randomUUID(),customer.getId(),UUID.randomUUID(),code,Timestamp.from(at));
        }
    }
    @Test void emptyHistoryIsAvailableWithoutCreatingRecoveryState(){
        var page=history.own(customer.getId(),sid,null);assertTrue(page.items().isEmpty());assertNull(page.next());assertEquals(0,events());
    }
    @Test void confirmedRecoveryIncludesLinkedKeyCountAfterNewKeyLogin() throws Exception {
        var old=initial();var code=issue(old);pending();String retired=customerAccess;var next=new SoftwareAuthenticator();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());recovery.recover(customer.getId(),sid,registration(o,next));
        mvc.perform(get("/api/auth/customer-recovery/changes").header("Authorization","Bearer "+retired)).andExpect(status().isUnauthorized());login(next);
        var page=history.own(customer.getId(),sid,null);assertEquals(2,page.items().size());assertEquals("MFA_RECOVERED",page.items().getFirst().kind());assertEquals(2,page.items().getFirst().keysChanged());assertEquals("CODE_REPLACED",page.items().getLast().kind());assertEquals(0,page.items().getLast().keysChanged());assertNull(page.next());
    }
    @Test void sameTimestampPagesHaveNoDuplicatesOrOmissions() throws Exception {
        var key=initial();issue(key);seed(29,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        var first=history.own(customer.getId(),sid,null);assertEquals(25,first.items().size());assertNotNull(first.next());var second=history.own(customer.getId(),sid,first.next());assertEquals(5,second.items().size());assertNull(second.next());Set<UUID> ids=new HashSet<>();first.items().forEach(c->assertTrue(ids.add(c.id())));second.items().forEach(c->assertTrue(ids.add(c.id())));assertEquals(30,ids.size());
    }
    @Test void exactlyOnePageHasNoEmptyNextRequest() throws Exception {
        var key=initial();issue(key);seed(24,Instant.now().minusSeconds(1));var page=history.own(customer.getId(),sid,null);assertEquals(25,page.items().size());assertNull(page.next());
    }
    @Test void invalidCursorIsRejectedAndAudited() throws Exception {
        initial();for(var cursor:List.of(new CustomerRecoveryHistory.Cursor(null,UUID.randomUUID()),new CustomerRecoveryHistory.Cursor(Instant.now(),null),new CustomerRecoveryHistory.Cursor(Instant.now().plusSeconds(600),UUID.randomUUID())))assertEquals(400,assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),sid,cursor)).getStatusCode().value());
        mvc.perform(get("/api/auth/customer-recovery/changes").header("Authorization","Bearer "+customerAccess).param("afterId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_RECOVERY_HISTORY' AND outcome='DENIED'",Long.class,customer.getId()));
    }
    @Test void pendingForeignAndOperatorSessionsCannotReadHistory() throws Exception {
        var key=initial();issue(key);assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),maker.sid(),null));assertThrows(ResponseStatusException.class,()->history.own(maker.user().getId(),maker.sid(),null));
        mvc.perform(get("/api/auth/customer-recovery/changes").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());pending();assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),sid,null));mvc.perform(get("/api/auth/customer-recovery/changes").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void auditFailurePreventsSuccessfulHistoryResponse() throws Exception {
        initial();doThrow(new IllegalStateException("격리 조회 감사 실패")).when(audit).record(any(),eq("CUSTOMER_RECOVERY_HISTORY_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->history.own(customer.getId(),sid,null));
    }
    @Test void httpReturnsOnlyOwnPublicFactsWithoutRequiringFreshSignature() throws Exception {
        var key=initial();var code=issue(key);var session=tokens.findBySessionId(sid).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);
        String body=mvc.perform(get("/api/auth/customer-recovery/changes").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].kind").value("CODE_REPLACED")).andReturn().getResponse().getContentAsString();
        for(String secret:List.of(code.recoveryCode(),password,customer.getEmail(),key.id(),sid.toString(),"codeHash","code_hash","sessionId","publicKey","challengeId","userId"))assertFalse(body.contains(secret));
    }
    @Test void anotherCustomersFactsAreNeverIncluded(){
        var other=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@other-customer.invalid").passwordHash(passwords.hash(token())).username("다른 격리 고객").build());
        UUID code=UUID.randomUUID();jdbc.update("INSERT INTO customer_recovery_codes(id,user_id,code_hash) VALUES(?,?,?)",code,other.getId(),passwords.hash(token()));jdbc.update("INSERT INTO customer_recovery_events(id,user_id,session_id,kind,code_id) VALUES(?,?,?,'CODE_REPLACED',?)",UUID.randomUUID(),other.getId(),UUID.randomUUID(),code);assertTrue(history.own(customer.getId(),sid,null).items().isEmpty());
    }
}
