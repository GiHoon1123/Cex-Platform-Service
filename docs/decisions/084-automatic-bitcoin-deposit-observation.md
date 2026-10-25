# 독립 워커로 고객 입금 거래 관찰 자동 이어가기

블록 발견과 고객 입금 ID 생성에 이어, 영속 임대 큐의 거래 재확인도 독립 워커로 자동 실행한다. 느린 블록 조회가 입금 관찰을 막지 않고, 종료 후에도 불변 원본과 작업 기한에서 이어가는 실행 경계를 설명한다.

## 문제와 기존 동작

083은 재확인 작업의 기한/임대/노드 조회/늦은 결과 차단을 구현했지만 호출자가 refreshDue를 실행해야 했다. 이를 블록 조회와 같은 실행 스레드에 넣으면 많은 주소 묶음이나 과거 블록 읽기 때문에 이미 발견한 입금의 현재 상태 확인도 늦어진다.

## 기존 제품과 공식 자료의 접근

이미 설치된 Spring7.0.9의 ThreadPoolTaskScheduler와 [고정 지연/종료 계약](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)을 사용한다. 082에서 실제 수명/오류/종료 시험으로 검증한 전용 스케줄러 방식을 관찰 업무에도 적용했다. 스케줄러 빈을 기본 실행기로 공개하지 않고 수명이 긴 단일 풀을 내부에 둔다. 새 추상 워커 체계/스레드별 런타임/패키지를 도입하지 않았다.

## 선택과 트레이드오프

| 선택 | 얻는 보장 | 비용·한계 |
| --- | --- | --- |
| 발견 워커와 관찰 워커 각1개 | 오래 걸리는 블록 조회와 기한 도래 입금 관찰을 독립 진행 | 플랫폼 스레드/DB/노드 자원 추가, CPU/디스크 경쟁 자체를 없애지는 못함 |
| 기한 큐 한 건/고정 지연1초 | 한 실행기의 중첩 방지·반복 실행, 실패 거래는 자기 다음 기한까지 대기 | 관찰 완료 주기는 큐 길이/RPC 시간에 따라 달라짐. 매초 모든 입금을 확인한다는 뜻 아님 |
| 두 워커를 같은 실제 활성화 플래그로 제어 | 설정을 불필요하게 늘리지 않고 전체 입금 발견을 중지 가능 | 일부 업무만 운영 중지하는 제어는 아직 제공하지 않음. 봇 설정과는 독립 |
| 종료 인터럽트/10초 대기·남은 작업 있으면 재생성 금지 | 같은 객체의 중복 실행기 방지 | DB가 인터럽트에 응답하지 않으면 경고/운영 확인 필요, 강제 프로세스 종료 검증 아님 |

## 구현과 실행 조건

`BitcoinProcessingWorker`는 기존처럼 미처리 블록의 입금 ID 식별→다음 블록 조회를 수행한다. `BitcoinDepositObservationWorker`는 별도의 bitcoin-observation 스레드에서 기한 도래 거래 한 건의 refreshDue를 실행한다. 입금/관찰의 금융 근거는 계속 DB 원본이며 워커 메모리는 권위가 아니다.

두 워커 모두 노드 설정이 없거나 bitcoin.processing.enabled=false이면 시작하지 않는다. 일반 서버의 설정 기본값은true다. `BITCOIN_PROCESSING_ENABLED=false` 또는 `--bitcoin.processing.enabled=false`로 자동 발견/식별/관찰을 모두 끈다. 테스트 프로필/격리 실행기는 false로 고정하고 해당 시험에서 명시적으로 만든 워커만 실행한다. 봇은 계속0명이다.


