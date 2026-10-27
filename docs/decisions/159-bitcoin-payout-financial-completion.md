# 여섯 확인 뒤 엔진 완료와 BTC 출금 금융 정산

158의 여섯 확인 머리만 엔진 `COMPLETE` 권한으로 사용한다. HTTP 응답은 전송 재확인에만 쓰고, 엔진 WAL에서 Kafka로 공개된 `withdrawal_completed`를 소비한 한 트랜잭션에서 고객 잠금 차감·고객 수수료·실제 광부 비용·수탁 자산 감소를 확정한다.

## 문제와 기존 동작

거래가 체인에서 확정돼도 플랫폼과 엔진의 고객 잠금은 그대로였다. HTTP `DURABLY_COMPLETED`만 믿고 플랫폼 잔액을 먼저 바꾸면 Kafka 수신 실패나 다른 엔진 결과와 분리되어 두 상태가 어긋난다. 또한 고객 고정 수수료와 실제 광부 비용을 순액 하나로만 기록하면 가격 정책과 회사 비용을 각각 설명할 수 없다.

## 기존 제품과 공식 자료의 접근

기존 프로젝트의 검증된 패턴을 그대로 확장했다. 엔진 명령은 같은 출금 UUID·고객·자산·총액으로 멱등 재확인하고, 금융 반영은 WAL 확정 뒤 단일 파티션 Kafka 이벤트와 영속 수신 키를 같은 DB 트랜잭션에서 처리한다. 158의 Core 거래·블록·tip 교차검증이 완료 권한이며 HTTP 확인은 금융 수신을 대신하지 않는다.

복식 원장은 고객에게 받은 고정 수수료와 실제 광부 비용을 별도 거래로 기록한다. 이로써 `고객 LOCKED -총액 + 회사 FEE +고객수수료 + CUSTODY +순지급액 = 0`, `회사 FEE -광부비용 + CUSTODY +광부비용 = 0`을 각각 검증할 수 있다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 체인 머리 뒤에만 `COMPLETE` | 멤풀 수신을 지급 완료로 오인하지 않음 | 여섯 확인까지 고객 자금이 잠김 |
| HTTP와 Kafka 금융 확정 분리 | 응답 유실·재시작에도 WAL 결과가 권위 | 완료가 두 단계라 지연과 운영 관찰 항목 증가 |
| 고객 수수료와 광부 비용 별도 원장 거래 | 매출·실제 비용·회사 부담을 각각 대사 | 한 출금에 원장 거래 두 개 필요 |
| 기존 `CUSTODY` 보호의 좁은 확장 | 임의 업무가 수탁 계정을 만들지 못함 | 완료 이벤트·체인 머리·정확 금액을 같은 커밋에 갖춰야 함 |
| 완료를 기존 출금 장수 워커의 최우선 작업으로 처리 | 별도 임시 스레드 없이 자동 진행 | 한 플랫폼 프로세스의 출금 예약·해제·완료가 같은 워커 처리량을 공유 |

회사가 부담하는 금액은 `max(광부 비용 - 고객 수수료, 0)`이며 155에서 미완료 실행별로 예약했다. 완료 시 광부 비용 전체를 회사 FEE에서 차감하고 고객 수수료를 별도로 적립하므로 최종 순변화가 이 예약과 일치한다.

## 구현과 관련 코드

출처: [BitcoinPayoutCompletionDispatches.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCompletionDispatches.java), 현재 소스의 부분 발췌.

```java
    public Optional<Execution> processNext(){Claim claim=tx.execute(t->claim());if(claim==null)return Optional.empty();EngineWithdrawalClient.Outcome outcome;try{outcome=engine.execute(new EngineWithdrawalClient.Command(claim.request(),claim.owner(),"BTC",claim.gross(),EngineWithdrawalClient.Action.COMPLETE));if(outcome==null)outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}catch(RuntimeException uncertain){outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}var saved=outcome;tx.executeWithoutResult(t->{users.lockById(claim.owner()).orElseThrow();jdbc.update("INSERT INTO bitcoin_payout_completion_results(attempt_id,outcome) VALUES(?,?)",claim.attempt(),saved.name());audit.record(null,"BTC_WITHDRAWAL_COMPLETE_FINISHED","bitcoin-withdrawal-complete:"+claim.attempt(),"SUCCESS","OUTCOME:"+saved.name());});return Optional.of(new Execution(claim.request(),claim.attempt(),saved));}
```

