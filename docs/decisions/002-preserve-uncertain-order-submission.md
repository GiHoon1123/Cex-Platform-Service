# 엔진 전송 결과가 불명인 주문의 예약을 보존하기

플랫폼이 엔진 응답을 받지 못해도 엔진은 주문을 처리했을 수 있다. 통신 실패를 주문 거부로 바꾸던 경로를 삭제하고, 저장한 주문과 예약을 보존한다. 이 문서는 금융 위험, HTTP 응답 계약, 선택의 비용과 테스트 결과를 설명한다.

- 작성·검증일: 2026-10-21.
- 상태: 플랫폼 생성 경로 구현·격리 로컬 검증 완료. 엔진의 영속 접수·자동 결과 확정·중복 방지는 미완료.
- 구현 커밋: `f5f95ab`.
- 후속 변경: [003. 고객 요청 키로 중복 생성 방지](003-idempotent-order-submission.md). 현재 생성 API는 Idempotency-Key가 필수이며 같은 키·내용의 플랫폼 재요청이 가능하다. 아래 응답 예시·미완료 목록은 이 사이클 당시 기록이며 현재 계약은 003을 따른다.
- 선행 작업: [시작 시 잔액 재입금 제거](001-remove-startup-balance-replay.md), [엔진 불완전 초기 상태 차단](../../../Cex-Engine/docs/decisions/002-block-incomplete-startup-state.md).

## 문제와 기존 동작

기존 생성 경로는 다음 순서였다.

1. DB 트랜잭션에서 `pending` 주문 저장과 자산 예약을 커밋한다.
2. 트랜잭션 밖에서 엔진 HTTP API를 호출한다.
3. 통신 예외 또는 `false` 응답이면 별도 트랜잭션에서 주문을 `rejected`로 바꾸고 예약을 해제한다.

예를 들어 1,000 USDT 중 200 USDT를 예약하고 엔진이 주문을 처리한 뒤 응답만 유실되면, 플랫폼은 200 USDT를 사용 가능 잔액으로 돌려줬다. 고객은 엔진에 주문이 남아 있어도 그 자산으로 다른 주문을 만들 수 있었다. 또한 응답을 기다리는 사이 다른 처리자가 주문을 `filled`로 바꿔도 실패 보상 경로가 이를 `rejected`로 덮어썼다.

처리 전의 확정적인 검증 실패와 처리 여부를 알 수 없는 통신 실패를 같은 경로로 취급한 것이 원인이다. DB 트랜잭션의 롤백은 그 트랜잭션 안의 변경을 되돌린다. 이미 별도 프로세스가 처리했을 수 있는 HTTP 요청까지 되돌리지는 못한다.

## 기존 제품과 공식 자료의 접근

