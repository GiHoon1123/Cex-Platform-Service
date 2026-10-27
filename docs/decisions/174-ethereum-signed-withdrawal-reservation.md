# 고객 서명 ETH 출금 예약과 엔진 확정 결과

ETH 출금의 지급 주소·지급액·0.01% 고객 수수료를 짧은 수명의 견적으로 고정하고, 고객 패스키 서명 뒤 플랫폼 원장과 Rust 엔진에 같은 총액을 예약한다. HTTP 응답은 금융 확정으로 사용하지 않고 엔진 WAL에서 Kafka로 공개된 예약·거절 결과만 최초 결과로 반영한다.

## 문제와 기존 동작

ETH 거래는 가능했지만 고객 출금 원본과 예약 경로가 없었다. BTC 전용 표와 스크립트 판독 경로를 억지로 재사용하면 서로 다른 체인 의미가 섞이고, BTC 제약을 느슨하게 바꿔 기존 보장을 약화시킬 수 있다.

## 기존 제품과 공식 자료의 접근

- [Ethereum JSON-RPC](https://ethereum.org/developers/apis/json-rpc/)가 사용하는 20바이트 주소 형식을 정규화한다. 플랫폼은 개인키를 받지 않는다.
- 고객의 중요 금융 요청은 로그인만으로 실행하지 않고 기존 BTC 출금과 같은 WebAuthn 건별 서명 패턴을 적용한다.
- 엔진 예약은 기존 WAL 확정·Kafka 공개 계약을 그대로 사용한다. 새 자산을 이유로 동기 DB 쓰기나 공유 락을 Rust 코어에 추가하지 않았다.

## 선택과 트레이드오프

BTC와 ETH는 고객 화면의 흐름이 같아도 주소 검증·체인 지급·확정 근거가 다르므로 별도 불변 표와 서비스를 사용했다. 코드가 일부 반복되지만 BTC 스크립트·UTXO 조건을 완화하지 않고 자산별 감사를 명확히 한다.

현재 공통 원장/엔진 정밀도에 맞춰 ETH 출금은 소수점 아홉 자리까지 허용한다. 0.01% 수수료는 아홉 번째 자리에서 올림하며 고객 지급액은 반올림하지 않는다. 네이티브 wei의 18자리 전체를 바로 출금할 수 없다는 제약 대신 플랫폼·엔진 사이의 정확한 금액 계약을 유지한다.

체인 지급 완료 이벤트는 아직 실패 폐쇄한다. 별도 승인·서명·방송·확인 계층 없이 `withdrawal_completed`를 받아 고객 잠금을 차감하면 가짜 완료가 될 수 있기 때문이다. 이 문서는 출금 전체 완료가 아니라 안전한 예약 계층의 완료 기록이다.

## 구현과 관련 코드

- `EthereumWithdrawalAmounts`: 0.01% 고정 요율, 올림, 총 차감액을 한 번 계산한다.
- `EthereumWithdrawalQuotes`: 현재 고객·세션·자격, 정규 주소와 금액을 2분 견적으로 고정하고 요청 키를 재사용한다.
- `EthereumWithdrawalAuthorization`: 견적 해시와 고객·세션·업무 목적을 WebAuthn 챌린지에 결합한다.
- `EthereumWithdrawalRequests`: 서명 검증, 가용→잠금 잔액 이동, 균형 원장, 출금 원본, 필수 감사를 한 DB 트랜잭션에 둔다.
- `EthereumWithdrawalDispatches`: DB 잠금 밖에서 같은 UUID·고객·ETH·총액을 엔진에 예약하고 불명 결과는 동일 ID로만 재확인한다.
- `BitcoinWithdrawalEvents`: 기존 이름은 유지하지만 ETH 최초 예약·거절을 별도 표와 원장 종류로 분기한다. ETH 체인 완료·해제는 후속 생명주기 전까지 거부한다.

실제 작성한 코드 일부다.

```java
outcome = engine.execute(new EngineWithdrawalClient.Command(
    id, claim.owner(), "ETH", claim.total(), EngineWithdrawalClient.Action.RESERVE));
```

Java `record` 접근자인 `owner()`와 `total()`은 TypeScript의 읽기 전용 객체 필드와 비슷하지만 생성자에서 정해진 명목 타입의 구성요소다. 외부 호출 전에 원본과 시도를 커밋하므로 응답이 유실돼도 새 출금 ID나 새 금액을 만들지 않는다.

```java
if (ethereum && (type.equals("withdrawal_released") || type.equals("withdrawal_completed"))) {
    throw new IllegalStateException("ETH 출금의 체인 지급 생명주기가 아직 확정되지 않았습니다");
}
```

지원하지 않는 상태를 성공처럼 저장하는 폴백이 아니라 명시적으로 중단한다. Kafka 소비 트랜잭션도 실패하므로 브로커 위치가 전진하지 않고, 이후 올바른 지급 계층을 배포한 뒤 같은 이벤트를 다시 처리할 수 있다.

## 작성한 테스트

- `EthereumWithdrawalAmountsTest.calculatesOneBasisPointAndTotalWithoutBinaryFloatingPoint`: 십진 금액과 0.01% 올림을 검증한다.
- `EthereumWithdrawalAmountsTest.roundsFeeUpButNeverRoundsCustomerAmount`: 최소 단위 수수료와 10자리·지수·음수 입력 거부를 검증한다.
- `EthereumWithdrawalEventTest.confirmedReservationUsesEthereumSourceAndCreatesNoSecondBalanceMove`: ETH 원본 표를 조회하고 예약 확인이 잔액을 두 번 이동하지 않는지 검증한다.
- `EthereumWithdrawalEventTest.ethereumCompletionIsClosedUntilThePayoutLifecycleExists`: 지급 증거 없는 ETH 완료 이벤트를 거부한다.
- `scripts/verify_platform_security.py`: 실제 격리 PostgreSQL에서 등록된 API 전수 인증·역할 경계 42개를 실행했다.

## 실행 명령과 결과

```text
./gradlew test --tests 'dustin.cex.domains.ethereum.*' --no-daemon --console=plain
python3 scripts/verify_platform_security.py
./gradlew bootJar operatorAdminJar --no-daemon --console=plain
```

ETH 단위 테스트 9개와 실제 PostgreSQL 보안 회귀 42개가 실패·오류·제외 없이 통과했다. 두 웹 JAR 생성도 통과했다. 실제 ETH 서명·전송·확정·엔진 COMPLETE와 원장 차감은 아직 구현하지 않았으므로 출금 전체 완료로 표시하지 않는다.

## 관련 커밋과 참고 자료

구현·테스트·이 문서를 같은 기능 커밋으로 기록한다. 다음 단계는 다른 운영자의 내용 결합 승인, 웹 플랫폼 밖 서명/방송 프로세스, 확정 블록 증거, 엔진 완료와 수탁 대사다.
