package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 주소 공개는 DB 확정 뒤에만 한다. 노드의 label이나 HTTP 응답만으로 고객 소유권을 확정하지 않는다. */
@Service
public class BitcoinDepositAddressService {
    public record Address(UUID id,String asset,String network,String address,Instant createdAt){}
    private final CustomerSessionPolicy sessions;
    private final CustomerFinancialEligibility eligibility;
    private final BitcoinCoreClient core;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinDepositAddressService(CustomerSessionPolicy sessions,CustomerFinancialEligibility eligibility,BitcoinCoreClient core,
            BitcoinSettings settings,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.sessions=sessions;this.eligibility=eligibility;this.core=core;this.settings=settings;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Address own(long actor,UUID sid){
        boundary();
        try{return tx.execute(t->{
            require(actor,sid);var existing=find(actor);
            if(existing==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"발급된 BTC 입금 주소가 없습니다");
            audit.record(actor,"BTC_ADDRESS_READ","address:"+existing.id(),"SUCCESS","OWN_DURABLE_ADDRESS");return existing;
        });}catch(ResponseStatusException e){denied(actor,e);throw e;}
    }
    public Address issue(long actor,UUID sid){
        boundary();
        try{
            var existing=tx.execute(t->{
                require(actor,sid);var known=find(actor);
                if(known!=null)audit.record(actor,"BTC_ADDRESS_REUSED","address:"+known.id(),"SUCCESS","OWN_DURABLE_ADDRESS");
                return known;
            });
            if(existing!=null)return existing;
            UUID allocation=UUID.randomUUID();
            // RPC가 느리거나 응답을 잃어도 사용자 행 잠금은 유지하지 않는다. 미공개 파생 주소는 재사용하지 않는다.
            var candidate=core.derive(allocation);
            return tx.execute(t->{
                // 그 사이의 로그아웃·회수·고객 확인 만료·업무 제한을 최신 사용자 잠금 아래 다시 확인한다.
                require(actor,sid);var winner=find(actor);
                if(winner!=null){audit.record(actor,"BTC_ADDRESS_REUSED","address:"+winner.id(),"SUCCESS","CONCURRENT_DURABLE_WINNER");return winner;}
                if(!settings.network.id.equals(candidate.network()))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"입금 네트워크를 확인할 수 없습니다");
                int inserted=jdbc.update("""
                    INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key)
                    VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """,allocation,actor,candidate.network(),settings.wallet,candidate.address(),candidate.scriptPubKey());
                // 다른 고객의 주소/지출 조건과 충돌하면 소유권을 바꾸거나 타인의 행을 반환하지 않는다.
                if(inserted!=1)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"입금 주소 소유를 확정할 수 없습니다");
                var saved=find(actor);audit.record(actor,"BTC_ADDRESS_ISSUED","address:"+allocation,"SUCCESS","OWNERSHIP_COMMITTED");return saved;
            });
        }catch(ResponseStatusException e){denied(actor,e);throw e;}
    }
    private void require(long actor,UUID sid){
        var owner=sessions.require(actor,sid);
        if(!eligibility.permitted(owner))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 BTC 입금 주소 이용이 제한되어 있습니다");
    }
    private Address find(long actor){
        var rows=jdbc.queryForList("SELECT id,network,address,created_at FROM bitcoin_deposit_addresses WHERE user_id=? AND network=?",actor,settings.network.id);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();return new Address((UUID)row.get("id"),"BTC",(String)row.get("network"),(String)row.get("address"),((Timestamp)row.get("created_at")).toInstant());
    }
    private void denied(long actor,ResponseStatusException e){audit.denied(actor,"BTC_DEPOSIT_ADDRESS","customer:"+actor,"DENIED_"+e.getStatusCode().value());}
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 주소 업무는 외부 트랜잭션 없이 호출하세요");}
}
