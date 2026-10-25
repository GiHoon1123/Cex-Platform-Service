package dustin.cex.domains.reconciliation;

import tools.jackson.databind.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.ledger.LedgerService.Posting;
import dustin.cex.domains.order.repository.OrderSubmissionRepository;
import static dustin.cex.domains.ledger.LedgerService.p;

/**
 * 엔진 저널·원장·조회 잔고·미체결 예약을 비교하고 차이를 저장한다.
 * 엔진 조회는 디비 스냅샷을 열기 전에 끝낸다. 검사에는 잔고 쓰기나 재정산 호출이 없다.
 */
@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final EngineAuditClient source;
    private final OrderSubmissionRepository submissions;
    private final TransactionTemplate snapshot;
    private final ObjectMapper mapper=new ObjectMapper();
    public ReconciliationService(JdbcTemplate jdbc,EngineAuditClient source,OrderSubmissionRepository submissions,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.source=source;this.submissions=submissions;this.snapshot=new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // 긴 스냅샷이 과거 행 버전을 무기한 붙잡지 않도록 실패 경계를 둔다.
        snapshot.setTimeout(30);
    }
    public record Report(UUID id,String status) {}
    @Transactional(propagation=Propagation.NEVER)
    public Report run() {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO reconciliation_runs(id,status) VALUES(?,'RUNNING')",id);
        boolean imported=false;
        try {
            long after=0;Long through=null;String engine=null;
            do {
                JsonNode page=Objects.requireNonNull(source.page(after,through,engine));
                String pageEngine=page.path("engine_id").asText();UUID.fromString(pageEngine);
                long end=integer(page,"through"),next=integer(page,"next_after");
                if((engine!=null && (!engine.equals(pageEngine) || through!=end)) || next<after || next>end || (next==after && next!=end))
                    throw new IllegalArgumentException("감사 조회 기준 또는 진행 위치 불일치");
                engine=pageEngine;through=end;
                if(!page.path("events").isArray()) throw new IllegalArgumentException("감사 이벤트 목록 누락");
                for(JsonNode payload:page.path("events")) {
                    if(!payload.isTextual()) throw new IllegalArgumentException("감사 원본 문자열 누락");
                    JsonNode event=mapper.readTree(payload.textValue());String eventId=event.path("event_id").asText();
                    if(!eventId.matches(java.util.regex.Pattern.quote(engine)+":[1-9][0-9]*:[0-9]+") || !engine.equals(event.path("engine_id").asText()))
                        throw new IllegalArgumentException("감사 이벤트 식별자 불일치");
                    long sequence=Long.parseLong(eventId.split(":")[1]);
                    if(sequence<=after || sequence>next) throw new IllegalArgumentException("감사 이벤트 범위 불일치");
                    jdbc.update("INSERT INTO reconciliation_evidence(run_id,event_id,payload_hash,payload) VALUES(?,?,?,?)",id,eventId,hash(payload.textValue()),payload.textValue());
                }
                after=next;
            } while(after<through);
            jdbc.update("UPDATE reconciliation_runs SET engine_id=?,through_sequence=? WHERE id=?",engine,through,id);
            imported=true;
            String engineId=engine;long cut=through;
            String status=snapshot.execute(tx->compare(id,engineId,cut));
            finish(id,status); return new Report(id,status);
        } catch(Exception failure) {
            // 예외 본문에는 주소나 내부 인증 정보가 섞일 수 있어 보고서에 넣지 않는다.
            issue(id,imported?"CHECK_FAILED":"SOURCE_UNAVAILABLE","run","complete",failure.getClass().getSimpleName());
            finish(id,"FAILED");return new Report(id,"FAILED");
        }
    }
    private String compare(UUID run,String engine,long cut) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key='opening')",Boolean.class)))
            issue(run,"OPENING_MISSING","opening","explicit opening","missing");
        // 같은 시점의 원장 합계와 현재 조회용 잔고를 비교한다. 검사 중 새 거래가 커밋돼도 스냅샷은 변하지 않는다.
        jdbc.query("""
            WITH l AS (SELECT owner_id,asset,sum(delta) FILTER(WHERE bucket='AVAILABLE') a,sum(delta) FILTER(WHERE bucket='LOCKED') k
              FROM ledger_entries WHERE owner_id>0 GROUP BY owner_id,asset)
            SELECT coalesce(l.owner_id,b.user_id) owner,coalesce(l.asset,b.mint_address) asset,
              coalesce(l.a,0) expected_a,coalesce(l.k,0) expected_k,coalesce(b.available,0) actual_a,coalesce(b.locked,0) actual_k
            FROM l FULL OUTER JOIN user_balances b ON l.owner_id=b.user_id AND l.asset=b.mint_address
            """,row->{
                String ref=row.getLong("owner")+":"+row.getString("asset");
                difference(run,"BALANCE_AVAILABLE",ref,row.getBigDecimal("expected_a"),row.getBigDecimal("actual_a"));
                difference(run,"BALANCE_LOCKED",ref,row.getBigDecimal("expected_k"),row.getBigDecimal("actual_k"));
            });
        checkReservations(run);
        jdbc.query("SELECT engine_event_id FROM trades t WHERE NOT EXISTS(SELECT 1 FROM engine_event_receipts r WHERE r.event_id=t.engine_event_id)",
            row->{issue(run,"TRADE_RECEIPT_MISSING",row.getString(1),"atomic receipt","missing");});
        jdbc.query("""
            WITH fills AS (SELECT buy_order_id id,amount,price*amount quote FROM trades UNION ALL SELECT sell_order_id,amount,price*amount FROM trades)
            SELECT o.id,o.filled_amount,o.filled_quote_amount,coalesce(sum(f.amount),0) quantity,coalesce(sum(f.quote),0) quote
              FROM orders o LEFT JOIN fills f ON f.id=o.id GROUP BY o.id,o.filled_amount,o.filled_quote_amount
            """,row->{
                difference(run,"ORDER_FILLED",row.getString("id"),row.getBigDecimal("quantity"),row.getBigDecimal("filled_amount"));
                difference(run,"ORDER_SPENT",row.getString("id"),row.getBigDecimal("quote"),row.getBigDecimal("filled_quote_amount"));
            });
        // 공개 호가 수신 키는 같은 저장소를 쓰지만 금융 대사의 대상은 숫자 인덱스 결과뿐이다.
        jdbc.query("SELECT t.operation_key FROM ledger_transactions t WHERE t.operation_key LIKE 'engine:%' AND NOT EXISTS(SELECT 1 FROM engine_event_receipts r WHERE t.operation_key='engine:'||r.event_id)",
            row->{issue(run,"LEDGER_RECEIPT_MISSING",row.getString(1),"atomic receipt","missing");});
        // 수신 키가 존재한다는 것만으로 성공 처리하지 않는다. 원본 해시와 실제 원장 이동도 비교한다.
        jdbc.query("SELECT event_id,payload_hash,payload FROM reconciliation_evidence WHERE run_id=? ORDER BY event_id",row->{
            String event=row.getString(1),payload=row.getString(3);
            var receipts=jdbc.queryForList("SELECT payload_hash FROM engine_event_receipts WHERE event_id=?",String.class,event);
            if(receipts.isEmpty()) {issue(run,"EVENT_PENDING",event,"received","missing");return;}
            if(!receipts.getFirst().equals(row.getString(2))) issue(run,"EVENT_PAYLOAD",event,row.getString(2),receipts.getFirst());
            try { var original=mapper.readTree(payload); checkPostings(run,"engine:"+event,expected(original)); checkTrade(run,original); checkDeposit(run,original,payload); }
            catch(Exception invalid) {issue(run,"EVENT_CONTRACT",event,"valid original",invalid.getClass().getSimpleName());}
        },run);
        jdbc.query("""
            SELECT event_id FROM engine_event_receipts WHERE engine_id=? AND split_part(event_id,':',3) ~ '^[0-9]+$' AND split_part(event_id,':',2)::bigint<=?
              AND NOT EXISTS(SELECT 1 FROM reconciliation_evidence e WHERE e.run_id=? AND e.event_id=engine_event_receipts.event_id)
            """,row->{issue(run,"UNEXPECTED_RECEIPT",row.getString(1),"engine result","missing");},engine,cut,run);
        jdbc.query("SELECT DISTINCT engine_id FROM engine_event_receipts WHERE engine_id<>? AND split_part(event_id,':',3) ~ '^[0-9]+$'",
            row->{issue(run,"ENGINE_ID_MISMATCH",row.getString(1),engine,"other engine history");},engine);
        long mismatch=jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category<>'EVENT_PENDING'",Long.class,run);
        long pending=jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category='EVENT_PENDING'",Long.class,run);
        return mismatch>0?"MISMATCH":pending>0?"INCOMPLETE":"MATCHED";
    }
    private void checkReservations(UUID run) {
        var reserved=new HashMap<String,BigDecimal>();
        jdbc.query("SELECT o.id,o.user_id,o.status,o.filled_amount,o.filled_quote_amount FROM orders o ORDER BY o.id",row->{
            long id=row.getLong(1);var found=submissions.findByOrderId(id);
            if(found.isEmpty()) {issue(run,"ORDER_ORIGINAL_MISSING",Long.toString(id),"original","missing");return;}
            var original=found.get();var c=original.command();String asset=c.reservationMint();long user=original.getUserId();
            if(user!=row.getLong(2)) issue(run,"ORDER_OWNER",Long.toString(id),Long.toString(user),Long.toString(row.getLong(2)));
            checkPostings(run,"reserve:"+id,List.of(p(user,"AVAILABLE",asset,c.reservationAmount().negate()),p(user,"LOCKED",asset,c.reservationAmount())));
            if(Set.of("pending","partial").contains(row.getString(3))) {
                BigDecimal used=c.orderType().equals("sell")?row.getBigDecimal(4):c.orderSide().equals("market")?row.getBigDecimal(5):c.price().multiply(row.getBigDecimal(4));
                BigDecimal remaining=c.reservationAmount().subtract(used);
                if(remaining.signum()<0) issue(run,"ORDER_OVERFILL",Long.toString(id),"nonnegative remainder",remaining.toPlainString());
                reserved.merge(user+":"+asset,remaining,BigDecimal::add);
            }
        });
        jdbc.query("SELECT user_id,mint_address,locked FROM user_balances",row->{
            String key=row.getLong(1)+":"+row.getString(2);difference(run,"RESERVATION",key,reserved.getOrDefault(key,BigDecimal.ZERO),row.getBigDecimal(3));reserved.remove(key);
        });
        reserved.forEach((key,value)->difference(run,"RESERVATION",key,value,BigDecimal.ZERO));
    }
    private List<Posting> expected(JsonNode e) throws Exception {
        String type=e.path("event_type").asText();
        if(type.equals("deposit_credited")) {
            // 정산 핸들러를 재호출하지 않는다. 독립된 입금 원본/실행 권한에서 기대 이동을 계산한다.
            UUID receipt=UUID.fromString(e.path("deposit_id").asText());
            var originals=jdbc.queryForList("""
                SELECT r.user_id,r.asset,r.amount,i.basis intent_basis,i.basis_hash intent_hash,a.basis authorization_basis,a.basis_hash authorization_hash
                 FROM bitcoin_deposit_receipts r JOIN bitcoin_credit_intents i ON i.receipt_id=r.id AND i.user_id=r.user_id AND i.asset=r.asset AND i.amount=r.amount
                 JOIN bitcoin_credit_authorizations a ON a.receipt_id=r.id AND a.user_id=r.user_id AND a.asset=r.asset AND a.amount=r.amount WHERE r.id=?
                """,receipt);
            if(originals.size()!=1)throw new IllegalArgumentException("입금 원본과 실행 권한 연결이 없습니다");var r=originals.getFirst();
            long owner=((Number)r.get("user_id")).longValue();String asset=(String)r.get("asset");BigDecimal amount=(BigDecimal)r.get("amount");
            if(!receipt.toString().equals(e.path("deposit_id").asText())||owner!=e.path("user_id").asLong()||!asset.equals(e.path("asset").asText())||amount.compareTo(money(e,"amount"))!=0
                ||!hash((String)r.get("intent_basis")).equals(r.get("intent_hash"))||!hash((String)r.get("authorization_basis")).equals(r.get("authorization_hash")))throw new IllegalArgumentException("입금 결과와 경제적 원본이 다릅니다");
            return List.of(p(owner,"PENDING",asset,amount.negate()),p(owner,"AVAILABLE",asset,amount));
        }
        if(type.equals("trade_executed")) {
            var buy=submissions.findByOrderId(e.path("buy_order_id").asLong()).orElseThrow();
            var sell=submissions.findByOrderId(e.path("sell_order_id").asLong()).orElseThrow();
            BigDecimal q=money(e,"amount"),value=q.multiply(money(e,"price"));
            BigDecimal bf=q.multiply(buy.getFeeRate()).setScale(9,RoundingMode.DOWN),sf=value.multiply(sell.getFeeRate()).setScale(9,RoundingMode.DOWN);
            if(bf.compareTo(money(e,"buyer_fee"))!=0 || sf.compareTo(money(e,"seller_fee"))!=0
                || buy.getFeeRate().compareTo(money(e,"buyer_fee_rate"))!=0 || sell.getFeeRate().compareTo(money(e,"seller_fee_rate"))!=0)
                throw new IllegalArgumentException("원본 수수료 불일치");
            var c=buy.command();long b=buy.getUserId(),s=sell.getUserId();String base=c.baseMint(),quote=c.quoteMint();
            if(b!=e.path("buyer_id").asLong() || s!=e.path("seller_id").asLong() || !base.equals(e.path("base_mint").asText()) || !quote.equals(e.path("quote_mint").asText()))
                throw new IllegalArgumentException("체결 소유자 불일치");
            BigDecimal used=c.price()==null?value:c.price().multiply(q);
            // 정산 코드를 호출하지 않고 엔진 원본·고정 정책에서 독립적으로 기대 항목을 계산한다.
            return List.of(p(b,"AVAILABLE",quote,used.subtract(value)),p(b,"LOCKED",quote,used.negate()),p(b,"AVAILABLE",base,q.subtract(bf)),
                p(s,"LOCKED",base,q.negate()),p(s,"AVAILABLE",quote,value.subtract(sf)),p(0,"FEE",base,bf),p(0,"FEE",quote,sf));
        }
        var original=submissions.findByOrderId(e.path("order_id").asLong()).orElseThrow();var c=original.command();
        String terminal=type.equals("market_order_completed")?e.path("status").asText():type.equals("order_rejected")?"rejected":"cancelled";
        String actualStatus=jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,original.getOrderId());
        if(!terminal.equals(actualStatus)) throw new IllegalArgumentException("확정 종료와 주문 상태 불일치");
        BigDecimal refund=switch(type) {
            case "order_rejected" -> c.reservationAmount();
            case "market_order_completed" -> c.reservationAmount().subtract(money(e,c.orderType().equals("buy")?"filled_quote_amount":"filled_amount"));
            case "order_cancelled" -> {
                String column=c.orderType().equals("buy")?"buy_order_id":"sell_order_id";
                BigDecimal filled=jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM trades WHERE "+column+"=?",BigDecimal.class,original.getOrderId());
                yield c.amount().subtract(filled).multiply(c.orderType().equals("buy")?c.price():BigDecimal.ONE);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 확정 결과");
        };
        return List.of(p(original.getUserId(),"AVAILABLE",c.reservationMint(),refund),p(original.getUserId(),"LOCKED",c.reservationMint(),refund.negate()));
    }
    private void checkDeposit(UUID run,JsonNode event,String payload) throws Exception {
        if(!"deposit_credited".equals(event.path("event_type").asText()))return;
        String id=event.path("event_id").asText();var rows=jdbc.queryForList("SELECT * FROM bitcoin_credited_deposits WHERE receipt_id=?",UUID.fromString(event.path("deposit_id").asText()));
        if(rows.size()!=1){issue(run,"DEPOSIT_RECEIPT_MISSING",id,"one financial receipt","missing");return;}
        var row=rows.getFirst();
        if(!id.equals(row.get("event_id"))||!event.path("engine_id").asText().equals(row.get("engine_id").toString())||!hash(payload).equals(row.get("payload_hash"))
            ||!("engine:"+id).equals(row.get("operation_key"))||!event.path("timestamp").asText().equals(row.get("event_time")))
            issue(run,"DEPOSIT_EVENT_BINDING",id,"exact durable event","different financial receipt");
    }
    private void checkTrade(UUID run,JsonNode event) {
        if(!"trade_executed".equals(event.path("event_type").asText())) return;
        String id=event.path("event_id").asText();var trades=jdbc.queryForList("SELECT * FROM trades WHERE engine_event_id=?",id);
        if(trades.size()!=1) {issue(run,"TRADE_MISSING",id,"one trade",Integer.toString(trades.size()));return;}
        var trade=trades.getFirst();
        for(String field:List.of("amount","price")) difference(run,"TRADE_VALUE",id+":"+field,money(event,field),(BigDecimal)trade.get(field));
        for(String field:List.of("buyer_id","seller_id","buy_order_id","sell_order_id")) {
            if(((Number)trade.get(field)).longValue()!=event.path(field).asLong()) issue(run,"TRADE_OWNER",id+":"+field,event.path(field).asText(),trade.get(field).toString());
        }
        for(String asset:List.of("base_mint","quote_mint")) {
            if(!event.path(asset).asText().equals(trade.get(asset))) issue(run,"TRADE_ASSET",id+":"+asset,event.path(asset).asText(),Objects.toString(trade.get(asset)));
        }
        for(String side:List.of("buyer","seller")) {
            var fees=jdbc.queryForList("SELECT fee_amount,fee_mint,fee_rate,trade_value,user_id FROM trade_fees WHERE trade_id=? AND fee_type=?",trade.get("id"),side);
            if(fees.size()!=1) issue(run,"FEE_MISSING",id+":"+side,"one fee",Integer.toString(fees.size()));
            else {
                difference(run,"FEE_VALUE",id+":"+side,money(event,side+"_fee"),(BigDecimal)fees.getFirst().get("fee_amount"));
                difference(run,"FEE_RATE",id+":"+side,money(event,side+"_fee_rate"),(BigDecimal)fees.getFirst().get("fee_rate"));
                difference(run,"FEE_TRADE_VALUE",id+":"+side,money(event,"amount").multiply(money(event,"price")),(BigDecimal)fees.getFirst().get("trade_value"));
                if(((Number)fees.getFirst().get("user_id")).longValue()!=event.path(side+"_id").asLong()) issue(run,"FEE_OWNER",id+":"+side,event.path(side+"_id").asText(),fees.getFirst().get("user_id").toString());
                String mint=event.path(side.equals("buyer")?"base_mint":"quote_mint").asText();
                if(!mint.equals(fees.getFirst().get("fee_mint"))) issue(run,"FEE_ASSET",id+":"+side,mint,fees.getFirst().get("fee_mint").toString());
            }
        }
    }

    private void checkPostings(UUID run,String key,List<Posting> expected) {
        var wanted=normalized(expected);var actual=new TreeMap<String,BigDecimal>();
        jdbc.query("SELECT owner_id,bucket,asset,delta FROM ledger_entries WHERE operation_key=?",row->{actual.put(account(row.getLong(1),row.getString(2),row.getString(3)),row.getBigDecimal(4).stripTrailingZeros());},key);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=?)",Boolean.class,key)) || !wanted.equals(actual))
            issue(run,"LEDGER_POSTINGS",key,wanted.toString(),actual.toString());
    }
    private Map<String,BigDecimal> normalized(List<Posting> postings) {
        var map=new TreeMap<String,BigDecimal>();for(var p:postings) map.merge(account(p.owner(),p.bucket(),p.asset()),p.delta(),BigDecimal::add);
        map.entrySet().removeIf(e->e.getValue().signum()==0);map.replaceAll((k,v)->v.stripTrailingZeros());return map;
    }
    private String account(long owner,String bucket,String asset) { return owner+":"+bucket+":"+asset; }
    private BigDecimal money(JsonNode e,String name) {
        if(!e.path(name).isTextual()) throw new IllegalArgumentException("금융 문자열 누락");
        return dustin.cex.shared.model.ExactAmount.requireNonNegative(new BigDecimal(e.path(name).textValue()),name);
    }
    private long integer(JsonNode page,String name) {
        if(!page.path(name).isIntegralNumber() || !page.path(name).canConvertToLong() || page.path(name).longValue()<0) throw new IllegalArgumentException("잘못된 감사 순번");
        return page.path(name).longValue();
    }
    private String hash(String payload) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
    private void difference(UUID id,String category,String ref,BigDecimal expected,BigDecimal actual) {
        if(expected.compareTo(actual)!=0) issue(id,category,ref,expected.toPlainString(),actual.toPlainString());
    }
    private void issue(UUID id,String category,String ref,String expected,String actual) {
        jdbc.update("INSERT INTO reconciliation_issues(run_id,category,reference,expected,actual) VALUES(?,?,?,?,?)",id,category,ref,expected,actual);
    }
    private void finish(UUID id,String status) {jdbc.update("UPDATE reconciliation_runs SET status=?,completed_at=clock_timestamp() WHERE id=? AND status='RUNNING'",status,id);}
}
