# 관찰 전용 Core의 정확한 미서명 BTC 지급 거래 준비

관찰 전용 Bitcoin Core에서 미서명 PSBT를 준비하고, 고정 지급액/주소·실제 수탁 입력·자체 거스름돈·노드 비용 합계를 다시 검사한다. Core의 코인 선택/직렬화를 재사용하며 Java에 개인키를 넣거나 거래를 서명·방송하지 않는다. 이 결과는 아직 영속 지급 후보나 UTXO 예약·지급 완료가 아니다.

## 문제와 기존 동작

148의 독립 승인은 금융 원본에 대한 업무 결정이다. 이를 곧바로 실제 서명 RPC에 넘기기 전에 정확한 NET이 나가고 남는 금액이 자체 지갑으로 돌아오는지, 입력이 현재 수탁 소유/확정 상태인지, 고객 수수료와 실제 채굴자 비용을 혼동하지 않는지 확인해야 한다.

## 기존 제품과 공식 자료의 접근

설치된 공식 Core31.1의 RPC와 실제 응답을 먼저 확인했다. [walletcreatefundedpsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletcreatefundedpsbt/)는 코인 선택·입력/출력 작성·비용 계산을 제공한다. [decodepsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/decodepsbt/)와 [gettxout](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/gettxout/)을 사용해 준비 응답과 현재 UTXO를 대조한다. 코인 선택·주소 체크섬·Bitcoin 직렬화·ECDSA를 직접 구현하지 않았다.

