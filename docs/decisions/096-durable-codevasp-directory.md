# 사업자 목록 조회를 보존하고 최신 실패·미완료를 숨기지 않기

사업자 목록을 읽기만 하면 새 조회 실패 뒤에도 옛 정상 자료를 계속 사용하기 쉽다. 조회 시작·전체 결과·검증한 내용을 PostgreSQL에 보관하고, 가장 최근 시도만 현재 자료로 인정한다. 이 문서는 금융 허가가 아닌 기술 연동 자료의 보존·현재성 계약을 설명한다.

## 문제와 기존 동작

[095](095-codevasp-cipher-directory-client.md)는 공식 Cipher 헤더로 기술 목록을 읽고 전체 필드를 검증했다. 그러나 지속 보관/현재 자료 조회/중복 호출 제어가 없었다. 네트워크 호출 중 DB 트랜잭션을 유지하거나 새 실패를 무시하고 마지막 성공만 쓰면 잠금 대기와 오래된 상대방 정보가 금융 업무로 번질 수 있다.

## 기존 제품과 공식 자료의 접근

- [CodeVASP 목록 공식 문서](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/01-VASP-List-Search)는 주기적 갱신을 권고하며 실제 입출금 가능 여부는 상대 사업자의 내부 정책에 따라 달라진다고 설명한다. health=down 대상에는 요청하지 않도록 명시한다. 이번 단계는 그 원본 상태를 그대로 보관하며 송금 가능성/신고 적격성으로 해석하지 않는다.
- [PostgreSQL 16 행/자문 잠금](https://www.postgresql.org/docs/16/explicit-locking.html)을 활용한다. 시작의 단기 자문 잠금과 현재 위치의 행 공유 잠금을 사용하고 직접 메모리 락/공급사 SDK를 추가하지 않는다. 공급사의 내부 저장 구조를 추정해 복제한 것은 아니다.
- [095의 서명 모듈·입출력 제한·공식 계약 확인](095-codevasp-cipher-directory-client.md)을 재사용한다. 설치된 Spring JDBC/TransactionTemplate/Jackson/JDK SHA-256으로 구현했으며 의존성 변경은 없다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·검토한 대안 |
| --- | --- | --- |
| 조회 시작과 현재 위치를 같은 짧은 TX로 확정 | 새 미완료/실패를 옛 성공으로 숨기지 않음 | 공급사 장애 때 현재 자료를 요구하는 후속 업무가 보류된다. 마지막 성공을 계속 제공하는 가용성 우선 대안은 채택하지 않음 |
| 시작→외부 호출→완료의 세 단계 | 외부 응답 중 DB 연결/잠금을 잡지 않음 | 완료 저장 실패 시 미완료 원본이 남으며 이후 재조회가 필요함. 공급사 결과와 DB를 하나의 원자적 작업으로 주장하지 않음 |
| 미완료 시작 2분 이내는409, 지난 시작은 새 시도로 대체 | 동시 중복 호출 제한·프로세스 장애 뒤 재개 | 2분은 자체 임대 정책이며 외부 API 계약이 아니다. 실제 처리 중 2분을 넘기면 두 호출이 겹칠 수 있으나 늦은 완료가 현재 위치를 덮지 않음 |
| 현재 자료는 시작 시각부터5분 | 긴 처리 후 결과만 새것처럼 보이는 상태 차단 | 5분은 자체 정책이다. 후속 실제 호출/외부 운영 쿼터와 함께 조정 근거를 검토해야 하며 현재 자동 갱신 워커는 없다 |
| 공급사 원점·등록 주체·공개키로 자료 구분 | 설정/키가 바뀌어도 옛 자료를 새 권위로 재사용하지 않음 | 키 교체 후 새 목록을 받아야 함. Cipher 주소는 운송 경로이므로 주체 키에 포함하지 않음 |
| 타입 목록 내용은 해시로 중복 제거, 모든 시도는 별도 보관 | 동일 목록의 반복 저장 비용 감소·모든 시작/결과 추적 | 공급사 순서를 보존하므로 순서만 바뀌어도 새 내용이다. 단순 정규 JSON 직렬화이며 원래 HTTP 바이트/공급사 응답 서명 보관이 아님 |
| 현재 위치 FOR SHARE | 호출자의 금융 TX 안에서 현재 근거 고정 가능 | 해당 TX가 끝날 때까지 새 현재 위치 갱신이 기다린다. 장기 TX/네트워크 호출 금지. Rust 체결 코어와 무관한 플랫폼 DB 경계 |

## 구현과 관련 코드

[CodeVaspDirectoryService](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryService.java)가 서비스 경계다. refresh()는 외부 TX에서 시작할 수 없으며 설정이 없으면 외부 호출 전에503이다. 시작/완료에 필수 감사가 참여한다. 실패 코드는 PROVIDER_UNAVAILABLE/INVALID_SOURCE/INTERNAL_FAILURE 세 값이며 공급사 본문·예외 원문을 저장하지 않는다. 완료 저장 자체의 예외는 다시 공급사 실패로 잡지 않는다.

[travelrule/schema.sql](../../src/main/resources/travelrule/schema.sql)의 표는 다음과 같다.

| 표 | 역할 |
| --- | --- |
| codevasp_directory_sources | 원점/주체/공개키와 그 UTF-8 프레임 해시 |
| codevasp_directory_attempts | 매번 새 시작 시각/원본 ID/시작 TX ID |
| codevasp_directory_heads | 주체별 최신 시작 ID, 같은 TX의 새 시도로만 전진 |
| codevasp_directory_contents | 검증한 타입 목록의 직렬화/바이트 해시, 주체별 내용 공유 |
| codevasp_directory_results | 시도별 SUCCESS/FAILED·제한 실패 코드·내용 ID·클라이언트 읽기 시각 |

sources/attempts/contents/results는 UPDATE/DELETE/TRUNCATE를 거부한다. heads는 삭제/TRUNCATE를 거부하며 이전 시도/다른 주체로 되돌릴 수 없다. 결과/내용의 복합 외래 키는 서로 다른 주체의 원본 혼합을 막는다. 성공 결과에는 내용/정상 순서 시각이 필요하고 실패 결과에는 내용/성공 시각을 넣을 수 없다. 내용은4MiB 이내 타입 목록(JSON 배열), 최대5000행이며 SHA-256은 바이트와 함께 DB에서도 대조한다. 095의 게이트웨이 원문 제한2MiB와 타입 투영 저장 상한4MiB는 서로 다른 경계다.

[CodeVaspSchemaInitializer](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspSchemaInitializer.java)는 기존 KYC 불변 함수·보안 감사 스키마 이후 실행한다. 설정 없음으로 기존 표/이력을 지우거나 외부에 접속하지 않는다. H2 정산 시험 두 개는 기존 다른 PostgreSQL 초기화와 같은 방식으로 이 초기화만 명시적으로 모의 처리한다. 제품에서 PostgreSQL 통제를 생략하는 분기는 넣지 않았다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDirectoryService.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryService.java#L36), 현재 소스의 부분 발췌.

```java
    public Report refresh(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("사업자 목록 갱신은 DB 트랜잭션 밖에서 시작하세요");
        requireConfigured();String source=source();Start started=tx.execute(t->start(source));
        CodeVaspDirectoryClient.Directory directory=null;String payload=null,failure=null;
        try{
            directory=client.read();
            // 시각은 접속 시각을 증명하는 서명이 아니다. 잘못된 JVM 시각/반환값을 새 현재 원본으로 저장하지 않는다.
            if(directory==null||directory.readStartedAt()==null||directory.readFinishedAt()==null||directory.readFinishedAt().isBefore(directory.readStartedAt())
                ||directory.readStartedAt().isBefore(started.at().minusSeconds(10))||directory.readFinishedAt().isAfter(Instant.now().plusSeconds(10)))failure="INVALID_SOURCE";
            else{payload=json.writeValueAsString(directory.vasps());if(payload.getBytes(StandardCharsets.UTF_8).length>4194304)failure="INVALID_SOURCE";}
        }catch(ResponseStatusException unavailable){failure="PROVIDER_UNAVAILABLE";}
```

TransactionTemplate.execute의 람다는 짧은 DB 트랜잭션이다. client.read는 두 execute 사이에서 호출하므로 네트워크 대기 중 DB 트랜잭션이 없다. TypeScript의 async 함수와 달리 자바 메서드 자체는 호출 워커에서 기다리며, 이 서비스는 Rust 코어에 배치하지 않는다. record는 변경하지 않는 결과 묶음이다.

출처: [CodeVaspDirectoryService.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryService.java#L87), 현재 소스의 부분 발췌.

```java
    public Snapshot current(){
        requireConfigured();String source=source();
        return tx.execute(t->{
            var heads=jdbc.queryForList("SELECT attempt_id FROM codevasp_directory_heads WHERE source_id=? FOR SHARE",Long.class,source);if(heads.size()!=1)throw unavailable();
            // 머리 행을 먼저 고정한 뒤 새 SQL 스냅샷에서 불변 원본을 읽는다. 큰 다중 조인의 잠금 재검사에 현재 위치를 맡기지 않는다.
            var rows=jdbc.queryForList("""
                SELECT a.id,a.started_at,r.status,r.recorded_at,r.client_started_at,r.client_finished_at,c.id content_id,c.payload_hash,c.payload,
                 a.started_at<=clock_timestamp() AND a.started_at>=clock_timestamp()-interval '5 minutes' AND r.recorded_at<=clock_timestamp() fresh
                 FROM codevasp_directory_attempts a LEFT JOIN codevasp_directory_results r ON r.attempt_id=a.id
                 LEFT JOIN codevasp_directory_contents c ON c.id=r.content_id AND c.source_id=a.source_id WHERE a.id=? AND a.source_id=?
                """,heads.getFirst(),source);
            if(rows.size()!=1)throw unavailable();var row=rows.getFirst();if(!"SUCCESS".equals(row.get("status"))||!Boolean.TRUE.equals(row.get("fresh")))throw unavailable();
```

먼저 최신 위치 행을 FOR SHARE로 고정한 뒤 그 불변 결과를 별도 SQL에서 읽는다. 기존 TX가 있으면 기본 REQUIRED 전파로 참여해 반환 후에도 바깥 TX 종료까지 잠금이 유지된다. 단독 호출의 잠금은 반환 시 끝나므로 Snapshot을 나중의 금융 승인 토큰처럼 사용하면 안 된다.

출처: [schema.sql](../../src/main/resources/travelrule/schema.sql#L35), 현재 소스의 부분 발췌.

```sql
 IF TG_OP='UPDATE' AND (NEW.source_id<>OLD.source_id OR NEW.attempt_id<=OLD.attempt_id) THEN RAISE EXCEPTION '사업자 목록의 현재 조회를 과거로 되돌릴 수 없습니다'; END IF;
 IF NOT EXISTS(SELECT 1 FROM codevasp_directory_attempts a WHERE a.id=NEW.attempt_id AND a.source_id=NEW.source_id AND a.created_txid=txid_current()) THEN
  RAISE EXCEPTION '사업자 목록 현재 위치는 새 조회 시작과 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
```

현재 위치는 이전 ID로 되돌리거나 다른 주체로 바꿀 수 없다. 새 시도 원본과 현재 위치는 같은 DB 트랜잭션에서 작성한다. 늦게 도착한 결과는 불변 이력만 추가하고 이 위치를 옮기지 않는다. created_txid는 애플리케이션 정상 권한 경계이며 DB 관리자 변조 방지/외부 백업을 대체하지 않는다.

## 작성한 테스트

[CodeVaspDirectoryIntegrationTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspDirectoryIntegrationTest.java)13개를 새로 작성했다. 각 시험은 별도 등록 주체로 격리하며 실제 PostgreSQL 불변 통제를 유지한다. 아래 장애 대역은 이미 검증된 클라이언트의 반환/예외를 모의하며 실제 공급사 가입/통신 시험이 아니다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|startIsDurableBeforeIoAndPendingHeadCannotExposeOldSuccess | 외부 호출 중 TX 없음·시작 원본2개 확인·current503, 완료 뒤 최신 시도 조회 |
|equalPayloadSharesContentButEachReadKeepsItsOwnAttemptAndAudit | 같은 내용2회에 내용1개·시도2개·시작/완료 감사4개 |
|latestProviderFailureBlocksEarlierSuccessAndDoesNotStoreException | 과거 SUCCESS 보존, 새 FAILED/제한 코드, current503·추가 내용 없음 |
|failedStartAuditRollsBackEverythingBeforeProviderIo | 시작 감사 실패 시 자료 주체/시도/현재 위치 확정 없음·공급사 호출0 |
|failedFinishAuditLeavesPendingHeadAndRollsBackResultAndNewContent | 완료 감사 실패 시 새 결과/내용 롤백, 미완료 시작만 보존, 이전 성공 우회 없음·즉시 재조회409 |
|concurrentRefreshHasOneClaimAndNoSecondNetworkCall | 첫 외부 호출을 래치로 정지, 두 번째409·호출1회·첫 완료 성공 |
|abandonedAttemptCanBeReplacedAndLateResultCannotMoveCurrentHead | 처음부터181초 지난 미완료 원본을 삽입해 장애 상태 구성, 새 조회 뒤 옛 완료를 추가해도 최신 위치 유지. 실제2분 프로세스 정지 시험은 아님 |
|staleSuccessIsUnavailableWithoutModifyingHistory | 처음부터361초 지난 시작/성공 원본을 새로 삽입, 원본 수정/트리거 해제 없이 current503 |
|callerTransactionPinsHeadUntilItFinishes | 바깥 TX가 현재 자료를 읽는 동안 동시 갱신이 완료/외부 호출하지 못함, TX 종료 뒤 정상 진행 |
|sourceChangesAndNewServiceInstanceDoNotReuseDifferentAuthority | 새 서비스 객체는 같은 자료 재조회, 원점/주체/공개키 변경은 current503·새 외부 호출 없음 |
|immutableRowsAndHeadRewindAndBadHashCannotBypassSourceContract | 네 원본 표의 UPDATE/DELETE/TRUNCATE CASCADE 거부·현재 위치 역행/틀린 내용 해시 거부, 최신 자료 보존 |
|malformedReadAndUnexpectedFailureKeepBoundedFailureCodes | null/미래 시각은 INVALID_SOURCE, 예외는 INTERNAL_FAILURE·현재 자료 미제공 |
|unconfiguredAndAmbientTransactionCannotStartIo | 미설정/외부 TX에서 갱신 금지·외부 호출0 |

[095 클라이언트/설정19개](095-codevasp-cipher-directory-client.md#작성한-테스트)를 재실행했다. 실제 소유 HTTP 대역의 서명 계약/잘못된 헤더·JSON·크기·전체 응답 시간 제한도 유지된다. [TradeSettlementFailureTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementFailureTest.java)와 [TradeSettlementRunServiceTest](../../src/test/java/dustin/cex/domains/settlement/trade/TradeSettlementRunServiceTest.java)는 H2 시험의 새 PG 초기화 빈만 모의 처리했고 금융 시험 시나리오는 바꾸지 않았다.

## 실행 명령과 결과

- Java21/격리 PostgreSQL16. `python3 scripts/verify_codevasp_directory.py`: 신규13+기존19=32개, 실패/오류/제외0. `bootJar operatorAdminJar` 성공. 실행기가 만든 DB 컨테이너 제거 확인.
- CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_ 환경을 제거하고 소비자 자동 시작=false로 `python3 scripts/verify_engine_inbox.py`: 금융/주문/시장/수수료/수신182개, 실패/오류/제외0. 별도 소유 PostgreSQL 및 두 H2 시험을 포함하며 해당 DB 컨테이너 제거 확인.
- 고유214개/실행214개, 중복0. `.local/cycle96-proof`에 두 실행의 XML/결과/로그를 따로 보존하고 현재 파일명 덮어쓰기로 누락시키지 않았다.
- 고객/운영자 JAR 모두 서비스 클래스·travelrule/schema.sql 포함, 테스트 클래스 제외 확인. `git diff --check` 통과. 소스의 마지막 추가 설명 주석은 로직 변경이 아니다.
- 기존 deprecated/unchecked 및 JVM CDS 경고가 있다. 오류로 숨기거나 이번 변경에서 제거했다고 표시하지 않는다. Rust/BTC 전체 실제 노드/실제 Kafka/브라우저 회귀는 이번에 재실행하지 않았다.

## 남은 한계

자동 갱신 워커/API·공급사 실제 계정/Cipher 이미지 검증·샌드박스/라이브 확인은 아직 없다. 저장 자료는 기술 목록의 타입 투영이며 법적 신고·상대 위험·송수신인·개인지갑 소유·정보 교환 완료·자금 반영 승인이 아니다. 공급사 원문 서명 검증 증적이나 규제 준수 완료를 주장하지 않는다. current()는 후속 업무에서 같은 TX 안에 재검사할 수 있는 근거이며 아직 입출금 승인에 연결하지 않았다. 정상 조회 시도 이력은 계속 증가하므로 실제 호출 빈도/보존 운영 비용 검토가 필요하다. 외부 정보/시각 신뢰와 DB 관리자·디스크 손실 통제는 별도 운영 조건이다.


## 관련 커밋과 증적

- [구현 a929c42](https://github.com/GiHoon1123/Cex-Platform-Service/commit/a929c42f57e1ac50131fc95e1e036e1b9c548fb8)
- [격리 실행 증적](../reference/096-durable-codevasp-directory-verification-2026-10-25.json)
