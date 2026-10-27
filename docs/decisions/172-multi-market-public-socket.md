# 거래쌍별 공개 시세 연결

BTC 전용 WebSocket 경로를 거래쌍별 경로로 바꿔 ETH/USDT를 같은 플랫폼 진입점에서 제공한다. 금융 소비와 엔진 코어는 바꾸지 않고 공개 시세 전송 경계만 일반화했다.

## 문제와 기존 동작

REST 시세와 Kafka 영속 저장은 거래쌍을 구분했지만 WebSocket 등록과 발행은 `BTC/USDT`로 고정돼 있었다. 엔진이 ETH/USDT 스냅샷을 발행해도 브라우저가 구독할 경로가 없었다.

## 기존 제품과 공식 자료의 접근

Spring WebSocket은 경로 패턴으로 핸들러를 등록하고 연결의 URI를 제공한다. 하나의 공개 소켓에 모든 시장을 섞지 않고 거래쌍별 스트림을 두는 기존 API 계약을 유지했다. [Spring WebSocket 문서](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)

## 선택과 트레이드오프

`/ws/market/{base}/{quote}` 형태를 사용하고 연결마다 요청 시장을 고정했다. 구독 메시지 프로토콜보다 구현과 권한 경계가 단순하며 한 연결의 시장 전환은 재연결이 필요하다. 현재 발행 주기마다 연결별 시세를 읽으므로 시장·접속 수가 커지면 같은 시장 조회를 묶어야 하지만 두 시장 단계에서는 캐시 계층을 미리 추가하지 않았다.

## 구현과 관련 코드

[MarketSocket.java](../../src/main/java/dustin/cex/domains/market/MarketSocket.java)의 연결 시점 일부다.

```java
String[] market = market(session.getUri());
if (market == null || !capacity.tryAcquire()) {
    session.close(CloseStatus.POLICY_VIOLATION);
    return;
}
clients.put(session.getId(), new Client(session, market[0], market[1]));
```

URI의 대문자 자산 식별자를 기존 `MarketService.pair` 규칙으로 검증하고, 유한 연결 슬롯을 얻기 전에 잘못된 경로를 거부한다. 각 클라이언트는 자신의 거래쌍만 조회한다.

## 이해에 필요한 문법과 원리

Java 배열은 이 작은 두 값의 내부 전달에만 사용한다. `Client`가 `base`, `quote`를 `final`로 보관하므로 연결 중 다른 시장으로 바뀌지 않는다. 시장 변경은 기존 소켓을 닫고 새 경로에 연결하는 프론트 책임이다.

## 작성한 테스트

- `MarketSocketTest.eachConnectionReadsOnlyItsRequestedMarket`: ETH 연결이 ETH/USDT만 조회하고 BTC를 조회하지 않는지 확인한다.
- `MarketSocketTest.malformedMarketPathIsRejectedBeforeCapacityUse`: 소문자 자산 경로를 정책 위반으로 닫는지 확인한다.
- 기존 연결 상한·메시지 명령 거부 회귀도 함께 실행했다.

## 실행 명령과 결과

```sh
./gradlew test --tests dustin.cex.domains.market.MarketSocketTest \
  --tests dustin.cex.domains.market.MarketServiceTest --no-daemon
```

관련 테스트가 모두 통과했다. 실제 Kafka ETH 스냅샷과 브라우저 전체 연결은 프론트 ETH 시장 검사와 이후 전체 연결에서 별도로 확인한다.

## 관련 커밋과 참고 자료

구현과 테스트를 같은 플랫폼 커밋으로 남긴다. 공식 문서는 2026-10-27 확인했다.
