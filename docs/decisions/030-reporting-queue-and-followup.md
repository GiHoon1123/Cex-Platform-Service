# 과거 보고 필요를 놓치지 않는 제출 대기와 외부 결과 관찰

현재 사건이 바뀌어도 과거에 승인된 보고 필요는 남는다. 실제 확정 이력을 기준으로 지정 인물의 제출 대기 목록을 조회하고, 결과 불명·반려 관찰·접수 참조 관찰을 암호화된 불변 이력으로 기록한다. 문서는 업무 흐름과 구현·문법·테스트를 설명할 개발자를 위한 기록이다. **이번 관찰은 실제 FIU 전송이나 접수 진위 확인이 아니다.**

## 문제와 기존 동작

[029](029-confidential-reporting-review.md)의 내부 보고 여부 결정은 실제 독립 보안키 서명으로 확정되지만, 일반 요청 목록에서 보고 필요와 미보고를 구분해 후속 확인할 수 없었다. 현재 EDD 상태만 조회하면 새 신고·새 회부·후속 미보고 뒤 과거 보고 필요가 사라질 위험이 있다. 외부 작업을 시도한 직원이 응답을 보지 못한 상황과 실제 반려·접수 참조를 본 상황도 구분되지 않았다.

이번 범위는 지정된 두 인물이 보는 **확정 이력 기반 제출 대기 조회 + 외부 처리 관찰의 기록/열람**이다. 목록을 완료 처리하는 경로·외부 호출·독립 접수 진위 확인·기관 원본 파일·보고서 필수 필드·보정 제출은 포함하지 않는다. 동작하는 내부 결정을 유지하면서 후속 추적을 얹는다.

## 공식 자료와 기존 제품 접근

확인일은 2026-10-23이다.

