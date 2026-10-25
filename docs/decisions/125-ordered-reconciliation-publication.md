# 입금 대사의 현재 근거를 불변 시작 순번과 필수 감사로 공개하기

입금의 최신 대사 근거를 벽시계 대신 불변 시작 순번으로 고른다. 늦게 완료한 옛 성공이나 시각이 앞선 옛 실행이 더 새로운 진행/실패를 덮지 않도록 한다. 시작/완료 공개는 짧은 플랫폼 고객 잠금과 필수 감사로 확정하며, 외부 노드 읽기는 계속 트랜잭션 밖에서 수행한다.

후속 [126 현재 금융 조건과 영속 입금 의도](126-verified-vasp-bitcoin-credit-intents.md)는 이 현재 대사의 실제 증거와 체인 앵커를 자동 정상 정책에 연결한다. 의도 자체는 엔진/사용 가능 잔액 반영이 아니다. 아래125의 수치는 당시 범위다.

## 문제와 기존 동작

금융 반입 정책에 대사를 연결하기 전에 현재 근거의 정의를 확인했다. 기존 BitcoinDepositReviewSource는 started_at/id 역순의 첫 실행을 조회했다. 시계가 뒤로 움직이면 나중 시작의 시각이 더 이르고 옛 성공을 고를 수 있다. 늦게 끝난 옛 작업도 현재 위치를 되돌리지 않아야 한다.

기존 대사 시작/완료 쓰기는 금융 판단과 같은 고객 잠금을 사용하지 않았다. 금융 판단이 고객을 잠근 상태에서도 새 RUNNING 공개가 끼어들 수 있어 같은 잠금 기준으로 현재 근거를 포착할 수 없었다. 또한 감사 호출이 예외를 내면 롤백했지만 호출이 조용히 누락된 경우의 시작/완료 커밋 제약은 부족했다.

## 기존 구현과 공식 자료의 접근

기존 [CodeVaspDirectoryService](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryService.java)의 새 시작 공개와 늦은 완료 분리 패턴을 참고했다. 과거 성공을 현재 실패 대신 읽지 않는 기준을 입금 대사에도 적용한다. 다른 거래소가 내부에서 같은 SQL을 쓴다고 추측하지 않는다.

