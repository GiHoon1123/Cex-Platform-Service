# 메일 실패·적체를 원문 없이 점검하는 운영 조회

고객 메일의 실패 소진·만료·임대 만료·발송 지연을 운영자가 원문 없이 발견한다. 읽기 전용 점검과 실제 재발송 권한의 차이, 페이지 진행·필수 감사·현재 운영 인증을 설명한다.

## 문제와 기존 동작

053~055는 발송 예약/재시도/수신 선택을 처리하지만 실패 작업을 운영 API로 점검할 수 없었다. DB를 직접 보거나 암호문을 열어야 한다면 최소 권한과 감사 경계를 유지하기 어렵다. 같은 작업을 조회하면서 선점해 재시도 횟수를 늘리거나 발송을 만드는 동작도 피해야 한다.

## 기존 제품과 공식 자료의 접근

[AWS SQS 문제 메시지 격리](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/capturing-problematic-messages.html)는 최대 시도 뒤 실패를 별도로 보존한다. [재처리 권한 분리](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-configure-dead-letter-queue-redrive.html)를 참고해 조회와 재발송을 구분한다. SQS를 도입하거나 내부 DB 모델을 그대로 복사하지 않는다. [OWASP 로그 지침](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)의 비밀 값 제외와 권한 사용 감사를 적용했다. 법적 보존/비공개 보고·최소 수집의 기존 검토는 [046](046-customer-final-receipt.md)을 따른다. 이번 역할·5분 지연 경계는 자체 정책이며 법정 기준이 아니다.

## 선택과 트레이드오프

- 현재 OPERATOR/APPROVER/AUDITOR와 최근5분 MFA를 확인하고 작업 메타데이터만 제공한다. 암호문/주소/토큰/고객 ID/업무 근거/워커 임대 토큰은 반환하지 않는다. 별도 인력 역할을 늘리지 않지만 운영자 전역 메타데이터 열람 범위가 생기므로 필수 감사를 남긴다.
- 고객/출력 큐를 잠그지 않고 본인 운영자 행만 잠근다. 금융/SMTP 흐름과 분리되지만 DB·CPU 자원 경쟁은 남고 조회25개 제한과 인덱스 저장 비용이 든다.
- 후보25개를 읽은 뒤 주의 항목을 고른다. 빈 페이지가 있어도 후보의 마지막 커서를 반환한다. 상태가 바뀌는 실시간 목록이므로 영속 점검 완료 증명이 아니며 처음부터 주기적으로 다시 읽어야 한다.
- 상태 조회 API를 선택했다. 새 경보 브로커/대시보드/외부 운영자 메시지 발송은 추가하지 않는다. 외부 경보 전달과 실제 담당자의 확인은 아직 별도다.

## 구현과 데이터 흐름

`GET /api/admin/operations/customer-mail`은 주의 후보 목록, `/{id}`는 작업 메타데이터, `/{id}/attempts`는25개씩 불변 시도 이력을 제공한다. 커서는 afterAt/afterId를 함께 받는다. 부분/미래 커서는400, 없는 작업은404, 고객/부적절한 세션은403, 익명은401이다.

EXPIRED는 기한이 지난 미완료/소진 작업, EXHAUSTED는 아직 기한이 남은 시도 소진, LEASE_EXPIRED는 SENDING 임대 만료다. PENDING이 생성 후5분 또는 RETRY가 다음 시도 시각 후5분 지나면 DELAYED다. 이미 SMTP_ACCEPTED/SUPPRESSED인 작업은 과거 기한이 지나도 주의로 되돌리지 않는다. SMTP_ACCEPTED는 수신함 도달/고객 확인을 뜻하지 않는다. 조회·시도 목록의 감사 실패는 응답도 실패하게 한다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMailOperations.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailOperations.java#L30), 현재 소스의 부분 발췌.

```java
        String query="SELECT j.id,j.kind,j.created_at,j.expires_at,d.status,d.attempts,d.lease_until,d.next_attempt_at FROM customer_mail_jobs j JOIN customer_mail_delivery d ON d.job_id=j.id WHERE d.status IN ('PENDING','RETRY','SENDING','EXHAUSTED') ";
        var rows=after==null?jdbc.queryForList(query+"ORDER BY j.created_at,j.id LIMIT 25"):jdbc.queryForList(query+"AND (j.created_at,j.id)>(?,?) ORDER BY j.created_at,j.id LIMIT 25",Timestamp.from(after.createdAt()),after.id());
        var items=new ArrayList<Item>();for(var row:rows){var item=item(row,now);if(item.signal()!=null)items.add(item);}
        // 필터 뒤 결과가 비어도 실제 읽은 후보의 마지막 위치를 반환한다. 전체 목록은 처음부터 새로 점검한다.
        var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(time(last,"created_at"),(UUID)last.get("id")):null;
        audit.record(actor,"CUSTOMER_MAIL_ATTENTION","operator:"+actor,"SUCCESS","METADATA_ONLY");
```

