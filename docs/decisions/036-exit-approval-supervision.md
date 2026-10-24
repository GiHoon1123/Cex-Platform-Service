# 미결·만료된 종료와 철회 요청을 원문 없이 점검하기

아직 결정되지 않은 종료/철회 요청의 만료와 담당자 변경을 전사 승인 역할이 확인한다. 유효한 종료 개시를 보는 035와 달리, 결정 전에 남은 업무를 찾는 계층이다. 개발자가 종류별 식별자·커서·현재성 표시의 범위를 설명하도록 작성했다.

## 문제와 기존 동작

개인별 목록에는 지정된 요청만 보였고 035는 실제 승인된 현재 종료 효과만 보여줬다. 서명 전에 담당자가 권한을 잃거나 요청이 만료돼도 전사 점검에서 발견할 수 없었다. 만료를 성공/반려로 바꾸거나 레코드를 지우면 미처리의 원인이 사라진다.

이번 API는 실제 결정이 없는 요청을 만료 후에도 표시한다. 승인·반려가 기록되면 미결 목록에서 제외하지만 원래 기록은 보존한다. 읽기만으로 신규 주문·원장·기존 종료/철회·STR를 바꾸지 않는다.

## 기존 제품과 공식 자료의 접근

확인일 2026-10-24. [Adyen Case management](https://docs.adyen.com/risk-management/case-management)의 역할별 수동 검토와 별도 원문 권한, [Stripe dispute API](https://docs.stripe.com/disputes/api)의 개별 사건 ID/상태·원거래 연결을 참고한다. 해외 결제 업무의 기한·결정 효과를 국내 거래소 의무로 옮기지 않는다.

[업비트](https://static.upbit.com/terms/private_data.html)·[빗썸](https://privacy.bithumb.com/ko/faq/)의 관계 관리/고충 처리 목적과 [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887)의 고객확인·종료·보고 여부 검토를 구분한다. 법률 제21857호·시행 2026-10-02를 기준으로 보되 이 목록이나 앱 10분 만료를 법정 기한/의무 이행으로 표시하지 않는다. 삼성증권 민원 안내 본문은 502로 미확인이며 절차를 추정하지 않는다. [PostgreSQL 16 LIMIT](https://www.postgresql.org/docs/16/queries-limit.html)의 정해진 순서를 적용한다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 비용·제약과 대안 |
| --- | --- | --- |
| 종료/철회 미결 후보를 종류별 식별자로 결합 | 둘 중 하나가 만료돼 잊히는 일을 발견한다. | 두 표의 조회·정렬과 담당자 조회 비용이 든다. 종류·UUID를 별도 키로 유지하므로 단순 UUID 중복 제거는 사용하지 않는다. |
| 만료된 요청도 보존/노출 | 재요청/검토가 필요한 과거 업무를 찾는다. | 오래된 요청까지 재순회하는 부담이 있다. 목록에서 지워 완료로 가장하지 않고 실제 승인/반려 기록으로만 제외한다. |
| 현재 인물·역할·버전 일치 표시 | 권한 회수/역할 왕복으로 옛 서명이 살아나지 못함을 점검한다. | 여러 인물의 조회가 같은 시점 스냅샷은 아니다. 현재 자료 전체를 복호화하지 않으며 최종 자격은 결정 API가 다시 검사한다. |
| 요청 생성 시각 인덱스 두 개 | 시간/ID 순회와 anti join의 접근 경로를 지원한다. | 저장 공간·요청 생성 시 인덱스 갱신 비용이 추가된다. LIMIT은 응답 후보 제한이며 전체 DB 작업량의 상한이라고 주장하지 않는다. |

## 구현과 API

GET /api/admin/compliance/exit-approval-supervision. 현재 APPROVER와 최근 MFA를 필터·서비스에서 확인한다. afterCreated·afterKind(EXIT/RESCISSION)·afterRequest를 함께 주거나 모두 생략한다. 최대 25개, (created_at, kind, id) 순서다. 같은 시각/UUID가 두 업무에 있어도 별개 항목이며 전체를 처음부터 새로 순회한다.

응답은 종류·요청 ID·고객/지정 인물 ID·생성/만료 시각·만료 여부·각 담당자의 고정 인물/역할/버전 일치 여부·RECHECK_REQUIRED다. 사유 원문·서명 해시·근거·보고 결론은 제공하지 않는다. 목록을 읽은 승인자가 미지정 원문이나 실제 결정을 수행할 권한을 얻지 않는다.

서명/자격의 단일 진실원을 새 가변 상태에 복제하지 않았다. 새 표·승인 종류·의존성은 없고 요청 표 두 곳의 정렬 인덱스만 추가했다.

## 실제 코드와 문법

### 두 업무의 미결 후보를 분리해 합치기

[ExitApprovalSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitApprovalSupervisionService.java#L29)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
private static final String CANDIDATES="""
    SELECT * FROM (
     SELECT 'EXIT' AS kind,r.id,r.user_id,r.requester_id,r.checker_id,r.created_at,r.expires_at,r.payload,r.payload_hash
      FROM relationship_exit_requests r WHERE NOT EXISTS(SELECT 1 FROM relationship_exit_decisions d WHERE d.review_id=r.id)
     UNION ALL
     SELECT 'RESCISSION' AS kind,r.id,r.user_id,r.requester_id,r.checker_id,r.created_at,r.expires_at,r.payload,r.payload_hash
      FROM relationship_exit_rescissions r WHERE NOT EXISTS(SELECT 1 FROM relationship_exit_rescission_decisions d WHERE d.request_id=r.id)
    ) candidates
    """;
```

NOT EXISTS로 실제 결정이 없는 요청만 고른다. 승인과 반려 모두 미결 목록에서 빠지며 만료만으로는 사라지지 않는다. UNION ALL은 두 종류의 같은 UUID를 같은 요청으로 합치지 않는다.

### 메타데이터 결합과 판단 권한 구분

[ExitApprovalSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitApprovalSupervisionService.java#L64)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
if(!id.equals(row.get("id"))||user!=((Number)row.get("user_id")).longValue()||maker!=((Number)row.get("requester_id")).longValue()||checker!=((Number)row.get("checker_id")).longValue()||!expires.equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException("미결 요청의 대상 결합 검증 실패");
items.add(new Item(kind,id,user,((Timestamp)row.get("created_at")).toInstant(),expires,!expires.isAfter(observed),maker,
    matches(maker,makerPerson,makerVersion,UserRole.OPERATOR),checker,matches(checker,checkerPerson,checkerVersion,UserRole.APPROVER),"RECHECK_REQUIRED"));
```

불변 payload의 해시·정책/보장·대상·인물·만료를 검증한 뒤 최소 값만 반환한다. RECHECK_REQUIRED는 현재 근거/세션/서명을 실제 결정 API가 다시 확인해야 한다는 뜻이다. 담당자 버전이 같고 미만료여도 승인 가능을 보장하지 않는다.

### 역할·인물·버전을 모두 비교하기

[ExitApprovalSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitApprovalSupervisionService.java#L73)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
private boolean matches(long id,UUID person,long version,UserRole role) {
    var u=users.findById(id).orElse(null);return u!=null&&u.isAuthenticationAllowed()&&u.getRole()==role&&person.equals(u.getOperatorPersonId())&&version==u.getVersion();
}
```

현재 계정의 역할과 원래 인물 UUID, 고정된 version을 비교한다. Java long은 원시 정수 값 비교다. 계정이 나중에 같은 역할로 돌아와도 JPA version이 바뀌면 옛 요청의 검토 대상으로 그대로 인정하지 않는다. 현재 MFA 만료는 이 값과 분리한다.

## 작성한 테스트

[ExitApprovalSupervisionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ExitApprovalSupervisionIntegrationTest.java).

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| originalPendingDecisionIsVisibleWithoutPrivateFactsOrDecisionAuthority | 실제 미결 종료의 최소 정보만 제공, 원문/결정 권한 없음 |
| finalizedExitAndRescissionRequestsLeavePendingQueue | 실제 종료 승인 후 제외, 철회 요청은 보이고 승인 후 제외 |
| rejectedDecisionAlsoLeavesQueueWithoutCreatingEffect | 반려도 미결에서 제외, 금융 효과 없음 |
| versionChangedRequesterAndCheckerAreNotReportedAsMatching | 작성자와 승인자 버전 변경 각각 불일치 표시 |
| staleStaffMfaDoesNotInvalidateBoundIdentityVersion | 세션 재인증 필요와 고정 인물 버전 일치 구분 |
| expiredUndecidedRequestRemainsVisibleAndNeverBecomesAnApproval | 만료 표본도 조회하고 승인 효과 없음 |
| sameTimestampPaginationScansEveryRequestExactlyOnce | 같은 시각 26개 표본이 두 페이지에 중복/누락 없이 나타남 |
| sameUuidInBothBusinessKindsIsNotCollapsed | 같은 시각·UUID의 EXIT/RESCISSION이 각각 유지됨 |
| missingCursorPartsFutureAndNegativeTimesAreRejected | 세 커서 필드 누락·미래·음수 거부 |
| currentSupervisorAuthorityIsChecked | 비활성·만료·복구·역할 변경 4개 거부 |
| revokedAndForeignSessionsCannotRead | 회수/다른 인물 세션 거부 |
| auditFailureReturnsNoListOrFinancialEffect | 감사 실패 시 목록 반환 없음, 재시도와 효과 보존 |
| httpBoundaryNoStoreAndMalformedKind | 실제 MVC 역할·익명·헤더·잘못된 enum 입력 경계 |

만료·동일 시각/식별자 테스트는 추가 전용 **메타데이터 표본**이다. 복사한 암호문은 원래 행 문맥에 묶여 있고 새 업무 서명 결합은 없으므로 정상 승인·사유 열람·실제 금융 사실로 사용하지 않는다. 불변 트리거를 끄거나 과거 이력을 수정하지 않는다. 다른 정상/반려/철회는 실제 제품 요청과 소프트웨어 보안키 서명으로 만든다.

## 실행 명령과 결과

선택 JUnit **135개**, 실패·오류·제외 0개. ExitSupervisionIntegrationTest 18개, ExitApprovalSupervisionIntegrationTest 16개, ExitRescissionFactsTest 7개, ExitRescissionIntegrationTest 43개, RelationshipExitIntegrationTest 44개, RelationshipExitFactsTest 7개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle36.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다. 만료 메타데이터 표본의 생성 시각이 시작 커서보다 과거여서 조회되지 않았다. 생성 위치를 수정하고 제품의 마이크로초 만료 정밀도도 표본에 명시했다. 전체 선택 회귀와 현재 신규 클래스 집중 검증을 구분해 재검증했다. 중간 실패는 최종 통과에 합산하지 않았다. 현재 신규 클래스 16개도 정밀도 명시 후 집중 재실행해 모두 통과했다. 135개와 중복되므로 합산하지 않는다.

공통 격리 PostgreSQL·봇 0명·외부 대역 계약을 유지한다. 실제 명령/개별 사례와 자원 정리, 중간 오류를 확정 증적에서 구분한다.

## 한계

버전 일치·미만료는 승인 가능 표시가 아니다. 현재 CDD/EDD·자료 변경·서명·본인 제공은 실제 API에서 다시 검사한다. 담당자 부재 인계·자동 재요청/알림·법정 기한·기관 제출·실제 조직/인증은 별도다. 현재 후보를 순회하므로 나중에 추가된 과거 위치를 찾으려면 처음부터 새로 읽는다. 원래 금융/감사/보고 기록과 고객 상태·엔진 코어·프론트는 변경하지 않는다.

## 관련 커밋과 증적

- [`b8a3fea`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/b8a3fea601826df800d97d45d7bc3bdc2673e4a1): 미결·만료 종료와 철회 요청 점검와 관련 회귀.

[036 확정 증적](../reference/exit-approval-supervision-verification-2026-10-24.json), [035 활성 효과 점검](035-exit-supervision.md), [034 독립 철회](034-exit-rescission.md).
