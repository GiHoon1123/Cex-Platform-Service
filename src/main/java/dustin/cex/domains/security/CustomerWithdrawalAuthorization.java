package dustin.cex.domains.security;

import dustin.cex.domains.bitcoin.BitcoinWithdrawalQuotes;
import dustin.cex.domains.cdd.CddCrypto;
import java.util.UUID;
import java.util.function.Supplier;
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

/** 로그인과 구분되는 출금 한 건의 서명을 검증한다. 원본·예약·출력 의도와 같은 상위 트랜잭션에서만 사용한다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CustomerWithdrawalAuthorization {
    public record Options(UUID challengeId, String purpose, BitcoinWithdrawalQuotes.Quote quote, JsonNode publicKey) {}
    public record Proof(UUID challengeId, JsonNode publicKey) {
        @Override public String toString() { return "Proof[출금 서명 자료 생략]"; }
    }

    private final JdbcTemplate jdbc;
    private final BitcoinWithdrawalQuotes quotes;
    private final CustomerMfaPolicy mfa;
    private final CddCrypto crypto;
    private final AuditService audit;
    private final WebAuthnVerifier verifier;
    private final JsonMapper json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public CustomerWithdrawalAuthorization(JdbcTemplate jdbc, BitcoinWithdrawalQuotes quotes,
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

    public Options prepare(long actor, UUID sid, UUID quoteId) {
        return operation(actor, () -> {
            var quote = require(actor, sid, quoteId);
            UUID id = UUID.randomUUID();
            String options = json.writeValueAsString(verifier.request(actor));
            // 같은 세션의 이전 미사용 챌린지는 새 확인으로 교체한다. 확정된 서명 원본은 삭제하지 않는다.
            jdbc.update("DELETE FROM customer_withdrawal_challenges WHERE session_id=? AND NOT consumed OR expires_at<clock_timestamp() AND NOT consumed", sid);
            jdbc.update("""
                INSERT INTO customer_withdrawal_challenges(id,user_id,session_id,quote_id,quote_hash,scope_mac,key_id,options,expires_at)
                VALUES(?,?,?,?,?,?,?,?,? )
                """, id, actor, sid, quoteId, quote.payloadHash(), mac(actor, sid, quote), crypto.keyId(),
                options, java.sql.Timestamp.from(quote.payload().expiresAt()));
            audit.record(actor, "CUSTOMER_WITHDRAWAL_OPTIONS", "bitcoin-withdrawal:" + quoteId,
                "SUCCESS", "QUOTE:" + quote.payloadHash());
            return new Options(id, "BTC_WITHDRAWAL_RESERVATION", quote, json.readTree(options));
        });
    }

    public void authorize(long actor, UUID sid, UUID quoteId, Proof proof) {
        operation(actor, () -> {
            var quote = require(actor, sid, quoteId);
            if (proof == null || proof.challengeId() == null || proof.publicKey() == null) throw denied();
            var rows = jdbc.queryForList("""
                SELECT options FROM customer_withdrawal_challenges
                 WHERE id=? AND user_id=? AND session_id=? AND quote_id=? AND quote_hash=? AND scope_mac=?
                  AND key_id=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE
                """, proof.challengeId(), actor, sid, quoteId, quote.payloadHash(), mac(actor, sid, quote), crypto.keyId());
            if (rows.size() != 1) throw denied();
            // 설치된 검증기가 출처·RP·서명·사용자 확인·공개키 소유·카운터를 검사한다. 단순 로그인 시각으로 대신하지 않는다.
            var credential = verifier.verifyAssertion(actor, (String) rows.getFirst().get("options"), proof.publicKey());
            jdbc.update("UPDATE customer_withdrawal_challenges SET consumed=true WHERE id=?", proof.challengeId());
            String fingerprint = OperatorMfaService.sha256Hex(credential.getRawId().toBase64UrlString());
            // 지연 외래키/커밋 제약은 같은 트랜잭션에 실제 출금 요청·예약이 있어야 서명을 확정하게 한다.
            jdbc.update("""
                INSERT INTO customer_withdrawal_authorizations(withdrawal_id,user_id,session_id,quote_hash,challenge_id,credential_fingerprint)
                VALUES(?,?,?,?,?,?)
                """, quoteId, actor, sid, quote.payloadHash(), proof.challengeId(), fingerprint);
            audit.record(actor, "CUSTOMER_WITHDRAWAL_AUTHORIZED", "bitcoin-withdrawal:" + quoteId,
                "SUCCESS", "KEY:" + fingerprint);
            return null;
        });
    }

    private BitcoinWithdrawalQuotes.Quote require(long actor, UUID sid, UUID quoteId) {
        var quote = quotes.requireForWithdrawal(actor, sid, quoteId);
        // 출금은 미등록 고객에게 비밀번호만으로 열어주지 않는다. 등록·분실 복구는 기존 고객 인증 업무를 따른다.
        if (!mfa.required(actor)) throw denied();
        long failures = jdbc.queryForObject("""
            SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_WITHDRAWAL_AUTHORIZATION'
             AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'
            """, Long.class, actor);
        if (failures >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "출금 서명 시도 제한");
        return quote;
    }

    private String mac(long actor, UUID sid, BitcoinWithdrawalQuotes.Quote quote) {
        // 무작위 표준 챌린지의 저장 사실에 고객/세션/업무 목적/경제적 원본을 결합한다. 공개 원본 해시만으로 권한을 만들지 않는다.
        return crypto.fingerprint("customer-btc-withdrawal-reservation-v1",
            actor + ":" + sid + ":" + quote.payload().id() + ":" + quote.payloadHash());
    }

    private <T> T operation(long actor, Supplier<T> work) {
        try { return work.get(); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().is4xxClientError()) audit.denied(actor, "CUSTOMER_WITHDRAWAL_AUTHORIZATION",
                "customer:" + actor, error.getStatusCode().value() == 429 ? "RATE_LIMITED" : "VERIFICATION_DENIED");
            throw error;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException
                | com.webauthn4j.verifier.exception.VerificationException
                | com.webauthn4j.converter.exception.DataConversionException error) {
            audit.denied(actor, "CUSTOMER_WITHDRAWAL_AUTHORIZATION", "customer:" + actor, "VERIFICATION_DENIED");
            throw denied();
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "이 출금 원본의 현재 본인 패스키 서명을 확인하세요");
    }
}
