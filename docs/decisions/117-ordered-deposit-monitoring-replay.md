# 밀린 입금의 과거 검토 신호를 원장 순번 재생으로 보존하기

처리 지연 뒤 최신 입금만 분석하던 경로를 제거한다. 고객별 미처리 원장을 한 순번씩 확정해, 최신 시간 창 밖으로 밀려난 과거 신호도 놓치지 않도록 한다. 기존 금융 기록과 확정 평가를 수정하거나 처리 순번을 되돌리지는 않는다.

후속 [118 원본 검증/담당자 의견](118-verified-monitoring-evidence-and-analyst-observations.md)은 당시 입력을 검증해 열람하고 암호화 의견·접근 증적·필수 감사를 연결한다. 사건 종결·금융 허가와 구분한다.

후속 [119 준비된 입력 연속 처리](119-drain-ready-deposit-monitoring-inputs.md)는 아래117 당시의 건별1초 대기를 제거한다. 순번/입력별 TX는 유지하며 빈 큐·실패 때만 지연한다. 현재 워커를 초당1건 미만으로 제한한다고 해석하지 않는다.

## 문제와 기존 동작

116의 영속 예약은 새 금융 입력을 잃지 않았지만, 처리 시점에 고객의 **최신** 입력을 기준으로24시간 창을 한 번만 분석했다. 이 방식은 현재 잔액처럼 마지막 값만 필요한 조회와 달리, 중간 시점의 위험 신호를 보존해야 하는 탐지에는 충분하지 않았다.

예를 들어 이틀 전 가까운 시각에5건의 소액 입금으로 신호가 형성됐고,25시간 뒤 입금1건이 추가된 상태에서 워커가 다시 시작하면 이전 구현은 마지막1건의 창만 계산했다. 큐는 처리 완료처럼 보이지만 과거 신호가 평가 이력에 없게 된다. 후속 검토에서 이 누락을 발견해 바로 수정했다.

## 기존 제품·공식 자료의 접근

