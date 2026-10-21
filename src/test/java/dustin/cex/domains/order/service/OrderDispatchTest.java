package dustin.cex.domains.order.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.model.entity.OrderDispatch;

/** 저장하는 시각과 시도 번호가 만료·지연 응답·중복 확정의 금융 의미를 지키는지 검증한다. */
class OrderDispatchTest {
    @Test void expiredLeaseCanBeClaimedButOldResponseCannotConfirmNewAttempt() {
        var row=new OrderDispatch(Order.builder().id(1L).build()); var now=Instant.now();
        assertTrue(row.claim(now,true)); assertFalse(row.claim(now.plusSeconds(29),false));
        assertTrue(row.claim(now.plusSeconds(31),false));
        row.finish(1,true,now.plusSeconds(32)); assertNull(row.getConfirmedAt());
        row.finish(2,true,now.plusSeconds(33)); assertNotNull(row.getConfirmedAt());
    }
    @Test void confirmedRejectionCannotBeReopenedByLateFailure() {
        var row=new OrderDispatch(Order.builder().id(1L).build()); var now=Instant.now();
        row.claim(now,true); row.confirm(now); row.finish(1,false,now.plusSeconds(1));
        assertEquals(now,row.getConfirmedAt()); assertFalse(row.claim(now.plusSeconds(100),false));
    }
    @Test void backoffIsBoundedAndNeverExpiresFinancialReservation() {
        var row=new OrderDispatch(Order.builder().id(1L).build()); var now=Instant.now();
        for(int attempt=1;attempt<=10;attempt++) {
            assertTrue(row.claim(now,attempt==1)); row.finish(attempt,false,now);
            long delay=java.time.Duration.between(now,row.getNextAttemptAt()).toSeconds();
            assertEquals(Math.min(60,1L<<Math.min(attempt,6)),delay);
            assertNull(row.getConfirmedAt()); now=row.getNextAttemptAt();
        }
    }
}
