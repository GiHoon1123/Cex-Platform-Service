# 고객 재검토 요청을 원문 없이 전사 점검하기

고객 접수는 원래 담당자의 개별 목록에만 있었다. 새 요청의 발견과 담당자 부재를 전체 승인 역할이 점검할 수 없었다. 접수 원문을 전사 공개하거나 열람만으로 처리 완료를 표시하지 않고 최소 정보의 발견 경로를 만든다. 개발자가 업무 경계·기술 선택·실제 코드/문법·실패 검증을 설명하도록 작성했다.

## 문제와 구현 범위

GET /api/admin/compliance/customer-review-supervision. 현재 APPROVER와 최근 MFA를 필터·서비스에서 확인한다. afterReceived·afterRequest를 함께 주거나 생략하며 시각/UUID로 최대 25개를 순회한다. 모든 고객 요청은 원래 종료가 철회되거나 고객이 비활성화돼도 보존한다. 현재 배정된 두 인물의 자격·서버 열람 감사의 최근 시각을 제공하지만 원문 권한·인계·금융 결정을 부여하지 않는다.

## 기존 제품과 공식 근거

확인일 2026-10-24. [Zendesk Ticket Comments](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 공개/비공개 대화와 [Ticket Audits](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/)의 추가 전용 변경 이력을 참고했다. 해당 제품의 내부 구현이나 국내 법적 의무를 추측하지 않는다. 공개 여부 플래그 하나로 내부 사유가 공개되지 않도록 업무 경로/자료를 구분한다.

[미래에셋증권 민원 안내](https://trading.securities.miraeasset.com/hki/hki3062/n18.do)의 접수·조사·회신 단계와 [업비트](https://static.upbit.com/terms/private_data.html)·[빗썸](https://privacy.bithumb.com/ko/faq/)의 관계 관리/고충 처리 목적을 대조했다. 증권사의 14영업일 안내를 거래소의 법정 기한으로 이식하지 않는다. [개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353), 법률 제21445호·시행 2026-09-11의 목적별 최소 수집을 기준으로 원문 노출을 줄인다. 실제 처리 근거·고지·보존/파기·인증/기관 확인은 별도다. [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률 제21857호·시행 2026-10-02의 고객확인·종료·별도 STR 검토를 이 기능으로 대체하지 않는다.

## 선택과 트레이드오프

업무 존재·고객/담당자 내부 ID를 배정 밖의 승인 역할에게 제공하는 비용과 후보별 현재 자격/감사 조회 비용이 있다. 감독자만 잠그므로 전체 항목이 동일 시점 스냅샷은 아니다. 25개 제한은 응답 제한이며 DB 전체 작업량 상한을 보장하지 않는다. 기존 감사의 목적별 인덱스를 추가해 조회를 지원한다.

원래 승인/철회·고객확인·STR·원장/예약은 그대로 보존한다. 업무 기록을 삭제하거나 새 가변 상태를 금융 진실원으로 만들지 않는다. 기존 Spring/JDBC/JDK·암호화 의존성을 재사용하고 신규 패키지를 추가하지 않는다.

## 구현과 이해에 필요한 코드·문법

### 시각과 UUID로 제한 순회

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L48)의 현재 부분 발췌다. 주변 의존성과 트랜잭션이 필요하며 독립 실행 예제가 아니다.

```java
var candidates=after==null?jdbc.queryForList("SELECT id,user_id,exit_id,created_at FROM customer_exit_review_requests ORDER BY created_at,id LIMIT 25"):
    jdbc.queryForList("SELECT id,user_id,exit_id,created_at FROM customer_exit_review_requests WHERE (created_at,id)>(?,?) ORDER BY created_at,id LIMIT 25",Timestamp.from(after.receivedAt()),after.requestId());
```

키셋 커서로 조회한다. 같은 시각에는 UUID를 비교해 중복/누락을 피한다. 현재 목록을 영구 소비 위치로 쓰지 않고 처음부터 재순회한다.

### 제공 기록과 현재 담당자만 표시

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L55)의 현재 부분 발췌다. 주변 의존성과 트랜잭션이 필요하며 독립 실행 예제가 아니다.

```java
Timestamp viewed=jdbc.queryForObject("SELECT max(occurred_at) FROM security_audit WHERE action='CUSTOMER_EXIT_REVIEW_VIEWED' AND outcome='SUCCESS' AND target=?",Timestamp.class,"customer-review:"+id);
var progress=jdbc.queryForMap("""
    SELECT (SELECT min((payload::jsonb->>'observedAt')::timestamptz) FROM customer_exit_review_replies WHERE request_id=?) AS first_reply,
    d.request_id,d.decided_at,a.acknowledged_at FROM (SELECT 1) seed
    LEFT JOIN customer_review_final_decisions d ON d.review_id=? AND d.approved
    LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id
    """,id,id);
var assessment=CustomerReviewResponseTarget.assess(((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("first_reply")),instant(progress.get("decided_at")),Instant.now());
var item=new Item(id,p.userId(),p.id(),((Timestamp)row.get("created_at")).toInstant(),viewed==null?null:viewed.toInstant(),members,members.stream().noneMatch(Member::available),assessment,(UUID)progress.get("request_id"),instant(progress.get("decided_at")),instant(progress.get("acknowledged_at")),updates.assess(id,((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("decided_at"))),CustomerReviewCompletionTarget.assess(((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("decided_at")),Instant.now()));
if(!attentionOnly||item.requiresAttention())items.add(item);
```

사유 원문이나 암호문을 읽지 않고 성공한 열람 감사의 마지막 시각만 읽는다. 담당자 자격은 현재 역할·인물·사용 가능 여부이며 최근 MFA가 오래됐다는 이유로 인물이 사라졌다고 표시하지 않는다.

