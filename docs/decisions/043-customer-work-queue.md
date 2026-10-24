# 인계받은 고객 업무와 내부 기록 대비 미회신 건을 발견하기

현재 담당자가 자신에게 배정된 고객 재검토 접수를 찾고, 내부 처리 기록 중 아직 공개 회신의 근거가 되지 않은 최신 기록을 구분한다. 조회나 상태 회신을 최종 민원 처리 완료로 해석하지 않는다.

## 문제와 기존 동작

041·042에서 독립 인계와 담당자 부재 복구를 구현했지만 새 담당자는 접수 UUID를 별도로 전달받아야 했다. 038의 전사 감독은 APPROVER의 최소 점검 목적이므로 일반 OPERATOR의 개인 업무 발견에 사용하지 않는다. 또한 최신 내부 기록을 추가한 뒤에도 예전 회신이 있다는 이유로 후속 업무를 놓칠 수 있었다.

## 기존 제품과 공식 자료의 접근

2026-10-24 확인한 [Zendesk 부재 업무 재배정](https://developer.zendesk.com/documentation/ticketing/managing-tickets/tickets-reassign-orphaned/)은 기존 담당자 업무의 발견과 명시적 재배정을 다루고, [수동 배정](https://support.zendesk.com/hc/en-us/articles/4408887127450-Manually-assigning-a-ticket)은 배정과 접근 권한을 구분한다. [Ticket comments](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 내부 메모/공개 회신 구분을 참고한다. 이 프로젝트에서는 최신 내부 기록과 실제 공개 회신의 결합을 비교하며 다른 제품도 같은 순번/DB/서명 방식을 쓴다고 주장하지 않는다.

[PostgreSQL 16 LIMIT](https://www.postgresql.org/docs/16/queries-limit.html)의 유일 정렬 요구에 따라 접수 시각·UUID로 제한 순회한다. 국내 법적 의무·거래소/증권사 공개 자료·확인 한계는 [041 근거](041-customer-work-transfer.md#기존-제품과-공식-근거)를 연결한다. 이 목록과 미반영 표시는 자체 운영 통제다. 표시가 사라졌다는 사실을 법정 기한 충족·기관 판단·민원 종결로 해석하지 않는다.

## 선택과 트레이드오프

개인 목록은 현재 OPERATOR/APPROVER의 실제 인물 배정만 보여준다. 전사 감독에 일반 운영자 권한을 늘리지 않아 개인정보 목적 제한을 유지한다. 대신 한 요청에 현재 배정·기초 이력을 확인하는 조회가 필요하고 업무 존재·고객 내부 ID는 지정 담당자에게 노출된다.

별도 가변 완료 플래그나 목록용 복제 테이블 없이 이미 보존한 내부 기록·회신의 논리 순번을 사용한다. 재인계·재실행 뒤에도 기존 기록에서 상태를 계산한다. 순번 비교는 실제 최종 판단이나 고객 수신 여부를 대체하지 못하며 상세 원문의 HMAC/복호화 검증은 기존 열람/회신 서비스가 수행한다. 목록은 원문을 복호화하지 않는다.

조회자는 사용자 행을 잠가 현재 인증을 확인하지만 전체 고객을 잠그지 않는다. 고객 업무와 대사/정산의 대기 증가를 줄이는 대신 전체 페이지가 같은 시점의 고정 스냅샷은 아니다. 실제 원문 열람·새 기록·회신은 기존 잠금과 현재 배정/세대·본인 사유 제공을 다시 확인한다. 커서는 영구 처리 체크포인트가 아니며 담당자 변경 후 처음부터 새로 조회한다. 무관한 접수도 최대25개 검사하므로 sparse 배정에는 빈 페이지/DB 왕복이 늘지만 과거 전체 조회·추측성 배정 인덱스를 추가하지 않는다.

## 구현과 API

GET `/api/admin/compliance/customer-review-work?afterReceived=...&afterRequest=...`. 두 커서는 함께 생략하거나 함께 제공한다. `items`는 최대25개 검사 후보 중 현재 자신에게 배정된 접수, `next`는 다음 검사 위치, `scanned`는 검사 수다. items가 비어도 next가 있으면 계속 요청한다. `observedAt`과 `semantics`는 관측/재조회 계약을 표시한다.

| 필드 | 의미 |
| --- | --- |
| requestId / userId / receivedAt | 고객 접수/고객 내부 식별자와 수신 시각 |
| custodyGeneration | 승인된 현재 인계 논리 세대 |
| latestNoteSequence | 현재 최신 내부 처리 기록 순번, 없으면0 |
| latestReplyNoteSequence | 실제 공개 회신이 참조한 최대 내부 순번, 없으면0 |
| hasReply | 실제 회신 존재, 전달/고객 확인/최종 종결을 의미하지 않음 |
| hasUnreflectedNote | 최신 내부 기록이 아직 회신 근거에 반영되지 않음 |

note=reply=0이면 아직 내부 기록과 회신이 없는 접수다. note>reply면 후속 회신 검토가 필요하다. 두 값이 같고 hasReply가 true여도 최종 업무 판단 완료는 아니다. 고객 사유·내부 메모·배정 사유·STR·원문 해시는 돌려주지 않는다. 현재 담당자는 기존 context/inspect/notes/replies API에서 본인 권한에 따라 업무를 이어간다. 목록 조회는 사유 제공 영수증이나 서명·금융 상태를 바꾸지 않는다. 새 표·의존성·설정값·승인 종류는 없다.

## 실제 코드와 이해에 필요한 문법

다음은 현재 실제 소스의 부분 발췌이며 독립 실행 예제가 아니다.

### 원래 작성자 대신 현재 배정으로 목록 구성

[CustomerWorkQueueService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkQueueService.java#L44)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
UUID id=(UUID)row.get("id");var state=custody.current(id);
// 원래 작성자라는 이유만으로 남기지 않는다. 승인된 최신 배정의 계정과 실제 인물이 모두 맞아야 한다.
if(!state.contains(person))continue;
```

승인된 최신 세대에서 계정 ID와 실제 인물 UUID가 모두 맞아야 개인 업무로 표시한다. 이전 인물·독립 승인자·복구 요청자라는 이유만으로 목록이나 원문 권한을 주지 않는다. continue는 TypeScript의 반복문과 같이 해당 후보를 건너뛴다.

### 논리 기록과 실제 회신 근거 비교

[CustomerWorkQueueService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkQueueService.java#L47)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var progress=jdbc.queryForMap("""
    SELECT (SELECT coalesce(max(sequence),0) FROM customer_exit_review_notes WHERE request_id=?) AS note_sequence,
    (SELECT coalesce(max(note_sequence),0) FROM customer_exit_review_replies WHERE request_id=?) AS reply_sequence,
    d.request_id AS final_id,d.decided_at,a.acknowledged_at
    FROM (SELECT 1) seed LEFT JOIN customer_review_final_decisions d ON d.review_id=? AND d.approved
    LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id
    """,id,id,id);
long note=((Number)progress.get("note_sequence")).longValue(),reply=((Number)progress.get("reply_sequence")).longValue();
if(reply>note)throw new IllegalStateException("회신의 내부 기록 근거가 존재하지 않습니다");
UUID finalId=(UUID)progress.get("final_id");
// 최종 답변은 과거 관측 회신과 다른 지표다. 과거 상태 회신과 승인/고객 확인을 따로 제공한다.
items.add(new Item(id,state.userId(),((Timestamp)row.get("created_at")).toInstant(),state.generation(),note,reply,reply>0,note>reply&&finalId==null,finalId,instant(progress.get("decided_at")),instant(progress.get("acknowledged_at"))));
```

max(sequence)와 max(note_sequence)를 하나의 SQL 관측에서 읽는다. 회신 건수나 기록 시각 대신 040이 실제 서명/보존한 내부 기록 순번을 비교한다. 새 내부 기록이 회신보다 앞서면 hasUnreflectedNote가 true다. note=reply=0은 아직 내부 기록과 회신이 없다는 뜻이며 업무 완료를 뜻하지 않는다.

### 빈 필터 페이지도 순회를 계속

[CustomerWorkQueueService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkQueueService.java#L62)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(((Timestamp)last.get("created_at")).toInstant(),(UUID)last.get("id")):null;
audit.record(actor,"CUSTOMER_WORK_QUEUE_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
return new Page(items,next,rows.size(),observed,SEMANTICS);
```

최대 25개 후보를 검사하고 그중 자신에게 배정된 것만 돌려준다. 빈 items라도 next가 있으면 계속 요청한다. 목록 감사 실패는 응답을 중단하며 원문 제공 영수증을 새로 쓰지 않는다. Java 삼항 연산자·record는 응답 분기와 값 묶음에 사용한다.

## 작성한 테스트

[CustomerWorkQueueIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerWorkQueueIntegrationTest.java)에서 실제 고객확인·종료 승인·접수·회신·암호 서명·감사/MVC를 검증한다. 기존 인계/복구 공통 준비를 재사용하고 금융 불변 이력을 직접 수정하지 않는다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `bothCurrentPeopleSeeMinimalMetadataWithoutCompletingAnything` | 현재 두 인물에게만 최소 ID·순번·관측 메타데이터, 고객 접수/금융 차단은 보존 |
| `newNoteNeedsReplyAndReadingDoesNotClearIndicator` | 추가 내부 기록은 미반영 표시, 목록/메모 열람으로 해제 안 됨, 최신 근거의 실제 회신 뒤에만 해제 |
| `noNoteAndNoReplyRemainDiscoverableWithoutInventedCompletion` | 미작성 접수도 계속 발견, 순번0과 hasReply=false의 의미 검증 |
| `normalTransferMovesDiscoveryOnlyAfterApproval` | 미결 때 기존 목록 유지, 실제 승인 뒤 새 두 담당자에게만 표시 |
| `orphanRecoveryMakesWorkDiscoverableWithoutGivingRequesterBody` | 독립 복구 뒤 새 담당자의 목록, 요청자와 독립 승인자에게 원문/업무 미부여 |
| `withdrawalAndDisabledCustomerDoNotHideAssignedHistory` | 원래 종료 효과 철회·고객 정지 뒤에도 업무와 이력 보존 |
| `authorityAndActualPersonAreCheckedOnEveryRead` | 비활성·만료·복구·역할·인물 변경 5입력, 현재 인증 및 실제 인물 배정 검증 |
| `staleRevokedAndForeignSessionsCannotRead` | 오래된 MFA·철회 세션·타인 세션 차단 |
| `malformedCursorsAreRejected` | 한쪽 누락·음수·미래 커서 차단 |
| `auditFailureReturnsNoMetadataOrReadReceipt` | 감사 실패 시 메타데이터 없음, 별도 사유 제공 영수증도 늘지 않음 |
| `twentySixRealReceiptsAdvanceAcrossEmptyFilteredPageForWorkAndOrphans` | 실제 26접수의 첫25개 필터 공백 뒤 마지막 배정 접수 발견; 같은 표본에서 042 공백 발견도 다음 커서로 진행 |
| `httpRequiresCurrentOperatorAndReturnsNoStore` | MVC no-store, 고객/감사자403·익명401·잘못된 커서400 |

매개변수 5입력을 포함한 신규 16개다. 26접수 검사는 실제 새 고객과 업무 API로 만들며 첫 빈 필터 페이지와 042 공백 목록의 다음 페이지 진행도 함께 검증한다. 감사 실패·원문 경계·접근 회수·금융 상태 보존을 정상 경로와 함께 검사한다.

## 실행 명령과 결과

선택 JUnit **861개**, 실패·오류·제외 0개. AmlInvestigationIntegrationTest 42개, AmlResolutionIntegrationTest 35개, AddressFactsTest 12개, CustomerReviewNoteIntegrationTest 21개, EvidenceCryptoTest 9개, ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, CustomerWorkRecoveryIntegrationTest 17개, ReportingFollowupIntegrationTest 28개, ExitApprovalSupervisionIntegrationTest 16개, CustomerWorkCustodyIntegrationTest 23개, WealthReviewIntegrationTest 40개, ExitRescissionFactsTest 7개, EddDossierIntegrationTest 23개, FundsFactsTest 19개, AmlFalsePositiveRulesTest 38개, ReportingCustodyIntegrationTest 45개, FundsReviewIntegrationTest 43개, EddEvidenceIntegrationTest 26개, ExitRescissionIntegrationTest 43개, ReportingFactsTest 10개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, ReportingInternalCheckTest 3개, EddIntegrationTest 35개, ReportingFollowupFactsTest 15개, WealthFactsTest 30개, CustomerWorkQueueIntegrationTest 16개, EvidenceImageTest 14개, AmlSourceProjectionTest 11개, CustomerReviewReplyIntegrationTest 21개, ReportingSupervisionIntegrationTest 24개, ReportingReviewIntegrationTest 37개, RelationshipExitIntegrationTest 44개, CustomerReviewNoteFactsTest 2개, AddressReviewIntegrationTest 42개, RelationshipExitFactsTest 7개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle43.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다.

최종 선택 JUnit **1,427개**, 실패·오류·제외0개다. 전체 EDD/보고/종료/고객 업무861개, KYC150·승인/접근190·MFA42·금융183·실제 시장 연결1개를 통과했다. 이번 세 사이클 신규 **56개**(041 23 + 042 17 + 043 16)는 최종861개에 이미 포함된다. 중간 선택179개와 각 사이클 검증을 더하지 않는다. 기존 프로세스 **15개**도 통과했고 신규 강제 종료 시나리오는0개다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점222개 버전의 알려진 OSV 검출0개·변조 체크섬의 예상 Gradle 종료1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 현재 실제 코드 발췌 **186개**와 별도 역사/범위6개를 소스와 대조했다. 실제 명령·개별 사례·시각/환경·공식 자료·기존 경고/한계는 확정 증적에서 확인한다.

현재 실행 소유 PostgreSQL·실제 보안키 암호 서명·감사/MVC와 공통 대역인 외부 Didit 생성·엔진 HTTP·Kafka를 구분한다. 실제 시장 연결 검증은 별도 실행 소유 Kafka/PostgreSQL에서 수행한다. 기존 DB·개발 서버·금융 기록·Rust 엔진 코어·프론트는 변경하지 않으며 봇은0명이다.

같은 전체 EDD 선택을 다시 실행할 저장소 실행기는 `python3 scripts/verify_edd.py`다. 실행 소유 PostgreSQL을 만들고 제거하며 봇0명·외부 격리 주소를 적용한다. 광역 실행 명령은 확정 JSON의 extended_commands를 따른다.

## 한계

담당자 인계·복구·개인 발견은 완료했지만 고객 재검토의 최종 판정·법정 기한/외부 통지·기관 제출·운영 인원/실물키·보존/파기·외부 백업/복원·인증/신고 심사는 별도다. 목록은 권한·진행의 관측이며 변조된 DB에 대한 독립 감사나 실제 처리량 보장이 아니다. 새 성능 수치·배포·기존 DB 변환·개발 서버 재시작은 없다.

## 관련 커밋과 증적

- [`88db669`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/88db6696ac9fd096476d8f6a5d16350ccff84652): 현재 담당 업무 발견과 내부 기록 대비 회신 점검와 관련 회귀.

[043 확정 증적](../reference/customer-work-queue-verification-2026-10-24.json), [041 인계](041-customer-work-transfer.md), [042 복구](042-customer-work-recovery.md), [039 내부 기록](039-customer-review-notes.md), [040 공개 회신](040-customer-review-replies.md).


046 이후 현재 목록은 `finalReviewId`·`finalDecidedAt`·`customerAcknowledgedAt`도 구분한다. 승인된 최종 답변이 최신 내부 근거를 다루면 hasUnreflectedNote는 false이며, latestReplyNoteSequence는040의 과거 관측 회신 근거를 그대로 뜻한다. 최종 답변 확정은 고객의 명시적 확인이나 외부 통지 이행이 아니다. [046 실제 구현](046-customer-final-receipt.md)을 따르며 당시043 실행 증적은 보존한다.
