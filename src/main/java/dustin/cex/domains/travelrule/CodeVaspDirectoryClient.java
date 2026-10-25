package dustin.cex.domains.travelrule;

import dustin.cex.shared.http.LimitedResponseBody;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 공식 내부 모듈이 만든 서명 헤더로 기술 연동 사업자 목록을 읽는다. 목록 포함을 법적/금융 승인으로 바꾸지 않는다. */
@Component
public class CodeVaspDirectoryClient implements AutoCloseable {
    public record Key(String publicKey,Instant expiresAt){}
    public record Vasp(String entityId,String name,String legalName,String country,String alliance,boolean reachable,List<Key> keys){public Vasp{keys=List.copyOf(keys);}}
    public record Directory(Instant readStartedAt,Instant readFinishedAt,List<Vasp> vasps){public Directory{vasps=List.copyOf(vasps);}}
    // 공식 유효기한은 UTC이며 문서 예제에는 시차 표기가 없다. JVM의 로컬 시간대를 추측해 사용하지 않는다.
    private static final DateTimeFormatter KEY_EXPIRY=new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .optionalStart().appendOffsetId().optionalEnd().parseDefaulting(ChronoField.OFFSET_SECONDS,0).toFormatter();
    private final CodeVaspSettings settings;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).build()).build()).build();
    public CodeVaspDirectoryClient(CodeVaspSettings settings){this.settings=settings;}
    public Directory read(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("이전거래 공급사 호출은 DB 트랜잭션 밖에서 실행하세요");
        if(!settings.configured())throw unavailable();Instant started=Instant.now();
        try{
            // 고객 원문/임의 API 종류를 받지 않는다. 서명 개인키와 암호 구현은 공식 모듈에만 둔다.
            var signed=call(HttpRequest.newBuilder(settings.cipher.resolve("/api/v1/code/api-payloads"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"allianceName\":\"CODE\",\"apiType\":\"SEARCH_VASP_LIST\"}")),65536);
            String signature=text(signed,"signature",88),key=text(signed,"publicKey",44),date=text(signed,"dateTime",64);
            var nonce=signed.path("nonce");
            if(!CodeVaspSettings.key(signature,64)||!key.equals(settings.publicKey)||!nonce.isIntegralNumber()||!nonce.canConvertToLong()||nonce.longValue()<0)throw unavailable();
            var time=utc(date);Instant now=Instant.now();
            // 이 분량/시각 제한은 모듈 오설정·오래된 응답을 막는 자체 정책이며 공급사 전체 인증 허용 창의 복제가 아니다.
            if(time.isBefore(now.minusSeconds(60))||time.isAfter(now.plusSeconds(10)))throw unavailable();
            if(signed.hasNonNull("body")&&(!signed.path("body").isString()||!signed.path("body").asString().isEmpty()))throw unavailable();
            var response=call(HttpRequest.newBuilder(settings.api.resolve("/v1/code/vasps"))
                .header("X-Code-Req-Datetime",date).header("X-Code-Req-Nonce",Long.toString(nonce.longValue()))
                .header("X-Code-Req-PubKey",key).header("X-Code-Req-Signature",signature)
                .header("X-Request-Origin","code:"+settings.entity).GET(),2097152);
            var rows=response.path("vasps");if(!rows.isArray()||rows.size()>5000)throw unavailable();
            var vasps=new ArrayList<Vasp>();var ids=new HashSet<String>();
            for(var row:rows){
                String id=text(row,"vaspEntityId",128),name=text(row,"vaspName",128),legal=text(row,"vaspLegalName",256),country=text(row,"countryOfRegistration",2),alliance=text(row,"allianceName",64),health=text(row,"health",4);
                if(!ids.add(id)||!country.matches("[A-Z]{2}")||!(health.equals("up")||health.equals("down")))throw unavailable();
                var sourceKeys=row.path("pubkeys");if(!sourceKeys.isArray()||sourceKeys.size()>100)throw unavailable();
                var keys=new ArrayList<Key>();var seen=new HashSet<String>();
                for(var entry:sourceKeys){String value=text(entry,"pubkey",44);if(!CodeVaspSettings.key(value,32)||!seen.add(value))throw unavailable();keys.add(new Key(value,keyExpiry(text(entry,"expiresAt",64))));}
                // 중단 사업자/기한 지난 키도 원문 상태대로 유지한다. 조회에서 조용히 제거하거나 승인으로 변환하지 않는다.
                vasps.add(new Vasp(id,name,legal,country,alliance,health.equals("up"),keys));
            }
            return new Directory(started,Instant.now(),vasps);
        }catch(ResponseStatusException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
    }
    private JsonNode call(HttpRequest.Builder request,long limit){
        try{
            var pending=http.sendAsync(request.timeout(Duration.ofSeconds(5)).header("Accept","application/json").build(),info->new LimitedResponseBody(limit));
            try{
                // 헤더뿐 아니라 본문 수신까지5초다. 리다이렉트/응용 코드의 재시도/오류 본문 반환을 하지 않는다.
                var response=pending.get(5,TimeUnit.SECONDS);if(response.statusCode()!=200)throw unavailable();
                var value=json.readTree(response.body());if(value==null||!value.isObject())throw unavailable();return value;
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException failure){throw unavailable();}
    }
    private static String text(JsonNode node,String field,int max){var value=node.path(field);if(!value.isString())throw unavailable();String text=value.asString();if(text.isBlank()||text.length()>max||!text.equals(text.strip())||text.codePoints().anyMatch(Character::isISOControl))throw unavailable();return text;}
    private static Instant keyExpiry(String value){var time=OffsetDateTime.parse(value,KEY_EXPIRY);if(!time.getOffset().equals(ZoneOffset.UTC))throw unavailable();return time.toInstant();}
    private static Instant utc(String value){var time=OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME);if(!time.getOffset().equals(ZoneOffset.UTC))throw unavailable();return time.toInstant();}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"이전거래 사업자 목록을 확인할 수 없습니다");}
    @jakarta.annotation.PreDestroy @Override public void close(){http.shutdownNow();}
}
