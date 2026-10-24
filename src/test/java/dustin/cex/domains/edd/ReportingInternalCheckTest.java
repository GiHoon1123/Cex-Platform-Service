package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 주말·연도 경계에도 24시간이라는 내부 목표만 계산하며 목표 정각부터 초과로 표시한다. */
class ReportingInternalCheckTest {
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void targetBoundaryUsesExactElapsedDayInsteadOfBusinessCalendar(long nanos) {
        Instant decision=Instant.parse("2027-12-31T15:00:00Z");
        Instant target=Instant.parse("2028-01-01T15:00:00Z");
        var check=ReportingFollowupService.InternalCheck.at(decision,target.plusNanos(nanos));
        assertEquals(target,check.targetAt());
        assertEquals(nanos>=0,check.overdue());
    }
}
