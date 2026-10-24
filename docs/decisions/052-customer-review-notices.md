# 진행 회신과 최종 답변의 통합 고객 알림

진행 회신과 최종 답변을 같은 본인 알림 목록에서 찾는다. 두 종류의 의미와 고객 확인 가능 여부, 실제 금융/업무 원본을 구분한다.

## 문제와 기존 동작

047~049는 최초 공개 응답24시간·원문 없는 주의 목록·본인 확정 답변 알림을 구현했다. 최초 답변만 있으면 이후 장기간 답변/종결이 없어도 주의 대상에서 빠질 수 있었고, 고객 알림에는 진행 회신이 없었다. 이번 계층은 기존 작동 경로 위에 후속 점검과 원본 재사용을 추가한다.

## 기존 제품과 공식 자료의 접근

2026-10-24 확인한 [Zendesk SLA 공식 문서](https://support.zendesk.com/hc/en-us/articles/5600997516058-About-SLA-policies-and-how-they-work)는 최초 응답·후속/주기적 업데이트·전체 해결의 측정을 구분한다. [PostgreSQL16 윈도 함수](https://www.postgresql.org/docs/16/functions-window.html)의 lag와 집계 FILTER를 사용해 기존 불변 이력을 집계한다. 직접 시간 달력/이력 복제 엔진을 만들지 않는다. [GitHub 알림 API](https://docs.github.com/en/rest/activity/notifications)의 본인 메타데이터 목록/최근 항목 우선 패턴을 참고하되 공개되지 않은 내부 DB·발송 구조를 추측하지 않는다.

국내 고객확인·개인정보 최소 수집·STR 사실 누설 금지의 실제 시행 버전과 적용 조건은 [046 근거](046-customer-final-receipt.md#기존-제품과-공식-자료의-접근)를 연결한다.24시간은 이번 서비스의 자체 운영 점검이며 법정 최종 처리 기한·영업일·고객 약관의 약속이 아니다. 증권사의 공개 처리기한을 그대로 적용하지 않는다. 기관/조직/인증·운영 통지 이행은 코드와 구분한다.

## 선택과 트레이드오프

후속 공개 회신 목표는 최신 공개 회신(없으면 원래 접수)부터 달력24시간이다. 내부 메모·직원 열람·고객 확인은 시계를 옮기지 않는다. 미종결 검토 목표는 원래 접수부터24시간이며 공개 답변/인계가 있어도 옮기지 않는다. 두 기준 모두 최종 승인을 자동 생성하거나 거래 제한·CDD/STR·원장/예약을 바꾸지 않는다. 최종 승인이 있으면 현재 점검은 종료하고 과거 초과 결과는 남긴다.

고정 달력 기준은 미검증 영업일/공휴일·범용 설정 없이 재현되지만 주말/야간 대응과 장기 조사 건의 반복 검토 부담이 생긴다. 감독 목록이 계속 보인다는 것은 금융 결론을 서두르라는 뜻이 아니다. 과거 기록을 현재 정책으로 평가하며 과거에 이 목표를 약속했다고 소급 주장하지 않는다. 시계 오차·역행은 정상으로 보정하지 않고 별도 검토 상태를 표시한다.

기존 Spring/JDBC·java.time·Jackson·암호화/감사 기능만 사용한다. 새 의존성·금융 승인 종류·알림 복제 표·발송 워커를 만들지 않는다. DB 집계는 전체 이력을 JVM으로 읽는 비용을 줄이지만 후보별 SQL/과거 기록 집계 비용이 들며 실제 운영 부하 측정은 별도다. 입력/조회 제한·목적별 역할과 필수 감사·현재 인물 검사는 유지한다.

## 구현과 실제 코드

### 같은 DB의 실제 공개 기록을 통합

[CustomerReviewNoticeService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewNoticeService.java#L31)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
String sql="""
    SELECT * FROM (
        SELECT 'STATUS'::text AS kind,r.id AS notice_id,r.request_id AS review_id,
            (r.payload::jsonb->>'observedAt')::timestamptz AS occurred_at,r.payload,r.content_mac AS integrity,
            r.fingerprint_key_id AS key_id,NULL::bigint AS approver_id,NULL::uuid AS approver_person,
            NULL::timestamptz AS final_ack,r.sequence AS logical_sequence,r.note_id
        FROM customer_exit_review_requests c JOIN customer_exit_review_replies r ON r.request_id=c.id WHERE c.user_id=?
        UNION ALL
        SELECT 'FINAL',d.request_id,d.review_id,d.decided_at,f.payload,f.payload_hash,NULL,
            d.approver_id,d.approver_person,a.acknowledged_at,NULL::bigint,NULL::uuid
        FROM customer_exit_review_requests c JOIN customer_review_final_decisions d ON d.review_id=c.id AND d.approved
        JOIN customer_review_final_requests f ON f.id=d.request_id AND f.review_id=c.id
        LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id AND a.user_id=c.user_id
        WHERE c.user_id=?
    ) notices
    """;
var rows=after==null?jdbc.queryForList(sql+" ORDER BY occurred_at DESC,kind DESC,notice_id DESC LIMIT 25",actor,actor):
    jdbc.queryForList(sql+" WHERE (occurred_at,kind,notice_id)<(?,?,?) ORDER BY occurred_at DESC,kind DESC,notice_id DESC LIMIT 25",actor,actor,Timestamp.from(after.occurredAt()),after.kind().name(),after.noticeId());
var items=new ArrayList<Item>();for(var row:rows)items.add(metadata(actor,row));
```

UNION ALL로 상태 회신과 최종 승인을 구분해 합친다. 같은 UUID가 종류마다 존재할 수 있으므로 종류·ID를 함께 식별자로 사용한다. 최신 시각·종류·ID의 복합 커서로 정렬을 재현한다. 별도 알림 복제 표/외부 발송은 없다.

### 원문 없는 종류별 연결 검증

[CustomerReviewNoticeService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewNoticeService.java#L55)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
private Item metadata(long actor,Map<String,Object> row){
    Kind kind=Kind.valueOf((String)row.get("kind"));String raw=(String)row.get("payload");UUID id=(UUID)row.get("notice_id"),review=(UUID)row.get("review_id");
    if(kind==Kind.FINAL){
        var p=json.readValue(raw,CustomerFinalReviewService.Payload.class);
        if(!verifier.digest(json.readTree(raw)).equals(row.get("integrity"))||!CustomerFinalReviewService.POLICY.equals(p.policy())||p.userId()!=actor||!p.id().equals(id)||!p.reviewId().equals(review)||p.checker().id()!=((Number)row.get("approver_id")).longValue()||!p.checker().person().equals(row.get("approver_person")))throw new IllegalStateException("고객 알림의 실제 최종 승인 연결 검증 실패");
    }else{
        var p=json.readValue(raw,CustomerReviewReplyService.Payload.class);
        // 상태 회신의 해시는 비밀키 지문이다. 최종 승인의 일반 해시와 혼동하지 않고 원래 검증 방식을 유지한다.
        if(!cdd.keyId().equals(row.get("key_id"))||!cdd.fingerprint("customer-review-public-reply",raw).equals(row.get("integrity"))||p.userId()!=actor||!p.id().equals(id)||!p.requestId().equals(review)||p.sequence()!=((Number)row.get("logical_sequence")).longValue()||!p.noteId().equals(row.get("note_id")))throw new IllegalStateException("고객 알림의 실제 상태 회신 연결 검증 실패");
    }
    return new Item(kind,id,review,((Timestamp)row.get("occurred_at")).toInstant(),row.get("final_ack")==null?null:((Timestamp)row.get("final_ack")).toInstant());
}
```

각 원본의 대상·ID·HMAC 또는 승인 해시/인물을 검사한다. 상태 회신은 확인 대상이 아니고 최종 답변만 기존 명시적 확인 API를 사용한다. 목록 제공 자체는 확인을 만들지 않는다.

## API 교체와 의미

GET `/api/customer/relationship/review-notices`가 현재 유일한 고객 알림 경로다. 기존 `/final-notices`와 CustomerFinalNoticeService/Controller는 삭제했다. 엔진·프론트 참조를 확인했고 해당 경로의 구현 의존은 없었다. 호환 별칭이나 데이터 변환은 없다. 기존 확정 답변·상태 회신·명시적 확인 기록은 그대로 조회한다.

항목은 kind(STATUS/FINAL)·noticeId·reviewId·occurredAt·finalAcknowledgedAt이다. 시각·종류·ID의 내림차순25개를 제공하며 다음 요청은 afterDecision·afterKind·afterNotice를 모두 지정한다. afterDecision은 해당 공개 이벤트 시각이다. 종류까지 포함해야 다른 원본의 UUID 충돌/동일 시각을 구분할 수 있다. 영구 소비 위치로 쓰지 않고 처음부터 갱신한다.

STATUS는 기존 본인 replies 조회, FINAL은 기존 final 조회·명시적 확인 API로 연결한다. STATUS의 finalAcknowledgedAt=null은 미확인이 아니라 확인 대상이 아니라는 뜻이다. 최종 확인은 STATUS를 확인한 것으로 쓰지 않는다. 목록에는 고객 문구를 복사하지 않는다. 이메일/SMS/푸시 전송·수신은 구현하지 않았다.

기존049 최종 알림11개 회귀는 현재 통합 서비스의 FINAL 하위 항목을 선택해서 유지한다. 이 선택은 시험 도우미이며 제품의 호환/필터 경로가 아니다. 신규52 회귀는 실제 통합 응답의 진행/최종을 함께 검사한다. 한 고객의25개가 가득 찬 통합 알림 경계·동일 UUID의 다른 원본 실물 충돌은 이번에 실제 생성하지 않았다. 서로 다른 실제 진행/최종과 엄격 복합 커서는 검증한다.

## 작성한 테스트

[CustomerReviewNoticeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewNoticeIntegrationTest.java). 시간 경계는 고정 Instant로 검증하고, 업무 검증은 실행 소유 PostgreSQL·실제 암호 서명/감사/MVC를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `existingStatusReplyIsDiscoverableWithoutFinalApproval` | 최종 승인 전에도 실제 상태 회신을 발견하고 기존 본인 상세 조회로 연결 |
| `finalAndStatusRemainDistinctAndLatestFirst` | 진행/최종 유형·식별자 분리와 최신순 |
| `exactCompositeCursorReturnsOlderStatusWithoutFinalDuplicate` | 시각·종류·ID의 엄격 커서로 최종 중복 없이 이전 진행 회신 조회 |
| `repeatedReadsNeverCreateCustomerConfirmation` | 반복 조회는 명시적 확인을 만들지 않음 |
| `finalConfirmationAppliesOnlyToFinalItem` | 최종 항목만 확인 시각 반영, 진행 ID로 최종 확인 불가 |
| `pendingOrRejectedFinalDoesNotHideRealStatus` | 미결·반려 최종 초안이 실제 상태 회신을 숨기지 않음 |
| `metadataContainsNeitherPublicBodyNorInternalMaterial` | 고객 문구·진행 문구·내부 기록/해시 미공개 |
| `completeCursorIsRequiredIncludingKind` | 종류를 포함한 세 커서 값 모두 필요 |
| `otherSessionAndOperatorCannotReadCustomerList` | 타인 세션·운영자 고객 목록 권한 거부 |
| `auditFailureReturnsNoStatusOrFinal` | 감사 실패는 진행/최종 목록 모두 응답하지 않음 |
| `oldFinalOnlyRouteIsRemovedAndNewRouteIsCustomerOnly` | 기존 최종 전용 경로 기본 거부403, 통합 고객 경로/no-store·역할·잘못된 종류 검증 |

## 문서 검증 수정

첫 발췌 대조에서 SQL 뒤 같은 행의 for 문까지 포함하지 않아 실제 소스 행과 일치하지 않았다. 제품 코드는 바꾸지 않고 실제 행 전체로 발췌를 고쳐 전체 대조를 다시 실행했다. 이 문서 오류를 제품 JUnit 실패로 합산하지 않는다.

## 집계 관측의 후속 회귀

전체991개/관련109개 실행 이후 [050 관측 시각 보완](050-customer-periodic-updates.md#집계-중-새-회신의-관측-시각-보완)을 적용했다. 관측은 집계 결과를 읽은 뒤 평가해 조회 도중 실제 공개된 회신을 미래 시각으로 오인하지 않는다. 현재 관련53개/bootJar로 다시 검증하며 추가 회귀1개와 중복52개를 구분한다. 이후 광역566개는 현재 코드에서 실행한다. 전체991개가 이 마지막 변경 뒤 실행됐다고 표시하지 않는다.

추가 테스트는 CustomerUpdateTimelineIntegrationTest.responsePublishedDuringQueryIsNotMistakenForFutureClockData이며 실제 DB 새 회신을 조회 중 확정하는 제어로 WAITING을 기대한다. 원본 시각을 수정하지 않는다.

## 실행 명령과 결과

선택 JUnit **991개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle52.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

최종 고유 선택 JUnit **1,558개**, 실패·오류·제외0개다. 전체 EDD991개 뒤 관측 시각 보완을 현재53개/bootJar로 검증했고 추가 회귀1개를 포함한다. 광역 KYC150·승인/접근190·MFA42·금융183·시장 연결1개를 통과했다. 이번 세 사이클 신규 **43개**(050 17 + 051 14 + 052 12)중42개는 전체991개에, 추가1개는 현재53개 검증에 포함된다. 관련109개·현재53개와 각 사이클 선택을 중복 합산하지 않는다. 기존 프로세스 **15개**도 통과했고 신규 강제 종료/성능 검증은0개다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점222개 버전의 알려진 OSV 검출0개·변조 체크섬의 예상 Gradle 종료1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 현재 실제 코드 발췌 **202개**와 별도 역사/범위6개를 소스와 대조했다. 실제 명령·사례·환경·문서 발췌 오류 정정·기존 경고와 검증 한계는 확정 증적을 따른다.

실제 DB/암호 서명/감사/MVC와 공통 대역인 외부 Didit 생성·엔진 HTTP·Kafka를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 실행 소유 DB/컨테이너·격리 외부 주소·봇0명을 적용한다. 기존 개발 DB/서버/엔진/프론트 변경·운영 배포 없이 검증한다. 새 처리량/지연 수치는 없다.

## 한계

자체 점검은 법정 기산점/영업일·최종 해결·기관 제출·외부 이메일/SMS의 전달/수신을 의미하지 않는다. 대량 알림·보존/파기·운영 시간 동기화·인력/실물 키·신고/인증 심사는 별도다. 본인 목록·문구 조회와 최종 결과의 명시적 확인을 구분하며 알림 GET으로 확인 이력을 만들지 않는다. 외부 메시지를 발송하지 않았다.

## 관련 커밋과 증적

- [`9928e03`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9928e032f47e6ac9f2e5bdca7da716ae23ea53a3): 구현과 관련 회귀.

- [`3f12dae`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/3f12dae): 통합 고객 알림·기존 경로 제거와 관련 회귀.

[확정 증적](../reference/customer-review-notices-verification-2026-10-24.json), [049 앞선 알림 범위](049-customer-final-notices.md).
