# 정상 수신과 고객 자료의 일관성을 분리해 기록하기

정상 수신한 송수신인 정보와 요청 당시 봉인한 고객 자료를 비교하고, 일관성 분류를 암호화 응답 자료·감사와 함께 커밋한다. 누락·이름 구조·불일치는 별도 검토로 남기며 금융 해제나 동일인 확정으로 표시하지 않는다.

후속 [114 보호된 전송/비교 이력 조회](114-private-transfer-operator-metadata.md)는 복호화 없이 최소 상태·사유와 요청 당시 근거를 제공한다. 과거 실행 수치와 현재 응답 계약을 구분한다.

## 문제와 기존 동작

107은 DATA_RECEIVED를 정상 수신이라는 기술 상태로 보존했다.112까지 요청 목적·평가·현재 고객/체인·상대 정책을 확인하지만 받은 이름·생일과 요청 당시 확인된 고객 정보의 일관성 분류는 없었다. 기술적 수신 성공을 곧바로 신원 확인 성공으로 해석하면 불일치 자료로 자금을 풀 위험이 있다.

이번에는 DATA_RECEIVED를 그대로 수신 사실로 유지하면서 별도 CONSISTENT/REVIEW_REQUIRED 분류를 추가한다. 비교 대상은 **요청 당시** Prepared의 전체 이름·생일·수취 주소다. 외부 대기 중 고객 상태가 바뀌어도 과거 응답을 지우지 않으며, 이 분류가 현재 고객 적격성을 대신하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [업비트 공식 안내](https://support.upbit.com/hc/ko/articles/4498679629337)에서 경로별 계정 정보 일치·계정주/출처 확인을 다시 확인했다. 연동 경로마다 절차가 다르므로 타사의 모든 판단을 단일 성명 비교로 일반화하지 않는다. [CodeVASP 요청 계약](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)과 [104의 파싱 기록](104-codevasp-private-transfer-data.md)이 정의한 검증 후 추출 자료를 입력으로 사용한다. 이번 IVMS 안내 페이지 도구 재조회는 접근 오류였으므로 전체 표준을 재검증했다고 표시하지 않는다.

설치된 JDK21 [Normalizer](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/Normalizer.html)는 Unicode 정규화 기능을 제공한다. 별도 문자열/이름 유사도 라이브러리를 추가하지 않았다. NFC와 양끝 공백 정리만 사용하고, 음역·대소문자·성/이름 순서·내부 공백을 추측해 보정하지 않는다. 일반 텍스트 검색의 유사도와 금융 업무의 자료 일치 판단은 요구사항이 다르다.

현재 확인된 고객 이름은 전체 이름 한 필드다. 응답이 성/이름으로 나뉘어 있으면 현재 자료만으로 조합 순서를 확정하지 않고 검토로 보낸다. 수취인 생일은 프로토콜상 생략 가능하지만, 정보가 부족한 상태를 자동 일관성으로 만들지 않는다. 이 비교는 동일 고객 자료와 일관된 자동 후보만 좁히는 내부 정책이며 다른 사람의 정당한 송금을 사기로 확정하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 수신 상태와 정보 일관성을 분리 | 응답 성공과 업무 판단을 혼동하지 않는다 | 두 상태를 함께 읽어야 한다. 불일치를 통신 실패로 바꾸고 재요청하는 대안은 반복 개인정보 교환을 만든다 |
| 전체 이름·생일·수취 주소를 엄격 비교 | 누락/다른 고객/주소 혼합을 자동 일치로 처리하지 않는다 | 합법적인 이름 표기 차이·분리된 이름·생일 없는 응답도 검토가 필요하다. 유사도/음역/순서 추정은 채택하지 않았다 |
| NFC와 양끝 공백만 정규화 | 같은 문자의 정규 조합 표현 차이를 처리한다 | 호환 문자·대소문자·내부 공백 차이는 검토로 남는다. NFKC나 퍼지 비교로 더 넓게 합치지 않는다 |
| 응답·분류·감사를 한 트랜잭션에 저장 | 기록 누락과 부분 성공을 막는다 | 분류/감사 저장 실패 시 시작만 미해결로 남는다. 외부 제공은 이미 진행했으므로 자동 재전송하지 않는다 |
| 첫 불일치만 고정 사유로 남김 | 응답과 감사의 개인정보 노출을 줄인다 | 모든 위험의 목록은 아니다. 암호화한 비교 대상 자료를 인가된 후속 검토에서 확인해야 한다 |

이름/생일 일치만으로 실제 동일인·주소 통제·자금 출처·제재 위험이 모두 확인되는 것은 아니다. 금융 해제는 현재 고객/체인/위험과 인증된 응답 근거를 별도로 재검증해야 하며 이번 범위에 없다.

## 구현과 관련 코드·문법

출처: [CodeVaspTransferDetailAssessment.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferDetailAssessment.java#L41), 현재 소스의 부분 발췌.

```java
    static Decision compare(String name, LocalDate birth, String address, CodeVaspTransferDataClient.Data data) {
        if (data == null || data.beneficiary() == null || data.originator() == null) return review("PARTIES_INCOMPLETE");
        String reason = person(name, birth, data.beneficiary(), "BENEFICIARY");
        if (reason != null) return review(reason);
        if (data.beneficiary().accounts().size() != 1 || !address.equals(data.beneficiary().accounts().getFirst())) return review("BENEFICIARY_ACCOUNT_MISMATCH");
        reason = person(name, birth, data.originator(), "ORIGINATOR");
        return reason == null ? new Decision("CONSISTENT", "DETAILS_CONSISTENT") : review(reason);
```

Java record Decision은 고정 상태·사유만 반환한다. 먼저 수취인 자료와 요청 주소를 확인하고 그 다음 송신인을 같은 고객 자료와 비교한다. 정보가 부족하거나 다르면 REVIEW_REQUIRED다. 이름이나 생일의 원문은 이 분류 객체에 넣지 않는다. 실제 동일인 확정이나 금융 허가 값을 반환하지 않는다.

출처: [CodeVaspTransferDetailAssessment.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferDetailAssessment.java#L52), 현재 소스의 부분 발췌.

```java
        if (person.secondaryName() != null && !person.secondaryName().isBlank()) return role + "_STRUCTURED_NAME";
        if (expected == null || person.primaryName() == null || expected.isBlank() || person.primaryName().isBlank()
            || !canonical(expected).equals(canonical(person.primaryName()))) return role + "_NAME_MISMATCH";
        if (birth == null || person.dateOfBirth() == null) return role + "_BIRTH_DATE_MISSING";
```

분리된 이름은 순서를 추측하지 않고 검토한다. LocalDate.equals는 생일의 정확한 일치만 확인한다. source의 생일 누락도 구분하며, 형식 파싱 실패와 업무상 불일치를 혼동하지 않는다. 기존 클라이언트가 검증해 추출한 자료를 비교하는 코드다.

출처: [CodeVaspTransferDetailAssessment.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferDetailAssessment.java#L59), 현재 소스의 부분 발췌.

```java
    private static String canonical(String name) {
        // 같은 문자의 유니코드 조합 표현과 양끝 공백만 정리한다. 음역·대소문자·이름 순서·내부 공백을 추측하지 않는다.
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    }
```

NFC는 같은 문자의 조합 표현을 정규화한다. strip은 양끝 공백만 제거한다. TypeScript에서도 문자열의 normalize("NFC")와 유사하지만, 문자열 유사성 자체가 신원 보증을 만드는 것은 아니다. 내부 공백 제거·호환 문자 합치기·음역·성명 순서 변경은 하지 않는다.

출처: [CodeVaspTransferExecution.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferExecution.java#L58), 현재 소스의 부분 발췌.

```java
            if("DATA_RECEIVED".equals(outcome))assessments.record(start.id(),start.basis().prepared(),details);
            audit.record(null,"CODE_TRANSFER_FINISHED","codevasp-transfer:"+intentId,plain==null?"DENIED":"SUCCESS","ATTEMPT:"+start.id()+":STATUS:"+outcome+":REASON:"+code);
            return state(intentId);
```

결과 저장 직후 분류와 감사를 같은 완료 트랜잭션에서 수행한다. 이 구간은 외부 호출의 예외 처리 밖이다. 저장 실패를 원격 오류로 다시 저장하거나 개인정보를 자동 재전송하지 않는다. RUNNING은 시작이 확정됐다는 뜻이며 작업자의 생존 보장은 아니다.

출처: [detail-assessment-schema.sql](../../src/main/resources/travelrule/detail-assessment-schema.sql#L30), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER codevasp_detail_assessment_complete_check AFTER INSERT ON codevasp_transfer_results
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.status='DATA_RECEIVED') EXECUTE FUNCTION codevasp_detail_assessment_complete();
```

새 DATA_RECEIVED 결과는 커밋 때 같은 트랜잭션의 분류가 필요하다. 삽입 트리거는 현재 실행의 수신 원본인지 확인하고, 분류 UPDATE·DELETE·TRUNCATE를 금지한다. 과거 결과에 임의 분류를 소급 생성하는 폴백은 없다. 이 표의 상태 문자열만으로 금융 해제를 승인하지 않는다.

## 작성한 테스트

신규 단위18개는 정확한 자료·NFC 조합 차이·양끝 공백, 대소문자·호환 문자·다른 문자 체계의 닮은 글자·내부 공백·이름 순서, 양쪽의 분리된 이름/생일 누락/생일 불일치·다른 송신인·다른/추가 수취 계정·누락 당사자를 확인한다. 정상 결과도 이름 원문을 분류 문자열에 포함하지 않는다.

신규 PG 통합10개는 다음을 검증한다.

1. 암호화 응답·CONSISTENT·최소 감사가 함께 저장됨.
2. 수취 이름 불일치도 수신 사실은 보존하고 검토, 재전송 금지.
3. 송신인 생일 불일치는 REVIEW_REQUIRED이며 통신 실패로 바꾸지 않음.
4. 프로토콜상 선택인 수취인 생일이 없으면 검토.
5. 분리된 이름은 자동 조합하지 않음.
6. 분류 감사 실패 시 결과/분류 롤백·RUNNING 유지·재전송 금지.
7. 서비스가 분류를 누락해도 실제 지연 제약으로 커밋 차단, 구체 원인 확인.
8. SQL 수정·삭제·TRUNCATE·수신 근거 없는 분류 삽입 차단.
9. 기록 메서드는 기존 업무 트랜잭션 없으면 거부.
10. 외부 대기 중 고객 제한이 생겨도 당시 자료 분류는 보존되고 현재 제한을 지우지 않음.

이전 실행19·자동 일정/수명25·금융182 회귀도 새 생성자 의존성을 명시해 다시 실행했다. 보호 제약을 끄거나 금융 이력을 삭제하지 않았다.

## 실행 명령과 결과

- 사전 `./gradlew test --tests '*CodeVaspTransferDetailAssessmentTest' --console=plain`: 신규 단위18개 통과. 아래 최종47개에도 포함되어 있으므로 따로 합산하지 않는다.
- `python3 scripts/verify_codevasp_transfer_details.py`: 신규28+기존 실행19=47개 통과. 봇0·소비자 자동 시작 금지·외부 서비스 접두 환경 제거·자체 PG를 적용하고 고객/운영자 JAR도 빌드한다.
- `python3 scripts/verify_codevasp_transfer_processing.py`:25개 통과.
- `python3 scripts/verify_engine_inbox.py`:182개 통과. 상위 실행에서도 외부 관련 환경을 제거하고 소비자 자동 시작을 금지했다.
- 총254회/고유254개, 실패·오류·제외0. 각각 소유 PostgreSQL16 서버를 사용하고 모두 정리했다. 원본 XML·로그는 `.local/cycle113-proof`에 보존했다.
- 두 JAR의 클래스/SQL 포함·Python 구문·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남아 있다. 최종 설명 주석 수정은 동작을 바꾸지 않는다.

가격/신원 공급사·정보 교환 HTTP는 대역이며 실제 메인넷·개인정보 교환·공급사 운영 검증은 하지 않았다. PG·웹훅 HMAC/운영 인증·암호화·업무 트랜잭션은 실제 코드다. 전체 시스템 기동/운영 인증 완료와 구분한다.

## 남은 범위

분류 근거의 보호된 운영 조회·추가 확인/해결, 미확인/개인지갑 등 경로·거래 위험, 금융 해제·엔진 반영은 계속 남는다. 금융 결정은 이 분류 문자열을 단독 신뢰하지 않고 인증된 원본과 현재 근거를 다시 확인해야 한다. 공급사가 전송한 전체 IVMS 원문을 보관한 것으로 표시하지 않는다. 현재 보존 대상은 검증해 추출한 응답 자료와 암호문 해시·요청 근거다. 법적 보존/제공 근거·실제 계약·정확한 표준 상호운용·외부 심사는 별도 확인이다. 개인정보 자동 제공 기본 false와 봇0은 유지한다.

## 작성한 테스트 코드


[CodeVaspTransferDetailAssessmentTest.java](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransferDetailAssessmentTest.java)

- `exactDetailsAreConsistentWithoutFinancialPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `canonicallyEquivalentUnicodeDoesNotCreateFalseMismatch`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `surroundingWhitespaceIsRemoved`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `caseChangesAreNotGuessed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `compatibilityCharactersAreNotFolded`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `visuallySimilarOtherAlphabetIsNotAccepted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `internalWhitespaceIsNotDeleted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nameOrderIsNotGuessed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `splitBeneficiaryNameRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `splitOriginatorNameRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingBeneficiaryBirthRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentBeneficiaryBirthRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingOriginatorBirthRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentOriginatorBirthRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentOriginatorNameRequiresReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `beneficiaryAccountMustBeExactlyTheRequestedOne`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `extraBeneficiaryAccountsAreNotSilentlySelected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `incompletePartiesAreNotConsistent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[CodeVaspTransferDetailAssessmentIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferDetailAssessmentIntegrationTest.java)

- `consistentDetailsAndEncryptedReplyCommitTogetherWithMinimalAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentBeneficiaryNamePreservesReplyAndRequiresReviewWithoutResend`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentOriginatorBirthIsReviewRatherThanTransportFailure`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `protocolOptionalBeneficiaryBirthCannotBecomeAutomaticConsistency`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `splitNamesAreNotAutomaticallyReordered`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `assessmentAuditFailureLeavesUnresolvedStartAndNoAutomaticResend`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `omittedAssessmentPreventsReceivedResultCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `classificationsCannotBeChangedDeletedOrInventedWithoutSource`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `recordingRequiresBusinessTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `consistentHistoricalDetailsDoNotEraseRestrictionChangedDuringIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 db04abe](https://github.com/GiHoon1123/Cex-Platform-Service/commit/db04abe803afd619b6fcf8318f9953ad574a263a)
- [격리 실행 증적](../reference/113-received-transfer-detail-consistency-verification-2026-10-25.json)
