# 운영 조회에서 전송·정보 비교·작업 이력을 최소 정보로 연결하기

기존 보호된 입금 근거 조회에 전송 목적·최신 결과·정보 비교 사유·자동 작업 상태를 연결한다. 개인정보 복호화나 외부 호출 없이 최소 이력을 읽고, 요청 당시 근거와 현재 근거를 구분한다.

## 문제와 기존 동작

113은 정상 수신과 요청 당시 고객 자료의 일관성을 나눠 저장했지만 운영 조회에서는 검색 결과까지만 볼 수 있었다. 운영자가 원문 접근 없이도 전송 전·미해결 시작·결과 불명·정보 부족/불일치·자동 작업의 별도 검토를 구분할 수 있어야 한다.

추가로 요청 뒤 새 체인 관찰이 생길 수 있다. 현재 관찰 ID를 과거 비교의 근거로 바꿔 표시하면 당시 요청을 재현할 수 없다. 실제 실행이 저장한 검색·디렉터리·체인 판단/관찰·CDD 평가 참조를 함께 반환한다.

## 기존 제품과 공식 자료의 접근

[103 운영 조회](103-operator-deposit-origin-search-review.md)와 [111 평가 근거 조회](111-deposit-valuation-operator-review.md)의 같은 현재 운영 역할·5분 추가 인증·대상 접근·필수 감사·no-store 경계를 재사용한다.2026-10-25 [OWASP API1:2023](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)의 객체별 인가와 테스트 원칙도 다시 확인했다. UUID를 안다는 사실이나 JWT가 있다는 사실만으로 대상 접근을 허용하지 않는다.

기존 JDBC 읽기와 불변 원본/현재 포인터를 사용한다. 외부 제품의 비공개 어드민 내부 구조를 추측하지 않고, 이미 구현해 검증한 플랫폼의 인가된 최소 정보 투영 패턴을 확장했다. 설치된 기능만 사용하며 패키지는 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 기존 조회에 최소 이력 연결 | 운영자가 상태·사유·원본 참조를 같은 대상에서 확인한다 | SQL 조인/응답 필드가 늘어난다. 별도 조회 API의 인증/감사 중복을 만들지 않았다 |
| 성명·생일·주소 원문·암호문·신원 지문 제외 | 단순 목록/근거 확인에서 개인정보 노출을 줄인다 | 실제 불일치 해결에는 별도의 인가된 원문 검토가 필요하다 |
| 현재 전송 포인터만 사용 | 새 시작이 미완료일 때 과거 결과로 오인하지 않는다 | RUNNING은 시작 기록이며 워커 생존을 보장하지 않는다. 과거 성공 폴백은 없다 |
| 요청 당시 근거 ID를 그대로 반환 | 현재 체인/고객 자료와 당시 비교를 구분한다 | 운영자가 서로 다른 시점의 근거를 이해해야 한다. 현재 ID로 덮어 쓰지 않는다 |
| 메타데이터 조회와 금융 결정을 분리 | 요약의 CONSISTENT가 금융 허가가 되지 않는다 | 금융 결정에는 인증된 원본·현재 고객/체인/위험의 재검증이 필요하다 |

이 읽기는 암호문을 해독해 원본을 재인증하는 작업이 아니다. 저장된 상태와 참조의 요약이며 `METADATA_ONLY_NOT_A_FINANCIAL_DECISION`을 함께 반환한다. 감사용 최소 이력 확인과 금융 승인 판단을 구분한다.

## 구현과 관련 코드·문법

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L17), 현재 소스의 부분 발췌.

```java
    public record Assessment(String policy,String status,String reason,Instant recordedAt){}
    public record RequestBasis(Long searchAttemptId,Long directoryAttemptId,Long chainDecisionId,Long observationId,UUID cddAssessmentId){}
    public record Transfer(UUID intentId,Instant intendedAt,String purpose,String purposePolicy,UUID attemptId,
        Instant startedAt,String status,String reason,Instant recordedAt,RequestBasis requestBasis,Assessment assessment){}
    public record Job(String state,String reason,Instant nextAttemptAt,Instant leaseUntil,Instant updatedAt){}
    public record Review(boolean providerConfigured,String sourceRef,long historicalSearches,String reason,Instant readAt,
```

Java record로 응답 필드를 제한한다. RequestBasis는 실제 전송 시도의 과거 근거 참조다. 현재 Source의 고객/체인 근거와 이름부터 구분한다. Assessment는 정책·상태·사유·기록 시각만 포함하고 원문 개인정보는 포함하지 않는다. Job의 임대 기한은 저장된 작업 상태이며 실행 스레드의 생존 확인이 아니다.

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L53), 현재 소스의 부분 발췌.

```java
             LEFT JOIN codevasp_transfer_intents i ON i.search_id=s.id
             LEFT JOIN codevasp_transfer_purposes purpose ON purpose.intent_id=i.id
             LEFT JOIN codevasp_transfer_heads th ON th.intent_id=i.id
             LEFT JOIN codevasp_transfer_attempts ta ON ta.id=th.attempt_id
             LEFT JOIN codevasp_transfer_results tr ON tr.attempt_id=ta.id
             LEFT JOIN codevasp_transfer_detail_assessments assessment ON assessment.attempt_id=tr.attempt_id
             LEFT JOIN codevasp_transfer_jobs job ON job.search_id=s.id
```

