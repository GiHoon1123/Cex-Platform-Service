# 현재 담당자 모두 부재인 고객 업무를 독립 복구하기

현재 두 인물이 모두 실제 업무 권한을 잃은 고객 재검토 접수를 원문 없이 발견하고, 독립 승인으로 새 담당자를 배정한다. 계정 재활성화와 재상실을 거쳐 옛 서명이 다시 유효해지는 문제도 막는다.

## 문제와 기존 동작

[041 정상 인계](041-customer-work-transfer.md)는 현재 담당자가 요청해야 했다. 두 계정이 퇴사·만료·역할 상실 등으로 사용할 수 없으면 아무도 인계를 시작할 수 없다. 반대로 외부 운영자에게 모든 고객 원문을 공개하거나 단일 인물의 부재만으로 인수를 허용하면 목적 제한과 현재 담당자의 통제를 약화한다.

## 기존 제품과 공식 자료의 접근

2026-10-24 확인한 [Zendesk의 부재 담당자 업무 재배정](https://developer.zendesk.com/documentation/ticketing/managing-tickets/tickets-reassign-orphaned/)은 계정 비활성화 뒤에도 기존 배정이 남아 별도 재배정이 필요함을 설명한다. 공개 안내의 발견·명시적 인수 패턴을 적용하며 제품 내부가 이 프로젝트와 같은 독립 승인/인원/암호화 구조라고 주장하지 않는다. [PostgreSQL 16 LIMIT 문서](https://www.postgresql.org/docs/16/queries-limit.html)의 유일하고 일정한 정렬 원칙에 따라 created_at·id를 커서로 사용한다.

국내 의무와 공개 거래소·증권사 자료의 적용 조건은 [041 근거](041-customer-work-transfer.md#기존-제품과-공식-근거)를 따른다. 고객확인·종료/보고 기록과 개인정보 목적 제한을 보존하며 담당자 공백의 발견과 인수를 자체 운영 통제로 구현한다. 이 복구 방식·두 명 기준·10분/5분/일일 10개·독립 인원 수는 법정 수치가 아니다. 법정 처리기한·기관 제출·조직/인증 충족을 선언하지 않는다.

## 선택과 트레이드오프

현재 둘 모두 계정·인물·역할·유효기간·복구 상태 기준으로 업무 불가일 때만 외부 OPERATOR/APPROVER가 복구를 요청한다. 한 명이라도 가능하면 그 사람이 정상 인계를 진행한다. MFA가 오래됐다는 사실만으로 담당자를 빼앗지 않는다.

발견은 최소 메타데이터이고 실제 권한 이전은 요청자·기존/신규 인물에게서 독립된 APPROVER의 실제 업무별 서명을 요구한다. 외부 요청자는 배정 후보를 고르더라도 원문을 읽지 못하며 승인자도 배정 사유만 본다. 인수 승인 후 현재 두 담당자만 고객 사유·내부 기록·회신을 이어받는다. 더 넓은 복구권보다 원문 노출을 줄이지만 별도 독립 인력과 새 두 역할, 반복 인증/서명·운영 대응이 필요하다.

이전 두 계정의 버전도 승인 내용에 고정한다. 무해한 계정 정보 변경도 미결 요청을 무효화해 새 요청/서명이 필요하다는 운영 비용이 있다. 대신 재활성화→재정지 뒤 옛 증명이 살아나는 우회를 막는다. 미결 요청은 만료/반려 전 다음 세대 요청을 막으며 별도 강제 무효화 경로는 추가하지 않는다. 승인된 금융/업무 이력은 그대로 보존하고 기존 증거를 덮어쓰거나 변환하지 않는다. 새 의존성·DB 표·승인 종류는 추가하지 않으며 041의 13번째 CUSTOMER_REVIEW_CUSTODY를 재사용한다.

## 구현과 실제 코드

GET `/api/admin/compliance/customer-review-orphans?afterReceived=...&afterRequest=...`에서 두 값 모두 없는 첫 요청 또는 두 값 모두 있는 다음 커서를 받는다. 서버가 최대 25개 접수를 검사하며 `items`는 그중 현재 공백만, `scanned`는 검사 수, `next`는 계속 검사할 위치다. 빈 items만 보고 끝내지 말고 next가 없을 때 끝낸다. 권한·배정이 바뀌면 처음부터 갱신한다. 고객 사유·내부 메모·STR 내용은 목록에 없다. 요청 시 `/customer-review-requests/{id}/custody`로 실제 현재 stamp를 얻고 기존 인계 API를 사용한다. mode는 서버가 결정한다.

Java의 List.copyOf는 응답 목록 변경을 막고, record는 값 필드를 묶는다. 사용자 행 잠금·READ COMMITTED·독립 승인/감사 원자성은 041 계약을 유지한다. 다음은 실행 예제가 아닌 실제 소스의 부분 발췌다.

### 담당자 공백을 원문 없이 발견하기

[CustomerWorkCustodyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustodyService.java#L48)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Orphans orphans(long actor,UUID sid,Cursor after){return attempt(actor,()->tx.execute(t->{
    sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER);
    if(after!=null&&(after.receivedAt()==null||after.requestId()==null||after.receivedAt().isBefore(Instant.EPOCH)||after.receivedAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
    var rows=after==null?jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests ORDER BY created_at,id LIMIT 25"):
        jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests WHERE (created_at,id)>(?,?) ORDER BY created_at,id LIMIT 25",Timestamp.from(after.receivedAt()),after.requestId());
    var result=new ArrayList<Context>();for(var row:rows){var state=custody.current((UUID)row.get("id"));if(state.members().stream().noneMatch(m->custody.available(m,users.findById(m.id()).orElse(null))))result.add(context(state,true));}
    var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(((Timestamp)last.get("created_at")).toInstant(),(UUID)last.get("id")):null;
    audit.record(actor,"CUSTOMER_WORK_ORPHANS_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");return new Orphans(result,next,rows.size());
}));}
```

수신 시각과 접수 UUID의 복합 커서로 최대 25건을 검사한다. 필터 결과가 비어도 검사한 마지막 행의 커서를 돌려줘 다음 페이지로 진행한다. 배정 사유나 고객 사유는 반환하지 않고 감사가 성공해야 메타데이터를 제공한다. 조회 결과는 관측일 뿐 실제 요청과 승인은 사용자 잠금 아래 다시 검사한다.

### 기존 두 계정의 버전 고정

[CustomerWorkCustodyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustodyService.java#L58)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
private List<Target> prior(CustomerWorkCustody.State state,Map<Long,User> people){return state.members().stream().map(m->{var u=people.get(m.id());if(u==null)throw new IllegalStateException("원래 배정 계정이 없습니다");return new Target(m.id(),m.person(),u.getVersion());}).toList();}
```

