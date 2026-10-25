# 독립 워커로 보류 원장을 자동 반입하고 잠긴 고객 건너뛰기

자동 발견/현재 거래 관찰 뒤에 고객 보류 자산 원장도 독립 워커로 반입한다. 특정 고객의 잠금이 다른 고객을 막지 않도록 후보를 선택하고, 명시적인 최초 원장/종료·재개/금융 원본 보존을 실제 두 고객 수신으로 검증한 범위를 설명한다.

## 문제와 기존 동작

088의 recognize/recognizeNext는 실제 원장을 기록하지만 내부 호출이 필요했다. 이를 블록 발견이나 노드 관찰의 같은 실행기에 넣으면 고객 잠금/필수 감사 장애가 이미 들어온 입금의 확인까지 막을 수 있다. 또 recognizeNext가 가장 오래된 후보를 고른 뒤 고객 행을 기다리면 그 한 고객의 긴 잠금이 다른 고객의 반입을 지연시킬 수 있었다.

## 기존 제품과 공식 자료의 접근

이미 설치된 Spring ThreadPoolTaskScheduler의 [고정 지연/종료](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)를 사용한다. KYC와082/084의 전용 스케줄러 방식처럼 업무별 수명이 긴 단일 실행기를 둔다. 유지보수되는 실행기 라이브러리를 그대로 쓰고 새 범용 워커 프레임워크/패키지를 만들지 않았다.

PostgreSQL16 [SKIP LOCKED](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE)는 큐의 독립 대상을 여러 소비자가 선택하는 패턴에 사용할 수 있다. 이 선택은 고객별 보류 입금 후보에만 적용하고, 선택한 입금의 원본/현재 조건/금액은 같은 TX에서 다시 검사한다. 엔진의 주문 순서/매칭/공통 Kafka 파티션을 나누는 작업이 아니다.

Spring [TransactionTemplate 설정](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html#tx-prog-template-settings)으로 보류 반입의 DB 트랜잭션 제한10초를 설정했다. 이는 연결 획득/드라이버/OS 정지를 포함한 모든 작업이 절대10초 안에 끝난다는 보장이 아니다. 실제 시험은 잠긴 고객을 기다리지 않고 다른 고객을 처리하는 동작을 확인했다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·한계 |
| --- | --- | --- |
| 발견/관찰/보류 반입 각 독립 스레드 | 느린 원장/감사/고객 잠금이 앞의 두 작업을 직접 막지 않음 | 플랫폼 스레드3개와 DB/CPU/디스크 경쟁. 일반 앱 전체 스레드가3개라는 뜻 아님 |
| 고객 행 SKIP LOCKED 후보 선택 | 한 고객이 잠겨도 독립 고객의 적합 입금 처리 | 선택 순서가 전체 고객 간 절대 FIFO가 아님. 오래 잠긴 고객의 최장 대기/미처리 수 관측 필요 |
| 고정 지연1초/한 건 | 실행 중첩 없이 다음 후보 재확인 | 처리량은 노드/DB 시간과 고객별 경합에 영향. 매초 모든 고객 처리 보장 아님 |
| 명시적인 최초 원장 유지 | 수신이 보인다는 이유로 초기 자산/원장을 추측 생성하지 않음 | 최초 원장 준비 전 반입은 보류되고 원본/관찰은 계속 저장됨 |
| 검증된 업무별 작은 실행기 구성 | 관심사/종료/오류 경계를 바로 읽을 수 있음 | 수명 설정 코드의 작은 중복을 감수. 공통 추상화보다 현재3개 업무의 명시적 구성을 선택 |
| 같은 활성화 플래그 사용 | 운영 설정을 불필요하게 늘리지 않고 전체 흐름 중단 가능 | 일부 단계만 끄는 별도 운영 제어는 아직 제공하지 않음 |

## 실행과 잠금 경계

일반 앱에서 관찰 노드가 설정되고 bitcoin.processing.enabled=true이면 세 워커가 시작한다. false 또는 노드 미설정이면 시작하지 않는다. `BITCOIN_PROCESSING_ENABLED=false`는 발견·관찰·보류 반입 모두 끈다. 테스트 프로필/격리 실행기는 false를 적용하고 실제 워커 시험만 생성한 객체를 켠다. 봇 활성화와는 별개이며 봇은 계속0명이다.

보류 워커는 미반입/현재 온체인 준비/최신 관찰/지갑 사용false 후보 하나를 선택한다. 다른 업무가 잠근 고객 행은 건너뛰고 다음 실행에서 다시 고려한다. 선택한 고객 행은 같은 금융 TX에서 소유하며 기존 recognize 내부의 사용자 잠금/원본 재검사/멱등/원장/감사 계약은 유지한다.


출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L39), 현재 소스의 부분 발췌.

