# 보고 후속 업무를 원문 없이 전사 점검하기

현재 담당자가 살아 있어도 업무를 놓칠 수 있다. 개인별 제출 대기와 고립 복구 목록 위에 승인 역할 전용의 최소 메타데이터 조회를 추가한다. 개발자와 준법 업무 담당자가 권한·내부 목표·실제 코드·테스트를 설명하도록 작성했다.

## 문제와 기존 동작

[030](030-reporting-queue-and-followup.md)의 대기는 현재 담당자만, [031](031-reporting-custody-and-orphan-recovery.md)의 고립 목록은 두 담당자 모두 권한이 없어진 건만 제공했다. 두 사람의 권한이 정상인 미처리 업무와 한 사람만 권한이 없는 업무를 담당자 외의 승인 인물이 전체적으로 점검할 경로가 없었다.

이번 API는 실제 승인된 REPORT_REQUIRED의 현재 후속 업무만 점검한다. 내부 보고 검토 중·반려·만료·미보고 판단·아직 보고 검토를 시작하지 않은 회부 전체를 다루는 전사 사건 시스템은 아니다. 실제 FIU 제출이나 법정 기한 달력도 추가하지 않는다.

## 기존 제품과 공식 근거 대조

확인일은 2026-10-24다. 원문은 복사하지 않고 이번 선택과 연결되는 범위만 정리한다.

