# 운영자의 비밀번호 세션에 업무 권한을 주지 않고 WebAuthn으로 인증 수준을 높이기

2026-10-22. 운영자 MFA의 첫 구현 사이클이다. **후속 [기기 폐기·복구·권한 만료/회수](015-operator-recovery-and-access-lifecycle.md)에서 아래의 후속 과제를 구현했고 최초 부여 실행 경계도 교체했다.** 이 문서의 검증 개수와 미완료 목록은 첫 사이클 당시의 기록이다. 기기 등록·로그인·추가 키 등록과 세션 교체를 끝까지 연결한다. 분실 기기 폐기·계정 복구·권한 만료/회수/재검토 업무는 후속 사이클이다. 고객 MFA·화면·엔진 정책을 추가한 작업이 아니다.

## 문제와 기존 동작

기존 `AuthService.signin`은 고객과 운영자 모두 비밀번호만으로 업무 세션을 만들었다. 운영 승인의 비밀번호 재확인과 요청 해시는 다른 인증 요소가 아니므로 MFA라고 부를 수 없다. 운영 계정의 비밀번호를 알아낸 공격자는 관리자 API를 호출할 수 있었다.

최초 키 등록도 별도 경계가 필요하다. 비밀번호만으로 첫 패스키를 등록할 수 있으면 공격자가 자신의 키를 등록해 버린다. 인증 전에 받은 토큰에 인증 성공 표시만 붙이면 그 토큰의 탈취자에게도 권한이 생긴다. 갱신할 때 인증 시각을 현재로 바꾸면 MFA의 유효 시간이 무한 연장된다.

## 기존 제품과 공식 자료의 접근

확인일은 2026-10-22다. 공개 고객 기능을 참고했으며 회사 내부 운영자의 구현을 확인했다고 해석하지 않는다.

