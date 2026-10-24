# 고객 최초 패스키 등록과 서명 후 세션 교체

고객이 최초 패스키를 등록하고 비밀번호 이후 실제 서명을 검증해 로그인한다. 등록 사실·대기 세션·검증된 세션을 구분하고, HTTP와 고객 업무 정책의 인증 수준 검사 및 동시 소비/감사 실패의 원자성을 설명한다. 예비 키·복구·업무 내용 결합 인증까지 완료한 기록은 아니다.

## 문제와 기존 동작

운영자만 WebAuthn 서명 인증을 사용했고 고객은 비밀번호 세션만 있었다. 고객 MFA를 화면 플래그로만 표시하거나 JWT 역할만 바꾸면 갱신·서비스 호출로 우회할 수 있다. 키를 잃었을 때 공개키 행이 없다는 이유로 비밀번호만 허용해서도 안 된다.

검토 중 기존 로그인 전체 세션 교체를 고객의 비밀번호 단계에 그대로 적용하면 비밀번호만 아는 사람이 패스키를 몰라도 사용자를 계속 로그아웃시킬 수 있음을 확인했다. 등록된 고객은 기존 강한 세션을 유지하고 대기 세션만 바꾸며, 실제 서명 성공 때 전체 세션을 교체하도록 분리했다.

## 기존 제품과 공식 자료의 접근