2026-10-26 [PostgreSQL16 시퀀스](https://www.postgresql.org/docs/16/functions-sequence.html)와 [명시적 잠금](https://www.postgresql.org/docs/16/explicit-locking.html)을 확인했다. 원자적으로 발급하는 순번과 트랜잭션의 고객 행 잠금을 함께 사용한다. **시퀀스 자체가 커밋 순서를 보장하는 것은 아니다.** 같은 고객의 짧은 시작 쓰기를 잠금으로 직렬화하며 서로 다른 고객의 순번을 전역 금융 처리 순서로 해석하지 않는다.

롤백하면 등록 행은 없어져도 시퀀스 값에 빈 번호가 생길 수 있다. 번호가 연속인지로 금융 기록 누락을 판정하지 않고 실제 확정한 등록을 읽는다. 새 큐·설정·저널 라이브러리를 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·대안·제약 |
| --- | --- | --- |
| 실행 생성과 같은 TX의 불변 시작 등록 | 시계/완료 역전과 무관한 현재 근거, 재시작 후 동일 판정 | 실행당 등록 행과 인덱스 쓰기 비용. 완료 시각 정렬·과거 성공 폴백은 사용하지 않음 |
| 새 등록의 최대 순번 조회 | 현재 실행이 RUNNING/FAILED여도 그대로 보임 | 현재 실행 실패가 해소될 때까지 금융 판단은 기다려야 함. 최신 성공만 골라 쓰지 않음 |
| 플랫폼 고객 잠금 아래 시작/완료 공개 | 금융 판단과 원본 공개를 같은 기준으로 직렬화 | 해당 고객의 플랫폼 쓰기와 짧게 경합. 엔진 코어의 단일 소유/공유 락 제거와 구분 |
| 노드 읽기는 TX 밖 유지 | 느린 RPC 동안 고객 잠금/스냅샷을 붙잡지 않음 | 시작·읽기·완료가 별도 단계이므로 RUNNING 상태와 후속 재검증이 필요 |
| 시작/완료 필수 감사의 지연 제약 | 조용히 감사 호출을 빼도 성공 확정 차단 | 감사/DB 장애 시 시작 또는 완료 공개가 롤백됨 |
| 옛 기록의 순번을 추측해 채우지 않음 | 금융 역사/시각을 고쳐 거짓 순서를 만들지 않음 | 새 기준 전의 실행만 있으면 현재 조회는 없음. 새 실제 대사를 수행해야 하며 과거 표/증거는 보존 |

## 현재 근거와 공개 계약

bitcoin_deposit_reconciliation_starts는 새 실행 INSERT의 트리거에서만 같은 TX로 등록한다. 직접 등록·수정·삭제·TRUNCATE를 거부한다. BEFORE 실행 INSERT에서도 실제 소유 고객을 잠가 직접 SQL 시작이 순번 직렬화를 우회하지 못하게 한다. 애플리케이션의 시작 쓰기도 같은 사용자 잠금을 먼저 명시한다.

bitcoin_current_deposit_reconciliation은 입금별 최대 등록 순번의 실행을 반환한다. 새 실행이 실패하거나 진행 중이면 그 실행을 보여 주며 과거 성공으로 돌아가지 않는다. 완료는 자기 실행의 결과/증거만 갱신하고 시작 등록을 바꾸지 않는다. 기존 시각 정렬 경로는 현재 조회에서 제거했다.

대사의 시작과 완료 쓰기는 각각 고객을 잠근 짧은 TX다. 두 TX 사이의 실제 노드 읽기는 잠금/트랜잭션 밖이다. 재시작/지연·RPC 장애로 남은 RUNNING을 임의 성공으로 바꾸지 않는다. 시작 감사가 없으면 실행/등록/플랫폼 증거 전체를 커밋하지 못한다. 완료 감사가 없으면 시작은 보존하지만 완료/노드 증거/차이 기록은 롤백돼 현재 RUNNING으로 남는다.

이 대사는 기존 PENDING_RECEIPT 범위를 유지한다. 사용 가능 잔액·엔진 반영 완료나 모든 수탁 부채 대사를 증명하지 않는다. 엔진 감사 조회·코어 큐·매칭 상태·공유 락·출력 계약은 변경하지 않았다.

## 실제 코드와 문법

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L430), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS bitcoin_deposit_reconciliation_starts (
 position bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 run_id uuid NOT NULL UNIQUE REFERENCES bitcoin_deposit_reconciliation_runs(id),
 receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
```

IDENTITY는 기존 DB의 순번 생성 기능이다. 등록은 실행 생성 TX에 함께 들어가고 성공 감사가 있어야 커밋한다. 과거 실행의 원본을 UPDATE하거나 시각으로 순번을 추정하지 않는다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L480), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE VIEW bitcoin_current_deposit_reconciliation AS
 SELECT DISTINCT ON(s.receipt_id) r.*,s.position start_position,s.registered_at
 FROM bitcoin_deposit_reconciliation_starts s JOIN bitcoin_deposit_reconciliation_runs r ON r.id=s.run_id
 ORDER BY s.receipt_id,s.position DESC;
```

PostgreSQL DISTINCT ON과 정렬로 입금마다 가장 큰 확정 등록을 선택한다. status=MATCHED 조건을 붙여 최신 실패를 숨기지 않는다. 가상 뷰이므로 별도 메모리 캐시/갱신 워커는 없다.

출처: [BitcoinDepositReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationService.java#L48), 현재 소스의 부분 발췌.

```java
            // 시작 공개와 금융 판단은 같은 플랫폼 고객 잠금을 따른다. 이 잠금은 아래 노드 조회 전에 해제한다.
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,captured.receipt().user());
            jdbc.update("""
                INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status)
```

이 플랫폼 DB 잠금은 시작 공개 TX가 끝날 때 해제한다. Java 메서드 전체에 @Transactional을 걸어 외부 RPC를 같은 잠금 안으로 끌어들이지 않는다. TypeScript에서도 네트워크 await를 DB TX 안에서 하면 DB 잠금이 자동 해제되지는 않는다.

출처: [BitcoinDepositReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationService.java#L80), 현재 소스의 부분 발췌.

```java
            // 외부 조회가 끝난 뒤에만 다시 잠근다. 오래 걸린 옛 실행의 완료가 새 시작 순번을 바꾸지는 않는다.
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,captured.receipt().user());
            if(nodeEvidence!=null)evidence(run,"NODE",nodeEvidence);
            for(var issue:issues)jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_issues(run_id,code,expected,actual) VALUES(?,?,?,?)",run,issue.code(),issue.expected(),issue.actual());
```

외부 읽기가 끝난 뒤 완료를 별도로 공개한다. 옛 실행이 늦게 끝나도 자신의 결과만 바뀌고 새 시작 순번은 유지한다.

## 작성한 테스트

[BitcoinDepositReviewSourceIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositReviewSourceIntegrationTest.java)에 실제 PG와 정상 제약의 원본을 사용한8개를 추가했다. 기존16개를 포함한24개를 실행했다.

| 새 함수 | 시나리오·기대 결과 |
| --- | --- |
| laterRegisteredStartWinsEvenWithAnEarlierWallClock | 옛 기록 수정 없이 더 이른 시각의 새 실행 생성, 순번 기준 새 RUNNING 선택 |
| lateOldSuccessCannotReplaceANewerFailure | 옛 RPC 지연/새 FAILED 완료 뒤 옛 MATCHED 완료, 새 FAILED 유지 |
| startPublicationWaitsForCustomerLockBeforeNodeRead | 고객 잠금 중 시작 공개/노드 호출이 기다리고 해제 뒤 정상 완료 |
| completionPublicationWaitsForCustomerLockAfterNodeRead | RPC 후 완료 공개가 고객 잠금 해제까지 기다림, 그동안 RUNNING |
| nodeReadRunsOutsideCustomerTransaction | 실제 서비스의 외부 읽기 대역에서 Spring TX 비활성 확인 |
| missingStartAuditCannotCommitTheRunOrSequence | 조용한 시작 감사 누락 시 실행/등록 롤백·노드 호출0 |
| missingFinishAuditLeavesTheCurrentRunRunning | 조용한 완료 감사 누락 시 RUNNING·플랫폼 증거만 보존 |
| startSequenceCannotBeForgedRewrittenOrTruncated | 등록의 직접 위조/변경/삭제/초기화 거부 |

[BitcoinDepositReconciliationIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDepositReconciliationIntegrationTest.java)의 정상 실행 fixture는 실제 시작 감사를 같은 TX에 남기도록 수정했다. 무결성 트리거를 끄거나 금융/감사 이력을 삭제하지 않았다. RUNNING 조회도 현재 순번 뷰로 맞췄다.

## 실행 명령과 결과

```sh
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/bin/caffeinate -i python3 scripts/verify_bitcoin.py
```

2026-10-26 실제 Bitcoin Core31.1의 소유 regtest·제한 RPC/관찰 지갑·묶음별 새 PostgreSQL16에서 **고유231개/231회** 통과했다. 실패·오류·제외0. 기존223개에 이번8개가 추가된 수다. 주소·발견·확정/충돌/재조직·보류·실제 노드 대사·고객/운영 조회·네트워크/원장 회귀와 새 공개 순서를 포함한다. Java/Rust의 다른 과거 결과를 합산하지 않는다.

처음 실행은 필수 Core 경로 환경이 없어 시험 전에 종료했다. 경로를 지정한 중간231개 성공 뒤 직접 SQL 시작의 고객 잠금도 보강하고 전체231개를 다시 실행했다. 중간 실행은 최종 통과 수에 넣지 않는다. 실행기는 외부 CODEVASP/VALUATION 환경도 제거하고 Kafka 자동 소비·코드 사업자/개인정보 워커·외부 가격/메일 전달을 명시적으로 끈다. 봇0을 유지한다. 소유 노드와 묶음별 컨테이너를 정리했고 기존5개 컨테이너는 유지했다.

최종 JSON/그룹별 함수·DB·정리 결과/로그는 `.local/cycle125-proof`에 보존한다. 플랫폼 웹/운영 초기화 CLI JAR 빌드·SQL/클래스 포함·시험 제외, Python 구문·발췌/상대 링크·공백 검사를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 메인넷/공급사·운영 키·장기 부하·시계 동기화·DB 관리자 방어 완료를 의미하지 않는다.

## 남은 범위

자동 금융 반입 조건·안정 의도 저장·엔진 호출/재확인·확정 Kafka/플랫폼 원장·잔액 연결은 계속 진행한다. 이 변경은 그 판단이 읽을 현재 대사 근거를 확실히 정의하는 선행 보완이다. 논리 순서와 시각 신선도는 다르므로 순번을 도입해도 운영의 시계 동기화/신선도 조건이 불필요해지는 것은 아니다.

등록/감사 트리거는 일반 애플리케이션 DML을 통제한다. 권한 있는 DB 관리자의 DDL·트리거 해제·시퀀스 초기화까지 코드만으로 방어했다고 주장하지 않는다. DB 계정 분리·접근/변경 통제·백업/복원은 운영 검증 사항이며 호스트 외부 백업 구현은 기존 사용자 지시대로 하지 않았다.

## 관련 커밋과 증적

- [구현 d01c1e3](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d01c1e327707baaf13d8b2b5f9e6478b0c291a64)
- [격리 실행 증적](../reference/125-ordered-reconciliation-publication-verification-2026-10-26.json)
