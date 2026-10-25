# 확정 원장의 직접·연쇄 전체 비우기 차단하기

확정 원장 두 표의 전체 비우기 우회를 차단한다. 행 삭제 방지와 문장 단위 TRUNCATE 방지가 다른 이유, 기존 금융 기록을 지우지 않고 시험을 준비하는 방법, 주문/정산/대사/실제 엔진 경로의 검증 범위를 설명한다.

## 문제와 기존 동작

보류 자산 원장 확장 전 검토에서 ledger_transactions와 ledger_entries의 불변 트리거가 UPDATE/DELETE 행에만 걸려 있음을 확인했다. TRUNCATE는 행 삭제 트리거를 실행하지 않으므로 SQL 권한이 있는 경로에서 전체 원장을 비울 수 있었다. API에 삭제 기능이 없다는 사실만으로 DB 원본의 불변성이 완성되지 않는다.

기존 OrderLedgerTest의 두 시나리오도 최초 반입 없는 상태를 만들기 위해 준비한 원장을 TRUNCATE했다. 이번 차단 후 그대로 실패한 것은 시험 준비가 더 강한 원본 보존 계약을 우회했기 때문이다. 시험을 위해 트리거를 끄지 않고 처음부터 최초 반입을 생략한 새 스키마를 사용하도록 바꿨다.

## 기존 제품과 공식 자료의 접근

