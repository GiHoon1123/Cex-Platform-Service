# 현재 고객 이메일의 일회용 소유 확인

고객의 현재 이메일 주소를 실제 수신자가 확인하도록15분짜리 일회 토큰을 발송한다. 가입 주소의 존재와 소유 확인을 구분하고 확인 결과를 KYC·로그인 복구·거래 승인으로 오해하지 않도록 경계를 설명한다.

## 문제와 기존 동작

User.email은 로그인 식별자로 저장돼 있었지만 실제 메일 수신을 확인한 근거는 없었다. 등록 문자열만 믿고 재검토/최종 답변 알림을 발송하면 잘못된 주소나 나중에 변경한 주소로 보낼 수 있다. 이 단계는 외부 알림 수신 자격을 검증한다. 기존 가입/로그인/거래 활성 조건 전체의 이메일 정책을 변경하지 않았으며, 이를 OWASP 계정 활성 권고 전체를 이행한 것으로 표시하지 않는다.

## 기존 제품과 공식 자료의 접근

[OWASP 이메일 검증 지침](https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html)의 안전한 난수·일회성·기한·주소 비교 정책을 적용했다. [비밀번호 재설정 지침](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)의 토큰 보호/횟수 제한은 검증 토큰에도 참고한다. 비밀번호 재설정 기능을 만든 것은 아니다. 실제 적용은 고엔트로피 난수32바이트와 SHA-256, 현재 계정/세션 결합이다. 메일 소유는 강한 신원 인증 수단이 아니다.

## 선택과 트레이드오프

- JWT 대신 무작위 토큰과 서버 측 단일 소모 행을 택했다. DB 조회/보존 비용을 감수하고 기한·중복·현재 주소를 명시적으로 검사한다. 토큰 원문은 발송용 암호문에만 존재하며 API 응답·검증 표·감사에는 없다.
- 이메일의 현재 문자열을 정확히 비교한다. 로컬 파트의 대소문자/점/별칭을 임의로 합쳐 다른 계정의 검증을 넘기지 않는다. 도메인 표기만 바뀌어도 재확인이 필요할 수 있는 보수적 비용이 있다. 발송 횟수 제한용 지문만 소문자화하므로 같은 수신함 별칭의 우회는 줄이되 일부 다른 주소가 한도를 공유할 수 있다.
- 사용자별 DB 행 잠금과 주소별 PostgreSQL 트랜잭션 advisory lock을 사용한다. 분산 플랫폼 프로세스에서도 같은 주소의 접수를 직렬화하지만 해시 충돌/핫 주소의 대기는 늘 수 있다. 엔진 체결 코어에는 잠금을 추가하지 않는다.
- 새 요청은60초 간격·시간당3회, 잘못된 확인 토큰은15분에5회로 제한한다. 모두 자체 정책이다. 계정 가입 남용/IP 전체 한도/공급사 수신함 평판 보호까지 완성한 통제는 아니다.

## 구현과 API

`POST /api/customer/email/challenges`와 Idempotency-Key는 challengeId/expiresAt만 반환한다. 같은 키 재전송은 원래 접수만 반환한다. `POST /api/customer/email/confirmations`는 challengeId/token을 받는다. GET이나 메일 보안 스캐너는 확인 상태를 바꾸지 않는다. `GET /api/customer/email`은 본인의 현재 주소 검증 여부만 반환한다.

CustomerSessionPolicy는 기존 고객 재검토의 계정 잠금·CUSTOMER·활성 계정·실제 본인 세션·회수·기한 검사를 추출했다. 기존 helper는 이 정책을 호출한다. User에 검증 필드를 추가해 JPA 버전/다른 승인 근거를 바꾸지 않고 불변 challenge/confirmation 행으로 근거를 보존한다. 나중에 주소가 바뀌면 기존 확인은 현재 주소에 적용되지 않는다. 주소 변경 API는 이번 범위에 없다.

## 이해에 필요한 문법과 원리

