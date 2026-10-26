# 출금 신청 시점의 순 지급액 원화 참고평가를 같은 금융 커밋에 보존

고객 출금 요청의 순 지급액과 그 시점에 이미 관찰된 원화 참고가격을 불변 원본에 결합했다. 서명·총액 예약·참고평가·필수 감사는 같은 트랜잭션에서 확정하며, 나중에 바뀐 현재가로 과거 요청을 소급 평가하지 않는다.

## 문제와 기존 동작

출금 원본에는 지급액·고객 수수료·총 차감액과 서명이 있지만 신청 시점의 원화 참고평가가 없었다. 심사/이전 승인 때의 가격만 사용하면 같은 출금의 환산 근거가 달라진다. 가격을 고객 총 차감액에 곱하면 실제 이전될 BTC에 고객 수수료까지 섞이기도 한다.

144 클라이언트는 원화 값을8자리로 정규화해 지급액8자리×참고가격8자리의16자리 곱을 표현하지 못하는 문제도 있었다. BTC의 사토시 정밀도와 원화 곱의 정밀도는 다른 제약이다. 이번 연결에서 원화 곱을 자르지 않도록 수정했다.

## 기존 제품과 공식 자료의 접근

[109 가격 원본과 적용 시점 검토](109-durable-bitcoin-krw-reference-price.md), [110 입금 참고평가](110-pending-deposit-price-capture.md)를 먼저 대조했다. 이미 설치된 서버 가격 서비스·불변 가격 원본·BigDecimal·JDBC/트랜잭션·필수 감사를 재사용하며 새 가격 SDK나 공급사 대체 경로를 추가하지 않았다.

