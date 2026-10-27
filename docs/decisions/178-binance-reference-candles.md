# Binance 기준 차트를 플랫폼에서 중계하기

BTC/USDT와 ETH/USDT 차트는 Binance 공개 봉을 사용하고, 자체 호가·최근 체결·주문 체결 가능 가격은 엔진 결과를 유지한다. 외부 기준 시세와 자체 시장을 섞지 않는 API 계약과 실패 처리를 설명한다.

## 문제와 기존 동작

기존 차트 API는 우리 엔진의 최근 체결 5천 건을 1분 봉으로 묶었다. 봇이 꺼진 소규모 시장에서는 체결이 없거나 한 가격에 한 건뿐이어서 차트가 비어 있거나 수평선처럼 보였다. 반면 호가창은 자체 엔진 상태여야 하므로 Binance 호가로 교체할 수 없다.

## 기존 제품과 공식 자료의 접근

[Binance Spot Kline API](https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints#klinecandlestick-data)는 심볼, 간격, 개수를 명시해 OHLC와 거래량을 반환한다. Binance의 일반 REST 안내는 공개 시세 전용 요청에 `data-api.binance.vision`을 사용하도록 권고한다. 설치된 Spring MVC와 Jackson을 유지하고 Java 21 표준 `HttpClient`로 이 공개 API만 호출한다. 새로운 HTTP 패키지는 추가하지 않았다.

## 선택과 트레이드오프

차트까지 자체 체결만 사용하면 데이터 의미는 가장 단순하지만 현재 유동성에서는 시장 흐름을 보여주지 못한다. Binance 봉을 기준 차트로 사용하면 살아 있는 시장 흐름을 제공하는 대신 차트 가격과 우리 호가·실제 체결 가격이 달라질 수 있다.

이를 숨기지 않고 다음처럼 분리했다.

| 화면/데이터 | 권위 |
| --- | --- |
| `Binance 외부 기준 시세` 차트 | Binance 공개 Kline |
| 호가창·최근 체결 | 우리 엔진 WAL 확정 뒤 Kafka로 공개한 시장 상태 |
| 주문 예상 체결과 정산 | 우리 엔진과 플랫폼 금융 기록 |

브라우저가 Binance를 직접 호출하는 방식은 단순하지만 공개 출처·제한·장애 처리가 각 클라이언트로 흩어진다. 플랫폼 중계를 선택해 진입점을 유지했다. 거래쌍과 봉 간격별 10초 캐시는 동시 고객 요청을 하나의 외부 호출로 합친다. 캐시 만료 뒤 Binance 호출이 실패하면 만료 자료를 정상 응답하지 않고 503을 반환한다.

## 구현과 관련 코드

[BinanceReferenceCandleService](../../src/main/java/dustin/cex/domains/market/BinanceReferenceCandleService.java)의 핵심 캐시 경계다.

```java
synchronized(slot) {
    if(slot.cached!=null&&slot.cached.storedAt().plus(CACHE_TTL).isAfter(now))
        return slot.cached.value();
    ReferenceCandles fresh=fetch(symbol,selectedInterval,now);
    slot.cached=new Cached(now,fresh);
    return fresh;
}
```

`synchronized`는 외부 기준 시세 캐시 슬롯에만 사용한다. Rust 체결 코어의 상태나 주문 처리 경로와 무관하며 거래쌍·간격별 슬롯이므로 BTC 1분 봉 조회가 ETH 월봉 캐시를 잠그지 않는다. 지원 시장은 BTCUSDT와 ETHUSDT, 간격은 `1m`, `5m`, `15m`, `1h`, `4h`, `1d`, `1w`, `1M`으로 고정한다. Binance 계약에서 대문자 `M`은 월을 뜻하므로 소문자 분 단위와 구분한다. 원격 URL은 HTTPS만 허용하고 단위 테스트 서버를 위해 루프백 HTTP만 예외로 허용한다.

응답은 `source`, `market`, `interval`, `fetchedAt`, `candles`를 포함한다. 클라이언트가 이 목록을 자체 체결 봉으로 오인하지 않게 출처를 데이터에도 고정했다. 과거 `/candles` 공개 경로는 더 이상 사용하지 않고 `/reference-candles`로 교체했다.

## 작성한 테스트

[BinanceReferenceCandleServiceTest](../../src/test/java/dustin/cex/domains/market/BinanceReferenceCandleServiceTest.java):

- `bitcoinAndEthereumUseTheValidatedBinanceContract`: 두 심볼, 서로 다른 봉 간격과 OHLC·거래량·출처를 검증한다.
- `tenSecondCacheCombinesIdenticalRequestsWithoutSharingConditions`: 같은 조건의 요청 병합과 시장·간격별 캐시 분리를 검증한다.
- `unsupportedMarketAndMalformedRowsFailClosed`: SOL 요청 404, 허용하지 않은 간격 400, 잘못된 Binance 응답 503을 검증한다.
- `insecureRemoteEndpointIsRejected`: 원격 평문 HTTP 설정을 시작 단계에서 거부한다.

## 실행 명령과 결과

```text
./gradlew test --tests dustin.cex.domains.market.BinanceReferenceCandleServiceTest --tests dustin.cex.domains.market.MarketSafetyTest
`BinanceReferenceCandleServiceTest` 4개와 `MarketSafetyTest` 6개, 총 10개 통과, 실패 0
```

실제 로컬 플랫폼에서 BTC와 ETH의 여덟 간격을 호출해 `source=BINANCE`, 요청 간격·시장·갱신 시각을 확인했다. 조회 상한은 500개이며 실제 Binance 상장 이력에 따라 주봉은 481개, 월봉은 111개가 반환됐다. 허용하지 않은 `2m`은 400을 반환했다. 프론트 Chrome 결과는 [반응형 기간 선택 기록](../../../Cex-Frontend/docs/decisions/009-responsive-chart-periods.md)을 따른다.

## 남은 한계

Binance API 장애·지역별 접근 제한·호출 정책 변경의 영향을 받는다. 현재는 503과 화면 오류를 표시할 뿐 다른 거래소로 자동 전환하지 않는다. 여러 거래소를 합성한 지수 가격이나 주문 가격 보호 장치가 아니며, 향후 그런 정책을 도입하려면 구성 종목·이상치 제거·가중치·중단 조건을 별도 승인해야 한다.

## 관련 커밋과 참고 자료

- [Binance Spot Kline API](https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints#klinecandlestick-data)
