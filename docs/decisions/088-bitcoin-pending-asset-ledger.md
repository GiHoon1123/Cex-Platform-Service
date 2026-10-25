# 검증된 고객 입금을 보류 자산 원장에 한 번만 반입하기

현재 온체인 조건과 관찰 지갑의 사용 근거를 통과한 입금을 고객 보류 자산 원장에 한 번만 기록한다. 고객의 자산 사실 보존과 사용 가능 잔액/엔진 반입을 분리하고, 입금 원본·양쪽 원장 항목·감사의 원자성과 DB 우회 검사를 설명한다.

## 문제와 기존 동작

086까지는 발견 원본/지갑 사용/온체인 준비를 보존했지만 고객 자산의 보류 원장 기록은 없었다. 확인된 수신을 바로 AVAILABLE로 넣으면 고객 자격·위험 심사·엔진 영속 반입이 끝나기 전에 주문에 쓸 수 있다. 반대로 정지/미확인 고객이라는 이유로 실제 고객 귀속 자산을 기록하지 않으면 보유 사실과 제한 상태가 섞인다.

원장에 균형 항목만 남기는 것도 충분하지 않다. 잘못된 금액/다른 입금의 근거로 양쪽 합계를0으로 만들 수 있으므로 정확한 입금 ID/고객/자산/금액과 결합해야 한다. 확장 전에는 [087](087-block-ledger-truncate.md)에서 기존 확정 원장 전체 비우기 우회를 먼저 막았다.

## 기존 제품과 공식 자료의 접근

[Modern Treasury의 잔액 구분](https://docs.moderntreasury.com/ledgers/docs/transaction-status-and-balances)과 [잔액 정의](https://docs.moderntreasury.com/platform/reference/ledger-balances-object)는 대기/확정/사용 가능의 의미를 나눈다. 이 프로젝트의 PENDING은 **고객의 보류 계정 이름**이며 그 제품의 pending_balance 계산식이나 변경 가능한 pending transaction과 같은 개념으로 취급하지 않는다.

[TigerBeetle의 단계별 이체](https://docs.tigerbeetle.com/coding/two-phase-transfers/)는 대기 기록을 수정해 완료하지 않고 별도 불변 기록으로 결과를 연결한다. 여기서도 보류 반입은 확정 원장 원본이며, 나중의 해제/역거래는 새 업무/항목으로 남겨야 한다. 별도 원장 서버를 추가하지 않고 이미 사용 중인 Spring JDBC/JPA/PostgreSQL의 트랜잭션·행 잠금·지연 제약을 활용한다.

## 계정과 기록 의미

| 계정 | 이 반입에서의 변화 | 의미 |
| --- | ---: | --- |
| 고객 PENDING/BTC | +수신액 | 자산 사실은 인정하지만 거래에 사용하지 않는 보류 |
| 시스템0 CUSTODY/BTC | −수신액 | 기존 부호 규칙에서 고객 귀속과 균형을 이루는 수탁 자산 상대 항목 |
| 고객 AVAILABLE/LOCKED |0 | 사용 가능/주문 예약에 공개하지 않음 |

PENDING은 원장 기록이 미완성이라는 뜻이 아니다. 양쪽 항목과 업무 원본은 이미 원자적으로 확정되고 수정/삭제할 수 없다. 거래 가능 잔액을 위한 user_balances와 Rust 실행 잔액에는 이 단계에서 쓰지 않는다. OPENING은 최초 반입 근거이므로 새 온체인 입금의 반대 계정으로 재사용하지 않는다.

CUSTODY의 이번 항목은 검증된 입금 유입을 기록한다. 이를 현재 전체 UTXO/실제 지급 가능 수탁 잔액 증명으로 표시하면 안 된다. 출금·스윕·네트워크 비용/회사 부담과 실제 수탁 대사는 후속이며 법인 전체의 재무회계 계정과목/결산 구현이 아니다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·제약과 대안 |
| --- | --- | --- |
| 보류와 사용 가능 별도 계정 | 제한 고객의 자산 사실 보존과 지출 허용 구분 | 후속 해제/엔진 반입/역거래 단계가 필요. 지금 AVAILABLE로 넣는 방식은 사용 허용을 앞당김 |
| 불변 입금 ID당 업무 키 하나 | 재시도/동시 실행에 금융 기록 한 번 | DB 조회와 고객 행 잠금. 매 시도 UUID를 새 업무로 쓰지 않음 |
| 고객 행→현재 판단/관찰 잠금 | 같은 소유자의 반입 직렬화와 원본 교체 창 차단 | 플랫폼 고객별 경합. Rust 코어의 공유 락/DB 대기와 무관 |
| 현재 CHAIN_READY와 wallet_debit=false 근거 모두 요구 | 내부 이동/근거 없는 과거 관찰을 외부 유입으로 추정하지 않음 | 지갑 전체 수신/거스름돈 관찰 범위가 운영 전제. false 자체가 송금자 신원/AML 증거는 아님 |
| 거래/원장 항목/입금 근거의 상호 지연 제약 | 균형만 맞춘 금액 변조와 근거 없는 보류 계정 생성도 커밋 거부 | PostgreSQL 커밋 시 추가 조회/검사 비용. 관리자 DDL 권한은 별도 통제 필요 |
| 원본·현재 판단·양쪽 항목·감사 같은 TX | 일부만 저장한 반입/감사 없는 성공 방지 | 필수 감사 장애도 반입을 지연시킴. 실제 수신 원본은 이미 별도로 보존됨 |

## 구현과 흐름

`recognize(UUID)`는 고객/금액/자산을 요청에서 받지 않고 불변 입금에서 읽는다. 소유자 행을 먼저 잠그고 이미 반입된 ID면 기존 업무 키를 반환한다. 신규이면 현재 판단과 현재 관찰이 같은 원본인지, 읽기 시작이 현재~과거2분 범위인지, 해당 관찰의 wallet_debit=false 근거가 있는지 다시 검사한다.

`recognizeNext()`는 같은 조건을 만족하는 미반입 원본 한 개를 고르며 내부 지갑 사용/근거 누락/이미 완료된 원본은 후보에서 제외한다. 선택 후의 원본은 같은 금융 TX에서 다시 검사한다. 서비스는 독립 트랜잭션을 요구하며 노드 RPC나 엔진 HTTP는 호출하지 않는다.


출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L62), 현재 소스의 부분 발췌.

```java
        users.lockById(owner).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
        var existing=jdbc.queryForList("SELECT operation_key FROM bitcoin_pending_deposits WHERE receipt_id=?",deposit);
        if(!existing.isEmpty())return new Recognized(deposit,(String)existing.getFirst().get("operation_key"),false);
        if(!receipt.get("network").equals(settings.network.id)||!receipt.get("wallet").equals(settings.wallet))throw held();
        var sources=jdbc.queryForList("""
```

현재 고객의 거래 허용과 자산 보류 기록은 별개다. 정지된 소유자도 실제 입금을 보류로 남길 수 있다. 같은 입금의 재시도는 이미 확정된 원본을 반환하며 새 항목을 만들지 않는다. 기존 완료 응답이 현재 거래 허용을 새로 승인한다는 의미는 아니다.

출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L67), 현재 소스의 부분 발췌.

