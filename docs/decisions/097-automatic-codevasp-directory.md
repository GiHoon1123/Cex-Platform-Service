# 사업자 목록을 독립 워커에서 갱신하고 실패 후 재개하기

[096 목록 보존/현재성](096-durable-codevasp-directory.md)을 전용 장수 워커에 연결한다. 주기 갱신이 다른 플랫폼 작업을 막지 않으며, 실패를 과거 성공으로 숨기지 않고 다음 시도에서 회복하는 과정과 자동 실행의 테스트 경계를 설명한다.

## 문제와 기존 동작

095/096에서 서명 목록 조회와 영속 원본을 구현했지만 자동 갱신은 없었다. current()의5분 현재성을 유지하려면 명시적인 실행 주체가 필요하다. 기존 공용 @Scheduled 실행기에 외부 호출을 넣으면 주문 전달·고객 확인 작업이 기다릴 수 있다. 매번 스레드를 생성하거나 실패 후 주기 작업을 취소하는 방식도 피해야 한다.

## 기존 제품과 공식 자료의 접근

- [CodeVASP 공식 목록](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/01-VASP-List-Search)은 주기 갱신을 권고한다. 간격2분은 공식 의무/허용 쿼터를 복사한 값이 아닌 자체 정책이다. 실제 계약의 빈도/과금 조건은 확인해야 한다.
- [Spring ThreadPoolTaskScheduler](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html)의 장수 풀·고정 지연·종료 기능을 사용한다. 설치된 Spring7 계열 타입과 기존 [BTC 워커](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinProcessingWorker.java)의 검증된 수명 패턴을 재사용했다. 새 실행기 라이브러리/재시도 프레임워크를 추가하지 않았다.
- [Spring @Profile](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Profile.html)로 test 프로필의 자동 워커 등록 자체를 제외한다. 설정값만 껐다고 가정하지 않고 실제 컨텍스트 등록과 호출 횟수를 검증한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·검토한 대안 |
| --- | --- | --- |
| 전용 ThreadPoolTaskScheduler, 스레드1개 | 느린 Cipher/목록 갱신이 공용 예약 작업을 막지 않음 | 장수 스레드/HttpClient 자원 사용·동일 호스트 CPU/메모리 경쟁은 남음. 엔진 코어에 동기 대기/락을 넣지 않음 |
| 즉시 최초 실행, 완료 후2분 지연 | 시작 후 갱신·같은 실행기 안 비중첩·누적 따라잡기 호출 없음 | 정상 호출 지연이 아주 작다면 하루 약720회 목록 조회+720회 Cipher 요청/프로세스다. 실제 지연/중단에 따라 감소하며 계약 쿼터/비용은 미확인 |
| 주기 실패는 제한 로그 후 다음 시도 | DB/감사 장애 뒤도 재개 가능, 원문/민감 자료 비출력 | 즉시 재시도를 안 하므로 다음 주기까지 업무가 보류될 수 있다. 연속 장애의 현재성은096이503으로 차단 |
| 설정4개가 모두 유효할 때만 실행, processing.enabled로 중지 | 불완전 설정으로 외부 호출 안 함·운영 정지 가능 | 설정 후 일반 앱을 시작하면 외부 접속이 시작된다. 실제 계정/쿼터 확인 전에는 codevasp.processing.enabled=false로 둔다 |
| test 프로필 자동 빈 제외 | 환경 변수로 활성화를 요청해도 시험의 외부 자동 접속 없음 | 시험에서 직접 소유한 객체로 워커 수명을 따로 검증해야 함. 공급사 실제 운영 기동 검증을 대체하지 않음 |
| 종료 인터럽트/최대10초 대기 | 정상 작업을 회수하고 프로세스 종료를 무한 대기하지 않음 | 인터럽트 무시 작업을 강제 종료할 수 없다. 남은 실행기 위에 새 실행기를 생성하지 않음 |

## 구현과 관련 코드

[CodeVaspDirectoryWorker](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryWorker.java)는 서비스의 refresh()만 호출한다. 공급사 오류가 FAILED Report로 저장된 경우와 DB/감사 오류가 예외로 올라오는 경우 모두 주기 자체를 중단하지 않는다. 실패 중 로그는 첫 상태 전환 때만 기록하고, 회복 때 별도 제한 안내를 남긴다. 로그에는 원문 예외/주소/서명/고객 정보가 없다.

