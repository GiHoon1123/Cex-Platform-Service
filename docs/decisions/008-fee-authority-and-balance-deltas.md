# 수수료 권위를 고정하고 체결 변화량으로 예약금을 보존하기

수수료가 다음 체결에서 사라지거나 후속 주문의 예약금이 덮어써지는 문제를 해결한다. 적용 정책 선택, 엔진 계산, 플랫폼 반영의 책임과 지정가 검증 범위를 설명한다. 작성일은 2026-10-22다.

## 기존 동작의 문제

엔진은 수수료 없는 잔고를 전달하고 플랫폼이 현행 설정으로 USDT 수수료를 다시 계산했다. 각 체결에 전체 주문의 최종 잔고가 반복되어 이전 차감이 사라질 수 있었다. Kafka 반영 전에 플랫폼이 새 주문을 접수하면 그 예약도 과거 잔고로 덮어쓸 수 있었다. 정책이 바뀐 후 지연 소비한 체결의 비용도 접수 당시와 달라질 수 있었다.

## 기존 제품과 공식 자료

[바이낸스 현물 수수료 설명](https://github.com/binance/binance-spot-api-docs/blob/master/faqs/commission_faq.md)의 수취 자산 차감 패턴을 채택했다. 매수자는 기준 자산 수량, 매도자는 결제 자산 대금에 적용률을 곱한다. 확인일은 2026-10-22다. 바이낸스 내부 서비스 경계·반올림 방식을 추측하지 않으며, 이 프로젝트의 고정 정밀도와 책임 구분은 아래에 별도로 정의한다.

## 책임과 트레이드오프

| 책임 | 담당 | 이유와 비용 |
| --- | --- | --- |
| 활성 정책 검증·우선순위 선택 | 플랫폼 `FeeConfigService` | 고객 접수 업무와 결합. 누락·중복·미지원 정책은 신규 접수를 막음 |
| 주문 적용률 보존 | 플랫폼 `OrderSubmission`, 엔진 접수 증명·WAL | 재요청·정책 변경·복구가 기존 비용을 바꾸지 않음. 필수 저장 필드가 늘어남 |
| 실제 수수료·순수취액 계산 | 엔진 단일 코어 | 실행 가능 잔고와 차감 일치. 코어에 입출력이나 공유 락은 없음 |
| 주문·잔고·수수료 반영 | 플랫폼 금융 소비자 | 실제 디비 트랜잭션 안에서 원자적으로 반영. 행 락과 순차 소비 비용을 감수 |
| 중복 방지 | 기존 영속 수신 키 | 동일 결과 재발행은 재차감하지 않음. 금융 효과와 같은 트랜잭션이어야 함 |

정책이 없을 때 임의로 0.0001을 쓰던 동작을 제거했다. 지정 거래쌍 → 기준 자산 범위 → 결제 자산 범위 → 명시한 전역 정책 순서로 선택한다. 전역 정책도 디비에 실제 등록돼 있어야 한다. 정책 사본을 검증한 뒤 메모리에 한 번에 공개하며, 공개한 객체를 외부에서 바꿀 수 없게 사본을 반환한다. 현재는 양측 동일률만 지원하고 메이커·테이커 차등 설정은 거부한다.

원본 적용률은 0 이상 1 미만, 소수 6자리 이내다. 엔진은 수수료를 소수 9자리 아래 절삭한다. 일반 체결 대금·예약·잔고에는 임의 반올림을 하지 않는다. 여러 작은 체결은 단일 큰 체결과 수수료 총합이 다를 수 있다. 향후 최소 주문 금액·자산별 정밀도는 별도 F2 정책으로 정해야 한다.

## 구현과 금액 흐름

- [접수 서비스](../../src/main/java/dustin/cex/domains/order/service/OrderService.java): 주문·불변 원본·고정 적용률·예약을 한 트랜잭션에 저장한다. 동일 요청 키의 재요청은 정책을 새로 선택하지 않는다.
- [정책 서비스](../../src/main/java/dustin/cex/domains/fee/service/FeeConfigService.java): 기본률 추측·소비 시점 수수료 계산을 제거했다. 새로고침 실패는 기존 공개 상태를 부분 변경하지 않는다.
- [금융 소비자](../../src/main/java/dustin/cex/shared/kafka/KafkaOrderEventConsumer.java): 원본 소유자·시장·방향·지정가 범위·누적 수량·적용률·수수료를 검사한다. 이벤트의 실제 수수료를 반영하며 계산은 계약 검증에만 사용한다.
- [수수료 기록](../../src/main/java/dustin/cex/domains/settlement/trade/model/entity/TradeFee.java): 매수 수수료는 기준 자산, 매도 수수료는 결제 자산으로 기록한다. 지연 소비 날짜 대신 체결 시각을 기존 업무 시간대 `Asia/Seoul`로 변환해 기록한다. 파생 포지션의 평균 취득가는 수수료 후 순수취 수량과 실제 지출 대금으로 계산한다.
- [정산 서비스](../../src/main/java/dustin/cex/domains/settlement/trade/service/TradeSettlementService.java): 기존 USDT 요약·정산 항목·사용자 요약은 USDT 수수료만 합친다. 자산별 실제 수익은 `calculateFeeRevenueByAsset`로 구분한다.
- [리포트](../../src/main/java/dustin/cex/domains/settlement/trade/service/TradeReportService.java): 일별·월별 리포트에 `feeRevenueAsset=USDT`와 `feeRevenueByAsset`을 명시한다. BTC 수수료를 USDT와 더하지 않는다.
- [검증기](../../src/main/java/dustin/cex/domains/settlement/trade/service/TradeSettlementValidator.java): 각 기록의 수취 자산·적용률·절삭 결과를 검사하고 기존 결제 자산 요약은 같은 단위끼리 비교한다.

지정가 매수의 체결 수량을 `q`, 주문 가격을 `L`, 체결 가격을 `P`라고 하면 다음 변화량을 반영한다.

| 계정 | 사용 가능 잔액 변화 | 예약 잔액 변화 |
| --- | --- | --- |
| 매수자 결제 자산 | `(L-P) × q` | `-L × q` |
| 매수자 기준 자산 | `q - 매수 수수료` | 0 |
| 매도자 기준 자산 | 0 | `-q` |
| 매도자 결제 자산 | `P × q - 매도 수수료` | 0 |

후속 주문이 이미 예약한 금액은 위 체결에 해당하지 않으므로 그대로 남는다. 부분 체결·여러 상대 주문의 체결도 순서대로 같은 규칙을 적용한다. 취소는 앞선 체결 반영 후 남은 예약만 해제한다.

엔진과 플랫폼은 비동기이므로 모든 순간 같은 잔고를 보여 주는 계약은 아니다. 플랫폼의 선행 예약, 엔진 접수, WAL 확정, 플랫폼 소비가 같은 범위까지 완료된 시점의 금액을 비교해야 한다. 완전한 자산 원장·거래소 수익 계정·독립 대사는 다음 H1/H6 작업이다.

## 이해에 필요한 Java 문법

`BigDecimal`은 TypeScript `number`와 달리 10진수 금액을 정확히 표현한다. `compareTo`는 0.001과 0.001000을 같은 금액으로 비교한다. `setScale(9, RoundingMode.DOWN)`은 여기서는 엔진 수수료 검증에만 사용한다.

`@Transactional`이 붙은 소비 서비스를 Spring 프록시를 통해 호출하면 수신 키·체결·주문·잔고·수수료 중 하나라도 실패할 때 함께 롤백된다. 같은 클래스 내부 호출이나 단순히 `new`로 만든 객체에는 자동 트랜잭션이 생기지 않으므로 실제 저장 검증은 주입받은 소비 서비스를 호출한다.

`volatile Map`과 불변 사본은 플랫폼 설정을 한 번에 교체하는 방식이다. 엔진 코어 상태의 공유 락과는 무관하다. 원본 적용률은 디비에 보존하므로 캐시 갱신이 진행 중인 주문을 바꾸지 않는다.

## 작성·수정한 테스트

| 파일 | 함수 | 시나리오·기대 결과 |
| --- | --- | --- |
| [정책 검증](../../src/test/java/dustin/cex/domains/fee/service/FeeConfigServiceTest.java) | `missingPolicyRejectsInsteadOfInventingDefault` | 등록 정책이 없으면 접수용 조회 거부 |
| 같은 파일 | `specificityAndAtomicRefreshPreserveValidatedPolicy` | 구체성 우선·사본 격리·중복 설정의 새로고침 실패 후 기존 상태 유지 |
| 같은 파일 | `invalidRatesAndUnsupportedTypesAreRejected` | 음수·100%·정밀도 초과·미지원 구분 거부 |
| [접수 멱등성](../../src/test/java/dustin/cex/domains/order/service/OrderIdempotencyTest.java) | `feePolicyChangesAffectOnlyNewIntentAndInvalidPolicyRollsBackReservation` | 재요청 적용률 유지·신규 주문만 변경률·잘못된 정책의 주문·예약 롤백 |
| [실제 정산 저장](../../src/test/java/dustin/cex/shared/kafka/TradeFeeSettlementTest.java) | `consecutivePartialFillsKeepFeesAndFutureReservation` | 연속 체결 후 수수료 누적·가격 개선 반환·후속 예약 50 보존 |
| 같은 파일 | `alteredFeeOrFrozenRateRollsBackAllFinancialRows` | 위조 수수료·원본과 다른 적용률 거부 |
| 같은 파일 | `lateBalanceFailureRollsBackTradeOrdersAndEarlierAccountChanges` | 뒤쪽 계정 실패가 앞선 잔고 변경·체결·주문까지 롤백 |
| [자산별 수익](../../src/test/java/dustin/cex/domains/fee/service/FeeRevenueTest.java) | `revenueKeepsEachAssetAndExactDayBoundary` | BTC와 USDT 분리·다음 날 시작 제외 범위 |
| [실제 통합 경로](../../src/test/java/dustin/cex/integration/MarketPathTest.java) | `botOrderHttpAcceptanceDurableKafkaSnapshotCancelAndReconnect` | 아래 실제 프로세스 시나리오 |

H2 정산 테스트는 저장과 트랜잭션을 실제 실행하지만 수신 키는 대역이다. 기존 `EngineEventInboxTest`는 격리 PostgreSQL에서 실제 수신 키와 롤백을 검사한다. 아래 통합 경로는 PostgreSQL·Kafka·Rust·Spring을 모두 실제로 실행한다.

통합 시나리오는 먼저 0.1% 정책으로 두 매도 주문을 접수한 다음 0.2%로 변경해 두 매수 주문을 접수한다. 두 번째 매수는 두 상대 주문과 체결해 총 3건을 만든다. 최종 매수자 BTC는 **0.002495**, USDT는 **850**, 매도자 USDT는 **149.85**다. 브로커 원본을 실제 소비 서비스에 다시 전달해 재차감이 없음을 확인하고, 남은 매도 예약 취소·엔진 WAL 재시작·공개 소켓 재연결까지 검증한다. 봇은 격리 테스트에서 서비스 메서드만 직접 호출하며 자동 거래는 0명이다.

## 실행 명령과 결과

실행 환경은 macOS·Java 21·Rust 1.91·PostgreSQL 16·Kafka 3.9.1이다. 공유 로컬 서비스·데이터는 사용하지 않는다.

| 명령 | 결과 |
| --- | --- |
| `python3 scripts/verify_engine_inbox.py` | Java **141개 통과**, 실패·오류·제외 0. 제품 JAR 빌드 포함 |
| `python3 scripts/verify_market_path.py` | 실제 전체 경로 **1개 시나리오 통과**, PostgreSQL·Kafka 정리 |
| `python3 scripts/verify_order_idempotency.py` | 실제 플랫폼 HTTP·재시작·동시 접수 **10개 통과**. 실행 소유 PostgreSQL 서버로 격리 강화 |
| 엔진 `python3 scripts/verify_order_safety.py` | 고유 Rust **119개 통과**, 중복 선택 포함 121회. 전체 테스트 컴파일·제품 빌드 포함 |
| 엔진 `python3 scripts/verify_engine_output.py` | 강제 종료·DB 불가 복구·전달 순서·Kafka 장애·미발행 복구·정상 종료 **6개 시나리오 통과** |
| 엔진 `python3 scripts/verify_durable_acceptance.py` | 실제 접수·응답 유실·재시작 **3개 시나리오 통과** |
| 엔진 `cargo check --locked --benches --features bench_mode` | 벤치 컴파일 확인. 성능 측정은 아님 |

원격 푸시 전 최종 재실행 결과를 기준으로 기록한다. 실행 증거는 공통 루트 `.local/fee-*.log`와 해당 검증기의 JSON에 남긴다. 프론트는 수정·테스트하지 않았다. Rust 기존 미사용 경고·미래 호환 경고는 남아 있다.

## 기록 보존과 남은 작업

[엔진 기록](../../../Cex-Engine/docs/decisions/012-received-asset-fees.md)의 저널 버전 2와 새 접수 스키마가 함께 필요하다. 예전 데이터에 적용률을 추측해 채우는 호환 경로나 변환을 추가하지 않았다. 기존 실행 서버·기존 디비·저널은 변경하거나 재시작하지 않았다. 금융 이력을 삭제해서 전환해서는 안 된다.

이번 완료 범위는 **지정가 수수료·연속/다중 체결·예약 보존**이다. 다음은 시장가 매수의 예산·수량 0 처리와 IOC 종료·미사용 예약 반환이다. 엔진 거절 결과·응답 불명 자동 해소, 완전한 복식 원장, 자산별 독립 대사, 일·월 정산 전체의 날짜 경계와 외부 수탁 잔고 검증은 완료하지 않았다. 기존 정산 집계의 존재나 이번 수수료 검증을 실제 복식 원장·외부 대사 완료로 해석하지 않는다.

## 관련 커밋

- [엔진 구현·테스트 `9f441fa`](https://github.com/GiHoon1123/Cex-Engine/commit/9f441fa7078f45cf6e34c7c5e71230cdd345a1d3)
- [플랫폼 구현·테스트 `646c553`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/646c553a6416f9ae957f9eae8798d5d0cc77df77)

두 저장소의 금융 이벤트 계약이 함께 바뀐다. 개별 프로세스만 먼저 새 버전으로 배포하는 검증은 수행하지 않았다.
