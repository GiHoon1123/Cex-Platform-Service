# 입금 검색 요청 ID를 외부 접수 전에 영속 예약하기

[098 거래 검색](098-codevasp-transaction-origin-search.md)을 실제 입금 원본에 연결하기 전에 요청 ID를 PostgreSQL에 확정한다. 같은 입금/접속 주체의 재시도·동시 처리·재시작이 같은 ID를 사용하며, 새 검색 예약에는 현재 성공한 기술 목록이 필요하다. 아직 외부 검색/폴링·금융 승인·잔액 반영을 실행하지 않는다.

## 문제와 기존 동작

098 클라이언트는 호출자가 제공한 요청 ID를 정확히 서명/응답에 결합한다. 그러나 호출자가 재시작 때 ID를 잃고 새 ID를 만들면 이미 접수된 검색과 결과를 연결할 수 없다. 사용자 입력 거래/주소를 그대로 검색하면 실제 입금과 다른 대상을 검증할 수도 있다. 외부 호출보다 먼저 불변 입금 원본과 요청 ID를 확정해야 한다.

## 기존 제품과 공식 자료의 접근

- [CodeVASP 검색 접수](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/11-Search-VASP-by-TXID-Request)는 요청 ID와 거래·수신 주소를 받고, 결과는 같은 ID로 조회한다. 같은 ID의 POST 재접수가 공급사에서 멱등 처리된다는 보장은 공개 문서에서 확인하지 못했으므로 이번 예약을 외부 효과의 정확히1회 보장으로 설명하지 않는다.
- [AWS transactional outbox 패턴](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)은 외부 전달에 앞서 업무와 전달할 내용을 같은 DB에서 확정하는 접근이다. 이번 단계는 그 선행 예약까지만 구현한다. 출력 워커/임대/결과 불명 처리는 미완료다.
- [PostgreSQL16 자문/행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html)을 재사용한다. 같은 입금·접속 주체의 예약만 짧게 직렬화하고 현재 기술 목록은 공유 잠금으로 고정한다. 원본은 기존 불변 입금/주소 표를 참조하며 중복 JSON 본문·새 저장 라이브러리를 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·검토한 대안 |
| --- | --- | --- |
| 입금 ID·접속 주체별 영속 UUID1개 | 동시/재시작에도 같은 결과 조회 ID 유지 | 공급사 원점·등록 주체·공개키 변경은 다른 검색으로 분리된다. 이것이 금융 입금 ID를 바꾸지는 않음 |
| 불변 입금/주소 원본 참조 | 고객 입력과 실제 입금의 불일치 방지·중복 저장 감소 | 원본 표를 보존해야 함. 삭제/UPDATE/TRUNCATE가 금지된 기존 계약을 전제로 함 |
| 새 예약에 현재 성공한 목록 결합 | 미완료/실패/오래된 자료로 새 외부 업무 개시 방지 | 공급사 장애 시 새 예약 보류. 이미 확정한 예약을 다시 읽는 데 새 목록 성공을 요구하지 않아 안정 ID를 잃지 않음 |
| 감사와 예약을 같은 TX로 확정 | 감사 없는 검색 원본이 남지 않음 | 감사 장애 때 예약 실패. 이 TX 안에서 외부 검색은 하지 않으므로 외부 접수 결과를 롤백했다고 주장하지 않음 |
| regtest 검색은 명시적 루프백 원점만 | 시험망 거래가 외부 등록 공급사로 잘못 전달되는 기본 경로 차단 | 루프백 서버의 프록시 동작까지 증명하지 않음. 실제 공급사 regtest 지원을 가정하지 않으며 실제 테스트망 연동은 별도 확인 필요 |
| 읽기/예약 내부 서비스만 제공 | 아직 완성되지 않은 금융/운영 권한 경계를 노출하지 않음 | 사용자/운영자 HTTP API와 자동 실행은 후속. 내부 get은 고객별 HTTP 인가를 대체하지 않음 |

## 구현과 관련 코드

