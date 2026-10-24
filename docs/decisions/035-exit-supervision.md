# 현재 종료 개시를 원문 없이 전사 점검하기

유효한 종료 개시와 원래 담당자의 현재 자격을 지정 담당자 밖의 승인 역할이 점검한다. 개발자가 목록의 정보 경계·순회 방식·원래 업무와의 차이, 코드와 회귀 결과를 이해하도록 작성했다.

## 문제와 기존 동작

033·034는 종료 개시와 독립 철회를 제공하지만 원래 배정된 인물의 개인 목록만 있었다. 담당자가 모두 권한을 잃었거나 원래 OPERATOR가 사라진 건을 전사에서 최소 정보로 발견할 수 없었다. 일반 승인 역할에 사유 원문이나 철회 권한까지 주면 점검을 빌미로 접근 통제를 우회한다.

이번 계층은 실제 승인된 과거 후보 중 아직 철회되지 않은 현재 차단만 표시한다. 종료 완료·자산 반환·고객 이의 처리 완료로 표시하지 않는다.

## 기존 제품과 공식 자료의 접근

확인일 2026-10-24. [Adyen Case management](https://docs.adyen.com/risk-management/case-management)는 위험 검토 역할과 별도 개인정보 열람 역할을 구분한다. 이 공개 권한 분리를 참고했고 결제 캡처나 거래소 내부 구조를 그대로 옮기지 않았다. [Stripe disputes API](https://docs.stripe.com/disputes/api)는 원거래와 구별되는 개별 업무 식별자로 조회한다. 카드 분쟁의 기한·기관 절차를 거래소 종료 의무로 적용하지 않는다.

[업비트 개인정보 처리방침](https://static.upbit.com/terms/private_data.html)과 [빗썸 FAQ](https://privacy.bithumb.com/ko/faq/)의 거래관계·고객지원 목적 구분을 대조했다. 공개 방침만으로 양사의 내부 감독 구현을 추측하지 않는다. 삼성증권 민원 안내는 본문 502로 확인하지 못해 세부 절차 근거로 삼지 않았다.

[특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률 제21857호·시행 2026-10-02의 고객확인 불가 시 거절/종료와 별도 보고 검토를 확인했다. 이 목록은 운영 통제이며 법정 종료나 STR 제출을 이행한 증명이 아니다. [PostgreSQL 16 LIMIT](https://www.postgresql.org/docs/16/queries-limit.html)의 정해진 순서 요구를 적용해 시각과 UUID로 제한 순회한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 현재 APPROVER의 원문 없는 전사 점검 | 담당자 부재를 발견해 후속 조치를 준비한다. | 업무 존재·고객/담당자 내부 ID가 기존 배정 밖에 노출된다. 사유·서명 해시·개인정보 원문은 주지 않고 상세 권한은 기존 검사로 유지한다. |
| 후보 25개 제한과 필터 뒤 커서 진행 | 과거 전체를 한 번에 읽지 않고 철회된 빈 페이지도 넘어간다. | 정기 전체 재순회 비용이 든다. 현재 뷰 하나의 OFFSET 목록이나 영구 체크포인트는 변경된 과거 항목을 놓칠 수 있어 쓰지 않는다. |
| 감독자만 잠그고 다른 인물은 현재 조회 | 많은 고객을 잠가 주문 접수를 방해하지 않는다. | 전체 항목이 동일 시점의 고정 스냅샷은 아니다. 실제 업무 결정은 고객/담당자 잠금 뒤 다시 검사한다. |

## 구현과 API

GET /api/admin/compliance/exit-supervision. 현재 APPROVER 역할과 최근 5분 추가 인증을 필터·서비스에서 재확인한다. afterDecision·afterReview는 함께 주거나 모두 생략한다. 미래·음수·불완전 커서는 거부한다.

항목은 원래 승인 ID·고객 ID·승인 시각·원래 두 담당자 ID와 역사 열람/철회 요청 자격 여부뿐이다. requesterUnavailable은 원래 참여자 중 현재 OPERATOR로 철회를 요청할 인물이 없다는 뜻이다. 이 값으로 원문 권한이나 인계를 부여하지 않는다. 고객 비활성화·새 신고는 남은 종료 개시를 숨기지 않는다.

새 테이블·승인 종류·외부 의존성은 없다. 기존 ExitRescissionIntegrationTest의 실제 준비 절차를 ExitTestSupport로 추출해 새 검증에서 재사용했다. 기존 50개 철회 회귀를 함께 재실행한다. 제품 추상화나 과거 기록 변경이 아니다.

## 실제 코드와 문법

### 순서가 정해진 제한 조회

[ExitSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitSupervisionService.java#L42)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var candidates=after==null?jdbc.queryForList("SELECT review_id,user_id,decided_at FROM relationship_exit_decisions WHERE approved ORDER BY decided_at,review_id LIMIT 25"):
    jdbc.queryForList("SELECT review_id,user_id,decided_at FROM relationship_exit_decisions WHERE approved AND (decided_at,review_id)>(?,?) ORDER BY decided_at,review_id LIMIT 25",Timestamp.from(after.decidedAt()),after.reviewId());
```

ORDER BY 시각, UUID와 같은 두 값을 커서로 비교한다. LIMIT 25는 한 번에 읽는 승인 후보를 제한한다. OFFSET으로 큰 앞부분을 반복 건너뛰지 않는다. 후속 철회로 현재 결과가 달라지므로 영구 소비 위치로 쓰지 않고 전체를 다시 순회한다.

### 빈 페이지에서도 다음 위치 보존

[ExitSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitSupervisionService.java#L53)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var last=candidates.isEmpty()?null:candidates.getLast();
Cursor next=candidates.size()==25?new Cursor(((Timestamp)last.get("decided_at")).toInstant(),(UUID)last.get("review_id")):null;
audit.record(actor,"EXIT_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
return new Page(items,next,candidates.size(),observed,SEMANTICS);
```

후보 25개가 전부 철회돼 items가 비어도 마지막 검사 위치로 진행한다. Java 삼항 연산자는 TypeScript와 같은 조건 분기다. record와 List.copyOf로 응답 목록의 외부 수정을 막는다. 감사 실패 시 메서드가 목록을 반환하지 않는다.

### 원문 열람과 철회 요청 가능성을 구분

[ExitSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ExitSupervisionService.java#L59)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
private Member member(long id,UUID person) {
    var u=users.findById(id).orElse(null);
    // 최근 MFA 만료는 인물의 업무 자격 상실과 다르다. 실제 열람/철회에서는 세션을 다시 검사한다.
    boolean available=u!=null&&u.isAuthenticationAllowed()&&person.equals(u.getOperatorPersonId())
        &&(u.getRole()==UserRole.OPERATOR||u.getRole()==UserRole.APPROVER);
    return new Member(id,available,available&&u.getRole()==UserRole.OPERATOR);
}
```

원래 인물 UUID, 현재 역할과 사용 가능 여부를 확인한다. APPROVER는 원래 역사 원문을 읽을 수 있어도 현재 OPERATOR가 아니면 철회 요청자가 아니다. 최근 MFA 만료는 재인증할 문제이므로 자격 상실과 섞지 않는다. 실제 업무에서는 세션을 별도로 검사한다.

## 작성한 테스트

[ExitSupervisionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ExitSupervisionIntegrationTest.java). 함수별 시나리오·기대 결과는 아래와 같다. 파라미터 조합까지 실제 XML 사례 수로 집계한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| unrelatedApproverSeesActiveMetadataWithoutPrivateJudgmentOrAuthority | 미배정 승인자가 최소 메타데이터만 읽고 원래 원문/철회 요청 권한을 얻지 못함 |
| pendingRejectedAndRescindedApprovalsAreNotActive | 미승인·반려·철회된 결정은 현재 효과 목록에서 제외, 스캔 수는 보존 |
| noEligibleOriginalRequesterRemainsVisible | 비활성·만료·복구·역할·인물 교체 5개에서도 현재 차단은 보존되고 요청자 부재 표시 |
| oldMfaDoesNotMeanStaffIdentityOrRoleHasDisappeared | 재인증 필요와 인물 자격 상실 구분 |
| newCustomerDeclarationAndDisabledCustomerDoNotHideActiveExit | 새 신고/고객 비활성화로 과거 확정 차단을 숨기지 않음 |
| serviceRechecksSupervisorRoleAndAccess | 감독자 비활성·만료·복구·역할 변경 4개 거부 |
| staleRevokedAndForeignSessionCannotRead | 오래된 MFA·회수·다른 계정 세션 거부 |
| incompleteNegativeAndFutureCursorsAreRejected | 불완전/음수/미래 위치 거부 |
| auditFailureReturnsNoMetadataAndRetryDoesNotChangeHold | 감사 실패는 목록 반환 없음, 재시도와 금융 차단 유지 |
| emptyFilteredPageAdvancesAndLaterActiveExitIsNotLost | 실제 25건 종료·철회 후 빈 페이지를 넘어 26번째 유효 개시 조회 |
| httpRoleBoundaryNoStoreAndIncompleteCursor | 실제 MVC 역할/익명·저장 금지 헤더·입력 경계 |

## 실행 명령과 결과

__RESULTS__

실제 PG·CDD/EDD·원래 종료/철회 보안키 서명·감사·MVC를 사용했다. 26건 페이지 fixture도 실제 독립 승인/철회이며 이력·트리거를 우회하지 않는다. Didit 세션 생성·엔진 HTTP·Kafka 등 공통 외부 연계는 대역이다. 운영 배포·실물 보안키·외부 기관 검증과 구분한다.

## 남은 한계

현재 인물 자격은 조회 시점의 참고 정보다. 현재 CDD/근거·요청 만료·새 서명 가능 여부 전체를 계산하지 않는다. 원래 담당자 부재 시 인계/고립 복구·법정 기한/알림·실제 최종 관계 종료/자산 반환·고객 요청 업무는 후속이다. 원문/인계·철회·STR·금융 상태를 목록 조회로 변경하지 않는다. 엔진 단일 스레드 코어·출력 워커와 프론트는 변경하지 않았다. 기존 DB·서버 재시작/배포 없이 봇 0명으로 검증했다.

## 관련 커밋과 증적

__COMMITS__

[035 확정 증적](../reference/exit-supervision-verification-2026-10-24.json), [선행 034](034-exit-rescission.md), [공통 진행 기록](../../../docs/roadmap/edd-case-review.md).
