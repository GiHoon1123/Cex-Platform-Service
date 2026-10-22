package dustin.cex.domains.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import dustin.cex.shared.model.ExactAmount;

/**
 * 금융 상태 변경과 같은 트랜잭션에 자산 이동의 양쪽을 기록한다.
 * 잔고를 복사한 로그가 아니라 고객 사용 가능·예약·수수료·최초 반입 상대 계정 사이의 이동이다.
 * 확정 기록의 정정은 새 역거래로 해야 하며 이 서비스에는 수정·삭제 기능이 없다.
 */
@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    public LedgerService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct void schema() throws Exception {
        jdbc.execute(new ClassPathResource("ledger/schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }
    public record Posting(long owner, String bucket, String asset, BigDecimal delta) {
        public Posting {
            if(owner<0 || asset==null || asset.isBlank() || asset.length()>50
                    || !(owner==0 ? Set.of("FEE","OPENING") : Set.of("AVAILABLE","LOCKED")).contains(bucket))
                throw new IllegalArgumentException("잘못된 원장 계정");
            ExactAmount.requireNonNegative(delta.abs(),"원장 이동액");
        }
    }
    public static Posting p(long owner,String bucket,String asset,BigDecimal delta) { return new Posting(owner,bucket,asset,delta); }

    /** 최초 반입은 명시적 실행만 허용한다. 기존 거래 이력이나 예약이 있으면 추측해 채우지 않는다. */
    @Transactional
    public void initializeOpening() {
        jdbc.execute("SELECT pg_advisory_xact_lock(71522026)");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions)",Boolean.class))
                || List.of("orders","order_submissions","order_dispatches","trades","trade_fees","engine_event_receipts").stream()
                    .anyMatch(table->jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)!=0))
            throw new IllegalStateException("최초 원장은 금융 이력이 없는 환경에서만 반입할 수 있습니다");
        var postings=new ArrayList<Posting>();
        jdbc.query("SELECT user_id,mint_address,available,locked FROM user_balances ORDER BY user_id,mint_address FOR UPDATE",row->{
            long user=row.getLong(1); String asset=row.getString(2);
            BigDecimal available=ExactAmount.requireNonNegative(row.getBigDecimal(3),"최초 잔고");
            if(row.getBigDecimal(4).signum()!=0) throw new IllegalStateException("최초 반입에 예약 잔액이 있습니다");
            postings.add(p(user,"AVAILABLE",asset,available)); postings.add(p(0,"OPENING",asset,available.negate()));
        });
        append("opening","OPENING",postings);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(String key,String kind,List<Posting> postings) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key='opening')",Boolean.class)))
            throw new IllegalStateException("명시적인 최초 원장 반입이 필요합니다");
        append(key,kind,postings);
    }
    private void append(String key,String kind,List<Posting> postings) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("원장은 금융 트랜잭션 안에서만 기록할 수 있습니다");
        if(key==null || key.length()>160 || key.isBlank()) throw new IllegalArgumentException("잘못된 원장 업무 키");
        // 같은 계정 항목을 합쳐 유일한 계정 변화로 기록한다. 자산 간 환율로 합계를 맞추지 않는다.
        record Account(long owner,String bucket,String asset) {}
        var accounts=new LinkedHashMap<Account,BigDecimal>(); var totals=new HashMap<String,BigDecimal>();
        for(var entry:postings) {
            accounts.merge(new Account(entry.owner(),entry.bucket(),entry.asset()),entry.delta(),BigDecimal::add);
            totals.merge(entry.asset(),entry.delta(),BigDecimal::add);
        }
        if(totals.values().stream().anyMatch(value->value.signum()!=0)) throw new IllegalArgumentException("자산별 원장 합계 불일치");
        jdbc.update("INSERT INTO ledger_transactions(operation_key,kind) VALUES(?,?)",key,kind);
        accounts.forEach((account,delta)->{
            if(delta.signum()!=0) jdbc.update("INSERT INTO ledger_entries(operation_key,owner_id,bucket,asset,delta) VALUES(?,?,?,?,?)",
                key,account.owner(),account.bucket(),account.asset(),delta);
        });
    }
}
