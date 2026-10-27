package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.security.OperationalHealthService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 공개 상태의 최소 노출과 인증된 금융 작업 적체 조회를 실제 스키마에서 검증한다. */
class OperationalHealthIntegrationTest extends EddTestSupport {
    @Autowired OperationalHealthService health;

    @Test void publicLivenessAndReadinessExposeOnlyAStatus()throws Exception{
        for(String path:List.of("/api/health/live","/api/health/ready"))mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.queues").doesNotExist());
    }
    @Test void operationalBacklogsRequireAnOperatorRoleAndContainNoWorkIdentifiers()throws Exception{
        mvc.perform(get("/api/admin/operations/status").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
        String raw=mvc.perform(get("/api/admin/operations/status").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.queues.length()").value(6)).andReturn().getResponse().getContentAsString();
        assertFalse(raw.contains(customer.getEmail()));assertFalse(raw.contains(customer.getId()+"-"));
    }
    @Test void readinessFailsClosedWhenTheDatabaseIsUnavailable(){var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("격리 장애"));assertEquals("DOWN",new OperationalHealthService(jdbc).ready().status());}
}
