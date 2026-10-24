# 실제 블록 활동에서 BTC 입금 출력을 발견

Bitcoin Core의 실제 블록 활동으로 지정 수신 조건의 입금 출력을 찾는다. 블록/출력 해독기를 직접 구현하지 않고, 정규 체인·부모 블록·정확한 금액·출력 중복을 검증한다. 아직 자동 수집/체크포인트나 금융 적립은 연결하지 않았다.

## 문제와 공식 자료

[078](078-durable-bitcoin-observations.md)은 이미 아는 txid의 사실을 저장한다. 고객 주소로 들어온 거래를 서버가 발견하려면 블록을 제한된 범위로 읽어야 한다. 지갑 전체 이력을 한 번에 내려받거나 프론트가 신고한 txid를 곧바로 믿지 않는다.

2026-10-25 [Core31 getdescriptoractivity](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/getdescriptoractivity/), [scanblocks](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/scanblocks/), [31.1 실제 구현](https://github.com/bitcoin/bitcoin/blob/v31.1/src/rpc/blockchain.cpp)을 확인했다. Core가 제공하는 raw(지출조건) descriptor와 실제 수신 활동을 사용한다. Java에서 블록 파서·서명·필터 인코딩을 다시 구현하지 않는다.

처음에는 필터 인덱스로 후보를 좁히는 방식을 검토했다. 소유 regtest에서 블록 무효화 뒤 getindexinfo는 synced=true/best_block_height=105, 실제 체인은104였다. 인덱스 높이의 정확한 tip 일치를 기다린 시험 준비가 종료됐다. 이것은 인덱스 높이 차이를 확인한 실험이며 입금 누락을 재현한 시험은 아니다.

구현도 확인했다. scanblocks의 필터 범위 조회 실패 경로와 completed/빈 결과의 의미를 구분해야 하므로 응답만으로 완전한 부재 증명을 구성하지 않았다. 현재 경로는 **getdescriptoractivity로 해당 블록의 실제 활동을 직접 읽는다**. Core는 블록·복구 데이터 읽기 실패를 오류로 반환한다. 실험용 필터 경로/설정/RPC 권한을 호환·폴백으로 남기지 않았다.

## 선택과 트레이드오프

| 대안/선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 필터로 후보 블록만 읽기 | 수신이 드문 긴 이력 조회의 디스크 비용 감소 | 추가 인덱스·동기화/분기 상태·완전성 조건을 검증해야 함. 현재 채택하지 않음 |
| 블록 하나의 실제 활동 직접 읽기 | 추가 인덱스 없이 명시적인 읽기/실패 계약, 현재 요구의 단순한 검증 | 수신이 없는 블록도 읽으므로 긴 과거 조회의 I/O 증가. 블록/undo 보존이 필요하며 prune 등으로 읽을 수 없으면503 |
| 한 블록·최대200개 수신 조건 | 주소가 늘어도 작업을 작은 단위로 나눌 수 있음 | 여러 주소 페이지/블록 순회의 완료 체크포인트는 후속. 현재 한 호출을 전체 지갑 동기화라고 표시하지 않음 |
| 조회 전후 정규 해시/tip 검증 | 재조직/새 블록 도중 섞인 결과를 성공으로 저장하지 않음 | 체인 변화 중503 후 다시 확인할 수 있어 수집 가용성 비용이 있음 |
| Core의 raw descriptor와 공통 BitcoinAmounts | 기존 발급 주소의0014 지출 조건과 동일한 정확한 금액 계약 | 현재 발급하는 네이티브 SegWit v0만 입력 지원. 임의 스크립트/자산 설정을 처음부터 열지 않음 |

## 구현과 데이터 흐름

BitcoinBlockDiscovery.scan(height,scripts)는 내부 함수다. 입력은0 이상 블록 높이와 중복 없는0014+20바이트 지출 조건1~200개다. 입력 목록을 복사해 노드 호출 도중 호출자가 목록을 바꿔도 요청 범위가 달라지지 않게 한다. DB 트랜잭션 안의 호출은 기존 RPC 경계에서 거부한다.

1. 기대 체인·초기 동기화 완료·관찰 전용 descriptor 지갑/스캔 상태를 확인한다.
2. 지정 높이의 정규 해시와 헤더 높이/깊이·부모 해시를 검증한다.
3. 그 블록 하나와 raw descriptor 목록으로 getdescriptoractivity를 호출한다. mempool은 포함하지 않는다.
4. 반환 활동의 블록 해시/높이를 검사한다. spend는 입금으로 만들지 않으며 receive는 요청한 지출 조건·정확한 금액·유일한 txid/vout이어야 한다.
5. 마지막 정규 해시/tip/체인 상태가 시작과 같은지 확인한 뒤 결과를 반환한다.

결과는 네트워크·블록 높이/해시/부모 해시·기준 tip/높이·수신 출력이다. 수신 출력은 txid, vout, scriptPubKey, amount만 포함한다. 블록의 raw 거래/개인키를 반환하지 않는다. 정상적으로 전체 활동을 읽고 receive가 없을 때만 빈 목록을 반환한다. 본문 읽기/블록 읽기 실패는 빈 수신으로 바꾸지 않는다.

각 RPC 본문 전체의5초 제한과 거래 활동32MiB 제한을 사용한다. 한 블록 조회는 최대7회 순차 RPC와 JSON 해독 비용이 있어 전체5초 완료를 보장하지 않는다. 공개 HTTP/스케줄러를 추가하지 않았고 아직 여러 블록을 자동 순회하지 않는다.

## 실제 코드와 문법

출처: [BitcoinBlockDiscovery.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinBlockDiscovery.java#L31), 현재 소스의 부분 발췌.

```java
        var activity=rpc.call("getdescriptoractivity",List.of(List.of(block),descriptors,false),33554432).path("activity");
        if(!activity.isArray())throw unavailable();
        var received=new ArrayList<Receive>();var seen=new HashSet<String>();
        for(var event:activity){
            if(!hash(event,"blockhash").equals(block)||integer(event,"height")!=height)throw unavailable();
            String kind=text(event,"type");
            if(kind.equals("spend"))continue;
```

Core의 표준 descriptor 문자열을 전달한다. 임의 자산/URL/노드 설정을 요청자로부터 받지 않는다. include_mempool=false이므로 이 함수는 포함된 블록만 발견하며 미확정 자동 발견은 별도 업무다.

출처: [BitcoinBlockDiscovery.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinBlockDiscovery.java#L39), 현재 소스의 부분 발췌.

```java
            String txid=hash(event,"txid"),script=text(event.path("output_spk"),"hex");
            var vout=event.path("vout");
            if(!vout.isIntegralNumber()||!vout.canConvertToInt()||vout.intValue()<0||!scripts.contains(script)
                    ||!seen.add(txid+":"+vout.intValue()))throw unavailable();
            received.add(new Receive(txid,vout.intValue(),script,BitcoinAmounts.fromNode(event.path("amount"))));
        }
        // 전체 활동을 읽은 뒤의 빈 수신만 정상 결과다. 블록 읽기 실패를 빈 목록으로 대체하지 않는다.
```

출력 번호는 정수 범위를 먼저 검사한다. 같은 txid/vout을 두 번 반환하면 실패하며 금액을 합쳐 숨기지 않는다. 실제 수신 조건의 문자열 비교와 BTC 금액 파서를 함께 사용한다. 이 값만으로 고객 귀속/적립을 허용하지 않는다.

출처: [BitcoinAmounts.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinAmounts.java#L9), 현재 소스의 부분 발췌.

```java
    static BigDecimal fromNode(tools.jackson.databind.JsonNode node){
        // 실수 토큰은 전용 ObjectReader가 BigDecimal로 만든다. DoubleNode로 바뀌면 잘못된 정밀도를 추측하지 않는다.
        if(!node.isNumber()||(!node.isIntegralNumber()&&!node.isBigDecimal()))throw unavailable();
        try{
            BigDecimal value=node.decimalValue();
            // 거대한 지수/소수 자릿수를 먼저 거부한다. 범위 검사 전에 setScale로 큰 정수를 만들지 않는다.
            if(value.signum()<0||value.compareTo(new BigDecimal("21000000"))>0||value.stripTrailingZeros().scale()>8)throw unavailable();
            return value.setScale(8,RoundingMode.UNNECESSARY);
        }catch(ArithmeticException e){throw unavailable();}
    }
```

거래 판독과 블록 발견이 같은 십진수 검증을 사용한다. BigDecimal로 읽고 범위/실질 소수 자릿수를 먼저 확인한다. Java의 Double·TypeScript Number로 금액을 보정하지 않는다. 기존077의 파서를 옮겼으며 의존성/동작을 중복 구현하지 않았다.

## 작성한 테스트

[BitcoinBlockDiscoveryTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinBlockDiscoveryTest.java)의9개는 실제 루프백 HTTP/전용 JSON 판독기를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| verifiedBlockActivityReturnsExplicitOutpoint | 블록·부모·출력·정확한 금액 반환, 결과 목록 수정 불가 |
| unreadableBlockCannotPretendEmptyActivity | 블록 읽기500을503으로 전달, 빈 결과 성공 금지 |
| missingOrMalformedActivityIsRejected | 활동 누락/비배열 거부 |
| activityMustBeSameBlockUniqueRequestedScriptAndExactAmount | 다른 블록/지출 조건·중복 출력·번호 범위 초과·정밀도·알 수 없는 활동 거부 |
| movingTipOrCanonicalBlockInvalidatesScan | 변동 tip/정규 해시·잘못된 부모 해시 거부 |
| emptyActivityAndSpendsDoNotCreateReceives | 정상 빈 활동·지출만 있으면 수신0건 |
| mutableCallerListCannotChangeValidatedScan | RPC 도중 원래 목록을 비워도 검증한 조건으로 같은 결과 |
| invalidBoundsScriptsDuplicatesAndOversizedPageNeverContactNode | 잘못된 높이/조건/중복/201개/null 거부, 노드 호출0회 |
| databaseTransactionCannotWaitForBlockScan | 외부 DB TX 호출 거부, 노드 호출0회 |

[BitcoinBlockDiscoveryIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinBlockDiscoveryIntegrationTest.java)의3개는 실제31.1 regtest 블록을 읽는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| realBlockActivityFindsKnownReceive | 실제0.12345678BTC 수신과 부모/블록·요청 지출 조건 일치 |
| unusedRegisteredStyleScriptDoesNotInventDeposit | 미사용 수신 조건은 실제 블록에서0건 |
| discoveredOutpointAgreesWithIndependentWalletTransactionRead | 발견 출력을 별도 지갑 거래 판독의 vout/지출 조건/금액과 대조 |

## 실행과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1/각 업무별 소유 PG16에서 실행했다. **신규12개+기존52개=고유64개 통과**, 최종 실패/오류/제외0개다. addresses 묶음53개와 observations 묶음11개는 서로 다른 PG 서버를 사용한다. 최종 부모 해시 검사까지 포함해 다시 검증했고 중간64개 실행은 합산하지 않는다. bootJar/operatorAdminJar와 노드 종료/두 PG 제거를 확인했다.

초기 필터 실험2회는 높이 일치 조건 때문에 준비 단계에서 종료됐고 Java 시험을 실행하지 않았다. 실제 활동 대역의 첫 빌드는 switch 표현식 내부 return으로 컴파일 실패해 예외 주입으로 수정했다. 그 실행도 시험 통과로 집계하지 않는다. 이전 XML을 사용하지 않으며 단계별 실측/실패와 최종 증적을 연결한다.

최종 실행기는 blockfilterindex 설정/조회/scanblocks 권한을 추가하지 않는다. getdescriptoractivity 권한만 필요한 기존 제한 RPC에 추가했다. 봇0·peer0·regtest·자동 지갑 전파 금지와 시험 준비의 명시적 로컬 mempool 전송을 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있으며, 의존성 추가/OSV 감사/새 성능 벤치는 없다.

## 남은 연결과 운영 조건

등록 주소를 페이지로 고정하고 블록 완료를 영속 체크포인트로 확정하는 작업, 재시작/재조직 시 재조회·출력별 고객 귀속/안정 입금 ID·확정/성숙도 정책·원장/엔진/Kafka 반영은 후속이다. 수신 발견을 거래 가능 잔액으로 표시하지 않는다. 실제 MAINNET/사업 신고·수탁 심사를 검증하지 않았다.

운영 노드는 필요한 블록과 undo 데이터를 보관해야 한다. 긴 과거 조회의 디스크/CPU·주소 페이지 수·동시 조회량과 노드 가용성을 실측해야 한다. 초기 단일 호스트의 자원 예산과 관찰 노드/서명 경계는 별도 확인한다. 외부 복제/백업은 기존 지시대로 필요 조건만 문서화한다.


## 관련 커밋과 증적

- [구현 c9716fe](https://github.com/GiHoon1123/Cex-Platform-Service/commit/c9716fe39b20d29d868c2517ae40c7e203c337bd)
- [격리 실행 증적](../reference/079-bitcoin-block-deposit-discovery-verification-2026-10-25.json)

- [공통 BTC 금액 해독 8e0595f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8e0595f)
