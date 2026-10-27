package dustin.cex.domains.ethereum;

import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.CustomerFinancialEligibility;
import dustin.cex.domains.security.CustomerSessionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 현재 고객·세션·정규 주소·정확한 금액을 짧은 수명의 ETH 출금 견적으로 고정한다. */
@Service
public class EthereumWithdrawalQuotes {
    public static final String ASSURANCE = "QUOTE_ONLY_FUNDS_NOT_RESERVED";

    public record Payload(UUID id, String customerId, long customerVersion, String network, String asset,
                          String address, String amount, String feeRate, String fee, String total,
                          String policy, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "EthereumWithdrawalQuotePayload[원본 생략]"; }
    }
    public record Quote(Payload payload, String payloadHash, String state, String assurance) {}

    private final JdbcTemplate jdbc;
    private final CustomerSessionPolicy sessions;
    private final CustomerFinancialEligibility eligibility;
    private final EthereumSettings settings;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public EthereumWithdrawalQuotes(JdbcTemplate jdbc, CustomerSessionPolicy sessions,
            CustomerFinancialEligibility eligibility, EthereumSettings settings, AuditService audit,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.eligibility = eligibility;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public Quote prepare(long actor, UUID session, UUID key, String destination, String text) {
        independent();
        if (!settings.configured()) throw unavailable();
        final String address;
        final EthereumWithdrawalAmounts.Amounts money;
        try {
            address = EthereumSettings.address(destination);
            money = EthereumWithdrawalAmounts.quote(text);
        } catch (IllegalArgumentException invalid) {
            audit.denied(actor, "ETH_WITHDRAWAL_QUOTE_REQUEST", "customer:" + actor, "INVALID_INPUT");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정확한 이더리움 주소와 금액을 확인하세요");
        }
        if (key == null || key.equals(new UUID(0, 0))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String input = digest(actor + ":" + session + ":" + address + ":" + money.amount().toPlainString());
        return tx.execute(status -> {
            var customer = sessions.require(actor, session);
            var previous = jdbc.queryForList("SELECT id,session_id,input_hash FROM ethereum_withdrawal_quotes WHERE user_id=? AND request_key=?", actor, key);
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (!session.equals(row.get("session_id")) || !input.equals(row.get("input_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
                return read(actor, (UUID) row.get("id"));
            }
            if (!eligibility.permitted(customer)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            Timestamp at = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
            UUID id = UUID.randomUUID();
            Instant expires = at.toInstant().plusSeconds(120);
            String network = "ethereum-" + settings.chainId;
            var payload = new Payload(id, Long.toString(actor), customer.getVersion(), network, "ETH", address,
                    money.amount().toPlainString(), EthereumWithdrawalAmounts.RATE.toPlainString(),
                    money.fee().toPlainString(), money.total().toPlainString(), EthereumWithdrawalAmounts.POLICY,
                    at.toInstant(), expires);
            String raw = json.writeValueAsString(payload);
            String hash = digest(raw);
            jdbc.update("INSERT INTO ethereum_withdrawal_quotes(id,user_id,user_version,session_id,request_key,input_hash,address,network,amount,fee,total,policy,payload,payload_hash,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, actor, customer.getVersion(), session, key, input, address, network, money.amount(), money.fee(),
                    money.total(), EthereumWithdrawalAmounts.POLICY, raw, hash, at, Timestamp.from(expires));
            audit.record(actor, "ETH_WITHDRAWAL_QUOTED", "ethereum-withdrawal-quote:" + id, "SUCCESS", hash);
            return read(actor, id);
        });
    }

    public Quote own(long actor, UUID session, UUID id) {
        independent();
        return tx.execute(status -> { sessions.require(actor, session); return read(actor, id); });
    }

    /** 이미 만든 출금의 상태 조회에서는 현재 본인 세션만 확인하고 과거 견적을 새 권한으로 재사용하지 않는다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Quote historical(long actor, UUID session, UUID id) {
        sessions.require(actor, session);
        return read(actor, id);
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Quote require(long actor, UUID session, UUID id) {
        var customer = sessions.require(actor, session);
        var quote = read(actor, id);
        if (!session.equals(jdbc.queryForObject("SELECT session_id FROM ethereum_withdrawal_quotes WHERE id=?", UUID.class, id))
                || !"PRICED".equals(quote.state()) || customer.getVersion() != quote.payload().customerVersion()
                || !eligibility.permitted(customer)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return quote;
    }

    private Quote read(long actor, UUID id) {
        var rows = jdbc.queryForList("SELECT *,expires_at>clock_timestamp() fresh FROM ethereum_withdrawal_quotes WHERE user_id=? AND id=?", actor, id);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row = rows.getFirst();
        String raw = (String) row.get("payload");
        String hash = (String) row.get("payload_hash");
        if (!digest(raw).equals(hash)) throw new IllegalStateException("ETH 출금 견적 지문이 다릅니다");
        Payload payload = json.readerFor(Payload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        if (!id.equals(payload.id()) || !"ETH".equals(payload.asset()) || !EthereumWithdrawalAmounts.POLICY.equals(payload.policy())
                || !EthereumSettings.address(payload.address()).equals(payload.address())) throw new IllegalStateException("ETH 출금 견적 원본이 다릅니다");
        return new Quote(payload, hash, Boolean.TRUE.equals(row.get("fresh")) ? "PRICED" : "EXPIRED", ASSURANCE);
    }

    public static String digest(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("표준 지문 계산을 확인하세요", impossible); }
    }

    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ETH 출금 견적은 독립 트랜잭션으로 실행하세요");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이더리움 출금 네트워크가 준비되지 않았습니다");
    }
}
