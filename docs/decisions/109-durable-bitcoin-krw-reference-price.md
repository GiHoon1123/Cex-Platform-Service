# BTC 원화 참고가격의 원문과 시각·실패를 보존하기

BTC 원화 참고가격의 원문·체결/스냅샷·조회/저장 시각과 실패를 보존한다. 현재 가격이 없을 때 이전 성공을 빌리지 않고, 별도 전용 워커가 이력을 수집한다. 이 가격은 아직 특정 입금의 법정 환산액이나 소액 면제·금융 승인에 연결하지 않았다.

후속 [110 입금 시점 결합](110-pending-deposit-price-capture.md)은 이 원본을 실제 보류 인식 시점에 연결한다. 아래109의 검증은 그 이전 범위이며 최종 금액·제공 목적·자금 사용 판단은 여전히 별도다.

## 문제와 기존 동작

108은 정보 교환 일정/실행을 연결했지만 적용 금액과 개인정보 제공 목적 판단은 미완료다. BTC/USDT 체결가를 원화 금액이라고 볼 수 없고, 나중에 받은 현재가를 과거 송신 시점의 가격으로 바꿔 쓸 수도 없다. 외부 원문/시각 없이 소액 면제라고 추정하거나 거래 시작 경로에 공급사 I/O를 넣어서는 안 된다.

설치된 기존 시장/봇 경로를 확인했다. 바이낸스 봇 참조는 BTC/USDT이며 원화 평가 자료가 아니다. 별도 설치된 원화 가격 클라이언트/SDK는 없고 JDK21 HttpClient·Jackson3·기존 LimitedResponseBody로 고정된 공개 가격 계약을 구현할 수 있다. 새 SDK/의존성은 추가하지 않았다.

## 공식 자료와 적용 시점

2026-10-25 기준으로 확인했다.