[estimatesmartfee](https://bitcoincore.org/en/doc/31.0.0/rpc/util/estimatesmartfee/)의 BTC/kvB를 준비 요청의 sat/vB로 정확히 변환한다.31의 includeWatching은 미사용 deprecated 옵션이라 추가하지 않는다. 기존 BitcoinRpcClient의 장수 HttpClient/직접 연결/고정 노드·지갑/제한 본문/전체 RPC 기한을 사용하고 중복 키·뒤 JSON도 거절하도록 판독기를 강화했다. 설치 Jackson3.1.8의 ObjectReader.with(StreamReadFeature)/JsonNodeFeature 타입을 확인했다. 의존성은 추가하지 않았다.

[lockunspent](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/lockunspent/)에는 이미 persistent=true가 있다. 사전 격리 실험에서 wallet unload/load 뒤 복원과 자동 코인 선택 제외를 확인했다. 이 기능이 없다고 가정해 메모리 잠금/별도 코인 선택을 만들지 않는다. 현재 준비 컴포넌트는 잠금을 변경하지 않으며 영속 후보와 연결할 다음 단계에서 다룬다. 노드 reload 실험을 호스트/디스크 손실 복구 증명으로 확대하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| Core의 watch-only 코인 선택/PSBT | 유지보수되는 실제 Bitcoin 구현 재사용, 플랫폼에 개인키 없음 | 노드/지갑 가용성과 응답 신뢰가 필요. 독립 검증 노드/수탁 대사를 대체하지 않음 |
| 현재 노드 비용 추정·30초/같은 tip 확인 | 오래된/다른 체인의 비용과 입력을 섞지 않음 | 추정 불가·새 블록·느린 준비는 보류/새 준비 필요. 임의 가격 폴백 없음 |
| 자체 상한100sat/vB·총 네트워크 비용0.0001BTC | 과도한 네트워크 지출을 자동 준비에서 차단 | 혼잡/큰 거래는 보류. 시장 권장가나 법적 기준이 아닌 자체 정책이며 고객 요율0.01%와 별개 |
| 입력 최대100·응답1MiB·표준 자체 P2WPKH 입력 | 반복 RPC/메모리 범위를 제한하고 현재 수탁 형식만 검증 | 큰 이전 거래/다른 입력 형식/과다 입력은 별도 정책 필요. 지원 목적지5종과 입력 형식은 다름 |
|6확정·비코인베이스·소유/solvable·중복 입력 차단 | 미성숙/다른 소유/중복 UTXO를 지급 재원으로 오인하지 않음 | 실제 체인/노드 상태 조회가 필요. 여섯 확정이 재조직 불가능을 뜻하지 않음 |
| 외부 지급과 자체 목적지 구분 | 자체 수탁 잔액 변화와 외부 출금을 혼동하지 않음 | 내부 이체는 별도 업무이며 여기서 지원하지 않음 |
| 비서명/비방송·비잠금 준비 | 실패/응답 유실만으로 돈을 보내거나 출처 없는 입력 잠금을 만들지 않음 | 같은 입력을 여러 후보가 선택할 수 있음. 영속 후보/입력 UNIQUE/현재 승인·서명 연결이 다음 단계 |

실제 네트워크 비용은 입력합계−출력합계이며 고객 수수료를 출력에서 빼지 않는다. 실제 비용이 고정 고객 수수료보다 크거나 작을 수 있고 차이는 거래소 비용/수익으로 별도 추적해야 한다. 이 단계는 해당 비용의 원장 분개를 아직 만들지 않는다. 네트워크 상한 초과를 고객 수수료 인상으로 바꾸지 않는다.

## 구현과 실제 코드

출처: [BitcoinPayoutClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutClient.java#L66), 현재 소스의 부분 발췌.

```java
        var options=new LinkedHashMap<String,Object>();options.put("add_inputs",true);options.put("include_unsafe",false);options.put("minconf",6);
        options.put("fee_rate",quote.satPerVbyte().toPlainString());options.put("change_type","bech32");options.put("changePosition",1);
        // 잠금은 영속 후보와 연결한 다음 단계의 책임이다. RPC 응답 유실로 출처 없는 메모리 잠금을 만들지 않는다.
        options.put("lockUnspents",false);options.put("replaceable",false);options.put("subtractFeeFromOutputs",List.of());
        var created=rpc.call("walletcreatefundedpsbt",List.of(List.of(),List.of(Map.of(command.address(),command.amount().toPlainString())),0,options,true),BODY_LIMIT);
```

현재 부분 발췌다. Core가 코인을 선택하고 고객 NET 전체를 지급 출력에 넣는다. subtractFeeFromOutputs가 비어 있어 네트워크 비용을 고객 지급액에서 차감하지 않는다. replaceable=false는 대체 신호를 넣지 않는 선택이며 충돌·재조직·다른 키 보유자의 이중 지출 불가능을 보장하지 않는다.

출처: [BitcoinPayoutClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutClient.java#L54), 현재 소스의 부분 발췌.

```java
        BigDecimal rate=nodeMoney(fee,"feerate").movePointRight(5);
        // Core의 추정 단위는 BTC/kvB이고 준비 RPC의 fee_rate는 sat/vB다. 이진 실수나 반올림 없이100000배 한다.
        return new FeeQuote(settings.network.id,settings.wallet,rate,hex(ready.chain(),"bestblockhash"),integer(ready.chain(),"blocks"),started,Instant.now());
```

현재 부분 발췌다.1BTC는100000000sat이고1kvB는1000vB라100000배다. BigDecimal과 기존 BitcoinAmounts/ExactAmount를 사용해 큰 지수를 먼저 제한하고 BTC8자리·요율3자리를 반올림 없이 판독한다. TypeScript Number로 바꾼 뒤 계산하지 않는다.

출처: [BitcoinPayoutClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutClient.java#L116), 현재 소스의 부분 발췌.

```java
        if(inputSum.compareTo(new BigDecimal("21000000"))>0||inputSum.subtract(outputSum).compareTo(fee)!=0)throw held();
        fresh(quote);sameTip(rpc.call("getblockchaininfo",List.of()),quote);
        return new Prepared(command.withdrawalId(),POLICY,command.network(),settings.wallet,psbt,txid,command.amount().toPlainString(),fee.toPlainString(),changeAddress,changeScript,changeAmount,verified,quote,Instant.now());
    }
```

현재 부분 발췌다. 보고 fee만 믿지 않고 실제 입력/지급/거스름돈 합계가 맞는지 확인한다. 이후 같은 tip과 비용 수명도 다시 검사한다. Prepared는 금액을 정확한8자리 문자열로 보존하고 PSBT/지급지 진단 출력을 숨긴다. 영속/승인/서명을 대신하는 타입이 아니다.

BitcoinPayoutClient.Command는 서버가 고정한 출금 ID/네트워크/주소/스크립트/NET을 받는다. FeeQuote는 관찰 전용 노드의 비용·tip·조회 시점을 가진다. 실제 추정에 표본이 없으면 새 기본 요율을 만들지 않는다. 단순 양수·주소 문자열이 지급 권한은 아니며 이 컴포넌트에 공개 Controller는 없다.

Core 준비 결과와 decodepsbt의 실제 거래 버전2/locktime0, 비서명 입력, 중복 outpoint, UTXO확정/소유/값·스크립트, 포함된 이전 거래의 해당 출력, 지급액/주소/스크립트, 자체 내부 거스름돈, 전체 합계/비용을 대조한다. Core31의 비대체 신호 입력 sequence0xfffffffe도 실제 응답으로 확인해 허용한다. 다른 서명 해시 유형·서명/검토하지 않은 입력 메타데이터·추가 지급 출력은 거절한다.

Java record의 필드 참조는 final이지만 중첩 객체 전체가 자동 불변인 것은 아니다. Prepared는 List.copyOf로 입력 목록도 복사/고정한다. TypeScript의 readonly 타입과 달리 호출자가 실제 목록을 바꾸려 하면 런타임에서도 거절한다.

관찰 지갑의 ismine은 등록된 디스크립터 기준의 귀속이다. 단독 개인키 보유 증명이나 외부 수신인 신원 증명이 아니며 키 보호/수신인 심사는 별도다.

이 검증은 고정 노드가 제공하는 데이터에 대한 의미/일관성 검사다. 노드의 실제 Bitcoin 검증을 재사용하며 Core가 악의적으로 일치하는 허위 응답을 제공하는 상황까지 별도 합의 없이 해결했다고 주장하지 않는다. 기존 수탁 대사·권한/노드 운영 조건은 유지한다.

## 작성한 테스트

신규 BitcoinPayoutClientTest는 소유 HTTP 노드 대역에서 정확한 RPC 인자/비용 단위/목적지·합계 변조·다른/미성숙/중복 입력·이전 거래 불일치·서명/해시 유형·tip·오래된/미래 비용·중복/뒤 JSON·DB 잠금 중 RPC 거부·큰 지수·외부/내부 목적지 분리를 검증한다. 합성 PSBT 문자열 자체의 Bitcoin 유효성을 증명하는 시험은 아니다.

BitcoinPayoutCoreIntegrationTest는 실제31.1 regtest에서5종 목적지·NET/거스름돈/입력/비용·dust·잘못된 네트워크/키 지갑·자체 지급지 거절과 제한 RPC의 서명/방송/키 반출 거절을 확인한다. 실제 짧은 체인은 비용 추정 표본이 없어 자동 추정이 거절되는 것도 확인한다. 유효 거래 준비의2sat/vB는 시험이 제공하는 합성 비용 근거이며 실제 네트워크 요금/추정 성공으로 표현하지 않는다.

실행기가 임시 Core 키 지갑을 만들고 공개 디스크립터만 관찰 지갑으로 가져온다. Java에는 관리자 쿠키/개인키를 주지 않고 별도 RPC 허용 목록 계정만 전달한다. 외부 연결0·노드 종료·임시 데이터 정리를 확인한다. 실제 Core 준비가 고객 원장·승인·서명·방송을 끝냈다고 표시하지 않는다.

## 실행 명령과 결과

```sh
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin_payout_preparation.py
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
```

준비/공통노드65·기존BTC249·금융217·보안41, 고유520개/실제572회 통과·실패/오류/제외0. 같은 공통 노드/원장 사례를 두 번 실행한 부분은 고유 합계에서 제외했다. 현재 RPC 판독 변경의 실제 기존 입금/관찰/스캔/정책/원장/대사와 금융·보안을 검증했다. JAR 두 종류 빌드 통과. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 이전528개 결과를 이번 합계에 재사용하지 않았다. 기존 BTC/금융/보안 검증 후 새 클라이언트의 자체 수탁 목적지 거절과 실제 노드 경계 시험을 보강하고 준비/노드65개를 다시 실행했다. 공통 RPC 판독 소스의 동작은 그 사이 변경하지 않았다. 새 지급 클라이언트의 노드 금액 판독도 기존 BitcoinAmounts를 재사용하도록 정리하고 준비/노드65개를 최종 재실행했다. 실제 운영 공급사/공개 체인 지급/봇/인프라 배포는 하지 않았다.

## 다음 연결과 한계

다음은 준비 결과를 불변 후보/입력별 UNIQUE 예약으로 보존하고 현재 독립 승인·취소 배타적 실행 확정과 결합하는 작업이다. 그 뒤 보호된 키 영역의 실제 서명과 고정 raw bytes 방송·불명/재시작·확정/재조직·실제 비용·플랫폼/엔진 COMPLETE·독립 대사를 연결한다. 아직 이 컴포넌트를 지급 워커/공개 API에 연결하거나 Core의 RPC 허용 목록을 운영에 배포하지 않았다. 고객 수수료0.01%와 Rust 단일 스레드 코어는 변경하지 않았다.

## 작성한 테스트 코드


[BitcoinPayoutClientTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutClientTest.java)

- `coreSelectsCoinsAndExactNetChangeAndNativeCostAreVerified`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeBtcPerKilobyteEstimateIsConvertedExactlyToSatPerVbyte`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingEstimateCannotUseCustomerFeeOrAFixedFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ownRateBudgetRejectsPreparationBeforeCoinSelection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nativeAbsoluteCostAboveTheBudgetIsHeld`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `amountAndDestinationCannotBeChangedByTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `extraOutputsCannotHideAnotherRecipient`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changeMustBeAnOwnedInternalWalletOutput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualInputsMustBeOwnedSafeAndMatureNonCoinbase`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateInputCannotBeCountedTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `inputAndWitnessValuesAndTheActualFeeMustAllAgree`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `includedParentTransactionMustDescribeTheSameSpentOutput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `alreadySignedOrUnreviewedPsbtMetadataIsRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `rbfLocktimeOrVersionCannotChangeThePreparedPolicy`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aChangedTipDuringReadsCannotMixChainSnapshots`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedPsbtIsNotAcceptedAsAnOpaqueSuccess`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeIdentityAndPrivateWalletCannotReachCoinSelection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateAndTrailingRpcJsonAreNotSilentlyAccepted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `expiredFutureOrDifferentWalletFeeCannotStartPreparation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `activeDatabaseTransactionCannotStartNodeIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `inexactOrExtremeAmountsCannotAllocateUnboundedDecimalValues`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anOwnedDestinationCannotBeTreatedAsAnExternalPayout`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinPayoutCoreIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutCoreIntegrationTest.java)

- `realWatchOnlyCorePreparesAnUnsignedExactPaymentAndOwnedChange`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `allFiveSupportedActualDestinationsKeepTheirExactNetAndScript`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualMinerCostIsSeparateFromTheFixedCustomerFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aDifferentExpectedScriptCannotRedirectTheRealTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualDustRejectionCannotRoundTheCustomerPaymentUp`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unavailableActualFeeEstimateHasNoFixedFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualWrongNetworkAndPrivateKeyWalletCannotPrepare`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `preparationCredentialsCannotSignBroadcastOrExport`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realCustodyAddressIsHeldForASeparateInternalTransferPath`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 fd0526d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fd0526dfb5d60d67196ac6081a9116bee3812fb8)
- [격리 실행 증적](../reference/149-bitcoin-watch-only-payout-preparation-verification-2026-10-26.json)
