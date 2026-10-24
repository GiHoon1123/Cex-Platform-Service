# 내부 메모와 분리한 고객용 상태 회신을 기록하기

현재 지정 담당자가 고객 재검토 요청에 대해 원래 종료 개시의 확정 상태를 회신한다. 직원이 상태나 자유 문구를 입력하지 않고 서버가 실제 승인/철회에서 문구를 계산한다. 개발자가 상태 사실·판단·고객 안내의 경계와 코드/실패 검증을 설명하도록 작성했다.

## 문제와 실제 범위

037은 접수증, 038은 발견, 039는 내부 처리 관찰을 제공한다. 내부 메모를 그대로 고객에게 주면 STR/조사 사유 등이 노출될 수 있다. 반대로 접수증만으로는 원래 종료 개시의 확인된 상태를 안내할 수 없다.

이번 회신은 **원래 종료의 당시 상태 확인**이다. 실제 재검토 판단의 완료·사유 설명·민원 종결·법적 통지 이행·전체 거래 허용이 아니다. 독립 종료/철회 결정 자체는 기존 033·034가 담당한다. 새 금융 승인 종류를 추가하지 않는다.

## 기존 제품과 공식 근거

확인일 2026-10-24. [Zendesk 댓글](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 공개/비공개 구분과 [읽기 전용 감사](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/)의 변경 보존을 참고한다. 여기서는 공개 플래그 하나를 뒤집는 대신 내부 메모와 별도 회신 표/DTO/API를 사용한다. [미래에셋증권](https://trading.securities.miraeasset.com/hki/hki3062/n18.do)의 접수·조사·회신 분리를 참고하되 그 기관/14영업일 정책을 거래소 의무로 가정하지 않는다. [업비트](https://static.upbit.com/terms/private_data.html)·[빗썸](https://privacy.bithumb.com/ko/faq/)의 고객지원과 관계 관리 목적을 구분한다.

[개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353), 법률 제21445호·시행 2026-09-11과 [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률 제21857호·시행 2026-10-02를 대조했다. 최소 정보 제공을 적용하되 고객확인·실제 종료·STR 검토/기관 제출·고객 통지/비밀 유지의 법적 적합성 전체를 이 코드로 인증하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 서버가 기존 확정 결정에서 계산하는 두 상태와 문구 | 임의 상태/내부 사유를 고객에게 보내는 실수를 줄인다. | 개별 사안의 이유·민원 처리 결론은 설명하지 못한다. 담당자의 자유 답변·추가 자료 요청·법적 통지는 별도다. |
| 본인 최근 고객 사유 제공·최신 내부 기록에 결합 | 맥락 없이 상태만 전송하지 않고 어떤 기록 뒤 회신했는지 추적한다. | 재열람·키 보존·충돌 후 재조회·DB/인력 비용이 든다. 이것이 실제 조사 품질/사람의 이해를 증명하지는 않는다. |
| 불변 회신과 HMAC·복합 외래 키 | 기록 후 상태가 달라져도 과거 회신을 보존하고 잘못된 근거 결합/변조를 탐지한다. | 저장/키 관리/검증 비용과 고객 행 잠금 경합이 있다. 이전 기록을 덮어쓰거나 새 현재 상태로 자동 바꾸지 않는다. |

최근 5분 본인 제공·24시간 요청별 새 회신 10개·25개 목록은 자체 정책이다. 법정 기한/회신 횟수라고 주장하지 않는다. 새 표 하나·기존 메모의 근거용 고유 인덱스만 추가하고 패키지/승인 종류는 추가하지 않는다. 금융 효과를 바꾸지 않는 상태 관찰이므로 새로운 이중 금융 승인을 만들지 않고 기존 독립 결정만 참조한다.

## API와 데이터 흐름

- POST /api/admin/compliance/customer-review-requests/{id}/replies: 현재 지정 OPERATOR/APPROVER, 최근 MFA·본인 제공, Idempotency-Key, latestNoteId만 받는다. 상태/자유 문구는 입력받지 않는다.
- GET 같은 경로: 현재 지정 인물의 회신 목록.
- GET /api/customer/relationship/review-requests/{id}/replies: 실제 본인의 현재 세션으로 공개 회신 목록만 조회한다.

원래 대상 A가 유효하면 EXIT_INITIATION_ACTIVE, A에 실제 승인 철회가 있으면 EXIT_INITIATION_RESCINDED다. A 철회 후 새 종료 B가 있어도 A의 회신이지 B나 전체 거래 허용의 상태가 아니다. 고객·담당자를 정렬 잠금하고 실제 최신 내부 순번/서명을 확인한다. 같은 키/같은 근거 재송신은 옛 회신만 재조회하며 새 상태로 갈아 끼우지 않는다.

회신은 고객 API 조회로 제공한다. 이메일/SMS/외부 알림은 발송하지 않는다. RECEIVED 접수증은 원래 접수 사실로 유지하며 이를 처리 완료로 덮어쓰지 않는다. 관찰 시각과 고정 문구를 별도로 읽어야 한다.

## 실제 코드와 문법

### 본인 제공과 실제 최신 내부 근거

[CustomerReviewReplyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewReplyService.java#L35)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
requests.requirePersonalView(actor,sid,request);var note=notes.currentBasis(request,latestNote);
```

최근 본인 세션에서 고객 사유를 제공받고, 호출자가 지정한 기록이 논리 순번상 최신인지 확인한다. 메모 원문은 공개하지 않지만 해독/HMAC 검증으로 근거의 무결성을 확인한다. 다른 메모가 새로 생기면 새 회신은 충돌하고 옛 키의 응답 유실 재조회만 유지한다.

### 직원이 입력하지 않은 상태를 서버가 계산

[CustomerReviewReplyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewReplyService.java#L38)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE review_id=? AND user_id=?)",Boolean.class,exit,user))){status=Status.EXIT_INITIATION_ACTIVE;basis=exit;}
else {
    var decisions=jdbc.queryForList("SELECT request_id FROM relationship_exit_rescission_decisions WHERE exit_id=? AND user_id=? AND approved",exit,user);
    if(decisions.size()!=1)throw new IllegalStateException("회신할 원래 종료 상태의 확정 근거가 없습니다");status=Status.EXIT_INITIATION_RESCINDED;basis=(UUID)decisions.getFirst().get("request_id");
}
```

요청의 원래 종료 ID만 확인한다. 활성 뷰에서 빠졌다는 이유만으로 철회로 추측하지 않고 실제 APPROVED 독립 철회를 반드시 찾는다. 새 종료 B가 생겨도 원래 A에 대한 회신이며 전체 거래 허용을 뜻하지 않는다. 고객 행 잠금으로 결정/철회와 순서를 정한다.

### 당시 상태·근거를 보존하고 감사와 확정

[CustomerReviewReplyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewReplyService.java#L45)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var payload=new Payload(id,request,user,actor,sequence,note.id(),note.sequence(),status,basis,message(status),Instant.now().truncatedTo(ChronoUnit.MICROS));String raw=json.writeValueAsString(payload);
jdbc.update("INSERT INTO customer_exit_review_replies(id,request_id,sequence,actor_id,request_key,note_id,note_sequence,payload,content_mac,fingerprint_key_id) VALUES(?,?,?,?,?,?,?,?,?,?)",id,request,sequence,actor,key,note.id(),note.sequence(),raw,mac(raw),fingerprints.keyId());
audit.record(actor,"CUSTOMER_REVIEW_REPLY_RECORDED","customer-reply:"+id,"SUCCESS","STATUS_OBSERVED_ONLY");return safe(payload);
```

Java record인 Payload는 런타임 값이다. 요청·고객·행위자·논리 순번·최신 메모·확정 근거·실제 안내 문구·마이크로초 시각을 묶은 JSON에 기존 비밀 HMAC을 붙인다. TypeScript interface만으로는 이 런타임 기록/검증이 생기지 않는다. 감사 실패는 회신과 순번을 함께 롤백한다.

### 공개 회신의 허용 목록

[CustomerReviewReplyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewReplyService.java#L53)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
// 조회 때 최신 문구로 바꾸지 않는다. 실제 회신 당시 서명한 문구를 그대로 제공한다.
private Reply safe(Payload p){return new Reply(p.id(),p.status(),p.message(),p.observedAt());}
private String message(Status status){return switch(status){
    case EXIT_INITIATION_ACTIVE->"회신 시점에 요청 대상의 거래관계 종료 개시는 유지 중입니다. 재검토 요청의 처리 완료를 뜻하지 않습니다.";
    case EXIT_INITIATION_RESCINDED->"요청 대상의 거래관계 종료 개시가 철회된 사실을 확인했습니다. 다른 고객확인·거래 제한은 별도로 적용됩니다.";
};}
```

신규 작성 때 switch로 두 상태에 대응하는 서버 문구만 선택해 HMAC 대상 payload에 저장한다. 조회 때는 현재 템플릿을 다시 적용하지 않고 당시 저장된 문구를 그대로 제공한다. 내부 메모·STR·행위자/근거 ID·임의 고객용 문구는 이 응답에 복사하지 않는다. 공개 시각은 회신 당시 관찰 시각이며 현재 전체 거래 상태가 아니다.

### 원래 요청과 내부 기록의 외래 키 결합

[customer-exit-review-schema.sql](../../src/main/resources/edd/customer-exit-review-schema.sql#L32)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```sql
CREATE TABLE IF NOT EXISTS customer_exit_review_replies (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_exit_review_requests(id),sequence bigint NOT NULL CHECK(sequence>0),
 actor_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,note_id uuid NOT NULL,note_sequence bigint NOT NULL,
 payload text NOT NULL,content_mac varchar(64) NOT NULL,fingerprint_key_id varchar(64) NOT NULL,
 FOREIGN KEY(note_id,request_id,note_sequence) REFERENCES customer_exit_review_notes(id,request_id,sequence),
 UNIQUE(actor_id,request_key),UNIQUE(request_id,sequence)
);
```

세 필드 외래 키가 다른 요청의 메모나 다른 순번을 근거로 붙이지 못하게 한다. 사용자별 요청에 대해 회신 순번이 고유하다. 별도 트리거로 수정/삭제/일괄 삭제를 거부하고, 읽을 때 HMAC과 행/근거 필드를 다시 검사한다.

## 작성한 테스트

[CustomerReviewReplyIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewReplyIntegrationTest.java)의 실제 PG·독립 서명·감사·MVC 사례다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `activeExitProducesOnlyControlledHistoricalStatus` | 유효 종료의 안전 상태·서명한 당시 안내 문구 보존, 내부 메모/작성자/근거 정보 없음 |
| `rescindedStatusRequiresActualApprovedIndependentRescission` | 실제 독립 승인 철회만 철회 상태로 회신 |
| `pendingAndRejectedRescissionDoNotPublishRescindedStatus` | 미결/반려 철회를 철회 완료처럼 공개하지 않음 |
| `oldRequestIsNotConfusedWithAReinitiatedExit` | 새 종료 B가 있어도 원래 A의 철회 사실만 회신 |
| `sameKeyReplayPreservesOldStatusAfterActualStateChange` | 옛 키는 옛 회신 보존, 새 키만 새 사실 회신 |
| `staleInternalNoteCannotBeUsedForNewReplyButOldKeyStillReplays` | 새 메모 뒤 옛 근거의 새 회신 거부, 과거 키 재조회 유지 |
| `anotherPersonsViewCannotAuthorizeReply` | 다른 사람의 제공은 대체 불가, 본인 제공 뒤 허용 |
| `missingAndForeignNoteIdsAreRejected` | 없는/빈 근거 거부 |
| `concurrentRepliesKeepDistinctLogicalOrder` | 두 스레드 동시 회신은 고유한 1·2 순번 |
| `tenRepliesAndIdempotentReplayRespectDailyLimit` | 요청별 새 회신 10개 한도, 동일 키 재조회 유지 |
| `auditFailureRollsBackReplyAndSameKeyCanRetry` | 감사 실패 전체 롤백과 같은 키 재시도 |
| `customerCannotReadOtherCustomersReplies` | 실제 다른 고객 계정/세션의 접수별 회신 접근 거부 |
| `customerReadAuditFailureDoesNotReturnReplyList` | 본인 조회도 감사 실패 시 반환 없음 |
| `immutableReplyAndCopiedPayloadIntegrityAreEnforced` | 회신 수정/삭제/일괄 삭제 차단, 다른 행으로 복사한 payload 결합 검증 실패 |
| `currentStaffAuthorityIsRequired` | 비활성/만료/복구/인물 교체 4개 기록 거부 |
| `oldMfaAndRevokedCustomerSessionAreRejected` | 오래된 운영 MFA/회수 고객 세션 거부 |
| `badPageKeyAndAmbientTransactionAreRejected` | 페이지·키·외부 트랜잭션 거부 |
| `httpOnlyAssignedStaffCanRecordAndCustomerReadsSafeReply` | MVC 지정자 기록/본인 안전 회신·저장 금지 헤더, 고객/미배정자 기록 권한 없음 |

서버가 다른 문구를 서명한 과거 템플릿 표본도 추가해 현재 안내문으로 덮어쓰지 않는지 검사한다. 이 표본은 실제 고객에게 새 금융 판단을 전송한 것이 아니다. 복사 payload 표본은 추가 전용 부정 검사이며 정상 직원 회신이나 금융 판단을 위조한 예가 아니다. 실제 원래 종료/철회는 제품 API와 소프트웨어 보안키 서명으로 만들고 기존 금융/고객확인 회귀를 함께 검증한다.

## 실행 명령과 결과

선택 JUnit **805개**, 실패·오류·제외 0개. AmlInvestigationIntegrationTest 42개, AmlResolutionIntegrationTest 35개, AddressFactsTest 12개, CustomerReviewNoteIntegrationTest 21개, EvidenceCryptoTest 9개, ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, ReportingFollowupIntegrationTest 28개, ExitApprovalSupervisionIntegrationTest 16개, WealthReviewIntegrationTest 40개, ExitRescissionFactsTest 7개, EddDossierIntegrationTest 23개, FundsFactsTest 19개, AmlFalsePositiveRulesTest 38개, ReportingCustodyIntegrationTest 45개, FundsReviewIntegrationTest 43개, EddEvidenceIntegrationTest 26개, ExitRescissionIntegrationTest 43개, ReportingFactsTest 10개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, ReportingInternalCheckTest 3개, EddIntegrationTest 35개, ReportingFollowupFactsTest 15개, WealthFactsTest 30개, EvidenceImageTest 14개, AmlSourceProjectionTest 11개, CustomerReviewReplyIntegrationTest 21개, ReportingSupervisionIntegrationTest 24개, ReportingReviewIntegrationTest 37개, RelationshipExitIntegrationTest 44개, CustomerReviewNoteFactsTest 2개, AddressReviewIntegrationTest 42개, RelationshipExitFactsTest 7개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle40.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다.

최종 선택 JUnit **1,371개**, 실패·오류·제외 0개다. EDD/보고/종료/고객 요청·감독·내부 기록·회신 805개, KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 이번 세 사이클 신규 **58개**(038 14 + 039 23 + 040 21)는 최종 805개에 이미 포함돼 중복 합산하지 않는다. 기존 프로세스 **15개**도 통과했다. 전체/광역 실행 뒤 안내 문구 보존의 최종 변경은 현재 신규 21개와 bootJar로 다시 통과했다. 과거 서버 문구를 현재 템플릿으로 덮어쓰지 않는 표본도 검사했다. 실행 시점을 구분하며 집중 21개와 각 사이클의 선택 회귀는 중복 합산하지 않는다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점 222개 버전의 알려진 OSV 검출 0개·변조 체크섬의 예상 Gradle 종료 1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 현재 실제 발췌 **176개**와 별도 역사/범위 6개를 대조했다. 실제 명령·개별 사례·중간 오류/집중/최종 검증·공식 자료·한계는 확정 증적에 남겼다.

초기 집중 실행 뒤 다른 고객 접근을 실제 별도 고객/세션으로 보강해 전체 805개에서 검증했다. 이후 안내 문구까지 서명·보존하도록 보완하고 과거 템플릿 표본을 포함한 현재 신규 21개와 bootJar를 다시 검증했다. 전체/광역 실행 시점과 문구 보존 최종 집중 검증을 구분한다. 중간/집중 결과를 최종 합계에 중복 합산하지 않는다. 기존 컴파일/JVM 도구 경고는 남아 있으며 새 강제 종료·성능 수치는 없다.

## 남은 한계

고객 재검토의 최종 판단/종결·개별 사유 답변·법정 기산점/기한/통지·실제 외부 발송·기관 절차·원래 담당자 부재 인계는 별도다. 로그인 불가 고객의 대체 접수/회신 채널과 키 교체/보존/파기·백업/복제·조직/ISMS/신고 심사는 운영 전에 확인해야 한다. 기존 원장/예약·STR·고객확인/종료 결정은 변경하지 않는다. 엔진 코어와 프론트·기존 개발 DB/저널·서버 재시작/운영 배포는 없으며 봇은 0명이다.

## 관련 커밋과 증적

- [`44b41ee`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/44b41ee02432dbe2cacfc5561958b1f0d5433a6b): 고객용 상태 회신과 당시 안내 문구 보존와 관련 회귀.

[040 확정 증적](../reference/customer-review-replies-verification-2026-10-24.json), [039 내부 관찰](039-customer-review-notes.md), [038 전사 점검](038-customer-review-supervision.md), [037 고객 접수](037-customer-exit-review.md).
