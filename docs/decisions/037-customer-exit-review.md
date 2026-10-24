# 고객의 종료 개시 재검토 요청을 접수하고 지정 담당자에게만 제공하기

현재 종료 개시로 거래가 제한된 고객이 사유를 제출하고 접수번호를 다시 확인할 수 있게 한다. 사유는 암호화해 보존하며 원래 지정 담당자만 감사와 함께 읽는다. 고객 요청과 내부 종료·철회·STR 판단을 분리하는 이유, 실패·재송신·원문 권한과 실제 검증을 개발자가 설명하도록 작성했다.

## 문제와 기존 동작

033·034는 운영자가 종료 개시를 판단하고 독립 철회할 수 있게 했지만 고객이 직접 재검토 요청을 남기는 경로는 없었다. 감독 목록 035·036도 내부 업무를 발견하는 읽기 기능이다. 고객의 이의 제기를 내부 승인으로 취급하면 종료 차단이나 보고 업무를 우회할 위험이 있다.

이번 계층은 **현재 종료 개시의 고객 재검토 요청 접수·본인 접수 확인·원래 담당자의 감사 열람**을 끝까지 연결한다. 판단·회신·통지·법정 기한·외부 민원 절차 전체가 아니다. RECEIVED는 플랫폼 내부 DB와 감사에 접수됐다는 뜻이며 검토 완료·철회 승인·기관 접수를 뜻하지 않는다.

## 기존 제품과 공식 자료의 접근

확인일 2026-10-24. 국내 의무·공개 사업자 참고·자체 정책을 구분한다.

