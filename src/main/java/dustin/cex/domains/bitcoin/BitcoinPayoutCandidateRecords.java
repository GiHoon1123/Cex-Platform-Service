package dustin.cex.domains.bitcoin;

import dustin.cex.domains.cdd.CddCrypto;
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

/** 후보 금융 원본의 인증과 현재 배정만 읽는다. 준비·승인·노드 호출의 생명주기는 소유하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinPayoutCandidateRecords {
    private final JdbcTemplate jdbc;private final CddCrypto crypto;private final ObjectMapper json;
    public BitcoinPayoutCandidateRecords(JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json){this.jdbc=jdbc;this.crypto=crypto;this.json=json;}
    /** 내부 조회에서도 암호 원문과 검색용 열의 결합을 확인한다. */
    public BitcoinPayoutCandidates.Candidate read(UUID id){
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_payout_candidates WHERE id=?",id);long owner=((Number)row.get("user_id")).longValue();
        try {
            if(!crypto.keyId().equals(row.get("key_id")))throw held();
            String raw=new String(crypto.decrypt(owner,id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-payout-candidate-v1",raw).equals(row.get("payload_hash")))throw held();var p=json.readValue(raw,BitcoinPayoutCandidates.Payload.class);var n=p.prepared();
            if(!id.equals(p.id())||!p.reviewId().equals(row.get("review_id"))||!p.funds().withdrawalId().equals(row.get("withdrawal_id"))||p.funds().customerId()!=owner
                ||!n.network().equals(row.get("network"))||!n.wallet().equals(row.get("wallet"))||!n.policy().equals(row.get("policy"))||!n.unsignedTxid().equals(row.get("unsigned_txid"))||!sha(n.psbt()).equals(row.get("psbt_hash"))
                ||new BigDecimal(n.amount()).compareTo((BigDecimal)row.get("amount"))!=0||new BigDecimal(n.nativeFee()).compareTo((BigDecimal)row.get("native_fee"))!=0||n.inputs().size()!=((Number)row.get("input_count")).intValue())throw held();
            var review=jdbc.queryForMap("SELECT payload_hash,scope_hash FROM bitcoin_withdrawal_payout_reviews WHERE id=?",p.reviewId());
            if(!p.reviewHash().equals(review.get("payload_hash"))||!p.scopeHash().equals(review.get("scope_hash")))throw held();
            // 검색용 입력 행과 현재 배정도 암호 원문과 대조한다. 정상적인 암호문만 있고 배정이 빠진 상태도 허용하지 않는다.
            var inputs=jdbc.queryForList("SELECT * FROM bitcoin_payout_candidate_inputs WHERE candidate_id=?",id);
            if(inputs.size()!=n.inputs().size())throw held();
            for(var input:n.inputs())if(inputs.stream().noneMatch(i->input.txid().equals(i.get("txid"))&&input.vout()==((Number)i.get("vout")).intValue()
                &&input.scriptPubKey().equals(i.get("script_pub_key"))&&new BigDecimal(input.amount()).compareTo((BigDecimal)i.get("amount"))==0))throw held();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE withdrawal_id=? AND candidate_id=?)",Boolean.class,p.funds().withdrawalId(),id))
                ||jdbc.queryForObject("SELECT count(*) FROM bitcoin_payout_input_reservations WHERE candidate_id=? AND network=?",Long.class,id,n.network())!=inputs.size())throw held();
            return new BitcoinPayoutCandidates.Candidate(p,(String)row.get("payload_hash"));
        }catch(RuntimeException corrupt){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"지급 후보의 보존 원본을 확인할 수 없습니다");}
    }
    static String sha(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"보존한 지급 후보 원본을 확인하세요");}
}
