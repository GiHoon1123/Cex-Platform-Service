package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.mail.CustomerMailOutbox;
import dustin.cex.domains.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/** 실제 PostgreSQL과 보안키 서명으로 보존 정지·해제·메일 암호문 파기 증적을 검증한다. */
class DataRetentionIntegrationTest extends EddTestSupport {
    @Autowired DataRetentionService retention;
    @Autowired CustomerMailOutbox outbox;

    @Test void independentlyApprovedHoldBlocksDisposalUntilIndependentlyApprovedRelease()throws Exception{
        UUID mail=expiredMail();
        var hold=retention.propose(maker.user().getId(),maker.sid(),"HOLD","USER",Long.toString(customer.getId()),null,checker.user().getId(),"분쟁 조사 중 고객 자료 보존","CASE-2026-0001",UUID.randomUUID());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->retention.options(checker.user().getId(),checker.sid(),hold.payload().id(),hold.payloadHash(),true));
        approve(hold,true);assertEquals(0,outbox.pruneExpiredPayloads());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_mail_payloads WHERE job_id=?",Long.class,mail));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM customer_mail_payloads WHERE job_id=?",mail));

        var release=retention.propose(maker.user().getId(),maker.sid(),"RELEASE","USER",Long.toString(customer.getId()),hold.payload().id(),checker.user().getId(),"분쟁 종결 확인 뒤 파기 재개","CASE-2026-0001-CLOSED",UUID.randomUUID());
        approve(release,true);assertEquals(1,outbox.pruneExpiredPayloads());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_mail_payloads WHERE job_id=?",Long.class,mail));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM data_disposal_items WHERE item_id=?",Long.class,mail));
        assertNotNull(jdbc.queryForObject("SELECT released_at FROM data_retention_hold_releases WHERE hold_id=?",java.sql.Timestamp.class,hold.payload().id()));
    }

    @Test void rejectedOrTamperedControlCannotAlterRetentionState()throws Exception{
        var hold=retention.propose(maker.user().getId(),maker.sid(),"HOLD","USER",Long.toString(customer.getId()),null,checker.user().getId(),"반려할 보존 제안","CASE-REJECT",UUID.randomUUID());approve(hold,false);
        assertTrue(retention.holds(0).stream().noneMatch(value->value.id().equals(hold.payload().id())));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE data_retention_requests SET reason='변조'"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM data_retention_request_decisions"));
    }

    @Test void decisionAuditFailureRollsBackHold()throws Exception{
        var hold=retention.propose(maker.user().getId(),maker.sid(),"HOLD","USER",Long.toString(customer.getId()),null,checker.user().getId(),"감사 실패 원자성 검증","CASE-AUDIT",UUID.randomUUID());
        retention.inspect(checker.user().getId(),checker.sid(),hold.payload().id());var proof=signature(hold,true);
        doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(checker.user().getId()),eq("DATA_RETENTION_DECIDED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->retention.decide(checker.user().getId(),checker.sid(),hold.payload().id(),hold.payloadHash(),true,proof));reset(audit);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM data_retention_holds WHERE id=?",Long.class,hold.payload().id()));
    }

    @Test void httpBoundaryAllowsOnlyTheAssignedRoles()throws Exception{
        String body=json.writeValueAsString(Map.of("action","HOLD","scopeType","USER","scopeId",Long.toString(customer.getId()),"checkerId",checker.user().getId(),"reason","화면 경계 검증","authorityReference","CASE-HTTP"));
        mvc.perform(post("/api/admin/data-retention/requests").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/data-retention/requests").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/data-retention/holds").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk());
    }

    private DataRetentionService.Request approve(DataRetentionService.Request request,boolean approved)throws Exception{retention.inspect(checker.user().getId(),checker.sid(),request.payload().id());return retention.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved,signature(request,approved));}
    private OperatorMfaService.AuthorizationProof signature(DataRetentionService.Request request,boolean approved)throws Exception{var option=retention.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved).authentication();return new OperatorMfaService.AuthorizationProof(option.challengeId(),checker.key().assertion(option,"http://localhost:3003",true,counter.incrementAndGet(),null));}
    private UUID expiredMail(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO customer_mail_jobs(id,user_id,kind,reference_id,content_mac,key_id,expires_at) VALUES(?,?,'STATUS_NOTICE',?,'"+"a".repeat(64)+"','test',clock_timestamp()-interval '2 days')",id,customer.getId(),UUID.randomUUID());jdbc.update("INSERT INTO customer_mail_payloads(job_id,payload_cipher) VALUES(?,?)",id,new byte[]{1,2,3});jdbc.update("INSERT INTO customer_mail_delivery(job_id,status) VALUES(?,'SUPPRESSED')",id);return id;}
}
