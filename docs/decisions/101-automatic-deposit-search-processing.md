# 예약된 입금 검색을 독립 워커에서 제한적으로 자동 처리하기

예약된 검색을 전용 장수 워커에서 자동 실행한다. 최신 DB 시도와 간격을 기준으로 작업 하나를 선택하고, 기술 검색이 끝났거나 확인할 수 없는 상태를 무한 조회하지 않는다. 새 접수의 목록 전제와 기존 ID의 독립 조회를 구분하며 자금 이동/금융 승인은 실행하지 않는다.

## 문제와 기존 동작

[100](100-durable-deposit-search-execution.md)은 접수/조회·미전송/불명·불변 결과를 연결했지만 내부 호출자가 직접 실행해야 했다. 자동 처리에서 모든 기록을 매초 재실행하거나 오래된 검색을 끝없이 조회하면 공급사 호출/저장 비용과 대기열 부담이 커진다. 목록 장애로 보류된 새 접수가 앞에 있다고 이미 접수됐을 수 있는 다른 ID의 조회까지 막아서도 안 된다.

## 기존 제품과 공식 자료의 접근

- [CodeVASP 결과 조회](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/12-Search-VASP-by-TXID-Result)는 PROCESSING의 재조회 간격을10초보다 길게 권고한다.100의30초 간격을 유지한다.15분 자동 선택 기한이나 터미널 분류를 법적 의무/공급사 기한으로 표시하지 않는다.
- [Spring 장수 스케줄러](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html)의 풀1개/고정 지연/종료를 재사용한다. [097](097-automatic-codevasp-directory.md)과 같은 실제 수명 패턴으로 공용 예약 실행기를 점유하지 않는다.
- [PostgreSQL16 잠금](https://www.postgresql.org/docs/16/explicit-locking.html)과100의 영속 현재 위치/시도 제약이 최종 실행권을 검사한다. 후보 선택을 별도 메모리 큐나 중복 임대 표로 복제하지 않는다. 새 라이브러리/의존성 변경은 없다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| DB 후보1개 선택 후 기존 실행 서비스 호출 | 기존 임대/간격/미전송 계약 재사용·메모리 상태 유실 없음 | 후보 선택은 실행권 확정이 아니다. 경합은 실제 시작 시409로 차단하며 다음 주기에 다른 작업을 선택 |
| 전용 스레드1개·완료 후1초 지연 | 동일 워커 비중첩·공용 주문/고객 확인 작업 분리 | 스레드/DB 조회 비용·동일 호스트 자원 경쟁은 남음. 대규모 backlog 처리량과 공급사 쿼터는 운영 검증 필요 |
| 최초 시작부터15분까지만 자동 후보 | 일시 오류/처리 중을 무한 조회하는 비용 제한 | 이후 원본/보류 상태가 남고 운영 해결 필요. 한 번도 시작하지 않은 예약은 이 기한으로 버리지 않음 |
| FOUND/NOT_FOUND/UNKNOWN_REQUEST/AMBIGUOUS는 자동 조회 중지 | 끝난 검색·해결되지 않는 ID를 반복 호출하지 않음 | NOT_FOUND는 비회원/장애 가능, UNKNOWN_REQUEST는 미접수 증명 아님. 운영 검토가 남으며 금융 거절/개인지갑 승인으로 바꾸지 않음 |
| 새 접수는 현재 성공 목록 요구·기존 ID 조회는 독립 선택 | 목록 장애 때 새 노출을 보류하면서 기존 검색 상태 회복 | 두 종류 작업의 전제를 구분해야 함. 과거 성공 목록으로 우회하는 방식은 쓰지 않음 |
| 자동 워커도 test 프로필 등록 제외 | 실제 환경 변수 때문에 시험의 외부 자동 통신이 시작되지 않음 | 소유한 별도 컨텍스트/직접 만든 실행기로 실제 수명과 PG 연결을 검증해야 함 |

15분은 **후보 SQL의 선택 시점** 기준이다. 마지막 실행의 완료 시각을15분 이내로 보장하는 하드 마감이 아니며 선택 뒤 스레드 정지/DB 대기와 마지막 호출은 더 늦게 끝날 수 있다. 실행 서비스는 시작 시점의 단계/간격을 다시 확인한다. 선택 조건을 금융 승인 토큰처럼 사용하지 않는다. 명시적 내부 refresh는 이 자동 기한과 별개로100의 단계/간격을 지켜 해결할 수 있다. 그 호출을 운영자 HTTP로 공개하려면 업무 인가/감사/재확인 경계를 추가해야 한다.

## 구현과 관련 코드

[CodeVaspDepositSearchProcessing](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchProcessing.java)은 processNext()에서 현재 설정과 같은 접속 주체의 예약만 읽는다.

| 최신 상태 | 자동 후보 조건 |
| --- | --- |
|미착수 | 시작 가능. 최초 접수 전 현재 목록 필요 |
|완료 없는 시작 | 최초 시작15분 안·시작 후2분 이상. 같은 ID POLL |
|SUBMIT_NOT_SENT | 최초 시작15분 안·완료 후30초 이상. 현재 목록 확인 후 같은 ID SUBMIT |
|SUBMIT_ACKNOWLEDGED/SUBMIT_UNKNOWN/PROCESSING/POLL_FAILED | 최초 시작15분 안·완료 후30초 이상. 같은 ID POLL |
|FOUND/NOT_FOUND/UNKNOWN_REQUEST/AMBIGUOUS | 자동 후보 제외, 원본/결과 유지 |

새 접수 후보가 먼저 선택됐는데 현재 목록이503이면, 목록 없이 허용되는 조회 후보를 별도 SQL로 선택한다. 조회 후보가 없으면 원래503을 보고한다. 원본을 지우거나 옛 정상 목록을 금융 근거로 재사용하지 않는다. 정상 목록일 때에는 원래 대기 시간 순서로 선택해 항상 조회만 앞세우는 고정 우선순위도 추가하지 않았다. 새 접수 후보1개+독립 조회 후보1개의 제한된 선택이며 외부 호출은 최대1개다.

후보 선택의 DB 연결/현재 목록 TX가 끝난 뒤100의 실행 서비스를 호출한다. 같은 후보를 다른 프로세스가 먼저 시작하면409를 정상 경합으로 처리하고 외부 호출을 반복하지 않는다. 다른 오류는 삼키지 않는다. [스키마](../../src/main/resources/travelrule/schema.sql)에 접속 주체/생성 시각의 예약 인덱스와 검색별 시도 인덱스를 추가했다. 쿼리 상한1은 전체 DB 스캔/정렬 비용이 항상 상수라는 의미가 아니다. 대량 기록의 EXPLAIN/자원 예산은 별도 운영 검증이다.

[CodeVaspDepositSearchWorker](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchWorker.java)는 독립 ThreadPoolTaskScheduler로 처리한다. 비활성/미설정이면 실행기를 만들지 않는다. 일반 앱에서 codevasp.processing.enabled=true(기본값)이고 설정4개가 모두 유효할 때 시작한다. test 프로필에서는 자동 빈 등록을 제외한다. 기존 관리용 별도 프로세스는 이 컴포넌트를 탐색하지 않는다.

실패 결과의 failureCode나 예외는 제한된 경고 상태 전환으로만 알리고 원문/주소/키를 출력하지 않는다. 정상 기술 처리 재개를 금융 입금 완료라고 기록하지 않는다. 종료는 중단 요청과 최대10초 대기이며 무시하는 작업 위에 새 실행기를 만들지 않는다. 정상 실행의 단일 스레드와 별개로 여러 플랫폼 프로세스의 경합은100의 DB 계약이 검사한다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDepositSearchProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchProcessing.java#L36), 현재 소스의 부분 발췌.

```java
        if(Boolean.TRUE.equals(candidate.get("requires_submit"))){
            try{directory.current();}
            catch(ResponseStatusException unavailable){
                if(unavailable.getStatusCode().value()!=503)throw unavailable;
                // 새 접수가 목록 장애로 보류되어도 이미 접수됐을 수 있는 ID의 독립 조회를 앞에서 막지 않는다.
                var polls=candidates(source,true);
                if(polls.isEmpty())throw unavailable;
                candidate=polls.getFirst();
            }
        }
        try{
```

requires_submit는 SQL의 불리언 값이다. 새 접수의 현재 목록 전제가 실패해도 독립 조회 후보를 다시 선택한다. 과거 정상 목록을 돌려주는 폴백이 아니라 서로 다른 전제를 가진 작업의 선택이다. Optional.empty는 할 일이 없거나 다른 프로세스가 먼저 실행한 경우이며 금융 성공/실패를 뜻하지 않는다.

출처: [CodeVaspDepositSearchProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchProcessing.java#L60), 현재 소스의 부분 발췌.

```java
             WHERE s.source_id=?
              AND (NOT ? OR (h.attempt_id IS NOT NULL AND COALESCE(r.status,'')<>'SUBMIT_NOT_SENT'))
              AND (h.attempt_id IS NULL OR (SELECT min(initial.started_at) FROM codevasp_search_attempts initial WHERE initial.search_id=s.id)>clock_timestamp()-interval '15 minutes')
              AND (h.attempt_id IS NULL OR
                (r.attempt_id IS NULL AND a.started_at<=clock_timestamp()-interval '2 minutes') OR
                (r.status IN ('SUBMIT_NOT_SENT','SUBMIT_ACKNOWLEDGED','SUBMIT_UNKNOWN','PROCESSING','POLL_FAILED') AND r.recorded_at<=clock_timestamp()-interval '30 seconds'))
             ORDER BY COALESCE(r.recorded_at,a.started_at,s.created_at),s.id LIMIT 1
            """,source,pollOnly);
```

SQL의 최신 시도/완료 시각으로 미착수·임대 만료·재조회 간격·자동 선택 기한을 판정한다. 첫 시작의 min은 실제 영속 이력이며 워커 메모리 카운터를 재시작 복구 원본으로 쓰지 않는다. pollOnly 바인딩은 이미 접수됐을 수 있는 ID의 조회만 고른다.

출처: [CodeVaspDepositSearchWorker.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchWorker.java#L60), 현재 소스의 부분 발췌.

```java
    private void tick(){
        if(!active)return;
        var result=processing.processNext();
        if(!active)return;
        if(result.isEmpty())return;
        if(result.get().failureCode()!=null){
            markFailed();
        }else if(failed){
            failed=false;
            log.info("입금 검색 요청 처리를 재개했습니다. 금융 승인은 별도로 확인합니다");
        }
    }
```

고정 지연 실행기 하나가 실제 작업 하나를 수행한다. Optional<State>는 작업이 있었을 때의 값을 담는다. 원문 없는 실패 상태만 제한 로그로 알리며 정상 재개도 금융 승인으로 표시하지 않는다. TypeScript의 Promise가 반환됐다는 사실만으로 업무가 성공한 것이 아니듯, 내부 처리 진행과 자금 효과를 구분한다.

## 작성한 테스트

[CodeVaspDepositSearchIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspDepositSearchIntegrationTest.java)에 실제 PG11개를 추가했다. 기존27개와 합쳐38개이며 별도 입금/접속 주체의 합성 원본으로 격리한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|automaticProcessingUsesReservedIdentityAndSkipsImmediateSecondCall | 기존 요청 ID로 최초 접수·즉시 두 번째 자동 실행 없음·접수1회 |
|automaticOldUnknownSubmissionCanPollWhileDirectoryRefreshHasFailed |181초 지난 중단 시작을 새로 구성, 목록 실패여도 기존 ID POLL/FOUND·재접수0 |
|automaticProvenNonSendWaitsForFreshDirectoryBeforeSubmittingAgain | 지난 미전송 결과라도 목록 실패이면 새 접수0, 목록 회복 뒤 같은 ID 접수 |
|automaticProcessingRetriesDueProcessingResultThenRespectsThirtySecondBackoff | 지난 PROCESSING을 조회하고 새 완료 뒤 즉시 재조회 안 함 |
|completedOrUnresolvableTechnicalResultsAreNotPolledForever | FOUND/NOT_FOUND/UNKNOWN_REQUEST/AMBIGUOUS 네 결과 모두 현재 조회 가능·자동 I/O0 |
|automaticWindowExhaustionKeepsRunningEvidenceAndAllowsExplicitResolution | 첫 시작901초 지난 원본은 자동 제외·RUNNING 보존, 명시적 내부 조회로 해결 가능 |
|automaticSelectionDoesNotCrossConfiguredSourceAuthority | 다른 접속 주체의 예약이 있어도 현재 주체의 요청만 접수 |
|concurrentAutomaticSelectorsHaveOnlyOneExternalSubmission | 첫 접수 정지 중 두 번째 선택은 빈 결과, 외부 접수1회 |
|automaticSelectorCannotHoldCallerTransactionOverProviderIo | 바깥 DB TX에서 자동 선택/외부 호출 금지 |
|automaticallyStartedOwnedWorkerPersistsRealDatabaseAttemptAndStops | 격리 PG에 실제 실행기를 가진 소유 자식 컨텍스트를 시작, 자동 ACK 영속/접수1회, 컨텍스트 종료 |
|heldNewSubmissionCannotBlockIndependentDuePollDuringDirectoryOutage | 앞선 오래된 미착수+뒤의 조회 후보·목록 장애, 뒤의 기존 ID만 조회·새 접수0 |

과거 시각/중단은 원본을 처음부터 그 시각으로 구성하는 합성 fixture다. 기존 이력 수정/불변 트리거 해제·실제15분 프로세스 중단 시험이 아니다. 온체인 원본/공식 공급사 계정과 실제 고객을 사용한 시험으로 해석하지 않는다.

[CodeVaspDepositSearchWorkerTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchWorkerTest.java)의 실제 실행기/컨텍스트8개를 추가했다. 각 시험은 실행기를 소유하고 종료한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|disabledAndUnconfiguredCannotCreateAutomaticAttempts | 비활성/미설정이면 처리0 |
|oneDedicatedThreadDoesNotOverlapOrBlockOtherScheduler | 느린 작업/중복 start에도1회·전용 이름·다른 실행기 진행·종료 인터럽트 |
|fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks | 느린 완료 직후 따라잡기 호출을 쌓지 않고 지연 뒤 진행 |
|storedFailureAndThrownPersistenceFailureDoNotCancelFutureAttempts | 저장된 실패·예외 뒤에도 다음 처리 진행 |
|stoppedWorkerCanRestartAfterOldExecutorTerminates | 정상 종료 뒤 새 실행 가능 |
|taskIgnoringShutdownCannotStartOverlappingReplacement | 중단 무시 작업의10초 제한 뒤에도 겹친 대체 실행기 없음·finally 해제 |
|testProfileCannotAutomaticallyStartEvenWhenConfiguredAndEnabled | 활성/설정 true여도 test 자동 빈0/처리0 |
|productionContextHonorsFlagAndClosesItsAutomaticallyStartedExecutor | 일반 컨텍스트의 비활성0·활성 자동1회·종료 후 전용 스레드 없음 |

## 실행 명령과 결과

- 최초 실행은 새 단위 시험의 컨텍스트 호출을 기계적으로 바꾸면서 ctx.refresh()를 ctx.processNext()로 잘못 바꿔 compileTestJava가 실패했다. 제품 코드/외부 실행 실패가 아니며 해당 호출을 수정했다. 실패를 통과 테스트로 세지 않는다.
- Java21/소유 PostgreSQL16. `python3 scripts/verify_codevasp_deposit_searches.py`: 최종46개(PG38+워커8), 실패/오류/제외0. 신규19+기존27이다. 실행기 선택 패턴에 새 단위 시험을 명시적으로 포함하고 스크립트의 설명/실패 안내도 실제 입금 검색 범위로 고쳤다.
- `python3 scripts/verify_codevasp_directory.py`: 기존54개, 실패/오류/제외0. 별도 소유 DB에서 실행했다.
- 공급사/메일/BTC/CodeVASP 환경을 제거하고 소비자 자동 시작=false로 `python3 scripts/verify_engine_inbox.py`: 기존 금융182개, 실패/오류/제외0. 전역 빈/스키마·H2 정산 fixture를 함께 확인했다.
- 최종 세 묶음 고유282개/실행282개·중복0. `.local/cycle101-proof`에 검색/목록/금융 XML·결과·로그/빌드 대조를 분리 보존했다. 소유한 최종 세 DB 컨테이너 모두 제거 확인.
- `bootJar operatorAdminJar` 성공. 두 JAR에 자동 선택/워커/인덱스 포함·테스트 제외. 기존 deprecated/unchecked/JVM CDS 경고는 남는다. `git diff --check`·현재 발췌/상대 링크 대조. 의존성 변경은 없다.
- 실제 공급사/샌드박스/Cipher 이미지·메인넷·BTC 실제 노드 전체·Rust/Kafka/브라우저 회귀는 이번에 실행하지 않았다.

## 남은 한계

기존 예약의 자동 실행까지다. 실제 입금 보류 원장에서 새 검색을 자동 예약하는 연결·운영 보류 조회/해결·송수신인 정보 교환/개인지갑 확인·고객/위험/금융 승인·엔진/원장 반영은 미완료다. 자동 기한이 지났거나 UNKNOWN_REQUEST/NOT_FOUND로 멈춘 상태는 실패한 금융 입금으로 지우지 않는다. 여러 프로세스의 전역 호출 쿼터/리더 선출은 없고 실제 계약/과금·15분 정책·운영 처리 용량 검증이 필요하다. 설정 후 일반 앱의 기존 예약은 자동 통신 대상이 될 수 있으므로 실제 계정/비공개 Cipher·쿼터 준비 전에는 codevasp.processing.enabled=false로 중지한다. 실제 외부 접속·배포를 수행한 기록이 아니다.


## 관련 커밋과 증적

- [구현 e2b1cc8](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e2b1cc8469fb656c662e0e4a2b3684de071538b8)
- [격리 실행 증적](../reference/101-automatic-deposit-search-processing-verification-2026-10-25.json)

## 후속 보류 원장 연결 시점

[102](102-automatic-pending-deposit-search-reservation.md)에서 이미 보류 반입한 일반 BTC 입금을 자동 예약에 연결했다. 위 자동 예약 미연결 설명은101 시점이다. 코인베이스/현재 체인 재검사·정보 교환·금융 허가는 별도이며 금융 반영은 미완료다.
