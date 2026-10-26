package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 정확한 근거의 지정 검토·독립 서명만 노출한다. 실행 확정은 고객/운영자 HTTP에서 호출하지 않는다. */
@RestController
@RequestMapping("/api/admin/withdrawals/BTC/payout-reviews")
public class BitcoinWithdrawalPayoutController {
    private final BitcoinWithdrawalPayoutReviews reviews;
    private final AuditService audit;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public BitcoinWithdrawalPayoutController(BitcoinWithdrawalPayoutReviews reviews,AuditService audit){this.reviews=reviews;this.audit=audit;}
    @PostMapping(consumes="application/json")
    public BitcoinWithdrawalPayoutReviews.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,HttpServletRequest request)throws IOException {
        var body=body(userId,request,Set.of("transferId","checkerId","requestKey","note"));final UUID transfer,key;final long checker;final String note;
        try {
            transfer=uuid(body,"transferId");key=uuid(body,"requestKey");var id=body.path("checkerId");
            if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0||!body.path("note").isString())throw new IllegalArgumentException();
            checker=id.longValue();note=body.path("note").stringValue();
        }catch(RuntimeException invalid){throw invalid(userId);}
        return reviews.propose(userId,sessionId,transfer,checker,key,note);
    }
    @PostMapping("/{id}/inspect")
    public BitcoinWithdrawalPayoutReviews.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,HttpServletRequest request)throws IOException {
        if(request.getInputStream().read()!=-1)throw invalid(userId);return reviews.inspect(userId,sessionId,id);
    }
    @PostMapping("/{id}/{decision:approve|reject}/options")
    public BitcoinWithdrawalPayoutReviews.Confirmation options(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable String decision,HttpServletRequest request)throws IOException {
        var body=body(userId,request,Set.of("payloadHash"));return reviews.options(userId,sessionId,id,hash(userId,body),decision.equals("approve"));
    }
    @PostMapping("/{id}/{decision:approve|reject}")
    public BitcoinWithdrawalPayoutReviews.Request decide(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable String decision,HttpServletRequest request)throws IOException {
        var body=body(userId,request,Set.of("payloadHash","proof"));String hash=hash(userId,body);final OperatorMfaService.AuthorizationProof proof;
        try {
            var node=body.path("proof");if(!node.isObject()||node.size()!=2||!node.path("credential").isObject())throw new IllegalArgumentException();
            proof=new OperatorMfaService.AuthorizationProof(uuid(node,"challengeId"),node.path("credential"));
        }catch(RuntimeException invalid){throw invalid(userId);}
        return reviews.decide(userId,sessionId,id,hash,decision.equals("approve"),proof);
    }
    private JsonNode body(long actor,HttpServletRequest request,Set<String> fields)throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw invalid(actor);
        try {var node=json.readTree(bytes);if(node==null||!node.isObject()||node.size()!=fields.size()||!node.propertyNames().equals(fields))throw new IllegalArgumentException();return node;}
        catch(RuntimeException invalid){throw invalid(actor);}
    }
    private String hash(long actor,JsonNode body){var value=body.path("payloadHash");if(!value.isString()||!value.stringValue().matches("[0-9a-f]{64}"))throw invalid(actor);return value.stringValue();}
    private static UUID uuid(JsonNode body,String field){var node=body.path(field);if(!node.isString())throw new IllegalArgumentException();String raw=node.stringValue();UUID value=UUID.fromString(raw);if(!value.toString().equals(raw))throw new IllegalArgumentException();return value;}
    private ResponseStatusException invalid(long actor){audit.denied(actor,"BTC_PAYOUT_REVIEW_INPUT","bitcoin-payout-review-restricted","INVALID_BODY");return new ResponseStatusException(HttpStatus.BAD_REQUEST,"지급 검토의 지정 입력·서명 본문을 확인하세요");}
}