| 근거 | 적용 범위 |
| --- | --- |
| [업비트 개인정보 처리방침](https://static.upbit.com/terms/private_data.html), [빗썸 개인정보 FAQ](https://privacy.bithumb.com/ko/faq/) | 관계/서비스 관리와 고객지원·고충 처리 목적을 분리해 확인했다. 이 공개 방침을 양사의 내부 승인·사유 암호화 방식이나 외부 심사 통과 증거로 쓰지 않는다. |
| [미래에셋증권 민원 안내](https://trading.securities.miraeasset.com/hki/hki3062/n18.do), [모바일 민원 신청 안내](https://securities.miraeasset.com/mw/mki/mki3062/n10.do) | 본인 접수·신청/처리 내역·조사·회신을 별도 단계로 다루는 공개 흐름을 읽었다. 증권사의 거부 조건과 14영업일 안내를 국내 현물 거래소의 법정 기한으로 이식하지 않는다. 삼성증권 민원 안내는 502로 본문 미확인이며 이 미확인을 숨기지 않는다. |
| [Stripe disputes API](https://docs.stripe.com/disputes/api) | 원거래에 별도 개별 업무 ID와 근거를 연결하는 패턴을 참고했다. 결제 차지백이나 환불·기관 대응을 구현한 것이 아니다. |
| [Adyen Case management](https://docs.adyen.com/risk-management/case-management) | 검토 역할과 민감 정보 열람 역할을 분리하는 접근을 참고한다. 여기서는 원래 지정 계정/인물만 고객 사유를 읽으며 감독자에게 원문을 일괄 공개하지 않는다. |
| [개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353) | 법률 제21445호·시행 2026-09-11의 목적에 필요한 최소 수집을 확인했다. 기존 로그인 식별자를 쓰고 사유 외에 전화·주소·주민번호·첨부 서류를 추가 요구하지 않는다. 처리 근거·고지·보유/파기·실제 권리 행사와 외부 심사는 별도로 확인해야 한다. |
| [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887) | 법률 제21857호·시행 2026-10-02의 고객확인·거절/종료와 별도 STR 검토를 유지한다. 고객의 주장만으로 이 의무나 현재 위험 판단을 없애지 않는다. |

2,000 UTF-16 단위·최근 24시간 고객별 새 접수 3개·페이지 25개와 접수 상태명은 자체 API 정책이다. 법정 민원 접수 제한이나 법정 처리 기한이라고 주장하지 않는다. 운영 전 법적 처리 근거·고객 안내·계정 접근 상실 고객의 다른 접수 수단을 준비해야 한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 고객 본인의 현재 유효 종료를 서버가 선택 | 다른 고객·내부 결정 번호를 지정한 접근과 사유 탐색을 줄인다. | 현재 종료 개시 이외의 일반 민원·대리 접수·계정 접근 상실은 다루지 못한다. 내부 종료 사유나 STR 유무를 고객에게 공개하지 않는다. |
| 같은 키는 원래 접수, 추가 사유는 새 접수 | 응답 유실/동시 재송신의 중복을 막고 옛 신청을 새 결정으로 옮기지 않는다. | 고객이 키를 보존해야 한다. 새 접수 3개/24시간 한도는 저장/검토 부담을 줄이지만 반복 추가 자료 제출을 제한하므로 운영 전에 적정성과 다른 수단을 검토해야 한다. |
| 기존 암호화·HMAC과 불변 표 재사용 | 평문 사유·사전 공격용 공개 해시·무단 수정과 문맥 간 암호문 복사를 막는다. | 저장·암호화·키 관리·보존/파기 비용이 늘어난다. 기존 증빙 키를 공유하며 고객 요청 전용 KMS 계정/키 분리는 구현하지 않았다. |
| 원래 두 지정 인물만 감사 열람 | 감독 목록 권한이나 일반 운영 역할만으로 원문을 얻지 못한다. | 원래 인물 부재 시 새 인계/고립 복구가 필요하다. 이번에는 우회 권한을 만들지 않는다. |
| 접수/열람과 내부 판단 분리 | 고객이 문제를 제기해도 자산·거래 제한·STR가 자동 변경되지 않는다. | 고객에게 아직 검토 결과/회신이 제공되지 않는다. 정정 판단은 실제 034의 독립 서명/전체 근거 절차를 거쳐야 한다. |

## 구현과 API

| 경로 | 실제 동작 |
| --- | --- |
| POST /api/customer/relationship/review-requests | 본인 현재 세션, Idempotency-Key UUID, rationale. HTTP 200으로 내부 접수증 반환 |
| GET 같은 경로 ?page=0 | 본인의 접수 ID·수신 시각·RECEIVED만 최대 25개 제공 |
| GET 같은 경로 /{id} | 본인 접수 확인. 다른 고객의 접수와 없는 ID는 404 |
| GET /api/admin/compliance/relationship-exits/{exit}/review-requests | 현재 원래 지정 인물만 대상 종료의 접수 메타데이터 조회 |
| POST /api/admin/compliance/customer-review-requests/{id}/inspect | 현재 지정 인물/역할·최근 MFA를 검사한 뒤 고객 사유만 필수 감사와 제공 |

고객 입력에 내부 exitId·userId·담당자 지정은 없다. 본인 확인/EDD가 제한됐더라도 로그인 가능한 현재 고객은 접수할 수 있다. 비활성·회수·만료/다른 계정 세션은 서비스에서도 거부한다. 고객의 추가 OTP/WebAuthn을 새로 요구하지 않으며 새 거래를 허용하지도 않는다.

기존 사용자 행 잠금과 READ COMMITTED 아래 중복·한도를 검사한다. submit 진입의 NEVER는 외부 트랜잭션 합류로 격리 조건이 바뀌지 않게 한다. 입력·암호화·접수·감사는 한 트랜잭션이다. 후속 읽기는 감사 실패 시 응답을 반환하지 않는다. 원래 종료가 철회된 뒤에도 같은 키 재조회와 기존 접수 열람은 가능하고 새 접수만 거부한다.

새 표는 customer_exit_review_requests 하나다. 사용자별 키 고유성·원래 결정/고객의 복합 외래 키·사용자/종료별 조회 인덱스와 불변 트리거를 추가했다. 금융 기록이나 기존 스키마를 변환하지 않는다. 신규 승인 종류나 패키지는 없다.

## 실제 코드와 문법

### 고객의 여러 줄 사유를 정규화하기

[CustomerExitReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerExitReviewService.java#L29)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Submission validated() {
    if(rationale==null)throw denied(HttpStatus.BAD_REQUEST);
    String value=Normalizer.normalize(rationale.replace("\r\n","\n"),Normalizer.Form.NFC).strip();
    // 여러 줄의 사유는 허용하되 로그 제어 문자와 숨은 방향 전환 문자는 받지 않는다.
    if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n')||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
    return new Submission(value);
}
```

NFC 정규화와 strip으로 같은 의미의 입력을 정리한다. CRLF는 LF로 바꾸고 여러 줄 사유는 허용하지만 다른 제어/방향 전환 문자는 거부한다. String.length의 2,000 한도는 UTF-16 코드 단위이며 JavaScript/TypeScript 문자열 length와 같은 기준이다. 사용자에게 눈에 보이는 글자 수와 항상 같지는 않다.

### 원래 접수의 멱등 재조회

[CustomerExitReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerExitReviewService.java#L59)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var old=jdbc.queryForList("SELECT * FROM customer_exit_review_requests WHERE user_id=? AND request_key=?",actor,key);
if(!old.isEmpty()) {
    var row=old.getFirst();UUID exit=(UUID)row.get("exit_id");
    if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!crypto.keyId().equals(row.get("evidence_key_id"))||!mac(actor,exit,value.rationale()).equals(row.get("content_mac")))throw denied(HttpStatus.CONFLICT);
    audit.record(actor,"CUSTOMER_EXIT_REVIEW_REPLAYED","customer-review:"+row.get("id"),"SUCCESS","RECEIPT_ONLY");return receipt(row);
}
```

사용자·요청 키로 원래 행을 먼저 찾는다. 그 행의 exit_id와 정규화 사유로 HMAC을 다시 계산한다. 현재 종료 대상이 바뀌어도 옛 키를 새 대상으로 붙이지 않는다. 같은 키/같은 내용은 원래 접수번호이고 다른 내용이나 키 구성이면 충돌이다. 요청 한도를 이미 채워도 같은 키 재조회는 가능하다.

### 암호화 접수와 감사를 함께 확정하기

[CustomerExitReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerExitReviewService.java#L69)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
UUID id=UUID.randomUUID();String mac=mac(actor,exit,value.rationale());byte[] cipher=crypto.encrypt(context(id,actor,exit,mac),value.rationale().getBytes(StandardCharsets.UTF_8));
jdbc.update("INSERT INTO customer_exit_review_requests(id,user_id,exit_id,request_key,reason_cipher,content_mac,fingerprint_key_id,evidence_key_id) VALUES(?,?,?,?,?,?,?,?)",id,actor,exit,key,cipher,mac,fingerprints.keyId(),crypto.keyId());
// 접수 기록과 감사도 같은 트랜잭션에 확정돼야 고객에게 접수번호를 반환한다.
audit.record(actor,"CUSTOMER_EXIT_REVIEW_RECEIVED","customer-review:"+id,"SUCCESS","RECEIVED_ONLY");return receipt(row(id));
```

기존 EvidenceCrypto의 AES-GCM을 재사용하고 행·고객·종료·내용 지문을 인증 문맥으로 고정한다. 사유를 공개 SHA 해시만으로 저장하지 않고 기존 CddCrypto의 비밀 HMAC으로 비교한다. 접수 행과 감사는 같은 트랜잭션이며 감사 실패 시 접수번호를 반환하지 않고 전체 롤백한다. 외부 기관의 접수나 심사 완료를 뜻하지 않는다.

### 담당자 목적 확인·해독·필수 감사 순서

[CustomerExitReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerExitReviewService.java#L87)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Inspection inspect(long actor,UUID sid,UUID id) {
    return attempt(actor,()->tx.execute(t->{var row=row(id);UUID exit=(UUID)row.get("exit_id");var original=assigned(actor,sid,exit);long user=((Number)row.get("user_id")).longValue();
        if(original.payload().userId()!=user)throw new IllegalStateException("고객 재검토 대상 검증 실패");String mac=(String)row.get("content_mac");
        byte[] plain=crypto.decrypt((String)row.get("evidence_key_id"),context(id,user,exit,mac),(byte[])row.get("reason_cipher"));
        String value=new Submission(new String(plain,StandardCharsets.UTF_8)).validated().rationale();
        if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!mac.equals(mac(user,exit,value)))throw new IllegalStateException("고객 사유 무결성 검증 실패");
        // 원문을 먼저 해독해도 감사가 실패하면 호출자에게 반환하지 않는다. 제공을 금융 승인 영수증으로 쓰지 않는다.
        audit.record(actor,"CUSTOMER_EXIT_REVIEW_VIEWED","customer-review:"+id,"SUCCESS","CUSTOMER_REASON_ONLY");return new Inspection(receipt(row),value);
    }));
}
```

assigned가 원래 계정·동일 인물 UUID·현재 역할·사용 가능 여부·최근 MFA를 확인한 뒤 해독한다. 다른 행에 옮긴 암호문은 인증 태그에서 실패한다. HMAC/키를 다시 검증한 뒤 감사가 성공해야만 고객 사유를 반환한다. 이 제공은 철회 승인에 필요한 당시/현재 근거 열람 영수증을 대체하지 않는다.

### 기존 결정에 연결한 추가 전용 기록

[customer-exit-review-schema.sql](../../src/main/resources/edd/customer-exit-review-schema.sql#L2)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```sql
CREATE TABLE IF NOT EXISTS customer_exit_review_requests (
 id uuid PRIMARY KEY,user_id bigint NOT NULL,exit_id uuid NOT NULL,request_key uuid NOT NULL,
 reason_cipher bytea NOT NULL,content_mac varchar(64) NOT NULL,fingerprint_key_id varchar(64) NOT NULL,
 evidence_key_id varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(exit_id,user_id) REFERENCES relationship_exit_decisions(review_id,user_id),UNIQUE(user_id,request_key)
);
```

복합 외래 키는 고객과 원래 종료 결정을 연결하고 사용자별 키가 다른 고객에게 충돌하지 않게 한다. 운영 입력에서는 실제 현재 APPROVED만 선택한다. 별도 불변 트리거가 UPDATE/DELETE/TRUNCATE를 거부한다. 이력 보존과 법정 보존 기간/파기 정책의 완성은 구분한다.

## 작성한 테스트

[CustomerExitReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerExitReviewIntegrationTest.java)의 24개 실제 PG·서명·감사·MVC 사례와 [CustomerExitReviewSubmissionTest.java](../../src/test/java/dustin/cex/domains/edd/CustomerExitReviewSubmissionTest.java)의 입력 7개, 총 31개를 작성했다.

| 통합 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| customerReceivesOnlyReceiptAndNoFinancialOrOriginalDecisionChanges | 접수증만 제공, 기존 종료·고객확인/사건·거래 차단 유지 |
| sameKeyReturnsSameReceiptButChangedReasonConflicts | 같은 키 같은 내용은 같은 접수, 사유 변경 충돌 |
| simultaneousSameKeyCreatesExactlyOneReceipt | 실제 두 스레드 경합에서 하나의 접수 |
| dailyLimitAllowsSupplementaryRequestsAndDoesNotBlockSameKeyReplay | 3개 추가 입력 허용/4번째 429, 같은 키 재조회 유지 |
| noCurrentApprovedExitCannotAcceptNewRequest | 없음/미승인/반려 종료에서는 새 접수 불가 |
| responseLossReplayAfterWithdrawalKeepsOldReceiptWithoutNewApplication | 철회 후 옛 접수 재확인·역사 열람 가능, 새 접수 불가 |
| oldKeyIsNotRetargetedToLaterExit | 새 현재 회부로 재개시해도 옛 키의 대상은 원래 결정 |
| onlyCurrentOriginalParticipantsCanReadReasonAndAssignedReceipts | 원래 지정 두 인물만 원문/대상 목록 제공, 고객·미배정 감독자 거부 |
| customerCannotReadAnotherCustomersReceipt | 본인 접수 목록/조회 격리 |
| serviceChecksCurrentCustomerAndSession | 비활성·만료·회수·다른 세션 4개를 서비스에서 거부 |
| changedIdentityAndStaleMfaCannotProvideCustomerReason | 새 인물이나 오래된 운영 MFA는 사유 제공 불가 |
| auditFailureRollsBackReceiptAndSameKeyRetries | 접수 감사 실패는 행 롤백, 같은 키 재시도 성공 |
| failedViewAuditDoesNotReturnPlaintextAndRetrySucceeds | 열람 감사 실패는 원문 반환 없음 |
| ciphertextCopiedToAnotherRequestFailsContextAuthentication | 다른 행으로 복사한 암호문은 인증 실패 |
| immutableRecordAndAuditContainNoReasonPlaintext | UPDATE/DELETE/TRUNCATE 거부, 저장/감사 평문 노출 없음 |
| customerRequestAndInspectionPreserveExistingFinancialReservation | 실제 주문 예약/원장 유지, 기존 주문 키 재조회에 추가 엔진 호출 없음 |
| receivedRequestDoesNotRemoveConfidentialReportingDuty | 실제 승인 STR 필요/후속 업무·종료 차단 유지 |
| existingAmbientTransactionCannotChangeSubmissionIsolation | 외부 트랜잭션이 있으면 submit 진입 거부 |
| badPagesAndNullInputsAreRejectedWithoutCreatingRequests | 페이지 범위·빈 키/입력 거부 |
| httpEndToEndReceiptListAndDesignatedReasonInspection | 실제 MVC 본인 접수/확인/목록·지정자 열람·저장 금지 헤더 |
| httpCustomerOperatorAuditorAndAnonymousBoundaries | 고객/운영자/감독자/감사/익명 경계 |

| 순수 입력 테스트 함수 | 사례·기대 결과 |
| --- | --- |
| invalidRationaleIsRejected | null·빈 문자열·공백·널 제어 문자·방향 전환 문자 5개 거부 |
| normalizeMultilineAndPreserveMeaningWithoutPrintingReason | CRLF/NFC/여백 정리·여러 줄 유지·안전 출력 1개 |
| exactUtf16LengthLimitIsEnforced | 정확히 2,000 허용/2,001 거부 1개 |

원장 회귀는 실제 PostgreSQL·OrderService·자산 예약/원장을 사용한다. 공통 Didit 세션 생성·엔진 HTTP·Kafka·수수료 정책은 대역이며 소프트웨어 보안키는 실제 암호 서명을 수행한다. 실제 외부 기관·실물 키·엔진 WAL 전체 검증과 구분한다.

## 실행 명령과 결과

선택 JUnit **747개**, 실패·오류·제외 0개. AmlInvestigationIntegrationTest 42개, AmlResolutionIntegrationTest 35개, AddressFactsTest 12개, EvidenceCryptoTest 9개, ExitSupervisionIntegrationTest 18개, ReportingFollowupIntegrationTest 28개, ExitApprovalSupervisionIntegrationTest 16개, WealthReviewIntegrationTest 40개, ExitRescissionFactsTest 7개, EddDossierIntegrationTest 23개, FundsFactsTest 19개, AmlFalsePositiveRulesTest 38개, ReportingCustodyIntegrationTest 45개, FundsReviewIntegrationTest 43개, EddEvidenceIntegrationTest 26개, ExitRescissionIntegrationTest 43개, ReportingFactsTest 10개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, ReportingInternalCheckTest 3개, EddIntegrationTest 35개, ReportingFollowupFactsTest 15개, WealthFactsTest 30개, EvidenceImageTest 14개, AmlSourceProjectionTest 11개, ReportingSupervisionIntegrationTest 24개, ReportingReviewIntegrationTest 37개, RelationshipExitIntegrationTest 44개, AddressReviewIntegrationTest 42개, RelationshipExitFactsTest 7개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle37.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다.

최종 선택 JUnit **1,313개**, 실패·오류·제외 0개다. EDD/보고/종료/철회/새 감독/고객 요청 747개, KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 세 사이클의 신규 테스트는 **65개**(035 18 + 036 16 + 037 31)이며 최종 747개에 이미 포함돼 중복 합산하지 않는다. 기존 프로세스 **15개**도 통과했다.

전체 실행 뒤 평문 검사에서 JSON 개행과 PostgreSQL escape 표현 때문에 검사가 약해지지 않도록 식별 가능한 문구와 실제 암호문 바이트를 확인하고, 한글 분해/조합 정규화 단언을 보강해 현재 신규 두 클래스 **31개**를 다시 통과했다. 추가 단언과 집중 결과는 전체 사례 수에 중복으로 더하지 않는다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점 222개 버전의 알려진 OSV 검출 0개·변조 체크섬 예상 Gradle 종료 1 차단을 확인했다. 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB·체크섬 표본을 정리했다. 현재 코드 발췌 **165개**와 별도 역사/범위 6개를 실제 소스와 대조했다. 명령/개별 사례·중간 실패·집중/최종 검증 분리·자원 정리·공식 원문과 한계는 확정 증적을 따른다.

중간 compileTestJava에서는 새 테스트의 cdd.EddService와 security.OperatorMfaService import 누락 2개를 수정했다. 제품 테스트 실행 전의 오류이며 예전 XML을 새 테스트 결과로 세지 않았다. 이후 집중 31개와 최종 전체 선택 회귀를 구분해 보존한다. 기존 컴파일/JVM 도구 경고는 결과 한계로 남긴다.

## 남은 한계와 운영 조건

- 내부 접수와 원문 제공까지다. 조사/회신·통지 내용/시점·처리 기한·추가 증빙/대표자·고객 불만 분류·외부 분쟁 조정 전체는 미완료다.
- 원래 두 인물이 모두 자격을 잃은 경우의 인계/고립 복구·미열람 고객 요청 전사 경보/작업 완료 추적은 아직 없다. 035·036 감독과 이 원문 권한은 구분한다.
- 로그인할 수 없는 고객의 대체 접수 경로와 장애 시 수동 절차를 운영 전에 마련해야 한다. 고객의 본인 계정 재검토에만 적용하며 일반 민원 전체의 접수를 막는 정책으로 적용하지 않는다.
- 고객의 주장을 기존 EDD 근거 묶음에 자동 편입하거나 기존 서명에 대한 본인 제공으로 취급하지 않는다. 실제 증빙/평가 갱신은 기존 업무에서 수행해야 한다. 사유의 실제 타당성·자료 진위·법적 적정성을 자동 인증하지 않는다. 종료 철회는 034의 독립 판단이고 실제 최종 관계 종료·잔여 자산 반환·STR/법정 의무는 별도다.
- 개인정보 처리 근거/고지·보존/파기·키 격리/KMS·운영 계정·외부 백업/복제·기관/조직/인증 조건은 별도다. 암호화 코드가 개인정보 보호/ISMS 전체 충족을 뜻하지 않는다.
- 신규 강제 종료 시험 0개와 성능 수치 없음. 기존 프로세스 회귀·JMH 컴파일을 새 기능 장애/성능 보장으로 바꾸어 설명하지 않는다. 봇 0명, 엔진/프론트 변경·기존 개발 DB 변환·서버 재시작/운영 배포는 없다.

## 관련 커밋과 증적

- [`adc8acb`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/adc8acb16a32a99a4ac769a8b3c7dd11bfc9723b): 고객 종료 개시 재검토 요청 접수와 감사 열람와 관련 회귀.

[037 확정 증적](../reference/customer-exit-review-verification-2026-10-24.json), [036 미결 점검](036-exit-approval-supervision.md), [035 활성 종료 점검](035-exit-supervision.md), [034 독립 철회](034-exit-rescission.md).
