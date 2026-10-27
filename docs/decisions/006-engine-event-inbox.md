# 엔진 WAL 재발행을 한 번만 금융 상태에 반영하기

엔진이 Kafka 전달 확인 뒤 커서 저장 전에 종료되면 같은 이벤트를 다시 보낼 수 있다. 플랫폼은 엔진의 고유 이벤트 ID를 금융 변경과 같은 PostgreSQL 트랜잭션에 기록해 중복 반영을 막는다. 다른 본문이 같은 키를 사용하거나 금융 반영이 실패하면 성공으로 건너뛰지 않는다.

## 문제와 기존 동작

기존 소비자는 주문이 filled인지 보는 방식으로 중복을 추측했다. 부분 체결의 동일 이벤트가 다시 오면 주문이 아직 partial일 수 있으므로 중복을 판별하지 못한다. 주문이 없거나 수량이 초과해도 일부 분기는 예외 없이 반환했다. 영속 수신 키만 추가하면 금융 반영을 건너뛴 이벤트의 키만 커밋할 위험이 있다.

엔진이 자체 저널로 복원하고 미전달 결과를 재발행하므로 수신 멱등성은 해당 복구 계약의 일부다. [엔진의 WAL·복구 설계](../../../Cex-Engine/docs/decisions/009-durable-journal-recovery.md)와 함께 읽는다.

### 현재 코드로 연결해 읽기

