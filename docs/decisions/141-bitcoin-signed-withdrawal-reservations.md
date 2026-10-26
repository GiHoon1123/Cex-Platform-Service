# 고객의 일회 패스키 서명과 BTC 총액 예약·확정 수신

고객이 확인한 BTC 지급지·지급액·0.01% 수수료·총 차감액을 한 번의 패스키 서명에 결합하고, 그 총액만 원장에 예약한다. 엔진 HTTP 확인과 Kafka 금융 반영을 구분하며 실패·중복·불명·독립 대사를 설명한다. 공통 사이클141이다.

## 문제와 기존 동작

140의 견적은 금액 확인 자료이며 금융 권한이 아니다. 로그인한 고객이 견적 UUID만 보내서 자산을 움직이거나, 같은 서명을 다른 지급지에 재사용하면 안 된다. 플랫폼의 예약은 엔진의 최초 예약 결과보다 먼저 저장되므로 응답 유실 때 일반 HTTP 오류를 근거로 자금을 반환할 수도 없다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [OWASP 거래 승인 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)의 중요 거래 정보 확인·건별 승인·서버 검증·변경 방지 원칙을 적용했다. [Spring Security 패스키 문서](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)를 대조하고 이미 설치된 WebAuthn 검증기·JDBC·트랜잭션·기존 고객 인증을 재사용했다. 새 인증 라이브러리를 추가하지 않았다. [Coinbase holds API](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/accounts/get-single-account-hold)의 주문/출금 예약과 가용 잔액 구분을 참고하며 공개되지 않은 내부 구현은 추측하지 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 비용·제약과 대안 |
| --- | --- | --- |
| 출금별 일회 WebAuthn 서명 | 로그인 세션 탈취만으로 새 출금 금액을 승인하지 못함 | 고객은 패스키를 등록하고 건별 확인을 해야 한다. 단순 최근 로그인 재사용은 원본 결합이 약해 제외 |
| 고객·세션·견적 해시의 HMAC 결합 | 공개 해시를 승인 권한처럼 재사용하지 못함 | 기존 CDD 키의 가용성과 회전 정책을 공유한다. 키 변경 전 미사용 챌린지는 다시 발급해야 함 |
| 플랫폼 총액 예약→커밋→엔진 호출 | 네트워크 대기 동안 금융 DB 잠금을 보유하지 않음 | 플랫폼과 엔진 확정 사이에는 잠금 상태가 남는다. 응답 불명은 같은 UUID로 재확인하며 임의 환불하지 않음 |
| 원본/원장/서명/필수 감사의 같은 커밋 | 중간 실패가 금융 원본 일부만 남기지 못함 | PostgreSQL 제약·지연 트리거와 더 많은 금융 기록이 필요. 감사 장애 때 출금도 실패 |
| HTTP 결과와 최초 금융 이벤트 분리 | 일반409를 이미 지급된 돈의 환불 근거로 오해하지 않음 | 확정 금융 이벤트가 지연되면 고객 상태도 대기. 운영 대사와 영속 재발행이 필요 |
| 같은 고객 행에서 예약 직렬화 | 주문/다른 출금과 가용 BTC 이중 사용 방지 | 플랫폼의 같은 고객 업무는 DB 잠금을 공유한다. Rust 체결 코어에 공유 락을 추가한 것은 아님 |

## 업무 흐름과 API