[CodeVaspDepositSearches](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearches.java)는 reserve(receiptId)와 내부 get(searchId)을 제공한다. 외부 트랜잭션 안에서 예약할 수 없다. 같은 입금/주체의 기존 예약이 있으면 같은 원본을 돌려준다. 새 예약은 directory.current()로 현재 위치를 같은 TX에서 고정하고, 입금·등록 주소에서 실제 요청 DTO를 구성해 해시/주소의 어휘 조건도 검사한다. 원본이 없으면404, 현재 자료 없음은503, 감사 실패는 TX 롤백이다. 원문 거래/주소는 감사/로그에 출력하지 않는다.

[travelrule/schema.sql](../../src/main/resources/travelrule/schema.sql)의 codevasp_deposit_searches는 다음만 저장한다.

| 필드 | 의미 |
| --- | --- |
|id | 외부 검색 접수/결과 조회에 사용할 안정 UUID |
|receipt_id | 원본 금융 입금 식별자 |
|source_id | 공급사 원점·등록 주체·등록 공개키의 식별자 |
|directory_attempt_id | 예약 시점의 현재 성공한 기술 목록 시도 |
|created_at | DB 예약 시각 |

UNIQUE(receipt_id,source_id)와 외래 키·입력 트리거·불변 트리거를 둔다. 트리거는 현재 목록 위치 FOR SHARE, SUCCESS·5분 현재성·미래가 아닌 결과 기록 시각을 검사한다. 입금의 BTC 자산·주소/사용자/네트워크 결합·플랫폼 DB의 고정 네트워크도 확인한다. 시험망이면 공급사 원점이 명시적127.0.0.1/[::1] 루프백 형태여야 한다. UPDATE/DELETE/TRUNCATE CASCADE는 거절한다. DB 관리자/트리거 비활성화/호스트 손실까지 막는 보장은 아니다.

[CodeVaspSchemaInitializer](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspSchemaInitializer.java)에 기존 bitcoinSchemaInitializer 선행 조건을 추가했다. 참조하는 입금 표가 먼저 준비되어야 하기 때문이다. H2 정산 시험은096에서 추가한 명시적 초기화 모의 처리를 유지하며 제품의 PostgreSQL 검증을 우회하지 않는다.

저장된 directory_attempt_id는 예약 시점의 이력이다. 실제 외부 결과 사용/금융 승인 때 현재 목록과 고객/위험/송수신인 근거를 다시 확인해야 한다. 예약 존재만으로 잔액을 올리거나 엔진에 입금을 보내지 않는다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDepositSearches.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearches.java#L35), 현재 소스의 부분 발췌.

```java
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-deposit-search-v1:"+receiptId+":"+source);
            var existing=jdbc.queryForList("SELECT id FROM codevasp_deposit_searches WHERE receipt_id=? AND source_id=?",UUID.class,receiptId,source);
            if(!existing.isEmpty())return load(existing.getFirst(),source);
            var snapshot=directory.current();
            var receipts=jdbc.queryForList("SELECT b.txid,d.address FROM bitcoin_deposit_receipts b JOIN bitcoin_deposit_addresses d ON d.id=b.address_id WHERE b.id=? AND b.asset='BTC'",receiptId);
            if(receipts.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 원본을 찾을 수 없습니다");
            UUID id=UUID.randomUUID();
            // 실제 전송할 DTO도 지금 구성해 불량 해시/주소 원본을 전송 가능한 예약으로 남기지 않는다. 원문을 로그에 출력하지 않는다.
            new CodeVaspTransactionClient.Request(id,(String)receipts.getFirst().get("txid"),(String)receipts.getFirst().get("address"));
            jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id) VALUES(?,?,?,?)",id,receiptId,source,snapshot.attemptId());
```

같은 입금/주체를 짧게 직렬화하고 이미 확정한 요청은 그대로 반환한다. 새 요청만 현재 성공 목록을 요구한다. UUID.randomUUID는 최초 예약에서만 만들며 감사와 함께 커밋된 뒤 외부 요청에 사용해야 한다. TypeScript에서도 메모리 Map이 아닌 같은 DB 트랜잭션의 유일 키가 재시작/복수 프로세스 중복을 막는다.

