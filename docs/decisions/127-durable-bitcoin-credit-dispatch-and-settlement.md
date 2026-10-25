# 영속 입금 명령을 전달하고 확정 이벤트로 보류 자금을 반영하기

최초 전송 권한을 현재 근거로 확정하고 잠금을 놓은 뒤 같은 입금 원본을 엔진에 전달한다. HTTP 확정과 플랫폼 잔액 확정은 구분하며, 실제 Kafka 입금 결과·수신 키·원장·잔액·감사는 하나의 DB 트랜잭션으로 반영한다. 독자는 응답 유실·중복·순서 역전·부분 실패에서도 무엇이 확정됐는지 설명할 수 있다.

후속 [128 자동 후보/영속 일정/장수 워커](128-automatic-bitcoin-credit-processing.md)는 준비된 정상 VASP 입금의 실제 대사·전달을 자동 연결한다. 아래127의 실행 수치는 당시 범위이며 실제 브로커/전체 프로세스 연결과 다른 출처 해결은 별도다.

## 문제와 기존 동작

[126](126-verified-vasp-bitcoin-credit-intents.md)은 정상 VASP 입금의 불변 의도까지만 기록했다. [엔진021](../../../Cex-Engine/docs/decisions/021-internal-deposit-credit-http.md)과 [플랫폼124](124-strict-engine-deposit-http-client.md)는 HTTP 계약을 제공했지만 실제 금융 의도/시도/잔액에 연결되지 않았다. 기존 금융 소비자는 주문 결과만 받았고 고객 입금 조회는 PENDING까지만 표시했다.

첫 전송 직전에도 현재 조건을 다시 확인해야 한다. 의도 생성 이후 고객·체인·출처·조사 상태가 달라질 수 있기 때문이다. 반면 이미 실행 권한을 확정하고 전송한 명령의 결과가 불명인데 새 입금 ID를 만들거나 원장을 먼저 해제해서는 안 된다. HTTP 성공을 받았어도 플랫폼 DB 반영 전까지 고객 자금 상태는 보류다.

## 기존 제품과 공식 자료의 접근

2026-10-26 [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)와 [동일 식별자의 멱등 재시도](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)를 다시 확인했다. DB의 실행 의도와 원격 실행은 하나의 원자적 커밋이 아니므로 먼저 영속 원본을 남기고 중복을 허용하는 전달과 소비자 중복 방지를 결합한다. 기존 주문 전송/EngineEventInbox 패턴과 설치된 JdbcTemplate·JPA·Jackson·EngineDepositClient를 재사용한다.

[Spring Kafka 오류 처리 참조](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)는 공식 페이지 기준4.1.2였다. 프로젝트 잠금은4.1.1이며 설치된4.1.1 JAR의 CommonContainerStoppingErrorHandler 바이트코드에서 비정상 중지와 KafkaException 전파를 따로 확인했다. GitHub v4.1.1 원문 조회는 실패했으므로 해당 원문을 읽었다고 주장하지 않는다. 기존 단일 금융 리스너·한 파티션·실패 시 중지 설정을 유지한다. 새 의존성이나 별도 Kafka 소비 그룹/정책 프레임워크를 만들지 않는다.