출처: [BitcoinDepositObservationWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationWorker.java#L28), 현재 소스의 부분 발췌.

```java
    @PostConstruct synchronized void start(){
        // 이 잠금은 플랫폼 워커의 생성/종료에만 사용한다. 거래 엔진 코어의 상태나 명령 처리를 잠그지 않는다.
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-observation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error->{
            // 노드 주소·인증·원문 예외를 기록하지 않는다. 계속 실패할 때 매초 같은 경고를 쌓지 않는다.
            if(active&&!failed){failed=true;log.warn("비트코인 입금 관찰 중단. 임대와 원본을 보존하고 다음 주기에 재확인합니다");}
        });
        scheduler.initialize();active=true;
        // 스케줄러 빈으로 공개하지 않아 기존 주문/고객 확인 작업의 실행 스레드를 빼앗지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
```

플랫폼 워커의 생성/종료를 보호하는 synchronized와 엔진의 단일 스레드 독점 상태는 서로 다른 경계다. 여기에는 Rust 코어/주문 매칭/공유 상태가 없다. 풀1개를 장수 객체로 만들고 요청마다 새 스레드를 만들지 않는다.

출처: [BitcoinDepositObservationWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationWorker.java#L41), 현재 소스의 부분 발췌.

```java
    private void tick(){
        // 기한이 된 거래 하나만 처리한다. 다른 거래의 실패는 큐가 기한을 분리해 독점을 막는다.
        jobs.refreshDue();
        if(failed){failed=false;log.info("비트코인 입금 관찰 재실행 성공. 저장된 작업 기한에 따라 계속합니다");}
    }
```

반복 호출은 기한이 온 작업 한 개에 한정된다. 오류 분류/임대 인수/재시도 시각/늦은 결과 차단은 083의 DB 업무가 담당한다. 워커의 실행 재개 로그가 해당 입금의 금융 확정을 뜻하지는 않는다.

출처: [BitcoinDepositObservationWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationWorker.java#L46), 현재 소스의 부분 발췌.

```java
    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler!=null){
            scheduler.shutdown();
            // 인터럽트를 무시하는 DB/외부 코드가 남았으면 같은 객체에서 새 실행기를 만들지 않는다.
            if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
            else log.error("비트코인 관찰 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
        }
    }
}
```

종료가 늦어도 기존 실행기를 참조로 보존해 같은 객체의 재생성/중첩을 막는다. 작업이 정상 종료됐다면 새 실행기를 시작할 수 있다. 각 워커의 종료 훅은 자신의 작업만 중단하며 다른 스케줄러를 종료하지 않는다.

고객 잔액/원장/엔진 HTTP 명령은 호출하지 않는다. 관찰 지갑의 현재 읽기와 생성한 입금의 불변 소유/금액을 보존한다. 운영자 관리용 진입점은 이 컴포넌트 탐색에 참여하지 않는다. 기존 로컬 개발 서비스/영속 노드/MAINNET을 기동하거나 배포하지 않았다.

## 작성한 테스트

[BitcoinDepositObservationWorkerTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationWorkerTest.java)의5개는 실제 Spring 스케줄러를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| disabledOrUnconfiguredNeverClaimsWork | 비활성/노드 미설정 시 작업 호출0 |
| blockedObservationHasOneDedicatedTaskWhileOtherSchedulerProgresses | 관찰 대기 중 중복 시작에도 호출1, 다른 주기 작업 진행/종료 인터럽트 |
| unexpectedQueueFailureDoesNotCancelFutureScheduledAttempt | 큐 예외가 반복 예약을 영구 취소하지 않음 |
| terminatedWorkerCanRestartWithoutDuplicateOldTask | 정상 종료 후 새 실행을 이어감 |
| taskIgnoringInterruptPreventsOverlappingReplacementAfterShutdownTimeout | 인터럽트 무시 작업이10초 후에도 남으면 대체 실행기 생성 금지, 마지막에 해제/정리 |

[BitcoinDepositObservationWorkerIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositObservationWorkerIntegrationTest.java)의 `independentWorkersDiscoverObserveAndResumeActualCustomerDepositWithoutCredit`는 실제 Core31.1/소유 PG에서 두 워커를 동시에 실행한다. 수신 직전 과거 블록은 동기로 준비하고, 수신 블록/입금 ID/작업/실제 CONFIRMED 관찰은 두 워커가 만든다. 둘을 종료한 뒤 새 두 객체가 남은 작업/블록을 완료하고 같은 UUID/0.12345678BTC를 유지하는지 확인한다. 완료 블록 식별105개, 모든 작업의 성공 관찰, 원장/잔액0과 엔진 호출0을 확인한다.

이는 정상 종료/새 객체 재개 시험이며 운영 JVM 강제 종료·실제 재조직 자동 복구·자산 지급 검증으로 표현하지 않는다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/묶음별 PG16에서 **신규6개+기존104개=고유110개 통과**, 실패/오류/제외0개다. addresses53/observations11/scans12/receipts10/worker7/jobs11/observation-worker6은 각각 별도 PG 서버를 사용했다. bootJar/operatorAdminJar 빌드와 소유 노드 종료/PG7개 제거를 확인했다. 봇0/peer0/외부 주소 격리를 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있고 의존성 추가/MAINNET/성능/OSV 감사는 없다.

## 남은 범위

자동 발견/고객 입금 ID/현재 거래 관찰까지 연결했다. 관찰의 출력·금액·소유 검증과 확정/coinbase 성숙도·위험 보류 판단, 원장·엔진 HTTP/Kafka 금융 반영, 재조직 공통 조상/금융 복구는 후속이다. 완료된 과거 입금의 반복 관찰 종료와 재조직 재가동도 그 금융 상태와 함께 정해야 한다. 엔진 코어에 락/디스크/RPC 대기를 추가하지 않았다.


## 관련 커밋과 증적

- [구현 247f628](https://github.com/GiHoon1123/Cex-Platform-Service/commit/247f628efcaa8b128711dcb9d28786aa0bda630f)
- [격리 실행 증적](../reference/084-automatic-bitcoin-deposit-observation-verification-2026-10-25.json)
