package dustin.cex.domains.security;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.ethereum.EthereumWithdrawalQuotes;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 로그인과 별도로 ETH 출금 한 건의 경제적 원본에 고객 패스키 서명을 결합한다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EthereumWithdrawalAuthorization {
    public record Options(UUID challengeId, String purpose, EthereumWithdrawalQuotes.Quote quote, JsonNode publicKey) {}
    public record Proof(UUID challengeId, JsonNode publicKey) {
        @Override public String toString() { return "Proof[출금 서명 자료 생략]"; }
    }

    private final JdbcTemplate jdbc;
    private final EthereumWithdrawalQuotes quotes;
    private final CustomerMfaPolicy mfa;
    private final CddCrypto crypto;
    private final AuditService audit;
    private final WebAuthnVerifier verifier;
    private final JsonMapper json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public EthereumWithdrawalAuthorization(JdbcTemplate jdbc, EthereumWithdrawalQuotes quotes,
            CustomerMfaPolicy mfa, CddCrypto crypto, AuditService audit,
            @Value("${customer.webauthn.rp-id:localhost}") String rp,
            @Value("${customer.webauthn.origin:http://localhost:3003}") String origin) {
        this.jdbc = jdbc;
        this.quotes = quotes;
        this.mfa = mfa;
        this.crypto = crypto;
        this.audit = audit;
        verifier = new WebAuthnVerifier(jdbc, rp, origin, "CEX 고객 인증");
    }

    public Options prepare(long actor, UUID session, UUID quoteId) {
        var quote = require(actor, session, quoteId);
        UUID challenge = UUID.randomUUID();
        String options = json.writeValueAsString(verifier.request(actor));
        // 사용하지 않은 이전 ETH 출금 챌린지만 교체하며 확정된 서명 이력은 삭제하지 않는다.
        jdbc.update("DELETE FROM ethereum_withdrawal_challenges WHERE session_id=? AND NOT consumed OR expires_at<clock_timestamp() AND NOT consumed", session);
        jdbc.update("INSERT INTO ethereum_withdrawal_challenges(id,user_id,session_id,quote_id,quote_hash,scope_mac,key_id,options,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                challenge, actor, session, quoteId, quote.payloadHash(), mac(actor, session, quote), crypto.keyId(), options,
                Timestamp.from(quote.payload().expiresAt()));
        audit.record(actor, "ETH_WITHDRAWAL_OPTIONS", "ethereum-withdrawal:" + quoteId, "SUCCESS", "QUOTE:" + quote.payloadHash());
        return new Options(challenge, "ETH_WITHDRAWAL_RESERVATION", quote, json.readTree(options));
    }

    public void authorize(long actor, UUID session, UUID quoteId, Proof proof) {
        var quote = require(actor, session, quoteId);
        if (proof == null || proof.challengeId() == null || proof.publicKey() == null) throw denied();
        var rows = jdbc.queryForList("SELECT options FROM ethereum_withdrawal_challenges WHERE id=? AND user_id=? AND session_id=? AND quote_id=? AND quote_hash=? AND scope_mac=? AND key_id=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE",
                proof.challengeId(), actor, session, quoteId, quote.payloadHash(), mac(actor, session, quote), crypto.keyId());
        if (rows.size() != 1) throw denied();
        var credential = verifier.verifyAssertion(actor, (String) rows.getFirst().get("options"), proof.publicKey());
        jdbc.update("UPDATE ethereum_withdrawal_challenges SET consumed=true WHERE id=?", proof.challengeId());
        String fingerprint = OperatorMfaService.sha256Hex(credential.getRawId().toBase64UrlString());
        jdbc.update("INSERT INTO ethereum_withdrawal_authorizations(withdrawal_id,user_id,session_id,quote_hash,challenge_id,credential_fingerprint) VALUES(?,?,?,?,?,?)",
                quoteId, actor, session, quote.payloadHash(), proof.challengeId(), fingerprint);
        audit.record(actor, "ETH_WITHDRAWAL_AUTHORIZED", "ethereum-withdrawal:" + quoteId, "SUCCESS", "KEY:" + fingerprint);
    }

    private EthereumWithdrawalQuotes.Quote require(long actor, UUID session, UUID quoteId) {
        var quote = quotes.require(actor, session, quoteId);
        if (!mfa.required(actor)) throw denied();
        return quote;
    }

    private String mac(long actor, UUID session, EthereumWithdrawalQuotes.Quote quote) {
        return crypto.fingerprint("customer-eth-withdrawal-reservation-v1", actor + ":" + session + ":" + quote.payload().id() + ":" + quote.payloadHash());
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "이 ETH 출금 원본의 현재 본인 패스키 서명을 확인하세요");
    }
}
