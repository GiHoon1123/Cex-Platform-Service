package dustin.cex.domains.edd;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import tools.jackson.databind.ObjectMapper;

/** 기존 체결의 자금 사용 결과만 읽는다. 최초 반입·예약/해제를 외부 자금 유입으로 바꿔 설명하지 않는다. */
@Component
public class FundsTradeLink {
    public enum Scope { ONBOARDING, RECORDED_TRADE }
    public record Target(Scope scope,String eventId) {
        public Target validated() {
            if(scope==null || (scope==Scope.ONBOARDING?eventId!=null:eventId==null || !eventId.matches("[0-9a-fA-F-]{36}:[1-9][0-9]*:[0-9]+") || eventId.length()>120)) throw denied();
            if(scope==Scope.RECORDED_TRADE)try {UUID.fromString(eventId.split(":")[0]);}catch(IllegalArgumentException e){throw denied();}return this;
        }
    }
    public record Link(String eventId,String operationKey,String spentAsset,BigDecimal spentAmount,Instant recordedAt,String inboxHash,String ledgerDigest) {}
    private final JdbcTemplate jdbc;private final DiditWebhookVerifier verifier;private final ObjectMapper json;
    public FundsTradeLink(JdbcTemplate jdbc,DiditWebhookVerifier verifier,ObjectMapper json) {this.jdbc=jdbc;this.verifier=verifier;this.json=json;}
    public Link read(long user,Target target) {
        if(target==null) throw denied();target.validated();if(target.scope()==Scope.ONBOARDING)return null;
        var rows=jdbc.queryForList("""
            SELECT l.operation_key,l.created_at,r.payload_hash FROM ledger_transactions l
             JOIN engine_event_receipts r ON l.operation_key='engine:'||r.event_id
             JOIN trades t ON t.engine_event_id=r.event_id AND (t.buyer_id=? OR t.seller_id=?) AND t.buyer_id<>t.seller_id
            WHERE r.event_id=? AND l.kind='TRADE'
            """,user,user,target.eventId());
        if(rows.size()!=1)throw denied();var row=rows.getFirst();String operation=(String)row.get("operation_key");
        var entries=jdbc.queryForList("SELECT asset,bucket,delta FROM ledger_entries WHERE operation_key=? AND owner_id=? ORDER BY asset,bucket",operation,user);
        var totals=new TreeMap<String,BigDecimal>();for(var e:entries) totals.merge((String)e.get("asset"),(BigDecimal)e.get("delta"),BigDecimal::add);
        var spent=totals.entrySet().stream().filter(e->e.getValue().signum()<0).toList();
        if(spent.size()!=1 || totals.values().stream().filter(v->v.signum()>0).count()!=1) throw denied();
        // 반환된 예약분을 별도 지출로 세지 않고 같은 자산의 AVAILABLE+LOCKED 순변화를 사용한다.
        String digest=verifier.digest(json.valueToTree(entries));
        return new Link(target.eventId(),operation,spent.getFirst().getKey(),spent.getFirst().getValue().negate().stripTrailingZeros(),((Timestamp)row.get("created_at")).toInstant(),(String)row.get("payload_hash"),digest);
    }
    private static ResponseStatusException denied() {return new ResponseStatusException(HttpStatus.CONFLICT,"본인에게 확정 반영된 체결과 원장 근거가 필요합니다");}
}
