package dustin.cex.domains.travelrule;

import dustin.cex.shared.http.LimitedResponseBody;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
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

/** 목록과 거래 출처 조회가 동일한 서명·본문·시간·크기 경계를 사용한다. 호출 종류와 경로는 내부에서 고정한다. */
@Component
final class CodeVaspTransport implements AutoCloseable {
    private final CodeVaspSettings settings;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).build()).build()).build();
    CodeVaspTransport(CodeVaspSettings settings){this.settings=settings;}

    JsonNode directory(){return exchange("SEARCH_VASP_LIST","/v1/code/vasps",null);}
    JsonNode searchTransaction(UUID requestId,String txid,String address){
        var request=json.createObjectNode().put("txid",txid).put("beneficiaryAddress",address).put("requestId",requestId.toString());
        return exchange("SEARCH_TRANSACTION_ID","/v1/code/verification/tx",request);
    }
    JsonNode transactionResult(UUID requestId){return exchange("FETCH_TRANSACTION_ID","/v1/code/verification/tx/"+requestId,null);}

    private JsonNode exchange(String type,String path,JsonNode expectedBody){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("이전거래 공급사 호출은 DB 트랜잭션 밖에서 실행하세요");
        if(!settings.configured())throw unavailable();
        try{
            var command=json.createObjectNode().put("allianceName","CODE").put("apiType",type);
            if(expectedBody!=null)command.set("request",expectedBody);
            var signed=call(HttpRequest.newBuilder(settings.cipher.resolve("/api/v1/code/api-payloads"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))),65536);
            String signature=text(signed,"signature",88),key=text(signed,"publicKey",44),date=text(signed,"dateTime",64);
            var nonce=signed.path("nonce");
            if(!CodeVaspSettings.key(signature,64)||!key.equals(settings.publicKey)||!nonce.isIntegralNumber()||!nonce.canConvertToLong()||nonce.longValue()<0)throw unavailable();
            var time=OffsetDateTime.parse(date,DateTimeFormatter.ISO_OFFSET_DATE_TIME);Instant now=Instant.now();
            if(!time.getOffset().equals(ZoneOffset.UTC)||time.toInstant().isBefore(now.minusSeconds(60))||time.toInstant().isAfter(now.plusSeconds(10)))throw unavailable();
            var request=HttpRequest.newBuilder(settings.api.resolve(path))
                .header("X-Code-Req-Datetime",date).header("X-Code-Req-Nonce",Long.toString(nonce.longValue()))
                .header("X-Code-Req-PubKey",key).header("X-Code-Req-Signature",signature)
                .header("X-Request-Origin","code:"+settings.entity);
            if(expectedBody==null){
                if(signed.hasNonNull("body")&&(!signed.path("body").isString()||!signed.path("body").asString().isEmpty()))throw unavailable();
                request.GET();
            }else{
                String body=text(signed,"body",65536);
                // 모듈이 다른 거래/수신 주소를 서명했으면 전송하지 않는다. JSON 의미를 대조한 뒤 서명된 원래 바이트를 그대로 보낸다.
                if(!json.readTree(body).equals(expectedBody))throw unavailable();
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            }
            return call(request,2097152);
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
    static String text(JsonNode node,String field,int max){var value=node.path(field);if(!value.isString())throw unavailable();String text=value.asString();if(text.isBlank()||text.length()>max||!text.equals(text.strip())||text.codePoints().anyMatch(Character::isISOControl))throw unavailable();return text;}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"이전거래 공급사 요청을 완료할 수 없습니다");}
    @jakarta.annotation.PreDestroy @Override public void close(){http.shutdownNow();}
}
