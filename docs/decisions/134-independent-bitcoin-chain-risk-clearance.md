# 독립 내용 서명으로 복구한 체인 위험 구간만 해제

실물 체인 복구와 실제 금융 대사가 일치한 입금의 위험 구간을 담당자와 별도 승인자가 확인한다. 승인 내용에 원래 입금·위험 구간·금융 지문·블록 앵커·두 실제 대사·지정 인물·만료를 고정하고 실제 보안키로 서명한다. 원시 위험/금융 기록을 보존하며 새 위험은 다시 차단한다. 공통 사이클134다.

## 문제와 기존 동작

[133](133-bitcoin-chain-recovery-proofs.md)은 잠금 밖의 자체 WAL/원장 대사와 독립 노드 대사를 수행해 READY 근거를 만들었지만 제한을 해제하지 않았다. 정상으로 돌아온 출력도 담당자 단독으로 해제하거나 승인 전에 추가된 위험/금융 이동을 예전 근거로 덮어서는 안 된다. 제한 전체를 지우거나 과거 위험 관찰을 삭제하는 방식은 누가 무엇을 확인했는지 재구성하기 어렵다.

## 기존 제품과 공식 자료·적용 구분

확인일은2026-10-26 KST다. [Kraken의 공개 입금 상태](https://support.kraken.com/articles/360000674066-cryptocurrency-deposit-statuses)는 보류에 담당자 수동 확인이 필요하다고 설명한다(표시 최종 갱신2026-05-05). 공개 상태만 참고하며 실제 내부 승인 인력/DB 구조가 이 프로젝트와 같다고 주장하지 않는다.

[OWASP 거래 인가 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)의 중요한 업무 내용 확인, 서버의 내용 고정/상태 전이 검사, 목적별 일회 인증과 제한된 유효 시간을 적용했다. 기존 [122 사건 후속 업무](122-explicit-case-closure-and-reporting-referral.md)의 OperatorMfaService·WebAuthn·불변 요청/결정·필수 감사를 재사용했다. 일반 인증과 이번 해제 인증을 혼용하지 않는다. 새 인증 라이브러리·범용 승인 프레임워크·별도 큐는 추가하지 않았다.

[NIST SP800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b/authenticators/)는 인증/재전송 방지의 해외 기술 참고다. 두 실제 인물·지정 역할·2분 근거 수명은 위험에 따른 자체 정책이며 국내 법령이 같은 화면/함수/시간을 직접 요구한다고 표시하지 않는다. 코드의 인물 ID가 실제 조직 신원 분리를 대신하지 않는다.

국내 기록 보존 근거는 [가상자산이용자보호법 제9조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0009&lsiSeq=290735&urlMode=lsScJoRltInfoR)다. 확인한 법률 제21857호의 시행일은2026-10-02이고 거래관계 종료 후15년의 가상자산거래기록 보존을 정한다. 이번에는 금융/위험 원본을 삭제하지 않고 결정과 연결했다. 모든 운영 로그에 같은 기간을 무차별 적용하거나 이 SQL만으로 하위 규정의 보관·파기·외부 심사를 충족했다고 주장하지 않는다. 전체 적용 조건은 공통 R6/R8 및 보존 계획과 연결한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| OPERATOR 요청·다른 실제 인물의 지정 APPROVER | 담당자 단독/다중 계정 자기 해제 방지 | 실제 조직 신원 매핑·별도 승인 인력이 필요하며 부재 시 새 요청 필요 |
| 실제 근거의 내용 전체를 해시/서명에 고정 | 다른 입금/금액/구간/근거로 바꿔 실행하지 못함 | 운영자는 내용을 직접 열람하고 별도 보안키 확인을 수행한다. 일반 로그인 서명을 재사용하지 않음 |
| 결정 자체에서 현재 위험 뷰 계산 | 결정·제한 반영 사이 비동기 지연/중간 상태 없음 | 위험 조회 때 승인 범위와의 SQL 결합 비용이 든다. 원시 관찰은 계속 보존 |
| 알려진 입금의 마지막 위험 ID까지만 제외 | 후속 위험이 과거 해제로 묻히지 않음 | 새 위험은 새 대사/요청/독립 판단이 필요하다. 고객 전체 제한 비트 초기화는 선택하지 않음 |
| 실제 대사2분 수명 그대로 사용 | 오래된 정상 근거의 무기한 승격 방지 | 두 운영자가 남은 시간 안에 처리하지 못하면 재대사 비용이 발생. 승인 옵션 발급이 근거 수명을 늘리지 않음 |
| 정상 관찰 ID 변경은 허용하되 같은 앵커/현재 판단 요구 | 정상 주기 재관찰만으로 승인을 계속 무효화하지 않음 | 관찰만 도착하고 판단이 아직 예전이면 잠시 차단, 새 판단 완료 후 다시 확인해야 함 |
| 해제 중 외부 RPC/엔진 금융 명령0 | 잠금 안 외부 대기와 추가 금융 부작용 제거 | 원래133의 실제 대사 증거에 의존하고 상태 변화/만료 때 새 근거 필요 |
| 감사/서명 소비/결정 같은 TX | 감사 실패 때 위험 해제와 인증 소비 모두 롤백 | 감사 장애면 해당 업무도 불가. 실패를 성공으로 바꾸는 폴백은 없음 |

## 업무 흐름과 보장 범위

1. 실제 적립 입금의133 READY 근거를 만든 OPERATOR가 지정 APPROVER로 해제 요청한다. 두 사람·고객 사용자 행은 ID 순으로 잠그고 현재 역할/직무/버전을 확인한다.
2. 입금/정확 문자열 금액·위험 관찰 개수와 첫/마지막 ID·금융 지문·대사 ID/블록 앵커·두 인물/버전·원래 근거의 만료를 불변 요청 및 승인 바인딩에 저장한다.
3. 지정 승인자는 실제 같은 내용/현재 세션의 열람 기록을 남긴다. 다른 요청·해시·승인/반려 방향·다른 세션의 인증은 재사용할 수 없다.
4. 승인/반려 직전 고객과 두 인물을 같은 순서로 잠그고 미결 상태·현재 위험/금융 지문·미종결 주문/잠금 잔액 없음·최신 입금 대사·현재 정상 관찰/판단을 재확인한다.
5. 실제 WebAuthn 인증 소비·불변 결정·필수 감사를 같은 TX로 확정한다. 승인만 알려진 접두 위험 구간을 제외하고 반려는 위험을 유지한다.
6. 새 위험 관찰은 원시 이력에 보존되고 이전 마지막 ID를 넘으므로 신규 금융 권한이 다시 제한된다. 이전 승인/위험/원장 원본은 그대로다.

현재 근거 검사는 결정 INSERT 전 트리거에서 하고, 필수 감사는 커밋 때 지연 제약으로 검사한다. 승인 행이 들어간 뒤에는 그 구간이 현재 위험 뷰에서 빠지므로, 같은 근거를 승인 후 현재 뷰에서 다시 찾는 식의 자기 무효화를 만들지 않는다. 판정 중 새 위험 공개는 같은 고객 행 잠금을 기다리고 승인 후 공개되면 새 ID로 다시 제한한다.

고객 비활성/다른 거래 제한·KYC/CDD/다른 입금 위험은 별도다. 이번 해제는 원래 적립을 다시 실행하거나 수탁 손실을 보전하거나 잔액을 추정하는 명령이 아니다. 일반 비코인베이스 출력의 물리 복구 근거에 한정하며 미체결 주문 정리·손실 정정·미실행/결과 불명 입금·반환과 성숙 코인베이스 복구는 별도 범위다.

## API

- `POST /api/admin/deposits/BTC/chain-clearances`: 현재 OPERATOR, Idempotency-Key UUID, 본문 proofId/checkerId.
- `GET /api/admin/deposits/BTC/chain-clearances/{id}`: 지정된 현재 담당자/승인자만 열람·감사. AUDITOR의 근거 조회는 기존133 경계를 넓히지 않는다.
- `POST /{id}/approve/options`, `/{id}/reject/options`: 지정 APPROVER의 해당 내용/방향 인증 옵션.
- `POST /{id}/approve`, `/{id}/reject`: 같은 해시와 해당 AuthorizationProof로 결정.

동일 키는 같은 근거/지정 승인자의 기존 요청을 반환하며 내용을 바꾸면409다. 익명401·역할/인물/세션 불일치403·미결/현재 근거/범위 불일치409다. 없는 원본도 현재 운영 인증부터 확인하며 외부 TX에서 시작하지 않는다. 기존 인증의 no-store를 유지한다.

## 실제 작성한 코드와 문법

출처: [chain-recovery-clearance-schema.sql](../../src/main/resources/bitcoin/chain-recovery-clearance-schema.sql#L27), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE VIEW bitcoin_credit_chain_risks AS
 SELECT h.* FROM bitcoin_credit_chain_risk_history h WHERE NOT EXISTS(
  SELECT 1 FROM bitcoin_chain_clearance_requests r JOIN bitcoin_chain_clearance_decisions d ON d.request_id=r.id AND d.approved
   WHERE r.receipt_id=h.receipt_id AND h.observation_id<=r.last_risk_id);

```

원시 이력 history는 지우지 않는다. 승인한 같은 입금/마지막 위험 ID 이하만 현재 제한에서 제외하므로 이후 새 관찰이 이전 승인에 포함되지 않는다. NOT EXISTS는 관련된 유효 승인 행이 없을 때만 해당 위험을 반환한다.

출처: [BitcoinChainRecoveryClearances.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRecoveryClearances.java#L74), 현재 소스의 부분 발췌.

```java
        check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.BTC_CHAIN_RECOVERY,id,hash,approve,proof);
        jdbc.update("INSERT INTO bitcoin_chain_clearance_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        audit.record(actor,"BTC_CHAIN_CLEARANCE_DECIDED","bitcoin-chain-clearance:"+id,"SUCCESS",hash);return read(id);
```

기존 내용 결합 인증 서비스를 사용한다. 이 호출과 결정 INSERT·감사는 같은 TransactionTemplate 안에서 실행된다. 감사 실패 시 공개키 카운터/챌린지 소비/결정이 함께 롤백되며 테스트에서 같은 서명을 다시 사용할 수 있는지도 검증한다.

출처: [BitcoinChainRecoveryClearances.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinChainRecoveryClearances.java#L82), 현재 소스의 부분 발췌.

```java
        var current=read(id);if(!"PENDING".equals(current.status())||!Objects.equals(hash,current.payloadHash()))throw denied(HttpStatus.CONFLICT);
        requireCurrent(p.proofId());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_chain_clearance_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=?)",
            Boolean.class,id,actor,p.checkerPerson(),p.checkerVersion(),sid,hash)))throw denied(HttpStatus.CONFLICT);
        return current;
