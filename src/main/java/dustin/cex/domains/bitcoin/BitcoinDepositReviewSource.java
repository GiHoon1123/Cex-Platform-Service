package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 운영 심사에 필요한 현재 원본의 참조만 제공한다. 조회는 금융 해제/이전거래 심사 완료를 의미하지 않는다. */
@Service
public class BitcoinDepositReviewSource {
    public record Customer(long id,long version,boolean financiallyEligible,UUID cddAssessmentId,UUID kycAttemptId){}
    public record Chain(Long observationId,String status,Long confirmations,Instant observedAt,boolean fresh,Boolean walletDebit,
        Long decisionId,String decisionReason,boolean decisionCurrent){}
    public record Pending(boolean recognized,String operationKey){}
    public record Reconciliation(UUID id,String status,Instant snapshotAt,Instant completedAt){}
    public record Source(UUID depositId,String asset,String network,String txid,int vout,String amount,Instant detectedAt,
        Instant readAt,Customer customer,Chain chain,Pending pending,Reconciliation reconciliation,dustin.cex.domains.travelrule.CodeVaspDepositSearchReview.Review originSearch,dustin.cex.domains.valuation.BitcoinDepositValuationReview.Review valuation){}
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final CustomerFinancialEligibility eligibility;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final dustin.cex.domains.travelrule.CodeVaspDepositSearchReview originSearch;
    private final dustin.cex.domains.valuation.BitcoinDepositValuationReview valuation;
    public BitcoinDepositReviewSource(UserRepository users,OperatorSessionPolicy sessions,CustomerFinancialEligibility eligibility,
        BitcoinSettings settings,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager,dustin.cex.domains.travelrule.CodeVaspDepositSearchReview originSearch,dustin.cex.domains.valuation.BitcoinDepositValuationReview valuation){
        this.users=users;this.sessions=sessions;this.eligibility=eligibility;this.settings=settings;this.jdbc=jdbc;this.audit=audit;this.originSearch=originSearch;this.valuation=valuation;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Source read(long actor,UUID sid,UUID deposit){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 심사 근거 조회는 독립 트랜잭션으로 실행하세요");
        try{return tx.execute(t->{
            var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=? AND network=?",Long.class,deposit,settings.network.id);
            // 사용자 잠금은 오름차순으로 통일한다. 원본 존재 여부는 현재 운영 인증을 확인한 뒤에만 응답한다.
            var locked=new HashMap<Long,User>();var ids=new TreeSet<Long>();ids.add(actor);if(!owners.isEmpty())ids.add(owners.getFirst());
            for(long id:ids)users.lockById(id).ifPresent(user->locked.put(id,user));
            sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            if(deposit==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"입금 식별자를 확인하세요");
            if(owners.isEmpty())throw missing();
            var customer=locked.get(owners.getFirst());boolean permitted=eligibility.permitted(customer);
            // 관찰/판단/보류/가장 최근 대사는 한 SQL 문장의 같은 커밋 스냅샷에서 읽는다.
            // 고객 자격 변경은 같은 사용자 잠금 계약을 따르며, 노드와 엔진을 기다리지 않는다.
            var row=jdbc.queryForMap("""
                SELECT r.*,statement_timestamp() read_at,cp.assessment_id,kp.active_attempt,
                 o.id observation_id,o.status chain_status,o.confirmations,o.read_started_at,w.wallet_debit,
                 coalesce(o.read_started_at<=statement_timestamp() AND o.read_started_at>=statement_timestamp()-interval '2 minutes',false) fresh,
                 d.id decision_id,d.reason decision_reason,coalesce(d.observation_id=o.id,false) decision_current,
                 p.operation_key,q.id reconciliation_id,q.status reconciliation_status,q.snapshot_at,q.completed_at
                 FROM bitcoin_deposit_receipts r LEFT JOIN cdd_profiles cp ON cp.user_id=r.user_id LEFT JOIN kyc_profiles kp ON kp.user_id=r.user_id
                 LEFT JOIN bitcoin_observation_heads oh ON oh.network=r.network AND oh.txid=r.txid
                 LEFT JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
                 LEFT JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id
                 LEFT JOIN bitcoin_deposit_chain_heads dh ON dh.receipt_id=r.id LEFT JOIN bitcoin_deposit_chain_decisions d ON d.id=dh.decision_id
                 LEFT JOIN bitcoin_pending_deposits p ON p.receipt_id=r.id
                 LEFT JOIN LATERAL(SELECT id,status,snapshot_at,completed_at FROM bitcoin_deposit_reconciliation_runs WHERE receipt_id=r.id ORDER BY started_at DESC,id DESC LIMIT 1) q ON true
                 WHERE r.id=? AND r.network=?
                """,deposit,settings.network.id);
            // 검색과 불변 평가 기록은 각각 별도 SQL로 읽는다. 같은 운영 인증·감사 경계 안의 조회이며 전체 금융 승인 스냅샷으로 사용하지 않는다.
            var result=new Source(deposit,(String)row.get("asset"),(String)row.get("network"),(String)row.get("txid"),((Number)row.get("vout")).intValue(),
                ((BigDecimal)row.get("amount")).setScale(8,RoundingMode.UNNECESSARY).toPlainString(),at(row,"recorded_at"),at(row,"read_at"),
                new Customer(customer.getId(),customer.getVersion(),permitted,(UUID)row.get("assessment_id"),(UUID)row.get("active_attempt")),
                new Chain(number(row,"observation_id"),row.get("chain_status")==null?"UNKNOWN":(String)row.get("chain_status"),number(row,"confirmations"),at(row,"read_started_at"),Boolean.TRUE.equals(row.get("fresh")),(Boolean)row.get("wallet_debit"),number(row,"decision_id"),(String)row.get("decision_reason"),Boolean.TRUE.equals(row.get("decision_current"))),
                new Pending(row.get("operation_key")!=null,(String)row.get("operation_key")),
                row.get("reconciliation_id")==null?null:new Reconciliation((UUID)row.get("reconciliation_id"),(String)row.get("reconciliation_status"),at(row,"snapshot_at"),at(row,"completed_at")),originSearch.read(deposit),valuation.read(deposit));
            // 조회의 대상과 관찰/대사 참조를 남긴다. 이름/신분증/신고 원문을 감사 문자열에 복사하지 않는다.
            audit.record(actor,"BTC_DEPOSIT_REVIEW_SOURCE_READ","bitcoin-deposit:"+deposit,"SUCCESS","CUSTOMER_VERSION:"+result.customer().version()+":ELIGIBLE:"+result.customer().financiallyEligible()+":CDD:"+result.customer().cddAssessmentId()+":KYC:"+result.customer().kycAttemptId()+":OBSERVATION:"+result.chain().observationId()+":DECISION:"+result.chain().decisionId()+":RECONCILIATION:"+(result.reconciliation()==null?null:result.reconciliation().id())+":ORIGIN_SEARCH:"+(result.originSearch().search()==null?null:result.originSearch().search().id())+":ORIGIN_ATTEMPT:"+(result.originSearch().search()==null?null:result.originSearch().search().attemptId()));return result;
        });}catch(ResponseStatusException e){audit.denied(actor,"BTC_DEPOSIT_REVIEW_SOURCE","restricted",(e.getStatusCode().is5xxServerError()?"SOURCE_UNAVAILABLE_":"POLICY_DENIED_")+e.getStatusCode().value());throw e;}
    }
    private static Long number(Map<String,Object> row,String key){var value=(Number)row.get(key);return value==null?null:value.longValue();}
    private static Instant at(Map<String,Object> row,String key){var value=(Timestamp)row.get(key);return value==null?null:value.toInstant();}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 심사 근거가 없습니다");}
}
