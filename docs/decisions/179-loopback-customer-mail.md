# 로컬 고객 메일을 실제 브라우저 흐름에 연결하기

고객 이메일 확인 API와 보안키 정책은 이미 있었지만 로컬 SMTP 출력이 없어 브라우저에서 최초 보안키 등록까지 진행할 수 없었다. 루프백 Mailpit만 제한적으로 허용하고, 운영 SMTP의 TLS·인증 요구는 그대로 유지한 변경과 검증 범위를 기록한다.

## 문제와 기존 동작

신규 고객은 출금 보안키 등록 전에 현재 세션에서 이메일 주소를 확인해야 한다. 플랫폼은 확인 챌린지와 일회용 토큰을 영속 예약했지만 `CustomerSmtpTransport`가 모든 평문 SMTP와 HTTP 복귀 주소를 거부했다. 이는 운영 경계에서는 맞지만 외부로 전송하지 않는 로컬 Mailpit까지 막아, 브라우저에서 메일 링크를 받을 수 없었다.

KYC의 `503`은 별개 원인이다. 현재 로컬 프로세스에 Didit 샌드박스 API 자격 증명이 없으므로 세션 생성이 실패한다. 이메일 확인 성공을 KYC 성공으로 대신하거나 가짜 VERIFIED 상태를 저장하지 않는다.

## 기존 제품과 공식 자료의 접근

- [OWASP 이메일 확인 지침](https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html)은 일회용·기한 제한 토큰과 사용 후 무효화를 권고한다. 기존 플랫폼의 영속 챌린지 계약을 그대로 사용했다.
- [W3C WebAuthn Level 3](https://www.w3.org/TR/webauthn-3/)의 등록 절차처럼 서버 옵션을 받은 뒤 브라우저 `navigator.credentials.create()` 결과를 서버가 검증한다. 이메일 링크가 보안키 자격 증명을 대신하지 않는다.
- [Mailpit 문서](https://mailpit.axllent.org/docs/)의 로컬 SMTP 수신함을 사용해 외부 메일 전송 없이 실제 MIME 링크를 브라우저에서 열었다.

## 선택과 트레이드오프

로컬에서만 이메일 확인을 생략하면 개발은 빠르지만 운영 정책과 다른 우회 경로가 생긴다. 실제 챌린지·SMTP·링크·확정 API를 유지하고 Mailpit을 출력 대상으로 선택했다. 실제 운영과 같은 업무 경로를 검증하는 대신 로컬 Compose 구성 요소와 1025·8025 포트가 늘어난다.

평문 SMTP와 HTTP 복귀 주소는 SMTP 호스트와 공개 주소가 모두 `localhost`, `127.0.0.1`, `::1`인 조합에만 허용한다. 원격 주소는 기존 TLS·서버 신원 확인·SMTP 인증을 계속 요구한다. 따라서 이 편의가 운영 설정을 느슨하게 만들지는 않지만, Mailpit 검증은 실제 공급자의 배달·스팸 처리·TLS 운영 검증을 대신하지 못한다.

## 구현과 관련 코드

[CustomerSmtpTransport](../../src/main/java/dustin/cex/domains/mail/CustomerSmtpTransport.java)의 실제 일부다.

```java
boolean localPublic=Set.of("127.0.0.1","::1","localhost").contains(base.getHost());
// 로컬 Mailpit은 실제 외부 전송을 하지 않고 루프백에서만 메일을 잡는다. 이 조합에만
// 평문 SMTP와 HTTP 복귀 주소를 허용하고, 한쪽이라도 외부 주소면 운영 TLS 계약을 유지한다.
boolean isolatedLocal="smtp".equals(protocol)&&local&&localPublic&&"http".equals(base.getScheme());
if(!isolatedLocal&&!"https".equals(base.getScheme()))throw new IllegalStateException("고객 메일 발신/TLS/공개 주소 설정이 필요합니다");
```

Java의 `Set.of(...).contains(...)`는 허용할 정확한 루프백 호스트만 검사한다. `isolatedLocal`의 모든 조건이 참일 때만 예외이며, SMTP만 로컬이거나 공개 복귀 주소만 로컬인 혼합 구성은 허용하지 않는다. TypeScript의 불리언 조건과 같지만, Spring 시작 시 `requireReady()`가 실패하면 잘못된 설정으로 서비스를 시작하지 않는 점이 중요하다.

공통 [compose.local.yml](../../../compose.local.yml)에는 고정 버전 Mailpit과 상태 확인을 추가했다. SMTP는 `127.0.0.1:1025`, 웹 수신함은 `http://127.0.0.1:8025`로만 공개한다.

## 작성한 테스트

[CustomerSmtpTransportTest](../../src/test/java/dustin/cex/domains/kyc/CustomerSmtpTransportTest.java)에 다음 회귀를 추가했다.

- `loopbackMailpitAndLoopbackCustomerUrlAllowLocalPlaintext`: 양쪽이 루프백인 로컬 조합만 시작 가능한지 확인한다.
- `loopbackPlaintextCannotPointAtRemoteCustomerUrl`: 로컬 평문 SMTP와 원격 공개 주소를 섞으면 거부하는지 확인한다.
- 기존 원격 평문 SMTP, 선택적 STARTTLS, 신뢰 우회, 인증 누락 거부도 함께 실행했다.

실제 Chrome에는 가상 WebAuthn 인증기를 연결했다. 신규 고객 가입·로그인, 확인 메일 발송, Mailpit의 실제 링크 열기, 명시적 확인 버튼, 현재 비밀번호 제출, `navigator.credentials.create()`, 플랫폼 등록을 순서대로 실행했다. PostgreSQL에는 해당 계정의 이메일 확인 이력과 MFA 등록 이력이 각각 1건 남았고 Mailpit에는 이메일 확인과 보안키 변경 안내가 각각 1건 수신됐다.

## 실행 명령과 결과

```text
./gradlew test --tests dustin.cex.domains.kyc.CustomerSmtpTransportTest
BUILD SUCCESSFUL

./gradlew bootJar
BUILD SUCCESSFUL

docker compose -f compose.local.yml config --quiet
성공
```

플랫폼은 PostgreSQL `5433`, Kafka `9092`, Mailpit SMTP `1025`와 함께 `8080`에서 실행했다. 고객 앱은 `3003`에서 실행했다. 봇은 0명으로 유지했다. 실제 브라우저 검증은 로컬 개발 DB에 감사 가능한 테스트 고객과 이력을 남기며 운영 데이터 검증이 아니다.

## 남은 한계

Didit KYC는 샌드박스 자격 증명과 외부 세션 생성이 아직 없어 실제 성공을 검증하지 못했다. 화면은 이를 `Didit 설정 필요`로 표시하며 완료로 가장하지 않는다. Mailpit은 외부 수신자 배달, DNS, 운영 TLS, 반송 처리를 검증하지 않는다. 실제 보안키 대신 Chrome 가상 인증기를 사용했으므로 실물 장치·운영 HTTPS 검증도 별개다.

## 관련 커밋과 참고 자료

이 문서와 구현·테스트는 같은 플랫폼 작업 커밋에 포함한다. 프론트 화면과 브라우저 회귀는 Cex-Frontend의 `011-email-passkey-onboarding.md`에 연결한다.

