package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;

/** 명시적 확인 불가 판단과 사유 형식만 검사한다. 사유의 외부 진위나 법적 종료 완료를 인증하지 않는다. */
class RelationshipExitFactsTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","안\n쪽","안\u202E쪽"})
    void invalidRationaleIsRejected(String rationale) {assertThrows(ResponseStatusException.class,()->new RelationshipExitService.Facts(true,rationale).validated());}
    @Test void unableJudgmentCannotBeOmittedOrSubstitutedByRisk() {assertThrows(ResponseStatusException.class,()->new RelationshipExitService.Facts(false,"확인 필요").validated());}
    @Test void normalizationLengthBoundaryAndSafePrinting() {
        var facts=new RelationshipExitService.Facts(true," 가".strip()+"나".repeat(1999)).validated();assertEquals(2000,facts.rationale().length());assertFalse(facts.toString().contains("나"));
        assertThrows(ResponseStatusException.class,()->new RelationshipExitService.Facts(true,"가".repeat(2001)).validated());
    }
}