- Stripe는 네트워크 오류와 일부 서버 오류에서 처리 결과를 단정할 수 없다고 설명한다. 재시도는 같은 멱등성 키와 요청 내용을 사용하고, 불명인 결과는 조회·이벤트 등을 통해 대조해야 한다. [Stripe 오류 처리](https://docs.stripe.com/error-low-level)
- TigerBeetle은 최초 전송 전 식별자를 보존하고 재시도에도 같은 식별자를 사용한다. 서버가 같은 금융 효과를 중복 실행하지 않는 기능까지 제공한다. 이 프로젝트는 아직 그 보장을 구현하지 않았으므로 자동 재시도를 추가하지 않았다. [신뢰할 수 있는 거래 제출](https://docs.tigerbeetle.com/coding/reliable-transaction-submission/)
- HTTP `202 Accepted`는 요청이 접수됐지만 처리가 완료되지 않았음을 나타내며 상태 확인 방법을 제공하는 관례다. 이번 응답은 **플랫폼의 주문·예약 저장**을 뜻한다. 엔진의 영속 접수나 체결 완료를 뜻하지 않는다. [RFC 9110 §15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#name-202-accepted)
- 설치된 Spring Framework 6.1.6의 `SimpleClientHttpRequestFactory`는 연결·읽기 타임아웃을 제공한다. 기존 `RestTemplate`과 설정값을 사용해 적용했으며 새 의존성은 추가하지 않았다. [Spring API](https://docs.spring.io/spring-framework/docs/6.1.6/javadoc-api/org/springframework/http/client/SimpleClientHttpRequestFactory.html)

자료의 패턴을 채택한 범위는 결과 불명과 확정 실패의 구분, 기존 주문 ID로 조회, 확인 전 예약 보존이다. Stripe·TigerBeetle의 멱등성·내구성 전체를 도입했다고 해석하지 않는다.

## 선택과 트레이드오프

| 선택지 | 이점 | 비용·문제 | 판단 |
|---|---|---|---|
| 통신 실패 시 해제 | 바로 자산을 다시 사용할 수 있음 | 처리된 주문의 자산을 중복 사용할 수 있고 완료 상태도 덮어씀 | 삭제 |
| 결과 확인 전 주문·예약 보존 | 응답 유실만으로 자산을 다시 풀지 않음 | 실제 미처리·거부여도 예약이 장기간 남을 수 있음 | 채택 |
| 같은 POST 자동 재전송 | 일시적 장애 후 진행할 가능성 | 현재 엔진에 영속적인 중복 실행 방지 보장이 없음 | 구현하지 않음 |
| HTTP 대기까지 하나의 DB 트랜잭션 | 로컬 코드상 한 범위처럼 보임 | 원격 처리를 롤백하지 못하고 연결·락을 오래 점유 | 채택하지 않음 |
| `REQUIRES_NEW`로 외부 트랜잭션을 중단하고 내부 커밋 | 외부 트랜잭션에서도 호출 가능 | 외부 업무는 롤백해도 주문만 살아남는 의미를 호출자가 이해해야 함 | 현재 필요 없음 |
| 활성 외부 트랜잭션에서 생성 호출을 거부 | 전송 전 커밋이라는 계약이 명확함 | 트랜잭션이 열린 호출자는 호출 위치를 바꿔야 함 | `Propagation.NEVER` 채택 |

HTTP 400도 현재의 일반 HTTP 오류만으로 예약 해제 근거로 삼지 않는다. 직접 호출한 현재 Rust 경로의 사전 검증 거부일 수 있지만, 플랫폼 HTTP 클라이언트는 주문 ID와 연결된 영속적 최종 거부 결과를 검증하지 않는다. 상태 코드·오류 문자열에 기대어 금융 상태를 바꾸는 경로를 유지하지 않았다. 향후 확정적인 거부 결과를 정의하면 그 결과에 따라 해제한다.

`pending`은 기존 플랫폼 주문 기록을 그대로 사용한다. 전송 예외를 별도 DB 상태로 쓰지 않으므로, 이 값만으로 미전송·전송 중·엔진 처리 중을 구별할 수 없다. 반대로 늦게 도착한 통신 실패가 `filled` 등을 덮어쓰지도 않는다. 결과 불명 응답과 내부 로그는 제공하지만 영속적인 제출 시도 감사 기록은 아직 없다.

## 구현과 관련 코드

### 저장·예약 후 전송

[OrderService](../../src/main/java/dustin/cex/domains/order/service/OrderService.java)의 `createOrder`는 다음 계약을 따른다.

1. 요청을 검증한다.
2. `TransactionTemplate.execute`에서 주문 저장과 비관적 락을 사용하는 잔액 예약을 함께 커밋한다.
3. 커밋 후 엔진에 한 번 전송한다.
4. 전송 호출이 예외를 던지거나 `false`이면 DB를 변경하지 않고 `OrderSubmissionUncertainException`을 던진다.
5. HTTP 성공 응답을 받으면 기존 응답 경로를 계속 사용한다. 이 응답도 엔진의 영속 처리 보장은 아니다.

정규화된 저장 엔티티의 기준 통화를 예약과 전송에도 사용한다. 기준 통화 생략 시 저장·전송은 USDT인데 예약은 `null`로 조회하던 불일치도 제거했다.

`rollbackOrderAndBalance`, `updateOrderStatusRejected`, `unlockBalanceForOrder`, `unlockBalance`를 삭제했다. 모두 이 실패 보상 경로에만 사용됐다. 호출되지 않던 `saveOrderInTransaction`, 사용하지 않는 트랜잭션 매니저 필드, 커밋 검증을 표방하던 재조회도 제거했다. 금융 이력이나 기존 주문 행은 삭제하지 않는다.

### 결과 불명 HTTP 계약

[OrderController](../../src/main/java/dustin/cex/domains/order/controller/OrderController.java)의 지역 예외 처리기가 다음 응답을 만든다.

```http
HTTP/1.1 202 Accepted
Location: /api/cex/orders/123
Content-Type: application/json
```

```json
{
  "code": "ORDER_SUBMISSION_UNCERTAIN",
  "orderId": "123",
  "statusUrl": "/api/cex/orders/123",
  "message": "엔진 제출 결과를 확인하지 못했습니다. 새 주문을 제출하지 말고 기존 주문을 조회하세요."
}
```

인증한 고객은 `GET statusUrl`로 본인 주문을 조회한다. 새 생성 POST로 자동 재시도해서는 안 된다. `GET`이 `pending`이면 결과가 확정됐다는 뜻이 아니다. 계속 조회해도 해결되지 않을 수 있고, 아직 자동 해결 절차는 없다. `Location`은 상태 조회 주소이지 재전송 주소가 아니다.

내부 예외 원인·엔진 주소는 응답에 포함하지 않는다. 엔진 HTTP 성공 시에는 기존 `201`과 `OrderResponse` 형식이다. 요청 검증·인증 실패를 이 `202`로 바꾸지 않는다. 호환용 이중 응답 경로나 이전 실패 보상 경로는 추가하지 않았다.

### HTTP 대기 제한

[EngineHttpClient](../../src/main/java/dustin/cex/shared/http/EngineHttpClient.java)는 기존 `engine.http.timeout`을 연결·읽기에 적용한다. 기본값은 5,000ms다. 0 이하이면 시작 시 거부한다. 0을 무제한 대기로 허용하지 않는다.

연결 제한과 각 읽기의 제한이며 전체 요청의 절대 마감 시간은 아니다. 자동 재시도도 추가하지 않는다. 이 클라이언트를 공유하는 취소·잔액 동기화 호출에도 동일한 대기 제한이 적용된다. 해당 업무의 금융 결과 계약까지 이번에 수정한 것은 아니다.

## 이해에 필요한 Java 문법과 원리

- `TransactionTemplate.execute(status -> { ... })`: 람다는 TypeScript의 콜백과 비슷하다. 콜백이 정상 종료돼도 커밋 실패 시 `execute`는 예외를 던진다. 정상 반환 이후에만 HTTP로 진행한다.
- `@Transactional(propagation = NEVER)`: 이 public 메서드를 Spring 프록시를 통해 호출할 때 외부 트랜잭션이 활성 상태이면 거부한다. 메서드 안에서 `TransactionTemplate`으로 만드는 짧은 트랜잭션을 금지하는 뜻은 아니다. `new OrderService(...)`로 직접 생성하면 이 프록시 검사가 적용되지 않는다. 제품 경로는 Spring 관리 빈을 사용한다.
- 기존 private 메서드의 `@Transactional`은 같은 객체 내부 호출에서 새 프록시 호출이 일어나지 않는다. 삭제한 보상 경로의 의미를 이 어노테이션만으로 신뢰하면 안 된다.
- `OrderSubmissionUncertainException`은 저장된 주문 ID와 내부 원인을 가진 구체적인 예외 타입이다. 오류 메시지 문자열을 비교하던 분기를 없애고 타입으로 처리한다. TypeScript에서도 오류 코드·구체 타입을 사용하는 것과 같은 목적이다.
- 응답의 `record`는 불변 데이터 전달 객체다. Jackson이 구성 요소를 JSON 필드로 직렬화한다. `Long` 주문 ID를 문자열로 전달해 JavaScript `number`의 정밀도 손실을 피한다.
- 이 변경은 DB와 HTTP 사이에 분산 트랜잭션을 만들지 않는다. 확실한 로컬 커밋과 불명인 원격 결과를 구분하는 변경이다.

## 작성한 테스트

### 실제 JPA 저장·커밋: 신규 15건

파일: [OrderSubmissionTest.java](../../src/test/java/dustin/cex/domains/order/service/OrderSubmissionTest.java).

H2 인메모리 DB에 실제 엔티티·리포지토리를 구성한다. 테스트 트랜잭션을 끄고 서비스 자체의 커밋·롤백을 관찰한다. 엔진·Kafka는 Mockito 대역이며 전체 앱의 봇·소비자를 실행하지 않는다.

| 테스트 함수 | 시나리오와 기대 결과 |
|---|---|
| `uncertainSubmissionPreservesCommittedOrderAndReservation` ×4 | 타임아웃, HTTP 500, HTTP 400, 예상 밖 클라이언트 예외. 구체 결과 불명 예외의 ID·원인, `pending`, 예약 보존, 전송 1회, 생성 이벤트 없음 |
| `falseResponseDoesNotProveRejectionOrReleaseFunds` | boolean false도 예약 해제 근거가 아님 |
| `sellTimeoutPreservesBaseAssetReservation` | 매도 실패 시 기준 자산 예약 유지 |
| `marketBuyTimeoutPreservesExactQuoteReservation` | 시장가 매수 금액 123.250000001을 소수점 손실 없이 예약 유지 |
| `engineCallObservesCommittedReservationOutsideTransaction` | 엔진 호출 시 서비스 트랜잭션이 없고 주문·예약 조회 가능, 정상 생성 이벤트 호출 |
| `lateResponseFailureDoesNotOverwriteAlreadyFilledOrder` | 전송 대기 중 `filled` 변경을 주입한 뒤 예외. 완료 상태를 `rejected`로 덮어쓰지 않음. 실제 매칭·정산 테스트는 아님 |
| `insufficientBalanceRollsBackOrderBeforeSending` | 로컬 잔액 부족이면 주문 행도 롤백, 전송 없음 |
| `ambientTransactionIsRejectedBeforeAnyFinancialWrite` | 외부 트랜잭션 안의 생성 호출은 주문·예약·전송 전 거부 |
| `omittedQuoteUsesSameAssetForStorageReservationAndSubmission` | 통화 생략 시 저장·예약·전송 모두 USDT |
| `createdEventFailureDoesNotUndoOrderOrRepeatSubmission` | 부가 이벤트 예외로 주문·예약을 되돌리거나 전송을 반복하지 않음 |
| `unknownOrderCannotBeReadByAnotherOwner` | 결과 불명 주문도 소유자 조회 조건 유지 |
| `nextOrderCannotSpendFundsReservedByUnknownSubmission` | 불명 주문이 예약한 돈을 두 번째 주문에서 사용할 수 없음 |

### HTTP 응답: 신규 5건

파일: [OrderSubmissionResponseTest.java](../../src/test/java/dustin/cex/domains/order/controller/OrderSubmissionResponseTest.java).

- `uncertainSubmissionReturnsAcceptedWithExactIdAndQueryLocation`: 202, 코드·조회 경로·Location, JS 안전 정수 범위를 넘는 ID의 문자열 보존, 내부 원인 비노출.
- `confirmedHttpResponseKeepsCreatedContract`: 정상 서비스 응답은 201과 기존 주문 DTO.
- `missingIdentityDoesNotCreateAnOrder`: 사용자 속성이 없으면 401, 서비스 호출 없음.
- `malformedOrderDoesNotEnterUncertainSubmissionHandling`: Bean Validation 실패는 400, 서비스 호출 없음.
- `statusLookupPassesAuthenticatedOwnerToService`: 조회 시 소유자 ID를 전달.

실제 MVC 직렬화·예외 처리를 사용하되 사용자 속성을 주입한다. 이 테스트만으로 JWT 보안 검증을 주장하지 않는다.

### 실제 HTTP 클라이언트: 신규 7건

파일: [EngineHttpClientTest.java](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java).

- `nonpositiveTimeoutIsRejectedAtInitialization` ×2: 0과 -1 설정 거부.
- `responseStatusIsNotAutomaticallyRetried` ×4: 루프백 JDK HTTP 서버가 201·400·500·503을 반환. 성공·예외를 구분하며 POST는 각 1회.
- `receivedRequestWithoutResponseTimesOutWithoutResubmitting`: 서버가 요청을 받은 뒤 응답을 보류. 250ms 읽기 제한으로 `SocketTimeoutException` 발생, 검증 여유 3초 안에 반환, POST 1회.

## 실행 명령과 결과

Java 21, 기존 Gradle Wrapper와 설치된 Spring Boot 3.2.5 의존성을 사용했다. 새 패키지는 추가하지 않았다.

```bash
cd /Users/dustin/Desktop/common/project/Cex-Platform-Service
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew clean test \
  --tests 'dustin.cex.domains.order.service.OrderSubmissionTest' \
  --tests 'dustin.cex.domains.order.controller.OrderSubmissionResponseTest' \
  --tests 'dustin.cex.shared.http.EngineHttpClientTest' \
  --tests 'dustin.cex.shared.services.PlatformBalanceStartupTest' \
  --tests 'dustin.cex.shared.services.BalanceSyncServiceTest' bootJar
```

- 변경 전 재현: 최초 서비스 테스트 10건 중 8건 실패. 잘못된 `rejected` 변경·예약 해제·완료 상태 덮어쓰기를 재현했다.
- 변경 후: 신규 27건 + 기존 잔액 회귀 7건 = **34건 통과, 실패 0, 제외 0**.
- `clean` 후 전체 테스트 소스 컴파일과 실행 JAR 빌드 성공. 선택한 테스트만 실행했으며 기존 전체 통합 테스트 통과를 주장하지 않는다.
- 기존 deprecated API 사용 안내와 테스트 JVM의 class sharing 경고가 남아 있다.
- 실행 로그: 공통 루트 `.local/order-submission-before.log`, `.local/order-submission-suite.log`. 응답 DTO의 주석·OpenAPI 설명까지 갱신한 뒤 같은 34건과 JAR 빌드를 다시 확인한 로그는 `.local/order-submission-final.log`다.

### 새 JAR + PostgreSQL + 응답 장애 주입

공통 루트에서 다음 로컬 검증 스크립트를 실행했다. `.local`은 Git 관리 대상이 아닌 이 작업 환경의 실행 증거다. 자동화 회귀 소스는 위 테스트 파일에 남는다.

```bash
PATH=/usr/local/bin:$PATH python3 .local/order_submission_smoke.py
```

기존 로컬 전용 PostgreSQL 컨테이너에 UUID를 붙인 **새 임시 DB**를 만들었다. 새 JAR·HTTP 대역은 각각 임의 루프백 포트로 실행하고 봇은 0, Kafka·시세 WebSocket은 사용하지 않는 루프백 주소로 격리했다. 임시 JWT 키로 실제 JWT 필터를 통과해 생성·조회 API를 호출했다. 실제 엔진과 기존 고객 DB에는 주문을 보내지 않았다.

초기 USDT `available=1000`, `locked=25`에 200짜리 주문 4건을 순서대로 제출했다.

| HTTP 대역 동작 | 플랫폼 응답 | 누적 주문 수 | available / locked | DB 주문 상태·본인 조회 |
|---|---|---|---|---|
| 요청을 읽고 응답 보류, 읽기 제한 300ms | 202 | 1 | 800 / 225 | pending / 200 |
| HTTP 500 | 202 | 2 | 600 / 425 | pending / 200 |
| HTTP 400 | 202 | 3 | 400 / 625 | pending / 200 |
| HTTP 201 | 201 | 4 | 200 / 825 | pending / 200 |

각 주문의 엔진 대역 요청은 1회였다. 결과 불명 응답의 주문 ID와 Location을 대조했고, 임시 프로세스·DB를 정리했다. 결과는 `.local/order-submission-smoke.json`, 플랫폼 로그는 `.local/order-submission-runtime.log`다. 이 대역은 네트워크 경계를 시험하며 Rust의 실제 체결·저장 결과를 검증하지 않는다.

## 남은 범위와 다음 경계

1. 엔진은 아직 HTTP 응답 전에 주문을 영속 접수하거나 처리 완료를 보장하지 않는다. 이 변경은 그 보장을 추가하지 않았다.
2. 클라이언트가 최초 플랫폼 응답 자체를 잃으면 주문 ID도 모를 수 있다. 고객 요청 식별자·요청 내용 보존·중복 방지가 필요하다. 새 ID로 생성 POST를 재시도하면 별개 주문이 만들어진다.
3. 불명 결과를 자동 조회·재전송·확정하는 워커는 없다. 엔진이 실제로 받지 않았어도 예약이 남는다. 시간 경과만으로 해제해서는 안 된다.
4. 현재 주문 행에는 시장가 매수의 원본 `quoteAmount`가 없다. 그 행만 읽어 정확한 원본 명령을 재구성할 수 없으므로 자동 재전송 근거로 사용하지 않는다.
5. 플랫폼 예약, 엔진 잔액 변경, 체결·취소 결과 소비의 권위와 원자적 기록은 계속 정리해야 한다. 이 변경만으로 자산 모델 전체의 정합성·취소 경쟁·원장·감사가 완성되지 않는다.
6. 새 DB 상태나 감사 테이블을 추가하지 않았다. `pending` 주문과 예약은 DB에 남지만 어떤 전송 시도가 실패했는지의 영속 감사 증적까지 보장하지 않는다.
7. 엔진의 기존 시작 검증은 미종결 주문·예약이 있으면 시작을 거부한다. 보존한 주문을 임의로 종결시키거나 DB를 수정해 우회하는 복구 절차는 제공하지 않는다.
8. 기존에 실행 중이던 플랫폼·엔진·프론트는 교체하지 않았다. 새 JAR는 격리 검증에만 실행했다. 봇 비활성 상태를 유지한다.

다음 구현은 정확한 원본 주문 명령·고객 요청 식별자와 엔진의 영속 접수·중복 방지 경계를 연결해야 한다. 예약 해제는 주문 ID와 연결된 확정 거부·취소·체결 결과를 근거로 한다.
