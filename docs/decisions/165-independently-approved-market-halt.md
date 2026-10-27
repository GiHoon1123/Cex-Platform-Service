# 독립 승인으로 신규 주문 시장 중단·재개하기

시장감시 신호만으로 고객을 제재하지 않으면서, 실제 위험 시 지정된 두 사람의 현재 권한과 내용 결합 보안키 서명으로 BTC/USDT 신규 주문을 중단하고 다시 열 수 있게 했다.

## 문제와 기존 동작

164의 탐지는 검토 신호만 만들었다. 운영자가 위험을 확인해도 신규 주문을 일관되게 중단할 영속 상태가 없었고, 임의 설정 변경은 누가 어떤 근거로 중단·재개했는지 증명할 수 없었다. 반대로 탐지 즉시 자동 중단하면 단순 가격 급변이나 정상적인 대량 취소가 전체 고객 불편으로 이어진다.

## 기존 제품과 공식 자료의 접근

- [가상자산이용자보호법 제12조](https://www.law.go.kr/LSW/LsiJoLinkP.do?docType=&joNo=&languageType=KO&lsNm=%EA%B0%80%EC%83%81%EC%9E%90%EC%82%B0+%EC%9D%B4%EC%9A%A9%EC%9E%90+%EB%B3%B4%ED%98%B8+%EB%93%B1%EC%97%90+%EA%B4%80%ED%95%9C+%EB%B2%95%EB%A5%A0&paras=1)은 이상거래 상시 감시 뒤 이용자 보호와 거래질서를 위한 적절한 조치를 요구한다.
- 금융위원회의 [2026년 불공정거래 조사 성과](https://www.fsc.go.kr/po010105/87357)는 거래소의 적출·심리·통보와 반복 거래의 예방적 주문 제한을 구분한다. 이 구현도 자동 신호, 사람 승인, 실제 신규 주문 통제를 분리한다.
- CME의 [Rule 575 안내](https://www.cmegroup.com/rulebook/files/cme-group-Rule-575.pdf)는 질서 있는 거래와 공정한 체결을 해치는 행위를 다룬다. 국내 법률 적용을 대신하지 않지만 탐지와 시장 통제를 분리하는 참고다.

## 선택과 트레이드오프

BTC/USDT마다 `OPEN` 또는 `HALTED`인 한 행을 두고, 주문 예약 트랜잭션이 공유 행 잠금으로 `OPEN`을 확인한다. 상태 변경은 담당자 제안, 지정된 다른 승인자의 실제 열람, 승인 방향·원문 해시·세션에 묶인 패스키 서명 뒤에만 가능하다. 중단에는 실제 감시 신호가 필요하고 재개는 새 사유와 새 승인을 요구한다.

장점은 재시작 후에도 상태가 유지되고 승인과 주문 차단 사이의 경계가 명확하다는 것이다. 비용은 신규 주문마다 DB 행 확인이 추가되고, 두 사람 승인은 긴급 중단 시간을 늘린다. 현재는 포트폴리오의 명확한 책임 분리와 오탐 방지를 선택했다. 자동 서킷브레이커와 한 사람 긴급 중단·사후 승인은 정책·복구·남용 통제가 더 필요해 넣지 않았다.

플랫폼은 외부 고객의 유일한 진입점이므로 신규 주문을 막는다. 이미 오더북에 있는 주문은 철회하지 않고 고객 취소는 계속 허용한다. 엔진에 중단 상태를 넣지 않아 단일 스레드 코어의 자료구조·매칭·출력 경로는 바뀌지 않는다. 내부 네트워크에서 엔진 API를 플랫폼 이외 주체가 직접 호출하지 못하게 하는 배포 통제는 여전히 필요하다.

## 구현과 관련 코드

- [MarketControlService.java](../../src/main/java/dustin/cex/domains/market/MarketControlService.java)는 상태 조회, 중단·재개 제안, 열람, 서명 결정과 적용을 담당한다.
- [surveillance-schema.sql](../../src/main/resources/market/surveillance-schema.sql)은 시장 상태 버전, 요청·열람·결정·변경 이력, 역할·신호·감사·승인 결합과 불변성을 검사한다.
- [TradingAccessPolicy.java](../../src/main/java/dustin/cex/domains/security/TradingAccessPolicy.java)는 고객 자격 확인 뒤 해당 시장 행을 공유 잠금으로 읽는다.
- [OrderService.java](../../src/main/java/dustin/cex/domains/order/service/OrderService.java)는 검증된 주문의 실제 기준·결제 자산을 접근 정책에 전달한다.
- [MarketControlController.java](../../src/main/java/dustin/cex/domains/market/MarketControlController.java)는 로그인 화면이나 리다이렉트 없이 운영 API만 제공한다.

## 이해에 필요한 문법과 원리

아래는 제품 코드의 일부다.

```java
var rows=jdbc.queryForList(
    "SELECT * FROM market_trading_states WHERE base_asset=? AND quote_asset=? FOR SHARE",
    asset(base),asset(quote));
if(rows.size()!=1||!"OPEN".equals(rows.getFirst().get("state")))
    throw denied(HttpStatus.SERVICE_UNAVAILABLE,"현재 시장의 신규 주문이 중단되었습니다");
```

PostgreSQL의 `FOR SHARE`는 여러 신규 주문이 동시에 상태를 읽게 하되 상태 변경과는 순서를 정한다. 상태 변경이 먼저 커밋되면 뒤 주문은 `HALTED`를 읽고, 주문 예약이 먼저 공유 잠금을 얻으면 해당 예약 트랜잭션이 끝난 뒤 중단이 적용된다. Rust 코어의 공유 락이 아니라 플랫폼 DB의 업무 경계다.

```java
jdbc.update("INSERT INTO market_state_changes(...) VALUES(...)", ...);
jdbc.update("UPDATE market_trading_states SET state=?,version=version+1 ...", ...);
```

변경 이력을 먼저 추가하고 같은 트랜잭션에서 현재 상태를 갱신한다. DB 트리거는 승인된 요청의 이전 상태·버전과 다음 상태·버전이 정확히 맞는지 검사한다. 어느 한 단계나 필수 감사가 실패하면 전체가 롤백된다.

## 작성한 테스트

- [MarketControlIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/MarketControlIntegrationTest.java) `independentApprovalHaltsAndResumesOnlyNewOrders`: 실제 신호, 열람, 패스키 승인, 중단 시 신규 주문 503, 새 승인 재개, 이력 불변성을 검증한다.
- `rejectedOrUnboundRequestsCannotChangeTheMarket`: 반려, 존재하지 않는 신호, 직접 SQL 상태 변경을 거부한다.
- `httpRoutesAreHeadlessAndRoleBound`: 운영 역할 API와 고객 거부를 확인한다.
- `missingDecisionAuditRollsBackTheStateChange`: 결정 감사 누락 시 상태·변경 이력이 함께 롤백되는지 확인한다.
- 기존 주문·KYC 테스트의 `requireNewOrder` 호출은 실제 BTC/USDT 시장을 명시하도록 바꿨다. 자산을 생략하는 호환 오버로드는 두지 않았다.

## 실행 명령과 결과

```sh
python3 scripts/verify_market_surveillance.py
```

감시·시장 통제·실제 취소 소비·수신 단위 회귀를 서로 다른 DB에서 실행하고 두 JAR를 매 묶음 빌드한다. 결과는 `docs/reference/165-market-control-verification-2026-10-27.json`에 남긴다.

현재 상태는 플랫폼 단일 DB·단일 진입점 계약이다. 리버스 프록시에서 엔진을 외부에 노출하지 않는 검증, 다중 플랫폼 인스턴스 부하 시험, 규정상 통보·신고 판단과 운영 연락망은 별도 운영 증적이 필요하다.

## 관련 커밋과 참고 자료

구현·테스트 커밋은 이 문서 직전 이력이며 164의 감시 입력·신호를 선행 조건으로 사용한다.