1. `POST /api/customer/withdrawals/BTC/quotes/{id}/authorization-options`: 현재 고객/MFA/세션·KYC/CDD·견적 기한/계정 버전을 검사하고 최대 견적 만료 시각까지 유효한 챌린지를 만든다.
2. 고객이 실제 서명한 `challengeId`, `publicKey`만 `POST .../reservations`로 보낸다. 추가 금액/수수료·중복 JSON·초과 본문은 거절한다.
3. 고객 잠금 아래 총액을 확인한 뒤 패스키 서명·RP/출처·사용자 확인·카운터를 검증한다. `AVAILABLE - total`, `LOCKED + total`, 불변 출금 원본과 필수 감사를 함께 커밋한다. 부족 잔액은 서명을 소비하지 않는다.
4. 커밋 뒤 별도 엔진 HTTP로 같은 UUID/고객/BTC/총액의 RESERVE를 보낸다. 전송 시도와 결과도 각각 저장하고 감사한다. 불명 시도는30초, 확인된 비확정 응답은5초 뒤 같은 원본으로 재확인한다. WAL 확정 응답은 추가 전송을 멈추되 금융 수신 완료로 표시하지 않는다.
5. Kafka 수신 키와 `withdrawal_reserved`는 잔액을 다시 이동하지 않고 원장 확인을 저장한다. 정확한 최초 `withdrawal_rejected`만 총액을 한 번 반환한다. 원본/금액/고객/이벤트 ID/필드/사유가 다르면 전체 수신 트랜잭션을 롤백한다.
6. `GET .../reservations`는 현재 세션에서 `PENDING_ENGINE`, `RESERVED`, `REJECTED`와 원래 지급액/수수료/총액을 조회한다. 만료된 견적을 새로운 승인으로 재해석하지 않는다.

독립 대사는 주문 예약과 출금 예약을 합산해 현재 잠긴 잔액과 비교한다. 확정 저널 원본·플랫폼 수신 해시·불변 요청·서명 연결·실제 원장 이동도 비교한다. 금융 결과가 없으면 INCOMPLETE이며 자동 수리/재정산하지 않는다. 정상 reserved를 잘못 WITHDRAWAL_STATE 오류로 분류하던 분기를 신규 대사 회귀로 찾아 수정했다.

## 구현과 이해에 필요한 실제 코드

