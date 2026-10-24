# 고객 예비 패스키와 분실 키 폐기의 원자성

고객이 예비 패스키를 등록하고 다른 키로 인증해 분실 키를 폐기한다. 마지막 키 보호, 최근 서명·소유 범위, 불변 변경 사실과 모든 세션 회수·감사의 원자성을 설명한다. 분실 복구나 외부 보안 통지까지 완료한 기록은 아니다.

## 문제와 기존 동작

063은 최초 키 하나와 서명 로그인을 연결했다. 기기를 잃으면 비밀번호만으로 돌아가지 않는 대신 접근할 수 없었고 예비 키 추가/분실 키 폐기가 없었다. 키를 지워도 과거 등록·폐기 사실은 지워서는 안 된다. 공개키 ID를 다른 고객의 ID로 바꾸거나 현재 인증한 키만으로 그 키 자체를 제거하는 경로도 막아야 한다.

## 기존 제품과 공식 자료의 접근

[Coinbase 2단계 설정](https://help.coinbase.com/en/coinbase/getting-started/getting-started-with-coinbase/2-step-verification)의 주 키+예비 키와 [OWASP MFA](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)의 요소 변경 재인증을 참고한다. 기존 운영자 [014](014-operator-webauthn-mfa.md)/[015 복구·키 수명](015-operator-recovery-and-access-lifecycle.md)의 실제 코드 패턴과063의 현재 고객 세션 정책을 대조했다. 공개 안내만으로 해당 거래소 내부 저장·복구 구조가 같다고 주장하지 않는다.

설치된 Spring Security7.1.1의 `Webauthn4JRelyingPartyOperations` 실제 타입/등록 바이트코드를 확인했다. 라이브러리는 이미 같은 credential ID의 재등록을 거부하며, 직접 중복 자격 저장 로직을 새로 만들지 않았다.062의 표준 검증과 JDBC 저장소를 그대로 재사용했다. 새 의존성이나 수제 암호 구현은 없다.

## 선택과 트레이드오프

- 추가 등록은 현재 비밀번호·확인된 주소와5분 안의 살아 있는 기존 키 인증을 요구한다. 기존 로그인으로 바로 새 키를 심지 못하지만 오래된 세션에서는 패스키 확인을 다시 해야 한다. 등록 확정 순간에도 최근 인증/확인 주소/최대5개를 다시 검사한다.
- 현재 서명한 키와 다른 분실 키만 제거한다. 마지막 키를 지우는 자체 해제 버튼은 제공하지 않고 복구 업무로 구분한다. 기존 키를 모두 잃으면 이 경로로 복구할 수 없는 제약이 있다.
- 키 추가/폐기 후 모든 세션과 진행 중 등록/인증 권한을 회수한다. 유실·탈취 세션을 끊는 대신 정상 고객도 다시 로그인해야 한다. 고객의 키 관리 화면에서 이 결과를 안내해야 한다.
- 현재 공개키는 실제 폐기하되 ADDED/REVOKED의 불변 원본과 최초 등록 사실은 남긴다. 추적 가능성을 얻는 대신 보존/개인정보 관리 비용이 있다. 과거 금융·감사를 삭제하거나 최초 사실을 새 키로 덮어쓰지 않는다.
- 이전부터 저장된 최초 등록 사실을 가공해 과거 키 변경 행을 새로 만들지 않는다. 현재 신규 업무부터 변경 사실을 함께 기록하고 기존 최초 사실은 자체 기록으로 보존한다. 호환 API나 이력 변환 경로를 추가하지 않는다.

## 구현과 API

기존 `POST /api/auth/customer-mfa/register/options`, `/register`가 최초/예비 등록을 처리한다. 최초는 확인 주소+현재 비밀번호, 예비는 추가로 최근 기존 키 인증을 요구한다. 등록 때마다 `customer_mfa_key_changes`에 새 ID를 만들고 첫 등록만 `customer_mfa_enrollments`를 생성한다. 성공은 requiresLogin=true를 반환한다.

`GET /api/auth/customer-mfa/credentials`는 본인 지문·표시 이름·실제 생성/최근 서명 시각만 반환한다. 공개키/원본 credential ID/인증 자료/고객 ID/세션 ID를 넣지 않는다. 이름은 사용자가 정한 표시 값이며 실제 기기 모델이나 실명을 검증한 값이라고 표현하지 않는다.

`DELETE /api/auth/customer-mfa/credentials/{fingerprint}`는 본인 검증 세션·최근 서명·현재 살아 있는 다른 키·2개 이상 조건을 같은 사용자 잠금 아래 검사한다. 키 삭제→불변 폐기 사실→전체 세션 회수/단기 챌린지 제거→필수 감사가 하나의 트랜잭션이다. 예외를 삼켜 일부만 확정하지 않는다. 두 동시 폐기는 하나만 성공하고 뒤 요청의 회수된 세션은 거부된다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMfaService.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaService.java#L92), 현재 소스의 부분 발췌.

```java
    private void requireRegistration(User user,UUID sid){
        var keys=verifier.keys(user.getId());
        if(keys.size()>=5||!recipients.verified(user))throw denied();
        // 최초 등록에는 현재 비밀번호/확인 주소를, 예비 키에는 추가로 살아 있는 기존 키의 최근 서명을 요구한다.
        if(policy.required(user.getId())){
            if(!policy.fresh(user.getId(),tokens.findBySessionId(sid).orElseThrow()))throw denied();
        }else if(!keys.isEmpty())throw denied();
    }
```

기존 최초 등록 사실을 지우거나 수정하지 않는다. 정책상 이미 등록된 고객은 현재 서명 키/시각까지 만족해야 추가할 수 있다. 분기 자체는 고객의 정상 등록 상태를 표현하며 과거 API/데이터 형식을 우회하는 폴백이 아니다.

출처: [CustomerDeviceService.java](../../src/main/java/dustin/cex/domains/security/CustomerDeviceService.java#L47), 현재 소스의 부분 발췌.

```java
        // 실제 서명한 키가 현재 남아 있으며 폐기 대상과 다른지 확인한다. 시각만 있는 세션은 허용하지 않는다.
        if(keys.size()<2||session.getMfaCredentialFingerprint().equals(fingerprint))throw denied();
        credentials.delete(lost.getCredentialId());UUID event=changes.append(actor,sid,CustomerMfaKeyChanges.Kind.REVOKED,fingerprint);
        tokens.revokeAllByUserId(actor,LocalDateTime.now());jdbc.update("DELETE FROM customer_mfa_challenges WHERE user_id=?",actor);
        audit.record(actor,"CUSTOMER_MFA_KEY_REVOKED","customer-mfa:"+event,"SUCCESS","ALL_SESSIONS_REVOKED");
        return null;
    }));}
```

CustomerSessionPolicy와 fresh가 현재 소유·수명·키의 생존을 먼저 검사한다. 같은 키 자체의 제거/마지막 키 제거를 막고 모든 금융/감사 원본과 구분해 공개키만 제거한다. 마지막 감사까지 실패하면 DB 트랜잭션이 키와 이력·세션을 되돌린다.

출처: [CustomerMfaKeyChanges.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaKeyChanges.java#L15), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID append(long actor,UUID sid,Kind kind,String fingerprint){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_mfa_key_changes(id,user_id,session_id,kind,credential_fingerprint) VALUES(?,?,?,?,?)",id,actor,sid,kind.name(),fingerprint);
        notice.reserve(actor,id);
        return id;
    }
```

065 이후 현재 발췌에서는 같은 트랜잭션에 보안 통지 예약도 참여한다. MANDATORY는 상위 업무 트랜잭션이 없으면 호출을 거부한다. 별도 커밋이나 비동기 기록으로 금융/보안 업무와 이력이 갈라지지 않는다. UUID는 변경 영수증이며 실제 생성 사실을 저장한 뒤에만 반환한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 기존64개·신규 기기 수명11개를 별도 실행 소유 PostgreSQL16 서버/테스트 묶음에서 실행해75개, 실패/오류/제외0·bootJar 통과. 실제 이메일 확인·Argon2·서명·세션·MVC와 DB 잠금/감사를 사용한다. 기존 이력/트리거를 지워 fixture 충돌을 해결하지 않았다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고가 남는다.063에서 검증한577개 전체 금융/시장 선택 회귀를 이번 수치로 재사용하지 않는다. 공유 표준 검증/금융 경로는 이번에 바꾸지 않았으며 이번 결과는75개의 고객 계정 회귀다. 새 OSV 조회/의존성 변경/새 성능 측정·외부 메일/배포/엔진·프론트 변경은 없다. 봇은0명이고 소유 테스트 자원만 정리했다.

## 한계와 다음 연결

이 기록 당시 보안 통지는 미완료였으며 [065](065-customer-passkey-notices.md)에서 키 변경 원본 조회/보안 통지를 연결했다. 마지막 키/모든 기기 분실의 독립 복구, 보안 변경·출금의 정확한 내용 결합 추가 인증도 후속이다. 키 수명 구현만으로 고객 MFA/복구·출금 보안 전체를 완료했다고 표시하지 않는다. 실물/동기화 패스키·운영 도메인 HTTPS·보존/키 보호의 실제 검증은 별도다.

## 작성한 테스트 코드


[CustomerDeviceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerDeviceIntegrationTest.java)

- `backupRequiresVerifiedAndFreshExistingKey`: 비밀번호 단계 대기 세션과6분 지난 서명으로 예비 키 옵션을 받지 못하며 이력1건은 유지한다.
- `backupRevokesAllAndBothKeysCanAuthenticate`: 예비 키 등록 뒤 기존 세션을 모두 회수하고 두 실제 키가 각각 로그인한다. 최초 사실1건/추가 이력2건이다.
- `deviceHttpReturnsOnlyOwnMetadata`: 실제 HTTP는 본인 최소 메타데이터/no-store를 반환하고 운영자403이며 자격 원문을 노출하지 않는다.
- `backupKeyCanRevokeLostKeyAndEndsEverySession`: 다른 예비 키로 분실 키를 폐기하면 그 키는 없어지고 모든 세션이 종료되며 예비 키 재로그인은 가능하다.
- `lastKeyAndCurrentlySigningKeyCannotBeRemoved`: 마지막 키와 현재 서명한 키 자체의 제거는 거부되고 추가 원본을 만들지 않는다.
- `concurrentRevokeHasOneEffect`: 동시 폐기는 하나만 성공하고 폐기 사실도1건만 추가된다.
- `revokeAuditFailureRestoresKeySessionsAndHistory`: 폐기 감사 실패는 키·현재 세션·변경 사실을 모두 롤백한다.
- `backupAuditFailureRestoresOriginalKeyAndHistory`: 예비 등록 감사 실패는 새 키/변경 사실/기존 세션 회수를 모두 롤백한다.
- `foreignAndOperatorSessionsCannotManageCustomerKeys`: 타인/운영 세션으로 고객의 키 목록/폐기를 수행하지 못한다.
- `fiveKeyLimitCannotBeExceeded`: 실제5개 키를 등록한 뒤6번째 옵션은 거부하고5건의 추가 사실만 남긴다.
- `keyChangeFactsCannotBeRewrittenOrErased`: 추가/폐기 사실의 수정·삭제·TRUNCATE를 DB가 거부한다.

## 관련 커밋과 증적

- [구현 1de719c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1de719cd70bb638e1c353610441bc9e045eaa46a)
- [격리 실행 증적](../reference/064-customer-passkey-devices-verification-2026-10-25.json)
