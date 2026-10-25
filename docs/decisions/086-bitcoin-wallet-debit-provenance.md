# 수탁 지갑의 자산 사용을 새 고객 입금과 구분할 근거 남기기

관찰 지갑의 자산을 사용한 거래인지 불변 근거로 남긴다. 내부 지갑 이동을 외부 고객 입금으로 중복 적립하지 않도록 금융 반입 이전에 필요한 출처 경계와 시험 지갑 분리를 설명한다. 이번 단계는 출처 표지 확보이며 아직 원장/엔진 금융 적립 경로를 만들지 않았다.

## 문제와 기존 동작

085는 출력 일치/일반 확인 수/보상 성숙도를 판단했다. 내부 이동도 같은 출력 조건과 확인 수를 만족할 수 있으므로 CHAIN_READY만으로 외부 자산 유입을 추정하면 고객 부채를 중복 증가시킬 수 있다. 기존 판독은 수신 상세만 골라 send를 무시했으며, 관찰 지갑에서 자산이 사용됐다는 근거를 저장하지 않았다.

또한 이전 실제 노드 fixture는 수탁 공개 디스크립터를 내보낸 개인키 지갑으로 시험 자금을 만들었다. 수신/정밀도/복구 동작은 검증했지만 수탁과 외부 송금 지갑의 분리 증거는 아니었다. 금융 반입을 붙이기 전에 이 경계를 분리한다. 과거 테스트가 외부 입금 검증까지 완료했다고 재해석하지 않는다.

## 기존 제품과 공식 자료의 접근

