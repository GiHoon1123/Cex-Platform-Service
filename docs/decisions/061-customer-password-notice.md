# 비밀번호 변경 이력과 검증 주소 보안 통지

비밀번호 변경의 불변 이력을 본인이 조회하고, 검증된 현재 이메일에는 고정 보안 안내를 예약한다. 변경·세션 회수·메일 예약·감사의 원자성과 실제 SMTP 전달, 주소 변경과 계정 중지 정책을 설명한다.

## 문제와 기존 동작

060에서 변경 사실을 보존했지만 고객이 다시 로그인해 확인할 조회 API와 보안 통지는 없었다. 일반 서비스 답변 알림의 수신 선택을 그대로 적용하면 비밀번호를 변경한 사실까지 숨길 수 있다. 원문 비밀번호나 해시를 메일에 복사할 이유도 없다.

## 기존 제품과 공식 자료의 접근

[OWASP 인증 지침](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)의 민감한 계정 변경 재인증과 [MFA 지침](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)의 계정 변경 통지 원칙을 참고한다. 실제 변경과 외부 전달은 기존 [053 영속 메일 예약](053-durable-customer-mail.md)의 독립 워커 계약을 재사용한다. 이미 설치된 Spring Mail·암호화·검증 주소·불변 변경 행을 활용하며 패키지를 추가하지 않는다. 보안 알림은 자체 보안 정책이며 국내 법률이 특정 문구/24시간 만료를 의무화한다고 주장하지 않는다.

## 선택과 트레이드오프

- 검증된 현재 주소에만 안내한다. 서비스 답변 알림의 선택과 분리해 이상 변경을 알리지만 사용자가 이 안내만 끄는 설정은 제공하지 않는다. 확인하지 않은 주소에는 보내지 않고 변경 이력은 남긴다.
- 계정이 중지되어도 확인된 주소에는 보안 안내를 보낸다. 중지 뒤에도 침해 사실을 알 수 있는 대신 메일량이 늘 수 있다. 다른 업무 알림의 계정 활성 조건은 유지한다.
- 발송 전에 주소가 달라지면 예약을 취소한다. 이미 변경된 옛 주소로 보내지 않는 대신 주소 변경 공격 자체의 안내는 이후 이메일 변경 업무에서 따로 설계해야 한다. SMTP 호출 중 주소 변경까지 원자적으로 취소할 수 있다고 주장하지 않는다.
- 24시간 만료·기존 기본5회 재시도를 사용하고 운영자의 추가 재발송 승인 대상에 넣지 않는다. 오래된 보안 통지의 반복 노출을 제한하는 대신 소진/만료 뒤 보장되는 수신은 없다. 브로커/SMTP 수신 확인은 실제 고객 열람과 구분한다.
- 예약 또는 필수 감사 실패는 비밀번호 변경 전체를 롤백한다. 반쪽 상태를 막는 대신 검증 주소의 암호화 키/예약 저장 장애 때 변경이 실패한다. 외부 SMTP 장애는 커밋된 비밀번호 변경을 되돌리지 않는다.

## 구현과 API

`GET /api/customer/security/password-changes`는 현재 고객의 변경 ID·확정 시각만25개씩 반환한다. 고객 ID/세션 ID/해시/주소/비밀번호를 포함하지 않는다. `afterAt`과 `afterId`를 함께 사용해 동일 시각의 행도 누락하지 않는 내림차순 커서다. 다른 고객/운영자의 세션과 불완전하거나 미래의 커서는 거부한다. 조회는 필수 감사를 남기며 고객이 변경을 승인했다는 영수증을 만들지 않는다.

비밀번호 변경 성공 뒤에는 이전 세션을 회수했으므로 정상 재로그인 후 조회한다. 보안 통지 근거는 같은 트랜잭션의 `customer_password_changes` ID·소유자·정확한 DB 시각이다. 확인 이력이 없는 고객에게 불필요한 외부 메일이나 키 요구를 만들지 않는다. 확인된 주소의 키/무결성 오류는 삼켜서 통지를 빼지 않는다. 제목/본문은 고정 안내와 `/account/security` 링크뿐이며 프론트 화면은 백엔드 완료 뒤 구현한다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerPasswordNotice.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordNotice.java#L21), 현재 소스의 부분 발췌.