```java
            // 다른 업무가 잠근 고객은 다음 시도로 미룬다. 독립 고객의 보류 반입이 한 계정의 긴 잠금 때문에 멈추지 않는다.
            var pending=jdbc.queryForList("""
                SELECT r.id FROM bitcoin_deposit_receipts r JOIN users u ON u.id=r.user_id JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
                 JOIN bitcoin_deposit_chain_heads qh ON qh.receipt_id=r.id JOIN bitcoin_deposit_chain_decisions d ON d.id=qh.decision_id
                 JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id
                 JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
                 JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
                 WHERE r.network=? AND a.wallet=? AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1'
                  AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
                  AND NOT EXISTS(SELECT 1 FROM bitcoin_pending_deposits f WHERE f.receipt_id=r.id)
                 ORDER BY r.recorded_at,r.id LIMIT 1 FOR UPDATE OF u SKIP LOCKED
                """,settings.network.id,settings.wallet);
            return pending.isEmpty()?Optional.empty():Optional.of(within((UUID)pending.getFirst().get("id")));
```

FOR UPDATE OF u는 사용자 행을 대상으로 한다. SKIP LOCKED는 다른 업무가 보유한 행을 기다리지 않는다. JDBC 후보 선택과 JPA의 lockById가 같은 Spring 트랜잭션/DB 연결을 사용하는지 실제 동작으로 검증한다. 코어 상태나 오더북에 락을 넣지 않는다.

