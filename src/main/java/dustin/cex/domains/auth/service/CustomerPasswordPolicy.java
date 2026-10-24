package dustin.cex.domains.auth.service;
import org.springframework.stereotype.Component;

/** 신규 고객 비밀번호 설정 정책이다. 문자를 자르거나 공백/유니코드를 몰래 바꾸지 않는다. */
@Component
public class CustomerPasswordPolicy {
    public boolean accepts(String value){
        // 고객에게 아직 다중 인증이 없으므로 길이는15개 코드 포인트 이상으로 정한다.
        // 상한은 기존 검증기의256 UTF-16 코드 단위와 맞춰 과도한 해시 입력을 제한한다.
        if(value==null||value.isBlank()||value.length()>256||value.codePointCount(0,value.length())<15)return false;
        // 정상 이모지의 서로게이트 쌍은 허용하되 단독 서로게이트와 제어 문자는 받지 않는다.
        return value.codePoints().noneMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF));
    }
}
