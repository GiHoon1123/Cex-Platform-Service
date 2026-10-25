# 준비된 입금 탐지 입력 사이의 불필요한 대기 없애기

준비된 입금 탐지 작업 사이의 불필요한1초 대기를 제거한다. 같은 전용 스레드가 한 입력씩 독립적으로 확정하고, 빈 큐나 실패 뒤에만 기존 지연을 적용한다. 중간 실패의 롤백 범위와 종료 경계는 유지한다.

## 문제와 기존 동작

117은 원장 순번마다 분석해 중간 시간 창을 보존했다. 하지만 워커가 각 입력 뒤1초를 쉬어 실제 계산/DB 처리 시간이 짧아도 단일 워커 처리량은 초당1건 미만으로 제한됐다. 적체를 해소하려고 최신 입력만 처리하면 과거 신호가 다시 누락되므로 순서 보존과 불필요한 대기 제거를 함께 적용한다.

새 경로는 기존 장수 전용 스레드에서 processNext를 순서대로 호출한다. 반환 값이 있으면 다음 입력을 처리하고, 없으면 반복을 마쳐1초 고정 지연을 적용한다. 오류도 기존 스케줄러의 예외 처리 뒤1초를 기다린다. 모든 입력을 한 트랜잭션으로 합치지 않는다.

## 기존 제품·공식 자료의 접근

2026-10-25 [Spring7.0.10 ThreadPoolTaskScheduler](https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html)를 확인했다. 이 클래스의 작업은 별도 무제한 작업 스레드가 아니라 설정한 스케줄러 스레드에서 실행되고, fixed delay는 앞선 Runnable 완료 뒤 다음 시작까지의 간격이다.

따라서 설치된 라이브러리의 의미를 유지하면서 Runnable 안에서 준비된 작업을 처리하고 유휴 상태에서 반환한다. 별도 스레드 풀·런타임·패키지·즉시 재귀 스케줄러를 추가하지 않았다. [117 순차 재생](117-ordered-deposit-monitoring-replay.md)의 원장 순번/임대/짧은 TX 계약과 [118 조사 원본](118-verified-monitoring-evidence-and-analyst-observations.md)은 변경하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 같은 전용 스레드에서 준비된 입력을 연속 처리 | 입력 간 인위적인1초 상한을 제거하며 순서를 유지 | 준비된 작업이 계속 있으면 DB·CPU 사용도 계속된다. 같은 EC2의 자원 경합은 남는다 |
| 한 processNext마다 기존 독립 TX | 중간 실패가 이전 확정 결과를 되돌리지 않는다 | 입력별 커밋/감사 비용이 남는다. 전체 적체를 한 TX로 묶지 않는다 |
| 빈 큐/실패 때 기존1초 지연 | 유휴 폴링·실패 재시도의 뜨거운 반복 방지 | 빈 큐 확인 직후 들어온 입력은 다음 폴링까지 기다릴 수 있다 |
| 매 입력 전 종료 상태 확인 | 종료 요청 뒤 준비된 작업 전체를 계속 소비하지 않는다 | 진행 중 호출과 종료가 겹칠 수 있고 JDBC 인터럽트가 즉시 취소를 보장하지 않는다 |

추측에 근거한 배치 크기·처리 시간 예산·추가 설정값을 만들지 않았다. 전용 워커가 처리할 실제 업무를 계속 수행하고, 종료 플래그·유휴/실패 경계에서 멈춘다.10초 종료 대기 후에도 기존 실행이 남으면 대체 실행기 생성은 계속 차단한다.

이는 탐지 워커의 불필요한 대기 제거다. 앞단 블록 발견·관찰·보류 반입 주기는 바꾸지 않았고 전체 입금 파이프라인/엔진의 처리량을 측정하거나 보장한 결과가 아니다. 실제 지속 부하·자원 예산·적체 알림/우선순위는 후속 운영 검증이다.

## 구현과 관련 코드·문법

출처: [BitcoinDepositMonitoringWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorker.java#L39), 현재 소스의 부분 발췌.

```java
        while(active){
            var completed=service.processNext();
            if(completed.isEmpty())return;
            // 실패한 작업의 임대 만료를 기다리는 빈 폴링만으로 업무 복구를 선언하지 않는다.
            if(failed){failed=false;log.info("입금 모니터링 처리 재개");}
        }
```

while은 준비된 결과가 있을 때만 이어진다. Optional.empty면 return으로 이번 Runnable을 끝내 스케줄러의 고정 지연을 적용한다. processNext 안의 입력별 TX는 그대로라 이전 확정 결과를 한 묶음으로 롤백하지 않는다. 실패 뒤 빈 폴링은 처리 재개로 기록하지 않는다.

출처: [BitcoinDepositMonitoringWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorker.java#L25), 현재 소스의 부분 발췌.

```java
    private volatile boolean active;
    private boolean failed;
```

volatile은 종료를 요청하는 스레드의 active 변경을 작업 스레드가 볼 수 있게 한다. 다중 필드 전체를 원자화하거나 DB 호출을 즉시 취소하는 문법이 아니다. TypeScript의 일반 변수와 달리 실제 스레드 간 가시성 계약이며, 사용자 플래그를 JavaScript Worker와 공유하려면 별도 공유 메모리/메시지 계약이 필요하다.

출처: [DepositMonitoringDrainIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringDrainIntegrationTest.java#L39), 현재 소스의 부분 발췌.

```java
        await().atMost(Duration.ofSeconds(5)).until(()->count()==10);
        var original=jdbc.queryForList("SELECT input_cursor FROM deposit_monitoring_inputs WHERE user_id=? ORDER BY input_cursor",Long.class,customer.getId());
        var analyzed=jdbc.queryForList("SELECT input_cursor FROM deposit_monitoring_assessments WHERE user_id=? ORDER BY input_cursor",Long.class,customer.getId());assertEquals(original,analyzed);
        assertTrue(jdbc.queryForObject("SELECT target_cursor=observed_cursor AND lease_id IS NULL FROM deposit_monitoring_jobs WHERE user_id=?",Boolean.class,customer.getId()));
```

실제 PG·원장·감사를 쓰는 워커가 대기한10입금을 처리하고, 원래 입력 순번 목록과 확정 분석 순번 목록이 정확히 같은지 비교한다.5초는 이전 구현의 건별1초 대기를 구분하는 회귀 여유 시간이며 생산 처리량 벤치마크 수치가 아니다.

## 작성한 테스트와 기대 결과

[BitcoinDepositMonitoringWorkerTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorkerTest.java)에3개를 추가했다.

| 함수 | 검증·기대 결과 |
| --- | --- |
| readyInputsAreDrainedBeforeTheIdleDelay | 실제 전용 스케줄러에서5개 준비 결과 뒤 빈 큐까지 건별1초 대기 없이 도달, 같은 스레드 유지 |
| shutdownStopsTheDrainBeforeTakingAnotherInput | 첫 입력 처리 중 종료를 요청하면 확정 결과 반환 뒤 두 번째 입력을 가져오지 않음 |
| failureDuringDrainKeepsTheIdleDelayBeforeRetry | 준비 입력2개 뒤 예외 발생, 다음 시도는 실패 뒤1초 이상 지나 실행 |

기존9개도 실행했다. 비활성/미설정·비중첩/중복 기동·빈 큐 완료 뒤 지연·실패 후 계속 실행·정상 재기동·인터럽트 무시 시 대체 금지·test 프로필 제외·생산 플래그/자원 종료·거짓 복구 로그/원문 비노출을 유지한다. 기존 복구 로그 대역은 성공1개 뒤 빈 큐로 끝나도록 바꿔 무한 성공 대역으로 시험이 뜨겁게 돌지 않게 했다.

[DepositMonitoringDrainIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringDrainIntegrationTest.java)에 실제 PG 회귀2개를 작성했다.

| 함수 | 검증·기대 결과 |
| --- | --- |
| actualWorkerDrainsTenReadyInputsWithoutPerInputIdleDelay | 실제 수탁 원장10개와 영속 예약을 생산 프로필 워커에 연결. 분석10개/필수 감사10개·순번 완전 일치·목표/처리 위치 일치·임대 해제 |
| midDrainFailureKeepsConfirmedInputsAndResumesFromTheFailedPosition | 세 번째 분석의 감사 실패. 앞2개 UUID/현재 위치는 보존되고 실패 입력은 미확정. 운영 임대만 만료시킨 뒤 실패 위치부터 끝까지 처리. 최종 분석/감사10개, 실제 분석 시도11회 |

부모 Spring 시험 맥락에서 실제 스키마/서비스를 초기화하고 자식 맥락에는 생산 프로필의 실제 워커만 등록한다. 부모의 다른 자동 작업은 처리 플래그 false이며 외부 노드/엔진/Kafka를 호출하지 않는다. 자식의 의존성 이름용 빈은 이미 완료된 초기화를 나타내고 DB 제약을 대역으로 바꾸지 않는다. 후처리로 원장 합계0·사용 가능 잔액0·외부 호출 없음·전용 스레드 정리를 확인한다. 원장·확정 분석·감사 이력을 수정/삭제하거나 불변 트리거를 해제하지 않았다.

## 실행 명령과 결과

- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*BitcoinDepositMonitoringWorkerTest' --console=plain`:12개 통과.
- `python3 scripts/verify_deposit_monitoring_drain.py`: 소유 PostgreSQL16·함수별2 DB로2개 통과.
- 최종 고유14개/14회, 실패·오류·제외0. 중간11개 실행은 합산하지 않는다. `.local/cycle119-proof`의 JUnit XML·실행기 JSON·로그를 보존한다.118의285개를 이번 수정 뒤 전체 재실행했다고 표시하지 않는다.
- 고객/운영자 JAR 빌드·Python 구문·현재 코드 발췌/링크·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

## 남은 범위

지속 유입에서의 실제 처리량/자원 경쟁·적체 알림은 별도 측정이 필요하다. 거래 탐지 정책·사건 묶음·독립 종결/보고 판단과 입금 금융 허가는 변경하지 않았다. 자산 반입/출금과 나머지 전체 로드맵은 계속 진행한다. Rust 단일 소유 코어·자체 WAL·출력 확정 계약은 바꾸지 않는다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 47fa9aa](https://github.com/GiHoon1123/Cex-Platform-Service/commit/47fa9aa959ce1781cb3ad657f2cbba39c9f28ff0)
- [격리 실행 증적](../reference/119-drain-ready-deposit-monitoring-inputs-verification-2026-10-25.json)