| 근거 | 확인한 내용과 적용 |
| --- | --- |
| [KoFIU 의심거래보고 안내](https://www.kofiu.go.kr/kor/policy/amls03.do) | 내부 보고·보고책임자 검토·외부 제출을 구분한다. 내부 승인만으로 외부 접수를 완료로 표시하지 않는다. |
| [FIU 보고시스템](https://report.fiu.go.kr/), [관세청의 시스템 개통 안내](https://www.customs.go.kr/kcs/na/ntt/selectNttInfo.do?mi=10316&nttSn=10054875&nttSnUrl=search) | 실제 기관 포털의 존재는 확인했다. 공개 문서로 사용할 수 있는 접수 API·인증·오류/접수 증명 계약은 확인하지 못했다. 임의 API를 발명하거나 포털 자동 제출을 구현하지 않는다. |
| [특정 금융거래정보 보고 및 감독규정](https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq=2100000285986&chrClsCd=010201) | 고시 제2026-3호·시행 2026-10-02의 실제 본문에서 제3조의 보고책임자 결정 후 3영업일, 보고 방법/접수 통보와 제8조의 보정·기록 보존을 재대조했다. 직원의 참조 입력은 그 접수 통보의 진위 확인과 다르다. |
| [KONA PLATE 공개 흐름](https://www.konaplate.com/platforms/onboardingguide/guidetab2/compliance03) | 내부 승인과 별도 외부 보고를 나누는 공개 사례다. 전자금융업 사례이며 거래소의 적용 의무나 내부 DB를 이 문서로 확정하지 않는다. |
| [PostgreSQL 16 트랜잭션 격리](https://www.postgresql.org/docs/16/transaction-iso.html), [행 값 비교](https://www.postgresql.org/docs/16/functions-comparisons.html#ROW-WISE-COMPARISON) | 검사 시점의 커밋 가시성과 날짜/UUID 경계 비교를 확인했다. 페이지를 영구 소비 위치나 전체 시점 고정 스냅샷으로 설명하지 않는다. |
| [Stripe 오류 처리](https://docs.stripe.com/error-low-level), [멱등 요청](https://docs.stripe.com/api/idempotent_requests) | 통신 실패와 업무 거절을 구분하고 같은 논리 요청의 재전달은 같은 키로 처리하는 검증된 접근을 참고했다. Stripe의 결제 계약을 FIU의 계약이라고 주장하지 않는다. 우리 관찰 저장은 키를 24시간 후 지우는 방식도 아니다. |

[029의 거래소·증권사·국내 법령·해외 참고 대조](029-confidential-reporting-review.md#공식-근거와-기존-제품의-접근)를 이어 사용한다. 이번 추가 확인이 전체 법령·사업 신고·ISMS·실제 보고 체계를 전수 충족했다는 뜻은 아니다. 보고 사실의 비밀 보호·같은 회사 내부 AML 목적 제공 예외와 지정 두 계정이라는 자체 통제도 구분한다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 비용·제약 |
| --- | --- | --- |
| 실제 승인 이력을 직접 조회 | 새 사건 포인터·고객 제한·후속 미보고가 과거 보고 필요를 지우지 못한다. 별도 큐 복제와 누락 복구 상태를 만들지 않는다. | 후보마다 기존 암호화 판단을 해독·검증한다. 이력이 쌓이면 모든 페이지 조회/복호화 비용이 늘어난다. DB의 후보 필터·정렬이 검사 후보 25개보다 많은 행을 처리할 수 있으므로 전체 DB 작업량이 25행이라는 보장은 아니다. |
| 승인 후보 25개 단위의 키 기반 페이지 | 이름/사유를 복사하지 않고 조회량을 제한한다. 미보고로 비어 있는 페이지도 다음 위치를 가진다. | 응답 items가 비어도 next가 있으면 계속 조회해야 한다. 새 목록 갱신은 처음부터 다시 시작해야 한다. |
| 결과를 직원 관찰로만 보존 | 결과 불명·반려·접수 참조의 서로 다른 사실 주장을 추적하고 근거 없는 성공 판정을 피한다. | 실제 제출한 건도 원본 접수 확인 절차가 갖춰질 때까지 FOLLOW_UP_REQUIRED로 남아 반복 운영 부담이 생긴다. |
| 내부 24시간 점검 목표와 법정 기한 분리 | 달력·보고책임자 판단 시각을 추측해 잘못된 법정 날짜를 만들지 않는다. | 주말/공휴일에도 내부 목표가 도래하므로 인력 대응 부담이 있다. 법정 기한 확인·긴급 보고·영업일 달력은 별도로 필요하다. |
| 상세는 원래 지정된 두 계정/인물만 제공 | 다른 역할 보유자에게 외부 참조와 메모를 열지 않는다. | 담당자가 모두 회수/퇴사하면 현재 API로 그 건을 처리할 수 없다. 전담 조직 권한·독립 재배정·감독용 누락 집계가 운영 전에 필요하다. |

설치된 Spring/JdbcTemplate·Jackson·CddCrypto/EvidenceCrypto를 확인해 재사용했다. 캘린더 패키지를 새로 추가하지 않았다. 주말만 제외한 계산을 법정 영업일로 간주할 수 없고, 국내 공휴일·임시 공휴일·기관 업무일과 기산점의 검증된 입력이 현재 없기 때문이다. 정책과 달력 입력 없이 라이브러리만 추가하는 것은 법정 기한의 정확성을 보장하지 않는다.

## 업무 흐름과 목록의 정확한 범위

1. 승인된 compliance_review_decisions 전체를 조회한다. 현재 사건·현재 평가·KYC 만료·고객의 enabled 상태를 필터로 사용하지 않는다.
2. 현재 운영자의 계정 ID와 원래 지정 인물 UUID가 모두 맞는 후보만 최대 25개 읽는다. 현재 역할·접근 유효성·최근 MFA를 확인한다.
3. 원래 불변 payload 해시·실행 정책·암호화 사유 무결성을 확인한다. 승인된 실제 결론이 REPORT_REQUIRED인 건만 항목으로 반환한다.
4. `(decided_at, review_id)` 순서로 다음 페이지 위치를 만든다. 미보고 후보 25개로 items가 비어도 검사한 마지막 위치를 반환한다.
5. 결과 관찰은 원래 보고 필요에 결합한 OUTCOME_UNKNOWN·REJECTION_OBSERVED·RECEIPT_REFERENCE_OBSERVED 중 하나다. 참조와 메모는 암호화하고 공개 메타데이터에는 상태·내부 ID·HMAC/해시만 보존한다.
6. 원래 지정된 두 인물의 최근 MFA·사용자 행 잠금 뒤 같은 키/내용의 재전달을 처리한다. 다른 내용/대상은 충돌하며 실제 동시 요청도 한 행만 확정한다.
7. 최종 저장·감사는 같은 트랜잭션으로 확정된다. 감사 실패는 새 기록을 취소하고, 상세 제공 감사 실패는 평문을 반환하지 않는다.
8. 접수 참조를 보았다고 적어도 계속 FOLLOW_UP_REQUIRED이며 법정 기한은 LEGAL_DEADLINE_REQUIRES_OFFICER_CONFIRMATION이다. 신규 주문·사건·잔액을 변경하지 않는다.

이 목록은 **현재 지정된 계정/인물의 확정 이력 조회**다. 전사 모든 직원의 누락 없는 보고 큐·실제 외부 제출 대기/완료 작업 엔진이라고 과장하지 않는다. 원래 보고 판단 자체가 생성되지 않은 미처리 회부는 범위 밖이다. 보고 필요라고 승인된 이력은 추후 다른 판단으로 덮거나 한 고객의 최신 한 건으로 축약하지 않는다.

페이지는 영구 소비 체크포인트가 아니다. 다른 트랜잭션이 뒤늦게 커밋하면 이번 조회의 앞 경계에 새 행이 들어올 수 있다. 클라이언트는 한 번의 탐색에서 next가 없어질 때까지 진행하고, 다음 갱신에서는 처음부터 다시 조회해야 한다. 이 계약으로 실제 늦은 승인을 새 조회에서 다시 찾는다. 전체 시점 고정 스냅샷이나 백그라운드 알림 워커를 구현한 것은 아니다.

## 실제 코드·문법과 원리

모두 2026-10-23 현재 소스의 부분 발췌다. 클래스와 주입 객체·업무 트랜잭션이 필요하며 독립 실행 예제가 아니다.

### 후보 페이지의 권한과 순서

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L134)

```java
String sql="""
    SELECT r.id,d.decided_at FROM compliance_reviews r JOIN compliance_review_decisions d ON d.review_id=r.id AND d.approved=true
    WHERE EXISTS(SELECT 1 FROM reporting_current_custody c WHERE c.review_id=r.id AND c.member_id=? AND c.member_person=?)
    """;
var args=new ArrayList<Object>(List.of(actor,person.getOperatorPersonId()));
if(after!=null) {sql+=" AND (d.decided_at,r.id)>(?,?)";args.add(Timestamp.from(after));args.add(afterId);}
var rows=jdbc.queryForList(sql+" ORDER BY d.decided_at,r.id LIMIT 25",args.toArray());var result=new ArrayList<DeliveryOrigin>();
```

역할 외에 두 계정/인물 조합을 SQL에서 먼저 제한한다. `AND (d.decided_at,r.id)>(?,?)`는 PostgreSQL의 행 값 비교로 시각이 같아도 UUID로 경계를 정한다. 사용자 입력은 SQL 문장이 아니라 바인딩 값으로 보낸다. 25는 실제 결과 수가 아니라 검사할 승인 후보 수의 상한이다.

### 빈 페이지를 끝으로 오인하지 않기

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L141)

```java
for(var row:rows) {
    var r=read((UUID)row.get("id"));if(!custody.current(r.payload().id()).contains(person))throw denied(HttpStatus.CONFLICT);
    // 개인정보 원문은 반환하지 않는다. 승인된 미보고 판단도 실제 해독·무결성을 검사한 뒤 제외한다.
    if(!"APPROVED".equals(r.status())||!POLICY.equals(r.payload().policy())||!ASSURANCE.equals(r.payload().assurance()))throw denied(HttpStatus.CONFLICT);
    if(facts(r).conclusion()==Conclusion.REPORT_REQUIRED)result.add(new DeliveryOrigin(r.payload(),r.payloadHash(),((Timestamp)row.get("decided_at")).toInstant()));
}
var last=rows.isEmpty()?null:rows.getLast();return new DeliveryBatch(result,last==null?null:((Timestamp)last.get("decided_at")).toInstant(),last==null?null:(UUID)last.get("id"),rows.size(),rows.size()==25);
```

후보의 진짜 결론을 검증한 뒤 미보고를 제외한다. TypeScript의 배열 filter 이후 원래 검사 위치를 잃지 않는 것과 같다. next를 items의 마지막 요소에서 만들면 미보고만 있는 페이지 뒤의 보고 필요를 놓친다. DeliveryBatch/Page의 목록은 `List.copyOf`로 호출자가 런타임에 변경하지 못하게 한다.

### 현재 사건이 아니라 확정된 원래 판단

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L161)

```java
private DeliveryOrigin approvedDeliveryOrigin(Review r) {
    if(!"APPROVED".equals(r.status())||!POLICY.equals(r.payload().policy())||!ASSURANCE.equals(r.payload().assurance())||facts(r).conclusion()!=Conclusion.REPORT_REQUIRED)throw denied(HttpStatus.CONFLICT);
    var row=jdbc.queryForMap("SELECT decided_at FROM compliance_review_decisions WHERE review_id=? AND approved=true",r.payload().id());
    return new DeliveryOrigin(r.payload(),r.payloadHash(),((Timestamp)row.get("decided_at")).toInstant());
```

read는 원래 payload 해시와 결정 상태를 확인한다. APPROVED를 확인한 뒤에는 원래 요청의 10분 기한을 다시 적용하지 않는다. 승인 후 시간이 지났다고 보고 필요를 없애지 않기 때문이다. 현재 고객 신고/사건 번호를 재검사하는 029의 신규 결정 경로와 업무 목적이 다르다.

### 법정 날짜와 다른 내부 점검 목표

출처: [ReportingFollowupService.java](../../src/main/java/dustin/cex/domains/edd/ReportingFollowupService.java#L65)

```java
Instant observed=Instant.now();
var items=batch.origins().stream().map(origin->{var check=InternalCheck.at(origin.decidedAt(),observed);
    return new Item(origin.payload().id(),origin.payload().userId(),origin.decidedAt(),check.targetAt(),check.overdue(),DEADLINE,"FOLLOW_UP_REQUIRED",latest(origin));}).toList();
```

plusSeconds(86400)는 시간대나 영업일과 무관한 24시간이다. 내부 승인 시각의 고정 목표로만 표시하며 새 관찰이나 직원의 occurredAt 입력으로 재설정하지 않는다. 실제 보고책임자의 결정이 더 이전일 수 있으므로 이 값이 법정 기산점이나 제출 기한이라는 뜻은 아니다. 법정 날짜는 계산하지 않고 확인 필요로 둔다.

### 같은 키에 다른 관찰을 넣지 않기

출처: [ReportingFollowupService.java](../../src/main/java/dustin/cex/domains/edd/ReportingFollowupService.java#L84)

```java
var prior=jdbc.queryForList("SELECT id FROM reporting_followups WHERE actor_id=? AND request_key=?",actor,key);
if(!prior.isEmpty()) {var old=read((UUID)prior.getFirst().get("id"));if(!old.payload().reviewId().equals(review)||!old.payload().factsMac().equals(mac))throw denied(HttpStatus.CONFLICT);
    audit.record(actor,"REPORTING_FOLLOWUP_REPLAYED","reporting-followup:"+old.payload().id(),"SUCCESS","SAME_OBSERVATION");return old;}
if(jdbc.queryForObject("SELECT count(*) FROM reporting_followups WHERE review_id=? AND recorded_at>clock_timestamp()-interval '1 day'",Long.class,review)>=20)throw denied(HttpStatus.TOO_MANY_REQUESTS);
```

원래 보고 해시·정규화한 관찰을 HMAC으로 결합한다. UUID 키를 바꾸는 것은 별도 관찰을 추가하는 것이며 이전 실패를 삭제하지 않는다. 하루 20개 제한은 자체 저장/업무 한도다. 법정 보고를 제한하는 규정이나 외부 제출 시도 수가 아니다.

### 암호화·감사를 함께 확정

출처: [ReportingFollowupService.java](../../src/main/java/dustin/cex/domains/edd/ReportingFollowupService.java#L90)

```java
String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(context(p),json.writeValueAsBytes(value));
jdbc.update("INSERT INTO reporting_followups(id,review_id,user_id,actor_id,request_key,payload,payload_hash,facts_cipher) VALUES(?,?,?,?,?,?,?,?)",id,review,p.userId(),actor,key,json.writeValueAsString(p),hash,cipher);
// 감사에는 외부 참조·메모·관찰 방향을 복사하지 않는다.
audit.record(actor,"REPORTING_FOLLOWUP_RECORDED","reporting-followup:"+id,"SUCCESS",hash);return read(id);
```

암호문의 인증 문맥에는 관찰 ID·고객·원래 검토·원래 해시·내용 MAC이 들어간다. 다른 행으로 암호문을 복사하면 무결성 검증에 실패한다. 저장 성공 뒤 감사가 실패해도 같은 TransactionTemplate 트랜잭션이므로 새 관찰은 롤백된다. 별도의 승인 종류를 만들지 않은 이유는 이 기록이 새로운 법적 최종 판단/독립 확인이 아닌 관찰이기 때문이다.

### 상세 메모도 검증한 후 제공

출처: [ReportingFollowupService.java](../../src/main/java/dustin/cex/domains/edd/ReportingFollowupService.java#L120)

```java
    var value=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),Facts.class).validated();
    if(!p.cddKeyId().equals(cdd.keyId())||value.outcome()!=p.outcome()||!p.factsMac().equals(fingerprint(origin,value)))throw new IllegalStateException();return value;
} catch(RuntimeException e) {throw new IllegalStateException("외부 결과 관찰의 해독·무결성 검증 실패");}
```

현재 보관 키와 전용 MAC·관찰 상태를 대조한다. 원인 예외에 JSON/메모가 섞여 나가지 않게 새 일반 오류만 전달한다. 목록에는 메모/참조가 없고 별도 상세 제공에만 평문을 반환한다. 기본 toString도 원문을 생략한다. 운영 키 교체와 보유/파기는 계속 별도 운영 조건이다.

## API와 관찰 입력

| API | 동작 |
| --- | --- |
| GET `/api/admin/compliance/reporting-queue` | 본인 지정 승인 후보를 25개 검사하고 보고 필요·내부 점검 목표·법정 기한 미확인·최신 관찰 메타데이터를 반환 |
| GET `…/reporting-queue?afterDecision=…&afterReview=…` | 응답 next의 두 값을 함께 전달. next가 있으면 빈 items도 끝이 아님 |
| POST `/api/admin/compliance/reviews/{review}/followups` | Idempotency-Key와 facts로 관찰 추가. 운영 OPERATOR/APPROVER이면서 원래 지정된 계정/인물이어야 함 |
| GET `…/reviews/{review}/followups?page=0` | 25개 단위의 원문 없는 관찰 이력. 저장 시각의 최신순 |
| POST `/api/admin/compliance/followups/{id}/inspect` | 원래 지정된 두 인물의 최근 MFA·감사 후 복호화한 외부 참조/메모 제공 |

facts에는 outcome·occurredAt·externalReference·notes가 있다. 관찰 시각은 직원의 주장으로 epoch 이전·미래는 받지 않고 마이크로초로 정규화한다. 외부 참조는 200자, 메모는 2,000자까지이며 빈 메모·제어/방향 제어 문자를 거절한다. 접수 참조 관찰은 빈 참조를 받지 않는다. 결과 불명·반려는 번호가 없을 수 있으므로 빈 참조를 허용한다. 없는 번호를 만들어 채우지 않는다.

UUID·고객/운영자 ID도 다른 기록과 연결 가능한 개인정보다. 공통 보안 감사에는 조회/관찰 업무의 존재·식별자·성공/실패와 해시가 남으므로 모든 내부 감사 역할에게 업무 존재까지 숨기는 구조는 아니다. 내부 AML 목적 제공과 실제 조직의 최소 권한을 구분해 관리한다.

최신은 직원 주장 시각이 아니라 서버 recorded_at과 ID의 순서다. 늦게 기록한 더 이른 주장도 이전 실패를 지우지 않고 이력에 남는다. 원문 파일·접수 서명·기관의 실제 회신과 관찰의 사실 일치는 검증하지 않는다.

reporting_followups 한 표를 추가하고 UPDATE·DELETE·TRUNCATE를 거절한다. 승인 종류 9개와 기존 원문/금융 기록은 유지한다. 과거 확정 판단을 새 표로 이동/변환하지 않고 원래 이력에서 읽는다. 개발 DB 변환·서버 재시작·운영 배포는 하지 않았다.

## 작성한 테스트

### [ReportingFollowupFactsTest.java](../../src/test/java/dustin/cex/domains/edd/ReportingFollowupFactsTest.java)

15개 실행 사례다. 매개변수 입력은 각각 별도 JUnit 사례로 센다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `knownOutcomesPreserveMeaningWithoutPrintingPrivateText` | 결과 불명·반려·접수 참조 3종의 의미 보존, 기본 문자열에서 실제 참조/메모 제외 |
| `invalidNotesNeverBecomeObservation` | null·빈 값·공백·탭·내부 줄바꿈·방향 제어·숨은 문자 7종 거절 |
| `receiptNeedsReferenceButUnknownAndRejectionMayHaveNoReference` | 접수 참조만 빈 번호 거절, 불명/반려는 번호 없음 허용 |
| `missingFutureAndPreEpochTimeAreRejected` | 누락·미래·epoch 이전 시각 거절 |
| `missingOutcomeAndReferenceAreRejected` | 결과 종류와 참조 필드 누락 거절 |
| `lengthAndControlBoundariesAreExplicit` | 참조 200/메모 2,000자 경계와 제어 문자 거절 |
| `normalizationAndMicrosecondsDoNotInventNewTime` | 바깥 공백 정리와 마이크로초 절삭, 새 시각 추측 없음 |

### [ReportingFollowupIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ReportingFollowupIntegrationTest.java)

28개 실행 사례다. 매개변수 입력은 각각 별도 JUnit 사례로 센다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `realApprovedReportNeededAppearsWithoutDeclaringLegalDeadlineOrSubmissionSuccess` | 실제 독립 승인된 보고 필요의 목록·고정 24시간 목표·법정 기한 확인 필요·후속 대상·거래 차단 |
| `unapprovedAndApprovedNoReportNeverBecomePendingReport` | 미승인 보고 판단은 목록/관찰 대상 아님 |
| `approvedNoReportIsActuallyVerifiedAndExcluded` | 실제 승인된 미보고 판단은 검사 후 목록 제외·관찰 거절 |
| `oldNeededDecisionSurvivesNewDeclarationAndAnotherApprovedConclusion` | 새 회부/미보고와 고객 신고 뒤에도 앞선 보고 필요의 조회·기록 유지 |
| `disabledCustomerDoesNotEraseHistoricalReportingWork` | 실제 enabled=false 고객도 과거 보고 필요의 업무에서 누락되지 않음 |
| `everyObservationOutcomeStaysUnverifiedAndDoesNotCloseTask` | 3개 관찰 상태 모두 원문 일치·기관 확인 미완료·후속 대상·신규 주문 차단 유지 |
| `declaredEarlierExternalTimeDoesNotResetInternalAnchorOrCertifyLegalTime` | 직원의 과거 외부 시각 주장으로 내부 목표·법정 기한 상태 변경 없음 |
| `bothAssignedPeopleCanReadButOthersHaveNoQueueOrObservationAccess` | 지정 둘의 조회 가능, 다른 두 운영 역할의 목록/상세/작성 차단 |
| `changedOperatorIdentityCannotRecoverOldAssignmentUsingSameAccount` | 같은 계정의 인물 UUID가 바뀌면 옛 배정 목록 제외 |
| `sameHumanOnAnotherAccountHasNoImplicitAssignment` | 다른 계정에 같은 인물 UUID를 넣어도 원래 배정으로 취급하지 않음 |
| `staleMfaBlocksQueueAndRawObservation` | 오래된 MFA의 목록/상세 제공 거절 |
| `observationMetadataAndAuditExcludePrivateReferenceAndNotes` | 목록·저장 payload·감사·암호문에서 외부 참조/메모 평문 제외 |
| `sameKeyAndSameFactsReplayButChangedOutcomeOrNotesConflict` | 같은 키/내용은 같은 ID, 다른 메모/상태는 충돌·추가 행 없음 |
| `sameKeyCannotBeMovedToAnotherApprovedReview` | 키를 다른 실제 승인 검토로 옮길 수 없음 |
| `concurrentSameKeyCommitsOneObservation` | 실제 두 스레드의 같은 키 경합은 하나만 확정 |
| `latestRecordedObservationCannotHideEarlierFailureHistory` | 나중 기록의 주장 시각이 더 빨라도 저장순 최신 표시, 이전 반려 이력 보존 |
| `recordAuditFailureRollsBackAndSameKeyRetries` | 저장 감사 실패는 새 기록 롤백, 같은 키 정상 재시도 |
| `queueAuditFailureDoesNotReturnPendingList` | 목록 감사 실패 시 응답 불가 |
| `inspectionAuditFailureDoesNotReturnPrivateNotes` | 상세 감사 실패 시 평문 제공 불가 |
| `allObservationMutationsAreRejected` | 새 표의 UPDATE·DELETE·TRUNCATE 거절 |
| `emptyFilteredFirstPageStillAdvancesAndFindsOldHistoricalWork` | 실제 서로 다른 고객 27명의 서명 승인: 미보고 25개뿐인 첫 페이지도 next로 진행, 뒤의 보고 필요를 찾음 |
| `freshScanFindsApprovalCommittedAfterEarlierQueueRead` | 앞선 조회 뒤 새 승인을 처음부터 재조회해 찾음, 영구 체크포인트 아님 |
| `malformedAndFutureCursorsAndNegativeHistoryPageAreRejected` | 부분/미래 커서와 음수 이력 페이지 거절 |
| `perReviewDailyLimitDoesNotTreatObservationAsCompletion` | 검토별 20개 관찰 뒤 21번째는 429, 후속 대상은 유지 |
| `actualHttpQueueRecordHistoryAndPrivateInspectionUseAssignedFacts` | 실제 HTTP 대기 목록→관찰 저장→지정 승인자의 이력/상세 조회, no-store·원문 제외/일치·거래 차단 |
| `customerAuditorAndUnassignedOperatorCannotUseHttpRoutes` | 고객/AUDITOR HTTP 403, 미지정 운영자 상세 404 |

신규 합계는 **43개 = 순수 형식 15 + 통합 28**이다. 원래 029의 466개 EDD 선택 범위를 유지하고 verify_edd.py에 새 두 클래스를 추가했다. 새 금융 제한 검사는 실제 TradingAccessPolicy.requireNewOrder를 같은 업무 트랜잭션에서 호출한다. 후속 관찰 고객으로 HTTP 주문부터 매칭까지 새 전용 연결 시험을 만든 것은 아니며 기존 시장/프로세스 회귀와 구분한다.

## 실행 명령과 결과

macOS·Temurin 21·실행기 소유 PostgreSQL/Kafka와 임시 DB를 사용한다. 공급사 HTTP는 대역이며 원래 HMAC·소프트웨어 WebAuthn·실제 PG 트랜잭션·새 HTTP 흐름/권한을 검증한다. 봇은 0명이고 기존 고객 데이터·다른 프로젝트 DB는 사용하지 않는다.

- `caffeinate -dims python3 scripts/verify_edd.py`
- `python3 scripts/verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`
- `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`
- `python3 scripts/verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`
- `python3 scripts/audit_dependencies.py`, `verify_dependency_integrity.py`

최종 선택 JUnit **1,075개**, 실패·오류·제외 0개다. EDD/증빙/AML/보고 검토/후속 509개(신규 43 = 통합 28 + 형식 15), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했고 실행 소유 컨테이너·임시 DB·체크섬 표본을 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성을 통과했다. 의존성 222개 버전의 조회 시점 알려진 OSV 검출은 0개이며 변조 체크섬은 예상한 Gradle 종료 코드 1로 차단됐다. 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/reporting-followup-verification-2026-10-23.json)에 실제 명령·개별 사례·분리 집계·자원 정리·법령 본문 대조·현재 코드 125개와 별도 6개 대조·한계를 남겼다. 초기 509개 중 실패 1개는 위의 고객별 한도에 맞지 않은 시험 표본이었다.

기존 deprecated/unchecked 컴파일 경고와 JVM 테스트 계측 안내는 남아 있다.

첫 전체 실행의 페이지 표본은 같은 고객에게 승인 27개를 만들려 해 기존 하루 10건 제한에 걸렸다. 제한이나 감사 이력을 바꾸지 않고 실제 서로 다른 고객 27명의 서명 승인으로 표본을 구성했다. 고객 비활성화 회귀도 실제 enabled=false로 검증했다. 중간 실행 수를 최종 합계에 더하지 않는다.

## 한계와 다음 업무

현재 목록/관찰은 실제 접수 확인이나 법정 기한 충족을 보장하지 않는다. 실제 FIU 기관 등록·보고책임자/지정 역할·보고서 형식·접수 증빙의 진위/독립 확인·보정·재제출·긴급 채널·외부 실패 복구를 연결해야 한다. 공개 API 계약은 확인하지 못했고 실제 외부 전송은 수행하지 않았다. 관찰을 근거로 자동 재제출하지 않으므로 중복 외부 보고 문제를 해결한 것도 아니다.

전담 조직의 감독·독립 재배정·고립된 사건 탐지·미승인/만료 검토 인수·전사 미처리 집계·법정 기산점/영업일 달력과 기한 초과 알림은 남아 있다. 24시간 목표를 실제 경과시킨 운영 알림 검증은 하지 않았다. 더 이전의 법정 보고 의무나 최초 의심 인지를 앱의 승인·새 관찰 시각으로 미룰 수 없다. 업무 조회가 되지 않거나 관찰 저장 한도에 도달해도 법정 보고 의무는 사라지지 않는다.

보관 암호화는 기존 EvidenceCrypto 키를 재사용한다. 앱에서의 인물 권한 분리와 DB/KMS 분리를 구분한다. 키 교체·내부 감사·보유/파기·국외 이전·조직/실물 보안키·슈퍼유저 침해·운영 수용량·외부 감사/백업은 계속 운영 조건이다. 금융 관계 유지/거절/종료와 잔여 자산은 보고 업무와 별도다.

새 업무 전용 JVM 강제 종료 검증은 없으며 기존 프로세스 검증·신규 감사 롤백/경합을 구분한다. JMH는 컴파일만 수행하며 성능을 재측정하지 않는다. 전체 법령·신고·인증·ISMS 충족을 주장하지 않는다. 엔진의 단일 스레드 코어/출력 워커와 프론트는 변경하지 않는다. 디스크·호스트 손실 보호는 기존 문서의 외부 복제·백업 필요 조건을 유지한다.

## 관련 커밋과 연결

- [`0257131`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0257131b6560b6e18c36e66ca371568a5c6a679a): 확정 보고 필요의 제출 대기·빈 후보 페이지·고정 내부 점검 목표·암호화 관찰과 신규 테스트 43개.

[029 비공개 내부 판단](029-confidential-reporting-review.md), [028 제한적 오탐 종결](028-aml-false-positive-resolution.md), [공통 EDD 진행 기록](../../../docs/roadmap/edd-case-review.md)을 함께 읽는다.

## 2026-10-24 후속 배정 인계

[031](031-reporting-custody-and-orphan-recovery.md)에서 최초 두 인물의 배정과 승인된 후속 인계를 하나의 현재 권한으로 계산한다. 위 최초 030의 원래 두 계정 설명·9종 승인 유지·검증 수치는 당시 기준이다. 현재 코드 발췌의 목록 SQL과 상세 검사는 현재 후속 담당자를 사용하며 새 열 번째 STR_CUSTODY 스키마가 필요하다. 인계가 원래 보고 필요·시각·24시간 목표·법정 의무를 바꾸지 않는다. 기존 내부 보고 역사 API와 새 담당자의 별도 원래 근거 제공 경로도 구분한다.


2026-10-24 후속: [032의 원문 없는 전사 점검](032-reporting-supervision.md)이 실제 확정 보고 필요의 정상 배정·한 명/두 명의 권한 공백을 승인 역할에 제공한다. 이 문서 최초 시점의 전사 감독 미완료 범위 중 해당 메타데이터 점검을 보완한 것이다. 미승인/만료 검토·전체 회부 감독·상향 보고/알림·법정 기한/실제 제출은 여전히 별도다.
