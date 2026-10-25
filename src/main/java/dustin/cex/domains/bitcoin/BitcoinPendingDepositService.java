package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static dustin.cex.domains.ledger.LedgerService.p;

/** 검증된 입금을 고객 보류 원장에 한 번만 반입한다. 사용 가능 잔액/엔진에는 공개하지 않는다. */
@Service
public class BitcoinPendingDepositService {
    public record Recognized(UUID depositId,String operationKey,boolean created){}
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final UserRepository users;
    private final AuditService audit;
    private final BitcoinSettings settings;
    private final dustin.cex.domains.valuation.BitcoinDepositValuationService valuations;
    private final TransactionTemplate tx;
    public BitcoinPendingDepositService(JdbcTemplate jdbc,LedgerService ledger,UserRepository users,AuditService audit,
            BitcoinSettings settings,dustin.cex.domains.valuation.BitcoinDepositValuationService valuations,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.ledger=ledger;this.users=users;this.audit=audit;this.settings=settings;this.valuations=valuations;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Recognized recognize(UUID deposit){
        requireIndependent();if(deposit==null)throw new IllegalArgumentException("입금 원본이 필요합니다");
        return tx.execute(t->within(deposit));
    }
    public Optional<Recognized> recognizeNext(){
        requireIndependent();
        return tx.execute(t->{
            // 다른 업무가 잠근 고객은 다음 시도로 미룬다. 독립 고객의 보류 반입이 한 계정의 긴 잠금 때문에 멈추지 않는다.
            var pending=jdbc.queryForList("""
                SELECT r.id FROM bitcoin_deposit_receipts r JOIN users u ON u.id=r.user_id JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
                 JOIN bitcoin_deposit_chain_heads qh ON qh.receipt_id=r.id JOIN bitcoin_deposit_chain_decisions d ON d.id=qh.decision_id
                 JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id
                 JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
                 JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
                 WHERE r.network=? AND a.wallet=? AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1'
                  AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
                  AND NOT EXISTS(SELECT 1 FROM bitcoin_pending_deposits f WHERE f.receipt_id=r.id)
                 ORDER BY r.recorded_at,r.id LIMIT 1 FOR UPDATE OF u SKIP LOCKED
                """,settings.network.id,settings.wallet);
            return pending.isEmpty()?Optional.empty():Optional.of(within((UUID)pending.getFirst().get("id")));
        });
    }
    private Recognized within(UUID deposit){
        var rows=jdbc.queryForList("""
            SELECT r.user_id,r.asset,r.amount,r.network,a.wallet FROM bitcoin_deposit_receipts r
             JOIN bitcoin_deposit_addresses a ON a.id=r.address_id WHERE r.id=?
            """,deposit);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 원본이 없습니다");
        var receipt=rows.getFirst();long owner=((Number)receipt.get("user_id")).longValue();
        // 보류 기록은 현재 거래 허용과 구분한다. 자격이 만료돼도 실제 자산/고객 귀속 사실을 삭제하지 않는다.
        users.lockById(owner).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
        var existing=jdbc.queryForList("SELECT operation_key FROM bitcoin_pending_deposits WHERE receipt_id=?",deposit);
        if(!existing.isEmpty())return new Recognized(deposit,(String)existing.getFirst().get("operation_key"),false);
        if(!receipt.get("network").equals(settings.network.id)||!receipt.get("wallet").equals(settings.wallet))throw held();
        var sources=jdbc.queryForList("""
            SELECT d.id FROM bitcoin_deposit_chain_heads qh JOIN bitcoin_deposit_chain_decisions d ON d.id=qh.decision_id
             JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
             JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
             WHERE qh.receipt_id=? AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1'
              AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
             FOR SHARE OF qh,oh
            """,deposit);
        if(sources.size()!=1)throw held();
        long decision=((Number)sources.getFirst().get("id")).longValue();String key="btc:pending:"+deposit;
        BigDecimal amount=(BigDecimal)receipt.get("amount");String asset=(String)receipt.get("asset");
        // 기존 원장을 사용해 자산별 합계0을 보장한다. 고객 보류 증가의 반대편은 최초 잔고 반입이 아닌 수탁 자산 계정이다.
        // 현재 공개 참고가격의 위치를 먼저 고정한다. 가격이 없어도 자산은 미평가 상태로 보류 원장에 기록한다.
        Long priceId=valuations.captureCurrentPrice();
        ledger.record(key,"BTC_DEPOSIT_PENDING",List.of(p(owner,"PENDING",asset,amount),p(0,"CUSTODY",asset,amount.negate())));
        jdbc.update("INSERT INTO bitcoin_pending_deposits(receipt_id,decision_id,operation_key) VALUES(?,?,?)",deposit,decision,key);
        valuations.recordRecognized(deposit,priceId);
        audit.record(null,"BTC_PENDING_DEPOSIT_RECOGNIZED","bitcoin-deposit:"+deposit,"SUCCESS","DECISION:"+decision+":OP:"+key);
        return new Recognized(deposit,key,true);
    }
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 온체인 조건과 지갑 사용 근거를 다시 확인해야 합니다");}
    private static void requireIndependent(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 보류 반입은 독립 트랜잭션으로 실행하세요");}
}
