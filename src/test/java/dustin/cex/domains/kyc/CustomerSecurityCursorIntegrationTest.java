package dustin.cex.domains.kyc;

import dustin.cex.domains.account.CustomerPasswordHistory;
import dustin.cex.domains.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 고객 보안 이력의 외부 시각을 JDBC로 넘기기 전에 제한하고 정상 빈 조회는 보존한다. */
class CustomerSecurityCursorIntegrationTest extends CustomerMailTestSupport {
    @Autowired CustomerPasswordHistory passwordsHistory;
    @Autowired CustomerMfaHistory keys;
    @Autowired CustomerRecoveryHistory recovery;
    @Autowired CustomerEmailChangeHistory emails;
    @Test void cursorBoundsAreCheckedBeforeJdbcConversion() {
        List<Function<Instant,Object>> reads=List.of(
            at->passwordsHistory.own(customer.getId(),sid,at==null?null:new CustomerPasswordHistory.Cursor(at,UUID.randomUUID())),
            at->keys.own(customer.getId(),sid,at==null?null:new CustomerMfaHistory.Cursor(at,UUID.randomUUID())),
            at->recovery.own(customer.getId(),sid,at==null?null:new CustomerRecoveryHistory.Cursor(at,UUID.randomUUID())),
            at->emails.own(customer.getId(),sid,at==null?null:new CustomerEmailChangeHistory.Cursor(at,UUID.randomUUID())));
        for(var read:reads){
            for(Instant invalid:List.of(Instant.MIN,Instant.EPOCH.minusNanos(1),Instant.now().plusSeconds(600)))
                assertEquals(400,assertThrows(ResponseStatusException.class,()->read.apply(invalid)).getStatusCode().value());
            assertNotNull(read.apply(Instant.EPOCH));assertNotNull(read.apply(null));
        }
    }
}
