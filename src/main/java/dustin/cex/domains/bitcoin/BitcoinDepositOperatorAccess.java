package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.OperatorSessionPolicy;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 입금의 최소 이력 조회와 개인정보 검토가 같은 현재 인증·잠금 순서를 사용하게 한다. 감사 저장은 호출 업무가 책임진다. */
@Service
public class BitcoinDepositOperatorAccess {
    public record Access(User actor,User customer){
        @Override public String toString(){return "DepositOperatorAccess[개인정보 생략]";}
    }
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final JdbcTemplate jdbc;
    public BitcoinDepositOperatorAccess(UserRepository users,OperatorSessionPolicy sessions,JdbcTemplate jdbc){this.users=users;this.sessions=sessions;this.jdbc=jdbc;}

    /** 현재 운영 인증을 먼저 판정한 뒤 대상의 존재를 알린다. 고객·운영자 변경은 같은 사용자 잠금 계약으로 직렬화한다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Access require(long actor,UUID sid,UUID receipt,BitcoinSettings settings,UserRole... roles){
        var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=? AND network=?",Long.class,receipt,settings.network.id);
        var locked=new HashMap<Long,User>();var ids=new TreeSet<Long>();ids.add(actor);if(!owners.isEmpty())ids.add(owners.getFirst());
        // 다른 운영/고객 업무와 같은 오름차순 잠금으로 교차 조회의 잠금 순서 역전을 피한다.
        for(long id:ids)users.lockById(id).ifPresent(user->locked.put(id,user));
        sessions.requireFresh(locked.get(actor),sid,roles);
        if(receipt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"입금 식별자를 확인하세요");
        if(owners.isEmpty()||locked.get(owners.getFirst())==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 심사 근거가 없습니다");
        return new Access(locked.get(actor),locked.get(owners.getFirst()));
    }
}
