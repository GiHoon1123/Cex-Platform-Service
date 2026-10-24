# 비밀번호 변경 내용에 결합한 일회 패스키 서명

패스키 등록 고객의 비밀번호 변경에 별도의 일회 서명을 요구한다. 서명을 고객·세션·정확한 새 비밀번호에 결합하고, 변경 원본·서명 근거·키 카운터·세션 회수·메일·감사를 같은 트랜잭션으로 확정한다.

## 문제와 기존 동작

065까지 등록된 고객의 로그인은 패스키를 통과했지만 비밀번호 변경은 그 세션과 현재 비밀번호만 확인했다. 로그인 서명을 다른 업무의 승인으로 재사용하거나 옵션 발급 뒤 변경할 비밀번호를 바꾸는 경로를 막아야 한다. 새 비밀번호의 공개 해시를 저장하면 DB 유출 시 사전 대입 근거가 될 수 있다.

검토 중 고객 인증에서 서버의 키 설정 오류503까지 잘못된 인증 시도로 집계할 수 있던 처리도 찾았다. 서버 장애와 고객의 잘못된 서명을 구분하지 않으면 복구 후에도 고객이 시도 제한에 걸린다.

## 기존 제품과 공식 자료의 접근

[OWASP 거래 승인 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)의 서버 측 내용/상태 검사·업무별 일회성·만료·로그인과 승인 구분을 적용한다. 기존 운영자 [017 내용 결합 승인](017-bound-approval-for-existing-operations.md)의 실제 패턴과062 표준 WebAuthn 검증을 재사용한다. 외부 제품 내부 구현과 같다고 추측하지 않는다.

