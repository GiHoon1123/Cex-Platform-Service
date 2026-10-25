package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.security.CustomerFinancialEligibility;
import dustin.cex.domains.travelrule.*;
import dustin.cex.shared.model.ExactAmount;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 보존된 VASP 증빙이 있는 정상 경로의 자동 반입 조건이다. 소액/개인지갑 자료 부재를 면제나 동일인 확정으로 바꾸지 않는다. */
@Service
@DependsOn({"depositMonitoringSchemaInitializer","codeVaspSchemaInitializer"})
public class BitcoinCreditBasis {
    public static final String POLICY="BTC_VERIFIED_VASP_CREDIT_V1";
    public enum Reason {CUSTOMER_NOT_ELIGIBLE,PENDING_NOT_RECOGNIZED,CHAIN_NOT_READY,VALUATION_UNAVAILABLE,MONITORING_PENDING,MONITORING_INCOMPLETE,MONITORING_REVIEW_REQUIRED,RECONCILIATION_REQUIRED,ORIGIN_NOT_VERIFIED,SOURCE_UNAVAILABLE}
    public static final class Held extends ResponseStatusException {
        private final Reason reason;
        Held(Reason reason){super(reason==Reason.SOURCE_UNAVAILABLE?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT,"현재 입금 반입 조건을 확인하세요: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    /** 엔진에 보낼 불변 경제적 원본이다. 관찰 시각/현재 정책 근거와 분리하며 금액은 이진 실수로 바꾸지 않는다. */
    public record Funds(UUID depositId,long customerId,String network,String wallet,String asset,String amount,String txid,int vout){@Override public String toString(){return "BitcoinCreditFunds[원본 생략]";}}
    public record Identity(long userVersion,UUID declarationId,UUID assessmentId,UUID kycAttemptId,String declarationMac,String identityMac,String keyId){}
    public record Chain(long observationId,long decisionId,String blockHash,long blockHeight){}
    public record Origin(UUID searchId,long searchAttemptId,UUID transferIntentId,UUID transferAttemptId,String sourceId,String keyId,String requestMac,String responseMac,long directoryAttemptId,String policyHash,CodeVaspCounterpartyPolicy.Approval counterparty){}
    public record Risk(UUID assessmentId,String cursor,String outcome){}
    public record Reconciliation(UUID runId,String startPosition,String platformHash,String nodeHash){}
    public record Valuation(long priceAttemptId,Instant referenceAt,String krwValue){}
    public record Basis(String policy,Funds funds,Identity identity,Chain chain,Origin origin,Risk risk,Reconciliation reconciliation,Valuation valuation,Instant checkedAt){@Override public String toString(){return "BitcoinCreditBasis[금융/신원 근거 생략]";}}
    private record ReceiptProof(UUID id,long user,String asset,BigDecimal amount,String network,String wallet,String txid,int vout,String address,String script){}
    private record PlatformProof(ReceiptProof receipt,Instant at,String recognizedKey,Long decisionId,String ledgerKind,List<BitcoinDepositReconciliationService.Entry> entries){}
    private record NodeProof(String contract,BitcoinDepositReader.Observation metadata,List<BitcoinDepositReader.Output> outputs){}
    private final JdbcTemplate jdbc;private final BitcoinSettings settings;private final CustomerFinancialEligibility eligibility;
    private final DepositMonitoringReview monitoring;private final CodeVaspDepositSearchReview searches;private final CodeVaspDirectoryService directory;
    private final CodeVaspCounterpartyPolicy counterparties;private final CodeVaspTransferEvidence evidence;private final CddCrypto crypto;private final ObjectMapper json;
    public BitcoinCreditBasis(JdbcTemplate jdbc,BitcoinSettings settings,CustomerFinancialEligibility eligibility,DepositMonitoringReview monitoring,CodeVaspDepositSearchReview searches,CodeVaspDirectoryService directory,CodeVaspCounterpartyPolicy counterparties,CodeVaspTransferEvidence evidence,CddCrypto crypto,ObjectMapper json){
        this.jdbc=jdbc;this.settings=settings;this.eligibility=eligibility;this.monitoring=monitoring;this.searches=searches;this.directory=directory;this.counterparties=counterparties;this.evidence=evidence;this.crypto=crypto;this.json=json;
    }
    /** 호출자가 먼저 실제 고객을 잠근다. 현재 머리를 고정하고 값만 포착하며 노드/공급사/엔진을 호출하지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Basis require(UUID receipt,User customer){
        if(!eligibility.permitted(customer))throw held(Reason.CUSTOMER_NOT_ELIGIBLE);
        // 현재 포인터 행만 공유 잠금으로 고정한다. 관찰 전체를 복사하거나 엔진 실행 상태를 잠그지 않는다.
        // 고객 잠금을 먼저 잡는 호출 순서를 지키며, 아래 검사 동안 새 체인 머리가 섞이지 않게 한다.
        var heads=jdbc.queryForList("""
            SELECT oh.observation_id FROM bitcoin_deposit_receipts r
             JOIN bitcoin_observation_heads oh ON oh.network=r.network AND oh.txid=r.txid
             JOIN bitcoin_deposit_chain_heads dh ON dh.receipt_id=r.id
             WHERE r.id=? FOR SHARE OF oh,dh
            """,receipt);if(heads.size()!=1)throw held(Reason.CHAIN_NOT_READY);
        var rows=jdbc.queryForList("""
            SELECT r.*,addr.wallet,addr.address,addr.script_pub_key,p.operation_key,p.decision_id pending_decision,
             o.id observation_id,o.status chain_status,o.confirmations,o.coinbase,o.block_hash,o.block_height,w.wallet_debit,
             o.read_started_at<=statement_timestamp() AND o.read_started_at>=statement_timestamp()-interval '2 minutes' chain_fresh,
             d.id chain_decision,d.reason chain_reason,d.policy_id chain_policy,d.observation_id decision_observation,
             cp.declaration_id,cp.assessment_id,kp.active_attempt,cd.key_id declaration_key,cd.ciphertext declaration_cipher,cd.content_mac declaration_mac,
             ca.identity_mac,ca.key_id assessment_key,ca.outcome cdd_outcome,
             v.status valuation_status,v.policy_id valuation_policy,v.price_attempt_id,v.reference_at,v.krw_value,statement_timestamp() checked_at
             FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id AND addr.user_id=r.user_id AND addr.network=r.network
             LEFT JOIN bitcoin_pending_deposits p ON p.receipt_id=r.id
             JOIN bitcoin_observation_heads oh ON oh.network=r.network AND oh.txid=r.txid JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
             LEFT JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id
             JOIN bitcoin_deposit_chain_heads dh ON dh.receipt_id=r.id JOIN bitcoin_deposit_chain_decisions d ON d.id=dh.decision_id
             JOIN cdd_profiles cp ON cp.user_id=r.user_id JOIN cdd_declarations cd ON cd.id=cp.declaration_id
             JOIN cdd_assessments ca ON ca.id=cp.assessment_id JOIN kyc_profiles kp ON kp.user_id=r.user_id
             LEFT JOIN bitcoin_deposit_valuations v ON v.receipt_id=r.id WHERE r.id=?
            """,receipt);if(rows.size()!=1)throw held(Reason.CUSTOMER_NOT_ELIGIBLE);var row=rows.getFirst();
        if(((Number)row.get("user_id")).longValue()!=customer.getId()||!settings.network.id.equals(row.get("network"))||!settings.wallet.equals(row.get("wallet"))||!"BTC".equals(row.get("asset")))throw held(Reason.SOURCE_UNAVAILABLE);
        // 강화 심사 후의 별도 금융 판단을 일반 자동 경로로 합치지 않는다. 현재 자동 정책은 표준 확인 고객만 다룬다.
        if(!"STANDARD".equals(row.get("cdd_outcome")))throw held(Reason.CUSTOMER_NOT_ELIGIBLE);
        String operation=(String)row.get("operation_key");if(!("btc:pending:"+receipt).equals(operation))throw held(Reason.PENDING_NOT_RECOGNIZED);
        if(!"CONFIRMED".equals(row.get("chain_status"))||!"CHAIN_READY".equals(row.get("chain_reason"))||!"BTC_CHAIN_V1".equals(row.get("chain_policy"))
            ||!Objects.equals(row.get("observation_id"),row.get("decision_observation"))||!Boolean.TRUE.equals(row.get("chain_fresh"))||!Boolean.FALSE.equals(row.get("wallet_debit"))||number(row,"confirmations")<6)throw held(Reason.CHAIN_NOT_READY);
        if(!Boolean.FALSE.equals(row.get("coinbase")))throw held(Reason.ORIGIN_NOT_VERIFIED);
        // BTC의 온체인 단위는 소수8자리다. UNNECESSARY는 남는 자릿수를 조용히 반올림하지 않고 오류로 만든다.
        // 원장에 같은 합계만 있는 것으로는 부족하므로 원래 업무 키와 두 상대 계정의 정확한 금액도 대조한다.
        BigDecimal amount;try{amount=ExactAmount.requirePositive((BigDecimal)row.get("amount"),"입금 금액").setScale(8,RoundingMode.UNNECESSARY);}catch(RuntimeException invalid){throw held(Reason.SOURCE_UNAVAILABLE);}
        var entries=jdbc.query("SELECT owner_id,bucket,asset,delta FROM ledger_entries WHERE operation_key=?",(r,n)->new BitcoinDepositReconciliationService.Entry(r.getLong("owner_id"),r.getString("bucket"),r.getString("asset"),r.getBigDecimal("delta")),operation);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=? AND kind='BTC_DEPOSIT_PENDING')",Boolean.class,operation))||!BitcoinDepositReconciliationService.matchesEntries(customer.getId(),"BTC",amount,entries))throw held(Reason.SOURCE_UNAVAILABLE);
        if(!"VALUED".equals(row.get("valuation_status"))||!"BTC_PENDING_KRW_REFERENCE_V1".equals(row.get("valuation_policy"))||row.get("price_attempt_id")==null||row.get("krw_value")==null)throw held(Reason.VALUATION_UNAVAILABLE);
        // 탐지 결과는 현재까지 알려진 입력을 모두 포함해야 한다. 예전 NO_SIGNAL이나 머리가 옮겨 숨은 사건을 허용하지 않는다.
        var risk=monitoring.read(customer.getId());
        if(risk.assessmentId()==null||risk.newerInputsPending()||!SmallDepositMonitoringPolicy.POLICY.equals(risk.policy()))throw held(Reason.MONITORING_PENDING);
        if(!Set.of("NO_SIGNAL","REVIEW_REQUIRED").contains(risk.outcome())||risk.unknownTransfers()!=0)throw held(Reason.MONITORING_INCOMPLETE);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_cases WHERE user_id=? AND NOT monitoring_case_closed(id))",Boolean.class,customer.getId())))throw held(Reason.MONITORING_REVIEW_REQUIRED);
        var funds=new Funds(receipt,customer.getId(),(String)row.get("network"),(String)row.get("wallet"),"BTC",amount.toPlainString(),(String)row.get("txid"),((Number)row.get("vout")).intValue());
        var reconciliation=verifyReconciliation(funds,row,amount);
        // 출처 경로가 실제로 마련된 경우에만 개인정보를 연다. 자료가 없거나 소액이라는 이유로 통과하지 않는다.
        var search=searches.read(receipt);if(!search.providerConfigured()||search.search()==null||!"FOUND".equals(search.search().status())||search.transfer()==null||search.transfer().attemptId()==null||!"DATA_RECEIVED".equals(search.transfer().status()))throw held(Reason.ORIGIN_NOT_VERIFIED);
        var peerDirectory=directory.current();if(!peerDirectory.sourceId().equals(search.sourceRef()))throw held(Reason.ORIGIN_NOT_VERIFIED);
        var locked=jdbc.queryForList("""
            SELECT a.declaration_id,a.assessment_id,sr.payload,sr.payload_hash FROM codevasp_transfer_attempts a
             JOIN codevasp_transfer_heads th ON th.intent_id=a.intent_id AND th.attempt_id=a.id
             JOIN codevasp_search_heads sh ON sh.search_id=a.search_id JOIN codevasp_search_results sr ON sr.attempt_id=sh.attempt_id
             WHERE a.id=? AND a.receipt_id=? AND a.user_id=? AND sr.status='FOUND' FOR SHARE OF th,sh
            """,search.transfer().attemptId(),receipt,customer.getId());if(locked.size()!=1)throw held(Reason.ORIGIN_NOT_VERIFIED);var source=locked.getFirst();
        if(!Objects.equals(row.get("declaration_id"),source.get("declaration_id"))||!Objects.equals(row.get("assessment_id"),source.get("assessment_id")))throw held(Reason.ORIGIN_NOT_VERIFIED);
        var verified=evidence.load(receipt,search.transfer().attemptId(),customer.getId(),search.sourceRef());
        if(!"CONSISTENT".equals(verified.comparison().status()))throw held(Reason.ORIGIN_NOT_VERIFIED);
        String searchRaw=(String)source.get("payload");if(!digest(searchRaw).equals(source.get("payload_hash")))throw held(Reason.SOURCE_UNAVAILABLE);
        var searchData=json.readTree(searchRaw);if(!verified.received().originEntityId().equals(searchData.path("entityId").asString())||!search.search().id().toString().equals(searchData.path("requestId").asString()))throw held(Reason.ORIGIN_NOT_VERIFIED);
        // 과거 목록의 성공보다 현재 법인/사업자 허용 조건을 사용한다. 관련 없는 다른 사업자 목록 변경만으로
        // 이 원본의 인증된 자료를 폐기하지는 않으며 이번에 선택한 현재 정책 지문을 근거에 보존한다.
        var selected=counterparties.select(peerDirectory.directory(),verified.received().originEntityId());
        // 저장된 성공 분류만 믿지 않고 현재 선언의 인증 암호문과 신원 지문을 실제로 다시 확인한다.
        // 키를 모르면 다른 키로 복구를 추측하지 않는다. 이름/생일은 새 근거 JSON이나 감사에 복제하지 않는다.
        try{
            if(!crypto.keyId().equals(row.get("declaration_key"))||!crypto.keyId().equals(row.get("assessment_key")))throw new IllegalStateException();
            String raw=new String(crypto.decrypt(customer.getId(),(UUID)row.get("declaration_id"),(byte[])row.get("declaration_cipher")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("declaration",raw).equals(row.get("declaration_mac")))throw new IllegalStateException();
            var declaration=json.readValue(raw,CustomerDeclaration.class).validated();String identityMac=crypto.fingerprint("identity",json.writeValueAsString(List.of(declaration.fullName(),declaration.dateOfBirth().toString(),declaration.nationality())));
            if(!identityMac.equals(row.get("identity_mac"))||!declaration.fullName().equals(verified.expected().fullName())||!declaration.dateOfBirth().equals(verified.expected().dateOfBirth()))throw new IllegalStateException();
        }catch(RuntimeException invalid){throw held(Reason.SOURCE_UNAVAILABLE);}
        return new Basis(POLICY,funds,new Identity(customer.getVersion(),(UUID)row.get("declaration_id"),(UUID)row.get("assessment_id"),(UUID)row.get("active_attempt"),(String)row.get("declaration_mac"),(String)row.get("identity_mac"),crypto.keyId()),
            new Chain(number(row,"observation_id"),number(row,"chain_decision"),(String)row.get("block_hash"),number(row,"block_height")),
            new Origin(search.search().id(),search.search().attemptId(),search.transfer().intentId(),verified.attemptId(),search.sourceRef(),verified.keyId(),verified.requestMac(),verified.responseMac(),peerDirectory.attemptId(),selected.policyHash(),selected.approval()),
            new Risk(risk.assessmentId(),risk.inputCursor().toString(),risk.outcome()),reconciliation,
            new Valuation(number(row,"price_attempt_id"),((Timestamp)row.get("reference_at")).toInstant(),((BigDecimal)row.get("krw_value")).toPlainString()),((Timestamp)row.get("checked_at")).toInstant());
    }
    private Reconciliation verifyReconciliation(Funds funds,Map<String,Object> row,BigDecimal amount){
        // 최신 성공이 아니라 최신 시작을 읽는다. 새 대사가 진행/실패 중이면 옛 MATCHED로 되돌아가지 않는다.
        var runs=jdbc.queryForList("SELECT *,snapshot_at<=statement_timestamp() AND snapshot_at>=statement_timestamp()-interval '2 minutes' AND completed_at<=statement_timestamp() fresh FROM bitcoin_current_deposit_reconciliation WHERE receipt_id=?",funds.depositId());
        if(runs.size()!=1||!"MATCHED".equals(runs.getFirst().get("status"))||!Boolean.TRUE.equals(runs.getFirst().get("fresh")))throw held(Reason.RECONCILIATION_REQUIRED);var run=runs.getFirst();
        var proofs=jdbc.queryForList("SELECT kind,payload,payload_hash FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",run.get("id"));if(proofs.size()!=2)throw held(Reason.SOURCE_UNAVAILABLE);
        try{
            Map<String,String> raw=new HashMap<>(),hashes=new HashMap<>();for(var p:proofs){String value=(String)p.get("payload");if(!digest(value).equals(p.get("payload_hash")))throw new IllegalStateException();raw.put((String)p.get("kind"),value);hashes.put((String)p.get("kind"),(String)p.get("payload_hash"));}
            // 공개 SHA는 저장 바이트의 손상 탐지일 뿐 금융 내용의 진위를 대신하지 않는다.
            // 모르는 필드를 거부하는 실제 타입으로 읽은 뒤 소유자/금액/원장/출력/체인 앵커까지 아래에서 비교한다.
            PlatformProof platform=json.readerFor(PlatformProof.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw.get("PLATFORM"));
            NodeProof node=json.readerFor(NodeProof.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw.get("NODE"));var r=platform.receipt();var n=node.metadata();
            if(!r.id().equals(funds.depositId())||r.user()!=funds.customerId()||!r.asset().equals(funds.asset())||r.amount().compareTo(amount)!=0||!r.network().equals(funds.network())||!r.wallet().equals(funds.wallet())||!r.txid().equals(funds.txid())||r.vout()!=funds.vout()
                ||!r.address().equals(row.get("address"))||!r.script().equals(row.get("script_pub_key"))||!platform.at().equals(((Timestamp)run.get("snapshot_at")).toInstant())||!platform.recognizedKey().equals(row.get("operation_key"))
                ||!platform.decisionId().equals(number(row,"pending_decision"))||!"BTC_DEPOSIT_PENDING".equals(platform.ledgerKind())||!BitcoinDepositReconciliationService.matchesEntries(funds.customerId(),funds.asset(),amount,platform.entries()))throw new IllegalStateException();
            if(!"BTC_RECEIPT_AUDIT_V1".equals(node.contract())||!n.network().equals(funds.network())||!n.txid().equals(funds.txid())||n.status()!=BitcoinDepositReader.Status.CONFIRMED||n.confirmations()<6||n.coinbase()||n.walletDebit()
                ||!Objects.equals(n.blockHash(),row.get("block_hash"))||!Objects.equals(n.blockHeight(),number(row,"block_height"))||!n.outputs().isEmpty()||node.outputs().size()!=1)throw held(Reason.RECONCILIATION_REQUIRED);
            var output=node.outputs().getFirst();if(output.vout()!=funds.vout()||!output.address().equals(row.get("address"))||!output.scriptPubKey().equals(row.get("script_pub_key"))||output.amount().compareTo(amount)!=0)throw new IllegalStateException();
            return new Reconciliation((UUID)run.get("id"),run.get("start_position").toString(),hashes.get("PLATFORM"),hashes.get("NODE"));
        }catch(Held held){throw held;}catch(RuntimeException invalid){throw held(Reason.SOURCE_UNAVAILABLE);}
    }
    static String digest(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("입금 근거 지문을 만들 수 없습니다");}}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static Held held(Reason reason){return new Held(reason);}
}
