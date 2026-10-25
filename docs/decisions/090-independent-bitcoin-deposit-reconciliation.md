# 새 노드 읽기와 보류 원장을 입금별로 독립 대사하기

입금 한 건의 불변 원본과 보류 원장 항목을 고정한 뒤 비트코인 노드에서 거래를 새로 읽어 비교한다. 일치·누락·보류·불일치·조회 실패를 구분하고, 대사 과정에서 금융 기록을 자동 수정하지 않는다.

후속 [125 불변 시작 순번](125-ordered-reconciliation-publication.md)에서 현재 실행 선택을 시각 정렬에서 시작 순번으로 바꾸고 짧은 고객 잠금/필수 감사로 공개한다. 외부 노드 읽기는 계속 TX 밖이다. 아래 수치는 당시 범위다.

## 문제와 기존 동작

089까지 블록 발견→출력 식별→현재 체인 판단→보류 원장 반입이 동작한다. 같은 쓰기 경로가 저장한 현재 판단만 다시 읽으면 노드와 원장이 실제로 같은 입금을 표현하는지 독립적으로 확인하지 못한다. 합계가0이라는 사실도 잘못된 고객/금액의 균형 분개를 찾기에 충분하지 않다.

이번 범위는 `PENDING_RECEIPT`, 즉 입금 출력 한 건과 그 보류 반입이다. 거래소 전체 UTXO/수탁 준비금·수수료·출금·엔진 실행 잔액 대사나 고객 거래 허용 승인이 아니다. MATCHED 결과로 사용 가능 잔액을 생성하지 않는다.

## 기존 제품과 공식 자료의 접근