| 공식 자료 | 확인한 패턴과 적용 |
|---|---|
| [Coinbase 2단계 인증](https://help.coinbase.com/en/coinbase/getting-started/getting-started-with-coinbase/2-step-verification) | 패스키·보안키 및 예비 수단의 준비를 참고해 복수 키 등록을 지원 |
| [Kraken 복수 인증 수단](https://support.kraken.com/articles/how-to-enable-multiple-2fa) | 기기 하나의 분실에 대비해 최대 5개 키 등록. 비밀번호만으로 예비 키를 추가할 수 없도록 최근 MFA 요구 |
| [IBKR Secure Login System](https://www.ibkrguides.com/clientportal/sls/secureloginsystem.htm) | 로그인 보안을 독립적인 업무 조건으로 둠. IBKR의 임시 수단을 이 프로젝트의 운영자 우회 경로로 복제하지 않음 |
| [NIST 인증 수단](https://pages.nist.gov/800-63-4/sp800-63b/authenticators/), [수명 관리](https://pages.nist.gov/800-63-4/sp800-63b/events/) | RP에 결합되는 공개키 인증, 사용자 확인, 등록·분실·회수의 구분을 설계 근거로 사용. NIST 등급 인증이나 전체 준수를 주장하지 않음 |
| [OWASP MFA](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html) | 인증 수단 변경과 복구가 우회 경로가 될 수 있음을 반영. 이번에는 비밀번호/SMS/이메일로 MFA를 끄는 API를 제공하지 않음 |
| [Spring Security Passkeys](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html) | 기존 Security 7.1.1에 선택 모듈을 추가. 표준 검증기·사용자 JDBC 저장·Jackson 3 모듈 재사용. 기본 로그인 화면과 HTTP 세션은 활성화하지 않음 |
| [CVE-2026-47841](https://spring.io/security/cve-2026-47841/) | 사용자 확인 필수 조건이 직렬화 뒤 사라지는 공지 확인. 수정 버전 7.1.1 사용, 저장 옵션 복원 뒤에도 `REQUIRED`를 명시하고 실제 UV 누락 거부 검사 |
| [WebAuthn4J 문서](https://webauthn4j.github.io/webauthn4j/en/), [릴리스](https://github.com/webauthn4j/webauthn4j/releases) | Spring이 선택한 0.31.9.RELEASE 사용. 서명·CBOR·COSE를 직접 구현하지 않음. `none` attestation은 기기 제조사나 하드웨어 전용 여부를 증명하지 않음 |

국내 근거는 [접근 통제 대조표](../reference/access-control-compliance.md)에 연결한다. [현행 안전성 확보조치 기준](https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq=2100000281400)의 제2026-9호·2026-07-01 시행 표기, [개인정보위의 ISMS-P 안내서 게시](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=G010030000&nttId=9862), [금융보안원 금융권 점검항목](https://www.fsec.or.kr/bbs/detail?bbsNo=11386&menuNo=247)을 재확인했다. 법령의 사업별 적용·부칙·운영 증적과 전체 인증은 여전히 별도 확인 대상이다. 금융회사 대상 기준을 현물 거래소의 일률적 의무로 단정하지 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 비용·대안과 선택 이유 |
|---|---|---|
| WebAuthn + 사용자 확인 필수 | RP·출처·챌린지에 결합되는 인증, PIN/생체 확인 강제 | TOTP보다 기기·브라우저 준비가 필요. TOTP/SMS는 운영자 피싱 저항성을 제공하지 못하므로 이번 수단으로 채택하지 않음 |
| 서버 DB에 인증 수준 저장 | 세션 회수·역할·인증 상태를 현재 값으로 확인 | 요청당 DB 비용. JWT 안의 MFA 주장만 신뢰하는 방식은 회수와 상태 변경에 불리함 |
| 비밀번호 통과 후 5분 대기 세션 | 등록·추가 인증만 가능하며 운영/고객 업무는 차단 | 인증 단계가 하나 늘어남. 아직 완성하지 않은 MFA에 운영 권한을 먼저 주지 않음 |
| MFA 성공 때 토큰 교체 | 인증 전 토큰을 가진 사람의 권한 상승 차단 | 클라이언트가 새 접근/갱신 토큰을 함께 교체해야 함 |
| 운영 세션 12시간, 변경은 최근 5분 MFA | 무한 갱신과 오래된 인증의 변경 권한 제한 | 운영자가 다시 보안키를 확인해야 함. 수치는 이 프로젝트 정책이며 법정 시간을 주장하지 않음 |
| 최초 등록 비밀의 별도 전달 | 비밀번호 유출만으로 첫 키를 심는 공격 차단 | OS 관리자의 신원 확인·안전한 전달이 필요. 무작위 생성과 전달을 코드만으로 증명할 수 없음 |
| 키 최대 5개, 복구 우회 없음 | 예비 키로 정상 재로그인 가능 | 모든 키/최초 등록 비밀을 잃으면 잠긴다. 복구 승인 업무가 완성되기 전 실운영 도입 완료로 보지 않음 |
| 표준 검증기 + 작은 PostgreSQL 키 저장 구현 | 암호 검증은 라이브러리에 맡기고 빈 전송수단도 정확히 복원 | 기본 JDBC 키 저장의 빈 문자열 처리와 카운터 갱신 계약을 소스에서 확인하고 필요한 저장 코드만 작성 |

## 구현과 관련 코드

- [OperatorMfaService](../../src/main/java/dustin/cex/domains/security/OperatorMfaService.java): 사용자 행 잠금, 일회성 등록 권한/챌린지, 검증, 세션 교체, 성공·거부 감사. 사용한 공개키 ID의 SHA-256 지문으로 기기를 추적하고 원본 인증 자료는 기록하지 않는다.
- [WebAuthnCredentialRepository](../../src/main/java/dustin/cex/domains/security/WebAuthnCredentialRepository.java): 공개키·원본 attestation·카운터 보관. 개인키는 받지 않는다. 사용자 실명 대신 내부 ID와 무작위 WebAuthn user handle을 사용한다.
- [OperatorMfaController](../../src/main/java/dustin/cex/domains/security/OperatorMfaController.java): JSON API. 요청의 사용자 ID를 신뢰하지 않고 필터가 확인한 사용자/세션을 사용한다. 인증 본문·토큰의 자동 문자열 표현을 숨긴다.
- [AuthService](../../src/main/java/dustin/cex/domains/auth/service/AuthService.java), [RefreshToken](../../src/main/java/dustin/cex/domains/auth/model/entity/RefreshToken.java): 운영 비밀번호 세션 5분, `mfaRequired=true`, 미검증 갱신 거부, 검증 시각을 보존하는 갱신.
- [필터](../../src/main/java/dustin/cex/domains/auth/middleware/JwtAuthenticationFilter.java), [인가](../../src/main/java/dustin/cex/domains/security/SecurityConfiguration.java), [세션](../../src/main/java/dustin/cex/domains/security/SessionService.java): 미검증 운영자는 `ROLE_MFA_PENDING`. 모든 기존 운영 HTTP 업무는 MFA를 통과해야 한다. 인증 응답은 `Cache-Control: no-store`.
- [최초 운영자 부여 업무](../../src/main/java/dustin/cex/domains/security/OperatorBootstrapService.java): 금융 이력이 없는 전용 계정에 역할 부여, 모든 세션 회수, 10분 등록 권한의 SHA-256 해시, 감사 기록을 함께 커밋. 등록 비밀은 필수 입력이며 자동 로그에 출력하지 않는다.
- [스키마](../../src/main/resources/security/schema.sql): 공개키, 등록 권한, 챌린지. 수명이 짧은 챌린지를 지우는 것은 금융/감사 기록 삭제가 아니다. 영속 감사는 기존 불변 트리거로 유지한다.
- [CORS](../../src/main/java/dustin/cex/domains/auth/config/WebConfig.java): 설정한 고객/어드민 출처만 허용. 와일드카드와 쿠키 인증은 사용하지 않는다.

### 상태·동시성 계약

1. 고객 로그인은 기존 고객 흐름을 사용한다. 운영자는 현재 역할·활성 상태·인물 ID를 계속 확인한다.
2. 최초 등록에는 비밀번호로 받은 대기 세션과 OS 관리자가 별도로 전달한 등록 비밀이 모두 필요하다. 등록 비밀은 최소 43문자, 최대 256문자이며 운영자는 CSPRNG로 32바이트 이상 생성해야 한다. 길이 검사만으로 엔트로피가 증명되지는 않는다.
3. 최초 등록 비밀은 **옵션 발급 성공 때** 소비된다. 발급 후 5분 안에 등록하지 못하거나 세션을 잃으면 재사용할 수 없다. 추가 키는 최근 5분 MFA가 필요하다. 완료 때도 다시 확인한다.
4. 등록 성공만으로 세션을 높이지 않는다. 새 인증 챌린지의 실제 서명 검증 뒤에만 이전 세션을 회수하고 새 세션을 발급한다.
5. 챌린지는 사용자·세션·목적·5분 만료에 묶고 성공과 소비를 같은 트랜잭션에 둔다. 새 옵션은 같은 세션의 이전 옵션을 폐기한다. 병렬 성공은 하나만 가능하다.
6. 사용자 행 잠금 안에서 실패 감사까지 별도 커밋한다. 거부된 업무의 롤백과 별개로 15분간 MFA 실패 5회를 센다. 이후 요청은 429다. 제한 뒤의 요청은 별도 `RATE_LIMITED` 감사로 남겨 원래 실패 창을 계속 연장하지 않는다. 알려지지 않은 계정/분산 IP의 앞단 제한은 별도 운영 과제다.
7. 실패한 검증은 DB 변경을 롤백한다. 유효한 챌린지는 만료/새 발급/성공 전까지 제한된 횟수로 다시 시도할 수 있다. 감사 저장 장애도 성공으로 숨기지 않는다.
8. 등록·인증에 정확한 origin/RP, 사용자 확인 필수, 키 소유자·user handle, `id=rawId=attested credential ID`, 허용 키 목록을 검사한다. 다른 출처의 iframe 인증을 거부한다.
9. 카운터를 사용하는 기기는 마지막 성공 카운터보다 증가해야 한다. 0만 쓰는 패스키는 새 챌린지의 유효한 서명으로 인증한다. 0 카운터로는 복제 탐지를 주장할 수 없다.
10. 성공 감사·카운터·챌린지 소비·새 세션은 함께 커밋한다. 기존 세션으로는 성공 후에도 인증을 재실행할 수 없다.
11. 운영 변경 API는 최근 5분 MFA를 요구한다. 기존 승인 요청의 해시·상태 버전·별도 인물·정적 비밀번호 재확인은 함께 유지된다. MFA가 특정 승인 요청에 결합된 **별도 거래 서명**까지 구현한 것은 아니다.

### API 계약

모두 플랫폼 진입점이다. 기본 경로는 `/api/auth/operator-mfa`, 인증은 `Authorization: Bearer ...`다.

| API | 입력 | 응답/의미 |
|---|---|---|
| `POST /register/options` | `{ "enrollmentToken": "별도 전달한 비밀" }`, 추가 키는 `{}` | `{challengeId, publicKey}`. `publicKey`는 `navigator.credentials.create` 옵션 |
| `POST /register` | `{challengeId, publicKey:{credential:...,label:"기기 이름"}}` | 200. 키 등록만 완료, 아직 운영 권한 없음 |
| `POST /authenticate/options` | 본문 필요 없음 | `{challengeId, publicKey}`. `navigator.credentials.get` 옵션 |
| `POST /authenticate` | `{challengeId,publicKey:브라우저의 credential}` | `{accessToken,refreshToken}`. 이전 토큰 폐기 후 두 값을 함께 교체 |

옵션과 credential의 이진 값은 표준 Base64url을 사용한다. 브라우저 API 호출 전후 ArrayBuffer 변환은 후속 프론트 구현에 속한다. 401은 세션 인증 실패, 403은 MFA/정책 거부, 429는 시도 제한이다. 로그인 화면 리다이렉트는 없다.

## 이해에 필요한 문법과 원리

- Java의 `record`는 고정된 필드의 값 객체다. TypeScript의 DTO 인터페이스와 달리 런타임 생성자·접근자·기본 문자열 표현도 존재하므로 토큰 record의 `toString()`을 직접 가렸다.
- `PublicKeyCredential<AuthenticatorAssertionResponse>`는 응답 종류를 타입으로 고정한다. JSON 경계에서 제네릭이 지워지므로 Jackson의 매개변수 타입을 지정해 복원한다.
- 옵션은 공식 모듈로 응답 직렬화하지만 해당 타입의 DB JSON 생성자 복원은 제공되지 않는다. 저장한 필드만 표준 빌더에 넣는다. 객체 스트림이나 임의 클래스 역직렬화를 도입하지 않는다.
- 트랜잭션 콜백 안의 예외는 업무를 롤백한다. `AuditService.denied`는 별도 트랜잭션이므로 거부 감사는 남는다. 실패 기록을 사용자 잠금 밖으로 빼면 병렬 요청이 같은 실패 횟수를 보고 한도를 넘길 수 있다.
- WebAuthn 개인키로 `authenticatorData + SHA256(clientDataJSON)`에 서명한다. 서버는 등록된 공개키로 검증한다. 테스트는 이 서명을 실제로 생성한다. 해시된 등록 비밀은 이 공개키 인증을 대신하는 수단이 아니다.
- 서버 안의 역할 확인과 인증 수준은 별개다. 역할이 APPROVER여도 MFA 전에는 승인할 수 없고 MFA를 통과해도 OPERATOR가 APPROVER가 되지는 않는다.

### 현재 코드로 연결해 읽기

공식 검증 모듈의 서명 검증 뒤 서명 카운터도 검사한다. 아래는 2026-10-25 현재 [실제 소스](../../src/main/java/dustin/cex/domains/security/WebAuthnVerifier.java#L88)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

```java
    var authenticated=relyingParty.authenticate(new RelyingPartyAuthenticationRequest(options,key));
    if(!authenticated.getName().equals(Long.toString(actor))) throw denied();
    // 일부 동기화 패스키는 카운터 0을 쓴다. 카운터를 쓰는 기기는 마지막 성공 값보다 증가해야 한다.
    // 인증 데이터의 앞 32바이트는 RP 해시, 다음 1바이트는 플래그, 이어지는 4바이트는 부호 없는 카운터다.
    long current=Integer.toUnsignedLong(ByteBuffer.wrap(key.getResponse().getAuthenticatorData().getBytes(),33,4).getInt());
    if((existing.getSignatureCount()!=0 || current!=0) && current<=existing.getSignatureCount()) throw denied();
    return key;
}
```

라이브러리 인증 결과의 사용자 이름을 현재 계정과 대조한다. `Integer.toUnsignedLong`은 4바이트 카운터를 부호 없는 값으로 읽는다. 동기화 패스키의 0 카운터와 증가 카운터 정책을 구분한 이유는 본문에 설명했다.

## 작성한 테스트

[OperatorMfaTest](../../src/test/java/dustin/cex/domains/security/OperatorMfaTest.java)는 실제 PostgreSQL과 검증기를 사용한다. [SoftwareAuthenticator](../../src/test/java/dustin/cex/domains/security/SoftwareAuthenticator.java)는 테스트마다 P-256 키를 생성하고 ECDSA 서명과 표준 CBOR/COSE 등록 데이터를 만든다. 검증기 성공을 모킹하지 않는다.

| 테스트 함수 | 시나리오·기대 결과 |
|---|---|
| `passwordSessionCannotUseOperatorApisOrRefresh` | 비밀번호 직후 운영 조회·갱신 차단, MFA 시각 없음 |
| `actualRegistrationAndSignatureRotateSessionThenOpenAdmin` | 필수 UV 옵션, 실제 등록·서명, 운영 API 허용, 이전 토큰 회수, 캐시 금지 |
| `enrollmentNeedsSeparateUnexpiredOneTimeGrant` | 다른 비밀·소비된 비밀 거부, 이전 챌린지 보존 |
| `expiredEnrollmentGrantCannotRegister` | 등록 권한 만료 거부 |
| `registrationRejectsWrongOriginAndMissingUserVerification` | 등록 출처·UV 변조 거부, 키 미저장 |
| `authenticationRejectsWrongOriginAndMissingUserVerification` | 서명 인증 출처·UV 변조 거부 |
| `wrongKeySignatureAndUserHandleAreRejected` | 타 개인키 서명·다른 user handle 거부 |
| `changedChallengeAndRpHashAreRejected` | 서명한 챌린지·RP 해시가 다르면 거부 |
| `expiredOrWrongPurposeChallengeAndOldSessionAreRejected` | 만료·등록용 챌린지 오용·회수 세션 거부 |
| `concurrentSignatureConsumptionHasOneWinner` | 같은 서명의 병렬 소비에서 성공/성공 감사 각 하나 |
| `auditFailureRollsBackCredentialCounterChallengeAndSession` | 성공 감사 장애 시 카운터·소비·세션 회수 롤백 |
| `refreshPreservesMfaTimeAndCannotExtendTwelveHourBoundary` | 갱신이 최초 MFA 시각/만료 연장하지 않음, 12시간 초과 차단 |
| `staleMfaCanReadButCannotRequestOperatorChange` | 6분 지난 MFA는 조회 가능, 운영 변경 차단 |
| `failedVerificationIsRateLimitedAndAuditedWithoutProofData` | 실패 5회 후 429, 감사에 원본 비밀·서명·토큰 없음 |
| `customersAndDisabledOperatorsCannotEnroll` | 고객·비활성 계정 키 등록 차단 |
| `malformedProofIsDeniedWithoutServerError` | 누락된 인증 본문은 403 |
| `initialRegistrationCannotBeRepeatedOrSilentlyReplaceExistingKey` | 최초 등록 재사용·비밀번호만으로 추가 키 등록 차단 |
| `backupKeyNeedsFreshMfaAndCanAuthenticateAfterFreshSignin` | MFA 뒤 예비 키 등록, 새 비밀번호 로그인 뒤 예비 키로 인증 |
| `counterRollbackIsRejectedAcrossDifferentChallenges` | 새 챌린지여도 카운터가 증가하지 않으면 거부 |
| `zeroCounterAuthenticatorCanSignTwoDistinctChallenges` | 카운터 없는 기기의 서로 다른 챌린지 인증 허용 |
| `anotherSessionCannotUseOwnersChallenge` | 다른 사용자의 정상 세션으로 챌린지 소비 불가 |
| `registrationAuditFailureRollsBackKeyAndChallenge` | 등록 감사 장애 때 키·소비 롤백 |
| `registrationOptionsAuditFailureDoesNotConsumeGrant` | 옵션 감사 장애 때 최초 등록 비밀 소비 롤백 |
| `backupRegistrationIsLimitedToFiveKeysAndNeedsFreshVerification` | 다섯 키 상한·최근 MFA 요구 |
| `pendingSessionExpiresEvenWhileAccessJwtStillHasTime` | JWT 만료 전이라도 5분 대기 세션 만료 시 401 |
| `optionsEndpointsReturnBrowserContractWithoutLoginPage` | 실제 필터·컨트롤러의 등록/인증 옵션 계약, 리다이렉트 없음 |
| `crossOriginIframeAndMismatchedCredentialIdAreRejected` | 다른 출처 iframe·id/rawId 불일치 거부 |
| `realHttpServerCompletesPasswordRegistrationAndSignatureFlow` | 실제 임의 포트 HTTP 서버로 비밀번호→등록→서명→운영 조회, 이전 토큰 회수 |
| `rawCredentialIdMustMatchAttestedIdAndRollbackUntrustedRecord` | id/rawId를 함께 조작해도 attested ID 불일치 거부·레코드 롤백 |

[OperatorMfaConfigurationTest](../../src/test/java/dustin/cex/domains/security/OperatorMfaConfigurationTest.java): `invalidRpOriginConfigurationFailsBeforeServingRequests` 8개 설정 거부, `explicitHttpsOrLocalhostOriginIsAccepted` 3개 정상 설정, `corsAllowsExactCustomerAndOperatorOriginsWithoutCredentialsOrExtraHeaders`의 출처·헤더·쿠키 제한 1개. MFA 클래스 29개와 합쳐 신규 전용 검증은 41개다.

기존 [PlatformSecurityTest](../../src/test/java/dustin/cex/domains/security/PlatformSecurityTest.java)의 역할·승인 테스트 세션에는 명시적인 검증 시각 fixture를 넣는다. 이 fixture로 암호 검증을 입증했다고 주장하지 않는다. `bootstrapIsExplicitRevokesSessionsAndAuditsRoleGrant`에 만료·해시 저장 검사를 추가하고, `bootstrapRejectsShortEnrollmentSecretWithoutGrantingRole`, `bootstrapAuditFailureRollsBackRoleEnrollmentAndSessionRevocation` 2개를 추가한다. 기존 37개와 합쳐 39개다.

## 실행 명령과 결과

아래 명령을 실제 실행해 통과했다. 실행기는 매번 임의 포트의 PostgreSQL 컨테이너를 생성·제거하고 봇 0명, Kafka 자동 소비/주문 자동 전달 금지, 기존 DB·Kafka 주소 배제를 설정한다.

```sh
python3 scripts/verify_operator_mfa.py
python3 scripts/verify_platform_security.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_order_idempotency.py
python3 scripts/verify_market_path.py
./gradlew jmhClasses bootJar writeDependencyInventory --console=plain
python3 scripts/audit_dependencies.py
python3 scripts/verify_dependency_integrity.py
```

| 검증 | 최종 결과 | 범위 |
|---|---|---|
| `verify_operator_mfa.py` | **41개 통과**, 실패/제외 0 | 실제 PostgreSQL, 실제 공개키 서명, MockMvc와 임의 포트 실제 HTTP, 설정·CORS |
| `verify_platform_security.py` | **39개 통과**, 실패/제외 0 | 기존 역할·승인·감사, 최초 등록 권한·필수 감사 롤백 |
| `verify_engine_inbox.py` | **183개 통과**, 실패/제외 0 | 선택한 주문·수수료·원장·정산·재시도·시장·기존 클라이언트 회귀 |
| `verify_order_idempotency.py` | **10개 시나리오 통과** | 실제 JAR 프로세스 2회, PostgreSQL, 엔진 HTTP 대역. 동일 키 동시 제출·응답 유실·재시작 포함 |
| `verify_market_path.py` | **MarketPathTest 1개 통과**, 실패/제외 0 | 실제 Java/Rust/PostgreSQL/Kafka, 호가·체결·수수료·원장·독립 대사 CLI |
| `jmhClasses bootJar writeDependencyInventory` | 통과 | 벤치 클래스 컴파일·실행 JAR·실제 의존성 목록. 성능 벤치 실행 아님 |
| `audit_dependencies.py` | **222개 패키지 버전, 알려진 OSV 검출 0** | 실행·테스트·벤치·빌드의 좌표 대조. 조회 실패/누락 없음 |
| `verify_dependency_integrity.py` | 통과 | 임시 복사본의 잘못된 체크섬에 Gradle이 예상대로 종료 코드 1. 원본 캐시 미수정·복사본 제거 |

JUnit 실행은 선택한 264개, 별도 실제 프로세스 시나리오는 10개다. 전체 저장소의 모든 테스트를 실행한 결과로 표현하지 않는다. 봇은 0명이며 실행기가 소유한 DB·Kafka·플랫폼/엔진 프로세스는 정리했다. 기존 5432/5433/5435 DB와 9092 브로커는 건드리지 않았다.

새 의존성은 Security WebAuthn 7.1.1, WebAuthn4J 0.31.9.RELEASE, Jackson CBOR 3.1.8이다. 기존 BOM의 선택과 패치 정책을 유지하고 `gradle.lockfile`·SHA-256 검증 메타데이터를 갱신했다. 빌드 플러그인 잠금의 버전 변경은 없다. [이번 고정 의존성 증적](../reference/operator-mfa-dependencies-2026-10-22.json)은 실제 파일 해시와 OSV 대조 결과를 담는다. 초기 체크섬 등록은 최초 신뢰에 기반하며 독립 서명 검증과 동일하지 않다.

로컬 증적은 공통 루트 `.local/operator-mfa-results.json`, `operator-mfa.json`, `platform-security.json`, `wal-java-inbox.json`, `order-idempotency-process.json`, `market-path-results.json`, `dependency-audit.json`, `dependency-integrity.json` 및 관련 로그다. OS/JDK/컨테이너 이미지·JNI·운영 환경 전체의 취약점 검증은 이 결과에 포함하지 않는다.

기존과 일부 신규 호출의 deprecated API, JVM 클래스 공유, Gradle 향후 버전 관련 경고는 남아 있다. 실제 브라우저·실물 인증기·Linux EC2 배포·성능 수치는 검증하지 않았다.

첫 실행 실패도 기록한다. 옵션 JSON을 기본 생성자로 복원할 수 없어 19개 중 14개가 실패했다. 서버 필드의 표준 빌더 복원으로 수정했다. 다음에는 테스트 기기의 COSE 알고리즘 누락으로 정상 등록 fixture가 거부되어 ES256을 명시했다. 마지막 단일 실패는 테스트에서 이전 JPA 버전 객체를 다시 저장한 문제였으며 갱신된 엔티티를 사용하도록 수정했다. 검증 정책을 느슨하게 바꿔 통과시키지 않았다.

## 운영 조건과 남은 범위

- `OPERATOR_WEBAUTHN_RP_ID`, `OPERATOR_WEBAUTHN_ORIGIN`은 실제 어드민 도메인/HTTPS 출처로 지정한다. 기본 `localhost`는 로컬 개발값이다. 등록 뒤 RP 변경은 기존 키와 다른 인증 경계이므로 임시 호환 경로를 만들지 않는다.
- 최초 부여는 `operator.bootstrap.enabled`, `user-id`, `person-id`, `role`, 필수 `enrollment-token` 설정을 가진 기존 일회성 초기화 프로세스를 사용한다. 비밀은 승인된 비밀 전달 수단/프로세스 환경으로 공급하고 셸 인수·커밋·공유 로그에 넣지 않는다. 인물 ID 검증과 관리자의 신원 확인은 조직의 책임이다.
- 이 초기화 프로세스는 현재 애플리케이션 컨텍스트를 시작한다. 상시 서비스와의 동시 실행·모든 예약 작업 차단·실제 운영 초기화 절차를 검증한 전용 관리 도구가 아니다. 이번 테스트는 초기화 서비스의 트랜잭션 계약을 검증했다. 후속 권한 생명주기 작업에서 실행 경계도 분리해야 한다.
- 공개키/사용자 handle/등록 원본/감사는 백업·접근 제한·보존 정책의 대상이다. 개인키·생체 원본은 서버에 보관하지 않는다. DB 최고 관리자 침해까지 트리거·해시로 보호한다고 주장하지 않는다.
- 실물 보안키·브라우저의 사용자 확인·인증기 제조사 증명·기기 관리/폐기, 독립된 예비 장치의 준비, TLS·도메인·앞단 요청량 제한·보안 경보·외부 감사 보존·조직의 복구 신원 확인은 운영 검증이 필요하다.
- API 키/SMS/이메일/복구 코드로 운영 MFA를 끄는 우회 경로가 없다. **분실 키 폐기·모든 키 분실 복구·등록 권한 재발급·퇴직/직무 변경·권한 만료·정기 재검토는 아직 미완료**다.
- 최근 MFA는 승인 요청 하나에 대한 별도 서명이 아니다. 더 민감한 출금/원장 정정 승인에는 요청 내용과 인증 증명을 함께 묶는 후속 통제가 필요하다.
- 새 스키마를 격리 PostgreSQL에서 검증했다. 기존 개발 DB의 DDL·금융 기록을 직접 바꾸거나 배포하지 않았다. 하위 호환 분기·임시 OTP·새 로그인 화면은 추가하지 않았다.
- 엔진 소스·코어 큐·WAL·Kafka 워커 구조는 변경하지 않았다. 디스크 손실 대비 복제·백업 인프라는 이번에도 문서 조건으로만 둔다.

## 관련 커밋과 참고 자료

선행: 플랫폼 `1ee1bf3`, `c5f564a`의 지원 런타임 교체. 이번 커밋:

- `b421aa4` — WebAuthn 모듈·전이 의존성 잠금·체크섬.
- `b12bf28` — 운영자 등록/인증·세션·최근 MFA·자동화 검증.
- 이 문서·근거 대조표·고정 의존성 증적은 별도 문서 커밋으로 기록한다.

062에서 표준 등록·서명 검증은 [WebAuthnVerifier](../../src/main/java/dustin/cex/domains/security/WebAuthnVerifier.java)로 옮겼다. 현재 발췌의 위치를 갱신하며014의 당시 테스트 결과는 그대로 구분한다. 운영자 등록 권한·세션/승인 정책은 OperatorMfaService에 남는다.
