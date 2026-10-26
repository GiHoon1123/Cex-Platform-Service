package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.CustomerWithdrawalAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 고객은 서버가 고정한 견적 ID에만 서명한다. 출금액·수수료·지급지는 이 요청의 입력이 아니다. */
@RestController
@RequestMapping("/api/customer/withdrawals/BTC/quotes/{quoteId}")
public class CustomerBitcoinWithdrawalController {
    private final BitcoinWithdrawalRequests withdrawals;
    private final BitcoinWithdrawalCancellations cancellations;
    private final AuditService audit;
    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
            .maxStringLength(4096).maxNumberLength(64).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public CustomerBitcoinWithdrawalController(BitcoinWithdrawalRequests withdrawals, BitcoinWithdrawalCancellations cancellations, AuditService audit) {
        this.withdrawals = withdrawals;
        this.cancellations = cancellations;
        this.audit = audit;
    }

    @PostMapping("/authorization-options")
    public CustomerWithdrawalAuthorization.Options options(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId, @PathVariable UUID quoteId) {
        return withdrawals.options(userId, sessionId, quoteId);
    }

    @PostMapping(value = "/reservations", consumes = "application/json")
    public BitcoinWithdrawalRequests.Receipt reserve(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId, @PathVariable UUID quoteId,
            HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) {
            audit.denied(userId, "BTC_WITHDRAWAL_REQUEST", "bitcoin-withdrawal:" + quoteId, "INVALID_BODY_SIZE");
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        final CustomerWithdrawalAuthorization.Proof proof;
        try {
            var node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.path("challengeId").isString() || !node.path("publicKey").isObject())
                throw new IllegalArgumentException();
            proof = new CustomerWithdrawalAuthorization.Proof(UUID.fromString(node.path("challengeId").stringValue()),
                node.path("publicKey"));
        } catch (RuntimeException invalid) {
            audit.denied(userId, "BTC_WITHDRAWAL_REQUEST", "bitcoin-withdrawal:" + quoteId, "INVALID_BODY");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "출금 일회 서명 본문을 확인하세요");
        }
        return withdrawals.submit(userId, sessionId, quoteId, proof);
    }

    @GetMapping("/reservations")
    public BitcoinWithdrawalRequests.Receipt status(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId, @PathVariable UUID quoteId) {
        return withdrawals.status(userId, sessionId, quoteId);
    }

    @PostMapping("/reservations/cancellation")
    public BitcoinWithdrawalRequests.Receipt cancel(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId,@PathVariable UUID quoteId,HttpServletRequest request)throws IOException {
        // 취소는 원래 총액의 반환만 허용한다. 새 금액·수수료·지급지 입력을 받지 않는다.
        if(request.getInputStream().read()!=-1) {
            audit.denied(userId,"BTC_WITHDRAWAL_CANCEL","bitcoin-withdrawal:"+quoteId,"UNEXPECTED_BODY");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"출금 취소에는 추가 본문을 보내지 마세요");
        }
        return cancellations.cancel(userId,sessionId,quoteId);
    }
}
