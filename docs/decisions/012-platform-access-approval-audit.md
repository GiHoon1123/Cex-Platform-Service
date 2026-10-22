# 고객 세션 회수와 운영 요청·승인·감사 경계

인증 없이 노출된 정산 API를 닫고, 회수 가능한 고객 세션과 사람 단위로 분리한 운영 승인을 구현했다. 신규 주문 제한/해제를 첫 승인 업무로 연결했다. 체결 코어는 고객 역할·승인 정책을 알지 않는다.

[근거·법령·인증·제품 사례와 미충족 목록](../reference/access-control-compliance.md) · [문서 목차](../README.md)

## 문제와 기존 동작

기존 필터는 `/api/settlement` 전체와 로그아웃을 공개했고, 공개 체결 응답에는 고객·주문 식별자가 포함됐다. JWT 서명과 만료만 확인하므로 로그아웃이나 운영 권한 회수를 기존 접근 토큰에 반영할 수 없었다. 갱신 토큰의 동시 사용을 직렬화하지 않았으며, 인증 예외 처리 범위에 컨트롤러 실행까지 들어 있어 업무 장애가 인증 실패로 바뀔 수 있었다. 기본 JWT 비밀키도 코드에 있었다.

운영 역할·불변 승인 대상·자기 승인 금지·보안 감사의 영속 기록이 없었다. 엔진 HTTP 명령은 플랫폼 신원을 증명하지 않고 `user_id`를 전달할 수 있었다.

## 기존 제품과 공식 자료의 접근

업비트·Coinbase는 용도별 권한과 API 호출 경계를 공개하고, IBKR은 사용자별 기능 권한과 변경 확인 절차를 제공한다. OWASP는 서버에서 승인 내용을 고정·검증하고 이전 경로로 우회하지 못하게 하며, 비밀정보를 감사 로그에서 제외하도록 설명한다. 이 근거의 적용 범위와 국내 신고·인증·개인 자격증의 차이는 [근거 문서](../reference/access-control-compliance.md)에 기록했다.

Spring Security의 경로 인가를 사용하고, 설치된 JJWT 0.12.5와 Argon2 2.11을 유지했다. 인증 원시 기능이나 HTTP 보안 체인을 새로 구현하지 않았다. 실제 JJWT 타입을 확인했으며, `sig().clear()`가 0.12.5에서 빈 레지스트리를 거부하는 것을 테스트에서 발견해 서명 검증 뒤 허용 알고리즘을 명시적으로 검사한다.

## 선택과 트레이드오프

| 선택 | 얻는 점 | 비용·제약과 검토한 대안 |
|---|---|---|
| API 전용 Spring Security, 명시한 메서드/경로만 허용 | 기본 거부와 역할 검증, 로그인 화면 없는 JSON 오류 | 필터 순서와 정책 테스트 필요. 자체 경로 필터 확장은 채택하지 않음 |
| 매 요청 현재 사용자·영속 세션 확인 | 로그아웃·비활성화·역할 회수 즉시 반영 | DB 읽기·가용성 의존. 서명만 확인하는 완전 무상태 토큰 대신 회수를 선택 |
| 15분 접근 토큰과 일회성 갱신 토큰 교체 | 용도·발급자·수신자·알고리즘·수명 검사, 동시 갱신 한 번만 성공 | 기존 토큰은 사용할 수 없음. 재로그인 필요. 로그인은 기존 정책대로 단일 활성 세션 |
| 요청/승인 인물 분리와 10분 불변 요청 | 동일인의 여러 계정·변경된 대상·중복 승인 차단 | 인사 신원 매핑, 다른 사람의 승인과 운영 대기 비용. 자유로운 범용 명령 실행기는 만들지 않음 |
| 감사와 중요 변경의 같은 트랜잭션 | 감사 장애 때 승인·주문·예약·원장이 함께 롤백 | 감사 DB 장애는 중요 업무 가용성을 낮춤. 감사 실패를 무시하는 대안은 채택하지 않음 |
| 실패 5회/15분 제한 | 로그인·승인 비밀번호 무한 추측 제한 | 계정 잠금을 통한 서비스 방해 가능. MFA·앞단 요청량 제한·복구 절차를 대체하지 않음 |
| 고객의 신규 예약만 제한 | 제한 변경과 새 자산 예약의 순서를 정의 | 고객 행 잠금 비용. 기존 접수 조회·취소·영속 재전송은 계속 진행 |
| 플랫폼→엔진의 별도 공유 서비스 키 | 사용자 정책 없이 내부 호출자 경계를 보호 | 단일 플랫폼 신원만 구분. 네트워크·TLS·키 저장/교체 운영 필요 |

