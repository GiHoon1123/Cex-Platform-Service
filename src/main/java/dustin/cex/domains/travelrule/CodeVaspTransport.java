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

/** 목록·거래 출처 조회·정보 교환이 동일한 서명·본문·시간·크기 경계를 사용한다. 호출 종류와 경로는 내부에서 고정한다. */
@Component
final class CodeVaspTransport implements AutoCloseable {
    private final CodeVaspSettings settings;
    // 등록 신원·서명·암호문을 JVM 전역 프록시로 내보내지 않고 설정한 공급사/비공개 모듈에 직접 연결한다.
    private final HttpClient http=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
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
        boolean gatewayStarted=false;
        try{
            var command=json.createObjectNode().put("allianceName","CODE").put("apiType",type);
            if(expectedBody!=null)command.set("request",expectedBody);
            var signed=sign(command);
            var request=headers(path,signed);
            if(expectedBody==null){
                if(signed.raw().hasNonNull("body")&&(!signed.raw().path("body").isString()||!signed.raw().path("body").asString().isEmpty()))throw unavailable();
                request.GET();
            }else{
                String body=text(signed.raw(),"body",65536);
                // 모듈이 다른 거래/수신 주소를 서명했으면 전송하지 않는다. JSON 의미를 대조한 뒤 서명된 원래 바이트를 그대로 보낸다.
                if(!json.readTree(body).equals(expectedBody))throw unavailable();
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            }
            // 이 표시 이후의 예외는 실제 미접수를 증명하지 않는다. 서명 모듈 단계의 실패와 구분한다.
            gatewayStarted=true;
            return call(request,2097152);
        }catch(RuntimeException failure){
            if(expectedBody!=null)throw new CodeVaspSubmissionFailure(gatewayStarted?CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN:CodeVaspSubmissionFailure.Admission.NOT_SENT);
            throw unavailable();
        }
    }
    private record Signed(JsonNode raw,String signature,String date,long nonce){
        @Override public String toString(){return "Signed[서명 자료 생략]";}
    }
    private Signed sign(JsonNode command){
        var signed=call(HttpRequest.newBuilder(settings.cipher.resolve("/api/v1/code/api-payloads"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))),65536);
        String signature=text(signed,"signature",88),key=text(signed,"publicKey",44),date=text(signed,"dateTime",64);
        var nonce=signed.path("nonce");
        if(!CodeVaspSettings.key(signature,64)||!key.equals(settings.publicKey)||!nonce.isIntegralNumber()||!nonce.canConvertToLong()||nonce.longValue()<0)throw unavailable();
        var time=OffsetDateTime.parse(date,DateTimeFormatter.ISO_OFFSET_DATE_TIME);Instant now=Instant.now();
        if(!time.getOffset().equals(ZoneOffset.UTC)||time.toInstant().isBefore(now.minusSeconds(60))||time.toInstant().isAfter(now.plusSeconds(10)))throw unavailable();
        return new Signed(signed,signature,date,nonce.longValue());
    }
    private HttpRequest.Builder headers(String path,Signed signed){
        return HttpRequest.newBuilder(settings.api.resolve(path)).header("X-Code-Req-Datetime",signed.date())
            .header("X-Code-Req-Nonce",Long.toString(signed.nonce())).header("X-Code-Req-PubKey",settings.publicKey)
            .header("X-Code-Req-Signature",signed.signature()).header("X-Request-Origin","code:"+settings.entity);
    }
    record DecodedTransfer(JsonNode identity,String ciphertextHash){
        @Override public String toString(){return "DecodedTransfer[교환 원문 생략]";}
    }
    JsonNode transferData(CodeVaspTransferDataClient.Request input){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("이전거래 정보 교환은 DB 트랜잭션 밖에서 실행하세요");
        if(!settings.configured())throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);boolean gatewayStarted=false;
        try{
            // 개인정보는 본인 소유의 비공개 모듈에만 평문으로 보낸다. 게이트웨이에는 모듈이 암호화한 payload만 전달한다.
            var request=json.createObjectNode().put("transferId",input.query().requestId().toString()).put("txid",input.query().txid())
                .put("beneficiaryAddress",input.query().beneficiaryAddress()).put("beneficiaryWalletAddress",input.query().beneficiaryAddress())
                .put("beneficiaryNameIdentifierType","LEGL").put("beneficiaryNaturalPersonLastName",input.recipientName());
            var command=json.createObjectNode().put("allianceName","CODE").put("apiType","VERIFICATION_TRANSACTION_ID")
                .put("remotePublicKey",input.key().publicKey()).set("request",request);
            var signed=sign(command);String raw=text(signed.raw(),"body",65536);var body=json.readTree(raw);
            if(body==null||!body.isObject()||body.size()!=4||!input.query().requestId().toString().equals(text(body,"transferId",36))
                ||!input.query().txid().equals(text(body,"txid",64))||!input.query().beneficiaryAddress().equals(text(body,"beneficiaryAddress",120)))throw unavailable();
            // 추가 평문 개인정보 필드가 생겨도 조용히 전송하지 않는다. 본문에는 공개 결합 필드3개와 암호문만 허용한다.
            for(String field:body.propertyNames())if(!Set.of("transferId","txid","beneficiaryAddress","payload").contains(field))throw unavailable();
            ciphertext(text(body,"payload",65536));
            if(!input.key().expiresAt().isAfter(Instant.now()))throw unavailable();
            var httpRequest=headers("/v1/code/verification/tx/"+input.peer().entityId(),signed)
                .header("X-Code-Req-Remote-PubKey",input.key().publicKey()).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(raw));
            gatewayStarted=true;
            return call(httpRequest,2097152);
        }catch(Exception failure){
            // 서명/본문 검사 전의 미전송과 PUT 이후·복호화 단계의 불명을 구분하고 원문 원인을 전파하지 않는다.
            throw new CodeVaspSubmissionFailure(gatewayStarted?CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN:CodeVaspSubmissionFailure.Admission.NOT_SENT);
        }
    }
    JsonNode authorizeWithdrawal(CodeVaspWithdrawalAuthorizationClient.Request input){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("출금 이전 승인은 DB 트랜잭션 밖에서 실행하세요");
        if(!settings.configured())throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);
        boolean gatewayStarted=false;
        try{
            // 지급액은 고객 총 차감액이 아닌 실제 체인 지급액이다. 신원 평문은 비공개 모듈에만 보낸다.
            var publicFields=json.createObjectNode().put("transferId",input.transferId().toString()).put("currency","BTC")
                .put("amount",input.amount().toPlainString()).put("tradePrice",input.krwValue().toPlainString())
                .put("tradeCurrency","KRW").put("isExceedingThreshold",input.thresholdExceeded())
                .put("address",input.address()).put("network","BTC");
            var request=publicFields.deepCopy().put("nameIdentifierType","LEGL")
                .put("originatorNaturalPersonLastName",input.originatorName()).put("originatorNaturalPersonDob",input.originatorBirth().toString())
                .put("originatorWalletAddress",input.originatorAccount()).put("beneficiaryWalletAddress",input.address())
                .put("beneficiaryNameIdentifierType","LEGL").put("beneficiaryNaturalPersonLastName",input.beneficiaryName());
            var command=json.createObjectNode().put("allianceName","CODE").put("apiType","TRANSFER_AUTHORIZATION")
                .put("remotePublicKey",input.key().publicKey()).set("request",request);
            var signed=sign(command);String raw=text(signed.raw(),"body",65536);var body=json.readTree(raw);
            if(body==null||!body.isObject())throw unavailable();
            for(String field:publicFields.propertyNames())if(!publicFields.path(field).equals(body.path(field)))throw unavailable();
            for(String field:body.propertyNames())if(!publicFields.has(field)&&!Set.of("payload","historicalCost","originatingVasp").contains(field))throw unavailable();
            // 현재 요청하지 않은 원가·사업자 정보는 임의 생성하지 않는다. 신원 필드가 평문에 추가돼도 차단한다.
            if(body.has("historicalCost")&&(!body.path("historicalCost").isString()||!body.path("historicalCost").asText().isEmpty()))throw unavailable();
            if(body.has("originatingVasp")&&(!body.path("originatingVasp").isObject()||!body.path("originatingVasp").isEmpty()))throw unavailable();
            ciphertext(text(body,"payload",65536));
            if(!input.key().expiresAt().isAfter(Instant.now()))throw unavailable();
            var httpRequest=headers("/v1/code/transfer/"+input.peer().entityId(),signed)
                .header("X-Code-Req-Remote-PubKey",input.key().publicKey()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(raw));
            gatewayStarted=true;
            return call(httpRequest,2097152);
        }catch(Exception failure){
            // 게이트웨이 전송 뒤에는 검증 응답을 잃었을 수 있다. 새 이전 UUID로 자동 재요청하지 않는다.
            throw new CodeVaspSubmissionFailure(gatewayStarted?CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN:CodeVaspSubmissionFailure.Admission.NOT_SENT);
        }
    }
    DecodedTransfer decrypt(JsonNode envelope,CodeVaspDirectoryClient.Key key){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("이전거래 복호화는 DB 트랜잭션 밖에서 실행하세요");
        try{
            String encrypted=text(envelope,"payload",65536);byte[] encryptedBytes=ciphertext(encrypted);
            var decrypt=json.createObjectNode().put("allianceName","CODE").put("data",encrypted).put("remotePublicKey",key.publicKey());
            var decoded=call(HttpRequest.newBuilder(settings.cipher.resolve("/api/v1/code/decrypt"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(decrypt))),262144);
            var identity=decoded.path("body");String original=text(decoded,"bodyString",65536);
            if(!identity.isObject()||!identity.equals(json.readTree(original)))throw unavailable();
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encryptedBytes));
            return new DecodedTransfer(identity,hash);
        }catch(Exception invalid){throw unavailable();}
    }
    private static byte[] ciphertext(String value){
        byte[] decoded=Base64.getDecoder().decode(value);
        if(decoded.length<40||decoded.length>49152||!Base64.getEncoder().encodeToString(decoded).equals(value))throw unavailable();
        return decoded;
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
