# 최초 패스키 등록을 현재 세션의 최근 이메일 확인에 결합

최초 패스키 등록에는 단순한 과거 이메일 확인과 구분한 이번 세션의 최근 소유 확인을 요구한다. 확인 토큰의 재호출/요청 재생으로 시각·세션을 갱신하지 않고, 현재 상태 API와 불변 근거를 연결한다.

## 문제와 기존 동작

이메일 주소를 한 번 확인한 사실만으로 최초 보안키 설정이 가능했다. 로그인 세션과 현재 비밀번호를 함께 탈취한 사람이 오래된 확인 상태를 이용해 새 키를 등록할 수 있는 범위가 있었다. 등록 후 통지와 별개로 최초 등록 전에 독립된 주소 소유 확인을 최근에 수행했는지도 확인해야 한다.

기존 API는 verified만 제공하고 이미 확인한 주소의 새 확인 요청을 막았다. 보안 수준을 높이려면 확인 이력을 지우거나 최근 시각을 덮어쓰는 대신 새 요청/토큰/확인 사실을 남길 수 있어야 한다.

## 기존 제품과 공식 자료의 접근

[OWASP MFA 지침](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)의 요소 등록/변경 재확인·독립 통지와 [NIST SP800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html)의 기존 인증 수준을 고려한 새 인증기 결합/별도 통지를 참고한다. 최초 계정에 기존 MFA가 없다면 현재 인증 수준을 고려한다는 기준이며, 이메일을 NIST의 피싱 저항 인증기나 AAL2로 인정한다고 설명하지 않는다.

이번10분·같은 세션의 추가 이메일 확인은 비밀번호/세션 동시 탈취 위험에 대한 자체 강화 정책이다. 국내 법률이나 NIST가 이 수치/이메일 절차를 그대로 의무화한다고 주장하지 않는다. 새로운 외부 인증 제품을 붙이지 않고 현재 SMTP·고엔트로피 토큰·HMAC·불변 확인 사실과 고객 세션 정책을 재사용했다.

## 선택과 트레이드오프

- 최초 키에는 현재 비밀번호와 같은 세션에서10분 안에 실제 이메일 토큰을 확인한 사실을 요구한다. 정상 가입/확인 직후 설정에는 단계를 더하지 않지만 오래된 주소 확인이나 다른 기기/새 세션에서는 새 확인이 필요하다.
- 최근 확인을 요청 세션이 아닌 실제 토큰을 확인한 세션에 결합한다. 다른 기기에서 로그인해 메일 링크를 확인했다면 그 기기 세션에서 설정을 계속할 수 있다. 세션 갱신/교체 뒤에는 옛 세션 근거를 자동 승계하지 않는다.
- 이미 확인한 주소도 새 요청 키로 다시 확인할 수 있게 한다. 확인/발송 비용은 늘지만 기존60초/시간당3회와 주소별 경합 제한을 유지한다. 과거 사실을 지우거나 실패 한도를 초기화하지 않는다.
- 소비된 토큰/동일 요청 키의 재생은 기존 사실/영수증만 반환한다. 편의상 최근 시각이나 현재 세션을 붙여 주지 않는다. 반복 확인 API가 성공 상태를 반환해도 최초 키 등록 조건은 충족되지 않을 수 있다.
- 예비 키는 살아 있는 기존 키의 최근 인증을 요구하므로 이 추가 이메일 단계를 반복하지 않는다. 분실 키 폐기·일상 거래에도 불필요한 이메일 단계를 늘리지 않는다. 모든 키 분실을 이메일로 자동 해제하는 우회도 만들지 않는다.

## 구현과 API

`CustomerEmailVerification.confirm`이 새 토큰을 최초로 확인할 때 원본 확인 행과 `customer_email_confirmation_sessions`를 같은 트랜잭션으로 기록한다. 필수 감사 실패는 둘 다 롤백한다. 이미 소모한 토큰의 재호출은 새 결합 행을 만들지 않는다. 과거 확인의 세션을 추측해 채우거나 변환하지 않는다.