```java
            SELECT d.id FROM bitcoin_deposit_chain_heads qh JOIN bitcoin_deposit_chain_decisions d ON d.id=qh.decision_id
             JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
             JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
             WHERE qh.receipt_id=? AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1'
              AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
             FOR SHARE OF qh,oh
            """,deposit);
```

과거 CHAIN_READY 문자열만 신뢰하지 않고 현재 판단/관찰 포인터·시각·지갑 사용 근거를 함께 검사한다. FOR SHARE는 플랫폼 DB의 짧은 확정 구간을 보호한다. 근거가 없으면 false 기본값이나 다른 DB로 우회하지 않는다.

출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L80), 현재 소스의 부분 발췌.

```java
        ledger.record(key,"BTC_DEPOSIT_PENDING",List.of(p(owner,"PENDING",asset,amount),p(0,"CUSTODY",asset,amount.negate())));
        jdbc.update("INSERT INTO bitcoin_pending_deposits(receipt_id,decision_id,operation_key) VALUES(?,?,?)",deposit,decision,key);
        valuations.recordRecognized(deposit,priceId);
        audit.record(null,"BTC_PENDING_DEPOSIT_RECOGNIZED","bitcoin-deposit:"+deposit,"SUCCESS","DECISION:"+decision+":OP:"+key);
        return new Recognized(deposit,key,true);
```

[110의 평가 결합](110-pending-deposit-price-capture.md) 이후 현재 코드는 같은 트랜잭션에 평가·미평가 사실도 저장한다. `priceId`는 원장 저장 전 확인한 당시 가격 위치다. BigDecimal.negate는 정확한 반대 항목을 만든다. 같은 금융 트랜잭션에서 양쪽 원장, 근거 연결, 감사를 기록한다. 고객 AVAILABLE과 엔진 입력은 수정하지 않는다. TypeScript number의 부동소수점으로 두 항목의 합계를 보정하는 처리가 아니다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L318), 현재 소스의 부분 발췌.

