# 첫 공개 답변의 내부 점검 목표

고객 재검토의 지연과 확정 답변 발견을 실제 기록에 연결한다. 첫 공개 답변의 내부 점검 목표의 계산·접근 경계·실제 코드와 자동화 검증을 개발자가 설명하도록 작성했다.

## 문제와 기존 동작

046까지 최종 답변 작성·서명 승인·본인 조회/확인은 구현했지만 최초 공개 답변의 점검 목표와 지연 발견, 접수 UUID를 모르는 고객의 새 결과 발견은 없었다. 내부 열람을 고객 응답으로 세거나 후속 회신/인계 때 시작 시각을 바꾸면 지연을 숨긴다. 고객 확인과 외부 통지도 구분해야 한다.

## 기존 제품과 공식 자료의 접근

확인일2026-10-24. [Zendesk SLA 정책](https://support.zendesk.com/hc/en-us/articles/5600997516058-About-SLA-policies-and-how-they-work)은 최초 답변과 전체 해결 시간을 별도 측정하고, [공식 최초 답변 설명](https://support.zendesk.com/hc/en-us/articles/4408821871642-Understanding-ticket-reply-time)은 공개 답변과 업무 시간을 구분한다. 공개 기록의 최초 시각을 재사용하고 24시간은 이 프로젝트의 자체 점검 값으로 선택했다. Zendesk나 거래소의 내부 구현/목표 수치가 같다고 주장하지 않는다. 알림의 확정 기록 재사용은 같은 DB 안의 원자적 공개 패턴이며 외부 이메일 전송에는 별도 전달 계약이 필요하다.

[미래에셋증권 공개 민원 안내](https://securities.miraeasset.com/mw/mki/mki3062/n10.do)의 14영업일을 가상자산 거래소의 법정 기한으로 적용하지 않는다. 공개 거래소/개인정보·고객확인·STR 누설 금지의 현행 버전과 적용 조건은 [046의 공식 근거](046-customer-final-receipt.md#기존-제품과-공식-자료의-접근)를 연결한다. 업비트 일반 약관 URL은 이번 웹 조회로 열리지 않아 그곳의 처리기한을 확인했다고 주장하지 않는다. 최초 답변 목표·지연 표시는 자체 정책이며 법정 최종 처리·외부 통지·기관/인증 충족이 아니다.

## 선택과 트레이드오프

첫 공개 답변은040 관측 회신의 실제 보존 observedAt과045 승인 결정의 decided_at 중 먼저 확정된 시각이다. 최종 문구가 공개 가능해진 시각을 세며 고객의 GET/확인이나 이메일 도착은 뜻하지 않는다. 목표는 원래 접수 created_at부터 달력24시간이고 인계/새 회신으로 재시작하지 않는다. 과거 접수도 현재 정책 V1로 평가하지만 과거에 이 목표를 약속했거나 적용했다고 소급 주장하지 않는다. 기존 데이터 변환·추정 이력은 만들지 않는다.

고정 달력 시간은 새 의존성·미검증 영업일/공휴일 규칙 없이 재현되지만 휴일/야간 대응 인력이 필요하고 복잡한 조사까지 하루 만에 끝내라는 목표는 아니다. 늦게 답변해도 RESPONDED_LATE는 남고 금융 제한을 풀지 않는다. 시각 이상은 검토 필요로 보이며 운영 호스트의 시간 동기화가 필요하다. 범용 SLA 설정·일정 엔진·실제 법정 기산점은 추가하지 않았다.

기존 PostgreSQL·Spring/JDBC·java.time과 실제 금융/업무 불변 기록을 사용한다. 신규 패키지·승인 종류·복제 표·출력 워커를 추가하지 않는다. 조회량은 후보25개와 커서로 제한하고 목록은 전체 고정 스냅샷이나 영구 처리 위치가 아니다. 원문 없는 목록도 업무 존재·내부 식별자를 노출하므로 현재 역할/본인 경계·최근 추가 인증·필수 감사를 유지한다.

## 구현과 실제 코드

### 고정 24시간과 실제 최초 답변

[CustomerReviewResponseTarget.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewResponseTarget.java#L12)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public static Assessment assess(Instant received,Instant firstStatusReply,Instant finalDecided,Instant observed) {
    if(received==null||observed==null)throw new IllegalArgumentException("접수·관측 시각이 필요합니다");
    // 내부 메모·직원 열람·고객 확인은 공개 답변이 아니다. 실제 공개 회신/최종 승인 중 먼저 확정된 시각만 쓴다.
    Instant first=firstStatusReply==null?finalDecided:finalDecided==null?firstStatusReply:
        (firstStatusReply.isBefore(finalDecided)?firstStatusReply:finalDecided);
    Instant target=received.plus(Duration.ofHours(24));
    // 시계 역행이나 서로 다른 호스트 시각 오차를 성공으로 보정하지 않는다. 운영자가 시각 근거를 확인해야 한다.
    if(observed.isBefore(received)||(first!=null&&(first.isBefore(received)||first.isAfter(observed))))
        return new Assessment(POLICY,target,first,State.TIME_REVIEW_REQUIRED);
    State state=first==null?(observed.isBefore(target)?State.WAITING:State.OVERDUE):
        (first.isAfter(target)?State.RESPONDED_LATE:State.RESPONDED_ON_TIME);
    return new Assessment(POLICY,target,first,state);
}
```

Duration은 경과 시간이고 영업일 달력이 아니다. Java Instant의 순서 비교로 최초 공개 회신/최종 승인 시각을 고른다. 열람/내부 메모/고객 확인은 인자로 받지 않는다. 완료가 정확히 목표 시각이면 적기, 미답변 상태로 목표 시각에 도달하면 지연이다. 시계 불일치는 정상으로 보정하지 않고 TIME_REVIEW_REQUIRED로 표시한다. TypeScript Date의 밀리초와 달리 Instant는 나노초 경계도 표현한다.

## API 필드

기존 GET `/api/admin/compliance/customer-review-work`의 각 항목에 responseTarget을 추가했다. policy·targetAt·firstPublicResponseAt·state를 제공한다. WAITING/OVERDUE는 아직 공개 응답 없음, RESPONDED_ON_TIME/RESPONDED_LATE는 최초 답변 시각의 결과, TIME_REVIEW_REQUIRED는 시각 근거 확인 필요다. 목록 관측은 고정 스냅샷이 아니며 각 항목 평가 시점의 현재 시간을 사용한다.

## 작성한 테스트

[CustomerReviewResponseTargetTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewResponseTargetTest.java), [CustomerResponseTargetIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerResponseTargetIntegrationTest.java). 실제 업무 검증은 실행 소유 PostgreSQL·실제 서명/감사/MVC를 사용하고, 시간 경계는 고정 Instant로 검증해 실제 시간을 기다리지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `beforeTargetIsWaiting` | 목표 직전 미답변은 대기 |
| `exactOpenTargetIsOverdue` | 목표 시각에 미답변이면 지연 |
| `afterTargetIsOverdue` | 목표 이후 미답변은 지연 |
| `responseExactlyAtTargetMeetsGoal` | 정확히 목표 시각의 답변은 적기 |
| `responseOneNanosecondLateRemainsLate` | 1나노초 늦은 최초 답변은 지연 이력 유지 |
| `finalApprovalCountsWithoutStatusReply` | 상태 회신 없이 승인된 최종 답변도 최초 공개 응답 |
| `earliestStatusReplyWinsEvenAfterLaterFinal` | 나중 최종 승인으로 앞선 상태 회신 시각을 초기화하지 않음 |
| `earliestFinalApprovalWinsOverLaterStatus` | 최종 승인이 먼저면 해당 시각 사용 |
| `oldLateResponseDoesNotBecomeTimelyAfterAnotherResponse` | 이미 늦은 최초 답변을 후속 답변으로 적기로 바꾸지 않음 |
| `weekendsAndTimezoneDoNotPauseCalendarTarget` | 주말·시간대에도 달력24시간 유지 |
| `eventBeforeReceiptRequiresTimeReview` | 접수보다 과거인 응답은 시각 검토 |
| `futureEventRequiresTimeReview` | 관측보다 미래인 응답은 시각 검토 |
| `observationBeforeReceiptRequiresTimeReview` | 접수보다 과거 관측은 시각 검토 |
| `missingRequiredTimesAreRejected` | 필수 시각 누락 거부 |
| `actualPublicReplyIsResponseButNotFinalResolution` | 실제 공개 회신은 첫 응답이지만 최종 종결은 아님 |
| `reasonViewAndInternalNoteDoNotCountAsPublicResponse` | 실제 사유 열람·내부 메모는 미응답 유지 |
| `laterFinalApprovalAndCustomerAckDoNotResetFirstPublicTime` | 실제 최종 승인·고객 확인 뒤 최초 시각과 목표 보존 |
| `custodyTransferDoesNotResetOriginalTarget` | 담당자 인계 뒤 원래 목표 보존 |

## 실행 명령과 결과

선택 JUnit **34개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle47.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

외부 Didit 생성·엔진 HTTP·Kafka의 공통 대역과 실제 DB/보안키 암호 서명·감사/MVC를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용한다. 기존 개발 DB/서버/엔진 코어/프론트 변경·배포 없이 검증하며 새 성능 수치는 없다.

## 한계

24시간은 최종 해결 기한이나 법적 약속이 아니다. 업무 목록의 관측 시각과 실제 제공/고객 확인/외부 전송을 구분한다. 법정 기산점·공휴일·개별 공개 문구의 적정성·외부 이메일/SMS 발송/수신·신원 확인 지원 채널·기관/운영/인증 심사는 별도다. 고객에게 실제 메시지를 발송하거나 외부 서비스를 등록하지 않았다.

## 관련 커밋과 증적

- [`4b1d005`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/4b1d005a6aa956d4a123409513f8f974396de9a8): 구현과 관련 회귀.

[확정 증적](../reference/customer-response-target-verification-2026-10-24.json), [046 최종 답변](046-customer-final-receipt.md).
