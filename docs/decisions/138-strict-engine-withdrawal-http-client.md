# 출금 원본과 예약·해제·완료 단계를 함께 확인하는 플랫폼 클라이언트

엔진137의 출금 예약·해제·완료를 플랫폼에서 정확한 원본/동작/응답 코드로 확인한다. 통신 확인을 금융 반환·실제 지급 증명으로 바꾸지 않고, 응답 유실·시간 초과·다른 단계 응답은 결과 불명으로 보존한다. 공통 사이클138이며 플랫폼 출금 원본/원장/소비를 연결하기 전의 실제 통신 경계다.

## 문제와 기존 동작

137은 엔진의 출금별 예약 생명주기·최초 금융 거절 이벤트를 만들었다. 플랫폼에는 이를 호출할 정확한 원본·단계별 클라이언트가 없었다. 단순2xx나 같은 출금 UUID만 확인하면 release 성공을 reserve 성공으로 읽을 수 있고, 일반409를 모두 반환 근거로 사용하면 이미 완료한 자산을 다시 반환할 수 있다.

기존 입금 클라이언트의 제한된 본문 수신·명시적 주소/키·전역 프록시/리다이렉트 차단·불명 계약을 참고한다. 입금의2필드 응답과 출금의3필드/세 단계 의미를 억지로 공용 설정 프레임워크에 합치지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST 확인한 [Coinbase holds API](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/accounts/get-single-account-hold)의 주문/출금 예약과 완료 구분은 [엔진023](../../../Cex-Engine/docs/decisions/023-withdrawal-reservations.md)에 적용했다. 공개 API 이외의 내부 구현을 추측하지 않는다.

[JDK21 HttpClient.Builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)의 NO_PROXY·리다이렉트 정책·연결 대기 시간과 [요청 timeout](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))을 확인했다. 연결 대기 제한만으로 본문 전체의 기한을 설명하지 않고 최종 바이트 완료의 future에도 기한을 둔다. 이미 있는 LimitedResponseBody를 재사용해 본문을 무제한 모으지 않는다.

설치된 Jackson3.1.8 타입을 javap로 확인해 새 코드에는 isString/stringValue를 사용했다. 문자열로 강제 변환하는 asString을 타입 검사 대신 쓰지 않는다. 기존 ExactAmount·JDK HttpClient·Jackson·Awaitility/JUnit을 사용하고 의존성·버전/체크섬 파일은 변경하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 고정 UUID/고객/자산/금액/동작의 Command | 단계와 경제적 원본을 함께 재전송 | 플랫폼이 원본을 먼저 저장하고 같은 값으로 재확인해야 한다. 임의 새 UUID 재시도 없음 |
| 세 단계 각각의 성공 Outcome | 다른 단계의 ACK를 현재 성공으로 사용하지 않음 | 성공 코드가 늘고 양쪽 계약 변경을 함께 해야 함. 일반 boolean/2xx 성공 없음 |
| ID/action/code 세 필드와 HTTP 상태 정확 검사 | 다른 원본/단계/모호한 JSON 차단 | 새 응답 필드도 미지로 취급해 함께 갱신 필요. 별도 호환 응답/폴백 없음 |
| 일반409는 통신상 이번 거절만 확인 | 이미 완료한 지급의 반환 오인 방지 | 금융 소비·원장 근거가 올 때까지 예약을 유지해야 한다. HTTP만으로 금융 상태를 정하지 않음 |
| 단일 장수 HttpClient·외부 TX 거부 | 요청별 실행기 생성/DB 잠금 중 네트워크 대기 방지 | 클라이언트 수명 관리와 호출자의 짧은 TX/영속 출력 연결 필요 |
| 직접 연결/리다이렉트 금지 | JVM 전역 프록시나 다른 URL에 서비스 키 전달 방지 | 프록시가 필요한 배포는 별도 명시적 설계가 필요. 현재 내부/루프백 설계를 유지 |
|4096바이트/엄격 JSON/UTF-8/전체 기한 | 잘못된 응답의 메모리·대기 제한 | 늦은 정상 응답도 불명이 된다. 기한이 엔진 계산/커밋을 취소하는 보장은 아님 |
| 자동 재전송 없음 | 수신 불명에서 새로운 금융 원본 생성 방지 | 호출 업무의 영속 일정/동일 원본 재확인으로 이어야 함. 메모리 재시도 루프를 숨기지 않음 |

## 구현과 계약

