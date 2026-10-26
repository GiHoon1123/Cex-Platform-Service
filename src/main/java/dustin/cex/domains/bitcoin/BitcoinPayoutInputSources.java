package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 선택된 실제 입력이 확정 반입한 수탁 자금인지 확인한다. 지갑 소유·소액·고객 잔액만으로 출처를 추측하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinPayoutInputSources {
    public static final String POLICY="BTC_SETTLED_DEPOSIT_INPUT_V1";
    public record Point(String txid,int vout) {
        public Point {if(txid==null||!txid.matches("[0-9a-f]{64}")||vout<0)throw held(Reason.INPUT_MISMATCH);}
        @Override public String toString(){return "BitcoinInputPoint[원본 생략]";}
    }
    public record Origin(UUID receiptId,long customerId,String authorizationHash,String creditEvent,String creditHash,String originMac) {
        @Override public String toString(){return "BitcoinInputOrigin[원본 생략]";}
    }
    public record Chain(long observationId,long decisionId,String blockHash,long blockHeight){}
    public record Risk(UUID assessmentId,String sourceHash,String closedCasesHash){}
    public record Certificate(Point point,String amount,String scriptPubKey,Origin origin,Chain chain,Risk risk) {
        @Override public String toString(){return "BitcoinInputCertificate[원본 생략]";}
    }
    public enum Reason {UNKNOWN_INPUT,UNSETTLED_INPUT,INPUT_MISMATCH,CHAIN_NOT_READY,CHAIN_RISK,MONITORING_PENDING,MONITORING_REVIEW_REQUIRED,SOURCE_UNAVAILABLE}
    public static final class Held extends ResponseStatusException {
        private final Reason reason;
        private Held(Reason reason){super(reason==Reason.SOURCE_UNAVAILABLE?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT,"현재 선택 입력의 확정 출처를 확인하세요: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final DepositMonitoringReview monitoring;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final BitcoinSettings settings;
    private final AuditService audit;
    public BitcoinPayoutInputSources(JdbcTemplate jdbc,UserRepository users,DepositMonitoringReview monitoring,CddCrypto crypto,ObjectMapper json,BitcoinSettings settings,AuditService audit){
        this.jdbc=jdbc;this.users=users;this.monitoring=monitoring;this.crypto=crypto;this.json=json;this.settings=settings;this.audit=audit;
    }
    /** 암호 원문을 열기 전에 관련 인물 전체를 수집해 잠근다. 다른 고객의 수탁 입력도 같은 순서 규칙을 지킨다. */
    public void lockForReview(UUID reviewId,String network,String wallet,List<Point> points){
        scope(network,wallet,points);
        var reviews=jdbc.queryForList("SELECT user_id,requester_id,checker_id FROM bitcoin_withdrawal_payout_reviews WHERE id=?",reviewId);
        if(reviews.size()!=1)throw held(Reason.SOURCE_UNAVAILABLE);var ids=new TreeSet<Long>();
        for(Object id:reviews.getFirst().values())ids.add(((Number)id).longValue());
        // 현재 포인터나 개인 원문은 아직 읽지 않는다. 출금/입금의 관련 사용자 ID만 조회한다.
        for(var point:points)for(var row:jdbc.queryForList("SELECT r.user_id FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id AND a.user_id=r.user_id AND a.network=r.network WHERE r.network=? AND a.wallet=? AND r.txid=? AND r.vout=?",network,wallet,point.txid(),point.vout()))ids.add(((Number)row.get("user_id")).longValue());
        for(long id:ids)users.lockById(id).orElseThrow(()->held(Reason.SOURCE_UNAVAILABLE));
    }
    /** 호출 업무는 현재 승인 목적과 관련 고객 잠금을 먼저 확인한다. 이 결과가 금융 실행이나 외부 심사 인증은 아니다. */
    public List<Certificate> require(BitcoinPayoutClient.Prepared prepared){
        var points=prepared.inputs().stream().map(i->new Point(i.txid(),i.vout())).toList();scope(prepared.network(),prepared.wallet(),points);
        var result=new ArrayList<Certificate>();
        for(var input:prepared.inputs())result.add(capture(prepared.network(),prepared.wallet(),input));return List.copyOf(result);
    }
    /** 새 후보의 출처 판정을 별도 불변 증거로 보존한다. 금융 원본의 과거 형식을 다시 쓰지 않는다. */
    public void save(UUID candidate,long owner,List<Certificate> certificates){
        String plain=json.writeValueAsString(certificates);if(plain.getBytes(StandardCharsets.UTF_8).length>131072)throw held(Reason.SOURCE_UNAVAILABLE);
        String mac=crypto.fingerprint("btc-payout-input-certificates-v1",plain);
        jdbc.update("INSERT INTO bitcoin_payout_candidate_sources(candidate_id,key_id,ciphertext,content_mac) VALUES(?,?,?,?)",candidate,crypto.keyId(),crypto.encrypt(owner,candidate,plain.getBytes(StandardCharsets.UTF_8)),mac);
        audit.record(null,"BTC_PAYOUT_INPUT_SOURCES_SAVED","bitcoin-payout-candidate:"+candidate,"SUCCESS",mac);
    }
    /** 과거 금융 원본의 정리와 새 지급 허가를 구분한다. 새 잠금은 이 별도 증거가 없으면 진행하지 못한다. */
    public List<Certificate> stored(UUID candidate,long owner,BitcoinPayoutClient.Prepared p){
        var rows=jdbc.queryForList("SELECT * FROM bitcoin_payout_candidate_sources WHERE candidate_id=?",candidate);if(rows.size()!=1)throw held(Reason.SOURCE_UNAVAILABLE);var row=rows.getFirst();
        try {
            if(!crypto.keyId().equals(row.get("key_id")))throw held(Reason.SOURCE_UNAVAILABLE);
            String plain=new String(crypto.decrypt(owner,candidate,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-payout-input-certificates-v1",plain).equals(row.get("content_mac")))throw held(Reason.SOURCE_UNAVAILABLE);
            List<Certificate> certificates=json.readValue(plain,json.getTypeFactory().constructCollectionType(List.class,Certificate.class));verifyStored(p,certificates);return List.copyOf(certificates);
        }catch(RuntimeException corrupt){throw held(Reason.SOURCE_UNAVAILABLE);}
    }
    /** 저장한 출처 원본은 노드 재조회 없이 인증한다. 현재 위험 허가는 별도 require 호출에서 다시 확인한다. */
    public void verifyStored(BitcoinPayoutClient.Prepared p,List<Certificate> certificates){
        if(certificates==null||certificates.size()!=p.inputs().size())throw held(Reason.SOURCE_UNAVAILABLE);
        for(int i=0;i<p.inputs().size();i++){
            var input=p.inputs().get(i);var c=certificates.get(i);var o=c.origin();
            if(!c.point().equals(new Point(input.txid(),input.vout()))||!c.amount().equals(input.amount())||!c.scriptPubKey().equals(input.scriptPubKey())||o==null||o.customerId()<=0
                ||!o.originMac().equals(originMac(p.network(),p.wallet(),c.point(),c.amount(),c.scriptPubKey(),o.receiptId(),o.customerId(),o.authorizationHash(),o.creditEvent(),o.creditHash())))throw held(Reason.SOURCE_UNAVAILABLE);
        }
    }
    private Certificate capture(String network,String wallet,BitcoinPayoutClient.Input input){
        var point=new Point(input.txid(),input.vout());var receipts=receipt(network,wallet,point);if(receipts.size()!=1)throw held(Reason.UNKNOWN_INPUT);
        var r=receipts.getFirst();UUID receipt=(UUID)r.get("id");long owner=number(r,"user_id");
        if(!input.scriptPubKey().equals(r.get("script_pub_key"))||!input.address().equals(r.get("address"))||input.amount()==null||!input.amount().matches("[0-9]{1,8}(?:\\.[0-9]{1,8})?")
            ||new BigDecimal(input.amount()).compareTo((BigDecimal)r.get("amount"))!=0||!"BTC".equals(r.get("asset")))throw held(Reason.INPUT_MISMATCH);
        var rows=jdbc.queryForList("""
            SELECT a.*,c.event_id,c.engine_id,c.payload_hash,c.operation_key,e.engine_id received_engine,e.payload_hash received_hash
             FROM bitcoin_credit_authorizations a JOIN bitcoin_credited_deposits c ON c.receipt_id=a.receipt_id
             JOIN engine_event_receipts e ON e.event_id=c.event_id WHERE a.receipt_id=?
            """,receipt);
        if(rows.size()!=1)throw held(Reason.UNSETTLED_INPUT);var credit=rows.getFirst();
        if(number(credit,"user_id")!=owner||!"BTC".equals(credit.get("asset"))||!BitcoinCreditBasis.POLICY.equals(credit.get("policy_id"))
            ||((BigDecimal)r.get("amount")).compareTo((BigDecimal)credit.get("amount"))!=0||!credit.get("engine_id").toString().equals(credit.get("received_engine"))
            ||!credit.get("payload_hash").equals(credit.get("received_hash"))||!("engine:"+credit.get("event_id")).equals(credit.get("operation_key")))throw held(Reason.SOURCE_UNAVAILABLE);
        String raw=(String)credit.get("basis");if(!BitcoinCreditBasis.digest(raw).equals(credit.get("basis_hash")))throw held(Reason.SOURCE_UNAVAILABLE);
        var basis=json.readValue(raw,BitcoinCreditBasis.Basis.class);var original=basis.funds();
        if(!original.depositId().equals(receipt)||original.customerId()!=owner||!network.equals(original.network())||!wallet.equals(original.wallet())||!"BTC".equals(original.asset())
            ||!point.txid().equals(original.txid())||point.vout()!=original.vout()||new BigDecimal(input.amount()).compareTo(new BigDecimal(original.amount()))!=0||!BitcoinCreditBasis.POLICY.equals(basis.policy()))throw held(Reason.SOURCE_UNAVAILABLE);
        // 입금 원장 확정은 해당 원본의 두 분개로 확인한다. 원래 고객이 거래한 뒤 잔액이 달라져도 수탁 자금 출처는 보존된다.
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=? AND kind='BTC_DEPOSIT_CREDIT')
             AND(SELECT count(*) FROM ledger_entries WHERE operation_key=?)=2
             AND EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=? AND owner_id=? AND asset='BTC' AND bucket='PENDING' AND delta=-?)
             AND EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=? AND owner_id=? AND asset='BTC' AND bucket='AVAILABLE' AND delta=?)
            """,Boolean.class,credit.get("operation_key"),credit.get("operation_key"),credit.get("operation_key"),owner,r.get("amount"),credit.get("operation_key"),owner,r.get("amount"))))throw held(Reason.SOURCE_UNAVAILABLE);
        var chains=jdbc.queryForList("""
            SELECT o.*,d.id decision_id,d.reason,d.policy_id,d.observation_id decision_observation
             FROM bitcoin_observation_heads h JOIN bitcoin_transaction_observations o ON o.id=h.observation_id
             JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND NOT w.wallet_debit
             JOIN bitcoin_deposit_chain_heads ch ON ch.receipt_id=? JOIN bitcoin_deposit_chain_decisions d ON d.id=ch.decision_id
             WHERE h.network=? AND h.txid=? AND o.wallet=? AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp()
            """,receipt,network,point.txid(),wallet);
        if(chains.size()!=1)throw held(Reason.CHAIN_NOT_READY);var chain=chains.getFirst();
        if(!"CONFIRMED".equals(chain.get("status"))||!Boolean.FALSE.equals(chain.get("coinbase"))||number(chain,"confirmations")<6
            ||!"CHAIN_READY".equals(chain.get("reason"))||!"BTC_CHAIN_V1".equals(chain.get("policy_id"))||!chain.get("id").equals(chain.get("decision_observation")))throw held(Reason.CHAIN_NOT_READY);
        var outputs=json.reader(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(chain.get("outputs").toString());if(!outputs.isArray())throw held(Reason.CHAIN_NOT_READY);int matching=0;
        for(var output:outputs){if(!output.path("vout").isIntegralNumber()||!output.path("vout").canConvertToInt())throw held(Reason.CHAIN_NOT_READY);if(output.path("vout").intValue()==point.vout()){
            if(!input.address().equals(output.path("address").asString())||!input.scriptPubKey().equals(output.path("scriptPubKey").asString())
                ||!output.path("amount").isNumber()||new BigDecimal(input.amount()).compareTo(output.path("amount").decimalValue())!=0)throw held(Reason.CHAIN_NOT_READY);matching++;
        }}
        if(matching!=1)throw held(Reason.CHAIN_NOT_READY);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE receipt_id=?)",Boolean.class,receipt)))throw held(Reason.CHAIN_RISK);
        var risk=monitoring.read(owner);
        if(risk.assessmentId()==null||risk.newerInputsPending()||!SmallDepositMonitoringPolicy.POLICY.equals(risk.policy())||risk.unknownTransfers()!=0
            ||!Set.of("NO_SIGNAL","REVIEW_REQUIRED").contains(risk.outcome()))throw held(Reason.MONITORING_PENDING);
        var riskRow=jdbc.queryForMap("SELECT source_document,source_hash FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",risk.assessmentId(),owner);
        if(!BitcoinCreditBasis.digest((String)riskRow.get("source_document")).equals(riskRow.get("source_hash")))throw held(Reason.SOURCE_UNAVAILABLE);
        var cases=jdbc.queryForList("SELECT c.id,s.scope_version,monitoring_case_closed(c.id) closed FROM deposit_monitoring_cases c CROSS JOIN LATERAL monitoring_case_scope(c.id,9223372036854775807) s WHERE c.user_id=? ORDER BY c.id LIMIT 1001",owner);
        if(cases.size()>1000||cases.stream().anyMatch(c->!Boolean.TRUE.equals(c.get("closed"))))throw held(Reason.MONITORING_REVIEW_REQUIRED);
        String originMac=originMac(network,wallet,point,input.amount(),input.scriptPubKey(),receipt,owner,(String)credit.get("basis_hash"),(String)credit.get("event_id"),(String)credit.get("payload_hash"));
        return new Certificate(point,input.amount(),input.scriptPubKey(),new Origin(receipt,owner,(String)credit.get("basis_hash"),(String)credit.get("event_id"),(String)credit.get("payload_hash"),originMac),
            new Chain(number(chain,"id"),number(chain,"decision_id"),(String)chain.get("block_hash"),number(chain,"block_height")),
            new Risk(risk.assessmentId(),(String)riskRow.get("source_hash"),crypto.fingerprint("btc-native-input-cases-v1",json.writeValueAsString(cases))));
    }
    private String originMac(String network,String wallet,Point point,String amount,String script,UUID receipt,long owner,String authorization,String event,String hash){
        return crypto.fingerprint("btc-native-input-origin-v1",json.writeValueAsString(List.of(POLICY,network,wallet,point,amount,script,receipt,owner,authorization,event,hash)));
    }
    private List<Map<String,Object>> receipt(String network,String wallet,Point point){return jdbc.queryForList("SELECT r.*,a.address,a.script_pub_key FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id AND a.user_id=r.user_id AND a.network=r.network WHERE r.network=? AND a.wallet=? AND r.txid=? AND r.vout=?",network,wallet,point.txid(),point.vout());}
    private void scope(String network,String wallet,List<Point> points){if(!settings.network.id.equals(network)||!settings.wallet.equals(wallet)||points==null||points.isEmpty()||points.size()>BitcoinPayoutClient.MAX_INPUTS||new HashSet<>(points).size()!=points.size())throw held(Reason.INPUT_MISMATCH);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Held held(Reason reason){return new Held(reason);}
}
