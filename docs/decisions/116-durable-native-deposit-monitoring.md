# 실제 수탁 원장으로 반복 소액 입금의 검토 신호 남기기

실제 BTC 보류 수탁 원장을 입력으로 삼아 반복 소액 입금의 한 가지 검토 신호를 보존한다. 금융 반입과 처리 예약은 함께 확정하고, 별도 워커가 원본·정책·입력 위치를 고정해 재현 가능한 결과를 남긴다. 이 결과는 입금 승인·고객 제한·범죄 판단·의심거래보고가 아니다.

후속 [117 순차 재생 수정](117-ordered-deposit-monitoring-replay.md)에서 최신 위치로 합치던 처리를 제거했다. 아래 최신 위치 합치기 선택은116 당시 기록이며, 현재는 미처리 원장 입력을 하나씩 분석해 중간 시간 창도 보존한다. 당시 검증 수치는116 소스 기준이다.

후속 [118 원본 검증/담당자 의견](118-verified-monitoring-evidence-and-analyst-observations.md)은 당시 입력을 검증해 열람하고 암호화 의견·접근 증적·필수 감사를 연결한다. 사건 종결·금융 허가와 구분한다.

## 문제와 기존 동작

110은 입금 인식 당시의 원화 참고값을 보존했고 115는 인가된 담당자가 이전거래 자료를 검토하도록 했다. 하지만 여러 입금 사이의 반복 패턴을 계산하는 거래 기반 탐지는 없었다. 고객 확인 조사 기능이 있다는 사실을 거래 모니터링 완료로 설명할 수 없었다.

처리 사실이 없는 고객을 정상으로 표시하거나, 현재 시세로 과거 미평가 금액을 채우거나, 한 비트코인 거래의 여러 출력을 여러 송금으로 세면 잘못된 판단 근거가 생긴다. 새 모듈은 이런 경우를 명시적으로 구분한다.

## 기존 제품·공식 자료의 접근

