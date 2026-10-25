# 준비된 입금을 장수 워커와 영속 일정으로 자동 반영하기

준비된 입금을 실제 대사→현재 의도→실행 권한→엔진 전달로 자동 연결한다. 작업 일정과 금융 권한을 구분하고 재시작·임대 만료·늦은 완료에서 실제 불변 원본을 다시 읽는다. 독자는 준비되지 않은 입금을 건너뛰는 이유와 자동 처리/금융 확정의 차이를 이해할 수 있다.

## 문제와 기존 동작

[127](127-durable-bitcoin-credit-dispatch-and-settlement.md)의 전달/확정 수신 서비스는 실제 코드로 연결됐지만 자동 후보 선택과 실행 수명이 없었다. 특정 입금의 외부 자료가 없는데 매번 선택하거나 최신 대사를 생략하면 다른 입금을 지연시키거나 과거 근거를 재사용할 수 있다. HTTP 성공을 작업 완료로 표시하면 실제 플랫폼 잔액과 일정이 어긋난다.

자동 제공/고객 KYC를 다시 구현하지 않는다. 기존 수탁·CodeVASP·탐지 흐름이 만든 실제 현재 자료가 준비됐을 때만 새 반입을 시도한다. 이미127에서 실행 권한을 확정한 명령은 별도로 결과를 확인한다. 이 구분은 고객/출처 조건이 바뀌었다고 이미 실행된 사실을 미실행으로 추측하지 않기 위해 필요하다.

## 기존 구현과 공식 자료의 접근

기존 [CodeVaspTransferProcessing](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferProcessing.java)의 짧은 DB 임대→TX 밖 실행→자기 임대 결과 저장과 [BitcoinDepositMonitoringWorker](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorker.java)의 장수 전용 스레드/준비 입력 배수 패턴을 따른다. 새 큐 제품이나 재시도 프레임워크를 도입하지 않는다.