Modern Treasury의 [계정 대사](https://docs.moderntreasury.com/ledgers/docs/account-reconciliation)는 내부 원장과 외부 금융기관의 기록 비교를 다룬다. 이번에는 외부 관찰 대상이 비트코인 노드이며 공개된 대사 원칙을 적용한다. 제품 내부 구현을 복제했다거나 전체 준비금 증명을 구현했다고 주장하지 않는다.

PostgreSQL16 [REPEATABLE READ](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-REPEATABLE-READ)는 같은 트랜잭션의 일관된 커밋 스냅샷을 제공한다. 플랫폼의 원본/반입/원장 항목을 이 경계에서 값으로 복사하고 트랜잭션을 끝낸 뒤 [gettransaction](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/gettransaction/) 경로로 새 노드 읽기를 한다. 이미 설치된 JdbcTemplate·TransactionTemplate·Jackson과 JDK SHA-256을 사용하며 패키지를 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 한계 |
| --- | --- | --- |
| 별도 짧은 플랫폼 스냅샷 후 새 노드 읽기 | 노드 대기 중 DB 트랜잭션/연결을 유지하지 않고 쓰기 경로와 독립 비교 | 플랫폼과 체인의 전역 동시 스냅샷은 아니다. 플랫폼 캡처 시점과 노드 tip을 증거로 남긴다 |
| 고객/시스템 계정의 정확한 두 항목 비교 | 균형은 맞지만 고객/자산/금액이 틀린 원장 구분 | 향후 수수료/부분 해제 등 계약 확장 시 명시적인 새 대사 범위가 필요 |
| RUNNING 시작 기록과 최종 기록 분리 | 노드 장애·최종 감사 실패를 정상 완료로 오인하지 않음 | 프로세스 종료 시 RUNNING 원본이 남는다. 운영의 미완료 조회/재시도 연결은 후속 |
| 해당 출력과 거래 메타데이터만 증거 저장 | 같은 거래의 다른 고객 출력 복사를 줄임 | 전체 원시 RPC 응답을 보관하는 포렌식과 다름. SHA-256은 증거 변조 검출용이며 노드의 진실/서명을 증명하지 않음 |
| 불변 이력과 명시적 차이 기록 | 재실행을 새 이력으로 비교하고 최초 결과 보존 | 입금별 저장량/보존·조회 권한 관리 필요 |
| 자동 금융 정정 없음 | 재조직·조회 장애를 이유로 원본 삭제/중복 반입 방지 | 불일치의 격리·조사·승인된 후속 조치는 별도 업무 |

## 결과의 의미

| 결과 | 조건 |
| --- | --- |
| MATCHED | 새 노드에서 같은 출력/금액/수신 조건·온체인 준비를 확인하고 정확한 보류 원장 두 항목과 반입 연결이 있음 |
| INCOMPLETE | 외부 입금의 온체인 준비는 있지만 캡처 시점에 보류 반입이 없음 |
| HELD | 미반입 출력이 현재 체인 준비 전이거나 설정된 수탁 지갑 자체 사용 거래임 |
| MISMATCH | 출력이 다르거나 원장 항목/연결이 다름. 이미 반입했는데 새 노드 상태가 보류인 경우도 포함 |
| FAILED | 노드 읽기/거래 정체성을 확인하지 못했거나 실행 설정과 원본 범위가 다름 |

일반6확정·코인베이스 깊이101은085의 자체 체인 정책을 독립 비교에 적용한다. 지갑 사용 여부는086의 설정 지갑 범위이며 모든 수탁 키의 관찰 커버리지가 운영 전제다. FAILED는 일치를 의미하지 않는다. 서로 다른 체인 시점의 정상 경합도 차이로 나타날 수 있어 즉시 금전 정정을 하지 않는다.

## 구현과 관련 코드

출처: [BitcoinDepositReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationService.java#L45), 현재 소스의 부분 발췌.

```java
        // 같은 시점의 플랫폼 원본을 값으로 고정한 다음 DB 연결을 돌려준다. 노드를 기다리며 스냅샷을 붙잡지 않는다.
        Snapshot captured=snapshot.execute(t->capture(deposit));UUID run=UUID.randomUUID();String platform=json.writeValueAsString(captured);
        write.executeWithoutResult(t->{
            // 시작 공개와 금융 판단은 같은 플랫폼 고객 잠금을 따른다. 이 잠금은 아래 노드 조회 전에 해제한다.
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,captured.receipt().user());
            jdbc.update("""
                INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status)
                 VALUES(?,?,?,?,'PENDING_RECEIPT',?,'RUNNING')
                """,run,deposit,captured.receipt().network(),captured.receipt().wallet(),Timestamp.from(captured.at()));
            evidence(run,"PLATFORM",platform);
            audit.record(null,"BTC_DEPOSIT_RECONCILIATION_STARTED","bitcoin-reconciliation:"+run,"SUCCESS","RECEIPT:"+deposit);
        });
```

Snapshot은 Java record의 값 묶음이며 목록을 List.copyOf로 고정한다. TypeScript의 타입 readonly가 실행 중 변경을 자동 차단하지 않는 것과 달리 이 목록은 변경 불가능한 복사본이다. 플랫폼 캡처 TX를 마친 뒤 시작/증거를 별도 TX에서 확정하고 그 다음 노드를 읽는다.

출처: [BitcoinDepositReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationService.java#L68), 현재 소스의 부분 발췌.

```java
                    // 다른 고객의 같은 거래 출력을 불필요하게 복사하지 않는다. 거래 메타데이터와 해당 출력만 증거로 남긴다.
                    var metadata=new BitcoinDepositReader.Observation(current.network(),current.txid(),current.status(),current.confirmations(),current.coinbase(),current.walletDebit(),current.blockHash(),current.blockHeight(),current.tipHash(),current.tipHeight(),List.of());
                    node=json.writeValueAsString(new NodeEvidence("BTC_RECEIPT_AUDIT_V1",metadata,relevant));
                    status=compareNode(captured,current,relevant,platformMismatch,issues);
                }
```

출력 번호가 같은 항목만 남기고 거래 메타데이터의 outputs는 빈 목록으로 만든다. 원본 금액은 BigDecimal로 유지하며 부동소수점 변환을 하지 않는다. 이 부분 발췌는 전체 노드 응답이나 단독 실행 예제가 아니다.

출처: [BitcoinDepositReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationService.java#L128), 현재 소스의 부분 발췌.

```java
    static boolean matchesEntries(long owner,String asset,BigDecimal amount,List<Entry> entries){
        return entries.size()==2&&entries.stream().anyMatch(e->e.owner()==owner&&e.bucket().equals("PENDING")&&e.asset().equals(asset)&&e.delta().compareTo(amount)==0)
            &&entries.stream().anyMatch(e->e.owner()==0&&e.bucket().equals("CUSTODY")&&e.asset().equals(asset)&&e.delta().compareTo(amount.negate())==0);
    }
```

size==2와 두 계정의 정확한 고객/버킷/자산/금액을 함께 검사한다. BigDecimal.compareTo는0.1과0.100을 같은 수치로 비교한다. 순서가 달라도 일치할 수 있지만 누락/추가/중복 항목은 통과하지 않는다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L372), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_deposit_reconciliation_finish() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '입금 대사 원본을 삭제할 수 없습니다'; END IF;
 IF TG_OP='INSERT' THEN
  -- 직접 SQL로 시작하더라도 고객 잠금 뒤에만 시작 순번을 할당한다. 외부 노드 호출은 이 트랜잭션 밖이다.
  PERFORM u.id FROM users u JOIN bitcoin_deposit_receipts r ON r.user_id=u.id WHERE r.id=NEW.receipt_id FOR UPDATE OF u;
  IF NEW.status<>'RUNNING' OR NEW.completed_at IS NOT NULL THEN RAISE EXCEPTION '입금 대사는 실행 상태에서 시작해야 합니다'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
    WHERE r.id=NEW.receipt_id AND r.network=NEW.network AND a.wallet=NEW.wallet) OR NEW.snapshot_at>NEW.started_at THEN
   RAISE EXCEPTION '입금 대사 범위와 시점은 원본에 일치해야 합니다';
  END IF;
 ELSE
  IF NEW.completed_at<OLD.started_at OR OLD.status<>'RUNNING' OR NEW.status='RUNNING' OR NEW.id<>OLD.id OR NEW.receipt_id<>OLD.receipt_id OR NEW.network<>OLD.network OR NEW.wallet<>OLD.wallet
    OR NEW.scope<>OLD.scope OR NEW.snapshot_at<>OLD.snapshot_at OR NEW.started_at<>OLD.started_at THEN RAISE EXCEPTION '완료한 입금 대사의 범위/결과를 바꿀 수 없습니다'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.id AND kind='PLATFORM')
    OR(NEW.status<>'FAILED' AND NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.id AND kind='NODE'))
    OR(NEW.status='MATCHED' AND EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_issues WHERE run_id=NEW.id))
    OR(NEW.status<>'MATCHED' AND NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_issues WHERE run_id=NEW.id)) THEN
   RAISE EXCEPTION '입금 대사 결과에는 범위에 맞는 증거와 차이 기록이 필요합니다';
  END IF;
 END IF;
 RETURN NEW;
