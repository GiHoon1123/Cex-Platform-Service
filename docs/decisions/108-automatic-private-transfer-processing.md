# 정보 교환의 자동 일정과 전용 워커에서 보류·임대·중복을 관리하기

검색이 끝난 보류 입금의 정보 교환 의도·현재 근거·외부 실행·암호화 응답을 전용 워커에서 연결한다. 보류 건의 재확인 일정, 임대 만료/늦은 완료와 불명 재전송 차단을 검증한다. 실제 개인정보 자동 제공은 별도 명시적 설정을 요구하며 기본은 꺼져 있다.

후속 [112 목적·당시 평가 제한](112-private-transfer-purpose-boundary.md)은 새 자동 의도/실행에 목적 근거를 요구하고 소액·미평가의 자동 일정을 별도 검토로 종료한다. 이전 문서의 실행 수치는 당시 범위이며 법정 근거·동일인·금융 사용 판단과 구분한다.

## 문제와 기존 동작

107까지 내부 서비스는 제공 의도에서 실제 정보 교환의 시작/응답을 보존했지만 자동 진입점이 없었다. 첫 미해결 건만 매초 고르면 현재 고객/체인/상대 조건을 만족하지 못하는 입금이 다른 준비된 입금을 막는다. 프로세스 재시작/일정 완료 저장 실패를 단순 재전송으로 해결하면 이미 제공한 개인정보를 반복할 수 있다.

정보 교환 여부의 최종 업무 정책도 전송 기술과 구분해야 한다. 검색 FOUND만으로 현재 모든 금액의 법적 정보 교환 의무가 생기는 것은 아니다. 이번 구현은 실행 일정/워커 연결이며 최종 금액 환산/제공 목적·대상 판단과 금융 승인은 아직 남는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 다음 공식 자료를 대조했다.

