package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.*;
import dustin.cex.domains.travelrule.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 출금 상대 확인을 주소 조회와 개인정보 제공의 두 단계로 확정한다. 원장·엔진·체인 지급 권한은 소유하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalTransferReview {
    public record State(UUID intentId,UUID withdrawalId,UUID attemptId,String stage,String status,String reason,Instant createdAt,Instant expiresAt){}
    private record Access(User actor,User customer){}
    private record Started(UUID id,UUID intent,long owner,String stage,BitcoinWithdrawalTransferBasis.Basis basis){}
    private static final Set<String> ADDRESS_REASONS=Set.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN");
    private static final Set<String> TRANSFER_REASONS=Set.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","INPUT_NAME_MISMATCHED","DOB_MISMATCHED","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN");
    private final BitcoinWithdrawalTransferBasis sources;
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final CodeVaspWithdrawalAddressClient addresses;
    private final CodeVaspWithdrawalAuthorizationClient transfers;
    private final BitcoinSettings settings;
    private final boolean privateTransferEnabled;
    private final TransactionTemplate tx;
    public BitcoinWithdrawalTransferReview(BitcoinWithdrawalTransferBasis sources,UserRepository users,OperatorSessionPolicy sessions,
            JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit,CodeVaspWithdrawalAddressClient addresses,
            CodeVaspWithdrawalAuthorizationClient transfers,BitcoinSettings settings,PlatformTransactionManager manager,
            @Value("${codevasp.private-transfer.enabled:false}") boolean privateTransferEnabled) {
        this.sources=sources;this.users=users;this.sessions=sessions;this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;
        this.addresses=addresses;this.transfers=transfers;this.settings=settings;this.privateTransferEnabled=privateTransferEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 고객/운영자 잠금 아래 고정 원본을 봉인한다. 재요청 키는 같은 원본 조회에만 사용한다. */
    public State prepare(long actor,UUID sid,UUID withdrawal,String peer,UUID requestKey) {
        boundary();
        if(withdrawal==null||requestKey==null||peer==null||!peer.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw bad();
        return tx.execute(t->{
            var access=access(actor,sid,withdrawal,UserRole.OPERATOR);
            var previous=jdbc.queryForList("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE requester_id=? AND request_key=?",actor,requestKey);
            if(!previous.isEmpty()) {
                var old=previous.getFirst();requireCreator(old,access.actor());
                if(!withdrawal.equals(old.get("withdrawal_id"))||!peer.equals(old.get("peer_id")))throw held();
                audit.record(actor,"BTC_WITHDRAWAL_TRANSFER_READ","bitcoin-withdrawal-transfer:"+old.get("id"),"SUCCESS","REPLAY_METADATA");
                return state((UUID)old.get("id"));
            }
            // 새 요청 키로 미해결 전송을 우회하지 못한다. DB 제약도 같은 조건을 독립적으로 검사한다.
            var latest=jdbc.queryForList("SELECT id FROM bitcoin_withdrawal_transfer_intents WHERE withdrawal_id=? ORDER BY position DESC LIMIT 1",UUID.class,withdrawal);
            if(!latest.isEmpty()) {
                var address=latest(latest.getFirst(),"ADDRESS");
                if(address==null||!"INVALID".equals(address.get("status"))||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_attempts WHERE withdrawal_id=? AND stage='TRANSFER')",Boolean.class,withdrawal))throw held();
            }
            UUID id=UUID.randomUUID();var basis=sources.capture(access.customer(),withdrawal,peer,id);
            String plain=plain(basis);
            // 시각은 같은 DB에서 만든다. 생성 시점과15분 상한을 서로 다른 호스트 시계로 계산하지 않는다.
            jdbc.update("""
                INSERT INTO bitcoin_withdrawal_transfer_intents(id,withdrawal_id,user_id,requester_id,requester_person,requester_version,
                 requester_session_id,request_key,peer_id,quote_hash,meaning_mac,directory_source_id,directory_attempt_id,policy_hash,key_id,ciphertext,content_mac,expires_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp()+interval '15 minutes'-interval '1 second'
                """,id,withdrawal,access.customer().getId(),actor,access.actor().getOperatorPersonId(),access.actor().getVersion(),sid,requestKey,peer,
                basis.quoteHash(),basis.meaningMac(),basis.directorySource(),basis.directoryAttempt(),basis.policyHash(),crypto.keyId(),seal(basis.customerId(),id,plain),mac("intent",plain));
            audit.record(actor,"BTC_WITHDRAWAL_TRANSFER_PREPARED","bitcoin-withdrawal-transfer:"+id,"SUCCESS","MEANING:"+basis.meaningMac());
            return state(id);
        });
    }
    /** 한 호출은 주소 확인 뒤 현재 조건을 재검사하고 이전 승인을 요청한다. HTTP 동안 DB 연결·고객 잠금을 유지하지 않는다. */
    public State execute(long actor,UUID sid,UUID intent) {
        boundary();Objects.requireNonNull(intent,"이전 확인 식별자가 필요합니다");
        // 이미 남은 결과를 반환해도 호출자의 현재 운영 인증은 검사한다. 실행 시작은 별도 짧은 커밋이다.
        for(int phase=0;phase<2;phase++) {
            Started started=tx.execute(t->begin(actor,sid,intent));
            if(started==null)return tx.execute(t->state(intent));
            observe(started);
            if(started.stage().equals("TRANSFER"))break;
        }
        return tx.execute(t->state(intent));
    }
    public State read(long actor,UUID sid,UUID intent) {
        boundary();return tx.execute(t->{var row=authorizedIntent(actor,sid,intent,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            audit.record(actor,"BTC_WITHDRAWAL_TRANSFER_READ","bitcoin-withdrawal-transfer:"+intent,"SUCCESS","MINIMAL_METADATA");return state(intent);});
    }
    private Started begin(long actor,UUID sid,UUID id) {
        var row=authorizedIntent(actor,sid,id,UserRole.OPERATOR);var access=access(actor,sid,(UUID)row.get("withdrawal_id"),UserRole.OPERATOR);requireCreator(row,access.actor());
        // 사용자 잠금은 같은 출금의 동시 실행을 직렬화한다. 시작만 남은 개인정보 전송은 무조건 보류한다.
        var prior=latest(id,"TRANSFER");
        if(prior!=null&&(!"NOT_SENT".equals(prior.get("status"))||!oldEnough(prior,30)))return null;
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(now())||!latestIntent(row))throw held();
        var basis=sources.capture(access.customer(),(UUID)row.get("withdrawal_id"),(String)row.get("peer_id"),id);
        if(!basis.meaningMac().equals(row.get("meaning_mac"))||!basis.directorySource().equals(row.get("directory_source_id")))throw held();
        verifySealed(row,basis.customerId(),id);
        var address=latest(id,"ADDRESS");String stage="ADDRESS";
        if(address!=null) {
            if("INVALID".equals(address.get("status")))return null;
            boolean valid="VALID".equals(address.get("status"))&&!oldEnough(address,120)
                &&basis.request().key().publicKey().equals(address.get("peer_public_key"));
            if(valid) {verifyAddress(address,basis);if(!privateTransferEnabled)return null;stage="TRANSFER";}
            else if(!oldEnough(address,30))return null;
        }
        UUID attempt=UUID.randomUUID();String plain=plain(basis);
        jdbc.update("""
            INSERT INTO bitcoin_withdrawal_transfer_attempts(id,intent_id,withdrawal_id,user_id,stage,actor_id,session_id,
             directory_attempt_id,policy_hash,peer_public_key,key_id,ciphertext,content_mac)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,attempt,id,basis.withdrawalId(),basis.customerId(),stage,actor,sid,basis.directoryAttempt(),basis.policyHash(),basis.request().key().publicKey(),crypto.keyId(),seal(basis.customerId(),attempt,plain),mac("attempt",plain));
        audit.record(actor,"BTC_WITHDRAWAL_TRANSFER_STARTED","bitcoin-withdrawal-transfer-attempt:"+attempt,"SUCCESS","INTENT:"+id+":STAGE:"+stage);
        return new Started(attempt,id,basis.customerId(),stage,basis);
    }
    private void observe(Started started) {
        String status,reason=null,plain=null;
        try {
            var request=started.basis().request();
            if(started.stage().equals("ADDRESS")) {
                // 복호화된 내부 신원은 이 요청 형식에 담을 수 없다. 주소가 확인돼야 다음 짧은 TX로 넘어간다.
                var result=addresses.verify(new CodeVaspWithdrawalAddressClient.Request(request.address(),request.peer(),request.key()));
                if(result==null)throw new IllegalStateException();
                if("VALID".equals(result.status())&&result.reason()==null&&request.peer().entityId().equals(result.entityId()))status="VALID";
                else if("INVALID".equals(result.status())&&result.entityId()==null&&ADDRESS_REASONS.contains(result.reason())){status="INVALID";reason=result.reason();}
                else throw new IllegalStateException();
                plain=plain(result);
            } else {
                var result=transfers.authorize(request);if(result==null)throw new IllegalStateException();
                if("VERIFIED".equals(result.status())&&result.reason()==null) {
                    var data=result.data();
                    if(data==null||!request.transferId().equals(data.transferId())||data.ciphertextHash()==null||!data.ciphertextHash().matches("[0-9a-f]{64}"))throw new IllegalStateException();
                    var sender=data.originator();var beneficiary=data.beneficiary();
                    if(sender==null||beneficiary==null||!request.originatorName().equals(sender.primaryName())||sender.secondaryName()!=null
                        ||!request.originatorBirth().equals(sender.dateOfBirth())||!List.of(request.originatorAccount()).equals(sender.accounts())
                        ||!request.beneficiaryName().equals(beneficiary.primaryName())||beneficiary.secondaryName()!=null
                        ||!List.of(request.address()).equals(beneficiary.accounts()))throw new IllegalStateException();
                    status="VERIFIED";
                } else if("DENIED".equals(result.status())&&result.data()==null&&TRANSFER_REASONS.contains(result.reason())){status="DENIED";reason=result.reason();}
                else throw new IllegalStateException();
                plain=plain(result);
            }
        }catch(CodeVaspSubmissionFailure failure){status=failure.admission()==CodeVaspSubmissionFailure.Admission.NOT_SENT?"NOT_SENT":"OUTCOME_UNKNOWN";reason="PROVIDER_UNAVAILABLE";plain=null;}
        catch(RuntimeException malformed){status="OUTCOME_UNKNOWN";reason="INVALID_SOURCE";plain=null;}
        String outcome=status,code=reason,observed=plain;
        // 취소/제한이 IO 중 발생해도 이미 관측한 사실은 보존한다. 완료 저장 실패는 재전송으로 바꾸지 않는다.
        tx.executeWithoutResult(t->{
            users.lockById(started.owner()).orElseThrow(BitcoinWithdrawalTransferReview::held);
            jdbc.update("INSERT INTO bitcoin_withdrawal_transfer_results(attempt_id,status,reason,key_id,ciphertext,content_mac) VALUES(?,?,?,?,?,?)",
                started.id(),outcome,code,observed==null?null:crypto.keyId(),observed==null?null:seal(started.owner(),started.id(),observed),observed==null?null:mac("result",observed));
            audit.record(null,"BTC_WITHDRAWAL_TRANSFER_FINISHED","bitcoin-withdrawal-transfer-attempt:"+started.id(),"SUCCESS","STATUS:"+outcome+":REASON:"+(code==null?"NONE":code));
        });
    }
    private Access access(long actor,UUID sid,UUID withdrawal,UserRole... roles) {
        var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_withdrawals WHERE id=? AND network=?",Long.class,withdrawal,settings.network.id);
        var locked=new HashMap<Long,User>();var ids=new TreeSet<Long>();ids.add(actor);if(!owners.isEmpty())ids.add(owners.getFirst());
        for(long user:ids)users.lockById(user).ifPresent(u->locked.put(user,u));sessions.requireFresh(locked.get(actor),sid,roles);
        if(owners.isEmpty()||!locked.containsKey(owners.getFirst()))throw missing();return new Access(locked.get(actor),locked.get(owners.getFirst()));
    }
    private void requireCreator(Map<String,Object> row,User user) {
        if(((Number)row.get("requester_id")).longValue()!=user.getId()||!row.get("requester_person").equals(user.getOperatorPersonId())
            ||((Number)row.get("requester_version")).longValue()!=user.getVersion())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 원래 담당자의 인증이 필요합니다");
    }
    private void verifySealed(Map<String,Object> row,long owner,UUID id) {
        if(!crypto.keyId().equals(row.get("key_id")))throw held();
        String plain=new String(crypto.decrypt(owner,id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
        if(!mac("intent",plain).equals(row.get("content_mac")))throw held();
        // 만료된 상대 키가 포함된 과거 요청을 현재용 생성자로 복원하지 않는다. 현재 원본의 의미 지문을 대조한다.
        var sealed=json.readTree(plain);
        if(!sealed.path("meaningMac").asString().equals(row.get("meaning_mac"))||!sealed.path("withdrawalId").asString().equals(row.get("withdrawal_id").toString())
            ||sealed.path("customerId").asLong()!=owner||!sealed.path("request").path("transferId").asString().equals(id.toString()))throw held();
    }
    /** 과거 주소 응답도 현재 전송의 근거가 되기 전에 원본 시도와 결과 양쪽의 봉인을 검증한다. */
    private void verifyAddress(Map<String,Object> row,BitcoinWithdrawalTransferBasis.Basis current) {
        if(!crypto.keyId().equals(row.get("key_id"))||!crypto.keyId().equals(row.get("result_key_id")))throw held();
        UUID id=(UUID)row.get("id");long owner=current.customerId();
        String attempt=new String(crypto.decrypt(owner,id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
        String result=new String(crypto.decrypt(owner,id,(byte[])row.get("result_ciphertext")),StandardCharsets.UTF_8);
        if(!mac("attempt",attempt).equals(row.get("content_mac"))||!mac("result",result).equals(row.get("result_mac")))throw held();
        var original=json.readTree(attempt);var reply=json.readTree(result);
        if(!current.meaningMac().equals(original.path("meaningMac").asString())
            ||!current.request().transferId().toString().equals(original.path("request").path("transferId").asString())
            ||!current.request().key().publicKey().equals(original.path("request").path("key").path("publicKey").asString())
            ||!"VALID".equals(reply.path("status").asString())||reply.hasNonNull("reason")
            ||!current.request().peer().entityId().equals(reply.path("entityId").asString()))throw held();
    }
    private Map<String,Object> authorizedIntent(long actor,UUID sid,UUID id,UserRole... roles) {
        var rows=jdbc.queryForList("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE id=?",id);
        access(actor,sid,rows.isEmpty()?null:(UUID)rows.getFirst().get("withdrawal_id"),roles);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private boolean latestIntent(Map<String,Object> row){return !jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_intents WHERE withdrawal_id=? AND position>?)",Boolean.class,row.get("withdrawal_id"),row.get("position"));}
    private Map<String,Object> intent(UUID id){var rows=jdbc.queryForList("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE id=?",id);if(rows.size()!=1)throw missing();return rows.getFirst();}
    private Map<String,Object> latest(UUID id,String stage){var rows=jdbc.queryForList("SELECT a.*,r.status,r.reason,r.recorded_at,r.key_id result_key_id,r.ciphertext result_ciphertext,r.content_mac result_mac FROM bitcoin_withdrawal_transfer_attempts a LEFT JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.intent_id=? AND a.stage=? ORDER BY a.position DESC LIMIT 1",id,stage);return rows.isEmpty()?null:rows.getFirst();}
    private State state(UUID id){var row=intent(id);var attempts=jdbc.queryForList("SELECT a.id,a.stage,r.status,r.reason FROM bitcoin_withdrawal_transfer_attempts a LEFT JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.intent_id=? ORDER BY a.position DESC LIMIT 1",id);
        var a=attempts.isEmpty()?Map.<String,Object>of():attempts.getFirst();return new State(id,(UUID)row.get("withdrawal_id"),(UUID)a.get("id"),(String)a.get("stage"),a.isEmpty()?"PREPARED":a.get("status")==null?"RUNNING":(String)a.get("status"),(String)a.get("reason"),((Timestamp)row.get("created_at")).toInstant(),((Timestamp)row.get("expires_at")).toInstant());}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private boolean oldEnough(Map<String,Object> row,int seconds){var time=(Timestamp)row.get(row.get("recorded_at")==null?"started_at":"recorded_at");return !time.toInstant().isAfter(now().minusSeconds(seconds));}
    private String plain(Object data){String value=json.writeValueAsString(data);if(value.getBytes(StandardCharsets.UTF_8).length>65508)throw held();return value;}
    private byte[] seal(long owner,UUID id,String value){return crypto.encrypt(owner,id,value.getBytes(StandardCharsets.UTF_8));}
    private String mac(String kind,String plain){return crypto.fingerprint("btc-withdrawal-transfer-"+kind+"-v1",plain);}
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("출금 이전 확인은 외부 트랜잭션 없이 실행하세요");}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 출금 이전 원본·진행·재시도 근거를 확인하세요");}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"출금과 현재 상대·재요청 키를 확인하세요");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"출금 이전 확인 근거가 없습니다");}
}