## 구현과 데이터 흐름

### API 인증과 권한

- [SecurityConfiguration](../../src/main/java/dustin/cex/domains/security/SecurityConfiguration.java): 폼 로그인·HTTP Basic·기본 로그아웃·리다이렉트·요청 캐시를 끈다. 미인증은 JSON 401, 권한 부족은 JSON 403이다. `/login` 페이지도 제공하지 않는다. CSRF 비활성화는 자동 전송 쿠키를 쓰지 않는 Bearer 헤더 전용 조건에 한정한다.
- [JwtAuthenticationFilter](../../src/main/java/dustin/cex/domains/auth/middleware/JwtAuthenticationFilter.java): 인증과 세션 검증만 수행하고, 업무 예외를 인증 실패로 바꾸지 않는다. 검증한 사용자 ID와 세션 ID만 컨트롤러에 제공한다. 요청 ID는 서버에서 생성한다.
- [JwtService](../../src/main/java/dustin/cex/domains/auth/service/JwtService.java): HS256·발급자 `cex-platform`·수신자 `cex-api`·용도 `access`·세션 ID·최대 15분 수명을 검증한다. JWT에 역할을 고정하지 않는다. `JWT_SECRET`은 필수 외부 설정이며 하드코딩 기본값을 삭제했다.
- [SessionService](../../src/main/java/dustin/cex/domains/security/SessionService.java): 현재 사용자 활성 상태와 세션의 사용자·회수·만료를 확인한다. OSIV를 꺼 필터에서 읽은 엔티티가 업무 트랜잭션의 최신 권한 재검사를 가리지 않게 했다.
- [AuthService](../../src/main/java/dustin/cex/domains/auth/service/AuthService.java): 사용자 행 → 세션 행 순서로 잠근다. 갱신 토큰의 소유자만 먼저 조회해, 잠금 전에 로드한 토큰 엔티티의 오래된 값이 재검사를 가리지 않게 했다. 로그아웃은 현재 접근 세션과 갱신 토큰의 소유자·세션이 모두 일치해야 한다.

`CUSTOMER`는 자신의 주문·잔액·포지션·체결에 접근한다. 전체 고객 식별자를 포함한 기존 체결 조회는 `AUDITOR`로 제한하고, 화면용 공개 시세는 `/api/market/**`를 사용한다. 정산 보고서는 운영자·승인자·감사자만 조회한다. 이전의 승인 없는 수동 정산/검증 POST 메서드는 삭제했다. 정산 서비스·스케줄러·이력은 유지하며, 승인된 정산 재실행은 후속 업무다.

공개 가입은 역할·운영 인물 ID를 받지 않고 항상 `CUSTOMER`를 생성한다. 역할을 올리는 공개 API는 없다. 비밀번호·접근/갱신 토큰·해시가 Lombok의 자동 문자열 표현에 들어가지 않게 했다.

### 최초 운영 계정 부여

[OperatorBootstrapRunner](../../src/main/java/dustin/cex/domains/security/OperatorBootstrapRunner.java)는 `operator.bootstrap.enabled=true`로 시작한 일회성 프로세스에서만 실행한다. 기존의 활성 고객 계정 중 금융 이력·잔액이 없는 전용 계정만 `OPERATOR`, `APPROVER`, `AUDITOR`로 설정한다. 기존 세션 회수·역할·인물 ID·감사는 함께 커밋하고 해당 프로세스를 종료한다.