첫 트랜잭션은 여섯 확인 머리를 잠그고 시도를 저장한다. 엔진 호출 동안 DB 잠금은 없다. `DURABLY_COMPLETED`여도 여기서는 고객 잔액을 바꾸지 않고 결과만 보존한다.

출처: [BitcoinWithdrawalEvents.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalEvents.java), 현재 소스의 부분 발췌.

```java
        String operation="engine:"+eventId,miner="btc:withdrawal:miner-fee:"+id;ledger.record(operation,"BTC_WITHDRAWAL_COMPLETE",List.of(p(owner,"LOCKED","BTC",gross.negate()),p(0,"FEE","BTC",fee),p(0,"CUSTODY","BTC",amount)));ledger.record(miner,"BTC_WITHDRAWAL_MINER_FEE",List.of(p(0,"FEE","BTC",nativeFee.negate()),p(0,"CUSTODY","BTC",nativeFee)));
```

`gross.negate()`는 고객이 예약한 순지급액+고정 수수료를 정확히 제거한다. 첫 원장은 고객 계약, 두 번째 원장은 실제 체인 비용이다. 두 원장의 자산별 합은 각각 0이며 같은 Kafka 수신 트랜잭션에서 잔액·영수증·감사와 함께 커밋된다.

## 작성한 테스트

- [BitcoinPayoutCompletionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCompletionIntegrationTest.java), 9개: 정확한 COMPLETE 명령, HTTP만으로 미정산, Kafka 일회 정산, 다른 엔진 차단, 결과 불명, 감사 누락 전체 롤백, 불변 이력, 두 소비자 경쟁, 독립 원장 대사.
- 기존 출금 예약 회귀 19개: 예약·거절·중복·감사·독립 대사가 완료 이벤트 지원 뒤에도 유지된다.
- 실제 별도 Rust/WAL/Kafka 출금 연결 3개: 예약·해제 응답 유실/재시작/중복 소비 회귀.
- Rust 단일 코어 완료 회귀 1개: 다른 예약을 보존하고 원래 잠금만 한 번 차감한다. 라이브러리와 바이너리 시험 모듈에서 같은 시험이 2회 실행됐다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_payout_completion.py`: 메서드별 소유 PostgreSQL 9개 통과, 실패·오류·제외 0, 다섯 JAR 통과.
- `python3 scripts/verify_bitcoin_withdrawal_reservations.py`: 기존 19개 통과, 실패·오류·제외 0.
- `python3 scripts/verify_bitcoin_withdrawal_wire.py`: 실제 소유 PostgreSQL·Kafka·Rust/WAL 연결 3개 통과, 자원 정리 성공.
- `cargo test --locked --offline withdrawal_complete_debits_only_the_original_hold_once`: 고유 1개가 두 시험 모듈에서 2회 통과. 기존 Rust 경고는 남아 있으며 경고 없음으로 주장하지 않는다.
- 고유 32개/33회 통과다. 이 문서 시점에는 실제 Core 방송·여섯 확인과 Rust/Kafka 검증이 분리돼 있었다. 이후 [160 전체 실제 연결](160-bitcoin-payout-full-wire.md)이 그 빈칸을 닫았다.

## 완료 범위와 남은 한계

BTC 출금의 코드 경로는 고객 예약 → 심사/승인 → 관찰 지갑 준비 → 입력 잠금 → 격리 서명 → 격리 방송 → 여섯 확인 → 엔진 완료 → 플랫폼 원장 정산까지 연결됐다. 각 단계의 격리·실패 회귀는 통과했지만 메인넷 자금, 실제 공급사, 별도 운영 노드/호스트, HSM, 외부 백업·복원, 조직상 승인 분리와 전체 운영 부하 시험은 완료하지 않았다.

다음 백엔드 검토에서는 이 경로를 포함한 전체 미충족 목록과 실행 설정을 다시 대조한다. 이후 고객·어드민 프론트와 연결 검증을 끝내고, 합의한 마지막 기능인 ETH 거래시장·입출금을 BTC와 같은 안전 원칙으로 추가한다.

## 관련 커밋과 증적

- [금융 완료 구현 40fbf6d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/40fbf6d)
- [검증 증적](../reference/159-bitcoin-payout-financial-completion-verification-2026-10-27.json)
