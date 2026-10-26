# 시장가 예산·IOC 종료·미사용 예약금을 함께 정산하기

시장가 매수의 수량 미정, 일부 체결 후 종료, 체결 없는 종료를 처리한다. 엔진의 영속 종료 결과를 근거로 주문 상태와 미사용 예약을 함께 반영하고 실제 프로세스에서 재발행까지 검증한다. 작성일은 2026-10-22다.

## 문제와 발견한 정밀도 불일치

시장가 매수는 수량 대신 결제 예산을 지정한다. 불변 원본의 수량은 `null`, 상태 행의 초기 수량은 `0`이다. 이를 수량 상한으로 판단하면 정상 체결도 거부한다. 엔진이 시장가 잔량을 메모리에서 반환해도 플랫폼에는 종료 이벤트가 없어 예약이 남을 수 있었다.

실제 통합 검증에서 별도 문제도 재현했다. 예산 1을 가격 3에서 사용하면 체결 대금은 **0.999999999**, 반환액은 **0.000000001**이다. `Order.filledQuoteAmount`가 `NUMERIC(20,8)`이라 저장 후 대금이 1로 반올림됐다. 종료 검증은 이 불일치를 감지해 소비를 중단하고 반환을 확정하지 않았다. 공통 금액 계약인 **NUMERIC(30,9)**로 맞추고 저장 후 재조회하는 회귀를 추가했다. 실패를 무시하거나 허용 오차로 통과시키지 않았다.

## 공식 자료와 선택