```sql
 IF entries<>2 OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=op AND owner_id=source.user_id AND bucket='PENDING' AND asset=source.asset AND delta=source.amount)
   OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=op AND owner_id=0 AND bucket='CUSTODY' AND asset=source.asset AND delta=-source.amount) THEN
  RAISE EXCEPTION '보류 원장의 양쪽 항목은 같은 입금의 정확한 소유자/자산/금액이어야 합니다';
 END IF;
```

합계0뿐 아니라 항목 수2개, 고객 PENDING의 정확한 금액, 시스템 CUSTODY의 반대 금액을 검사한다. 일반 업무 키로 새 계정을 만들거나 BTC_DEPOSIT_PENDING 원장만 만들고 입금 근거를 빠뜨리면 별도 지연 트리거가 거부한다.

## 스키마와 기존 금융 기록 보존

[LedgerService.Posting](../../src/main/java/dustin/cex/domains/ledger/LedgerService.java)은 고객 PENDING/시스템 CUSTODY만 추가 허용한다. 고객 CUSTODY/시스템 PENDING은 계속 잘못된 계정이다. [원장 스키마](../../src/main/resources/ledger/schema.sql)는 기존 행을 새 이름의 계정 제약으로 먼저 검증한 뒤 이전 익명 제약을 제거한다. 금융 행/순번/금액/기존 업무 키는 변환하거나 삭제하지 않는다. 제약이 이미 있으면 시작할 때 전체 표를 다시 검증하지 않는다.

