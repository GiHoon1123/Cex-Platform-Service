# 지연·고객 미확인 요청의 전사 점검

고객 재검토의 지연과 확정 답변 발견을 실제 기록에 연결한다. 지연·고객 미확인 요청의 전사 점검의 계산·접근 경계·실제 코드와 자동화 검증을 개발자가 설명하도록 작성했다.

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

### 현재 조치가 필요한 조건

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L24)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public boolean requiresAttention(){return responseTarget.state()==CustomerReviewResponseTarget.State.OVERDUE||responseTarget.state()==CustomerReviewResponseTarget.State.TIME_REVIEW_REQUIRED||(finalReviewId!=null&&customerAcknowledgedAt==null)||(orphaned&&finalReviewId==null);}
```

미응답 지연·시각 이상·미확인 최종 결과·미종결 담당자 공백을 고른다. 늦게라도 응답한 과거 이력은 전체 목록에 남고 현재 지연으로 영구 남기지 않는다.

### 전사 주의 목록의 별도 목적

[CustomerReviewSupervisionService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewSupervisionService.java#L37)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Page attention(long actor,UUID sid,Cursor after){
    // 전체 감독과 같은 관측/커서를 쓰되 지금 조치할 건만 고른다. 과거 지연 이력은 전체 목록에 남는다.
    return scan(actor,sid,after,true);
}
```

현재 APPROVER의 원문 없는 감독만 허용한다. 기존 전체 감독과 같은 커서·관측 계약을 사용하고 응답만 필터링한다. 필터 결과가 비어도 검사한 후보의 next를 유지한다.

## API

GET `/api/admin/compliance/customer-review-attention`은 현재 APPROVER만 사용할 수 있다. afterReceived·afterRequest는 기존 전체 감독과 같은 복합 커서다. responseTarget·finalReviewId·finalDecidedAt·customerAcknowledgedAt·현재 담당자 공백만 제공하고 사유/문구는 주지 않는다. attention 필터는 검사 후 적용하므로 items가 비어도 next가 있으면 계속 읽는다. 기존 전체 감독 경로는 이력을 계속 제공한다.

## 작성한 테스트

[CustomerReviewAttentionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewAttentionIntegrationTest.java). 실제 업무 검증은 실행 소유 PostgreSQL·실제 서명/감사/MVC를 사용하고, 시간 경계는 고정 Instant로 검증해 실제 시간을 기다리지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `timelyStatusResponseIsNotFinalButNeedsNoImmediateAttention` | 적기 상태 회신은 최종 종결이 아니지만 당장 주의 목록에서는 제외 |
| `approvedButUnacknowledgedAnswerIsVisibleWithoutPrivateText` | 최종 승인/고객 미확인은 메타데이터로 발견하고 원문 권한은 부여하지 않음 |
| `explicitCustomerAckRemovesAttentionButKeepsFullHistory` | 명시적 본인 확인 후 주의 제외, 전체 목록의 확정/확인·금융 이력 보존 |
| `orphanedUnfinishedWorkNeedsAttentionEvenAfterTimelyResponse` | 적기 회신이 있어도 미종결 담당자 공백은 주의 필요 |
| `transferredWorkUsesCurrentPeopleAndPreservesResponseTarget` | 현재 인계된 두 인물로 공백 판정, 원래 목표 보존 |
| `rolesAndFreshSessionAreRequired` | 미승인 역할·타인 세션·오래된 MFA 거부 |
| `auditFailureReturnsNoAttentionMetadata` | 주의 목록 감사 실패는 응답하지 않고 정상 재시도 가능 |
| `invalidCursorIsRejected` | 불완전한 커서를 첫 페이지로 우회시키지 않음 |
| `overdueAndClockReviewNeedAttentionButResolvedLateHistoryDoesNot` | 고정 상태의 주의 분류와 과거 늦은 응답의 현재 제외, 시간 경계 자체는047 순수 검증 |
| `twentySixRealRequestsAdvanceAcrossEmptyAttentionPage` | 실제26접수의 첫25개 필터 공백 후 마지막 고객 미확인 결과 발견 |
| `httpAttentionUsesApproverAndNoStore` | 별도 MVC 경로/현재 APPROVER/no-store, 운영자·고객·익명 거부 |

## 실행 명령과 결과

선택 JUnit **59개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle48.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

외부 Didit 생성·엔진 HTTP·Kafka의 공통 대역과 실제 DB/보안키 암호 서명·감사/MVC를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용한다. 기존 개발 DB/서버/엔진 코어/프론트 변경·배포 없이 검증하며 새 성능 수치는 없다.

## 한계

적기 첫 답변이 있고 현재 담당자가 정상이며 아직 최종 승인이 없는 접수는 현재 attention 목록에서 제외된다. 전체/개인 목록에서는 계속 보인다. 첫 답변 이후 장기 미종결·주기적 후속 회신/최종 해결의 기한은 이24시간 지표로 감시하지 않는다. 형식적 상태 회신만으로 실제 업무가 진행됐다고 주장하지 않으며 운영 점검과 별도 최종 처리 기준이 필요하다.

24시간은 최종 해결 기한이나 법적 약속이 아니다. 업무 목록의 관측 시각과 실제 제공/고객 확인/외부 전송을 구분한다. 법정 기산점·공휴일·개별 공개 문구의 적정성·외부 이메일/SMS 발송/수신·신원 확인 지원 채널·기관/운영/인증 심사는 별도다. 고객에게 실제 메시지를 발송하거나 외부 서비스를 등록하지 않았다.

## 관련 커밋과 증적

- [`9dbcbe4`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9dbcbe402b72bf38c7b92edcdbd0d8d435102854): 구현과 관련 회귀.

[확정 증적](../reference/customer-review-attention-verification-2026-10-24.json), [046 최종 답변](046-customer-final-receipt.md).


고객의 본인 확정 결과 발견과 명시적 확인 의미는 [049 알림 목록](049-customer-final-notices.md)을 따른다. 당시 회귀/증적은 보존한다.
