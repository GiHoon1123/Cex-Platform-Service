# 전용 워커로 블록 발견과 고객 입금 식별 이어가기

등록 주소의 블록 조회와 고객 입금 ID 생성을 전용 장수 워커로 연결한다. 저장된 원본/처리 완료를 기준으로 자동 재개하는 방법과 기존 주문·KYC 스케줄러/엔진 코어에 영향을 주지 않는 실행 경계를 설명한다.

## 문제와 기존 동작

080/081의 내부 업무는 실제 노드/DB에서 동작하지만 호출자가 `scanNext`와 `projectNext`를 실행해야 했다. 일반 서버 기동 후에도 발견을 이어가려면 수명이 긴 실행 주체가 필요하다. 외부 노드 RPC를 기본 공용 스케줄러에 추가하면 주문 재전송 같은 다른 작업이 대기할 수 있고, 매번 스레드를 새로 만들면 수명/종료가 복잡해진다.

## 기존 제품과 공식 자료의 접근

Spring의 [TaskScheduler/고정 지연](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)은 이전 실행이 끝난 뒤 다음 실행을 예약한다. 이미 사용 중인 `ThreadPoolTaskScheduler`로 단일 전용 스레드를 구성하고 종료/예외 처리를 맡긴다. 직접 주기 루프/스레드 생성 체계를 만들거나 새 패키지를 추가하지 않았다.

공개 설명 페이지는 조회 시7.0.10 문서였으며 실제 잠금 파일의 Spring context는7.0.9다. 설치된7.0.9 JAR의 `javap`로 Duration 고정 지연·종료 대기·실행 중단 API를 확인하고 실제 비동기 시험으로 동작을 검증했다. 다른 버전의 문서만 보고 런타임 기능을 추측하지 않는다. 프로젝트의 기존 [KycReconciliationWorker](../../src/main/java/dustin/cex/domains/kyc/KycReconciliationWorker.java)와 같은 전용 스케줄러 분리 방식을 적용한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용/대안과 한계 |
| --- | --- | --- |
| 전용 단일 스레드/고정 지연1초 | 같은 워커의 실행 중첩 없이 기본 스케줄러의 다른 업무 분리 | 플랫폼 스레드 하나와 노드/DB 자원 사용. 전체 과거 체인은 RPC 호출 수가 많으므로 초기 동기화 비용 측정 필요 |
| 미처리 입금 식별 후 다음 블록 조회 | 조회 성공 뒤 종료됐어도 식별을 먼저 재개, 식별 오류에서 더 전진하지 않음 | 한 단계 실패면 그 워커의 다음 조회 대기. 재조직/내용 충돌은 자동 덮어쓰기 대신 중단/재확인 |
| DB 원본/완료 상태로 재개 | 프로세스 메모리의 임의 순번을 신뢰하지 않음 | DB 가용성과 짧은 확정 잠금에 의존. 여러 앱 프로세스가 뜨면 RPC 중복 조회 비용은 발생 가능하며 기존 CAS/유일 키가 금융 중복을 막음 |
| 실패 경고는 전환 시, 성공하면 복구 로그 | 비밀/노드 원문 유출과 같은 경고의 매초 누적 감소 | 로그만으로 경보/지표 체계가 완성되지는 않음. 전역 관측/알림은 B21에서 검토 |
| 운영 활성화와 테스트 격리 구분 | 노드 설정 없으면 스레드0, 실제 노드 시험도 지정한 워커만 실행 | 설정을 명시적으로 관리해야 함. 봇 활성화와 무관하며 봇은 계속0명 |

## 실행과 종료

일반 CexApplication에 노드 접속 설정이 모두 있고 `bitcoin.processing.enabled=true`이면 자동 시작한다. 값의 기본은true이며 노드가 미설정이면 시작하지 않는다. 관찰 전용 지갑/체인/노드 검증은 각 업무가 유지한다. `BITCOIN_PROCESSING_ENABLED=false` 또는 `--bitcoin.processing.enabled=false`로 자동 처리를 끌 수 있다.