2026-10-26 [PostgreSQL16 SELECT 잠금](https://www.postgresql.org/docs/16/sql-select.html)을 확인했다. SKIP LOCKED는 잠긴 작업을 기다리지 않고 다른 준비 작업을 고르는 큐 용도에 적합하지만 일반 금융 판단의 일관된 스냅샷을 대신하지 않는다. 작업 행만 잠그며 현재 금융 근거는 BitcoinCreditBasis가 별도 고객 잠금 아래 검증한다.

설치된 Spring Core7.0.9의 실제 타입/상수와 [공식 ExponentialBackOff API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/util/backoff/ExponentialBackOff.html)를 확인했다. 엔진 장애의 실행기 요청 간격은 이 라이브러리를 사용한다. 재시도 횟수·지연 계산용 새 공통 계층/설정·직접 구현 패키지는 만들지 않는다.127의 [outbox 원칙](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)도 유지하며 메시지 전송과 금융 완료를 하나로 설명하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 현재 준비 사실의 SQL 사전 필터 | 소액/미평가/미처리/열린 사건/출처 없음 때문에 앞선 입금을 계속 선택하지 않음 | 관련 표 조회 비용. 필터와 최종 정책은 역할이 다르며 사전 통과로 금융 권한을 만들지 않음 |
| 실제 PENDING에서 일정 생성 | 재시작 후 메모리 큐/알림 유실 없이 재구성 | 최대20개씩 일정/감사 쓰기. 과거 금융 사실이나 승인을 소급 생성하는 마이그레이션은 아님 |
| 2분 DB 임대·자기 임대 완료 | 프로세스 중단 후 재실행, 늦은 옛 완료의 새 일정 덮기 차단 | 긴 실행은 임대를 잃을 수 있음. 실행 원본/결과는 별도 불변 표에서 복구하며 새 ID로 재실행하지 않음 |
| 첫 권한 전에 새 실제 대사 | RPC 실패/변경을 과거 MATCHED로 숨기지 않음 | 입금당 별도 노드 읽기/대사 증거 쓰기. RPC는 TX와 고객 잠금 밖 |
| 이미 확정한 권한은 원본 재확인 | KYC 만료·소스 키 제거 뒤에도 이미 보낸 명령의 금융 사실 수렴 | 새 권한을 주는 단계와 명확히 구분해야 함. 이후 위험의 명시적 통제/정정은 별도 |
| 영속 일정과 메모리 부하 지연 분리 | 재시작 뒤에도 작업 지연/실행 원본 유지 | 실행기의 전역 지연은 재시작 때 초기화. DB 대기 조건은 사라지지 않음 |
| 전용 단일 실행기·준비 작업 연속 처리 | 건마다 스레드를 만들거나 불필요하게1초씩 기다리지 않음 | 한 워커의 처리 용량/노드·DB 경합을 운영에서 측정해야 함. 매칭 코어 처리량 보장으로 환산하지 않음 |
| 실제 결과에서 종료 상태 검증 | HTTP만으로 SETTLED를 사칭하지 못함 | HTTP 확정/Kafka 지연은 WAITING_EVENT 유지·관측/전달 복구 필요 |

사업상 비용은 원본/감사/대사 저장, 준비 조건 조회와 외부 노드 가용성, 일부 장애 해소 후 재확인 대기다. 모든 정상 입금에 운영자 서명/새 고객 추가 인증을 붙이지 않는다. 운영자가 확인할 예외 업무를 정상 사용자 화면에 그대로 노출하지 않는다.

## 처리 계약과 현재 범위

BitcoinCreditProcessing은 같은 네트워크/관찰 지갑의 실제 PENDING을 읽는다. 신규 명령 후보는 현재 고객·CDD 키/평가·live KYC 최근성·체인/지갑 사용·동결 평가·탐지 처리 범위·전체 사건·현재 VASP 수신/비교를 사전 확인한다. 준비되지 않은 예전 작업도 선택에서 제외하므로 뒤의 준비된 작업을 가로막지 않는다.

사전 필터는 개인정보를 복호화하거나 송금 정보 요청을 보내지 않는다. 실제 선택 이후 새 노드 대사를 실행하고 intents.reserve/dispatch.execute가 전체 현재 근거와 원본을 다시 검증한다. 필터를 통과했더라도 노드 읽기 중 고객 제한이 발생하거나 실제 정책이 거부하면 금융 권한을 만들지 않는다.

이미 실행 권한이 있으면 신규 고객/출처 필터를 통과 조건으로 다시 사용하지 않는다. 같은 원본의 시도와 실제 금융 결과를 읽어 엔진 확정 대기·거절·미확정을 구분한다. 실제 금융 결과가 있으면 SETTLED, 영속 거절이면 ENGINE_REJECTED, 엔진 성공 응답만 있으면 WAITING_EVENT다. 거절은 입금 원장을 해제하거나 반환 완료한 상태가 아니다.

작업의 미확정 재시도는 DB에5초 기반, 조건 보류는30초 기반으로 횟수에 따라 증가하고 최대120초로 제한한다. 엔진 응답 대기는30초 뒤 실제 결과를 다시 확인한다. 이미127의 시도 자체 대기 조건도 함께 적용한다. 실제 전송이 접수되지 않거나 불명이면 워커 전체의 요청 간격도 Spring ExponentialBackOff로5초부터 최대60초까지 늘려 한 장애에 다른 준비 작업을 빠르게 쏟지 않는다. 단조 시각은 이 메모리 부하 제한에만 사용하고 금융 시각/영속 일정은 DB 기준이다.

BitcoinCreditWorker는 기존 bitcoin.processing.enabled와 노드 설정만 사용한다. 전용 실행 스레드 하나를 재사용하고 준비 작업이 없으면1초 지연을 적용한다. test 프로필에서는 활성화 설정을 주어도 자동 생성하지 않는다. 종료 시 현재 처리의 영속 원본을 보존하고 다음 입금을 가져오지 않는다. 기존 실행이 종료되지 않았으면 새 실행기를 중복 생성하지 않는다. 봇이나 개인정보 자동 제공 설정을 켜지 않았다.

## 실제 코드와 필요한 문법

출처: [BitcoinCreditProcessing.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditProcessing.java#L70), 현재 소스의 부분 발췌.

```java
        Claim claim=tx.execute(t->{seed(scope);return claim(scope);});if(claim==null)return Optional.empty();
        Result result=perform(claim);
        // 결과/감사 저장 장애는 외부 실패로 다시 분류하지 않는다. 임대가 남으면 실제 금융 원본에서 복구한다.
        return tx.execute(t->finish(claim,result));
```

TransactionTemplate 콜백 안에서는 일정 생성과 임대 확보만 끝낸다. perform은 그 TX가 커밋된 뒤 호출한다. TypeScript에서도 외부 HTTP/RPC await를 DB TX 콜백 안에 넣으면 잠금은 계속 유지될 수 있다. 여기서는 두 범위를 코드로 분리했다.

출처: [BitcoinCreditProcessing.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditProcessing.java#L96), 현재 소스의 부분 발췌.

```java
            var known=known(claim.receipt());if(known.state()!=null)return new Result(known.state(),"RECOVERED_FINANCIAL_FACT",null);
            if(!known.authorized()){
                // 노드 읽기는 임대/고객 잠금과 DB TX 밖이다. 최신 실제 대사 뒤에도 의도/전송은 각자 현재 조건을 재검증한다.
                var proof=reconciliation.reconcile(claim.receipt());
                if(proof.status()!=BitcoinDepositReconciliationService.Status.MATCHED)return new Result("HELD","RECON_"+proof.status(),null);
                intents.reserve(claim.receipt());
            }
```

불변 금융 결과를 먼저 확인해 재시작/마지막 일정 저장 실패를 회복한다. 아직 최초 실행 권한이 없을 때만 새 노드 대사와 현재 의도를 준비한다. 기록이 없다는 이유로 플랫폼 잔액을 엔진에 덮어쓰지 않는다.

출처: [BitcoinCreditProcessing.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditProcessing.java#L128), 현재 소스의 부분 발췌.

```java
             WHERE receipt_id=? AND lease_id=? AND lease_until>clock_timestamp()
            """,result.state(),result.reason(),terminal,result.state(),base,result.state(),claim.receipt(),claim.lease());
        if(updated==0)return Optional.empty();
```

완료 쓰기는 현재 소유한 만료 전 임대에만 적용한다. 예전 호출이 뒤늦게 끝났다면 일정 변경0건으로 끝나고 실제 실행 권한/응답은 별도 표에 남는다. 같은 시점의 다른 작업 상태를 되돌리지 않는다.

출처: [BitcoinCreditWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditWorker.java#L43), 현재 소스의 부분 발췌.

```java
        if(!active||(delayNanos>0&&System.nanoTime()-failedAtNanos<delayNanos))return;
        while(active){
            var result=processing.processNext();if(result.isEmpty())return;
            var transport=result.get().transportOutcome();
            if(transport==EngineDepositClient.Outcome.NOT_ACCEPTED||transport==EngineDepositClient.Outcome.OUTCOME_UNKNOWN){cooldown();return;}
            if(transport==EngineDepositClient.Outcome.DURABLY_CREDITED||transport==EngineDepositClient.Outcome.DURABLY_REJECTED){
                retries=backoff.start();delayNanos=0;if(failed){failed=false;log.info("입금 명령의 엔진 응답 확인을 재개했습니다. 플랫폼 금융 확정은 별도 결과를 따릅니다");}
```

메모리 지연은 단조 시각 차이로 계산한다. while은 수명이 긴 같은 스레드가 준비 입력을 이어 처리한다는 뜻이며 주문/입금마다 새 스레드를 만드는 구조가 아니다. 중단·금융 확정 여부는 이 플래그가 아니라 영속 원본과 결과로 판단한다.

출처: [credit-processing-schema.sql](../../src/main/resources/bitcoin/credit-processing-schema.sql#L17), 현재 소스의 부분 발췌.

```sql
 IF NEW.state='SETTLED' AND NOT EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=NEW.receipt_id) THEN RAISE EXCEPTION '실제 금융 결과 없이 입금 작업을 완료할 수 없습니다';END IF;
 IF NEW.state IN ('WAITING_EVENT','ENGINE_REJECTED') AND NOT EXISTS(
  SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
   WHERE a.receipt_id=NEW.receipt_id AND r.outcome=CASE WHEN NEW.state='WAITING_EVENT' THEN 'DURABLY_CREDITED' ELSE 'DURABLY_REJECTED' END
 ) THEN RAISE EXCEPTION '실제 확정 응답 없이 입금 작업 상태를 바꿀 수 없습니다';END IF;
```

작업 상태만 UPDATE해 입금 완료나 엔진 확정을 사칭하지 못하게 실제 결과/응답 존재를 확인한다. 이 표는 재구성 가능한 일정이므로 상태와 임대는 갱신하지만 입금 원본과 생성 시각·종료 사실을 바꾸거나 행을 삭제하지 않는다.

## 작성한 테스트

[BitcoinCreditProcessingIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditProcessingIntegrationTest.java)의29개와 [BitcoinCreditWorkerTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinCreditWorkerTest.java)의9개를 작성했다. PG 시험은 메서드별 별도 DB다. 외부 Node/엔진 HTTP 응답은 대역이며 실제 기존 CDD 암호화·출처 증빙·원장·대사·임대·감사/금융 수신 코드를 사용한다.

마지막 PG 시험은 test 프로필의 자동 실행 차단을 바꾸지 않고 시험이 직접 생성/종료한 실제 워커를 PG 처리 서비스에 연결했다. 반입 권한/HTTP 응답 대기 상태까지 자동으로 진행하고 아직 Kafka 결과가 없으면 실제 잔액은 보류다. 실제 엔진 프로세스·브로커/오프셋·메인넷/공급사까지 검증했다고 주장하지 않는다.

| PG 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `readySourceAutomaticallyReconcilesAuthorizesAndWaitsForTheEvent` | 정상 자료는 새 실제 대사/의도/권한/전달로 자동 연결하고 WAITING_EVENT·자금은 보류 |
| `currentCustomerRestrictionsAreSkippedBeforeAnyNodeRead` | 현재 제한 고객은 노드 읽기 전에 제외 |
| `staleKycAndChainCannotEnterTheAutomaticCandidates` | 미래 KYC 확인이면 후보 제외 |
| `oldUnreadyJobCannotStarveThePreparedSource` | 더 오래된 미준비 작업이 준비 입금을 가로막지 않음 |
| `unprocessedMonitoringInputMustFinishBeforeAutomation` | 탐지 입력 처리가 끝나야 후보에 진입 |
| `anOpenCaseIsSkippedAndIndependentClosureMakesTheSourceReady` | 열린 사건 제외·실제 독립 종결 뒤 자동 처리 |
| `missingFrozenValuationDoesNotCreateAnAutomaticJob` | 동결 평가 실패/불완전 탐지는 일정 생성 없음 |
| `currentSourceConfigurationIsRequiredOnlyForNewAuthority` | 현재 제공자 설정이 없으면 새 권한 후보 없음 |
| `anotherWalletOrNetworkCannotProcessThisDatabaseReceipt` | 다른 지갑/네트워크는 이 입금을 처리하지 못함 |
| `freshIndependentNodeFailureDoesNotBorrowThePreviousMatchedRun` | 새 노드 실패는 과거 MATCHED를 재사용하지 않음 |
| `changedCustomerDuringNodeReadCannotObtainFirstExecutionAuthority` | 노드 읽기 중 제한이 생기면 최초 권한 거부 |
| `prerequisiteFilterCannotOverrideTheActualAdmissionPolicy` | 사전 필터 통과도 실제 정책 거부를 덮지 않음 |
| `nodeAndEngineWaitsRunOutsideTheClaimTransaction` | 노드/엔진 대기는 실제 Spring TX 비활성·임대/권한은 먼저 커밋 |
| `unknownOutcomePersistsRetryScheduleAndDoesNotRepeatTheNodeRead` | 불명은 영속 재시도 지연·즉시 중복 전송/노드 재독 없음 |
| `alreadyAuthorizedUnknownWorkResumesWithoutSourceKeysOrCustomerPermission` | 이미 권한을 확정한 불명은 키/제공자/고객 조건 변화 뒤 같은 원본으로 확인 |
| `anExistingHttpAcknowledgementIsRecoveredWithoutAnotherTransport` | 이미 확정한 HTTP 응답은 재전송 없이 결과 대기 복원 |
| `durableRejectionEndsOnlyTheJobAndPreservesPendingMoney` | 영속 거절은 작업 종료·보류 자금 보존 |
| `actualFinancialEventEndsTheJobWithoutRecontactingEngine` | 실제 Kafka 금융 결과가 있어야 SETTLED·재접속 없음 |
| `aFinancialEventBeforeHttpResponseCanBeSettledInTheSameProcessingPass` | HTTP보다 먼저 오는 실제 금융 결과도 SETTLED |
| `anExpiredOperationalLeaseIsRecoveredFromTheActualSource` | 만료된 운영 임대는 원본에서 회복 |
| `anActiveLeaseCannotBeClaimedByAnotherExecution` | 살아 있는 임대는 중복 확보하지 않음 |
| `seedAuditFailureCannotCreateOrExecuteWork` | 생성 감사 실패 시 일정/실행 없음 |
| `claimAuditFailureCannotRetainALeaseOrExecuteWork` | 임대 감사 실패 시 임대/실행 없음 |
| `finishAuditFailureRecoversTheConfirmedFactAfterLeaseExpiry` | 완료 감사 실패 뒤 임대 만료 시 실제 확정 응답으로 회복 |
| `anOldCompletionCannotOverwriteTheNewLeaseAndItsState` | 늦은 옛 완료는 새 임대의 RETRY 상태를 덮지 않음 |
| `workStateCannotPretendToBeAFinancialResult` | 일정 상태만으로 SETTLED/엔진 확정 사칭 불가 |
| `operationalIdentityAndHistoryCannotBeDeletedOrReassigned` | 작업 원본/생성 시각 변경·삭제/초기화 거부 |
| `ambientTransactionCannotStartAutomaticProcessing` | 이미 열린 DB TX에서 자동 처리 시작 거부 |
| `anExplicitlyOwnedWorkerRunsTheActualDatabasePipeline` | 직접 소유한 실제 워커가 실제 PG 처리 서비스의 전송 대기 단계까지 진행 |

| 워커 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `disabledWorkerDoesNotAllocateOrCall` | 비활성 설정은 실행기/호출 없음 |
| `missingNodeConfigurationDoesNotStartTheWorker` | 노드 설정 부재도 실행기 없음 |
| `testProfileExcludesAutomaticExecutionEvenWhenEnabled` | test 프로필은 활성화 요청에도 자동 워커 제외 |
| `readyItemsAreDrainedWithoutWaitingBetweenThem` | 준비3개는 건별5초를 기다리지 않고 연속 처리 |
| `repeatedStartUsesOneLongLivedExecutionThread` | 중복 start에도 같은 스레드 재사용 |
| `slowProcessingDoesNotAccumulateOverlappingCalls` | 느린 처리 중 주기가 지나도 호출 중첩 없음 |
| `runtimeFailureWaitsBeforeResumingTheSameWorker` | 런타임 실패 뒤80ms 이상 대기 후 같은 워커 회복 |
| `consecutiveEngineUnknownOutcomesIncreaseTheGlobalWait` | 연속 엔진 불명은80ms/160ms로 실제 대기 증가 |
| `shutdownStopsBeforeTheNextReadyItem` | 종료 요청 뒤 다음 준비 항목을 가져오지 않음 |

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests dustin.cex.domains.bitcoin.BitcoinCreditWorkerTest --console=plain
/usr/bin/caffeinate -i python3 scripts/verify_bitcoin_credit_processing.py
/usr/bin/caffeinate -i python3 /tmp/verify-cycle128-owned-worker.py
/usr/bin/caffeinate -i python3 /tmp/run-cycle128-regressions.py
```

2026-10-26 최초 정상1개 뒤 PG28개 전체를 실행했고, 실제 소유 워커/PG 연결1개를 추가해 해당 함수를 별도 격리 실행했다. 마지막1개는 `/tmp/verify-cycle128-owned-worker.py`가 동일한 격리 실행기로 함수 하나만 선택했다. 제품/기존28개 동작은 바꾸지 않았으므로 이를 합쳐 **고유 PG29개/29회**로 기록한다. 현재 저장소의 `verify_bitcoin_credit_processing.py`는29개 전체를 재현하는 기준 실행기다. 최초 정상1개 결과는 최종 수에 합산하지 않는다.

워커9개·PG29개와 기존 금융 연결37개·금융182개·실제 Core31.1 회귀231개를 확인했다. 같은 원장18개를 중복 제외한 **고유470개/488회** 통과다. 실패·오류·제외0.127의 금융182개 초기 실패/수정은 당시 기록이며 이번 수치에 재사용하지 않는다. 이번 회귀는 새로운 스키마/자동 워커 격리가 기존 금융/네트워크/체인 계약에 미치는 영향을 확인한다. 변경하지 않은126 정책35개를 불필요하게 다시 실행하지 않았으며127의 당시 결과와 구분한다.

상위 회귀는 외부 공급사/메일/BTC/CodeVASP/가격 설정을 제거하고 봇0·소비자 자동 시작 금지·격리 외부 주소를 적용했다. 각 소유 PG/노드를 정리하고 기존5개 컨테이너는 보존했다. 두 제품 JAR의 구현/SQL 포함·시험 제외, Python 구문·발췌·상대 경로·공백을 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

## 남은 범위와 운영 조건

정상 VASP 입금의 자동 연결을 구현했으며 실제 Rust/HTTP·Kafka 브로커·플랫폼 전체 연결은 다음 검증 범위다. 새 운영 상태의 인가된 조회/관측, 지연/실패 처리량·저장/부하 예산도 마무리해야 한다. 이 시험은 선택/재시도/복구의 기능 검증이며 실제 서비스 TPS나 공급사/메인넷 연동 성공을 의미하지 않는다.

비VASP·소액/개인지갑·강화 고객의 별도 해결, 전송 후 재조직/거절·반환/명시적 정정, 보고 후속·보존·실제 계약/외부 확인은 계속 진행한다. 프론트는 백엔드 완료와 전체 검토 뒤이며 마지막 ETH/USDT·이더리움 입출금 순서를 유지한다. 코어 단일 스레드/공유 락 제거·WAL 확정/자체 복원은 변경하지 않았다. 외부 복제/백업은 사용자 지시대로 문서 필요 조건만 유지한다.


## 관련 커밋과 증적

- [구현 ff6bf6c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/ff6bf6c801c45e040e9042bb75c781ef5991758a)
- [격리 실행 증적](../reference/128-automatic-bitcoin-credit-processing-verification-2026-10-26.json)