[Spring Security 패스키](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)의 등록 옵션/자격 등록/요청 옵션/서명 검증을 기존062 컴포넌트로 사용한다. [OWASP MFA](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)의 요소 등록·변경·복구 분리, [세션 관리](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)의 인증 수준 상승 시 세션 교체를 적용한다. [Coinbase 공개 2단계 설정](https://help.coinbase.com/en/coinbase/getting-started/getting-started-with-coinbase/2-step-verification)의 패스키와 예비 키는 사용자 기능 참고다. 내부 구현이 같다고 추측하지 않는다.

설치된 Spring Security7.1.1/WebAuthn4J0.31.9의 실제 타입과 기존 이메일 확인·Argon2·세션·감사를 재사용했다. 고객 RP/정확한 출처는 `customer.webauthn.rp-id`, `customer.webauthn.origin`이며 로컬 기본값은 localhost/http://localhost:3003이다. 운영자 출처에 합치지 않고 HTTPS 정책도062와 같다. 새 의존성과 수제 서명/OTP 구현은 없다. 다음 자체 정책의5분/12시간/시도 한도 등을 국내 법령의 지정 수치라고 주장하지 않는다.

## 선택과 트레이드오프

- 최초 등록은 활성 고객 본인 세션·현재 비밀번호·확인된 현재 이메일을 요구한다. 등록 옵션과 정확한 주소 MAC을 묶고5분 동안만 유효하다. 비밀번호 재확인과 이메일 준비 단계가 필요하지만 탈취된 JWT만으로 자격을 등록하지 못한다.
- 실제 등록 성공은 최초 등록 사실/공개키/챌린지/모든 기존 세션 회수/필수 감사를 같은 트랜잭션에 확정한다. 등록 응답은 재로그인을 요구하며 등록 서명을 로그인 서명으로 재사용하지 않는다. 고객은 등록 뒤 한 번 더 로그인해야 한다.
- 등록된 고객의 비밀번호 단계에는5분짜리 대기 세션만 준다. 금융 API와 고객 보안 업무 권한은 없으며 갱신으로 승격할 수 없다. 실제 서명 성공 때 새12시간 세션을 만들고 모든 이전 세션을 회수한다. 갱신은 최초 서명 시각을 연장하지 않는다.
- 비밀번호 재로그인은 이전 대기만 회수한다. 검증 완료 세션 하나와 대기 하나가 잠시 공존할 수 있지만 비밀번호만 아는 공격자의 반복 로그아웃을 막는다. 기존 미등록 고객/운영자의 정책을 임의로 바꾸지는 않는다.
- 등록 사실은 불변으로 보존하고 현재 공개키 지문도 검사한다. 키 유실·삭제·서명 시각 만료를 비밀번호 인증으로 우회하지 않는 대신 별도 복구가 없으면 접근할 수 없다. 예비 키·복구 업무는 후속으로 완성해야 한다.
- DB에서 등록/세션/키를 현재 값으로 확인한다. 회수와 현재 정책을 반영하는 대신 플랫폼 요청당 조회 비용이 늘어난다. 이 작업은 Rust 계산 코어에 락·대기·인증 로직을 추가하지 않는다.

## 구현과 API

| API | 자격과 결과 |
| --- | --- |
| POST /api/auth/customer-mfa/register/options | 최초 등록 가능한 고객의 현재 비밀번호/확인 주소, 사용자 확인 required 옵션 |
| POST /api/auth/customer-mfa/register | 동일 고객·세션·주소·목적·기한의 챌린지와 실제 등록 서명, 등록 ID/requiresLogin |
| POST /api/auth/customer-mfa/authenticate/options | 등록된 고객의 활성 대기 또는 현재 세션, required 요청 옵션 |
| POST /api/auth/customer-mfa/authenticate | 같은 사용자·세션·목적의 일회 서명, 새 접근/갱신 토큰 |

컨트롤러는 호출자 ID를 입력받지 않고 인증 필터의 값을 사용한다. 인증 전용 API가 authenticated여도 서비스는 CUSTOMER 역할과 현재 세션을 다시 확인하므로 운영자나 다른 고객 세션으로 등록할 수 없다. 현재 입력/인증 자료는 record 출력에 생략하고 기본 비밀 값 필드 검증 로그를 만들지 않는다.

`CustomerMfaPolicy`는 불변 `customer_mfa_enrollments`로 필요 여부를 판단한다. 현재 계정·회수·만료 확인 뒤 HTTP 필터는 미검증 세션을 MFA_PENDING으로만 분류한다. `CustomerSessionPolicy`를 사용하는 고객 업무도 동일 수준을 재확인한다. 등록하지 않은 고객의 모든 금융 업무에 MFA를 강제한 단계는 아니다. 출금·보안 변경의 최신 내용 결합 인증/필수 등록 정책은 다음 작업에서 연결하며, 이미 인가된 진행 중 금융 요청을 소급 취소한다고 주장하지 않는다.

공개키를 등록한 뒤에도 최초 등록 사실을 삭제하거나 갱신하지 않는다. 단기 챌린지와 원본 이력은 구분한다. 고객별 등록·서명 실패는 사용자 잠금 안에서 별도로 보존하고15분5회 이후429로 제한한다. DB/감사 오류는 단순 인증 실패로 숨기지 않는다. 새 비밀번호 로그인이 성공해도 실패 기록을 지우지 않는다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMfaService.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaService.java#L62), 현재 소스의 부분 발췌.

```java
            boolean first=!policy.required(actor);var key=verifier.register(stored.options(),publicKey);
            String fingerprint=OperatorMfaService.sha256Hex(key.getCredentialId().toBase64UrlString());
            UUID event=changes.append(actor,sid,CustomerMfaKeyChanges.Kind.ADDED,fingerprint);
            if(first)jdbc.update("INSERT INTO customer_mfa_enrollments(id,user_id,credential_fingerprint) VALUES(?,?,?)",event,actor,fingerprint);
            // 등록 자체를 로그인 서명으로 간주하지 않는다. 모든 기존 세션을 회수하고 별도로 인증한다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
```

064의 예비 키 분기까지 포함한 현재 발췌다. 모든 추가 사실은 새 불변 변경 행으로 남기고 최초 등록 사실은 첫 등록에서만 만든다. 등록 사실과 기존 세션 회수는 같은 트랜잭션이다. 마지막 감사가 실패하면 공개키까지 롤백하며 강한 로그인 세션을 발급하지 않는다. var는 타입을 없애는 것이 아니라 컴파일러가 타입을 추론하는 Java 문법이다.

출처: [AuthService.java](../../src/main/java/dustin/cex/domains/auth/service/AuthService.java#L55), 현재 소스의 부분 발췌.

```java
            boolean customerMfa=user.getRole()==UserRole.CUSTOMER&&mfa.required(user.getId());
            // 고객은 비밀번호만 아는 사람의 재로그인으로 기존 강한 세션이 회수되지 않아야 한다.
            // 대기는 하나만 남기고 실제 패스키 성공 시 CustomerMfaService가 전체 세션을 교체한다.
            if(customerMfa)tokens.revokePendingByUserId(user.getId(),LocalDateTime.now());
            else tokens.revokeAllByUserId(user.getId(),LocalDateTime.now());
            var pair=create(user,null,null);
            audit.record(user.getId(),"SIGNIN",target,"SUCCESS",customerMfa?"MFA_PENDING_CREATED":"SESSION_REPLACED");
            boolean requiresMfa=user.getRole()!=UserRole.CUSTOMER||customerMfa;
```

등록된 고객은 이전 대기 세션만 바꾼다. 다음 실제 서명 성공의 전체 회수와 구분한다. TypeScript의 Promise 완료 여부만으로 인증 권한을 높이지 않는 것처럼, 대기 JWT 발급과 다중 인증 완료는 별개 상태다.

출처: [CustomerMfaPolicy.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaPolicy.java#L21), 현재 소스의 부분 발췌.

```java
    public boolean satisfied(long actor,RefreshToken session){
        if(!required(actor))return true;
        if(session==null||session.getUserId()!=actor||!session.hasVerifiedMfa()
                ||!session.getMfaVerifiedAt().plusHours(12).isAfter(LocalDateTime.now()))return false;
        // 회수된 키에 결합된 인증 시각만 남아 있어도 현재 자격으로 인정하지 않는다.
        return jdbc.queryForList("SELECT c.credential_id FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.name=?",String.class,Long.toString(actor))
            .stream().anyMatch(id->OperatorMfaService.sha256Hex(id).equals(session.getMfaCredentialFingerprint()));
    }
```

시각뿐 아니라 서명 키 지문과 현재 소유 공개키를 비교한다. 키가 없으면 등록 사실이 남아 있어 비밀번호 인증으로 돌아가지 않는다. stream의 anyMatch는 현재 키 중 실제 서명 키가 있는지를 검사한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 최종64개(기존47·이번 고객 MFA17), 실패/오류/제외0. 초기15개 중 테스트에서 주소 확인을60초 안에 두 번 요청한1개가429로 실패했다. 제품의 재발급 한도를 낮추거나 이력을 바꾸지 않고 주소 변경 시 기존 등록 옵션 거부 시나리오로 고쳤다. 만료 경계까지 추가한16개가 통과했고, 비밀번호만으로 기존 강한 세션을 회수하지 않는17번째 회귀를 더해 다시 통과했다.

`python3 scripts/verify_customer_mail.py`95개, `python3 scripts/verify_operator_mfa.py`44개, `python3 scripts/verify_bound_approvals.py`190개, `python3 scripts/verify_engine_inbox.py`183개, `python3 scripts/verify_market_path.py`1개와 합해 현재 선택577 JUnit의 실패/오류/제외0을 확인했다. 앞선 실행 수치를 중복 합산하지 않는다. 실제 PostgreSQL16·Argon2·P-256 서명·MVC/HTTP·격리 Kafka/엔진 경로를 사용했고 봇은0명이다. 실행 소유 컨테이너만 생성/정리했다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고가 남는다. 의존성 버전 변경·새 OSV 조회·성능 측정·프론트/배포·실물 패스키 기기 검증은 하지 않았다.061의 메일 워커6개 강제 종료 검증은 그 당시 결과이며 이번에 재실행한577 JUnit과 구분한다.

## 한계와 다음 연결

현재는 최초 등록과 이후 로그인이 끝까지 동작하는 한 층이다. 예비 키 등록/폐기·요소 변경 보안 통지·분실/계정 복구·보안 변경/출금의 내용 결합 추가 인증은 계속 구현할 범위다. 등록 전 고객에게 모든 업무를 막는 전환/호환 레이어를 추가한 것은 아니다. 현재 출금 전체 요구사항을 충족했다고 표시하지 않는다. 운영 도메인의 실제 HTTPS·기기/동기화 패스키·보존/파기와 신고/인증의 외부 확인은 별개다.

## 작성한 테스트 코드


[CustomerMfaIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMfaIntegrationTest.java)

- `firstRegistrationNeedsVerifiedAddressAndCurrentPassword`: 미확인 주소/틀린 현재 비밀번호는 등록 옵션을 받지 못하고 등록 사실은 없다.
- `registrationRevokesAllAndNextPasswordLoginIsPending`: 등록 직후 모든 기존 세션을 회수하며 새 비밀번호 로그인은5분 대기/주문403·고객 세션 정책 거부다.
- `actualSignatureRotatesSessionAndOpensCustomerHttpOnly`: 실제 서명 뒤 세션이 교체되고 고객 조회200/운영자403·옛 JWT401이다.
- `registrationRejectsForeignOriginAndMissingUserVerification`: 잘못된 출처/사용자 확인 없는 등록은 공개키/등록 사실을 남기지 않는다.
- `authenticationRejectsForeignOriginAndMissingUserVerification`: 잘못된 출처/사용자 확인 없는 인증은 세션을 승격하지 않는다.
- `foreignKeyHandlePurposeAndExpiredChallengeAreRejected`: 다른 서명 키/사용자 handle/등록 목적 혼용/만료 옵션을 거부한다.
- `concurrentSignatureConsumptionCommitsOnce`: 동시 서명 소비는 하나만 성공하고 성공 감사는1건이다.
- `authenticationAuditFailureRollsBackCounterChallengeAndSession`: 인증 감사 실패는 키 카운터/챌린지 소비/세션 회수를 롤백하고 정상 재시도가 가능하다.
- `registrationAuditFailureRollsBackKeyFactAndRevocation`: 등록 감사 실패는 키/불변 사실/회수를 함께 롤백한다.
- `addressChangeInvalidatesEnrollmentOptions`: 등록 옵션 발급 뒤 주소가 바뀌면 기존 옵션을 등록할 수 없다.
- `fiveFailuresAreDurableAndBoundFurtherProofs`: 5회 오류를 보존하고 올바른 입력도 같은 창에서는429다.
- `pendingRefreshFailsAndVerifiedRefreshDoesNotExtendMfaTime`: 대기 토큰 갱신은 거부하고 검증된 갱신도 최초 서명 시각을 연장하지 않는다.
- `operatorForeignAndDisabledSessionsCannotEnroll`: 운영자/타인/중지 계정 세션으로 등록할 수 없다.
- `enrollmentFactIsImmutableAndMissingKeyDoesNotRestorePasswordAccess`: 등록 행 수정/삭제/비우기를 거부하고 키 유실 뒤에도 MFA가 필요하며 HTTP 접근은403이다.
- `actualHttpOptionsRequireAuthenticationAndDoNotExposePassword`: 익명401·운영자403·본인200/no-store이며 응답/record에 비밀번호가 없다.
- `expiredPendingSessionAndOldMfaTimeCannotAuthorizeCustomer`: 만료 대기 세션·12시간을 넘긴 서명 시각은 API/갱신 권한을 얻지 못한다.
- `passwordOnlyReloginPreservesStrongSessionUntilActualSignature`: 비밀번호 재로그인 두 번은 이전 대기만 회수하며 기존 강한 세션은 실제 새 서명 성공 때 회수한다.

## 관련 커밋과 증적

- [구현 686c502](https://github.com/GiHoon1123/Cex-Platform-Service/commit/686c5020cd8e9b8e185d5bf7fbc10d04baf59bc1)
- [격리 실행 증적](../reference/063-customer-passkey-login-verification-2026-10-25.json)

064에서 [예비 키·분실 키 폐기](064-customer-passkey-devices.md)를 추가했다. 최초 등록/로그인 검증 수치는063 당시 결과이며 위 현재 발췌는064 소스와 대조한다.

067에서 [최초 등록의 최근 이메일 확인](067-customer-enrollment-email.md)을 강화했다. 최초 등록은 같은 세션에서10분 내 실제 확인한 근거가 필요하며 기존 키를 가진 계정은 기존 패스키의 최근 인증을 사용한다.
