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
    private final BitcoinPayoutInputSources inputSources;
    private final BitcoinPayoutCandidateRecords records;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinPayoutCandidates(BitcoinWithdrawalPayoutReviews reviews,BitcoinPayoutClient node,BitcoinPayoutInputSources inputSources,BitcoinPayoutCandidateRecords records,JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit,PlatformTransactionManager manager) {
        this.reviews=reviews;this.node=node;this.inputSources=inputSources;this.records=records;this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;
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
                // 출금 관련 운영자와 선택 입력의 원래 고객을 모두 수집해 같은 숫자 순서로 잠근다.
                inputSources.lockForReview(reviewId,prepared.network(),prepared.wallet(),prepared.inputs().stream().map(i->new BitcoinPayoutInputSources.Point(i.txid(),i.vout())).toList());
                var review=reviews.currentForPreparation(reviewId);
                if(!review.payloadHash().equals(start.review().payloadHash())||!review.payload().basis().scopeHash().equals(start.review().payload().basis().scopeHash()))throw held();
                var old=existing(reviewId);if(old!=null)throw held();
                var certificates=inputSources.require(prepared);var f=review.payload().basis().funds();UUID id=UUID.randomUUID();
                var payload=new Payload(id,reviewId,review.payloadHash(),review.payload().basis().scopeHash(),f,prepared);String plain=json.writeValueAsString(payload);
                if(plain.getBytes(StandardCharsets.UTF_8).length>1048576)throw held();String hash=crypto.fingerprint("btc-payout-candidate-v1",plain);
                jdbc.update("""
                    INSERT INTO bitcoin_payout_candidates(id,review_id,withdrawal_id,preparation_id,user_id,network,wallet,policy,amount,native_fee,input_count,psbt_hash,unsigned_txid,key_id,ciphertext,payload_hash)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,reviewId,f.withdrawalId(),start.attemptId(),f.customerId(),prepared.network(),prepared.wallet(),prepared.policy(),new BigDecimal(prepared.amount()),new BigDecimal(prepared.nativeFee()),prepared.inputs().size(),sha(prepared.psbt()),prepared.unsignedTxid(),crypto.keyId(),crypto.encrypt(f.customerId(),id,plain.getBytes(StandardCharsets.UTF_8)),hash);
                inputSources.save(id,f.customerId(),certificates);
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
        }catch(BitcoinPayoutInputSources.Held unverified){return finish(start,"INPUT_UNVERIFIED",sha(prepared.psbt()));
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
        var rows=jdbc.queryForList("SELECT review_id,network,wallet FROM bitcoin_payout_candidates WHERE id=?",id);if(rows.size()!=1)throw held();var row=rows.getFirst();
        var points=jdbc.queryForList("SELECT txid,vout FROM bitcoin_payout_candidate_inputs WHERE candidate_id=?",id).stream().map(i->new BitcoinPayoutInputSources.Point((String)i.get("txid"),((Number)i.get("vout")).intValue())).toList();
        inputSources.lockForReview((UUID)row.get("review_id"),(String)row.get("network"),(String)row.get("wallet"),points);
        var review=reviews.currentForPreparation((UUID)row.get("review_id"));var candidate=active(id);var p=candidate.payload();
        if(!review.payloadHash().equals(p.reviewHash())||!review.payload().basis().scopeHash().equals(p.scopeHash())||!review.payload().basis().funds().equals(p.funds()))throw held();
        var current=inputSources.require(p.prepared());
        // 정상 재관찰/탐지 위치는 바뀔 수 있다. 출처의 불변 원본은 같아야 하고 현재 위험 검사는 새로 통과해야 한다.
        var stored=inputSources.stored(id,p.funds().customerId(),p.prepared());
        if(current.size()!=stored.size())throw held();for(int i=0;i<current.size();i++)if(!current.get(i).origin().equals(stored.get(i).origin()))throw held();return candidate;
    }
    // 원래 승인 상태가 바뀌었더라도 외부 호출의 결과 사실은 보존한다. 현재 허가를 복구하거나 자금을 풀지 않는다.
    private Result finish(Start start,String status,String psbtHash){return tx.execute(t->{result(start,status,null,psbtHash);return new Result(start.attemptId(),status,null);});}
    private void result(Start start,String status,UUID candidate,String psbtHash){
        jdbc.update("INSERT INTO bitcoin_payout_preparation_results(preparation_id,status,candidate_id,observed_psbt_hash) VALUES(?,?,?,?)",start.attemptId(),status,candidate,psbtHash);
        audit.record(null,"BTC_PAYOUT_PREPARATION_FINISHED","bitcoin-payout-preparation:"+start.attemptId(),"SUCCESS",status);
    }
    private Candidate existing(UUID review){var ids=jdbc.queryForList("SELECT id FROM bitcoin_payout_candidates WHERE review_id=?",UUID.class,review);return ids.isEmpty()?null:read(ids.getFirst());}
    private Candidate read(UUID id){return records.read(id);}
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