```sh
java -jar build/libs/cex-0.0.1-SNAPSHOT.jar \
  --operator.bootstrap.enabled=true \
  --operator.bootstrap.user-id=운영전용계정ID \
  --operator.bootstrap.person-id=검증한실제인물UUID \
  --operator.bootstrap.role=OPERATOR \
  --server.address=127.0.0.1 --server.port=0 \
  --bot.bot-count=0 --order.dispatch.enabled=false \
  --spring.kafka.listener.auto-startup=false
```

DB·Kafka·필수 비밀키 환경은 별도로 준비한다. 이미 실행 중인 서비스에 붙여 역할을 자동 부여하는 기능이 아니다. OS·배포·DB 접근권한을 가진 관리자와 실제 인물 매핑 절차가 신뢰 경계다. 이 명령만으로 신원이 증명되는 것은 아니다. 역할 변경·퇴사 회수의 전체 운영 절차와 MFA 등록은 남아 있다.

### 요청 → 승인 → 신규 주문 제한

[OperatorApprovalService](../../src/main/java/dustin/cex/domains/security/OperatorApprovalService.java)의 첫 업무는 고객의 **신규 주문 제한/해제**다.

1. 운영자가 `GET /api/admin/customers/{id}`에서 고객 상태 버전을 확인한다.
2. `POST /api/admin/trading-restrictions`에 `targetUserId`, `expectedVersion`, `restricted`, `reason`을 보낸다.
3. 서버는 요청 ID·인물 ID·대상 버전·제한 여부·사유·만료를 고정하고 SHA-256 요약을 반환한다. 요청 내용을 바꾸는 API는 없다.
4. 다른 실제 인물인 승인자가 요청 목록에서 내용을 확인하고 `/api/admin/requests/{id}/approve` 또는 `/reject`에 `payloadHash`와 재인증 비밀번호를 보낸다.
5. 요청 행과 관련 사용자 행을 잠근 뒤 현재 역할·활성 상태·동일인 여부·만료·해시·대상 버전을 재검사한다. 성공 효과와 감사 기록은 같은 트랜잭션이다.
6. [TradingAccessPolicy](../../src/main/java/dustin/cex/domains/security/TradingAccessPolicy.java)가 새 주문의 자산 예약 직전에 고객 행을 잠가 제한을 확인한다. 확정된 같은 요청 키의 재조회·기존 취소·영속 전송 워커에는 새 제한을 소급 적용하지 않는다.

요청 해시는 승인 내용을 식별하는 값이며 인증 수단이나 디지털 서명이 아니다. 정적 비밀번호 재확인은 MFA가 아니다. 승인자가 내용을 검토하고 승인해야 한다는 업무 절차도 필요하다.

### 감사

[AuditService](../../src/main/java/dustin/cex/domains/security/AuditService.java)와 [스키마](../../src/main/resources/security/schema.sql)는 행위자 ID·당시 역할/인물 ID·상관 ID·접속지·대상·작업·결과·시각·고정 사유 코드를 추가한다. 임의 `X-Forwarded-For`는 신뢰하지 않는다. 조회 페이지는 50건, 최대 페이지 번호도 제한한다.

성공한 인증 변경·승인 효과·주문 예약은 같은 트랜잭션의 감사 기록이 실패하면 롤백한다. 거부 감사는 업무 롤백과 분리해 보존한다. 로그인 거부는 실패 기록을 커밋한 뒤 예외를 반환한다. 취소는 외부 호출 전에 **의도**를 영속 기록하고 최종 금융 결과는 엔진 WAL·Kafka 기록과 주문 ID로 연결한다. HTTP 인가의 `ALLOWED`는 업무 최종 성공을 뜻하지 않는다.

트리거는 감사 UPDATE/DELETE/TRUNCATE와 승인 내용 변경·완료 요청 재사용을 거부한다. DB 소유자·슈퍼유저가 트리거를 무력화하는 공격까지 막지는 못한다. 현재 개발 환경의 DB 관리자 계정과 운영용 최소 권한 계정 분리·외부 증적 보관·보존 잠금은 별도 작업이다.