PostgreSQL16 [제약 추가/검증](https://www.postgresql.org/docs/16/sql-altertable.html#SQL-ALTERTABLE-DESC-ADD-TABLE-CONSTRAINT)은 검증/잠금 비용이 있으므로 큰 운영 원장에서는 배포 시점/관리 역할/잠금 예산을 따로 검토해야 한다. 이 변경은 운영 DB에 적용/배포하지 않았고 소유 시험 DB에서 기존 행 보존을 검증했다.

[Bitcoin 스키마](../../src/main/resources/bitcoin/schema.sql)는 bitcoin_pending_deposits와 현재 판단의 입금 ID를 외래 키로 연결한다. 원장 거래와 입금 근거 양쪽의 지연 트리거가 서로 빠진 경우를 거부한다. 신규 PENDING/CUSTODY 항목은 현재 BTC_DEPOSIT_PENDING 업무에서만 허용한다. 후속 해제/역거래는 해당 금융 근거 계약을 별도로 추가해야 하며 임의 일반 업무로 우회하지 않는다. 초기화 순서는 원장→Bitcoin 금융 참조가 준비되도록 명시했다.

명시적인 opening 원장 없이는 실제 입금 관찰만으로 최초 원장을 추측 생성하지 않는다. 새 초기 환경은 기존 정책대로 검증된 명시적 반입을 먼저 해야 한다.

## 작성한 테스트

[BitcoinPendingDepositIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositIntegrationTest.java)는 실제 노드/독립 PG에서14개를 순서 있는 시나리오로 실행한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| explicitOpeningIsRequiredAndNeverInferredFromObservedFunds | 온체인 준비여도 최초 원장 없으면 반입0, 명시적 빈 최초 반입 후 진행 |
| actualDepositCreatesBalancedPendingForDisabledCustomerWithoutAvailableOrEngineCredit | 정지 고객도 실제0.12345678 보류/+수탁 반대 항목, 합계0/가용 잔액0/엔진 호출0 |
| sameDepositRetryPreservesOperationAndNeverCreatesSecondPosting | 재시도 created=false/같은 키/항목 수 보존 |
| simultaneousRecognitionOfAnotherRealDepositHasExactlyOneCreator | 실제 다른 입금 동시 처리 중 하나만 생성, 같은 키/항목2개 |
| confirmedInternalWalletTransferCannotCreateExternalDepositLedger | 내부 거래도 확인 깊이는 준비지만 지갑 사용true이면 반입409/금융 기록0 |
| observationWithoutWalletDebitEvidenceIsNeverAssumedExternal | 준비 관찰이라도 지갑 사용 근거가 없으면409 |
| staleObservationCannotCreatePendingMoney | 오래된 관찰은 보류/새 금융 기록0 |
| mandatoryAuditFailureRollsBackReceiptAndBothLedgerEntries | 필수 감사 장애에 근거·원장 거래·항목 모두 롤백 |
| databaseRejectsBalancedButWrongAmountAgainstCurrentDepositSource | 양쪽 합계가0이어도 원래 입금보다 큰 금액은 커밋 거부 |
| databaseRejectsPendingOperationWithoutReceiptAndGenericBucketBypass | 근거 없는 보류 원장/일반 업무의 새 계정 우회 모두 거부 |
| committedPendingSourceCannotBeEditedDeletedOrTruncated | 확정 반입의 변경·삭제·비우기 거부 |
| previouslyRecognizedFactIsIdempotentAfterLaterChainHold | 이후 체인 보류가 있어도 과거 완료 사실을 중복 작성/삭제하지 않음, 가용0 |
| invalidIdentifierAndSurroundingTransactionCannotWritePartialMoney | null/외부 TX 거부, 기록 수 유지 |
| nextRecognitionSkipsInternalAndCompletedSourcesAndFindsEligibleExternalDeposit | 내부/완료 후보 제외, 적합 외부 한 건 생성 후 대기/재시도 무증가 |

[OrderLedgerTest](../../src/test/java/dustin/cex/domains/ledger/OrderLedgerTest.java)에2개를 추가했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| accountConstraintExpansionPreservesCommittedRows | 이전 제약의 소유 스키마를 새 제약으로 바꿔도 확정 행/대사 그대로 |
| customerCustodyAndSystemPendingAreInvalidAccounts | 잘못된 소유자/계정 조합 거부, 새 적합 조합 허용 |

오래된/근거 없는 관찰과 이후 미확정은 경계 주입 시험이다. 실제 체인 재조직의 자동 금융 역거래를 검증한 것은 아니다. 실제 노드는 충분히 확인된 외부 수신과 내부 수탁 이동을 각각 제공했다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
```

두 번째 명령은 상위 Python 호출에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 값을 제거한 환경으로 실행했다. macOS arm64/Temurin21/Core31.1/PG16에서 BTC 업무/원장 묶음163개와 기존 금융 회귀182개가 모두 통과했다. 공통 OrderLedgerTest18개를 실제 클래스/함수명으로 대조해 **총345회 실행/고유327개**, 실패/오류/제외0개다. 신규 시험은 보류14개+계정2개=16개다.163개에는 기존 원장16개도 별도 묶음으로 포함되므로 전부 신규라고 집계하지 않는다.

기존 묶음은 각 단계의104/107 높이 계약을 유지하고 pending-ledger에서만3블록을 더 생성해 tip110/내부7회도 검증했다. BTC10개 업무 묶음과 금융 회귀는 각각 새 PG 서버를 썼다. 소유 노드 종료/각 PG 제거, 두 제품 JAR의 새 스키마/시험 제외, 봇0/peer0/외부 주소 격리를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 실제 엔진 연결 시험은087의 결과이며 이번 기능이 엔진 입금을 수행한 시험으로 합산하지 않는다. MAINNET/새 성능 벤치/OSV 감사는 실행하지 않았다.

## 남은 연결과 운영 한계

이번 구현은 내부 recognize/recognizeNext까지다. 자동 보류 반입과 독립 입금 대사, 현재 고객 자격/위험 심사·내용 결합 금융 승인, 엔진 HTTP 반입/WAL 확정/Kafka 수신, 재조직 역거래/보상과 관찰 종료/재가동을 이어야 한다. 과거 보류 반입이 있는 상태에서 체인이 바뀌면 원본을 지우지 않고 별도 복구/정정 업무가 필요하다. 아직 사용 가능 잔액에 공개하지 않는다.

수탁 전체 키/거스름돈 관찰, 네트워크 비용/실제 자산 대사, DB 앱 역할/관리자 DDL 분리와 외부 복제/백업은 운영 조건이다. 로컬 원본/제약 검증을 외부 사업 신고·심사 또는 법인 전체 회계 충족으로 주장하지 않는다. Rust 코어의 단일 스레드 독점 상태/락 제거를 변경하지 않았다.


## 관련 커밋과 증적

- [구현 fcc2e60](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fcc2e60e8208aa885656cebe750be4b8921a7237)
- [격리 실행 증적](../reference/088-bitcoin-pending-asset-ledger-verification-2026-10-25.json)

089 현행 연결: [독립 보류 반입 워커](089-automatic-bitcoin-pending-ledger.md)를 추가했다. recognizeNext는 잠긴 고객을 건너뛰고 다른 고객을 선택한다.088의 당시 수동 호출/시험 기록은 유지하며 사용 가능 잔액/엔진 금융 반입은 여전히 후속이다.
