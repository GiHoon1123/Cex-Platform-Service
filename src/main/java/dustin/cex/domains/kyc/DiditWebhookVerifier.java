package dustin.cex.domains.kyc;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2의 전체 본문 HMAC만 허용한다. 단순 서명·원문 서명으로 자동 전환하지 않는다.
 * Didit의 Python 정규화 계약은 JCS와 다르므로 일반 JSON 정렬만으로 같다고 가정하지 않는다.
 */
@Component
public class DiditWebhookVerifier {
    private final DiditSettings settings;
    final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(1048576).build()).build())
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    public DiditWebhookVerifier(DiditSettings settings) {this.settings=settings;}
    JsonNode verify(byte[] body,String signature,String timestamp) {
        if(!settings.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"KYC 연동 설정이 필요합니다");
        try {
            if(body==null || body.length==0 || body.length>1048576 || signature==null || !signature.matches("[0-9a-f]{64}")
                    || timestamp==null || !timestamp.matches("[0-9]{1,12}")) throw rejected();
            long sent=Long.parseLong(timestamp),now=Instant.now().getEpochSecond();
            if(sent<now-300 || sent>now+300) throw rejected();
            var parsed=json.readTree(body);
            // 헤더만 새 시각으로 바꾼 과거 본문의 재전송도 막는다. 본문 시각 자체가 HMAC에 들어 있다.
            if(!parsed.isObject() || !parsed.path("timestamp").isIntegralNumber() || !parsed.path("timestamp").canConvertToLong() || parsed.path("timestamp").longValue()!=sent) throw rejected();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(canonical(parsed).getBytes(StandardCharsets.UTF_8)),HexFormat.of().parseHex(signature))) throw rejected();
            return parsed;
        } catch(ResponseStatusException e) {throw e;}
        catch(RuntimeException | GeneralSecurityException e) {throw rejected();}
    }
    String canonical(JsonNode node) {
        if(node.isObject()) {
            var fields=new ArrayList<String>();node.propertyNames().forEach(fields::add);
            // Python은 유니코드 코드 포인트 순서다. Java 문자열의 UTF-16 순서와 비BMP 문자에서 다르다.
            fields.sort((a,b)->Arrays.compare(a.codePoints().toArray(),b.codePoints().toArray()));
            var parts=new ArrayList<String>();for(String name:fields) parts.add(quote(name)+":"+canonical(node.get(name)));
            return "{"+String.join(",",parts)+"}";
        }
        if(node.isArray()) {var items=new ArrayList<String>();node.forEach(value->items.add(canonical(value)));return "["+String.join(",",items)+"]";}
        if(node.isString()) return quote(node.asText());
        if(node.isIntegralNumber()) return node.bigIntegerValue().toString();
        if(node.isFloatingPointNumber()) return floating(node.doubleValue());
        if(node.isBoolean()) return node.asBoolean()?"true":"false";
        if(node.isNull()) return "null";
        throw rejected();
    }
    private String quote(String value) {
        // 불완전한 서로게이트는 UTF-8로 조용히 대체하지 않는다. 서명 대상 바이트가 달라질 수 있다.
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw rejected();}
            else if(Character.isLowSurrogate(c)) throw rejected();
        }
        return json.writeValueAsString(value);
    }
    private String floating(double value) {
        if(!Double.isFinite(value)) throw rejected();
        BigDecimal exact=new BigDecimal(value);
        // 공급사의 shorten_floats는 정수 값인 부동소수를 임의 정밀도 정수로 바꾼다.
        if(value==Math.rint(value)) return exact.toBigIntegerExact().toString();
        BigDecimal shortest=null;
        // JDK의 십진 반올림과 역변환을 사용한다. 서명·해시·JSON 문자열 처리는 표준 구현에 맡긴다.
        // Python과 Java의 지수 자릿수·고정소수점 선택 차이만 이 공급사 계약에 맞춘다.
        for(int precision=1;precision<=17;precision++) {
            var candidate=exact.round(new MathContext(precision,RoundingMode.HALF_EVEN)).stripTrailingZeros();
            if(Double.doubleToLongBits(candidate.doubleValue())==Double.doubleToLongBits(value)) {shortest=candidate;break;}
        }
        if(shortest==null) throw rejected();
        int exponent=shortest.precision()-shortest.scale()-1;
        if(exponent>=-4 && exponent<16) return shortest.toPlainString();
        String digits=shortest.unscaledValue().abs().toString();
        return (value<0?"-":"")+digits.charAt(0)+(digits.length()>1?"."+digits.substring(1):"")
            +"e"+(exponent<0?"-":"+")+String.format(Locale.ROOT,"%02d",Math.abs(exponent));
    }
    String digest(JsonNode node) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(node).getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e) {throw new IllegalStateException(e);}
    }
    private ResponseStatusException rejected() {return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"KYC 웹훅 인증 실패");}
}
