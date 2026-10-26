# 고객 출금 취소 의도와 확정 해제 뒤 총 예약금 반환

고객이 서명해 예약한 BTC 출금을 취소하고 원래 지급액과 고객 수수료를 함께 반환한다. 취소 의도·엔진 해제 확인·Kafka 금융 확정을 구분해 중복 취소, 응답 유실, 재로그인, 감사 장애에도 다른 예약금을 사용하거나 두 번 환불하지 않도록 했다.

## 문제와 기존 동작

141은 고객의 일회 서명·총액 예약과 최초 예약 거절의 반환을 연결했다. 성공적으로 예약된 고객은 아직 직접 취소할 수 없었다. 불명 응답을 취소 성공으로 간주하면 엔진은 돈을 잠근 채 플랫폼에서 다시 사용할 수 있고, 과거 세션만 허용하면 재로그인 뒤 본인 출금을 관리할 수 없다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [Coinbase holds 공개 계약](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/accounts/get-single-account-hold)의 예약금과 가용금 구분을 유지한다. [OWASP 거래 승인 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)의 서버 상태 전이·금액 결합·실행 경계 원칙과 [기존 엔진 출금 생명주기](../../../Cex-Engine/docs/decisions/023-withdrawal-reservations.md)를 따른다. 공개된 API로 내부 취소 구현이나 보장 범위를 추측하지 않는다.

설치된 Spring JDBC/트랜잭션/고객 세션·MFA, 엄격한 엔진 HTTP 클라이언트와 기존 Kafka 수신 키를 재사용했다. 패키지·새 큐·새 실행 스레드를 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 고객 취소 의도를 먼저 영속화 | 예약 응답이 유실됐어도 나중에 같은 출금을 취소할 수 있음 | 예약 확정이 올 때까지 자금은 잠긴다. 불명 상태의 즉시 환불은 이중 사용 위험 때문에 제외 |
| 확정 예약 뒤 같은 ID/고객/BTC/총액으로 RELEASE | 엔진의 원래 예약 몫만 해제 | 최초 예약 이벤트가 누락되면 해제 전송도 기다린다. 운영 대사/확정 저널 재발행 필요 |
| HTTP 확인과 금융 반환 분리 | 해제 응답 유실·일반409에도 잘못 환불하지 않음 | Kafka 확정 수신까지 고객 상태가 대기다. DURABLY_REJECTED는 환불 근거가 아니며 자동 반복도 멈추고 조사 필요 |
| 지급액+고객 수수료 전액 반환 | 체인 지급 전 취소에 수수료를 남기지 않음 | 실제 네트워크 비용이 발생한 지급 이후의 취소 정책과 다르다. 현재는 체인 전송 전 예약 취소만 구현 |
| 현재 본인 세션/MFA로 과거 출금 조회·취소 | 재로그인 뒤에도 자금 관리 가능 | 취소 자체에 새 건별 서명을 요구하지 않는다. 외부 지급/지급지 변경 권한을 주지 않고 기존 서명 원본의 전액 반환만 허용 |
| 예약·취소 전달 이력 별도 보존 | 각각의 재확인 시각·응답과 금융 확정을 명확히 추적 | 표/감사 기록이 늘어난다. 기존 예약 단계 기록을 수정해 해제 단계로 재사용하지 않음 |
| 기존 장수 워커에서 취소 우선 처리 | 추가 스레드 없이 반환을 진행 | 다른 금융 출력과 CPU/DB 자원을 공유한다. 엔진 단일 코어의 상태/공유 락 계약은 그대로 |

## 실제 흐름과 API

`POST /api/customer/withdrawals/BTC/quotes/{id}/reservations/cancellation`은 본문 없이 현재 본인 세션으로 요청한다. 운영자·다른 고객·회수 세션과 추가 금액/수수료 본문을 거절한다. 반복 요청은 불변 취소 원본 하나를 재사용한다.

| 상태 | 취소의 결과 |
| --- | --- |
| 예약 결과 불명 | 취소 의도만 저장, CANCELLATION_PENDING, 잔액 유지. 기존 예약 재확인 경로 계속 사용 |
| 예약 확정 | 취소 의도와 해제 시도를 커밋한 뒤 DB 잠금 밖에서 RELEASE 전송 |
| 최초 예약 거절 | 기존 거절 수신이 총액을 반환, 별도 RELEASE/추가 환불 없음 |
| 해제 HTTP 확인/불명 | 금융 잔액 유지, 확정 해제 이벤트 대기. 불명은 실제30초/5초 영속 재확인 기준 적용 |
| 확정 `withdrawal_released` | 동일 엔진의 더 뒤 위치·취소 의도·선행 예약·원래 총액을 확인하고 한 번 반환, CANCELLED |
| 이미 취소 완료 | 같은 원본/상태 재조회, 새 반환/해제 없음 |

수신 키·원장·현재 잔액·해제 금융 기록·필수 감사는 같은 트랜잭션에서 확정한다. 직접 SQL도 취소의 현재 고객/세션·본인 원본·선행 확정 예약·해제 전송 시도·이벤트 해시·두 원장 항목·필수 감사를 확인한다. 금융 기록의 UPDATE/DELETE/TRUNCATE를 거절한다. DB 소유자/슈퍼유저의 임의 권한 변경까지 막았다고 주장하지 않는다.

