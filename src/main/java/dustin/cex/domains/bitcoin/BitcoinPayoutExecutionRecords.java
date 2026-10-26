package dustin.cex.domains.bitcoin;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
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

/** 현재 승인·후보·출처·실제 잠금 관측과 회사 비용 여력을 한 번의 고정 서명 요청에 결합한다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinPayoutExecutionRecords {
    public static final String POLICY="BTC_FIXED_PAYOUT_EXECUTION_V1";
    public static final String COMPLETION_KEY="btc:withdrawal:complete:";
    public record Payload(String policy,UUID withdrawalId,UUID reviewId,String reviewHash,UUID candidateId,String candidateHash,
        String sourceHash,UUID lockAttempt,String lockMac,BitcoinWithdrawalPayoutBasis.NativePolicy nativePolicy,
        BitcoinWithdrawalPayoutBasis.Funds funds,BitcoinPayoutClient.Prepared prepared,List<BitcoinPayoutInputSources.Certificate> sources,
        String companyExposure,Instant authorizedAt) {
        public Payload {sources=List.copyOf(sources);}
        @Override public String toString(){return "BitcoinPayoutExecution[고정 지급 원본 생략]";}
    }
    private final JdbcTemplate jdbc;private final CddCrypto crypto;private final BitcoinSignerEnvelopeCrypto signer;private final ObjectMapper json;private final AuditService audit;
    public BitcoinPayoutExecutionRecords(JdbcTemplate jdbc,CddCrypto crypto,BitcoinSignerEnvelopeCrypto signer,ObjectMapper json,AuditService audit){this.jdbc=jdbc;this.crypto=crypto;this.signer=signer;this.json=json;this.audit=audit;}
    /** 호출자는 관련 사용자 잠금과 현재 검토·입력 출처 검사를 끝낸 상태여야 한다. 키/노드는 여기서 호출하지 않는다. */
    public void create(BitcoinWithdrawalPayoutReviews.Request review,BitcoinPayoutCandidates.Candidate candidate,List<BitcoinPayoutInputSources.Certificate> sources){
        var p=candidate.payload();var f=p.funds();var prepared=p.prepared();var lock=lock(candidate);
        var archive=jdbc.queryForMap("SELECT content_mac FROM bitcoin_payout_candidate_sources WHERE candidate_id=?",p.id());
        BigDecimal exposure=new BigDecimal(prepared.nativeFee()).subtract(new BigDecimal(f.fee())).max(BigDecimal.ZERO).setScale(8);
        // 고객의 고정 수수료를 바꾸지 않는다. 회사 수수료 계정의 같은 자산 여력을 직렬화해 예약한다.
        // 거래 수수료의 양수 입금은 병렬로 가능하며, 모든 네이티브 비용 예약은 같은 잠금을 사용한다.
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('cex:btc-withdrawal-fee-budget',0))");
        BigDecimal income=jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=0 AND bucket='FEE' AND asset='BTC'",BigDecimal.class);
        BigDecimal allocated=jdbc.queryForObject("SELECT coalesce(sum(e.company_exposure),0) FROM bitcoin_payout_executions e WHERE NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.operation_key=?||e.withdrawal_id AND t.kind='BTC_WITHDRAWAL_COMPLETE')",BigDecimal.class,COMPLETION_KEY);
        if(income.subtract(allocated).compareTo(exposure)<0)throw held();
        var at=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        var body=new Payload(POLICY,f.withdrawalId(),review.payload().id(),review.payloadHash(),p.id(),candidate.payloadHash(),(String)archive.get("content_mac"),
            (UUID)lock.get("id"),(String)lock.get("content_mac"),review.payload().basis().nativePolicy(),f,prepared,sources,exposure.toPlainString(),at);
        String raw=json.writeValueAsString(body);if(raw.getBytes(StandardCharsets.UTF_8).length>1048576)throw held();String mac=crypto.fingerprint("btc-native-payout-execution-v1",raw);
        jdbc.update("""
            INSERT INTO bitcoin_payout_executions(withdrawal_id,review_id,candidate_id,user_id,network,unsigned_txid,lock_attempt,company_exposure,key_id,ciphertext,content_mac,authorized_at)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,f.withdrawalId(),review.payload().id(),p.id(),f.customerId(),prepared.network(),prepared.unsignedTxid(),lock.get("id"),exposure,crypto.keyId(),crypto.encrypt(f.customerId(),f.withdrawalId(),raw.getBytes(StandardCharsets.UTF_8)),mac,Timestamp.from(at));
        // 원래 출금 UUID가 서명 요청의 안정적 식별자다. 재확인 때 새 요청/금액/목적지를 만들지 않는다.
        String signerMac=signer.fingerprint(raw);byte[] signerCipher=signer.encrypt(f.customerId(),f.withdrawalId(),raw.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO bitcoin_payout_sign_requests(id,withdrawal_id,execution_mac,signer_key_id,ciphertext,content_mac) VALUES(?,?,?,?,?,?)",f.withdrawalId(),f.withdrawalId(),mac,signer.keyId(),signerCipher,signerMac);
        audit.record(null,"BTC_NATIVE_PAYOUT_AUTHORIZED","bitcoin-withdrawal:"+f.withdrawalId(),"SUCCESS",mac);
        audit.record(null,"BTC_NATIVE_SIGN_REQUESTED","bitcoin-sign-request:"+f.withdrawalId(),"SUCCESS",mac);
    }
    /** 과거 확인을 새 키 사용 허가로 해석하지 않는다. 동일 실행 기록의 원본 인증만 수행한다. */
    public Payload read(UUID withdrawal){
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_payout_executions WHERE withdrawal_id=?",withdrawal);
        try{
            if(!crypto.keyId().equals(row.get("key_id")))throw held();long owner=((Number)row.get("user_id")).longValue();
            String raw=new String(crypto.decrypt(owner,withdrawal,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-native-payout-execution-v1",raw).equals(row.get("content_mac")))throw held();var p=json.readValue(raw,Payload.class);
            if(!POLICY.equals(p.policy())||!withdrawal.equals(p.withdrawalId())||!p.reviewId().equals(row.get("review_id"))||!p.candidateId().equals(row.get("candidate_id"))||p.funds().customerId()!=owner
                ||!p.prepared().network().equals(row.get("network"))||!p.prepared().unsignedTxid().equals(row.get("unsigned_txid"))||!p.lockAttempt().equals(row.get("lock_attempt"))
                ||new BigDecimal(p.companyExposure()).compareTo((BigDecimal)row.get("company_exposure"))!=0||!p.authorizedAt().equals(((Timestamp)row.get("authorized_at")).toInstant()))throw held();return p;
        }catch(RuntimeException corrupt){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"고정 지급 실행 원본을 확인할 수 없습니다");}
    }
    private Map<String,Object> lock(BitcoinPayoutCandidates.Candidate candidate){
        UUID id=candidate.payload().id();var rows=jdbc.queryForList("""
            SELECT a.*,r.status,r.locked,r.key_id,r.ciphertext,r.content_mac,r.recorded_at,
             r.recorded_at BETWEEN clock_timestamp()-interval '30 seconds' AND clock_timestamp() fresh
             FROM bitcoin_payout_wallet_attempts a LEFT JOIN bitcoin_payout_wallet_results r ON r.attempt_id=a.id
             WHERE a.candidate_id=? ORDER BY a.position DESC LIMIT 1
            """,id);
        if(rows.size()!=1)throw held();var row=rows.getFirst();
        if(!"LOCK".equals(row.get("operation"))||!"OBSERVED".equals(row.get("status"))||!Boolean.TRUE.equals(row.get("locked"))||!Boolean.TRUE.equals(row.get("fresh"))
            ||!crypto.keyId().equals(row.get("key_id"))||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=? AND operation='UNLOCK') OR EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=?)",Boolean.class,id,id)))throw held();
        String raw=new String(crypto.decrypt(candidate.payload().funds().customerId(),(UUID)row.get("id"),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
        if(!crypto.fingerprint("btc-payout-wallet-observation-v1",raw).equals(row.get("content_mac")))throw held();var proof=json.readValue(raw,BitcoinPayoutCandidateWallet.Proof.class);var o=proof.observation();var p=candidate.payload().prepared();
        if(!id.equals(proof.candidateId())||!candidate.payloadHash().equals(proof.candidateHash())||!"LOCK".equals(proof.operation())||o==null||!o.locked()||!p.network().equals(o.network())||!p.wallet().equals(o.wallet())
            ||!p.inputs().stream().map(i->i.txid()+":"+i.vout()).sorted().toList().equals(o.inputs().stream().sorted().toList()))throw held();return row;
    }
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 잠금 관측과 회사 비용 여력을 확인하세요");}
}