### 감사 실패 시 목록 반환 차단

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L67)의 현재 부분 발췌다. 주변 의존성과 트랜잭션이 필요하며 독립 실행 예제가 아니다.

```java
audit.record(actor,attentionOnly?"CUSTOMER_REVIEW_ATTENTION_READ":"CUSTOMER_REVIEW_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
return new Page(items,next,candidates.size(),observed,SEMANTICS);
```

Spring 트랜잭션 콜백에서 감사가 성공한 뒤 응답 값을 반환한다. TypeScript 콜백과 문법은 비슷하지만 같은 DB 연결의 원자성과 서버 인가 경계를 함께 사용한다.

## 작성한 테스트

[CustomerReviewSupervisionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewSupervisionIntegrationTest.java)의 함수별 시나리오와 기대 결과다. 실제 파라미터별 사례 수는 XML 증적을 기준으로 한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `supervisorSeesUnviewedRequestButCannotReadReason` | 미배정 감독자가 미열람 메타데이터만 조회하고 고객 사유 열람은 거부 |
| `successfulViewIsVisibleButDoesNotHideOrCompleteRequest` | 정상 열람 감사 시각을 표시하되 요청은 계속 보존 |
| `viewAuditFailureDoesNotPretendReasonWasProvided` | 열람 감사 실패 시 제공된 것으로 표시하지 않음 |
| `withdrawalAndDisabledCustomerDoNotEraseOutstandingRequest` | 종료 철회/고객 비활성화 후에도 요청 보존 |
| `missingOriginalPeopleAreVisibleWithoutGrantingTakeover` | 원래 두 인물의 권한 상실 표시, 인계 권한은 부여하지 않음 |
| `currentSupervisorAuthorityIsRequired` | 비활성/만료/복구/역할 변경 4개 거부 |
| `staleRevokedAndForeignSessionsCannotRead` | 오래된 MFA/회수/다른 인물 세션 거부 |
| `malformedCursorsAreRejected` | 불완전/음수/미래 조회 위치 거부 |
| `auditFailureReturnsNoMetadata` | 감독 감사 실패 시 메타데이터 반환 없음 |
| `twentySixRealReceiptsSpanTwoPagesWithoutLosingHistory` | 실제 26개 고객 접수를 두 페이지로 중복/누락 없이 순회 |
| `httpNoStoreRoleAndCursorBoundaries` | MVC의 역할/익명·저장 금지 헤더·조회 위치 확인 |

## 실행 명령과 결과

선택 JUnit **79개**, 실패·오류·제외 0개. ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, ExitApprovalSupervisionIntegrationTest 16개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle38.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다. 새 감사 인덱스를 KYC 쪽 스키마에서 준비해 감사 표가 생기기 전 애플리케이션 초기화가 실패했다. 감사 스키마의 표 생성 뒤로 인덱스를 옮겨 순서를 바로잡고 새 격리 DB에서 79개를 통과했다. 첫 실행의 초기화 실패 4개/순수 입력 7개와 실제 업무 검증을 구분한다. 중간 실패는 최종 통과에 합산하지 않았다.

실제 PostgreSQL·소프트웨어 보안키의 실제 서명·감사·MVC를 사용한다. 외부 Didit 세션 생성·엔진 HTTP·Kafka·수수료 설정은 공통 대역이며 실물 키/기관/운영 검증과 구분한다. 테스트별 전용 DB/컨테이너를 사용하고 기존 금융/감사 이력을 삭제해 fixture를 정리하지 않는다.

## 남은 한계

서버의 원문 제공 감사는 고객 수신·사람의 이해·조사/회신 완료가 아니다. 만료/미처리 경보·법정 기한·원래 인물 부재 시 인계·기관/조직 통제는 별도다. 이번 API만으로 전체 민원·법적 통지·조직·ISMS/신고 심사 충족을 주장하지 않는다. 신규 강제 종료 시험과 성능 수치는 없다. 기존 컴파일의 deprecated/unchecked 및 JVM 도구 경고를 남긴다. 엔진 단일 스레드 코어·출력 워커와 프론트는 변경하지 않으며 기존 개발 DB/저널 변환·서버 재시작·배포 없이 봇은 0명이다.

## 관련 커밋과 증적

- [`f6b4b29`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/f6b4b292ac03659a755d4dc3242b798cef04dddb): 고객 재검토 요청의 원문 없는 전사 점검와 관련 회귀.

[038 확정 증적](../reference/customer-review-supervision-verification-2026-10-24.json), [037 고객 접수](037-customer-exit-review.md), [공통 진행 기록](../../../docs/roadmap/edd-case-review.md).


041 이후 이 목록은 승인된 현재 고객 업무 배정의 인물로 공백을 계산한다. 마지막 열람 감사 시각은 과거 인물/세대의 기록도 포함하며 새 담당자가 읽었다는 뜻은 아니다. 과거 038의 실행·증적은 보존하고 [041](041-customer-work-transfer.md)을 따른다.


048 이후 현재 전사 감독은 최초 공개 응답 목표·최종 승인·고객 확인도 구분한다. 별도 attention 경로는 미응답 지연/시각 이상/미확인 최종 결과/담당자 공백만 고르고, 빈 필터 페이지의 커서는 유지한다. [048 구현](048-customer-review-attention.md)을 따르며038 당시 회귀/증적은 보존한다.


051 이후 전사 감독에는 후속 회신 간격과 원래 접수부터의 미종결 감독도 포함한다. [051 구현](051-customer-open-case-review.md)을 따르고038 당시 실행/증적은 보존한다.