[Core31.1 gettransaction 실제 구현](https://github.com/bitcoin/bitcoin/blob/v31.1/src/wallet/rpc/transactions.cpp#L696)은 CachedTxIsFromMe인 거래에 fee를 포함한다. 지갑의 debit은 입력 소유를 사용하므로 수신 출력이 있어도 자기 지갑의 자산을 쓰면 별도 표지로 볼 수 있다. [동일 파일의 send 상세](https://github.com/bitcoin/bitcoin/blob/v31.1/src/wallet/rpc/transactions.cpp#L285)도 지갑 사용 근거이며, 받는 출력만 보고 외부 입금이라고 판단하지 않는다.

모든 입력이 지갑 소유가 아닐 수 있으므로 이 fee 필드를 실제 전체 수수료 정산액으로 사용하지 않는다. 값은 부호가 있는 정확한 숫자로 검증하고 존재 여부만 walletDebit 근거로 사용한다. 특정 외부 송금자 신원/자금 출처/AML 심사 통과를 증명하는 표지가 아니다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·한계 |
| --- | --- | --- |
| walletDebit를 수신과 별도 기록 | 자기 지갑 이동/일부 지갑 입력이 섞인 거래를 금융 반입에서 따로 보류할 근거 | false는 해당 관찰 지갑에서 debit 표지가 없다는 뜻이며 전 세계의 외부 출처 증명이 아님 |
| fee 표지와 send 상세의 모순 거부 | send가 있는데 표지가 없거나 모르는 상세 유형이면 외부로 추측하지 않음 | 비표준/변경된 노드 응답은503으로 보류. 조용히 무시하는 대안보다 가용성이 낮을 수 있음 |
| 새 관찰과 같은 TX의 별도 불변 근거 | 과거 기록을 수정하지 않고 새 판독 근거를 정확한 관찰에 결합 | 근거 한 행/관찰 추가. 옛 관찰에 false 기본값을 채우지 않으므로 금융 반입 전에 새 조회 필요 |
| 수탁/외부 시험 지갑의 실제 키 분리 | 정상 외부 수신과 수탁 내부 이동을 실제 노드에서 구별 검증 | fixture 지갑2개/수신·거스름돈 공개 분기 반입 관리 |
| 부호 있는 fee와 비음수 입금 파서 분리 | 일부 지갑 입력의 fee 표현을 수신액에 섞거나 반올림하지 않음 | 공통 정확성 검증을 공유하되 수신/호가 금액의 음수 금지는 계속 별도 적용 |

운영에서 관찰 범위를 벗어난 다른 수탁 키/지갑의 사용은 이 한 지갑 표지만으로 탐지할 수 없다. 모든 현재 수탁 수신/거스름돈 분기를 관찰하고 서명 지갑/운영 목록과 대조하는 조건이 필요하다. 이 사이클은 HSM/모든 수탁 키 커버리지/실제 MAINNET 운영 적합성을 인증한 작업이 아니다.

## 구현과 실제 코드

`Observation`에 boolean walletDebit를 추가한다. fee가 있으면 정확한 부호 있는 BTC 숫자로 검증하고, send가 있는데 fee가 없거나 coinbase 보상 거래에 지갑 사용 표지가 있으면 모순으로 거부한다. 허용하는 상세 유형을 명시해 새로운 종류를 무조건 건너뛰지 않는다. 지갑 사용 표지가 있어도 원래 수신 출력/금액은 보존한다.


출처: [BitcoinDepositReader.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReader.java#L36), 현재 소스의 부분 발췌.

```java
        // fee 존재는 Core가 이 지갑의 자산 사용을 감지했다는 표지다. 실제 수수료 정산액이나 외부 송금자 신원으로 사용하지 않는다.
        boolean walletDebit=transaction.has("fee"),sent=false;
        if(walletDebit)BitcoinAmounts.signedFromNode(transaction.path("fee"));
        for(var detail:transaction.path("details")){
            String category=text(detail,"category");
            if(!Set.of("receive","generate","immature","orphan","send").contains(category))throw unavailable();
            if(category.equals("send"))sent=true;
        }
        if((sent&&!walletDebit)||(coinbase&&walletDebit))throw unavailable();
```

자기 지갑 사용 표지를 입금액과 별개로 계산한다. fee가0이어도 표지는 존재하며, 일부 입력만 지갑 소유이면 양수 표현도 그대로 검증한다. fee 값으로 고객에게 새 금액을 적립하지 않는다. boolean false를 EXTERNAL이라고 이름 붙이지 않는다.

출처: [BitcoinAmounts.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinAmounts.java#L9), 현재 소스의 부분 발췌.

```java
    static BigDecimal fromNode(tools.jackson.databind.JsonNode node){
        BigDecimal value=signedFromNode(node);if(value.signum()<0)throw unavailable();return value;
    }
    // 지갑 수수료 표지는 일부 입력만 지갑 소유일 때 양수일 수도 있다. 입금액과 분리해 부호 있는 원문만 검증한다.
    static BigDecimal signedFromNode(tools.jackson.databind.JsonNode node){
```

수신/호가 금액은 fromNode가 음수를 거부한다. signedFromNode는 같은 JSON 숫자 타입·범위·8자리/반올림 금지 검증을 공유하며 fee 표지 해독에만 부호를 허용한다. TypeScript Number로 양수/음수 금액을 합산하는 처리가 아니다.

출처: [BitcoinObservationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinObservationService.java#L51), 현재 소스의 부분 발췌.

```java
                long id=((Number)saved.get("id")).longValue();
                // 지갑 사용 근거도 같은 새 관찰 TX에 확정한다. 과거 원본에 false를 추측해 채우지 않는다.
                jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,?)",id,observation.walletDebit());
                jdbc.update("""
```

관찰 원본을 만든 같은 트랜잭션에서 근거를 추가하고 그 다음 현재 포인터/감사를 확정한다. 한 단계 실패 시 함께 롤백한다. 기존 관찰에 나중에 분류를 추측해 붙이지 않는다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L271), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS bitcoin_observation_wallet_debits (
 observation_id bigint PRIMARY KEY REFERENCES bitcoin_transaction_observations(id),wallet_debit boolean NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE OR REPLACE FUNCTION bitcoin_wallet_debit_new_observation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_transaction_observations o WHERE o.id=NEW.observation_id AND o.created_txid=txid_current()
   AND NOT(o.coinbase AND NEW.wallet_debit)) THEN
  RAISE EXCEPTION '지갑 사용 근거는 모순 없는 새 관찰과 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_wallet_debit_fresh ON bitcoin_observation_wallet_debits;
CREATE TRIGGER bitcoin_wallet_debit_fresh BEFORE INSERT ON bitcoin_observation_wallet_debits
```

외래 키는 정확한 관찰을 가리키며 삽입 트리거는 그 관찰이 현재 TX에서 만들어졌는지 확인한다. 보상 거래와 wallet_debit=true의 모순도 거부한다. 원본 수정/삭제/전체 비우기는 별도 불변 트리거가 막는다.

## 실제 노드 fixture 변경

[scripts/verify_bitcoin.py](../../scripts/verify_bitcoin.py)는 수탁 개인키 fixture_keys에서 wpkh 외부/내부 공개 디스크립터를 모두 cex_watch에 반입한다. 자금 생성/외부 송금/수수료 대체는 별도 funding_keys를 사용한다. 설정의 수신/거스름돈 유형을 bech32로 명시해 시험에서 관찰하지 않는 유형을 우연히 쓰지 않는다.

기존104 높이 계약을 유지하면서104 블록에 수탁 키 지갑의 자기 주소0.01BTC 전송도 포함한다. 일반0.12345678BTC는 서로 다른 키의 외부 수신이고 walletDebit=false, 내부0.01BTC는 walletDebit=true임을 준비 단계에서 실제 RPC로 대조한다. Java에는 기존 제한 RPC 계정만 제공하며 두 개인키 지갑/관리자 쿠키를 넘기지 않는다.

이 시험 구성의 분기 일치를 운영 수탁 목록 전체에 대한 자동 검증으로 일반화하지 않는다. 별도 수탁 지갑 추가/주소 유형 변경은 입력 자산 사용 탐지 범위를 함께 설계해야 한다.

## 작성한 테스트

[BitcoinDepositReaderTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReaderTest.java)에3개를 추가했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| feeAndSendSignalsNeverBecomeApparentlyExternalReceive | 정상 수신 false, fee/send와 fee만 존재하는 지갑 사용 모두 true |
| missingContradictoryAndUnparseableWalletDebitSignalsAreRejected | send 무표지/알 수 없는 상세/fee null·문자열·정밀도·범위/보상 모순503 |
| signedFeeEvidenceIsSeparateFromReceivedFinancialAmount | 양수 fee 표지도 지갑 사용으로 기록하고 원래 수신액0.12345678 그대로 |

[BitcoinDepositReaderIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReaderIntegrationTest.java)의 새 `actualCustodyWalletSpendRetainsDebitEvidenceAlongsideReceive`는 실제 수탁 내부 전송의 표지와0.01 수신을 동시에 확인한다. 기존 `realConfirmedReceiveMatchesDecodedOutputAndCanonicalBlock`에 외부 송금의 false 대조도 추가했다.

[BitcoinObservationIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinObservationIntegrationTest.java)의 새 `walletDebitEvidenceIsAtomicImmutableAndCannotBeAttachedToOldObservation`는 실제 내부 근거true·변경/삭제/비우기 거부·이전에 확정된 원본의 근거 후첨 거부를 확인한다. 기존 `actualObservationCommitsSourceCurrentPointerAndAuditWithoutCredit`는 실제 외부 근거false도 확인한다. 기존 감사 롤백/외래 키·현재 포인터 시험도 새 근거를 가진 실제 경로로 다시 실행했다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/묶음별 PG16에서 **기존126개+신규5개=고유131개 통과**, 실패/오류/제외0개다. addresses57/observations12/scans12/receipts10/worker7/jobs11/observation-worker6/qualification16은 각각 별도 PG 서버에서 실행했다. 실제 ordinary_wallet_debit=false/internal_wallet_debit=true와 마지막 tip107/일반6회를 증적에 남겼다. 두 제품 JAR 빌드와 소유 노드 종료/PG8개 제거를 확인했다. 봇0/peer0/외부 주소 격리를 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있고 새 의존성/MAINNET/OSV 감사/성능 측정은 없다.

## 다음 금융 단계와 한계

아직 원장/엔진 적립을 만들지 않았다. 다음 단계는 현재 온체인 준비와 이 지갑 사용 근거를 함께 검사해 보류 자산 원장에 기록하는 것이다. walletDebit=true 또는 근거가 없는 관찰로 새 외부 입금을 적립하지 않아야 한다. CHAIN_READY 자체는 온체인 조건이므로 내부 이동도 확인 깊이를 통과할 수 있다. 고객 자격/위험 심사·트래블룰·현재 증거의 기한·수탁/원장 대사·재조직/수수료·금융 반입은 각각 이어야 한다.


## 관련 커밋과 증적

- [구현 8576f5b](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8576f5b229d13a5029dd45df4277cc0ef3a82511)
- [격리 실행 증적](../reference/086-bitcoin-wallet-debit-provenance-verification-2026-10-25.json)
