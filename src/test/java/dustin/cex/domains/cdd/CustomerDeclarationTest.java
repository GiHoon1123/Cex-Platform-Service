package dustin.cex.domains.cdd;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 이름 표준화는 같은 표기의 공백·대소문자 차이만 처리하고 생일·국적·성년 경계를 검증한다. */
class CustomerDeclarationTest {
    CustomerDeclaration sample(LocalDate birth,String country,BigDecimal volume) {
        return new CustomerDeclaration("가상 이름",birth,country,"KOR","가상 주소",CustomerDeclaration.Occupation.EMPLOYED,
            CustomerDeclaration.Purpose.PERSONAL_INVESTMENT,CustomerDeclaration.SourceOfFunds.SALARY,volume,true,CustomerDeclaration.Pep.NO);
    }
    @Test void exactNormalizationPreservesPunctuationAndDoesNotTransliterate() {
        assertEquals("ALICE DOE",CustomerDeclaration.name("  Alice\u00a0  Doe  "));
        assertNotEquals(CustomerDeclaration.name("Alice-Doe"),CustomerDeclaration.name("Alice Doe"));
        assertEquals("가",CustomerDeclaration.name("가"));assertNotEquals(CustomerDeclaration.name("김철수"),CustomerDeclaration.name("Kim Cheolsu"));
    }
    @Test void controlBidiAndInvisibleCharactersAreRejected() {
        for(String name:new String[]{""," ","가상\n이름","가상\u202e이름","가상\u200b이름","가".repeat(151)})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->CustomerDeclaration.name(name));
    }
    @Test void adulthoodUsesExplicitKoreanCalendarBoundary() {
        LocalDate boundary=LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(19);
        assertNotNull(sample(boundary,"KOR",BigDecimal.ONE).validated());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sample(boundary.plusDays(1),"KOR",BigDecimal.ONE).validated());
    }
    @Test void countryAndExactVolumeMustBeComplete() {
        var birth=LocalDate.of(1990,1,1);
        for(String country:new String[]{"KR","kor","ZZZ"}) assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sample(birth,country,BigDecimal.ONE).validated());
        for(BigDecimal volume:new BigDecimal[]{BigDecimal.ZERO,new BigDecimal("-1"),new BigDecimal("0.0000000001"),new BigDecimal("1e21"),new BigDecimal("1e2147483647")})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sample(birth,"KOR",volume).validated());
        assertFalse(sample(birth,"KOR",BigDecimal.ONE).toString().contains("가상 이름"));
    }
}