END $$;
```

DB는 원본의 네트워크/지갑/시점·실행→완료 전이·필수 증거와 차이 기록을 검사한다. 완료 상태는 다시 쓰지 않는다. 별도 자식 트리거는 완료 뒤 증거/차이 추가를 거부하고 수정/삭제/TRUNCATE도 차단한다. 관리자 권한 탈취까지 방어하는 외부 증거 보관소를 구현한 것은 아니다.

증거 TEXT는 직렬화된 원문 그대로 저장한다. JDK SHA-256과 PostgreSQL16의 내장 sha256(convert_to(...,'UTF8')) CHECK가 같은 바이트 해시인지 검증한다. JSONB 재직렬화로 해시 기준을 바꾸지 않는다. 새 DB 확장/패키지를 설치하지 않았다.

대사 서비스는 내부 호출용이다. 고객/운영자 공개 HTTP 경로·자동 실행·금융 해제 승인과 연결하지 않았다. 자격/권한을 검사하는 후속 진입점 없이 고객 입력으로 직접 실행할 수 없다. 새 엔진 API/코어 상태/공유 락을 추가하지 않았다.

## 작성한 테스트

아래 모든 신규 테스트는 실제 작성·실행했다. 단위 테스트는 원장 비교 규칙을, 통합 테스트는 Core31.1 regtest/별도 PG 서버와 실제 스캔→관찰→보류 반입 이후 대사를 검사한다. 노드 장애/금액 변경 등 일부 시나리오는 명시적인 spy 실패 주입이며 실제 메인넷 재조직을 발생시킨 검증은 아니다.


[BitcoinDepositReconciliationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationTest.java)

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `exactPairMatchesRegardlessOfDecimalScale` | 두 항목/정확한 고객과 금액. 소수 표현 자리 차이는 일치 |
| `balancedWrongAmountDoesNotMatch` | 합계0이어도 원본보다 큰 금액이면 불일치 |
| `wrongOwnerBucketOrAssetDoesNotMatch` | 고객/버킷/자산이 각각 다르면 거부 |
| `MissingExtraOrDuplicatePostingDoesNotMatch` | 누락/추가/중복 항목은 거부 |

[BitcoinDepositReconciliationIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationIntegrationTest.java)

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `actualRecognizedDepositMatchesAndRepeatedAuditKeepsSeparateImmutableHistory` | 실제 반입 MATCHED·새 실행 이력/해시·DB TX 밖 노드 읽기·원장 불변 |
| `eligibleUnrecognizedExternalOutputIsIncompleteInsteadOfInventingFinancialEntries` | 현재 적합 외부 출력 미반입은 INCOMPLETE, 금융 항목 생성0 |
| `internalWalletOutputIsHeldRatherThanCreditedAsExternalDeposit` | 수탁 지갑 내부 이동은 HELD |
| `differentOutputAmountIsMismatchWithoutRepairingTheLedger` | 노드 금액 변경은 MISMATCH, 원장 자동 보정0 |
| `laterChainHoldOfRecognizedAssetIsMismatchWithoutDeletingOriginalFact` | 이미 보류 반입한 입금의 현재 미확정은 MISMATCH, 원본 보존 |
| `failedNodeReadCannotBecomeMatchedOrLeakExceptionText` | 노드 예외 FAILED·고정 코드·원문 비밀 비저장·플랫폼 증거만 유지 |
| `completedAuditEvidenceAndResultsRejectMutationAndLateEvidence` | 완료 결과/증거 수정·삭제·TRUNCATE/CASCADE 및 뒤늦은 차이 추가 차단 |
| `finalAuditFailureLeavesRunningPlatformEvidenceInsteadOfFalseSuccess` | 최종 감사 실패 시 최종 TX 롤백, RUNNING/플랫폼 캡처 보존 |
| `startAuditFailureRollsBackRunAndEvidenceBeforeNodeRead` | 시작 감사 실패면 시작/증거 롤백·노드 호출0 |
| `nullMissingAndAmbientTransactionAreRejectedBeforeNodeRead` | null/없는 입금/외부 TX 거부·노드 호출0 |
| `databaseRejectsWrongSourceScopeAndEvidenceHash` | 타 네트워크 범위와 틀린 증거 해시 DB 거부 |
| `missingDuplicateAddressAndScriptMismatchNeverPassAsMatched` | 누락/중복 출력·주소/지출 조건 변경 각각 MISMATCH |
| `differentTransactionIdentityCannotSupplyEvidenceForThisReceipt` | 다른 거래 ID는 FAILED·타 거래 증거 채택 금지 |

## 실행 명령과 결과

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew testClasses --console=plain
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
```

