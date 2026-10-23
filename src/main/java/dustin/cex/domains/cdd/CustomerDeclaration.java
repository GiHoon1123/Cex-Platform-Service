package dustin.cex.domains.cdd;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 고객의 신고이며 증명된 사실이라는 뜻은 아니다. 주민등록번호·여권번호·신분증 사본은 받지 않는다. */
public record CustomerDeclaration(String fullName,LocalDate dateOfBirth,String nationality,String residenceCountry,
        String address,Occupation occupation,Purpose purpose,SourceOfFunds sourceOfFunds,
        BigDecimal expectedMonthlyQuote,Boolean beneficialOwnerSelf,Pep pep) {
    public enum Occupation {EMPLOYED,SELF_EMPLOYED,STUDENT,RETIRED,UNEMPLOYED,OTHER}
    public enum Purpose {PERSONAL_INVESTMENT,PAYMENT,OTHER}
    public enum SourceOfFunds {SALARY,BUSINESS,SAVINGS,INHERITANCE,OTHER}
    public enum Pep {NO,YES,UNKNOWN}
    private static final Set<String> COUNTRIES=Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3);
    public CustomerDeclaration validated() {
        if(dateOfBirth==null || dateOfBirth.isBefore(LocalDate.of(1900,1,1)) || dateOfBirth.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(19))
                || occupation==null || purpose==null || sourceOfFunds==null || beneficialOwnerSelf==null || pep==null
                || expectedMonthlyQuote==null || expectedMonthlyQuote.signum()<=0 || expectedMonthlyQuote.scale()>9
                || expectedMonthlyQuote.precision()-(long)expectedMonthlyQuote.scale()>21) throw invalid();
        return new CustomerDeclaration(name(fullName),dateOfBirth,country(nationality),country(residenceCountry),
            plain(address,300),occupation,purpose,sourceOfFunds,expectedMonthlyQuote.stripTrailingZeros(),beneficialOwnerSelf,pep);
    }
    /** 문장 부호·발음·로마자 표기를 바꾸지 않는다. 느슨한 유사도 판정으로 다른 사람을 연결하지 않는다. */
    public static String name(String value) {return plain(value,150).toUpperCase(Locale.ROOT);}
    public static String country(String value) {if(value==null || !COUNTRIES.contains(value)) throw invalid();return value;}
    private static String plain(String value,int max) {
        if(value==null || value.length()>max) throw invalid();
        var result=new StringBuilder();boolean space=false;
        for(int c:Normalizer.normalize(value,Normalizer.Form.NFC).codePoints().toArray()) {
            if(Character.isISOControl(c) || Character.getType(c)==Character.FORMAT || Character.getType(c)==Character.SURROGATE) throw invalid();
            if(Character.isWhitespace(c) || Character.isSpaceChar(c)) {space=result.length()>0;continue;}
            if(space) result.append(' ');space=false;result.appendCodePoint(c);
        }
        if(result.isEmpty()) throw invalid();return result.toString();
    }
    static ResponseStatusException invalid() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"고객 신고 항목을 확인하세요");}
    @Override public String toString() {return "CustomerDeclaration[개인정보 생략]";}
}
