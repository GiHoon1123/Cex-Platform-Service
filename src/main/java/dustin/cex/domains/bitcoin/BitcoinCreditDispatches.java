package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 최초 실행 권한·시도·응답을 보존하고 같은 금융 원본만 재전송한다. HTTP 성공으로 플랫폼 자금을 해제하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinCreditDispatches {
    public record Execution(UUID attemptId,EngineDepositClient.Outcome outcome){}
    private record Claim(UUID attemptId,BitcoinCreditBasis.Funds funds){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final BitcoinCreditBasis policy;
    private final ObjectMapper json;private final AuditService audit;private final EngineDepositClient engine;private final TransactionTemplate tx;
    public BitcoinCreditDispatches(JdbcTemplate jdbc,UserRepository users,BitcoinCreditBasis policy,ObjectMapper json,AuditService audit,EngineDepositClient engine,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.policy=policy;this.json=json;this.audit=audit;this.engine=engine;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Execution> execute(UUID receipt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 전송은 독립 TX로 시작하세요");
        if(receipt==null)throw new IllegalArgumentException("입금 원본이 필요합니다");
        Claim claim;
        try{claim=tx.execute(t->prepare(receipt));}
        catch(BitcoinCreditBasis.Held held){audit.denied(null,"BTC_CREDIT_DISPATCH","bitcoin-deposit:"+receipt,"HELD_"+held.reason());throw held;}
        catch(org.springframework.web.server.ResponseStatusException denied){audit.denied(null,"BTC_CREDIT_DISPATCH","bitcoin-deposit:"+receipt,"SOURCE_OR_POLICY_"+denied.getStatusCode().value());throw denied;}
        if(claim==null)return Optional.empty();
        // 이 시점에는 실행 권한/시도가 이미 커밋됐고 고객 잠금과 DB 연결을 놓았다.
        // 원격 실행과 로컬 결과 기록 사이에서 중단되면 미완료 시도가 남고 같은 ID로 재확인한다.
        var f=claim.funds();EngineDepositClient.Outcome outcome;
        try{outcome=engine.credit(new EngineDepositClient.Credit(f.depositId(),f.customerId(),f.asset(),new BigDecimal(f.amount())));if(outcome==null)outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}
        catch(RuntimeException uncertain){outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}
        var finished=outcome;
        tx.executeWithoutResult(t->{
            users.lockById(f.customerId()).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
            jdbc.update("INSERT INTO bitcoin_credit_dispatch_results(attempt_id,outcome) VALUES(?,?)",claim.attemptId(),finished.name());
            audit.record(null,"BTC_CREDIT_DISPATCH_FINISHED","bitcoin-credit-attempt:"+claim.attemptId(),"SUCCESS","OUTCOME:"+finished.name());
        });
        return Optional.of(new Execution(claim.attemptId(),outcome));
    }
    private Claim prepare(UUID receipt){
        var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_credit_intents WHERE receipt_id=?",Long.class,receipt);
        if(owners.size()!=1)throw new IllegalStateException("영속 입금 의도가 필요합니다");
        var customer=users.lockById(owners.getFirst()).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
        var intent=jdbc.queryForMap("SELECT * FROM bitcoin_credit_intents WHERE receipt_id=?",receipt);String raw=(String)intent.get("basis");
        if(!BitcoinCreditBasis.digest(raw).equals(intent.get("basis_hash")))throw new IllegalStateException("입금 의도 원본 지문이 다릅니다");
        var original=json.treeToValue(json.readTree(raw).path("funds"),BitcoinCreditBasis.Funds.class);
        if(!original.depositId().equals(receipt)||original.customerId()!=customer.getId()||!original.asset().equals(intent.get("asset"))||new BigDecimal(original.amount()).compareTo((BigDecimal)intent.get("amount"))!=0)throw new IllegalStateException("입금 의도의 경제적 원본이 다릅니다");
        // 최신 시도보다 먼저 끝난 옛 시도의 확정도 존중한다. 늦은 UNKNOWN이 성공을 되돌리지 않는다.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id WHERE a.receipt_id=? AND r.outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED'))",Boolean.class,receipt)))return null;
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=?)",Boolean.class,receipt)))return null;
        var previous=jdbc.queryForList("""
            SELECT CASE WHEN r.attempt_id IS NULL THEN a.started_at<=clock_timestamp()-interval '30 seconds'
             ELSE r.completed_at<=clock_timestamp()-interval '5 seconds' END due
             FROM bitcoin_credit_dispatch_attempts a LEFT JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
             WHERE a.receipt_id=? ORDER BY a.position DESC LIMIT 1
            """,receipt);if(!previous.isEmpty()&&!Boolean.TRUE.equals(previous.getFirst().get("due")))return null;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_authorizations WHERE receipt_id=?)",Boolean.class,receipt))){
            // 의도 생성 뒤 최초 전송까지 조건이 달라질 수 있다. 실행 권한은 여기서 다시 판단해 원본과 묶어 확정한다.
            var current=policy.require(receipt,customer);if(!current.funds().equals(original))throw new IllegalStateException("현재 입금과 최초 금융 원본이 다릅니다");
            String basis=json.writeValueAsString(current),hash=BitcoinCreditBasis.digest(basis);
            jdbc.update("INSERT INTO bitcoin_credit_authorizations(receipt_id,user_id,asset,amount,policy_id,basis,basis_hash) VALUES(?,?,?,?,?,?,?)",receipt,customer.getId(),original.asset(),new BigDecimal(original.amount()),current.policy(),basis,hash);
            audit.record(null,"BTC_CREDIT_DISPATCH_AUTHORIZED","bitcoin-credit:"+receipt,"SUCCESS","BASIS:"+hash);
        }
        // 이미 확정한 실행 권한은 새 의도 재승인이 아니다. 후속 제한은 신규 권한/주문/출금을 막으며,
        // 전송 중이던 명령을 미실행으로 추측해 취소하지 않는다. 같은 원본의 금융 결과를 끝까지 확인한다.
        UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_credit_dispatch_attempts(id,receipt_id) VALUES(?,?)",attempt,receipt);
        audit.record(null,"BTC_CREDIT_DISPATCH_STARTED","bitcoin-credit-attempt:"+attempt,"SUCCESS","RECEIPT:"+receipt);
        return new Claim(attempt,original);
    }
}
