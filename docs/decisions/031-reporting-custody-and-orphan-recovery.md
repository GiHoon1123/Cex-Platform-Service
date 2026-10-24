# 권한이 사라진 보고 업무를 독립 승인으로 인계하기

보고 필요를 승인한 두 사람이 모두 퇴사·권한 만료·복구 대기 상태가 되면, 기존 지정 인물 전용 API로는 그 업무를 이어갈 수 없었다. 원래 보고 판단과 시각은 보존하고, 고립 건의 최소 메타데이터 발견·독립 업무 서명·현재 후속 담당자 교체를 구현한다. 개발자와 운영 업무 담당자가 권한 흐름·실제 코드·테스트를 설명할 수 있도록 남기는 기록이다.

## 문제와 기존 동작

[030](030-reporting-queue-and-followup.md)은 현재 고객 상태와 별개로 과거 보고 필요를 조회했지만 원래 두 계정/인물만 후속 업무를 처리했다. 정상 교대도, 두 담당자의 동시 권한 회수도 새로운 담당자를 지정하는 경로가 없었다. 일반 운영자 모두에게 원문을 여는 해결책은 노출 범위를 넓히고, 원래 보고 요청의 작성자 필드를 고치면 당시 판단의 추적성이 훼손된다.

이번 범위는 실제 승인된 REPORT_REQUIRED의 **후속 업무 인계**다. 실제 보고 필요/미보고 판단·법정 보고책임자 임명·금융 거래 제한·외부 제출/접수·법정 기한을 바꾸지 않는다. 초기 배정, 승인된 인계, 현재 후속 업무 권한을 하나의 상태 흐름으로 관리한다.

## 공식 자료와 검증된 패턴

확인일은 2026-10-24다.

