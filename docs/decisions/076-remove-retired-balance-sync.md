# 미사용 잔액 증감 경로 제거와 정산 시험 의존성 수정

고객 입금의 식별자 없이 잔액을 증감하던 미사용 외부 경로를 엔진과 플랫폼에서 제거한다. 과거 금융 저널은 보존하고, 주문·취소·시작 시 재입금 방지·정산 기록의 동작을 회귀 검증한다.

## 문제와 실제 참조

BalanceSyncService의 운영 호출자는 없었다. 엔진의 balances/sync HTTP는 user_id/mint/available_delta만 받아 같은 외부 입금인지 구분할 수 없었다. 응답 유실 뒤 변화량을 반복하면 중복 자산이 생길 수 있다. [엔진018의 식별자 결합 명령](../../../Cex-Engine/docs/decisions/018-idempotent-deposit-credit.md)을 준비한 상태에서 이 구형 경로를 신규 입금에 다시 연결하지 않도록 삭제했다.

실제 src와 프론트·테스트·실행기를 검색했다. 운영 Java 호출은 도우미→EngineHttpClient.syncBalance뿐이고 나머지는 시험 참조였다. 기존 봇 관리자가 이 도우미를 사용한다는001 문서 설명은 현재 코드와 맞지 않아 정정했다. 봇은 계속0명이다.

## 공식 패턴과 선택

[엔진018의 Stripe/Kafka 근거](../../../Cex-Engine/docs/decisions/018-idempotent-deposit-credit.md)처럼 금융 재시도는 안정적인 원본 식별자와 내용 결합이 필요하다. 단순 변화량 경로를 호환용으로 남기면 이 계약을 우회한다. 사용하지 않는 HTTP·도우미를 삭제하는 대신 실제 금융 기록의 재생 계약은 유지했다. 외부 API 제거와 과거 금융 사실 삭제를 구분한다.

검증 중 H2 정산 기록 시험 두 클래스가 074의 BitcoinSchemaInitializer를 그대로 생성하는 문제를 찾았다. H2는 제품의 PostgreSQL 전용 시간 타입/불변 제약 스키마를 처리하지 못해9개 기동 실패를 냈다. 이 문제는 이번 API 삭제의 기능 실패가 아니라 **074에서 새 초기화 의존성을 기존 H2 시험 범위에 반영하지 못한 회귀**다. 좁은074 검증에서는 해당 시험을 실행하지 않아 놓쳤다.

[Spring 공식 MockitoBean 문서](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-mockitobean.html)의 테스트 빈 대체 기능과 이미 설치된 타입을 사용했다. 기존 정산 시험은 원장/KYC 초기화를 대역으로 두고 정산 실행 기록만 다루므로 BTC 초기화도 같은 경계에서 대체했다. 제품 SQL을 H2로 우회하거나 불변 트리거를 끄지 않았다. 실제 주소 제약은074의 PG 시험이 검증하며 이번 H2 시험으로 대신했다고 주장하지 않는다.

## 트레이드오프와 변경 범위

- 호환 경로를 제거해 직접 변화량 요청은404가 된다. 외부 수동 운영 도구도 이 경로를 사용할 수 없다. 실제 참조가 없음을 확인했으며 숨은 사용자 데이터나 DB 기록을 지우지 않았다.
- 사용하지 않는 도우미 전용 시험4개는 제품과 함께 삭제했다. 시작 시 금융 명령0회 시험3개는 유지하고 특정 삭제 메서드 대신 모든 금융 호출을 감시하도록 강화했다.
- H2 정산 기록 시험은 빠르고 기존 단위 범위를 유지하지만 PostgreSQL 제약을 보장하지 않는다. 그래서 제품 스키마 검증을 약화하는 대안 대신 시험 초기화 의존성만 분리했다.
- 엔진의 내부 Balance 명령과 과거 WAL 변형은 재생/시험에 필요해 보존한다. 새 외부 입금의 대체 경로나 플랫폼 DB 복구 폴백으로 사용하지 않는다.
- 과거 실행 명령과 수치는 역사로 남기고 삭제한 파일의 링크는 당시 Git 소스로 연결했다. 현재 코드 설명은 실제 남은 시험 코드로 바꿨다. 과거 JSON 증적은 덮어쓰지 않았다.

