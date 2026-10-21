# 플랫폼 시작 시 기존 잔액 재입금 제거

플랫폼이 시작될 때 DB의 기존 잔액을 엔진에 추가 입금하던 경로를 삭제했다. 애플리케이션 준비 이벤트와 Spring 컨텍스트 재시작이 금융 효과를 만들지 않도록 검증한다.

- 작업일: 2026-10-21.
- 분류: 고도화 — 잔액 정합성, 시작·재시작 안전성.
- 구현 커밋: `fc33046` (`fix(balance): 플랫폼 시작 시 기존 잔액 재입금 제거`).
- 검증: 신규 자동화 테스트 7개 통과, 실행 JAR 빌드, 격리한 PostgreSQL에서 실제 프로세스 시작 2회 확인.
- 범위: 플랫폼의 기존 잔액 재입금 경로. 엔진 전체 복구·원장·입출금 멱등성 완료를 의미하지 않는다.

## 문제와 기존 동작

기존 `BalanceInitializationService`는 `ApplicationReadyEvent`를 받으면 `user_balances`를 조회하고 각 양수 `available`을 `BalanceSyncService.syncDeposit()`으로 전송했다.

그 요청은 잔액을 지정하는 명령이 아니라 `available_delta`를 더하는 명령이다. [엔진 시작 코드](../../../Cex-Engine/src/domains/cex/engine/runtime/engine.rs)는 이미 DB 잔액을 읽고, [엔진 증감 처리](../../../Cex-Engine/src/domains/cex/engine/runtime/threads.rs)는 받은 delta를 메모리에 추가한다.

예를 들어 예약금이 없는 DB 잔액이 100 USDT라면 다음과 같이 동작할 수 있다.

```text
DB: 100 USDT
→ 엔진 시작: 메모리에 100 USDT 로드
→ 플랫폼 시작: 기존 100 USDT를 +100 delta로 전송
→ 엔진 메모리: 200 USDT
→ 플랫폼만 다시 시작: +100 delta 재전송
→ 엔진 메모리: 300 USDT
```

이 경로는 엔진 메모리만 바꾸므로 DB 숫자만 확인하면 문제를 놓친다. 체결·새 입금 없이 애플리케이션 재시작이 가용 자산을 늘리고, DB와 엔진 사이의 불일치를 만들 수 있다.

## 기존 제품과 공식 자료의 접근