확인일은2026-10-25다. [FATF2020 가상자산 위험 신호](https://www.fatf-gafi.org/en/publications/Methodsandtrends/Virtual-assets-red-flag-indicators.html)와 [공식 보고서](https://www.fatf-gafi.org/content/dam/fatf-gafi/reports/Virtual-Assets-Red-Flag-Indicators.pdf.coredownload.pdf)는 반복·소액화 등 거래 패턴을 맥락과 함께 검토하는 접근을 제시한다. 단일 지표를 범죄 증거나 신고 결론으로 취급하지 않는다. [AUSTRAC의 사업자 위험 신호](https://www.austrac.gov.au/industry-and-business/education-and-resources/publications-and-resources/indicators-suspicious-activity-virtual-asset-service-providers)도 단기간 반복 소액 거래를 예로 든다. 해외 참고이며 국내 법정 수치로 옮기지 않는다.

[금융위·KoFIU2022 검사 결과](https://www.fsc.go.kr/po010101/78636?curPage=&srchBeginDt=&srchCtgry=1&srchEndDt=&srchKey=&srchText=)는 탐지 기준의 실제 효과와 조정을 검토할 필요를 보여 준다. 당시 설명을2026년 현행 조문의 버전 확인으로 대신하지 않는다. [KoFIU 의심거래보고 설명](https://www.kofiu.go.kr/kor/policy/amls01.do)의 합리적 의심에 대한 판단도 기계적인 수량 비교와 구분한다.

이 구현의24시간·5건·100만원·최대1000입력은 **자체 단일 탐지 정책의 초기 고정값**이다. 법령이나 FATF가 이 조합을 강제한다고 주장하지 않는다. 트래블룰 적용 금액이나 현금거래보고 기준으로 재사용하지 않는다. 실제 운영에서는 거래 분포·오탐·분석가 처리 역량·누락 유형을 확인해 정책을 검증해야 한다.

금융 입력과 예약의 원자성에는 [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 같은 트랜잭션 패턴을 적용한다. 작업 확보는 [PostgreSQL16 SELECT의 SKIP LOCKED](https://www.postgresql.org/docs/16/sql-select.html)를 사용한다. 이는 일반 조회의 완전한 스냅샷 용도가 아니라 여러 작업자의 대기 경합을 피하는 큐 확보다. 실제 분석 입력은 별도 고객 잠금 아래 고정한다. 설치된 Spring JDBC·TransactionTemplate·ThreadPoolTaskScheduler·Jackson·BigDecimal을 사용하며 의존성을 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 감수하는 비용·대안 |
| --- | --- | --- |
| 실제 수탁 원장의 고객별 순번을 입력 위치로 사용 | 시계가 뒤로 움직여도 새 금융 기록을 빠뜨리지 않는다 | 시간만 저장하는 방식보다 원장과의 정확한 결합이 필요하다 |
| 금융 반입과 고객별 처리 예약을 같은 트랜잭션으로 확정 | 반입 성공 뒤 프로세스가 죽어도 처리할 위치가 남는다 | 큐 저장 실패 시 신규 반입도 롤백한다. 이미 관찰한 체인 수신 사실은 별도로 보존한다 |
|116 당시: 한 고객의 최신 입력 위치까지 합쳐 처리(117에서 제거) | 입금 폭주 시 같은 고객의 중간 예약을 모두 수행할 필요가 없다 | 매 입금마다 별도 경보를 생성하지 않는다. 입력 원본은 보존하고 계산 당시 창 전체를 저장한다 |
|24시간 창 최대1001개를 읽어 초과 여부 판정 | 처리량과 문서 크기를 제한하면서 일부만 읽은 정상을 막는다 |1000개 초과는 INCOMPLETE이며 별도 처리·정책 검토가 필요하다 |
| 입금 인식 당시 원화값과 네트워크별 거래 ID로 묶기 | 과거 재가격·출력 중복 횟수 집계 방지 | 같은 거래의 출력 인식 시점별 가격 차이, 과거 입금의 일괄 발견에 따른 오탐 가능성이 있다 |
| 미평가가 섞인 거래는 전체를 미확인 처리 | 일부 금액만으로 소액이라고 단정하지 않는다 | 탐지 가능한 범위가 줄어든다. 이미 확인된 거래들만으로 신호가 충족되면 신호와 미확인 수를 함께 남긴다 |
| 불변 분석 기록·원본 문서·지문·현재 위치 분리 | 원본·정책·당시 결과를 설명하고 중복 실행을 막는다 | JSON 근거와 이력 저장/검증 비용이 든다. 지문만 남기면 실제 근거를 읽을 수 없다 |
| 전용 장수 워커와 짧은 고객 잠금 | 엔진 코어·외부 HTTP와 탐지 실행을 분리한다 | 플랫폼의 같은 고객 금융 업무가 짧게 대기할 수 있다. DB/CPU 자원 경쟁까지 없애지는 못한다 |

벽시계 불연속은 INCOMPLETE로 남긴다. 인식 시각은 실제 송신 시각과 같지 않으며, 과거 입금을 한꺼번에 발견했다는 사실만으로 분할 송금이라고 결론 내리지 않는다. 정책의 NO_SIGNAL은 이 규칙에서 신호가 없다는 뜻이다. 전체 저위험·거래 허용·조사 종결이 아니다.

## 구현과 관련 코드·문법

출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L85), 현재 소스의 부분 발췌.

```java
        monitoring.enqueue(owner,key);
        audit.record(null,"BTC_PENDING_DEPOSIT_RECOGNIZED","bitcoin-deposit:"+deposit,"SUCCESS","DECISION:"+decision+":OP:"+key);
```

입금 당시 평가 다음에 모니터링 예약을 확정한다. 이 호출은 현재 금융 트랜잭션 안에서 실행된다. 큐가 실패하면 보류 원장·평가도 함께 롤백한다. 외부 체인을 읽은 관찰 원본은 지우지 않는다.

출처: [DepositMonitoringQueue.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringQueue.java#L15), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(long owner,String operation){
        Long cursor=jdbc.queryForObject("SELECT input_cursor FROM deposit_monitoring_inputs WHERE user_id=? AND operation_key=?",Long.class,owner,operation);
        jdbc.update("""
            INSERT INTO deposit_monitoring_jobs(user_id,target_cursor) VALUES(?,?)
             ON CONFLICT(user_id) DO UPDATE SET target_cursor=greatest(deposit_monitoring_jobs.target_cursor,excluded.target_cursor),updated_at=clock_timestamp()
            """,owner,cursor);
```

MANDATORY는 호출자의 실제 트랜잭션이 있어야 한다는 의미다. TypeScript 함수 호출 자체는 이런 원자성을 만들지 않는다. 여기서는 Spring 프록시와 같은 DB 트랜잭션이 계약을 적용한다. greatest는 새 알림이 기존 처리 목표·임대를 뒤로 돌리지 않도록 한다.

출처: [DepositMonitoringService.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringService.java#L35), 현재 소스의 부분 발췌.

```java
        Claim claim=tx.execute(t->claim());if(claim==null)return Optional.empty();
        return tx.execute(t->process(claim));
```

첫 트랜잭션은 SKIP LOCKED로2분 임대를 확보하고 종료한다. 다음 트랜잭션에서 고객→작업 순서로 잠가 실제 계산한다. Optional.empty는 할 작업이 없거나 유효 임대가 없는 경우이며 업무가 복구됐다는 증거가 아니다. 임대 뒤 실패하면 원본은 유지되고 만료 후 다시 시도한다.

출처: [SmallDepositMonitoringPolicy.java](../../src/main/java/dustin/cex/domains/monitoring/SmallDepositMonitoringPolicy.java#L43), 현재 소스의 부분 발췌.

```java
            if(missing){unknown++;continue;}
            if(value.compareTo(BOUNDARY)<0){small++;total=total.add(value);}
        }
        if(small>=MIN_TRANSFERS&&total.compareTo(BOUNDARY)>=0)return new Result("REVIEW_REQUIRED","REPEATED_SMALL_REFERENCE_INFLOW",small,total,unknown);
        if(unknown>0)return new Result("INCOMPLETE","VALUATION_UNAVAILABLE",small,total,unknown);
        return new Result("NO_SIGNAL","NO_SIGNAL_IN_THIS_RULE",small,total,0);
```

같은 네트워크·거래 ID의 모든 출력 금액을 먼저 더한다. 하나라도 미평가이면 그 거래 전체를 미확인으로 센다. BigDecimal.compareTo는 표시 자릿수 차이와 무관하게 정확한 수치를 비교한다. TypeScript number의 부동소수점 합산으로 대체하지 않는다. 규칙 충족·미확인·이 규칙 무신호를 구분한다.

출처: [deposit-schema.sql](../../src/main/resources/monitoring/deposit-schema.sql#L4), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE VIEW deposit_monitoring_inputs AS
 SELECT e.id input_cursor,p.operation_key,b.id receipt_id,b.user_id,b.asset,b.network,b.txid,p.recorded_at recognized_at,
  'KRW'::varchar currency,v.policy_id valuation_policy,v.status valuation_status,v.price_attempt_id,v.krw_value
 FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts b ON b.id=p.receipt_id
 JOIN ledger_transactions l ON l.operation_key=p.operation_key AND l.kind='BTC_DEPOSIT_PENDING'
 JOIN ledger_entries e ON e.operation_key=p.operation_key AND e.owner_id=b.user_id AND e.bucket='PENDING' AND e.asset=b.asset AND e.delta=b.amount AND e.delta>0
 LEFT JOIN bitcoin_deposit_valuations v ON v.receipt_id=b.id;
```

조회용 뷰는 실제 보류 원장과 당시 평가를 연결한다. 금융 권위를 새 탐지 표로 옮기지 않는다. 새 평가 삽입 시에는 각 근거가 실제 불변 원본과 같은지·창 입력 누락이 없는지도 DB에서 확인한다. 평가 수정/삭제/TRUNCATE를 거부한다. 관리자 권한·호스트 손실 보호는 별도다.

출처: [BitcoinDepositMonitoringWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorker.java#L37), 현재 소스의 부분 발췌.

```java
        var completed=service.processNext();
        // 실패한 작업이 임대 만료를 기다리는 동안 빈 큐를 읽었다고 업무 복구를 선언하지 않는다.
        if(failed&&completed.isPresent()){failed=false;log.info("입금 모니터링 처리 재개");}
```

기존 처리 플래그와 전용 단일 스케줄러를 쓰고,1초 fixed delay는 작업 완료 시점부터 센다. 실패 뒤 빈 큐를 폴링한 것만으로 복구 로그를 남기지 않는다. 실제 작업 완료가 있어야 처리 재개를 기록하며 민감한 예외 원문은 로그에 싣지 않는다.

현재 운영 조회는 계정의 현재 정책/입력 위치·미처리 여부·과거 REVIEW_REQUIRED 기록 수를 제공한다. 과거 신호 수는 조사 사건 수가 아니며 같은 패턴을 후속 평가에서 다시 포착하면 여러 기록이 생길 수 있다. 이후 NO_SIGNAL이 나와도 과거 기록을 지우거나 해결됐다고 표시하지 않는다.

POST `/api/admin/deposits/BTC/{receipt}/monitoring-requests`는 현재 OPERATOR/APPROVER·5분 추가 인증으로 실제 수탁 원본의 재처리를 예약한다. 고객/AUDITOR는 생성할 수 없다. 금융 인식 전이면409다. 이미 처리한 위치는 ALREADY_CURRENT이며 임대나 순번을 초기화하지 않는다. 자동 전체 과거 자료 검색·가상 입력 생성·현재 가격으로 과거 채우기를 추가하지 않았다. 처리 통지가 없는 원본은 NOT_ASSESSED/AWAITING_PROCESSING으로 명시한다.

## 작성한 테스트와 기대 결과

신규 단위25개, PostgreSQL 통합14개, 기존 운영 인가 테스트에 HTTP2개를 추가했다. 아래 목록은 실제 함수명을 연결한다.

- 순수 정책16개: 정확히100만원 누계, 바로 아래 값의 반올림 금지, 횟수 부족, 한 거래5출력의 중복 횟수 금지, 미평가 출력의 전체 거래 제외, 알려진 신호+미확인 병존, 가격 원본 부재, 건별100만원 제외, 다른 네트워크의 같은 해시 구분,1001개 초과, 중복 순번·잘못된 통화·시계 역행·기간 밖 입력·0/음수값·무입력. 기대 결과는 신호/이 규칙 무신호/미완료의 정확한 구분이다.
- 워커9개: 비활성/미설정 폴링 금지, 실제 전용 스레드·중복 기동/겹침 금지, 완료 후1초 대기, 예외 뒤 후속 폴링, 정상 종료 후 재기동, 종료 무시 시 대체 실행 금지, test 프로필 제외, 실제 프로필 플래그/종료, 실패 후 빈 폴링의 거짓 복구 로그와 민감 원문 차단. Awaitility·실제 nanoTime·설치된 Logback을 사용한다.
- `oneRecognizedDepositHasDurableSourceAndCurrentMonitoringPosition`: 실제 인식·평가·큐·현재 위치 연결.
- `fiveDistinctSmallTransactionsCreateReviewSignalWithoutFinancialEffect`:5거래의1,049,382.63원 참고 누계로 신호, 사용 가능 잔액·제한 변경 없음.
- `oldMissingValuationIsNotRepairedWithCurrentPrice`: 나중 시세로 과거 미평가를 정상화하지 않음.
- `unchangedInputDoesNotCreateDuplicateAssessmentsOrAudit`: 같은 원장 위치의 중복 분석/감사 없음.
- `assessmentAuditFailurePreservesInputAndRetriesAfterLeaseExpiry`: 필수 감사 실패 시 확정 분석 없음, 임대 만료 뒤 원본으로 재처리.
- `concurrentProcessorsKeepOneAssessmentAndOneCurrentHead`: 동시 호출에서 같은 위치의 평가/현재 위치 하나.
- `incomingDepositDuringAssessmentIsNotLostFromQueue`: pg_stat_activity로 실제 사용자 행 대기를 확인하고 새 입금 알림이 유실되지 않음.
- `assessmentAndFinancialPositionsCannotBeRewrittenOrTruncated`: 평가·현재 위치·작업의 보존/단조 증가.
- `sqlCannotSubstituteDifferentFinancialSourceOrDropPartOfWindow`: 위조 JSON의 지문까지 다시 계산해도 원본 불일치·누락의 구체 제약 오류로 거부.
- `noCustodyInputDoesNotInventMonitoringOrFinancialPermission`: 무원본은 미평가, 금융 권한 생성 없음.
- `queueNeedsBusinessTransactionAndRejectsForeignOperation`: 독립 호출과 다른 고객 금융 원본 거부.
- `queueFailureRollsBackRecognitionButPreservesObservedReceipt`: 예약 실패 시 금융 반입 롤백·관찰 원본 보존.
- `overLimitSnapshotRemainsIncompleteInsteadOfBecomingPartialNoSignal`: 실제1001회 보류 반입에서 부분 정상 대신 미완료.
- `unqueuedSourceStaysExplicitlyUnassessedUntilRequested`: 통지 없는 실제 입력은 미평가로 보이고 명시 요청 후 처리.
- 기존 `BitcoinDepositReviewSourceIntegrationTest`에 `operatorAndApproverRequestActualInputWithoutRewritingAssessment`, `customerAndAuditorCannotCreateMonitoringWork` 추가: 실제 Spring 필터/컨트롤러로 역할·수탁 전409·반복 요청 멱등성 검증. 기존 조회 함수에는 금융 허가가 아닌 보증 문자열도 확인한다.

통합 테스트는 실제 PostgreSQL 원장/가격/입금 인식을 사용하고 외부 체인·공급사/가격 HTTP는 고정 대역으로 격리한다. 각 함수는 별도 DB다. 원장/감사 기록 삭제나 불변 제약 해제로 fixture를 초기화하지 않는다. 후처리에서 원장 합계0·사용 가능 잔액/엔진/Kafka 부작용 없음을 확인한다.

## 실행 명령과 결과

| 실제 실행 | 결과 |
| --- | --- |
| `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*SmallDepositMonitoringPolicyTest' --tests '*BitcoinDepositMonitoringWorkerTest' --console=plain` |25개 통과 |
| `python3 scripts/verify_deposit_monitoring.py` |14개 통과, 소유 PG 서버와 함수별14 DB 격리/정리 |
| `python3 scripts/verify_codevasp_transfer_inspections.py` |58개 통과 |
| `python3 scripts/verify_bitcoin_deposit_valuations.py` |14개 통과 |
| `python3 scripts/verify_engine_inbox.py` |182개 통과 |
| `CEX_BITCOIN_CORE_HOME=../.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py` |223개 통과, 실제 Core31.1 regtest·제한 RPC·16개 격리 그룹 |

마지막 실행에는 그룹별 JUnit XML을 별도 증적 폴더로 복사하는 수집 코드만 실행기 메모리 사본에 삽입했다. 테스트 선택·예상 개수·행동은 변경하지 않았다. 공급사/가격 환경을 제거하고 봇0·Kafka 자동 소비 금지·격리 주소를 사용했다. Core는 정상 종료, 소유 테스트 DB/컨테이너는 정리됐다. 기존5개 서비스 컨테이너는 유지했다.

최종 **516회/고유482개**, 실패·오류·제외0. 같은 운영 조회16개·금융 원장18개가 여러 실행기에서 중복된다. XML의 클래스+함수명으로 중복을 제거했다. 중간13개/단일 probe/이전 단위 실행은 최종에 재합산하지 않는다. 원본 XML/로그는 `.local/cycle116-proof`에, 커밋 가능한 요약은 아래 검증 JSON에 보존한다.

고객/운영자 JAR 빌드·모니터링 클래스/SQL 포함·시험 클래스 제외, Python 구문·실제 코드 발췌·링크·`git diff --check`도 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다. 실제 메인넷·외부 공급사·운영 인증/신고·장기 부하 검증을 수행한 결과는 아니다.

## 남은 범위

이 구현은 첫 거래 기반 규칙이며 전체 AML 탐지/조사/보고 완료가 아니다. 신호의 사건 결합·담당자 검토/근거/종결·오탐 관리·보고 필요성 판단·시장 감시가 남는다. 현재 고객 위험/제한·입금 출처 자료·신원 검토와 금융 허용 조건도 별도다. 실제 사용 가능 잔액과 엔진 반입은 아직 연결하지 않았다. 엔진 코어·WAL·Kafka 공개 계약은 변경하지 않는다.

## 작성한 테스트 코드


[SmallDepositMonitoringPolicyTest.java](../../src/test/java/dustin/cex/domains/monitoring/SmallDepositMonitoringPolicyTest.java)

- `fiveSmallTransfersAtExactAggregateBoundaryRequireReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aggregateJustBelowBoundaryIsNotRoundedUp`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `fewerTransfersDoNotMeetFrequencyEvenWhenSumIsLarge`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oneTransactionWithFiveOutputsIsCountedOnce`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownOutputMakesWholeTransactionUnclassified`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `knownSignalIsPreservedAlongsideUnknownTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingPriceDoesNotBecomeZeroOrLowRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `transferAtIndividualBoundaryIsOutsideSmallBand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sameHashOnDifferentNetworksIsNotCollapsed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceLimitNeverProducesPartialNoSignal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateLedgerCursorIsNotDoubleCounted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCurrencyCannotBeComparedAsWon`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `backwardsRecognitionClockDoesNotSilentlyDropFutureDatedInput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `outsideWindowIsNotAcceptedAsCurrentEvidence`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidValuedAmountDoesNotBecomeSmall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `absentSourceIsIncomplete`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinDepositMonitoringWorkerTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositMonitoringWorkerTest.java)

- `disabledOrUnconfiguredDoesNotPoll`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oneDedicatedThreadCannotOverlapOrCreateDuplicateExecutors`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `delayBeginsAfterPreviousWorkCompletes`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `failureDoesNotCancelLaterPolling`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `terminatedWorkerCanRestart`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ignoredShutdownCannotStartOverlappingReplacement`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `testProfileNeverStartsAutomaticMonitoring`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `productionHonorsFlagAndClosesItsExecutor`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `emptyPollingAfterFailureDoesNotClaimRecoveryBeforeACompletedJob`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[DepositMonitoringIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringIntegrationTest.java)

- `oneRecognizedDepositHasDurableSourceAndCurrentMonitoringPosition`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `fiveDistinctSmallTransactionsCreateReviewSignalWithoutFinancialEffect`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oldMissingValuationIsNotRepairedWithCurrentPrice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unchangedInputDoesNotCreateDuplicateAssessmentsOrAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `assessmentAuditFailurePreservesInputAndRetriesAfterLeaseExpiry`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentProcessorsKeepOneAssessmentAndOneCurrentHead`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `incomingDepositDuringAssessmentIsNotLostFromQueue`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `assessmentAndFinancialPositionsCannotBeRewrittenOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sqlCannotSubstituteDifferentFinancialSourceOrDropPartOfWindow`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `noCustodyInputDoesNotInventMonitoringOrFinancialPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `queueNeedsBusinessTransactionAndRejectsForeignOperation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `queueFailureRollsBackRecognitionButPreservesObservedReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `overLimitSnapshotRemainsIncompleteInsteadOfBecomingPartialNoSignal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unqueuedSourceStaysExplicitlyUnassessedUntilRequested`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 da23971](https://github.com/GiHoon1123/Cex-Platform-Service/commit/da23971c237e0a0216cfa9ef30876569c09edf8e)
- [격리 실행 증적](../reference/116-durable-native-deposit-monitoring-verification-2026-10-25.json)