현재 설정의 검색 s에 연결된 의도와 그 의도의 최신 전송 head만 읽는다. LEFT JOIN은 의도·실행·결과·비교가 아직 없는 상태를 null로 유지한다. 빈 자료를 과거 성공이나 자동 허가로 대신 채우지 않는다. 같은 SELECT의 커밋 스냅샷에서 이 기술 이력을 읽는다.

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L81), 현재 소스의 부분 발췌.

```java
        Transfer transfer=row.get("transfer_intent_id")==null?null:new Transfer((UUID)row.get("transfer_intent_id"),at(row,"intended_at"),(String)row.get("purpose"),(String)row.get("purpose_policy"),(UUID)row.get("transfer_attempt_id"),at(row,"transfer_started_at"),
            row.get("transfer_status")==null?(row.get("transfer_attempt_id")==null?"UNSTARTED":"RUNNING"):(String)row.get("transfer_status"),(String)row.get("transfer_reason"),at(row,"transfer_recorded_at"),basis,assessment);
```

의도만 있으면 UNSTARTED, 시작이 있고 결과가 없으면 RUNNING이다. 결과가 있으면 해당 결과의 상태·사유를 그대로 보인다. TypeScript의 null 검사와 비슷한 표현이지만 데이터의 권위는 서버의 현재 포인터와 DB 계약에 있다. 클라이언트가 상태를 추측해 만드는 방식이 아니다.

## 작성한 테스트와 기대 결과

신규 `CodeVaspTransferReviewIntegrationTest`11개:

1. FOUND만 있으면 전송/비교/작업을 추측해 만들지 않음.
2. 의도만 있으면 목적 정책과 UNSTARTED, 실행/비교 없음.
3. 정상 수신·일관성의 참조/코드만 실제 HTTP로 반환하고 no-store, 성명·생일·주소·암호문·MAC 제외.
4. 수신 성공이어도 불일치는 REVIEW_REQUIRED로 표시.
5. 전송 결과 불명에 비교 결과를 빌리지 않고 추가 요청도 하지 않음.
6. 외부 호출 대기 중 DB 트랜잭션 없이 운영 조회 가능, 현재 RUNNING과 비교 부재 표시.
7. 별도 검토 작업은 제공 의도 없이 존재할 수 있고 다음 시도 없음.
8. 접속 주체 변경 시 과거 다른 주체의 전송 이력을 현재란에 노출하지 않음.
9. 고객은 존재/부재 입금의 운영 조회 모두403.
10. 필수 감사 실패 시 메타데이터 성공 응답 금지.
11. 실제 새 체인 관찰 뒤에도 과거 실행의 observation ID와 검색/디렉터리/판단/CDD 참조가 유지됨.

공통 read 도우미는 조회 전후 원장·의도·시도·결과·비교·작업 행 수가 같음을 검사한다. 외부 가격/정보 교환 요청과 사용 가능 잔액·엔진/Kafka 변경이 없는지도 확인한다. 기존 출처13개와 운영 인가14개도 다시 실행했다.

## 실행 명령과 결과

- 첫 `python3 scripts/verify_codevasp_transfer_review.py`: 신규10+기존27=37개 통과. 이후 현재/과거 관찰 참조 구분을 추가했다.
- 최종 같은 명령: 신규11+출처13+운영 인가14=고유38개 통과, 실패·오류·제외0. 첫37개를 다시 합산하지 않는다.
- 묶음별 소유 PostgreSQL16 세 서버를 각각 생성해 서로 다른 JPA/금융 기록 맥락을 격리하고 모두 정리했다. 봇0·소비자 자동 시작 금지·외부 접두 환경 제거·비활성 루프백 주소를 적용했다. 원본 XML/로그는 `.local/cycle114-proof`에 보존했다.
- 각 묶음에서 고객/운영자 JAR 빌드 성공. Python 구문·문서 실제 코드 발췌·로컬 링크·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.
- 이번에는 읽기 투영과 응답 필드만 바꿨다.113의254개는113 당시 검증이며114의 실행 수로 다시 사용하지 않는다. 실제 공급사/메인넷·운영 인증 검증은 하지 않았다.

## 남은 범위

원문이 필요한 담당자의 별도 인가·복호화/무결성 검증·접근 증적, 불일치 해결·현재 위험/금융 해제·엔진 반영은 남는다. AUDITOR에게 이 최소 이력 조회가 허용된다는 사실을 개인정보 원문 접근 권한으로 확대하지 않는다. 현재/과거 근거가 다르면 금융 판단 단계에서 명시적으로 처리해야 하며 요약 화면에서 자동 승인하지 않는다.

## 작성한 테스트 코드


[CodeVaspTransferReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferReviewIntegrationTest.java)

- `foundSearchAloneDoesNotInventTransferOrAssessment`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `reservedIntentShowsPurposeAndExplicitlyUnstartedTransfer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `receivedConsistentSummaryContainsOnlyReferencesAndCodes`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mismatchedDetailsRemainReviewDespiteSuccessfulReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownOutcomeCannotBorrowAssessmentOrTriggerAnotherRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `inFlightReadShowsCurrentStartWithoutWaitingForExternalCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `manualReviewJobCanExistWithoutDisclosableIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedConnectionAuthorityDoesNotExposeOldPrivateRecords`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerCannotUsePrivateMetadataView`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `auditFailureCannotReturnPrivateMetadata`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `newerChainObservationDoesNotRelabelHistoricalRequestBasis`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 d244ad4](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d244ad47053addfb1f1004ea42ef5725d29aace7)
- [격리 실행 증적](../reference/114-private-transfer-operator-metadata-verification-2026-10-25.json)