출처: [BitcoinPendingDepositWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositWorker.java#L28), 현재 소스의 부분 발췌.

```java
    @PostConstruct synchronized void start(){
        // 이 잠금은 플랫폼 워커의 생성/종료에만 사용한다. 거래 엔진 코어의 상태나 명령 처리를 잠그지 않는다.
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-pending-ledger-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error->{
            // 노드 주소·인증·원문 예외를 기록하지 않는다. 계속 실패할 때 매초 같은 경고를 쌓지 않는다.
            if(active&&!failed){failed=true;log.warn("비트코인 보류 원장 반입 중단. 원본을 보존하고 다음 주기에 재확인합니다");}
        });
        scheduler.initialize();active=true;
        // 스케줄러 빈으로 공개하지 않아 기존 주문/고객 확인 작업의 실행 스레드를 빼앗지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
```

전용 풀1개를 한 번 생성한다. 같은 객체의 중복 시작을 막고, 스케줄러 빈으로 공개하지 않아 기존 주문/고객 확인 작업의 기본 실행기 선택을 바꾸지 않는다. synchronized는 플랫폼 실행기 수명에만 사용한다.

출처: [BitcoinPendingDepositWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositWorker.java#L41), 현재 소스의 부분 발췌.

```java
    private void tick(){
        // 검증된 미반입 입금 하나만 처리한다. 이미 확정된 입금은 DB의 불변 근거로 제외한다.
        pending.recognizeNext();
        if(failed){failed=false;log.info("비트코인 보류 원장 실행 재개. 저장된 입금 근거에 따라 계속합니다");}
    }
```

워커의 메모리에 마지막 입금 ID를 저장하지 않는다. 새 객체도 DB의 불변 반입 원본/현재 조건을 읽어 다음 대상을 선택한다. 처리 실패는 다음 주기에 다시 확인하며 운영 로그에 원본 예외/자격 증명을 복사하지 않는다.

출처: [BitcoinPendingDepositWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositWorker.java#L46), 현재 소스의 부분 발췌.

```java
    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler!=null){
            scheduler.shutdown();
            // 인터럽트를 무시하는 DB/외부 코드가 남았으면 같은 객체에서 새 실행기를 만들지 않는다.
            if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
            else log.error("비트코인 보류 원장 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
        }
    }
}
```

종료 요청 뒤 반복 실행을 취소하고 인터럽트/종료 대기10초를 적용한다. 작업이 남으면 같은 객체에서 새 실행기를 만들지 않는다. 금융 기록은 작업 중단에 맞춰 지우지 않고 DB 커밋/롤백 결과를 따른다.

반입 실패 중에는 실패 전환 시 한 번 경고하고 반복 같은 오류를 매초 쌓지 않는다. 큐 실행이 재개되면 재개를 기록한다. 정상 실행/대기가 특정 고객의 금융 해제 승인 또는 엔진 반입 완료를 뜻하지 않는다. 오래 잠긴 고객/반입 실패의 운영 지표·경보는 전역 관측과 연결해야 한다.

## 실제 두 고객 fixture

[scripts/verify_bitcoin.py](../../scripts/verify_bitcoin.py)에 두 번째 수탁 수신 주소/외부 송금0.2BTC를 추가했다. 별도 자금 키가 두 번째 수신을103 블록에 넣으므로 기존104/107/110 높이 계약을 바꾸지 않는다. 실제 시험의 두 고객은 서로 다른 주소/소유자/입금 UUID로 연결한다.

자동 워커 검증과 고객 잠금 선택 검증은 서로 다른 Spring 설정이므로 **서로 다른 PG 서버**에서 실행한다. 사용자 ID/금융 이력의 충돌을 원본 삭제나 불변 트리거 해제로 해결하지 않는다.

## 작성한 테스트

[BitcoinPendingDepositWorkerTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositWorkerTest.java)의5개는 실제 스케줄러의 수명을 검증한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| disabledOrUnconfiguredNeverClaimsWork | 비활성/노드 미설정이면 반입 호출0 |
| blockedObservationHasOneDedicatedTaskWhileOtherSchedulerProgresses | 보류 반입 호출 대기 중 중복 시작에도1개, 다른 스케줄러 진행/종료 인터럽트 |
| unexpectedQueueFailureDoesNotCancelFutureScheduledAttempt | 반입 조회 예외 뒤 반복 예약이 살아 있어 다음 시도 수행 |
| terminatedWorkerCanRestartWithoutDuplicateOldTask | 정상 종료 후 재시작, 이전 작업 중첩 없음 |
| taskIgnoringInterruptPreventsOverlappingReplacementAfterShutdownTimeout | 인터럽트 무시 작업이10초 후에도 남으면 대체 실행기 생성 차단/시험 해제 |

[BitcoinPendingDepositWorkerIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositWorkerIntegrationTest.java)의 `threeWorkersWaitForExplicitOpeningThenResumeBothCustomerPendingBalances`는 실제 Core31.1/소유 PG에서 세 워커를 함께 실행한다. 수신 전 과거 원본을 준비한 뒤 발견/관찰/보류 반입은 워커가 수행한다. 현재 온체인 준비가 있어도 명시적인 최초 원장 전에는 금융 기록0이고, 명시적 반입 후 고객 보류가 생성된다. 종료 후 새3개 객체가 나머지 블록/관찰을 이어가며 기존 UUID/원장 원본 하나와 두 번째 고객0.2BTC를 보존한다. 내부 이동의 보류 반입0/사용 가능 잔액0/엔진 호출0도 확인한다.

[BitcoinPendingSelectionIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPendingSelectionIntegrationTest.java)의 `lockedFirstCustomerCannotStarveAnotherCustomersReadyDeposit`는 첫 고객 행을 별도 실제 TX로 잡은 동안 다른 실제 고객의 반입을3초 안에 완료한다. 첫 고객의 금융 기록은 아직 없고 잠금을 해제하면 다음 선택에서 첫 고객을 반입한다. 금융 원본/트리거를 지우지 않고 실제 고객 행 잠금과 선택 경계를 사용한다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
```

두 번째 명령은 상위 호출에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 값을 제거했다. macOS arm64/Temurin21/Core31.1/PG16에서 BTC/원장170개와 기존 금융 회귀182개가 모두 통과했다. 공통 원장18개를 실제 클래스/함수명으로 대조한 **352회 실행/고유334개**, 실패/오류/제외0개다. 신규7개는 워커 수명5/세 워커 통합1/잠금 선택1이다.

BTC12개 묶음과 금융 회귀는 각각 새 PG 서버를 사용했다. 소유 노드 종료/각 PG 제거·두 제품 JAR 빌드/시험 제외·봇0/peer0/외부 주소 격리를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 실제 운영 프로세스 강제 종료·MAINNET·새 처리량 벤치·OSV 감사는 실행하지 않았다. 종료/재개는 정상 수명과 새 객체/인터럽트 무시 장애의 시험이다.

## 남은 연결

자동 흐름은 현재 보류 원장까지다. 독립 입금 대사, 현재 고객 자격/위험 통제·내용 결합 금융 승인, 엔진 HTTP 반입/WAL 확정/Kafka 금융 수신, 재조직 역거래/영향 처리와 완료 입금 관찰 종료/재가동을 이어야 한다. 보류 항목은 고객이 주문에 쓸 수 없다. 과거 반입 이후 체인이 보류로 바뀌면 확정 사실을 지우지 않고 별도 조치가 필요하다.

세 워커의 CPU/DB/디스크 경쟁과 미처리/최장 대기/실패 경보 예산은 운영에서 검증해야 한다. 수탁 키 관찰 커버리지/DB 관리자 권한 분리/외부 복제·백업은 별도 운영 조건이다. 엔진 코어의 단일 스레드 독점 상태/락 제거는 변경하지 않았다.


## 관련 커밋과 증적

- [구현 ef8180c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/ef8180c4372a14ed31e925e84d3a5c1f0d8a0e6c)
- [격리 실행 증적](../reference/089-automatic-bitcoin-pending-ledger-verification-2026-10-25.json)
