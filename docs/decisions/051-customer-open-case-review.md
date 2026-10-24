# 공개 답변과 별개인 미종결 요청 감독

상태 회신을 했다는 이유로 장기 미종결 요청이 감독에서 빠지는 공백을 막는다. 최종 해결을 강제하거나 금융 제한을 자동 해제하지 않는다.

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

### 미종결 검토 시점은 접수부터 유지

[CustomerReviewCompletionTarget.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewCompletionTarget.java#L10)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public static Assessment assess(Instant received,Instant finalized,Instant observed){
    if(received==null||observed==null)throw new IllegalArgumentException("미종결 감독의 접수·관측 시각이 필요합니다");
    Instant target=received.plus(Duration.ofHours(24));
    boolean invalid=observed.isBefore(received)||(finalized!=null&&(finalized.isBefore(received)||finalized.isAfter(observed)));
    boolean late=finalized==null?!observed.isBefore(target):finalized.isAfter(target);
    return new Assessment(POLICY,target,invalid?State.TIME_REVIEW_REQUIRED:finalized!=null?State.CLOSED:late?State.OPEN_REVIEW_DUE:State.OPEN_WAITING,late);
}
```

이24시간은 최종 해결 약속이 아니라 감독자가 미종결 업무를 확인할 자체 기준이다. 새 회신/인계로 원래 목표를 옮기지 않는다. 최종 승인 뒤에는 미종결 시계를 종료하되 기준 초과 여부를 보존한다.

### 각 주의 사유를 독립적으로 평가

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L24)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public boolean requiresAttention(){return updateTarget.state()==CustomerReviewUpdateTarget.State.OVERDUE||updateTarget.state()==CustomerReviewUpdateTarget.State.TIME_REVIEW_REQUIRED||completionReview.state()==CustomerReviewCompletionTarget.State.OPEN_REVIEW_DUE||completionReview.state()==CustomerReviewCompletionTarget.State.TIME_REVIEW_REQUIRED||responseTarget.state()==CustomerReviewResponseTarget.State.OVERDUE||responseTarget.state()==CustomerReviewResponseTarget.State.TIME_REVIEW_REQUIRED||(finalReviewId!=null&&customerAcknowledgedAt==null)||(orphaned&&finalReviewId==null);}
```

최초 응답·후속 회신·미종결·고객 미확인·담당자 공백을 OR로 묶는다. 한 종류의 정상 회신이 다른 종류의 지연을 지우지 않는다. 원문 열람/금융 해제 권한을 주지 않는다.

## API 필드와 운영 의미

기존 개인/전사 감독 목록에 updateTarget·completionReview를 제공한다. 기존 attention 경로는 두 지표의 지연/시각 이상도 독립적으로 포함한다. completionReview.OPEN_REVIEW_DUE는 원래 접수 후24시간 이상 미종결이므로 감독 검토 대상이라는 뜻이다. 검토 메모를 썼다고 숨기거나 목표를 재설정하지 않아 장기 조사 건의 반복 검토/알림 피로가 생길 수 있다. 원문과 근거는 기존 지정 담당자 경로에서 확인하며 금융 결론을 서두르거나 강제 종료하지 않는다.

## 작성한 테스트

[CustomerReviewCompletionTargetTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewCompletionTargetTest.java), [CustomerOpenCaseIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerOpenCaseIntegrationTest.java). 시간 경계는 고정 Instant로 검증하고, 업무 검증은 실행 소유 PostgreSQL·실제 암호 서명/감사/MVC를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `openBeforeTargetWaits` | 기준 직전 미종결 대기 |
| `exactOpenTargetNeedsReview` | 접수24시간 경계 미종결은 감독 필요 |
| `repeatedLaterObservationDoesNotMoveTarget` | 다음 조회로 원래 목표를 옮기지 않음 |
| `exactFinalTargetIsNotExceeded` | 기준 시각의 최종 승인은 초과 아님 |
| `lateFinalClosesButKeepsExceededFlag` | 늦은 최종 승인은 시계 종료·초과 결과 보존 |
| `observationBeforeReceiptRequiresReview` | 관측 시각 역행은 검토 |
| `finalBeforeReceiptRequiresReview` | 접수 이전 최종 시각 검토 |
| `futureFinalRequiresReview` | 미래 최종 시각 검토 |
| `requiredTimesCannotBeMissing` | 필수 시각 누락 거부 |
| `timelyPublicReplyDoesNotBecomeFinal` | 실제 적기 회신 후에도 미종결 상태 유지 |
| `completionReviewCannotBeHiddenByFreshUpdate` | 고정 시각의 새 회신이 미종결 감독 기준을 숨기지 못함 |
| `periodicOverdueIsIndependentlyVisible` | 후속 회신 지연도 독립적인 주의 사유 |
| `finalApprovalStopsBothOpenTimers` | 실제 최종 승인/고객 확인 후 두 미종결 시계 종료·금융 효과 보존 |
| `transferDoesNotResetOriginalCompletionTarget` | 실제 인계 후 원래 미종결 감독 기준 보존 |

## 실행 명령과 결과

선택 JUnit **72개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle51.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

실제 DB/암호 서명/감사/MVC와 공통 대역인 외부 Didit 생성·엔진 HTTP·Kafka를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 실행 소유 DB/컨테이너·격리 외부 주소·봇0명을 적용한다. 기존 개발 DB/서버/엔진/프론트 변경·운영 배포 없이 검증한다. 새 처리량/지연 수치는 없다.

## 한계

자체 점검은 법정 기산점/영업일·최종 해결·기관 제출·외부 이메일/SMS의 전달/수신을 의미하지 않는다. 대량 알림·보존/파기·운영 시간 동기화·인력/실물 키·신고/인증 심사는 별도다. 본인 목록·문구 조회와 최종 결과의 명시적 확인을 구분하며 알림 GET으로 확인 이력을 만들지 않는다. 외부 메시지를 발송하지 않았다.

## 관련 커밋과 증적

- [`56c1964`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/56c1964bb86d8436e6123f3130d38c0e538b8119): 구현과 관련 회귀.

[확정 증적](../reference/customer-open-case-review-verification-2026-10-24.json), [049 앞선 알림 범위](049-customer-final-notices.md).


공개 진행 회신과 최종 결과의 실제 본인 알림 연결은 [052 통합 알림](052-customer-review-notices.md)을 따른다. 당시 회귀/증적은 보존한다.
