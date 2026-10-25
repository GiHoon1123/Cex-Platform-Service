# 고객 입금의 출력과 확정·성숙도 조건 판단하기

현재 거래 관찰의 출력/소유/금액과 확인 깊이를 대조해 온체인 준비 또는 보류 사유를 불변 기록으로 남긴다. 이 판단과 고객 자격/위험 심사/원장·엔진 금융 반입을 분리하는 이유와 일반6회/보상 거래101 깊이의 차이를 설명한다.

## 문제와 기존 동작

084는 입금 발견과 현재 거래 관찰을 자동으로 잇지만 어느 관찰이 온체인 조건을 충족하는지 결정하지 않았다. 단순히 status=CONFIRMED만 보면 첫 블록/미성숙 보상 거래도 사용 가능으로 오해할 수 있고, 다른 출력이나 오래된 관찰로 잘못 연결될 수 있다. 당시 기준을 남기지 않고 상태만 갱신하면 나중에 왜 허용/보류했는지 재현하기 어렵다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [Coinbase 확인 횟수 안내](https://help.coinbase.com/en/coinbase/getting-started/crypto-education/glossary/confirmations)와 [공개 BTC API](https://api.exchange.coinbase.com/currencies/BTC)의 네이티브 bitcoin network_confirmations는2였다. 일반 교육 문서 일부의3회 안내를 모든 상품의 현재 공통 기준으로 일반화하지 않는다. [Bitcoin.org 확인 설명](https://bitcoin.org/en/faq#why-do-i-have-to-wait-for-confirmation)은 수신자가 충분한 확인 수준을 정하며6회가 널리 쓰이는 기준임을 설명한다. 확정 수는 상품 정책이며 법적 공통 의무라고 표시하지 않는다.

이 프로젝트는 고객 자산의 이중 사용 위험과 아직 자동 금융 재조직 보상이 완성되지 않은 조건을 고려해 일반 BTC에 **6회**를 선택했다.2회보다 고객의 사용 가능 대기가 길어지는 비용이 있다. 블록 생성 간격은 확률적이므로 도착 시간을 보장하지 않으며6회도 깊은 재조직을 불가능하게 만드는 것은 아니다.

여기서 **coinbase 거래**는 Coinbase 회사가 아니라 비트코인 채굴 보상 거래다. [Core31.1 합의 상수](https://github.com/bitcoin/bitcoin/blob/v31.1/src/consensus/consensus.h)의 COINBASE_MATURITY는100이다. [합의 입력 검사](https://github.com/bitcoin/bitcoin/blob/v31.1/src/consensus/tx_verify.cpp)는 지출 블록 높이와 보상 높이 차이를 검사하고, [지갑의 GetTxBlocksToMaturity](https://github.com/bitcoin/bitcoin/blob/v31.1/src/wallet/wallet.cpp#L3060)는100+1-depth로 계산한다. 현재 블록을 포함한 지갑 깊이101을 성숙 기준으로 사용한다. 합의의100과 지갑 깊이101을 같은 단위의 숫자로 섞지 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·대안과 한계 |
| --- | --- | --- |
| 실제 출력 번호/주소/스크립트/금액이 유일하게 일치해야 판단 | 다른 출력/금액/중복 출력으로 허위 준비 처리 방지 | DB JSON 대조/집계 비용. 단순히 txid만 연결하는 방식보다 검증이 많음 |
| 일반6회/보상 거래101 깊이 | 적용 기준이 명시되고 일반 확인과 성숙도를 구분 | 고객 대기·수탁 재고 사용 제약. 모든 재조직 위험 제거/법적 의무는 아님 |
| DB 읽기 시작 시각 기준2분 최신성 | 오래된/미래 시각 근거로 새 준비 판단 금지 | 노드 장애 시 입금 진행 보류. recorded_at만 보면 오래 걸린 읽기가 방금 확인한 것처럼 보일 수 있음 |
| 정책 버전/관찰/사유를 새 원본으로 기록 | 왜/무슨 원본으로 판단했는지 재현, 과거 보류 보존 | 관찰마다 원본/감사 저장 비용. 금융 반입 완료 후 재관찰 종료 정책은 후속 |
| 현재 관찰 포인터의 짧은 공유 잠금 | 판단/감사 확정 동안 관찰 포인터 교체를 방지 | 플랫폼 DB 읽기/갱신 사이 잠깐 경합. 엔진 코어/노드 RPC를 잠그는 구조가 아님 |
| 온체인 준비와 금융 허용 분리 | 고객 KYC/위험 보류/원장·엔진 미완료를 준비 판정으로 우회하지 않음 | 후속 금융 반입에서 현재 고객 조건/동일 원본/최신성을 다시 검증해야 함 |

현재 지원은 네이티브 BTC의 regtest/mainnet 구분이며 wrapped BTC/추가 네트워크를 새로 도입한 것이 아니다. 실제 MAINNET 금융 사용은 검증하지 않았다.

## 판단 사유

정책 버전은 BTC_CHAIN_V1이다. 다음 순서로 판단하며 출력 일치 검사에서 실패하면 확정 수가 많아도 준비가 아니다.

| 사유 | 조건/의미 |
| --- | --- |
| OUTPUT_MISMATCH | 같은 vout의 출력이 정확히1개가 아니거나 주소/스크립트/정확한 금액 불일치 |
| CONFLICTED | 일치 출력의 거래가 충돌 상태 |
| WAITING_CHAIN | 아직 정규 블록 포함이 없는 거래 |
| STALE_OBSERVATION | 확인 거래지만 읽기 시작이 현재 DB 시각의 과거2분~현재 범위 밖 |
| IMMATURE_COINBASE | 보상 거래의 깊이가101 미만 |
| WAITING_CONFIRMATIONS | 일반 거래의 확인 수가6 미만 |
| CHAIN_READY | 위 조건을 통과한 **온체인 준비**이며 사용 가능 잔액 지급/고객 심사 승인이 아님 |

## 구현과 실제 코드

현재 관찰을 `FOR SHARE OF h`로 잠깐 고정한 뒤 그 거래의 모든 입금 출력을 대조한다. 입력 데이터는 불변 주소/입금/노드 관찰이며 RPC/엔진 호출은 없다. 고객별 결정 원본·현재 결정 포인터·감사는 현재 작업 완료와 같은 TX에 확정한다.


출처: [BitcoinDepositQualificationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationService.java#L21), 현재 소스의 부분 발췌.

```java
            SELECT o.*,o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes' AS fresh
             FROM bitcoin_transaction_observations o JOIN bitcoin_observation_heads h ON h.observation_id=o.id
             WHERE o.id=? FOR SHARE OF h
            """,observationId);
```

공유 잠금은 플랫폼 DB의 관찰 포인터에만 걸고 노드 대기는 이미 끝난 상태다. 호출자는 DB 트랜잭션을 소유해야 하므로 @Transactional(MANDATORY)를 사용한다. TypeScript에서 함수 호출만으로 DB 트랜잭션이 자동 보장되는 것이 아닌 것처럼 Java에서도 Spring 프록시와 호출 경계를 구분해야 한다.

출처: [BitcoinDepositQualificationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationService.java#L30), 현재 소스의 부분 발췌.

```java
            SELECT r.id,COALESCE(count(e)=1 AND bool_and(e->>'address'=a.address AND e->>'scriptPubKey'=a.script_pub_key
                AND (e->>'amount')::numeric=r.amount),false) AS matches
             FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
             JOIN bitcoin_transaction_observations o ON o.id=? AND o.network=r.network AND o.txid=r.txid AND o.wallet=a.wallet
             LEFT JOIN LATERAL jsonb_array_elements(o.outputs) e ON (e->>'vout')::integer=r.vout GROUP BY r.id ORDER BY r.id
            """,observationId);
```

같은 vout가1개이며 주소/지출 조건/금액이 모두 같아야 한다. numeric은 이진 실수로 반올림하지 않는다. 출력이 없거나 같은 번호가2개이면 false다. 고객은 불변 입금/주소 소유에서 가져오며 요청자가 선택하지 않는다.

출처: [BitcoinDepositQualificationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationService.java#L57), 현재 소스의 부분 발췌.

```java
    static Reason reason(boolean matches,BitcoinDepositReader.Status status,long confirmations,boolean coinbase,boolean fresh){
        if(status==null||(status==BitcoinDepositReader.Status.CONFIRMED?confirmations<=0:status==BitcoinDepositReader.Status.CONFLICTED?confirmations>=0:confirmations!=0))throw new IllegalArgumentException("관찰 상태와 확정 수가 다릅니다");
        if(!matches)return Reason.OUTPUT_MISMATCH;
        if(status==BitcoinDepositReader.Status.CONFLICTED)return Reason.CONFLICTED;
        if(status==BitcoinDepositReader.Status.UNCONFIRMED)return Reason.WAITING_CHAIN;
        if(!fresh)return Reason.STALE_OBSERVATION;
        // 일반6회는 프로젝트의 보수적 정책이다. 보상 거래101 깊이는 Core 지갑의 성숙도 계산과 맞춘다.
        if(coinbase&&confirmations<101)return Reason.IMMATURE_COINBASE;
        if(confirmations<6)return Reason.WAITING_CONFIRMATIONS;
        return Reason.CHAIN_READY;
    }
}
```

enum은 가능한 사유를 제한한다. 순수 판단 함수는 상태/확인 수의 모순도 거부한다. 일반 확인 수와 보상 성숙도는 별도 분기이며, CHAIN_READY는 온체인 조건만 표현한다. 회사 Coinbase와 채굴 보상 coinbase를 혼동하지 않는다.

출처: [BitcoinDepositQualificationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationService.java#L41), 현재 소스의 부분 발췌.

```java
            // 같은 관찰도 시간이 지나면 오래된 근거가 된다. 현재 이유와 원본이 같은 재시도만 합치고 상태 변화는 새 기록으로 남긴다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-chain-decision-v1:"+receipt);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id
                 WHERE h.receipt_id=? AND d.observation_id=? AND d.policy_id=? AND d.reason=?)
                """,Boolean.class,receipt,observationId,POLICY,reason.name())))continue;
            long id=jdbc.queryForObject("INSERT INTO bitcoin_deposit_chain_decisions(receipt_id,observation_id,policy_id,reason) VALUES(?,?,?,?) RETURNING id",
                Long.class,receipt,observationId,POLICY,reason.name());
```

같은 현재 관찰/정책/사유를 다시 판단하면 원본을 중복 생성하지 않는다. 시간 경과로 같은 관찰의 사유가 달라질 수 있어 관찰 ID 하나만 영구 유일 키로 고정하지 않는다. 새 사유는 새 원본으로 보존한다. 안정 순서의 입금별 짧은 자문 잠금으로 동시 판단을 직렬화한다.

출처: [BitcoinDepositObservationJobs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationJobs.java#L78), 현재 소스의 부분 발췌.

```java
            if(observed!=null)qualification.evaluate(observed.id());
            jdbc.update("""
                UPDATE bitcoin_deposit_observation_jobs SET lease_id=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+interval '30 seconds',
                 latest_observation_id=COALESCE(?,latest_observation_id),last_outcome=?,failures=CASE WHEN ? THEN 0 ELSE failures+1 END
                 WHERE network=? AND wallet=? AND txid=?
                """,observed==null?null:observed.id(),code,observed!=null,settings.network.id,settings.wallet,work.txid());
```

유효한 임대를 확인한 이후의 판단과 작업 완료/감사는 하나의 TX다. 노드 관찰 원본은 그보다 먼저 별도 TX로 확정되어 있다. 판단 감사가 실패하면 결정/현재 포인터/작업 완료가 롤백되지만 이미 확정한 노드 관찰은 보존한다.

[DB 스키마](../../src/main/resources/bitcoin/schema.sql)는 다른 거래/지갑/고객 입금의 관찰 연결과 과거 관찰을 현재로 쓰는 것을 거부한다. CHAIN_READY 삽입은 DB에서도 확인 수/성숙도/최신성/유일 출력 일치를 재검사한다. 결정 원본 변경/삭제/전체 비우기와 현재 포인터 삭제/과거 재활성을 막는다. 현재 포인터와 새 결정은 같은 TX의 원본이어야 한다.

중요한 해석: 현재 결정 행의 CHAIN_READY는 **기록 당시** 판단이다. 시간이 흐르거나 더 새 관찰/재조직이 생길 수 있다. 아직 구현하지 않은 금융 반입은 이 문자열만 믿으면 안 되고 현재 관찰 포인터/읽기 기한과 고객 자격/위험 보류를 다시 검사해야 한다. 현재 코드는 이 결정으로 원장/엔진 잔액을 늘리는 경로가 없다.

## 작성한 테스트

[BitcoinDepositQualificationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationTest.java)의 순수 판단6개:

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| ordinaryDepositWaitsUntilSixConfirmations |1/2/5회 대기,6회 온체인 준비 |
| coinbaseDepthOneHundredIsStillImmatureAndOneHundredOneIsReady | 보상1/6/99/100 깊이 미성숙,101 준비 |
| unconfirmedAndConflictedNeverBecomeChainReady | 미확정0/충돌 음수는 각각 보류 |
| staleOrFutureObservationCannotUseDeepConfirmations | 큰 확인 수라도 최신성 실패면 보류 |
| missingWrongOrDuplicatedOutputIsHeldEvenWithEnoughDepth | 출력 대조 실패는 깊이와 무관하게 보류 |
| inconsistentStatusAndConfirmationSignAreRejected | 상태와 확인 수 부호 모순/null 거부 |

[BitcoinDepositQualificationIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositQualificationIntegrationTest.java)의 실제 Core31.1/PG10개:

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| actualSixConfirmationsCreatesChainReadyWithoutFinancialBalance | 실제 일반6회 준비, 원장/잔액0 |
| actualImmatureCoinbaseRemainsHeld | 실제 보상 거래 미성숙 보류 |
| actualOrdinaryDepositBelowThresholdWaits | 새로 포함된 실제 일반 거래의 확인 부족 대기 |
| sameCurrentSourceAndReasonDoesNotDuplicateDecision | 같은 관찰/사유 재평가0건/현재 ID 보존 |
| mismatchedAddressScriptAmountIndexAndDuplicateOutputNeverBecomeReady | 실제 관찰 경계의 출력 변조5종 모두 보류 |
| staleEvidenceCreatesNewHoldAndDatabaseCannotForceReady | 오래된 읽기 근거 주입은 새 보류 원본, 직접 SQL 준비 강제도 거부 |
| databaseRejectsReadyOnImmatureCoinbaseAndForeignObservation | 미성숙 준비/다른 입금 거래의 원본 연결 DB 거부 |
| qualificationAuditFailureRollsBackDecisionAndJobButPreservesNodeObservation | 판단/작업 롤백, 확정한 관찰은 유지 |
| originalDecisionsAndCurrentHeadCannotBeChangedDeletedOrReactivated | 변경/삭제/비우기/과거 판단 재활성 거부 |
| supersededObservationIsNotCurrentAndCallerMustOwnTransaction | 교체된 관찰은 현재 판단0/감사, 외부 TX 없으면 거부 |

성숙도100/101 경계는 순수 정책 시험이며 실제101 깊이 채굴 시험으로 주장하지 않는다. 실제 노드는 일반6회와 아직 미성숙한 보상 거래를 확인했다. 출력 변조/오래된 관찰은 의도적인 경계 주입이며 실제 노드가 그 값을 반환했다고 표현하지 않는다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/묶음별 PG16에서 **기존110개+신규16개=고유126개 통과**, 실패/오류/제외0개다. 이전7개 묶음은104 높이에서 끝내고 마지막 qualification 묶음에서만 소유 노드에3블록을 더 생성해 tip107/실제 일반6회로 검증했다.8개 업무 묶음은 각각 별도 PG 서버를 사용했다. 두 제품 JAR 빌드, 소유 노드 종료/PG8개 제거, 봇0/peer0/외부 주소 격리를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있으며 새 의존성/OSV 감사/MAINNET/성능 측정은 없다.

첫 실행은 시험 코드의 두 enum에 같은 CONFLICTED 이름을 정적 반입한 충돌과 JUnit/TransactionTemplate의 Integer 타입 추론 오류로 컴파일 단계에서 종료됐다. 시험0개 실행이다. 상태 enum을 명시하고 반환 타입을 Integer로 확정한 뒤 새 노드/DB에서126개를 실행했다. 실패 증적은 .local/cycle85-proof/compile-failure에 남기며 이전 XML을 통과 결과로 쓰지 않았다.

## 남은 연결과 운영 한계

온체인 판정 다음에는 현재 고객 자격/위험 보류와 수탁/고객 원장, 안정 입금 ID의 엔진 반입·WAL 확정·Kafka 수신을 연결해야 한다. 재조직 공통 조상/진행 위치 복구·금융 영향 처리와 완료 입금의 재관찰 종료/재가동도 후속이다. 원본 보존/로컬 시험을 사업 신고·법적 의무·외부 수탁 심사 통과로 표현하지 않는다. Rust 코어의 단일 스레드 독점 상태와 공유 락 제거는 유지했다.


## 관련 커밋과 증적

- [구현 586a47e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/586a47e9dad55f9248594cba153f4ce8ca75cd45)
- [격리 실행 증적](../reference/085-bitcoin-deposit-chain-qualification-verification-2026-10-25.json)