Spring Boot 3.2.5 문서에서 `ApplicationReadyEvent`는 해당 애플리케이션의 runner 실행 이후 발생하는 수명주기 이벤트다. 외부 Rust 엔진의 준비·상태 일치를 보장하는 이벤트가 아니다. 컨텍스트 계층에 따라 같은 종류의 이벤트가 여러 번 전달될 수도 있다. [Spring Boot 애플리케이션 이벤트](https://docs.spring.io/spring-boot/docs/3.2.5/reference/htmlsingle/#features.spring-application.application-events-and-listeners)

TigerBeetle은 금융 거래의 중복 생성을 막기 위해 고유 ID를 재사용하는 제출 패턴을 설명한다. 현재 경로에는 새로운 입금을 증명하는 거래 ID나 중복 처리 방지가 없다. 기존 잔액 스냅샷을 새 입금으로 재해석하지 않는 것이 이번 변경의 핵심이다. TigerBeetle을 새 의존성으로 도입한 것은 아니다. [Reliable Transaction Submission](https://docs.tigerbeetle.com/coding/reliable-transaction-submission/)

## 선택과 트레이드오프

| 대안 | 이점 | 비용·문제 | 결정 |
|---|---|---|---|
| 플랫폼 시작 때마다 delta 전송 | 기존 코드 유지 | 기존 잔액을 반복 적립 | 삭제 |
| 플랫폼 시작 때 절대 잔액 덮어쓰기 | 반복 적립은 피할 수 있음 | 진행 중 체결·예약과 경쟁하고 상태 권위가 추가됨 | 채택하지 않음 |
| DB 잔액이 있으면 한 번만 보내기 | 단순한 조건 추가 | JVM 플래그는 재시작을 견디지 못하고, DB 플래그는 새로운 동기화 상태를 요구 | 채택하지 않음 |
| 시작 시 재입금 자체 제거 | 현재 엔진의 DB 로드와 중복되는 금융 동작 제거 | 엔진 지연·복구 문제는 별도 해결 필요 | 채택 |

새 동기화 API·설정·호환 레이어·폴백을 추가하지 않았다. 이 결정은 전체 자산 원장과 체결 확정 경계의 최종 설계까지 확정하지 않는다.

## 구현과 관련 코드

- `BalanceInitializationService.java`와 이벤트 리스너를 삭제했다.
- [BalanceSyncService](../../src/main/java/dustin/cex/shared/services/BalanceSyncService.java)의 사용하지 않는 `syncAllBalances()` 호환 메서드를 삭제했다.
- 남아 있는 서비스에 시작 복원과 명시적 증감을 구분하는 한국어 주석을 추가했다.
- `BalanceSyncService`는 [봇 관리자](../../src/main/java/dustin/cex/domains/bot/service/BotManagerService.java)에서 참조하므로 클래스 전체를 삭제하지 않았다. 봇은 계속 `--bot.bot-count=0`으로 비활성화한다.
- 패키지·라이브러리·엔진 코드를 변경하지 않았다. 기존 Spring Test, Mockito, AssertJ, H2로 검증했다.

## 이해에 필요한 문법과 원리

- `@EventListener`는 이벤트 발생 시 메서드를 실행한다. 시작 이벤트를 금융 거래의 발생 근거로 사용하면 재시작도 새 입금처럼 처리될 수 있다.
- `@PostConstruct`는 Spring이 빈을 초기화하면서 호출한다. 테스트용 엔진 클라이언트에도 이 메서드가 호출되므로 객체 전체 상호작용과 실제 `syncBalance()` 호출을 구분해 검사한다.
- `AnnotationConfigApplicationContext`는 Spring 빈 등록·의존성 주입·이벤트 전달을 실제로 실행한다. 전체 Boot 앱 대신 잔액 서비스 패키지를 스캔해 봇·Kafka·스케줄러 실행을 피했다.
- `try (var context = ...)`는 블록이 끝날 때 컨텍스트를 닫는 Java의 자원 정리 구문이다. 같은 DB를 유지하고 컨텍스트만 새로 만들며 재시작 경계를 검사한다.
- `BigDecimal`은 문자열에서 생성해 십진수 금액을 유지한다. `negate()`는 원본을 변경하지 않고 부호를 바꾼 값을 반환한다.
- Mockito의 `verify(..., never())`로 금액 증감 요청이 없었음을 확인한다. 명시적 요청 테스트는 한 번만 전달되고 추가 호출이 없는지도 검사한다.

## 작성한 테스트

다음 두 파일에 신규 테스트 7개를 작성했다. H2는 테스트마다 고유 이름으로 생성·종료하며 실제 PostgreSQL·Kafka·Rust 엔진에 접근하지 않는다.

| 파일·함수 | 시나리오와 기대 결과 | 결과 |
|---|---|---|
| [PlatformBalanceStartupTest](../../src/test/java/dustin/cex/shared/services/PlatformBalanceStartupTest.java) · `readyEventDoesNotSendExistingBalancesAsDeposits` | 양수 available·locked와 locked만 있는 잔액을 두고 준비 이벤트 발행. 엔진 증감 요청 0회, DB 잔액 유지 | 통과 |
| 같은 파일 · `repeatedReadyEventsDoNotReplayDeposits` | 준비 이벤트 3회 발행. 증감 요청 0회, DB 유지 | 통과 |
| 같은 파일 · `restartingPlatformContextDoesNotReplayDeposits` | 같은 DB·엔진 대역을 유지하고 Spring 컨텍스트를 3회 생성·종료. 증감 요청 0회, DB 유지 | 통과 |
| [BalanceSyncServiceTest](../../src/test/java/dustin/cex/shared/services/BalanceSyncServiceTest.java) · `explicitDepositSendsOnePositiveDelta` | 명시적 입금 호출은 같은 양수 금액을 한 번 전달 | 통과 |
| 같은 파일 · `explicitWithdrawalSendsOneNegativeDelta` | 명시적 출금 호출은 부호를 바꾼 금액을 한 번 전달 | 통과 |
| 같은 파일 · `rejectedDeltaPropagatesFailureWithoutRetry` | 엔진 실패 응답을 예외로 전달하고 자동 재시도하지 않음 | 통과 |
| 같은 파일 · `transportFailurePropagatesCauseWithoutRetry` | 통신 예외 원인을 보존하고 자동 재시도하지 않음 | 통과 |

삭제 전 리스너를 적용한 상태에서 같은 시작 테스트 3개가 실제 `syncBalance()` 호출 때문에 실패하는 것을 확인한 뒤 삭제 후 통과를 확인했다. 테스트는 삭제한 클래스를 직접 참조하지 않고 실제 서비스 패키지를 스캔하므로 같은 패키지에 재입금 리스너가 다시 들어오면 탐지한다.

## 실행 명령과 결과

플랫폼 프로젝트 루트에서 Java 21과 프로젝트 Gradle wrapper를 사용했다.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
./gradlew clean test \
  --tests dustin.cex.shared.services.PlatformBalanceStartupTest \
  --tests dustin.cex.shared.services.BalanceSyncServiceTest \
  bootJar --console=plain
```

- 삭제 전 재현: 시작 테스트 3개 실행·3개 실패. 의도한 금융 메서드 호출 검증 실패를 확인했다.
- 삭제 후 clean 실행: 7개 실행·7개 통과·실패 0·제외 0.
- 전체 테스트 소스 컴파일과 `bootJar` 성공. 기존 테스트 전체를 실행한 결과는 아니다.
- 기존 정산 테스트는 전체 `@SpringBootTest` 앱과 봇·Kafka 설정을 포함하므로 별도 실행 격리를 확인하기 전에는 실행하지 않았다. 이번 7개 테스트는 H2·엔진 대역과 잔액 서비스 범위로 격리했다.
- 실행 JAR에서 `BalanceInitializationService` 클래스가 제거됐음을 확인했다.
- 기존 코드의 deprecated API 컴파일 안내와 Mockito/Byte Buddy의 Java agent·클래스 공유 경고가 있었다. 이번에 의존성이나 JVM 경고 설정은 바꾸지 않았다.

실제 프로세스 확인은 공통 루트에서 로컬 보조 스크립트를 실행했다.

```sh
python3 .local/platform_balance_startup_smoke.py
```

이 스크립트·결과는 현재 작업 공간의 로컬 검증 산출물이며 플랫폼 저장소에 포함된 테스트 실행기는 아니다. 절차는 다음과 같다.

1. CEX 전용 로컬 PostgreSQL 컨테이너에 임의 이름의 새 DB 생성.
2. 임시 HTTP 엔진 대역과 임의 루프백 포트의 새 플랫폼 JAR 실행. 봇 수 0, Kafka·외부 시세 연결은 사용하지 않는 루프백 주소로 격리.
3. 첫 시작에서 테이블 생성과 Swagger HTTP 200 확인 후 프로세스 종료.
4. 임시 DB에 101번 고객 USDT available 100.25·locked 30.50, 102번 고객 SOL available 0·locked 2.75 입력.
5. 같은 DB에서 스키마 `validate`로 새 프로세스 실행. Swagger HTTP 200, 시작 중 엔진 HTTP 요청 0회와 DB 잔액 유지 확인.
6. 임시 프로세스·HTTP 대역·DB 정리. 기존 8080 플랫폼·실제 엔진·Kafka·사용자 잔액은 테스트 대상으로 사용하지 않음.

실행 결과는 [로컬 JSON 기록](../../../.local/platform-balance-startup-smoke.json), [수정 전 재현 로그](../../../.local/platform-balance-startup-before.log), [최종 테스트·빌드 로그](../../../.local/platform-balance-startup-after.log)에 있다. 로컬 기록은 작업 공간에만 있으며 저장소 외부 보관·CI 증적을 대신하지 않는다.

## 남은 한계와 다음 검증

- 이번 수정은 과거 실행에서 이미 늘어난 엔진 메모리 잔액을 역산·정정하지 않는다. 근거 없는 차감이나 DB 덮어쓰기는 하지 않았다.
- 이 사이클 당시 엔진은 `available + locked`를 available로 합치고 locked를 0으로 읽었다. 후속 [엔진 시작 검증](../../../Cex-Engine/docs/decisions/002-block-incomplete-startup-state.md)에서 이 동작을 제거하고 미종결 주문·예약금이 있으면 시작을 거부하도록 변경했다. 자동 복구는 아직 미완료다.
- 플랫폼이 실행 중인 상태에서 엔진을 재시작할 때의 상태 경계·업무 접수 제어도 아직 해결하지 않았다.
- 남아 있는 delta API에는 거래 ID에 의한 멱등성·권한·영속 처리 보장이 충분하지 않다. 실제 고객 입출금 API의 완료 형태로 사용하지 않는다.
- 봇을 다시 켜면 봇 초기화 자체가 자산을 주입하는 경로는 남아 있다. 봇 비활성화 조건을 유지하며 실제 운영 차단은 후속 작업으로 검증한다.
- 자동화 테스트는 잔액 서비스와 Spring 컨텍스트 경계, 실행 확인은 격리된 실제 플랫폼과 HTTP 대역을 검증했다. 실제 Rust 매칭·Kafka 체결·전체 장애 복구·운영 배포 검증은 포함하지 않는다.
- 실행 중인 기존 8080 플랫폼은 이번 검증으로 교체하지 않았다. 빌드 산출물과 실행 중인 버전을 구분한다.

## 관련 커밋과 후속 계획

구현·테스트 커밋은 `fc33046`다. 문서와 목차는 별도 문서 커밋으로 기록한다. 후속 엔진 초기 상태 검증은 엔진 저장소의 `c7d89c1`에서 구현했다. 남은 영속 접수·체결 확정과 복구 작업은 [정합성과 장애 복구 계획](../../../docs/roadmap/reliability.md)을 따른다.
