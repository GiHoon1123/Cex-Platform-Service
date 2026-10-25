# 영속 임대로 입금 거래 재확인과 늦은 결과 분리하기

발견한 고객 입금의 거래를 영속 임대로 다시 확인한다. 노드 장애·실행 종료·늦은 결과가 있어도 기존 관찰을 보존하고 다음 실행이 이어받는 계약을 설명한다. 이 사이클은 내부 재확인 업무와 큐까지이며, 새 자동 관찰 워커/잔액 적립을 완료한 기록은 아니다.

## 문제와 기존 동작

082의 자동 워커는 실제 블록 발견과 안정 입금 ID를 생성하지만 078의 독립 거래 관찰을 지속 호출하지 않았다. 새 입금을 한 번 조회하고 끝내면 확정 수 증가/충돌/재조직을 계속 확인할 수 없다. 단순히 가장 오래된 거래를 매번 골라 호출하면 같은 노드 오류의 거래가 다른 입금을 막을 수 있으며, 프로세스 메모리의 재시도 목록은 종료 시 사라진다.

## 기존 제품과 공식 자료의 접근

PostgreSQL16 [SELECT 잠금 절](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE)은 SKIP LOCKED를 일반 조회의 완전한 스냅샷이 아닌 여러 소비자의 큐 처리에 적용할 수 있다고 설명한다. 기한 순으로 한 행을 잠깐 잠가 임대 UUID/만료를 확정하고 잠금을 해제한다. 이미 도입한 [KycReconciliationService](../../src/main/java/dustin/cex/domains/kyc/KycReconciliationService.java)의 영속 임대/외부 호출 분리를 같은 금융 사실 확인에 적용했다. 범용 큐 프레임워크나 새 패키지를 만들지 않는다.

주문 명령의 순서를 나누기 위해 SKIP LOCKED를 쓰는 것이 아니다. 서로 다른 입금 거래의 **읽기 전용 노드 재확인**만 나눠 수행하며 Rust 매칭 코어와 출력 계약은 변경하지 않았다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·한계와 대안 |
| --- | --- | --- |
| 입금 원본과 관찰 작업 같은 TX | 입금만 생기고 재확인 작업이 누락되는 틈 방지 | DB 쓰기 한 건/인덱스 사용. 메모리 큐만으로는 종료 이후 보장 불가 |
| 거래별 작업 하나 | 같은 거래의 여러 고객 출력은 노드 관찰 한 번 공유 | 재확인 단위와 고객별 입금/승인 단위가 다름. 후속 적립은 각각의 출력과 소유를 대조해야 함 |
| 짧은 임대/노드 조회 분리 | DB 연결/행 잠금으로 느린 RPC를 기다리지 않음 | 임대 만료 때 중복 노드 읽기가 가능. 임대 UUID와 최신 관찰 CAS로 늦은 확정 차단 |
| 임대2분·성공/실패 후30초 대기 | 중단 실행 인수와 실패 거래의 독점 방지 | 즉시 재확인은 아니며 노드 부하/최신성 예산 측정 필요. 현재 미결 입금 재확인 기한이며 거래 확정 수 정책을 뜻하지 않음 |
| 관찰과 작업 완료 별도 TX | 원래 노드 관찰의 불변 이력 보존 | 관찰은 저장됐는데 큐 완료는 실패할 수 있음. 다음 조회가 새 관찰을 남겨 이어가며 과거 사실을 삭제하지 않음 |
| 재시작 시 불변 입금에서 빠진 작업 재구성 | 기존 금융 원본을 바꾸지 않고 미전달 작업 복원 | 시작 시 기존 입금 조인/충돌 검사가 필요. 매 주기 전체 원본을 다시 훑지 않음 |

이 큐는 미결 입금 관찰을 위한 기반이다. 금융 반입이 완료된 과거 입금의 재확인 종료/재조직 재가동 조건은 후속 금융 단계에서 결합해야 한다. 현재 반복 조회의 운영 처리량을 검증하지 않았으며 모든 과거 거래를 영구히30초마다 읽는 것이 최종 운영 정책이라고 주장하지 않는다.

## 구현과 흐름

[BitcoinDepositReceiptService](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptService.java)는 새 양수 입금 원본을 만든 트랜잭션에서 `(network,wallet,txid)` 작업도 저장한다. 같은 거래의 출력이 여러 개여도 작업 하나다. 기동 시 `seedKnownReceipts`가 기존 불변 입금/주소에서 누락 작업만 추가하고 기존 작업의 기한/임대/관찰 포인터는 바꾸지 않는다.

`refreshDue`는 외부 DB 트랜잭션 호출을 거부한다. 첫 짧은 TX에서 기한 도래/미임대 또는 만료 행을 하나 골라2분 임대와 시작 감사를 확정한다. 노드를 조회하는 `BitcoinObservationService.refresh`는 TX 밖에서 호출하고 자신의 독립 TX로 관찰 원본을 확정한다. 마지막 짧은 TX에서 여전히 같은 UUID의 유효 임대를 가진 경우만 작업의 마지막 성공 관찰/다음 기한/오류 수와 완료 감사를 확정한다.


