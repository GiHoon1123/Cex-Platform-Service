package dustin.cex.domains.bitcoin;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 승인 원본·미서명 PSBT·전역 입력 배정을 함께 보존한다. 후보 저장은 서명·송금·지급 완료가 아니다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinPayoutCandidates {
    public record Payload(UUID id,UUID reviewId,String reviewHash,String scopeHash,
            BitcoinWithdrawalPayoutBasis.Funds funds,BitcoinPayoutClient.Prepared prepared) {
        @Override public String toString(){return "BitcoinPayoutCandidate[수탁 원문 생략]";}
    }
    public record Candidate(Payload payload,String payloadHash) {
        @Override public String toString(){return "BitcoinPayoutCandidate[원문 생략]";}
    }
    public record Result(UUID attemptId,String status,Candidate candidate){}
    // 시도 식별자는 노드 호출 전부터 존재한다. 저장된 후보 재조회는 새 시도를 만들지 않는다.
    private record Start(UUID attemptId,BitcoinWithdrawalPayoutReviews.Request review,Candidate existing){}
    private final BitcoinWithdrawalPayoutReviews reviews;
    private final BitcoinPayoutClient node;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinPayoutCandidates(BitcoinWithdrawalPayoutReviews reviews,BitcoinPayoutClient node,JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit,PlatformTransactionManager manager) {
        this.reviews=reviews;this.node=node;this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 먼저 시도를 커밋하고 노드를 호출한다. 응답 후에는 현재 승인·취소·위험 범위를 다시 검사한다. */
    public Result prepare(UUID reviewId) {
        boundary();Start start=tx.execute(t->{
            var review=reviews.currentForPreparation(reviewId);var old=existing(reviewId);
            if(old!=null){
                if(!old.payload().reviewHash().equals(review.payloadHash())||!old.payload().scopeHash().equals(review.payload().basis().scopeHash())||!old.payload().funds().equals(review.payload().basis().funds()))throw held();
                return new Start(null,review,old);
            }
            UUID withdrawal=review.payload().basis().funds().withdrawalId();
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE withdrawal_id=?)",Boolean.class,withdrawal)))throw held();
            var recent=jdbc.queryForList("SELECT coalesce(r.recorded_at,a.started_at) AS stamp FROM bitcoin_payout_preparations a LEFT JOIN bitcoin_payout_preparation_results r ON r.preparation_id=a.id WHERE a.review_id=? ORDER BY a.position DESC LIMIT 1",reviewId);
            if(!recent.isEmpty()&&((Timestamp)recent.getFirst().get("stamp")).toInstant().isAfter(now().minusSeconds(30)))throw held();
            UUID attempt=UUID.randomUUID();var basis=review.payload().basis();
            jdbc.update("INSERT INTO bitcoin_payout_preparations(id,review_id,withdrawal_id,user_id,review_hash,scope_hash) VALUES(?,?,?,?,?,?)",attempt,reviewId,withdrawal,basis.funds().customerId(),review.payloadHash(),basis.scopeHash());
            audit.record(null,"BTC_PAYOUT_PREPARATION_STARTED","bitcoin-payout-preparation:"+attempt,"SUCCESS",review.payloadHash());
            return new Start(attempt,review,null);
        });
        if(start.existing()!=null)return new Result(null,"SAVED",start.existing());
        // 노드가 선택한 입력을 DB에 저장하기 전까지는 후보일 뿐이다. 이 구간에서 Core 잠금이나 자금 이동은 없다.
        BitcoinPayoutClient.Prepared prepared;
        try {
            var f=start.review().payload().basis().funds();
            prepared=node.prepare(new BitcoinPayoutClient.Command(f.withdrawalId(),f.network(),f.address(),f.scriptPubKey(),new BigDecimal(f.amount())),node.quote());
            validate(start.review(),prepared);
        }catch(ResponseStatusException unavailable){return finish(start,"NODE_UNAVAILABLE",null);}
        try {
            return tx.execute(t->{
                var review=reviews.currentForPreparation(reviewId);
                if(!review.payloadHash().equals(start.review().payloadHash())||!review.payload().basis().scopeHash().equals(start.review().payload().basis().scopeHash()))throw held();
                var old=existing(reviewId);if(old!=null)throw held();
                var f=review.payload().basis().funds();UUID id=UUID.randomUUID();
                var payload=new Payload(id,reviewId,review.payloadHash(),review.payload().basis().scopeHash(),f,prepared);String plain=json.writeValueAsString(payload);
                if(plain.getBytes(StandardCharsets.UTF_8).length>1048576)throw held();String hash=crypto.fingerprint("btc-payout-candidate-v1",plain);
                jdbc.update("""
                    INSERT INTO bitcoin_payout_candidates(id,review_id,withdrawal_id,preparation_id,user_id,network,wallet,policy,amount,native_fee,input_count,psbt_hash,unsigned_txid,key_id,ciphertext,payload_hash)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,reviewId,f.withdrawalId(),start.attemptId(),f.customerId(),prepared.network(),prepared.wallet(),prepared.policy(),new BigDecimal(prepared.amount()),new BigDecimal(prepared.nativeFee()),prepared.inputs().size(),sha(prepared.psbt()),prepared.unsignedTxid(),crypto.keyId(),crypto.encrypt(f.customerId(),id,plain.getBytes(StandardCharsets.UTF_8)),hash);
                // 여러 입력을 예약할 때 순서를 고정해 서로 다른 출금의 교차 대기를 줄인다.
                var inputs=prepared.inputs().stream().sorted(Comparator.comparing(BitcoinPayoutClient.Input::txid).thenComparingInt(BitcoinPayoutClient.Input::vout)).toList();
                for(var input:inputs){
                    jdbc.update("INSERT INTO bitcoin_payout_candidate_inputs(candidate_id,txid,vout,amount,script_pub_key) VALUES(?,?,?,?,?)",id,input.txid(),input.vout(),new BigDecimal(input.amount()),input.scriptPubKey());
                    jdbc.update("INSERT INTO bitcoin_payout_input_reservations(network,txid,vout,candidate_id) VALUES(?,?,?,?)",prepared.network(),input.txid(),input.vout(),id);
                }
                jdbc.update("INSERT INTO bitcoin_payout_active_candidates(withdrawal_id,candidate_id) VALUES(?,?)",f.withdrawalId(),id);
                audit.record(null,"BTC_PAYOUT_CANDIDATE_SAVED","bitcoin-payout-candidate:"+id,"SUCCESS",hash);
                result(start,"SAVED",id,sha(prepared.psbt()));return new Result(start.attemptId(),"SAVED",read(id));
            });
        }catch(DuplicateKeyException collision){
            // 중복 배정 제약만 업무 결과로 분류한다. 다른 SQL/감사 실패를 노드 장애로 숨기지 않는다.
            String constraint=constraint(collision);
            if(!Set.of("bitcoin_payout_input_reservations_pkey","bitcoin_payout_active_candidates_pkey","bitcoin_payout_candidates_review_id_key").contains(constraint))throw collision;
            return finish(start,"INPUT_RESERVED",sha(prepared.psbt()));
        }catch(ResponseStatusException changed){
            if(changed.getStatusCode().value()!=403&&changed.getStatusCode().value()!=409)throw changed;
            return finish(start,"BASIS_CHANGED",sha(prepared.psbt()));
        }
    }
    /** 배정 귀속만 확인하는 내부 읽기다. 취소/만료 뒤의 안전한 정리에도 원래 암호 원문이 필요하다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Candidate active(UUID id) {
        var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_payout_candidates WHERE id=?",Long.class,id);
        if(owners.size()!=1)throw held();jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owners.getFirst());return read(id);
    }
    /** 새 잠금은 현재 유효한 승인과 원본을 다시 검사한다. 원문을 읽을 수 있다는 사실만으로 허가하지 않는다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Candidate forLock(UUID id) {
        var ids=jdbc.queryForList("SELECT review_id FROM bitcoin_payout_candidates WHERE id=?",UUID.class,id);if(ids.size()!=1)throw held();
        var review=reviews.currentForPreparation(ids.getFirst());var candidate=active(id);var p=candidate.payload();
        if(!review.payloadHash().equals(p.reviewHash())||!review.payload().basis().scopeHash().equals(p.scopeHash())||!review.payload().basis().funds().equals(p.funds()))throw held();return candidate;
    }
    // 원래 승인 상태가 바뀌었더라도 외부 호출의 결과 사실은 보존한다. 현재 허가를 복구하거나 자금을 풀지 않는다.
    private Result finish(Start start,String status,String psbtHash){return tx.execute(t->{result(start,status,null,psbtHash);return new Result(start.attemptId(),status,null);});}
    private void result(Start start,String status,UUID candidate,String psbtHash){
        jdbc.update("INSERT INTO bitcoin_payout_preparation_results(preparation_id,status,candidate_id,observed_psbt_hash) VALUES(?,?,?,?)",start.attemptId(),status,candidate,psbtHash);
        audit.record(null,"BTC_PAYOUT_PREPARATION_FINISHED","bitcoin-payout-preparation:"+start.attemptId(),"SUCCESS",status);
    }
    private Candidate existing(UUID review){var ids=jdbc.queryForList("SELECT id FROM bitcoin_payout_candidates WHERE review_id=?",UUID.class,review);return ids.isEmpty()?null:read(ids.getFirst());}
    /** 내부 조회에서도 암호 원문과 검색용 열의 결합을 확인한다. */
    private Candidate read(UUID id){
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_payout_candidates WHERE id=?",id);long owner=((Number)row.get("user_id")).longValue();
        try {
            if(!crypto.keyId().equals(row.get("key_id")))throw held();
            String raw=new String(crypto.decrypt(owner,id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-payout-candidate-v1",raw).equals(row.get("payload_hash")))throw held();var p=json.readValue(raw,Payload.class);var n=p.prepared();
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
            return new Candidate(p,(String)row.get("payload_hash"));
        }catch(RuntimeException corrupt){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"지급 후보의 보존 원본을 확인할 수 없습니다");}
    }
    private void validate(BitcoinWithdrawalPayoutReviews.Request r,BitcoinPayoutClient.Prepared p){
        if(p==null)throw held();var f=r.payload().basis().funds();
        if(!p.withdrawalId().equals(f.withdrawalId())||!p.network().equals(f.network())||!BitcoinPayoutClient.POLICY.equals(p.policy())||new BigDecimal(p.amount()).compareTo(new BigDecimal(f.amount()))!=0
            ||p.inputs().isEmpty()||p.inputs().size()>BitcoinPayoutClient.MAX_INPUTS||p.psbt()==null||p.psbt().length()>700000||!p.unsignedTxid().matches("[0-9a-f]{64}"))throw held();
    }
    private static String constraint(Throwable failure){for(Throwable e=failure;e!=null;e=e.getCause())if(e instanceof org.postgresql.util.PSQLException pg&&pg.getServerErrorMessage()!=null)return Objects.toString(pg.getServerErrorMessage().getConstraint(),"");return "";}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 승인과 고정 후보·입력 배정을 확인하세요");}
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("미서명 후보 준비는 독립 트랜잭션으로 실행하세요");}
}