출처: [schema.sql](../../src/main/resources/travelrule/schema.sql#L65), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS codevasp_deposit_searches (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 source_id varchar(64) NOT NULL REFERENCES codevasp_directory_sources(id),directory_attempt_id bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(receipt_id,source_id),
 FOREIGN KEY(directory_attempt_id,source_id) REFERENCES codevasp_directory_attempts(id,source_id)
);
CREATE OR REPLACE FUNCTION codevasp_deposit_search_source() RETURNS trigger LANGUAGE plpgsql AS $$
```

검색 ID와 금융 입금 ID를 구분한다. 같은 입금/주체의 예약은 하나이며 목록의 시도와 주체를 복합 외래 키로 묶는다. 요청 본문은 불변 입금/주소에서 다시 구성하므로 변할 수 있는 고객 입력이나 JSON 복사본이 원본을 대신하지 않는다.

출처: [schema.sql](../../src/main/resources/travelrule/schema.sql#L74), 현재 소스의 부분 발췌.

```sql
 PERFORM h.source_id FROM codevasp_directory_heads h
 JOIN codevasp_directory_attempts a ON a.id=h.attempt_id AND a.source_id=h.source_id
 JOIN codevasp_directory_results r ON r.attempt_id=a.id AND r.source_id=a.source_id
 WHERE h.source_id=NEW.source_id AND h.attempt_id=NEW.directory_attempt_id AND r.status='SUCCESS' AND r.recorded_at<=clock_timestamp()
  AND a.started_at BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()
 FOR SHARE OF h;
 IF NOT FOUND THEN RAISE EXCEPTION '거래 검색 예약에 현재 성공한 사업자 목록이 필요합니다'; END IF;
 IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts b