시작/종료 synchronized는 Java 플랫폼 실행기 수명만 보호한다. active는 다른 종료 스레드에서 보이도록 volatile이며 failed는 전용 실행 스레드가 관리한다. Rust 거래 상태/오더북/잔액과 아무 락도 공유하지 않는다. 여러 플랫폼 프로세스를 띄운 경우 각 프로세스는 자기 주기를 갖는다.096의 DB 시작 잠금/미완료 임대가 동시 중복을 제한하지만 전체 클러스터를2분당 정확히1회로 제한하는 리더 선출은 아니다. 초기 단일 플랫폼 배포 기준이며 확장 시 실제 공급사 쿼터에 맞춰 실행 주체를 지정해야 한다.

고정 지연2분은 제품 생성자에서 지정한다. 패키지 내부의 별도 Duration 생성자는 실제 시간 시험을 짧게 실행하기 위한 것이며 외부 설정/API로 주기를 노출하지 않는다. 일반 앱에 설정된 주체만 자동 접속하며 전용 OperatorAdminApplication은 필요한 두 업무 빈만 @Import하므로 이 워커를 탐색/실행하지 않는다.

### 실행 조건

- `codevasp.api-url`, `codevasp.cipher-url`, `codevasp.entity-id`, `codevasp.public-key`: [095 설정 계약](095-codevasp-cipher-directory-client.md). 실제 등록된 공급사 원점/본인 공개키와 비공개 Cipher 접근 조건이 필요하다. 메인넷/실제 공급사 기본 주소는 없다.
- `codevasp.processing.enabled`: 기본 true, 운영 중지 시 false. test 프로필은 값과 무관하게 자동 등록 제외.
- 설정 없음: 워커는 실행기를 생성하지 않는다. 현재 로컬의 실제 공급사 계정/이미지를 추가·배포하거나 외부 접속을 실행하지 않았다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDirectoryWorker.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryWorker.java#L16), 현재 소스의 부분 발췌.

```java
@Component
// 테스트 프로필에서는 환경 변수로 활성화를 요청해도 자동 워커를 만들지 않는다. 수명 시험은 직접 소유한 객체만 실행한다.
@Profile("!test")
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspDirectoryWorker {
```

@Profile("!test")는 test가 활성화되지 않은 컨텍스트에서만 자동 빈을 등록한다. 라이프사이클 어노테이션이 붙은 객체를 TypeScript에서 단순 new 하는 것과 달리, Spring은 등록한 빈의 PostConstruct/PreDestroy를 호출한다. 직접 만든 테스트 객체는 시작/종료를 명시적으로 소유한다.

