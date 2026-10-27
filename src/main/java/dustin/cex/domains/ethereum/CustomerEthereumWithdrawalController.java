package dustin.cex.domains.ethereum;

import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.EthereumWithdrawalAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** ETH 출금 입력을 작은 JSON 계약으로 제한하고 고객·세션 식별자는 인증 속성만 사용한다. */
@RestController
@RequestMapping("/api/customer/withdrawals/ETH")
public class CustomerEthereumWithdrawalController {
    private final EthereumWithdrawalQuotes quotes;
    private final EthereumWithdrawalRequests requests;
    private final AuditService audit;
    private final ObjectMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public CustomerEthereumWithdrawalController(EthereumWithdrawalQuotes quotes,
            EthereumWithdrawalRequests requests, AuditService audit) {
        this.quotes = quotes;
        this.requests = requests;
        this.audit = audit;
    }

    @PostMapping(value="/quotes", consumes="application/json")
    public EthereumWithdrawalQuotes.Quote quote(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key, HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            var node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 2 || !node.path("address").isString() || !node.path("amount").isString()) throw new IllegalArgumentException();
            return quotes.prepare(userId, sessionId, key, node.path("address").asString(), node.path("amount").asString());
        } catch (ResponseStatusException known) {
            throw known;
        } catch (RuntimeException invalid) {
            audit.denied(userId, "ETH_WITHDRAWAL_QUOTE_REQUEST", "customer:" + userId, "INVALID_BODY");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정확한 지급 주소와 금액을 확인하세요");
        }
    }

    @GetMapping("/quotes/{id}")
    public EthereumWithdrawalQuotes.Quote quote(@RequestAttribute long userId, @RequestAttribute UUID sessionId, @PathVariable UUID id) {
        return quotes.own(userId, sessionId, id);
    }

    @PostMapping("/quotes/{id}/authorization-options")
    public EthereumWithdrawalAuthorization.Options options(@RequestAttribute long userId, @RequestAttribute UUID sessionId, @PathVariable UUID id) {
        return requests.options(userId, sessionId, id);
    }

    @PostMapping(value="/quotes/{id}/reservations", consumes="application/json")
    public EthereumWithdrawalRequests.Receipt reserve(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            @PathVariable UUID id, HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            var node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 2 || !node.path("challengeId").isString() || !node.path("publicKey").isObject()) throw new IllegalArgumentException();
            var proof = new EthereumWithdrawalAuthorization.Proof(UUID.fromString(node.path("challengeId").asString()), node.path("publicKey"));
            return requests.submit(userId, sessionId, id, proof);
        } catch (ResponseStatusException known) {
            throw known;
        } catch (RuntimeException invalid) {
            audit.denied(userId, "ETH_WITHDRAWAL_REQUEST", "ethereum-withdrawal:" + id, "INVALID_BODY");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ETH 출금 일회 서명 본문을 확인하세요");
        }
    }

    @GetMapping("/quotes/{id}/reservations")
    public EthereumWithdrawalRequests.Receipt status(@RequestAttribute long userId, @RequestAttribute UUID sessionId, @PathVariable UUID id) {
        return requests.status(userId, sessionId, id);
    }
}
