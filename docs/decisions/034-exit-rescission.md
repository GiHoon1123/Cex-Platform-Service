# 종료 개시의 원래 기록을 보존하면서 독립적으로 철회하기

잘못됐거나 현재 근거로 유지할 이유가 없어진 종료 개시의 효과만 별도 결정으로 철회한다. 원래 승인·금융 기록·의심거래 보고 업무는 보존한다. 개발자가 업무 경계, 세 인물의 역할, 현재 근거와 서명 결합, PostgreSQL 경합 처리, 실제 테스트를 설명할 수 있도록 작성했다.

## 문제와 기존 동작

[033 종료 개시](033-relationship-exit-initiation.md)는 고객확인 불가 판단을 별도로 승인하고 새 주문을 차단했다. 이후 신원 불일치가 정정돼도 원래 종료 승인은 사라지지 않았다. 일반 거래 제한을 풀거나 고객확인을 정상화하는 일과 종료 개시를 철회하는 일을 구분해야 했다.

원래 승인을 삭제하면 누가 어떤 근거로 고객을 제한했고 왜 정정했는지 사라진다. 반대로 고객별 영구 유일 승인 제약을 그대로 두면 철회 후 새 위험으로 다시 종료를 개시할 수 없다. 이번에는 원래 승인 A → 독립 철회 A → 새 현재 회부에 근거한 종료 승인 B를 별개 이력으로 남긴다. 옛 철회 A를 재조회해도 B는 해제되지 않는다.

철회는 **종료 개시의 신규 주문 차단 효과만** 제거한다. 이미 최종 종료된 계약을 복구하거나 모든 거래를 허용한다는 의미가 아니다. 현재 고객확인·EDD·일반 거래 제한·계정 사용 가능 여부를 기존 주문 정책이 계속 검사한다.

## 기존 제품과 공식 자료의 접근

확인일은 2026-10-24다. 공개 업무 패턴을 참고하며 공개되지 않은 회사 내부 테이블이나 승인 인원 수를 추측하지 않는다.