출처: [CodeVaspDirectoryWorker.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryWorker.java#L48), 현재 소스의 부분 발췌.

```java
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("codevasp-directory-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        // 감사/DB 저장 실패도 다음 주기를 취소하지 않는다. 원본은 서비스가 이미 보존한 상태로 둔다.
        scheduler.setErrorHandler(error->markFailed());
        scheduler.initialize();
        active=true;
        // 지연은 앞선 실행 완료부터 센다. 느린 호출 중 같은 워커에 새 호출을 계속 쌓지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,interval);
    }

```

풀 크기1과 scheduleWithFixedDelay가 같은 워커의 비중첩을 만든다. this::tick은 실행 시 호출할 메서드 참조이며 새 주문마다 스레드를 생성하지 않는다. ErrorHandler는 실패 후 주기 취소를 막지만 업무 결과를 성공으로 바꾸지 않는다. 서비스가 영속 상태를 보존한다.

출처: [CodeVaspDirectoryWorker.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryWorker.java#L80), 현재 소스의 부분 발췌.

```java
    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler==null)return;
        scheduler.shutdown();
        // 종료 제한을 무시한 작업이 남으면 새 실행기를 겹쳐 만들지 않는다. 남은 작업의 완료가 현재성을 되돌릴 수는 없다.
        if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
        else log.error("사업자 목록 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
    }
}
```

종료는 새 실행을 막고 기존 실행기에 중단을 요청한다. 제한 시간 안에 종료하지 못한 실행기를 null로 지워 버리지 않으므로 같은 객체를 다시 시작해도 중복 워커가 생기지 않는다. 플랫폼 DB 시작/결과 계약이 프로세스 재시작의 근거이며 워커 메모리는 복구 원본이 아니다.

## 작성한 테스트

[CodeVaspDirectoryWorkerTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspDirectoryWorkerTest.java)8개를 새로 작성했다. HTTP 공급사는 호출하지 않고 실제 Spring 실행기/컨텍스트를 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|disabledAndUnconfiguredCannotCreateAutomaticAttempts | 비활성/미설정은 갱신0회 |
|oneDedicatedThreadDoesNotOverlapOrBlockOtherScheduler | 느린 실행을 정지, 중복 start 후 호출1회/전용 이름, 다른 스케줄러 진행·종료 인터럽트 |
|fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks | 첫 호출을 주기보다 길게 정지해도1회, 해제 직후 따라잡기 호출 없음·다음 주기 진행 |
|storedFailureAndThrownPersistenceFailureDoNotCancelFutureAttempts | 저장된FAILED→저장 예외→SUCCESS 순서로 세 번째 호출까지 진행 |
|stoppedWorkerCanRestartAfterOldExecutorTerminates | 정상 종료 뒤 재시작하면 새 갱신 진행 |
|taskIgnoringShutdownCannotStartOverlappingReplacement | 종료 신호 무시 시험은10초 제한 뒤에도 호출1회/대체 실행기 없음, finally에서 반드시 해제 |
|testProfileCannotAutomaticallyStartEvenWhenConfiguredAndEnabled | 설정/활성 true인 test 컨텍스트에도 자동 빈0/갱신0 |
|productionContextHonorsFlagAndClosesItsAutomaticallyStartedExecutor | 일반 컨텍스트 비활성 시 호출0, 활성 시 자동1회·컨텍스트 종료 후 전용 스레드 없음 |

[CodeVaspDirectoryIntegrationTest.automaticWorkerPersistsFailureAndRecoversWithNewAttempt](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspDirectoryIntegrationTest.java)의 실제 PG 통합1개를 추가했다. 첫 공급사 실패가 FAILED로 저장되고 두 번째 호출 중 current503이며, 두 번째 성공 후 새 현재 자료와 원본 결과2개를 확인한다. 외부 호출 시 DB TX가 없음을 대역 안에서 검사한다. 짧은 주기는 시험 객체 내부에서만 사용한다.

096의 기존 PG13개·095 설정/실제 HTTP 대역19개도 재실행했다. 테스트 객체와 컨테이너는 각 시험/실행기가 소유·종료하며 봇0/실제 Kafka 소비 없음이다.

## 실행 명령과 결과

- Java21/격리 PostgreSQL16, `python3 scripts/verify_codevasp_directory.py`: 신규9+기존32=고유41개, 실패/오류/제외0. 최초 실행40개 통과 뒤 새 실제 PG 워커 회복 시나리오를 추가해 최종41개를 다시 실행했다. 두 결과를 합쳐 고유81개로 세지 않는다.
- `bootJar operatorAdminJar` 성공. 두 JAR에 워커 클래스 포함·테스트 코드 제외 확인. 실행 소유 DB 컨테이너 제거 확인.
- `.local/cycle97-proof`의 최종 XML/결과/로그/빌드 확인을 보존한다. deprecated/unchecked/JVM CDS 경고는 기존대로 남는다. `git diff --check` 통과.
- 금융182/보안41/BTC 전체/Core/Rust/Kafka/브라우저는 이번에 다시 실행하지 않았다. 새 빈의 실제 자동 기동/제외와 서비스 영속 연결은 위 선택 시험 범위다.

## 남은 한계

실제 공급사 가입/인증키·Cipher 이미지/메모리·요청 쿼터·과금·실제 샌드박스/라이브 기동은 미확인이다. 이번 자동화는 기술 사업자 목록만 갱신하며 송수신인 정보 교환/상대 정책 승인/자금 이동은 하지 않는다. 서버 상태는 정책상 거래 허용과 다르다. DB/감사 장애 뒤 시작만 보존된 시도는096의2분 임대가 지난 뒤 새 시도에서 회복한다. 실패 코드가 기록된 조회는 다음 주기 시작을 금지하지 않는다. 장기 동시 프로세스의 호출 수/보존 비용은 운영 실행 주체와 계약에 맞춰 검증해야 한다.


## 관련 커밋과 증적

- [구현 43484cd](https://github.com/GiHoon1123/Cex-Platform-Service/commit/43484cde7677d9a3cdd40d6730aec9134858e7b4)
- [격리 실행 증적](../reference/097-automatic-codevasp-directory-verification-2026-10-25.json)
