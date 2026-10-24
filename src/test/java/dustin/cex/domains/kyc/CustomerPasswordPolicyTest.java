package dustin.cex.domains.kyc;
import dustin.cex.domains.auth.service.CustomerPasswordPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 정책의 길이·문자 보존·유니코드 경계를 검증한다. 해시 구현을 복제하지 않는다. */
class CustomerPasswordPolicyTest {
    private final CustomerPasswordPolicy policy=new CustomerPasswordPolicy();
    @Test void lengthBoundariesAreEnforced(){assertFalse(policy.accepts("a".repeat(14)));assertTrue(policy.accepts("a".repeat(15)));assertTrue(policy.accepts("a".repeat(256)));assertFalse(policy.accepts("a".repeat(257)));assertFalse(policy.accepts(null));}
    @Test void noCompositionRuleOrWhitespaceTrimmingIsAdded(){assertTrue(policy.accepts("long lowercase passphrase"));assertTrue(policy.accepts("  independent passphrase  "));assertFalse(policy.accepts(" ".repeat(15)));}
    @Test void codePointsAreCountedRatherThanUtf16Units(){assertFalse(policy.accepts("😀".repeat(14)));assertTrue(policy.accepts("😀".repeat(15)));assertTrue(policy.accepts("😀".repeat(128)));assertFalse(policy.accepts("😀".repeat(129)));}
    @Test void controlsAndUnpairedSurrogatesAreRejected(){assertFalse(policy.accepts("long\nindependent passphrase"));assertFalse(policy.accepts("a".repeat(15)+"\uD800"));assertFalse(policy.accepts("a".repeat(15)+"\uDC00"));}
}
