# 온체인 출력에 변하지 않는 고객 입금 ID 연결하기

완료 블록의 수신 출력에 불변 고객 소유와 안정적인 입금 ID를 연결한다. 같은 출력이 재조회돼도 ID/고객/금액을 보존하며, 출력 식별과 거래 가능 잔액 적립을 구분하는 방법을 설명한다.

## 문제와 기존 동작

080까지는 실제 등록 주소와 수신 출력을 완료 블록의 원본으로 저장했지만, 체인 출력 하나가 고객 입금 하나라는 영속 식별자는 없었다. 조회/재시작마다 임의의 새 ID를 전달하면 향후 엔진의 멱등 처리가 서로 다른 입금으로 인식할 수 있다. 같은 출력의 금액/소유자가 달라져도 단순히 충돌을 무시하면 금융 오류를 숨긴다.

## 기존 제품과 공식 자료의 접근

Bitcoin의 [거래 출력 참조](https://developer.bitcoin.org/reference/transactions.html#outpoint-the-specific-part-of-a-specific-output)는 거래 ID와 출력 번호를 사용한다. 거래 하나가 여러 출력을 가질 수 있으므로 txid 하나로 입금을 합치지 않는다. 이 서비스는 네트워크까지 포함한 `(network, txid, vout)`에 유일 제약을 둔다. 블록 해시/관찰 횟수는 재조직과 재조회로 달라질 수 있어 입금 키로 사용하지 않는다.

Stripe의 [멱등 요청](https://docs.stripe.com/api/idempotent_requests)은 같은 키의 원래 매개변수와 다른 요청을 오류로 다룬다. 이 원칙을 적용해 같은 온체인 출력의 고객/주소/자산/금액이 다르면 거부한다. Stripe의 키 보존 기간을 금융 기록의 보존 기간으로 가져오지는 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·대안과 선택 이유 |
| --- | --- | --- |
| 출력 키에 유일 제약, 최초 확정 UUID 재사용 | 재시작/중복 발견이 새 입금으로 바뀌지 않음 | 영속 조회 필요. 매번 UUID만 생성해 넘기는 방식은 중복 지급 위험 |
| 고객 소유는 불변 주소에서 조회 | 호출자가 고객을 임의로 지정하지 않음 | 주소/완료 원본을 DB에서 조인. 현재 KYC/거래 제한은 발견 사실을 버릴 이유가 아니며 후속 사용 허용 단계에서 판단 |
| 완료 블록별 별도 처리 완료 표 | 조회와 식별 사이 프로세스 종료에도 미처리 원본부터 재개 | 조회 완료와 입금 식별 완료가 잠시 다를 수 있음. 아직 워커가 자동 연결하지 않음 |
| 한 블록의 모든 입금 ID/완료/감사 같은 TX | 일부만 생성하고 나머지를 누락하지 않음 | 지갑별 짧은 직렬 확정과 DB 부하. 노드/엔진 호출은 이 TX에 없음 |
| 금액0은 경제적 입금 ID를 만들지 않음 | 금액0의 잔액 반입/고객 부채를 만들지 않음 | 원래0출력은 완료 블록 JSON에 계속 보존. 지우거나 유료 수수료 정책으로 해석하지 않음 |
| 발견 원본만 생성 | 미성숙/확정 부족/재조직 출력을 곧바로 사용하지 않음 | 최신 체인 관찰/확정/보류/원장/엔진 반입은 후속. 발견 기록이 고객 잔액이라는 오해를 막아야 함 |

## 구현과 데이터 흐름

`BitcoinDepositReceiptService.projectNext`는 현재 네트워크/관찰 지갑의 완료 블록 중 아직 식별 완료가 없는 가장 이른 원본 한 개를 처리한다. 같은 지갑의 짧은 DB TX에 자문 잠금을 적용하므로 동시 실행이 동일 원본을 서로 완료로 덮어쓰지 않는다. 다른 미처리 블록이 있으면 두 번째 실행이 다음 블록을 처리할 수 있다.

양수 수신만 `numeric`/JDBC `BigDecimal`로 읽고 주소 ID가 가리키는 불변 고객을 확인한다. 최초 insert가 출력 키를 확보하면 새로운 입금 UUID를 확정한다. 이미 있으면 기존 ID/내용을 읽어 비교하며, 다른 내용은409로 전체 롤백한다. `first_scan_id`는 최초 근거를 가리키며 재조회로 바꾸지 않는다. 첫 시도가 커밋 전에 실패한 UUID는 외부에 공개되지 않으며 재시도의 다른 UUID가 금융 중복을 만들지 않는다.


출처: [BitcoinDepositReceiptService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptService.java#L30), 현재 소스의 부분 발췌.

```java
            var pending=jdbc.queryForList("""
                SELECT s.id FROM bitcoin_block_scans s WHERE s.network=? AND s.wallet=?
                 AND NOT EXISTS(SELECT 1 FROM bitcoin_scan_receipt_projections p WHERE p.scan_id=s.id) ORDER BY s.id LIMIT 1
                """,settings.network.id,settings.wallet);
            if(pending.isEmpty())return Optional.empty();
            long scan=((Number)pending.getFirst().get("id")).longValue();
            // JSON 숫자는 PostgreSQL numeric과 JDBC BigDecimal로 읽는다. 이진 부동소수점으로 금액을 변환하지 않는다.
```

`NOT EXISTS`는 처리 완료가 없는 불변 원본만 선택한다. 다음 위치를 메모리에만 보관하지 않으므로 새 프로세스에서도 미처리 블록을 찾는다. PostgreSQL의 sequence ID에 빈 값이 있어도 LIMIT 1의 미처리 선택은 건너뛰지 않는다.

출처: [BitcoinDepositReceiptService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptService.java#L50), 현재 소스의 부분 발췌.

```java
                var inserted=jdbc.queryForList("""
                    INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id)
                     VALUES(?,?,?,?,?,?,'BTC',?,?) ON CONFLICT(network,txid,vout) DO NOTHING RETURNING id
                    """,UUID.randomUUID(),settings.network.id,output.txid(),output.vout(),output.addressId(),output.userId(),output.amount(),scan);
                var receipt=jdbc.queryForMap("SELECT id,address_id,user_id,asset,amount FROM bitcoin_deposit_receipts WHERE network=? AND txid=? AND vout=?",
                    settings.network.id,output.txid(),output.vout());
                if(!receipt.get("address_id").equals(output.addressId())||((Number)receipt.get("user_id")).longValue()!=output.userId()
                        ||!receipt.get("asset").equals("BTC")||((BigDecimal)receipt.get("amount")).compareTo(output.amount())!=0)throw conflict();
                if(!inserted.isEmpty()){
                    created++;
```

`ON CONFLICT ... DO NOTHING RETURNING id`는 기존 금융 기록을 수정하지 않는다. 삽입 결과가 비어도 원래 행을 다시 읽어 고객/주소/자산/정확한 금액이 같은지 확인한다. `BigDecimal.compareTo`는 숫자 값이 같은 서로 다른 소수 표현을 동등하게 다룬다. JS number의 이진 반올림은 사용하지 않는다.

출처: [BitcoinDepositReceiptService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptService.java#L70), 현재 소스의 부분 발췌.

```java
            jdbc.update("INSERT INTO bitcoin_scan_receipt_projections(scan_id,output_count,new_count) VALUES(?,?,?)",scan,outputs.size(),created);
            audit.record(null,"BTC_SCAN_RECEIPTS_PROJECTED","bitcoin-scan:"+scan,"SUCCESS","OUTPUTS:"+outputs.size()+":NEW:"+created);
            return Optional.of(new Projected(scan,outputs.size(),created));
        });
```

입금 원본 전부와 블록 처리 완료/감사는 같은 트랜잭션으로 확정된다. 빈 블록도 완료 표시가 있어 반복해서 처리하지 않는다. 예외가 나면 원본/완료/감사를 모두 롤백한다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L127), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS bitcoin_deposit_receipts (
 id uuid PRIMARY KEY,network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 txid varchar(64) NOT NULL CHECK(txid ~ '^[0-9a-f]{64}$'),vout integer NOT NULL CHECK(vout>=0),
 address_id uuid NOT NULL REFERENCES bitcoin_deposit_addresses(id),user_id bigint NOT NULL REFERENCES users(id),
 asset varchar(8) NOT NULL CHECK(asset='BTC'),amount numeric NOT NULL CHECK(amount>0 AND amount<=21000000 AND scale(amount)<=8),
 first_scan_id bigint NOT NULL REFERENCES bitcoin_block_scans(id),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(network,txid,vout)
);
CREATE INDEX IF NOT EXISTS bitcoin_receipt_customer ON bitcoin_deposit_receipts(user_id,recorded_at,id);
CREATE INDEX IF NOT EXISTS bitcoin_receipt_first_scan ON bitcoin_deposit_receipts(first_scan_id);
```

`numeric`에 고정 소수 자릿수를 선언해 입력을 자동 반올림하지 않고 scale<=8과 범위 제약으로 거부한다. 금액 상한2100만은 Bitcoin 금액 범위 검사이며 거래소 보유량/실제 총공급량을 뜻하지 않는다. 유일 제약은 트랜잭션이 겹쳐도 출력 하나에 입금 원본 하나만 허용한다.

[DB 스키마](../../src/main/resources/bitcoin/schema.sql)는 새 입금이 완료 블록의 같은 출력/주소/고객/금액을 참조해야 삽입을 허용한다. 모든 양수 출력에 맞는 입금이 있어야 처리 완료를 남기며 출력 수와 신규 수의 원본 대조도 검사한다. 입금과 처리 완료의 수정/삭제/전체 비우기를 금지한다. 고객/주소/완료 블록의 기존 불변 계약을 그대로 사용한다.

현재 이 원본에는 사용 가능 여부를 나타내는 상태 필드를 두지 않았다. 최신 거래 관찰의 확정 수/coinbase 여부/충돌과 미래 보류/금융 확정 원본을 별도로 연결해야 한다. 블록이 나중에 정규 체인에서 빠져도 과거 발견 사실과 입금 ID를 지우지 않는다.

## 작성한 테스트

[BitcoinDepositReceiptIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptIntegrationTest.java)는 실제 노드/소유 PG에서10개를 실행한다. 실제 블록의 발견→입금 ID를 검증하고, 별도로 불변 원본을 복제/변경해 중복·내용 충돌의 경계를 주입한다. 후자를 실제 체인 재조직 시험이라고 주장하지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| noCompletedSourceIsIdleAndEmptyBlockHasDurableProjection | 원본 없으면 대기, 최초 빈 블록은 완료1개/입금0개 |
| realDepositAuditFailureRollsBackIdentityAndCompletionThenRetrySucceeds | 실제 수신의 감사 장애 시 ID/완료 미확정, 재시도 시 정확한0.12345678BTC |
| allSourcesProduceOwnedStableFactsWithoutBalanceOrLedgerCredit | 실제105개 블록을 처리하고 소유/자산 연결, 발견 감사와 입금 수 일치, 원장/잔액0 |
| repeatedOutpointKeepsOriginalIdOwnerAmountAndFirstSource | 같은 출력이 새 원본으로 전달돼도 ID/최초 근거 보존, 신규0 |
| concurrentProjectionCommitsOneCompletionAndKeepsSameReceiptIds | 하나 남은 원본의 두 동시 실행에서 한 완료, 기존 입금 ID 유지 |
| zeroValueOutputRemainsSourceAndNeverBecomesEconomicReceipt |0출력은 원본에 남고 양수 입금/신규 생성0 |
| databaseRejectsForgedSourceBindingAndIncompleteProjection | 다른 txid로 위조한 입금/미생성 출력의 완료/신규 수 위조는 DB 거부 |
| immutableReceiptsAndProjectionMarkersRejectChangesDeletionAndTruncation | 입금/완료 원본 변경·삭제·비우기 거부 |
| callerTransactionCannotTurnIndependentProjectionIntoPartialCommit | 외부 TX 호출 거부, 기록 수 유지 |
| sameOutpointWithChangedAmountFailsWithoutReplacingFinancialIdentity | 같은 키의 금액 변조409, 원본 ID/금액/완료 수 보존 |

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/업무별 PG16에서 **신규10개+기존76개=고유86개 통과**, 최종 실패/오류/제외0개다. addresses53/observations11/scans12/receipts10은 각각 별도 PG 서버를 사용한다. 두 제품 JAR 빌드, 노드 종료/PG4개 제거, 봇0/peer0/외부 주소 격리를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 실제 MAINNET·의존성/OSV 감사·성능 보장은 검증하지 않았다.

중간86개 실행과 신규 수 DB 검사를 보강한 최종86개 실행은 합산하지 않는다. 운영 프로세스 강제 종료 대신 DB의 원본/처리 완료 선택과 실제 TX 롤백을 검증했으며, 실제 체인 재조직 자동 복구/금융 보상은 아직 검증하지 않았다.

## 후속 연결과 한계

자동 조회/식별 워커, 체인 재조직 공통 조상/재검증, 독립 거래 관찰과 확정/성숙도/위험 보류, 고객 원장·엔진 반입·Kafka 중복 수신을 이어야 한다. 현재 발견 UUID만 생성했으며 새 engine deposit 명령이나 고객 잔액 적립은 발생하지 않는다. 수신 발견을 KYC/AML 또는 법적 심사 통과로 주장하지 않는다. 엔진 단일 스레드 코어와 기존 거래 기능을 변경하지 않았다.


## 관련 커밋과 증적

- [구현 8abdd47](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8abdd47b83b7cc7d99ef6fdd176033d590a0405c)
- [격리 실행 증적](../reference/081-stable-bitcoin-deposit-receipts-verification-2026-10-25.json)
