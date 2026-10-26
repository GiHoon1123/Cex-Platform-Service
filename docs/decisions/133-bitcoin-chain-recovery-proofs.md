# 체인 복구 근거를 실제 저널·원장·독립 노드 대사에 결합

실행 이후 체인 위험이 생긴 실제 적립 입금에 대해, 현재 체인과 엔진 확정 저널·플랫폼 원장이 일치하는지 검증한다. 외부 조회 동안 고객 잠금을 보유하지 않고 전후 위험·금융 지문을 대조한다. 이 근거는 제한 해제 승인이 아니며 정상 근거만으로 거래 자격을 되살리지 않는다. 공통 사이클133이다.

## 문제와 기존 동작

[131](131-bitcoin-post-authorization-chain-risk.md)은 실행 권한 뒤 생긴 체인 위험을 신규 금융 권한에 연결했고 [132](132-operator-bitcoin-chain-risk-review.md)는 운영자가 그 위험 범위를 읽게 했다. 체인이 다시 정상으로 관찰되었다는 사실만으로는 원래 적립이 실제 엔진 저널에 존재하고 플랫폼 원장과 일치하는지 증명할 수 없다. 이전 성공 대사를 재사용하거나 조회 도중 달라진 금융 상태에 승인 근거를 붙여서도 안 된다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST에 확인한 [Bitcoin 개발자 결제 처리 가이드](https://developer.bitcoin.org/devguide/payment_processing.html)는 확인 수와 재조직 위험을 구분한다. 오래된 개발자 가이드의 안전 원리를 참고하며 특정 확인 수를 최종성 보장이나 국내 법적 의무로 해석하지 않는다. 기존 프로젝트의 일반 입금6확인·별도 대사 정책을 그대로 사용했다.

[PostgreSQL16 READ COMMITTED](https://www.postgresql.org/docs/16/transaction-iso.html)는 문장별 스냅샷이다. 외부 조회를 긴 DB 트랜잭션으로 감싸지 않고, 짧은 시작·완료 트랜잭션과 같은 사용자 잠금 계약, 불변 금융 원본의 전후 지문을 결합했다. 외부 조회 동안 잠금이 유지된다는 가정은 하지 않는다.

기존 ReconciliationService의 자체 WAL 읽기 전용 HTTP 대사와 BitcoinDepositReconciliationService의 독립 노드 대사를 사용했다. 새로운 금융 진실원·잔액 추정·프레임워크·의존성은 만들지 않았다. Jackson3.1.8의 설치된 타입을 확인하고 문자열/정수 노드를 명시적으로 검사했다. 외부 비공개 운영 구조를 추측해 사실로 쓰지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약·대안 |
| --- | --- | --- |
| 복구 근거와 위험 해제 분리 | 정상 노드 응답 하나가 신규 금융 권한으로 바뀌지 않음 | 운영자는 별도 독립 승인 업무를 수행해야 한다. 이번에는 해제 API가 없다 |
| 시작/완료는 짧은 TX, 외부 읽기는 TX 밖 | 느린 저널/노드가 고객 행 잠금을 점유하지 않음 | 조회 중 상태가 바뀌면 HELD가 되며 다시 요청해야 한다. 검사 조건을 느슨하게 하지 않음 |
| 전역 실제 금융 대사 뒤 입금 노드 대사 | 원래 엔진 이벤트/플랫폼 금융과 실제 출력 근거를 함께 확인 | 큰 저널의 읽기·DB 처리 비용과 다른 금융 불일치의 영향이 있다. 증분 고객 전용 대사는 새로 만들지 않음 |
| 원장 행 수·마지막 ID·정확 잔액 벡터 지문 | 잔액만 되돌아온 금융 이동도 원장 추가로 탐지 | 불변 원장과 같은 고객 잠금 계약에 의존하며 추가 집계 비용이 든다. 기존 이력을 삭제/수정하지 않음 |
| 실제 금융/노드 근거 시각 중 이른 시각에서2분 | 긴 조회 뒤 오래된 성공을 새 근거처럼 쓰지 않음 | 운영자가 짧은 시간 안에 후속 판단해야 하며 만료되면 재대사 비용 발생. 법정 보관 기간이나 외부 기준이라고 주장하지 않음 |
| 같은 운영자/요청 키는 한 번만 외부 조회 | 동시 재전송이 중복 대사를 만들지 않음 | 진행 중/완료 실패는 STARTED로 남을 수 있다. 같은 키로 자동 재실행하지 않고 새 요청을 사용 |
| 필수 감사와 결과 같은 TX, 불변 이력 | 감사 누락 때 READY/HELD를 성공 확정하지 않음 | 완료 감사 장애면 시작 기록만 남는다. 이를 소스 장애로 바꿔 성공 폴백하지 않음 |

## 데이터 흐름과 API

1. 현재 OPERATOR/APPROVER와 최근 운영 인증을 확인하고 운영자·입금 고객을 같은 오름차순으로 잠근다. 실제 적립 완료 입금, 미해결 위험, 미종결 주문/잠금 잔액이 없는 고객만 시작한다.
2. 입금/권한/원래 엔진 이벤트·위험 관찰 범위 해시와 고객 원장/잔액 지문을 불변 시작 이력 및 필수 감사에 저장한다.
3. 잠금/TX를 놓고 실제 금융 대사를 수행한다. 성공한 다음 새 노드 관찰·자격 판정과 독립 입금 노드 대사를 수행한다. 원장 조회를 체결 코어 큐로 보내지 않는다.
4. 다시 현재 운영 인증과 같은 사용자 잠금을 확인한다. 범위/지문·현재 건강한 관찰과 판단·독립 노드의 같은 블록 앵커·최신 입금 대사·원래 엔진 이벤트를 결합한다.
5. READY 또는 HELD와 감사를 함께 확정한다. 조회 시 만료된 READY는 EXPIRED로 표현하지만 원본 행을 수정하지 않는다. 위험/원장/사용자 설정은 변경하지 않는다.

- `POST /api/admin/deposits/BTC/{receiptId}/chain-recovery-probes`, 본문 `{"requestKey":"UUID"}`.
- `GET /api/admin/deposits/BTC/chain-recovery-probes/{id}`, 현재 OPERATOR/APPROVER/AUDITOR의 감사되는 최소 근거 조회.
- 익명401/고객403, 알 수 없는 근거도 현재 운영 인증부터 확인한다. 외부 트랜잭션에서 호출하면 시작하지 않는다.

외부 대사는 현재 요청 스레드에서 기존 클라이언트의 제한 시간 아래 수행한다. HTTP 연결이 먼저 끊겨도 시작 이력은 남고 같은 키 재전송은 작업을 새로 만들지 않는다. STARTED는 성공/실패 결론이 아니며 후속 조회 또는 새 키로 다시 검증한다. 비동기 운영 작업 큐를 별도로 만들지 않았다.

현재 복구 근거는 일반 비코인베이스 출력에 한정한다. 성숙 코인베이스 입금의 복구 승인까지 검증한 것으로 표시하지 않는다. 미실행/결과 불명 입금, 손실·반환·역분개와 같은 다른 업무를 READY로 해결했다고 취급하지 않는다. 고객 비활성·KYC/CDD·다른 자산 위험·수탁 조건 등은 독립 제한이며 이 서비스가 해제하지 않는다.

## 실제 작성한 코드와 문법

출처: [BitcoinChainRecoveryProofs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRecoveryProofs.java#L45), 현재 소스의 부분 발췌.

```java
        Claim started=tx.execute(t->begin(actor,sid,receipt,key));
        if(!started.created())return get(actor,sid,started.id());
        // 길어질 수 있는 저널 읽기 대사를 먼저 수행한다. 이후 새 노드 관찰/입금 대사로 최신 체인을 확인한다.
        // 외부 호출 동안 고객 잠금·DB 연결을 보유하지 않으며 기존 장수 HTTP 클라이언트를 사용한다.
        Evidence evidence=collect(started);
        // 완료/감사 저장 실패는 외부 검증 실패로 다시 분류하지 않는다. 시작만 남으면 승인 근거가 아니다.
        return tx.execute(t->finish(started,evidence));
```

TransactionTemplate.execute는 콜백이 끝날 때 DB 트랜잭션을 확정한다. collect 호출은 그 밖에 있으므로 외부 응답을 기다리는 동안 고객 행 잠금을 유지하지 않는다. TypeScript의 await 자체가 DB 잠금을 해제해 주는 것은 아니며 여기서는 트랜잭션 경계를 실제로 나눴다. finish나 감사 저장의 예외는 collect의 소스 오류 처리에 잡히지 않는다.

출처: [BitcoinChainRecoveryProofs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRecoveryProofs.java#L90), 현재 소스의 부분 발췌.

```java
        if(current==null||!started.scope().equals(current.get("scope_hash"))||!started.ledger().equals(current.get("ledger_hash")))reason="RISK_OR_FINANCIAL_STATE_CHANGED";
        Instant expires=null;
        if("VERIFIED_RECOVERY_BASIS".equals(reason)){
```

시작 때 고정한 위험 범위/원장 지문과 현재 값을 비교한다. record는 필드를 묶는 Java 값 타입이며 데이터베이스 불변성까지 보장하는 문법은 아니다. 불변성·현재 근거 결합은 SQL 제약과 트리거가 별도로 담당한다.

출처: [chain-recovery-proof-schema.sql](../../src/main/resources/bitcoin/chain-recovery-proof-schema.sql#L2), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_ledger_fingerprint(owner bigint) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'entries',(SELECT count(*) FROM ledger_entries WHERE owner_id=owner),
  'lastEntry',(SELECT coalesce(max(id),0) FROM ledger_entries WHERE owner_id=owner),
  'balances',coalesce((SELECT jsonb_agg(jsonb_build_array(mint_address,available::text,locked::text) ORDER BY mint_address,id)
    FROM user_balances WHERE user_id=owner),'[]'::jsonb))::text,'UTF8')),'hex');
$$;
```

원장은 추가 전용이므로 행 개수와 마지막 ID가 금융 이동을 탐지하고, 잔액 투영의 정확 문자열 벡터가 별도 변경을 탐지한다. jsonb_agg의 명시적인 정렬로 같은 상태의 해시를 안정화한다. 이 지문은 원장과 잔액이 원래 맞는다는 증명이 아니며 실제 금융 대사의 성공을 함께 요구한다.

출처: [chain-recovery-proof-schema.sql](../../src/main/resources/bitcoin/chain-recovery-proof-schema.sql#L72), 현재 소스의 부분 발췌.

```sql
  SELECT * INTO native FROM bitcoin_current_deposit_reconciliation WHERE id=NEW.native_run AND receipt_id=probe.receipt_id;
  SELECT * INTO financial FROM reconciliation_runs WHERE id=NEW.financial_run;
  SELECT r.*,a.wallet,a.address,a.script_pub_key INTO receipt FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id WHERE r.id=probe.receipt_id;
  SELECT payload::jsonb INTO node FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.native_run AND kind='NODE';
  IF COALESCE(native.status<>'MATCHED' OR native.snapshot_at<probe.created_at OR native.snapshot_at<clock_timestamp()-interval '2 minutes' OR native.completed_at>clock_timestamp()
```

READY 저장 때 기존 성공 ID만 믿지 않는다. 시작 이후 생성된 실제 대사와 현재 입금 대사, 불변 노드 증거를 다시 연결한다. SELECT INTO는 PL/pgSQL의 조회 결과를 지역 레코드에 담는 구문이며 외부 RPC를 수행하지 않는다.

출처: [BitcoinChainRecoveryProofIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinChainRecoveryProofIntegrationTest.java#L69), 현재 소스의 부분 발췌.

```java
            var first=pool.submit(()->proofs.prepare(maker.user().getId(),maker.sid(),receipt.id(),key));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            BitcoinChainRecoveryProofs.Report replay;try{replay=proofs.prepare(maker.user().getId(),maker.sid(),receipt.id(),key);assertEquals("STARTED",replay.state());assertEquals(1,rows("bitcoin_chain_recovery_probes"));}finally{release.countDown();}
            var completed=first.get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(replay.id(),completed.id());assertEquals("READY",completed.state());
        }
```

두 요청이 겹치는 동안 두 번째 요청은 같은 STARTED 기록을 보고 외부 생산자를 늘리지 않는다. CountDownLatch로 겹치는 시점을 고정하며 단순 시간 지연으로 경쟁을 추측하지 않는다. 테스트 전용 실행기는 닫고 제품의 체결 스레드를 추가하지 않는다.

출처: [BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java#L133), 현재 소스의 부분 발췌.

```java
    @Test void realJournalReconciliationBuildsRecoveryEvidenceWithoutClearingTheRisk()throws Exception{
        assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,processing.processNext().orElseThrow().transportOutcome());listeners.start();settled();
        String original=originalEvent();long through=actualAudit.page(0,null,null).path("through").longValue();
        var normal=node.inspect(receipt.txid());when(node.inspect(receipt.txid())).thenReturn(new BitcoinDepositReader.Observation(normal.network(),normal.txid(),BitcoinDepositReader.Status.UNCONFIRMED,0,false,false,null,null,normal.tipHash(),normal.tipHeight(),normal.outputs()));
        observations.refresh(receipt.txid());when(node.inspect(receipt.txid())).thenReturn(normal);
        var report=recoveryProofs.prepare(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID());assertEquals("READY",report.state());assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));assertEquals(1,rows("bitcoin_credited_deposits"));assertEquals(1,rows("bitcoin_credit_chain_risks"));assertEquals(original,originalEvent());assertEquals(through,actualAudit.page(0,null,null).path("through").longValue());
