package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Bitcoin Core의 신뢰 잔액과 불변 수탁 원장을 읽기 전용으로 비교하고 차이를 증거로 보존한다. */
@Service
@DependsOn("bitcoinSchemaInitializer")
public class BitcoinCustodyReconciliationService {
    public record Report(UUID id,String status){}
    private record Cut(long transactions,BigDecimal custody){}
    private final JdbcTemplate jdbc;private final BitcoinRpcClient rpc;private final BitcoinSettings settings;
    private final AuditService audit;private final TransactionTemplate snapshot;
    public BitcoinCustodyReconciliationService(JdbcTemplate jdbc,BitcoinRpcClient rpc,BitcoinSettings settings,
            AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.rpc=rpc;this.settings=settings;this.audit=audit;this.snapshot=new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setTimeout(30);
    }
    @Transactional(propagation=Propagation.NEVER)
    public Report run(){
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_custody_reconciliation_runs(id,network,wallet,status) VALUES(?,?,?,'RUNNING')",id,settings.network.id,settings.wallet);
        Cut before=cut();
        BitcoinRpcClient.Ready ready;JsonNode balances;String payload;
        try{
            ready=rpc.ready();balances=rpc.call("getbalances",List.of());payload=balances.toString();
        }catch(RuntimeException failure){
            // RPC 예외 원문에는 내부 주소나 자격 정보가 포함될 수 있으므로 분류명만 저장한다.
            snapshot.executeWithoutResult(status->{issue(id,"NODE_UNAVAILABLE","readable fixed wallet",failure.getClass().getSimpleName());complete(id,"FAILED",null,null,null,null,null,null);audit.record(null,"BTC_CUSTODY_RECONCILED","bitcoin-custody-reconciliation:"+id,"FAILURE","NODE_UNAVAILABLE");});
            return new Report(id,"FAILED");
        }
        // 저장·감사 실패를 노드 장애로 바꾸지 않는다. 완료 증거 전체가 롤백되고 호출자에게 실패가 전달돼야 한다.
        return snapshot.execute(status->finish(id,before,ready.chain(),balances,payload));
    }
    private Report finish(UUID id,Cut before,JsonNode chain,JsonNode balances,String payload){
        var mine=balances.path("mine");var block=balances.path("lastprocessedblock");
        String hash=text(block,"hash");long height=integer(block,"height");
        if(!hash.matches("[0-9a-f]{64}")||!hash.equals(text(chain,"bestblockhash"))||height!=integer(chain,"blocks"))throw new IllegalArgumentException("노드 지갑과 체인 머리가 다릅니다");
        BigDecimal trusted=money(mine,"trusted"),untrusted=money(mine,"untrusted_pending"),immature=money(mine,"immature");
        Cut after=cut();String outcome="MATCHED";BigDecimal expected=after.custody().negate();
        if(before.transactions()!=after.transactions()||before.custody().compareTo(after.custody())!=0){issue(id,"FINANCIAL_CUT_CHANGED",before.toString(),after.toString());outcome="INCOMPLETE";}
        if(untrusted.signum()!=0||immature.signum()!=0){issue(id,"NODE_BALANCE_PENDING","all documented transitional balances zero",untrusted+":"+immature);outcome="INCOMPLETE";}
        long payouts=jdbc.queryForObject("SELECT count(*) FROM bitcoin_payout_broadcast_heads h WHERE NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events c WHERE c.withdrawal_id=h.request_id)",Long.class);
        if(payouts>0){issue(id,"PAYOUT_IN_FLIGHT","no accepted payout awaiting ledger completion",Long.toString(payouts));outcome="INCOMPLETE";}
        else if(trusted.compareTo(expected)!=0){issue(id,"CUSTODY_BALANCE_MISMATCH",expected.toPlainString(),trusted.toPlainString());outcome="MISMATCH";}
        complete(id,outcome,hash,height,trusted,untrusted,immature,payload);
        audit.record(null,"BTC_CUSTODY_RECONCILED","bitcoin-custody-reconciliation:"+id,"SUCCESS","STATUS:"+outcome+":BLOCK:"+height);
        return new Report(id,outcome);
    }
    private Cut cut(){return new Cut(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class),jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=0 AND bucket='CUSTODY' AND asset='BTC'",BigDecimal.class));}
    private void complete(UUID id,String status,String block,Long height,BigDecimal trusted,BigDecimal untrusted,BigDecimal immature,String payload){
        jdbc.update("UPDATE bitcoin_custody_reconciliation_runs SET status=?,node_block_hash=?,node_block_height=?,wallet_trusted=?,wallet_untrusted=?,wallet_immature=?,ledger_custody=(SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=0 AND bucket='CUSTODY' AND asset='BTC'),source_payload=?,source_hash=?,completed_at=clock_timestamp() WHERE id=?",
            status,block,height,trusted,untrusted,immature,payload,payload==null?null:digest(payload),id);
    }
    private void issue(UUID id,String code,String expected,String actual){jdbc.update("INSERT INTO bitcoin_custody_reconciliation_issues(run_id,code,expected,actual) VALUES(?,?,?,?)",id,code,expected,actual);}
    private static String text(JsonNode node,String field){var value=node.path(field);if(!value.isString())throw new IllegalArgumentException("노드 문자열 필드 누락");return value.asString();}
    private static long integer(JsonNode node,String field){var value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw new IllegalArgumentException("노드 정수 필드 오류");return value.longValue();}
    private static BigDecimal money(JsonNode node,String field){var value=node.path(field);if(!value.isNumber())throw new IllegalArgumentException("노드 금액 필드 오류");var amount=ExactAmount.requireNonNegative(value.decimalValue(),"노드 잔액");if(amount.scale()>8)throw new IllegalArgumentException("비트코인 잔액 자릿수 오류");return amount;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("대사 증거 지문 생성 실패");}}
}