금융 회귀 명령의 상위 호출은 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 환경 변수를 제거했다. 최초 컴파일 로그 저장 경로를 저장소 내부.local로 잘못 지정해 셸 리디렉션이 실패했으며 이때 Gradle은 실행되지 않았다. 공통 루트.local 경로로 고친 뒤 컴파일 성공했다.

macOS arm64/Temurin21/Core31.1/PG16. BTC/원장187개(신규17)와 기존 금융182개가 모두 통과했다. 공통 원장18개를 클래스/함수명으로 대조한 **369회 실행/고유351개**, 실패/오류/제외0개다. BTC13개 묶음과 금융 회귀는 각각 소유한 별도 PG 서버에서 실행했다. 소유 노드 종료/PG 제거·두 제품 JAR 빌드·시험 클래스 제외를 확인했다. 봇0·외부 통신 격리 조건은 실행기 기준을 유지했다.

기존 deprecated/unchecked/JVM 계측 경고가 있다. 실제 메인넷/운영 체인 재조직·프로세스 강제 종료·성능 수치·OSV 새 감사·전체 수탁 준비금 대사는 이번 검증 범위가 아니다.

## 남은 연결

권한 있는 업무에서 실행/완료·미완료 조회와 재시도를 연결해야 한다. 고객 자격/위험 판단·내용 결합 금융 승인, 엔진 HTTP/WAL/Kafka 반영, 재조직 보상·전체 수탁/수수료/출금 대사와 운영 경보도 후속이다. 이 기록만으로 입금 전체 업무나 금융 규제 충족이 완료됐다고 표시하지 않는다.


## 관련 커밋과 증적

- [구현 0bf9e66](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0bf9e66d940ccb33ec705c841d13d7e1a56b59a4)
- [격리 실행 증적](../reference/090-independent-bitcoin-deposit-reconciliation-verification-2026-10-25.json)