| 근거 | 확인과 적용 |
| --- | --- |
| [Camunda 8.9 업무 생명주기](https://docs.camunda.io/docs/8.9/apis-tools/frontend-development/task-applications/user-task-lifecycle/) | 배정은 업무 완료 상태와 별개로 바뀌고, 권한 있는 사람만 재배정한다. 인계를 보고 완료로 취급하지 않는 원칙을 채택했다. Camunda 엔진이나 새 워크플로 프레임워크를 설치하지 않는다. |
| [Camunda 8.10 업무 권한](https://docs.camunda.io/docs/components/tasklist/user-task-authorization/) | 전체 프로세스 권한과 담당자 속성에 따른 개별 업무 권한을 구분한다. 이 프로젝트는 일반 역할만으로 모든 보고 원문을 읽게 하지 않고 실제 담당 인물도 검사한다. |
| [특정금융정보법 제4조·제5조](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=290725) | 현행 조회 식별자 290725, 법률 제21857호·시행 2026-10-02의 실제 본문을 재대조했다. 보고 의무·비밀 보호와 내부 AML 목적 제공 예외, 보고 담당자 임명/내부 보고 체제를 구분했다. 이 인계가 법정 보고책임자의 임면이나 그 기관 등록을 대체하지 않는다. |
| [FCA AML 안내](https://www.fca.org.uk/firms/financial-crime/money-laundering-terrorist-financing) | 조직의 책임 있는 AML 업무 담당과 위험에 맞는 내부 통제의 해외 참고다. 영국 보고 의무·책임자 제도를 국내 법적 요건으로 적용하지 않는다. |

[029의 거래소·증권사 공개 자료 대조](029-confidential-reporting-review.md#공식-근거와-기존-제품의-접근)와 [030의 제출/접수·법정 기한 확인 범위](030-reporting-queue-and-followup.md#공식-자료와-기존-제품-접근)를 이어 사용한다. 공개 자료로 다른 거래소의 내부 인계 테이블·승인 인원을 알 수 있다고 주장하지 않는다. 초기 Camunda 구 경로와 KoFIU 안내 조회의 실패/시간 초과가 있었고, Camunda는 정상 조회된 버전 경로·권한 문서를, 국내 의무는 실제 법령 본문을 확인했다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 원래 보고 판단 대신 별도 인계 이력 추가 | 작성자·원래 서명·보고 필요·내부 점검 기준 시각을 보존한다. | 인계 요청/제공/결정 세 표와 현재 상태를 계산하는 뷰가 생긴다. 조회·보존·정합성 검증 비용이 늘어난다. |
| 기존 현재 담당자 또는 두 담당자 모두 권한이 없는 경우에만 요청 | 일반 운영자의 정상 사건 탈취를 막고 고립 건 복구는 가능하게 한다. | 한 사람만 권한이 없으면 살아 있는 담당자가 요청해야 한다. 신선한 MFA가 없다는 사정만으로 고립 판정하지 않는다. |
| 새 주담당 OPERATOR·확인 담당 APPROVER와 별도의 독립 승인자 | 특정 계정/인물에게만 후속 원문을 제공하고 자기 권한 부여 승인을 막는다. | 최소 세 인물, 정상 교대 구성에 따라 네 인물이 필요할 수 있다. 역할·인물/버전·보안키·교대 운영 부담이 늘어난다. |
| 논리 세대와 직전 근거 해시로 현재 배정 계산 | 벽시계 순서나 최초 작성자 변경 없이 인계 순서를 추적한다. 같은 세대의 승인은 하나다. | 사용자/고객 행 잠금과 세대 재검사가 필요하다. 플랫폼의 같은 고객 업무가 잠시 기다릴 수 있다. |
| 고립 목록은 운영 역할의 최소 메타데이터로 제한 | 두 원래 담당자에게 연락/로그인 권한이 없어도 발견·복구를 시작할 수 있다. | 업무 존재·내부 고객/담당자 ID의 노출 범위가 넓어진다. 전담 준법 역할·조직 배정/감독의 실제 운영 정책은 별도다. |

10분 요청·5분 본인 제공·하루 10회 요청·현재 두 담당자와 독립 승인이라는 조합은 자체 기술 정책이다. 법이 정확히 이 인원·기한을 요구한다는 뜻은 아니다. 설치된 Spring 트랜잭션·JPA @Version·JdbcTemplate·기존 AES-GCM/HMAC·WebAuthn과 승인 결합을 재사용했다. 새 라이브러리·메시지 시스템·설정 프레임워크를 도입하지 않았다.

## 인계 흐름과 권한 범위

1. 대상은 원래 실제 독립 승인된 REPORT_REQUIRED다. 현재 고객 신고·현재 EDD 상태가 바뀌어도 이미 확정된 필요는 유지한다.
2. 인계가 없으면 실제 원래 두 계정/인물의 배정이 세대 0이다. 이후 승인된 인계의 가장 큰 논리 세대가 현재 배정이다. 과거 프로그램을 실행하는 폴백이나 원래 기록을 채워 넣는 마이그레이션이 아니다.
3. 현재 담당자는 OPERATOR 또는 APPROVER 역할로 인계를 요청할 수 있다. 지정되지 않은 사람은 두 현재 담당자가 모두 업무 권한이 없을 때만 복구를 요청한다.
4. 고립 여부는 계정 사용 가능·운영 역할·같은 인물 UUID·권한 기한·복구 대기 상태로 판단한다. 일시적인 최근 MFA 부족이나 한 사람의 부재는 두 사람 모두의 권한 상실로 취급하지 않는다.
5. 새 두 담당자는 현재 유효한 OPERATOR와 APPROVER이며 서로 다른 인물이어야 한다. 요청 당시 계정 버전·인물과 목표를 불변 payload에 고정하고 사유는 암호화한다.
6. 독립 APPROVER는 요청자 및 새 두 담당자와 다른 인물이어야 한다. 같은 사람의 다른 계정도 거절한다. 원래 담당자 중 승인 역할이 남아 있으면 새 수혜자가 아닌 경우 독립 승인을 맡을 수 있다.
7. 승인자는 인계 대상·사유를 본인 세션으로 제공받고 업무별 STR_CUSTODY 서명을 한다. 이 검사에서 보고 원문과 외부 참조는 제공하지 않는다.
8. 실제 결정에서 현재 배정 해시·세대·요청자 자격·고립 상태·대상 버전/역할·본인 제공·서명을 재검사한다. 계정이 복원되어 고립 조건이 사라지면 비담당자의 옛 복구 요청도 거절한다.
9. 결정·증명 소비·공개키 카운터·감사가 같은 트랜잭션에 확정된다. 승인만 세대를 올리고, 반려는 기존 권한을 유지한다. 같은 세대의 승인에는 DB 고유 제약도 있다.
10. 새 현재 담당자는 기존 제출 대기·관찰 이력과 현재 담당자용 원래 근거 API를 사용한다. 원래 보고 판단을 다시 승인하거나 거래를 허용할 권한은 인계되지 않는다.

고립 목록은 승인 후보 25개를 검사한다. 미보고·정상 배정 후보를 제외해 items가 비어도 next가 있으면 계속 진행한다. 페이지는 영구 소비 체크포인트가 아니며 다음 탐색은 처음부터 갱신한다. 미승인/만료된 보고 검토·확정 보고 필요가 생성되지 않은 회부·단 한 명만 이용 불가인 사건을 모두 다루는 전사 SLA/경보 시스템은 아니다.

## 원래 이력과 현재 후속 권한의 구분

- 기존 비공개 내부 보고 요청의 원래 작성자/승인자·해시·결정·제공 이력은 수정하지 않는다. 그 API의 역사 열람은 029의 원래 참여자 규칙을 유지한다.
- 제출 대기·새 관찰 작성·관찰 상세/이력·새 현재 담당자용 원래 근거 제공은 현재 승인된 후속 배정을 사용한다. 인계된 뒤 빠진 사람은 이 후속 업무에 접근하지 못한다.
- 새 담당자가 원래 내부 보고 결정을 변경/재승인할 수는 없다. 원래 근거는 별도 목적의 감사된 custody-context API로 제공한다.
- 인계 요청자·실제 결정자·현재 담당자는 원문 없는 인계 결과를 재조회할 수 있다. 승인 응답이 유실돼도 후속 권한을 되찾거나 서명을 다시 소비하지 않고 확정 상태를 확인한다.
- 현재 담당자는 과거 인계 목록·사유를 읽는다. 역사 열람은 승인용 제공 영수증을 생성하지 않는다.

## 실제 코드와 이해에 필요한 문법

모두 2026-10-24 현재 소스의 부분 발췌다. 주입 객체·클래스와 트랜잭션이 필요한 코드이며 독립 실행 예제가 아니다.

### 원래 배정과 승인된 논리 세대

출처: [ReportingCustody.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustody.java#L28)

```java
if(latest.isEmpty())return new State(review,p.userId(),0,(String)source.get("payload_hash"),List.of(new Member(p.requesterId(),p.requesterPerson()),new Member(p.checkerId(),p.checkerPerson())));
var row=latest.getFirst();String payload=(String)row.get("payload");var handoff=json.readValue(payload,ReportingCustodyService.Payload.class);
if(!verifier.digest(json.readTree(payload)).equals(row.get("payload_hash"))||!ReportingCustodyService.POLICY.equals(handoff.policy())||!review.equals(handoff.reviewId())
    ||!handoff.id().equals(row.get("id"))||handoff.primary().id()!=((Number)row.get("primary_id")).longValue()||handoff.checker().id()!=((Number)row.get("checker_id")).longValue()||handoff.userId()!=p.userId()||!handoff.reviewHash().equals(source.get("payload_hash"))||handoff.generation()!=((Number)row.get("generation")).longValue())throw new IllegalStateException("인계 근거 검증 실패");
return new State(review,p.userId(),handoff.generation(),(String)row.get("payload_hash"),List.of(new Member(handoff.primary().id(),handoff.primary().person()),new Member(handoff.checker().id(),handoff.checker().person())));
```

최초 상태는 원래 불변 payload의 두 인물이다. 이후에는 가장 큰 승인 세대의 payload 해시·대상·원래 보고 해시를 확인한다. State의 목록은 List.copyOf로 런타임 변경을 막는다. TypeScript의 readonly 타입 선언만으로 런타임 배열이 불변이 되는 것과는 다르다. 현재 뷰 reporting_current_custody는 같은 상태 규칙을 SQL 목록 권한 검사에도 사용한다.

### 잠금 순서와 직전 상태 재검사

출처: [ReportingCustody.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustody.java#L35)

```java
public Map<Long,User> lock(State expected,long... extra) {
    transaction();var ids=new TreeSet<Long>();ids.add(expected.userId());expected.members().forEach(m->ids.add(m.id()));for(long id:extra)ids.add(id);
    var result=new HashMap<Long,User>();ids.forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));
    if(!expected.stamp().equals(current(expected.reviewId()).stamp()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"현재 인계 상태가 바뀌었습니다");return result;
```

TreeSet은 중복 ID를 없애고 오름차순으로 정렬한다. 같은 인계의 여러 경로가 사용자 행을 다른 순서로 잡아 교착하는 위험을 줄인다. 잠금을 기다리는 사이 다른 인계가 승인되면 stamp 비교에서 충돌로 거절한다. 인계·후속 관찰은 플랫폼 사용자/고객 잠금으로 순서가 정해지고 Rust 코어에 공유 락을 넣지 않는다.

### 누가 인계를 시작할 수 있는가

출처: [ReportingCustodyService.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustodyService.java#L145)

```java
private void eligible(User maker,ReportingCustody.State state,Map<Long,User> people) {if(!state.contains(maker)&&!custody.orphaned(state,people))throw denied(HttpStatus.NOT_FOUND);}
```

현재 담당자가 아니면 두 현재 담당자 모두 업무 권한이 없어야 한다. 고립 목록은 발견용이며 실제 요청/결정에서는 행을 잠근 상태로 다시 검사한다. 조회 후 복구된 계정이나 역할 변경을 무시하고 옛 판단으로 진행하지 않는다.

### 요청자·수혜자와 독립된 현재 승인자

출처: [ReportingCustodyService.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustodyService.java#L133)

```java
if(maker==null||!maker.isAuthenticationAllowed()||!Set.of(UserRole.OPERATOR,UserRole.APPROVER).contains(maker.getRole())||maker.getVersion()!=p.requesterVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())
    ||actor==p.requesterId()||approver.getOperatorPersonId().equals(p.requesterPerson())||approver.getOperatorPersonId().equals(p.primary().person())||approver.getOperatorPersonId().equals(p.checker().person()))throw denied(HttpStatus.FORBIDDEN);
eligible(maker,state,people);var first=target(people.get(p.primary().id()),UserRole.OPERATOR);var second=target(people.get(p.checker().id()),UserRole.APPROVER);
if(!first.equals(p.primary())||!second.equals(p.checker())||first.person().equals(second.person()))throw denied(HttpStatus.CONFLICT);
var origin=reviews.custodyOrigin(p.reviewId());
```

Target의 version은 JPA 사용자 행 버전이며 역할·접근 변경 뒤 옛 요청을 무효화한다. 인계 generation은 별도로 해당 보고 업무의 배정 변경을 센다. 두 버전을 혼동하지 않는다. UUID와 primitive long을 값으로 비교해 같은 사람의 다른 계정이나 큰 숫자 ID의 참조 비교 문제를 피한다. 신규 대상은 승인 시점에도 역할·인물·버전이 같아야 한다.

### 승인 결합과 불변 결정

출처: [ReportingCustodyService.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustodyService.java#L98)

```java
var r=read(id);var actorUser=check(actor,sid,r,hash,true);var p=r.payload();mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.STR_CUSTODY,id,hash,approve,proof);
jdbc.update("INSERT INTO reporting_custody_decisions(request_id,review_id,generation,approver_id,approver_person,approved) VALUES(?,?,?,?,?,?)",id,p.reviewId(),p.generation(),actor,actorUser.getOperatorPersonId(),approve);
audit.record(actor,"REPORTING_CUSTODY_DECIDED","custody:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
```

STR_CUSTODY는 인계 요청 ID·전체 해시·승인/반려 방향에 서명을 묶는다. 외부 결과 관찰이나 원래 STR_REVIEW 서명을 재사용하지 않는다. reporting_custody_one_approval 부분 고유 인덱스는 같은 보고/세대에 승인 하나만 허용한다. 사건 상태·원장·원래 보고 시각을 갱신하지 않는다.

### 후속 업무는 현재 배정으로 검사

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L118)

```java
DeliveryOrigin deliveryOrigin(long actor,UUID sid,UUID id) {
    if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("보고 후속 업무에는 트랜잭션이 필요합니다");
    var r=read(id);var state=custody.current(id);var people=custody.lock(state,actor);var person=people.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
    if(!state.contains(person))throw denied(HttpStatus.NOT_FOUND);return approvedDeliveryOrigin(r);
```

원래 보고는 불변이므로 read에서 먼저 검증한다. 그 다음 현재 후속 배정과 계정/인물·최근 MFA를 검사한다. 원래 두 사람에게 영구 권한을 더하는 방식이 아니므로 제외된 사람의 후속 접근은 막힌다. 정상적인 최초 배정은 계속 사용되고, 승인된 인계만 이를 대체한다.

### 결과 불명에 대비한 원문 없는 재조회

출처: [ReportingCustodyService.java](../../src/main/java/dustin/cex/domains/edd/ReportingCustodyService.java#L110)

```java
public Request status(long actor,UUID sid,UUID id) {
    return attempt(actor,()->tx.execute(s->{var r=read(id);var p=r.payload();var state=custody.current(p.reviewId());var person=custody.lock(state,actor).get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
        boolean owner=actor==p.requesterId()&&p.requesterPerson().equals(person.getOperatorPersonId());
        boolean decider=jdbc.queryForObject("SELECT count(*) FROM reporting_custody_decisions WHERE request_id=? AND approver_id=? AND approver_person=?",Long.class,id,actor,person.getOperatorPersonId())>0;
        if(!owner&&!decider&&!state.contains(person))throw denied(HttpStatus.NOT_FOUND);
        audit.record(actor,"REPORTING_CUSTODY_STATUS","custody:"+id,"SUCCESS","METADATA_ONLY");return r;
```

실제 결정자의 ID/인물 기록과 원래 요청자, 현재 담당자를 구분한다. 응답이 끊긴 승인자는 상태만 재확인할 수 있고 보고 원문·새 관찰 권한을 되찾지는 않는다. 재조회는 증명을 소비하거나 새 결정을 쓰지 않는다.

## API·보존과 스키마

| API | 권한과 결과 |
| --- | --- |
| GET `/api/admin/compliance/reporting-orphans` | 현재 OPERATOR/APPROVER, 승인 후보 25개 중 두 담당자 모두 이용 불가인 건의 최소 메타데이터 |
| GET `/api/admin/compliance/reviews/{review}/custody` | 현재 담당자 또는 고립 복구 요청 가능자의 배정/세대/stamp. 원래 보고 사유 없음 |
| POST `/api/admin/compliance/custody-requests` | OPERATOR/APPROVER·현재 담당 또는 고립 조건, Idempotency-Key·expectedStamp·primaryId·checkerId·사유 |
| POST `…/custody-requests/{id}/inspect` | 독립 APPROVER, 현재 요청의 대상·암호화 사유 제공과 본인 영수증 |
| POST `…/{id}/approve/options`, `…/{id}/reject/options`, `…/{id}/approve`, `…/{id}/reject` | 현재 조건·최근 본인 제공·업무별 보안키, 독립 결정 |
| GET `/api/admin/compliance/custody-requests/{id}` | 원래 요청자·실제 결정자·현재 담당자의 원문 없는 상태 재조회 |
| POST `/api/admin/compliance/reviews/{review}/custody-context?page=0` | 현재 후속 담당자의 원래 확정 보고 사유/당시 자료 제공. 별도 감사 |
| GET `…/reviews/{review}/custody-history?page=0`, POST `…/custody-requests/{id}/history` | 현재 담당자의 인계 목록/사유 역사 조회. 승인용 영수증 없음 |

고객과 AUDITOR는 이 API를 사용하지 못한다. 운영 두 역할의 고립 메타데이터 발견은 기존보다 넓은 목적 권한이다. 내부 ID도 연결 가능한 개인정보이며 일반 보안 감사에는 업무 존재·식별자·해시가 남는다. 법의 내부 AML 제공 예외를 모든 직원의 무제한 원문 접근으로 해석하지 않는다. 전담 준법 직무/조직 범위·보고책임자 실명 검증/선임·인사 인물 식별 신뢰는 운영 조건이다.

새 세 표의 요청/암호문·본인 제공·결정은 UPDATE·DELETE·TRUNCATE를 거절한다. 새 승인 종류를 포함한 10종 스키마가 필요하며, 기존 9종 제약을 자동 변환하지 않고 명시적으로 거절한다. 원래 금융/보고 기록을 새 표로 옮기거나 과거 승인 근거를 채우지 않는다. 기존 개발 DB를 변환하거나 서버를 재시작/배포하지 않았다.

## 작성한 테스트

[ReportingCustodyIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ReportingCustodyIntegrationTest.java)에 신규 **45개 실행 사례**를 작성했다. 함수는 37개이며 고립 조건 5종·사유 입력 5종은 각각 별도 JUnit 사례로 집계한다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `initialAuthorityIsRealOriginalPairAndHealthyCaseIsNotOrphan` | 인계 없는 실제 최초 두 인물의 권한·세대 0, 정상 건은 고립 아님·외부자 context 거절 |
| `approvedHandoffMovesFollowupRightsWithoutChangingReportOrFinancialState` | 승인 뒤 두 새 담당자에게 후속 권한 이동, 기존 두 권한 제거·원래 payload/시각/목표/사건/금융 제한 보존 |
| `newCustodiansReadPastObservationsAndOriginalBasisButCannotApproveOriginalReport` | 새 담당자의 기존 관찰/원래 근거 열람·새 관찰 작성, 원래 보고 재승인 거절 |
| `bothUnavailableCustodiansAreDiscoverableAndRecoverableWithoutOriginalParticipation` | 사용 중지·권한 만료·복구 대기·운영 역할 회수·인물 변경 5종의 실제 고립 발견/복구, 승인 전 원문 거절 |
| `oneAvailableOldPersonPreventsOutsiderRescueButCanRequestHandoff` | 한 담당자가 남으면 외부 복구 거절, 남은 승인 역할 담당자는 독립 인계 요청 가능 |
| `recoveredOriginalAuthorityAfterOptionsInvalidatesOutsiderRescueWithoutConsumingProof` | 서명 준비 후 원래 권한 복원이 비담당자의 복구 자격을 없애면 거절·증명 미소비 |
| `healthyOutsiderCannotUseKnownStampToSeizeWork` | 정상 사건의 실제 stamp를 알아도 비담당자가 권한 탈취 불가 |
| `requesterAndEitherBeneficiaryCannotApproveTheirOwnAssignment` | 요청자·새 수혜자·수혜자와 같은 사람의 다른 계정도 자기 권한 승인 불가 |
| `sameHumanCannotHoldBothNewResponsibilities` | 두 새 담당자가 같은 인물 UUID면 요청 거절 |
| `wrongRoleDisabledAndExpiredTargetCannotReceiveWork` | 잘못된 역할·사용 중지·권한 만료 대상 거절 |
| `targetVersionChangeAfterSignatureRejectsHandoff` | 대상 버전 변경 뒤 옛 서명 거절, 원래 권한 유지 |
| `makerVersionChangeAfterSignatureRejectsHandoff` | 요청자 버전 변경 뒤 옛 인계 거절 |
| `changingCustomerDeclarationDoesNotEraseEstablishedReportingDutyOrBlockHandoff` | 새 고객 신고도 이미 확정된 필요의 인계를 막거나 위험을 해제하지 않음 |
| `laterGenerationsReplaceEarlierFollowupRightsAndKeepHistory` | 실제 1→2세대 인계·이전 담당 제외·새 담당자의 두 인계 역사/사유 조회 |
| `unchangedPairAndStaleStampAreRejected` | 그대로인 두 인물과 오래된 예상 해시는 새 인계로 받지 않음 |
| `idempotencyReplaysSameContentAndRejectsAnotherReasonOrTarget` | 같은 키/내용은 같은 요청, 다른 사유/대상은 충돌 |
| `sameKeyConcurrencyCreatesOneRequest` | 실제 두 스레드 같은 키 요청은 하나만 생성 |
| `onlyOnePendingRequestAndRejectionLeavesOldRightsButAllowsNewRequest` | 현재 미결 하나·반려 시 원래 권한 유지·새 요청 가능 |
| `concurrentApprovalAndRejectionCommitOnlyOneDecision` | 실제 승인/반려 경합은 한 결정만 확정, 그 결과에 맞는 권한만 남음 |
| `cannotUseOppositeDecisionSignatureAndProofIsNotConsumed` | 승인 서명을 반려에 재사용 거절·증명 미소비 |
| `historyReadIsNotApprovalInspectionAndNoReceiptMeansNoOptions` | 역사 사유 제공으로 승인용 영수증을 대신할 수 없음 |
| `inspectionFromOtherSessionDoesNotAuthorizeOptions` | 다른 유효 MFA 세션도 이전 세션의 본인 제공을 재사용 불가 |
| `staleMfaCannotDiscoverOrReadOrPropose` | 오래된 MFA의 고립 발견·현재 context·요청 거절 |
| `invalidReasonNeverCreatesHandoff` | null·빈 값·공백·내부 줄바꿈·방향 제어 5개 사유 거절 |
| `oversizedReasonCannotBeStored` | 2,001자 사유 거절 |
| `requestAuditFailureRollsBackCipherAndApprovalBinding` | 요청 감사 실패 시 새 암호문/요청과 STR_CUSTODY 결합 모두 롤백 |
| `inspectionAuditFailureRollsBackReceiptAndReturnsNoReason` | 제공 감사 실패는 새 영수증과 원문 응답을 막음 |
| `decisionAuditFailureRestoresOldAuthorityAndProofThenSameProofRetries` | 결정 감사 실패 시 원래 권한·증명 미소비 복원, 같은 실제 증명 재시도로 정상 인계 |
| `orphanAuditFailureReturnsNoDiscoveryList` | 고립 목록 감사 실패는 결과 반환 불가 |
| `custodyReasonDoesNotEnterMetadataOrAudit` | payload·암호문·공통 감사에서 사유 평문 제외 |
| `allCustodyRecordsRejectUpdateDeleteAndTruncate` | 세 표의 UPDATE·DELETE·TRUNCATE를 실제 PG에서 거절 |
| `previousNineKindSchemaIsRejectedWithoutTransformingHistory` | 옛 9종 승인 제약을 기동 SQL에서 거절, 트랜잭션 후 기존 요청과 10종 제약 보존 |
| `missingOrFutureCursorAndBadHistoryPageAreRejected` | 불완전/미래 커서·음수 역사 페이지 거절 |
| `authorityCannotBeReadWithoutBusinessTransaction` | 업무 트랜잭션 없는 현재 배정 읽기 거절 |
| `actualHttpIndependentHandoffAndNewCustodianContext` | 실제 HTTP 요청→인계 사유 제공→옵션→보안키 서명→승인→새 담당자 원래 근거 조회·no-store |
| `customerAndAuditorCannotUseCustodyHttpRoutes` | 고객/AUDITOR의 고립/배정 HTTP 경로 403 |
| `originalRequesterAndActualDeciderCanConfirmCommittedOutcomeWithoutRegainingFollowupAccess` | 원래 요청자·실제 결정자의 확정 결과 재조회 가능, 후속 접근 권한 복구 없음·다른 외부자 거절 |

기존 BoundApprovalTest의 exhaustive switch는 새 STR_CUSTODY를 전용 통합 검증 대상으로 분리했다. 원래 네 업무와 EDD/보고/후속 범위를 삭제하지 않고 verify_edd.py에 새 클래스를 추가했다.

## 실행 명령과 실제 결과

macOS·Temurin 21·실행기 소유 PostgreSQL/Kafka와 별도 임시 DB를 사용한다. 공급사 HTTP는 대역이며 원래 실제 HMAC·소프트웨어 WebAuthn·PG 잠금과 새 HTTP/서비스 인계 경로를 검증한다. 봇은 0명이고 기존 고객 데이터·다른 프로젝트 DB에는 테스트를 실행하지 않았다.

- `caffeinate -dims python3 scripts/verify_edd.py`
- `python3 scripts/verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`
- `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`
- `python3 scripts/verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`
- `python3 scripts/audit_dependencies.py`, `verify_dependency_integrity.py`

최종 선택 JUnit **1,120개**, 실패·오류·제외 0개다. EDD/증빙/AML/보고/후속/인계 554개(신규 인계 45), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했고 실행 소유 컨테이너·임시 DB·체크섬 표본을 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성을 통과했다. 의존성 222개 버전의 조회 시점 알려진 OSV 검출은 0개이고 변조 체크섬은 예상한 Gradle 종료 코드 1로 차단됐다. 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/reporting-custody-verification-2026-10-24.json)에 실제 명령·개별 사례·분리 집계·자원 정리·현행 법령 본문 대조·현재 코드 132개와 별도 6개 대조·한계를 남겼다.

첫 553개 EDD 실행은 통과했고, 처리 결과 재확인 경로와 인계 메타데이터·후속 근거 트랜잭션 검사를 보강해 최종 실행했다. 중간 수를 최종 합계에 더하지 않는다. 기존 deprecated/unchecked 컴파일 경고와 JVM 테스트 계측 안내는 남아 있다. 신규 금융 차단 검사는 실제 TradingAccessPolicy.requireNewOrder를 같은 트랜잭션에서 호출하며, 인계 고객의 HTTP 주문부터 매칭까지 새 전용 시험을 만든 것은 아니다.

## 남은 한계와 다음 업무

이번 인계는 소프트웨어 후속 업무 권한이며 법정 보고책임자 임면·기관 등록·책임 면제·보고 완료가 아니다. 실제 FIU 제출·접수 진위·보고서 필수 자료·법정 기산점/영업일·보정/긴급 채널은 여전히 별도다. 인계로 원래 보고 필요·제출 의무·내부 점검 목표를 늦출 수 없다.

대상은 승인된 보고 필요다. 아직 보고 판단이 없는 회부, 미승인/만료 검토, 한 담당자만 부재한 사건의 전사 경보, 전담 조직의 감독/분장·대량 인사 변화·연속성 훈련·기한 알림은 별도다. 고립 목록도 페이지를 끝까지 탐색/처음부터 갱신해야 하며 백그라운드 누락 경보가 아니다. 관리자들이 모두 권한을 잃은 상황은 기존 비웹 운영자 복구와 실제 조직 절차가 필요하다.

앱 권한 분리와 DB/KMS 분리를 구분한다. 암호화 사유는 기존 EvidenceCrypto 키를 사용하고 보유/파기·키 교체·슈퍼유저 침해·외부 감사·인사 인물 UUID의 실명 검증·실물 보안키·국외 이전·실제 운영 수용량을 검증하지 않았다. 새 테이블/뷰가 생겨 조회·보존·잠금 비용이 증가하며 처리량을 측정하지 않았다.

새 인계 전용 JVM 강제 종료 검증은 없고 기존 별도 프로세스와 신규 감사 롤백/경합을 구분한다. JMH는 컴파일만 수행했다. 엔진 단일 스레드 코어·출력 워커·프론트는 변경하지 않았다. 전체 법령·신고·ISMS 인증·운영 배포 충족을 주장하지 않고 디스크/호스트 손실의 외부 복제·백업은 기존 문서의 필요 조건을 유지한다.

## 관련 커밋과 연결

- [`7b8e8f2`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7b8e8f2f919ed4e5a564b1065b6be2b928fc3a83): 보고 후속 권한의 독립 인계·고립 발견/복구·논리 세대·결과 재확인과 신규 테스트 45개.

[030 제출 대기와 관찰](030-reporting-queue-and-followup.md), [029 원래 보고 판단](029-confidential-reporting-review.md), [공통 EDD 진행 기록](../../../docs/roadmap/edd-case-review.md)을 함께 읽는다.


2026-10-24 후속: [032의 원문 없는 전사 점검](032-reporting-supervision.md)이 실제 확정 보고 필요의 정상 배정·한 명/두 명의 권한 공백을 승인 역할에 제공한다. 이 문서 최초 시점의 전사 감독 미완료 범위 중 해당 메타데이터 점검을 보완한 것이다. 미승인/만료 검토·전체 회부 감독·상향 보고/알림·법정 기한/실제 제출은 여전히 별도다.