명령은 withdrawal_id, user_id, asset, amount 문자열과 action=reserve/release/complete다. 원본 필드는 디버그 toString에 복제하지 않는다. 고객 공개 Controller/출금 활성화·자동 송신·DB 금융 변경은 추가하지 않았다.

| 응답 | 필수 본문 결합 | 결과 |
| --- | --- | --- |
|200 | 같은 ID/action + 해당 단계 DURABLY_RESERVED/RELEASED/COMPLETED | 정확한 단계 확인 |
|409 | 같은 ID/action + DURABLY_REJECTED | 이번 명령 거절. 최초 금융 거절/과거 미실행/반환 권한을 뜻하지 않음 |
|503 | 같은 ID/action + NOT_ACCEPTED | 이번 전송 미접수. 과거 같은 원본의 실행 여부를 부정하지 않음 |
|그 밖/손상/다른 단계/타입/중복/추가 필드 | 확인 불가 | OUTCOME_UNKNOWN |

접속 실패·중간 본문 정지·시간 초과·인터럽트도 불명이다. 인터럽트 플래그는 유지하고 미완료 요청은 취소하지만 엔진 측 계산/저널을 되돌리는 기능은 아니다. 입력의 잘못된 ID/금액/동작, 외부 DB TX 진입은 전송 전에 거부한다.

## 실제 코드와 문법

