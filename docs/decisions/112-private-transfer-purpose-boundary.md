# 자동 정보 요청의 목적과 당시 평가 근거를 제한하기

자동 출처 정보 요청을 당시 원화 참고평가가 확인된 입금으로 제한하고, 요청 목적·정책·원본을 봉인해 실행 직전에 다시 대조한다. 소액·미평가는 고객 신고 원문을 열기 전에 차단하고 자동 재시도를 종료한다. 이 내부 제한은 법정 면제·동일인 판정·자금 사용 허가를 뜻하지 않는다.

후속 [113 수신 자료 일관성 분류](113-received-transfer-detail-consistency.md)는 정상 수신과 요청 당시 고객 자료의 비교 결과를 분리한다. 현재 고객 적격성·동일인 확정·금융 해제는 별도다.

## 문제와 기존 동작

108 자동 일정은 FOUND 검색을 대상으로 제공 의도를 예약했다. 고객·체인·상대 정책은 검사했지만 금액·목적 근거가 없었다. 기본 비활성화는 우발적인 실행을 막지만 활성화 뒤의 대상 범위도 코드로 제한해야 한다.111에서 FOUND를 추가 검토로 표시한 이유도 여기에 있다.

이제 자동 경로는110의 불변 평가가 VALUED이고 정확한 참고값이100만 원 이상인 입금만 `DEPOSIT_ORIGIN_VERIFICATION` 목적 후보로 받는다. 현재 고객·체인·현재 검색·상대 허용 정책과 키 확인도 계속 필요하다. 자동 개인정보 제공 설정은 여전히 기본 false다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [업비트 공식 안내](https://support.upbit.com/hc/ko/articles/4498679629337)와 [CodeVASP 정보 요청 계약](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)을 다시 대조했다. 공개 안내는 금액·경로에 따른 절차와 반복 소액 위험을 구분한다. 기술 API는 누락된 이전 정보를 상대에게 요청하는 방법을 정의한다. 기술적으로 조회할 수 있다는 사실만으로 모든 고객의 정보를 제공할 근거가 생기지는 않는다.

국내 조문과 개정 유예·이전 요청 시점/수취 시점 차이는 [109](109-durable-bitcoin-krw-reference-price.md#공식-자료와-적용-시점), [110](110-pending-deposit-price-capture.md)을 따른다. **이번100만 원 조건은 자동 요청을 더 좁히는 내부 최소 범위다.** 우리 보류 인식 시점의 외부 참고가격이 송신자의 이전 요청 시점 금액이나 법정 표시가격 요건을 증명한다고 주장하지 않는다. 운영에서 실제 제공 근거·고지/계약·상대 실사·법정 산정 시점을 확인해야 한다. 국내 적용/시행 예정/외부 확인을 이 조건 하나로 합치지 않는다.

기존 Spring JDBC·거래 트랜잭션·CddCrypto의 인증 암호화/HMAC·PostgreSQL 지연 제약을 재사용한다. 별도 승인 서버·정책 언어·라이브러리는 추가하지 않았다. [transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 업무와 전달 의도를 함께 저장하는 원칙도 유지한다. DB 커밋과 원격 처리 전체를 단일 원자성으로 설명하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·검토한 대안 |
| --- | --- | --- |
| 불변 참고평가100만 원 이상으로 자동 경로 제한 | 소액/미평가에 무조건 개인정보 요청을 만들지 않는다 | 별도 검토 대상이 늘어난다. 모든 FOUND를 자동 전송하는 대안은 목적·금액 범위가 불명확하다 |
| 목적과 평가 원본을 의도에 봉인 | 무엇을 위해 어떤 자료로 요청했는지 재현한다 | 기록·정책 버전 관리가 필요하다. 자유 텍스트 사유나 기능 플래그만으로 판단하지 않는다 |
| 의도와 목적 기록을 같은 트랜잭션으로 저장 | 감사·저장 실패의 부분 기록을 막는다 | PG 지연 제약과 정해진 쓰기 순서에 결합한다. 누락을 로그 경고로 넘기지 않는다 |
| 과거 목적 없는 의도는 새 실행에 사용하지 않음 | 옛 범위가 새 자동 경로로 우회하지 않는다 | 과거 의도는 조회용으로 보존되고 별도 판단이 필요하다. 기존 금융/감사 이력을 지우거나 목적을 자동 소급 생성하지 않는다 |
| 미평가/소액은 REVIEW_REQUIRED로 자동 일정 종료 | 바뀌지 않는 원본 때문에 반복 시도·외부 요청이 누적되지 않는다 | 운영 검토가 필요하다. 현재가를 다시 읽어 자동으로 재분류하지 않는다 |

소액이 안전하다거나 보고/이전 정보 의무에서 면제된다는 판단은 하지 않는다. 반복 소액·미확인 경로·거래 위험·법적 근거를 다루는 후속 업무를 자동 고액 출처 요청과 구분한다. 이 제한을 통과해도 현재 고객·체인·상대 조건이 나쁘면 제공 의도를 만들지 않는다.

## 구현과 관련 코드·문법

출처: [CodeVaspTransferPurposePolicy.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferPurposePolicy.java#L35), 현재 소스의 부분 발췌.

```java
            SELECT price_attempt_id,reference_at,krw_value FROM bitcoin_deposit_valuations
             WHERE receipt_id=? AND policy_id='BTC_PENDING_KRW_REFERENCE_V1'
               AND status='VALUED' AND krw_value>=?
            """, receipt, MINIMUM);
        if (rows.size() != 1) throw held();
```

현재가가 아니라 해당 입금의 원래 평가를 읽는다. SQL의 >=는 정확한 numeric 값을 비교한다. 평가가 없거나 미평가·소액이면 ReviewRequired 예외를 발생시킨다. MANDATORY는 호출자의 현재 트랜잭션 참여를 강제한다. 이 정책은 신원 신고 원문을 해독하기 전에 실행된다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L66), 현재 소스의 부분 발췌.

```java
            purposes.record(id,basis.prepared().purpose());
            audit.record(null,"CODE_TRANSFER_INTENT_RESERVED","codevasp-transfer:"+id,"SUCCESS","SEARCH:"+searchId+":ATTEMPT:"+basis.searchAttempt());
            return load(id);
```

봉인된 Prepared에도 Purpose가 들어가고, 별도 불변 목적 표는 원래 평가와 연결된다. 의도와 목적·감사를 함께 커밋한다. TypeScript의 일반 비동기 호출과 달리 여기서는 TransactionTemplate의 같은 DB 트랜잭션 안에서 실행된다. 중간 실패는 모두 롤백한다.

출처: [purpose-schema.sql](../../src/main/resources/travelrule/purpose-schema.sql#L30), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER codevasp_transfer_purpose_complete_check AFTER INSERT ON codevasp_transfer_intents
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_purpose_complete();
```

새 의도가 커밋될 때 같은 트랜잭션의 목적 기록이 필요하다. 삽입 검사는 입금·가격 원본 ID·기준 시각·정확한 환산액을 대조한다. 목적 표 UPDATE·DELETE·TRUNCATE는 금지한다. 실행 원본의 별도 삽입 트리거도 기록된 목적 참조를 요구한다. 기존 기록에는 새 목적을 자동으로 보충하지 않는다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L130), 현재 소스의 부분 발췌.

```java
        var sealedPurpose=original.path("purpose");var purpose=current.prepared().purpose();
        if(!purpose.policy().equals(sealedPurpose.path("policy").asString())
            ||!purpose.purpose().equals(sealedPurpose.path("purpose").asString())
            ||!purpose.receiptId().toString().equals(sealedPurpose.path("receiptId").asString())
            ||!purpose.referenceAt().toString().equals(sealedPurpose.path("referenceAt").asString())
            ||!sealedPurpose.path("priceAttemptId").isIntegralNumber()||sealedPurpose.path("priceAttemptId").longValue()!=purpose.priceAttemptId()
            ||!sealedPurpose.path("krwValue").isNumber()||sealedPurpose.path("krwValue").decimalValue().compareTo(purpose.krwValue())!=0)throw held();
```

봉인한 목적의 정책·대상·시점·가격 참조와 금액을 다시 비교한다. JSON 객체 트리의 숫자 노드 형식이 다를 수 있으므로 원화 금액은 BigDecimal.compareTo로 비교한다. 원래 암호문을 HMAC/AAD 검증한 뒤에만 이 필드를 신뢰한다. 현재가를 이용한 재평가나 과거 만료 키의 무리한 역직렬화를 하지 않는다.

## 작성한 테스트

신규 `CodeVaspTransferPurposeIntegrationTest`10개:

1. 목적·원래 평가액·가격 ID가 봉인된 의도와 불변 표에 일치, 예약 멱등.
2. 소액은 나중의 높은 가격을 빌리지 못하고 고객 신고 복호화 전 차단.
3. 미평가도 나중의 가격으로 자동 요청을 만들지 못함.
4. 평가 기록 없는 대상은 목적 후보가 되지 않음.
5. 기록된 목적 없는 의도는 실행 근거로 사용하지 못함.
6. 목적 UPDATE·DELETE·TRUNCATE 차단.
7. SQL로 위조한 평가액은 원본 대조에서 거부.
8. 의도 감사 실패 시 목적과 의도 모두 롤백.
9. 서비스가 목적 저장을 누락해도 실제 지연 제약으로 커밋 차단, 원인 메시지 확인.
10. 목적 조회·실행 근거 확인은 기존 업무 트랜잭션 없으면 거부.

[CodeVaspTransferProcessingIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferProcessingIntegrationTest.java)의 `smallReferenceRequiresReviewWithoutPrivateRequestOrAutomaticRetry`와 `missingValuationRequiresReviewWithoutPrivateRequestOrAutomaticRetry`를 추가했다. 소액과 미평가 모두 REVIEW_REQUIRED·다음 실행 없음·제공 의도 없음·개인정보 요청0이며 재호출해도 작업을 다시 실행하지 않는다. 기존 의도13/실행19/일정14/수명9도 새 명시적 합성 가격 원본으로 다시 확인했다. 금융 fixture는 삭제·트리거 해제로 맞추지 않았다.

별도 선행 수정은 [CodeVaspTransferWorkerTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransferWorkerTest.java)의 `fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks`다. 부하에 따라 실패할 수 있는220밀리초 관찰 창 대신 실제 첫 완료/두 번째 시작의 단조 시각 차이가300밀리초 이상인지 확인한다. 고정 주기로 밀린 작업을 따라잡는 잘못된 구현은 여전히 실패한다.

## 실행 명령과 결과

- 최초 목적 테스트 컴파일은 상속된 verify(boolean)와 Mockito.verify 이름 충돌1개로 실패했다. Mockito 호출을 명시해 수정했다.
- 첫42개 실행은 목적10/의도13 통과, 기존 실행19 중14개 실패했다. 목적 전체 JSON 노드 비교가 정확한 금액의 숫자 표현 차이를 의미 변경으로 오인했다. 원래 십진수와 직접 BigDecimal 비교로 수정했다. 중간 성공42개는 최종 결과에 중복 합산하지 않았다.
- 첫 일정/수명25개는 테스트 대역 재설정 시 기존 응답이 null 인자로 실행된2개와 짧은220밀리초 관찰 창의 수명1개가 실패했다. doAnswer 재설정과 실제 실행 시각 검증으로 수정했다. 제품의 정합성·금액·보호 조건을 낮추지 않았다.
- 최종 `python3 scripts/verify_codevasp_transfer_intents.py`:42개 통과(기존32+신규10).
- 최종 `python3 scripts/verify_codevasp_transfer_processing.py`:25개 통과(기존23+신규2).
- 최종 `python3 scripts/verify_engine_inbox.py`:182개 통과. 상위 실행에서 외부 서비스 관련 환경 접두값을 제거하고 소비자 자동 시작을 금지했다.
- 고유249개/249회 실행, 실패·오류·제외0. 세 묶음은 각각 소유 PostgreSQL16 서버를 사용하고 모두 정리했다. 전체 원본 XML과 로그는 `.local/cycle112-proof`에 있다.
- 고객/운영자 JAR 빌드·목적 정책 클래스/SQL 포함, Python 구문·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

실제 공급사·Cipher 이미지·유료 서비스·메인넷·실제 고객의 정보를 사용하지 않았다. 기존 소프트웨어 인증/웹훅·PG·업무 트랜잭션은 실제 코드이며 가격/외부 제공 HTTP만 대역이다. 과거110/111의 시험 개수는 이번 실행에 합산하지 않는다.

## 남은 한계

이 정책은 자동 요청의 내부 목적과 최소 범위를 명확히 하는 통제다. 법정 산정 시점·개인정보 제공의 실제 근거와 계약·상대 실사는 운영/외부 확인 사항이다. 소액·미평가·미확인 경로의 별도 판단, 수신 정보와 현재 고객의 업무상 일치 확인·위험 판단, 운영 해결/금융 해제·엔진 반영은 미완료다. CodeVASP 정상 응답 수신만으로 같은 사람이나 자금 사용 허가를 확정하지 않는다. 개인정보 자동 제공 기본 false와 봇0은 유지한다.

선행 수명 테스트 수정: [323281f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/323281f). 이 커밋은 테스트 검증 방법만 바꾸며 목적 제한 구현과 독립적이다.

## 작성한 테스트 코드


[CodeVaspTransferPurposeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferPurposeIntegrationTest.java)

- `exactPurposeAndOriginalValuationAreSealedWithIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `belowMinimumCannotBorrowLaterHighPriceOrOpenCustomerPlaintext`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unvaluedCannotBorrowLaterPriceForAutomaticRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `noCapturedValuationCannotBecomePurpose`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unrecordedIntentCannotBeUsedForExecution`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `purposeFactsCannotBeUpdatedDeletedOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `forgedPurposeValueIsRejectedByDatabase`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `intentAuditFailureRollsBackPurposeFactToo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `omittedPurposeFactPreventsIntentCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `purposeHooksRequireExistingBusinessTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 014ff8b](https://github.com/GiHoon1123/Cex-Platform-Service/commit/014ff8b759b2eebb7dfdfdc4f657a74fd904d147)
- [격리 실행 증적](../reference/112-private-transfer-purpose-boundary-verification-2026-10-25.json)
