package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 본인 입금 원본과 마지막 관찰/보류 반입만 제공한다. 조회를 입금 승인이나 사용 가능 잔액으로 바꾸지 않는다. */
@Service
public class CustomerBitcoinDeposits {
    public record Cursor(Instant detectedAt,UUID id){}
    public record Deposit(UUID id,String asset,String network,String txid,int vout,String amount,Instant detectedAt,
        String chainStatus,Long confirmations,Instant observedAt,boolean observationFresh,String fundingStatus){}
    public record Page(List<Deposit> items,Cursor next){public Page{items=List.copyOf(items);}}
    private final CustomerSessionPolicy sessions;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private static final String SELECT="""
        SELECT r.id,r.asset,r.network,r.txid,r.vout,r.amount,r.recorded_at,o.status,o.confirmations,o.read_started_at,
         coalesce(o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes',false) fresh,
         CASE WHEN c.receipt_id IS NOT NULL THEN 'CREDITED' WHEN f.receipt_id IS NULL THEN 'UNRECOGNIZED' ELSE 'PENDING' END funding_status
         FROM bitcoin_deposit_receipts r
         LEFT JOIN bitcoin_observation_heads h ON h.network=r.network AND h.txid=r.txid
         LEFT JOIN bitcoin_transaction_observations o ON o.id=h.observation_id
         LEFT JOIN bitcoin_pending_deposits f ON f.receipt_id=r.id
         LEFT JOIN bitcoin_credited_deposits c ON c.receipt_id=r.id
         WHERE r.user_id=? AND r.network=?
        """;
    public CustomerBitcoinDeposits(CustomerSessionPolicy sessions,BitcoinSettings settings,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.sessions=sessions;this.settings=settings;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Page own(long actor,UUID sid,Cursor after){return attempt(actor,()->{
        sessions.require(actor,sid);validate(after);
        // 주소 신규 발급의 고객 확인 조건과 달리 과거 사실 조회는 거래 제한/고객 확인 만료로 숨기지 않는다.
        String order=" ORDER BY r.recorded_at DESC,r.id DESC LIMIT 26";
        var rows=after==null?jdbc.queryForList(SELECT+order,actor,settings.network.id):jdbc.queryForList(SELECT+" AND (r.recorded_at,r.id)<(?,?)"+order,actor,settings.network.id,Timestamp.from(after.detectedAt()),after.id());
        var items=rows.stream().limit(25).map(CustomerBitcoinDeposits::deposit).toList();var last=items.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_BTC_DEPOSITS_READ","customer:"+actor,"SUCCESS","OWN_RECEIPTS_ONLY");
        return new Page(items,rows.size()>25?new Cursor(last.detectedAt(),last.id()):null);
    });}
    public Deposit detail(long actor,UUID sid,UUID id){return attempt(actor,()->{
        sessions.require(actor,sid);if(id==null)throw badCursor();
        // 존재 여부를 먼저 조회하지 않는다. 타인의 UUID와 없는 UUID는 같은404로 처리한다.
        var rows=jdbc.queryForList(SELECT+" AND r.id=?",actor,settings.network.id,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"본인 입금 내역이 없습니다");
        var result=deposit(rows.getFirst());audit.record(actor,"CUSTOMER_BTC_DEPOSIT_READ","bitcoin-deposit:"+id,"SUCCESS","OWN_RECEIPT_ONLY");return result;
    });}
    private <T>T attempt(long actor,Supplier<T> work){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("고객 입금 조회는 독립 트랜잭션으로 실행하세요");
        try{return tx.execute(t->work.get());}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_BTC_DEPOSIT_QUERY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static Deposit deposit(Map<String,Object> row){
        Timestamp observed=(Timestamp)row.get("read_started_at");Number confirmations=(Number)row.get("confirmations");
        // JSON 숫자를 브라우저의 이진 부동소수점으로 바꾸지 않도록 금액은8자리 십진 문자열로 공개한다.
        return new Deposit((UUID)row.get("id"),(String)row.get("asset"),(String)row.get("network"),(String)row.get("txid"),((Number)row.get("vout")).intValue(),
            ((BigDecimal)row.get("amount")).setScale(8,RoundingMode.UNNECESSARY).toPlainString(),((Timestamp)row.get("recorded_at")).toInstant(),
            row.get("status")==null?"UNKNOWN":(String)row.get("status"),confirmations==null?null:confirmations.longValue(),observed==null?null:observed.toInstant(),Boolean.TRUE.equals(row.get("fresh")),(String)row.get("funding_status"));
    }
    private static void validate(Cursor after){if(after!=null&&(after.id()==null||after.detectedAt()==null||after.detectedAt().isBefore(Instant.EPOCH)||after.detectedAt().isAfter(Instant.now())))throw badCursor();}
    private static ResponseStatusException badCursor(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"입금 조회 위치를 확인하세요");}
}