| 근거 | 적용과 경계 |
| --- | --- |
| [Stripe Unacceptable Risk Policy](https://stripe.com/en-ca/legal/unacceptable-risk-policy) | 2026-07-29 갱신, 캐나다 영문 경로의 Payments 관련 정책이다. 추가 정보의 사람 검토 후 일부/전체 조치를 재검토하는 공개 패턴을 참고한다. 지급 유예 기간·국내 법적 의무·회사 내부 세 인물 구조로 옮기지 않는다. |
| [Adyen reversal](https://docs.adyen.com/online-payments/reversal) | 원거래 참조와 별도 정정 작업의 참조/결과를 연결하는 방식을 참고했다. 이 서비스가 실제 결제 취소·환불을 수행한다는 뜻이 아니다. |
| [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887) | 법률 제21857호·시행 2026-10-02 본문의 고객확인 불가 시 거절/종료와 별도 STR 보고 여부 검토를 대조했다. 직원의 철회 판단만으로 고객확인 의무나 확정 보고 필요를 없애지 않는다. |
| [업비트 개인정보 처리방침](https://static.upbit.com/terms/private_data.html), [빗썸 개인정보 FAQ](https://privacy.bithumb.com/ko/faq/) | 거래관계·고객확인·고충/분쟁 처리의 목적 구분을 확인했다. 본 구현이 양사의 내부 철회 절차와 같거나 고객 이의 접수 전체를 구현했다고 주장하지 않는다. |
| [삼성증권 AML 공개 자료](https://www.samsungsecurities.co.kr/download/kor/pdf/control_file05.pdf) | CDD·STR·내부통제의 별도 업무를 확인했다. 공개된 과거 계획을 현행 이행 완료로 보지 않는다. 민원 처리 안내 검색 경로는 본문 502로 확인하지 못했으며 세부 처리 절차의 근거로 쓰지 않았다. |
| [PostgreSQL 16 함수 변동성](https://www.postgresql.org/docs/16/xfunc-volatility.html), [격리 수준](https://www.postgresql.org/docs/16/transaction-iso.html) | READ COMMITTED에서 VOLATILE 함수 안의 각 조회는 새 스냅샷을 얻는다. 고객 행을 기다린 뒤 현재 승인 효과를 다시 조회하는 조건이다. REPEATABLE READ를 같은 의미로 취급하지 않는다. |
| [Spring 트랜잭션 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) | REQUIRED가 기존 물리 트랜잭션에 참여하면 내부 격리 설정만으로 외부 격리를 바꾸지 못한다. 주문 접수는 기존 NEVER 진입 계약과 자체 READ COMMITTED 템플릿을 사용하고 승인 효과의 DB 트리거도 지원하지 않는 격리를 거부한다. |

원래 참여자와 독립된 세 번째 사람, 10분 요청, 5분 내 본인 전체 페이지 제공, 하루 고객별 10회, 별도 EXIT_RESCISSION 서명은 **자체 정책**이다. 법률이 정확히 세 명이나 이 시간을 요구한다는 뜻이 아니다. 법적 의무·인증 기준·운영 조직과 실제 외부 심사는 별도다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 원래 승인과 별도 불변 철회 | 원래 잘못과 정정 근거, 판단 인물·시점을 추적한다. | 요청·제공·결정 세 표와 암호화/조회·보존 비용이 늘어난다. 원래 승인 삭제나 가변 해제 플래그는 이력을 잃거나 두 권위가 어긋나므로 선택하지 않았다. |
| 원래 참여 OPERATOR가 요청, 양쪽 원래 인물에게서 독립된 APPROVER가 결정 | 자기 판단 정정의 자체 승인을 막고 무관한 운영자의 원문 탐색을 줄인다. | 최소 세 인물이 필요하다. 원래 참여자 모두 사용할 수 없거나 현재 OPERATOR가 없으면 진행할 수 없다. 고립 복구/인계는 아직 없으며 일반 운영자에게 우회 권한을 주지 않는다. |
| 당시·현재 고정 근거와 실제 본인 제공 | 당시 제한과 현재 변경 사유를 함께 확인하고 새 증거 뒤 옛 서명을 막는다. | 다른 내용이면 두 묶음의 모든 페이지를 제공받아야 하므로 복호화·저장·반복 검토 비용이 든다. 내용 해시가 같으면 같은 제공을 인정해 중복을 줄인다. 제공 기록이 실제 이해·판단 품질을 증명하지는 않는다. |
| 다른 금융/준법 제한은 유지 | 잘못된 종료 개시만 바로잡고 보고 필요·일반 제한을 우회하지 않는다. | 고객은 철회 뒤에도 다른 조건 때문에 거래하지 못할 수 있다. 철회 성공을 전역 거래 허용으로 표시하지 않는다. |
| 현재 효과를 원래 승인에서 철회만 제외해 계산 | 시간 순 최대값·가변 상태 복제 없이 재개시를 구별한다. | 조회 비용이 든다. 원래 영구 유일 인덱스 대신 고객 잠금과 명시적 격리·트리거가 필요하다. 지원하지 않는 스키마/격리는 안전하게 거부하며 자동 변환하지 않는다. |

## 업무 흐름과 API

1. 원래 실제 APPROVED 종료 개시를 읽는다. 현재 OPERATOR 역할·최근 인증과 원래 참여 인물인지 확인한다.
2. 원래 두 인물에게서 독립된 현재 APPROVER를 지정한다. 명시적 철회 판단과 사유를 암호화하고 원래 해시·현재 평가/사건·고객/운영자 버전·근거를 고정한다.
3. 새 지정 승인자가 감사가 함께 기록되는 inspect에서 원래/현재 전체 근거와 원래/새 사유를 확인한다. 원래 종료 API나 STR 원문 권한은 얻지 않는다.
4. 현재 근거·인물·세션·최근 본인 제공을 재검사하고 EXIT_RESCISSION 종류와 승인/반려 방향을 결합해 보안키 서명을 받는다.
5. 결정·증명 소모·보안키 카운터·감사를 같은 트랜잭션으로 커밋한다. 감사 실패 시 차단과 증명을 보존해 같은 서명으로 다시 시도한다.
6. 반려는 차단을 유지한다. 승인된 철회는 대상 A의 효과만 제외한다. 새 현재 회부로 B를 개시할 수 있고 같은 옛 키 재요청은 결과 재조회에 그친다.

| 경로 | 입력·권한 |
| --- | --- |
| POST /api/admin/compliance/exit-rescissions | 현재 원래 참여 OPERATOR, Idempotency-Key UUID, exitId·checkerId·facts |
| GET 같은 경로 / GET /{id} | 현재 지정 요청자/승인자 계정·동일 인물의 메타데이터. 전사 목록/원문 권한 없음 |
| POST /{id}/inspect?basis=ORIGINAL 또는 CURRENT&page=0 | 현재 지정 인물만 해당 고정 묶음을 감사와 함께 제공. 기본 CURRENT |
| POST /{id}/approve/options 또는 reject/options | 지정 독립 APPROVER·payloadHash·현재 전체 근거 제공 |
| POST /{id}/approve 또는 reject | 동일 지정자·payloadHash·해당 업무와 방향의 보안키 증명 |
| GET /api/customer/relationship | 본인 상태만 NO_EXIT_DECISION / EXIT_PENDING / EXIT_RESCINDED. EXIT_RESCINDED는 전체 거래 허용 표시가 아님 |

Snapshot.state의 CLOSED는 **EDD 사건 종결**이다. 고객 관계가 최종 종료됐다는 뜻이 아니다. 현재 선언에 대응하는 실제 평가와 사건의 latest_assessment가 일치해야 한다. 새 선언 뒤 평가가 아직 없으면 옛 정상 평가로 철회하지 않는다. OPEN/REFERRED/CLOSED를 모두 다룰 수 있지만 현재 CDD/EDD 차단은 별도로 남는다.

## 구현과 실제 코드·문법

### 변경 시점의 근거를 값으로 고정하기

출처: [ExitRescissionService.java](../../src/main/java/dustin/cex/domains/edd/ExitRescissionService.java#L41). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
public record Snapshot(UUID caseId,long revision,String state,UUID assessmentId,String outcome,List<String> reasons,EddDossier.Summary dossier) {
    public Snapshot {reasons=List.copyOf(reasons);}
}
public record Payload(UUID id,UUID exitId,String exitHash,long userId,long customerVersion,EddDossier.Summary originalDossier,
    Snapshot current,String policy,String assurance,String factsMac,String evidenceKeyId,String cddKeyId,
    long requesterId,UUID requesterPerson,long requesterVersion,long checkerId,UUID checkerPerson,long checkerVersion,Instant expiresAt) {}
public record Request(Payload payload,String payloadHash,String status) {}
```

Java record는 TypeScript interface와 달리 런타임 객체와 접근자·값 비교 구현을 만든다. List.copyOf는 호출자가 원본 목록을 바꿔 고정 판단을 바꾸지 못하게 한다. 현재 평가·사건 세대·내용 해시와 원래 결정·인물 UUID/버전이 모두 서명 대상이다.

### 서명 직전에 현재 상태를 다시 비교하기

출처: [ExitRescissionService.java](../../src/main/java/dustin/cex/domains/edd/ExitRescissionService.java#L135). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
if(maker==null||!maker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||maker.getVersion()!=p.requesterVersion()
    ||!maker.getOperatorPersonId().equals(p.requesterPerson())||actor!=p.checkerId()||reviewer.getVersion()!=p.checkerVersion()
    ||!reviewer.getOperatorPersonId().equals(p.checkerPerson())||people.get(p.userId()).getVersion()!=p.customerVersion())throw denied(HttpStatus.CONFLICT);
requireOriginalMember(maker,original.payload());independent(reviewer,maker,original.payload());requireActive(original);
if(!"PENDING".equals(read(p.id()).status())||!p.policy().equals(POLICY)||!p.assurance().equals(ASSURANCE)||!r.payloadHash().equals(hash)
    ||!p.evidenceKeyId().equals(crypto.keyId())||!p.cddKeyId().equals(cdd.keyId()))throw denied(HttpStatus.CONFLICT);
var row=currentCase(p.userId(),p.current().caseId());
if(!p.current().equals(snapshot(row,p.current().dossier()))||!p.current().dossier().digest().equals(dossiers.liveDigest(p.current().caseId(),p.userId())))throw denied(HttpStatus.CONFLICT);
facts(r,original);exits.correctionFacts(p.exitId());
```

옵션 발급만으로 상태를 예약하지 않는다. 실제 결정 시에도 현재 maker/checker/customer 버전, 원래 승인, 새 근거·내용 해시, 정책/키, 만료를 다시 확인한다. Java의 ||는 왼쪽부터 단락 평가하므로 maker가 null이면 뒤의 접근을 실행하지 않는다. 불일치하면 증명 소모 전 충돌로 거부한다.

### 원래·현재 전체 페이지의 본인 제공

출처: [ExitRescissionService.java](../../src/main/java/dustin/cex/domains/edd/ExitRescissionService.java#L144). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
for(var summary:List.of(p.originalDossier(),p.current().dossier())) {
    dossiers.verify(summary,p.current().caseId(),p.userId());
    long viewed=jdbc.queryForObject("SELECT count(DISTINCT page) FROM relationship_exit_rescission_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND digest=? AND page>=0 AND page<? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,reviewer.getOperatorPersonId(),reviewer.getVersion(),sid,hash,summary.digest(),summary.pageCount());
    if(viewed!=summary.pageCount())throw denied(HttpStatus.CONFLICT);
}
return reviewer;
```

var는 컴파일 시 지역 변수 타입을 추론한다. 런타임 동적 타입은 아니다. request/hash/actor/person/version/session과 최근 5분·내용 digest에 맞는 페이지 수를 각각 검사한다. 같은 digest이면 같은 내용 제공을 재사용하고 다른 digest이면 각각 전 페이지가 필요하다. 실제 제공한 물리 dossier ID는 이력 표에 보존한다.

### 원래 판단 인물의 자기 정정 승인을 막기

출처: [ExitRescissionService.java](../../src/main/java/dustin/cex/domains/edd/ExitRescissionService.java#L169). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
private void independent(User reviewer,User maker,RelationshipExitService.Payload original) {
    if(reviewer==null||reviewer.getRole()!=UserRole.APPROVER||!reviewer.isAuthenticationAllowed()||reviewer.getOperatorPersonId().equals(maker.getOperatorPersonId())
        ||reviewer.getOperatorPersonId().equals(original.requesterPerson())||reviewer.getOperatorPersonId().equals(original.checkerPerson()))throw denied(HttpStatus.FORBIDDEN);
}
```

계정 번호가 달라도 operatorPersonId가 같은 사람은 독립 승인자가 아니다. 현재 승인 역할과 사용 가능 여부도 확인한다. null/역할/인물 중 하나라도 부적합하면 거부한다. 원래 참여자가 요청할 수 있다는 조건은 별도 requireOriginalMember로 제한한다.

### 현재 차단 효과를 이력에서 계산하기

출처: [relationship-exit-rescission-schema.sql](../../src/main/resources/edd/relationship-exit-rescission-schema.sql#L23). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```sql
CREATE OR REPLACE VIEW relationship_active_exits AS
 SELECT d.review_id,d.user_id FROM relationship_exit_decisions d WHERE d.approved
 AND NOT EXISTS(SELECT 1 FROM relationship_exit_rescission_decisions w WHERE w.exit_id=d.review_id AND w.approved);
```

NOT EXISTS는 해당 원래 review_id를 승인 철회한 기록이 없는지 검사한다. 원래 APPROVED 행은 남는다. 최신 시각 하나를 고르는 방식이 아니므로 옛 철회 A가 새 승인 B를 제거하지 않는다. TradingAccessPolicy와 고객 상태는 이 뷰를 사용한다.

### 고객 잠금 이후 새 확정 효과를 확인하기

출처: [relationship-exit-rescission-schema.sql](../../src/main/resources/edd/relationship-exit-rescission-schema.sql#L27). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```sql
CREATE OR REPLACE FUNCTION relationship_exit_require_single_active() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
 IF NEW.approved THEN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
   RAISE EXCEPTION '종료 업무는 READ COMMITTED 격리 수준이 필요합니다' USING ERRCODE='25000';
  END IF;
  PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=NEW.user_id) THEN
   RAISE EXCEPTION '이미 유효한 종료 개시 결정이 있습니다' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END $$;
```

READ COMMITTED를 확인한 뒤 고객 행을 잠근다. 앞선 트랜잭션을 기다렸다면 VOLATILE 내부의 다음 SELECT에서 새 확정 효과를 읽는다. 이미 현재 승인이 있으면 SQLSTATE 23505로 거부한다. 철회 트리거도 같은 격리를 요구하고 지정한 원래 승인만 현재 활성인지 검사한다. REPEATABLE READ에서는 안전 조건이 달라지므로 25000으로 거부한다. PostgreSQL 행 잠금은 플랫폼 업무용이며 엔진 코어 락과 무관하다.

### 결정과 증명·감사를 함께 확정하기

출처: [ExitRescissionService.java](../../src/main/java/dustin/cex/domains/edd/ExitRescissionService.java#L112). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EXIT_RESCISSION,id,hash,approve,proof);
jdbc.update("INSERT INTO relationship_exit_rescission_decisions(request_id,exit_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?,?)",id,p.exitId(),p.userId(),actor,reviewer.getOperatorPersonId(),approve);
// 원래 판단과 금융 상태를 수정하지 않는다. 현재 효과는 승인된 철회를 제외하는 뷰가 계산한다.
audit.record(actor,"EXIT_RESCISSION_DECIDED","exit-rescission:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
```

tx.execute 안에서 검증한 보안키 증명의 소모와 단일 결정을 기록한다. 감사 예외가 나면 전체 롤백된다. TypeScript의 함수 콜백과 모양은 비슷하지만 Spring 트랜잭션은 같은 스레드/연결의 DB 원자성을 제공한다. 다른 네트워크 작업까지 자동 원자화하는 기능은 아니다.

### 주문 접수의 격리 조건을 명시하기

출처: [OrderService.java](../../src/main/java/dustin/cex/domains/order/service/OrderService.java#L98). 현재 소스의 부분 발췌이며 주변 빈·트랜잭션이 필요하다.

```java
this.transactionTemplate = new TransactionTemplate(transactionManager);
// 접수와 권한 변경은 같은 고객 행으로 순서를 정한다. 대기 후의 권한 조회가 새 커밋을 읽도록 고정한다.
this.transactionTemplate.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
```

기존 createOrder의 NEVER 진입 계약이 외부 트랜잭션 참여를 막고, 자체 주문 트랜잭션은 READ COMMITTED로 시작한다. 새 주문 정책과 종료/철회는 같은 고객 행을 잠가 순서를 정한다. 설정 한 줄만으로 임의 외부 물리 트랜잭션의 격리를 바꿀 수 있다고 설명하지 않는다.

## 작성한 테스트

[ExitRescissionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ExitRescissionIntegrationTest.java)의 실제 PostgreSQL·보안키 서명·감사·서비스/MVC 테스트 43개와 [ExitRescissionFactsTest.java](../../src/test/java/dustin/cex/domains/edd/ExitRescissionFactsTest.java)의 입력 7개, 총 50개를 새로 작성했다. 아래 함수별 시나리오와 기대 결과를 최종 XML 사례 목록과 함께 증적에 남긴다.

| 통합 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `withdrawalRemovesOnlyExitHoldAndPreservesOriginalApprovedRecord` | 승인 철회는 종료 차단만 제거하고 원래 APPROVED 기록을 보존 |
| `actualCddClearanceThenIndependentWithdrawalPermitsOrders` | 실제 새 CDD/EDD 해소와 독립 철회 뒤 기존 정책의 주문 허용 |
| `generalTradingRestrictionStillAppliesAfterWithdrawal` | 일반 tradingRestricted가 있으면 철회 후에도 주문 차단 |
| `rejectedOrUnapprovedExitCannotBeWithdrawn` | 미승인/반려 원래 결정을 철회할 수 없음 |
| `currentExitParticipantsCannotReviewTheirOwnOriginalDecision` | 원래 양쪽 인물은 같은 사람의 다른 계정으로도 철회 승인 불가 |
| `outsiderCannotSelfAssignCorrectionToReadPrivateOriginalReason` | 무관한 운영자가 스스로 원문 접근용 철회 요청을 만들 수 없음 |
| `designatedThirdPersonReadsOriginalOnlyThroughAuditedCorrectionPurpose` | 새 지정자는 감사된 정정 inspect만 허용, 원래 API 권한 없음 |
| `noPersonalViewsMeansNoOptionsAndMakerViewsCannotSubstitute` | 승인자 본인 제공 없으면 옵션 불가, 작성자 제공은 대체 불가 |
| `identicalContentUsesOneSetOfPagesDespiteDifferentSnapshotIds` | 내용 같고 물리 사본 ID 다른 경우 한 묶음 제공으로 충족 |
| `differentOriginalAndCurrentContentRequireBothCompleteSets` | 내용 다른 원래/현재 묶음은 둘 다 전체 제공 필요 |
| `newEvidenceAfterSignatureInvalidatesWithdrawalWithoutConsumingProof` | 서명 후 새 증거는 결정 무효화, 증명 미소모 |
| `newDeclarationBlocksStaleDecisionButHistoryRemainsReadable` | 새 선언 뒤 평가 없으면 결정 차단, 역사 열람은 유지 |
| `customerVersionChangeInvalidatesAlreadySignedWithdrawal` | 고객 버전 변경은 이미 서명한 요청 무효화 |
| `makerOrCheckerVersionChangeInvalidatesRequest` | 작성자 버전 변경은 요청 무효화 |
| `checkerVersionAndOldSessionViewsCannotBeReused` | 지정 승인자 버전 변경은 요청 무효화 |
| `sameFactsAndKeyReplayButChangedReasonOrCheckerConflicts` | 같은 키/사실 재조회, 사유/지정자 변경 충돌 |
| `concurrentSameKeyProducesOneRequest` | 동시 같은 키 요청은 하나만 생성 |
| `rejectionPreservesHoldAndAllowsNewCorrectionRequest` | 반려는 차단 유지, 새 정정 요청 허용 |
| `concurrentApprovalAndRejectionCommitExactlyOneDecision` | 동시 승인/반려는 정확히 한 결정 커밋 |
| `oppositeDecisionCannotUseApprovalProof` | 승인 방향 증명으로 반려 불가 |
| `anotherBusinessKindCannotAuthorizeWithdrawal` | 다른 업무 종류 증명으로 철회 불가 |
| `auditFailureRollsBackProposalSnapshotCipherAndBinding` | 요청 감사 실패는 사본·암호문·서명 결합 전부 롤백 |
| `auditFailureReturnsNoReasonAndRollsBackViews` | 열람 감사 실패는 원문 반환/제공 이력 없음 |
| `decisionAuditFailurePreservesHoldAndProofThenSameProofRetries` | 결정 감사 실패는 차단·증명 보존, 같은 증명 재시도 성공 |
| `newExitRequiresNewReferralAndOldWithdrawalReplayCannotClearIt` | 새 회부로 재개시, 같은 옛 철회 키 재조회는 새 차단 해제 불가 |
| `allNewRecordsRejectUpdateDeleteAndTruncate` | 신규 세 표 UPDATE/DELETE/TRUNCATE 차단 |
| `reasonIsAbsentFromMetadataCipherAndAudit` | 사유는 메타데이터·저장 암호문·감사 평문에 없음 |
| `staleMfaInvalidSessionAndBadPageDoNotProvideOriginal` | 오래된 MFA/잘못된 세션/페이지는 원문 제공 불가 |
| `decisionOutcomeCanBeReadAfterResponseLossWithoutReapplyingIt` | 응답 유실 뒤 결과 조회는 재적용하지 않음 |
| `originalReviewerWhoIsNotDesignatedCannotReadWithdrawalReason` | 지정되지 않은 원래 승인자는 새 철회 원문 조회 불가 |
| `oldElevenKindSchemaAndLegacyPermanentIndexAreRejected` | 옛 11종 제약/옛 영구 인덱스 이름의 지원 불가 스키마는 시작 거부 |
| `actualHttpIndependentWithdrawalAndCustomerSafeStatus` | 실제 MVC 독립 철회와 고객 EXIT_RESCINDED 안전 상태 |
| `customerAndAuditorCannotUseCorrectionRoutes` | 고객/감사 역할의 정정 경로 거부 |
| `databaseGuardSerializesDirectConcurrentApprovals` | 직접 SQL 동시 원래 승인에도 고객 잠금으로 활성 하나만 허용 |
| `rescissionCannotBeDuplicatedByDirectDatabaseInsertion` | 직접 SQL로 이미 철회한 대상의 중복 효과 삽입 거부 |
| `withdrawalPreservesMoneyAndReopensOnlyFutureAdmissionAfterAllChecks` | 실제 기존 주문 예약/원장 보존, 옛 키 재조회 후 새 HTTP 주문만 새 접수 |
| `withdrawalNeverRemovesReportingDutyOrGrantsReportingPlaintextAccess` | 실제 확정 STR 필요/후속 업무 유지, 새 지정자의 STR 원문 접근 거부 |
| `receiptFromDifferentSessionCannotAuthorizeWithdrawal` | 다른 세션의 제공 기록은 옵션 권한 대체 불가 |
| `expiredRequestIsReadableWithoutRevivingItsDecisionAuthority` | 만료 요청의 역사는 열람 가능, 결정 권한은 부활하지 않음 |
| `copiedCipherCannotReturnReasonOrCreateViews` | 다른 요청 암호문 복사 시 해독 검증 실패와 제공 이력 없음 |
| `oldViewAloneCannotAuthorizeNewOptions` | 오래된 본인 제공만으로 새 옵션 발급 불가 |
| `bothDifferentBundlesRequireLastPartialPage` | 다른 두 묶음의 마지막 부분 페이지까지 모두 제공 필요 |
| `unsupportedIsolationCannotApplyExitOrRescissionEffects` | REPEATABLE READ 직접 승인/철회 효과는 각각 거부되고 현재 효과 유지 |

| 순수 입력 테스트 함수 | 사례와 기대 결과 |
| --- | --- |
| invalidRationaleIsRejected | null·빈 문자열·공백·개행·방향 제어 문자 5개 거부 |
| rescissionJudgmentMustBeExplicit | 명시적 철회 판단 false 거부 1개 |
| normalizationLengthBoundaryAndSafePrinting | 2,000자 허용/2,001자 거부·안전한 출력 1개 |

직접 SQL 경합/중복/격리 검사는 **DB 제약 검증**이며 실제 MFA 승인 절차를 우회해 정상 승인하는 예가 아니다. 옛 인덱스 이름 검사는 기존 역사와 충돌하지 않는 이름 표본을 롤백 트랜잭션 안에 만들며 옛 영구 unique 인덱스 전체를 재현했다고 주장하지 않는다. 다중 페이지의 만료 증빙 fixture는 페이지 경계용 메타데이터이며 실제 금융 거래로 부풀리지 않는다.

금융 회귀는 실제 PostgreSQL의 OrderService·예약·원장과 MVC를 사용한다. 엔진 HTTP·Kafka·Didit 생성·수수료 설정은 대역이며 실제 엔진 WAL·실물 보안키·외부 기관 검증과 구분한다. 서명 검증에는 소프트웨어 보안키가 실제 암호학적 서명을 생성한다.

## 실행 명령과 결과

최종 선택 JUnit **1,248개**, 실패·오류·제외 0개다. EDD/보고/인계/감독/종료/철회 682개(신규 철회 통합 43 + 입력 7), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했고 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 집중 50개는 최종 682개와 중복되므로 더하지 않는다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점 222개 버전의 알려진 OSV 검출 0개와 변조 체크섬의 예상 Gradle 종료 코드 1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 기존 컴파일/JVM 도구 경고는 남아 있다. 현재 실제 발췌 154개와 별도 역사/범위 6개를 소스와 대조했다.

[확정 증적](../reference/exit-rescission-verification-2026-10-24.json)에 실제 명령·개별 함수·중간/집중/최종 결과 분리·자원 정리·공식 본문·한계를 남겼다.

명령은 각 검증 스크립트의 격리 계약으로 실행했다. 로컬 기존 개발 DB·서버를 변환하거나 재시작하지 않았고 봇은 0명이다.

```sh
python3 scripts/verify_edd.py
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

신규 두 클래스만 고른 임시 집중 실행기는 기존 verify_edd.py의 PostgreSQL 격리·봇/외부 주소 설정을 유지하고 --tests 두 패턴과 --rerun-tasks를 적용했다. 실행한 소스 해시·명령·사례 수는 증적에 별도로 보존한다. 중간 집중 43개·49개와 격리 보강 전 전체 681개는 최종 현재 코드의 실행과 구분하며 중복 합산하지 않는다. 격리 보강 후 집중/최종 결과는 아래 확정 증적을 기준으로 읽는다.

## 남은 한계와 운영 조건

- 종료 **개시 차단**만 철회한다. 최종 관계 종료·자산 정리/반환·신규 계정 수립 거절·출금 정책·고객 이의 접수/알림/처리 기한 전체는 미완료다.
- 원래 인물의 현재 OPERATOR가 없으면 요청할 수 없다. 별도 인계/고립 복구는 후속이며 STR 인계 권한을 그대로 전용하지 않는다.
- 실제 현재 평가와 고정 증빙의 일치는 외부 사실의 진위나 법적 적정성 자체를 인증하지 않는다. 기관 제출·보고 기한·책임자 선임·조직 분장·ISMS 등 운영/외부 심사는 별도다.
- 새 12종 승인 제약·세 표·뷰·두 트리거 함수가 필요하다. 옛 11종 제약과 영구 승인 인덱스는 거부한다. 자동 마이그레이션·금융 이력 삭제·호환 폴백은 없다. 실제 보존 기록이 있는 운영 전환은 별도로 검토해야 한다.
- 승인 효과는 READ COMMITTED 전제다. 애플리케이션 설정과 DB 거부를 함께 사용하며 지원하지 않는 격리의 자동 대체는 없다. DB 슈퍼유저 침해·암호화 키 관리·계정 격리·외부 백업/복제·실운영 수용량은 별도다.
- 신규 JVM 강제 종료 시나리오는 0개다. 기존 별도 프로세스 15개와 구분한다. JMH는 컴파일만 확인하며 처리량/지연 수치로 주장하지 않는다. 기존 deprecated/unchecked·JVM 도구 경고를 숨기지 않는다.
- 엔진·프론트는 변경하지 않는다. 코어의 단일 스레드·락 없는 독점 상태와 WAL/Kafka 출력 워커 계약을 유지한다.

## 관련 커밋과 참고 자료

- [`eeb4faa`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/eeb4faa525ee8364daa7703c7aa0006022a15082): 원래 기록 보존·독립 철회·현재 활성 효과/재개시·명시적 격리·신규 테스트 50개.

- 선행 [033 종료 개시](033-relationship-exit-initiation.md)와 원래 검증 기록은 당시 수치를 보존한다.
- [확정 증적](../reference/exit-rescission-verification-2026-10-24.json)은 현재 소스 검증·중간 실행 분리·개별 함수·자원 정리·공식 자료와 실제 코드 대조 범위를 담는다.
