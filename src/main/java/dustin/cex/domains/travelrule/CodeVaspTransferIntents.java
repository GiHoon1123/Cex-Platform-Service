package dustin.cex.domains.travelrule;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 개인정보 제공 전에 현재 입금·검색·상대 정책·확인된 수취인을 결합해 암호화 보관한다. 금융 승인이나 외부 전송은 하지 않는다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspTransferIntents {
    public record Intent(UUID id,UUID searchId,UUID receiptId,long userId,Instant createdAt){}
    // 전체 값은 암호문 안에만 둔다. DTO의 기본 문자열 출력으로 생일/신원 지문이 드러나지 않도록 막는다.
    record Prepared(CodeVaspTransferDataClient.Request request,String dateOfBirth,String identityMac,long userVersion){
        @Override public String toString(){return "PreparedTransfer[개인정보 생략]";}
    }
    private final CodeVaspDepositSearches searches;
    private final CodeVaspDepositSearchExecution execution;
    private final CodeVaspDirectoryService directory;
    private final CodeVaspCounterpartyPolicy policy;
    private final UserRepository users;
    private final CustomerFinancialEligibility eligibility;
    private final CddCrypto crypto;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CodeVaspTransferIntents(CodeVaspDepositSearches searches,CodeVaspDepositSearchExecution execution,CodeVaspDirectoryService directory,
            CodeVaspCounterpartyPolicy policy,UserRepository users,CustomerFinancialEligibility eligibility,CddCrypto crypto,JdbcTemplate jdbc,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.searches=searches;this.execution=execution;this.directory=directory;this.policy=policy;this.users=users;this.eligibility=eligibility;this.crypto=crypto;this.jdbc=jdbc;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 같은 검색의 예약은 한 번만 남긴다. 기존 ID 반환은 과거 의도의 조회이며 현재 전송 허가가 아니다. */
    public Intent reserve(UUID searchId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("정보 제공 예약은 독립 트랜잭션에서 실행하세요");
        var search=searches.get(Objects.requireNonNull(searchId,"검색 식별자가 필요합니다"));
        return tx.execute(t->{
            var receipt=jdbc.queryForMap("SELECT user_id,amount FROM bitcoin_deposit_receipts WHERE id=?",search.receiptId());long owner=((Number)receipt.get("user_id")).longValue();
            // 동일 고객의 KYC/제한 변경과 같은 잠금 순서를 따른다. 동시 예약도 한 사용자 잠금에서 같은 ID를 반환한다.
            var user=users.lockById(owner).orElseThrow(CodeVaspTransferIntents::held);
            var old=jdbc.queryForList("SELECT id FROM codevasp_transfer_intents WHERE search_id=?",UUID.class,searchId);
            if(!old.isEmpty())return load(old.getFirst());
            crypto.requireConfigured();if(!eligibility.permitted(user))throw held();
            var person=jdbc.queryForMap("""
                SELECT d.id declaration_id,d.ciphertext,d.content_mac,d.key_id,a.id assessment_id,a.identity_mac
                 FROM cdd_profiles p JOIN cdd_declarations d ON d.id=p.declaration_id AND d.user_id=p.user_id
                 JOIN cdd_assessments a ON a.id=p.assessment_id AND a.declaration_id=d.id AND a.user_id=p.user_id WHERE p.user_id=?
                """,owner);
            if(!crypto.keyId().equals(person.get("key_id")))throw held();
            UUID declaration=(UUID)person.get("declaration_id");String plain=new String(crypto.decrypt(owner,declaration,(byte[])person.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("declaration",plain).equals(person.get("content_mac")))throw held();
            var customer=json.readValue(plain,CustomerDeclaration.class).validated();String identity=crypto.fingerprint("identity",json.writeValueAsString(List.of(customer.fullName(),customer.dateOfBirth().toString(),customer.nationality())));
            if(!identity.equals(person.get("identity_mac")))throw held();
            // 현재 체인 판단과 관찰 위치를 함께 고정한다. 보류 원본의 과거 확정만으로 개인정보 제공을 개시하지 않는다.
            var chain=jdbc.queryForList("""
                SELECT d.id decision_id,o.id observation_id,o.outputs FROM bitcoin_deposit_chain_heads qh
                 JOIN bitcoin_deposit_chain_decisions d ON d.id=qh.decision_id AND d.receipt_id=qh.receipt_id
                 JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id
                 JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
                 JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
                 JOIN bitcoin_pending_deposits p ON p.receipt_id=qh.receipt_id
                 WHERE qh.receipt_id=? AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1' AND o.coinbase=false
                  AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
                 FOR SHARE OF qh,oh
                """,search.receiptId());
            if(chain.size()!=1)throw held();var origin=execution.current(searchId);
            Instant now=Instant.now();if(!"FOUND".equals(origin.status())||origin.result()==null||origin.recordedAt()==null||origin.recordedAt().isAfter(now)||origin.recordedAt().isBefore(now.minusSeconds(900)))throw held();
            var snapshot=directory.current();if(!snapshot.sourceId().equals(search.sourceId()))throw held();var selected=policy.select(snapshot.directory(),origin.result().entityId());
            // 공급사 API는 출력 번호를 받지 않는다. 같은 주소의 여러 양수 출력을 한 입금이라고 추측하지 않고 별도 검토로 보낸다.
            var outputs=json.readTree(chain.getFirst().get("outputs").toString());int matches=0;
            for(var output:outputs)if(search.request().beneficiaryAddress().equals(output.path("address").asString())&&output.path("amount").isNumber()&&output.path("amount").decimalValue().signum()>0)matches++;
            if(matches!=1)throw held();
            UUID id=UUID.randomUUID();var request=new CodeVaspTransferDataClient.Request(search.request(),(java.math.BigDecimal)receipt.get("amount"),customer.fullName(),selected.peer(),selected.key());
            String prepared=json.writeValueAsString(new Prepared(request,customer.dateOfBirth().toString(),identity,user.getVersion()));
            jdbc.update("""
                INSERT INTO codevasp_transfer_intents(id,search_id,user_id,receipt_id,search_attempt_id,directory_attempt_id,decision_id,observation_id,
                 declaration_id,assessment_id,policy_hash,peer_id,peer_public_key,key_id,ciphertext,content_mac)
                 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,searchId,owner,search.receiptId(),origin.attemptId(),snapshot.attemptId(),chain.getFirst().get("decision_id"),chain.getFirst().get("observation_id"),
                declaration,person.get("assessment_id"),selected.policyHash(),selected.peer().entityId(),selected.key().publicKey(),crypto.keyId(),crypto.encrypt(owner,id,prepared.getBytes(StandardCharsets.UTF_8)),crypto.fingerprint("codevasp-transfer-intent-v1",prepared));
            audit.record(null,"CODE_TRANSFER_INTENT_RESERVED","codevasp-transfer:"+id,"SUCCESS","SEARCH:"+searchId+":ATTEMPT:"+origin.attemptId());
            return load(id);
        });
    }
    private Intent load(UUID id){var row=jdbc.queryForMap("SELECT id,search_id,receipt_id,user_id,created_at FROM codevasp_transfer_intents WHERE id=?",id);return new Intent(id,(UUID)row.get("search_id"),(UUID)row.get("receipt_id"),((Number)row.get("user_id")).longValue(),((Timestamp)row.get("created_at")).toInstant());}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 수취인과 송신 출처 근거를 확인할 수 없습니다");}
}
