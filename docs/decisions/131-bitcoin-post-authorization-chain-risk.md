# 실행 권한 이후 체인 위험으로 신규 금융 권한 제한

입금 실행 권한을 확정한 뒤 같은 거래 출력의 체인 근거가 무너지면 플랫폼의 신규 금융 권한을 차단한다. 기존 입금·원장·엔진 명령은 보존하고 이미 허가한 실행의 결과는 계속 반영한다. 위험은 불변 관찰에서 계산하므로 후속 판단 작업 실패나 나중의 정상 관찰로 사라지지 않는다. 공통 사이클131이다.

후속 [132 위험 운영 조회](132-operator-bitcoin-chain-risk-review.md)는 최소 목록/상세 위험 범위와 실제 반환 원본의 열람 감사를 연결했다. 아래 당시 검증 결과와 현재 발췌를 구분한다. 해제 승인은 계속 후속이다.

## 문제와 기존 동작

[126 반입 조건](126-verified-vasp-bitcoin-credit-intents.md)과 [127 실행 권한/금융 확정](127-durable-bitcoin-credit-dispatch-and-settlement.md)은 첫 실행 전에 현재6회 확정·같은 출력·정상 고객·출처·조사·대사를 확인했다. [130](130-real-engine-bitcoin-credit-wire.md)은 실제 HTTP/WAL/Kafka/금융 원장과 독립 대사까지 검증했다.

하지만 권한 확정 이후 확정 수가 줄거나 충돌이 확인돼도 이미 적립한 고객의 신규 주문 권한에는 그 사실이 연결되지 않았다. 입금 조회에 새 체인 상태가 보이는 것과 그 위험을 신규 권한에서 차단하는 것은 다르다. 기존 원장/잔액을 조용히 되돌리면 이후 체결·자산 이동의 금융 이력을 훼손하므로 단순 역분개로 해결할 수 없다.

