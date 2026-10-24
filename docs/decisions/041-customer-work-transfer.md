# 고객 재검토 후속 권한을 독립 인계하기

현재 두 담당자가 고객 요청을 다른 두 인물에게 넘기고 독립 승인자가 이를 확정한다. 고객 사유·내부 메모·고객 회신의 후속 권한만 바꾸며 원래 종료/철회·STR·자산 판단은 그대로 남긴다. 개발자가 권한 범위·불변 세대·실제 서명/실패 검증을 설명하도록 작성했다.

## 문제와 구현 범위

고객 요청은 원래 종료 개시의 두 인물에게만 열려 있었다. 담당자 교대가 있어도 정상 인계 경로가 없었고 이전 인물의 원문 권한을 회수할 수 없었다. 원래 금융 결정의 작성자를 바꾸거나 일반 운영자에게 원문을 공개하는 방식은 쓰지 않는다.

현재 배정은 접수별 0세대의 실제 기초 인물과 승인된 고객 업무 인계의 최대 논리 세대에서 계산한다. 인계 요청·제공·결정 세 표를 추가하고 13번째 CUSTOMER_REVIEW_CUSTODY 서명을 사용한다. 041 구현 당시 mode는 TRANSFER이며 정상 담당자만 요청했다. 현재는 [042 복구](042-customer-work-recovery.md)에서 두 인물 모두 부재인 RECOVERY를 서버가 결정하며 기존 두 계정 버전도 승인 근거에 고정한다.

## 기존 제품과 공식 근거

