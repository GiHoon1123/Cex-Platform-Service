# 첫 답변 이후의 후속 공개 회신 간격 점검

첫 공개 답변 이후에도 미종결 요청의 후속 회신이 멈추면 발견한다. 새 회신이 현재 목표를 이동해도 과거 지연 간격은 지우지 않는다.

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

### 현재 후속 목표와 과거 지연 분리

[CustomerReviewUpdateTarget.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewUpdateTarget.java#L10)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public static Assessment assess(Instant received,Instant latestPublic,Instant finalized,long gaps,boolean invalidOrder,Instant observed){
    if(received==null||observed==null||gaps<0)throw new IllegalArgumentException("후속 회신의 시각·간격 근거가 필요합니다");
    Instant anchor=latestPublic==null?received:latestPublic,target=anchor.plus(Duration.ofHours(24));
    boolean invalid=invalidOrder||observed.isBefore(received)||anchor.isBefore(received)||anchor.isAfter(observed)
        ||(finalized!=null&&(finalized.isBefore(anchor)||finalized.isAfter(observed)));
    return new Assessment(POLICY,latestPublic,target,invalid?State.TIME_REVIEW_REQUIRED:finalized!=null?State.CLOSED:observed.isBefore(target)?State.WAITING:State.OVERDUE,gaps);
}
```

Java Duration은 달력24시간을 표현한다. 최신 공개 회신만 다음 목표를 옮기고 이미 초과한 간격 수는 입력 근거대로 보존한다. 최종 승인은 현재 시계를 종료한다. 열람/비공개 메모/고객 확인은 공개 회신 인자가 아니다. TypeScript 인터페이스와 달리 record는 런타임 생성자·값 비교도 제공한다.

### 불변 기록의 SQL 윈도 집계

[CustomerReviewUpdateTimeline.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewUpdateTimeline.java#L15)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
var row=jdbc.queryForMap("""
    WITH events AS (
        SELECT 0 AS kind,0::bigint AS sequence,?::timestamptz AS occurred
        UNION ALL SELECT 1,sequence,(payload::jsonb->>'observedAt')::timestamptz FROM customer_exit_review_replies WHERE request_id=?
        UNION ALL SELECT 2,0,?::timestamptz WHERE ?::timestamptz IS NOT NULL
    ), gaps AS (SELECT kind,occurred,occurred-lag(occurred) OVER(ORDER BY kind,sequence) AS elapsed FROM events)
    SELECT max(occurred) FILTER(WHERE kind=1) AS latest_public,
        count(*) FILTER(WHERE elapsed>interval '24 hours') AS breached,
        coalesce(bool_or(elapsed<interval '0 seconds'),false) AS reversed
    FROM gaps
    """,Timestamp.from(received),review,finalized==null?null:Timestamp.from(finalized),finalized==null?null:Timestamp.from(finalized));
var latest=(Timestamp)row.get("latest_public");
return CustomerReviewUpdateTarget.assess(received,latest==null?null:latest.toInstant(),finalized,((Number)row.get("breached")).longValue(),Boolean.TRUE.equals(row.get("reversed")),observed);
```

lag는 논리 순서의 바로 앞 시각을 읽는다. 종류0접수·1상태 회신·2최종 승인으로 정렬하므로 시각 정렬로 역행을 숨기지 않는다. FILTER로 전체 문구/이력을 JVM으로 복사하지 않고 집계 값만 받는다. SQL bigint는 Number.longValue로 읽으며 JS number와 정밀도 범위가 다르다.

## 작성한 테스트

[CustomerReviewUpdateTargetTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewUpdateTargetTest.java), [CustomerUpdateTimelineIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerUpdateTimelineIntegrationTest.java). 시간 경계는 고정 Instant로 검증하고, 업무 검증은 실행 소유 PostgreSQL·실제 암호 서명/감사/MVC를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `unansweredUsesReceiptAnchor` | 미응답은 원래 접수 기준 |
| `waitingBeforeBoundary` | 경계 직전 대기 |
| `exactBoundaryIsOverdue` | 목표 시각 도달 시 지연 |
| `updateMovesOnlyNextTarget` | 회신은 다음 목표만 이동하고 과거 지연 보존 |
| `newOverdueDoesNotChangePastGapCount` | 현재 지연 상태가 과거 초과 횟수를 덮어쓰지 않음 |
| `finalApprovalStopsCurrentTimerButPreservesPastGaps` | 최종 승인 뒤 현재 시계 종료·과거 지연 보존 |
| `finalWithoutStatusReplyIsClosed` | 상태 회신 없는 최종 승인도 종료 |
| `reversedLogicalTimelineRequiresReview` | 논리 순서의 시각 역행 검토 |
| `futurePublicTimeRequiresReview` | 미래 공개 시각 검토 |
| `finalBeforeLastReplyRequiresReview` | 최신 회신보다 이른 최종 승인 시각 검토 |
| `invalidInputsAreRejected` | 필수 시각/음수 간격 거부 |
| `actualPublishedReplyStartsNextTarget` | 실제 공개 회신부터 다음24시간 목표 |
| `realSqlGapCountPersistsAfterLaterReply` | 실제 SQL 윈도 집계에 고정된 과거 시작 입력을 주어 초과 횟수/새 회신 뒤 보존 검증 |
| `noteAndViewDoNotRestartPublicTimer` | 본인 열람/내부 메모는 공개 시계 미변경 |
| `finalAndAckKeepClosedTimerAndHistory` | 실제 최종 승인·고객 확인 뒤 종료/과거 결과 유지 |
| `sqlDetectsFinalBeforeLogicalReply` | SQL이 논리 순서의 최종 시각 역행을 찾음 |
| `privateQueueIncludesSameActualAssessment` | 개인 목록이 같은 실제 집계 사용 |

## 실행 명령과 결과

선택 JUnit **33개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle50.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

실제 DB/암호 서명/감사/MVC와 공통 대역인 외부 Didit 생성·엔진 HTTP·Kafka를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 실행 소유 DB/컨테이너·격리 외부 주소·봇0명을 적용한다. 기존 개발 DB/서버/엔진/프론트 변경·운영 배포 없이 검증한다. 새 처리량/지연 수치는 없다.

## 한계

자체 점검은 법정 기산점/영업일·최종 해결·기관 제출·외부 이메일/SMS의 전달/수신을 의미하지 않는다. 대량 알림·보존/파기·운영 시간 동기화·인력/실물 키·신고/인증 심사는 별도다. 본인 목록·문구 조회와 최종 결과의 명시적 확인을 구분하며 알림 GET으로 확인 이력을 만들지 않는다. 외부 메시지를 발송하지 않았다.

## 관련 커밋과 증적

- [`fd7b9b8`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fd7b9b88b20ef7028738929995581b4761f78d6c): 구현과 관련 회귀.

[확정 증적](../reference/customer-periodic-updates-verification-2026-10-24.json), [049 앞선 알림 범위](049-customer-final-notices.md).
