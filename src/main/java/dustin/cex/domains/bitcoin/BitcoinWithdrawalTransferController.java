package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 운영자는 허용 상대와 재요청 키만 선택한다. 고객·금액·주소·신원·성공 결과는 서버 원본에서만 가져온다. */
@RestController
@RequestMapping("/api/admin/withdrawals/BTC")
public class BitcoinWithdrawalTransferController {
    private final BitcoinWithdrawalTransferReview reviews;
    private final AuditService audit;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public BitcoinWithdrawalTransferController(BitcoinWithdrawalTransferReview reviews,AuditService audit){this.reviews=reviews;this.audit=audit;}
    @PostMapping(value="/{id}/transfer-intents",consumes="application/json")
    public BitcoinWithdrawalTransferReview.State prepare(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,HttpServletRequest request)throws IOException {
        byte[] body=request.getInputStream().readNBytes(2049);
        if(body.length>2048)throw invalid(userId,id,HttpStatus.PAYLOAD_TOO_LARGE);
        final String peer;final UUID key;
        try {
            var node=json.readTree(body);
            if(node==null||!node.isObject()||node.size()!=2||!node.path("peerId").isString()||!node.path("requestKey").isString())throw new IllegalArgumentException();
            peer=node.path("peerId").asString();String value=node.path("requestKey").asString();key=UUID.fromString(value);
            if(!key.toString().equals(value))throw new IllegalArgumentException();
        }catch(RuntimeException malformed){throw invalid(userId,id,HttpStatus.BAD_REQUEST);}
        return reviews.prepare(userId,sessionId,id,peer,key);
    }
    @PostMapping("/transfer-intents/{id}/execute")
    public BitcoinWithdrawalTransferReview.State execute(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,HttpServletRequest request)throws IOException {
        if(request.getInputStream().read()!=-1)throw invalid(userId,id,HttpStatus.BAD_REQUEST);
        return reviews.execute(userId,sessionId,id);
    }
    @GetMapping("/transfer-intents/{id}")
    public BitcoinWithdrawalTransferReview.State read(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return reviews.read(userId,sessionId,id);}
    private ResponseStatusException invalid(long actor,UUID id,HttpStatus status){audit.denied(actor,"BTC_WITHDRAWAL_TRANSFER_INPUT","bitcoin-withdrawal-transfer:"+id,"INVALID_BODY");return new ResponseStatusException(status,"이전 확인은 허용 상대·재요청 키 외의 입력을 받지 않습니다");}
}
