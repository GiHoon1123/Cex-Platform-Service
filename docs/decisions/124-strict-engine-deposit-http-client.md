# 입금 원본과 엔진 HTTP 응답을 결합하고 불확실한 결과를 보존하기

플랫폼이 엔진021 입금 API에 안정된 원본을 정확히 보내고, 응답의 입금 ID·상태 코드·기계 코드를 함께 검증한다. 네트워크/본문/시간 초과를 미반영이나 금융 거절로 추측하지 않는다. 아직 보류 자금 해제·반입 의도 저장·자동 전송·Kafka 금융 반영을 실행하는 단계는 아니다.

후속 [127 영속 전달과 확정 이벤트 반영](127-durable-bitcoin-credit-dispatch-and-settlement.md)은 최초 전송 권한을 재검증하고 같은 원본의 전송/재확인·Kafka 원장/잔액·고객 완료 조회·독립 대사를 연결한다. 아래 기록의 수치는 당시 범위이며 자동 처리 워커/전체 브로커 연동은 별도다.

## 문제와 기존 동작

[엔진021](../../../Cex-Engine/docs/decisions/021-internal-deposit-credit-http.md)은 기존 단일 소유 코어·저널/출력 워커를 바꾸지 않고 입금 명령 HTTP 경계를 연결했다. 플랫폼에는 이 계약을 호출하는 클라이언트가 없었다. 기존 EngineHttpClient의 주문 접수/취소 메서드를 입금에 재사용하면 입금 원본·명시적 결과 타입·전체 응답 크기/대기 제한을 따로 검증할 수 없다.

HTTP200만 보거나 실패 예외를 미반영으로 처리하면 다른 입금의 응답·손상된 성공 본문·실제 커밋 뒤 응답 유실에서 자산이 중복될 수 있다. 서버는 성공했지만 호출자는 확인하지 못한 상태를 독립된 OUTCOME_UNKNOWN으로 유지해야 한다.

## 기존 제품과 공식 자료의 접근

엔진021의 [AWS 안정 식별자/멱등 재시도](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) 패턴을 이어간다. 호출자는 같은 입금 UUID·고객·자산·정확한 금액을 보존하며 응답 유실 뒤 새 ID로 우회하지 않는다.

2026-10-26 [JDK21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)와 [HttpRequest.Builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html)를 확인했다. 수명이 긴 클라이언트의 연결 재사용·명시적 리다이렉트 정책·비동기 응답·종료 API를 사용한다. 응답 본문 처리 방식에 따라 완료 시점이 다르므로 기존 LimitedResponseBody의 바이트 배열 수신 완료를 기다리며 Future 대기 제한도 적용한다.

설치된 JDK21·Jackson3 및 [BitcoinKrwPriceClient](../../src/main/java/dustin/cex/domains/valuation/BitcoinKrwPriceClient.java)의 실제 제한 수신/엄격 UTF-8/전체 대기 패턴과 [ExactAmount](../../src/main/java/dustin/cex/shared/model/ExactAmount.java)를 먼저 확인했다. 새 라이브러리·공용 전송 프레임워크를 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 입금 전용 작은 클라이언트 | 기존 주문 전송의 오류/응답 계약을 바꾸지 않고 금융 결과를 명시 | 별도 연결 풀이 생기지만 컴포넌트 수명 동안 재사용한다. 기존 RestTemplate에 정책 플래그를 늘리는 대안을 택하지 않음 |
| 기존 URL/timeout/서비스 키 재사용 | 설정을 새로 늘리지 않고 같은 내부 엔진 경계를 사용 | 주문과 입금 대기 시간이 공유된다. 현재 요구에 별도 설정은 필요하지 않음 |
| HTTP·기계 코드·정확한 입금 ID 동시 검사 | 다른 원본/손상 성공·단순2xx를 금융 확정으로 오인하지 않음 | 변경된 응답 스키마를 자동 수용하지 않는다. 계약 변경은 양쪽 코드/시험을 함께 바꿔야 함 |
| 애매한 상태는 모두 결과 불명 | 확정됐을 수 있는 자산을 재지급/잘못 해제하지 않음 | 일시적 오류도 후속 재확인이 필요하고 세부 원인 분류·운영 지표는 후속 워커가 책임져야 함 |
| 전역 프록시 상속/리다이렉트 금지·응답4KiB·엄격 파서 | 서비스 키 전파·과도한 메모리·중복/손상 JSON 차단 | 임의 프록시 리다이렉트/추가 필드는 거부한다. 키/개인정보를 원문 오류 로그에 남기지 않음 |
| DB 트랜잭션 안 전송 거부 | 네트워크를 기다리는 동안 금융 잠금을 붙잡지 않음 | 호출자는 먼저 안정된 의도를 저장하고 TX 밖에서 전송해야 함. 이 클라이언트가 의도 영속화를 대신하지 않음 |

