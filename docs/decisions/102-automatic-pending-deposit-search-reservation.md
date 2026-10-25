# 보류 원장 입금을 자동 출처 검색 예약·접수에 연결하기

이미 보류 원장에 반입된 일반 BTC 입금을 안정 검색 ID 예약과 자동 접수에 연결한다. 수탁 기록·검색 진행·금융 승인은 서로 다른 사실이며, 자동 연결이 고객의 사용 가능 잔액이나 엔진 잔액을 늘리지 않음을 검증한다.

## 문제와 기존 동작

[101](101-automatic-deposit-search-processing.md)은 이미 예약한 검색을 처리하지만 실제 보류 입금의 예약을 만들지는 않았다. 모든 입금 발견을 무조건 외부 검색에 보내면 미확인/수탁 미반입 원본과 채굴 보상까지 불필요한 검색 대상이 될 수 있다. 새 예약의 목록 장애가 이미 접수된 다른 검색의 결과 조회를 막는 문제도 피해야 한다.

## 기존 제품과 공식 자료의 접근

- [CodeVASP TXID 검색](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/11-Search-VASP-by-TXID-Request)은 거래·수신 주소·요청 ID로 송신 회원 사업자를 찾는다. 비회원/일시 장애의 NOT_FOUND를 개인지갑 판정으로 바꾸지 않는098/100 계약을 유지한다.
- [코인베이스 거래 정의](https://developer.bitcoin.org/reference/transactions.html#coinbase)는 새 블록 보상의 생성 거래를 일반 송신 거래와 구분한다. 자동 VASP 조회에서 coinbase를 제외하는 것은 이 의미에 따른 자체 정책이다. 채굴 자금의 적격성을 승인하거나 법적 신고 의무를 충족했다는 판단이 아니다. 채굴 풀의 일반 지급 거래와 코인베이스 자체도 구분한다.
- [099 안정 예약](099-durable-deposit-search-reservation.md)·[100 출력 전 시작 확정](100-durable-deposit-search-execution.md)·[088 보류 원장](088-bitcoin-pending-asset-ledger.md)의 검증된 원본/트랜잭션을 재사용한다. 별도 큐 표/원장 복사/외부 라이브러리는 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 보류 원장에 이미 반입한 일반 입금만 자동 예약 | 단순 발견/미수탁 원본의 외부 검색 비용 제한 | 체인 확정·보류 반입을 기다리므로 출처 확인 시작도 늦어질 수 있음. 확정 전 병렬 조회는 채택하지 않음 |
| 코인베이스는 자동 VASP 검색 제외 | 송신 회원 사업자가 없는 생성 거래에 맞지 않는 반복 조회 방지 | 채굴 출처/개인지갑·소득 증빙의 별도 검토가 남음. 보류 자산/금융 기록을 삭제하거나 승인하지 않음 |
| 불변 보류 원장과 원래 관찰을 조회 근거로 사용 | 이후 관찰이 바뀌어도 수탁/귀속 이력을 잃지 않고 기술 출처를 조사 | 현재 체인 확정을 보증하지 않음. 사용 가능 반입 전 최신 관찰/대사/위험을 별도 재검사해야 함 |
| 매 tick 예약 최대1개·실행 최대1개 | 대기 원본과 실행을 점진적으로 처리·기존 안정 ID 재사용 | 대량 backlog·공급사 호출/저장 비용과 처리 용량은 운영 검증 필요 |
| 새 예약503만 구분해 기존 조회 진행 | 목록 장애로 독립 조회를 앞에서 막지 않음 | DB/감사 장애까지 삼키지 않는다. 두 업무를 한 TX로 묶은 전체 원자성을 주장하지 않음 |
| 테스트 메서드마다 새 DB | 불변 금융 이력 삭제/트리거 해제 없이 완전 격리 | JVM/스키마 기동 시간이 늘고 PG 저장 비용이 생김. 검증 종료 시 실행 소유 서버 전체를 제거 |

## 구현과 관련 코드

[CodeVaspDepositSearches.reserveNextPending](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearches.java)는 bitcoin_pending_deposits·입금 원본·원래 체인 판단/관찰·BTC_DEPOSIT_PENDING 원장을 조인한다. coinbase=false와 같은 접속 주체의 미예약 조건으로 가장 오래된 원본 하나를 고른다. 시험망을 외부 공급사 원점에 자동 전송하지 않는099 경계도 유지한다.

후보 선택 뒤 DB 연결을 돌려주고 기존 reserve()의 짧은 트랜잭션/현재 목록/유일 키/필수 감사를 재사용한다. 동시 선택이 같은 입금을 골라도 기존 확정 ID를 반환하므로 새 ID를 중복 생성하지 않는다. 자동 선택은 새로운 금융 허가가 아니라 이미 보존한 수탁 사실의 기술 조사다. 이후 체인 재조직/관찰 변경을 금융 반영 때 다시 검사해야 한다.

[CodeVaspDepositSearchProcessing](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchProcessing.java)은 먼저 예약을 최대1개 시도한 뒤 기존 검색 후보를 처리한다. 예약의 현재 목록503은 임시 보류로 구분하고 기존 ID 조회를 계속한다. 할 일이 없으면 그503을 보고하며, 다른 DB/감사 오류는 전파한다. 예약 성공 후 실행이 실패해도 확정 예약은 지우지 않으며 이후 시도에서 계속한다. Source 단계의 두 커밋을 하나의 외부 원자적 작업이라고 설명하지 않는다.

활성 조건은101과 같은 codevasp.processing.enabled=true·설정4개 완료·일반 앱이다. test 프로필은 자동 빈 등록 제외다. 실제 공급사/쿼터·비공개 Cipher 준비 전에는 false로 두어야 한다. 이번에 실제 외부 공급사/메인넷에 접속하거나 앱을 배포한 것은 아니다. 프론트와 엔진 코어 변경도 없다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDepositSearches.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearches.java#L51), 현재 소스의 부분 발췌.

```java
    public Optional<Search> reserveNextPending(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("자동 검색 예약은 DB 트랜잭션 밖에서 실행하세요");
        String source=directory.configuredSourceId();
        var candidates=jdbc.queryForList("""
            SELECT p.receipt_id FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts b ON b.id=p.receipt_id
             JOIN bitcoin_deposit_chain_decisions d ON d.id=p.decision_id AND d.receipt_id=b.id
             JOIN bitcoin_transaction_observations o ON o.id=d.observation_id AND o.coinbase=false
             JOIN ledger_transactions l ON l.operation_key=p.operation_key AND l.kind='BTC_DEPOSIT_PENDING'
             JOIN codevasp_directory_sources s ON s.id=?
             WHERE b.asset='BTC' AND (b.network='bitcoin-mainnet' OR s.api_origin ~ '^https?://(127[.]0[.]0[.]1|\\[::1\\])(:[0-9]+)?/$')
              AND NOT EXISTS(SELECT 1 FROM codevasp_deposit_searches previous WHERE previous.receipt_id=b.id AND previous.source_id=s.id)
             ORDER BY p.recorded_at,p.receipt_id LIMIT 1
            """,source);
```

Optional.empty는 자동 예약할 일반 수탁 원본이 없다는 뜻이다. 단순 발견 원본을 전체 승인으로 해석하지 않는다. Java 텍스트 블록 안의 SQL 역슬래시는 Java 문자열/정규식 두 단계를 고려하며, 값은 바인딩한다. 실제 요청 원문은 불변 입금/주소에서 읽고 이미 검증한 reserve 트랜잭션을 재사용한다.

출처: [CodeVaspDepositSearchProcessing.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchProcessing.java#L22), 현재 소스의 부분 발췌.

```java
        ResponseStatusException reservationHeld=null;
        try{searches.reserveNextPending();}
        catch(ResponseStatusException unavailable){
            // 새 예약의 현재 목록 전제가 실패해도 이미 접수됐을 수 있는 검색의 조회는 계속한다.
            if(unavailable.getStatusCode().value()!=503)throw unavailable;
            reservationHeld=unavailable;
        }
        var candidates=candidates(source,false);
        if(candidates.isEmpty()){
            if(reservationHeld!=null)throw reservationHeld;
            return Optional.empty();
        }
        var candidate=candidates.getFirst();
```

예약과 결과 조회가 다른 실패 경계를 가진다. 명확한 현재 목록503만 별도로 보류하고, 이전에 접수됐을 수 있는 ID의 조회를 막지 않는다. TypeScript에서도 catch 안에서 모든 예외를 빈 성공으로 바꾸면 DB/감사 장애까지 숨기므로 오류 종류와 실제 진행 상태를 구분해야 한다.

## 작성한 테스트

[CodeVaspPendingReservationIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspPendingReservationIntegrationTest.java)에12개를 추가했다. 실제 LedgerService·체인 판단·보류 반입·검색 예약·실행 서비스와 실제 PG 제약을 사용한다. 노드 관찰은 기존 합성 BitcoinDepositFixtures로 구성하며 실제 체인/키 보유를 증명하지 않는다. 거래 클라이언트는 대역이다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|unrecognizedReceiptIsNotAutomaticallyReservedButRecognizedPendingIs | 단순 입금 발견은 예약0, 실제 보류 반입 뒤1회 예약·거래 결합·중복 없음 |
|coinbaseIsPreservedForReviewWithoutOriginatingVaspLookup |101확인 코인베이스를 실제 보류 원장에 반입하지만 자동 검색0·원본 보존 |
|twoPendingReceiptsAreReservedOnceInDurableArrivalOrder | 보류 입금2개를 순서대로 별도 ID 예약·세 번째 빈 결과 |
|concurrentAutomaticReservationCreatesOneStableRequestAndAudit | 동시 선택에도 ID1개/필수 감사1개 |
|reservationAuditFailureKeepsPendingAndHasNoExternalSubmission | 예약 감사 실패는 검색0·보류 양쪽 원장 유지·정상 재예약 가능 |
|failedCurrentDirectoryCannotReserveOrDiscardPending | 목록 실패 시 새 예약503/0개, 보류 자산 이력 유지 |
|newPendingReservationFailureCannotBlockExistingIndependentPoll | 새 보류 예약은 목록 장애로 실패해도 기존 중단 ID의 POLL/FOUND 진행·새 접수0 |
|ownedWorkerAutomaticallyReservesPendingAndPersistsAcknowledgement | 실제 소유 자식 컨텍스트/워커가 보류 입금 예약→접수 ACK를 PG에 확정·접수1회·정상 종료 |
|existingReservationSurvivesNewServiceInstanceWithoutAnotherAutomaticIdentity | 서비스 재생성 뒤 기존 ID 재사용·추가 자동 예약 없음 |
|ambientTransactionCannotAutomaticallyReserveBeforeProviderIo | 외부 TX에서 예약 금지·외부 호출0 |
|testChainPendingCannotBeAutomaticallySentToExternalProviderOrigin | regtest 보류를 외부 HTTPS 원점으로 자동 예약하지 않음·보류 보존 |
|technicalLookupDoesNotDeclareLaterStaleChainEvidenceReadyForSpending | 보류 반입 후 현재 관찰이 낡아 CHAIN_READY가 아니어도 기술 검색만 가능, 사용 가능/엔진 반입은0 |

모든 시험 뒤 엔진/Kafka 생산자 호출0·user_balances 행0을 확인한다. 보류 건수와 정확히2개의 PENDING/CUSTODY 항목·BTC 합계0도 관련 시나리오에서 대조한다. 노드/공식 공급사/실제 재시작을 수행한 시험으로 과장하지 않는다.

## 실행 명령과 결과

- Java21/소유 PostgreSQL16. `python3 scripts/verify_codevasp_pending_reservation.py`: 신규12개, 실패/오류/제외0. 한 서버 안에서 메서드마다 별도12개 DB를 만들고 Gradle을 순서대로 실행했다. 각 메서드는 시작 전 입금 원본0을 확인해 이전 금융 이력을 재사용하지 않는다. 원본 삭제/불변 트리거 해제로 fixture를 정리하지 않았다.
- `python3 scripts/verify_codevasp_deposit_searches.py`: 기존46개, 실패/오류/제외0. 별도 소유 서버에서 기존 예약/실행/후보 선택/실행기 수명 회귀를 확인했다.
- 최종 고유58개/실행58개·중복0. `.local/cycle102-proof`에 새12개 각각의 로그/XML·통합 manifest와 기존46개 결과를 따로 보존한다. 소유한 두 PG 컨테이너 제거 확인.
- `bootJar operatorAdminJar` 성공. 두 JAR에 reserveNextPending 메서드 포함·테스트 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. `git diff --check`·현재 발췌/상대 링크 대조. 의존성/제품 스키마 변경 없음.
- 금융182/공급사 HTTP54/BTC 실제 노드 전체/Rust/Kafka/브라우저는 이번에 재실행하지 않았다. 이전101/094의 검증 수치를 이번 실행으로 재사용하지 않는다. 실제 보류 원장 경계는 새 PG12개에서 함께 호출/검증한 범위다.

## 남은 한계

운영 검색/보류 조회·송수신인 정보 교환·개인지갑/채굴 출처 검토·고객/위험/금융 승인·엔진/원장 반영은 미완료다. 기술 검색의 ACK/FOUND가 자금 사용 허가를 뜻하지 않는다. 앞으로 보류 해제/완료가 구현되면 자동 대상은 그 실제 업무 상태도 반영해야 하며, 현재의 불변 보류 반입 기록을 사용 가능 잔액 판정으로 재사용해서는 안 된다. 실제 계약/쿼터·콜백/암호 모듈 운영·법적/외부 적합성 확인도 남는다. source 공개키/원점 변경 시 별도 검색 주체로 분리하는099 정책의 비용과 이전 요청 해결 절차는 운영 조건이다.


## 관련 커밋과 증적

- [구현 b188389](https://github.com/GiHoon1123/Cex-Platform-Service/commit/b18838933f6f5262968e3a914e922470552156e3)
- [격리 실행 증적](../reference/102-automatic-pending-deposit-search-reservation-verification-2026-10-25.json)
