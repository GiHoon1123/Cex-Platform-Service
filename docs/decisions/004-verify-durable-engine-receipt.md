# 엔진의 성공 상태 코드와 영속 접수 증명을 구분하기

주문 생성 HTTP가 2xx라는 이유만으로 엔진 접수를 확인했다고 처리하지 않는다. 요청한 주문·고객과 일치하는 접수 증명을 검증한다. 엔진의 저장·동시성 설계는 [엔진 영속 접수 기록](../../../Cex-Engine/docs/decisions/003-durable-order-acceptance.md)을 기준으로 읽는다.

- 작성·검증일: 2026-10-21.
- 구현 커밋: `4810e55`.
- 이전 고객 요청 키·재요청 계약은 [003](003-idempotent-order-submission.md)을 유지한다.

## 선택과 트레이드오프

기존 클라이언트는 빈 본문이나 잘못된 주문 DTO라도 2xx면 true를 반환했다. 엔진은 이제 PostgreSQL 접수 기록을 커밋한 뒤 202, 동일한 기존 접수에는 200을 반환한다.

[EngineHttpClient](../../src/main/java/dustin/cex/shared/http/EngineHttpClient.java)는 다음을 모두 확인한다.

1. HTTP 상태는 200 또는 202다.
2. 본문은 JSON 객체다.
3. `order_id`와 `user_id`는 문자열이며 요청 ID와 정확히 일치한다.
4. `receipt_status`는 `accepted`다.
5. `accepted_at`은 시간대가 있는 시각으로 파싱된다.

ID의 느슨한 숫자 변환·과거 엔진 201 DTO·빈 응답 폴백은 없다. 불일치·파싱 실패는 기존 결과 불명 경로로 전달해 주문·예약을 보존한다. 자동 재전송은 추가하지 않았다. Jackson·Java `OffsetDateTime`을 사용하며 의존성을 추가하지 않았다.

**이점:** 다른 요청의 응답이나 오래된 프로토콜을 접수 증거로 오인하지 않는다. **비용:** 엔진과 플랫폼을 새 계약으로 함께 준비해야 하며 형식 불일치 시 예약이 남을 수 있다. 내부 HTTP 호출자 인증·응답 서명까지 보장하는 검증은 아니다.

고객 API의 최초 201은 이제 검증된 엔진 영속 접수 응답을 받았다는 뜻이다. 체결 완료는 아니다. 엔진 결과 불명일 때 고객 202, 같은 키의 플랫폼 재요청 200, 내용 충돌 409 계약은 유지한다.

## 작성한 테스트

파일: [EngineHttpClientTest](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java).

- `responseStatusIsNotAutomaticallyRetried`: 기존 4개 상태에서 6개로 확장. 200·202의 유효 접수만 성공하고 이전 201·400·500·503은 예외다. 각 HTTP 전송은 1회다.
- 신규 `successfulHttpWithoutMatchingReceiptIsNotAcceptance` ×7: 빈 객체·다른 주문·다른 고객·숫자 ID·누락 시각·잘못된 시각·다른 접수 상태를 202로 받아도 실패한다. 추가 전송 없음.
- 기존 비양수 대기 제한 2건·실제 응답 보류 타임아웃 1건도 유지한다. 클라이언트 테스트 합계 16건이다.

기존 플랫폼 주문·결과 불명·잔액 회귀를 함께 실행해 **83건 통과, 실패 0, 제외 0**을 확인했다. 기존 74건에 상태 조합 2건·잘못된 접수 7건을 더한 결과다. JAR 빌드·전체 테스트 소스 컴파일도 성공했다. 기존 deprecated API·JVM class sharing 경고가 남고, 기존 전체 통합 테스트는 실행하지 않았다.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
./gradlew test --tests 'dustin.cex.domains.order.*' \
  --tests 'dustin.cex.shared.http.EngineHttpClientTest' \
  --tests 'dustin.cex.shared.services.PlatformBalanceStartupTest' \
  --tests 'dustin.cex.shared.services.BalanceSyncServiceTest' bootJar
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
PATH=/usr/local/bin:$PATH python3 scripts/verify_order_idempotency.py
```

[프로세스 검증 대역](../../scripts/verify_order_idempotency.py)도 새 접수 형식으로 바꾸고 기존 8건을 재실행해 모두 통과했다. 대역이 실제 엔진의 내구성을 증명하는 것은 아니다.

실제 새 Java·Rust·PostgreSQL 연결에서는 엔진 응답 유실 후 202·같은 키 재요청 200, 정상 전달 시 실제 접수 검증 후 고객 201을 확인했다. 엔진의 접수·실행 중복 차단과 `800/200`, 이후 `700/300` 예약 확인은 엔진 문서의 연결 시나리오를 따른다. 기존 실행 서비스는 교체하지 않았고 봇은 비활성 상태다.

## 문법과 남은 경계

Jackson `path`는 없는 필드에 MissingNode를 반환한다. `isTextual`과 실제 값 비교로 숫자 ID를 문자열처럼 느슨하게 받아들이지 않는다. `OffsetDateTime.parse`는 단순 문자열 존재와 유효한 시간대 시각을 구분한다. TypeScript의 타입 선언만으로 외부 JSON을 검증할 수 없는 것과 같은 이유다.

영속 접수는 체결 확정·예약 해제 근거가 아니다. 접수 뒤 전달·실행 중 종료된 주문의 재생과 처리 결과 확정은 후속 구현이 필요하다. 현재 플랫폼은 같은 고객 요청 키로 기존 주문을 조회하며 엔진에 자동 재전송하지 않는다.

실행 로그: 공통 루트 `.local/platform-receipt-suite.log`, `.local/platform-receipt-process.log`. 실제 엔진 연결 결과: `.local/durable-acceptance-process.json`.

### 현재 코드로 연결해 읽기

HTTP 성공 코드와 영속 접수 증명을 함께 검사하는 부분이다. 아래는 2026-10-23 현재 [실제 소스](../../src/main/java/dustin/cex/shared/http/EngineHttpClient.java#L137)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

```java
int status = response.getStatusCode().value();
JsonNode receipt = objectMapper.readTree(response.getBody());
if ((status != 200 && status != 202) || receipt == null || !receipt.isObject()
        || !receipt.path("order_id").isTextual()
        || !orderId.toString().equals(receipt.path("order_id").textValue())
        || !receipt.path("user_id").isTextual()
        || !userId.toString().equals(receipt.path("user_id").textValue())
        || !"accepted".equals(receipt.path("receipt_status").textValue())
        || !receipt.path("accepted_at").isTextual()) {
    throw new IllegalStateException("엔진의 영속 접수 응답을 확인하지 못했습니다");
}
OffsetDateTime.parse(receipt.path("accepted_at").textValue());
return true;
```

`||`로 하나라도 불일치하면 거부한다. JSON의 요청 ID·고객 ID·상태·시각을 검사하므로 단순 2xx를 체결 완료로 취급하지 않는다. 접수와 체결은 별도 결과다.
