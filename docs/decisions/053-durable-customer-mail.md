# 고객 메일의 영속 예약·SMTP 출력과 결과 불명 처리

고객 업무가 확정돼도 프로세스 장애 때문에 외부 메일 예약이 사라지지 않도록 영속 발송함을 추가했다. 대상 독자는 플랫폼의 업무 트랜잭션과 외부 출력 경계를 설명하려는 개발자다. 이 단계는 발송 기반이며 고객 발송 API는 다음 단계에서 연결한다.

## 문제와 기존 동작

기존 049~052의 알림은 로그인 후 보는 자체 알림이다. 이메일 접수·재시도·결과 불명·주소/토큰 보존을 처리하는 외부 출력 계약이 없었다. 업무 트랜잭션에서 SMTP를 호출하면 외부 대기 동안 DB 잠금을 점유하며 SMTP 접수와 DB 커밋을 원자적으로 묶을 수도 없다.

## 기존 제품과 공식 자료의 접근

[AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)는 업무 데이터와 출력 예약을 같은 트랜잭션으로 저장하고 중복 가능성을 처리한다. [Spring Boot 이메일](https://docs.spring.io/spring-boot/reference/io/email.html)은 JavaMailSender와 유한 연결/읽기/쓰기 제한을 제공한다. 조회한 문서 표시는4.1.2지만 실제 프로젝트는 기존4.1.1을 유지한다. [GreenMail](https://greenmail-mail-test.github.io/greenmail/)2.1.14의 로컬 SMTPS로 외부 전달 없이 실제 MIME/TLS를 검증했다. 공개 자료로 내부 거래소 메일 구조를 추측하지 않는다.

## 선택과 트레이드오프

- 동일 PostgreSQL 트랜잭션의 발송함을 선택했다. DB 예약/임대/감사 저장 비용을 감수하며 업무 확정과 예약 유실 사이 틈을 없앤다. 별도 브로커·메일 제품은 현재 단계에 추가하지 않는다.
- 전용 워커가 DB 잠금 밖에서 SMTP를 기다린다.60초 임대·최대5회·30/60/120/240초 재시도로 무한 발송을 제한한다. SMTP 접수 후 확정 실패는 중복 메일을 만들 수 있으며 동일 Message-ID가 공급사 중복 제거를 보장하지 않는다.
- 수신 주소/토큰은 기존 CddCrypto의 AES-GCM과 목적별 HMAC으로 보호한다. 키 관리 재구현은 피하지만 고객 데이터 키와 장애/유출 범위를 공유하는 비용이 있다. 별도 키 운영·회전 검증은 미완료다.
- 불변 작업/시도 감사와 삭제 가능한 암호문을 나눈다. 종료 건의 만료 후 하루가 지나면100개씩 암호문만 삭제한다. 자체 최소 보존 정책이며 금융기록의 법정 보존기간이라고 주장하지 않는다.

## 구현과 관련 코드

`customer_mail_jobs`는 근거/기한, `customer_mail_payloads`는 암호문, `customer_mail_delivery`는 현재 임대, `customer_mail_attempts`는 불변 시도다. SKIP LOCKED로 동시 워커가 같은 예약을 갖지 않도록 하며 늦은 워커 결과는 새 임대를 덮어쓰지 않는다. 감사 실패도 같은 트랜잭션을 롤백한다. 기존 금융 데이터는 수정하지 않는다.

## 이해에 필요한 문법과 원리

출처: [CustomerMailOutbox.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailOutbox.java#L46), 현재 소스의 부분 발췌.

```java
    public Lease claim(){return tx.execute(t->{
        crypto.requireConfigured();var rows=jdbc.queryForList("SELECT d.job_id,d.attempts FROM customer_mail_delivery d JOIN customer_mail_jobs j ON j.id=d.job_id WHERE d.next_attempt_at<=clock_timestamp() AND (d.status IN ('PENDING','RETRY') OR (d.status='SENDING' AND d.lease_until<clock_timestamp())) ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED");
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("job_id");int attempt=((Number)row.get("attempts")).intValue();UUID lease=UUID.randomUUID();
        if(attempt>=5){jdbc.update("UPDATE customer_mail_delivery SET status='EXHAUSTED',last_reason='LEASE_EXHAUSTED',lease_id=NULL,lease_until=NULL WHERE job_id=?",id);event(id,lease,attempt,"RESULT","EXHAUSTED");audit.record(null,"CUSTOMER_MAIL_EXHAUSTED","mail:"+id,"SUCCESS","LEASE_EXHAUSTED");return null;}
        jdbc.update("UPDATE customer_mail_delivery SET status='SENDING',attempts=attempts+1,lease_id=?,lease_until=clock_timestamp()+interval '60 seconds' WHERE job_id=?",lease,id);
        event(id,lease,attempt+1,"CLAIMED","CLAIMED");audit.record(null,"CUSTOMER_MAIL_CLAIMED","mail:"+id,"SUCCESS","LEASED");return new Lease(id,lease,attempt+1);
    });}
```

TransactionTemplate.execute는 콜백 반환/예외 시 커밋/롤백한다. TypeScript Promise와 달리 이 콜백은 DB 동기 호출이며 반환 후 SMTP를 수행해 DB 잠금을 해제한다. SKIP LOCKED는 플랫폼 출력 큐에만 적용한다. Rust 체결 코어에는 변경이 없다.

출처: [CustomerMailDispatcher.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailDispatcher.java#L9), 현재 소스의 부분 발췌.

```java
    public boolean dispatchOnce(){
        if(!transport.configured())return false;transport.requireReady();var lease=outbox.claim();if(lease==null)return false;
        String outcome;try{var p=outbox.open(lease.id());if(!outbox.eligible(p))outcome="SUPPRESSED";else{transport.send(p);outcome="SMTP_ACCEPTED";}}catch(RuntimeException e){outcome="SMTP_UNKNOWN";}
        // SMTP가 접수한 뒤 감사/DB 확정에 실패하면 임대 복구 시 중복 전송될 수 있다. 같은 메시지 ID와 일회 토큰을 유지한다.
        outbox.finish(lease,outcome);return true;
    }
```

영속 임대를 얻은 뒤 복호화·현재 계정/주소 검사를 수행한다. SMTP 예외는 미발송 증거가 아니므로 SMTP_UNKNOWN이다. finish의 감사 실패를 삼키지 않아 임대 복구가 가능하다.

## 실행 명령과 결과

`python3 scripts/verify_customer_mail.py`: Java21, 새 PostgreSQL16 tmpfs 컨테이너와 루프백 SMTPS, JUnit15개 통과·실패/오류/제외0, bootJar 통과. 암호화·업무 롤백·동일 근거 재예약·실제 SMTP 고정 문구/메시지 ID·계정 중지/주소 변경 억제·동시 임대·만료/늦은 결과·감사 롤백·5회 제한·불변 이력·실제 중복 전송·암호문 삭제 조건과 삭제 감사 롤백을 검증했다.

첫 실행12개 중 SMTP 관련2개가 실패했고, 다음 실행은 테스트 수신함 setUser 인수 오류로 컴파일 실패했다. 로컬 테스트 인증서의 호스트 검사와 수신함 등록을 수정한 뒤 현재15개를 재실행해 통과했다. 로컬 신뢰 예외는 루프백 테스트에만 허용하며 외부 호스트는 TLS 호스트 검사·발신 인증을 강제한다.

`python3 scripts/audit_dependencies.py`: 실제229개 패키지 버전을 OSV와 대조해 조회 시점 알려진 검출0개. `python3 scripts/verify_dependency_integrity.py`: 임시 복사본 체크섬 변조의 예상 Gradle 종료1 차단, 검증기 종료0. 버전 잠금/검증 메타데이터 갱신, GreenMail의 번들형 Jakarta Mail 중복 구현 제외. JUnit4.13.2는 GreenMail 테스트 전이 의존성이며 운영 클래스패스에는 없다. 조회가 운영 안전성 보장은 아니다.

기존 deprecated/unchecked/JVM 공유 경고가 있다. 새 컨테이너와 SMTP 서버는 정리했고 봇0명·엔진/Kafka 대역을 사용했다. 기존 개발 서버/DB는 재시작하지 않았다.

## 운영 경계와 남은 조건

`customer-mail.dispatch-enabled=true`일 때만 전용 스케줄러를 만들며 기본은 꺼져 있다. spring.mail.host/port/protocol/username/password와 TLS 설정, customer-mail.from/public-base를 검증한다. debug를 끄고 timeout은 각각5초로 제한한다. 실제 공급사 계약·운영 자격증명·SPF/DKIM/DMARC·도메인·전달 실패/바운스·수신함 도달/고객 열람·법정 통지·보존 운영 검증은 완료하지 않았다. 메일 토큰을 조회 문자열로 포함하는 향후 화면에는 no-referrer·로그 제외·제3자 리소스 금지·명시적 POST 확인이 필요하다. 현재 프론트 화면은 구현하지 않았다. SMTP_ACCEPTED는 메일 서버 접수만 뜻한다.

## 작성한 테스트 코드


[CustomerMailOutboxIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMailOutboxIntegrationTest.java)

- `encryptedQueueDoesNotExposeAddressOrToken`: 메타데이터·toString에 주소/토큰이 없고 암호문을 정상 복호화한다.
- `producerRollbackLeavesNoDeliverableJob`: 업무 예외로 롤백하면 발송 가능한 예약이0건이다.
- `duplicateBusinessReferenceReplaysButChangedPayloadFails`: 같은 근거/본문은 같은 예약 ID, 변경한 토큰은 충돌한다.
- `actualSmtpsAcceptsFixedTemplateAndStableMessageId`: 로컬 SMTPS가 고정 링크·토큰·원래 Message-ID를 실제 수신하고 SMTP_ACCEPTED를 남긴다.
- `accountDisableSuppressesBeforeAnySmtp`: 계정을 중지하면 발송은0건이고 SUPPRESSED다.
- `changedRecipientSuppressesBeforeAnySmtp`: 예약 후 주소 변경은 발송0건·SUPPRESSED다.
- `concurrentClaimHasOneOwner`: 두 워커가 동시에 시도해도 임대 소유자는 한 명이다.
- `expiredLeaseCanBeReclaimedButOldOwnerCannotOverwrite`: 임대 만료 후 새 소유자가 생기며 이전 결과는 현재 상태를 덮어쓰지 않는다.
- `smtpResultAuditFailureRollsBackAndSameLeaseCanRetry`: 결과 감사 실패는 상태 변경을 롤백하고 같은 임대로 재확정할 수 있다.
- `retryStopsAtFiveClaimsWithoutFalseSuccess`: 다섯 번 결과 불명 뒤 EXHAUSTED가 되고 성공으로 표시하지 않는다.
- `immutableJobsAndAttemptsRejectUpdateDeleteTruncate`: 예약과 시도의 UPDATE/DELETE/TRUNCATE를 DB가 거부한다.
- `expiredClaimAfterActualSmtpCanDuplicateSameMessageId`: 실제 SMTP 접수 뒤 임대 만료는 같은 Message-ID의 두 메일을 만들 수 있음을 확인한다.
- `activeOrRecentPayloadCannotBePurged`: 진행 중 또는 최근 만료 암호문은 삭제할 수 없다.
- `terminalExpiredPayloadCanBePurgedWithoutDeletingAuditOrJob`: 종료·만료 후 하루가 지난 암호문만 삭제되고 원래 예약/상태는 남는다.
- `purgeAuditFailureRestoresCiphertext`: 삭제 감사 실패는 암호문 삭제도 롤백한다.

## 관련 커밋과 증적

- [구현 96d22a7](https://github.com/GiHoon1123/Cex-Platform-Service/commit/96d22a71c1c99a6792c2e8731f1f99df9d620e86)
- [격리 실행 증적](../reference/053-durable-customer-mail-verification-2026-10-24.json)