## 이해에 필요한 Java·트랜잭션 원리

- `SecurityFilterChain`은 TypeScript 서버의 인증·인가 미들웨어 구성을 프레임워크에 맡기는 것과 유사하다. 인증은 “누구인가”, 인가는 “이 경로를 실행할 수 있는가”를 각각 판단한다.
- `record`는 생성자·접근자를 제공하는 값 자료형이다. Java의 `record`만으로 DB 불변성을 보장하지 않아 트리거와 서비스의 변경 금지를 함께 둔다.
- `@Version`은 수정 시 증가하는 상태 버전이다. 승인 요청 뒤 고객 상태가 바뀌면 예전 요청을 거부하는 근거로 사용한다. 이를 엔진 코어의 락으로 전달하지 않는다.
- `TransactionTemplate.execute`는 콜백의 성공/예외에 따라 커밋/롤백한다. 엔진 HTTP 호출은 금융 DB 커밋 후 실행해 네트워크 대기 중 금융 락을 잡지 않는다.
- `REQUIRES_NEW`는 별도 트랜잭션으로 거부 감사를 보존한다. `MANDATORY`는 예약·원장·감사 같은 변경이 기존 업무 트랜잭션 밖에서 실행되지 못하게 한다. 프록시를 거치지 않는 자기 호출에는 새 트랜잭션이 자동 적용되지 않으므로 호출 위치를 함께 읽어야 한다.
- 여러 사용자 행을 잠글 때 ID 순서를 고정해 승인끼리의 교착 위험을 줄인다. 사용자 상태 잠금은 플랫폼 정책·금융 정합성을 위한 것이며 Rust 매칭 상태는 공유하지 않는다.

## 작성·수정한 테스트

[PlatformSecurityTest](../../src/test/java/dustin/cex/domains/security/PlatformSecurityTest.java)는 실행 소유 PostgreSQL에서 실제 Spring Security·JPA·JDBC·감사 트리거를 사용한다. 엔진·Kafka 전송만 모킹하고, 별도 실제 프로세스 회귀로 외부 연결을 확인했다.

