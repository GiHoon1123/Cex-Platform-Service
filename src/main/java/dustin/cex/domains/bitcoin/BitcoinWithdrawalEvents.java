package dustin.cex.domains.bitcoin;

import static dustin.cex.domains.ledger.LedgerService.p;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 엔진 저널에서 확정된 최초 예약·금융 거절만 동일한 수신 키 TX로 반영한다. HTTP409는 이 반환 경로가 아니다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalEvents {
    private static final Set<String> RESERVED = Set.of("event_id", "engine_id", "event_type",
        "withdrawal_id", "user_id", "asset", "amount", "timestamp");
    private static final Set<String> REJECTED = Set.of("event_id", "engine_id", "event_type",
        "withdrawal_id", "user_id", "asset", "amount", "timestamp", "reason_code");
    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256)
            .maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserBalanceRepository balances;
    private final LedgerService ledger;
    private final AuditService audit;

    public BitcoinWithdrawalEvents(JdbcTemplate jdbc, UserRepository users,
            UserBalanceRepository balances, LedgerService ledger, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(String raw) {
        JsonNode event = json.readTree(raw);
        if (event == null || !event.isObject()) throw new IllegalArgumentException("출금 금융 이벤트 형식이 다릅니다");
        String type = text(event, "event_type");
        if (!("withdrawal_reserved".equals(type) && event.propertyNames().equals(RESERVED)
                || "withdrawal_rejected".equals(type) && event.propertyNames().equals(REJECTED)))
            throw new IllegalArgumentException("출금 금융 결과의 필드가 다릅니다");
        if (type.equals("withdrawal_rejected") && !"RESERVATION_REJECTED".equals(text(event, "reason_code")))
            throw new IllegalArgumentException("엔진의 금융 거절 근거가 다릅니다");
        String eventId = text(event, "event_id");
        UUID engine = canonical(text(event, "engine_id"));
        UUID id = canonical(text(event, "withdrawal_id"));
        if (!eventId.matches(java.util.regex.Pattern.quote(engine.toString()) + ":[1-9][0-9]*:0"))
            throw new IllegalArgumentException("확정 이벤트 위치가 다릅니다");
        var user = event.path("user_id");
        if (!user.isIntegralNumber() || !user.canConvertToLong() || user.longValue() <= 0)
            throw new IllegalArgumentException("출금 고객 식별자가 다릅니다");
        long owner = user.longValue();
        String asset = text(event, "asset");
        String money = text(event, "amount");
        if (!"BTC".equals(asset) || money.length() > 40 || !money.matches("[0-9]+(?:\\.[0-9]{1,8})?"))
            throw new IllegalArgumentException("출금 결과의 자산·금액 형식이 다릅니다");
        BigDecimal gross = ExactAmount.requirePositive(new BigDecimal(money), "엔진 출금 총액");
        String timestamp = text(event, "timestamp");
        Instant.parse(timestamp);
        users.lockById(owner).orElseThrow(() -> new IllegalStateException("확정 출금 고객이 없습니다"));
        var originals = jdbc.queryForList("SELECT user_id,asset,total FROM bitcoin_withdrawals WHERE id=?", id);
        if (originals.size() != 1) throw new IllegalStateException("확정 출금의 서명/예약 원본이 없습니다");
        var original = originals.getFirst();
        if (((Number) original.get("user_id")).longValue() != owner
                || !asset.equals(original.get("asset")) || gross.compareTo((BigDecimal) original.get("total")) != 0)
            throw new IllegalStateException("엔진 결과와 서명한 금융 원본이 다릅니다");
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events WHERE withdrawal_id=?)", Boolean.class, id)))
            throw new IllegalStateException("같은 출금에 서로 다른 최초 금융 결과가 도착했습니다");
        String operation = "engine:" + eventId;
        if (type.equals("withdrawal_rejected")) {
            var balance = balances.findByUserIdAndMintAddressForUpdate(owner, "BTC")
                .orElseThrow(() -> new IllegalStateException("반환할 BTC 잔액이 없습니다"));
            BigDecimal locked = ExactAmount.requireNonNegative(balance.getLocked(), "잠긴 BTC 잔액");
            if (locked.compareTo(gross) < 0) throw new IllegalStateException("출금 거절의 반환할 예약금이 부족합니다");
            balance.setLocked(ExactAmount.requireNonNegative(locked.subtract(gross), "반환 후 잠긴 BTC"));
            balance.setAvailable(ExactAmount.requireNonNegative(balance.getAvailable().add(gross), "반환 후 가용 BTC"));
            balances.saveAndFlush(balance);
            ledger.record(operation, "BTC_WITHDRAWAL_REJECTED", List.of(
                p(owner, "LOCKED", "BTC", gross.negate()), p(owner, "AVAILABLE", "BTC", gross)));
        } else {
            // 이미 플랫폼에서 총액을 잠갔으므로 영속 예약 확인은 잔액을 또 움직이지 않는다.
            ledger.record(operation, "BTC_WITHDRAWAL_RESERVED", List.of());
        }
        jdbc.update("""
            INSERT INTO bitcoin_withdrawal_reservation_events
              (withdrawal_id,event_id,engine_id,event_type,event_time,payload_hash,operation_key)
            VALUES(?,?,?,?,?,?,?)
            """, id, eventId, engine, type, timestamp, BitcoinWithdrawalQuotes.digest(raw), operation);
        audit.record(null, "BTC_WITHDRAWAL_ENGINE_RESULT", "bitcoin-withdrawal:" + id,
            "SUCCESS", "EVENT:" + eventId);
    }

    private static String text(JsonNode json, String field) {
        var value = json.path(field);
        if (!value.isString()) throw new IllegalArgumentException("출금 금융 결과의 문자열 필드가 다릅니다");
        return value.stringValue();
    }

    private static UUID canonical(String value) {
        UUID id = UUID.fromString(value);
        if (id.equals(new UUID(0, 0)) || !id.toString().equals(value))
            throw new IllegalArgumentException("출금 금융 결과의 안정 식별자가 다릅니다");
        return id;
    }
}