[PostgreSQL16 CREATE TABLE](https://www.postgresql.org/docs/16/sql-createtable.html)의 지연 외래키는 트랜잭션 끝에 참조 무결성을 검사할 수 있다. 실제 변경 ID·소유자에 연결된 불변 서명 근거를 같은 커밋에 강제한다. 설치된 CddCrypto의 목적 구분 HMAC·독립 키·키 ID와 표준 서명 라이브러리를 사용하고 새 의존성을 추가하지 않았다.

## 선택과 트레이드오프

- 로그인과 별도로 변경 전용 옵션→서명→변경의 절차를 요구한다. 로그인 서명을 업무에 재사용하지 않는 대신 드문 비밀번호 변경 때 패스키 제스처가 하나 더 필요하다. 정상 주문마다 이 단계를 요구하지 않는다.
- 새 비밀번호는 고객/세션을 포함한 목적별 HMAC에 결합한다. 원문/공개 비밀번호 해시를 별도 저장하지 않는 대신 서버 비밀키 보호와 회전 상태에 의존한다. 기존 키를 재사용해 설정을 늘리지 않았지만 키 사고/회전의 영향 범위는 넓어진다. 키 ID가 달라지면 추측 복구하지 않고 옵션을 다시 발급받는다.
- 2분짜리 옵션은 한 세션에 하나만 남긴다. 다시 발급하면 이전 옵션은 쓸 수 없다. 만료는 즉시 거부하나 물리 삭제는 다음 발급의 정리 시점이며 백업까지 즉시 파기한다고 주장하지 않는다. 전역 보존/파기는 B19에서 관리한다.
- 서명 검증·카운터·일회 소비·불변 근거와 비밀번호 변경·모든 세션 회수·보안 예약·감사를 같은 커밋으로 묶는다. 원본 없는 서명 성공만 커밋할 수 없지만 DB 제약/감사/예약 장애는 업무 전체를 실패시킨다.
- 등록하지 않은 고객은 기존 현재 비밀번호 재확인 경로를 사용한다. 이는 고객의 실제 등록 상태 정책이며 과거 형식 호환 계층이 아니다. 미등록 고객이 임의 서명 객체를 보내면400이다. 전체 출금/복구에 대한 MFA 필수 정책을 완료한 단계는 아니다.
- 비밀번호 입력은 옵션 발급과 실제 변경에서 각각 검증한다. 중간 상태 변경을 다시 확인하는 대신 Argon2와 사용자 행 잠금 비용이 두 번 발생한다. 이 비용은 Java의 드문 보안 업무에 한정되며 Rust 코어에는 적용하지 않는다.

## 구현과 API

`POST /api/customer/security/password/options`는 currentPassword/newPassword를 받아 현재 고객·검증된 세션·현재 비밀번호·새 정책을 확인하고 PASSWORD_CHANGE 목적과2분짜리 publicKey 옵션/챌린지 ID를 반환한다. 새 비밀번호/HMAC/키 ID를 응답하지 않는다.

`POST /api/customer/security/password`의 Input은 currentPassword/newPassword/proof이며 proof는 challengeId/publicKey다. 패스키 등록 고객은 정확한 변경에 대한 실제 서명이 있어야 한다. 고객 ID/세션·변경 ID는 클라이언트가 결정하지 않는다. 로그인용·다른 세션·다른 고객·다른 새 비밀번호·만료/소비된 옵션은 사용할 수 없다. 서명 검증 자체는 MFA 시각을 연장하거나 새 로그인 토큰을 발급하지 않는다.

`customer_password_authorizations`는 실제 변경 ID·소유자·챌린지 ID·서명 키 지문을 불변으로 남긴다. 변경 원본과 소유자의 복합 지연 외래키/챌린지 고유 제약을 사용한다. 변경을 만들지 않거나 다른 소유자의 원본에 연결하면 커밋이 실패한다. 원문 서명/새 비밀번호/이전 해시는 장기 근거에 복사하지 않는다. 실행 시 정확한 입력 결합을 검증하며 과거 비밀번호를 복원할 수 있다고 설명하지 않는다.

입력 검증 공통 부분은 private validate로 재사용한다. 잘못된 현재 비밀번호는 사용자 잠금 아래 감사가 커밋된 뒤 오류로 바꾸고, 잘못된 서명은 별도15분5회로 제한한다. 429 응답은 기존 실패 창을 연장하지 않는다. 5xx 키/저장 장애는 고객 인증 실패로 집계하지 않고 그대로 전파한다.

패스키는 서버가 저장한 정확한 변경에 서명했는지 검증하지만 인증기 화면이 새 비밀번호를 직접 표시하는 기능은 아니다. 프론트에서 변경 목적을 명시하고 로그인 제스처와 구분해야 한다. 브라우저/프론트가 완전히 침해된 상황까지 신뢰할 수 있다고 주장하지 않는다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerPasswordChange.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordChange.java#L34), 현재 소스의 부분 발췌.

```java
                UUID id=UUID.randomUUID();
                if(mfa.required(actor))authorization.authorize(actor,sid,input.newPassword(),input.proof(),id);
                else if(input.proof()!=null)throw denied(HttpStatus.BAD_REQUEST);
                user.setPasswordHash(passwords.hash(input.newPassword()));users.saveAndFlush(user);
```

서버가 변경 ID를 만든 뒤 등록된 고객에게만 해당 변경 전용 서명을 검증한다. 검증 실패 전에 해시/세션을 먼저 바꾸지 않는다. 호환 생성자를 남기지 않고 테스트도 세 인자 Input으로 갱신했다.

출처: [CustomerPasswordAuthorization.java](../../src/main/java/dustin/cex/domains/security/CustomerPasswordAuthorization.java#L52), 현재 소스의 부분 발췌.

```java
    private String mac(long actor,UUID sid,String next){
        // 공개 해시를 남기면 DB 유출 시 비밀번호를 사전 대입할 수 있다. 기존 독립 HMAC 키와 목적 구분을 쓴다.
        return crypto.fingerprint("customer-password-change-v1",actor+":"+sid+":"+next);
    }
```

공개 SHA 해시가 아닌 기존 독립 HMAC 키와 목적을 쓴다. 계정/세션/정확한 입력을 결합하므로 다른 변경에 같은 서명을 쓸 수 없다. TypeScript에서 crypto.createHmac을 사용할 때와 마찬가지로 키는 서버 밖으로 공개하지 않는다.

출처: [schema.sql](../../src/main/resources/account/schema.sql#L46), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS customer_password_authorizations (
 change_id uuid PRIMARY KEY,user_id bigint NOT NULL,
 challenge_id uuid NOT NULL UNIQUE,credential_fingerprint varchar(64) NOT NULL CHECK(credential_fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(change_id,user_id) REFERENCES customer_password_changes(id,user_id) DEFERRABLE INITIALLY DEFERRED
);
```

DEFERRABLE INITIALLY DEFERRED는 참조 행을 나중에 만들 수 있게 하되 커밋 때 실제 원본/소유자를 강제한다. Spring TransactionTemplate은 커밋까지 성공해야 결과를 반환한다. TypeScript의 트랜잭션 콜백과 목적은 비슷하지만 Spring은 같은 스레드의 트랜잭션 문맥으로 JPA/JDBC를 참여시킨다. 새 스레드/비동기 작업에 자동 전파된다고 이해하면 안 된다.

## 작성한 테스트와 실행 결과

`python3 scripts/verify_customer_account.py`: 기존85개·미등록 고객 옵션 거부1개·신규 서명 업무14개=100개, 실패/오류/제외0·bootJar 통과. 초기 신규12개 중1개는 캐시 헤더를 정확한 no-store 문자열로 기대해 실패했다. 실제 응답은 Spring Security의 no-store 포함 복합 헤더였으므로 포함 여부로 고쳤다. 제품 캐시 정책을 완화하지 않았다. 503 실패 집계 회귀2개와 실제 HTTP 변경 확인을 보강했다. HTTP 검증 보강 중 식별자 치환 오타로 컴파일이 한 번 실패했고 해당 실행의 테스트는0개였다. 오타를 고치고 현재 소스의100개를 다시 실행했다. 앞선 실행을 중복 합산하지 않는다.

[CustomerPasswordChangeIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerPasswordChangeIntegrationTest.java)의 신규 `unregisteredCustomerCannotRequestPasskeyChangeOptions`는 미등록 고객이 전용 옵션을 받지 못함을 확인한다. 변경/미등록 옵션/실패/시도 제한·전체 회수17개는 실제 상태 정책을 그대로 검증한다.

실행 소유 PostgreSQL16·실제 Argon2·P-256 서명·JWT/MVC·DB 잠금·감사를 사용했다. 지연 외래키가 실제 변경 없는 커밋을 거부하고 키 카운터/챌린지 소비까지 되돌리는지도 시험했다. 봇0명이며 소유 컨테이너를 정리했다. `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고가 남는다.

새 의존성/버전 변경·OSV 조회·성능 측정·실물 기기·외부 메일/배포·엔진/프론트 변경은 없다. 이전 금융/시장/메일 전체 선택 결과를 이번100개로 재사용하지 않는다.

## 한계와 다음 연결

흔한/유출 암호 차단과 유니코드 정책의 남은 범위, 이메일 변경/계정 복구·모든 키 분실의 독립 확인, 출금 내용 결합 인증은 계속 구현한다. 현재 코드만으로 NIST 전체/인증·사업 신고·운영 심사 완료를 주장하지 않는다. 실제 브라우저 확인 화면·TLS/키 보호·보존/파기와 운영 증적은 별도 검증이 필요하다.

## 작성한 테스트 코드


[CustomerPasswordAuthorizationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerPasswordAuthorizationIntegrationTest.java)

- `verifiedLoginAloneCannotChangePassword`: 로그인 서명 완료 세션도 변경 전용 서명 없이는403이며 해시/세션/서명 근거를 바꾸지 않는다.
- `exactSignatureChangesPasswordAndBindsImmutableSource`: 실제 HTTP에서 정확한 서명으로 변경하고 원본 ID/소유자/챌린지/키가 연결된다. 이전 세션은 종료되고 새 비밀번호 로그인도 MFA가 필요하다.
- `proofCannotApplyDifferentNewPasswordAndOriginalStillWorks`: 다른 새 비밀번호는 같은 서명으로 바꿀 수 없으며 원래 내용의 재시도는 성공한다.
- `loginChallengeCannotAuthorizePasswordChange`: 로그인 챌린지 서명을 비밀번호 변경 승인으로 재사용하지 못한다.
- `wrongOriginUserVerificationKeyAndExpiredProofAreRejected`: 잘못된 출처/사용자 확인 없는 서명/다른 키/만료 옵션을 거부하고 원래 자격을 유지한다.
- `newlyVerifiedSessionCannotUsePreviousSessionsOptions`: 새로 검증한 같은 고객 세션도 이전 세션의 옵션을 쓸 수 없다.
- `finalAuditFailureRollsBackSignatureChangeSessionsAndMail`: 마지막 변경 감사 실패는 해시/근거/키 카운터/일회 소비/세션 회수/예약을 모두 롤백하고 같은 정상 서명 재시도가 가능하다.
- `optionsAuditFailureCreatesNoPendingAuthorization`: 옵션 감사 실패는 생성/이전 옵션 정리를 롤백해 성공 옵션을 반환하지 않는다.
- `concurrentUseCommitsOneChangeAndOneBinding`: 동시 사용은 실제 변경1건/불변 서명 근거1건만 확정한다.
- `fiveFailedProofsAreDurableAndRateLimited`: 실패5회를 보존하고 다음 검증은429이며 기존 자격은 유지한다.
- `optionsExposePurposeWithoutPasswordsOrPublicPasswordHashes`: 실제 HTTP는 PASSWORD_CHANGE/no-store를 제공하며 응답/저장 옵션/record 출력에 비밀번호나 공개 비밀번호 해시를 넣지 않는다.
- `sourceMustExistAtCommitAndAuthorizationFactsAreImmutable`: 변경 원본 없는 직접 커밋을 외래키가 막고 소비를 롤백한다. 정상 확정 근거의 수정/삭제/비우기도 거부한다.
- `passwordSetupFailureDoesNotConsumeVerificationBudget`: 비밀번호 HMAC 설정503을6회 주입해도 고객 실패0이며 복구 후 정상 옵션을 받을 수 있다.
- `factorSetupFailureDoesNotConsumeVerificationBudget`: 고객 MFA의 주소 지문 설정503을6회 주입해도 고객 실패0이며 복구 후 예비 등록 옵션이 가능하다.

## 관련 커밋과 증적

- [구현 fc421d4](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fc421d4df4a7db18c521f169834cb7134c094c79)
- [격리 실행 증적](../reference/066-customer-password-authorization-verification-2026-10-25.json)