출처: [EngineWithdrawalClient.java](../../src/main/java/dustin/cex/shared/http/EngineWithdrawalClient.java#L24), 현재 소스의 부분 발췌.

```java
    public enum Action {
        RESERVE("reserve",Outcome.DURABLY_RESERVED),RELEASE("release",Outcome.DURABLY_RELEASED),COMPLETE("complete",Outcome.DURABLY_COMPLETED);
        private final String wire;private final Outcome confirmed;
        Action(String wire,Outcome confirmed){this.wire=wire;this.confirmed=confirmed;}
        public String wire(){return wire;}
    }
```

enum은 동작 문자열과 그 단계의 성공 타입을 함께 고정한다. TypeScript의 문자열 합 타입과 다르게 실제 Java 런타임 값이며, 호출자가 임의의 성공 코드 문자열을 주입하지 않는다.

출처: [EngineWithdrawalClient.java](../../src/main/java/dustin/cex/shared/http/EngineWithdrawalClient.java#L30), 현재 소스의 부분 발췌.

```java
    public record Command(UUID withdrawalId,long customerId,String asset,BigDecimal amount,Action action){
        // 디버그 출력에 금융 원본을 자동으로 복제하지 않는다. 전용 원장·업무 감사의 보존과 구분한다.
        @Override public String toString(){return "EngineWithdrawalCommand[출금 원본 생략]";}
    }
```

record는 필드와 접근자를 만든다. UUID/String/BigDecimal/enum을 고정 원본으로 전달한다. toString을 명시해 자동 디버그 문자열에 원본을 복제하지 않는다. 전용 업무 감사·원장 보존과 구분한다.

출처: [EngineWithdrawalClient.java](../../src/main/java/dustin/cex/shared/http/EngineWithdrawalClient.java#L64), 현재 소스의 부분 발췌.

```java
            if(receipt==null||!receipt.isObject()||receipt.size()!=3||!receipt.path("code").isString()||!receipt.path("withdrawal_id").isString()||!command.withdrawalId().toString().equals(receipt.path("withdrawal_id").stringValue())||!receipt.path("action").isString()||!command.action().wire().equals(receipt.path("action").stringValue()))return Outcome.OUTCOME_UNKNOWN;
            // 상태/원본/동작/단계별 코드를 함께 검사한다. 같은 출금의 다른 단계 성공도 현재 단계의 성공이 아니다.
            return switch(response.statusCode()){
                case 200 -> command.action().confirmed.name().equals(receipt.path("code").stringValue())?command.action().confirmed:Outcome.OUTCOME_UNKNOWN;
                case 409 -> "DURABLY_REJECTED".equals(receipt.path("code").stringValue())?Outcome.DURABLY_REJECTED:Outcome.OUTCOME_UNKNOWN;
                case 503 -> "NOT_ACCEPTED".equals(receipt.path("code").stringValue())?Outcome.NOT_ACCEPTED:Outcome.OUTCOME_UNKNOWN;
                default -> Outcome.OUTCOME_UNKNOWN;
            };
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Outcome.OUTCOME_UNKNOWN;}
```

isString으로 실제 JSON 타입을 먼저 검사한 뒤 stringValue를 읽는다. 단순 문자열 변환이나2xx 검사가 아니다. 같은 UUID여도 다른 단계의 응답은 불명이며,409가 확인돼도 이 함수는 잔액을 변경하지 않는다.

출처: [EngineWithdrawalClient.java](../../src/main/java/dustin/cex/shared/http/EngineWithdrawalClient.java#L60), 현재 소스의 부분 발췌.

```java
            pending=http.sendAsync(request,info->new LimitedResponseBody(4096));var response=pending.get(timeout,TimeUnit.MILLISECONDS);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))return Outcome.OUTCOME_UNKNOWN;
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            var receipt=json.readTree(raw);
```

future는 LimitedResponseBody가 최종 바이트 수신을 끝냈을 때 완료된다. get의 기한이 연결 이후의 본문 정지도 제한한다. 기존 출력 워커에서 이 함수를 호출해야 하며 DB TX를 유지하면 함수 진입에서 거부한다.

출처: [BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java#L205), 현재 소스의 부분 발췌.

```java
            loseWithdrawalResponse.set(true);assertEquals(dustin.cex.shared.http.EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN,actual.execute(reserve));assertEquals(2,actualAudit.page(0,null,null).path("events").size());
            engineProcess.destroyForcibly();assertTrue(engineProcess.waitFor(10,TimeUnit.SECONDS));startEngine();loseWithdrawalResponse.set(false);
            // 같은 원본으로 명시적으로 재확인한다. 불명을 새 출금 UUID로 바꾸지 않는다.
            await(()->actual.execute(reserve)==dustin.cex.shared.http.EngineWithdrawalClient.Outcome.DURABLY_RESERVED,10);assertEquals(2,actualAudit.page(0,null,null).path("events").size());
            var release=new dustin.cex.shared.http.EngineWithdrawalClient.Command(released,customer.getId(),"BTC",new BigDecimal("0.01"),dustin.cex.shared.http.EngineWithdrawalClient.Action.RELEASE);
```

실제 Rust가 예약을 저널에 확정한 뒤 HTTP 응답만 유실시킨다. 같은 WAL로 강제 종료/복원하고 같은 Command를 명시 재확인해 새로운 예약/금융 출력이 생기지 않았는지 검사한다.

## 작성한 테스트

| 테스트 | 시나리오·기대 결과 |
| --- | --- |
| EngineWithdrawalClientTest35개 | 소유 실제 HTTP 서버로 원본/문자열 금액/서비스 키·세 단계 각각·다른 ID/방향/코드·거절/미접수의 방향 결합·JSON 타입/필수/추가/중복/후행/UTF-8·본문/전체 기한·전역 프록시/리다이렉트·접속 실패/인터럽트·금융 TX 거부·금액 범위/미지 동작을 검증 |
| EngineDepositClientTest28개 | 기존 입금 원본/정확한 응답·외부 전송 경계 회귀 |
| BitcoinCreditWireIntegrationTest.withdrawalClientConfirmsRealStagesAfterLostResponseAndRecoveryWithoutSettlingFromHttp | 실제 Java 클라이언트→소유 TCP 프록시→Rust/WAL/Kafka 연결에서 최초 예약200을504로 유실→강제 복원/같은 원본 재확인→해제/완료·늦은 다른 단계 거절·최초 부족 금융 거절·중복 결과 없음 |
| 기존 실제 연결6개 | 입금/중복·응답 유실/WAL 복원·필수 감사 실패/수신 복구·복구 근거·고객 취소 응답 유실/금융 반환·실제 위험의 시스템 취소 |

실제 출금 클라이언트 시험은 먼저 실제 입금을 금융 소비해 엔진을 채운 뒤 출금 단계 동안 소비자를 중지한다. 새 출금 원본/수신을 아직 연결하지 않았기 때문이다. HTTP 확인 전후 플랫폼 원장 항목 수·수신 키·가용/잠금이 그대로임을 확인한다. 이것을 플랫폼 출금 정산 완료나 실제 BTC 서명/전송 시험으로 표시하지 않는다. 노드·공급사·수수료 정책은 합성이고 Rust/TCP/WAL/Kafka는 실제다.

## 실행 명령과 실제 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests dustin.cex.shared.http.EngineWithdrawalClientTest --tests dustin.cex.shared.http.EngineDepositClientTest bootJar operatorAdminJar --console=plain
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
python3 scripts/verify_bitcoin_credit_wire.py
```

macOS/JDK21/Gradle8.14.6·소유 PostgreSQL16/Kafka·엔진023/공통137 소스의 cargo --locked --offline 빌드. 최종 대표 묶음은 클라이언트63+금융217+보안41+실제 연결7, **고유328개/328회 통과**, 실패·오류·제외0이다. 문자열 타입 API 정리 뒤 해당63개를 다시 통과했고 마지막 실행을 대표 XML로 보존했다. 개발 중 중복 실행을 고유 합계에 추가하지 않는다. 이전 사이클의 테스트 수를 이번 수치에 합산하지 않았다.

.local/cycle138-proof에 실제 로그/JSON/JUnit XML을 보존했다. 실제 연결은 함수별 새 DB/브로커/Rust를 사용하며 소유 컨테이너/프로세스를 정리했다. 기존5개 서비스·봇0·공유5432/9092 미접속을 유지했다. 두 JAR의 새 클라이언트 포함/시험 제외·Python 구문·실제 코드 발췌와 문서 링크·git diff --check를 확인했다. 기존 폐기 예정 API/unchecked/JDK 계측 경고는 남는다. 신규 의존성/배포·외부 전송은 없다.

## 다음 경계

출금 원본·영속 일정·실제 금융 예약/수신·독립 대사와 고객/운영 인가를 이어간다. 네 종류 엔진 금융 결과를 원본에 결합하고 최초 금융 거절/해제에서만 원래 예약을 반환해야 한다. 반환을 새 명령 ID나 HTTP409에 결합하지 않는다. 출금 전송 이력 표가 생길 때 엔진 최초 기동의 이력 검사도 함께 추가한다.

다른 출처 입금/소액/개인지갑·반환·손실/정정·출금 심사/서명·전체 백엔드 검토·프론트·마지막 ETH는 미완료다. 이 클라이언트를 고객 출금 완료로 표시하지 않는다.

## 작성한 테스트 코드


[EngineWithdrawalClientTest.java](../../src/test/java/dustin/cex/shared/http/EngineWithdrawalClientTest.java)

- `sendsExactStableFinancialContentAndServiceIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `financialInputIsNotCopiedIntoDebugOutput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `explicitRetryKeepsTheSameIdentityAndBody`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `onlyExactDurableRejectionIsAConfirmedRejection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `onlyExplicitThisAttemptNotAcceptedHasThatMeaning`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `explicitUnknownDoesNotBecomeARejection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mismatchedStatusAndCodeNeverConfirmsAnything`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherWithdrawalsReplyCannotConfirmThisIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingIdentityOrCodeIsUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownAndNullCodesAreNotCompatibilityFallbacks`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateFieldsAndTrailingDocumentsAreUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unexpectedFieldsAndWrongJsonTypesAreUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedUtf8IsNotSilentlyReplaced`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nonJsonContentTypeCannotConfirmFinancialState`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `declaredJsonWithCharsetIsSupported`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oversizedResponseIsCancelledAndRemainsUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `globalProxyCannotReceiveTheInternalServiceKey`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `redirectDoesNotForwardTheServiceSecret`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `deadlineIncludesWaitingForResponseHeaders`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `deadlineIncludesTheIncompleteBody`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `connectionFailureDoesNotProveNoPreviousExecution`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `interruptedCallerRetainsTheInterruptFlag`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseTransactionCannotHoldLocksAcrossTransport`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidIdentityAndAssetsNeverReachTheWire`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `zeroNegativePrecisionAndRangeFailuresNeverReachTheWire`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `largeValidIntegerIsSerializedWithoutExponent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `credentialsQueryFragmentAndPathInEndpointAreRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidTimeoutAndSecretFailAtConstruction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherStageSuccessCannotConfirmTheCurrentStage`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingNullOrNonTextualActionIsUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nullActionCannotReachTheWire`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherStageRejectionOrNotAcceptedCannotConfirmThisCommand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java)

- `actualHttpAcknowledgementWaitsForKafkaAndDuplicateDeliveryDoesNotCreditTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostHttpSuccessAndForcedEngineRestartPreserveTheSameDepositAndEvent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requiredAuditFailureStopsTheRealConsumerAndReplaysWithoutLosingFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realJournalReconciliationBuildsRecoveryEvidenceWithoutClearingTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationResponseLossAndEngineRestartPreserveReservationUntilTheRealKafkaResult`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unresolvedChainRiskReachesTheRealEngineAndOnlyKafkaReleasesTheReservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `withdrawalClientConfirmsRealStagesAfterLostResponseAndRecoveryWithoutSettlingFromHttp`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 d67615e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d67615ed34cb1df8ffb7eb6dbbab2eb07ceea9f9)
- [격리 실행 증적](../reference/138-strict-engine-withdrawal-http-client-verification-2026-10-26.json)
