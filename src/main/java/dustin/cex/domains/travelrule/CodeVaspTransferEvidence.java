package dustin.cex.domains.travelrule;

import dustin.cex.domains.cdd.CddCrypto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 인가된 업무 내부에서 요청·응답의 인증 암호화와 HMAC·관계를 재검증한다. 이 메서드는 독립 HTTP 권한 경계가 아니다. */
@Service
public class CodeVaspTransferEvidence {
    public record Expected(String fullName,LocalDate dateOfBirth,String address){
        @Override public String toString(){return "ExpectedTransferCustomer[개인정보 생략]";}
    }
    public record Verified(UUID attemptId,UUID receiptId,long owner,String keyId,String requestMac,String responseMac,
        Expected expected,CodeVaspTransferDataClient.Data received,CodeVaspTransferDetailAssessment.Decision comparison){
        @Override public String toString(){return "VerifiedTransferEvidence[개인정보 생략]";}
    }
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    public CodeVaspTransferEvidence(JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json){this.jdbc=jdbc;this.crypto=crypto;this.json=json;}

    /** 현재 접속 주체와 해당 입금의 최신 수신 시도에만 접근한다. 만료된 요청의 상대 키를 현재용 요청 생성자에 재주입하지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Verified load(UUID receipt,UUID attempt,long owner,String source){
        var rows=jdbc.queryForList("""
            SELECT a.id,a.user_id,a.peer_id,a.key_id request_key,a.ciphertext request_cipher,a.content_mac request_mac,
             r.key_id response_key,r.ciphertext response_cipher,r.content_mac response_mac,
             s.id search_id,b.txid,b.amount,addr.address,
             d.policy_id,d.status assessment_status,d.reason assessment_reason
             FROM codevasp_transfer_attempts a JOIN codevasp_transfer_intents i ON i.id=a.intent_id AND i.receipt_id=a.receipt_id
              JOIN codevasp_deposit_searches s ON s.id=a.search_id AND s.id=i.search_id
              JOIN bitcoin_deposit_receipts b ON b.id=a.receipt_id AND b.user_id=a.user_id
              JOIN bitcoin_deposit_addresses addr ON addr.id=b.address_id AND addr.user_id=b.user_id AND addr.network=b.network
              JOIN codevasp_transfer_heads h ON h.intent_id=a.intent_id AND h.attempt_id=a.id
              JOIN codevasp_transfer_results r ON r.attempt_id=a.id AND r.status='DATA_RECEIVED'
              JOIN codevasp_transfer_detail_assessments d ON d.attempt_id=a.id
             WHERE a.id=? AND a.receipt_id=? AND a.user_id=? AND s.source_id=?
            """,attempt,receipt,owner,source);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"검토할 정보 수신 근거가 없습니다");
        var row=rows.getFirst();crypto.requireConfigured();
        try{
            if(!crypto.keyId().equals(row.get("request_key"))||!crypto.keyId().equals(row.get("response_key")))throw unavailable();
            String request=new String(crypto.decrypt(owner,attempt,(byte[])row.get("request_cipher")),StandardCharsets.UTF_8);
            String response=new String(crypto.decrypt(owner,attempt,(byte[])row.get("response_cipher")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("codevasp-transfer-attempt-v1",request).equals(row.get("request_mac"))
                ||!crypto.fingerprint("codevasp-transfer-result-v1",response).equals(row.get("response_mac")))throw unavailable();
            var prepared=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(request).path("prepared");
            var query=prepared.path("request").path("query");var reply=json.readValue(response,CodeVaspTransferDataClient.Result.class);var data=reply.data();
            // 암호문이 진짜여도 다른 입금/검색의 자료를 결합하면 안 된다. 요청·응답의 경제적 대상을 실제 불변 원본과 대조한다.
            if(!"NORMAL".equals(reply.status())||data==null||!row.get("search_id").toString().equals(query.path("requestId").asString())
                ||!row.get("txid").equals(query.path("txid").asString())||!row.get("address").equals(query.path("beneficiaryAddress").asString())
                ||!row.get("search_id").equals(data.transferId())||!row.get("peer_id").equals(data.originEntityId())
                ||!prepared.path("request").path("expectedAddressAmount").isNumber()
                ||prepared.path("request").path("expectedAddressAmount").decimalValue().compareTo((BigDecimal)row.get("amount"))!=0
                ||data.amount()==null||data.amount().compareTo((BigDecimal)row.get("amount"))!=0)throw unavailable();
            var expected=new Expected(prepared.path("request").path("recipientName").asString(),LocalDate.parse(prepared.path("dateOfBirth").asString()),(String)row.get("address"));
            var checked=CodeVaspTransferDetailAssessment.compare(expected.fullName(),expected.dateOfBirth(),expected.address(),data);
            // 평문 분류 문자열만 신뢰하지 않는다. 인증된 요청·응답으로 재계산해 저장된 정책·상태·사유와 대조한다.
            if(!CodeVaspTransferDetailAssessment.POLICY.equals(row.get("policy_id"))||!checked.status().equals(row.get("assessment_status"))||!checked.reason().equals(row.get("assessment_reason")))throw unavailable();
            return new Verified(attempt,receipt,owner,crypto.keyId(),(String)row.get("request_mac"),(String)row.get("response_mac"),expected,data,checked);
        }catch(RuntimeException invalid){
            // 공급사 원문·암호문·인물 필드를 예외 원인이나 응답에 붙이지 않는다. 옛 키나 다른 DB로 우회하지 않는다.
            throw unavailable();
        }
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"정보 수신 근거를 검증할 수 없습니다");}
}