```

실제 Rust 엔진 HTTP/WAL/Kafka로 한 번 확정한 입금을 만든 다음 실제 EngineAuditClient로 자체 저널을 다시 읽는다. 체인/외부 공급사 경계는 합성이고 금융 경로는 실제다. 복구 검증 전후 원래 이벤트 원문과 저널 through가 같아 새 금융 명령을 보내지 않았는지도 확인한다.

## 작성한 테스트와 기대 결과

신규 BitcoinChainRecoveryProofIntegrationTest26개는 실제 격리 PostgreSQL, 실제 전역 금융 대사/노드 대사/원장, 합성 EngineAuditClient/노드 경계로 검증한다. 아래 함수명 목록의 각 함수가 해당 시나리오의 실행 코드다.

| 함수/묶음 | 시나리오와 기대 결과 |
| --- | --- |
| realReconciliationsCreateAnImmutableBasisWithoutClearingTheRisk | READY지만 위험/원장/고객 제한 유지, 원본 대사1회·노드2회 |
| anUnresolvedChainNeverBecomesAReadyRecoveryProof / aSecondIndependentNodeReadCanHoldAnApparentlyHealthyObservation / aChangedBlockAnchorCannotBorrowTheFirstHealthyObservation | 미회복·독립 두 번째 조회 불일치·다른 블록 앵커는 HELD |
| aMissingEngineSourceDoesNotFallBackToPreviousSuccessfulReconciliation / aWrongJournalIdentityCannotProveTheCreditedReceipt | 옛 성공·잘못된 엔진 ID를 근거로 재사용하지 않음 |
| financialProjectionChangesDuringNodeIoInvalidateTheBeforeAfterFingerprint / anotherRiskPublicationDuringTheJournalReadInvalidatesTheScope | 조회 중 금융/위험 변화가 근거를 무효화, 손상 원본은 숨기거나 덮어쓰지 않음 |
| sameRequestKeyReturnsTheSameProofWithoutRepeatingRemoteWork / concurrentSameKeyHasOneRemoteProducerAndAnHonestStartedReplay / aRepeatedKeyCannotBeUsedForAnotherReceipt | 순차/동시 중복은 한 생산자·한 기록, 다른 원본 키 재사용409 |
| everyExternalReadReleasesTheTransactionAndAllowsAnotherCustomerLock | 외부 읽기의 매 콜백 중 다른 실제 TX가 같은 고객 잠금200ms 제한 아래 획득 |
| oldSuccessfulReconciliationsCannotBeForgedIntoANewCurrentProof | 직접 SQL로 옛 대사 결과를 새 시작에 결합하면 제약 실패/롤백 |
| firstAuditFailurePreventsEveryExternalRead / silentlyOmittedStartAuditCannotCommitAClaim | 시작 감사 실패/조용한 누락 모두 시작 롤백·외부 읽기0 |
| completionAuditFailureDoesNotBecomeASuccessfulHeldFallback / silentlyOmittedCompletionAuditCannotCommitAReadyResult | 완료 감사 실패/누락은 결과0·시작만 보존 |
| customerAndForeignSessionCannotProbeFinancialRecovery / auditorReadsAnExistingProofButCannotStartOne / actualHttpCreatesReadOnlyEvidenceAndAppliesRoleBoundaries | 현재 역할/세션·실제 HTTP401/403/200, 감사자는 조회만 |
| ambientTransactionsAndNilKeysNeverStartRemoteWork / aSettledDepositWithoutRiskCannotCreateARecoveryRequest / anUnsettledOrderPreventsStartingRecoveryEvenWhenNoReservationIsVisible | 잘못된 호출/위험 없음/미완료 주문은 외부 조회 전 차단 |
| nodeUnavailabilityKeepsTheRiskAndHidesPrivateFailureText / immutableProbeAndResultHistoryCannotBeRewrittenOrDeleted / aDisabledCustomerDoesNotTurnPhysicalRecoveryEvidenceIntoNewPermission | 오류 원문 비노출·불변 이력·비활성 고객 제한 보존 |

기존 BitcoinCreditWireIntegrationTest에 실제 저널 복구 근거 검증1개를 추가했고 기존3개도 같은 현재 소스로 다시 실행했다. 노드/공급사만 합성이며 Rust/HTTP/WAL/Kafka/플랫폼 원장·수신 키와 강제 종료/재시작·소비자 롤백/재처리는 실제 격리 서버다. 위험21·운영 조회18·금융182·보안41개는 새 스키마/정확한 보안 경로 아래의 관련 회귀다.

## 실행 명령과 실제 결과

환경: macOS·JDK21·Gradle8.14.6·소유 PostgreSQL16, 실제 연결은 소유 Kafka와 현재 엔진 소스의 cargo --locked --offline 빌드를 사용했다. 봇0·기존 소비자 자동 시작 금지·테스트별 격리를 유지했다.

```sh
caffeinate -i python3 scripts/verify_bitcoin_chain_recovery_proofs.py
caffeinate -i python3 scripts/verify_bitcoin_credit_wire.py
python3 scripts/verify_bitcoin_credit_chain_risk.py
python3 scripts/verify_bitcoin_credit_chain_risk_review.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
```

/tmp/run-cycle133-regressions.py는 원시 JSON/로그/JUnit XML을 .local/cycle133-proof에 보존하고 실제 클래스/함수명으로 고유 수를 집계했다. 신규26·실제 연결4·위험21·조회18·금융182·보안41, **고유292개/292회 통과**, 실패·오류·제외0이다. 개발 중 초기3/21개 실행·과거 사이클 수치를 합산하지 않는다. 두 JAR 빌드/새 클래스와 스키마 포함·테스트 제외, Python 구문·문서 발췌/링크·git diff --check도 확인했다.

개발 중 Mockito 검증 헬퍼의 상속 이름 충돌과 PL/pgSQL 지역 이름/감사 컬럼 충돌을 수정했다. 초기21개 실행 한 번은 기존 합성 가격 시각 fixture에서 실패해 복구 서비스까지 도달하지 못했다. 금융 시각 제한을 완화하지 않고 호스트/소유 DB 시각 차이를 기록한 뒤 전체26개를 다시 통과했다. 첫 실패 원인을 단정하지 않으며 JDK 계측·기존 deprecated/unchecked 경고는 남는다.

## 남은 범위

현재 단계는 **복구 증거 수집 완료**다. 위험 해제·독립 내용 결합 추가 인증·원래 위험 범위만의 해제와 새 위험 재차단은 후속134에서 구현한다. 원장 정정·손실·반환·출금·전체 백엔드 검토·프론트·마지막 ETH는 계속 후속이다. 운영 규모 성능·독립 조직 승인·실제 노드/공급사 운영 적합성·외부 인증을 이 로컬 증적으로 보장하지 않는다. 엔진 단일 소유 코어/출력 워커·Kafka 계약과 외부 복제/백업 배포를 변경하지 않았다.

## 작성한 테스트 코드


[BitcoinChainRecoveryProofIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinChainRecoveryProofIntegrationTest.java)

- `realReconciliationsCreateAnImmutableBasisWithoutClearingTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnresolvedChainNeverBecomesAReadyRecoveryProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMissingEngineSourceDoesNotFallBackToPreviousSuccessfulReconciliation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aSettledDepositWithoutRiskCannotCreateARecoveryRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sameRequestKeyReturnsTheSameProofWithoutRepeatingRemoteWork`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aRepeatedKeyCannotBeUsedForAnotherReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerAndForeignSessionCannotProbeFinancialRecovery`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `auditorReadsAnExistingProofButCannotStartOne`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ambientTransactionsAndNilKeysNeverStartRemoteWork`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `firstAuditFailurePreventsEveryExternalRead`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedStartAuditCannotCommitAClaim`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `completionAuditFailureDoesNotBecomeASuccessfulHeldFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedCompletionAuditCannotCommitAReadyResult`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeUnavailabilityKeepsTheRiskAndHidesPrivateFailureText`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aSecondIndependentNodeReadCanHoldAnApparentlyHealthyObservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aChangedBlockAnchorCannotBorrowTheFirstHealthyObservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `financialProjectionChangesDuringNodeIoInvalidateTheBeforeAfterFingerprint`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherRiskPublicationDuringTheJournalReadInvalidatesTheScope`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immutableProbeAndResultHistoryCannotBeRewrittenOrDeleted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aDisabledCustomerDoesNotTurnPhysicalRecoveryEvidenceIntoNewPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aWrongJournalIdentityCannotProveTheCreditedReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnsettledOrderPreventsStartingRecoveryEvenWhenNoReservationIsVisible`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentSameKeyHasOneRemoteProducerAndAnHonestStartedReplay`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `everyExternalReadReleasesTheTransactionAndAllowsAnotherCustomerLock`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oldSuccessfulReconciliationsCannotBeForgedIntoANewCurrentProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualHttpCreatesReadOnlyEvidenceAndAppliesRoleBoundaries`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java)

- `actualHttpAcknowledgementWaitsForKafkaAndDuplicateDeliveryDoesNotCreditTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostHttpSuccessAndForcedEngineRestartPreserveTheSameDepositAndEvent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requiredAuditFailureStopsTheRealConsumerAndReplaysWithoutLosingFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realJournalReconciliationBuildsRecoveryEvidenceWithoutClearingTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 34e6eef](https://github.com/GiHoon1123/Cex-Platform-Service/commit/34e6eefe2054f59f986a04f87d0ec5e9c0b4ee11)
- [격리 실행 증적](../reference/133-bitcoin-chain-recovery-proofs-verification-2026-10-26.json)
