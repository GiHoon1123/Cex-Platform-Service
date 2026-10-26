# 미해결 BTC 체인 위험을 시스템 근거와 영속 주문 취소에 연결

BTC 입금 적립 후 미해결 체인 위험이 생기면 해당 고객의 남은 지정가 주문을 영속 취소에 연결한다. 시스템 근거·취소 예약·감사를 함께 확정하고 기존 출력 워커가 실제 엔진에 전달한다. HTTP 응답으로 예약금을 풀지 않고 Kafka 금융 결과에만 결합한다. 공통 사이클136이다.

## 문제와 기존 동작

131의 제한은 위험 고객의 새 금융 권한을 막지만 이미 제출한 지정가 주문을 없애지는 않는다.135는 고객이 직접 요청한 취소를 영속 재확인한다. 자동 조치를 고객의 클릭으로 기록하거나 위험 관찰 없이 그 큐만 만들면 행위자·근거가 왜곡된다.

이번은 실제 미해결 위험과 원래 주문을 연결한다. 고객의 전체 비종결 지정가를 보수적으로 정리한다. 시장가의 진행 중 처리를 취소 대기로 바꾸거나 원래 체결·입금을 역분개하지 않는다. 위험 해제 후 주문을 자동 재생성하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST 확인한 [Coinbase Cancel all orders](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/orders/cancel-all-orders)는 열린 주문의 일괄 취소를 best effort로 설명하고 여러 번 요청이 필요할 수 있다고 명시한다. [단건 취소](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/orders/cancel-an-order)의 주문 식별/취소·체결 경쟁과 함께 참고한다. 공개 API의 계약만 참고하며 비공개 체인 재조직 정책이나 내부 워커 구조를 추측하지 않는다.

전용 장수 스케줄러·저장 장애 지연·종료는 이미 설치된 Spring과135의 워커 패턴을 사용한다. 새로운 큐/패키지/스케줄러 설정은 추가하지 않았다. 체인 위험 고객의 모든 지정가를 정리하는 것은 이 프로젝트의 보수적 자체 위험 정책이며 법률이 이 알고리즘을 직접 요구한다고 주장하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 현재 미해결 위험 고객의 모든 지정가 | 같은 계정 자산을 공유하는 다른 시장의 기존 노출도 정리 | 관련 없는 정상 주문도 취소될 수 있고 고객이 재주문해야 한다. 특정 BTC 시장만 취소하면 이미 교환된 공유 자산 노출이 남을 수 있음 |
| 위험 원본→예약→시스템 감사 같은 TX | 근거 없는 자동 명령·조용한 감사 누락·고객 위장 방지 | 추가 불변 표/인덱스/감사 저장 및 제약 검사 비용. 기존 고객 취소 경로를 복제하지 않음 |
| 예약 워커와135 외부 출력 워커 분리 | 네트워크 응답 대기 때문에 다음 위험의 DB 예약이 멈추지 않음 | 장수 스레드 하나와 DB 후보 조회 추가. 느린 외부 응답의 취소 완료 지연은 남음 |
| 고객→주문 잠금 뒤 현재 사실 재확인 | 동시 계획·고객 요청·이미 끝난 주문과의 경쟁 보존 | 플랫폼의 짧은 행 잠금 비용. Rust 매칭 코어에는 락/정책/조회 추가 없음 |
| 요청 최초 일정은 즉시, 실제 청구부터30초 임대 | 시스템 예약이 API 없이도 즉시 출력 대상 | 고객 API와 출력 워커가 먼저 청구하려 경쟁한다. 같은 행 잠금/회귀로 하나만 보내고 임대 만료 뒤 중복 전달은 기존 엔진 멱등성에 의존 |
| 가장 오래된 미해결 위험부터 연결 | 정상 재관찰/반복 위험 때문에 우선순위를 계속 뒤로 미루지 않음 | 여러 위험 중 대표 원본 하나를 원래 취소에 보존한다. 위험 전체 이력은 삭제하지 않음 |
| 표준 기존 취소 명령 재사용 | 자체 WAL→Kafka→정산 계약 그대로 | 취소 전에 체결될 수 있다. 동기적 강제 중단이나 손실 회수 보장이 아니며 외부 지연 중 기존 노출이 남을 수 있음 |

## 구현과 데이터 흐름