```

사용자 잠금 뒤 결정 상태를 다시 읽는다. 같은 요청에 두 결정이 경합하면 먼저 확정한 한 건만 성공한다. 단순한 사전 if 검사나 화면 버튼 비활성화가 동시성 통제를 대신하지 않는다.

출처: [chain-recovery-clearance-schema.sql](../../src/main/resources/bitcoin/chain-recovery-clearance-schema.sql#L34), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_current(target uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM bitcoin_chain_recovery_probes p JOIN bitcoin_chain_recovery_results r ON r.probe_id=p.id AND r.status='READY'
  JOIN bitcoin_chain_recovery_basis(p.receipt_id) b ON b.scope_hash=p.scope_hash AND b.ledger_hash=p.ledger_hash
  JOIN bitcoin_current_deposit_reconciliation n ON n.id=r.native_run AND n.receipt_id=p.receipt_id AND n.status='MATCHED'
  JOIN bitcoin_deposit_receipts receipt ON receipt.id=p.receipt_id JOIN bitcoin_deposit_addresses addr ON addr.id=receipt.address_id
  JOIN bitcoin_observation_heads h ON h.network=receipt.network AND h.txid=receipt.txid
```

불변 READY의 존재만 확인하지 않는다. 현재 위험/금융 지문·최신 입금 대사·체인 관찰/판단을 다시 연결한다. 외부 노드나 엔진 코어는 호출하지 않는 SQL 읽기다.