PostgreSQL16 [TRUNCATE 문서](https://www.postgresql.org/docs/16/sql-truncate.html)는 ON DELETE 트리거 대신 ON TRUNCATE 트리거를 실행하고 CASCADE가 참조 표까지 비운다고 설명한다. 같은 공개 계약에 맞춰 두 표에 BEFORE TRUNCATE 문장 단위 트리거를 적용한다. 기존 cex_ledger_immutable 함수를 그대로 사용하고 새 원장 저장소/비교 스냅샷/호환 경로를 추가하지 않는다.

[Modern Treasury의 확정 원장 불변성](https://docs.moderntreasury.com/ledgers/docs/transaction-status-and-balances)처럼 과거 확정 항목을 수정/삭제하지 않고 정정은 새로운 역거래로 남기는 기존 방향을 유지한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 원장 거래/항목 각각 BEFORE TRUNCATE 차단 | 직접 항목 비우기와 부모 CASCADE 우회 모두 거부 | 유지보수/시험에서도 원본을 비울 수 없으므로 보존/역거래 또는 소유 시험 스키마 재생성이 필요 |
| 기존 불변 함수 재사용 | 오류 계약과 구현이 일관되고 행별 처리 비용 없음 | PostgreSQL 트리거 의존, 관리자 권한 분리/DDL 통제는 별도 필요 |
| 최초 원장 없는 시험은 처음부터 반입 생략 | 원본 삭제/트리거 비활성화 없이 실제 시작 상태 검증 | fixture 조건을 명시적으로 관리해야 하므로 JUnit 태그를 사용 |
| 전체 금융 회귀+실제 연결 경로 실행 | 삭제 방지 외에 기존 주문/정산/대사가 계속 동작하는지 확인 | 소유 PG/Kafka/네이티브 엔진 실행 자원 사용. 사용자 기존 서비스/DB에는 접속하지 않음 |

트리거를 제거/비활성화하거나 표를 삭제할 수 있는 관리자·슈퍼유저까지 코드만으로 통제했다고 주장하지 않는다. 운영 앱 역할의 DDL/TRUNCATE 권한 제한과 관리자 작업 감사/백업은 인프라 단계에서 확인해야 한다. 이번에 운영 권한/서비스를 변경하거나 배포하지 않았다.

## 구현과 관련 코드


출처: [schema.sql](../../src/main/resources/ledger/schema.sql#L27), 현재 소스의 부분 발췌.

```sql
-- 전체 비우기는 행 삭제 트리거를 실행하지 않는다. 확정 원본 두 표의 문장 단위 비우기도 별도로 차단한다.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_transactions_no_truncate' AND tgrelid='ledger_transactions'::regclass) THEN
        CREATE TRIGGER ledger_transactions_no_truncate BEFORE TRUNCATE ON ledger_transactions FOR EACH STATEMENT EXECUTE FUNCTION cex_ledger_immutable();
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_entries_no_truncate' AND tgrelid='ledger_entries'::regclass) THEN
        CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION cex_ledger_immutable();
    END IF;
END $$;
```

TRUNCATE는 표 전체에 대한 문장이므로 FOR EACH STATEMENT로 막는다. 직접 ledger_entries를 비우거나 ledger_transactions CASCADE로 두 표를 비우려 해도 원본 변경 전에 예외가 발생한다. 현재 스키마의 표를 regclass로 확인해 다른 시험 스키마의 같은 이름 트리거와 혼동하지 않는다.

출처: [OrderLedgerTest.java](../../src/test/java/dustin/cex/domains/ledger/OrderLedgerTest.java#L39), 현재 소스의 부분 발췌.

```java
        // 최초 반입 전 시나리오는 처음부터 빈 원장으로 시작한다. 확정 원본을 지워 시험 상태를 만들지 않는다.
        if(!test.getTags().contains("without-opening"))tx.executeWithoutResult(status->ledger.initializeOpening());
```

JUnit TestInfo의 태그를 읽어 without-opening 시나리오는 최초 반입을 실행하지 않는다. TypeScript 테스트의 beforeEach 조건과 비슷하지만 금융 원본을 뒤에서 지우는 준비 대신 각 테스트가 소유한 새 PG 스키마의 초기 상태를 명시한다.

출처: [OrderLedgerTest.java](../../src/test/java/dustin/cex/domains/ledger/OrderLedgerTest.java#L157), 현재 소스의 부분 발췌.

```java
    @Test void committedLedgerCannotBeTruncatedDirectlyOrThroughCascade() {
        long transactions=jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class);
        long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE ledger_entries"));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE ledger_transactions CASCADE"));
        assertEquals(transactions,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));
        assertEquals("MATCHED",reconciliation.run().status());
    }
```

두 우회가 실제 PostgreSQL에서 예외를 내는지, 거래/항목 수가 유지되는지, 독립 대사가 여전히 MATCHED인지 확인한다. 단순히 트리거 이름이 있다는 검사보다 실제 금융 원본 보존을 검증한다.

## 작성한 테스트

[OrderLedgerTest](../../src/test/java/dustin/cex/domains/ledger/OrderLedgerTest.java):

| 함수/준비 | 시나리오·기대 결과 |
| --- | --- |
| committedLedgerCannotBeTruncatedDirectlyOrThroughCascade 신규 | 직접 항목 비우기/부모 CASCADE 모두 거부, 거래/항목 수 유지, 대사 MATCHED |
| missingOpeningNeverAdoptsCurrentBalanceAutomatically 수정 | 처음부터 최초 반입 없는 원장, 현재 잔고를 자동 채택하지 않고 기록 거부 |
| openingRefusesFinancialHistoryEvenWhenBalancesLookValid 수정 | 처음부터 최초 반입 없이 과거 체결만 존재하면 최초 반입 거부 |
| setup(TestInfo) 수정 | 소유 새 스키마를 사용하고 without-opening만 반입 생략, 다른 시나리오는 기존 최초 원장 유지 |

주문 접수/멱등/수수료·체결 정산/재시도/원장/독립 대사/엔진 HTTP/시장 공개 등을 포함한 기존 선택179개에 신규1개를 더해180개를 실행했다. [MarketPathTest](../../src/test/java/dustin/cex/integration/MarketPathTest.java)는 실제 Rust 엔진 HTTP→자체 WAL→Kafka→플랫폼 정산/공개 소켓의1개 연결 시나리오를 추가 실행했다. 예약된 봇은0명이고 시험이 정해진 합성 주문을 명시적으로 보낸다.

## 실행 명령과 결과

```bash
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_market_path.py
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew operatorAdminJar --console=plain
```

두 Python 명령은 상위 호출에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 환경값을 제거한 환경으로 실행했다. 각 실행기가 새 PG와 필요한 격리 Kafka/엔진 자원을 만들고 test 프로필/봇0/격리 외부 주소를 적용했다.

macOS/Temurin21/PG16에서 **금융 회귀180개+실제 시장 경로1개=고유181개 통과**, 최종 실패/오류/제외0개다. bootJar와 operatorAdminJar를 빌드하고 두 JAR 모두 새 차단 스키마를 포함하며 시험 클래스가 제외됐는지 확인했다. 금융 PG 제거와 시장 경로의 PG/Kafka 제거를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 새 처리량 벤치/OSV 감사/실제 MAINNET/전체 BTC 노드 묶음 재실행은 하지 않았다. BTC 노드의 직전131개 결과는086 기록이며 이번181개와 섞지 않는다.

첫180개 실행은 기존 fixture의 TRUNCATE2개가 실패했다. 새 초기 상태로 바꾸는 과정에서 같은 줄의 호출1개를 누락한 두 번째 실행도180개 중1개가 실패했다. 남은 호출까지 제거하고 새 PG에서180개를 다시 실행한 후 실제 시장1개를 확인했다. 각 실패 로그는 .local/cycle87-proof/first-failure와 fixture-conversion-failure에 보존하며 최종 통과 수와 합산하지 않는다. 실패를 이유로 불변 트리거를 완화하지 않았다.

## 남은 연결

이 작업은 보류 원장 이전의 원본 보존 보강이다. BTC 보류 자산/고객 자격·위험 심사·원장/엔진 HTTP 반입·재조직 복구는 계속 이어야 한다. 엔진 코어의 상태 소유/스레드/락과 기존 금융 기능은 변경하지 않았다. 실제 DB 관리자 권한/복제·백업·복원과 운영 심사 충족은 별도 확인이다.


## 관련 커밋과 증적

- [구현 bc215f7](https://github.com/GiHoon1123/Cex-Platform-Service/commit/bc215f70200f58e6961cb162c256d76fcf042019)
- [격리 실행 증적](../reference/087-block-ledger-truncate-verification-2026-10-25.json)
