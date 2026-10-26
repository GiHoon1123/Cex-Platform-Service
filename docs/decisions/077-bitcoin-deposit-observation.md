# 실제 거래 출력과 기준 블록을 대조하는 BTC 입금 관찰

지갑의 수신 상세와 실제 거래 출력·현재 기준 블록을 대조해 BTC 입금 관찰 값을 만든다. 미확정·현재 체인 포함·충돌 상태를 구분하며 노드 금액을 십진수로 읽는다. 관찰은 아직 영속 저장하거나 고객 잔액에 반영하지 않는다.

## 문제와 공식 접근

[074](074-customer-bitcoin-deposit-address.md)의 주소 원본과 [엔진018](../../../Cex-Engine/docs/decisions/018-idempotent-deposit-credit.md)의 멱등 반입을 연결하려면 어떤 체인 거래 출력이 현재 유효한지 먼저 확인해야 한다. 주소에 표시된 합계나 txid 하나만으로 입금 완료를 정하면 중복 출력·재조직·다른 주소·금액 오독을 놓칠 수 있다.

2026-10-25 [Bitcoin Core31 RPC gettransaction](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/gettransaction/)과 [31.1 실제 구현](https://github.com/bitcoin/bitcoin/blob/v31.1/src/wallet/rpc/transactions.cpp)을 확인했다. 지갑의 details와 verbose 해독 결과를 함께 읽는다. confirmations가 음수면 충돌 상태이며 generated/coinbase는 일반 수신과 구분한다. Core는 결과에 lastprocessedblock을 넣고 지갑 조회 전 현재 체인 반영을 기다린다. 이 기준 블록도 대조한다.

설치된 Jackson3.1.8의 JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS와 ObjectMapper.reader(DatatypeFeature)를 javap로 확인했다. 실제 큰 소수 입력 시험으로 십진수 보존을 검증했다. JSON 해독기를 직접 구현하거나 새로운 패키지를 추가하지 않았다. 노드의 txid·공개 출력 정보를 검증된 Core RPC에서 받으며 블록 합의나 서명 검증을 Java에서 재구현하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 지갑 상세와 해독 vout 교차 대조 | 단순 지갑 순증감 대신 실제 출력 번호/주소/지출 조건/금액 식별 | 거래 원문/해독 정보가 커질 수 있음. 합계 API보다 RPC·메모리 비용이 큼 |
| 전용 ObjectReader의 BigDecimal | 노드가 숫자로 보내는 금액도 Double을 거치지 않음 | 소수 토큰의 십진 객체 생성 비용. 글로벌 ObjectMapper의 다른 API 설정은 변경하지 않음 |
| 조회 전/후 tip와 거래 자체의 lastprocessedblock 대조 | 중간 체인 변동·지갑 지연·서로 다른 시점 결과 결합을 거부 | 새 블록이 도착하는 동안 조회가503으로 끝날 수 있음. 다음 조회가 다시 확인해야 하며 현재 잔액을 추측해 쓰지 않음 |
| 기준 블록의 정규 체인 포함 확인 | 양수 confirmations만 그대로 신뢰하지 않고 헤더/높이/깊이/해당 높이의 현재 해시를 대조 | 확정 관찰에 순차 RPC6회, 각 본문5초 상한과 JSON 해독 비용. 코어/DB 트랜잭션 밖의 별도 업무에서만 사용 |
| 64KiB 일반 응답과32MiB 거래 응답 제한 | 큰 거래를 지원하면서 무제한 본문 축적을 막음 | 본문 외 JSON 트리 메모리도 필요. 상한 초과는 일부 결과 없이503이며, 현재 한도가 모든 미래/최악 입력의 운영 처리량을 보장하지 않음 |
| 코인베이스 별도 표시 | 채굴 보상을 일반 수신과 혼동하지 않음 | 적립 정책에서 성숙도를 별도 검증해야 함. 이번 단계에서 coinbase=true만으로 적립 허용하지 않음 |

## 구현과 데이터 흐름

BitcoinRpcClient로 기존 전송/기본 준비 확인을 분리했다. 주소 파생과 입금 판독은 같은 장수 HttpClient·고정 엔드포인트/자격·크기 제한·본문 전체 제한 시간·오류 비밀 제거를 공유한다. 기존 생성자/close 메서드를 호환용으로 남기지 않고 실제 시험 호출자와 종료 소유권을 수정했다. RPC 빈이 종료를 담당한다.

BitcoinDepositReader.inspect는 정확한64자리 소문자 거래 ID를 받아 다음을 확인한다.

1. DB 트랜잭션 외부 호출, 기대 네트워크, 초기 동기화 완료, 관찰 전용 descriptor 지갑, 스캔 미진행.
2. 체인 tip와 지갑 lastprocessedblock의 해시/높이 일치.
3. gettransaction의 txid와 decoded.txid 일치, 거래 응답 자체의 lastprocessedblock도 같은 tip인지 확인. 조회 전/후 tip가 같아도 중간에 다른 체인에서 읽은 거래를 받아들이지 않는다.
4. 양수 confirmations는 헤더 해시/높이/깊이, tip로 계산한 깊이, getblockhash(높이)와 모두 일치해야 한다. 0은 UNCONFIRMED, 음수는 CONFLICTED이며 정규 포함 블록을 부여하지 않는다.
5. details의 수신/채굴 관련 항목을 연속된 해독 vout 번호·주소·scriptPubKey·금액과 대조한다. 같은 출력 번호 중복은 거부한다. 출금 상세나 임의의 나머지 출력은 선택하지 않는다.
6. 조회 뒤 체인 tip/높이/네트워크/초기 동기화 상태를 재확인한다.

Observation은 네트워크, txid, 관찰 상태/확정 수, 코인베이스 여부, 정규 포함 블록/높이, 기준 tip/높이, 읽기 전용 출력 목록이다. CONFIRMED는 현재 정규 체인에 포함됐다는 뜻이며 서비스의 최종 적립 확정이나 재조직 불가능을 뜻하지 않는다. 원문 hex·공개 descriptor·RPC 자격을 결과에 싣지 않는다.

[Core31.1 금액 상수](https://github.com/bitcoin/bitcoin/blob/v31.1/src/consensus/amount.h)의 COIN/MAX_MONEY를 확인했다. 금액은0 이상21,000,000BTC 이하와 정확한8자리 표현을 검사한다. 이 값은 프로토콜의 상한 범위 검증이며 유통량/수탁 자산 총합 검증이 아니다. 반올림은 없다. 거대한 지수/자릿수는 setScale보다 먼저 검사해 불필요한 큰 정수 확장을 피한다. 관찰 금액0은 표현할 수 있지만 엔진의 양수 반입 명령이나 적립 허용을 뜻하지 않는다.

## 실제 코드와 문법

출처: [BitcoinJsonRpcClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinJsonRpcClient.java#L23), 156에서 역할별 클라이언트가 공유하도록 옮긴 현재 소스의 부분 발췌.

```java
        tree=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
```

ObjectReader는 이 통신에만 적용하는 해독 설정이다. TypeScript의 Number나 JSON.parse 결과를 금액 계산에 바로 쓰는 것과 달리 소수 원문을 BigDecimal로 보존한다. 다른 업무의 전역 매퍼를 변경하거나 자체 숫자 파서를 만들지 않는다.149에서 공통 RPC 판독기는 중복 키와 문서 뒤 추가 JSON도 거절하도록 강화했다. 아래 금액 보존 원칙은 그대로 유지한다.

출처: [BitcoinDepositReader.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReader.java#L28), 현재 소스의 부분 발췌.

```java
        if(!hash(transaction,"txid").equals(txid)||!hash(decoded,"txid").equals(txid)
                ||!hash(transaction.path("lastprocessedblock"),"hash").equals(tip)
                ||integer(transaction.path("lastprocessedblock"),"height")!=height
                ||!decoded.path("vin").isArray()||decoded.path("vin").isEmpty()
                ||!decoded.path("vout").isArray()||!transaction.path("details").isArray())throw unavailable();
        long confirmations=integer(transaction,"confirmations");
        boolean coinbase=decoded.path("vin").get(0).path("coinbase").isString();
```

거래를 조회한 지갑의 기준 블록까지 같은지 확인한다. 조회 전후 tip만 비교하면 체인이 바뀌었다가 원래 tip로 돌아온 사이에 읽은 결과를 놓칠 수 있다. nullable 블록/높이는 뒤에서 관찰 상태에 따라 명시적으로 결정한다.

출처: [BitcoinAmounts.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinAmounts.java#L13), 086의 공통 부호 검증을 반영한 현재 소스의 부분 발췌.

```java
    static BigDecimal signedFromNode(tools.jackson.databind.JsonNode node){
        // 실수 토큰은 전용 ObjectReader가 BigDecimal로 만든다. DoubleNode로 바뀌면 잘못된 정밀도를 추측하지 않는다.
        if(!node.isNumber()||(!node.isIntegralNumber()&&!node.isBigDecimal()))throw unavailable();
        try{
            BigDecimal value=node.decimalValue();
            // 거대한 지수/소수 자릿수를 먼저 거부한다. 범위 검사 전에 setScale로 큰 정수를 만들지 않는다.
            if(value.abs().compareTo(new BigDecimal("21000000"))>0||value.stripTrailingZeros().scale()>8)throw unavailable();
            return value.setScale(8,RoundingMode.UNNECESSARY);
        }catch(ArithmeticException e){throw unavailable();}
    }
```

BigDecimal의 compareTo는 금액 비교이고 setScale의 UNNECESSARY는 반올림이 필요하면 실패시킨다. 범위와 실질 소수 자릿수를 먼저 확인하므로 극단적 지수의 배율 확장을 시작하지 않는다. BTC8자리 관찰과 엔진의 공통9자리 정산 계약을 구분한다.

## 작성한 시험

[BitcoinDepositReaderTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReaderTest.java)는 실제 루프백 HTTP·전용 JSON 해독기를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| confirmedOutputHasExactAmountAndExplicitCanonicalPosition | 정확한 금액·출력과 명시적 블록/깊이 반환, 목록 수정 금지 |
| largeSatoshiAmountsNeverPassThroughDoubleRounding | 20999999.99999999/1사토시/상한/0의8자리 정확성 |
| unsupportedPrecisionNegativeAndSupplyOverflowAreRejected | 9자리 실제 소수·음수·상한 초과·거대한 양/음 지수 거부 |
| decodedTransactionOutputAndReceiveDetailsMustAgree | 다른 txid/주소/금액·중복 출력·출력 번호 범위 초과·코인베이스 모순 거부 |
| conflictingDepthCanonicalHashAndMovingTipAreRejected | 헤더 깊이·정규 해시·확정 수 범위 초과·변동 tip·거래 조회 기준 tip 모순 거부 |
| laggingWalletCannotGiveApparentlyCurrentObservation | 지갑 반영 지연 시 거래 조회 전503 |
| unconfirmedAndConflictedAreNeverGivenCanonicalBlock | 0/음수 확정 수를 구분하고 블록/높이는 null |
| invalidIdentifierAndOuterTransactionDoNotContactNode | 잘못된 ID/열린 DB TX 거부, 거래 호출0회 |

[BitcoinDepositReaderIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReaderIntegrationTest.java)는 실제31.1 regtest 거래를 읽는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| realConfirmedReceiveMatchesDecodedOutputAndCanonicalBlock | 실제0.12345678BTC 수신의 출력/지출 조건·정규 블록/깊이 일치 |
| actualUnconfirmedReceiveHasNoCanonicalBlock | 실제 mempool 수신의 확정 수0·블록 없음 |
| realFeeReplacementIsConflictedAndNeverPresentedAsConfirmed | 실제 fee bump 대체 후 원래 거래의 음수 확정 수·충돌 상태 |
| invalidatedBlockReceiveIsReadAgainAsUnconfirmed | 시험 준비에서 실제 포함1회를 확인한 뒤 invalidateblock. 다시 읽으면0·미확정 |
| realCoinbaseIsMarkedSeparatelyFromOrdinaryDeposit | 실제 채굴 보상을 coinbase로 표시하며 일반 입금 적립과 구분 |

기존 BitcoinCoreClientTest의7개·Core 실제 지갑3개·본문 제한3개·주소 소유권15개도 실행했다. 실제 RPC 권한 거부 시험은 sendrawtransaction/bumpfee/invalidateblock도 추가로403인지 확인했다. 별도의 기존 HTTP/시작/정산29개로 새 빈 구성과 기존076 H2 경계도 재확인했다.

## 로컬 체인 준비와 실행 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

실행기는 외부 통신 없는 regtest/peer0·임시 데이터 경로·제한 RPC 계정·별도 PG를 소유한다. Python 준비 프로세스가 시험용 개인키 지갑으로 블록/수신/수수료 대체/재조직을 만든다. walletbroadcast=0을 유지하고 서명된 공개 거래를 sendrawtransaction으로 **해당 로컬 mempool에만** 명시적으로 넣는다. Java에는 관리자 쿠키·개인키를 주지 않는다. 같은 시험 노드에 개인키 지갑이 있으므로 운영 서명 격리를 검증했다는 뜻은 아니다.

첫 준비는 블록100개 생성 RPC가 시험용10초 제한을 넘었다. 해당 노드는 종료했고 생성 명령을 맹목 재시도하지 않았다. 새 시험 노드에서10개씩 묶어 생성하도록 수정했다. 두 번째 준비는 자동 전파를 끈 시험 지갑의 생성 거래를 로컬 mempool에 넣지 않아 지갑 조회-5로 실패했다. 준비 프로세스에 명시적 로컬 전송을 추가했다. 두 준비 실패에서는 Java 시험이 실행되지 않았으며 이전 XML/로그를 이번 결과로 집계하지 않았다.

최종 실제 체인 증적은 확정 수3, 대체 충돌-2, 재조직 전1→후0이다. **신규13개+기존28개=41개 통과, 별도 HTTP/시작/정산29개 통과: 고유70개, 실패/오류/제외0개.** 중간41개 실행은 합산하지 않는다. bootJar/operatorAdminJar 빌드와 시험 노드 종료/PG 제거를 확인했다. 별도29개 실행은 개인 공급사/메일/BITCOIN 환경을 제거하고 H2·루프백 HTTP 범위만 사용했다.

macOS arm64·Temurin21·Core31.1·PostgreSQL16 환경이다. 기존 deprecated/unchecked/JVM 계측 경고는 남는다. 새로운 패키지/OSV 전체 감사/처리량 측정은 없다. RPC 상한5초는 각 본문 수신에 대한 것이며 전체 관찰의6회 통신·JSON 해독이5초 안에 끝난다는 보장이 아니다.

## 다음 연결과 운영 한계

현재는 알려진 거래 ID를 읽는 내부 API다. 지갑 거래 발견/체크포인트·불변 관찰 이력·고객 주소 소유 연결·확정 수/코인베이스 성숙도 정책·중복/재조직 후 보류·원장·엔진 반입·Kafka 소비는 후속이다. 관찰 상태를 고객 거래 가능 잔액으로 표시하지 않는다. 실제 MAINNET/수탁·격리 서명·사업 신고/규제 충족을 검증하지 않았다.

운영 RPC 자격의 메서드 허용은 지갑별 권한 분리까지 뜻하지 않는다. 운영 관찰 노드는 개인키 지갑과 분리하고 접근 망/인증/공개 descriptor 보관을 관리해야 한다. 대용량 본문과 JSON 트리·순차 RPC 비용을 단일 호스트 자원 예산과 함께 검증해야 한다. 디스크/호스트 손실 복제·백업은 기존 지시대로 필요 조건만 기록한다.


## 관련 커밋과 증적

- [구현 48f6fed](https://github.com/GiHoon1123/Cex-Platform-Service/commit/48f6fedea4025f5b2df236dfcc0f887a3346b0c8)
- [격리 실행 증적](../reference/077-bitcoin-deposit-observation-verification-2026-10-25.json)

- [전송·십진수 해독 분리 fbf7203](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fbf7203)

후속 [078 불변 관찰 저장](078-durable-bitcoin-observations.md)에서 관찰/현재 포인터/감사를 같은 TX로 확정했다. 077의 미저장 설명은 해당 사이클 시점이다. 자동 발견·금융 적립은 여전히 후속이다.

086 현행 기준: [지갑 자산 사용 근거](086-bitcoin-wallet-debit-provenance.md)를 별도로 저장한다. 수신액의 fromNode는 계속 음수를 거부하고 signedFromNode의 부호 허용은 fee 표지 해독에 사용한다.
