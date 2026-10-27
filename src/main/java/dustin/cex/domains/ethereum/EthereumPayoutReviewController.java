package dustin.cex.domains.ethereum;

import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.OperatorMfaService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 지정된 ETH 지급 검토자에게만 내용 열람과 건별 승인 서명 API를 제공한다. */
@RestController
@RequestMapping("/api/admin/withdrawals/ETH/payout-reviews")
public class EthereumPayoutReviewController {
    private final EthereumPayoutReviews reviews;
    private final AuditService audit;
    private final ObjectMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public EthereumPayoutReviewController(EthereumPayoutReviews reviews, AuditService audit) {
        this.reviews = reviews;
        this.audit = audit;
    }

    @PostMapping(consumes="application/json")
    public EthereumPayoutReviews.Review propose(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            HttpServletRequest request) throws IOException {
        JsonNode body = body(userId, request, Set.of("withdrawalId", "checkerId", "requestKey", "reason"));
        try {
            return reviews.propose(userId, sessionId, uuid(body, "withdrawalId"), body.path("checkerId").longValue(),
                    uuid(body, "requestKey"), body.path("reason").asString());
        } catch (ResponseStatusException known) { throw known; }
        catch (RuntimeException invalid) { throw invalid(userId); }
    }

    @PostMapping("/{id}/inspect")
    public EthereumPayoutReviews.Review inspect(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            @PathVariable UUID id, HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) throw invalid(userId);
        return reviews.inspect(userId, sessionId, id);
    }

    @PostMapping("/{id}/{decision:approve|reject}/options")
    public EthereumPayoutReviews.Confirmation options(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            @PathVariable UUID id, @PathVariable String decision, HttpServletRequest request) throws IOException {
        JsonNode body = body(userId, request, Set.of("payloadHash"));
        return reviews.options(userId, sessionId, id, hash(body), decision.equals("approve"));
    }

    @PostMapping("/{id}/{decision:approve|reject}")
    public EthereumPayoutReviews.Review decide(@RequestAttribute long userId, @RequestAttribute UUID sessionId,
            @PathVariable UUID id, @PathVariable String decision, HttpServletRequest request) throws IOException {
        JsonNode body = body(userId, request, Set.of("payloadHash", "proof"));
        var proof = body.path("proof");
        if (!proof.isObject() || !proof.path("credential").isObject()) throw invalid(userId);
        return reviews.decide(userId, sessionId, id, hash(body), decision.equals("approve"),
                new OperatorMfaService.AuthorizationProof(uuid(proof, "challengeId"), proof.path("credential")));
    }

    private JsonNode body(long actor, HttpServletRequest request, Set<String> fields) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(16385);
        try {
            var node = json.readTree(bytes);
            if (bytes.length > 16384 || node == null || !node.isObject() || !node.propertyNames().equals(fields)) throw new IllegalArgumentException();
            return node;
        } catch (RuntimeException invalid) { throw invalid(actor); }
    }
    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(node.path(field).asString()); }
    private static String hash(JsonNode node) { String value = node.path("payloadHash").asString(); if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(); return value; }
    private ResponseStatusException invalid(long actor) { audit.denied(actor, "ETH_PAYOUT_REVIEW_INPUT", "ethereum-payout-review-restricted", "INVALID_BODY"); return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