[PostgreSQL16 시각 타입](https://www.postgresql.org/docs/16/datatype-datetime.html)의 timestamp 정밀도는 마이크로초다. 엔진 원본의 나노초를 대사에서 잃지 않도록 이벤트 시각은 검증한 원래 문자열로 보존한다. DB 기록/재시도 시각은 DB 시계의 timestamptz를 계속 사용한다.

## 확정 경계와 트레이드오프

| 단계 | 확정하는 것 | 확정하지 않는 것 |
| --- | --- | --- |
| 126 입금 의도 | 원본 입금 UUID·경제적 내용·당시 근거 | 현재 첫 전송 권한·엔진/플랫폼 반영 |
| 최초 실행 권한+시도 커밋 | 현재 조건을 다시 확인한 금융 명령의 실행 허가 | HTTP 전송 도달·엔진 실행·플랫폼 잔액 |
| 엔진 HTTP 응답 저장 | 이 시도의 명시적 확정/거절/미접수/불명 | 플랫폼 AVAILABLE 반영 |
| Kafka 금융 TX 커밋 | 해당 원본의 엔진 이벤트·원장 이동·조회 잔액·감사 | 체인 절대 최종성·전체 운영/외부 규제 충족 |

**최초 실행 권한과 시도 커밋이 명령 허가의 기준점이다.** 그 이전에는 현재 고객/체인/출처/탐지/대사를 다시 평가한다. 이후 재시도는 새 금융 허가가 아니라 이미 허가한 같은 명령의 결과 확인이다. 고객 제한·고객확인 만료 이후에도 이미 발생한 엔진 결과를 지우지 않고 금융 사실로 기록한다. 제한 고객의 새 주문/출금 권한을 주지는 않는다.

이 선택은 원격 실행 여부를 알 수 없는데 소급 취소했다고 추측하는 문제를 막는다. 감수하는 제약은 전송 중이던 명령을 고객 제한만으로 철회했다고 보장할 수 없다는 점이다. 새 위험/전송 후 재조직의 보류·조사·명시적 정정은 별도 업무이며, 과거 결과 삭제나 원본 ID 변경으로 해결하지 않는다. 권한 기록 이후 모든 재시도에 새 고객 자격을 요구하는 대안은 이미 실행된 사실과 플랫폼 원장을 분리한 채 멈출 수 있어 채택하지 않았다.

| 선택 | 이점 | 비용·대안·제약 |
| --- | --- | --- |
| 의도와 별도 실행 권한 | 첫 전송 전의 현재 근거를 보존 | 근거 스냅샷 저장/검증 읽기 비용. 옛 의도만으로 전송하지 않음 |
| 불변 시도/결과, 시작 순번 | 재시작·늦은 응답을 추적하고 옛 UNKNOWN이 새 확정을 덮지 않음 | 시도마다 DB/감사 기록 비용. 전역 커밋 순서를 주장하지 않으며 같은 고객 시작을 잠금으로 직렬화 |
| 미완료30초·완료 불명/미접수 최소5초 | 연속 호출/중단 뒤 같은 원본 재확인을 제어 | 벽시계 의존. 설정된 HTTP가 더 오래 걸리면 중복 전송이 겹칠 수 있어 엔진 멱등성이 필요. 자동 호출량/일정은 후속 워커 범위 |
| 확정 HTTP 응답 또는 실제 입금 결과가 있으면 재전송 종료 | 브로커 지연 동안 같은 명령을 계속 보내지 않음 | HTTP 확정 뒤 Kafka가 멈추면 PENDING으로 남아 전달 지연을 관측/복구해야 함 |
| 단일 금융 리스너에서 입금 전용 서비스로 위임 | 기존 순서와 수신 TX를 유지하며 관심사 분리 | 같은 그룹/토픽에 별도 입금 리스너를 두면 임의 핸들러로 배정되므로 사용하지 않음 |
| 실제 수신 키와 별도 입금 UUID 고유성 | 같은 이벤트 재발행은 멱등, 다른 이벤트 ID의 같은 입금은 오류 | 충돌 시 금융 소비를 멈추고 원인을 검토해야 함. 조용히 무시하거나 새 입금으로 재처리하지 않음 |
| 원장/조회 잔액/결과의 DB 커밋 제약 | 일부 저장·감사를 빼도 부분 금융 확정 차단 | PG 제약·JPA flush 순서에 결합. 원장과 다른 기존 잔액은 자동 수리하지 않음 |
| 독립 대사의 입금 원본 비교 | 수신 키만 있는 거짓 성공을 방지 | 별도 읽기/저장 비용. 적용 핸들러를 호출해 같은 오류를 재현하지 않음 |

## 구현과 데이터 흐름

BitcoinCreditDispatches는 고객을 잠근 짧은 TX에서 의도 SHA/경제적 원본을 대조하고 최초 실행 권한을 현재 BitcoinCreditBasis로 검증한다. 권한·첫 시도·필수 감사가 함께 커밋돼야 HTTP를 호출한다. 권한만 있고 첫 시도가 없는 커밋도 DB가 거부한다. 긴 네트워크 대기는 TX 밖이다. 결과 저장 실패 시 이미 커밋한 시도는 남으며 미실행으로 간주하지 않는다.

전송 결과는 DURABLY_CREDITED·DURABLY_REJECTED·NOT_ACCEPTED·OUTCOME_UNKNOWN이다. 과거 같은 입금의 실행까지 NOT_ACCEPTED로 부정하지 않는다. 시각/번호를 다시 만들어 새 입금으로 우회하지 않는다. 감사의 DISPATCH_FINISHED/SUCCESS는 **결과 기록 성공**이며 금융 성공은 detail의 실제 OUTCOME과 구분한다.

BitcoinCreditedEvents는 엔진이 보낸 정확한8개 필드·문자열 금액·정수 고객 번호·UUID·이벤트 ID·시각을 검증한다. 중복 필드/후행 JSON/지수형·숫자형 금액/다른 고객·자산·금액을 허용하지 않는다. 실제 실행 권한과 원본을 대조한 뒤 PENDING 감소/AVAILABLE 증가 두 원장 항목과 user_balances.available을 함께 갱신한다. locked와 다른 자산은 이 코드에서 변경하지 않는다. 수탁 계정은 최초 PENDING 반입 때 기록했으므로 다시 증가시키지 않는다.

기존 보류/수탁 계정 보호는 일반 업무에 계속 적용한다. 새 허용은 같은 TX에 실제 bitcoin_credited_deposits와 연결된 정확한 고객 PENDING 감소뿐이다. BTC_DEPOSIT_CREDIT 업무 종류만 사칭한 고아 원장도 커밋할 수 없다. 금융 결과·실행 권한·시도·응답은 수정/삭제/TRUNCATE를 거부한다. DBA/DDL 권한까지 애플리케이션만으로 방어했다고 주장하지 않는다.

고객 fundingStatus=CREDITED는 실제 금융 결과 연결이 있을 때만 표시한다. 의도·실행 권한·HTTP 성공/불명만 있으면 PENDING을 유지한다. 독립 대사는 엔진 저널 원문을 기존 읽기 전용 감사 API로 읽고 원본 입금·실행 권한·기대 원장·수신 지문·실제 금융 결과 연결을 비교한다. 미수신이면 INCOMPLETE, 금액/원문이 다르면 MISMATCH이며 잔액을 수리하지 않는다.

## 실제 코드와 필요한 문법

출처: [BitcoinCreditDispatches.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditDispatches.java#L65), 현재 소스의 부분 발췌.

```java
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_authorizations WHERE receipt_id=?)",Boolean.class,receipt))){
            // 의도 생성 뒤 최초 전송까지 조건이 달라질 수 있다. 실행 권한은 여기서 다시 판단해 원본과 묶어 확정한다.
            var current=policy.require(receipt,customer);if(!current.funds().equals(original))throw new IllegalStateException("현재 입금과 최초 금융 원본이 다릅니다");
            String basis=json.writeValueAsString(current),hash=BitcoinCreditBasis.digest(basis);
            jdbc.update("INSERT INTO bitcoin_credit_authorizations(receipt_id,user_id,asset,amount,policy_id,basis,basis_hash) VALUES(?,?,?,?,?,?,?)",receipt,customer.getId(),original.asset(),new BigDecimal(original.amount()),current.policy(),basis,hash);
            audit.record(null,"BTC_CREDIT_DISPATCH_AUTHORIZED","bitcoin-credit:"+receipt,"SUCCESS","BASIS:"+hash);
        }
```

최초 실행 권한이 없을 때만 현재 정책을 다시 평가한다. Java record의 Funds.equals로 경제적 원본 전체를 비교하고 시점별 근거만 새로 보존한다. 같은 원본을 재확인할 때 다른 고객/금액을 끼워 넣지 않는다.

출처: [BitcoinCreditDispatches.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditDispatches.java#L37), 현재 소스의 부분 발췌.

```java
        var f=claim.funds();EngineDepositClient.Outcome outcome;
        try{outcome=engine.credit(new EngineDepositClient.Credit(f.depositId(),f.customerId(),f.asset(),new BigDecimal(f.amount())));if(outcome==null)outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}
        catch(RuntimeException uncertain){outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}
        var finished=outcome;
```

이 코드는 앞선 TransactionTemplate 콜백이 끝나 커밋된 뒤 실행된다. TypeScript에서 HTTP await를 DB TX 콜백 안에 넣으면 잠금이 저절로 해제되지 않는 것과 같다. JDK 클라이언트는 이 호출 스레드를 기다리게 하지만 엔진 매칭 코어나 고객 DB 잠금을 붙잡지 않는다.

출처: [KafkaOrderEventConsumer.java](../../src/main/java/dustin/cex/shared/kafka/KafkaOrderEventConsumer.java#L61), 현재 소스의 부분 발췌.

```java
        if (!engineEventInbox.claim(event.path("event_id").asText(), event.path("engine_id").asText(), message)) return;
        switch (type) {
            case "deposit_credited" -> deposits.apply(message);
            case "trade_executed" -> trade(event);
```

수신 키 확보와 실제 입금 서비스 호출은 기존 @Transactional 리스너의 같은 TX다. 같은 원문 이벤트를 다시 받으면 금융 코드를 반복하지 않는다. 처리 중 예외가 나면 수신 키도 롤백돼 정상 재수신할 수 있다.

출처: [BitcoinCreditedEvents.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditedEvents.java#L46), 현재 소스의 부분 발췌.

```java
        var balance=balances.findByUserIdAndMintAddressForUpdate(owner,asset).orElseGet(()->UserBalance.builder().userId(owner).mintAddress(asset).build());
        balance.setAvailable(ExactAmount.requireNonNegative(balance.getAvailable().add(amount),"반영 후 사용 가능 잔액"));balances.saveAndFlush(balance);
        String operation="engine:"+eventId;ledger.record(operation,"BTC_DEPOSIT_CREDIT",List.of(p(owner,"PENDING",asset,amount.negate()),p(owner,"AVAILABLE",asset,amount)));
        // 엔진 시각의 나노초를 DB timestamp의 마이크로초로 반올림하지 않는다. 원래 문자열을 보존해 독립 대사도 같은 바이트 의미를 확인한다.
```

JPA의 saveAndFlush는 잔액 SQL을 같은 TX에서 실행하게 하며 독립 커밋이 아니다. 이어지는 JDBC 원장과 금융 결과가 실패하면 잔액도 함께 롤백한다. BigDecimal과 기존 ExactAmount로 정확성을 검사하며 이진 실수로 바꾸지 않는다.

출처: [credit-execution-schema.sql](../../src/main/resources/bitcoin/credit-execution-schema.sql#L125), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER bitcoin_credited_deposit_commit_check AFTER INSERT ON bitcoin_credited_deposits DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credited_deposit_commit();
DROP TRIGGER IF EXISTS bitcoin_credited_deposits_immutable ON bitcoin_credited_deposits;
CREATE TRIGGER bitcoin_credited_deposits_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credited_deposits FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
```

지연 제약은 결과 연결/정확한 원장 두 항목/잔액/필수 감사를 TX 끝에 검사한다. 단순 합계0만 확인하는 것보다 강한 업무 원본 결합이다. 다른 이벤트 ID로 같은 입금을 다시 받는 경우는 PK와 애플리케이션 검사로 오류를 낸다.

## 작성한 테스트

[BitcoinCreditExecutionIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditExecutionIntegrationTest.java)는 메서드마다 새 PostgreSQL DB를 사용한다. [BitcoinCreditTestSupport](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditTestSupport.java)로 기존126의 실제 CDD 암호화·원장·출처 증빙·탐지·노드 대사 준비를 분리했다. 추상 support에는 @Test가 없으며 기존35개를 하위 클래스에 복제하지 않는다. 새 금융 테스트의 사후 조건은 실제 보류/사용 가능 원장 합계와 조회 잔액을 검증한다. 의도 테스트의 '잔액 증가 없음' 조건은 그대로 유지한다.

외부 공급사·엔진 HTTP·감사 조회 원문 공급은 대역이다. 금융 수신 진입점·JPA/JDBC·PG 제약·필수 감사·독립 대사 계산은 실제 코드다. 실제 브로커를 통과한 소비/오프셋·Rust와의 전체 프로세스 연동은 이번 새37개 범위가 아니다.

| 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `httpSuccessWaitsForTheExactKafkaEventAndCreditsOnlyOnce` | HTTP 성공만으로 해제하지 않으며 정확한 Kafka 재발행은1회만 반영·실제 결과 뒤 CREDITED |
| `firstTransmissionRechecksTheCurrentlyDisabledCustomer` | 첫 전송 때 비활성 고객 재검증·권한/시도/전송0 |
| `firstTransmissionRechecksTheCurrentChain` | 첫 전송 때 현재 체인 재검증 |
| `firstTransmissionCannotBorrowANewUnverifiedDeclaration` | 새 미검증 선언은 옛 의도로 전송 불가 |
| `executionAuthorizationCapturesFreshEvidenceWithoutChangingTheOriginalFunds` | 실행 권한은 새 근거 시각을 보존하고 Funds는 같음 |
| `durableRejectionNeverReleasesMoneyOrCreatesAnotherAttempt` | 영속 거절은 보류 유지·재전송 없음·모순된 성공 이벤트도 차단 |
| `notAcceptedKeepsPendingAndRespectsTheRetryWindow` | 이번 미접수는 보류 유지·재시도 대기 준수 |
| `unknownOutcomeRetriesTheSameEconomicIdentity` | 불명 뒤 정확히 같은 ID/고객/자산/금액 재전송 |
| `alreadyAuthorizedUnknownExecutionIsCompletedAfterRestrictionWithoutGrantingNewOrders` | 이미 허가한 불명 명령은 제한 뒤 결과 확인하되 새 주문은403 |
| `unexpectedTransportExceptionRemainsUnknown` | 전송 예외를 불명으로 저장 |
| `nullTransportResultCannotBecomeSuccess` | null 전송 결과도 불명으로 저장 |
| `authorizationAuditFailurePreventsAllTransport` | 권한 감사 예외 시 권한/시도/전송 전체 없음 |
| `silentlyOmittedAuthorizationAuditCannotCommitOrSend` | 권한 감사 조용한 누락은 커밋/전송 차단 |
| `silentlyOmittedAttemptAuditCannotCommitOrSend` | 시작 감사 조용한 누락도 커밋/전송 차단 |
| `missingResponseAuditLeavesAnUnfinishedAttemptAndKafkaCanStillCompleteIt` | 응답 감사 누락은 시도를 보존하고 후속 Kafka로 금융 확정 가능 |
| `kafkaCanConfirmBeforeTheHttpResponseArrives` | HTTP 응답보다 Kafka가 먼저 와도1회 반영 |
| `transportDoesNotHoldTheCustomerDatabaseLock` | HTTP 대기 중 고객 UPDATE 가능·동시 즉시 재전송은 대기 |
| `lateUnknownResultCannotUndoANewerDurableAcknowledgement` | 미완료30초 후 새 확정 응답을 늦은 옛 UNKNOWN이 덮지 않음 |
| `eventWithoutExecutionAuthorityRollsBackItsInboxClaim` | 실행 권한 없는 이벤트는 수신 키까지 롤백 |
| `malformedOrChangedEconomicEventsNeverReleaseThePendingSource` | 고객/자산/금액/ID/시각/필드 중복/후행 JSON 변조를 모두 차단 |
| `anotherEventIdentityForTheSameDepositIsNotSilentlyAccepted` | 다른 이벤트 ID의 같은 입금은 오류·증가1회 |
| `sameEventIdentityCannotChangeItsFinancialPayload` | 같은 이벤트 ID의 다른 본문 차단 |
| `concurrentSameEventCreditsExactlyOnce` | 같은 이벤트 동시 소비는1회 반영 |
| `concurrentDifferentEventsCannotDoubleCreditOneDeposit` | 다른 이벤트 동시 소비도 같은 입금에1회만 반영 |
| `financialAuditExceptionRollsBackInboxLedgerProjectionAndCreditReceipt` | 금융 감사 예외 시 수신 키/원장/잔액/결과 전체 롤백 |
| `silentlyMissingFinancialAuditCannotCommit` | 금융 감사 조용한 누락도 커밋 차단 |
| `missingLedgerCannotCommitTheProjectionOrInbox` | 원장 저장 누락 시 잔액/수신 키 커밋 불가 |
| `missingProjectionCannotCommitTheLedgerOrInbox` | 잔액 저장 누락 시 원장/수신 키 커밋 불가 |
| `wrongBalancedLedgerAmountsStillCannotPassTheCreditBinding` | 합계가 맞아도 다른 금액의 원장은 차단 |
| `missingCreditReceiptCannotCommitAnOtherwiseBalancedCredit` | 금융 결과 연결 누락 시 균형 원장도 차단 |
| `corruptedProjectionIsPreservedAndCreditIsHeld` | 기존 잔액 오류를 보존하고 자동 수리 없이 보류 |
| `orphanCreditLedgerKindDoesNotBypassThePendingSource` | 업무 종류만 사칭한 고아 입금 해제 원장 차단 |
| `committedExecutionAndCreditHistoryCannotBeChangedOrTruncated` | 확정 권한/시도/응답/금융 결과 변경·삭제·초기화 차단 |
| `independentReconciliationMatchesTheNanosecondOriginalAndNeverCallsSettlement` | 독립 대사가 나노초 원본 시각을 포함해 MATCHED·금융 재적용 없음 |
| `independentSourcePendingDoesNotPretendThatHttpAcknowledgementIsFinancialReceipt` | HTTP 확정만 있고 Kafka 미수신이면 INCOMPLETE |
| `independentSourceWithAnotherAmountCannotBeMatchedByAnExistingReceipt` | 같은 ID의 다른 원본 금액은 MISMATCH·금융 무변경 |
| `missingIntentAndAmbientTransactionNeverStartTransport` | null/의도 없음/이미 열린 TX는 전송 시작 금지 |

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew compileTestJava --console=plain
/usr/bin/caffeinate -i python3 scripts/verify_bitcoin_credit_execution.py
/usr/bin/caffeinate -i python3 scripts/verify_bitcoin_credit_intents.py
/usr/bin/caffeinate -i python3 scripts/verify_engine_inbox.py
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 /usr/bin/caffeinate -i python3 scripts/verify_bitcoin.py
```

2026-10-26 최종 신규37개·기존 의도35개·기존 금융182개·실제 Core31.1 회귀231개가 통과했다. 원장18개 중복을 빼면 **고유467개/485회**다. 실패·오류·제외0. 초기 단일 정상1개·중간 집중11개·실패를 포함한 중간 실행은 최종 통과 수에 더하지 않는다.

첫 단일 정상 실행은 기존 보류 계정 제약이 새 해제 업무를 거부했다. 실제 결과 연결의 정확한 고객 보류 감소만 허용하고 고아 해제 원장 차단을 함께 추가했다. 첫 전체 실행의 원장 누락 장애 주입은 Spring 필수 TX 프록시를 대역 설정 중 호출해 실패했다. 제품 TX 보호를 완화하지 않고 실제 spy target에 장애를 설정해 집중11개를 확인한 뒤 전체37개를 처음부터 다시 실행했다. 기존 금융 첫182개 실행은 체결/수수료 전용 H2 슬라이스가 새 입금 의존성 대역을 빠뜨려14개가 실패했다. 해당 테스트에만 입금 서비스 대역을 명시하고 금융182개를 다시 실행했다. 제품의 실제 PG 금융 경로는 새37개에서 검증하며 제약을 약화하지 않았다. 원래 실패 로그/부분 결과는 `.local/cycle127-intermediate`와 UUID별 실행 폴더에 보존한다. 컴파일 중 기존 verify(boolean) 이름 충돌과 내부 hash의 checked exception 선언도 수정 후 다시 확인했다.

상위 회귀 실행은 외부 CDD/EDD/Didit/메일/BTC/CodeVASP/가격 환경을 제거하고 봇0·소비자 자동 시작 금지·외부 실행 비활성/격리 주소를 적용했다. 소유 PG/노드만 사용하고 종료·제거했으며 기존5개 컨테이너는 보존했다. 제품 두 JAR의 클래스/SQL 포함·시험 제외, Python 구문·실제 발췌·상대 링크·공백 검사를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

## 남은 범위

이 단계는 실제 서비스의 전송/금융 수신 연결이며 자동 후보 선택·일정/재시작 워커는 후속이다. 실제 Rust·HTTP·Kafka 브로커·플랫폼의 전체 프로세스 연동과 브로커 중단/오프셋 복구도 종합 검증해야 한다. 기존 엔진021/클라이언트124의 과거 수치를 이번 실행으로 재사용하지 않는다. 매칭 코어·락/스레드·WAL/출력 코드는 변경하지 않았다.

이 금융 수신은 보호된 Kafka 채널과 올바른 단일 엔진 저널 계보를 전제로 한다. UUID 형식과 공개 SHA가 발신자 인증은 아니다. 실제 배포에서는 엔진 전용 생산 권한·브로커 접근/TLS·서비스 키·네트워크 경계를 검증해야 하며, 이번 직접 소비 시험으로 해당 인프라를 검증했다고 주장하지 않는다. 장기 중단/브로커 보존 기간 초과의 누락은 자체 저널 대사/승인된 원본 재처리로 해결해야 하며 새 입금 ID로 우회하지 않는다.

비VASP·소액/개인지갑·강화 고객의 별도 해결, 영속 거절/전송 후 재조직·지급 불가 상태의 명시적 처리, 보고 후속/보존·운영 관측·실제 공급사/계약·권한/키·장기 부하는 미완료다. 인프라/외부 심사 충족이나 실자금 운영 가능으로 보고하지 않는다. 디스크/호스트 손실에 대한 외부 복제·백업은 기존 지시대로 문서 필요 조건만 유지한다.


## 관련 커밋과 증적

- [구현 1860844](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1860844529b623f7da4b2b86070a1db5bd26b1c4)
- [격리 실행 증적](../reference/127-durable-bitcoin-credit-dispatch-and-settlement-verification-2026-10-26.json)