```java
        // 확인 사실 자체가 없으면 외부 메일을 만들지 않는다. 확인된 주소의 키/무결성 오류는 삼키지 않는다.
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE user_id=?)",Boolean.class,actor))return;
        var user=users.findById(actor).orElseThrow();if(!recipients.verified(user))return;
        // 원문 비밀번호/해시/기기 정보는 전달하지 않고24시간짜리 고정 보안 안내만 예약한다.
        outbox.enqueue(actor,CustomerMailOutbox.Kind.PASSWORD_CHANGED,id,user.getEmail(),null,at.plusSeconds(86400));
    }
```

실제 변경 행과 호출자의 소유·시각을 앞에서 검사한 뒤 같은 트랜잭션에 예약한다. return은 확인 주소가 없는 정상 상태에만 적용하며 암호화/무결성 예외를 숨기는 폴백이 아니다.

출처: [CustomerMailOutbox.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailOutbox.java#L60), 현재 소스의 부분 발췌.

```java
        // 보안 통지는 현재 검증 주소와 실제 변경 사실에만 결합한다. 계정 중지는 이 안내를 숨기지 않는다.
        if(p.kind()==Kind.PASSWORD_CHANGED)return recipients.verified(u)&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_password_changes WHERE id=? AND user_id=?)",Boolean.class,p.reference(),p.userId());
        if(p.kind()==Kind.MFA_CHANGED)return recipients.verified(u)&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_mfa_key_changes WHERE id=? AND user_id=?)",Boolean.class,p.reference(),p.userId());
        if(p.kind()==Kind.ACCOUNT_RECOVERY)return recipients.verified(u)&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_recovery_events WHERE id=? AND user_id=?)",Boolean.class,p.reference(),p.userId());
        if(!u.isAuthenticationAllowed())return false;
        if(p.kind()==Kind.EMAIL_VERIFICATION)return true;
```

065 이후 키 변경, 069 이후 계정 복구 보안 통지에도 같은 현재 주소/계정 중지 정책을 적용한 현재 발췌다. PASSWORD_CHANGED 분기는 본인 실제 변경·현재 검증 주소를 재확인한다. 계정 활성 검사는 그 아래에 있어 중지 계정의 보안 경고를 숨기지 않는다. 기존 일반 알림은 다음 활성/수신 선택 검사를 계속 통과해야 한다.

출처: [CustomerPasswordHistory.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordHistory.java#L23), 현재 소스의 부분 발췌.

```java
        String sql="SELECT id,changed_at FROM customer_password_changes WHERE user_id=? ";var rows=after==null?jdbc.queryForList(sql+"ORDER BY changed_at DESC,id DESC LIMIT 25",actor):jdbc.queryForList(sql+"AND (changed_at,id)<(?,?) ORDER BY changed_at DESC,id DESC LIMIT 25",actor,Timestamp.from(after.changedAt()),after.id());
        var items=rows.stream().map(r->new Change((UUID)r.get("id"),((Timestamp)r.get("changed_at")).toInstant())).toList();var last=rows.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_PASSWORD_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CHANGE_FACTS_ONLY");return new Page(items,rows.size()==25?new Cursor(last.changedAt(),last.id()):null);
```

복합 비교 (changed_at,id)는 시각이 같은 행을 UUID로 구분한다. List.copyOf는 반환 목록을 변경할 수 없게 하지만 금융 이력의 불변성 자체는 DB 제약과 트리거가 보장한다. TypeScript 객체 타입만으로 런타임 불변성이 생기는 것과 혼동하지 않는다.

## 실행 명령과 결과

Java21·실행 소유 PostgreSQL16·격리 Kafka·로컬 SMTP로 실행했다. `python3 scripts/verify_customer_account.py`47개(세션14·변경16·정책4·이번 보안 통지13), `python3 scripts/verify_customer_mail.py`95개, `python3 scripts/verify_engine_inbox.py`183개, `python3 scripts/verify_market_path.py`1개가 통과했다. 이번 최종 선택326 JUnit의 실패/오류/제외는0이다. `python3 scripts/verify_customer_mail_recovery.py`는 별도6개 프로세스 검증을 통과했다. 서로 다른 실행의 같은 테스트를 중복 합산하지 않는다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`도 통과했다. 성능 수치를 측정한 것은 아니며 의존성 추가/버전 변경은 없다. 이전060의 공통 인증 회귀232개는 별도 시점의 결과로 구분한다. 기존 deprecated/unchecked/JVM 경고가 남는다. 봇은0명이고 기존 로컬 서비스/금융 데이터에 테스트를 연결하지 않았다. 새 OSV 조회·실제 메일/공급사 사용·배포·프론트 구현은 하지 않았다.

## 한계와 다음 연결

메일의 SMTP 수신은 최종 배달/열람 보장이 아니다. 워커 장애와 중복 수신 가능성은053/058 계약을 따른다. 현재 고객 MFA·분실 복구·이메일 변경의 전체 업무는 아직 완료되지 않았으며 다음 백엔드 범위로 이어간다. 외부 키 보호·실운영 모니터링과 법적 보존/파기 검증도 별개다.

## 작성한 테스트 코드


[CustomerPasswordNoticeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerPasswordNoticeIntegrationTest.java)

- `verifiedAddressReceivesSecurityMailWithoutServiceOptIn`: 서비스 답변 알림이 꺼져 있어도 실제 SMTP로 고정 보안 안내를 전달하고 비밀 값은 넣지 않는다.
- `unverifiedAddressHasHistoryButNoExternalMail`: 미검증 주소에는 메일을 만들지 않고 재로그인 뒤 변경 사실은 조회된다.
- `oldSessionCannotReadHistoryButNewLoginCan`: 회수된 세션을 거부하고 새 로그인 세션은 본인 ID/시각만 조회한다.
- `addressChangeBeforeDispatchSuppressesOriginalRecipient`: 현재 주소가 바뀌면 이전 수신 주소의 예약은 SUPPRESSED이며 SMTP0건이다.
- `disabledAccountStillGetsVerifiedSecurityWarning`: 계정 중지 뒤에도 검증 주소의 보안 안내는 SMTP로 전달한다.
- `mailReservationFailureRollsBackPasswordSessionsAndHistory`: 예약 감사 실패는 해시/세션 회수/변경 행/보안 메일을 전부 롤백한다.
- `passwordAuditFailureAlsoRollsBackQueuedMail`: 마지막 변경 감사 실패 때 이미 예약한 메일까지 함께 롤백한다.
- `historyAuditFailurePreventsResponse`: 조회 필수 감사가 실패하면 이력을 성공 응답으로 반환하지 않는다.
- `foreignOrOperatorSessionCannotReadCustomerHistory`: 운영자와 타인 세션으로 고객 이력에 접근하지 못한다.
- `sameTimestampHistoryUsesIdCursorWithoutOmission`: 동일 시각29개를25+4로 중복/누락 없이 읽고 다음 커서는 끝난다.
- `invalidCursorIsRejected`: 불완전/미래 조회 커서는400으로 거부한다.
- `operatorsCannotReleaseExhaustedSecurityMail`: 보안 안내5회 소진을 운영자 추가 재발송 승인으로 우회하지 못한다.
- `actualHttpHistoryRequiresFreshValidCustomerSession`: 실제 HTTP에서 고객 본인 조회200/no-store·운영자403을 확인한다.

## 관련 커밋과 증적

- [구현 31ae57d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/31ae57d4980c3e49987414ff847025a671802cec)
- [격리 실행 증적](../reference/061-customer-password-notice-verification-2026-10-25.json)

063의 [고객 패스키 로그인](063-customer-passkey-login.md)을 등록한 고객은 비밀번호 단계의 대기 세션으로 이 고객 보안 업무를 수행할 수 없다. 실제 서명 후 새 세션이 필요하며, 당시 검증 수치는 각 기록의 실행 시점으로 구분한다.
