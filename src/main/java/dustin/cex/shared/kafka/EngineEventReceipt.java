package dustin.cex.shared.kafka;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * 엔진의 동일 저널 이벤트를 여러 번 받아도 금융 반영은 한 번만 수행하기 위한
 * 영속 수신 기록이다. 기록과 주문·체결·잔액 변경은 같은 트랜잭션에 참여한다.
 * 별도 저장 API나 삭제 API는 제공하지 않는다.
 */
@Entity
@Table(name = "engine_event_receipts")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class EngineEventReceipt {
    @Id
    @Column(name = "event_id", length = 120, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "engine_id", length = 36, nullable = false, updatable = false)
    private String engineId;

    @Column(name = "payload_hash", length = 64, nullable = false, updatable = false)
    private String payloadHash;
}
