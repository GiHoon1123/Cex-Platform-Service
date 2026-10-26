package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.security.CustomerFinancialEligibility;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 현재 정상 국내 사업자·신원·고객 원본·신청 평가를 개인정보 제공 목적에 결합한다. 지급 권한은 만들지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinWithdrawalTransferBasis {
    public static final String PURPOSE="BTC_CODE_SELF_WITHDRAWAL_REFERENCE_1M_V1";
    public record Basis(UUID withdrawalId,long customerId,long customerVersion,String quoteHash,
            UUID declarationId,UUID assessmentId,String identityMac,String meaningMac,
            String directorySource,long directoryAttempt,String policyHash,
            CodeVaspWithdrawalAuthorizationClient.Request request) {
        @Override public String toString(){return "WithdrawalTransferBasis[금융·신원 자료 생략]";}
    }
    private final JdbcTemplate jdbc;
    private final CustomerFinancialEligibility eligibility;
    private final CodeVaspDirectoryService directory;
    private final CodeVaspCounterpartyPolicy policy;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final BitcoinSettings settings;
    public BitcoinWithdrawalTransferBasis(JdbcTemplate jdbc,CustomerFinancialEligibility eligibility,
            CodeVaspDirectoryService directory,CodeVaspCounterpartyPolicy policy,CddCrypto crypto,
            ObjectMapper json,BitcoinSettings settings) {
        this.jdbc=jdbc;this.eligibility=eligibility;this.directory=directory;this.policy=policy;
        this.crypto=crypto;this.json=json;this.settings=settings;
    }

    /** 호출자가 고객을 먼저 잠근 상태에서만 포착한다. 외부 HTTP나 현재 시세 갱신은 없다. */
    public Basis capture(User customer,UUID withdrawal,String peerId,UUID transferId) {
        if(customer==null||withdrawal==null||peerId==null||transferId==null)throw held();
        long owner=customer.getId();
        var sources=jdbc.queryForList("""
            SELECT w.*,q.payload,q.payload_hash,q.user_version,v.price_attempt_id,v.source_hash,
                   v.reference_at,v.krw_value,v.amount valuation_amount,v.price valuation_price,
                   p.price source_price,p.payload_hash source_hash_original,p.status source_status
              FROM bitcoin_withdrawals w JOIN bitcoin_withdrawal_quotes q ON q.id=w.id
               JOIN bitcoin_withdrawal_valuations v ON v.withdrawal_id=w.id AND v.user_id=w.user_id
               JOIN btc_krw_price_results p ON p.attempt_id=v.price_attempt_id
               JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=w.id AND e.event_type='withdrawal_reserved'
               JOIN customer_withdrawal_authorizations a ON a.withdrawal_id=w.id AND a.user_id=w.user_id AND a.quote_hash=w.quote_hash
               JOIN customer_withdrawal_challenges c ON c.id=a.challenge_id AND c.consumed
              WHERE w.id=? AND w.user_id=? AND w.network=?
               AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations x WHERE x.withdrawal_id=w.id)
               AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events x WHERE x.withdrawal_id=w.id)
            """,withdrawal,owner,settings.network.id);
        if(sources.size()!=1)throw held();var source=sources.getFirst();
        BigDecimal amount=(BigDecimal)source.get("amount"),value=(BigDecimal)source.get("krw_value");
        // 현재 자동 개인정보 제공 범위를 제한하는 자체 목적 정책이다. 소액을 법적 면제나 무위험으로 판정하지 않는다.
        // 원래 요청 시점의 평가만 사용하며 사후 상승한 현재가로 제공 범위를 넓히지 않는다.
        if(value.compareTo(new BigDecimal("1000000"))<0)throw purposeReview();
        if(((Number)source.get("user_version")).longValue()!=customer.getVersion()
                ||!eligibility.permitted(customer)||!"SUCCESS".equals(source.get("source_status"))
                ||!source.get("source_hash").equals(source.get("source_hash_original"))
                ||amount.compareTo((BigDecimal)source.get("valuation_amount"))!=0
                ||((BigDecimal)source.get("source_price")).compareTo((BigDecimal)source.get("valuation_price"))!=0
                ||value.compareTo(amount.multiply((BigDecimal)source.get("source_price")))!=0
                ||!source.get("created_at").equals(source.get("reference_at")))throw held();
        String quoteRaw=(String)source.get("payload");
        if(!BitcoinWithdrawalQuotes.digest(quoteRaw).equals(source.get("quote_hash"))
                ||!source.get("quote_hash").equals(source.get("payload_hash")))throw held();
        final BitcoinWithdrawalQuotes.Payload quote;
        try {quote=json.readValue(quoteRaw,BitcoinWithdrawalQuotes.Payload.class);}catch(RuntimeException invalid){throw held();}
        if(!quote.id().equals(withdrawal)||!quote.customerId().equals(Long.toString(owner))||!quote.network().equals(settings.network.id)
                ||amount.compareTo(new BigDecimal(quote.amount()))!=0)throw held();
        var locked=jdbc.queryForList("SELECT locked FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,owner);
        if(locked.size()!=1||locked.getFirst().compareTo((BigDecimal)source.get("total"))<0)throw held();
        var addresses=jdbc.queryForList("SELECT address FROM bitcoin_deposit_addresses WHERE user_id=? AND network=? AND wallet=?",String.class,owner,settings.network.id,settings.wallet);
        if(addresses.size()!=1)throw held();
        var snapshot=directory.current();var selected=policy.select(snapshot.directory(),peerId);
        // 주소 소속 확인 전에는 자료를 모듈/상대에게 보내지 않는다. 현재 신원 원문은 이 내부 포착에서만 연다.
        crypto.requireConfigured();
        var people=jdbc.queryForList("""
            SELECT d.id declaration_id,d.ciphertext,d.content_mac,d.key_id,a.id assessment_id,a.identity_mac
             FROM cdd_profiles p JOIN cdd_declarations d ON d.id=p.declaration_id AND d.user_id=p.user_id
             JOIN cdd_assessments a ON a.id=p.assessment_id AND a.declaration_id=d.id AND a.user_id=p.user_id
             WHERE p.user_id=?
            """,owner);
        if(people.size()!=1)throw held();var person=people.getFirst();
        if(!crypto.keyId().equals(person.get("key_id")))throw held();UUID declaration=(UUID)person.get("declaration_id");
        final CustomerDeclaration identity;
        try {
            String plain=new String(crypto.decrypt(owner,declaration,(byte[])person.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("declaration",plain).equals(person.get("content_mac")))throw held();
            identity=json.readValue(plain,CustomerDeclaration.class).validated();
        }catch(RuntimeException invalid){throw held();}
        String identityMac=crypto.fingerprint("identity",json.writeValueAsString(List.of(identity.fullName(),identity.dateOfBirth().toString(),identity.nationality())));
        if(!identityMac.equals(person.get("identity_mac")))throw held();
        // 현재 경로는 본인 명의의 국내 CODE 이전이다. 다른 이름을 운영 입력으로 받아 추측해 합치지 않는다.
        var request=new CodeVaspWithdrawalAuthorizationClient.Request(transferId,amount,value,true,addresses.getFirst(),identity.fullName(),
            identity.dateOfBirth(),quote.address(),identity.fullName(),selected.peer(),selected.key());
        String meaning=crypto.fingerprint("btc-withdrawal-transfer-meaning-v1",json.writeValueAsString(List.of(
            PURPOSE,withdrawal,owner,customer.getVersion(),source.get("quote_hash"),quote.network(),quote.address(),quote.scriptPubKey(),
            quote.amount(),quote.fee(),quote.total(),source.get("price_attempt_id"),source.get("source_hash"),
            ((Timestamp)source.get("reference_at")).toInstant(),value.toPlainString(),declaration,identityMac,addresses.getFirst(),
            snapshot.sourceId(),selected.peer().entityId(),selected.peer().legalName(),selected.peer().country(),selected.peer().alliance())));
        return new Basis(withdrawal,owner,customer.getVersion(),(String)source.get("quote_hash"),declaration,
            (UUID)person.get("assessment_id"),identityMac,meaning,snapshot.sourceId(),snapshot.attemptId(),selected.policyHash(),request);
    }
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 출금 원본·확정 예약·본인 신원·허용 상대·신청 평가를 확인하세요");}
    private static ResponseStatusException purposeReview(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 자동 개인정보 제공 목적 범위 밖입니다. 별도 이전 검토가 필요합니다");}
}