| 자료 | 확인한 내용과 적용 경계 |
| --- | --- |
| [Camunda 8.9 업무 생명주기](https://docs.camunda.io/docs/8.9/apis-tools/frontend-development/task-applications/user-task-lifecycle/) | 담당 배정과 업무 상태를 분리하는 공개 패턴을 따른다. 배정이 정상이라는 이유만으로 미처리 업무를 목록에서 빼지 않는다. 새 워크플로 엔진을 설치하지 않는다. |
| [Camunda 8.10 업무 권한](https://docs.camunda.io/docs/components/tasklist/user-task-authorization/) | 프로세스 범위 권한과 개별 업무 권한, 검색/조회와 변경 권한을 구분한다. 이 프로젝트는 별도의 메타데이터 감독 API만 넓히고 원문·기록·인계의 기존 개별 인가를 유지한다. Camunda의 광역 권한 우선 규칙을 그대로 이식하지 않는다. |
| [특정금융정보법 제4조·제5조](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=290725) | 법률 제21857호·시행 2026-10-02의 실제 본문을 다시 읽었다. 보고와 비밀 보호·내부 AML 목적 제공 예외·내부 보고 체제를 구분한다. 웹 도구의 본문 조회 실패 후 같은 공식 서버의 본문 응답을 직접 확인했다. |
| [자금세탁방지 업무규정](https://law.go.kr/admRulLsInfoP.do?admRulId=2037208&efYd=0) | 고시 제2025-2호·시행 2026-01-22, 실제 본문 식별자 2100000267264의 제5조·제5조의2·제6조를 대조했다. 조직의 감독·점검·보고체계와 법령상 준법감시인 의무 유무의 조건을 확인했다. APPROVER가 법정 보고책임자나 준법감시인이라는 뜻은 아니다. 경력·직위 요건 전체와 부칙별 적용을 이번 코드로 충족했다고 주장하지 않는다. |
| [삼성증권 공개 AML 자료](https://www.samsungsecurities.co.kr/download/kor/pdf/control_file05.pdf) | 고객확인·거래 모니터링/STR·내부통제와 시나리오 점검을 구분하는 공개 사례를 재확인했다. 2025년까지의 계획 표시는 현행 이행 완료의 증명이 아니다. |
| [KONA PLATE 공개 가이드](https://www.konaplate.com/platforms/onboardingguide/guidetab2/compliance03) | 내부 검토·보고책임자 승인·외부 제출을 구분하는 전자금융업 사례다. 거래소에 대한 적용 의무나 내부 DB 구조를 이 자료에서 추측하지 않는다. |
| [빗썸 공개 공지](https://feed.bithumb.com/notice/1645245) | 2024-11-21 작성·2026-01-20 갱신을 다시 확인했다. 본문에서 직접 확인한 것은 시장감시 조직·이상거래 분석 공개다. 이 공지 자체를 AML 후속 점검 API의 구현 증거로 사용하지 않는다. [업비트 자료](https://upbitcare.com/academy/education/common/76)는 이번에도 403이므로 본문 확인 완료로 표시하지 않는다. |

국내 의무를 구현하는 데 필요한 조직·업무 분장·법정 책임자 선임·실제 제출과 운영 증적은 별도다. 24시간·25개 후보·5분 MFA·APPROVER 전용은 자체 기술 정책이며 법이 이 API나 숫자를 직접 요구한다는 뜻은 아니다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 감수하는 비용·한계 |
| --- | --- | --- |
| APPROVER에 원문 없는 전사 목록 제공 | 정상 배정 건과 한 사람만 부재한 건도 발견할 수 있다. | 업무 존재·내부 고객/담당자 ID·관찰 시각의 열람 범위가 넓어진다. 모든 승인 역할이 전담 준법 직무인 것은 아니므로 실제 조직 배치·권한 승인·정기 검토가 필요하다. |
| 기존 검증된 보고·현재 배정·관찰 메타데이터 재사용 | 원래 승인과 무관한 추정 업무를 만들지 않고 새 테이블·승인 종류를 피한다. | 후보별 복호화와 DB 조회 비용이 든다. DB 계정·키 격리는 앱 인가만으로 해결되지 않는다. |
| 한 번에 승인 후보 25개, 원래 승인 순서 | 전체 집계·무제한 원문 해독 없이 과거 확정 의무를 놓치지 않고 순회한다. | 실제 결과는 25개보다 적거나 비어 있을 수 있다. 전체 건수·초과 우선 정렬·영구 소비 위치를 제공하지 않는다. 반복 조회 비용과 운영자의 페이지 탐색 부담이 있다. |
| 담당자·고객을 일괄 잠그지 않는 참고 조회 | 감독을 위해 금융 업무와 인계 전체를 직렬화하지 않는다. | 여러 SQL 사이에 인계·관찰·권한이 변할 수 있다. 전체 시점 고정 스냅샷이 아니며 실제 변경 API가 잠금 뒤 다시 인가한다. |
| 외부 참조를 받아도 항상 FOLLOW_UP_REQUIRED | 검증되지 않은 직원 관찰로 업무를 숨기거나 완료 처리하지 않는다. | 실제 접수 진위 검증·제출/보정과 담당자의 반복 점검이 완성되기 전에는 목록이 계속 남는다. |

광역 원문 권한, 새 감독 역할/권한 프레임워크, 별도 상태 투영 표, 자동 완료·읽음 처리 대신 현재 Spring 트랜잭션·JPA 사용자 잠금·세션 정책·불변 근거·감사를 재사용했다. 새 의존성은 없다. 목록은 기존 인계 요청의 자격·독립 서명이나 고객의 거래 제한을 바꾸지 않는다.

## API와 데이터 흐름

`GET /api/admin/compliance/reporting-supervision?afterDecision=...&afterReview=...`

- 최근 MFA가 유효한 현재 APPROVER만 필터와 서비스에서 허용한다. 서비스는 행 잠금 후 역할·활성·기한·복구 상태와 해당 세션을 다시 확인한다.
- 원래 실제 승인된 후보를 최대 25개 검사하고 암호화된 결론과 무결성을 확인한다. 실제 보고 필요만 반환한다. 현재 고객 비활성화·신고 변경·새 평가로 과거 필요를 지우지 않는다.
- 응답은 검토 ID·내부 고객 ID·원래 승인 시각·24시간 목표와 초과 여부·현재 인계 세대·담당자 ID/권한 사용 가능 여부·두 사람 모두의 권한 상실 여부·최근 관찰 **저장 시각**이다.
- 보고 사유·원래 판단 원문·원문 지문·인물 UUID·외부 결과 방향·외부 참조·메모·관찰 작성자는 반환하지 않는다. 업무 존재 자체가 기밀 메타데이터라는 점은 남는다.
- 한 담당자만 사용 불가하면 members의 한 값만 false, orphaned는 false다. 계정과 인물이 같고 현재 OPERATOR/APPROVER로 업무가 가능한지를 본다. 담당자의 최근 MFA 부족만으로 퇴사/권한 상실을 추정하지 않는다.
- observedAt은 페이지 검사 시작 시각이며 내부 목표 비교에 공통으로 쓴다. 전체 데이터의 원자적 스냅샷 시각이라는 의미가 아니다. 각 페이지마다 현재 상태를 다시 읽는다.
- items가 비어도 next가 있으면 진행한다. 정확히 25개면 실제 다음 항목이 없더라도 한 번 더 빈 페이지를 읽을 수 있다. 새 탐색은 처음부터 갱신한다.
- 원래 보고 승인 시각을 정렬과 점검 목표의 기준으로 유지한다. 인계와 관찰의 반복으로 목표를 연장하지 않는다. 주말·공휴일 달력이나 법정 기산점을 계산하지 않는다.
- 성공 감사가 저장되지 않으면 목록을 반환하지 않는다. HTTP Cache-Control: no-store와 기존 거부 감사도 유지한다. 이 API에는 상태 변경 메서드가 없다.

## 실제 코드와 이해에 필요한 문법

모두 2026-10-24 실제 소스의 부분 발췌이며 주입 객체·클래스·트랜잭션을 생략한 부분은 독립 실행 예제가 아니다.

### 기존 목록과 감독 목록의 같은 내부 목표

출처: [ReportingFollowupService.java](../../src/main/java/dustin/cex/domains/edd/ReportingFollowupService.java#L47)

```java
public record InternalCheck(Instant targetAt,boolean overdue) {
    public static InternalCheck at(Instant decision,Instant observed) {
        Instant target=decision.plusSeconds(86400);
        return new InternalCheck(target,!target.isAfter(observed));
    }
}
```

record는 값 객체다. Instant의 비교는 절대 시각으로 이뤄진다. target이 observed보다 늦지 않으면 정각부터 초과로 본다. 86,400초를 더하는 계산이므로 영업일 계산이 아니다. 기존 담당자용 목록도 페이지 시작의 같은 observed 값과 이 계산을 사용한다.

### 필터를 통과한 뒤의 현재 업무 권한

출처: [ReportingSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ReportingSupervisionService.java#L54)

```java
var supervisor=users.lockById(actor).orElseThrow(()->denied(HttpStatus.FORBIDDEN));
sessions.requireFresh(supervisor,sid,UserRole.APPROVER);
Instant observed=Instant.now();
if(after!=null&&(after.decidedAt()==null||after.reviewId()==null
        ||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(observed))) {
    throw denied(HttpStatus.BAD_REQUEST);
}
```

var는 Java의 컴파일 시 타입 추론이며 TypeScript의 any가 아니다. 사용자 행을 잠근 뒤 현재 상태와 해당 세션을 검사한다. 이 역할 검사는 코드의 감독 목적 정책이며 실무 직책의 법적 자격 증명이 아니다.

### 후보 검사와 응답 투영

출처: [ReportingSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ReportingSupervisionService.java#L63)

```java
var candidates=reviews.custodyCandidates(after==null?null:after.decidedAt(),after==null?null:after.reviewId());
var items=new ArrayList<Item>();
for(UUID id:candidates) {
    var origin=reviews.custodyCandidate(id);
    if(origin==null)continue;
    var state=custody.current(id);
    var members=state.members().stream().map(member->new Member(member.id(),
        custody.available(member,users.findById(member.id()).orElse(null)))).toList();
    var check=ReportingFollowupService.InternalCheck.at(origin.decidedAt(),observed);
    // 기존 메타데이터 무결성 검사를 재사용하되 원문·결과 방향·작성자·외부 참조는 응답에 넣지 않는다.
    var latest=followups.latest(origin);
    items.add(new Item(id,origin.payload().userId(),origin.decidedAt(),check.targetAt(),check.overdue(),
        state.generation(),members,members.stream().noneMatch(Member::available),
        latest==null?null:latest.recordedAt(),"FOLLOW_UP_REQUIRED",ReportingFollowupService.DEADLINE));
}
```

continue는 실제 미보고 판단을 결과에서 제외한다. stream().map(...).toList()는 회원 ID와 사용 가능 여부만 담는 별도 값 목록을 만든다. TypeScript의 Array.map과 비슷하지만 Java 타입과 트랜잭션 안에서 실행되며 await로 병렬 호출하는 코드가 아니다. 기존 내부 사실을 읽어 판단하더라도 그대로 JSON 응답에 포함하지 않는다. latest는 기존 관찰의 메타데이터 해시와 원래 보고 근거 연결을 검사하며 암호화된 외부 원문을 인증/검증했다는 뜻은 아니다.

### 감사 확정 뒤 반환

출처: [ReportingSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/ReportingSupervisionService.java#L81)

```java
audit.record(actor,"REPORTING_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
return new Page(items,next,candidates.size(),observed,SEMANTICS);
```

tx.execute의 콜백에서 감사 예외가 나면 트랜잭션이 롤백되고 Page가 호출자에게 반환되지 않는다. 새 담당자·완료 상태를 쓰는 SQL은 없다. 현재 감사는 목록 API를 누가 어떤 목적으로 성공 호출했는지를 남긴다. 개별 반환 항목 ID·그때의 전체 응답을 감사에 복제하는 증적이나 사람이 실제로 읽었다는 증명은 아니다.

### 경로별 권한과 정확한 시간 경계 테스트

출처: [SecurityConfiguration.java](../../src/main/java/dustin/cex/domains/security/SecurityConfiguration.java#L66)

```java
.requestMatchers(HttpMethod.GET,"/api/admin/compliance/reporting-supervision").access(audited(audit,sessions,"APPROVER"))
```

광역 원문 경로를 여는 와일드카드를 추가하지 않고 새 GET 하나에만 APPROVER를 허용한다. 서비스에서도 같은 제한을 반복한다.

출처: [ReportingInternalCheckTest.java](../../src/test/java/dustin/cex/domains/edd/ReportingInternalCheckTest.java#L12)

```java
Instant decision=Instant.parse("2027-12-31T15:00:00Z");
Instant target=Instant.parse("2028-01-01T15:00:00Z");
var check=ReportingFollowupService.InternalCheck.at(decision,target.plusNanos(nanos));
assertEquals(target,check.targetAt());
assertEquals(nanos>=0,check.overdue());
```

세 실행은 목표 1ns 전·정각·1ns 후다. 미래의 달력 날짜는 테스트 입력일 뿐 법령의 미래 적용을 주장하는 자료가 아니다. 주말/연도 경계에도 같은 경과시간 정책을 유지한다.

## 작성한 테스트

통합 파일은 [ReportingSupervisionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ReportingSupervisionIntegrationTest.java), 시간 경계는 [ReportingInternalCheckTest.java](../../src/test/java/dustin/cex/domains/edd/ReportingInternalCheckTest.java)다. 실제 신규 함수와 시나리오는 아래와 같다.

| 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `unrelatedApproverSeesHealthyAssignedDutyButGetsNoOriginalContent` | 별도 APPROVER가 정상 배정된 확정 업무의 최소 정보·원래 목표를 보고, 원래/현재 담당자용 원문은 거절되며 거래 차단을 유지한다. |
| `metadataCannotSeizeHealthyWorkOrWriteFollowup` | 목록을 읽어 사건 ID를 알아도 정상 배정 인계 문맥과 후속 기록 권한을 얻지 못한다. |
| `pendingRejectedAndNoReportDoNotBecomeReportingDuty` | 미승인·실제 반려·승인된 미보고 결론은 보고 필요 목록에 들어가지 않는다. |
| `everyStaffObservationKeepsDutyAndNeverLeaksOutcomeOrReference` | 외부 관찰 3종 모두 저장 시각만 노출하고 업무를 유지한다. 원문/방향/지문/외부 참조·작성자 인물은 제외하고 상세 열람은 거절한다. |
| `bothUnavailablePeopleAreVisibleWithoutDroppingDuty` | 비활성·권한 만료·복구 대기·역할 변경·인물 변경의 5종에서 두 담당자 권한 공백을 표시하되 업무를 숨기지 않는다. |
| `oneUnavailablePersonIsVisibleButNotOrphaned` | 한 사람만 사용 불가이면 개별 상태만 표시하고 고립이라고 판단하지 않는다. |
| `staleCustodianMfaDoesNotPretendAuthorityIsLost` | 담당자의 최근 MFA 부족을 계정 권한 상실로 해석하지 않는다. |
| `handoffUpdatesCurrentPeopleButPreservesOriginalTargetAndFinancialHold` | 실제 별도 인물의 STR_CUSTODY 서명 승인 뒤 새 두 사람/세대로 갱신하고 원래 승인/목표/거래 차단을 보존한다. |
| `newDeclarationAndDisabledCustomerDoNotHidePastReportingDuty` | 고객이 재신고하고 비활성화되어도 이미 확정된 필요를 숨기지 않는다. |
| `emptyFilteredPageStillAdvancesThenFreshScanFindsLaterApproval` | 실제 서로 다른 고객의 미보고 승인 25건을 빈 페이지로 검사한 뒤 next로 나중 확정 업무를 찾는다. 불변 기록 삭제·한도 우회는 없다. |
| `serviceRechecksSupervisorAuthorityAfterAuthentication` | 인증 뒤 비활성·기한 만료·복구 대기·역할 변경의 4종을 직접 서비스에서도 거절한다. |
| `staleRevokedAndForeignSessionCannotReadSupervision` | 오래된 MFA·회수 세션·다른 사람 세션을 거절한다. |
| `failedAuditReturnsNoListAndSuccessAuditContainsNoReportText` | 감사 실패는 목록 반환 실패로 전파하고 재시도 시 정상 반환한다. 성공 감사에 보고 사유를 복사하지 않는다. |
| `malformedAndFutureCursorAreRejected` | 짝이 없는 위치·시대 전 시각·미래 시각은 거절한다. |
| `httpApproverGetsNoStoreMetadataWhileOtherRolesAndAnonymousAreDenied` | 실제 HTTP에서 별도 승인자의 원문 없는 no-store 응답, OPERATOR/AUDITOR/CUSTOMER 403·익명 401·불완전 위치 400을 검증한다. |
| `ReportingInternalCheckTest.targetBoundaryUsesExactElapsedDayInsteadOfBusinessCalendar` | 목표 1ns 전·정각·1ns 후 3종으로 초과 경계와 연도/주말에도 같은 24시간 정책을 검증한다. |

## 실행 명령과 결과

실행 환경은 macOS·Temurin JDK 21·격리 PostgreSQL 16이다. 봇 0명·소비자 자동 시작 금지와 격리 외부 주소를 사용하는 기존 실행기를 그대로 쓴다. 각 실행 소유 자원만 정리하고 기존 로컬 DB·Kafka·서버는 변경하지 않는다.

```sh
caffeinate -dims python3 scripts/verify_edd.py
python3 scripts/verify_kyc.py
python3 scripts/verify_kyc_recovery.py
python3 scripts/verify_bound_approvals.py
python3 scripts/verify_operator_mfa.py
python3 scripts/verify_engine_inbox.py
./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain
python3 scripts/verify_operator_admin.py
python3 scripts/verify_order_idempotency.py
python3 scripts/verify_market_path.py
python3 scripts/audit_dependencies.py
python3 scripts/verify_dependency_integrity.py
```

최종 선택 JUnit **1,147개**, 실패·오류·제외 0개다. EDD/보고/인계/감독 581개(신규 감독 통합 24 + 시간 경계 3), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했다. 실행 소유 컨테이너/임시 DB/체크섬 표본은 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성과 222개 버전의 조회 시점 알려진 OSV 검출 0개, 변조 체크섬의 예상 Gradle 종료 코드 1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/reporting-supervision-verification-2026-10-24.json)에 실제 명령·개별 사례·실패와 최종 실행의 분리·자원 정리·공식 본문 대조·현재 실제 코드 138개와 별도 6개 대조·남은 한계를 기록했다.

첫 EDD 실행은 새 테스트 complete 보조 함수에 소프트웨어 보안키 서명이 던지는 checked exception의 throws 선언이 없어 compileTestJava에서 실패했다. 제품 테스트 실행 전의 컴파일 실패이며 테스트 통과로 집계하지 않는다. 선언을 보완한 뒤의 최종 실행과 분리했다. deprecated/unchecked 컴파일 경고와 테스트 JVM의 클래스 공유 경고도 남아 있다.

## 남은 범위와 검증 한계

- 전사라는 말은 실제 승인된 보고 필요의 범위다. 미판단 회부·미승인/만료 검토·조사 대기·법정 기한 알림·전사 운영 알림/당직·상향 보고 이력 전체는 미완료다.
- 실제 FIU 전송·접수 검증·보고 양식·법정 기산점/영업일·보정/긴급 제출·관계 거절/종료·잔여 자산 처리는 별도다. 이 목록과 최근 관찰이 법정 의무 충족을 보증하지 않는다.
- 부재 표시와 현재 배정은 참고 조회다. 인계의 독립 승인·현재 상태 재검사를 건너뛰지 못한다. 광역 APPROVER의 메타데이터 목적 권한과 별도의 전담 준법 직무·조직 분장·법정 책임자 선임은 구분한다.
- 애플리케이션 인가·불변 표/감사는 DB 슈퍼유저 침해를 막지 않는다. 키 관리·독립 계정·보존/파기·실제 감독 운영 증적은 별도다.
- 기존 Didit HTTP 대역·실제 HMAC·소프트웨어 WebAuthn·격리 DB를 사용한 검증이다. 실제 고객·기관·실물 보안키·법령/신고/인증·운영 배포·처리량 측정 완료가 아니다.
- 조회 감사는 호출자/목적 단위이며 개별 반환 항목·응답 스냅샷 증적은 아니다. 후보 상한은 반복 요청 전체의 부하 제어·운영 수용량 검증을 대신하지 않는다.
- 이번 감독 전용 JVM 강제 종료 테스트는 없다. 조회에 새 영속 상태가 없다는 점과 기존 프로세스 회귀 검증 범위를 구분한다.
- 엔진 코어·프론트 변경, 기존 DB/저널 변환·서버 재시작·운영 배포는 하지 않았다. 봇은 0명이다.

## 관련 커밋과 참고 자료

- [`9083508`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9083508b28e3c586ecae09d9b61c8278d225dc38): 원문 없는 전사 점검·공통 내부 목표·새 감독 통합 24개와 시간 경계 3개.

[030](030-reporting-queue-and-followup.md)·[031](031-reporting-custody-and-orphan-recovery.md)의 원문 권한·실제 인계와 이 문서의 감독 목적을 함께 읽는다. 실제 법령 본문 확인/해시와 조회 실패는 확정 증적에서 구분한다.
