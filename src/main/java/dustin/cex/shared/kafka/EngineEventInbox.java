package dustin.cex.shared.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

/**
 * 기본 키 삽입으로 중복 전달의 승자를 정한다. 단순 사전 조회는 두 소비자가
 * 동시에 미존재를 볼 수 있으므로 사용하지 않는다. PostgreSQL의 충돌 처리는
 * 경쟁 트랜잭션의 종료를 기다리며, 본문이 다른 같은 키는 오류로 차단한다.
 */
@Component
@RequiredArgsConstructor
public class EngineEventInbox {
    private final JdbcTemplate jdbc;

    public boolean claim(String eventId, String engineId, String payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Engine inbox requires the financial transaction");
        }
        UUID.fromString(engineId);
        if (eventId == null || !eventId.matches(java.util.regex.Pattern.quote(engineId) + ":[1-9][0-9]*:[0-9]+")
                || eventId.length() > 120) {
            throw new IllegalArgumentException("Invalid engine event identity");
        }
        String hash = hash(payload);
        int inserted = jdbc.update("""
                INSERT INTO engine_event_receipts(event_id,engine_id,payload_hash) VALUES (?,?,?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, engineId, hash);
        if (inserted == 1) {
            return true;
        }
        String original = jdbc.queryForObject(
                "SELECT payload_hash FROM engine_event_receipts WHERE event_id=?", String.class, eventId);
        if (!hash.equals(original)) {
            throw new IllegalStateException("Engine event id is bound to a different payload");
        }
        return false;
    }

    private String hash(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