출처: [BitcoinDepositReceiptService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReceiptService.java#L63), 현재 소스의 부분 발췌.

```java
            // 새 입금과 재확인 작업을 같은 TX에서 남긴다. 작업 전달만 성공하고 입금 원본이 빠지는 틈을 만들지 않는다.
            jdbc.update("""
                INSERT INTO bitcoin_deposit_observation_jobs(network,wallet,txid)
                 SELECT DISTINCT network,?,txid FROM bitcoin_deposit_receipts WHERE first_scan_id=?
                 ON CONFLICT(network,wallet,txid) DO NOTHING
                """,settings.wallet,scan);
```

입금과 작업 등록은 같은 TransactionTemplate 내부다. 전송 완료로 오해할 외부 네트워크 호출은 없고, 트랜잭션 실패 시 새 입금·작업·블록 식별 완료·감사가 같이 롤백된다. 작업의 원본은 불변 입금이며 임의 거래 ID를 새 작업으로 넣는 경로가 아니다.

출처: [BitcoinDepositObservationJobs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationJobs.java#L55), 현재 소스의 부분 발췌.

```java
            SELECT txid FROM bitcoin_deposit_observation_jobs WHERE network=? AND wallet=?
             AND next_attempt_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())
             ORDER BY next_attempt_at,txid LIMIT 1 FOR UPDATE SKIP LOCKED
            """,settings.network.id,settings.wallet);
        if(rows.isEmpty())return null;
        String txid=(String)rows.getFirst().get("txid");UUID id=UUID.randomUUID();
```

`FOR UPDATE SKIP LOCKED`는 이미 다른 소비자가 잠근 작업을 기다리지 않는다. 이 잠금은 claim 트랜잭션이 끝나면 해제된다. 임대 시각/다음 기한은 애플리케이션 시계 대신 같은 DB의 clock_timestamp를 사용한다.

출처: [BitcoinDepositObservationJobs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationJobs.java#L43), 현재 소스의 부분 발췌.

```java
            observed=observations.refresh(work.txid());
            if(!observed.network().equals(settings.network.id)||!observed.txid().equals(work.txid()))throw new IllegalStateException("다른 거래의 관찰입니다");
        }catch(RuntimeException failure){
            // 오류 원문은 노드 응답/개인정보를 포함할 수 있다. 제한된 분류만 확정하고 다른 작업에 기회를 준다.
            String code=failure instanceof ResponseStatusException e?(e.getStatusCode().value()==409?"OBSERVATION_CONFLICT":"NODE_UNAVAILABLE"):"INTERNAL_FAILURE";
            finish(work,null,code);return true;
        }
        // 관찰은 이미 별도 TX에서 확정됐다. 여기서 장애가 나도 그 원본을 지우지 않고 임대 만료 뒤 다시 확인한다.
        finish(work,observed,"OBSERVED");return true;
```

노드 호출 때 실제 DB 트랜잭션이 없는 것을 테스트에서 확인한다. 읽기 오류는 제한된 코드로만 분류한다. 예외 원문을 DB/감사에 복사하지 않으며 성공했던 관찰을 지우지도 않는다. 409는 다른 관찰 확정과의 충돌로 따로 구분한다.

출처: [BitcoinDepositObservationJobs.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationJobs.java#L72), 085에서 온체인 판단을 연결한 현재 소스의 부분 발췌.

```java
                SELECT txid FROM bitcoin_deposit_observation_jobs WHERE network=? AND wallet=? AND txid=?
                 AND lease_id=? AND lease_until>clock_timestamp() FOR UPDATE
                """,settings.network.id,settings.wallet,work.txid(),work.id());
            if(owns.isEmpty()){
                audit.record(null,"BTC_DEPOSIT_OBSERVATION_FINISHED","bitcoin-observation-lease:"+work.id(),"DENIED","LEASE_LOST");return;
            }
            if(observed!=null)qualification.evaluate(observed.id());
            jdbc.update("""
                UPDATE bitcoin_deposit_observation_jobs SET lease_id=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+interval '30 seconds',
                 latest_observation_id=COALESCE(?,latest_observation_id),last_outcome=?,failures=CASE WHEN ? THEN 0 ELSE failures+1 END
```

늦은 실행의 UUID가 현재 임대와 다르거나 만료됐으면 DENIED/LEASE_LOST 감사만 추가하고 작업을 갱신하지 않는다. 임대가 끝난 이전 실행이 새로운 실행의 다음 기한/관찰 ID/오류를 덮어쓸 수 없다. 금융 원본에 공유 락을 넣는 방식이 아니다.

작업의 latest_observation_id는 이 작업에서 마지막으로 받아들인 성공 관찰이다. 다른 독립 읽기가 더 새 관찰을 만들 수 있으므로 이 포인터만으로 현재 체인 상태/거래 허용을 확정하지 않는다. 후속 확정 판단은 현재 관찰 원본과 정규 블록/기한을 따로 검증해야 한다.

[스키마](../../src/main/resources/bitcoin/schema.sql)는 입금 근거 없는 작업/다른 지갑 결과 연결을 거부하고 작업 키/생성 시각 변경·마지막 성공 관찰의 삭제/과거 이동을 막는다. 운영 임대/기한/오류 수만 갱신하며 작업 삭제/전체 비우기는 거부한다. 금융/관찰/감사 원본은 불변으로 남는다. 실패가 원래 관찰 성공을 되돌리지는 않는다.

## 작성한 테스트

[BitcoinDepositObservationJobsIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationJobsIntegrationTest.java)의11개는 실제 Core31.1/독립 PG16에서 실행한다. 실제 블록을 조회해 만든 입금 원본을 사용한다. 이전 확정 입금에는 작업이 없다는 시작 상태도 직접 fixture로 구성해 복원한다. 임대/대기 기한만 시험 목적에 맞게 변경하며 금융/감사 원본은 지우지 않는다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| idleAndStartupReseedDoNotRepeatNodeOrFinancialRecords | 기한 미도래는 RPC0/false, 시작 재구성 반복에 작업 중복0 |
| actualRefreshCommitsObservationJobPointerAuditAndCooldownWithoutCredit | 실제 관찰/작업 포인터/감사와30초 대기, 원장/잔액0 |
| nodeFailureKeepsPreviousObservationAndDelaysOnlyThisJob | 노드 오류는 기존 성공 관찰 유지, 실패 수 증가/임대 해제/대기 |
| simultaneousClaimDoesNotHoldDatabaseTransactionDuringNodeRead | 노드 읽기 동안 TX 없음, 다른 실행이 같은 유효 임대 재확인하지 않음 |
| expiredLeaseCannotOverwriteNewerCompletedJob | 만료 후 새 실행 완료, 늦은 이전 결과는 DENIED/LEASE_LOST·새 결과 보존 |
| claimedButAbandonedWorkCanBeResumedByFreshServiceAfterExpiry | 시작만 확정하고 중단, 유효 임대 중 재접수 없음, 만료 뒤 새 서비스 재개 |
| claimAuditFailureRollsBackLeaseBeforeNode | 시작 감사 장애는 임대 롤백/RPC0 |
| finishAuditFailureKeepsCommittedObservationAndLeaseForRecovery | 관찰은 남고 완료/임대 해제는 롤백, 만료 후 재조회 성공 |
| ambientTransactionAndForgedOrDeletedJobsAreRejected | 외부 TX/근거 없는 작업/키 변경/삭제/비우기 거부 |
| existingObservationPointerCannotBeClearedOrMovedBackward | 현재 작업 관찰의 초기화/과거 이동 거부 |
| conflictAndUnexpectedFailureUseBoundedCodesAndDoNotStarveAnotherTransaction | 오류 원문 대신 분류 코드, 다른 실제 입금 거래는 정상 진행 |

중단 시험은 claim 뒤 예외를 주입해 저장된 임대를 남기고 DB 시각을 과거로 조정해 인수 계약을 검증한다. 실제 운영 프로세스를 죽이거나2분 동안 대기한 시험으로 표현하지 않는다. 노드 응답/관찰 저장은 실제 경로이며 임대 소유권 충돌을 별도로 주입한다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/묶음별 PG16에서 **신규11개+기존93개=고유104개 통과**, 실패/오류/제외0개다. addresses53/observations11/scans12/receipts10/worker7/jobs11은 각각 별도 PG 서버를 사용했다. 두 제품 JAR 빌드와 소유 노드 종료/PG6개 제거를 확인했다. 봇0/peer0/외부 주소 격리를 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 의존성 추가/OSV 감사/MAINNET/성능 측정은 없다.

첫 실행은 기존93개 통과 후 신규11개 중3개가 감사 outcome 제약에 걸렸다. 기존 감사는 SUCCESS/DENIED/ALLOWED만 허용하는데 호출부에서 임의의 FAILED/STALE을 사용한 오류였다. 공통 스키마를 완화하지 않고 DENIED와 상세 사유 코드로 수정한 뒤 새 노드/DB에서 전체104개를 다시 실행했다. 실패 증적은 .local/cycle83-proof/first-failure에 보존하며 최종 통과 수와 합산하지 않는다.

## 남은 연결과 한계

이번 사이클은 내부 큐와 `refreshDue`까지다. 전용 자동 관찰 워커·확정/성숙도/위험 보류·원장/엔진 HTTP/Kafka 반영·재조직 공통 조상/금융 복구를 이어야 한다. 과거 입금의 관찰 주기 종료와 재조직 재가동도 해당 금융 상태와 연결해야 한다. 큐 성공을 엔진 잔액 적립 또는 외부 사업 심사 통과로 주장하지 않는다.


## 관련 커밋과 증적

- [구현 519b492](https://github.com/GiHoon1123/Cex-Platform-Service/commit/519b492b06b15c663354fc0251b389abbf7474e3)
- [격리 실행 증적](../reference/083-durable-bitcoin-observation-jobs-verification-2026-10-25.json)

085 현행 기준: 성공 관찰의 유효 임대 완료 TX에서 [온체인 판단](085-bitcoin-deposit-chain-qualification.md)도 기록한다. 기존083의 시험/시점은 유지하며 고객 자격/금융 반입은 여전히 후속이다.
