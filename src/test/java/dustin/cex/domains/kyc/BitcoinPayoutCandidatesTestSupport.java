package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 준비 시험은 실제 승인된 같은 원본 위에서 시작한다. 노드/출처 대역은 상위 공통 기반을 따른다. */
abstract class BitcoinPayoutCandidatesTestSupport extends BitcoinWithdrawalPayoutTestSupport {
    BitcoinWithdrawalPayoutReviews.Request approved;
    @BeforeEach void approvedFixture()throws Exception {approved=decide(proposal(),true);}
    BitcoinPayoutCandidates.Result prepareCandidate(){return candidates.prepare(approved.payload().id());}
}