출처: [BitcoinChainClearanceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinChainClearanceIntegrationTest.java#L64), 현재 소스의 부분 발췌.

```java
    @Test void decisionAuditFailureRollsBackBothClearanceAndSignatureConsumption()throws Exception{
        var r=request();clearances.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);
        doThrow(new IllegalStateException("격리 해제 결정 감사 실패")).when(audit).record(anyLong(),eq("BTC_CHAIN_CLEARANCE_DECIDED"),anyString(),anyString(),anyString());
        assertThrows(RuntimeException.class,()->clearances.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof));assertEquals(0,rows("bitcoin_chain_clearance_decisions"));assertFalse(eligible());
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));
        doCallRealMethod().when(audit).record(anyLong(),eq("BTC_CHAIN_CLEARANCE_DECIDED"),anyString(),anyString(),anyString());assertEquals("APPROVED",clearances.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof).status());assertTrue(eligible());
    }
```

실제 보안키 서명을 만든 뒤 결정 감사만 실패시킨다. 위험 제한과 미소비 챌린지를 확인하고 감사 복구 후 같은 서명이 한 번 정상 확정되는지 검증한다. 제품 트리거를 꺼서 시험을 통과시키지 않는다.

## 초기화·기존 경로 정리

원시 관찰 판정은 bitcoin_credit_chain_risk_history로 옮겼다. 관찰 저장의 감사 트리거는 원시 이력을 사용하고 금융 자격/현재 위험 목록은 승인 범위를 제외한 bitcoin_credit_chain_risks를 사용한다. 시작 시 active 뷰를 잠깐 무필터 정의로 되돌렸다가 나중에 수정하지 않는다. 모든 승인 표를 준비한 마지막 스키마에서 한 번 현재 뷰를 정의한다.

기존 proof_basis 함수는 PL/pgSQL RETURN QUERY로 바꿔 실행 시 준비된 현재 뷰를 해석한다. 원래 데이터나 기록을 변환하는 호환/폴백 경로를 만들지 않았다. 승인 종류 enum/DB 허용 목록에 실제 BTC_CHAIN_RECOVERY만 추가했고, 기존 BoundApprovalTest의 전체 switch도 전용 통합 시험을 요구하는 분기에 명시했다. 이 종류를 임의의 기존 승인 업무로 실행하지 않는다.

## 작성한 테스트

아래 신규 파일의 함수명마다 시나리오/기대 결과가 코드에 있다. 정상/반려·새 위험 재차단·열람/해시/서명 목적·필수 감사의 예외/조용한 누락·서명 소비 롤백/재실행·정상 재관찰과 판단 공백·최신 대사 실패·금융 투영 변경·현재 역할/인물/세션·다른 고객 제한 보존·멱등·불변 이력·실제 병렬 결정·직접 SQL 미인증·실제 HTTP를 검증한다. 경계 노드/엔진 원본은 합성이고 실제 PG/원장/대사/보안키 서명 검증을 사용한다.

실제 Rust HTTP/WAL/Kafka 연결 회귀와 기존 복구 근거·체인 위험·운영 조회·금융·네 승인/역할/수명/보안 회귀의 실행 및 고유 이름 집계는 아래 증적을 따른다. 테스트별 소유 DB·브로커만 제거하고 기존5개 컨테이너와 공유5432/9092를 건드리지 않는다. 봇0·일반 소비자 자동 시작 금지를 유지한다.

## 실행 명령과 결과

```sh
caffeinate -i python3 scripts/verify_bitcoin_chain_clearances.py
python3 scripts/verify_bitcoin_chain_recovery_proofs.py
python3 scripts/verify_bitcoin_credit_chain_risk.py
python3 scripts/verify_bitcoin_credit_chain_risk_review.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_bound_approvals.py
python3 scripts/verify_bitcoin_credit_wire.py
```

환경은 macOS/JDK21/Gradle8.14.6·소유 PostgreSQL16이며 실제 연결 회귀는 소유 Kafka와 현재 Rust 소스 빌드를 사용한다. 원시 증적은 .local/cycle134-proof에 보존하고 /tmp/run-cycle134-regressions.py에서 클래스/메서드 이름으로 집계했다.

최종 **고유474개/474회 통과**, 실패·오류·제외0이다. 실제 함수 목록/그룹별 결과는 기계 판독 증적을 따른다. 이전133/개발 중 실행을 합산하지 않는다.

초기 컴파일에서 새 enum의 기존 전체 switch 누락과 신규 시험의 상속 메서드 이름을 바로잡았다. 첫 확대 실행의 정상 재관찰 시험은 새 관찰의 자격 판단을 수행하지 않아 현재 판단 공백으로 차단됐다. 제품의 현재 판단 검사를 느슨하게 바꾸지 않고 실제 새 판단을 함께 수행하는 정상 시험과 판단 공백을 차단하는 별도 회귀로 나눴다. 최종 전체 실행으로 재검증했다.

두 JAR 빌드/새 클래스·스키마 포함과 테스트 제외·Python 구문·실제 코드 발췌·문서 상대 링크·git diff --check를 확인했다. 기존 deprecated/unchecked/JDK 계측 경고는 남는다. 로컬 소프트웨어 보안키 검증은 실운영 하드웨어/조직 인력·신원 검증의 완료가 아니다.

## 남은 범위

이번에는 물리 복구가 확인된 적립 입금의 알려진 위험 구간만 해제한다. 재조직 손실·노출된 기존 주문 취소/정리·원장 정정/반환·다른 출처 입금과 출금/수탁 대사, 전역 보존/제출·백엔드 전체 검토·프론트·마지막 ETH는 계속 후속이다. 외부 복제/백업 배포를 하지 않았고 실제 운영·조직·인허가/인증 충족으로 표시하지 않는다.

## 작성한 테스트 코드


[BitcoinChainClearanceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinChainClearanceIntegrationTest.java)

- `independentContentSignedApprovalClearsOnlyTheKnownRiskWithoutMovingMoney`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `rejectionPreservesTheRiskAndCannotBeApprovedAgain`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewRiskAfterApprovalBlocksAgainAndKeepsTheEarlierDecision`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theCheckerMustActuallyInspectTheExactRequestBeforeSigning`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aDifferentPayloadHashCannotBorrowTheSameRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aRejectionSignatureCannotBeUsedAsApproval`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMissingSignatureCannotClearTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requestAuditFailureRollsBackTheRequestAndItsMfaBinding`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedRequestAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `viewAuditFailureCannotLeaveAnInspectionReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedViewAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `decisionAuditFailureRollsBackBothClearanceAndSignatureConsumption`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedDecisionAuditCannotCommitAnActiveClearance`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewRiskBeforeTheDecisionInvalidatesTheSignedOldScope`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `healthyPollingWithTheSameAnchorDoesNotInvalidateTheRiskScope`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aLaterFailedNativeReconciliationInvalidatesTheEarlierReadyProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aChangedFinancialProjectionCannotUseTheOldRecoveryProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disablingTheRequesterInvalidatesTheAssignedApproval`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disablingTheCheckerInvalidatesTheAssignedApproval`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `clearingTheChainRiskDoesNotEnableADisabledCustomer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `clearingTheChainRiskDoesNotRemoveAnotherTradingRestriction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sameKeyHasOneRequestAndCannotAssignAnotherChecker`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theSamePersonCannotActAsTwoDifferentAccounts`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerAndAuditorCannotProposeOrInspectAnAssignedClearance`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnassignedOperatorCannotBorrowAnotherOperatorsRecoveryProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immutableRequestsInspectionsAndDecisionsCannotBeEdited`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `parallelDecisionsConsumeOneSignatureAndClearOnce`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directSqlCannotClearWithoutAConsumedMatchingAuthorization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `ambientTransactionsAndNilKeysCannotCreateClearanceRequests`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualHttpEnforcesRoleBoundariesAndShowsTheExactSignedTerms`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewObservationWithoutItsCurrentQualificationCannotReuseTheOldDecision`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnknownSourceDoesNotRevealItsExistenceBeforeCurrentAuthorization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BoundApprovalTest.java](../../src/test/java/dustin/cex/domains/security/BoundApprovalTest.java)

- `identicalIdsAndHashesAcrossWorkflowsDoNotShareProof`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oldPendingRequestWithoutBindingRemainsReadableButCannotExecute`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 e162947](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e162947fb9ff94f09f0ddbeb9a9641fdad4582d6)
- [격리 실행 증적](../reference/134-independent-bitcoin-chain-risk-clearance-verification-2026-10-26.json)
