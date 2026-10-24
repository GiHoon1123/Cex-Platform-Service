# 본인 세션 기록 조회·회수와 갱신 경쟁 제어

고객이 본인 세션의 발급/종료 기록을 확인하고 세션을 회수한다. 토큰을 노출하지 않는 조회, 회수와 토큰 갱신의 경쟁, 필수 감사와 실제 JWT 차단을 설명한다.

## 문제와 기존 동작

로그인/갱신/로그아웃은 있었지만 고객이 본인의 세션 기록을 조회하거나 특정 세션을 종료하는 계정 관리 API가 없었다. JWT의 만료만 기다리면 회수가 늦으므로 기존 영속 세션과 현재 계정 검사에 연결해야 한다. 기존 로그인은 이전 세션을 교체하고 갱신은 새 세션을 만든다. 다중 기기 동시 로그인을 추가한 작업은 아니다.

## 기존 제품과 공식 자료의 접근

[Coinbase 계정 보안](https://help.coinbase.com/en/coinbase/privacy-and-security/data-privacy/how-can-i-make-my-account-more-secure)은 알 수 없는 세션/기기 접근을 확인하고 제거하는 사용자 통제를 안내한다. 해당 제품 내부 DB 구조를 추측하지 않는다. [OWASP 세션 관리](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)의 서버 측 무효화 원칙을 기존 SessionService/CustomerSessionPolicy에 적용했다. 별도 세션 저장소·블랙리스트·라이브러리는 추가하지 않는다. 금융권/거래소의 신원·승인 정책 검토는 기존 공통 로드맵을 따르며 이번 페이지 크기/역할은 자체 정책이다.

## 선택과 트레이드오프

- 본인 CUSTOMER 세션으로만 조회/회수한다. 별도 비밀번호 입력 없이 자기 세션을 끝낼 수 있지만 탈취 세션도 이 권한을 가진다. 계정 비밀번호 변경은 다음 별도 재인증 경계다.
- 갱신/로그인과 같은 사용자 행을 먼저 잠근다. 회수와 갱신의 옛 상태 재사용을 막는 대신 플랫폼 계정 작업의 DB 경합이 생긴다. Rust 체결 상태나 코어에는 잠금을 추가하지 않는다.
- 발급 시각/세션 UUID로25개씩 순회한다. 저장/인덱스 비용이 추가되지만 오프셋 중복/누락을 줄인다. 기기명/IP/마지막 활동을 수집하지 않았으므로 이를 가짜로 표시하지 않는다.
- 원문/해시/운영자 인증 키 지문을 조회하지 않는다. 클라이언트는 이 세션 목록만으로 토큰을 복원할 수 없다. 실제 종료 뒤에는 다음 요청의 인증이 실패한다.

## 구현과 API

`GET /api/customer/security/sessions`는 afterAt/afterId 커서를 받으며 생성/만료 시각·ACTIVE/REVOKED/EXPIRED·현재 세션 여부만 반환한다. 시각은 기존 refresh_tokens의 LocalDateTime 계약을 유지하고 임의로 UTC의 Z를 붙이지 않는다. 세션 발급 시각은 실제 기기 로그인/마지막 활동 시각이 아니다. 토큰 갱신도 기록을 만든다.

`DELETE /api/customer/security/sessions/{id}`는 본인 세션만 회수한다. 없는 세션과 다른 고객의 세션은 같은404다. 본인의 이미 회수된 다른 세션에 대한 반복 요청은 금융 효과 없이 같은 영수증을 반환한다. 현재 세션을 회수하면 requiresLogin=true이고 이후 그 JWT로는 재조회/재전송할 수 없다. 다른 고객/운영자/중지·만료 세션은 거부한다.

갱신이 먼저 완료하면 옛 세션의 회수 요청은 더 이상 본인 인증을 통과하지 못한다. 회수가 먼저면 옛 갱신 토큰은 재사용할 수 없다. 이를 새 세션까지 소급해 회수한 것으로 설명하지 않는다. 감사 실패는 변경도 롤백한다. 실제 호출이 없던 deleteExpiredTokens 저장소 메서드는 제거했다. 현재 보존 동작은 바꾸지 않고, 별도 보존/파기 정책 없이 인증 이력을 일괄 지우는 경로를 두지 않는다.

## 실제 코드와 필요한 원리

출처: [CustomerSessionControl.java](../../src/main/java/dustin/cex/domains/account/CustomerSessionControl.java#L38), 현재 소스의 부분 발췌.

```java
        // 로그인/갱신도 같은 사용자 행부터 잠근다. 회수와 갱신의 경쟁에서 한쪽의 옛 검사를 재사용하지 않는다.
        var rows=jdbc.queryForList("SELECT revoked FROM refresh_tokens WHERE user_id=? AND session_id=?",actor,target);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);
        boolean already=Boolean.TRUE.equals(rows.getFirst().get("revoked"));if(!already)jdbc.update("UPDATE refresh_tokens SET revoked=true,updated_at=? WHERE user_id=? AND session_id=?",Timestamp.valueOf(LocalDateTime.now()),actor,target);
        audit.record(actor,"CUSTOMER_SESSION_REVOKED","session:"+target,"SUCCESS",already?"ALREADY_REVOKED":"REVOKED");return new Revocation(target,target.equals(sid));
```

사용자 잠금은 sessions.require가 이미 획득했다. JDBC는 본인 사용자와 목표 세션을 함께 제한한다. 같은 계정 작업이 순서대로 처리되므로 회수/갱신이 동시에 성공했다고 거짓 설명하지 않는다.

출처: [CustomerSessionControl.java](../../src/main/java/dustin/cex/domains/account/CustomerSessionControl.java#L20), 현재 소스의 부분 발췌.

```java
    public record Session(UUID id,LocalDateTime createdAt,LocalDateTime expiresAt,State state,boolean current) {}
    public record Page(List<Session> items,Cursor next,LocalDateTime observedAt){public Page{items=List.copyOf(items);}}
```

record의 필드는 실제 JSON 응답 계약이다. 엔티티 전체를 반환하지 않아 tokenHash/운영자 인증 지문이 직렬화되지 않는다. TypeScript interface만 선언하는 것과 달리 여기서는 반환 객체의 필드 자체를 제한한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 실제 세션·Argon2·JWT/MVC·PostgreSQL16의14개·bootJar 통과, 실패/오류/제외0. 사용하지 않는 삭제 메서드를 제거한 뒤14개를 다시 통과했다. 원문 토큰/해시 비노출, 같은 시각의29개 기록 페이지, 다른 고객404, JWT 회수, 갱신 경쟁, 필수 감사 롤백과 실제 HTTP 역할/no-store를 확인했다. 실행기는 새 서버를 만들고 봇0명/엔진·Kafka 대역을 사용하며 소유 컨테이너를 정리한다.

기존 deprecated/unchecked/JVM 공유 경고는 남는다. 신규 라이브러리/화면/배포/성능 측정은 없고 기존 DB/서버·Rust 엔진은 변경하지 않았다. UX 기준은 별도 사용자 지시에 따라 단순하게 정리했으며 이 사이클의 프론트 구현/제품 테스트로 합산하지 않는다.

## 한계

기기/IP·마지막 활동·장기 보존/파기·전 세계 시간대 계약·고객 MFA/비밀번호 복구는 아직 별도다. 목록은 현재 상태를 관측하며 영구 감사 완료 체크포인트가 아니다. 고객 비밀번호 변경과 보안 통지·이력은 다음 단계에서 연결한다.

## 작성한 테스트 코드


[CustomerSessionControlIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerSessionControlIntegrationTest.java)

- `listContainsOnlyOwnMetadataAndMarksCurrent`: 본인2개 기록 중 현재1개를 표시하고 토큰 해시/키 지문/고객 식별·주소를 숨긴다.
- `revokedAndExpiredAreDistinguished`: 종료와 기한 만료를 구분한다.
- `pagesPreserveAllSameTimestampRows`: 동일 시각29개를 두 페이지로 중복/누락 없이 읽는다.
- `foreignAndAbsentTargetAreEquallyNotFound`: 다른 사람/없는 세션은 모두404다.
- `otherSessionRevocationCanBeReplayed`: 본인의 다른 세션 회수는 반복해도 같은 영수증/종료 상태다.
- `currentRevocationImmediatelyBlocksOldAccessJwt`: 현재 회수 후 JWT와 직접 서비스 조회는 거부한다.
- `revokedRefreshTokenCannotRotate`: 회수한 토큰으로 새 세션을 만들 수 없다.
- `revocationAuditFailureRollsBackFlag`: 감사 실패는 회수 상태도 롤백한다.
- `listAuditFailurePreventsResponse`: 필수 조회 감사 실패는 반환을 막는다.
- `operatorForeignAndDisabledSessionsCannotUseCustomerFlow`: 운영자/다른 세션/중지 계정은 고객 기능을 사용할 수 없다.
- `invalidCursorAndAmbientMutationAreRejected`: 부분/미래 커서와 외부 트랜잭션 참여를 거부한다.
- `concurrentRevocationHasOneEffect`: 동시 회수는 같은 영수증과 하나의 종료 효과다.
- `refreshRaceCannotRestoreRevokedSession`: 갱신/현재 회수의 경쟁에서는 한 쪽만 성공하고 원래 세션은 종료된다.
- `httpOwnReadRevokeAndRoleBoundary`: 실제 HTTP 조회/회수·역할·401·no-store를 확인한다.

## 관련 커밋과 증적

- [구현 cc3a0c8](https://github.com/GiHoon1123/Cex-Platform-Service/commit/cc3a0c8ebeea45f42b6ea4f603e50ee4319d3cc0)
- [격리 실행 증적](../reference/059-customer-session-control-verification-2026-10-25.json)

063의 [고객 패스키 로그인](063-customer-passkey-login.md)을 등록한 고객은 비밀번호 단계의 대기 세션으로 이 고객 보안 업무를 수행할 수 없다. 실제 서명 후 새 세션이 필요하며, 당시 검증 수치는 각 기록의 실행 시점으로 구분한다.
등록된 고객의 재로그인은 실제 새 서명 성공 전까지 기존 강한 세션을 회수하지 않는다. 대기 세션만 교체하므로 대기/강한 세션이 잠시 공존할 수 있다. 이 목록의 ACTIVE는 회수/만료 기준의 생존 상태이며 다중 인증 완료를 뜻하지 않는다.