사전 조회 대신 고유 키 삽입 결과로 같은 이벤트의 처리자를 정한다. 아래는 2026-10-23 현재 [실제 소스](../../src/main/java/dustin/cex/shared/kafka/EngineEventInbox.java#L34)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

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

## 검증된 접근과 선택

[Kafka 공식 설계](https://kafka.apache.org/34/design/design/)는 외부 저장소까지 정확히 한 번 반영하려면 대상 시스템의 협력이 필요하다고 설명한다. 생산자 멱등성이나 Kafka 오프셋만으로 PostgreSQL 반영의 중복을 해결하지 않는다.

PostgreSQL 기본 키와 [ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)를 사용해 수신 기록의 삽입 승자를 정한다. 이미 설치된 Spring JDBC·트랜잭션 지원을 사용하며 새 메시징 패키지나 별도 저장 서버를 추가하지 않았다.

[Spring Kafka 오류 처리](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)의 중단 처리기를 사용한다. 기본 재시도 후 기록을 버리는 정책은 금융 이벤트에 적용하지 않는다. 설치된 Spring Kafka 3.1 계열의 실제 API와 컴파일·테스트로 확인했다.

| 선택 | 이점 | 비용·제약 |
|---|---|---|
| 수신 키와 금융 변경을 같은 트랜잭션에 저장 | 중복 경쟁의 승자 결정, 업무 실패 시 키도 롤백 | 이벤트마다 DB 쓰기·유일 인덱스 검사 필요 |
| ID와 본문 SHA-256 함께 저장 | 같은 ID의 다른 내용을 차단 | 본문 직렬화가 달라져도 충돌로 차단됨 |
| 오류 시 소비 컨테이너 중단 | 금융 반영을 버리고 다음으로 진행하지 않음 | 원인 해결·재기동 전 소비가 중단됨 |
| 엔진별 Kafka 키 | 체결과 취소의 상대 순서 유지 | 엔진 하나의 출력 소비는 한 파티션 처리량에 제한 |

## 구현과 코드

- [EngineEventReceipt](../../src/main/java/dustin/cex/shared/kafka/EngineEventReceipt.java): `engine_event_receipts`의 event_id PK·engine_id·payload_hash를 선언한다. 현재 프로젝트의 Hibernate 스키마 구성으로 테이블을 준비한다. 배포용 스키마 관리 체계 전체를 이번에 개편하지 않았다.
- [EngineEventInbox](../../src/main/java/dustin/cex/shared/kafka/EngineEventInbox.java): 활성 트랜잭션을 요구한다. UUID 기반 ID를 검증하고 `INSERT ... ON CONFLICT DO NOTHING`으로 승자를 정한다. 중복이면 원본 본문 해시를 비교한다.
- [KafkaOrderEventConsumer](../../src/main/java/dustin/cex/shared/kafka/KafkaOrderEventConsumer.java): 기존 `@Transactional` 진입점에서 수신 키를 확보한 뒤 금융 분기를 실행한다. 같은 이벤트면 저장소를 변경하지 않는다. 알 수 없는 타입·없는 주문·새 이벤트의 종료 주문·수량 초과는 예외로 차단한다. 취소도 원본 소유자·거래쌍을 확인한다.
- 현재 엔진이 발행하는 스냅샷 계약만 유지하고 구형 Delta 폴백과 그 전용 미사용 메서드를 삭제했다. 구형 이벤트에 ID를 추측해서 붙이지 않는다.
- [KafkaConfig](../../src/main/java/dustin/cex/shared/kafka/KafkaConfig.java): `CommonContainerStoppingErrorHandler`를 지정한다. 실패한 DB 트랜잭션은 롤백되고, 재기동에서 미확정 오프셋부터 재수신한다. 직접 생성한 팩터리에도 `spring.kafka.listener.auto-startup`을 적용한다.

금융 변경은 JPA, 수신 키 삽입은 JdbcTemplate을 사용한다. 같은 Spring 트랜잭션과 DataSource에 참여해야 한다. 별도 DB·별도 트랜잭션 매니저로 수신 키를 분리하면 이 보장은 깨진다.

### 이해에 필요한 문법과 원리

- `@Transactional`은 프록시로 진입한 소비자 메서드의 DB 작업을 함께 커밋·롤백한다. 내부 private 메서드에 별도 트랜잭션이 생기는 것은 아니다.
- JdbcTemplate은 바인딩 매개변수로 값을 전달하고 같은 트랜잭션에 묶인 연결을 사용한다.
- PK 충돌은 단순 사전 SELECT와 다르다. 두 요청이 동시에 미존재를 읽어도 삽입은 한 요청만 성공한다.
- 트랜잭션이 끝난 뒤 Kafka 오프셋 확인 전에 프로세스가 종료되면 재수신은 가능하다. 이때 수신 키가 같은 금융 반영의 반복을 막는다.
- 본문 해시는 동일 이벤트의 내용 비교용이다. 발신자 인증·암호화·감사 서명을 대체하지 않는다.

## 작성한 테스트

[EngineEventInboxTest](../../src/test/java/dustin/cex/shared/kafka/EngineEventInboxTest.java)는 격리 PostgreSQL에서 6개를 실행한다.

| 함수 | 검증 |
|---|---|
| `duplicateDeliveryChangesFinancialStateOnce` | 같은 이벤트 2회 전달 시 시험 금융 행 1개 |
| `financialFailureRollsBackReceiptAndAllowsRetry` | 금융 반영 실패 시 수신 키·시험 금융 행 모두 롤백 |
| `sameIdentityDifferentPayloadIsRejected` | 동일 키·다른 본문 차단 |
| `concurrentDeliveriesHaveOneWinner` | 실제 두 연결·트랜잭션 경쟁에서 승자 1개 |
| `actualConsumerFailureRollsBackTheRealInboxClaim` | 실제 소비자 분기의 실패가 실제 PostgreSQL 수신 키를 롤백 |
| `transactionAndDurableIdentityAreRequired` | 트랜잭션 밖 호출과 잘못된 ID 차단 |

[KafkaOrderEventConsumerTest](../../src/test/java/dustin/cex/shared/kafka/KafkaOrderEventConsumerTest.java)의 8개는 업무 저장소 대역을 사용한다.

- `knownDuplicateDoesNotTouchFinancialRepositories`: 중복에서 금융 저장소 호출 없음.
- `inboxFailurePropagatesBeforeFinancialUpdates`: 수신 키 충돌 시 금융 처리 전 실패.
- `missingFinancialOrderFailsInsteadOfCommittingOnlyReceipt`: 없는 주문을 조용히 건너뛰지 않음.
- `consumerConfigurationStopsInsteadOfDiscardingFailedFinancialEvent`: 소비 실패 중단 처리기 설정.
- `newEventCannotSilentlySkipAnAlreadyClosedOrder`: 새 취소 이벤트의 종료 주문 차단.
- `cancellationMustMatchOriginalOwnerAndMarket`: 원본과 다른 취소 차단.
- `fullApplicationTestConfigurationNeverEnablesBots`: 전체 기동 시험용 봇 수 0명.
- `unknownEventTypeIsRejected`: 알 수 없는 이벤트 타입 차단.

## 실행과 결과

```sh
python3 scripts/verify_engine_inbox.py
```

현재 실행기는 이름과 책임에 맞게 `EngineEventInboxTest` 6개와 제품 JAR 빌드만 실행한다. 시장·주문·정산 전체 회귀를 같은 DB에 섞으면 서로 다른 스키마 초기화 방식과 불변 원장이 충돌하므로, 해당 업무는 `verify_market_path.py`, `verify_order_idempotency.py` 등 각 격리 실행기에서 검증한다. 회귀 수가 줄어든 것은 제품 보장을 삭제한 것이 아니라 실행기의 소유 경계를 분리한 결과다.

실행기는 자신이 만든 PostgreSQL 16 컨테이너만 사용한다. H2를 쓰는 기존 주문·정산 회귀도 포함한다. **113개 통과, 실패·오류·제외 0개**, Java 21 bootJar 빌드도 통과했다. 새 PostgreSQL 검증은 실행기 환경 변수가 없으면 제외되지만, 위 결과에서는 변수를 지정해 6개 모두 실제 실행했다.

실제 Java↔Rust 응답 유실·재전송·재시작 3개와 실제 Kafka·Rust 장애·복구 6개는 엔진 문서에 기록한다. 이 결과는 실제 Kafka 소비자와 전체 정산 도메인을 한 번에 장애 주입한 종단 검증을 대신하지 않는다. 새 소비자 분기 테스트 일부는 업무 저장소 대역을 쓴다.

### 중간 실패와 격리 보완

최초 추가 테스트에서 설치된 팩터리에 없는 getter를 호출해 컴파일이 실패했다. 실제 필드 설정을 확인하는 검사로 바꾸고 재실행했다. 실패 결과는 통과 건수에 포함하지 않는다.

기존 정산의 전체 Spring 기동 회귀에는 봇·외부 서비스 차단이 빠져 있었다. 첫 실행에서 테스트 봇 주문 1건이 기존 로컬 엔진에 전달됐고 Kafka 소비자도 기동했다. 기존 엔진 로그의 매칭 0건과 로컬 DB의 주문·체결·잔액 각 0건을 확인했다. 기존 데이터가 없다는 사실을 모든 외부 영향이 없었다는 증거로 확대하지 않는다.

[TestConfig](../../src/test/java/dustin/cex/config/TestConfig.java)의 봇 수를 0명으로 지정하고, [테스트 설정](../../src/test/resources/application-test.properties)의 엔진·Kafka 주소를 사용하지 않는 루프백 포트로 바꿨다. 소비 자동 시작도 껐으며 수동 팩터리에도 적용했다. 이후 실행기로 격리해 113개를 재검증했다. Mockito의 JVM 클래스 공유 경고 등 기존 실행 경고는 남는다.

## 남은 범위

- 엔진 전달 커서는 Kafka 확인 위치이며 이 테이블은 플랫폼 반영 위치다. 서로 같은 진행 위치로 취급하지 않는다.
- 영속 수신 키 추가만으로 기존 수수료·잔액 스냅샷·시장가 주문 상태·잔액 권위 문제까지 해결한 것은 아니다. 각각의 업무 불변조건과 종단 장애 검증이 필요하다.
- 소비 중단의 경보·준비 상태 연동과 운영 재처리 도구는 이번에 구현하지 않았다. 실패 원인을 해결하지 않은 자동 재시작만 반복하지 않는다.
- 과거 ID 없는 메시지의 호환 처리·데이터 마이그레이션은 추가하지 않았다. 새로운 계약으로 준비된 토픽과 수신 스키마가 필요하다.

## 관련 커밋

- [엔진 구현·회귀 `91053fc`](https://github.com/GiHoon1123/Cex-Engine/commit/91053fc2b0b5a4f52562b6941dda84f4c63bb245)
- [플랫폼 수신 키·회귀 `8f41840`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8f4184001fd83a8c4470680e23499a91c54356fe)
- [전체 기동 테스트 격리 `f6ff235`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/f6ff235cfe23790b75923b039643da094c85202b)