- [PostgreSQL16 SELECT](https://www.postgresql.org/docs/16/sql-select.html): 큐 형태의 여러 처리자에서 잠긴 작업을 SKIP LOCKED로 건너뛸 수 있다. 일반 금융 잔액의 완전한 스냅샷 조회를 이 방식으로 대체하지 않는다.
- [Spring7 ThreadPoolTaskScheduler](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html): 작업은 해당 실행기 스레드에서 실행하며 fixed delay는 완료 뒤부터 센다. 기존101 검색 워커의 전용 실행기/종료/실패 후 진행 패턴을 재사용한다.
- [CodeVASP 상호 연동 안내](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/12-Interoperability-with-Other-Protocols): 기술 연결과 내부 실사 정책·연동별 필수 정보를 구분한다.
- [업비트 트래블룰 안내](https://support.upbit.com/hc/ko/articles/4498679629337): 금액과 입금 경로에 따라 절차가 구분된다. 이 제품의 동작을 우리 서비스에 동일한 법적 의무로 일반화하지 않는다.
- [업비트 입금대기 안내](https://support.upbit.com/hc/ko/articles/5126987484185): 입금대기/추가 확인·반환과 이상 거래 모니터링을 별도 흐름으로 관리한다. 모든 대기를 성공이나 자금 부재로 바꾸지 않는다.

기존 KycReconciliationService의 영속 일정/짧은 임대와 전용 워커·실패 후 재확인 패턴도 확인했다. 설치된 Spring/JDBC/PG를 사용하며 새 큐 라이브러리/별도 범용 작업 프레임워크를 추가하지 않는다. 향후 시행 규정을 현재 금액 무관 의무로 간주하지 않는다. 정확한 적용 금액/환산 기준·제공 목적 결정은 실제 법령·고시와 연결해 후속 업무로 완성해야 한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|PG에 검색별 단일 작업 일정 | 재시작·여러 플랫폼 프로세스의 작업 소유권/재확인 보존 | 별도 작은 운영 상태 표와 짧은 DB 쓰기/감사 비용 |
|한 주기 신규1건·처리1건 | 메모리 큐/고객 전체 스캔을 줄이고 명시적 처리량 경계 | 기본1초 완료 후 지연+외부 호출 시간. 대량 유입의 완료 시간/처리량 보장 아님·적체 관측 필요 |
|2분 임대·조건 보류/미전송은2분 재확인 | 무한 대기/매초 동일 장애 반복과 첫 건 막힘 완화 | 정상 조건 회복 뒤에도 재확인 지연.2분은 자체 정책이며 공급사 할당량 보장 아님 |
|불명/시작만 남음/원격 오류는 검토 정지 | 프로세스 종료/응답 유실을 자동 재전송으로 바꾸지 않음 | 실제 전송 안 됐을 가능성이 있어도 운영 확인 필요 |
|기존 수신 원본으로 일정만 회복 | 일정 완료 저장 실패 뒤 중복 PUT 없음 | 일정 상태와 실제 불변 실행 사실은 시점이 다를 수 있음. 금융 승인 근거는 일정 표가 아님 |
|별도 개인정보 자동 제공 설정·기본 끔 | 목록/검색 설정이 개인정보 제공까지 암묵적으로 활성화하지 않음 | 운영에서 제공 목적/대상·계약/동의·공급사 적합성을 확인하고 명시적으로 켜야 함 |
|원본/최소 감사와 운영 일정을 분리 | 개인정보 원문을 큐/로그에 복제하지 않음 | 업무 상태/불변 이력/일정 상태를 구분해 조회·진단해야 함 |

## 설정과 자동 흐름

`codevasp.processing.enabled`는 기존 전체 CodeVASP 갱신/처리 설정이다. 새 `codevasp.private-transfer.enabled`의 기본은 false다. 일반 앱에서 네 가지 공급사 설정을 모두 제공하고 두 설정이 모두 true여야 전용 정보 교환 워커가 시작한다. test 프로필은 모두 강제 true여도 자동 워커 빈을 등록하지 않는다. 제품 기본으로 가상 사업자/자격·키·전송 동의를 만들어 넣지 않는다.

이 스위치는 최종 금액/위험·개인정보 제공 근거 판정이 아니다. 현재 일정 후보는 자체 보류 원장과 최신 FOUND 검색을 현재 접속 주체로 한정해 선택한다. 금액별 최종 대상 판정은 아직 연결하지 않았으므로 해당 업무/운영 조건이 갖춰지기 전 실제 서비스의 자동 개인정보 제공을 활성화해서는 안 된다. 이번 자동 실행 시험에서는 가상 수취인/상대 정책·합성 체인·소유 클라이언트 대역만 명시적으로 켰다.

한 주기에서 새 후보 하나를 codevasp_transfer_jobs에 저장하고 같은 TX에서 현재 due 작업 하나를 FOR UPDATE SKIP LOCKED로 임대한다. 생성/임대 필수 감사가 실패하면 롤백하고 외부 제공을 하지 않는다.2분 임대를 커밋한 뒤106의 예약과107 실행을 각각 독립된 짧은 TX로 호출한다. 외부 대기 중 작업 행/고객 잠금을 들고 있지 않는다.

기존 DATA_RECEIVED는 원본을 다시 읽어 일정만 DONE으로 복원한다. NOT_SENT는107의 최소30초·현재 원본 검증을 거친 실행만 허용하며 이 워커의 재확인은2분이다. RUNNING/OUTCOME_UNKNOWN/REMOTE_ERROR는 REVIEW_REQUIRED로 정지한다. 접속 주체가 다른 과거 입금 시도도 확인해 별도 검색 ID로 불명을 우회하지 않는다.

현재 조건/원본 장애는 HELD로2분 뒤 재확인하고 다른 작업을 처리할 수 있게 한다. 내부 저장 실패 뒤에도 이미 확정한 실행이 있는지 읽는다. 필수 일정 완료 감사/저장 자체의 실패는 다시 원격 실패로 잡지 않고 임대를 남긴다. 임대 만료 뒤 실제 불변 상태를 읽어 복원한다. DB 읽기 자체가 실패하면 성공으로 꾸미지 않는다.

늦은 완료는 lease_id가 같을 때만 일정에 반영한다. 임대를 잃은 옛 작업의 실제 응답은107의 불변 표에 남지만 새 일정 상태를 덮지 않는다. 예를 들어 임대 교체 시점에는 RUNNING으로 검토 정지했는데 옛 호출이 뒤늦게 DATA_RECEIVED로 끝날 수 있다. 이때 일정은 REVIEW_REQUIRED, 불변 실행은 DATA_RECEIVED일 수 있다. 이를 같은 시각의 금융 승인 결과로 사용하지 않는다.

## 스키마와 수명 경계

일정 표는 개인정보 없는 검색/의도 참조·상태/제한 사유·다음 시각·임대를 보관한다. 현재 주체 필터/작업 고유 키·due 인덱스를 사용한다. 의도와 검색의 결합, 현재 수신 원본 없는 DONE 거부, 임대 필드/종료 일정 조합, 원본 변경/삭제/TRUNCATE 금지를 둔다. 일정 변경 이력은 필수 감사에 남기고 제공 근거/개인정보·응답은 기존 불변 암호화 표에 보관한다.

전용 수명 긴 스레드 하나, 완료 후1초 지연,10초 종료 대기다. 실패가 다음 주기를 취소하지 않고 공급사 원문·키·개인정보를 로그에 출력하지 않는다. 종료되지 않은 이전 실행기가 있으면 대체 실행기를 겹쳐 만들지 않는다. 이 잠금/스케줄러/외부 대기는 Java 플랫폼 경계이며 Rust 체결 코어에 변경이 없다.

## 실제 코드와 문법

출처: [CodeVaspTransferWorker.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferWorker.java#L33), 현재 소스의 부분 발췌.

```java
            @Value("${codevasp.private-transfer.enabled:false}") boolean privateEnabled){
        // 목록/검색 활성화가 고객 개인정보 자동 제공까지 암묵적으로 켜지는 것을 막는다.
        // 운영에서 제공 목적·적용 대상·계약/동의를 확인한 뒤 이 기능을 명시적으로 활성화한다.
        this(settings,processing,enabled&&privateEnabled,Duration.ofSeconds(1));
    }
```

목록/검색 활성화와 개인정보 자동 제공은 별개 설정이다. 기본 false는 설정이 없을 때도 적용하며 test 프로필은 별도로 빈 자체를 제외한다. 이 스위치는 법적 적용 대상/제공 목적 판정을 대신하지 않는다.

출처: [CodeVaspTransferProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferProcessing.java#L56), 현재 소스의 부분 발췌.

```java
            SELECT j.search_id,s.receipt_id FROM codevasp_transfer_jobs j JOIN codevasp_deposit_searches s ON s.id=j.search_id
             WHERE s.source_id=? AND j.state IN ('PENDING','RETRY','HELD') AND j.next_attempt_at<=clock_timestamp()
              AND(j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
             ORDER BY j.next_attempt_at,j.search_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
            """,source);if(due.isEmpty())return null;var row=due.getFirst();UUID search=(UUID)row.get("search_id"),lease=UUID.randomUUID();
```

FOR UPDATE OF j SKIP LOCKED는 다른 처리자가 잠근 일정만 건너뛴다. 원장/잔액에서 잠긴 기록을 생략하는 용도가 아니다. claim TX는 외부 정보 제공 전에 끝나며 다음 시각 순으로 독립 건을 처리한다.

출처: [CodeVaspTransferProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferProcessing.java#L34), 현재 소스의 부분 발췌.

```java
            intent=intents.reserve(claim.search()).id();var known=execution.current(intent);
            // 이미 확정한 수신/불명 원본은 읽어 일정만 회복한다. 같은 외부 PUT를 다시 하지 않는다.
            outcome=classify(Set.of("UNSTARTED","NOT_SENT").contains(known.status())?execution.execute(intent):known);
```

먼저 불변 실행 원본의 상태를 읽는다. 이미 수신/불명 상태가 있으면 외부 PUT를 다시 호출하지 않고 일정만 복원한다. Optional.empty는 처리할 일정/현재 임대 소유권이 없다는 뜻이며 입금이나 자산이 없다는 뜻이 아니다.

출처: [CodeVaspTransferProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferProcessing.java#L81), 현재 소스의 부분 발췌.

```java
            UPDATE codevasp_transfer_jobs SET intent_id=COALESCE(?,intent_id),state=?,last_reason=?,lease_id=NULL,lease_until=NULL,
             next_attempt_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+interval '2 minutes' END,updated_at=clock_timestamp()
             WHERE search_id=? AND lease_id=?
            """,intent,outcome.status(),outcome.reason(),terminal,claim.search(),claim.lease());
        // 임대가 바뀌었으면 늦은 완료로 새 작업 상태를 덮지 않는다. 실행 자체의 원본/결과는 이미 따로 보존됐다.
```

완료 UPDATE는 자신이 받은 lease_id를 조건으로 한다. 늦은 작업이 새 처리자의 일정 상태를 덮지 못한다. COALESCE는 이 운영 행의 기존 의도 참조를 유지하는 SQL 연산이며 과거 금융 성공/외부 자료로 우회하는 폴백이 아니다.

## 작성한 테스트

[CodeVaspTransferProcessingIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferProcessingIntegrationTest.java)14개를 추가했다. 현재 KYC 서명/신고·PG/보류 원장·개인정보 암호화·실제106/107 실행·필수 감사와 수명 긴 실행기를 사용하며 외부 정보 클라이언트만 대역이다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|foundPendingAutomaticallyReservesTransfersAndFinishesOnce | 현재 FOUND/보류→예약→암호화 응답→DONE·다음 처리 전송0 |
|heldReceiptDoesNotBlockAnotherReadyDeposit | 첫 입금의 최신 낡은 체인 보류 뒤 다른 준비된 입금 처리 |
|provenNonSendSchedulesRetryInsteadOfRepeatingImmediately | 미전송은2분 재확인·즉시 호출0·원본 상태 유지 |
|unknownOutcomeStopsAutomaticProcessingForReview | 불명은 검토 정지/다음 시각 없음·재전송0 |
|failedExecutionFinishAuditBecomesUnresolvedReviewWithoutRepeat | 실행 완료 감사 실패는 시작만 남은 검토 정지·재전송0 |
|jobFinishFailureRecoversFromDurableReplyWithoutAnotherPut | 일정 완료 감사 롤백 뒤 임대 만료/현재 수신 원본으로 복원·총 PUT1 |
|claimAuditFailureRollsBackSeedAndLeaseBeforeAnyDisclosure | 생성/임대 TX의 필수 감사 실패는 일정/임대 롤백·전송0 |
|expiredLeaseReplacementCannotBeOverwrittenByLateCompletion | 임대 교체 처리의 REVIEW_REQUIRED를 옛 완료가 덮지 못함·뒤늦은 수신 원본 별도 보존 |
|customerHoldCanBeRecheckedAfterMutableScheduleBecomesDue | 고객 제한 보류 뒤 현재 조건 회복/일정 due 재확인으로 처리 |
|latestNonFoundSearchCannotSeedPrivateJob | 최신 검색 실패는 옛 FOUND/새 자동 작업으로 우회 불가 |
|ambientTransactionCannotSeedClaimOrDisclose | 호출자 TX 안에서 생성/임대/제공 시작 거부 |
|ownedProductionWorkerRunsRealProcessingAndCloses | 소유 production 컨텍스트에서 명시적 활성화·실제 처리/종료 |
|scheduleCannotClaimDoneWithoutReplyOrLoseItsSource | 수신 원본 없는 DONE·일정 DELETE/TRUNCATE 직접 SQL 거부 |
|anotherConnectionAuthorityCannotClaimExistingJob | 다른 현재 접속 주체는 기존 일정 선택/실행0 |

가상 체인/검색 원본은 정상 제약을 통과한 합성 fixture다. 임대 만료/다음 일정은 갱신 가능한 운영 행에서 시험 시각을 조정했고, 불변 금융/감사/실행 기록을 수정하거나 검사 트리거를 끄지 않았다. test source마다 다른 접속 주체를 만들어 과거 시험의 FOUND가 선택되지 않게 했다. 같은 DB의 사용자 ID를 재설정하거나 원본을 지우지 않는다.

[CodeVaspTransferWorkerTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransferWorkerTest.java)9개를 추가했다.101의 검증된 수명 시험을 이 실행기에 적용했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|disabledAndUnconfiguredCannotCreateAutomaticAttempts | 전역 비활성/미설정이면 처리0 |
|oneDedicatedThreadDoesNotOverlapOrBlockOtherScheduler | 하나의 전용 스레드·중복 start/병렬 호출 없음·다른 실행기 진행 |
|fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks | 느린 작업 중 누적 없음·완료 뒤 지연 |
|storedFailureAndThrownPersistenceFailureDoNotCancelFutureAttempts | 저장된 보류/예외 뒤에도 다음 주기 진행 |
|stoppedWorkerCanRestartAfterOldExecutorTerminates | 종료한 워커만 재시작 가능 |
|taskIgnoringShutdownCannotStartOverlappingReplacement | 종료 무시 작업이 남으면 대체 실행기 중첩 금지 |
|testProfileCannotAutomaticallyStartEvenWhenConfiguredAndEnabled | test에서 두 설정/공급사 값 강제 true여도 자동 빈 없음 |
|productionContextHonorsFlagAndClosesItsAutomaticallyStartedExecutor | production 비활성/명시 활성·컨텍스트 종료 뒤 실행기 제거 |
|metadataProcessingFlagAloneCannotEnablePrivateDisclosure | 개인정보 설정을 아예 생략하면 기본 false·목록/검색 true만으로 처리0 |

공통 fixture의 보류 잔액 검사는 다중 입금 시나리오에 맞춰 실제 불변 수신/보류 원본의 합계와 원장 PENDING 합계를 대조한다. 단일 입금 시험은0.12345678, 독립 입금2개 시험은0.24691356을 보존한다. 모든 시험 뒤 user_balances0·의도하지 않은 공급사/엔진/Kafka 호출 없음·감사 원문 비노출을 확인한다.

## 실행 명령과 결과

- Java21/macOS arm64/소유 PG16·소유 실행기. 첫20개 통과 뒤 SQL 완료/다른 접속 주체2개를 보강해22개 통과했다. 별도 개인정보 스위치/기본 미설정 시험을 추가한 최종23개를 실행하고, 미설정 상태가 실제 생략되도록 시험 fixture를 보강해23개를 다시 통과했다. 중간 실행을 최종 합계에 더하지 않는다. 이번 사이클에 실패한 제품/시험 실행은 없다.
- `python3 scripts/verify_codevasp_transfer_processing.py`: 최종23개(통합14+수명9) 통과, 실패/오류/제외0. 실제 워커 대기 확인은 행이 아직 없어도 안전한 count 질의로 수행한다.
- `python3 scripts/verify_engine_inbox.py`: 금융182개 통과, 실패/오류/제외0. 상위 실행에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_를 제거하고 소비자 자동 시작 금지를 고정했다. 마지막 수정은 수명 시험의 설정 생략 fixture뿐이며 제품 소스는 금융 회귀 시점과 같다.
- 고유205개/최종 검증 집합205실행·중복0. 각 XML의 클래스/함수 집합을 대조하고 두 최종 소유 PG 제거를 확인했다. `.local/cycle108-proof`에23 XML/로그/manifest와 finance182 XML/로그/manifest·중복 대조를 보존한다. 앞 회차214/241개를 현재 합계에 더하지 않는다.
- bootJar/operatorAdminJar 성공·제품 워커/처리/스키마 포함·시험 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 의존성 추가 없음. `git diff --check`·현재 발췌/상대 링크 대조.
- 실제 BTC 노드·HTTP81·기존 실행32/검색46/자동 예약12·운영 심사/보안 전체·Rust/Kafka/브라우저는 이번에 재실행하지 않았다. 실제 외부 공급사/개인정보 전송·배포/봇 활성화는 하지 않았다.

## 남은 업무와 운영 조건

최종 금액 환산/제공 목적·대상 결정, 응답의 현재 고객/동일인·위험 판단, 보호된 운영 조회/불명 해결, 원장/엔진 적립과 재조직 복구는 남는다. 모든 FOUND를 법적 의무로 취급하거나 기본 스위치를 켜는 것으로 이 문제를 해결하지 않는다. 이 스위치는 장애 시 개인정보 자동 제공만 멈추고 목록/검색과 독립 운영하는 경계로 유지한다.

실제 공급사 계약/개인정보 제공 근거·상대 실사·Cipher/Sandbox/LIVE·키/망/보존/파기·적체/가용성 관측과 최종 법적 적용 확인은 미완료다. 코드는 현재 조건과 원본을 보존하지만 신고/인증 완료나 실제 사업 운영 적합성을 주장하지 않는다. 일정 DONE은 응답 수신 자동화의 진행 상태이며 금융 승인/사용 가능 자산이 아니다.


## 관련 커밋과 증적

- [구현 f6ea8d8](https://github.com/GiHoon1123/Cex-Platform-Service/commit/f6ea8d8c65856cd31b95b793467e2bda93fc8046)
- [격리 실행 증적](../reference/108-automatic-private-transfer-processing-verification-2026-10-25.json)