출처: [CustomerWithdrawalAuthorization.java](../../src/main/java/dustin/cex/domains/security/CustomerWithdrawalAuthorization.java#L76), 현재 소스의 부분 발췌.

```java
            var credential = verifier.verifyAssertion(actor, (String) rows.getFirst().get("options"), proof.publicKey());
            jdbc.update("UPDATE customer_withdrawal_challenges SET consumed=true WHERE id=?", proof.challengeId());
            String fingerprint = OperatorMfaService.sha256Hex(credential.getRawId().toBase64UrlString());
            // 지연 외래키/커밋 제약은 같은 트랜잭션에 실제 출금 요청·예약이 있어야 서명을 확정하게 한다.
            jdbc.update("""
                INSERT INTO customer_withdrawal_authorizations(withdrawal_id,user_id,session_id,quote_hash,challenge_id,credential_fingerprint)
                VALUES(?,?,?,?,?,?)
                """, quoteId, actor, sid, quote.payloadHash(), proof.challengeId(), fingerprint);
            audit.record(actor, "CUSTOMER_WITHDRAWAL_AUTHORIZED", "bitcoin-withdrawal:" + quoteId,
```

현재 소스의 부분 발췌다. 검증된 인증 자료를 소비하고 서명 지문을 원본 UUID에 연결한다. `MANDATORY`는 이미 열린 상위 금융 트랜잭션 안에서만 실행하라는 뜻이다. TypeScript의 비동기 함수 호출과 달리 프록시의 전파 규칙으로 같은 DB 커밋을 공유하며, 오류가 나면 카운터/소비 상태/금융 예약을 함께 되돌린다.

출처: [BitcoinWithdrawalRequests.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalRequests.java#L115), 현재 소스의 부분 발췌.

```java
        authorization.authorize(actor, sid, quoteId, proof);
        balance.setAvailable(ExactAmount.requireNonNegative(available.subtract(gross), "예약 후 가용 BTC"));
        balance.setLocked(ExactAmount.requireNonNegative(locked.add(gross), "예약 후 잠긴 BTC"));
        balances.saveAndFlush(balance);
        String operation = "withdrawal-reserve:" + quoteId;
        ledger.record(operation, "BTC_WITHDRAWAL_RESERVE", List.of(
            p(actor, "AVAILABLE", "BTC", gross.negate()), p(actor, "LOCKED", "BTC", gross)));
        var source = quote.payload();
        jdbc.update("""
```

서명 성공 후 정확한 BigDecimal 총액만 두 잔액과 복식 원장에 옮긴다. `subtract`/`add`는 기존 값을 변경하지 않고 새 값을 만든다. JS number의 이진 부동소수 금액과 다르다. `saveAndFlush`는 SQL 실행이지 최종 커밋이 아니며 뒤의 원본/감사 제약이 실패하면 전체가 롤백된다.

출처: [BitcoinWithdrawalEvents.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalEvents.java#L101), 현재 소스의 부분 발췌.

```java
            ledger.record(operation, "BTC_WITHDRAWAL_REJECTED", List.of(
                p(owner, "LOCKED", "BTC", gross.negate()), p(owner, "AVAILABLE", "BTC", gross)));
        } else {
            // 이미 플랫폼에서 총액을 잠갔으므로 영속 예약 확인은 잔액을 또 움직이지 않는다.
            ledger.record(operation, "BTC_WITHDRAWAL_RESERVED", List.of());
        }
        jdbc.update("""
```

최초 금융 거절은 원래 LOCKED 몫을 AVAILABLE로 반환한다. 예약 확정은 빈 이동으로 확정 이력만 남겨 이미 잠근 금액을 다시 차감하지 않는다. 같은 수신 ID는 inbox가 중복 차단하며, 다른 ID의 모순된 최초 결과는 원본별 단일 결과 제약이 거절한다.

## 작성한 테스트와 기대 결과

신규 [BitcoinWithdrawalReservationIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalReservationIntegrationTest.java)는 실제 PostgreSQL·고객 로그인·등록 패스키의 실제 서명·원장/감사/수신 트랜잭션을 사용한다. 노드 지급지 조회와 엔진 HTTP만 대역이다. 시험별 새 DB를 만들어 불변 이력을 삭제하지 않는다.

| 함수 묶음 | 검증하는 실제 결과 |
| --- | --- |
| exactSignature / loginAndQuote / signatureForAnotherQuote | 정확한 총액 예약, 서명 없음/다른 원본 서명 거절 |
| duplicateRequest / confirmedReserve / durableFinancialRejection | 중복 차감 없음, 모순 이벤트 롤백, 최초 금융 거절만 한 번 반환 |
| insufficientGross / auditFailure / revokedSession | 부족한 총액·감사 실패·회수 세션에서 금융 원본/서명 소비 없음 |
| uncertainEngineResponse | HTTP 불명에도 원래 잠금/영속 시도 유지 |
| httpAccepts / httpRejects | 실제 고객 HTTP 흐름과 추가 금액/중복 JSON/운영자 접근 거절 |
| unsignedDirect / reservedOriginal / forgedEngine | 직접 무서명 원본 커밋 금지, 원본·승인·전달 이력 변경/삭제 금지, 위조 값/사유 거절 |
| twoDistinctQuotes | 동시 두 출금이 같은 사토시를 두 번 예약하지 못함 |
| eventWithoutItsMandatoryAudit | 수신 키/원장까지 있어도 필수 감사 없이는 확정 불가 |
| independentLedgerReconciliation 2개 | 대기 INCOMPLETE, 예약/거절의 독립 대사 MATCHED와 정확한 반환 |

전체 실제 함수명은 아래 자동 목록과 원본 파일에서 확인한다. 기존 OrderLedgerTest의 최소 PG 표와 체결/정산 전용 H2 테스트의 빈 주입도 새 의존성에 맞춰 수정했다. 제품의 PostgreSQL 검사를 폴백으로 우회하지 않았다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_withdrawal_reservations.py`:19통과, 실패/제외0. 메서드별 새 DB, 고객/운영자 JAR 빌드 통과.
- `python3 scripts/verify_engine_inbox.py`:217통과, 실패/제외0.
- `python3 scripts/verify_platform_security.py`:41통과, 실패/제외0.
- 플랫폼 고유277개/277회 통과다. 개발 중 선택 재실행은 이 최종 집계에 다시 더하지 않는다.
- 엔진의 초기 반입 검사30개/빌드와 실제 별도 프로세스·WAL·Kafka·강제 종료 복원은 [엔진024](../../../Cex-Engine/docs/decisions/024-withdrawal-history-bootstrap-guard.md)에 기록했다. 플랫폼 대역 시험을 전체 실제 엔진 연결로 표시하지 않는다.

개발 중 최초 금융 회귀는 최소 PG 스키마 누락, H2의 PG 초기화 실행, 체결 슬라이스의 새 소비 의존성 누락으로 실패했다. 해당 시험 범위를 고친 뒤 위 명령을 다시 통과시켰다. 기존 JVM 클래스 공유 경고와 컴파일 경고는 보안/운영 검증 완료를 의미하지 않는다. 공유 로컬 DB/브로커와 봇은 사용하지 않았고 소유 시험 컨테이너를 제거했다.

## 한계와 다음 연결

현재는 예약과 최초 금융 거절의 환불까지다. 고객 취소/엔진 RELEASE, 출금 수신인·위험 심사와 지급 승인, 실제 BTC 서명/전송 및 COMPLETE는 후속이다. 예약 API가 실제 지급 완료를 의미하지 않는다. 프론트/운영 배포로 지급 가능 상태처럼 노출하기 전에 이 경로를 완성해야 한다. 고객 예약금을 영구히 묶는 공개 서비스로 배포한 것이 아니다.

기존 실제 엔진 연결138과 이번 플랫폼 PG 시험은 각각의 경계를 검증했다. 실제 고객 서명부터 별도 엔진·Kafka·플랫폼 원장까지 한 번에 흐르는 전체 연결·장기 부하·운영 키/백업·사업 신고/심사를 통과했다고 주장하지 않는다. 순수 계산 벤치 수치를 재사용하지 않는다.

## 작성한 테스트 코드


[BitcoinWithdrawalReservationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalReservationIntegrationTest.java)

- `exactSignatureReservesTheGrossAmountAndKeepsTheQuoteNetAmount`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `loginAndQuoteWithoutOneTimeSignatureCannotReserve`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `signatureForAnotherQuoteCannotMoveTheGrossDebit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateRequestCannotConsumeTheProofOrReserveTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `confirmedReserveDoesNotDebitTwiceAndSecondEngineEventRollsBack`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `durableFinancialRejectionAloneReleasesTheOriginalGrossOnce`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `insufficientGrossBalanceDoesNotConsumeTheSignatureOrWriteAnOriginal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `auditFailureRollsBackTheProofAndTheEntireFinancialHold`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `revokedSessionCannotUseAnAlreadyCreatedWithdrawalChallenge`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `uncertainEngineResponsePreservesTheSameLockedFundsAndDurableAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `httpAcceptsOnlyTheCurrentCustomersSignedQuoteWithoutInputFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `httpRejectsInjectedAmountDuplicateJsonAndOperatorAccess`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unsignedDirectFinancialOriginalCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `reservedOriginalSignatureAndDispatchHistoryCannotBeEditedOrDeleted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `forgedEngineValueAndReasonCannotReleaseOrAcknowledgeTheHold`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `twoDistinctQuotesCannotSpendTheSameAvailableSatoshis`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `eventWithoutItsMandatoryAuditCannotCommitEvenWithReceiptAndLedger`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `independentLedgerReconciliationSeesThePendingAndConfirmedReserve`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `independentLedgerReconciliationSeesTheExactRejectionRefund`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 e2a13fc](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e2a13fc467ba57849e46267789ce76e8a40b9620)
- [격리 실행 증적](../reference/141-bitcoin-signed-withdrawal-reservations-verification-2026-10-26.json)