| 테스트 함수 | 기대 결과 |
|---|---|
| `apiNeverRedirectsToLoginPageOrChallengesBasic` | 401 JSON, 로그인 페이지·리다이렉트·Basic 인증 요구 없음 |
| `settlementAndPrivateTradeDataAreNotPublic` | 미인증 정산·비공개 체결 조회 거부 |
| `oldUnapprovedSettlementWritesAndUnknownRoutesAreDenied` | 기존 무승인 변경 경로·미검토 경로 차단 |
| `auditUsesPeerAddressAndPreservesRoleSnapshot` | 전달 헤더 위조 무시·역할 스냅샷 기록 |
| `customerAndOperatorCannotEscalateRoles` | 고객·운영자의 역할 간 권한 상승 차단 |
| `roleChangeAndDisableAffectExistingAccessToken` | 이미 발급한 토큰에도 현재 권한·활성 상태 반영 |
| `logoutRevokesAccessAndRefreshTogether` | 로그아웃 후 접근·갱신 동시 차단 |
| `logoutCannotRevokeAnotherPersonsSession` | 다른 고객의 세션 회수 금지 |
| `refreshRotationHasOneWinnerUnderConcurrency` | 동시 갱신 단일 성공·이전 접근 세션 회수 |
| `fakeSessionAndWrongTokenPurposeAreRejected` | 없는 세션·잘못된 발급자/용도 거부 |
| `wrongAudiencePurposeAlgorithmAndTokenLifetimeAreRejected` | 수신자·용도·알고리즘·수명·만료 검증 |
| `missingAndWeakSecretsFailImmediately` | 짧은 비밀키 초기화 거부 |
| `requiredSigninAuditFailureDoesNotRevokeOrIssueSessions` | 로그인 감사 장애 때 기존 세션 보존·새 세션 롤백 |
| `bootstrapIsExplicitRevokesSessionsAndAuditsRoleGrant` | 명시적 초기 권한 부여·감사·세션 회수·해당 프로세스 종료 |
| `bootstrapRejectsCustomerWithFinancialState` | 금융 상태가 있는 고객의 운영 계정 전환 거부 |
| `bootstrapRejectsZeroBalanceWithHistoricalLedgerEntries` | 잔액이 0이어도 원장 이력이 있으면 운영 계정 전환 거부 |
| `publicSignupCannotChooseOperatorRole` | 역할 입력으로 가입 권한 상승 불가 |
| `approvalRunsThroughActualHttpAndAudit` | 실제 API 요청→다른 사람 승인→효과·감사 연결 |
| `missingApprovalFieldsAndUnboundedPagesAreRejected` | 필수 승인 필드·조회량 제한 검사 |
| `differentAccountsOfSamePersonCannotApprove` | 동일인의 다중 계정 승인 금지 |
| `changedPayloadAndWrongPasswordAreRejected` | 해시 불일치·재인증 실패 거부 |
| `changedTargetVersionAndRevokedRequesterInvalidateApproval` | 대상 변경·요청자 권한 회수 후 실행 금지 |
| `expiredApprovalCannotExecute` | 만료 요청 실행 금지 |
| `restrictionCanBeLiftedOnlyByAnotherApprovedRequest` | 새 승인 요청을 통한 제한 해제 |
| `restrictionStillAllowsOwnCancellationButNeverAnotherCustomersOrder` | 제한 중 본인 취소 허용·다른 고객 주문 차단 |
| `loginPasswordGuessingIsBoundedAndDoesNotRevokeExistingSession` | 로그인 실패 제한·기존 세션 유지 |
| `approvalPasswordGuessingIsBoundedWithoutApplyingEffect` | 재인증 실패 제한·효과 없음 |
| `completedRequestCannotExecuteAgain` | 완료 요청 재사용 거부 |
| `concurrentApprovalsExecuteExactlyOnce` | 동시 승인 효과·성공 감사 단 한 번 |
| `rejectionDoesNotApplyRestrictionAndCannotBeReused` | 거부 상태의 무효과·재사용 금지 |
| `mandatoryAuditFailureRollsBackApprovalAndCustomerState` | 감사 장애 시 승인 상태·고객 변경 동시 롤백 |
| `auditAndApprovalPayloadCannotBeMutated` | DB 변경·삭제·절단·요청 내용 변경 차단 |
| `restrictionBlocksNewReservationButKeepsOriginalReplay` | 제한 뒤 새 예약 차단·기존 접수 조회 유지 |
| `auditFailureRollsBackOrderReservationLedgerAndDispatch` | 감사 장애 시 주문·예약·원장·전송 롤백 |
| `auditReadsAreBoundedAndContainNoCredentials` | 감사 조회량 제한·비밀정보 배제 |

[EngineHttpClientTest.allEngineCommandsCarryServiceIdentity](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java)는 주문·취소·잔액 요청 모두 별도 서비스 키를 전달하는지 실제 HTTP 서버로 확인한다. 기존 주문 저장 테스트에는 정책 의존성을 명시해 금융 단위 테스트와 정책 통합 테스트를 구분했다. H2 정산 테스트는 PostgreSQL 전용 감사 스키마를 모킹하며 실제 트리거 보장은 PostgreSQL 테스트에서 확인한다.

[MarketPathTest](../../src/test/java/dustin/cex/integration/MarketPathTest.java)와 실제 프로세스 실행기는 실제 영속 세션·새 JWT 계약·별도 엔진 키를 사용하도록 수정했다. 인증을 우회하는 테스트 전용 제품 경로는 추가하지 않았다.

## 실행 명령과 결과

실행 소유 PostgreSQL 컨테이너·Kafka·프로세스·임시 WAL만 사용했다. 기존 로컬 서비스와 금융 DB는 변경하지 않았고, 봇은 계속 비활성화했다. `MarketPathTest`에서의 직접 봇 서비스 호출은 기존 통합 검증의 격리된 시나리오다.

