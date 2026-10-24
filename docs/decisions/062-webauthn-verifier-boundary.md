# 표준 보안키 검증과 운영자 업무 정책 분리

고객 다중 인증을 붙이기 전에 운영자 서비스에 섞인 표준 WebAuthn 검증을 분리했다. 기존 운영자 업무는 그대로 동작하며 출처별 검증 인스턴스, 트랜잭션 경계와 실제 서명 회귀를 설명한다. 고객 MFA API 자체의 완료 기록은 아니다.

## 문제와 기존 동작

운영자 서비스 하나에 최초 등록 권한, 세션/업무 승인, 출처 검사, 공개키 등록, 서명 검증, 옵션 복원이 함께 있었다. 고객 인증에 이를 복사하면 출처·사용자 확인·카운터 검증이 서로 달라질 수 있다. 운영자 등록 토큰이나 승인 권한까지 공통화하면 고객/운영자 경계를 흐릴 위험이 있다.

## 기존 제품과 공식 자료의 접근

[Spring Security 패스키 문서](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)의 표준 등록/인증 연산과 JDBC 저장소 계약을 따른다. 설치된 `spring-security-webauthn:7.1.1`, `webauthn4j-core:0.31.9.RELEASE`와 실제 `Webauthn4JRelyingPartyOperations`/`JdbcUserCredentialRepository` 타입을 `javap`로 확인했다. 새 의존성을 추가하지 않았다.