[바이낸스 시장가 FAQ](https://github.com/binance/binance-spot-api-docs/blob/master/faqs/market_orders_faq.md)와 [주문 상태 정의](https://github.com/binance/binance-spot-api-docs/blob/master/enums.md)를 확인했다. 이용 가능한 유동성 안에서 처리하고 주문 실행 규칙으로 끝난 미체결 부분을 만료로 구분하는 패턴을 채택했다. 확인일은 2026-10-22다. 해당 제품의 내부 메시지 구조나 이 프로젝트의 정밀도 정책까지 같다고 해석하지 않는다.

| 선택 | 얻는 것 | 감수하는 비용 |
| --- | --- | --- |
| 개별 체결 후 별도 시장가 종료 이벤트 | 체결 0건도 주문 종료·반환을 확정 | 시장가당 이벤트 1개 증가 |
| 원본 매수 예산·매도 수량을 상한으로 검증 | 수량 0 오해와 과도한 지출·체결 방지 | 매수 예산은 불변 원본 조회가 필요 |
| 반영한 합계·체결 건수와 종료 결과 비교 | 누락·잘못된 반환을 확정하지 않음 | 잘못된 결과는 소비 중단·운영 해결 필요 |
| 상태·반환·수신 키를 같은 트랜잭션에 기록 | 재발행의 이중 반환 방지 | 디비 행 락과 순차 소비 유지 |
| `filled`와 `expired` 구분 | 수량·예산 소진과 미사용 종료 구분 | 아주 작은 잔여 예산도 만료로 표시 |

시장가는 코어가 즉시 처리하고 오더북에 남기지 않는다. 개별 체결 반영 중의 `partial`은 종료 결과를 아직 반영하지 않았다는 의미도 가진다. 플랫폼의 최종 `filled`·`expired`는 마지막 종료 이벤트를 검증한 뒤에만 설정한다. 실패 시 미사용 예약을 임의 해제하지 않는다.

## 구현과 API 계약

- [금융 소비자](../../src/main/java/dustin/cex/shared/kafka/KafkaOrderEventConsumer.java): 매수는 불변 예산, 매도는 불변 수량을 검사한다. 시장가의 개별 체결은 아직 최종 상태로 만들지 않는다. `market_order_completed`에서 소유자·시장·방향·원본 예약·누적 수량·누적 대금·반환액·체결 건수·상태를 확인한다.
- [체결 저장소](../../src/main/java/dustin/cex/domains/trade/repository/TradeRepository.java): 시장가 주문이 참여한 실제 체결 건수를 조회한다. 종료를 먼저 소비하거나 체결을 빠뜨리면 합계·건수 검사가 실패한다. [체결 모델](../../src/main/java/dustin/cex/domains/trade/model/entity/Trade.java)에 주문 식별자가 선두인 인덱스를 명시했다. 건수 조회의 전체 이력 탐색을 줄이는 대신 인덱스 저장 공간·쓰기 비용이 생긴다.
- [주문 모델](../../src/main/java/dustin/cex/domains/order/model/entity/Order.java): 누적 대금을 소수 9자리로 저장한다. 새 스키마의 상태 제약에 `expired`를 포함한다.
- [응답 모델](../../src/main/java/dustin/cex/domains/order/model/dto/OrderResponse.java): 시장가 매수의 원본 `quoteAmount`를 십진 문자열로 반환한다. `amount=0`은 수량 미정이고 실제 수량은 `filledAmount`, 실제 사용 대금은 `filledQuoteAmount`다. 시장가 완료 판단에 `amount == filledAmount`를 사용하지 않는다.
- [주문 서비스](../../src/main/java/dustin/cex/domains/order/service/OrderService.java): 예산 기반 매수 조회에만 불변 예산을 가져온다. `expired` 주문의 추가 취소를 거부한다. 수량 주문 목록에는 예산 조회를 추가하지 않았다. 예산 기반 시장가 매수 목록은 현재 행별 원본 조회 비용이 있다.
- [엔진 기록](../../../Cex-Engine/docs/decisions/013-market-ioc-completion.md): 종료 계산·WAL·Kafka 식별·재생 경계를 설명한다.

종료 이벤트의 금액 관계는 다음과 같다.

| 주문 | 예약 원본 | 사용분 | 반환액 |
| --- | --- | --- | --- |
| 시장가 매수 | `quoteAmount` | 누적 체결 대금 | 원본 예산 − 사용 대금 |
| 시장가 매도 | 원본 `amount` | 누적 체결 수량 | 원본 수량 − 체결 수량 |

반환액 0은 `filled`, 반환액이 양수면 `expired`다. 체결 0건이면 전액 반환 후 `expired`다. 체결·수수료 기록은 만료되더라도 보존한다. 다른 주문이 예약한 금액을 덮어쓰지 않고 해당 시장가의 잔여분만 `locked → available`로 이동한다.

수수료는 이전 결정대로 받은 자산에서 차감한다. 시장가 매수 예산에 별도 수수료를 더하거나 반환액에서 다시 차감하지 않는다. 현재 고정 최소 단위에서 사용할 수 없는 예산도 반환한다.

## 이해에 필요한 Java 문법

`OrderCommand`의 불변 원본과 변경되는 `Order` 상태를 구분한다. TypeScript로 비유하면 최초 요청 객체와 처리 상태 객체를 별도로 보존하는 방식이다. 이미 정해진 원본 예산을 처리 상태에서 역산하지 않는다.

`BigDecimal.compareTo`로 정확한 금액을 비교한다. 종료 검증에서는 임의 반올림이나 오차 허용을 쓰지 않는다. `JsonNode.isIntegralNumber`와 `canConvertToLong`으로 체결 건수가 소수나 넘치는 수인지 검사한다.

Spring이 주입한 소비 서비스의 `@Transactional` 안에서 수신 키와 금융 변경을 함께 처리한다. 종료 반영에 실패하면 키도 롤백되므로 원인을 해결한 다음 같은 원본을 다시 처리할 수 있다. 이미 커밋한 개별 체결을 되돌리는 보상 처리는 하지 않는다.

### 현재 코드로 연결해 읽기

시장가 종료 이벤트를 받아 원래 주문과 연결하는 현재 처리 경로다. 아래는 2026-10-23 현재 [실제 소스](../../src/main/java/dustin/cex/shared/kafka/KafkaOrderEventConsumer.java#L168)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

```java
private void completeMarketOrder(JsonNode event) {
    Order order = order(event.path("order_id").asLong());
    verifyOrder(order, event.path("user_id").asLong(), event.path("order_type").asText(),
            event.path("base_mint").asText(), event.path("quote_mint").asText());
    var original = submissions.findByOrderId(order.getId()).orElseThrow().command();
    if (!"market".equals(original.orderSide()) || !"market".equals(order.getOrderSide())) {
        throw new IllegalArgumentException("시장가 이외 주문의 종료 결과입니다");
    }
    BigDecimal quantity = number(event, "filled_amount"), spent = number(event, "filled_quote_amount");
    BigDecimal reserved = number(event, "reserved_amount"), refund = number(event, "refund_amount");
    boolean buy = original.orderType().equals("buy");
    BigDecimal used = buy ? spent : quantity;
    BigDecimal expectedRefund = ExactAmount.requireNonNegative(original.reservationAmount().subtract(used), "시장가 미사용 예약");
    long actualTrades = buy ? tradeRepository.countByBuyOrderId(order.getId()) : tradeRepository.countBySellOrderId(order.getId());
```

여러 체결 이벤트와 최종 종료 이벤트를 구분한다. 이 발췌 뒤의 검증·상태 확정·잔여 예약 반환까지 같은 수신 트랜잭션에서 처리되며, HTTP 성공만으로 예약을 풀지 않는다.

## 작성한 테스트

| 파일 | 함수 | 검증 시나리오·기대 결과 |
| --- | --- | --- |
| [정산 저장 회귀](../../src/test/java/dustin/cex/shared/kafka/TradeFeeSettlementTest.java) | `marketBuyUsesBudgetAndReleasesOnlyItsUnusedReservation` | 수량 0인 매수의 정상 체결·부분 만료·후속 예약 보존·추가 반환 차단 |
| 같은 파일 | `zeroFillMarketBuyAndSellReturnWholeReservation` | 양방향 체결 0건의 전액 반환·만료 |
| 같은 파일 | `marketSellPartialCompletionPreservesOtherReservation` | 매도 부분 체결·수취 수수료·다른 예약 보존 |
| 같은 파일 | `fullySpentMarketBuyWaitsForCompletionAndDoesNotRefund` | 예산 전액 사용 후 종료 전 `partial`, 종료 후 `filled` |
| 같은 파일 | `wrongCompletionTotalsCountsAndStatusDoNotReleaseReservation` | 합계·건수·예약·반환·상태가 틀리면 거부하고 예약 보존 |
| 같은 파일 | `overBudgetTradeAndFailedRefundRollBackAndAllowCorrectCompletion` | 예산 초과 체결 롤백·반환 실패 시 상태 보존·정상 재처리 |
| 같은 파일 | `marketBudgetDustSurvivesDatabaseCommitWithoutRounding` | 대금 0.999999999 저장 후 재조회·최소 단위 반환 |
| [주문 멱등성](../../src/test/java/dustin/cex/domains/order/service/OrderIdempotencyTest.java) | `marketBudgetIsExposedInResponseAndExpiredOrderCannotBeCancelled` | 원본 예산 응답·수량 미정 0·만료 후 취소 거부 |
| [전체 경로](../../src/test/java/dustin/cex/integration/MarketPathTest.java) | `verifyMarketIoc`, 기존 테스트에서 호출 | 실제 PostgreSQL·Kafka·Rust·Spring 시나리오 |

정산 저장 회귀는 H2와 실제 서비스 트랜잭션을 사용하며 수신 키는 대역이다. 영속 수신 키는 기존 PostgreSQL 회귀와 아래 전체 경로에서 실제 구현을 검증한다.

전체 경로는 기존 지정가 수수료 검증 뒤 다음을 이어서 실행한다.

1. 빈 호가에서 시장가 매수·매도의 전액 반환.
2. 양방향 부분 체결 후 만료·잔여 반환.
3. 양방향 전량 체결 후 `filled`.
4. 예산 1·가격 3에서 최소 단위 0.000000001 반환.
5. 서로 다른 가격의 두 체결에서 예산 소진·합계·건수·수취 수수료 검증과 공개 HTTP 원본 예산 조회.
6. 실제 브로커의 종료 8건과 체결 원본을 소비 서비스에 재전달해 중복 금융 반영 방지.
7. 실행 소유 엔진을 정지하고 **전달 커서만 0으로 변경**한 뒤 실제 WAL 복구·Kafka 전량 재발행. 기존 고객 잔고·반환·최종 상태가 유지되고 호가가 비어 있음을 확인.

## 실행 명령과 결과

| 명령 | 결과 |
| --- | --- |
| `python3 scripts/verify_engine_inbox.py` | Java **149개 통과**, 실패·오류·제외 0, 제품 JAR 빌드 포함 |
| `python3 scripts/verify_market_path.py` | 지정가 회귀와 시장가 8건을 포함한 전체 경로 **1개 통합 시나리오 통과** |
| `python3 scripts/verify_order_idempotency.py` | 실제 HTTP·동시 접수·응답 유실·재시작 **10개 통과** |
| 엔진 `python3 scripts/verify_order_safety.py` | 고유 Rust **129개 통과**, 필터 중복 포함 **131회**, 전체 테스트 컴파일·제품 빌드 포함 |
| 엔진 `python3 scripts/verify_engine_output.py` | 실제 출력 순서·강제 종료·디비 불가 복구·Kafka 장애·재발행·정상 종료 **6개 시나리오 통과** |
| 엔진 `python3 scripts/verify_durable_acceptance.py` | 실제 영속 접수·응답 유실·재시작 **3개 시나리오 통과** |
| 엔진 `cargo check --locked --benches --features bench_mode` | 벤치 컴파일 확인. 성능 측정은 아님 |

macOS·Java 21·Rust 1.91·PostgreSQL 16·Kafka 3.9.1을 사용했다. PostgreSQL·Kafka·저널·프로세스는 모두 실행 소유 자원으로 격리하고 정리했다. 공유 디비나 기존 서버는 변경하지 않았다. 자동 봇은 0명이며 통합 검증에서만 기존 봇 서비스 메서드를 직접 호출했다.

최초 전체 경로의 최소 단위 시나리오는 소수 8자리 컬럼 때문에 실패했고, 이를 수정한 뒤 다시 통과했다. 로그는 공통 루트 `.local/ioc-path-initial-failure.log`와 최종 `.local/ioc-*.log`·검증기 JSON에 남긴다. Rust의 기존 경고와 Java 문서 어노테이션의 사용 중단 경고는 남아 있다. 프론트는 수정·테스트하지 않았다.

## 저장 계약과 남은 범위

새 환경은 저널 버전 **3**, 플랫폼 누적 대금 **NUMERIC(30,9)**, `expired`를 허용하는 상태 제약이 필요하다. 이전 엔진 디비 SQL은 현재 플랫폼 스키마의 권위가 아니며, 새 플랫폼 모델의 제약으로 검증했다. 기존 환경의 저널·컬럼·제약을 자동 변환하지 않았다. 기존 서비스 재시작·배포·금융 기록 삭제는 수행하지 않았다.

다음은 **엔진 거절 결과와 응답 불명 주문의 예약 해결**이다. 현재 명령 실행 자체가 거절되면 이번 시장가 종료 결과가 생기지 않는다. 이를 정상 만료와 혼동해 예약을 해제하지 않는다. 완전한 자산 원장·독립 대사·가격 보호·최소 주문 금액·운영자 통제는 후속 로드맵으로 남는다. 프론트의 `expired` 표시와 예산 표현은 화면 작업에서 이 API 계약에 맞춰야 한다.

## 관련 커밋

- [엔진 구현·테스트 `722bfa6`](https://github.com/GiHoon1123/Cex-Engine/commit/722bfa6f69facc3bfc0ab2517987d5e4543fd0ba)
- [플랫폼 구현·테스트 `7eed751`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7eed7510be774a7d3f493100d958f858f04f37cf)

두 저장소의 종료 이벤트 계약과 저장 정밀도가 함께 바뀐다. 개별 프로세스만 먼저 배포하는 검증은 수행하지 않았다.

## 후속 상태 갱신

거절 결과·응답 불명 자동 복구는 [다음 사이클](010-durable-order-resolution.md)에서 구현·검증했다. 현재 저널 버전은 4다. 이 문서의 버전 3은 당시 저장 계약이다. Rust 집계는 진행 로그로 나뉜 시작 테스트 4개 누락을 정정해 고유 129개/131회로 표시했다.