1. 현재 위험 뷰와 불변 접수 원본에서 큐가 없는 비종결 지정가 한 건을 조회한다. 후보 조회는 외부 TX 없이 하고 외부 노드/엔진을 호출하지 않는다.
2. 짧은 플랫폼 TX에서 고객→주문을 잠그고 위험·소유자·종료 여부·기존 취소를 다시 확인한다.
3. 실제 입금/위험 관찰/원래 주문과 고정 정책을 불변 연결한다. DB가 정규 감사 상세를 생성해 Java/SQL 표현 차이를 줄인다.
4. MANDATORY 내부 경계가 같은 TX에 기존 취소 큐를 넣는다. 시스템 주체 actor=null과 정확한 근거 감사를 남긴다. 지연 FK/감사 제약은 빠진 단계의 커밋을 거부한다.
5. 기존135 출력 워커가 HTTP를 잠금 밖에서 실행한다. 응답 ACK도 금융 종료가 아니며 실제 Kafka 취소 이벤트만 원장/예약에 한 번 반영한다.
6. 위험/고객의 다른 제한은 남는다. 모든 주문이 끝난 뒤133 복구 근거와134의 독립 승인을 진행할 수 있다. 정상 관찰이나 자동 취소만으로 해제하지 않는다.

새 스키마는 JPA/원장/보안/입금 스키마 순서에 결합했다. 초기화 중 근거 표가 없는 고객 취소 제약을 설치하지 않는다. 실사용 코드를 남겨 두는 호환 경로나 플랫폼 DB로 대체하는 엔진 복구 경로는 추가하지 않았다.

## 실제 코드와 이해에 필요한 문법