기본1초 지연은 이전 실행 완료 후의 대기이며 RPC 시간까지 합쳐1초 안에 처리한다는 보장이 아니다. 한 번의 실행은 미처리 완료 블록 하나를 입금 ID로 연결한 다음 다음 블록 하나를 읽는다. 새로 완료한 블록의 식별은 다음 실행이 처리한다. 뒤 단계가 지연돼도 미처리 원본은 DB에 남는다.


출처: [BitcoinProcessingWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorker.java#L29), 현재 소스의 부분 발췌.

```java
    @PostConstruct synchronized void start(){
        // 이 잠금은 플랫폼 워커의 생성/종료에만 사용한다. 거래 엔진 코어의 상태나 명령 처리를 잠그지 않는다.
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-processing-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error->{
            // 노드 주소·인증·원문 예외를 기록하지 않는다. 계속 실패할 때 매초 같은 경고를 쌓지 않는다.
            if(active&&!failed){failed=true;log.warn("비트코인 입금 발견 중단. 원본을 보존하고 다음 주기에 재확인합니다");}
        });
        scheduler.initialize();active=true;
        // 스케줄러 빈으로 공개하지 않아 기존 주문/고객 확인 작업의 실행 스레드를 빼앗지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
```

`@PostConstruct`는 필요한 빈과 스키마가 준비된 뒤 한 번 호출된다. `synchronized`는 플랫폼 워커의 생성/종료 중복만 제어하며 엔진 코어의 거래 상태와 무관하다. 풀 크기1과 고정 지연으로 동일 워커의 중첩 실행을 막고, 스케줄러 빈으로 공개하지 않아 다른 작업의 기본 스케줄러 선택을 바꾸지 않는다.

출처: [BitcoinProcessingWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorker.java#L42), 현재 소스의 부분 발췌.

```java
    private void tick(){
        // 먼저 남은 식별을 처리한다. 여기서 실패하면 추가 블록을 읽기 전에 원인을 해결해야 한다.
        receipts.projectNext();if(!active)return;scans.scanNext();
        if(failed){failed=false;log.info("비트코인 입금 발견 재확인 성공. 저장된 진행 위치에서 계속합니다");}
    }
    @PreDestroy synchronized void stop(){
```

미처리 입금 식별이 실패하면 다음 블록 조회를 호출하지 않는다. 성공 여부는 원본/처리 완료의 커밋으로 결정한다. `volatile active`는 종료 요청을 워커 스레드에 보이게 하며, 중간 단계 뒤 종료됐으면 새 RPC를 시작하지 않도록 한다. 원장/엔진 입금 명령은 호출하지 않는다.

출처: [BitcoinProcessingWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorker.java#L47), 현재 소스의 부분 발췌.

```java
    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler!=null){
            scheduler.shutdown();
            // 인터럽트를 무시하는 DB/외부 코드가 남았으면 같은 객체에서 새 실행기를 만들지 않는다.
            if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
            else log.error("비트코인 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
```

종료는 새 반복을 중단하고 실행 중인 작업에 인터럽트를 요청하며 최대10초 종료를 기다린다. 기존 RPC는 인터럽트 시 요청을 취소하고 예외를 반환한다. DB 커밋 전 실패는 전체 롤백되고 이미 커밋한 원본은 남는다. DB 자체가 응답하지 않는 상황을 자바 인터럽트만으로 강제 해결한다고 주장하지 않는다. 종료 대기 후에도 작업이 남으면 같은 객체의 새 실행기 생성을 차단하고 경고한다.

테스트 프로필은 자동 워커를false로 고정하고 격리 실행기도 해당 환경값을 명시한다. 실제 워커 시험만 수동 생성한 한정 객체를 켜고 finally에서 종료한다. 현재 로컬 개발 서비스/실제 MAINNET/새 영속 노드를 기동하거나 배포하지 않았다. 운영자 관리용 진입점은 명시적 Import만 사용하므로 이 워커를 자동 구성하지 않는다.

## 작성한 테스트

[BitcoinProcessingWorkerTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorkerTest.java)의6개는 실제 Spring 스케줄러를 동작시킨다. 금융 업무만 대역으로 격리하고 실행 수명/동시성과 장애 재시도를 확인한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| disabledOrUnconfiguredNeverCreatesTask | 비활성/노드 미설정이면 업무 호출0 |
| blockedProjectionUsesOneDedicatedThreadAndDoesNotOverlapOrBlockOtherScheduler | 입금 단계 대기 중 중복 시작에도 호출1, 별도 주기 작업은 진행, 종료 인터럽트/추가 조회0 |
| transientNodeFailureDoesNotCancelFutureTicks | 첫 노드 실패 뒤 다음 주기의 재실행 성공 |
| projectionFailurePreventsAdditionalScanUntilRetrySucceeds | 입금 식별 실패 직후 추가 조회0, 재시도 성공 후 진행 |
| stoppedWorkerCanStartAgainWithoutParallelOldTask | 종료한 워커의 재시작이 새 실행을 수행하며 기존 작업을 중첩하지 않음 |
| unresponsiveTaskPreventsSecondExecutorAfterShutdownTimeout | 인터럽트 무시 작업이10초 후에도 남으면 같은 객체의 새 실행기 생성 차단, 시험 종료 시 해제/정리 |

[BitcoinProcessingWorkerIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorkerIntegrationTest.java)의 `actualWorkerDiscoversCustomerReceiptAndFreshWorkerResumesWithoutDuplicateId`는 실제 Core31.1/소유 PG에서 수신 이전 과거 원본을 준비한 뒤 실제 워커가 수신 블록/고객 입금을 생성하게 한다. 종료 후 새 워커 객체가 나머지 블록과 미처리 식별을 완료하고 같은 UUID/0.12345678BTC를 유지한다. 완료 블록/처리 표105개와 원장/잔액0을 확인한다. 이는 정상 종료/새 객체 재개 시험이며 운영 프로세스 강제 종료/실제 자동 재조직 복구를 검증했다는 뜻이 아니다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1 regtest/업무별 PG16에서 **신규7개+기존86개=고유93개 통과**, 실패/오류/제외0개다. addresses53/observations11/scans12/receipts10/worker7은 각각 별도 PG 서버를 사용했다. 제품 bootJar/operatorAdminJar 빌드, 소유 노드 종료/PG5개 제거를 확인했다. 봇0/peer0/외부 주소 격리를 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다.

중간92개 실행과 종료 대기 한계를 보강한 최종93개 실행은 합산하지 않는다. 실제 MAINNET·블록 처리량/지연/초기 동기화 성능·OSV 전체 감사를 새로 수행하지 않았다. 설치된 Awaitility4.2를 사용했고 의존성/잠금 파일 변경은 없다.

## 남은 범위

자동화한 것은 발견/안정 입금 ID까지다. 현재 거래 관찰의 반복 확인, 확정/coinbase 성숙도·보류 판단, 재조직의 공통 조상 복구/금융 보상, 고객 원장/엔진 HTTP/Kafka 연동은 후속이다. 기존 080의 재조직409는 자동 복구 성공을 뜻하지 않으며 다음 주기에도 원본을 보존한 채 재확인한다. 엔진 코어에 스케줄러/조회/락을 넣지 않았다.


## 관련 커밋과 증적

- [구현 248fdea](https://github.com/GiHoon1123/Cex-Platform-Service/commit/248fdea79239a9839cb14a065952aefe643ade4d)
- [격리 실행 증적](../reference/082-bitcoin-discovery-processing-worker-verification-2026-10-25.json)