[JDK21 NO_PROXY 공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#NO_PROXY)와 설치된 타입을 추가 확인했다. 내부 명령은 JVM 전역 프록시를 상속하지 않고 설정한 엔진 주소로 직접 연결한다. 기업 프록시를 자동 사용하는 동작은 제공하지 않는다. 엔진 주소 자체가 사설 로드밸런서인 구성과 이 전역 프록시 상속은 구분한다. 새 프록시 옵션·선택기 구현을 만들지 않았다.

## 금융 결과 계약

Credit의 UUID는 nil이 아니고 고객은 양수 long, 자산은 대문자 ASCII/숫자128자 이내여야 한다. ExactAmount로 양수·NUMERIC(30,9)·Rust Decimal 계수 범위를 확인한 뒤 toPlainString으로 보낸다. BigDecimal의 지수/불필요한 끝0을 JSON 숫자나 이진 실수로 보내지 않는다. 디버그 toString은 금융 원본을 생략하며 실제 업무/원장 보존과 구분한다.

| 응답 | 클라이언트 결과 |
| --- | --- |
| 200 + DURABLY_CREDITED + 같은 입금 ID | DURABLY_CREDITED |
| 409 + DURABLY_REJECTED + 같은 입금 ID | DURABLY_REJECTED |
| 503 + NOT_ACCEPTED + 같은 입금 ID | NOT_ACCEPTED: 이번 접수만 부정, 과거 같은 원본의 실행 여부는 부정하지 않음 |
| 504/기타 상태, 잘못된 코드/ID/JSON/UTF-8/크기·응답 유실·시간 초과 | OUTCOME_UNKNOWN |

직접 검증하지 못한400/401/503도 이전 입금이 실행되지 않았다는 증거로 사용하지 않는다. 입력 검증 실패는 이번 전송 전에 예외로 거부하지만 그것도 과거 실행 여부의 증명은 아니다. 중단 예외는 스레드 중단 표시를 복구하고 결과 불명으로 반환한다.

호출 메서드는 플랫폼의 호출 스레드에서 최대 응답 대기를 수행한다. **Java의 Future.get은 그 스레드를 기다리게 한다. TypeScript의 await처럼 이벤트 루프를 자동 양보하는 의미로 설명하지 않는다.** 향후 수명이 긴 입금 전송 워커가 TX 밖에서 호출해야 하며 엔진 체결 스레드에 이 코드를 넣지 않는다. 클라이언트 안에 자동 재시도 루프나 새 UUID 생성·잔액 변경 경로는 없다. JDK 전송의 물리적 패킷/연결 시도 횟수 전체를 금융 실행 횟수와 같다고 주장하지 않는다.

## 실제 코드와 문법

출처: [EngineDepositClient.java](../../src/main/java/dustin/cex/shared/http/EngineDepositClient.java#L47), 현재 소스의 부분 발췌.

```java
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 명령은 DB 트랜잭션 밖에서 전송하세요");
        if(credit==null||credit.depositId()==null||credit.depositId().equals(new UUID(0,0))||credit.customerId()<=0||credit.asset()==null||!credit.asset().matches("[A-Z0-9]{1,128}"))throw new IllegalArgumentException("안정 입금 원본을 확인하세요");
        BigDecimal amount=ExactAmount.requirePositive(credit.amount(),"입금 금액");
        byte[] body=json.writeValueAsBytes(Map.of("deposit_id",credit.depositId(),"user_id",credit.customerId(),"asset",credit.asset(),"amount",amount.toPlainString()));
```

호출 스레드의 실제 Spring 트랜잭션 표시를 확인하고 정확한 금액을 검증한다. 다른 스레드의 잠금을 전역으로 추적하는 장치는 아니다. record와 BigDecimal은 고정된 요청 값을 표현하며 아직 원장에 저장됐다는 증명은 아니다.

출처: [EngineDepositClient.java](../../src/main/java/dustin/cex/shared/http/EngineDepositClient.java#L54), 현재 소스의 부분 발췌.

```java
            pending=http.sendAsync(request,info->new LimitedResponseBody(4096));var response=pending.get(timeout,TimeUnit.MILLISECONDS);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))return Outcome.OUTCOME_UNKNOWN;
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            var receipt=json.readTree(raw);
```

기존 바이트 제한 수신기를 재사용한다. Future는 본문 수신까지 완료돼야 결과를 내며 get의 제한이 끝나면 취소를 요청한다. 네트워크 취소는 엔진 실행 취소가 아니므로 결과 불명이다.

출처: [EngineDepositClient.java](../../src/main/java/dustin/cex/shared/http/EngineDepositClient.java#L60), 현재 소스의 부분 발췌.

```java
            return switch(response.statusCode()){
                case 200 -> "DURABLY_CREDITED".equals(receipt.path("code").textValue())?Outcome.DURABLY_CREDITED:Outcome.OUTCOME_UNKNOWN;
                case 409 -> "DURABLY_REJECTED".equals(receipt.path("code").textValue())?Outcome.DURABLY_REJECTED:Outcome.OUTCOME_UNKNOWN;
                case 503 -> "NOT_ACCEPTED".equals(receipt.path("code").textValue())?Outcome.NOT_ACCEPTED:Outcome.OUTCOME_UNKNOWN;
                default -> Outcome.OUTCOME_UNKNOWN;
            };
```

Java switch 식이 현재 필요한 네 가지 결과만 반환한다. 문자열 reason 파싱이나 임의 성공 플래그가 없고 상태/코드가 어긋나면 보수적으로 불명이다. TypeScript의 판별 가능한 합 타입과 비교해 볼 수 있다.

출처: [EngineDepositClient.java](../../src/main/java/dustin/cex/shared/http/EngineDepositClient.java#L66), 현재 소스의 부분 발췌.

```java
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Outcome.OUTCOME_UNKNOWN;}
        catch(Exception uncertain){return Outcome.OUTCOME_UNKNOWN;}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
```

인터럽트는 호출자의 종료/취소 신호다. 그 표시를 복구하면서 금융 상태는 불명으로 유지한다. finally의 취소는 요청 자원을 정리하는 최선 노력이며 서버의 미실행을 보장하지 않는다.

## 작성한 테스트

[EngineDepositClientTest](../../src/test/java/dustin/cex/shared/http/EngineDepositClientTest.java)의 소유 루프백 HTTP 서버로28개를 작성했다. 서버/스레드/클라이언트는 매 시험 종료 때 정리한다. 실제 엔진 저널은 엔진021의 별도 실제 워커 시험으로 검증하며 이 모의 응답을 WAL 증명이라고 표시하지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `sendsExactStableFinancialContentAndServiceIdentity` | 실제 POST 경로/키·UUID/고객/자산·정확한 문자열 금액·호출1회 |
| `financialInputIsNotCopiedIntoDebugOutput` | 기본 디버그 출력에 입금 ID/금액/자산 원문 없음 |
| `explicitRetryKeepsTheSameIdentityAndBody` | 호출자가 다시 호출해도 ID/본문 동일, 새 원본을 생성하지 않음 |
| `onlyExactDurableRejectionIsAConfirmedRejection` | 정확한409 거절만 확정 거절 |
| `onlyExplicitThisAttemptNotAcceptedHasThatMeaning` | 정확한503만 이번 미접수 |
| `explicitUnknownDoesNotBecomeARejection` | 504는 결과 불명 |
| `mismatchedStatusAndCodeNeverConfirmsAnything` | 다양한 상태와 어긋난 코드는 불명 |
| `anotherDepositsReplyCannotConfirmThisIntent` | 다른 입금 ID는 불명 |
| `missingIdentityOrCodeIsUnknown` | 필수 응답 필드 누락은 불명 |
| `unknownAndNullCodesAreNotCompatibilityFallbacks` | 예전 성공 이름/NULL을 성공으로 수용하지 않음 |
| `duplicateFieldsAndTrailingDocumentsAreUnknown` | 중복 키/이어 붙인 JSON 문서는 불명 |
| `unexpectedFieldsAndWrongJsonTypesAreUnknown` | 추가 필드/배열/NULL/다른 타입 불명 |
| `malformedUtf8IsNotSilentlyReplaced` | 손상 UTF-8을 대체 문자로 정상화하지 않음 |
| `nonJsonContentTypeCannotConfirmFinancialState` | text/HTML/jsonp/없는 콘텐츠 유형 불명 |
| `declaredJsonWithCharsetIsSupported` | 정상 JSON 대소문자·charset 선언 사용 가능 |
| `oversizedResponseIsCancelledAndRemainsUnknown` | 4KiB 초과를 취소하고 불명 |
| `redirectDoesNotForwardTheServiceSecret` | 리다이렉트 대상 호출0·서비스 키 전달 없음 |
| `deadlineIncludesWaitingForResponseHeaders` | 응답 헤더 지연도 기한 후 불명·명시 재시도 없음 |
| `deadlineIncludesTheIncompleteBody` | 본문 중간 정지도 기한 후 불명 |
| `connectionFailureDoesNotProveNoPreviousExecution` | 연결 실패도 과거 미실행으로 확정하지 않음 |
| `interruptedCallerRetainsTheInterruptFlag` | 결과 불명과 중단 표시 복구 |
| `databaseTransactionCannotHoldLocksAcrossTransport` | 호출 스레드의 TX 표시가 있으면 전송 전 예외·호출0 |
| `invalidIdentityAndAssetsNeverReachTheWire` | 잘못된 원본/고객/자산 입력의 외부 호출0 |
| `zeroNegativePrecisionAndRangeFailuresNeverReachTheWire` | 0/음수/범위/정밀도·큰 scale 값 거부·호출0 |
| `largeValidIntegerIsSerializedWithoutExponent` | 정확한 큰 정수도 지수 없이 문자열로 전송 |
| `credentialsQueryFragmentAndPathInEndpointAreRejected` | 자격/쿼리/조각/경로/비HTTP/불완전 주소 시작 거부 |
| `invalidTimeoutAndSecretFailAtConstruction` | 무제한/음수 시간·짧은/과도/개행 키 시작 거부 |

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests dustin.cex.shared.http.EngineDepositClientTest --tests dustin.cex.shared.http.EngineHttpClientTest --tests dustin.cex.domains.valuation.BitcoinKrwPriceClientTest bootJar operatorAdminJar --console=plain
python3 scripts/verify_engine_inbox.py
```

2026-10-26 새 클라이언트28개·기존 주문 HTTP17개·기존 가격 클라이언트10개와 금융182개를 실행했다. 주문 HTTP17개가 두 선택에 포함되므로 **고유220개/237회** 통과다. 실패·오류·제외0. 중간26개 이후 디버그 비노출과 전역 프록시 우회 차단을 보강해 최종28개를 다시 실행했다. 중간26/27개 결과는 최종 수에 합산하지 않는다. 금융182개는 기존 설정의 실제 Spring 애플리케이션에서 새 클라이언트 빈 조립·원장/주문/수수료/수신 회귀를 확인했다. 전역 프록시 차단 보강 뒤 금융182개도 다시 실행했다. 마지막 시험 격리 표시는 클라이언트 묶음을 다시 실행해 확인했다.

금융 회귀는 소유 PostgreSQL16·봇0·외부 소비/전송 금지·격리 주소를 사용했고 소유 컨테이너를 정리했다. 클라이언트 시험의28개는 DB/Kafka 없이 소유 HTTP만 사용한다. 각 XML/집계는 `.local/cycle124-proof`에 보존한다. 플랫폼 웹/운영 초기화 CLI JAR 빌드·새 클래스 포함·시험 제외, 현재 소스 발췌/상대 링크·공백 검사를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

## 남은 범위

이 클라이언트에 고객 API·자동 워커를 바로 연결하지 않았다. 현재 고객/체인·출처·위험 근거에 따른 금융 반입 의도를 먼저 영속화해야 한다. 이후 같은 원본으로 재확인하고 확정 Kafka 이벤트를 플랫폼의 영속 수신 키·PENDING/AVAILABLE 원장·잔액에 결합해야 한다. 성공 HTTP와 플랫폼 반영 완료는 구분한다.

원격 실제 엔진과 플랫폼 클라이언트를 함께 띄운 TCP 통합·프로세스 강제 종료/복구·외부 브로커/메인넷·보안 운영 검증은 이번 결과가 아니다. 엔진021의 실제 코어/저널/출력 시험과 이번 HTTP 계약을 분리해서 읽고 후속 전체 연결 시험을 수행해야 한다. Rust/프론트는 이번124에서 변경하지 않았다.

## 관련 커밋과 증적

- [구현 1e78869](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1e7886919a9d32dcc2e37614488bc3c43251dbcb)
- [격리 실행 증적](../reference/124-strict-engine-deposit-http-client-verification-2026-10-26.json)

- [전역 프록시 차단 0d9c5bc](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0d9c5bc5113fd363c499d081be158cf7b3c50852)