```

입력 트리거도 현재 위치를 공유 잠금으로 고정한다. SUCCESS인 결과라도 미래 recorded_at이거나 시작5분을 넘기면 새 예약 근거가 아니다. 상위 서비스만 검사하고 직접 SQL 경로를 무방비로 두지 않는다. 이 과거 시도 ID는 금융 승인 시의 현재성 보증서가 아니다.

## 작성한 테스트

[CodeVaspDepositSearchIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspDepositSearchIntegrationTest.java)12개를 새로 작성했다. 기존 [BitcoinDepositFixtures](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositFixtures.java)의 부모→원본 순서와 불변 트리거를 유지하고, 이 시험만 어휘가 맞는 합성 주소를 사전 삽입했다. 실제 체크섬/키 보유/노드 관찰을 증명하는 시험이 아니다. 가짜 RPC 루프백:1은 호출 불가이며 네트워크 결합 초기화는 노드 증명이 아님을 구분한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|reservationBindsStableRequestToImmutableReceiptWithoutExternalSubmission | 입금/거래/수신 주소·요청 ID·현재 목록 결합·내부 재조회 일치·원문 문자열 출력 제외 |
|restartAndLaterDirectoryFailureDoNotReplaceAlreadyReservedIdentity | 후속 목록 실패/서비스 재생성 뒤도 같은 예약 ID/원본 유지 |
|concurrentReservationCommitsOneIdentityAndOneMandatoryAudit | 동시2요청에 같은 결과/예약1개·필수 감사1개 |
|newerDirectoryDoesNotRewriteOriginalReservationEvidence | 새 목록이 있어도 이미 확정한 예약 시점 근거는 그대로 |
|distinctReceiptsHaveDistinctRequestsEvenWhenAddressIsShared | 같은 주소의 서로 다른 입금은 별도 ID. 자금 효과의 중복 방지는 금융 입금 ID 책임 |
|changedAuthorityCannotReadOldRequestAndNeedsItsOwnFreshDirectory | 접속 주체 변경 시 옛 ID404, 새 현재 자료 없으면503, 새 자료 뒤 별도 검색 예약 |
|failedCurrentDirectoryAndMissingReceiptCannotCreateReservation | 없는 입금404·실패 목록503·예약0 |
|mandatoryAuditFailureRollsBackReservationBeforeAnySubmission | 감사 장애 시 예약0, 정상 재시도 후1개·외부 검색0 |
|immutableReservationAndCurrentDirectoryTriggerRejectDirectBypass | UPDATE/DELETE/TRUNCATE CASCADE·과거 목록/없는 입금 직접 삽입 거부·원본 보존 |
|futureRecordedDirectoryCannotBeUsedThroughDirectSqlBypass | 새 목록 결과를 처음부터 미래 기록 시각으로 구성, 서비스503·직접 SQL도 거부, 불변 트리거 해제/기존 원본 수정 없음 |
|testChainCannotBeReservedAgainstExternalProviderOrigin | 모의 외부 원점의 기술 목록이 있어도 regtest 예약 거부·외부 검색0 |
|ambientTransactionAndDisabledSourceDoNotReserve | 외부 TX/미설정에서 예약 안 함 |

모든 시험 뒤 실제 검색 클라이언트·엔진·Kafka 생산자 호출0/사용 가능 잔액 표0을 확인한다. 이는 서비스가 금융 승인/자금 반영을 하지 않음을 검증한 것이며 실제 입금 전체 흐름의 성공 시험은 아니다.

## 실행 명령과 결과

- Java21/소유 PostgreSQL16. `python3 scripts/verify_codevasp_deposit_searches.py`: 신규12개, 실패/오류/제외0. 최초11개 통과 뒤 미래 기록 시각의 직접 입력 차단 시나리오를 추가해 최종12개를 다시 실행했다.
- `python3 scripts/verify_codevasp_directory.py`: 기존53개, 실패/오류/제외0. 선택 패턴을 명시적으로 좁혀 이 새 Spring 통합 묶음과 같은 DB에 자동 포함되지 않도록 수정했다. 새 스크립트는 별도 서버/DB를 소유한다. JPA 재생성으로 불변 금융/보안 이력이 충돌하는 fixture 우회를 쓰지 않았다.
- 공급사/메일/BTC/CodeVASP 관련 환경을 제거하고 소비자 자동 시작=false로 `python3 scripts/verify_engine_inbox.py`: 기존 금융182개, 실패/오류/제외0. 신규 스키마 초기화 의존성과 H2 정산 fixture를 함께 확인한다.
- 고유247개/실행247개·중복0. 소유한 세 DB 컨테이너 모두 제거 확인. `.local/cycle99-proof`에 검색/목록/금융 XML과 결과를 별도 보존한다. 세 묶음은 Gradle 산출물을 공유하므로 순서대로 실행했다.
- `bootJar operatorAdminJar` 성공. 두 JAR에 예약 서비스/스키마 포함·테스트 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 실제 공급사/Code Cipher 이미지·메인넷·BTC 노드 전체·Rust/Kafka/브라우저는 이번에 실행하지 않았다.
- `git diff --check`·현재 코드 발췌/상대 링크 대조. 의존성 변경은 없다.

## 남은 한계

외부 검색 접수/폴링/임대·결과 불명/재시도·운영 실행·금융 승인·엔진/원장 반영은 후속이다. 이 단계는 출력 전에 보관할 안정 원본이며 완성된 전송 아웃박스라고 표시하지 않는다. 같은 ID의 외부 POST 재시도가 멱등하다는 공급사 보장도 미확인이다. 여러 출력이 같은 거래/수신 주소에 있으면 금융 입금 ID마다 예약할 수 있어 후속 외부 호출 비용이 늘 수 있다. 현재 단순한 입금별 추적성을 우선했고 공통 검색 캐시/추정 묶음 처리를 추가하지 않았다. 내부 get은 권한 검사 API가 아니므로 HTTP에 그대로 노출하면 안 된다. 저장 자료는 온체인/법적 신고/송수신인/개인지갑/안전성의 최종 판정이 아니다.


## 관련 커밋과 증적

- [구현 f08a001](https://github.com/GiHoon1123/Cex-Platform-Service/commit/f08a0012a879478b91d80009862e77ea521b01e5)
- [격리 실행 증적](../reference/099-durable-deposit-search-reservation-verification-2026-10-25.json)