2026-10-25 확인한 [Confluent Kafka Streams 개념](https://docs.confluent.io/platform/current/streams/concepts.html)은 불변 입력의 재생 가능한 순서와 각 기록을 처리하는 스트림, 최신 상태를 표현하는 테이블을 구분한다. 탐지는 처리 대상 입력을 모두 반영해야 한다는 접근을 참고했다. 이 작업에서 Kafka Streams나 새 저장소를 도입하지 않았다. 설치된 JDBC·PostgreSQL의 영속 원장 순번으로 같은 요구를 충족한다.

[PostgreSQL16 SELECT](https://www.postgresql.org/docs/16/sql-select.html)의 ORDER BY/LIMIT과 SKIP LOCKED를 사용한다. 임대 확보는 작업 큐의 경합 회피이고, 실제 다음 입력 선택은 고객→작업 잠금 아래 순서대로 수행한다.116의 FATF/KoFIU 참고 근거와 자체 정책 수치는 바뀌지 않는다. 법정 기준을 새로 만든 수정이 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 최신 위치로 합치던 처리를 제거하고 다음 원장 입력 하나씩 확정 | 중간 시간 창의 신호를 빠뜨리지 않고 중단 후 남은 위치에서 재개 | 입금 수만큼 분석/감사 기록이 생기고 같은 창을 반복 읽는 DB·저장 비용이 든다 |
| 분석 결과·현재 위치·처리 순번 같은 TX | 분석만 남거나 순번만 완료되는 부분 성공 방지 | 감사·DB 장애 시 해당 위치에서 멈추고 임대 만료 후 다시 처리한다 |
| DB에서도 다음 입력과 같은 TX의 확정 평가 요구 | SQL로 관찰 위치를 건너뛰거나 처음부터 완료라고 만드는 우회 차단 | 원본·평가·현재 위치를 추가 조회한다. DB 관리자 DDL 권한 통제는 별도다 |
|117 당시: 기존 장수 워커와 고정된1건 처리 유지(119에서 대기 제거) | 변경 범위와 고객 잠금 시간을 제한 | 현재 단일 워커는 작업 완료 뒤1초를 기다려 전역 처리량이 초당1건 미만이다. 높은 입금률의 대기 적체/처리량 검증·개선은 후속 운영 성능 과제이며 엔진 주문 처리량과 다르다 |

입금 급증 시 최신 상태만 남기는 대안은 비용이 적지만 필요한 과거 신호를 잃는다. 무제한 한 트랜잭션 재생은 고객 잠금/실패 범위를 키우므로 채택하지 않았다. 정책 파라미터·공유 추상화·설정값을 새로 만들지 않는다. 단일 엔진 코어에는 조회·잠금·네트워크 대기를 추가하지 않았다.

## 구현과 관련 코드·문법

출처: [DepositMonitoringService.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringService.java#L56), 현재 소스의 부분 발췌.

```java
        long observed=((Number)jobs.getFirst().get("observed_cursor")).longValue();
        var anchor=jdbc.queryForMap("SELECT input_cursor,recognized_at FROM deposit_monitoring_inputs WHERE user_id=? AND input_cursor>? ORDER BY input_cursor LIMIT 1",claim.user(),observed);
        long cursor=((Number)anchor.get("input_cursor")).longValue();Instant reference=((Timestamp)anchor.get("recognized_at")).toInstant();
```

작업의 이미 확정한 원장 순번 다음에서 가장 작은 실제 입력을 선택한다. 금액·시각을 요청에서 받아 재생하지 않는다. long은 정수 순번이며 TypeScript number의 정수 정밀도 한계를 그대로 적용하지 않는다. JDBC의 Number.longValue로 PG bigint를 읽는다.

출처: [deposit-schema.sql](../../src/main/resources/monitoring/deposit-schema.sql#L80), 현재 소스의 부분 발췌.

```sql
 IF TG_OP='INSERT' THEN
  IF NEW.observed_cursor<>0 THEN RAISE EXCEPTION '새 모니터링 작업은 미처리 위치에서 시작해야 합니다';END IF;
 ELSIF NEW.observed_cursor<>OLD.observed_cursor THEN
  IF NEW.observed_cursor IS DISTINCT FROM (SELECT min(input_cursor) FROM deposit_monitoring_inputs WHERE user_id=NEW.user_id AND input_cursor>OLD.observed_cursor)
   OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_assessments a JOIN deposit_monitoring_heads h ON h.assessment_id=a.id
    WHERE a.user_id=NEW.user_id AND h.user_id=NEW.user_id AND a.input_cursor=NEW.observed_cursor AND a.created_txid=txid_current()) THEN
   RAISE EXCEPTION '모니터링 처리 위치는 다음 입력의 확정 평가가 필요합니다';END IF;
 END IF;
```

새 작업의 observed_cursor는0이어야 한다. 변경 시에는 이전 위치 다음의 최소 실제 입력과 같은 트랜잭션의 평가/현재 위치가 있어야 한다. TG_OP는 PostgreSQL 트리거의 작업 종류이고 IS DISTINCT FROM은 NULL까지 비교한다. 목표 알림 갱신과 임대 확보는 처리 순번을 바꾸지 않는다.

출처: [DepositMonitoringIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringIntegrationTest.java#L104), 현재 소스의 부분 발췌.

```java
    @Test void delayedProcessingPreservesEarlierSignalAfterWindowHasMoved(){
        Instant old=Instant.ofEpochMilli(Instant.now().minusSeconds(172800).toEpochMilli());
        for(int i=0;i<5;i++)historicalDeposit(old.plusSeconds(i));historicalDeposit(old.plusSeconds(90000));
        long previous=0;for(int i=0;i<6;i++){
            var a=monitoring.processNext().orElseThrow();assertTrue(a.inputCursor()>previous);previous=a.inputCursor();
            assertEquals(i==4?"REVIEW_REQUIRED":"NO_SIGNAL",a.result().outcome());
            assertEquals(i<5,summary().newerInputsPending());
        }
        assertEquals(6,count("deposit_monitoring_assessments"));assertEquals(1,summary().historicalSignals());assertEquals(1,summary().smallTransfers());assertTrue(monitoring.processNext().isEmpty());
```

과거 시각은 원본을 처음 삽입할 때만 지정한다. 실제 DB의 원장·당시 평가·큐·분석을 실행하고6개의 순번이 전진하는 동안5번째 신호와6번째 무신호를 모두 확인한다. 합성 과거 가격/입금 fixture이며 실제 과거 체인 장애를 재현했다는 주장은 아니다. 불변 원본 수정·트리거 해제·시스템 시계 변경은 하지 않았다.

## 작성한 테스트와 기대 결과

[DepositMonitoringIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringIntegrationTest.java)에 다음2개를 추가했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| delayedProcessingPreservesEarlierSignalAfterWindowHasMoved | 과거5입금+25시간 뒤1입금이 모두 대기한 상황에서6개 입력을 순서대로 분석.5번째 REVIEW_REQUIRED,6번째 NO_SIGNAL, 과거 신호1개 보존, 마지막에만 미처리 해소 |
| sqlCannotSkipUnassessedInput | 실제2입금 뒤 SQL로 마지막 순번까지 완료 변경을 시도하면 구체 제약 오류. 새 작업을 완료 위치로 삽입해도 초기 위치 제약으로 거부. 정상2회 처리 후 미처리 해소 |

기존 `fiveDistinctSmallTransactionsCreateReviewSignalWithoutFinancialEffect`는1~4번째 무신호 뒤5번째 신호를 확인하도록 수정했다. `sqlCannotSubstituteDifferentFinancialSourceOrDropPartOfWindow`는2개 입력 각각을 처리한 뒤 원본 변조/누락을 검사한다. `overLimitSnapshotRemainsIncompleteInsteadOfBecomingPartialNoSignal`은 실제1001개 보류 원장과1001개 순번 분석으로 상한 판정을 검증한다. 처리 위치를 시험 편의상 건너뛰지 않는다. 나머지 감사 실패/임대 재시도·중복·동시 처리·새 입금 경합·미평가·불변 원본·무원본·통지 누락 회귀도 유지했다.

## 실행 명령과 결과

- `python3 scripts/verify_deposit_monitoring.py`: 함수별16개 DB·소유 PostgreSQL16 서버,16개 통과. 실패·오류·제외0.1001개 경계도 포함한다.
- `python3 scripts/verify_codevasp_transfer_inspections.py`: 개인정보 검토18·최소 전송 조회11·입금 출처 조회13·운영 현재 인증/조회16=58개 통과. 별도 소유 PG 그룹으로 격리했다.
- 최종 고유74개/74회, 실패·오류·제외0. 원본 XML/로그와 정리 결과는 `.local/cycle117-proof`에 보존한다. 앞116의482개 검증을 이번 수정 뒤 전체 재실행한 수치로 재사용하지 않는다.
- 실행기는 봇0·Kafka 자동 소비 금지·외부 단말/공급사/엔진 격리 조건을 적용한다. 소유 시험 컨테이너는 정리했으며 기존 서비스 컨테이너는 유지했다.
- 고객/운영자 JAR 빌드·SQL/클래스 포함·테스트 제외, Python 구문·실제 코드 발췌·링크·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

## 남은 한계

현재 기록된 원장 입력을 순서대로 처리하는 계약이며, 워커의 장기 적체 감시·실제 처리량 최적화는 남는다.116의 확정된 과거 평가/기록을 새 의미로 덮어쓰거나 이미 처리한 순번을 되돌리지 않는다. 아직 실제 서비스로 배포하지 않았으며 과거 운영 기록의 누락을 자동 복구했다고 주장하지 않는다.

개별 탐지 이력을 조사 사건에 묶고 검토·종결·보고 판단으로 연결하는 업무, 현재 위험·출처·고객 자격을 반영한 금융 허가와 사용 가능 잔액/엔진 반입은 여전히 별도 작업이다. NO_SIGNAL이 과거 신호를 해소하거나 금융 사용을 허가하지 않는다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 fcc3bf3](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fcc3bf3497ac4b4eff81901ceedcd91209a16136)
- [격리 실행 증적](../reference/117-ordered-deposit-monitoring-replay-verification-2026-10-25.json)