2026-10-26 [감독규정2026-3호/부칙](https://www.law.go.kr/admRulLsInfoP.do?admRulSeq=2100000285986)과 [현행 시행령](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=290511&chrClsCd=010202&urlMode=lsInfoP&efYd=20261002&ancYnChk=0)을 확인했다. 현금 환산/이전 요청 시점, 현행 기준과 공포 후 유예 조항의 적용 시점을 구분한다. 고객 요청 시점과 사후 심사 시점을 섞지 않는 근거 보존이 이번 구현의 목적이다. Upbit 참고가격의 선택·최근성2분은 자체 정책이며 법령이 해당 공급자나 허용 오차를 지정한다는 뜻이 아니다.

[CodeVASP 이전 승인 계약](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/07-Asset-Transfer-Authorization)의 지급액/평가 자료 구분을 함께 적용한다. 이번 참고평가만으로 법정 환산·트래블룰 의무/면제·개인정보 제공이나 지급 허가를 결정하지 않는다. 실제 표시 가격 정책과 공급사 이용 조건·법적 적합성은 별도 확인 사항이다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 신청 때의 서버 원본을 같은 금융 TX에 고정 | 사후 현재가·고객 입력으로 역사적 근거가 바뀌지 않음 | 가격 부재/미완료/실패/오래된 자료에는 새 출금 예약을 거절한다. 과거 성공 가격으로 우회하지 않음 |
| 이미 수집한 가격만 읽음 | 출금의 DB 잠금 중 외부 가격 HTTP를 호출하지 않음 | 기존 가격 수집기의 가용성/주기/시계와 자료 보관에 의존 |
| 원본/참고평가/서명/원장/감사 같은 커밋 | 평가나 감사만 빠진 출금 원본을 만들지 못함 | 지연 제약과 저장 비용이 추가. 감사/DB 오류 때 고객은 재시도해야 함 |
| 순 지급액×정확한 가격의 전체 곱 | 수수료 포함 과대 평가와 반올림 경계 변화 방지 | 표시 반올림·법정 판정은 별도. 원화16자리 곱을 저장/전달할 수 있어야 함 |
| 과거 가격 원본으로 독립 대사 | 나중의 시세 상승/실패가 과거 정상 근거를 무효화하지 않음 | 기존 서명/원장에 더해 가격/원문 지문도 조회. 현재 머리 조회로 대체하지 않음 |
| 기존 과거 출금에 소급 기록하지 않음 | 없는 관측을 과거 사실로 만들지 않음 | 이전 버전의 참고평가 없는 원본은 별도 검토/취소 대상. 새 지급 근거로 자동 재사용할 수 없음 |

## 구현과 업무 흐름

고객 잠금 아래 사용 가능 총액을 확인한 뒤 기존 `BitcoinKrwPriceService.current()`로 현재 성공 원본을 포착한다. 가격 머리에 짧은 공유 DB 잠금을 두어 금융 커밋 전 다른 가격으로 바뀌지 않게 한다. 새로운 가격 수집의 DB 완료가 잠시 기다릴 수 있으며 Rust 체결 코어에는 이 잠금이 없다.

가격이 없거나 실패/미완료/만료됐으면 패스키 서명을 소비하지 않고 원본/예약도 남기지 않는다. 일반 가격 수신의 시계 허용 범위와 별개로, 신청 전에 존재하지 않은 미래 스냅샷 시각을 신청 근거로 사용하지 않는다.

서명 검증과 총액 예약 뒤 실제 출금 원본의 `created_at`·순 지급액을 읽어 참고평가를 저장한다. SQL 제약은 같은 원본/고객/생성 TX·가격 현재 위치/원문 지문·성공 상태/가격 시각·정확한 곱을 대조한다. 불변 기록이므로 UPDATE/DELETE/TRUNCATE를 허용하지 않는다. 직접 SQL로 평가를 생략해도 원본 커밋의 지연 제약이 실패한다.

독립 대사는 원래 가격 원본의 지문·가격·원본 지급액·기준 시각·정확한 환산액을 비교한다. 최신 가격 머리나 현재 시세로 재평가하지 않는다. 취소/거절은 고객 총액을 반환하되 원래 참고평가를 보존한다. 같은 요청 재조회도 새 가격을 요구하거나 원본을 바꾸지 않는다.

기존 사용하지 않는 중간 Receipt 생성/반환도 제거했다. 예약은 금융 원본만 확정하고 공개 응답은 현재 영속 상태 조회에서 한 번 구성한다.

## 이해에 필요한 실제 코드

출처: [BitcoinWithdrawalRequests.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalRequests.java#L119), 현재 소스의 부분 발췌.

```java
        // 신청 전에 이미 관찰된 서버 가격만 고정한다. 가격 부재를 나중의 심사 시세로 보충하지 않는다.
        var valuation=valuations.capture();
        // 부족한 자산에서 서명 카운터를 소비하지 않는다. 자산 확인 뒤 모든 변화는 같은 TX에서만 확정한다.
        authorization.authorize(actor, sid, quoteId, proof);
        balance.setAvailable(ExactAmount.requireNonNegative(available.subtract(gross), "예약 후 가용 BTC"));
        balance.setLocked(ExactAmount.requireNonNegative(locked.add(gross), "예약 후 잠긴 BTC"));
```

현재 소스의 부분 발췌다. 가격 부재는 서명 소비보다 먼저 실패한다. 금융 업무의 TransactionTemplate 안에서 이미 수집된 자료만 읽고 뒤의 서명/금융 기록과 함께 커밋한다. TypeScript에서도 외부 HTTP await를 DB TX 안에 넣으면 잠금이 자동으로 사라지지 않는다는 점과 구분한다.

출처: [BitcoinWithdrawalValuations.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalValuations.java#L35), 현재 소스의 부분 발췌.

```java
        var original=jdbc.queryForMap("SELECT amount,created_at FROM bitcoin_withdrawals WHERE id=? AND user_id=?",id,owner);
        BigDecimal amount=(BigDecimal)original.get("amount");
        BigDecimal value=amount.multiply(source.price());
        jdbc.update("""
            INSERT INTO bitcoin_withdrawal_valuations(withdrawal_id,user_id,policy,reference_at,price_attempt_id,
             source_hash,amount,price,krw_value) VALUES(?,?,?,?,?,?,?,?,?)
            """,id,owner,POLICY,original.get("created_at"),source.attemptId(),source.payloadHash(),amount,source.price(),value);
        audit.record(owner,"BTC_WITHDRAWAL_VALUED","bitcoin-withdrawal:"+id,"SUCCESS",
```

현재 소스의 부분 발췌다. API의 환산액/총액을 입력받지 않고 실제 원본의 순 지급액을 읽는다. BigDecimal.multiply는 이진 실수나 자동 반올림을 사용하지 않는다. 예를 들어0.01 BTC×100000000.12345678 KRW는1000000.0012345678000000 KRW이며0.010001 BTC 고객 총액을 곱한 값과 다르다.

출처: [withdrawal-valuation-schema.sql](../../src/main/resources/bitcoin/withdrawal-valuation-schema.sql#L30), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_valuation_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source record;
BEGIN
 SELECT * INTO source FROM bitcoin_withdrawal_valuations WHERE withdrawal_id=NEW.id;
 IF NOT FOUND OR source.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM security_audit
  WHERE actor_id=NEW.user_id AND action='BTC_WITHDRAWAL_VALUED' AND target='bitcoin-withdrawal:'||NEW.id::text
   AND outcome='SUCCESS' AND detail='PRICE:'||source.price_attempt_id::text||':HASH:'||source.source_hash) THEN
  RAISE EXCEPTION '출금 원본·신청 시점 참고평가·필수 감사를 같은 커밋에 보존해야 합니다';END IF;
 RETURN NULL;
```

현재 소스의 부분 발췌다. 이 함수는 원본 INSERT 뒤 커밋 시점까지 미뤄 실행하는 제약 트리거다. 같은 TX의 실제 참고평가와 필수 감사가 없으면 원본/원장/잔액/서명 소비를 함께 되돌린다. 과거 요청에 현재 가격을 붙이는 별도 TX도 허용하지 않는다.

## 작성한 테스트

신규 [BitcoinWithdrawalValuationIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalValuationIntegrationTest.java)15개는 실제 고객 패스키·PG 금융 TX·가격 기록 서비스·지연 제약·감사·독립 대사를 검증한다. 가격 HTTP만 대역이며 현재 위치·성공/실패 저장은 실제 제품 코드다.

| 함수 묶음 | 시나리오·기대 결과 |
| --- | --- |
| exactReference | 순 지급액과 전체16자리 곱, 원본 생성 시각/같은 TX/정책 일치 |
| duplicateRequest / laterReferencePrice | 같은 요청은 새 실패/시세와 무관하게 원래 참고평가 유지 |
| failedCurrentPrice / newUnfinishedPriceHead / staleProviderTrade | 새 실패·미완료·오래된 체결을 과거 성공으로 숨기지 않고 서명/예약 미실행 |
| valuationAuditFailure / exactFinancialHoldWithoutValuation / valuationWithoutAudit | 감사 장애/평가 생략/감사 생략에서 원본·잔액·서명 소비 모두 롤백 |
| storedPriceAndAmount | 다른 원문 지문을 같은 금액/가격으로 위장해 저장하지 못함 |
| valuationCannotBeRewritten / pastRequestCannotAcquire | 수정/삭제/전체 비우기·나중 TX의 소급 평가 금지 |
| independentReconciliation | 가격 머리가 바뀌어도 당시 원본과 비교해 MATCHED |
| cancellationPreserves | 고객 수수료 포함 총액은 반환하고 역사적 평가는 그대로 보존 |
| futureProviderTimestamp | 원문과 일치하는 미래 공급사 시각도 더 이른 신청의 근거로 사용하지 못함 |

기존 [CodeVaspWithdrawalAuthorizationClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAuthorizationClientTest.java)에 `fiatValuationPreservesTheExactProductWithoutEightDigitRounding`을 추가했다. 실제 소유 모듈/게이트웨이 HTTP에서16자리 원화 곱이 그대로 전달되는지 검증한다. 이전20개에1개가 추가됐으며 이전144의20개 실행 기록을 새 결과로 소급 수정하지 않는다.

공통 출금 시험 준비는 실제 가격 기록 서비스를 거쳐 원본을 만들며, 시험 프로필에서 자동 가격 워커는 생성하지 않음을 확인했다. `valuation.upbit.enabled=true`여도 `@Profile("!test")`가 자동 외부 수집을 차단한다. 실제 Wire의 제품 서비스 생성자에도 새 필수 가격 경계를 연결했다.

## 실행 명령과 결과

| 실행 명령 | 최종 결과 |
| --- | --- |
| `python3 scripts/verify_bitcoin_withdrawal_valuations.py` | 신규15통과 |
| `python3 scripts/verify_bitcoin_withdrawal_reservations.py` | 기존 예약19통과 |
| `python3 scripts/verify_bitcoin_withdrawal_cancellations.py` | 기존 취소20통과 |
| `python3 scripts/verify_engine_inbox.py` | 금융217통과 |
| `python3 scripts/verify_platform_security.py` | 보안41통과 |
| `python3 scripts/verify_bitcoin_withdrawal_wire.py` | 실제 Rust/WAL/Kafka·응답 유실/강제 복원3통과 |
| `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*CodeVaspWithdrawalAuthorizationClientTest' --tests '*CodeVaspTransferDataClientTest' --tests '*CodeVaspTransactionClientTest' --tests '*CodeVaspDirectoryClientTest' bootJar operatorAdminJar --console=plain` | 신규 클라이언트21+기존46=67통과 |

최종 고유382개/382회, 실패/제외0. 고객/운영자 JAR 빌드와 실제 형제 Rust 실행 파일의 새 빌드도 통과했다. 메서드별 PG DB·실제 연결별 PG/Kafka는 이번 실행이 생성하고 정리했다. 외부 실제 가격/유료 공급사·체인·봇·공용5432/5433/9092 서비스는 호출하지 않았다. 기존 Java deprecated/JVM 공유·Rust unused/dead_code/bench/SQLx 향후 호환 경고는 남는다.

개발 중 MANDATORY 프록시 바깥에서 시험 spy를 설정해 생긴 오류는 시험 스텁도 TX 안에서 설정하도록 수정했다. 미래 시각 시험의 원문 timestamp와 DTO가 달라 가격 DB 제약에서 먼저 거절된 오류도 대역을 수정했다. 해당 선택 재검증 뒤 위 최종 전체 명령을 실행했으며 개발 중 재실행을 최종 고유 수에 다시 더하지 않는다.

## 실행 조건과 한계

기존 `valuation.upbit.enabled` 기본값은false다. 새 출금 예약에는 가격 수집을 활성화하고 검증된 현재 원본이 먼저 준비돼 있어야 한다. 미설정·부재에는503이며 금융 권한을 생략하는 폴백이 없다. 가격 이용 조건/공유 호출 제한·시계 동기화·실제 표시/환산 정책·보존/외부 심사는 운영 조건이다.

이미 기록된 과거 출금에는 없는 과거 가격을 추가하지 않는다. 취소/금융 기록 조회는 보존하되 참고평가가 없는 원본을 새 지급 근거로 자동 사용할 수 없다. 독립 대사에서도 자료 부재를 표시한다. 기존 금융 이력을 지우거나 새 가격으로 재작성하지 않았다.

현재는 신청 시점의 참고 자료 보존이다. 원화 참고가가 법정 적용 가격이라는 최종 판단·금액 기준 의무의 시행일 판정·이전거래 상대/개인지갑 위험·개인정보 제공 목적과 실제 공급사 응답 결합, 독립 지급 승인·취소와 지급의 배타적 결정·실제 BTC 서명/전송/완료는 남는다. 다음에는 현재 신원·허용 상대/주소 소속·이 참고평가를 영속 이전 의도에 결합한다.

## 작성한 테스트 코드


[BitcoinWithdrawalValuationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalValuationIntegrationTest.java)

- `exactReferenceUsesNetPaymentAndPreservesTheFullProduct`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateRequestDoesNotRevalueAnOriginalAfterThePriceHeadFails`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `failedCurrentPriceCannotUseAHistoricalSuccessOrConsumeTheSignature`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `newUnfinishedPriceHeadCannotFallBackToItsPreviousSuccess`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `staleProviderTradeCannotCreateANewFinancialReservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationAuditFailureRollsBackTheOriginalSignatureAndHold`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `exactFinancialHoldWithoutItsValuationCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationWithoutItsMandatoryAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `storedPriceAndAmountCannotUseAnUnboundSourceHash`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationCannotBeRewrittenDeletedOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterReferencePriceDoesNotReplaceTheHistoricalRequestValuation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `independentReconciliationUsesTheHistoricalSourceAfterAHeadChange`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationPreservesTheValuationWhileReturningTheOriginalGross`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `pastRequestCannotAcquireANewValuationInALaterTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `futureProviderTimestampCannotBecomeTheEarlierRequestReference`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 dcac2fa](https://github.com/GiHoon1123/Cex-Platform-Service/commit/dcac2fa299009e8aa8a3bece7d673458cbe7ed03)
- [격리 실행 증적](../reference/145-bitcoin-withdrawal-request-time-valuation-verification-2026-10-26.json)

- [선행 원화 정밀도 수정 6feb44c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/6feb44c4de6cb0233e40a0f1793c1992f3082a9e)
