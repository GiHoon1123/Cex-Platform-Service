# Java·DB·Rust가 공통으로 표현할 수 있는 금액만 접수하기

주문을 예약한 뒤 엔진에서 금액을 해석하지 못하는 일을 막는다. 입력뿐 아니라 지정가 대금과 예약 전후 잔액도 같은 정확성 계약으로 검사한다. [엔진 검토·해독 수정](../../../Cex-Engine/docs/decisions/005-exact-amount-boundaries-review.md)과 함께 적용한다.

## 문제와 기존 동작

Java BigDecimal은 임의 정밀도 십진수를 표현하고 DB는 NUMERIC(30,9)를 사용하지만 Rust Decimal은 96비트 정수 계수만 가진다. 기존 검증은 정수 21자리·소수 9자리만 확인해, 엔진에서 표현할 수 없는 값도 저장·예약할 수 있었다.

`999999999999999999999.123456789`는 DB에는 들어가지만 Decimal에 정확히 들어가지 않는다. 입력이 각각 유효해도 `100000000000000000000 - 0.000000001` 같은 예약 후 잔액은 30자리 계수가 되어 표현되지 않는다.

또 지정가 대금 `price × amount` 검증이 매수에만 있어 매도는 9자리보다 세밀한 대금을 갖고 주문장에 들어갈 수 있었다. 기존 lockBalance는 NULL을 0으로 취급했고 음수 예약도 새로운 예약을 더하면 정상처럼 보일 수 있었다.

## 공식 자료와 선택