출처: [CustomerSessionPolicy.java](../../src/main/java/dustin/cex/domains/security/CustomerSessionPolicy.java#L18), 현재 소스의 부분 발췌.

```java
    public User require(long actor,UUID sid){
        // 서비스 직접 호출도 같은 경계를 지킨다. 호출자가 트랜잭션을 열어 잠금의 수명을 결정한다.
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("고객 업무 트랜잭션이 필요합니다");
        var owner=users.lockById(actor).orElse(null);
        var token=sid==null?null:tokens.findBySessionId(sid).orElse(null);
        if(owner==null||owner.getRole()!=UserRole.CUSTOMER||!owner.isAuthenticationAllowed()||token==null||token.getUserId()!=actor||token.getRevoked()||!token.getExpiresAt().isAfter(LocalDateTime.now()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 고객 계정과 본인 세션을 확인하세요");
        return owner;
    }
```

User를 반환하되 트랜잭션 안에서만 사용한다. HTTP 필터만 믿지 않고 직접 서비스 호출에서도 현재 계정과 세션을 검사한다. TypeScript 타입 선언과 달리 User의 JPA 잠금은 DB 트랜잭션 종료까지 유지된다.

출처: [CustomerEmailVerification.java](../../src/main/java/dustin/cex/domains/mail/CustomerEmailVerification.java#L49), 현재 소스의 부분 발췌.

```java
        UUID id=UUID.randomUUID();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires=Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,actor,key,address,rate,crypto.keyId(),digest(id,token),Timestamp.from(expires));
        outbox.enqueue(actor,CustomerMailOutbox.Kind.EMAIL_VERIFICATION,id,user.getEmail(),token,expires);
        audit.record(actor,"CUSTOMER_EMAIL_REQUESTED","email-check:"+id,"SUCCESS","QUEUED_ONLY");return new Receipt(id,expires);
```

SecureRandom은 추측하기 어려운 난수를 만들고 URL-safe Base64는 메일 링크에 담을 문자열을 만든다. digest는 challengeId와 토큰을 묶는다. 도전/메일 예약/필수 감사는 한 트랜잭션이므로 어느 하나 실패하면 모두 롤백한다.

출처: [CustomerEmailVerification.java](../../src/main/java/dustin/cex/domains/mail/CustomerEmailVerification.java#L62), 현재 소스의 부분 발췌.

```java
        if(!MessageDigest.isEqual(digest(id,token).getBytes(StandardCharsets.US_ASCII),((String)row.get("token_hash")).getBytes(StandardCharsets.US_ASCII))){audit.denied(actor,"CUSTOMER_EMAIL_CHECK","email-check:"+id,"WRONG_TOKEN");throw denied(HttpStatus.FORBIDDEN);}
        if(!crypto.keyId().equals(row.get("key_id"))||!addressMac(user.getEmail()).equals(row.get("email_mac")))throw denied(HttpStatus.CONFLICT);
        // 소모된 토큰의 같은 요청은 기존 사실만 반환한다. 새 검증 효과나 다른 주소에 대한 권한은 만들지 않는다.
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE challenge_id=?)",Boolean.class,id))return new Status(true);
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))throw denied(HttpStatus.GONE);
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id) VALUES(?,?,?,?)",id,actor,row.get("email_mac"),crypto.keyId());
        audit.record(actor,"CUSTOMER_EMAIL_VERIFIED","email-check:"+id,"SUCCESS","CURRENT_ADDRESS_ONLY");return new Status(true);
```

해시는 일정 시간 비교 API로 대조한다. 현재 주소와 키를 검사한 뒤 이미 소모된 같은 토큰은 기존 사실만 반환한다. 토큰을 반복 소모해 새 확인/금융 효과를 만들지 않는다. 다른 토큰은 소모 후에도 거부한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_mail.py`: 메일 기반15개+신규 이메일 확인18개=33개, 실패/오류/제외0·bootJar 통과. 신규 테스트는 실제 로컬 SMTPS에서 받은 토큰으로 확인, 한 번의 효과/동시 확인, 해시만 저장, 응답 비노출, 다른 세션·주소 변경·중지·만료·잘못된 토큰/실패 한도, 감사 실패의 예약/소모 롤백, 중복 요청/쿨다운/시간당 한도/동시 예약, 불변 표, 실제 HTTP 인증/역할/POST 경계를 검증한다.

`python3 /tmp/verify-cycle54-customer.py`: 기존 실행 소유 DB 검증기의 선택을 CustomerExitReviewIntegrationTest로 한정해24개·bootJar 통과. 공통 고객 세션 정책으로 분리한 기존 업무도 보존됨을 확인했다. 서로 다른 DB의 고유57개다. 현재 전체 EDD/금융 광역 검증은 다음 통합 단계에서 수행한다.

첫 혼합 실행은55개 중32개가 테스트 fixture 충돌로 실패했다. 메일 설정과 기존 Spring 설정을 같은 DB에 넣으면서 JPA 사용자 ID가 재설정되고 유지된 보안 이력과 충돌했다. 불변 이력을 삭제하거나 트리거를 끄지 않고 기존 고객 검증을 별도 컨테이너로 분리했다. 메일33개/기존24개를 각각 재실행해 통과했다. 기존 deprecated/unchecked/JVM 경고는 남는다. 컨테이너와 로컬 SMTPS는 정리했고 봇0명을 유지했다.

## 한계

이메일 확인은 연락처 소유에 한정된다. 기존 KYC·제한·원장·자산·운영자 승인 상태는 변경하지 않는다. SMTP 공급사 계약/실제 외부 주소 발송/인프라 운영/법적 통지 적합성은 미완료다. 향후 프론트의 링크 처리에는 no-referrer·토큰 로그 제외·제3자 리소스 금지·고객 로그인과 명시적 POST가 필요하다. 요청 한도/확인 기한은 법정 수치가 아닌 자체 정책이다. 보존 표의 키 회전/정보주체 삭제와 감사 최소 보존 운영도 별도 검증 사항이다.

## 작성한 테스트 코드


[CustomerEmailVerificationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerEmailVerificationIntegrationTest.java)

- `onlyHashedTokenAndEncryptedMailAreStored`: 검증 표에는64자 해시만 있고 수신 주소·원문 토큰·응답 노출은 없다.
- `sameKeyReturnsSameReceiptAndOneMail`: 같은 요청 키는 같은 접수증을 반환하고 메일 예약은1건이다.
- `tokenReceivedByActualSmtpConfirmsOnceWithoutChangingUserVersion`: 실제 SMTPS로 받은 토큰을 두 번 확인해도 확인 기록은1건이고 User 버전은 바뀌지 않는다.
- `wrongTokenAndForeignSessionDoNotVerify`: 틀린 토큰·다른 인물 세션은 확인에 실패한다.
- `wrongTokenAfterConsumptionStillFails`: 이미 소모한 뒤에도 다른 토큰으로는 확인할 수 없다.
- `changedAddressInvalidatesChallengeAndVerification`: 현재 주소를 바꾸면 옛 확인 근거와 토큰을 적용할 수 없다.
- `disabledAccountCannotConfirm`: 중지된 계정은 올바른 토큰으로도 확인할 수 없다.
- `expiredChallengeCannotConfirm`: 기한이 지난 도전은410이며 확인 기록을 만들지 않는다.
- `verificationAuditFailureRollsBackTokenConsumption`: 확인 감사 실패는 토큰 소모를 복구하며 같은 토큰으로 재시도할 수 있다.
- `requestAuditFailureRollsBackChallengeAndMail`: 접수 감사 실패는 도전과 메일 예약을 함께 롤백한다.
- `newRequestWithinCooldownIsRejectedButReplayWorks`: 60초 안의 새 요청은429, 원래 키 재전송은 원래 접수다.
- `fifthWrongTokenLimitsFurtherAttempts`: 잘못된 토큰5회 뒤에는 올바른 토큰도 한도 창 동안429다.
- `concurrentConfirmationHasOneEffect`: 동시 두 확인 호출은 모두 기존 사실을 얻되 확인 행은1건이다.
- `httpNeverReturnsTokenAndGetDoesNotConsume`: HTTP 접수는 토큰을 반환하지 않고 GET은 확인 효과가 없으며 인증된 POST만 확인한다.
- `anonymousAndOperatorCannotRequestVerification`: 익명은401, 운영자는403으로 본인 고객 이메일 접수를 막는다.
- `ownershipRecordsAreImmutable`: 확인 도전/소모 기록의 UPDATE/DELETE/TRUNCATE를 거부한다.
- `hourlyLimitUsesHistoricalRequestsWithoutRewritingThem`: 과거 접수3건을 추가한 표본에서 새 요청은429이며 원본 이력을 수정하지 않는다.
- `concurrentNewRequestsReserveOnlyOneMail`: 동시 새 요청 두 개 중 하나만 성공하고 예약은1건이다.

## 관련 커밋과 증적

- [구현 b3d3cbc](https://github.com/GiHoon1123/Cex-Platform-Service/commit/b3d3cbc9a630aebff1dae386374b09773d1b06c0)
- [격리 실행 증적](../reference/054-customer-email-ownership-verification-2026-10-24.json)

2026-10-24 현재 확인 테스트는055의 로그 비노출 회귀1개를 더해19개다. 당시054의18개 결과는 유지하며 컨트롤러 중복 검증 제거와 현재 실행은 [055](055-customer-service-mail.md)를 따른다.