확인일 2026-10-24. [Zendesk 수동 배정](https://support.zendesk.com/hc/en-us/articles/4408887127450-Manually-assigning-a-ticket)은 배정 권한·새 담당자·기존 접근 범위를 구분한다. [읽기 전용 변경 감사](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/)는 변경별 이력을 보존한다. 대형 Tickets API 본문은 도구 용량 한계로 읽지 못해 상세 구현 근거로 쓰지 않았고 실제 읽은 공식 배정 안내를 사용했다. 프로젝트의 [보고 인계](031-reporting-custody-and-orphan-recovery.md)의 세대·독립 승인·암호화 패턴을 고객 목적의 별도 자료와 승인 종류로 적용했다. 공개 제품이 동일 인원/암호화/DB 구현을 쓴다고 주장하지 않는다.

[미래에셋증권](https://trading.securities.miraeasset.com/hki/hki3062/n18.do)의 접수·조사·회신 분장과 [업비트](https://static.upbit.com/terms/private_data.html)·[빗썸](https://privacy.bithumb.com/ko/faq/)의 고충 처리 목적, [개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353)의 법률 제21445호·시행 2026-09-11 최소 수집을 대조했다. [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률 제21857호·시행 2026-10-02의 고객확인·종료/보고 의무를 인계로 없애지 않는다. 법정 담당자 임명·처리 기한·기관/인증·조직 요건 충족은 별도다.

## 선택과 트레이드오프

원래/새 두 인물과 요청자에게서 독립된 승인자를 요구해 임의 원문 확대를 줄인다. 두 담당자 전원을 바꾸면 최소 다섯 인물이 필요하며 신원/역할/버전 재확인·반복 열람/서명·보존/복호화·사용자 행 잠금 비용이 든다. 인원 수·10분 요청·5분 본인 제공·요청별 하루 10개는 자체 정책이며 법정 수치가 아니다.

현재 담당자는 고객 사유·과거 내부 기록·과거 공개 회신을 제공받는다. 승인자는 배정 사유만 확인하며 승인 사실만으로 업무 원문을 얻지 않는다. 원래 금융/STR 판단의 담당자나 고객 상태를 수정하지 않는다. 새 의존성이나 범용 동적 승인 실행기를 만들지 않고 기존 Spring/JDBC·보안키·암호화 기능을 재사용한다.

## API와 제거한 경로

- GET /api/admin/compliance/customer-review-requests/{review}/custody: 현재 담당자의 최소 배정 컨텍스트. reviewId는 고객 접수 UUID다.
- POST /api/admin/compliance/customer-custody-requests: reviewId·expectedStamp·primaryId(OPERATOR)·checkerId(APPROVER)·reason·Idempotency-Key.
- POST /customer-custody-requests/{id}/inspect, approve/options, reject/options, approve, reject: 독립 승인자와 해당 업무/방향의 보안키 증명.
- GET /customer-custody-requests/{id}: 현재 담당자·요청자·실제 결정자의 원문 없는 결과 재확인.
- GET /api/admin/compliance/customer-review-requests/{id}/context: 현재 담당자의 접수증·배정 상태.

원래 종료 ID로 묶인 assignedList와 GET /relationship-exits/{exit}/review-requests를 제거했다. 접수별로 배정이 달라지므로 이 경로를 호환 API로 유지하지 않는다. 현재 요청별 컨텍스트로 대체하고 기존 고객/메모/회신 경로에서 현재 배정을 검사한다. 미등록 경로는 기존 기본 거부 정책에 따라 403이다.

제공 영수증에는 당시 배정 generation·stamp도 기록한다. 다른 담당자에게 넘겼다가 돌아와도 이전 세대의 영수증으로 기록/회신하지 못한다. 옛 12종 승인 스키마와 세대 없는 제공 표는 시작 시 거부하며 과거 기록의 인물/세대를 추측해 채우지 않는다. 실제 개발 DB를 변환하거나 금융/감사 이력을 삭제하지 않았다.

## 실제 코드와 문법

### 불변 이력에서 현재 배정 계산

[CustomerWorkCustody.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustody.java#L26)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var latest=jdbc.queryForList("SELECT r.id,r.payload,r.payload_hash,r.generation,r.primary_id,r.checker_id FROM customer_work_custody_requests r JOIN customer_work_custody_decisions d ON d.request_id=r.id AND d.approved WHERE r.review_id=? ORDER BY r.generation DESC LIMIT 1",review);
// 인계가 없으면 실제 원래 배정이 현재 상태다. 이미 사용하지 않는 프로그램을 되살리는 우회가 아니다.
if(latest.isEmpty())return new State(review,origin.userId(),0,origin.hash(),origin.members());
var row=latest.getFirst();String payload=(String)row.get("payload");var handoff=json.readValue(payload,CustomerWorkCustodyService.Payload.class);
if(!verifier.digest(json.readTree(payload)).equals(row.get("payload_hash"))||!CustomerWorkCustodyService.POLICY.equals(handoff.policy())||!review.equals(handoff.reviewId())
    ||!handoff.id().equals(row.get("id"))||handoff.primary().id()!=((Number)row.get("primary_id")).longValue()||handoff.checker().id()!=((Number)row.get("checker_id")).longValue()||handoff.userId()!=origin.userId()||!handoff.reviewHash().equals(origin.hash())||handoff.generation()!=((Number)row.get("generation")).longValue())throw new IllegalStateException("인계 근거 검증 실패");
return new State(review,origin.userId(),handoff.generation(),(String)row.get("payload_hash"),List.of(new Member(handoff.primary().id(),handoff.primary().person()),new Member(handoff.checker().id(),handoff.checker().person())));
```

원래 고객 접수의 배정은 0세대다. 실제 승인된 인계만 논리 generation으로 고르며 시각 최대값이나 별도 가변 담당자 칼럼으로 현재 상태를 복제하지 않는다. 원래 종료 판단과 고객/접수 연결의 해시를 대조한다.

### 같은 순서의 사용자 잠금과 세대 재검사

[CustomerWorkCustody.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustody.java#L42)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Map<Long,User> lock(State expected,long... extra) {
    transaction();
    // 배정 변경을 기다린 뒤 새 확정 상태를 읽어야 한다. 고정 스냅샷 격리는 권한 판정에 사용하지 않는다.
    if(!"read committed".equals(jdbc.queryForObject("SELECT current_setting('transaction_isolation')",String.class)))throw new IllegalStateException("현재 배정 검사는 READ COMMITTED 격리 수준이 필요합니다");
    var ids=new TreeSet<Long>();ids.add(expected.userId());expected.members().forEach(m->ids.add(m.id()));for(long id:extra)ids.add(id);
    var result=new HashMap<Long,User>();ids.forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));
    if(!expected.stamp().equals(current(expected.reviewId()).stamp()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"현재 인계 상태가 바뀌었습니다");return result;
}
```

TreeSet은 중복을 제거하며 식별자 순서로 정렬한다. 실제 DB 격리가 READ COMMITTED인지 확인하고, 현재 두 인물·고객·행위자/대상을 함께 잠근 뒤 stamp를 다시 비교해 다른 인계와의 경합을 거부한다. TypeScript Set과 달리 정렬된 순서를 제공한다.

### 원래 담당자와 새 대상에게서 독립된 승인

[CustomerWorkCustodyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustodyService.java#L117)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
if(maker==null||!maker.isAuthenticationAllowed()||!Set.of(UserRole.OPERATOR,UserRole.APPROVER).contains(maker.getRole())||maker.getVersion()!=p.requesterVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())
    ||state.members().stream().anyMatch(member->member.person().equals(approver.getOperatorPersonId()))||actor==p.requesterId()||approver.getOperatorPersonId().equals(p.requesterPerson())||approver.getOperatorPersonId().equals(p.primary().person())||approver.getOperatorPersonId().equals(p.checker().person()))throw denied(HttpStatus.FORBIDDEN);
```

요청자·현재 두 담당자·새 두 대상과 같은 인물은 승인할 수 없다. 계정을 나눠도 인물 UUID가 같으면 거부한다. 현재 역할·유효 기간·복구 상태는 최근 세션과 함께 다시 검사한다.

### 업무별 서명·단일 세대 결정·감사의 원자성

[CustomerWorkCustodyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustodyService.java#L100)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var r=read(id);var actorUser=check(actor,sid,r,hash,true);var p=r.payload();mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.CUSTOMER_REVIEW_CUSTODY,id,hash,approve,proof);
jdbc.update("INSERT INTO customer_work_custody_decisions(request_id,review_id,generation,approver_id,approver_person,approved) VALUES(?,?,?,?,?,?)",id,p.reviewId(),p.generation(),actor,actorUser.getOperatorPersonId(),approve);
audit.record(actor,"CUSTOMER_WORK_CUSTODY_DECIDED","customer-custody:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
```

CUSTOMER_REVIEW_CUSTODY는 STR 인계와 다른 13번째 승인 종류다. 방향/요청/해시에 결합한 실제 보안키 증명 소모와 인계 결정, 감사가 한 DB 트랜잭션에 확정된다. 감사 실패는 이전 배정과 증명을 보존한다.

## 작성한 테스트

[CustomerWorkCustodyIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerWorkCustodyIntegrationTest.java), 실제 고객 접수·메모·회신·독립 WebAuthn 서명과 PostgreSQL 검증이다. [CustomerWorkTestSupport.java](../../src/test/java/dustin/cex/domains/kyc/CustomerWorkTestSupport.java)는 이후 복구/목록에서도 같은 실제 준비 절차를 재사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `approvedTransferMovesOnlyCustomerWorkAndPreservesHistory` | 승인 뒤 새 두 인물이 사유/메모/회신을 이어받고 고객·거래 차단 보존 |
| `priorPeopleLoseReasonNotesAndRepliesAuthority` | 이전 두 인물의 고객 후속 원문/기록 권한 회수 |
| `newPeopleDoNotGainOriginalExitOrRescissionAuthority` | 새 담당자가 원래 종료/철회 권한은 얻지 못하고 원래 금융 판단 권한은 보존 |
| `pendingAndRejectedTransferDoNotMoveAccess` | 미결·반려 상태에서 권한 이동 없음 |
| `requesterCurrentMembersAndTargetsCannotApprove` | 요청자/현재 담당자/새 대상의 자기 승인 거부 |
| `outsiderCannotSelfAssignHealthyWork` | 무관한 사람의 정상 업무 인수 거부 |
| `duplicateSameKeyReplaysButChangedReasonConflicts` | 같은 키/내용 재조회와 사유 변경 충돌 |
| `unchangedPeopleAndSameHumanTargetsAreRejected` | 무변경·같은 인물 두 계정 대상 거부 |
| `ownInspectionIsRequiredForOptions` | 승인자 본인 제공 없으면 옵션 불가 |
| `oppositeDecisionAndOtherBusinessProofCannotAuthorizeTransfer` | 반대 방향·STR_CUSTODY 서명 증명은 고객 인계에 사용 불가 |
| `targetVersionChangeInvalidatesSignedRequestWithoutConsumingProof` | 새 대상 버전 변경은 옛 서명 거부·기존 배정 유지 |
| `requesterVersionChangeInvalidatesRequest` | 요청자 버전 변경 후 원문/옵션 거부 |
| `proposalAuditFailureRollsBackRequestAndBinding` | 요청/결합 감사 실패 전체 롤백 |
| `viewAuditFailureDoesNotProvideAssignmentReason` | 열람 감사 실패는 사유/영수증 없음 |
| `decisionAuditFailureRestoresProofAndSameProofRetries` | 결정 감사 실패 뒤 같은 증명 재시도 |
| `approvalAndRejectionRaceCommitsExactlyOneDecision` | 실제 동시 승인/반려는 한 결정만 커밋 |
| `resultAfterResponseLossIsReadableWithoutAnotherTransfer` | 응답 유실 뒤 요청자/실제 결정자 메타데이터 확인 |
| `newOwnerMustObtainOwnReasonBeforeNewNotesOrReplies` | 새 담당자도 자신의 사유 제공 뒤 기록/회신 |
| `generationChangeInvalidatesEvenReturningOwnersOldPersonalReceipt` | 원래 담당자로 재인계해도 옛 세대 제공 재사용 불가 |
| `allCustodyRecordsRejectUpdateDeleteAndTruncate` | 새 세 표 모두 수정/삭제/일괄 삭제 차단 |
| `supervisionUsesCurrentCustodiansRatherThanOriginalPeople` | 전사 감독은 현재 배정으로 담당자 공백 계산 |
| `legacyApprovalAndUnboundViewSchemasAreRejectedWithoutBackfill` | 옛 12종 승인/세대 없는 제공 표·REPEATABLE READ 권한 검사는 거부하고 롤백해 이력 보존 |
| `httpTransferAndNewContextWorkWhileOldExitListIsRemoved` | MVC 인계/현재 배정 조회, 제거한 옛 종료별 목록은 기본 인가 거부 |

다른 업무 서명 표본은 STR 바인딩/증명의 종류 격리를 확인하는 부정 검사이며 실제 STR 인계 승인을 만든 것은 아니다. 옛 승인 CHECK는 롤백 트랜잭션의 NOT VALID 표본으로 검사하고 세대 칼럼 제거도 같은 트랜잭션에서 복원한다. 기존 이력이나 불변 트리거를 끄지 않는다.

## 실행 명령과 결과

선택 JUnit **146개**, 실패·오류·제외 0개. CustomerReviewNoteIntegrationTest 21개, ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, ExitApprovalSupervisionIntegrationTest 16개, CustomerWorkCustodyIntegrationTest 23개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, CustomerReviewReplyIntegrationTest 21개, CustomerReviewNoteFactsTest 2개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle41.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다. 첫 145개 중 제거된 경로의 기본 거부 403을 404로 기대한 검사와 업무 트랜잭션 밖의 MFA 옵션 호출 2개를 수정했다. 옛 스키마/격리 거부 검사를 보강했고 전체 선택 146개와 최종 READ COMMITTED 조건을 추가한 집중 23개를 구분했다. 중간 실패는 최종 통과에 합산하지 않았다. 배정 잠금의 실제 READ COMMITTED 검사를 마지막에 보강하고 현재 인계 클래스 23개/bootJar를 다시 통과했다. 146개와 중복 합산하지 않으며 실행 시점을 구분한다.

첫 선택 145개에서는 제거한 경로의 실제 기본 거부 403을 404로 기대한 검사와 업무 트랜잭션 밖에서 MFA 옵션을 생성한 테스트 2개가 실패했다. 기존 거부 계약을 유지하고 테스트를 올바른 트랜잭션/다른 종류 증명으로 수정했다. 스키마 거부 사례도 추가해 최종 선택 실행과 구분했다.

외부 Didit 생성·엔진 HTTP·Kafka·수수료 설정은 기존 대역이며 실제 서명/PG/감사/MVC와 구분한다. 새 강제 종료 시험/성능 수치는 없고 기존 compiler/JVM 경고는 남는다.

## 한계

고객 후속 배정만 이전한다. 원래 종료/독립 철회/STR 권한·법정 담당자·조직·기관 제출을 넘기지 않는다. 두 담당자 부재 복구·새 담당자의 전용 업무 발견·최종 민원 판단/기한/외부 알림·운영 키/백업·보존/파기·실물 키/ISMS/신고 심사는 후속이다. 엔진 단일 스레드 코어/프론트·기존 DB/저널/서버 재시작·운영 배포는 없으며 봇은 0명이다.

## 관련 커밋과 증적

- [`a0efc15`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/a0efc15411efff67d681b83007ea63f3e2010710): 고객 후속 업무의 독립 인계 승인와 관련 회귀.

[041 확정 증적](../reference/customer-work-transfer-verification-2026-10-24.json), [040 회신](040-customer-review-replies.md), [031 보고 인계](031-reporting-custody-and-orphan-recovery.md).


공유 보안 변경을 대조해 기존 승인/접근 190개와 MFA 42개도 새 격리 DB에서 통과했다. 실제 명령·자원 정리와 146개/최종 집중 23개의 실행 시점을 확정 증적에 구분했다.
