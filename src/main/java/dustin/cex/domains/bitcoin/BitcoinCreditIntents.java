package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 정상 자동 정책을 통과한 입금 원본을 한 번만 영속화한다. 생성 자체는 엔진/AVAILABLE 반영 완료가 아니다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinCreditIntents {
    public static final String ASSURANCE="INTENT_RECORDED_ENGINE_AND_PLATFORM_CREDIT_NOT_CONFIRMED";
    public record Intent(UUID depositId,String customerId,String asset,String amount,String policy,String basisHash,Instant recordedAt,String assurance){}
    public record Reserved(Intent intent,boolean created){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final BitcoinCreditBasis policy;
    private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public BitcoinCreditIntents(JdbcTemplate jdbc,UserRepository users,BitcoinCreditBasis policy,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.policy=policy;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 같은 입금 UUID가 곧 엔진의 멱등 원본이다. 재시도/응답 유실 때 임의로 새 금융 식별자를 만들지 않는다. */
    public Reserved reserve(UUID receipt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 의도 생성은 독립 트랜잭션으로 시작하세요");
        if(receipt==null)throw new IllegalArgumentException("입금 원본이 필요합니다");
        try{return tx.execute(t->{
            var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=?",Long.class,receipt);if(owners.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 원본이 없습니다");
            var customer=users.lockById(owners.getFirst()).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
            // 과거에 확정한 의도를 다시 읽을 뿐 현재 금융 조건을 다시 승인했다는 뜻이 아니다.
            // 향후 첫 전송은 같은 고객 잠금 아래 현재 조건을 별도로 재검증해야 한다.
            var old=jdbc.queryForList("SELECT receipt_id FROM bitcoin_credit_intents WHERE receipt_id=?",UUID.class,receipt);
            if(!old.isEmpty())return new Reserved(read(receipt),false);
            // 읽기·판단·의도 INSERT·필수 감사는 하나의 TX다. 감사 예외/누락이면 새 의도를 확정하지 않는다.
            // JSON을 만든 실제 UTF-8 바이트의 지문을 보존해 재조회 때 다른 본문을 같은 의도로 취급하지 않는다.
            var basis=policy.require(receipt,customer);String raw=json.writeValueAsString(basis),hash=BitcoinCreditBasis.digest(raw);var funds=basis.funds();
            jdbc.update("INSERT INTO bitcoin_credit_intents(receipt_id,user_id,asset,amount,policy_id,basis,basis_hash) VALUES(?,?,?,?,?,?,?)",receipt,customer.getId(),funds.asset(),new BigDecimal(funds.amount()),basis.policy(),raw,hash);
            audit.record(null,"BTC_CREDIT_INTENT_AUTHORIZED","bitcoin-credit:"+receipt,"SUCCESS","POLICY:"+basis.policy()+":BASIS:"+hash);
            return new Reserved(read(receipt),true);
        });}catch(BitcoinCreditBasis.Held held){audit.denied(null,"BTC_CREDIT_INTENT","bitcoin-deposit:"+receipt,"HELD_"+held.reason());throw held;}
        catch(ResponseStatusException denied){audit.denied(null,"BTC_CREDIT_INTENT","bitcoin-deposit:"+receipt,"SOURCE_OR_POLICY_"+denied.getStatusCode().value());throw denied;}
    }
    private Intent read(UUID receipt){
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_credit_intents WHERE receipt_id=?",receipt);String raw=(String)row.get("basis");
        if(!BitcoinCreditBasis.digest(raw).equals(row.get("basis_hash")))throw BitcoinCreditBasis.held(BitcoinCreditBasis.Reason.SOURCE_UNAVAILABLE);
        // 역사 메타데이터 조회는 전체 신원/정책 DTO 재조립이나 복호화를 요구하지 않는다. 경제적 원본과 저장 지문만 대조한다.
        var value=json.readTree(raw);var f=json.treeToValue(value.path("funds"),BitcoinCreditBasis.Funds.class);
        if(!f.depositId().equals(receipt)||f.customerId()!=((Number)row.get("user_id")).longValue()||!f.asset().equals(row.get("asset"))||new BigDecimal(f.amount()).compareTo((BigDecimal)row.get("amount"))!=0||!value.path("policy").asString().equals(row.get("policy_id")))throw BitcoinCreditBasis.held(BitcoinCreditBasis.Reason.SOURCE_UNAVAILABLE);
        return new Intent(receipt,Long.toString(f.customerId()),f.asset(),f.amount(),(String)row.get("policy_id"),(String)row.get("basis_hash"),((Timestamp)row.get("recorded_at")).toInstant(),ASSURANCE);
    }
}
