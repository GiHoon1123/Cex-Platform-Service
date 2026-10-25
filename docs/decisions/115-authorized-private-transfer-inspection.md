# 인가된 개인정보 검토와 검증된 원본·불변 접근 증적 연결하기

운영자·승인자가 요청/수신 자료의 암호화·HMAC·대상 관계를 검증해 개인정보를 열람하고, 그 접근을 필수 감사와 함께 보존한다. 감사자는 최소 이력만 읽으며, 검토 결과가 금융 허가가 되지는 않는다.

## 문제와 기존 동작

114의 최소 이력은 불일치 사유를 보여 주지만 실제 표기·생일·수취 계정 차이를 해결할 자료는 제공하지 않았다. 원문 접근을 기존 요약 조회에 섞으면 감사자에게까지 개인정보가 노출되고, 분류 문자열만 신뢰하면 인증된 응답과의 일치도 확인할 수 없다.

POST `/api/admin/deposits/BTC/{receipt}/origin-transfer/{attempt}/inspect`를 별도 명시적 검토 행위로 추가한다. OPERATOR/APPROVER의 현재 계정·운영 권한·세션·5분 추가 인증을 확인한 다음에만 복호화한다. AUDITOR/CUSTOMER는 원문에 접근하지 못한다. GET 요약의 기존 권한과 응답 범위는 유지한다.

## 기존 제품·공식 근거와 적용 조건

기존 [AML 조사 원문 열람](../../src/main/java/dustin/cex/domains/edd/AmlInvestigationService.java)의 현재 인증→암호화 원본 검증→별도 접근 증적→필수 감사 패턴을 재사용한다. [OWASP API1:2023](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)의 대상별 인가 원칙도 따른다. UUID나 JWT만으로 접근을 허용하지 않는다. 실제 금융기관의 비공개 내부 구현을 추정하지 않는다.