## 삭제와 유지한 실행 경로

Java BalanceSyncService, EngineHttpClient.syncBalance/buildSyncBalanceRequest, BalanceSyncServiceTest를 삭제했다. EngineHttpClient의 현재 주문·취소 호출과 서비스 신원 헤더는 남겼다. 검증 실행기의 삭제 시험 선택도 제거했다.

엔진 balance_handler와 라우트 등록을 삭제하고 현재 활성 주문·취소만 남도록 라우터의 비활성 주석 코드를 정리했다. 서비스 신원 검증·헬스·독립 감사 조회는 유지한다. [엔진019](../../../Cex-Engine/docs/decisions/019-remove-retired-balance-http.md)에 실제 라우터/404 회귀를 연결한다.

## 실제 코드와 문법

출처: [PlatformBalanceStartupTest.java](../../src/test/java/dustin/cex/shared/services/PlatformBalanceStartupTest.java#L126), 현재 소스의 부분 발췌.

```java
    private void assertNoFinancialCommandSent() {
        // Spring의 생성 후 초기화만 허용한다. 현재/미래의 금융 명령 호출은 모두 거부한다.
        // 삭제한 메서드 이름에 의존하지 않아도 시작 훅이 어떤 명령을 추가하는지 검출한다.
        assertThat(org.mockito.Mockito.mockingDetails(engineHttpClient).getInvocations())
                .allSatisfy(call -> assertThat(call.getMethod().getName()).isEqualTo("init"));
    }
```

Mockito 호출 이력에서 init만 허용한다. 금융 명령이 새 이름으로 추가돼도 준비 이벤트에서 호출하면 시험이 실패한다. 생성 후 초기화를 금융 거래로 오인하지 않고, 특정 삭제 메서드에 대한 verify 코드를 남기지 않는다.

출처: [EngineHttpClientTest.java](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java#L140), 현재 소스의 부분 발췌.

```java
            var client=client(server,2000);submit(client);client.cancelOrder(123L,101L,"BTC/USDT");
            assertThat(seen).containsExactly("isolated-engine-service-token-32-bytes","isolated-engine-service-token-32-bytes");
```

실제 루프백 HTTP 서버에서 주문/취소 두 요청의 서비스 신원 헤더를 관찰한다. 고객 JWT를 서비스 키로 대체해 보내지 않으며 삭제한 변화량 요청을 더 이상 시험하지 않는다.

출처: [TradeSettlementFailureTest.java](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementFailureTest.java#L47), 현재 소스의 부분 발췌.

```java
    // 정산 기록 전용 H2 시험은 BTC 소유권의 PostgreSQL 불변 제약 초기화도 실행하지 않는다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean dustin.cex.domains.bitcoin.BitcoinSchemaInitializer bitcoinSchema;
```

MockitoBean은 해당 시험 컨텍스트의 빈만 대체한다. 제품 코드의 스키마 초기화와 PostgreSQL 제약은 그대로이며 실제 BTC 주소/금액 보장에 대한 시험은 별도다.

## 작성·수정한 테스트

| 파일/함수 | 시나리오·기대 결과 |
| --- | --- |
| [엔진 routes/tests.rs](../../../Cex-Engine/src/routes/tests.rs) retired_balance_delta_route_is_absent_for_all_service_identities | 새1개. 유효 서비스 키/무키로 실제 형식의 증감 본문을 보내도404 |
| 같은 파일 trading_routes_still_validate_request_bodies | 현재 주문 경로의 잘못된 본문은422로 거부. 삭제 경로를 활성 경로 목록에 남기지 않음 |
| [PlatformBalanceStartupTest](../../src/test/java/dustin/cex/shared/services/PlatformBalanceStartupTest.java) readyEventDoesNotSendExistingBalancesAsDeposits | 실제 서비스 패키지 스캔/준비 이벤트 뒤 금융 호출0회·기존 잔액 불변 |
| 같은 파일 repeatedReadyEventsDoNotReplayDeposits | 준비 이벤트3회에도 금융 호출0회·DB 불변 |
| 같은 파일 restartingPlatformContextDoesNotReplayDeposits | 컨텍스트3회 재생성에도 금융 호출0회·DB 불변 |
| [EngineHttpClientTest](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java) allEngineCommandsCarryServiceIdentity | 남은 주문/취소 요청 각각 올바른 서비스 신원 헤더 |
| [TradeSettlementFailureTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementFailureTest.java) recordAndGetFailures/getFailuresByDateEmpty/recordFailureStepWithoutUserId/recordFailureWithNullErrorMessage | 실패 기록 저장·조회·빈 결과·사용자 미지정/오류문구 미지정 조건4개를 H2 전용 경계에서 다시 기동·검증 |
| [TradeSettlementRunServiceTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementRunServiceTest.java) runLifecycleSuccess/runLifecycleFail/failRunWithNullOrEmptyFailedUserIds/updateCompletedStepWithInvalidRunId/parseFailedUserIdsInvalidJson | 성공/실패 실행·빈 실패 사용자·없는 실행·잘못된 JSON 조건5개를 기존 기대대로 검증 |

## 실행과 결과

```bash
# 플랫폼의 실행 소유 PG와 H2·루프백 HTTP 시험
python3 scripts/verify_engine_inbox.py
# 엔진의 실행 소유 PG·임시 자체 저널
python3 scripts/verify_order_safety.py
# 별도 실행 소유 PG·Kafka·엔진/플랫폼 전체 거래
python3 scripts/verify_market_path.py
```

각 스크립트는 해당 저장소에서 실행했다. 상위 실행은 개인 CDD/EDD/Didit/메일/BITCOIN 환경을 제거했다. 첫 Java179개 중 정산 기동9개가 실패했다. 두 H2 컨텍스트 경계를 고친 뒤 해당9개를 먼저 통과시키고 전체179개를 다시 통과시켰다. 실패/중간 실행을 최종 합계에 더하지 않는다.

**최종 Java179개 + Rust 고유148개(선택 실행161회) + 실제 시장 연결1개 = 고유328개 통과. 최종 실패/오류/제외0개.** 전체 테스트 소스 컴파일, Rust 제품 build, Java bootJar/operatorAdminJar 통과다. 새 BTC 입금이 원장까지 반영되는 시험은 아니며 기존 거래가 살아 있는지를 확인한 연결 시험이다. 봇0·소비자 자동 시작 금지·소유 자원 종료/제거를 확인했다.

기존 Java deprecated/unchecked/JVM 계측 안내, Rust bin59개(중복7개 포함)·SQLx0.6.3 미래 호환 경고가 남아 있다. 새 의존성/보안 전체 감사/처리량 측정은 없다.

## 다음 연결

신규 입금은 불변 체인 관찰/확정 근거·안정적 입금 원본 ID·엔진 멱등 명령·확정 Kafka 결과·플랫폼 영속 수신/원장 반영을 연결해야 한다. 이번 삭제만으로 그 흐름이 완료된 것이 아니다. 고객 주소 발급은074 그대로, 내부 반입 명령은 엔진018 그대로다.


## 관련 커밋과 증적

- [구현 0e937b5](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0e937b5aef155bc0530688124720d1a3c6b1cfd5)
- [격리 실행 증적](../reference/076-remove-retired-balance-sync-verification-2026-10-25.json)

- [H2 시험 경계 수정 7486c3f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7486c3f)
- [엔진 경로 제거 417613a](https://github.com/GiHoon1123/Cex-Engine/commit/417613a)