SQL은 메타데이터만 선택한다. 조회 트랜잭션에 출력 큐의 FOR UPDATE를 넣지 않는다. 결과 필터 뒤가 아니라 실제 읽은 후보를 기준으로 커서를 진행해야 빈 페이지 뒤 작업도 찾을 수 있다.

출처: [CustomerMailOperations.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailOperations.java#L23), 현재 소스의 부분 발췌.

```java
    public record Event(UUID id,int attempt,String phase,String outcome,Instant recordedAt) {}
    public record Events(List<Event> items,Cursor next){public Events{items=List.copyOf(items);}}
```

이 DTO에는 임대 토큰이 없다. Java record는 실제 반환 값이며 TypeScript interface와 달리 런타임 직렬화 대상의 필드를 정의한다. 생성자에서 List.copyOf로 외부가 결과 목록을 바꾸지 못하게 한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_mail.py`: 실행 소유 PostgreSQL16 서버 두 개와 외부 전달 없는 루프백 SMTPS, Java21에서 현재70개·bootJar 통과. 신규 운영 조회12개와 기존 메일58개다. 실패·오류·제외0이다. 첫68개 실행을 통과한 뒤 과거/다른 테스트의 작업에 기대지 않도록 빈 페이지 시작 위치를 분리하고 만료 메타데이터/감사 역할 회귀2개를 더해70개를 재실행했다. 불변 업무 이력을 삭제하지 않았다.

KYC/엔진/Kafka는 공통 테스트의 격리 대역이며 이 단계에서 실제 외부 발송/프로세스 강제 종료/성능 측정은 하지 않았다. 기존 deprecated/unchecked/JVM 공유 경고가 있다. 테스트 서버/컨테이너를 정리하고 봇0명·기존 개발 DB/서버·엔진/프론트는 유지했다. 새로운 라이브러리는 추가하지 않았다.

## 한계

일반 API의 현재 목록이며 외부 경보 수신/업무 처리 완료·법정 통지를 입증하지 않는다. 시스템 시계 동기화·대량 이력의 실행 계획/처리 비용·지속 경보 수집은 운영 검증이 필요하다. 조회 권한은 재발송 승인을 포함하지 않는다. 시도 횟수 초기화나 암호문 복호화 API는 만들지 않았다.

## 작성한 테스트 코드


[CustomerMailOperationsIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMailOperationsIntegrationTest.java)

- `exhaustedIsVisibleWithoutRecipientTokenOrLease`: 소진 상태와10개 시도 이력을 확인하되 주소/토큰/임대/고객 식별 정보는 없고 작업 상태는 바뀌지 않는다.
- `activeLeaseIsNotAnErrorButExpiredLeaseIs`: 유효 임대는 정상, 만료 임대는 LEASE_EXPIRED이며 조회가 선점하지 않는다.
- `overdueRetrySignalsDelayWithoutStealingLease`: 다음 시도 후6분 지난 재시도는 DELAYED이고 RETRY를 유지한다.
- `acceptedAndSuppressedAreNotAttention`: 접수 확정/억제 결과는 새 주의 상태가 아니다.
- `emptyFilteredPagesStillAdvance`: 정상 후보26개에서 빈 첫 페이지25개 뒤 커서로 마지막1개까지 진행한다.
- `absentMalformedAndFuturePositionsAreRejected`: 없는 작업404·부분/미래 커서400을 확인한다.
- `customerAndForeignSessionCannotInspect`: 고객과 다른 인물의 세션은 운영 조회를 할 수 없다.
- `disabledOrStaleStaffIsRejected`: 중지 계정 또는5분 지난 MFA는 거부한다.
- `auditFailurePreventsMetadataResponse`: 필수 열람 감사 실패 시 응답도 실패하고 감사 복구 후 정상 조회한다.
- `httpReadIsAuthenticatedAndDoesNotExposeSecrets`: 실제 HTTP 인증/인가·no-store와 수신 주소/토큰 비노출을 확인한다.
- `expiredMetadataIsVisibleWithoutDecryptingPayload`: 암호문 없는 과거 만료 메타데이터 표본도 복호화 없이 EXPIRED로 점검한다.
- `auditorCanReadButNotDispatch`: 감사 역할은 읽을 수 있으나 발송 명령 경로는 없고 PENDING을 유지한다.

## 관련 커밋과 증적

- [구현 ed30af2](https://github.com/GiHoon1123/Cex-Platform-Service/commit/ed30af205a95fd33ca0571205395703d9e7355ea)
- [격리 실행 증적](../reference/056-customer-mail-operations-verification-2026-10-24.json)
