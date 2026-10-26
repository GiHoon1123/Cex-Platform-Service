package dustin.cex.domains.bitcoin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 고객 번호·네트워크·수수료는 입력받지 않는다. 검증된 세션과 서버 요율의 견적만 제공한다. */
@RestController
@RequestMapping("/api/customer/withdrawals/BTC/quotes")
public class CustomerBitcoinWithdrawalQuoteController {
    private final BitcoinWithdrawalQuotes quotes;
    private final dustin.cex.domains.security.AuditService audit;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(128).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public CustomerBitcoinWithdrawalQuoteController(BitcoinWithdrawalQuotes quotes,dustin.cex.domains.security.AuditService audit){this.quotes=quotes;this.audit=audit;}
    @PostMapping(consumes="application/json")
    public BitcoinWithdrawalQuotes.Quote prepare(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request)throws java.io.IOException{
        // 크기/실제 문자열 타입을 먼저 확인한다. 숫자 금액의 문자열 강제 변환이나 미지 수수료 필드를 수용하지 않는다.
        byte[] body=request.getInputStream().readNBytes(2049);if(body.length>2048){audit.denied(userId,"BTC_WITHDRAWAL_QUOTE_REQUEST","customer:"+userId,"INVALID_BODY_SIZE");throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);}
        String address,amount;
        try{var node=json.readTree(body);if(node==null||!node.isObject()||node.size()!=2||!node.path("address").isString()||!node.path("amount").isString())throw new IllegalArgumentException();address=node.path("address").stringValue();amount=node.path("amount").stringValue();}
        catch(RuntimeException invalid){audit.denied(userId,"BTC_WITHDRAWAL_QUOTE_REQUEST","customer:"+userId,"INVALID_BODY");throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"정확한 지급 주소와 금액을 확인하세요");}
        return quotes.prepare(userId,sessionId,key,address,amount);
    }
    @GetMapping("/{id}")
    public BitcoinWithdrawalQuotes.Quote own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return quotes.own(userId,sessionId,id);}
}
