# 실제 Core부터 원장까지 하나로 검증한 BTC 출금 완료

단계별로 검증했던 BTC 출금 경로를 소유 Bitcoin Core 31.1·Rust 엔진·WAL·Kafka·PostgreSQL 한 시험으로 연결했다. 같은 출금 UUID와 정확한 총액이 예약부터 여섯 확인, 완료 이벤트, 원장 대사까지 유지되는지를 검증한다.

## 문제와 기존 동작

159까지 제품 코드 경로는 완성됐지만 실제 시험은 체인 경계와 엔진·원장 경계가 나뉘어 있었다. 각 시험이 통과해도 설정, 식별자, 실제 PSBT, Kafka 소비가 결합되는 지점에서 실패할 가능성이 남았다. 특히 합성 엔진 ID를 쓴 플랫폼 승인 자료는 실제 Rust 엔진의 완료 이벤트를 받을 수 없으므로 전체 검증이라고 부를 수 없었다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core의 `walletprocesspsbt`는 지갑이 서명 가능한 PSBT 입력을 서명·완성하고, `sendrawtransaction`은 고정 원시 거래를 노드에 제출한다. `getrawtransaction`은 `txindex`가 켜진 노드에서 블록에 포함된 거래와 확인 수를 조회할 수 있다. 시험은 이 세 공식 경계를 그대로 사용하고 별도 가짜 체인 상태를 만들지 않는다.

- [Bitcoin Core 31 RPC 목록](https://bitcoincore.org/en/doc/31.0.0/rpc/)
- [Bitcoin Core sendrawtransaction](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/sendrawtransaction/)
- [Bitcoin Core getrawtransaction](https://bitcoincore.org/en/doc/30.0.0/rpc/rawtransactions/getrawtransaction/)
- [Spring Kafka의 정확히 한 번 처리 범위](https://docs.spring.io/spring-kafka/reference/kafka/exactly-once.html)

Kafka 문서도 읽기 자체는 재전달될 수 있음을 명시한다. 따라서 이번 검증은 메시지가 한 번만 도착한다고 가정하지 않고, 플랫폼의 영속 수신 키와 원장 작업 키가 중복 금융 반영을 막는 현재 설계를 유지한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 한 시험이 Core·Rust·Kafka·DB를 모두 소유 | 설정과 실제 직렬화까지 연결 검증 | 실행 시간이 길고 Docker·Core 바이너리가 필요 |
| 실제 Rust 예약 이벤트를 승인 원본으로 사용 | 완료 이벤트의 엔진 ID·순번 권위가 일치 | 시험 기반에 금융 이력 전 엔진 시작 확장점 필요 |
| 실제 체인만 사용하고 외부 개인정보 통신은 합성 | 자금 이동 핵심을 실제로 검증하면서 개인정보 전송 방지 | 실제 CODEVASP 공급사 연동 증명은 아님 |
| 한 노드 regtest에서 여섯 블록 생성 | 결정적인 반복 실행과 정확한 확인 수 | 독립 운영 노드·메인넷 재구성 시험은 아님 |

## 구현과 관련 코드

출처: [BitcoinPayoutFullWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutFullWireIntegrationTest.java)의 부분 발췌.

```java
assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,
    actualEngine.execute(new EngineWithdrawalClient.Command(
        withdrawal, customer.getId(), "BTC", GROSS,
        EngineWithdrawalClient.Action.RESERVE)));
var page = actualAudit.page(0L, null, null);
reserveEngine = UUID.fromString(page.path("engine_id").asString());
events.consumeOrderEvent(page.path("events").get(0).asString());
```

플랫폼 DB에 임의 엔진 ID를 쓰지 않는다. 실제 Rust 저널의 첫 예약 이벤트에서 엔진 ID와 순번을 읽어 플랫폼의 영속 수신 경로에 넣는다. 이후 완료는 같은 엔진의 다음 저널 위치에서만 성립한다.

```java
mineSixBlocks();
assertEquals("CONFIRMED",
    new BitcoinConfirmationService(jdbc, audit, confirmer, manager)
        .processNext().orElseThrow().status());
listeners.start();
assertEquals(EngineWithdrawalClient.Outcome.DURABLY_COMPLETED,
    completion.processNext().orElseThrow().transport());
await(() -> count("bitcoin_withdrawal_completion_events") == 1, 20);
```

`try` 자원 구문은 RPC 클라이언트를 범위가 끝나면 닫는다. `orElseThrow()`는 처리할 확정 대상이 없으면 성공처럼 넘어가지 않는다. `await`는 정해진 기한 안에서 Kafka 금융 완료를 관찰하며 임의 고정 대기로 성공을 추측하지 않는다.

## 작성한 테스트

- 파일: [BitcoinPayoutFullWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutFullWireIntegrationTest.java)
- 함수: `realCoreToRustKafkaAndLedgerCompletesOneFixedWithdrawal`
- 시나리오: 실제 Rust 예약 → 실제 PSBT 서명 → Core 방송 → 여섯 블록 → 읽기 확인 → Rust COMPLETE/WAL → Kafka → 고객 잠금 차감·수수료/채굴비 원장 → 독립 대사.
- 기대 결과: 한 완료 영수증, 고객 잠금 0, 사용 가능 잔액 0.00999900 BTC, 대사 `MATCHED`, Rust 감사 이벤트 2개.
- 실행기: [verify_bitcoin_payout_full_wire.py](../../scripts/verify_bitcoin_payout_full_wire.py). 각 실행이 소유 PostgreSQL·Kafka·Core·엔진 데이터 디렉터리를 만들고 종료 후 정리한다.

시험 기반에는 실제 주소·스크립트와 금융 이력 이전 엔진 시작을 재정의하는 좁은 확장점만 추가했다. 기존 시험은 계속 합성 주소와 합성 예약을 사용한다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
python3 scripts/verify_bitcoin_payout_full_wire.py
```

- Core 31.1 regtest, 외부 연결 0
- 실제 거래 `322c2f44e9b56220abb91b92b01b16a075580f2838fb4322a2b024489f0d328e`, 확인 6
- 시험 1개 통과, 실패·오류·제외 0
- 고객/운영·서명·방송·확정의 다섯 JAR 빌드 성공
- Core·Kafka·PostgreSQL·Rust 프로세스와 시험 자료 정리 성공

처음 실행은 엔진을 플랫폼 출금 이력 뒤에 시작해 초기화 보호가 정상 차단했고, 두 번째 실행은 기존 Mockito 답을 덮어쓰는 방식 때문에 실패했다. 제품 보호를 약화하지 않고 엔진 시작 순서와 `doAnswer` 기반 시험 준비를 수정한 뒤 위 최종 실행이 통과했다.

## 남은 한계

실제 체인·엔진·브로커·원장을 한 번에 검증했지만 regtest 한 노드다. 메인넷 자금, 독립 노드 장애 영역, 체인 재구성 중 지급 정책, HSM, 공급사 계약, 호스트 손실 복제·복원은 운영/외부 검증으로 남는다. 이번 시험은 외부 개인정보를 전송하지 않는다.

## 관련 커밋과 참고 자료

- 구현·시험: `a6d9109`
- [검증 증적](../reference/160-bitcoin-payout-full-wire-verification-2026-10-27.json)
