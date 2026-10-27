package dustin.cex.domains.ethereum;

import dustin.cex.domains.security.*;import java.time.Instant;import java.util.*;
import org.springframework.context.annotation.DependsOn;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.web.server.ResponseStatusException;

/** 외부 키 관리 계층이 준비한 주소만 고객에게 일회 배정하며 웹 플랫폼에서 키를 만들지 않는다. */
@Service @DependsOn("ethereumSchemaInitializer")
public class EthereumDepositAddressService {
    public record Address(UUID id,String asset,String network,String address,Instant createdAt){}
    private final JdbcTemplate jdbc;private final CustomerSessionPolicy sessions;private final CustomerFinancialEligibility eligibility;private final AuditService audit;
    public EthereumDepositAddressService(JdbcTemplate jdbc,CustomerSessionPolicy sessions,CustomerFinancialEligibility eligibility,AuditService audit){this.jdbc=jdbc;this.sessions=sessions;this.eligibility=eligibility;this.audit=audit;}
    @Transactional public Address issue(long user,UUID session){
        require(user,session);
        var existing=rows(user);if(!existing.isEmpty())return map(existing.getFirst());
        var free=jdbc.queryForList("SELECT id FROM ethereum_deposit_addresses WHERE user_id IS NULL ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",UUID.class);
        if(free.isEmpty())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"사용 가능한 이더리움 입금 주소가 없습니다");
        jdbc.update("UPDATE ethereum_deposit_addresses SET user_id=?,claimed_at=clock_timestamp() WHERE id=? AND user_id IS NULL",user,free.getFirst());
        audit.record(user,"ETH_DEPOSIT_ADDRESS_ISSUED","customer:"+user,"SUCCESS","ADDRESS_ID:"+free.getFirst());return map(rows(user).getFirst());
    }
    @Transactional(readOnly=true) public Address own(long user,UUID session){require(user,session);var rows=rows(user);if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return map(rows.getFirst());}
    private void require(long user,UUID session){var owner=sessions.require(user,session);if(!eligibility.permitted(owner))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 ETH 입금 주소 이용이 제한되어 있습니다");}
    private List<Map<String,Object>> rows(long user){return jdbc.queryForList("SELECT id,address,network,created_at FROM ethereum_deposit_addresses WHERE user_id=?",user);}
    private static Address map(Map<String,Object> row){return new Address((UUID)row.get("id"),"ETH",(String)row.get("network"),(String)row.get("address"),((java.sql.Timestamp)row.get("created_at")).toInstant());}
}