독립 대사는 해제 전까지 원래 총액을 잠긴 잔액에 합산한다. 취소 대기에는 EVENT_PENDING/INCOMPLETE를 기록하고, 확정 해제 뒤에는 같은 금액이 반환됐는지 별도 저널 원본과 비교한다. 자동 잔액 정정은 하지 않는다.

## 구현과 관련 코드·문법

출처: [BitcoinWithdrawalCancellations.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalCancellations.java#L56), 현재 소스의 부분 발췌.

```java
            int inserted=jdbc.update("""
                INSERT INTO bitcoin_withdrawal_cancellations(withdrawal_id,user_id,session_id)
                VALUES(?,?,?) ON CONFLICT(withdrawal_id) DO NOTHING
                """,id,actor,sid);
            if(inserted==1) audit.record(actor,"BTC_WITHDRAWAL_CANCEL_REQUESTED","bitcoin-withdrawal:"+id,
                "SUCCESS","RETURN_ORIGINAL_GROSS");
        }); } catch(org.springframework.web.server.ResponseStatusException denied) {
```

현재 소스의 부분 발췌다. `ON CONFLICT ... DO NOTHING`은 같은 출금 UUID에 취소 의도 한 건만 허용한다. 고객 행 잠금 아래 실행하며 새 원본을 만든 때만 필수 감사를 추가한다. TS의 메모리 Set과 달리 프로세스 재시작과 여러 요청 사이에서도 DB가 중복을 막는다. 커밋 후에만 네트워크를 호출한다.

출처: [BitcoinWithdrawalEvents.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalEvents.java#L122), 현재 소스의 부분 발췌.

```java
        String operation="engine:"+eventId;
        refund(owner,gross,operation,"BTC_WITHDRAWAL_RELEASED");
        jdbc.update("""
            INSERT INTO bitcoin_withdrawal_release_events(withdrawal_id,event_id,engine_id,event_time,payload_hash,operation_key)
             VALUES(?,?,?,?,?,?)
            """,id,eventId,engine,timestamp,BitcoinWithdrawalQuotes.digest(raw),operation);
        audit.record(null,"BTC_WITHDRAWAL_RELEASED","bitcoin-withdrawal:"+id,"SUCCESS","EVENT:"+eventId);
```

같은 출금 ID의 선행 예약과 취소 의도를 확인한 뒤 실행하는 부분 발췌다. `refund`는 원래 총액만 LOCKED에서 AVAILABLE로 옮긴다. 메서드 호출을 따로 작성해도 상위 Kafka 리스너의 트랜잭션이 이어진다. 뒤의 기록/감사/지연 제약 실패는 반환과 수신 키까지 함께 롤백한다.

출처: [BitcoinWithdrawalWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalWorker.java#L60), 현재 소스의 부분 발췌.

```java
            var next = cancellations.processNext();
            if(next.isEmpty())next=dispatch.processNext();
            if (next.isEmpty()) return;
            var outcome = next.get().transport();
```

`Optional`이 비어 있으면 처리할 확정 취소가 없다는 뜻이다. 예약 확인을 계속 진행하므로 취소 의도가 아직 선행 예약을 받지 못했다고 예약 재확인 자체를 막지 않는다. 기존 한 장수 스레드를 사용한다. 네트워크 대기는 플랫폼 워커에 있고 Rust 매칭 코어에 넣지 않는다.

## 작성한 테스트와 결과

신규 [BitcoinWithdrawalCancellationIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalCancellationIntegrationTest.java)20개는 실제 고객 패스키·PG 금융 원본/원장/감사/소비 트랜잭션을 검증한다. 141의 공통 실제 고객 준비 코드를 [BitcoinWithdrawalTestSupport](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalTestSupport.java)로 분리해 같은 조건을 사용한다. 고객 지급지 조회와 엔진 HTTP는 이 묶음에서 대역이며 실제 브로커/프로세스 연동 시험으로 과장하지 않는다.

| 함수 묶음 | 시나리오·기대 결과 |
| --- | --- |
| confirmedReleaseAlone / cancellationBeforeReserve / firstReserveRejection | 확정 해제만 전액 반환, 예약 불명 중 해제 전송 없음, 최초 거절의 중복 환불 없음 |
| duplicateCancellation / releaseWithoutCustomer / releaseFromAnotherEngine | 중복 반환0, 취소 의도 없는 결과와 다른 엔진/앞선 위치 거절·수신 키 롤백 |
| uncertainReleaseHttp / genericReleaseRejection | 불명·일반 HTTP409에서 금융 상태 변경 없음 |
| mandatoryAuditFailure / cancellationWithoutItsMandatoryAudit / releaseReceiptAndLedgerWithoutMandatoryAudit | 서비스 감사 장애와 직접 SQL 감사 누락 모두 전체 커밋 차단, 같은 이벤트 정상 재시도 가능 |
| cancellationOriginalAndReleaseHistory | 금융 원본/시도/응답/결과 삭제·수정·전체 비우기 거부 |
| renewedCustomerSession / wrongOwnerAndRevokedSession / customerHttpCancellation | 재로그인 본인만 조회/취소 가능, 다른 고객/회수 세션/운영자/추가 본문 거부 |
| concurrentCancellationRequests | 동시 요청에도 취소 의도1·해제 전송 시도1 |
| independentReconciliation | 취소 대기 INCOMPLETE, 실제 해제 원본 비교 뒤 MATCHED |
| databaseRejectsReleaseAttempt | 확정 예약 전 직접 해제 전송 시도 INSERT 거부 |
| alteredGrossOrExtraReleaseFields | 다른 금액/추가 필드의 이벤트로 반환하지 못함 |
| uncertainReleaseRetries | 보존 시각 변경 없이 실제5.1초 대기 후 같은 UUID/총액 재확인, 확정 이벤트 뒤 한 번 반환 |

각 함수의 전체 이름은 아래 목록과 테스트 소스에서 확인한다.

## 실행 명령과 검증 범위

- `python3 scripts/verify_bitcoin_withdrawal_cancellations.py`:20통과, 실패/제외0. 시험별 새 PG DB, 실제 재확인 대기 포함.
- `python3 scripts/verify_bitcoin_withdrawal_reservations.py`:기존19통과, 실패/제외0. 공통 준비 분리·조회 세션 변경·환불 메서드 공유의 회귀 확인.
- `python3 scripts/verify_engine_inbox.py`:금융217통과, 실패/제외0.
- `python3 scripts/verify_platform_security.py`:보안41통과, 실패/제외0.
- 고유297개/297회, 고객/운영자 JAR 빌드 통과. 개발 중 선택3개 재실행은 최종 고유 집계에 더하지 않는다.

초기 개발 실행은 상속한 고객 확인 `verify(boolean)`와 Mockito `verify(mock)`의 이름 충돌로 시험 컴파일이 실패했다. Mockito를 명시해 고친 뒤 위 최종 명령을 통과시켰다. 기존 deprecated API와 JVM 클래스 공유 경고는 남는다. 소유 PostgreSQL 컨테이너만 사용하고 실행 후 제거했다. 공유 서비스·봇·실제 BTC 전송을 사용하지 않았다.

## 한계와 다음 연결

현재 취소는 체인 지급 전 예약의 반환이다. 실제 지급 허가·서명·전송 단계가 추가되면 취소와 지급 중 하나만 선택하는 영속 결정 경계를 함께 연결해야 한다. 전송 시작/결과 불명의 출금을 무조건 취소할 수 있도록 확장하면 안 된다. 아직 체인 지급이 가능한 완성된 출금 서비스로 배포한 것이 아니다.

브로커 지연/해제 HTTP의 금융 거절은 고객 자금을 임의 반환할 근거가 아니다. 금융 대사·확정 저널 재발행·운영 조사로 해소해야 한다. 다음에는 고객 서명부터 실제 별도 Rust/HTTP/WAL/Kafka까지 연결하고 응답 유실/강제 종료 뒤 같은 원본이 복원되는지 검증한다. 법령·사업 신고·인증·운영 키/백업 검증 완료를 의미하지 않는다.

## 작성한 테스트 코드


[BitcoinWithdrawalCancellationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalCancellationIntegrationTest.java)

- `confirmedReleaseAloneReturnsTheOriginalGrossIncludingTheFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationBeforeReserveConfirmationWaitsWithoutSendingRelease`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `firstReserveRejectionFinishesPendingCancellationWithoutASecondRefund`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateCancellationAndDuplicateReleaseCannotRefundTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `releaseWithoutCustomerCancellationRollsBackItsInboxReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `releaseFromAnotherEngineOrAnEarlierPositionCannotRefund`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `uncertainReleaseHttpKeepsTheHoldAndTheSameDurableAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `genericReleaseRejectionCannotRefundOrBecomeFinancialRejection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mandatoryAuditFailureRollsBackTheReleaseAndCanRetryTheSameEvent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationOriginalAndReleaseHistoryCannotBeRewrittenOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationWithoutItsMandatoryAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `renewedCustomerSessionCanReadAndCancelItsHistoricalWithdrawal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongOwnerAndRevokedSessionCannotCreateCancellationIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpCancellationAcceptsOnlyAnEmptyBody`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentCancellationRequestsCreateOneReleaseAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `independentReconciliationSeparatesPendingCancellationAndConfirmedRefund`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseRejectsReleaseAttemptBeforeTheReservationIsConfirmed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `alteredGrossOrExtraReleaseFieldsCannotRefund`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `uncertainReleaseRetriesOnlyTheSameIdAndGrossAfterTheDurableDelay`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `releaseReceiptAndLedgerWithoutMandatoryAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 90f5b36](https://github.com/GiHoon1123/Cex-Platform-Service/commit/90f5b36908b37e3722cd405a7eebde3f2e7f6105)
- [격리 실행 증적](../reference/142-bitcoin-withdrawal-cancellation-and-release-verification-2026-10-26.json)