[BigDecimal 공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)의 unscaledValue와 scale을 사용한다. 새 패키지는 추가하지 않았다. [PostgreSQL NUMERIC](https://www.postgresql.org/docs/16/datatype-numeric.html)의 저장 시 반올림과 [Rust Decimal 구현](https://github.com/paupino/rust-decimal/blob/1.40.0/src/decimal.rs)의 96비트 계수 범위를 확인했다.

[ExactAmount.java](../../src/main/java/dustin/cex/shared/model/ExactAmount.java)는 현재 두 시스템의 공통 표현 범위를 검사하는 작은 값 검증기다.

1. NULL·음수 거부. 주문 금액은 0도 거부한다.
2. 끝의 0을 제거하고 소수 9자리·정수 21자리 이하인지 검사한다.
3. 정수 계수가 `2^96 - 1 = 79228162514264337593543950335` 이하인지 확인한다.
4. 정규화된 BigDecimal을 반환한다. 반올림은 하지 않는다.

큰 지수를 setScale로 먼저 확장하지 않는다. `1E+1000000000` 같은 입력은 자릿수 검사로 먼저 거부하고, 통과한 최대 21자리 정수에서만 음수 scale을 0으로 바꿔 계수를 확인한다.

**트레이드오프:** DB 자체보다 허용 금액이 좁아지고 계산 결과가 표현 불가인 예약도 거부한다. 대신 커밋 뒤 엔진에서 발견되는 금액 해석 실패를 줄인다. 모든 금액을 BigInt/BigDecimal로 바꾸는 대안은 엔진 매칭·저장·직렬화 전체 검증이 필요하므로 이번 범위에서는 기존 Decimal의 정확한 범위를 사용한다. 이 검증은 후속 부분 체결의 모든 조합이 실행 가능하다고 보장하지 않는다.

## 구현과 문법

- [OrderCommand.java](../../src/main/java/dustin/cex/domains/order/model/OrderCommand.java): 가격·수량·시장가 예산과 모든 지정가 주문의 대금을 검사한다. 기존 BigDecimal 정규화·멱등성 비교는 유지한다.
- [OrderService.java](../../src/main/java/dustin/cex/domains/order/service/OrderService.java): DB 비관적 락 안에서 available·locked 원본과 두 결과를 검사한 뒤 엔티티를 수정한다. 오류면 같은 TransactionTemplate의 주문·원본 명령·예약이 전부 롤백된다. NULL·음수 보정 경로를 삭제했다.

`BigInteger.ONE.shiftLeft(96).subtract(BigInteger.ONE)`는 부동소수점 없이 최대 계수를 만든다. TypeScript의 `1n << 96n`과 같은 정수 연산이다. BigDecimal의 `unscaledValue()`는 소수점 위치를 분리한 정수 계수이며 JavaScript Number와 달리 큰 금액을 부동소수점으로 바꾸지 않는다.

BigDecimal은 불변이므로 subtract/add로 새 값을 계산한 것만으로 엔티티가 바뀌지 않는다. 두 결과 모두 검사한 다음 setter를 호출한다. DB 커밋 뒤 엔진 HTTP를 호출하는 기존 접수 순서는 유지한다.

### 현재 코드로 연결해 읽기

금액이 양수인지 확인하면서 공통 표현 범위 검증을 먼저 재사용한다. 아래는 2026-10-23 현재 [실제 소스](../../src/main/java/dustin/cex/shared/model/ExactAmount.java#L17)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

```java
public static BigDecimal requirePositive(BigDecimal value, String field) {
    BigDecimal normalized = requireNonNegative(value, field);
    if (normalized.signum() == 0) {
        throw new IllegalArgumentException(field + "는 양수여야 합니다");
    }
    return normalized;
}
```

`BigDecimal`의 `signum()`은 값의 부호를 반환한다. 실제 소수점/정수 자릿수와 Rust 96비트 계수 검증은 호출한 `requireNonNegative`까지 읽어야 한다. 이 짧은 메서드만으로 전체 금액 범위를 보장하는 것은 아니다.

## 작성한 테스트

[OrderCommandTest.java](../../src/test/java/dustin/cex/domains/order/model/OrderCommandTest.java):

| 신규 함수 | 시나리오·기대 결과 |
|---|---|
| rustDecimalUnrepresentableInputIsRejectedBeforeReservation | DB 유효·Rust 초과 값 두 개 거부 |
| exactDecimalBoundaryAndTrailingZerosAreAccepted | 최대 96비트 계수·표시용 끝 0 허용 |
| unrepresentableReservationProductIsRejectedBeforeReservation | 입력은 각각 유효하나 대금 표현 불가, 거부 |
| limitSellWithInexactQuoteValueIsRejectedLikeLimitBuy | 1e-9 가격 × 0.1 매도 수량, 대금 1e-10 거부 |

[OrderSubmissionTest.java](../../src/test/java/dustin/cex/domains/order/service/OrderSubmissionTest.java):

| 신규 함수 | 시나리오·기대 결과 |
|---|---|
| corruptNegativeReservationIsRejectedWithoutRepairOrSubmission | 음수 예약 보정 없음, 주문·원본·HTTP·이벤트 없음 |
| unrepresentableReservationSplitRollsBackOrderAndOriginalBalance | 예약 후 사용 가능 잔액 표현 불가, 전체 롤백·원본 보존 |

[verify_order_idempotency.py](../../scripts/verify_order_idempotency.py)의 신규 실제 HTTP 검사:

| 함수 | 시나리오·기대 결과 |
|---|---|
| test_unrepresentable_commands_are_rejected_without_financial_effect | Rust 초과 입력·매도 대금 정밀도 오류 각각 400, 주문·원본·예약·엔진 전송 없음 |
| test_invalid_balance_and_unrepresentable_split_roll_back_everything | 음수 예약·예약 분할 오류 각각 400, 기존 금액 보존·주문과 접수 롤백 |

## 실행과 결과

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
./gradlew test --tests 'dustin.cex.domains.order.*' \
  --tests 'dustin.cex.shared.http.EngineHttpClientTest' \
  --tests 'dustin.cex.shared.services.PlatformBalanceStartupTest' \
  --tests 'dustin.cex.shared.services.BalanceSyncServiceTest' bootJar
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
PATH=/usr/local/bin:$PATH python3 scripts/verify_order_idempotency.py
```

- 수정 전 명령 테스트: **24건 중 20통과·4실패**. 새 5건 중 4건이 기존 검증의 허용 오류를 재현했다.
- 수정 후 선택 Java 테스트 **90건 통과**, 실패·오류·skip 0. 기존 83건에 신규 7건을 추가했다. 테스트 JPA는 H2에만 연결했다. JAR 빌드 성공.
- 새 Java 프로세스·전용 UUID PostgreSQL DB·엔진 HTTP 대역 검증 **10건 통과**. 신규 2건·기존 8건이며 동시 요청·응답 유실·프로세스 재시작도 재검사했다. 테스트가 만든 DB·프로세스를 정리했다. 기존 5432·고객 DB·실제 Kafka는 사용하지 않았고 봇은 0이다.
- 실제 Java↔Rust 접수 검증은 엔진 문서에 기록한다. HTTP 대역 검증과 실제 Rust 매칭 검증을 혼동하지 않는다.
- 공통 루트 `.local/review-command-before.log`, `review-java-final.log`, `review-platform-process.log`와 Gradle XML에 결과를 기록했다. 필터 밖 테스트는 실행하지 않았다.

## 한계와 관련 커밋

이 검사는 주문 생성·예약 경계에 적용했다. 기존 Kafka 스냅샷/증감 처리, 입출금, 수수료, 정정 원장 전체를 보장하지 않는다. 주문장·DB·이벤트의 확정, 취소 경쟁, 순서·재생은 여전히 우선 과제다. 현재 금액 오류는 기존 IllegalArgumentException 처리에 따라 HTTP 400으로 반환하며 손상 잔액의 운영 경보·오류 코드 세분화는 후속 범위다.

- `46b5fd6`: 공통 정확한 금액 계약·명령 검증과 테스트.
- `5a0441e`: 예약 원본·결과 검증과 실제 JPA 롤백 테스트.
- `fde9210`: 실제 HTTP·PostgreSQL 신규 2건.

076 현행 기준: 과거 실행 명령에 포함된 BalanceSyncServiceTest는 사용하지 않는 도우미와 함께 삭제했다. 위 명령/수치는 당시 실행 이력이며 현재 실행 범위는 [076 정리 기록](076-remove-retired-balance-sync.md)을 따른다.