- [업비트 페어 단위 현재가](https://docs.upbit.com/kr/reference/list-tickers): 고정 KRW-BTC 티커와 체결 시각/스냅샷 시각을 읽는다. 공개 REST 계약의 숫자 타입을 Java double 계산에 사용한다는 의미로 해석하지 않는다. IP별 현재가 그룹의 공유 호출 제한은 운영에서 함께 관리해야 한다.
- [감독규정2024-1호 원문](https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq=2100000243142&chrClsCd=010201) 제26조: 수취 등 현금 환산의 시점과 이전 요청 시점의 환산을 구분한다.
- [감독규정2026-3호](https://www.law.go.kr/admRulLsInfoP.do?admRulSeq=2100000285986)와 그 안의2026-2호 부칙: 제26조 등 개정 조항의 발령 후6개월 유예를 확인했다. 최신 페이지에 보이는 조문 전체를2026-10-25의 현행 적용으로 바로 쓰지 않는다. 공개 화면 본문이 동적으로 로드되어 공식 HTML의 공개 본문 요청도 대조했다.
- [금융위2021-03-22 설명](https://www.fsc.go.kr/no010101/75591)은 가격 산정 시점의 배경 자료다. 이 보도자료만으로2026년 적용 범위를 확정하지 않는다.
- [업비트 트래블룰 안내](https://support.upbit.com/hc/ko/articles/4498679629337)의 금액·경로별 절차도 대조했다. 타 서비스 동작을 우리 서비스의 법정 의무로 대신 쓰지 않는다.

원화 참고 데이터 공급자를 업비트로 선택한 것은 이 프로젝트의 선택이다. 법령이 이 공급자/API를 지정한다고 주장하지 않는다. 이 가격의 표시/적용 정책·수취/요청 시점 결합, 공급사 이용약관/상업적 사용 조건·법적 적합성은 별도 확인·업무 구현이 필요하다. 특히 송신 사업자의 원래 이전 요청 시점 금액을 우리 수취 후 현재가 하나로 증명할 수 없다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|공식 단일 고정 출처·KRW-BTC | 불필요한 공급사 선택/사용자 URL 입력 제거·명확한 자료 출처 | 공급사 장애 때 자동 대체 가격 없음. 외부 이용 조건/가용성 의존 |
|정확한 십진수·원문 바이트 지문 | 이진 실수/반올림으로 가격이 바뀌지 않음·원문 추적 | SHA-256은 공급사 독립 서명이나 시장가격 적정성 증명이 아님 |
|새 조회 시작부터 현재 위치 전진 | 새 실패/미완료를 과거 성공으로 숨기지 않음 | 조회 중 짧게 현재 참고가격을 사용할 수 없을 수 있음 |
|전용30초 주기·기본 비활성 | 기존 가격 이력과 시각을 수집·주문 경로와 분리 | 켠 프로세스마다 호출/DB·감사/저장 비용. 주기 호출만 시간 제외 시 하루 약2880회이며 실제 처리량 측정값 아님 |
|본문64KiB·연결2초/본문까지5초 | 응답 정지/큰 본문에 자원 상한 | 상한 초과/리다이렉트/오류는 보류. JDK 전송 계층의 재전송까지 exactly-once로 보장하지 않음 |
|원본/조회/체결 시각 분리·최근성2분 | 오래된 체결가를 새 스냅샷으로 오인하지 않음 | 자체 허용 창이며 법정 환산 허용 오차가 아님. 시계 동기화/적체 관측 필요 |
|가격 부재와 DB 실패를 구분 | 실제 자산 기록 TX가 가격 부재만으로 롤백 전용 상태가 되지 않음 | 자금 사용은 별도 업무에서 보류해야 함. SQL/DB 오류 자체를 무시하지 않음 |

원본/감사 보관 비용은 실제 운영에서 측정해야 한다. 가격당 최대64KiB 본문을 모두 보관하는 최악 조건이면 주기 호출만으로도 저장 비용이 크다. 모든 가격 이력을 무기한 보존해야 한다고 단정하지 않으며, 금융 판단에 실제 결합한 원본의 보존/법적 보류와 미사용 시장 이력의 수명은 전체 보존 작업에서 정해야 한다.

## 구현과 데이터 흐름

BitcoinKrwPriceClient의 제품 주소는 https://api.upbit.com으로 고정하고 GET /v1/ticker?markets=KRW-BTC만 제공한다. 같은 패키지 시험용 생성자만 명시적 루프백 HTTP를 허용한다. 임의 원격 URL/고객 입력을 전달하는 API는 없다. URI를 검증한 뒤 하나의 장수 HttpClient를 소유/종료한다.

JSON 배열은 정확히 하나의 KRW-BTC 항목이어야 한다. 중복 필드/연속 문서/잘못된 UTF-8·깊이/문자열/숫자 길이를 제한한다. 잘못된 UTF-8을 대체 문자로 바꾸면 원문 바이트 지문이 달라지므로 정상화하지 않고 거부한다. 가격은 BigDecimal로 읽고 양수·정수30자리/소수8자리 이내라는 자체 수신 범위를 확인한다. 반올림하지 않는다.

trade_timestamp와 timestamp를 각각 체결/공급사 시각으로 구분한다. 체결 시각이 스냅샷보다 미래이거나2분보다 오래됐거나, 공급사 시각이 로컬 완료보다10초 이상 미래이면 실패한다. 시차가 있는 로컬 문자열 대신 밀리초 epoch를 Instant로 변환한다. 응용 재시도/리다이렉트는 없고 오류 원문/원인은 외부 예외에 붙이지 않는다. 고객 정보/주문/개인키/수탁 요청은 공급사로 보내지 않는다.

BitcoinKrwPriceService는 짧은 시작 TX에서 조회/현재 위치/필수 감사를 먼저 확정하고, TX 밖에서 HTTP를 호출한 뒤 결과/원문·지문/감사를 새 TX로 저장한다. 실패는 PROVIDER_UNAVAILABLE/INVALID_SOURCE/INTERNAL_FAILURE만 보존한다. 완료 DB/감사 실패는 원격 실패로 다시 저장하지 않고 시작만 남긴다.2분 이내 미완료 조회는 중복 시작409이며 이후 새 조회가 대체해도 옛 이력을 삭제하지 않는다.

현재 읽기는 현재 위치를 FOR SHARE로 고정하고 최신 결과/체결 시각과 지문을 검증한다. 미설정/없음·미완료/실패/오래된 자료는503이며 이전 성공으로 우회하지 않는다. 정상적인 가격 부재는 조회 TX 밖에서 예외로 바꿔 바깥 TX의 롤백 전용 표시를 남기지 않는다. DB/SQL 오류는 그대로 실패한다. 이 구분은 실제 수탁 자산의 존재를 기록하는 작업과 자금 사용/환산을 분리하기 위한 계약이다. 아직 입금 원장에 가격을 결합하는 코드는 추가하지 않았다.

btc_krw_price_attempts/heads/results는 시작·현재 위치·불변 원본을 나눈다. 원문 SHA-256, 페어·정확한 가격/밀리초 시각과 DB 열의 일치, 상태별 필수/NULL 조합·시각/현재 위치 전진을 검사한다. PostgreSQL CHECK의 UNKNOWN 허용을 고려해 필수 값과 COALESCE를 명시한다. UPDATE/DELETE/TRUNCATE를 막는다. 기존 공통 불변 트리거 함수를 재사용해 그 함수를 준비한 KYC 초기화 뒤 스키마를 준비하며, 고객 자격/신원을 가격 판단에 사용하는 의존성은 아니다.

BitcoinKrwPriceWorker는 valuation.upbit.enabled=false가 기본이다. 켜면 전용 스레드 하나에서 완료 후30초 간격으로 갱신한다. test 프로필은 강제 true여도 자동 등록하지 않는다. 실패가 다음 주기를 취소하지 않고 종료되지 않은 실행기와 대체 실행기를 겹치지 않는다. 별도 임의 주기 설정이나 메모리 가격 폴백은 추가하지 않았다.

프론트의 자체 호가창/체결/차트·BTC/USDT 시장·봇 상태·Rust 코어는 변경하지 않았다. 이 참고가격 수집은 고객 화면에 업비트 호가를 표시하는 작업이 아니다.

## 실제 코드와 이해에 필요한 문법

출처: [BitcoinKrwPriceClient.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinKrwPriceClient.java#L53), 현재 소스의 부분 발췌.

```java
                BigDecimal price=ticker.path("trade_price").decimalValue();if(price.signum()<=0||price.scale()>8||price.precision()-(long)price.scale()>30)throw unavailable();price=price.setScale(8,RoundingMode.UNNECESSARY);
                Instant trade=time(ticker,"trade_timestamp"),provider=time(ticker,"timestamp"),finish=Instant.now();
                // 두 시각을 구분한다. 최근 스냅샷이라는 주장만으로 오래된 체결가를 새 가격으로 보지 않는다.
                if(trade.isAfter(provider)||provider.isAfter(finish.plusSeconds(10))||trade.isBefore(start.minusSeconds(120))||finish.isBefore(start))throw unavailable();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
```

BigDecimal은 입력 JSON 십진수를 정확히 표현한다. TypeScript number와 같은 이진 실수로 먼저 바꾼 뒤 복구하지 않는다. setScale(8, UNNECESSARY)는 값을 반올림해서 받아들이는 옵션이 아니며, 소수 범위를 벗어나면 실패한다. Instant는 공급사 밀리초와 로컬 읽기 시각을 구분하는 절대 시각이다.

출처: [BitcoinKrwPriceService.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinKrwPriceService.java#L66), 현재 소스의 부분 발췌.

```java
        // 완료 때만 현재 위치를 옮기면 새 실패를 과거 성공으로 숨긴다. 시작 원본과 현재 위치를 같은 TX에서 먼저 확정한다.
        jdbc.update("INSERT INTO btc_krw_price_heads(singleton,attempt_id) VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET attempt_id=excluded.attempt_id",id);
        audit.record(null,"BTC_KRW_PRICE_STARTED","btc-krw-reference","SUCCESS","ATTEMPT:"+id);
        return new Start(id,((Timestamp)row.get("started_at")).toInstant());
```

현재 위치는 완료 때가 아니라 시작과 함께 커밋한다. 새 조회가 아직 끝나지 않았거나 실패해도 옛 성공으로 새 판단을 통과시키지 않는다. 저장/HTTP의 원자성을 보장한다고 설명하지 않고 시작·결과·감사 이력을 연결한다.

출처: [BitcoinKrwPriceService.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinKrwPriceService.java#L83), 현재 소스의 부분 발췌.

```java
        // 가격 부재는 DB 오류가 아니다. 조회가 참여한 바깥 TX를 롤백 전용으로 오염시키지 않아 실제 수탁 자산은 별도로 기록할 수 있게 한다.
        // 고객 자금 사용은 호출 업무가 보류한다. SQL/무결성 제약 자체의 오류는 위 TX에서 그대로 실패시킨다.
        if(snapshot==null)throw BitcoinKrwPriceClient.unavailable();return snapshot;
    }
```

참여한 TransactionTemplate 안에서 예외를 던지면 바깥 TX가 rollback-only로 표시될 수 있다. 정상적인 부재는 조회 결과로 반환한 뒤 예외로 바꾼다. 가격을 쓰는 업무는 보류할 수 있고 실제 수탁 자산 기록은 별도로 커밋할 수 있다. SQL 오류를 성공으로 바꾸는 폴백은 아니다.

출처: [BitcoinKrwPriceWorker.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinKrwPriceWorker.java#L31), 현재 소스의 부분 발췌.

```java
            @Value("${valuation.upbit.enabled:false}") boolean enabled){
        this(processing,enabled,Duration.ofSeconds(30));
    }
```

명시적으로 켠 프로세스에서만 주기 조회를 시작한다. @Profile("!test")로 시험 프로필의 자동 기동을 별도로 막는다. 기본30초는 완료 후 지연이며 전체 네트워크/작업 시간을 포함한 정확한30초 처리 보장은 아니다.

## 작성한 테스트

[BitcoinKrwPriceClientTest](../../src/test/java/dustin/cex/domains/valuation/BitcoinKrwPriceClientTest.java)10개는 소유 HTTP 서버/가상 실행기에서 실제 JDK 요청과 원문/시간/오류 경계를 검증한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|exactPriceAndBothSourceTimesArePreserved | 정확한8자리 가격·체결/스냅샷 시각·원문 SHA-256 보존 |
|wrongOrAmbiguousMarketNeverBecomesQuote | 다른 페어·빈/복수 배열 거절 |
|staleFutureReversedAndNonIntegralSourceTimesAreRejected | 낡은/미래·순서 반대·문자열 시각 거절 |
|invalidPricesNeverBecomeRoundedOrZeroValues |0·문자열·소수8자리 초과/큰 가격 거절·임의 반올림 없음 |
|duplicateTrailingAndOversizedBodiesAreRejected | 중복·연속 JSON·유효하지만64KiB 넘는 본문 거부 |
|invalidUtf8CannotChangeOriginalByteFingerprint | 잘못된 인코딩 대체/정상화 거부 |
|errorsAndRedirectsAreNotRetriedOrFollowed |429/302 각각1회·원문/원인 없음·따라가지 않음 |
|stalledBodyHasAbsoluteDeadline | 본문 정지는 실제4초 이상8초 미만에 실패·호출1 |
|ambientTransactionCannotCallProvider | TX 안에서는 실제 HTTP0 |
|arbitraryRemoteOrUnsafeTestOriginCannotOverrideProductSource | 임의 원격/경로/사용자 정보 주소 거부 |

[BitcoinKrwPriceIntegrationTest](../../src/test/java/dustin/cex/domains/valuation/BitcoinKrwPriceIntegrationTest.java)11개는 메서드별 새 PG DB에서 검증한다. 고정된 단일 현재 위치와 미완료 감사 시험을 다른 메서드의 초기 상태로 재사용하지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|committedStartPrecedesIoAndPendingHeadCannotExposeOlderQuote | HTTP 시 DB TX 없음·시작 커밋·미완료 동안 옛 성공 차단·정확한 가격/감사 |
|latestProviderAndInternalFailuresNeverBorrowOldSuccessfulQuote | 새 공급사/내부 실패는 제한 코드만 저장·옛 성공으로 현재 조회 불가 |
|disabledAndAmbientCallsCannotStartAnySourceIo | 비활성/TX 내 갱신은 I/O0·정상 가격 부재를 처리한 바깥 TX는 커밋 가능 |
|startAuditFailureRollsBackBeforeProviderIo | 시작 감사 실패는 조회/위치0·HTTP0 |
|finishAuditFailureLeavesPendingHeadInsteadOfInventingProviderFailure | 완료 감사 실패는 새 미완료 위치/옛 원본 보존·원격 실패 재저장 없음 |
|concurrentRefreshHasOneClaimAndNoSecondSourceCall | 동시 시작은1개·중복409·공급사1회 |
|sourceRowsAreImmutableAndHeadCannotBeRewound | 원본 DELETE/TRUNCATE·현재 위치 되돌리기 거부 |
|sqlCannotStoreIncompleteOrMismatchedQuote | 직접 SQL의 불완전 성공/다른 원문 지문을 CHECK 위반23514로 거부 |
|callerTransactionPinsCurrentHeadUntilItFinishes | pg_stat_activity의 실제 Lock 대기를 확인·읽는 TX 완료 전 새 HTTP 시작 불가 |
|staleHistoricalQuoteIsNotCurrentAndRemainsUnchanged | 과거 시각으로 추가한 정상 원본은 현재 가격으로 거부·원본 보존 |
|ownedProductionPublisherCapturesSourceAndStops | 소유 production 컨텍스트/실제 서비스·PG/공급사 대역에서 명시 활성화·수집/종료 |

[BitcoinKrwPriceWorkerTest](../../src/test/java/dustin/cex/domains/valuation/BitcoinKrwPriceWorkerTest.java)9개다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|disabledCannotStartAnySourceRead | 비활성은 조회0 |
|oneDedicatedThreadDoesNotOverlapOrBlockOtherScheduler | 전용1스레드·중복 시작/호출 없음·다른 실행기 진행 |
|fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks | 실행 중 작업 누적 없음·완료 뒤 지연 |
|storedFailureAndThrownPersistenceFailureDoNotCancelFutureAttempts | 저장된 실패/예외 뒤 다음 주기 유지 |
|stoppedWorkerCanRestartAfterOldExecutorTerminates | 종료한 실행기만 재시작 |
|taskIgnoringShutdownCannotStartOverlappingReplacement | 종료를 무시한 작업과 새 실행기 중첩 금지 |
|testProfileCannotAutomaticallyStartEvenWhenConfiguredAndEnabled | test 강제 활성에도 자동 빈 없음 |
|productionContextHonorsFlagAndClosesItsAutomaticallyStartedExecutor | production 설정별 자동 기동·컨텍스트 종료 뒤 스레드 제거 |
|absentSourceFlagCannotStartLivePublicRead | 설정 미제공 기본은 비활성·조회0 |

기존 H2 정산 시험 [TradeSettlementRunServiceTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementRunServiceTest.java)/[TradeSettlementFailureTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementFailureTest.java)에는 새 PG 전용 가격 초기화를 명시적 대역으로 추가했다. H2가 이 가격 스키마를 검증한 것처럼 표시하지 않고 가격 원본은 실제 PG에서 검증한다. 기존 금융182개도 다시 실행했다. 모든 새 PG 시험 뒤 엔진/Kafka 호출0과 user_balances0을 확인한다. 실제 공급사·규제 판단·원장에 결합한 입금/재시작 복구 전체를 검증한 시험은 아니다.

## 실행 명령과 결과

- Java21/macOS arm64/소유 HTTP·PG16. 초기 클라이언트10개와 가격/PG10개를 검증한 뒤 주기 수집/수명 시험과 바깥 TX의 가격 부재 처리를 보강했다. 마지막에 설명 주석만 보강했다. 이번 작업에서 실패한 제품/시험 실행은 없다.
- `python3 scripts/verify_bitcoin_krw_prices.py`: 최종30개(HTTP10+PG11+수명9) 통과, 실패/오류/제외0. 하나의 소유 PG 서버에서11개 새 DB를 만들고 메서드별 실행했다. 첫 DB 그룹에서 HTTP/수명19개도 실행하고 나머지 그룹은 해당 PG 메서드1개만 실행했다. 전체 사례 중복0.
- 이 실행기는 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_/VALUATION_를 제거하고 봇0·소비자 자동 시작 금지·격리 외부 주소/새 DB를 고정한다. 원본/감사 이력을 삭제하거나 불변 트리거를 끄지 않았다.
- `python3 scripts/verify_engine_inbox.py`: 금융182개 통과, 실패/오류/제외0. 상위 실행에서도 위 접두 환경을 제거하고 소비자 자동 시작 금지를 고정했다. H2 fixture 범위와 실제 PG 가격 시험을 구분한다.
- 고유212개/최종 검증 집합212실행·중복0. 두 최종 소유 PG 컨테이너 제거 확인. `.local/cycle109-proof`에11개 그룹별 XML/로그와 원래 실행 manifest, 금융182 XML/로그/manifest·중복 대조를 보존한다. 초기/중간20/30개와 앞 회차205개를 현재 합계에 더하지 않는다.
- bootJar/operatorAdminJar 성공·두 JAR에 제품 클래스/valuation 스키마 포함·시험 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고는 남는다. 새 의존성 없음. `git diff --check`·현재 발췌/상대 링크 대조.
- 실제 업비트/JDK 클라이언트 실서비스 요청·가격 사용 계약·실제 노드/기존 개인정보 HTTP·Rust/Kafka/브라우저는 이번에 검증하지 않았다. 공식 HTTP는 문서 계약 대역이며 고시 본문 확인을 위한 공식 공개 자료 조회와 구분한다. 실제 배포·봇 활성화/개인정보 제공도 하지 않았다.

## 남은 업무

특정 입금의 수취/인식 시점에 당시 사용 가능한 가격 원본을 결합하는 기록, 가격 부재/오래된 입금의 근거 처리·독립 심사, 법적 적용 범위와 자체 위험 정책을 구분한 정보 제공 대상 판정이 남는다. 현재 참고가격만으로 송신 사업자의 과거 이전 요청 시점 가액을 증명하거나 소액 면제·모든 금액 의무라고 판정하지 않는다. 현재가로 과거 입금 값을 추측해 채우는 경로도 추가하지 않았다.

그 뒤에도 정보 교환 응답의 현재 고객/동일인·위험 판단, 불명/개인지갑 등 운영 해결, 원장/엔진 적립·재조직 복구·출금은 미완료다.109는 원화 가격 자료의 관측/보관 경계다. valuation.upbit.enabled와 개인정보 자동 제공은 기본 false를 유지하며 실제 사용 조건·가격 표시/환산 정책·외부 심사/운영 적합성 확인과 전체 보존/관측은 별도다.


## 관련 커밋과 증적

- [구현 28ea2e6](https://github.com/GiHoon1123/Cex-Platform-Service/commit/28ea2e645b523af0da5acbfc494bdf6342c01d10)
- [격리 실행 증적](../reference/109-durable-bitcoin-krw-reference-price-verification-2026-10-25.json)