또한 관찰 저장과 후속 체인 판단은 별도 트랜잭션이다. 판단 기록만 확인하면 관찰은 확정했지만 판단/감사 저장이 실패한 사이에 신규 권한을 허용할 수 있다. 따라서 판단 워커의 성공 여부와 독립적으로 이미 저장한 관찰 자체를 검사한다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST [Bitcoin 결제 처리 가이드](https://developer.bitcoin.org/devguide/payment_processing.html)에서 확정 수와 이중 지출/체인 분기 위험, 안전 모드의 원칙을 확인했다. 이 가이드의 오래된 결제 프로토콜·RPC 예시 전체를 현행 Core31.1 API라고 사용하지 않는다. 현재 노드 계약은 별도 실제 Core31.1 회귀와 설치 바이너리에서 검증한다.

[Kraken의 입금 상태 안내](https://support.kraken.com/articles/360000674066-cryptocurrency-deposit-statuses)는 2026-05-05 갱신된 공개 페이지에서 확인 중·적립·거래/출금 가능·수동 확인 보류를 구분한다. 이를 체인 사실·금융 적립·업무 권한을 분리하는 공개 참고로 삼는다. Kraken의 내부 재조직 통제나 승인 구조를 안다고 주장하지 않는다.

이번6회 확정과 고객 전체 신규 금융 권한 제한은 프로젝트의 보수적 수탁 위험 정책이다. 국내 법령이 일률적으로6회나 이 승인 절차를 명령한다고 표시하지 않는다. 사업 신고·규제/보존·외부 확인은 기존 로드맵과 구분한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 실행 권한의 시작 관찰 이후 불변 이력에서 위험 계산 | 과거 불확정 이력과 실행 이후 근거 상실을 구분, 재시작에도 유지 | 정상 이력이 늘면 관찰/출력 JSON 비교의 DB 읽기 비용도 늘 수 있다. 기존 네트워크/거래/순번 인덱스와 고객별 권한 인덱스를 사용하며 운영 데이터 규모의 쿼리 측정은 별도 필요 |
| 조회 뷰로 기존 원본 재사용 | 가변 위험 플래그·중복된 진실원을 만들지 않음 | 미리 복제한 위험 표보다 조회 계산 비용을 감수한다. 측정 없이 캐시/새 저장 계층을 도입하지 않는다 |
| 위험 관찰 저장과 고객 잠금을 직렬화 | 신규 권한과 관찰 공개 순서를 정의 | 플랫폼의 짧은 관찰 저장이 같은 고객의 주문 예약을 잠시 기다릴 수 있다. 노드 RPC는 잠금 밖이며 엔진 코어의 락 제거와 구분 |
| 단순 오래된 정상 관찰은 이 위험에서 제외 | 노드 지연만으로 이미 적립한 고객을 재조직 고객으로 오인하지 않음 | 별도 신선도/장애 감시가 필요하다. 신규 입금 첫 실행의 기존 신선도 검사는 유지 |
| 고객 전체 신규 금융 권한 제한 | 위험 BTC가 다른 자산으로 이동하는 추가 노출 제한 | 고객의 다른 자산 주문·새 입금 주소 공개·새 입금 권한도 공유 자격 조건에서 제한된다. 위험이 있는 고객에 한해 운영 확인 부담을 감수 |
| 나중에 체인이 정상이어도 자동 해제하지 않음 | 과거 위험 노출과 미처리 손실을 숨기지 않음 | 원인 확인/해제 승인 업무가 필요하다. 이 사이클은 자동 차단까지이며 후속 승인 구현을 이어간다 |
| 기존 금융 결과 보존 | 이미 실행한 사실과 신규 허가를 분리, 원장 추적 유지 | 기존 주문 즉시 취소·자산 회수·손실 확정·보상은 별도 업무다. 차단만으로 기존 노출을 회수했다고 주장하지 않음 |

## 위험 판정과 데이터 흐름

권한의 basis.chain.observationId를 시작점으로 삼는다. 같은 입금의 네트워크·지갑·거래 ID·출력 번호에서 그 뒤에 확정한 관찰만 비교한다.

| 이유 | 실제 관찰 조건 | 처리 |
| --- | --- | --- |
| OUTPUT_MISMATCH | 같은 출력 번호가 정확히 하나가 아니거나 주소/스크립트/금액 불일치 | 신규 금융 권한 제한 |
| CONFLICTED | 노드 관찰이 충돌 | 동일 |
| WAITING_CHAIN | 포함됐던 거래가 미확정 | 동일 |
| IMMATURE_COINBASE | 보상 거래 메타데이터인데101회 미만 | 동일. 현재 정상 자동 입금은 보상 거래를 허용하지 않으므로 예상하지 못한 근거 변화도 보수적으로 차단 |
| WAITING_CONFIRMATIONS |6회 미만 | 동일 |
| 해당 없음 | 같은 정상 출력과 충분한 확정, 시각만 오래됨 | 이번 위험으로 분류하지 않음 |

아직 실행 권한을 준 적 없는 입금의 미확정/충돌은 원래 보류 조건이다. 그 사유만으로 고객의 다른 자산을 막지 않는다. 이 정책의 기준은 첫 실행 권한이며 전송 응답만으로 과거 위험을 자동 해제하지 않는다. 이미 권한을 준 결과 불명 명령은 기존 원본으로 재확인할 수 있고, 그 금융 사실의 Kafka 수신도 계속 반영한다. 이것이 새 주문 권한을 부여하지는 않는다.

## 실제 코드·문법과 원리

출처: [credit-chain-risk-schema.sql](../../src/main/resources/bitcoin/credit-chain-risk-schema.sql#L4), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE VIEW bitcoin_credit_chain_risk_history AS
 SELECT a.receipt_id,a.user_id,a.asset,a.amount,o.id AS observation_id,risk.reason,o.recorded_at
  FROM bitcoin_credit_authorizations a JOIN bitcoin_deposit_receipts r ON r.id=a.receipt_id
  JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id
  JOIN bitcoin_transaction_observations o ON o.network=r.network AND o.wallet=addr.wallet AND o.txid=r.txid
   AND o.id>(a.basis::jsonb->'chain'->>'observationId')::bigint
  CROSS JOIN LATERAL(SELECT count(*) AS total,
    count(*) FILTER(WHERE e->>'address'=addr.address AND e->>'scriptPubKey'=addr.script_pub_key AND(e->>'amount')::numeric=r.amount) AS matching
    FROM jsonb_array_elements(o.outputs) e WHERE(e->>'vout')::integer=r.vout) output
  CROSS JOIN LATERAL(SELECT CASE
   WHEN output.total<>1 OR output.matching<>1 THEN 'OUTPUT_MISMATCH'
   WHEN o.status='CONFLICTED' THEN 'CONFLICTED'
```

뷰는 조회 SQL의 이름이며 금융 기록을 수정하는 저장 절차가 아니다. 원래 권한과 동일 수신 출력에 연결하고 시작 관찰 이후만 검사한다. LATERAL은 앞서 선택한 입금/관찰의 값을 이용해 출력 개수와 일치 개수를 계산한다. 금액은 numeric으로 정확하게 비교하며 이진 실수로 바꾸지 않는다.

출처: [credit-chain-risk-schema.sql](../../src/main/resources/bitcoin/credit-chain-risk-schema.sql#L26), 현재 소스의 부분 발췌.

```sql
 PERFORM u.id FROM users u WHERE u.id IN(
  SELECT r.user_id FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id
   WHERE r.network=NEW.network AND r.txid=NEW.txid AND addr.wallet=NEW.wallet)
  ORDER BY u.id FOR UPDATE OF u;
 RETURN NEW;
```

관찰에 포함된 거래를 수신하는 모든 고객을 ID순으로 잠근다. 아직 미커밋인 권한이 보이지 않는 경우도 고려해 권한 존재만으로 잠금 대상을 고르지 않는다. PostgreSQL 잠금은 금융 플랫폼의 저장 구간에서만 사용하며, Rust 체결 코어는 수정하지 않았다.

출처: [credit-chain-risk-schema.sql](../../src/main/resources/bitcoin/credit-chain-risk-schema.sql#L40), 현재 소스의 부분 발췌.

```sql
 IF EXISTS(SELECT 1 FROM bitcoin_credit_chain_risk_history WHERE observation_id=NEW.id) THEN
  IF NOT EXISTS(SELECT 1 FROM bitcoin_observation_heads h WHERE h.network=NEW.network AND h.txid=NEW.txid AND h.observation_id=NEW.id)
   OR NOT EXISTS(SELECT 1 FROM bitcoin_observation_wallet_debits w WHERE w.observation_id=NEW.id)
   OR NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_TRANSACTION_OBSERVED'
    AND a.target='bitcoin:'||NEW.network||':'||NEW.txid AND a.outcome='SUCCESS'
    AND a.detail='OBSERVATION:'||NEW.id::text AND a.occurred_at>=NEW.recorded_at AND length(a.source_address)>0)
   THEN RAISE EXCEPTION '실행 권한 이후 위험 관찰에는 현재 원본과 필수 감사가 필요합니다';END IF;
 END IF;
 RETURN NULL;
```

위험 관찰을 확정하려면 같은 현재 포인터·지갑 사용 근거·정확한 관찰 식별자의 필수 감사가 필요하다. DEFERRABLE INITIALLY DEFERRED 제약 트리거로 트랜잭션 종료 때 검사해 후속 INSERT 순서를 허용하면서 누락 시 전체 롤백한다.

출처: [CustomerFinancialEligibility.java](../../src/main/java/dustin/cex/domains/security/CustomerFinancialEligibility.java#L29), 현재 소스의 부분 발췌.

```java
            // 확정 수 감소/충돌/출력 불일치는 이미 실행 권한을 준 입금의 위험이다.
            // 과거 금융 결과는 취소하지 않고 신규 금융 권한만 막는다. 단순 관찰 지연은 이 근거에 포함하지 않는다.
            &&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE user_id=?)",Boolean.class,id));
    }
```

공유 자격 검사는 원래 고객/역할/제한·관계 종료·CDD/KYC 조건이 통과한 뒤 위험 뷰를 확인한다. 뷰 누락/조회 오류를 위험 없음으로 바꾸지 않는다. 호출자가 고객 잠금을 가진 MANDATORY 트랜잭션 안에서 실행한다. TypeScript의 DB 트랜잭션 콜백과 비교할 수 있으나 Spring 프록시의 전파 조건도 함께 지켜야 한다.

출처: [credit-schema.sql](../../src/main/resources/bitcoin/credit-schema.sql#L15), 현재 소스의 부분 발췌.

```sql
 -- 직접 SQL도 현재 근거를 읽기 전에 고객 잠금을 얻는다. 마지막 외래키 검사에서
 -- 뒤늦게 대기하는 것만으로는 기다리는 동안 바뀐 고객/체인 사실을 재검증할 수 없다.
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 b:=NEW.basis::jsonb;
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp()
```

Java 서비스만 잠그는 것으로 끝내지 않는다. 직접 SQL도 소스 조건을 읽기 전에 사용자 잠금을 얻는다. 외래키 검사에서 나중에 기다리는 것만으로는 대기 중 바뀐 제한을 앞선 판단에 다시 적용할 수 없다. 의도와 실행 권한이 같은 바인딩 함수를 재사용한다.

## 작성한 테스트와 기대 결과

파일: [BitcoinCreditChainRiskIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditChainRiskIntegrationTest.java). 합성 공급사/노드 응답과 실제 PG 원장·권한·관찰·잠금을 사용한다. 실제 체인 재조직을 이21개에서 발생시켰다고 표현하지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| unconfirmedAfterActualCreditBlocksNewOrdersWithoutReversingTheLedger | 실제 금융 수신 후 미확정 관찰, 신규 주문403·적립/원장 보존 |
| lostConfirmationsAfterAuthorizationAreNotOnlyAPendingDepositStatus | 권한 후5회 확정, 아직 결과 전이어도 신규 권한 차단 |
| conflictingTransactionBlocksTheAlreadyAuthorizedCustomer | 결과 불명 권한 뒤 충돌, 차단 |
| outputMismatchIsAnExecutionRiskWithoutChangingTheOriginalAmount | 출력 금액 불일치, 원래 권한 금액 보존·차단 |
| immatureCoinbaseMetadataCannotPreserveOldExecutionPermission | 예상치 못한 미성숙 보상 메타데이터, 차단 |
| aStaleObservationIsNotEvidenceOfAReorganization | 오래된 정상 관찰만으로 위험 없음 |
| aFreshConfirmedObservationDoesNotCreateAnIncident | 새 정상 관찰, 위험 없음 |
| anUnexecutedDepositCannotFreezeTheCustomersOtherAssets | 권한 전 미확정, 해당 반입만 보류·고객 권한 유지 |
| anIntentWithoutExecutionAuthorityDoesNotFreezeTheCustomer | 의도만 있고 권한 없음, 기존 자격 유지·첫 실행은 거부 |
| olderUnconfirmedHistoryDoesNotInvalidateANewVerifiedAuthorization | 과거 미확정 후 새 정상 시작점으로 허가, 과거를 새 위험으로 오인하지 않음 |
| laterChainRecoveryDoesNotSilentlyEraseTheRiskHistory | 후속 정상 판단에도 위험 유지 |
| alreadyExecutedFinancialFactStillCompletesAfterTheRiskWasDiscovered | 위험 이후 이미 허가한 결과 수신, 금융 기록은 완성·신규 권한 제한 유지 |
| alreadyAuthorizedUnknownRequestCanBeReconfirmedWithoutNewAuthority | 같은 원본 불명 재확인, 권한/의도 하나·새 허가 없음 |
| missingRequiredObservationAuditRollsBackTheNewRiskFact | 감사 조용한 누락, 관찰/현재 포인터 전체 롤백; 복원 후 위험 확정 |
| committedObservationBlocksNewPermissionBeforeQualificationRuns | 후속 판단 없이 확정 관찰만으로 즉시 차단 |
| qualificationAuditFailureCannotUndoTheAlreadyCommittedRiskObservation | 판단 감사 실패/판단 롤백에도 확정한 관찰 위험 유지 |
| aRiskFactCannotBeInsertedWithoutItsPublishedHeadAndAudit | 직접 위험 관찰 INSERT만으로 확정 불가 |
| aMissingRiskViewCannotBeInterpretedAsNoRisk | 뷰 누락을 안전으로 간주하지 않음 |
| userLockSerializesRiskPublicationWithFinancialPermission | 다른 연결이 고객 잠금 중이면 관찰 공개가 대기/시간 초과, 해제 후 정상 공개 |
| theActualObservationQueueRefreshesCreditedDepositsAndPublishesTheRisk | 실제 영속 재관찰 작업이 적립한 입금도 계속 확인하고 위험 공개 |
| directSqlTakesTheUserLockBeforeValidatingCurrentFinancialFacts | 실제 다른 INSERT의 잠금 대기를 확인, 고객 제한 커밋 후 최신 조건으로 의도 거부 |

마지막 시험은 pg_stat_clear_snapshot으로 통계 관찰을 새로 읽고 실제 pg_stat_activity의 잠금 대기까지 확인한다. Future 실패만으로 성공했다고 보지 않고 위험/권한·금융 원본 개수와 최종 제한도 검증한다.

## 실행 명령과 결과

환경: macOS·JDK21·소유 PostgreSQL16·실제 Core31.1 regtest와 소유 Kafka3.9.1 연결 회귀. 순서대로 아래 실행기를 사용했고 각 그룹의 외부 주소/봇0/작업 비활성화와 자원 정리를 확인한다.

```sh
python3 scripts/verify_bitcoin_credit_chain_risk.py
python3 scripts/verify_bitcoin_credit_intents.py
python3 scripts/verify_bitcoin_credit_execution.py
python3 scripts/verify_bitcoin_credit_processing.py
python3 scripts/verify_engine_inbox.py
CEX_BITCOIN_CORE_HOME=../.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_bitcoin_credit_wire.py
```

신규21·기존 의도35·전달37·자동 처리29·금융182·Core/주변 계약231·실제 엔진 연결3, 합계 **고유520개/538회** 통과다. 실패·오류·제외0이며 OrderLedgerTest18개가 금융/노드 그룹에 중복 선택된 것을 고유 수에서 제거했다. /tmp/run-cycle131-regressions.py가 위 실행과 원시 결과 보존/중복 집계를 수행했다. 제품 시험을 저장소마다 기능 단위 실행기로 재현할 수 있다.

전체 시험 컴파일과 각 그룹의 bootJar/operatorAdminJar 또는 해당 기존 실행기의 빌드를 수행했다. 구체 명령·결과/그룹·원시 로그/각 JUnit XML은 .local/cycle131-proof에 보존한다. 새 schema가 두 제품 JAR의 현재 소스와 정확하게 일치하며 시험 클래스는 포함되지 않는 것도 확인했다. Python AST·문서 실제 발췌/상대 링크·git diff --check는 별도로 확인한다.

초기 개발 중 패키지 내부 메서드를 직접 호출한 시험 컴파일 오류를 기존 시험 경계/sha 도우미로 수정했다. 첫20개 실행의 마지막 집계가 옛17 상수를 사용해 실패한 것도 수정했다. 직접 SQL 집중 시험은 통계 스냅샷 갱신 전 대기 관찰에서 실패해 pg_stat_clear_snapshot을 추가했다. 이러한 중간 결과와 판단 기록 기반의 초기17개 검증은 최종 합계에 더하지 않고 최종 현재 소스로 전체를 다시 실행했다.

기존 Java deprecation/unchecked/JDK class sharing 및 Rust unused/dead_code·SQLx 향후 호환 경고는 남는다. 과거 순수 계산 성능 수치를 이 변경의 처리량으로 재사용하지 않는다.

## 한계와 후속 작업

차단은 위험 관찰이 플랫폼 DB에 확정된 뒤의 신규 권한에 적용한다. 외부 체인의 변화부터 다음 관찰까지는 탐지 지연이 있으며 관찰 자체를 저장하지 못한 장애까지 이 뷰가 이미 안다고 주장하지 않는다. 노드/감사 장애의 경보·운영 대응·관찰 지연 예산은 별도 확인 대상이다.

기존 코어에 들어간 주문을 전부 회수하거나 이미 발생한 체결을 취소하지 않는다. 취소/노출 정리·손실 확정/정정은 승인된 별도 업무가 필요하다. 이번에는 자동 원장 차감이나 과거 이벤트 삭제를 추가하지 않았다.

현재 위험은 이후 정상 관찰만으로 해제되지 않는다. 다음 단계에서 운영자의 원인/전체 범위 조회, 최신 복구 근거와 독립 승인에 결합한 제한 해제를 구현한다. 비VASP/소액/개인지갑·미지원 출처의 반환·출금·서명·신고/운영 증적·전체 검토·프론트·마지막 ETH도 남는다. 외부 복제/백업은 기존 지시대로 문서 조건만 유지한다.

## 작성한 테스트 코드


[BitcoinCreditChainRiskIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditChainRiskIntegrationTest.java)

- `unconfirmedAfterActualCreditBlocksNewOrdersWithoutReversingTheLedger`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostConfirmationsAfterAuthorizationAreNotOnlyAPendingDepositStatus`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `conflictingTransactionBlocksTheAlreadyAuthorizedCustomer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `outputMismatchIsAnExecutionRiskWithoutChangingTheOriginalAmount`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immatureCoinbaseMetadataCannotPreserveOldExecutionPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aStaleObservationIsNotEvidenceOfAReorganization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aFreshConfirmedObservationDoesNotCreateAnIncident`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnexecutedDepositCannotFreezeTheCustomersOtherAssets`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anIntentWithoutExecutionAuthorityDoesNotFreezeTheCustomer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `olderUnconfirmedHistoryDoesNotInvalidateANewVerifiedAuthorization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterChainRecoveryDoesNotSilentlyEraseTheRiskHistory`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `alreadyExecutedFinancialFactStillCompletesAfterTheRiskWasDiscovered`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `alreadyAuthorizedUnknownRequestCanBeReconfirmedWithoutNewAuthority`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingRequiredObservationAuditRollsBackTheNewRiskFact`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `committedObservationBlocksNewPermissionBeforeQualificationRuns`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `qualificationAuditFailureCannotUndoTheAlreadyCommittedRiskObservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aRiskFactCannotBeInsertedWithoutItsPublishedHeadAndAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMissingRiskViewCannotBeInterpretedAsNoRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `userLockSerializesRiskPublicationWithFinancialPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theActualObservationQueueRefreshesCreditedDepositsAndPublishesTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directSqlTakesTheUserLockBeforeValidatingCurrentFinancialFacts`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 81baea6](https://github.com/GiHoon1123/Cex-Platform-Service/commit/81baea63d1cefd00e22d4c92da2a04cec9f59872)
- [격리 실행 증적](../reference/131-bitcoin-post-authorization-chain-risk-verification-2026-10-26.json)

### 현재 위험과 불변 원시 이력의 후속 분리

134에서는 이 기록의 원시 관찰 판정을 bitcoin_credit_chain_risk_history로 보존하고 bitcoin_credit_chain_risks는 독립 승인된 구간을 제외한 현재 제한만 계산한다. 관찰 저장의 필수 감사는 원시 이력에 계속 연결하므로 해제 뒤 새 위험을 놓치지 않는다. 이 문서의 당시21개 검증 수치는 그대로 당시 결과이며 후속 해제 구현/검증은 [134](134-independent-bitcoin-chain-risk-clearance.md) 기록을 따른다.