[OWASP MFA 지침](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)은 등록·인증·요소 변경·복구의 위협을 구분한다. [Coinbase 공개 설정 안내](https://help.coinbase.com/en/coinbase/getting-started/getting-started-with-coinbase/2-step-verification)의 패스키/예비 보안키는 고객 기능 참고이며 내부 저장/복구 구조를 추측하지 않는다. 이번에는 실제 설치된 표준 검증기만 재사용할 경계를 정리했다. 로그인 화면이나 폼 인증은 활성화하지 않는다.

## 선택과 트레이드오프

- 표준 등록/서명·출처·사용자 확인·카운터 검증만 `WebAuthnVerifier`로 옮긴다. 구현 중복과 정책 불일치를 줄이는 대신 공통 검증 변경의 영향 범위가 커진다. 기존 운영자 및 내용 결합 승인 전체 선택 회귀를 함께 실행한다.
- 등록 권한·역할·챌린지 소유/만료·세션 교체·승인 내용과 감사는 운영자 서비스에 남긴다. 범용 인증 업무 엔진/동적 정책/호환 래퍼를 만들지 않는다. 고객 업무는 후속 사이클에서 별도로 구현한다.
- 검증 인스턴스마다 정확한 허용 출처 하나와 RP를 받는다. 고객/운영자 출처를 합치는 것보다 설정 책임은 늘지만 한쪽 출처의 서명을 다른 쪽 인증에 쓰지 못한다. 로컬 localhost HTTP 예외와 운영 HTTPS 조건은 기존 정책을 유지한다.
- 옵션 생성도 신원 저장을 할 수 있으므로 생성/등록/검증은 실제 트랜잭션이 없으면 거부한다. 호출 업무가 사용자 행 잠금을 먼저 잡아야 한다. 트랜잭션 존재 검사만으로 행 잠금 획득까지 자동 보장했다고 주장하지 않는다.

## 구현과 관련 코드

`OperatorMfaService`는 기존 메서드와 API를 유지하며 검증기 호출만 위임한다. OS 관리자의 최초 등록 비밀,5분 신선도,일회 챌린지,인증 후 세션 교체,내용 결합 승인은 그대로 해당 서비스에 남는다. 이름이 운영자 전용이던 JDBC 구현은 `WebAuthnCredentialRepository`로 바꾸고 이전 클래스를 삭제했다. 기존 표·자격 자료를 변환하거나 삭제하지 않았다. `OperatorDeviceService`도 같은 저장소 구현을 사용한다.

기존 JDBC의 빈 전송수단 복원 처리와 최초 공개키 저장/인증 시 카운터 갱신 계약도 유지한다. 이를 라이브러리에 JDBC 기능이 없어서 구현한 것이라고 설명하지 않는다. 표준 JDBC 구현은 존재하며014에 기록한 실제 PostgreSQL 전송수단 경계를 위한 저장 어댑터다.

## 실제 코드와 이해에 필요한 원리

출처: [WebAuthnVerifier.java](../../src/main/java/dustin/cex/domains/security/WebAuthnVerifier.java#L21), 현재 소스의 부분 발췌.

```java
final class WebAuthnVerifier {
    private final JdbcPublicKeyCredentialUserEntityRepository identities;
    private final WebAuthnCredentialRepository credentials;
    private final Webauthn4JRelyingPartyOperations relyingParty;
    // 전역 JSON 매퍼나 자동 로그인 화면을 바꾸지 않는다. 표준 타입에만 필요한 모듈을 쓴다.
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
```

패키지 내부의 구체 컴포넌트다. 인증 권한을 스스로 부여하지 않고 설치된 표준 연산을 감싼다. 전역 금융 JSON 매퍼에 WebAuthn의 Bytes 직렬화를 추가하지 않는다. TypeScript의 모듈 비공개 구현과 목적은 비슷하지만 Java 패키지 접근 제어가 적용된다.

출처: [WebAuthnVerifier.java](../../src/main/java/dustin/cex/domains/security/WebAuthnVerifier.java#L69), 현재 소스의 부분 발췌.

```java
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("보안키 검증에는 업무 트랜잭션이 필요합니다");
    }
```

생성·검증의 저장 부수 효과는 호출 업무와 같은 트랜잭션에 참여해야 한다. 트랜잭션 밖 호출은 DB 작업 전에 실패하며 기존 키와 카운터를 따로 확정하지 않는다.

출처: [OperatorMfaService.java](../../src/main/java/dustin/cex/domains/security/OperatorMfaService.java#L82), 현재 소스의 부분 발췌.

```java
            var key=verifier.verifyAssertion(actor,stored,credential);
            // 인증 수준을 높일 때 세션을 교체한다. 이전 대기 토큰을 훔친 사람에게 운영 권한이 생기지 않는다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
            String raw=jwt.generateRefreshToken();LocalDateTime verifiedAt=LocalDateTime.now();
            var verified=tokens.saveAndFlush(RefreshToken.builder().userId(actor).tokenHash(jwt.hashRefreshToken(raw))
                .mfaVerifiedAt(verifiedAt).mfaCredentialFingerprint(sha256Hex(key.getRawId().toBase64UrlString())).expiresAt(verifiedAt.plusHours(12)).build());
```

표준 검증 뒤 운영자의 세션 교체는 원래 서비스가 담당한다. 검증 컴포넌트가 새 토큰을 만들거나 운영 권한을 결정하지 않는다. 키 검증·세션 교체·필수 감사 실패는 기존 업무 트랜잭션으로 함께 롤백한다.

## 작성한 테스트

[OperatorMfaTest](../../src/test/java/dustin/cex/domains/security/OperatorMfaTest.java)의 `separateRelyingPartyInstancesDoNotShareAllowedOrigins`: 같은 RP/사용자 저장소를 사용해도 고객 테스트 출처의 실제 P-256 등록/서명은 고객 검증기에서만 성공한다. 동일 서명자가 운영 옵션의 챌린지에 고객 출처로 서명하면 운영 검증기는 거부하고 카운터는 직전 성공1을 유지한다. 이는 검증 인스턴스 경계 테스트이며 아직 고객 로그인 API를 검증한 것은 아니다.

[OperatorMfaConfigurationTest](../../src/test/java/dustin/cex/domains/security/OperatorMfaConfigurationTest.java)의 `standardVerifierRejectsMutationWithoutBusinessTransaction`: 옵션2종·등록·서명 검증을 트랜잭션 없이 호출하면 IllegalStateException이며 JDBC 호출은0이다.

기존42개는 실제 등록/인증,잘못된 출처·RP·서명·사용자 확인·handle,rawId 불일치,만료/소유/목적,동시 소비1건,감사 실패의 키/카운터/세션 롤백,최초 등록 권한,정확한 HTTPS/CORS 경계를 그대로 확인한다. 테스트 소프트웨어 키는 실물 기기/브라우저 인증 증적과 구분한다.

## 실행 명령과 결과

`python3 scripts/verify_operator_mfa.py`: 초기 기존42개 통과. 신규2개 추가 후44개 중1개가 테스트용 전역 JSON 매퍼로 Bytes를 직렬화해 실패했다. 테스트를 제품과 동일한 WebauthnJacksonModule 전용 매퍼로 수정한 뒤44개 전부 통과했다. 제품 검증을 완화하거나 글로벌 JSON 설정을 바꿔 맞추지 않았다.

`python3 scripts/verify_bound_approvals.py`190개, `python3 scripts/verify_customer_account.py`47개와 함께 현재 선택281 JUnit의 실패/오류/제외0을 확인했다. 실행 소유 PostgreSQL16과 실제 서명/보안 필터/감사를 사용하고 컨테이너는 정리했다. 기존 봇은0명이다. `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고는 남는다.

새 의존성/버전 변경,OSV 신규 조회,엔진 변경,성능 측정,고객 MFA API/프론트 구현·배포는 이번 사이클 범위가 아니다. 고객 등록·로그인·추가 인증은 이어서 구현한다. 운영/고객별 실제 HTTPS 도메인과 실물 브라우저의 패스키·분실 복구 검증은 별도 확인이 필요하다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 db306d1](https://github.com/GiHoon1123/Cex-Platform-Service/commit/db306d1a28e63cd9b653bc60e6d5cff38be8a1ab)
- [격리 실행 증적](../reference/062-webauthn-verifier-boundary-verification-2026-10-25.json)
