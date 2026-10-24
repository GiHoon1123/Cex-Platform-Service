# 고객의 확정 답변 알림 목록

고객 재검토의 지연과 확정 답변 발견을 실제 기록에 연결한다. 고객의 확정 답변 알림 목록의 계산·접근 경계·실제 코드와 자동화 검증을 개발자가 설명하도록 작성했다.

## 문제와 기존 동작

046까지 최종 답변 작성·서명 승인·본인 조회/확인은 구현했지만 최초 공개 답변의 점검 목표와 지연 발견, 접수 UUID를 모르는 고객의 새 결과 발견은 없었다. 내부 열람을 고객 응답으로 세거나 후속 회신/인계 때 시작 시각을 바꾸면 지연을 숨긴다. 고객 확인과 외부 통지도 구분해야 한다.

## 기존 제품과 공식 자료의 접근

확인일2026-10-24. [Zendesk SLA 정책](https://support.zendesk.com/hc/en-us/articles/5600997516058-About-SLA-policies-and-how-they-work)은 최초 답변과 전체 해결 시간을 별도 측정하고, [공식 최초 답변 설명](https://support.zendesk.com/hc/en-us/articles/4408821871642-Understanding-ticket-reply-time)은 공개 답변과 업무 시간을 구분한다. 공개 기록의 최초 시각을 재사용하고 24시간은 이 프로젝트의 자체 점검 값으로 선택했다. Zendesk나 거래소의 내부 구현/목표 수치가 같다고 주장하지 않는다. 알림의 확정 기록 재사용은 같은 DB 안의 원자적 공개 패턴이며 외부 이메일 전송에는 별도 전달 계약이 필요하다.

[미래에셋증권 공개 민원 안내](https://securities.miraeasset.com/mw/mki/mki3062/n10.do)의 14영업일을 가상자산 거래소의 법정 기한으로 적용하지 않는다. 공개 거래소/개인정보·고객확인·STR 누설 금지의 현행 버전과 적용 조건은 [046의 공식 근거](046-customer-final-receipt.md#기존-제품과-공식-자료의-접근)를 연결한다. 업비트 일반 약관 URL은 이번 웹 조회로 열리지 않아 그곳의 처리기한을 확인했다고 주장하지 않는다. 최초 답변 목표·지연 표시는 자체 정책이며 법정 최종 처리·외부 통지·기관/인증 충족이 아니다.

## 선택과 트레이드오프

첫 공개 답변은040 관측 회신의 실제 보존 observedAt과045 승인 결정의 decided_at 중 먼저 확정된 시각이다. 최종 문구가 공개 가능해진 시각을 세며 고객의 GET/확인이나 이메일 도착은 뜻하지 않는다. 목표는 원래 접수 created_at부터 달력24시간이고 인계/새 회신으로 재시작하지 않는다. 과거 접수도 현재 정책 V1로 평가하지만 과거에 이 목표를 약속했거나 적용했다고 소급 주장하지 않는다. 기존 데이터 변환·추정 이력은 만들지 않는다.

고정 달력 시간은 새 의존성·미검증 영업일/공휴일 규칙 없이 재현되지만 휴일/야간 대응 인력이 필요하고 복잡한 조사까지 하루 만에 끝내라는 목표는 아니다. 늦게 답변해도 RESPONDED_LATE는 남고 금융 제한을 풀지 않는다. 시각 이상은 검토 필요로 보이며 운영 호스트의 시간 동기화가 필요하다. 범용 SLA 설정·일정 엔진·실제 법정 기산점은 추가하지 않았다.

기존 PostgreSQL·Spring/JDBC·java.time과 실제 금융/업무 불변 기록을 사용한다. 신규 패키지·승인 종류·복제 표·출력 워커를 추가하지 않는다. 조회량은 후보25개와 커서로 제한하고 목록은 전체 고정 스냅샷이나 영구 처리 위치가 아니다. 원문 없는 목록도 업무 존재·내부 식별자를 노출하므로 현재 역할/본인 경계·필수 감사를 유지한다. 운영자 목록은 최근 추가 인증, 고객 목록은 기존 로그인 세션과 현재 계정 자격을 검사한다.

## 구현과 실제 코드

### 확정 기록에서 본인 알림 발견

[CustomerFinalNoticeService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalNoticeService.java#L25)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Page list(long actor,UUID sid,Cursor after){
    try{return tx.execute(t->{
        requests.customer(actor,sid);
        if(after!=null&&(after.decidedAt()==null||after.finalId()==null||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
        String sql="""
            SELECT r.payload,r.payload_hash,d.request_id,d.review_id,d.decided_at,d.approver_id,d.approver_person,a.acknowledged_at
            FROM customer_exit_review_requests c JOIN customer_review_final_decisions d ON d.review_id=c.id AND d.approved
            JOIN customer_review_final_requests r ON r.id=d.request_id AND r.review_id=c.id
            LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id AND a.user_id=c.user_id
            WHERE c.user_id=?
            """;
        var rows=after==null?jdbc.queryForList(sql+" ORDER BY d.decided_at DESC,d.request_id DESC LIMIT 25",actor):
            jdbc.queryForList(sql+" AND (d.decided_at,d.request_id)<(?,?) ORDER BY d.decided_at DESC,d.request_id DESC LIMIT 25",actor,Timestamp.from(after.decidedAt()),after.finalId());
        var items=new ArrayList<Item>();
        for(var row:rows){
            String raw=(String)row.get("payload");var p=json.readValue(raw,CustomerFinalReviewService.Payload.class);
            if(!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash"))||!CustomerFinalReviewService.POLICY.equals(p.policy())||p.userId()!=actor||!p.id().equals(row.get("request_id"))||!p.reviewId().equals(row.get("review_id"))||p.checker().id()!=((Number)row.get("approver_id")).longValue()||!p.checker().person().equals(row.get("approver_person")))throw new IllegalStateException("고객 알림의 실제 승인 연결 검증 실패");
            items.add(new Item(p.id(),p.reviewId(),((Timestamp)row.get("decided_at")).toInstant(),row.get("acknowledged_at")==null?null:((Timestamp)row.get("acknowledged_at")).toInstant()));
        }
        var last=items.isEmpty()?null:items.getLast();Cursor next=items.size()==25?new Cursor(last.decidedAt(),last.finalId()):null;
        // 목록을 제공해도 명시적 확인 이력은 쓰지 않는다. 감사 실패는 목록 응답도 실패시킨다.
        audit.record(actor,"CUSTOMER_FINAL_NOTICES_LISTED","customer:"+actor,"SUCCESS","METADATA_ONLY_NOT_ACKNOWLEDGED");
        return new Page(items,next,"REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT");
    });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_FINAL_NOTICES","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
}
```

별도 발송 큐/복제 상태 대신 실제 승인 행을 알림의 영속 근거로 사용한다. 현재 고객 세션/소유권을 검사하고 원문 없는 결과 식별자·시각·명시적 확인 여부만 제공한다. 페이지 조회 자체는 확인 영수증을 만들지 않는다.

## 본인 알림 API와 전달 의미

GET `/api/customer/relationship/final-notices?afterDecision=...&afterFinal=...`은 현재 고객만 조회한다. 확정 시각·최종 답변 UUID의 내림차순으로25개를 제공하며 다음 커서는 더 오래된 결과를 가리킨다. 처음부터 갱신해 새 결과를 찾고 영구 소비 위치로 쓰지 않는다. 같은 확정 ID는 같은 알림이며 응답 유실/반복 조회로 별도 발송 기록을 중복 생성하지 않는다. 고객 확인 이력은046의 명시적 확인 API에서만 만든다.

[GitHub 공식 알림 API](https://docs.github.com/en/rest/activity/notifications)의 본인 인증·메타데이터 목록·최근 결과 우선 패턴을 참고한다. GitHub 내부 DB나 발송 구조가 같다고 주장하지 않는다. 여기는 민감 고객 데이터의 no-store·필수 감사를 유지해 조건부 캐시/304를 추가하지 않았다. 잦은 조회는 DB/감사 저장 부하를 만들므로 실제 프론트 구현 시 적절한 갱신 빈도와 운영 조회량 제한이 필요하다. 현재는 새 프론트·폴링 워커·추측성 설정을 추가하지 않는다.

별도 알림 큐/표가 없어 최종 승인과 공개 가능 상태 사이의 이중 쓰기가 없다. 이 API는 같은 플랫폼 DB 안의 영속 알림 발견이며 외부 이메일/SMS/푸시 제공자의 전송·도착을 구현한 것은 아니다. 실제 외부 전달을 도입할 때는 별도 outbox·재시도·제공자 멱등/반송·비공개 템플릿·주소 검증 계약이 필요하다. 현재 목록은 문구를 복호화하지 않고 finalId/reviewId로 기존 본인 문구 조회에 연결한다.

한 고객의 실제2결과로 최신순/엄격 커서와 중복 제외를 검증했다.25개가 가득 찬 한 고객의 대량 알림 경계는 이번 검증에 포함하지 않았다. 기존 접수 일일3개 제한을 시험에 맞춰 풀거나 금융/암호화 이력을 복사하지 않았다. 주의 목록의26개 실제 접수 경계와 이 고객별 알림 검증 범위는 구분한다.

## 검증 시점

최초 관련116개와 전체949개는 알림을 오래된 순으로 제공한 시점의 실행이다. 새 알림 발견을 개선해 최신순/과거 방향 커서로 조정한 현재 알림 API는 신규11개와 bootJar로 다시 실행한 결과를 별도 보존한다. 이후 보안/금융/시장 광역 회귀도 현재 코드에서 실행한다. 실행 시점을 구분하고11개를 고유 사례 수에 더하지 않는다.

## 작성한 테스트

[CustomerFinalNoticeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerFinalNoticeIntegrationTest.java). 실제 업무 검증은 실행 소유 PostgreSQL·실제 서명/감사/MVC를 사용하고, 시간 경계는 고정 Instant로 검증해 실제 시간을 기다리지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `pendingAndRejectedDraftsProduceNoNotice` | 미결·반려 초안은 알림 목록에 나타나지 않음 |
| `actualApprovalPublishesOneStableNoticeWithoutPrivateText` | 실제 최종 승인만 안정적 식별자로 발견, 원문/담당자/해시 미제공 |
| `listAndFinalGetDoNotPretendAcknowledged` | 목록/문구 GET은 확인 영수증을 만들지 않음 |
| `explicitAckUpdatesMetadataWithoutRemovingNotice` | 명시적 확인만 시각에 반영하고 알림 이력은 보존 |
| `otherCustomerAndOperatorCannotReceiveOwnersNotice` | 다른 고객 목록은 비고 운영자에게 고객 목록 권한 없음 |
| `foreignRevokedAndDisabledSessionsCannotList` | 타인·철회 세션 및 비활성 고객 차단 |
| `malformedCursorsAreRejected` | 불완전·음수·미래 커서를 거부 |
| `realTwoResultsUseStrictStableCursorWithoutDuplicateFirst` | 실제 두 결과를 최신순으로 제공하고 마지막 결과 커서로 과거 결과만 조회 |
| `laterTransferAndFinancialRescissionDoNotEraseNotice` | 이후 인계·종료 철회로 과거 확정 알림을 지우지 않음 |
| `auditFailureReturnsNoNoticeAndCreatesNoAcknowledgment` | 감사 실패는 목록 응답 실패, 확인 이력 미생성 |
| `httpCustomerOnlyNoStoreAndCursorValidation` | 실제 MVC 본인/no-store·운영자403/익명401·불완전 커서400 |

## 실행 명령과 결과

선택 JUnit **949개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle49.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

최종 고유 선택 JUnit **1,515개**, 실패·오류·제외0개다. 전체 EDD/보고/종료/고객 업무949개와 광역 KYC150·승인/접근190·MFA42·금융183·시장 연결1개를 통과했다. 이번 세 사이클 신규 **40개**(047 18 + 048 11 + 049 11)는 이미 전체에 포함된다. 최초 선택116개·전체949개 이후 현재 최신순 알림 API는11개/bootJar로 다시 검증했고 이후 광역566개를 현재 코드에서 통과했다. 실행 시점을 구분하며 중복 합산하지 않는다. 기존 프로세스 **15개**도 통과했고 신규 강제 종료 시나리오는0개다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점222개 버전의 알려진 OSV 검출0개·변조 체크섬의 예상 Gradle 종료1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 현재 실제 코드 발췌 **196개**와 별도 역사/범위6개를 소스와 대조했다. 실제 명령·사례·환경·공식 자료·기존 경고와 검증 한계는 확정 증적을 따른다.

외부 Didit 생성·엔진 HTTP·Kafka의 공통 대역과 실제 DB/보안키 암호 서명·감사/MVC를 구분한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용한다. 기존 개발 DB/서버/엔진 코어/프론트 변경·배포 없이 검증하며 새 성능 수치는 없다.

## 한계

24시간은 최종 해결 기한이나 법적 약속이 아니다. 업무 목록의 관측 시각과 실제 제공/고객 확인/외부 전송을 구분한다. 법정 기산점·공휴일·개별 공개 문구의 적정성·외부 이메일/SMS 발송/수신·신원 확인 지원 채널·기관/운영/인증 심사는 별도다. 고객에게 실제 메시지를 발송하거나 외부 서비스를 등록하지 않았다.

저장소 전체 선택을 재현하려면 `python3 scripts/verify_edd.py`를 실행한다. 현재 소스의 전체 선택과 이 문서에 보존한 과거 실행 시점은 구분한다. 실행기는 새 PostgreSQL 컨테이너를 만들고 제거하며 봇0명·격리 외부 주소를 적용한다. 광역 명령과 마지막 알림 집중 명령은 확정 JSON을 따른다.

## 관련 커밋과 증적

- [`27ba0dc`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/27ba0dcbc003e90f6c1269e9f07311c2ea26d38e): 구현과 관련 회귀.

[확정 증적](../reference/customer-final-notices-verification-2026-10-24.json), [046 최종 답변](046-customer-final-receipt.md).