출처: [BitcoinChainRiskOrderCancellations.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRiskOrderCancellations.java#L30), 현재 소스의 부분 발췌.

```java
        var due=jdbc.queryForList("""
            SELECT o.id,o.user_id,r.receipt_id,r.observation_id FROM orders o JOIN order_submissions s ON s.order_id=o.id AND s.user_id=o.user_id
             CROSS JOIN LATERAL(SELECT cr.receipt_id,cr.observation_id FROM bitcoin_credit_chain_risks cr WHERE cr.user_id=o.user_id ORDER BY cr.observation_id,cr.receipt_id LIMIT 1) r
             WHERE s.order_side='limit' AND o.status NOT IN('filled','cancelled','rejected','expired')
              AND NOT EXISTS(SELECT 1 FROM order_cancellations q WHERE q.order_id=o.id)
             ORDER BY r.observation_id,o.id LIMIT 1
            """);
        if(due.isEmpty())return Optional.empty();var row=due.getFirst();
```

LATERAL은 각 주문 소유자에 맞는 가장 오래된 현재 위험 한 건을 선택한다. immutable 접수의 order_side를 검사하며 표시용 최신 상태를 금융 원본으로 삼지 않는다. Optional.empty는 정상 후보 없음이며 DB 조회 실패를 빈 성공으로 바꾸지 않는다.

출처: [BitcoinChainRiskOrderCancellations.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRiskOrderCancellations.java#L40), 현재 소스의 부분 발췌.

```java
            users.lockById(candidate.customerId()).orElseThrow();var original=orders.findByUserIdAndIdForUpdate(candidate.customerId(),candidate.orderId()).orElseThrow();
            // 후보 조회는 권한이 아니다. 고객 잠금 뒤 현재 범위와 기존 요청을 다시 읽어 경합의 이전 관찰을 재사용하지 않는다.
            if(Set.of("filled","cancelled","rejected","expired").contains(original.getStatus())||cancellations.findForUpdate(candidate.orderId()).isPresent())return Optional.empty();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE receipt_id=? AND observation_id=? AND user_id=?)",Boolean.class,candidate.receiptId(),candidate.observationId(),candidate.customerId())))return Optional.empty();
            String detail=jdbc.queryForObject("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy) VALUES(?,?,?,?,?) RETURNING audit_detail",String.class,
                candidate.orderId(),candidate.customerId(),candidate.receiptId(),candidate.observationId(),POLICY);
            // 원본 연결→취소 예약→시스템 감사가 같은 커밋이다. 지연 외래키/감사 제약이 빠진 단계의 커밋을 거부한다.
```

고객→주문 잠금 뒤 정확한 원본을 재확인한다. TransactionTemplate이 함수 반환 뒤 커밋하므로 deferred 제약 실패도 전체 작업 실패다. TypeScript의 await나 일반 객체 복사만으로 이 DB 원자성/잠금 순서가 생기지는 않는다.

출처: [OrderCancellationService.java](../../src/main/java/dustin/cex/domains/order/service/OrderCancellationService.java#L51), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean stage(long userId,long orderId){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("취소 예약은 검증 업무의 트랜잭션 안에서 실행하세요");
        var owned=orders.findByUserIdAndIdForUpdate(userId,orderId).orElseThrow(()->new IllegalArgumentException("취소할 원본 주문을 확인하세요"));
        if(TERMINAL.contains(owned.getStatus()))return false;
        return ensureQueued(owned);
```

MANDATORY는 호출 업무가 이미 연 실제 TX를 요구한다. 실제 클라이언트로 직접 조립하는 경계도 명시적 TX 검사로 보호한다. 여기서는 저장만 하고 네트워크나 고객 위장 감사를 수행하지 않는다.

출처: [chain-risk-cancellation-schema.sql](../../src/main/resources/bitcoin/chain-risk-cancellation-schema.sql#L27), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_chain_risk_cancel_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_cancellations q JOIN security_audit a ON a.actor_id IS NULL AND a.action='BTC_CHAIN_RISK_CANCEL_PLANNED'
  AND a.target='order:'||NEW.order_id::text AND a.outcome='SUCCESS' AND a.detail=NEW.audit_detail AND a.occurred_at>=q.requested_at AND length(a.source_address)>0
  WHERE q.order_id=NEW.order_id AND q.user_id=NEW.user_id AND q.requested_at>=NEW.created_at) THEN
  RAISE EXCEPTION '자동 취소에는 같은 TX의 취소 예약과 정확한 시스템 근거 감사가 필요합니다';END IF;RETURN NULL;
END $$;
```

GENERATED 감사 상세와 정확한 주문/고객/저장 시각을 함께 검사한다. 지연 제약이므로 원본→큐→감사 순서의 쓰기가 가능하되 커밋 시 모두 필요하다. 원래 원장과 위험 관찰은 변경하지 않는다.

출처: [BitcoinChainRiskCancellationWorker.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRiskCancellationWorker.java#L36), 현재 소스의 부분 발췌.

```java
        while(active&&service.stageNext().isPresent()){}
        if(!active)return;
        retries=backoff.start();delayNanos=0;if(failed){failed=false;log.info("체인 위험 주문의 취소 예약을 재개했습니다");}
```

한 건이 실제 커밋된 뒤 다음 작업을 고른다. volatile 종료 신호·한 수명 스레드와 장애 지연은 워커 관리이며 엔진 코어의 독점 상태와 다르다. 설정은 기존 order.dispatch.enabled를 따르고 test 프로필에서는 자동 시작하지 않는다.

출처: [BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java#L185), 현재 소스의 부분 발췌.

```java
        var plan=services.risk().stageNext().orElseThrow();assertEquals(order,plan.orderId());assertEquals(observation,plan.observationId());assertTrue(services.risk().stageNext().isEmpty());assertEquals(ledgerBefore,rows("ledger_entries"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id IS NULL AND action='BTC_CHAIN_RISK_CANCEL_PLANNED' AND target=?",Long.class,"order:"+order));
        // 고객 요청과 구분하는 시스템 조치다. 고객의 취소 클릭이나 원장 반전을 만들어 내지 않는다.
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='ORDER_CANCEL_REQUESTED'",Long.class));
        assertTrue(services.cancellations().retryNext());assertNotNull(orderCancellations.findById(order).orElseThrow().getAcknowledgedAt());assertEquals("pending",orderRows.findById(order).orElseThrow().getStatus());amount("0.01","LOCKED");
        var journal=actualAudit.page(0,null,null);assertEquals(2,journal.path("events").size());assertEquals("order_cancelled",json.readTree(journal.path("events").get(1).asString()).path("event_type").asString());
        listeners.start();await(()->"cancelled".equals(orderRows.findById(order).orElseThrow().getStatus()),20);amount("0.12345678","AVAILABLE");amount("0","LOCKED");
```

실제 엔진 연결 시험의 부분 발췌다. 예약과 시스템 감사 뒤 아직 소비를 멈춰 둔 동안 HTTP ACK와 플랫폼 예약을 따로 검사한다. 이후 실제 Kafka 소비·원장 반환·독립 금융 대사와 복구 근거 작성을 확인한다.

## 작성한 테스트와 결과

| 코드 | 시나리오·기대 결과 |
| --- | --- |
| BitcoinChainRiskCancellationIntegrationTest28개 | 실제 PG·실제 적립/예약·현재 위험 위에 정상 고객 제외, 반복/정상 관찰 뒤 위험 유지, 원본/감사/시각 위조·고객 위장·고아 원본·누락 감사·롤백, 비활성/다른 제한 보존, 후보와 락 사이 실제 종료/고객 요청, 실제 두 스레드 중복 경쟁, 실제 운영 프로필 예약 워커 기동/종료 |
| BitcoinChainRiskCancellationWorkerTest4개 | 실제 소유 스케줄러의 비활성·단일 실제 스레드 ID/중복 시작·연속 준비 작업·저장 실패 지연·종료 인터럽트/후속 작업 차단 |
| OrderCancellationTest.theOutputWorkerMayClaimTheNewIntentBeforeTheApiWithoutASecondHttpCall | 최초 요청 커밋 직후 다른 스레드의 출력 선점·API의 중복 송신 방지·한 큐/한 HTTP·기존 예약 보존 |
| BitcoinCreditWireIntegrationTest.unresolvedChainRiskReachesTheRealEngineAndOnlyKafkaReleasesTheReservation | 실제 Rust HTTP/WAL/Kafka·플랫폼 원장으로 위험→시스템 예약→실제 취소 ACK→소비 전 예약 유지→실제 소비 뒤 한 번 반환·대사 MATCHED·복구 READY·위험 제한 유지 |

PG28개의 노드/외부 엔진 응답과 주입 금융 이벤트는 합성이다. 실제 운영 프로필 시험도 전체 애플리케이션의 외부 출력 자동 시작을 켠 시험은 아니며, 초기화된 실제 예약 서비스와 자신이 소유한 워커만 실행한다. 실제 연결6개는 각 함수별 독립 PG/Kafka/Rust를 쓰고 실제 주문 서비스를 실제 HTTP 클라이언트에 연결한다. 노드/사업자/수수료 정책은 합성이며 메인넷·외부 조직 운영 검증은 아니다.

```sh
python3 scripts/verify_bitcoin_chain_risk_cancellations.py
python3 scripts/verify_order_cancellations.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
python3 scripts/verify_bitcoin_credit_wire.py
./gradlew test --tests dustin.cex.domains.bitcoin.BitcoinChainRiskCancellationWorkerTest --rerun --console=plain
```

macOS/JDK21/Gradle8.14.6·PostgreSQL16·소유 Kafka·현재 Rust --locked --offline 빌드. 봇0·공유5432/9092 미사용·외부 공급사 비활성. PG28+고객 취소PG24+금융217+보안41+실제 연결6+예약 워커4 = **고유320개/320회 통과**, 실패·오류·제외0. 이전135의 수치를 더해 현재 합계로 만들지 않았다. .local/cycle136-proof에 실행 로그/JSON/JUnit XML을 보존하고 실행별 컨테이너 제거를 확인했다.

개발 중 마지막 운영 워커 시험의 작은 컨텍스트가 이미 초기화한 서비스의 DependsOn 스키마 빈을 재요구해 실패했다. 기존 실제 빈을 소유 컨텍스트의 singleton으로 등록해 재초기화를 피했고 제품 의존성을 제거하지 않았다. 새 워커 시험의 Plan 생성자에 UUID 대신 숫자를 넣은 컴파일 오류도 실제 타입에 맞춰 고쳤다. 해당 실패 원시 로그를 development-context-failure/development-compile-failure로 보존했다. 최종 결과와 구분한다.

두 JAR·Python 구문·현재 실제 코드 발췌/문서 링크·git diff --check를 확인했다. 기존 JDK 계측/폐기 예정 API/unchecked 경고는 남는다. 본격 운영 부하, 관리자 권한/보존 인프라/공급사 계약·심사는 별도다. 디스크/호스트 손실의 외부 백업·복제는 기존 지시대로 문서 조건만 유지했다.

## 남은 범위

취소 요청은 체결을 소급 취소하지 않는다. 미해결 노출/손실/보정은 별도 원장·승인 업무이며 자동 반전하지 않는다. 비VASP/소액/개인지갑·미지원 출처 반환·출금/서명·수탁 대사·전체 백엔드 검토·고객/운영 화면·마지막 ETH는 남는다. 코어 락/스레드/엔진 금융 프로토콜은 변경하지 않았다.

## 작성한 테스트 코드


[BitcoinChainRiskCancellationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinChainRiskCancellationIntegrationTest.java)

- `currentRiskStagesOneExactSystemCauseWithoutCallingTheEngine`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sequentialPlanningDoesNotCreateDuplicateRequestsOrMoveReservations`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theExistingOutputWorkerConfirmsOnlyTransportAndFinancialEventsReleaseTheFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `healthyCustomersDoNotAcquireAutomaticCancellationWork`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterHealthyObservationDoesNotEraseTheUnresolvedRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedRiskObservationsKeepTheOldestUnresolvedCause`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `systemAuditFailureRollsBackTheCauseAndQueueButPreservesTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedSystemAuditCannotCommitEitherRecord`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anIncorrectAuditDetailCannotBorrowTheSameIncident`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `systemActionCannotBeMisattributedToTheCustomer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerIntentAuditAloneCannotCoverTheSystemCause`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aCauseWithoutItsSameTransactionQueueCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `systemAuditWithoutARealCauseCannotAuthorizeTheNeutralQueue`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNormalObservationCannotBeSubstitutedForTheRiskCause`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCustomerCannotBorrowTheIncident`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `existingCustomerCancellationIsNeitherDuplicatedNorRelabeled`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aRetrospectiveSystemCauseCannotBeAttachedToAnExistingCustomerRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disabledCustomerStillReceivesRiskReducingCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherTradingRestrictionIsNeverRemovedByPlanning`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceAbsenceCannotBecomeAnEmptySuccessfulPlanningPass`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ambientTransactionsAreRejectedBeforePlanning`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `neutralStagingRequiresTheCallersActualTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `canonicalCauseCannotBeEditedDeletedOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceCannotBeBackdatedIntoAnotherTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `parallelPlannersDoNotCreateTwoCausesForTheSameCandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aFinancialCompletionBetweenCandidateReadAndLockIsNotCanceledAgain`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aCustomerRequestBetweenCandidateReadAndLockKeepsItsRealOrigin`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theOwnedProductionWorkerStagesReadyOrdersAndStops`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinChainRiskCancellationWorkerTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinChainRiskCancellationWorkerTest.java)

- `disabledWorkerDoesNotContactTheDispatchService`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oneLongLivedWorkerDrainsReadyRequestsAndDoesNotStartTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `storageFailureBacksOffBeforeTheNextOwnedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `shutdownDoesNotTakeAnotherRequestAfterTheInFlightCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[OrderCancellationTest.java](../../src/test/java/dustin/cex/domains/order/service/OrderCancellationTest.java)

- `requestAndClaimAreCommittedBeforeTheExternalCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aFreshDispatchServiceRetriesTheSamePersistedUnknownCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedAcceptedCancellationDoesNotSendAnotherFinancialCommand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anAmbientTransactionIsRejectedBeforeCreatingAnIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCustomerCannotCreateOrSendTheCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requestAuditFailurePreventsEveryExternalCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `claimAuditFailureLeavesTheCommittedIntentRetryableWithoutSending`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anOldResponseCannotOverwriteANewerLeasedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentCustomerRequestsHaveOneProducerAndOneOriginal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theOutputWorkerMayClaimTheNewIntentBeforeTheApiWithoutASecondHttpCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java)

- `actualHttpAcknowledgementWaitsForKafkaAndDuplicateDeliveryDoesNotCreditTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostHttpSuccessAndForcedEngineRestartPreserveTheSameDepositAndEvent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requiredAuditFailureStopsTheRealConsumerAndReplaysWithoutLosingFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realJournalReconciliationBuildsRecoveryEvidenceWithoutClearingTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationResponseLossAndEngineRestartPreserveReservationUntilTheRealKafkaResult`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unresolvedChainRiskReachesTheRealEngineAndOnlyKafkaReleasesTheReservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 90b9d88](https://github.com/GiHoon1123/Cex-Platform-Service/commit/90b9d88a1eaf36991a139ec3be81ab6fd51ef762)
- [격리 실행 증적](../reference/136-automatic-bitcoin-risk-order-cancellation-verification-2026-10-26.json)
