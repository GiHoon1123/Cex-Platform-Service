# 현재 비밀번호 재확인과 전체 세션 회수의 원자적 변경

고객이 현재 비밀번호를 재확인한 뒤 새 비밀번호로 바꾸고 모든 세션을 종료한다. 해시·세션·변경 사실·필수 감사의 원자성과 비밀 입력을 로그에 남기지 않는 검증, 명시적인 인증 오류 종류를 설명한다.

## 문제와 기존 동작

현재 비밀번호 변경 경로가 없었다. 활성 JWT만으로 새 비밀번호를 설정하거나 해시만 바꾸면 탈취된 기존 세션이 계속 살아 있을 수 있다. 입력 검증을 DTO 애너테이션에 두면 잘못 입력한 비밀번호 원문이 기본 검증 경고의 거부 값에 포함될 수도 있다. 가입 오류를 Invalid라는 단어로 분류해401로 보내던 기존 오류 처리도 이번 실제 HTTP 회귀에서 발견했다.

## 기존 제품과 공식 자료의 접근

[OWASP 인증 지침](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)의 활성 세션+현재 비밀번호 확인과 [세션 관리](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)의 서버 측 무효화 원칙을 적용했다. [Coinbase 계정 보안](https://help.coinbase.com/en/coinbase/privacy-and-security/data-privacy/how-can-i-make-my-account-more-secure)의 계정/세션 통제는 사용자 기능 참고이며 내부 구현이 같다고 추측하지 않는다.

[NIST SP800-63B-4 최종본](https://pages.nist.gov/800-63-4/sp800-63b.html)의 단일 비밀번호 최소15 코드 포인트와 복잡도 강요를 피하는 접근을 참고했다. 이는 미국 기술 지침 참고이며 국내 법적 의무라고 표시하지 않는다. 이번 구현은 길이·입력 경계·재확인·시도 제한 부분이다. 흔한/유출 비밀번호 차단 목록, 유니코드 정규화 정책, 고객 MFA 등 지침 전체의 충족을 주장하지 않는다. 기존 설치된 Argon2 PasswordService/저장소/감사/세션 정책을 재사용했고 새 패키지나 해시 알고리즘을 추가하지 않았다.

## 선택과 트레이드오프

- 현재 세션과 현재 비밀번호를 요구하고, 성공하면 현재 세션까지 모두 회수한다. 탈취된 자격의 수명을 끊는 대신 고객이 다시 로그인해야 한다. 유지할 세션/자동 로그인 같은 옵션은 만들지 않는다.
- 새 설정은15 코드 포인트 이상·기존 검증기의256 UTF-16 코드 단위 이하로 정했다. 비밀번호 관리자/붙여넣기·공백·유니코드를 허용하고 대문자/숫자/기호 조합을 강요하지 않는다. 길이 입력 부담과 반복 해시 CPU/사용자 행 잠금 비용은 남는다.
- 입력을 서비스에서 검증하고 DTO/요청 record의 출력에서 비밀번호를 생략한다. 기본 필드 오류에 비밀 값을 넣는 경로를 제거하지만 클라이언트가 규칙을 안내해야 한다. 선행/후행 공백을 자르거나 비밀번호를 몰래 바꾸지 않는다.
- 현재 비밀번호 실패는 사용자 잠금 안에서 기록하고15분5회를 적용한다. 실패 감사가 커밋된 뒤 거부 응답으로 바꾼다. 기존 감사 이력을 지우지 않으며 이는 자체 정책이다.

## 구현과 API

`POST /api/customer/security/password`는 currentPassword/newPassword만 받는다. 고객 ID는 인증 속성에서 얻는다. 성공 응답은 변경 ID·확정 시각·requiresLogin=true이며 새 토큰을 발급하지 않는다. 응답이 유실되면 기존 세션으로 자동 재전송하지 않고 정상 로그인 흐름에서 새 비밀번호를 사용한다. 같은 옛 세션으로 변경을 두 번 확정할 수 없다.

CustomerSessionPolicy의 사용자 잠금 안에서 재확인→새 해시 저장→모든 refresh_tokens 회수→customer_password_changes→필수 감사를 처리한다. 원문/이전 해시/새 해시는 변경 이력에 복사하지 않는다. session_id의 고유 제약과 불변 트리거로 한 세션의 변경 사실을 보존한다. 세션 발급/로그인/갱신도 같은 사용자 잠금 규칙을 따른다. 현재 인증을 이미 통과한 모든 외부 업무를 소급 취소한다고 주장하지 않는다.

가입도 같은 새 설정 정책을 사용한다. 로그인은 저장된 자격을 검증하는 기존 계약이며 별도 호환 분기/일괄 해시 변경/이력 마이그레이션을 만들지 않았다. AuthException은 INVALID_SIGNUP/INVALID_CREDENTIALS/INVALID_TOKEN/MFA_REQUIRED/USER_NOT_FOUND의 명시적 종류를 사용하고 HTTP400/401/403/404로 매핑한다. 문구를 검색하는 생성자/분기는 제거했다. 아직 고객의 비밀번호 분실 복구/MFA를 구현한 것은 아니다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerPasswordChange.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordChange.java#L39), 현재 소스의 부분 발췌.

```java
                // 현재 세션도 남기지 않는다. 갱신/로그인이 같은 사용자 잠금을 사용하므로 옛 자격으로 새 세션을 만들지 못한다.
                tokens.revokeAllByUserId(actor,LocalDateTime.now());UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO customer_password_changes(id,user_id,session_id) VALUES(?,?,?)",id,actor,sid);
                Instant at=jdbc.queryForObject("SELECT changed_at FROM customer_password_changes WHERE id=?",Timestamp.class,id).toInstant();
                audit.record(actor,"CUSTOMER_PASSWORD_CHANGED","password-change:"+id,"SUCCESS","ALL_SESSIONS_REVOKED");
                return new Outcome(new Receipt(id,at,true),null);
```

같은 트랜잭션에 해시·세션 회수·불변 변경 행·감사가 참여한다. 마지막 감사/DB 커밋이 실패하면 새 비밀번호만 적용하거나 토큰만 회수하는 반쪽 결과를 반환하지 않는다.

출처: [CustomerPasswordChange.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordChange.java#L34), 현재 소스의 부분 발췌.

```java
                    // 사용자 잠금 안에서 실패까지 커밋한다. 밖에서 예외로 변환해 실패 집계가 롤백되지 않게 한다.
                    audit.record(actor,"CUSTOMER_PASSWORD_CHANGE",target,"DENIED","WRONG_PASSWORD");return new Outcome(null,HttpStatus.FORBIDDEN);
                }
```

실패 결과는 예외로 즉시 롤백하지 않고 Outcome에 담아 트랜잭션 밖에서403으로 바꾼다. 그 뒤 실패 한도 집계가 남아 병렬 요청으로 우회하기 어렵다.

출처: [CustomerPasswordPolicy.java](../../src/main/java/dustin/cex/domains/auth/service/CustomerPasswordPolicy.java#L7), 현재 소스의 부분 발췌.

```java
    public boolean accepts(String value){
        // 고객에게 아직 다중 인증이 없으므로 길이는15개 코드 포인트 이상으로 정한다.
        // 상한은 기존 검증기의256 UTF-16 코드 단위와 맞춰 과도한 해시 입력을 제한한다.
        if(value==null||value.isBlank()||value.length()>256||value.codePointCount(0,value.length())<15)return false;
        // 정상 이모지의 서로게이트 쌍은 허용하되 단독 서로게이트와 제어 문자는 받지 않는다.
        return value.codePoints().noneMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF));
    }
```

String.length는 TypeScript처럼 UTF-16 코드 단위다. codePointCount는 서로게이트 쌍을 한 코드 포인트로 센다. 정상 이모지는 허용하지만 단독 서로게이트/제어 문자와 과도한 입력은 받지 않는다. 공백/문자열을 정규화하거나 자르지 않는다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 현재34개(세션14·변경16·정책4), 실패/오류/제외0·bootJar 통과. 초기32개 중 잘못된 가입 응답이401인1개가 실패했다. 문구 추측을 명시적 실패 종류로 교체하고 HTTP 경계2개를 더했다. 추가 테스트 작성 중 Lombok boolean 접근자를 getMfaRequired로 잘못 사용한 컴파일 오류도 isMfaRequired로 정정했다. 현재34개를 재실행해 통과했다.

`python3 scripts/verify_bound_approvals.py`190개·`python3 scripts/verify_operator_mfa.py`42개를 별도 실행 소유 DB에서 통과했다. 가입/공통 인증 예외 변경과 운영자 갱신/MFA에 대한 회귀다. 고유 선택266개이며 이전 실행은 중복 합산하지 않는다. 실제 Argon2/DB/JWT/MVC/감사와 동시 요청을 사용한다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다.

봇0명·격리 엔진/Kafka 대역·새 PostgreSQL16을 사용하고 소유 컨테이너를 정리했다. 외부 메일·배포·프론트 구현·새 성능 측정은 하지 않았다. 새로운 의존성/시스템 전역 Git 설정 변경도 없다.

## 한계와 다음 연결

신규 설정 정책의 유출/흔한 암호 차단과 고객 MFA/분실 복구는 전체 백엔드 보안 검토 전에 이어갈 범위다. 이 단계의 변경 사실은 저장했지만 고객별 조회/보안 통지는061에서 연결한다. TLS·키 보호·운영 모니터링·보존/파기와 사용자별 금융 업무의 인증 경계는 전체 검토 대상이다. 구현만으로 인증/신고·지침 전체를 충족했다고 주장하지 않는다.

## 작성한 테스트 코드


[CustomerPasswordChangeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerPasswordChangeIntegrationTest.java)

- `successChangesHashRevokesAllAndRecordsOneEvent`: 새 해시만 유효하고 모든 세션/JWT는 종료된다. 새 비밀번호로 정상 재로그인하고 변경 행은1건이다.
- `wrongCurrentDoesNotChangeHashOrSessions`: 현재 비밀번호 오류는403이며 해시/세션/변경 행은 유지한다.
- `fiveWrongAttemptsAreDurableAndBoundFurtherChanges`: 실패5회가 보존되고 다음 올바른 입력도 한도 창에서429이며 세션은 회수하지 않는다.
- `weakSameAndInvalidInputsAreRejected`: 짧음/상한 초과/제어 문자/동일 비밀번호/누락 입력을 거부하고 기존 자격을 유지한다.
- `auditFailureRollsBackHashSessionsAndEvent`: 성공 감사 실패는 해시/회수/이력을 모두 롤백하고 같은 본인 자격의 재시도가 가능하다.
- `currentSessionCannotChangeAgainAfterSuccess`: 같은 옛 세션으로 두 번째 변경 행을 만들지 못한다.
- `concurrentChangesCommitOnePasswordAndOneEvent`: 두 동시 변경은 하나만 성공하며 변경 사실은1건이다.
- `changedPasswordCannotMintOldRefreshSession`: 변경 전 발급한 refresh token은 새 세션으로 교환할 수 없다.
- `operatorForeignAndDisabledSessionsAreRejected`: 운영자·다른 사람 세션·중지 고객은 거부한다.
- `immutableEventsCannotBeUpdatedDeletedOrTruncated`: 변경 사실의 수정/삭제/전체 비우기를 DB가 거부한다.
- `signupAndChangeUseSameNewPasswordPolicy`: 가입도 동일한15 이상 새 설정 정책을 적용한다.
- `malformedPasswordDoesNotAppearInLogs`: 변경/가입/로그인에 보낸 과도한 비밀 입력은 거부되고 로그와 record 출력에 남지 않는다.
- `actualHttpReturnsReceiptButNoTokens`: 실제 HTTP는확정 영수증/재로그인 안내만 반환하고 비밀번호/해시/토큰을 노출하지 않는다.
- `ambientTransactionIsRejected`: 외부 트랜잭션 참여로 확정 경계를 흐리는 호출을 거부한다.
- `explicitAuthFailuresHaveCorrectHttpStatus`: 가입400·잘못된 로그인401·잘못된 갱신401을 명시적으로 확인한다.
- `pendingOperatorMfaCannotRefreshAndReturnsForbidden`: 운영자 비밀번호 단계만 통과한 세션의 갱신은403이다.

[CustomerPasswordPolicyTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerPasswordPolicyTest.java)

- `lengthBoundariesAreEnforced`: 14/15/256/257 코드 단위/문자 길이와 null 경계를 확인한다.
- `noCompositionRuleOrWhitespaceTrimmingIsAdded`: 소문자 구문·앞뒤 공백을 허용하되 공백만인 값은 거부한다.
- `codePointsAreCountedRatherThanUtf16Units`: 이모지14/15개와 UTF-16 상한128/129개의 차이를 확인한다.
- `controlsAndUnpairedSurrogatesAreRejected`: 제어 문자와 잘못된 단독 서로게이트를 거부한다.

## 관련 커밋과 증적

- [구현 7b35541](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7b35541670ba641b9ce6a221bef119bda288330f)
- [격리 실행 증적](../reference/060-customer-password-change-verification-2026-10-25.json)
