# 현재 운영 인증과 실제 열람 감사로 체인 위험 조회

실행 이후 체인 위험 때문에 제한된 입금을 운영자가 찾고 원인을 비교할 수 있게 한다. 현재 운영 세션/역할로 입금별 목록과 상세의 위험 범위를 읽으며, 실제 반환한 입금·마지막 위험 관찰을 같은 트랜잭션의 감사에 연결한다. 조회는 해제 승인이나 원장 정정이 아니다. 공통 사이클132다.

## 문제와 기존 동작

[131](131-bitcoin-post-authorization-chain-risk.md)은 권한 이후의 위험 관찰로 신규 금융 권한을 차단했다. 이후 정상 관찰만으로 위험을 지우지 않지만 운영자가 대상 입금을 찾는 목록은 없었다. 기존 [094 상세](094-operator-bitcoin-deposit-review-source.md)는 현재 체인/자격을 보여줘도 과거 실행 이후 위험 범위를 별도로 표시하지 않았다. 현재 CONFIRMED와 과거 위험으로 인한 제한을 함께 설명할 수 없었다.

또한 페이지 번호/건수만 감사하면 나중에 새 위험이 추가된 목록을 정확히 재구성하기 어렵다. 반환한 원본 참조가 남아야 누가 어느 입금을 열람했는지 연결할 수 있다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST 확인한 [Kraken 공개 입금 상태](https://support.kraken.com/articles/360000674066-cryptocurrency-deposit-statuses)는 보류 상태와 담당자의 확인을 구분한다. 이 공개 운영 흐름을 참고하되 내부 화면/DB 구조를 추측해 복제하지 않는다.

실제 접근 통제는 설치된 기존 BitcoinDepositOperatorAccess·OperatorSessionPolicy·AuditService를 따른다. [094](094-operator-bitcoin-deposit-review-source.md)·[103](103-operator-deposit-origin-search-review.md)의 현재 역할/최근 인증·감사 패턴을 재사용했다. 새 권한 프레임워크나 금융 상태 저장소는 추가하지 않았다. 부여/변경된 역할의 세션 회수는 기존 OperatorRoleService의 실제 독립 승인 경로로 검증한다.

[PostgreSQL16 문장 스냅샷](https://www.postgresql.org/docs/16/transaction-iso.html)을 기준으로 범위 집계와 마지막 이유를 한 SQL에서 읽는다. 서로 다른 SELECT 결과를 섞어 원래 없던 범위를 만들지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 입금 하나당 위험 관찰을 묶어 표시 | 반복 관찰을 여러 적립/손실로 오인하지 않음 | 관찰 수는 경제적 노출액이 아니다. 전체 과거 원본은 참조 ID로 확인 |
| 기존 상세에 executionRisk 추가 | 현재 체인 상태와 해결되지 않은 과거 위험을 함께 설명 | 고객 자격/체인/위험/대사는 별개이며 이 DTO를 금융 허가 증명으로 사용하지 않음 |
| 현재 OPERATOR/APPROVER/AUDITOR와 기존5분 인증 | 옛 JWT/회수 세션/만료 직무로 조회하지 못함 | 운영자는 최근 인증을 갱신해야 한다. 일반 고객의 일상 조회 흐름은 추가하지 않음 |
| 목록 감사와 항목별 참조 감사 | 변한 페이지에서도 실제 표시한 원본을 추적 | 최대25개 항목과 목록1개로 최대26개 감사 행이 생긴다. 저장/SQL 처리 비용을 감수하며500자 필드를 잘라 누락하지 않음 |
| 목록/항목 감사와 응답 구성 같은 TX | 일부 감사 실패 때 부분 성공 응답 방지 | 감사 저장 장애면 조회도 사용할 수 없다. 빈 목록 폴백 대신503 또는 실패 |
| 한 페이지25개·범위 제한·10초 TX | 대량 조회의 자원 사용 상한 | 전체 위험 이력 집계의 DB 비용이 사라지는 것은 아니다. 실제 운영 규모의 쿼리/조회율 측정은 남음 |
| 기존 노드 실행기의 조회 전용 선택 | 변경과 관련된 실제 설정/PG 회귀를 재현 | 전체231개를 다시 실행한 것으로 부르지 않는다. 기본 전체 실행은 유지 |

## API와 데이터 흐름

- `GET /api/admin/deposits/BTC/chain-risks?page=0`: 입금별 위험 관찰 개수·첫/마지막 관찰 ID와 기록 시각·마지막 이유·정확 문자열 금액을 반환한다. 페이지는0~10000, 한 번에25개다.
- 기존 `GET /api/admin/deposits/BTC/{id}`: executionRisk에 관찰 범위와 마지막 이유를 함께 반환한다. 현재 관찰이 CONFIRMED여도 과거 WAITING_CHAIN 위험은 남을 수 있다.
- 익명401·고객403이며 세 운영 역할은 현재 세션·최근 추가 인증·직무 수명을 서비스에서도 검사한다. 기존 Spring Security의 정확한 GET 경로와 역할 정책을 사용하며 HTTP 응답의 no-store를 확인했다.
- 목록은 이름·생일·주소·스크립트·공급사 원문을 복사하지 않는다. 기존 상세/비공개 증빙의 접근 통제를 넓히지 않았다.

목록 식별자는 내부 감사 연결에만 사용한다. 항목 감사는 `bitcoin-deposit:<입금 ID>` 대상에 같은 목록 ID와 마지막 위험 관찰 ID를 남긴다. 일반 감사 필드의500자 상한을 바꾸거나 잘라 보관하지 않는다. 항목 원본/순서는 불변 참조에서 확인한다.

## 실제 작성한 코드와 문법

출처: [BitcoinCreditChainRiskReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditChainRiskReview.java#L36), 현재 소스의 부분 발췌.

```java
            sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            if(page<0||page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"위험 목록 페이지를 확인하세요");
```

조회 전에 실제 사용자 행과 영속 세션을 확인한다. HTTP 필터를 통과한 뒤 역할/회수가 바뀌는 경우에도 현재 상태로 판단한다. 페이지의 범위 검사는 권한 검사 이후이며 새 프론트가 역할을 숨기는 것만으로 통제를 대신하지 않는다.

출처: [BitcoinCreditChainRiskReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditChainRiskReview.java#L41), 현재 소스의 부분 발췌.

```java
                WITH scoped AS(SELECT risk.* FROM bitcoin_credit_chain_risks risk
                  JOIN bitcoin_deposit_receipts r ON r.id=risk.receipt_id WHERE r.network=?),
                grouped AS(SELECT receipt_id,user_id,asset,amount,count(*) observations,min(observation_id) first_id,
                  max(observation_id) last_id
                  FROM scoped GROUP BY receipt_id,user_id,asset,amount)
                SELECT g.*,oldest.recorded_at first_at,latest.recorded_at last_at,latest.reason FROM grouped g
                 JOIN scoped oldest ON oldest.receipt_id=g.receipt_id AND oldest.observation_id=g.first_id
                 JOIN scoped latest ON latest.receipt_id=g.receipt_id AND latest.observation_id=g.last_id
                 ORDER BY g.last_id DESC,g.receipt_id LIMIT 25 OFFSET ?
                """,settings.network.id,page*25).stream().map(row->new Item((UUID)row.get("receipt_id"),((Number)row.get("user_id")).longValue(),
```

CTE는 이 SQL 안의 조회 결과에 붙인 이름이다. scoped를 재사용해 집계와 첫/마지막 관찰의 원래 시각/이유를 연결한다. ID의 순서와 시계의 최소/최대값을 섞지 않는다. 금액은 합산하지 않고 원래 입금 금액을8자리 문자열로 반환하므로 TypeScript Number 변환을 요구하지 않는다.

출처: [BitcoinCreditChainRiskReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditChainRiskReview.java#L55), 현재 소스의 부분 발췌.

```java
            UUID listId=UUID.randomUUID();
            audit.record(actor,"BTC_CREDIT_CHAIN_RISK_LIST_READ","bitcoin-chain-risks:"+listId,"SUCCESS","PAGE:"+page+":COUNT:"+items.size());
            for(var item:items)audit.record(actor,"BTC_CREDIT_CHAIN_RISK_ITEM_READ","bitcoin-deposit:"+item.depositId(),"SUCCESS",
                "LIST:"+listId+":LAST_RISK:"+item.lastObservationId());
            return new Page(page,items);
```

같은 TransactionTemplate 콜백에서 목록과 항목 감사를 기록한다. 어느 하나라도 예외가 나면 앞선 감사 행도 롤백되고 Page를 반환하지 않는다. UUID는 목록 열람의 참조이며 금융 승인 ID가 아니다. 응답 성공은 서버가 조회 결과를 구성했다는 뜻이고 사람이 내용을 실제로 읽었다는 증명은 아니다.

출처: [BitcoinDepositReviewSource.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java#L51), 현재 소스의 부분 발췌.

```java
                WITH execution_risk AS(SELECT * FROM bitcoin_credit_chain_risks WHERE receipt_id=?),
                risk_scope AS(SELECT count(*) risk_count,min(observation_id) first_risk_id,max(observation_id) last_risk_id FROM execution_risk)
                SELECT r.*,statement_timestamp() read_at,cp.assessment_id,kp.active_attempt,
                 rs.risk_count,rs.first_risk_id,rs.last_risk_id,last_risk.reason last_risk_reason,
                 o.id observation_id,o.status chain_status,o.confirmations,o.read_started_at,w.wallet_debit,
```

상세 조회도 동일 입금의 위험을 한 CTE에서 읽는다. count는 위험 원본이 없으면0이지만 min/max는 null로 유지한다. 조회 원본 자체가 없거나 오류가 난 경우를0으로 바꾸는 폴백은 없다. 기존 원장/대사나 엔진 상태를 수정하지 않는다.

출처: [BitcoinCreditChainRiskReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditChainRiskReviewIntegrationTest.java#L53), 현재 소스의 부분 발췌.

```java
        var request=roles.request(requester.user().getId(),requester.sid(),target.getId(),target.getVersion(),target.getOperatorPersonId(),UserRole.APPROVER,Instant.now().plusSeconds(3600),"체인 위험 조회 담당 직무 변경","IDENTITY_CHAIN_RISK_TEST","NOTICE_CHAIN_RISK_TEST");
        var options=roles.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true).authentication();
        var proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
        roles.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof);
        assertEquals(UserRole.APPROVER,users.findById(target.getId()).orElseThrow().getRole());assertTrue(tokens.findBySessionId(maker.sid()).orElseThrow().getRevoked());
```

역할 변경은 실제 요청/별도 승인자·소프트웨어 보안키의 내용 결합 서명을 거친다. 기존 세션의 회수를 확인하고 읽기 권한이 있는 APPROVER로 바뀐 뒤에도 옛 세션은 거부되는지 검사한다. User의 @Version만 바뀐다고 JWT/세션이 자동 회수되는 것은 아니며 실제 역할 서비스가 회수한다.

## 작성한 테스트와 기대 결과

신규 [BitcoinCreditChainRiskReviewIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditChainRiskReviewIntegrationTest.java)의18개는 실제 PG와 합성 외부 증빙/노드 응답을 사용한다. 입금 실행 권한과 위험 관찰은 제품 서비스를 통해 준비하며 새 조회가 엔진·노드·외부 공급사를 호출하지 않았는지 확인한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| allThreeCurrentOperatorRolesCanReadTheSameMinimalRisk | 세 현재 운영 역할의 동일 최소 범위/정확 금액, 목록·항목 감사 각각3개 |
| repeatedRiskObservationsAreOneDepositWithAnOrderedScope | 반복 관찰을 한 입금으로 묶고 첫/마지막 ID·마지막 이유 일치 |
| laterHealthyChainDoesNotRemoveTheUnresolvedRisk | 후속 정상 체인 관찰 뒤에도 기존 위험 표시 |
| serializedListAndAuditDoNotCopyIdentityOrAddress | 이름·생일·주소·스크립트·이메일 원문을 목록/목록 감사에 넣지 않음 |
| customerAndMismatchedSessionCannotProbeTheList | 고객은 페이지 범위와 관계없이403, 다른 운영 세션403 |
| disabledOperatorCannotReuseTheRole | 비활성 직무 주체의 조회403 |
| revokedSessionCannotReusePastMfa | 회수한 세션의 조회403 |
| expiredOperatorGrantCannotReuseThePreviouslyValidRole | 만료 직무의 조회403 |
| changedRoleVersionInvalidatesAnOldSession | 실제 독립 역할 변경 승인 후 옛 세션 회수/조회403 |
| pageBoundsAreCheckedAfterAuthorizationAndLargePageIsEmpty | 범위 밖400, 상한 안의 빈 페이지는 정상 빈 목록 |
| requiredReadAuditFailureDoesNotReturnAView | 목록 감사 실패 때 성공 반환/금융 변경 없음 |
| missingRiskSourceCannotBecomeAnEmptySuccessfulList | 뷰 누락503·성공 감사 없음 |
| ambientTransactionCannotHoldLocksAcrossAListRead | 외부 TX에서 시작 거부 |
| theActualHttpRouteRequiresCurrentOperatorAuthentication | 실제 HTTP 필터의401/403/200·정확 문자열 금액·no-store |
| existingDepositReviewSeparatesCurrentChainFromExecutionRisk | 현재 CONFIRMED와 예전 위험 범위·제한을 따로 표시 |
| actualHttpDetailReturnsTheRiskScopeWithoutMovingMoney | 상세 HTTP의 위험 참조와 사용 가능 잔액 미생성 |
| itemAuditFailureRollsBackTheListAuditAndReturnsNoPartialView | 항목 감사 실패 때 목록 감사까지 롤백 |
| listAndItemAuditsReferToTheExactReturnedSource | 동일 목록 UUID·실제 반환 입금/마지막 관찰과 감사 연결 |

기존 [BitcoinDepositReviewSourceIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositReviewSourceIntegrationTest.java)24개도 재실행했다. 미관찰/null·새 관찰/옛 판단 구분·최신 RUNNING/FAILED 공개·현재 역할/금융 자격·감사 실패·불변 대사 순서/이력 보존을 유지한다.

## 실행 명령과 실제 결과

환경: macOS·JDK21·Gradle8.14.6·소유 PostgreSQL16. 기존 조회24개는 실제 Core31.1 노드/제한 RPC 설정과 소유 PG를 준비하지만 조회용 금융 원본은 합성이고 실제 reader 호출0을 검증한다. 그24개를 모두 실제 온체인 입금 처리라고 부르지 않는다.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew compileTestJava --console=plain
caffeinate -i python3 scripts/verify_bitcoin_credit_chain_risk_review.py
CEX_BITCOIN_CORE_HOME=../.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py --deposit-review-only
python3 scripts/verify_engine_inbox.py
```

신규18·기존 조회24·금융182, **고유224개/224회 통과**, 실패·오류·제외0이다. /tmp/run-cycle132-regressions.py가 순서대로 실행/원시 로그·JUnit XML·고유 이름을 보존·집계했다. 원시 증적은 .local/cycle132-proof다. 이전131의520고유/538회나 첫16개 개발 실행을 이번 수에 합치지 않는다.

검증 실행기는 새 DB를 메서드별로 생성하고 현재 실행의 서버만 제거한다. 노드도 소유 프로세스만 종료했다. 금융 원본 삭제/트리거 해제로 충돌을 해결하지 않았고 기존5개 컨테이너는 유지했다. 봇0·소비자 자동 시작/외부 처리 비활성화를 유지했다.

새 실행기는 설치된 `./gradlew help --task test`에서 확인한 `test --rerun`을 쓴다. [Gradle 공식 CLI](https://docs.gradle.org/current/userguide/command_line_interface.html)는 특정 작업의 재실행과 전체 의존 작업 재실행을 구분한다.8.14.6 버전 웹 페이지 조회는 실패했지만 설치된 CLI와 실제 각 시험의 새 XML/정확한 함수 이름으로 실행을 확인했다. 테스트는 매번 강제로 실행하고 변경하지 않은 컴파일 결과만 재사용한다. 의존성/버전을 변경하지 않았다.

두 JAR 최종 빌드·새 제품 클래스 포함/시험 제외·Python AST·문서 발췌/상대 경로·git diff --check를 확인했다. CLI --help는 Core 환경 없이0, 알 수 없는 선택은2이며 노드/DB를 시작하지 않는다. 기본 전체 노드 실행 경로는 유지하지만 이번에는 --deposit-review-only를 실행했으며 전체231개를 재실행한 것으로 표시하지 않는다.

초기18개 검증 중 역할 필드만 직접 바꾼 뒤 공식 역할 변경 업무의 세션 회수를 기대한 시험이 실패했다. 제품 정책을 느슨하게 바꾸지 않고 실제 독립 승인/보안키 서명 경로로 시험을 수정한 뒤18개를 처음부터 다시 실행했다. 기존094/103 문서의 현재 발췌도 새 위험 참조/바인딩 인수에 맞춰 갱신했다. 기존 deprecated/unchecked/JDK 계측 경고는 남는다.

## 남은 범위

운영자가 위험 대상을 찾고 현재 체인/과거 위험을 비교할 수 있는 단계다. 조회/감사 성공만으로 제한을 풀거나 적립을 역분개하지 않는다. 다음은 복구 근거·현재 전체 위험 범위와 독립 승인에 결합한 해제 업무다. 기존 금융 사실·다른 고객 제한·새 위험 자동 차단은 유지해야 한다.

노출 정리·미체결 취소·손실 정정/반환·출금/서명·외부 운영 조건과 전체 검토·프론트·마지막 ETH는 계속 후속이다. 코어/기록/출력 워커·Kafka 금융 계약·외부 백업 인프라는 이번에 바꾸지 않았다.

## 작성한 테스트 코드


[BitcoinCreditChainRiskReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditChainRiskReviewIntegrationTest.java)

- `allThreeCurrentOperatorRolesCanReadTheSameMinimalRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedRiskObservationsAreOneDepositWithAnOrderedScope`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterHealthyChainDoesNotRemoveTheUnresolvedRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `serializedListAndAuditDoNotCopyIdentityOrAddress`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerAndMismatchedSessionCannotProbeTheList`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disabledOperatorCannotReuseTheRole`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `revokedSessionCannotReusePastMfa`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `expiredOperatorGrantCannotReuseThePreviouslyValidRole`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedRoleVersionInvalidatesAnOldSession`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `pageBoundsAreCheckedAfterAuthorizationAndLargePageIsEmpty`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requiredReadAuditFailureDoesNotReturnAView`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingRiskSourceCannotBecomeAnEmptySuccessfulList`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ambientTransactionCannotHoldLocksAcrossAListRead`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theActualHttpRouteRequiresCurrentOperatorAuthentication`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `existingDepositReviewSeparatesCurrentChainFromExecutionRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualHttpDetailReturnsTheRiskScopeWithoutMovingMoney`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `itemAuditFailureRollsBackTheListAuditAndReturnsNoPartialView`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `listAndItemAuditsReferToTheExactReturnedSource`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 5a3a27f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/5a3a27f6c4acb302a0b0fe8d6fe036b7b1059a74)
- [격리 실행 증적](../reference/132-operator-bitcoin-chain-risk-review-verification-2026-10-26.json)