`CustomerMailRecipientPolicy.recentlyVerified`는 고객·정확한 주소 MAC·키 ID·현재 세션·DB confirmed_at의10분 범위를 함께 검사한다. 최초 키 옵션과 실제 등록에서 모두 확인한다. 원본 세션이 없거나 시각이 오래된 확인 사실은 일반 주소 확인으로만 남고 최초 키의 최근 소유 증거로 쓰지 않는다.

`GET /api/customer/email`과 확인 응답은 verified/recentlyVerified를 구분한다. verified는 현재 정확한 주소의 확인 상태, recentlyVerified는 이번 세션의 최근 확인 상태다. 화면은 최초 키 설정 전에 이 값을 읽어 필요한 경우에만 이메일을 다시 확인하게 안내할 수 있다. 두 상태는 클라이언트가 권한을 결정하는 입력이 아니며 실제 등록에서 서버가 다시 검사한다. 주소·세션 ID·토큰을 상태 응답에 추가하지 않는다.

이메일은 독립된 연락 채널의 소유 확인이며 패스키 로그인 대체 수단이 아니다. 이미 등록한 고객은 새 이메일 확인으로 기존 키의 필요 여부를 없앨 수 없다. 등록 사실과 현재 키의 생존 조건은063/064대로 유지한다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerEmailVerification.java](../../src/main/java/dustin/cex/domains/mail/CustomerEmailVerification.java#L66), 현재 소스의 부분 발췌.

```java
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id) VALUES(?,?,?,?)",id,actor,row.get("email_mac"),crypto.keyId());
        // 실제 토큰을 확인한 현재 세션을 불변 사실로 남긴다. 소비된 토큰의 재호출로 이 결합을 갱신하지 않는다.
        jdbc.update("INSERT INTO customer_email_confirmation_sessions(challenge_id,session_id) VALUES(?,?)",id,sid);
        audit.record(actor,"CUSTOMER_EMAIL_VERIFIED","email-check:"+id,"SUCCESS","CURRENT_ADDRESS_ONLY");return new Status(true,recipients.recentlyVerified(user,sid));
```

새 확인 원본과 실제 확인 세션을 함께 기록하고 마지막 감사를 요구한다. 소비된 토큰은 이 구간 전에 반환하므로 재호출로 소유 확인을 새 세션/시각에 옮기지 않는다.

출처: [CustomerMailRecipientPolicy.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailRecipientPolicy.java#L16), 현재 소스의 부분 발췌.

```java
    public boolean recentlyVerified(User user,java.util.UUID sid){
        if(user==null||sid==null)return false;
        return jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM customer_email_confirmations c
              JOIN customer_email_confirmation_sessions s ON s.challenge_id=c.challenge_id
             WHERE c.user_id=? AND s.session_id=? AND c.email_mac=? AND c.key_id=?
               AND c.confirmed_at>clock_timestamp()-interval '10 minutes')
            """,Boolean.class,user.getId(),sid,addressMac(user.getEmail()),crypto.keyId());
    }
```

Java의 삼중 따옴표는 여러 줄 SQL 문자열이며 TypeScript 템플릿 문자열처럼 자동 보간하지 않는다. ? 값은 JDBC 인자로 바인딩한다. DB 확정 시각과 현재 서버의 사용자/세션·키 범위를 함께 검사하며 단순 verified 값을 재사용하지 않는다.

출처: [CustomerMfaService.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaService.java#L95), 현재 소스의 부분 발췌.

```java
        // 최초 등록에는 이번 세션의 최근 이메일 확인/현재 비밀번호를, 예비 키에는 살아 있는 기존 키의 최근 서명을 요구한다.
        if(policy.required(user.getId())){
            if(!policy.fresh(user.getId(),tokens.findBySessionId(sid).orElseThrow()))throw denied();
        }else if(!keys.isEmpty()||!recipients.recentlyVerified(user,sid))throw denied();
    }
```

처음 등록하는 계정과 이미 키를 등록한 계정의 필요한 근거를 구분한다. 등록된 계정에서 키를 잃었다고 else로 내려가지 않는다. 최초 등록 불변 사실이 남아 있으므로 이메일 확인만으로 MFA를 해제할 수 없다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 기존100개+신규9개=109개, 실패/오류/제외0·bootJar 통과. 초기 신규9개 중 원본 세션을 추측하지 않는 테스트1개가 다른 고객이 남긴 불변 이력까지 전역으로 세어 실패했다. 현재 고객으로 집계를 제한하고 이전 사실/트리거를 삭제하지 않았다. 상태 API의 현재 세션별 verified/recentlyVerified HTTP 회귀도 추가해109개를 다시 통과했다.

`python3 scripts/verify_customer_mail.py`: 기존 메일/확인/선택 알림/운영자 재발송95개 통과. 이번 고유 선택204 JUnit의 실패/오류/제외0이다. 실제 PostgreSQL16·서명·JWT/MVC·감사·로컬 SMTP를 사용했다. 과거 시각은 실행 소유 DB의 신규 역사 fixture로 만들며 기존 불변 행을 수정하거나 트리거를 끄지 않았다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고가 남는다. 봇0명이며 소유 테스트 자원만 정리했다. 새 의존성/버전 변경·OSV 신규 조회·성능 측정·실물 기기/외부 메일·엔진/프론트 변경·배포는 없다. 과거 금융/시장/프로세스 검증을 이번 수치로 합산하지 않는다.

## 한계와 다음 연결

메일 계정과 현재 비밀번호/세션까지 함께 탈취된 경우를 이 추가 확인만으로 막을 수 있다고 주장하지 않는다. 기존 키의 업무별 확인·모든 키 분실/비밀번호 분실 복구·이메일 변경·출금과 보존/파기 정책은 계속 구현한다. 실제 화면은 최근 확인이 필요한 이유와 재시작 조건을 단순하게 안내해야 한다. 운영 TLS/키 보호/메일 신뢰성·신고/인증 완료는 별도 확인이다.

## 작성한 테스트 코드


[CustomerEnrollmentEmailIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerEnrollmentEmailIntegrationTest.java)

- `historicalVerificationCannotBootstrapFirstKey`: 11분 전 실제 확인 상태는 verified여도 최근 증명은 아니며 최초 키 옵션을 받지 못한다.
- `newConfirmationAllowsFirstKeyWithoutRewritingOldFacts`: 오래된 사실을 보존한 채 새 메일 토큰을 확인하면 실제 최초 키 등록이 성공하고 확인 원본은2건이다.
- `consumedOldTokenDoesNotRenewConfirmationTime`: 소비된 옛 토큰 재호출의 성공 상태는 확인 시각을 갱신하지 않아 최초 키 등록이 거부된다.
- `anotherSessionCannotReuseConfirmedPossession`: 동일 세션 HTTP 상태는 verified/최근 확인 모두true이고 재로그인 뒤에는 verified만true며 최초 키 옵션은 거부된다.
- `idempotentRequestOrConfirmationCannotRebindOldProof`: 동일 요청 키·소비 토큰 재생은 기존 확인 세션을 유지하고 새로운 세션의 최근 증거가 되지 않는다.
- `confirmationAuditFailureRollsBackPossessionBinding`: 확인 감사 실패는 원본/세션 결합을 모두 롤백하고 같은 정상 토큰 재시도 뒤 옵션이 가능하다.
- `possessionSessionBindingIsImmutable`: 실제 확인 세션 근거의 수정/삭제/전체 비우기를 DB가 거부한다.
- `nineMinuteConfirmationIsStillFresh`: 같은 세션의9분 전 정상 확인은 최근 증거로 인정해 옵션을 발급한다.
- `historicalFactWithoutSessionIsNotInferred`: 9분 전 확인이 있어도 실제 확인 세션 원본이 없으면 세션을 추측하지 않고 거부한다.

## 관련 커밋과 증적

- [구현 a1374a8](https://github.com/GiHoon1123/Cex-Platform-Service/commit/a1374a84e5b7b417475e37930feb6e120f7727e1)
- [격리 실행 증적](../reference/067-customer-enrollment-email-verification-2026-10-25.json)
