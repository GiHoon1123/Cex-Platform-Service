package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
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

/** 위험 원본의 운영 목록이다. 조회 성공을 제한 해제나 자산 회수 승인으로 사용하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinCreditChainRiskReview {
    public record Item(UUID depositId,long customerId,String asset,String amount,long observations,
        long firstObservationId,long lastObservationId,Instant firstRecordedAt,Instant lastRecordedAt,String latestReason){}
    public record Page(int page,List<Item> items){}
    private final JdbcTemplate jdbc;private final BitcoinSettings settings;private final UserRepository users;
    private final OperatorSessionPolicy sessions;private final AuditService audit;private final TransactionTemplate tx;
    public BitcoinCreditChainRiskReview(JdbcTemplate jdbc,BitcoinSettings settings,UserRepository users,
        OperatorSessionPolicy sessions,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.users=users;this.sessions=sessions;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Page read(long actor,UUID sid,int page){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("체인 위험 목록은 독립 조회 트랜잭션으로 실행하세요");
        try{return tx.execute(t->{
            // 현재 역할·재인증·세션을 먼저 확인한다. 잘못된 페이지나 목록의 존재로 고객에게 운영 정보를 알리지 않는다.
            sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            if(page<0||page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"위험 목록 페이지를 확인하세요");
            // 반복 관찰을 입금 하나로 묶는다. 마지막 이유와 범위 집계는 한 문장의 같은 스냅샷에서 읽는다.
            // 주소·이름·신원 원문을 목록에 복사하지 않고 상세 조회의 기존 접근 통제를 계속 사용한다.
            var items=jdbc.queryForList("""
                WITH scoped AS(SELECT risk.* FROM bitcoin_credit_chain_risks risk
                  JOIN bitcoin_deposit_receipts r ON r.id=risk.receipt_id WHERE r.network=?),
                grouped AS(SELECT receipt_id,user_id,asset,amount,count(*) observations,min(observation_id) first_id,
                  max(observation_id) last_id
                  FROM scoped GROUP BY receipt_id,user_id,asset,amount)
                SELECT g.*,oldest.recorded_at first_at,latest.recorded_at last_at,latest.reason FROM grouped g
                 JOIN scoped oldest ON oldest.receipt_id=g.receipt_id AND oldest.observation_id=g.first_id
                 JOIN scoped latest ON latest.receipt_id=g.receipt_id AND latest.observation_id=g.last_id
                 ORDER BY g.last_id DESC,g.receipt_id LIMIT 25 OFFSET ?
                """,settings.network.id,page*25).stream().map(row->new Item((UUID)row.get("receipt_id"),((Number)row.get("user_id")).longValue(),
                    (String)row.get("asset"),((BigDecimal)row.get("amount")).setScale(8,java.math.RoundingMode.UNNECESSARY).toPlainString(),
                    number(row,"observations"),number(row,"first_id"),number(row,"last_id"),at(row,"first_at"),at(row,"last_at"),(String)row.get("reason"))).toList();
            // 페이지 번호만으로는 나중에 달라진 목록을 재구성할 수 없다. 실제 표시한
            // 입금/관찰 참조를 목록 식별자와 함께 남기고, 어느 한 감사라도 실패하면 결과를 반환하지 않는다.
            UUID listId=UUID.randomUUID();
            audit.record(actor,"BTC_CREDIT_CHAIN_RISK_LIST_READ","bitcoin-chain-risks:"+listId,"SUCCESS","PAGE:"+page+":COUNT:"+items.size());
            for(var item:items)audit.record(actor,"BTC_CREDIT_CHAIN_RISK_ITEM_READ","bitcoin-deposit:"+item.depositId(),"SUCCESS",
                "LIST:"+listId+":LAST_RISK:"+item.lastObservationId());
            return new Page(page,items);
        });}catch(org.springframework.dao.DataAccessException unavailable){
            // 조회/감사 저장 실패를 빈 목록으로 바꾸지 않고 내부 SQL을 고정 오류로 감춘다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"위험 목록의 현재 근거와 조회 감사를 확인할 수 없습니다");
        }catch(ResponseStatusException denied){audit.denied(actor,"BTC_CREDIT_CHAIN_RISK_LIST","restricted","POLICY_"+denied.getStatusCode().value());throw denied;}
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Instant at(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
}
