package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;

/** 이전 결과의 상태 문자열을 지급 근거로 쓰지 않는다. 현재 원본·암호 회신·원장·알려진 위험 범위를 다시 검증한다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinWithdrawalPayoutBasis {
    public static final String POLICY="BTC_SELF_CODE_PAYOUT_REVIEW_V2";
    public record Funds(UUID withdrawalId,long customerId,long customerVersion,String network,String address,String scriptPubKey,
        String amount,String fee,String total,String quoteHash,String reservationEvent,String reservationHash,UUID engineId) {
        @Override public String toString(){return "WithdrawalPayoutFunds[금융 원본 생략]";}
    }
    public record Transfer(UUID intentId,UUID attemptId,String meaningMac,String intentMac,String attemptMac,String resultMac,
        String directorySource,long currentDirectoryAttempt,String currentPolicyHash){}
    public record Risk(UUID declarationId,UUID assessmentId,UUID kycAttempt,String cddOutcome,String identityMac,String nativeInputCursor,
        UUID monitoringAssessment,String monitoringSourceHash,String closedCasesHash){}
    // 운영자가 승인하는 자동 준비 정책의 실제 상한이다. 상수가 바뀌면 서명 범위도 달라져야 한다.
    public record NativePolicy(String preparationPolicy,String sourcePolicy,String maxRequestedSatPerVbyte,String maxMinerFee,
        int maxInputs,int minimumConfirmations,String inputScript,String signatureHash,boolean signalReplaceable){}
    public record Basis(String policy,NativePolicy nativePolicy,Funds funds,Transfer transfer,Risk risk,String scopeHash) {
        @Override public String toString(){return "WithdrawalPayoutBasis[지급·위험 근거 생략]";}
    }
    private final BitcoinWithdrawalTransferBasis source;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    public BitcoinWithdrawalPayoutBasis(BitcoinWithdrawalTransferBasis source,JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json){this.source=source;this.jdbc=jdbc;this.crypto=crypto;this.json=json;}
    /** 고객 잠금을 가진 업무만 호출한다. 원래 계정/신원의 변화·취소·새 위험은 과거 VERIFIED보다 우선한다. */
    public Basis require(User customer,UUID intentId) {
        var intents=jdbc.queryForList("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE id=?",intentId);
        if(intents.size()!=1)throw held();var intent=intents.getFirst();long owner=customer.getId();
        if(((Number)intent.get("user_id")).longValue()!=owner)throw held();
        var current=source.capture(customer,(UUID)intent.get("withdrawal_id"),(String)intent.get("peer_id"),intentId);
        var clock=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        if(!current.meaningMac().equals(intent.get("meaning_mac"))
            ||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_intents WHERE withdrawal_id=? AND position>?)",Boolean.class,current.withdrawalId(),intent.get("position"))))throw held();
        var sealedIntent=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(open(owner,intentId,intent,"key_id","ciphertext","content_mac","intent"));
        checkOriginal(sealedIntent,current);
        var rows=jdbc.queryForList("""
            SELECT a.*,r.status,r.reason,r.key_id result_key,r.ciphertext result_cipher,r.content_mac result_mac,r.recorded_at
             FROM bitcoin_withdrawal_transfer_attempts a JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id
             WHERE a.intent_id=? AND a.stage='TRANSFER' AND r.status='VERIFIED' AND r.reason IS NULL
              AND a.position=(SELECT max(position) FROM bitcoin_withdrawal_transfer_attempts WHERE intent_id=? AND stage='TRANSFER')
            """,intentId,intentId);
        if(rows.size()!=1)throw held();var row=rows.getFirst();UUID attempt=(UUID)row.get("id");
        Instant at=((Timestamp)row.get("recorded_at")).toInstant();if(at.isAfter(clock))throw held();
        var requested=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(open(owner,attempt,row,"key_id","ciphertext","content_mac","attempt"));checkOriginal(requested,current);
        var reply=json.readValue(open(owner,attempt,row,"result_key","result_cipher","result_mac","result"),CodeVaspWithdrawalAuthorizationClient.Result.class);
        var r=current.request();var data=reply.data();
        if(!"VERIFIED".equals(reply.status())||reply.reason()!=null||data==null||!intentId.equals(data.transferId())
            ||data.ciphertextHash()==null||!data.ciphertextHash().matches("[0-9a-f]{64}"))throw held();
        var sender=data.originator();var beneficiary=data.beneficiary();
        if(sender==null||beneficiary==null||!r.originatorName().equals(sender.primaryName())||sender.secondaryName()!=null||!r.originatorBirth().equals(sender.dateOfBirth())
            ||!List.of(r.originatorAccount()).equals(sender.accounts())||!r.beneficiaryName().equals(beneficiary.primaryName())||beneficiary.secondaryName()!=null
            ||!List.of(r.address()).equals(beneficiary.accounts()))throw held();
        var money=jdbc.queryForMap("""
            SELECT w.*,q.payload,e.event_id,e.payload_hash,e.engine_id FROM bitcoin_withdrawals w JOIN bitcoin_withdrawal_quotes q ON q.id=w.id
             JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=w.id AND e.event_type='withdrawal_reserved'
             WHERE w.id=?
            """,current.withdrawalId());
        var quote=json.readValue((String)money.get("payload"),BitcoinWithdrawalQuotes.Payload.class);
        // 고객의 물질화 잔액과 원장 합계, 원래 두 분개를 함께 확인한다. 다른 주문의 잠금 잔액으로 누락을 덮지 않는다.
        var book=jdbc.queryForMap("""
            SELECT b.available,b.locked,coalesce(sum(e.delta) FILTER(WHERE e.bucket='AVAILABLE'),0) ledger_available,
             coalesce(sum(e.delta) FILTER(WHERE e.bucket='LOCKED'),0) ledger_locked
             FROM user_balances b LEFT JOIN ledger_entries e ON e.owner_id=b.user_id AND e.asset=b.mint_address
             WHERE b.user_id=? AND b.mint_address='BTC' GROUP BY b.available,b.locked
            """,owner);
        if(decimal(book,"available").compareTo(decimal(book,"ledger_available"))!=0||decimal(book,"locked").compareTo(decimal(book,"ledger_locked"))!=0
            ||!Boolean.TRUE.equals(jdbc.queryForObject("""
             SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=? AND kind='BTC_WITHDRAWAL_RESERVE')
              AND(SELECT count(*) FROM ledger_entries WHERE operation_key=?)=2
              AND EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=? AND owner_id=? AND asset='BTC' AND bucket='AVAILABLE' AND delta=-?)
              AND EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=? AND owner_id=? AND asset='BTC' AND bucket='LOCKED' AND delta=?)
            """,Boolean.class,money.get("operation_key"),money.get("operation_key"),money.get("operation_key"),owner,money.get("total"),money.get("operation_key"),owner,money.get("total"))))throw held();
        var identity=jdbc.queryForMap("SELECT p.declaration_id,p.assessment_id,a.outcome,k.active_attempt FROM cdd_profiles p JOIN cdd_assessments a ON a.id=p.assessment_id JOIN kyc_profiles k ON k.user_id=p.user_id WHERE p.user_id=?",owner);
        var cases=jdbc.queryForList("""
            SELECT c.id,s.scope_version,monitoring_case_closed(c.id) closed FROM deposit_monitoring_cases c
             CROSS JOIN LATERAL monitoring_case_scope(c.id,9223372036854775807) s WHERE c.user_id=? ORDER BY c.id LIMIT 1001
            """,owner);
        if(cases.size()>1000||cases.stream().anyMatch(c->!Boolean.TRUE.equals(c.get("closed"))))throw held();
        String casesHash=crypto.fingerprint("btc-payout-closed-case-scope-v1",json.writeValueAsString(cases));
        Long cursor=jdbc.queryForObject("SELECT max(input_cursor) FROM deposit_monitoring_inputs WHERE user_id=?",Long.class,owner);
        UUID assessment=null;String monitoringHash="NO_NATIVE_INPUT";
        if(cursor!=null) {
            var heads=jdbc.queryForList("SELECT a.* FROM deposit_monitoring_heads h JOIN deposit_monitoring_assessments a ON a.id=h.assessment_id AND a.user_id=h.user_id WHERE h.user_id=?",owner);
            if(heads.size()!=1)throw held();var head=heads.getFirst();
            if(((Number)head.get("input_cursor")).longValue()!=cursor||!"NATIVE_SMALL_REFERENCE_INFLOW_V1".equals(head.get("policy_id"))
                ||!Set.of("NO_SIGNAL","REVIEW_REQUIRED").contains(head.get("outcome"))||((Number)head.get("unknown_transfers")).intValue()!=0
                ||!BitcoinWithdrawalQuotes.digest((String)head.get("source_document")).equals(head.get("source_hash")))throw held();
            assessment=(UUID)head.get("id");monitoringHash=(String)head.get("source_hash");
        }
        // 탐지 규칙의 입력이 없다는 것과 AML 전체 위험이 없다는 것은 다르다. 담당자 의견·독립 승인은 별도로 필요하다.
        var funds=new Funds(current.withdrawalId(),owner,customer.getVersion(),quote.network(),quote.address(),quote.scriptPubKey(),quote.amount(),quote.fee(),quote.total(),current.quoteHash(),(String)money.get("event_id"),(String)money.get("payload_hash"),(UUID)money.get("engine_id"));
        var transfer=new Transfer(intentId,attempt,current.meaningMac(),(String)intent.get("content_mac"),(String)row.get("content_mac"),(String)row.get("result_mac"),current.directorySource(),current.directoryAttempt(),current.policyHash());
        var risk=new Risk((UUID)identity.get("declaration_id"),(UUID)identity.get("assessment_id"),(UUID)identity.get("active_attempt"),(String)identity.get("outcome"),current.identityMac(),cursor==null?"NONE":cursor.toString(),assessment,monitoringHash,casesHash);
        var nativePolicy=new NativePolicy(BitcoinPayoutClient.POLICY,BitcoinPayoutInputSources.POLICY,BitcoinPayoutClient.MAX_RATE.toPlainString(),BitcoinPayoutClient.MAX_FEE.toPlainString(),BitcoinPayoutClient.MAX_INPUTS,6,"P2WPKH","ALL",false);
        String hash=crypto.fingerprint("btc-payout-basis-v1",json.writeValueAsString(List.of(POLICY,nativePolicy,funds,transfer.intentId(),transfer.attemptId(),transfer.meaningMac(),transfer.intentMac(),transfer.attemptMac(),transfer.resultMac(),transfer.directorySource(),transfer.currentPolicyHash(),risk)));
        return new Basis(POLICY,nativePolicy,funds,transfer,risk,hash);
    }
    private void checkOriginal(tools.jackson.databind.JsonNode original,BitcoinWithdrawalTransferBasis.Basis current) {
        var request=original.path("request");var expected=current.request();
        if(!request.path("amount").isNumber()||request.path("amount").decimalValue().compareTo(expected.amount())!=0
            ||!request.path("krwValue").isNumber()||request.path("krwValue").decimalValue().compareTo(expected.krwValue())!=0
            ||!request.path("thresholdExceeded").isBoolean()||!request.path("thresholdExceeded").booleanValue()
            ||!expected.originatorAccount().equals(request.path("originatorAccount").asString())||!expected.originatorName().equals(request.path("originatorName").asString())
            ||!expected.originatorBirth().toString().equals(request.path("originatorBirth").asString())||!expected.address().equals(request.path("address").asString())
            ||!expected.beneficiaryName().equals(request.path("beneficiaryName").asString())||!expected.peer().entityId().equals(request.path("peer").path("entityId").asString())
            ||!current.quoteHash().equals(original.path("quoteHash").asString()))throw held();
        if(!current.meaningMac().equals(original.path("meaningMac").asString())||!current.withdrawalId().toString().equals(original.path("withdrawalId").asString())
            ||original.path("customerId").asLong()!=current.customerId()||!current.request().transferId().toString().equals(original.path("request").path("transferId").asString()))throw held();
    }
    private String open(long owner,UUID id,Map<String,Object> row,String key,String cipher,String mac,String kind) {
        try {
            if(!crypto.keyId().equals(row.get(key)))throw held();
            String plain=new String(crypto.decrypt(owner,id,(byte[])row.get(cipher)),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-withdrawal-transfer-"+kind+"-v1",plain).equals(row.get(mac)))throw held();return plain;
        }catch(RuntimeException invalid){throw held();}
    }
    private static BigDecimal decimal(Map<String,Object> row,String key){return (BigDecimal)row.get(key);}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 지급 원본·암호 회신·원장·위험 범위를 다시 검토하세요");}
}