| 명령 | 결과 |
|---|---|
| `python3 scripts/verify_platform_security.py` | 보안 통합 35개 통과, 실패·제외 0 |
| `python3 scripts/verify_engine_inbox.py` | 기존 금융·주문·HTTP 회귀 176개 통과, 실패·제외 0 |
| `python3 scripts/verify_order_idempotency.py` | 실제 JAR 프로세스 10개 시나리오 통과 |
| `python3 scripts/verify_market_path.py` | 실제 플랫폼·엔진·Kafka·대사·별도 대사 CLI 경로 1개 통과 |
| `python3 ../Cex-Engine/scripts/verify_durable_acceptance.py` | 실제 플랫폼·엔진 응답 유실/재시작 5개 시나리오 통과 |
| `python3 ../Cex-Engine/scripts/verify_engine_output.py` | WAL·출력·Kafka 장애·강제 종료/정상 종료 6개 시나리오 통과 |

처음 작성한 검증에서는 JJWT 알고리즘 목록 초기화 오류, 테스트의 오래된 버전 엔티티 재저장, 테스트 DTO 식별자 타입 오류를 발견해 수정했다. 최종 성공 결과만 나열해 초기 실패를 숨기지 않는다. Java의 기존 deprecated API 경고와 Rust의 기존 경고·향후 비호환 알림은 남아 있다. 전체 구형 통합 테스트를 공유 DB에서 실행하지 않았다.

## 운영 전 남은 범위와 다음 순서

1. **지원되는 프레임워크·의존성으로 교체하고 보안 공지 적용성을 재검토한다.** 기존 Spring Boot 3.2.5 관리 버전으로 Spring Security 6.2.4가 선택된다. 현재 지원 버전이라고 표현하지 않는다. [공식 지원 종료 안내](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)상 3.5 계열도 OSS 지원이 종료돼 단순히 3.5로 올리는 것으로 닫을 수 없다. 이 작업은 다음 최우선 선행 과제이며 운영 배포 조건이다. [공식 보안 공지](https://spring.io/security/)의 영향 버전과 활성 기능을 각각 대조해야 한다. WebFlux 정적 자원 취약점은 MVC API에 그대로 적용된다고 단정하지 않고, 미사용 OAuth 동의 화면·DPoP·WebAuthn 공지도 전체 안전 판정 근거로 사용하지 않는다. **이번 사이클은 전체 의존성 취약점 검사를 완료하지 않았다.**
2. 운영자 피싱 저항성 MFA, 기기 등록·분실·복구·퇴사 회수, 권한 재부여에 대한 별도 승인과 이력.
3. KYC/AML 조사 상태에 따른 거래·출금 제한, 정산 재실행·원장 정정·출금의 업무별 승인과 감사. 수동 정산 변경 API의 삭제를 정산 업무 전체 제거로 해석하지 않는다.
4. 인사 신원 검증·계정 비공유·실제 복수 인력·DB 최소 권한·비밀키 보관/교체·TLS·내부 네트워크 경계·앞단 요청량 제한·관측/경보.
5. 데이터별 보존 근거와 기산점, 개인정보 최소화·파기, 외부 감사 보존과 점검 증적. 디스크 손실에 대한 복제·백업은 이번 범위에서 문서 필요 조건만 남긴다.

현재 DB·공유 키·동일 호스트 구성이 내부자·관리자 침해까지 해결한다고 설명하지 않는다. 금융권 수준의 운영 검증·법적 검토·인증 심사는 별도 완료 조건이다.

## 관련 커밋과 문서

기능과 회귀, 문서를 목적별로 커밋하고 공통 사이클 기록에 실제 해시를 남긴다. 엔진 서비스 경계는 [엔진 결정 016](../../../Cex-Engine/docs/decisions/016-platform-service-boundary.md), 공통 결과는 [접근 통제 사이클](../../../docs/roadmap/access-approval-audit.md)을 따른다.

- 플랫폼 기능·회귀: [`892b23f`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/892b23f8e99b100fa8504731870ce9f15b27f9f7)
- 엔진 서비스 경계: [`b84f1dc`](https://github.com/GiHoon1123/Cex-Engine/commit/b84f1dc37985e0f0a57297451a8139274e165420)