2026-10-25 [개인정보의 안전성 확보조치 기준2026-9호](https://law.go.kr/admRulLsInfoP.do?admRulSeq=2100000281400)를 국가법령정보센터의 공개 본문 요청까지 대조했다. 고시 일반 시행일은2026-07-01이다. 부칙상 제15조제5호·제18조의2027-01-01 시행 항목은 현행 의무와 구분한다.

- 제2조의 접속기록 항목과 제5조 최소·차등 권한을 이번 접근 설계의 근거로 삼는다. 현재 운영 역할/인물/버전·세션과 대상 입금/시도·조회 시각은 접근 표에, 실제 접속지와 수행 업무는 같은 트랜잭션의 감사에 연결한다.
- 제8조는 원칙1년,5만 명 이상·고유식별/민감정보 처리 등 조건에2년 보관을 정한다. 적용 조건과 전체 시스템의 보존/법적 보류·파기 정책은 별도로 확정해야 한다. 불변 표를 만들었다는 사실로 보존·운영 준수가 완료됐다고 표시하지 않는다.
- 현재 제8조의 점검 주기·방법·사후조치는 내부 관리계획으로 정해 이행하는 항목이다. 과거 기준의 점검 빈도를 확인 없이 현행 조문으로 옮기지 않는다. 실제 조직의 점검·보호조치·운영 증적은 미완료다.

외부 접속의 안전한 인증과 접속 통제도 운영 조건이다. 코드의5분 한도는 자체 정책이며 법령이 지정한 시간이 아니다. 신뢰 프록시·망/단말·로그 보관/점검은 별도 운영 검증이다. 현재 감사는 전달 헤더를 신뢰하지 않고 Servlet의 실제 접속지를 남긴다. 프록시 도입 후 이를 실제 최종 사용자 IP라고 무조건 주장하지 않는다.

새 암호화 라이브러리는 추가하지 않았다. 설치된 CddCrypto의 AES-GCM/AAD·독립 HMAC·키 식별자와 Jackson/JDBC를 사용하고 [107 응답 보관](107-durable-private-transfer-execution.md)·[113 비교](113-received-transfer-detail-consistency.md)를 검증해 읽는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 요약/개인정보 검토 권한 분리 | 감사자의 최소 이력 권한이 개인정보 원문 권한으로 확대되지 않는다 | 담당자는 별도 명시적 검토 요청이 필요하다. 요약에 개인정보를 함께 반환하지 않는다 |
| 현재 역할·추가 인증을 복호화 전에 검사 | 회수된 역할·오래된 인증·다른 대상의 자료 접근을 막는다 | 플랫폼 사용자 행의 짧은 잠금이 필요하다. 엔진 코어와 무관한 플랫폼 업무다 |
| 요청/응답 복호화와 HMAC·경제적 대상·분류 재검증 | 암호문 손상·다른 입금 혼합·평문 분류 위조를 그대로 믿지 않는다 | 요청/응답 복호화와 JSON 검증 비용이 든다. 요약은 이 비용 없이 읽는다 |
| 접근 증적/감사가 있어야 커밋 | 감사 예외와 호출 누락 모두 성공 응답을 막는다 | 감사 장애 시 개인정보 검토도 이용할 수 없다. 원본을 자동 수정하거나 기록을 생략하지 않는다 |
| 현재 접속 주체/지원 키/최신 수신 근거에 한정 | 다른 접속 주체나 미확인 자료로 자동 우회하지 않는다 | 과거 다른 접속 주체·미지원 키·비교 없는 기록은 이 경로에서 읽지 못한다. 기록은 보존하고 별도 인가된 역사 자료 업무가 필요하다 |

GET도 감사할 수 있다. 여기서는 기존 AML inspect처럼 명시적인 개인정보 열람과 별도 접근 증적 발급을 POST로 표현했다. POST 자체가 보안을 보장하는 것은 아니며 실제 경계는 현재 인증·대상 인가·무결성·no-store·필수 기록이다.

## 구현과 관련 코드·문법

출처: [BitcoinDepositOperatorAccess.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositOperatorAccess.java#L33), 현재 소스의 부분 발췌.

```java
        sessions.requireFresh(locked.get(actor),sid,roles);
        if(receipt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"입금 식별자를 확인하세요");
        if(owners.isEmpty()||locked.get(owners.getFirst())==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 심사 근거가 없습니다");
        return new Access(locked.get(actor),locked.get(owners.getFirst()));
```

오름차순 사용자 잠금 뒤 현재 세션/역할을 확인한 다음 대상 존재 여부를 반환한다. 최소 이력은 감사자를 포함하고 개인정보 검토는 OPERATOR/APPROVER만 전달한다. Java varargs는 허용 역할 여러 개를 받는 문법이다. TypeScript의 rest 인자와 비슷하지만 Spring 트랜잭션/실제 DB 잠금은 별도 런타임 계약이다.

출처: [CodeVaspTransferEvidence.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferEvidence.java#L53), 현재 소스의 부분 발췌.

```java
            String request=new String(crypto.decrypt(owner,attempt,(byte[])row.get("request_cipher")),StandardCharsets.UTF_8);
            String response=new String(crypto.decrypt(owner,attempt,(byte[])row.get("response_cipher")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("codevasp-transfer-attempt-v1",request).equals(row.get("request_mac"))
                ||!crypto.fingerprint("codevasp-transfer-result-v1",response).equals(row.get("response_mac")))throw unavailable();
```

사용자와 실제 실행 UUID를 AAD로 검증해 요청/응답을 복호화하고 각각의 도메인 HMAC을 다시 확인한다. 과거 요청의 만료된 상대 키를 현재 요청 생성자에 넣지 않고 인증된 JSON의 필요한 필드를 읽는다. 다른 키로 추측하거나 불완전한 자료로 우회하지 않는다.

출처: [CodeVaspTransferEvidence.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferEvidence.java#L67), 현재 소스의 부분 발췌.

```java
            var checked=CodeVaspTransferDetailAssessment.compare(expected.fullName(),expected.dateOfBirth(),expected.address(),data);
            // 평문 분류 문자열만 신뢰하지 않는다. 인증된 요청·응답으로 재계산해 저장된 정책·상태·사유와 대조한다.
            if(!CodeVaspTransferDetailAssessment.POLICY.equals(row.get("policy_id"))||!checked.status().equals(row.get("assessment_status"))||!checked.reason().equals(row.get("assessment_reason")))throw unavailable();
            return new Verified(attempt,receipt,owner,crypto.keyId(),(String)row.get("request_mac"),(String)row.get("response_mac"),expected,data,checked);
```

인증된 요청/응답으로 비교를 다시 계산한다. 저장된 정책·상태·사유와 다르면503으로 닫는다. 이 원본 검증은 평문 분류를 금융 허가로 재사용하는 것과 다르다. 반환 객체와 민감한 하위 record의 toString은 개인정보를 생략한다.

출처: [CodeVaspTransferInspection.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferInspection.java#L44), 현재 소스의 부분 발췌.

```java
            var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);
            if(attempt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수신 시도 식별자를 확인하세요");
            var verified=evidence.load(receipt,attempt,allowed.customer().getId(),directory.configuredSourceId());UUID id=UUID.randomUUID();
```

현재 역할·5분 추가 인증·대상 네트워크를 확인한 뒤 실제 입금 소유자와 현재 접속 주체를 원본 읽기에 전달한다. 역할이 없으면 복호화까지 진행하지 않는다. 네트워크 설정의 패키지 내부 필드는 같은 Bitcoin 패키지의 접근 정책이 읽으며 외부에 공개 필드를 추가하지 않았다.

출처: [inspection-schema.sql](../../src/main/resources/travelrule/inspection-schema.sql#L38), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER codevasp_transfer_inspection_audit_complete_check AFTER INSERT ON codevasp_transfer_inspections
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_inspection_audit_complete();
```

지연 제약은 새 접근 증적을 커밋할 때 해당 운영자/인물·대상·성공 업무·접속지를 가진 감사를 요구한다. 서비스의 감사 호출이 조용히 빠져도 커밋할 수 없다. UPDATE·DELETE·TRUNCATE도 금지한다. 이 DB 제약만으로 관리자 권한·호스트·디스크 손실까지 보호한다고 주장하지 않는다.

반환 범위는 요청 당시 이름/생일/수취 주소, 송수신인의 이름/생일, 수취 계정과 비교 사유다. 송신인의 계정 목록·암호문·HMAC·신원 지문·전체 IVMS 원문을 반환하지 않는다. 위 검증 내부 읽기는 MANDATORY이고 독립 HTTP 권한 경계가 아니므로 호출 업무가 현재 인가를 책임진다.

## 작성한 테스트

신규 `CodeVaspTransferInspectionIntegrationTest`18개:

1. 실제 Spring 필터/컨트롤러를 실행하는 MockMvc에서 운영자·승인자의 검토/no-store/필드·비금융 보증. 같은 함수에 실제 접속지192.0.2.42가 감사/대상/담당자와 연결되고 X-Forwarded-For의203.0.113.77을 신뢰하지 않는 검사를 보강했다. 두 주소는 문서용 예약 주소이며 외부 통신하지 않는다.
2. 열람마다 별도 불변 접근 ID, 감사에 성명/생일/주소 없음.
3. 감사자는 요약200·원문403.
4. 고객/익명은 존재/부재 원문 모두 거부.
5. 오래된 MFA는 복호화 전 거부.
6. 현재 운영 계정 회수 시 과거 자료 접근 거부.
7. 다른 입금이 수신 시도를 빌리지 못함.
8. 접속 주체 변경 시 과거 개인정보를 이 경로로 열지 않음.
9. 요청 암호문 손상은 거부하고 DB 원본은 보존.
10. 응답 HMAC 불일치는 거부.
11. 저장 분류를 원본으로 재계산해 불일치 거부.
12. 미지원 키 식별자는 다른 키 폴백 없이 거부.
13. 감사 예외 시 접근 증적 롤백, 수신 원본 보존.
14. 접근 표의 수정/삭제/TRUNCATE·다른 운영 버전의 위조 삽입 거부.
15. 제한된 고객도 담당자가 조사할 수 있지만 내부 읽기는 기존 트랜잭션 필요.
16. 실제 불일치 수신 자료도 검토 가능하며 전송 실패나 승인으로 바꾸지 않음.
17. GET/PUT/임의 하위 경로는 거부하고 접근 증적 생성 없음.
18. 감사 호출이 조용히 누락돼도 구체 지연 제약 오류로 커밋 차단.

손상/위조 읽기 테스트는 실제 PG에서 조회한 행의 복사본에만 오류를 주입한다. 금융/감사 원본을 수정하거나 불변 트리거를 끄지 않았다. 공통 후처리로 사용 가능 잔액·엔진/Kafka/추가 정보 교환 변경 없음도 확인한다.

기존 전송 최소 조회11·출처 조회13·현재 인가14와 금융182를 다시 확인했다. 별도 선행 접근 정책 분리도38개로 검증했으나 최종 고유 수에 다시 합산하지 않는다.

## 실행 명령과 결과

- 접근 정책 선행 분리: `python3 scripts/verify_codevasp_transfer_review.py`38개 통과.
- 초기에 네트워크 설정의 패키지 내부 필드 접근2개와 테스트의 패키지 내부 메서드 호출1개가 컴파일에서 발견됐다. 공개 범위를 넓히지 않고 소유 패키지에서 설정을 해석하고 검색의 공개 sourceId를 사용하는 방식으로 수정했다.
- 최초15개 검토 실행은14개 통과·HTTP200 기대1개가403으로 실패했다. 새 POST가 기본 거부된 상태여서 지정된 경로만 OPERATOR/APPROVER로 등록했다. 다른 메서드/하위 경로 거부도 추가했다.
-17개 검토·기존38개 성공 후 감사 누락 지연 제약을 추가했다. 중간 개수는 최종에 중복 합산하지 않는다.
- 최종 `python3 scripts/verify_codevasp_transfer_inspections.py`: 신규18+기존38=56개 통과, 실패·오류·제외0. 각 Spring/JPA 맥락은 별도 소유 PostgreSQL16 서버4개로 격리하고 모두 정리했다.
- 접속지/전달 헤더 검사는 그 뒤 같은 실행기에서 해당 HTTP 함수1개만 선택해 자체 PG로 재실행했다. 실제 기대/사례 집계만1개로 바꾸었고 시험 코드와 격리/빌드 동작은 동일하다. 테스트 주장만 보강했으며 제품 동작은 바꾸지 않았다.
- 최종 `python3 scripts/verify_engine_inbox.py`:182개 통과. 외부 서비스 관련 환경 제거·소비자 자동 시작 금지·봇0·소유 PG를 적용했다.
- 최종 집계239회/고유238개, 실패·오류·제외0. 중복1회는 같은 HTTP 함수의 접속지 검사다. 초기/중간/접근 분리 실행을 새 사례로 부풀리지 않았다. 원본 XML·로그는 `.local/cycle115-proof`에 보존했다.
- 고객/운영자 JAR 빌드와 검토 클래스/SQL 포함, Python 구문·실제 코드 발췌·로컬 링크·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

MockMvc는 실제 Spring 보안 필터와 컨트롤러를 실행하지만 외부 TCP/공급사 호출은 아니다. 실제 운영자 키는 소프트웨어 시험 인증기이며 공급사·메인넷·유료 세션을 호출하지 않았다. 라이브 운영/인증·신고 완료로 표시하지 않는다.

## 남은 범위와 운영 조건

열람은 의견·종결·금융 허가가 아니다. 불일치 해결·미확인 경로·거래 위험·현재 근거와 금융 해제/엔진 반영은 남는다. 다른 접속 주체·옛 키·비교 없는 과거 자료는 보존하지만 이 현재 검토 경로에서 자동 우회하지 않는다. 별도 인가된 역사 자료 검토와 키 보존/파기·법적 보류는 전역 개인정보/감사 업무와 함께 처리해야 한다.

기간별 보관/법적 보류·파기·외부 보존·접속기록 점검과 소명·이상 열람 탐지·실제 망/단말/신뢰 프록시 조건은 운영/인프라 검증 상태다. 개인정보 자동 제공 기본 false·봇0·엔진 단일 스레드/출력 워커 계약은 유지한다.

선행 접근 정책 분리: [d1c8cf4](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d1c8cf4). 최종 구현은 같은 접근 정책을 두 실제 업무가 사용하도록 연결한다.

## 작성한 테스트 코드


[CodeVaspTransferInspectionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferInspectionIntegrationTest.java)

- `operatorAndApproverCanInspectVerifiedDetailsThroughHttpWithNoStore`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `eachInspectionHasDistinctImmutableAccessReceiptAndMinimalAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `auditorKeepsMetadataAccessButCannotDecrypt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerAndAnonymousCannotReadExistingOrMissingEvidence`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `staleOperatorMfaIsRejectedBeforeDecryption`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `revokedOperatorCannotReadPastEvidence`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherReceiptCannotBorrowReceivedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedConnectionAuthorityCannotReadOldPersonalEvidence`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `damagedRequestCiphertextIsRejectedWithoutChangingOriginal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongResponseMacIsRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mismatchedStoredClassificationIsRecomputedAndRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unsupportedKeyEpochDoesNotFallBackToAnotherKey`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `failedMandatoryAuditRollsBackAccessReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `accessReceiptsCannotBeUpdatedDeletedTruncatedOrForged`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `restrictedCustomerCanStillBeInvestigatedAndInternalReaderNeedsTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `verifiedMismatchCanBeInspectedWithoutCallingItTransportFailure`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `otherMethodsAndUnregisteredSubpathsRemainDenied`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedAuditCannotCommitInspectionReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 e9625ef](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e9625ef1b61526a08919f3376cc15897beeeed76)
- [격리 실행 증적](../reference/115-authorized-private-transfer-inspection-verification-2026-10-25.json)
