# 출처 검색 성공과 정보 제공 판단을 구분하고 당시 평가 근거 조회하기

운영자의 입금 근거 조회에 당시 가격과 정확한 원화 참고값을 연결하고, 출처 검색 성공을 곧바로 정보 교환 필요라고 표시하던 동작을 수정한다. 금액 구간·기술 검색·개인정보 제공 목적·금융 허가는 각각 구분한다.

후속 [112 목적·당시 평가 제한](112-private-transfer-purpose-boundary.md)은 새 자동 의도/실행에 목적 근거를 요구하고 소액·미평가의 자동 일정을 별도 검토로 종료한다. 이전 문서의 실행 수치는 당시 범위이며 법정 근거·동일인·금융 사용 판단과 구분한다.

## 문제와 기존 동작

103의 운영 조회는 FOUND를 INFORMATION_EXCHANGE_REQUIRED로 표시했다. FOUND는 상대 사업자를 기술적으로 발견했다는 뜻이므로 금액·대상·제공 목적 판단이 미완료인 현재 상태와 맞지 않았다. 110은 입금 인식 시점의 평가·미평가를 보존하지만 운영 조회에서 그 근거를 확인할 수 없었다.

이전 상태 문자열은 삭제하고 ORIGIN_FOUND_REVIEW_REQUIRED로 바꾼다. 옛 이름을 돌려주는 호환 경로는 만들지 않는다. 금융 기록의 원본이나 이전 검색 결과는 수정하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [업비트 공식 트래블룰 안내](https://support.upbit.com/hc/ko/articles/4498679629337)를 다시 확인했다. 금액과 입금 경로에 따라 추가 확인 절차가 다르고 반복 소액도 감시 대상이 될 수 있다. 따라서 검색 성공·단일 소액 참고값만으로 모든 경우의 개인정보 교환 의무나 면제를 추정하지 않는다.

[CodeVASP 정보 요청 API](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)는 TXID 검색으로 찾은 상대에게 누락 정보를 요청하는 기술 계약이다. 이 API 계약 자체가 우리 서비스의 모든 입금에 개인정보를 제공할 법적 근거가 되지는 않는다. 국내 조문·적용 시점·송신 요청과 수취/인식 시점 차이는 [109 근거 검토](109-durable-bitcoin-krw-reference-price.md)와 [110 시점 결합](110-pending-deposit-price-capture.md)을 따른다.

기존 플랫폼의 운영자 역할·추가 인증·감사 경계를 재사용하고, 설치된 Spring JDBC/BigDecimal로 불변 원본을 읽는다. 새 라이브러리나 별도 조회 서버는 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 감수하는 제약·검토한 대안 |
| --- | --- | --- |
| 기존 보호된 조회에 valuation 추가 | 같은 업무에서 당시 원본과 참고 구간을 확인한다 | 응답 필드가 늘고 플랫폼 DB 조회가 한 번 추가된다. 별도 API는 인증·감사 경계를 중복시켜 채택하지 않았다 |
| 정확한 값으로100만 원 경계 비교 | 표시 반올림으로 상하 구간이 뒤집히지 않는다 | 결과는 내부 참고 구간이다. 법정 면제나 전송 허가로 재사용할 수 없다 |
| 없는 평가는 UNKNOWN | 가격 부재나 과거 자료 누락을0원과 구분한다 | 해당 입금의 별도 판단이 필요하다. 현재 가격을 대신 넣는 대안은 과거 근거를 바꾼다 |
| FOUND는 추가 판단 필요로 표시 | 기술적 발견을 개인정보 제공 확정으로 오인하지 않는다 | 실제 제공 목적·상대 경로 판단은 아직 후속 업무다. 문구만으로 그 업무를 완료했다고 주장하지 않는다 |
| 가격·환산액은 문자열 | JSON 클라이언트의 큰 숫자/소수 정밀도를 보존한다 | 계산할 소비자는 십진수 처리가 필요하다. JavaScript number로 자동 변환해 비교하는 방식은 피한다 |

이 조회는 현재 가격 HTTP·CodeVASP·엔진을 호출하지 않는다. 불변 평가를 읽으므로 별도 현재 가격 잠금은 추가하지 않는다. 검색·체인·고객 자료와 같은 순간의 금융 승인 스냅샷이라고 주장하지 않는다.

## 구현과 관련 코드·문법

출처: [BitcoinDepositValuationReview.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinDepositValuationReview.java#L25), 현재 소스의 부분 발췌.

```java
    public record Review(boolean captured, String status, String reason, String policyId,
        Instant referenceAt, Long priceAttemptId, String price, String krwValue,
        String referenceBand, String assurance) {}
```

Java record는 응답 필드를 선언하는 불변 자료형이다. TypeScript의 읽기 전용 자료 구조와 비슷한 용도지만 JVM의 생성자·접근자·동등성 동작도 제공한다. assurance는 모든 경우에 REFERENCE_ONLY_PURPOSE_AND_RELEASE_UNDECIDED를 반환한다. 가격 응답 원문·신원·생일·암호문은 응답에 넣지 않는다.

출처: [BitcoinDepositValuationReview.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinDepositValuationReview.java#L48), 현재 소스의 부분 발췌.

```java
        if (rows.isEmpty()) {
            return new Review(false, "NOT_CAPTURED", "VALUATION_NOT_RECORDED", null,
                null, null, null, null, "UNKNOWN", ASSURANCE);
        }
```

아직 평가가 없는 입금은 NOT_CAPTURED/UNKNOWN으로 반환한다. 새 가격 수집이나 소급 평가를 하지 않는다. 기존 평가가 UNVALUED라면 당시 사유와 남아 있는 가격 원본 참조를 그대로 보존한다. 내부 메서드는 MANDATORY로 기존 트랜잭션을 요구하며 외부 요청은 현재 운영 역할·추가 인증을 확인한 상위 서비스만 통과한다.

출처: [BitcoinDepositValuationReview.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinDepositValuationReview.java#L61), 현재 소스의 부분 발췌.

```java
    static String band(BigDecimal value) {
        if (value == null) return "UNKNOWN";
        if (value.signum() <= 0) throw new IllegalStateException("입금 평가액은 양수여야 합니다");
        return value.compareTo(REFERENCE_BOUNDARY) >= 0 ? "AT_LEAST_1M_KRW_REFERENCE" : "BELOW_1M_KRW_REFERENCE";
    }
```

BigDecimal.compareTo는 scale 차이보다 수치 자체로 비교한다. 정확히1000000이면 상위 참고 구간에 포함하고, 소수점 아래의 아주 작은 차이도 반올림하지 않는다. 이 구간은 송신 당시 법정 기준액의 증명이나 법적 의무 판정이 아니다.

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L66), 현재 소스의 부분 발췌.

```java
            reason=switch(status){
                // 출처 발견은 기술적 검색 결과다. 금액·제공 목적·상대 경로 판단을 완료했다고 표시하지 않는다.
                case "FOUND"->"ORIGIN_FOUND_REVIEW_REQUIRED";
                case "NOT_FOUND","UNKNOWN_REQUEST","AMBIGUOUS"->"MANUAL_REVIEW_REQUIRED";
                default->ended?"AUTO_WINDOW_EXPIRED":status.equals("UNSTARTED")?"AWAITING_SUBMISSION":status.equals("RUNNING")?"IN_PROGRESS":"AWAITING_RESULT";
```

검색 결과와 후속 업무 판단을 분리한다. FOUND라도 상대 경로·현재 고객·금액과 목적 판단이 필요하다. 과거 INFORMATION_EXCHANGE_REQUIRED 응답 경로는 삭제했다. 금융 허가나 자동 개인정보 제공을 추가하지 않았다.

## 작성한 테스트의 시나리오와 기대 결과

신규 운영 조회 통합6개:

1. 평가가 없는 입금은 현재 가격이 있어도 UNKNOWN, 가격 호출0.
2. 당시 UNVALUED는 나중에 가격을 얻어도 미평가 유지.
3. 새 현재 가격 이후에도 당시 원본 ID·정확한 환산액 유지, 실제 HTTP 응답은 문자열·no-store.
4.999999.918원은 하위 참고 구간이며 금융 부적격 고객이 승인되지 않음.
5. 고객의 존재/부재 입금 운영 조회는 모두403, 내부 단독 호출은 트랜잭션 부재로 거절.
6. 조회 감사 실패 시 성공 응답 금지.

신규 단위5개는 평가 부재·경계 직전·정확한 경계·경계 직후·0/음수 거부를 검증한다. 기존 출처 조회13개는 FOUND 안내 변경과 현재 출처/누락/감사 회귀를, 기존 운영 근거14개는 현재 역할·추가 인증·존재 노출·체인/정산·감사 경계를 다시 확인한다. 서로 다른 Spring/JPA 맥락의 금융 기록이 충돌하지 않게 세 묶음마다 별도 PG 서버를 생성했다. 금융 이력을 지우거나 불변 제약을 끄지 않았다.

## 실행 명령과 결과

- 최초 `./gradlew compileTestJava --console=plain`: 기존 두 테스트의 직접 생성 코드가 새 생성자 의존성을 전달하지 않아 컴파일 오류2개. 두 호출에 실제 평가 조회 빈을 전달하도록 수정했다. 호환 생성자를 추가하지 않았다.
- `python3 scripts/verify_deposit_valuation_review.py`: 신규11+출처13+인가14=고유38개 통과. 실패/오류/제외0. 묶음별 소유 PostgreSQL16 세 서버는 모두 정리됐다.
- 실행기는 외부 서비스 관련 환경 접두값을 제거하고 봇0·소비자 자동 시작 금지·엔진/Kafka 루프백 비활성 주소를 고정한다. 가격·신원 공급사 HTTP는 대역이며 실제 메인넷이나 개인정보 교환은 하지 않았다.
- 고객/운영 두 JAR 빌드와 새 조회 클래스 포함 확인, `git diff --check`·Python 구문 검사를 통과했다. 기존 deprecated/unchecked/JVM CDS 경고는 남아 있다.
-110의399개는110 당시 검증이다.111의 신규 검증 수에 합산하거나 전체 서비스가 재검증됐다고 표시하지 않는다. 최종 소스에서 추가한 설명 주석은 동작을 바꾸지 않는다.

## 남은 범위

금액·목적·상대 경로·고객/거래 위험을 결합하는 실제 제공 판단과 현재 개인정보 제공 실행 경계의 연결은 아직 미완료다. 자동 제공은 계속 기본 false이며 이 응답의 구간만 보고 활성화해서는 안 된다. 사용 가능 잔액/엔진 반영도 이번 변경에 포함하지 않았다.

## 작성한 테스트 코드


[BitcoinDepositValuationReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositValuationReviewIntegrationTest.java)

- `missingCaptureIsUnknownAndDoesNotBorrowCurrentPrice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unavailableCaptureStaysUnknownAfterLaterPriceArrives`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `exactHistoricalValueAndSourceSurviveNewCurrentPrice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `belowBoundaryIsReferenceOnlyAndDoesNotGrantExemption`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerCannotReadValuationAndInternalReaderNeedsTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `auditFailurePreventsSuccessfulValuationResponse`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinDepositValuationReviewTest.java](../../src/test/java/dustin/cex/domains/valuation/BitcoinDepositValuationReviewTest.java)

- `missingIsUnknownRatherThanZeroOrExempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `justBelowBoundaryIsNotRoundedUp`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `exactBoundaryBelongsToUpperReferenceBand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `justAboveBoundaryIsComparedExactly`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nonPositiveValuesCannotLookLikeValidSmallDeposits`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 5f0e4f2](https://github.com/GiHoon1123/Cex-Platform-Service/commit/5f0e4f2b19f988c291a63088082dd4968b69e2a6)
- [격리 실행 증적](../reference/111-deposit-valuation-operator-review-verification-2026-10-25.json)
