# CEX Platform Service

Rust 체결 엔진 앞에서 인증·주문 접수·자산 예약을 처리하고, Kafka 결과를 받아 금융 상태와 공개 시장 데이터를 반영하는 Java 플랫폼이다.

- 고객 API: `http://localhost:8080`
- 내부 엔진: `http://localhost:3002`
- 공개 시세: `/api/market/BTC/USDT`, `/ws/market/BTC/USDT`
- 봇 기본값: 0명. 실행 소유 테스트 외에는 활성화하지 않는다.

## 실행과 검증

Java 21을 사용한다. 공통 로컬 DB는 5433이므로 시작 시 `spring.datasource.url`을 해당 CEX DB로 명시한다. 기존 다른 프로젝트의 5432 DB를 사용하지 않는다. 실행 조건과 운영 한계는 공통 작업 규칙·문서 목차를 따른다.

```sh
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_market_path.py
```

검증기는 별도의 PostgreSQL·Kafka를 생성하고 회수한다. 전체 경로 검증에는 형제 디렉터리 `Cex-Engine`의 빌드 결과가 필요하다.

## 문서

- [문서 목차](docs/README.md)
- [BTC 주문·자체 시세·봇 경계와 검증](docs/decisions/007-platform-market-gateway.md)
- [엔진 이벤트의 영속 수신](docs/decisions/006-engine-event-inbox.md)

현재 구현·격리 검증을 운영 보안·금융 정합성·규제 심사 완료로 해석하지 않는다. 수수료·시장가·주문 실패 복구의 현재 범위와 남은 원장·대사 과제는 구현 기록에서 확인한다.

## 주문 실패·복구의 현재 계약

[응답 불명 자동 재전송과 확정 거절 예약 반환](docs/decisions/010-durable-order-resolution.md)에서 현재 구현·테스트·저장 계약과 운영 한계를 확인한다. 엔진 저널 버전은 4이며 과거 저널을 자동 변환하지 않는다.

## 원장·독립 대사

[주문 자산 원장과 독립 대사](docs/decisions/011-asset-ledger-and-reconciliation.md)에서 현재 범위와 실행 조건을 확인한다. 최초 반입·운영 접근·저장 계약은 문서의 조건을 충족해야 한다.

## 실제 구현과 함께 읽기 · 2026-10-23

이 문서의 관련 동작을 확인할 현재 코드다. 메서드·설정의 일부이며 독립 실행 예제가 아니다. 계획이나 과거 검토 전체가 구현·검증됐다는 뜻은 아니다.

출처: [EngineEventInbox.java](src/main/java/dustin/cex/shared/kafka/EngineEventInbox.java#L34) · [전체 구현 설명·트레이드오프·작성한 테스트](docs/decisions/006-engine-event-inbox.md)

```java
String hash = hash(payload);
int inserted = jdbc.update("""
        INSERT INTO engine_event_receipts(event_id,engine_id,payload_hash) VALUES (?,?,?)
        ON CONFLICT (event_id) DO NOTHING
        """, eventId, engineId, hash);
if (inserted == 1) {
    return true;
}
```

`jdbc.update`는 영향받은 행 수를 반환한다. 최초 삽입만 1이며, 중복이면 뒤의 기존 본문 해시 확인으로 진행한다. 수신 키와 금융 반영은 같은 트랜잭션에서 확정해야 한다.

이번 보강은 문서 변경이다. 신규 제품 테스트는 0개이며 제품 테스트는 재실행하지 않았다. 소스와 발췌의 일치를 별도로 검사했다. 과거 실행 결과와 현재 소스의 동작 범위는 연결한 구현 기록에서 구분한다.
