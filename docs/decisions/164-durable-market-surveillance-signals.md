# 확정 체결·취소에서 시장 이상거래 검토 신호 만들기

엔진의 단일 스레드 코어를 건드리지 않고 플랫폼에 확정된 체결·취소를 불변 입력으로 남겨 순서대로 평가한다. 이 문서는 첫 정책의 근거, 신호와 위반 판단의 차이, 코드·테스트·남은 조사 업무를 설명한다.

## 문제와 기존 동작

플랫폼은 주문·체결·취소와 원장을 보존했지만 시장감시 전용 입력, 정책 버전, 처리 위치와 검토 신호가 없었다. 사후 SQL로 현재 주문 상태만 조회하면 취소 시점과 당시 근거가 사라지고, 재실행 결과가 달라질 수 있었다. 감시 계산을 Rust 코어에 넣으면 단일 소유·락 없는 계산 경로에 규제 조회와 저장 대기를 섞게 된다.

## 기존 제품과 공식 자료의 접근

- 국가법령정보센터의 현행 [가상자산이용자보호법 제12조](https://www.law.go.kr/LSW/LsiJoLinkP.do?docType=&joNo=&languageType=KO&lsNm=%EA%B0%80%EC%83%81%EC%9E%90%EC%82%B0+%EC%9D%B4%EC%9A%A9%EC%9E%90+%EB%B3%B4%ED%98%B8+%EB%93%B1%EC%97%90+%EA%B4%80%ED%95%9C+%EB%B2%95%EB%A5%A0&paras=1)은 거래소의 이상거래 상시 감시와 적절한 조치를 요구한다.
- [시행령 제13조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lspttninfSeq=188951)는 주문정보·변경 내역, 상시 감시와 조치 결과, 통보·신고 기록을 보관 범위로 둔다. 확인 기준은 2026-10-27, 시행 2026-10-02인 현행 조문이다.
- 금융위원회의 [가상자산 불공정거래 조사 성과와 시장감시 설명](https://www.fsc.go.kr/po010105/87357)은 거래소의 적출, 신속한 심리, 당국 통보를 구분한다. 따라서 자동 규칙의 결과를 곧바로 위반 확정이나 계정 제재로 사용하지 않았다.
- CME의 [Rule 575 안내](https://www.cmegroup.com/rulebook/files/cme-group-Rule-575.pdf)는 취소 의도가 있는 기만 주문을 다루며, [Rule 534 안내](https://www.cmegroup.com/content/dam/cmegroup/notices/market-regulation/2019/04/RA1905-5.pdf)는 가장매매에 결과와 의도 판단이 함께 필요함을 설명한다. 이는 국내 의무의 대체 근거가 아니라 탐지 신호와 최종 판단을 분리하는 해외 운영 참고다.

## 선택과 트레이드오프

선택은 `엔진 확정 결과 → 같은 플랫폼 금융 트랜잭션의 감시 입력 → 별도 순차 워커 → 검토 신호`다. 엔진 지연과 단일 소유 상태를 보존하고, Kafka 재전달은 기존 수신 키와 입력 유일 제약이 막는다. 대가로 플랫폼 DB 쓰기 한 건이 체결·취소 트랜잭션에 추가되고 감시는 최대 워커 주기만큼 늦다.

첫 정책은 5분 안 같은 고객·시장·방향의 미체결 지정가 취소 5건과 직전 체결 대비 10% 이상 가격 변화다. 두 기준은 조사 후보를 넓게 잡아 오탐이 있을 수 있으므로 고객 제한을 자동 변경하지 않는다. 연결 계정 가장매매·주문 크기 대비 호가 깊이·장기간 시세조종은 소유관계와 시장 기준 자료가 더 필요해 추측으로 넣지 않았다. 엔진의 동일 사용자 자전거래 차단은 계속 예방 통제이고, 감시기는 그 불변식 위반이 입력에 나타나면 별도 중요 신호로 분류한다.

## 구현과 관련 코드

- [MarketSurveillanceInputs.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceInputs.java)는 저장한 `Trade` 또는 취소된 `Order`에서만 입력을 만든다.
- [surveillance-schema.sql](../../src/main/resources/market/surveillance-schema.sql)은 금융 원본 결합, 단조 증가 처리 위치, 평가 해시와 불변 근거를 DB 제약으로 검사한다.
- [MarketSurveillanceService.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceService.java)는 전역 입력 순서를 한 건씩 처리하고 정책 ID와 증거 이벤트 ID를 고정한다.
- [MarketSurveillanceWorker.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceWorker.java)는 실행당 최대 100건만 처리해 주문 DB 자원을 무제한 점유하지 않는다.
- [MarketSurveillanceController.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceController.java)는 운영·승인·감사 역할에 신호 목록과 근거 읽기만 제공한다.

## 이해에 필요한 문법과 원리

아래는 제품 코드의 일부 발췌다.

```java
long cursor=jdbc.queryForObject(
    "SELECT observed_event FROM market_surveillance_state WHERE singleton=true FOR UPDATE",Long.class);
var events=jdbc.queryForList(
    "SELECT * FROM market_activity_events WHERE id>? ORDER BY id LIMIT 1",cursor);
```

`FOR UPDATE`는 한 플랫폼 DB에서 두 워커가 같은 처리 위치를 동시에 전진하지 못하게 한다. TypeScript 트랜잭션에서 행 잠금을 잡는 것과 같은 DB 규칙이며, Rust 엔진 상태에 락을 추가하는 것이 아니다. 다음 실제 ID 한 건만 처리하고 같은 트랜잭션에서 평가와 위치를 함께 확정하므로 중간 실패 시 둘 다 롤백된다.

```java
if("REVIEW_REQUIRED".equals(decision.outcome()))
    jdbc.update("INSERT INTO market_surveillance_signals(id,assessment_id) VALUES(?,?)",UUID.randomUUID(),assessment);
```

신호 생성은 평가와 같은 트랜잭션이다. 이 코드는 사용자의 `tradingRestricted`를 변경하지 않는다. 규칙 일치는 혐의 확정에 필요한 의도·소유관계·전체 맥락을 대신하지 않기 때문이다.

## 작성한 테스트

- [MarketSurveillanceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/MarketSurveillanceIntegrationTest.java) `confirmedEventsCreateReviewSignalsWithoutAutomaticallyRestrictingTheCustomer`: 실제 PostgreSQL에서 네 번째 빠른 취소까지 정상, 다섯 번째 신호, 직전 대비 11% 체결가 신호, 고객 자동 제한 없음, 역할별 읽기, 입력·평가·커서 변조 거부를 검증한다.
- [OrderCancellationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/OrderCancellationIntegrationTest.java) `theActualFinancialEventReleasesOnceAndStopsFurtherRetry`: 실제 Kafka 소비 경로가 취소 금융 반영과 감시 입력을 한 번만 함께 확정하는지 검증한다.
- 기존 `KafkaOrderEventConsumerTest`와 `EngineEventInboxTest`는 중복·수신 실패가 감시 의존성 추가 뒤에도 금융 저장소를 건드리지 않는지 회귀한다.

## 실행 명령과 결과

```sh
python3 scripts/verify_market_surveillance.py
```

검증기는 세 묶음을 서로 다른 PostgreSQL DB에서 실행하고 두 애플리케이션 JAR도 빌드한다. 실행 결과 JSON은 `docs/reference/164-market-surveillance-verification-2026-10-27.json`에 보존한다.

이 단계는 탐지 입력과 신호 생성까지다. 신호 담당자 배정·조사 의견·종결/회부, 독립 승인에 결합한 시장 일시중단·재개, 정책 효과·오탐·누락 성능 평가는 다음 단계다. 실제 신고·법률 판단·감독기관 협의가 완료됐다고 주장하지 않는다.

## 관련 커밋과 참고 자료

구현과 테스트 커밋은 이 문서 커밋의 바로 이전 이력이다. 공식 자료의 적용 조건과 해외 참고 구분은 위 링크를 따른다.