Java record Target에 계정·실제 인물·행 버전을 함께 넣는다. 같은 계정이 재활성화됐다 다시 정지되어도 버전이 달라 옛 복구 서명을 사용할 수 없다. TypeScript의 구조적 객체 비교와 달리 Java record의 equals는 필드 값 비교를 제공한다.

### 서버가 정한 복구 방식과 기존 상태 재검사

[CustomerWorkCustodyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerWorkCustodyService.java#L122)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
if(p.mode()!=(state.contains(maker)?Mode.TRANSFER:Mode.RECOVERY)||!prior(state,people).equals(p.previousMembers())||!read(p.id()).status().equals("PENDING")||!r.payloadHash().equals(hash)||!POLICY.equals(p.policy())||!p.evidenceKeyId().equals(crypto.keyId())||!p.cddKeyId().equals(cdd.keyId())
    ||!state.stamp().equals(p.previousStamp())||state.generation()+1!=p.generation()||!origin.hash().equals(p.reviewHash()))throw denied(HttpStatus.CONFLICT);
```

외부 요청자가 정상 담당자인 척 mode를 지정할 수 없다. 요청과 결정 시 서버가 현재 배정 포함 여부로 TRANSFER/RECOVERY를 정하고 이전 두 계정 버전·현재 세대·키·원래 고객 접수 연결을 다시 검사한다. 기존 인물이 한 명이라도 업무 가능하면 eligible에서 복구 자체를 거부한다.

## 작성한 테스트

[CustomerWorkRecoveryIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerWorkRecoveryIntegrationTest.java)는 실제 격리 PostgreSQL·실제 보안키 암호 서명·MVC·감사 실패 주입을 사용한다. 매 테스트는 실제 종료 승인과 고객 접수를 만들고 같은 고객 금융 차단을 보존한다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `outsideRequesterSeesOnlyOrphanMetadataBeforeIndependentApproval` | 독립 복구 전에는 원문 없는 최소 목록만 제공하고 실제 승인 뒤에만 새 담당자에게 접근 허용 |
| `oneAvailablePersonPreventsOutsideRecovery` | 기존 한 인물이 업무 가능하면 외부 인수 거부 |
| `eachActualLossOfBothCurrentPeopleAllowsRecovery` | 비활성·기한 만료·복구 중·역할 상실·인물 변경 각각 실제 권한 상실 판단 |
| `staleMfaOfCurrentPeopleDoesNotMeanTheirAuthorityIsGone` | 최근 MFA 시간 경과를 영구 권한 상실로 오인하지 않음 |
| `reactivatedMemberPreventsRecoveryAndSecondLossDoesNotReviveOldProof` | 재활성화 및 재정지 후에도 이전 서명 무효 |
| `oldMemberVersionChangeAlsoInvalidatesPendingNormalTransfer` | 정상 인계도 기존 두 계정 버전 변경 시 다시 요청 |
| `rejectionPreservesOrphanAndAllowsNewRequest` | 반려 뒤 원래 배정 유지와 새 복구 요청 허용 |
| `successfulRecoveryLeavesOrphanListWithoutHidingHistory` | 복구 뒤 공백 목록 제외, 고객/메모/회신 이력 보존 |
| `recoveryCannotGrantOriginalFinancialAuthority` | 원래 종료·철회·STR 권한을 새 담당자에게 주지 않음 |
| `lostRequesterAuthorityPreventsUsingItsPendingRecovery` | 요청자 권한 상실 뒤 독립 확인 차단 |
| `orphanDiscoveryAuditFailureReturnsNoMetadata` | 감사 실패 시 목록을 제공하지 않음 |
| `malformedCursorAndForeignSessionAreRejected` | 불완전·미래·음수 커서/다른 세션 거부 |
| `httpRecoveryDiscoveryRequiresCurrentOperatorAndNoStore` | HTTP 최근 운영자 인증·no-store·고객/감사자/익명 거부 |

권한 상실 매개변수 5개를 포함한다. 기존 정상 인계·회신·메모·감독·고객 접수 회귀도 함께 선택한다.

## 실행 명령과 결과

선택 JUnit **163개**, 실패·오류·제외 0개. CustomerReviewNoteIntegrationTest 21개, ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, CustomerWorkRecoveryIntegrationTest 17개, ExitApprovalSupervisionIntegrationTest 16개, CustomerWorkCustodyIntegrationTest 23개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, CustomerReviewReplyIntegrationTest 21개, CustomerReviewNoteFactsTest 2개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle42.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다.

실행기의 --rerun-tasks와 bootJar로 현재 제품 코드를 재컴파일했다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 외부 Didit 생성·Kafka·엔진 HTTP는 기존 공통 대역이며 실제 PG/암호 서명/감사/MVC와 구분한다. 테스트 컨테이너는 실행 소유 임시 DB이며 기존 개발 서비스와 데이터를 변경하지 않는다.

## 한계와 후속

공백 발견은 관측 시점의 결과이며 클릭 시 권한을 보장하지 않는다. 25건마다 배정 검증 조회가 필요하고 전체 순회 중 권한 변경은 새 조회가 필요하다. 고객 업무 최종 판정·법정 기한·외부 알림·운영 조직/실물 키·보존/파기·백업/복원·기관/인증 심사는 별도다. 전용 개인 업무 목록과 기록 대비 회신 누락 표시는 043에서 다룬다. 엔진 코어·프론트·기존 DB 변환·개발 서버 재시작·운영 배포는 없고 봇은 0명이다.

## 관련 커밋과 증적

- [`026b960`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/026b96003f0fdaa7fe414e9b5c858c7a728e34a8): 고객 업무 담당자 공백의 독립 복구와 관련 회귀.

[042 확정 증적](../reference/customer-work-recovery-verification-2026-10-24.json), [041 정상 인계](041-customer-work-transfer.md), [031 보고 후속 복구](031-reporting-custody-and-orphan-recovery.md).
