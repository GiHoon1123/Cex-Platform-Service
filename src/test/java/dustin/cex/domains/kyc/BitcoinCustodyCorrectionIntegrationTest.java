package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.bitcoin.BitcoinCustodyCorrections;
import dustin.cex.domains.bitcoin.BitcoinCustodyReconciliationService;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.OperatorMfaService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** 실제 PostgreSQL 불변 제약과 보안키 서명으로 수탁 차이가 새 균형 분개로만 정정되는지 검증한다. */
class BitcoinCustodyCorrectionIntegrationTest extends EddTestSupport {
    private static final BigDecimal GAP=new BigDecimal("0.10000000");
    @Autowired BitcoinCustodyCorrections corrections;
    @Autowired LedgerService ledger;
    @MockitoBean BitcoinCustodyReconciliationService reconciliation;

    @BeforeEach void opening(){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key='opening')",Boolean.class)))ledger.initializeOpening();
    }

    @Test void approvedFreshMismatchCreatesOnlyBalancedCustodyAndSuspenseCorrection()throws Exception{
        var request=proposal(GAP);approve(request,true);UUID verification=mismatch(GAP);when(reconciliation.run()).thenReturn(new BitcoinCustodyReconciliationService.Report(verification,"MISMATCH"));
        BigDecimal customerBefore=customerBalance(),custodyBefore=bucket("CUSTODY"),suspenseBefore=bucket("SUSPENSE");
        var result=corrections.execute(request.payload().id());
        assertEquals("EXECUTED",result.status());assertEquals(verification,result.verificationRun());
        assertEquals(0,customerBefore.compareTo(customerBalance()));assertEquals(0,custodyBefore.subtract(GAP).compareTo(bucket("CUSTODY")));assertEquals(0,suspenseBefore.add(GAP).compareTo(bucket("SUSPENSE")));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE operation_key=?",Long.class,result.operationKey()));
        assertEquals(0,jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE operation_key=?",BigDecimal.class,result.operationKey()).compareTo(BigDecimal.ZERO));
        assertEquals(result,corrections.execute(request.payload().id()));org.mockito.Mockito.verify(reconciliation,times(1)).run();
    }

    @Test void rejectionAndChangedMismatchCannotMoveCustody()throws Exception{
        var rejected=proposal(GAP);approve(rejected,false);assertThrows(ResponseStatusException.class,()->corrections.execute(rejected.payload().id()));
        var stale=proposal(GAP);approve(stale,true);UUID verification=mismatch(new BigDecimal("0.20000000"));when(reconciliation.run()).thenReturn(new BitcoinCustodyReconciliationService.Report(verification,"MISMATCH"));
        BigDecimal before=bucket("CUSTODY");long executions=count("bitcoin_custody_correction_executions");assertThrows(ResponseStatusException.class,()->corrections.execute(stale.payload().id()));assertEquals(0,before.compareTo(bucket("CUSTODY")));assertEquals(executions,count("bitcoin_custody_correction_executions"));
    }

    @Test void checkerMustInspectTheExactCurrentPayloadBeforeSigning()throws Exception{
        var request=proposal(GAP);assertThrows(ResponseStatusException.class,()->corrections.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true));
        corrections.inspect(checker.user().getId(),checker.sid(),request.payload().id());
        assertThrows(ResponseStatusException.class,()->corrections.options(checker.user().getId(),checker.sid(),request.payload().id(),"0".repeat(64),true));
        assertThrows(ResponseStatusException.class,()->corrections.inspect(customer.getId(),sid,request.payload().id()));
    }

    @Test void omittedAuditRollsBackProposalDecisionAndExecution()throws Exception{
        long requests=count("bitcoin_custody_correction_requests"),decisions=count("bitcoin_custody_correction_decisions"),executions=count("bitcoin_custody_correction_executions");
        UUID source=mismatch(GAP);doNothing().when(audit).record(anyLong(),eq("BTC_CUSTODY_CORRECTION_PROPOSED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->corrections.propose(maker.user().getId(),maker.sid(),source,checker.user().getId(),UUID.randomUUID()));reset(audit);assertEquals(requests,count("bitcoin_custody_correction_requests"));
        var request=proposal(GAP);corrections.inspect(checker.user().getId(),checker.sid(),request.payload().id());var proof=signature(request,true);
        doNothing().when(audit).record(anyLong(),eq("BTC_CUSTODY_CORRECTION_DECIDED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->corrections.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof));reset(audit);assertEquals(decisions,count("bitcoin_custody_correction_decisions"));
        assertEquals("APPROVED",corrections.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof).status());UUID verification=mismatch(GAP);when(reconciliation.run()).thenReturn(new BitcoinCustodyReconciliationService.Report(verification,"MISMATCH"));
        doNothing().when(audit).record(isNull(),eq("BTC_CUSTODY_CORRECTION_EXECUTED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->corrections.execute(request.payload().id()));reset(audit);assertEquals(executions,count("bitcoin_custody_correction_executions"));
    }

    @Test void correctionEvidenceIsImmutableAndDirectExecutionCannotBypassBindings()throws Exception{
        var request=proposal(GAP);approve(request,true);
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_custody_correction_executions(request_id,verification_run,operation_key) VALUES(?,?,?)",request.payload().id(),request.payload().runId(),"missing-ledger"));
        for(String table:List.of("bitcoin_custody_correction_requests","bitcoin_custody_correction_views","bitcoin_custody_correction_decisions"))for(String statement:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(statement));
    }

    @Test void headlessHttpEnforcesRolesAndDoesNotExposeExecution()throws Exception{
        UUID source=mismatch(GAP);String base="/api/admin/custody/BTC/corrections";UUID key=UUID.randomUUID();
        String result=mvc.perform(post(base).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("reconciliationRun",source,"checkerId",checker.user().getId())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(result).path("payload").path("id").asString());String hash=json.readTree(result).path("payloadHash").asString();
        mvc.perform(post(base+"/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk());
        mvc.perform(post(base+"/"+id+"/execute").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
        mvc.perform(post(base).header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        assertEquals(64,hash.length());assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_custody_correction_executions WHERE request_id=?)",Boolean.class,id)));
    }

    private BitcoinCustodyCorrections.Request proposal(BigDecimal gap){UUID source=mismatch(gap);return corrections.propose(maker.user().getId(),maker.sid(),source,checker.user().getId(),UUID.randomUUID());}
    private BitcoinCustodyCorrections.Request approve(BitcoinCustodyCorrections.Request request,boolean approved)throws Exception{
        corrections.inspect(checker.user().getId(),checker.sid(),request.payload().id());var proof=signature(request,approved);
        return corrections.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved,proof);
    }
    private OperatorMfaService.AuthorizationProof signature(BitcoinCustodyCorrections.Request request,boolean approved)throws Exception{
        var option=corrections.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved).authentication();
        return new OperatorMfaService.AuthorizationProof(option.challengeId(),checker.key().assertion(option,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    private UUID mismatch(BigDecimal gap){
        BigDecimal expected=bucket("CUSTODY").negate().setScale(8),observed=expected.add(gap);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO bitcoin_custody_reconciliation_runs(id,network,wallet,status,wallet_trusted,ledger_custody,completed_at) VALUES(?,?,'platform-custody','MISMATCH',?,?,clock_timestamp())",id,"bitcoin-regtest",observed,bucket("CUSTODY"));
        jdbc.update("INSERT INTO bitcoin_custody_reconciliation_issues(run_id,code,expected,actual) VALUES(?,'CUSTODY_BALANCE_MISMATCH',?,?)",id,expected.toPlainString(),observed.toPlainString());return id;
    }
    private BigDecimal bucket(String bucket){return jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=0 AND bucket=? AND asset='BTC'",BigDecimal.class,bucket);}
    private BigDecimal customerBalance(){return jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id>0 AND asset='BTC'",BigDecimal.class);}
    private long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
}
