# 입력 잠금 관측과 미실행 후보의 안전한 배정 해제

DB의 현재 입력 배정과 Core의 잠금 상태를 원자 커밋으로 취급하지 않고, 외부 호출 전 시도와 후 관측을 보존한다. 취소·새 승인·만료로 종료된 미실행 후보의 입력을 언제 해제할 수 있는지, 실패 시 어떤 기록과 배정을 남기는지 설명한다.

## 문제와 기존 동작

[151](151-bitcoin-durable-payout-candidates.md)의 입력 배정은 금융 귀속을 보존하지만 [150](150-bitcoin-persistent-input-locks.md)의 노드 잠금과 아직 연결되지 않았다. DB 기록 전에 노드를 호출하거나 노드 오류만 보고 배정을 삭제하면 응답 유실/재시작에서 결과를 추측해야 한다. 반대로 금융·감사 이력까지 삭제해 입력을 재사용하면 과거 지급 판단을 복원할 수 없다.

이번 구현은 내부 후보 잠금/정리 서비스다. 자동 실행 워커·새 고객/운영자 HTTP·키 사용·서명·방송을 추가하지 않는다. 148의 내부 업무 실행 확정과 실제 네이티브 서명 권한을 구분한다. 실제 지급 실행에 후보/현재 잠금 증거·선택 입력 출처를 결합하는 작업은 후속이며, 이번 결과를 지급 완료로 표시하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [Core31 lockunspent](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/lockunspent/)와 [listlockunspent](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/listlockunspent/)의 기존 구현을150 클라이언트로 재사용한다. Core 잠금은 자동 코인 선택 제외 수단이고 배정 주인을 반환하지 않는다. 이미 있는 잠금의 영속 여부를 조회로 증명하지 못하며 DB의 배정을 Core에서 추측해 재구성하지 않는다.

DB 원자성은 [PostgreSQL16 제약](https://www.postgresql.org/docs/16/ddl-constraints.html)과 [지연 제약 트리거](https://www.postgresql.org/docs/16/sql-createtrigger.html)에 맡긴다. 네트워크 호출은 그 트랜잭션 밖에 두고 기존 장수 RPC 클라이언트와 암호/HMAC을 사용한다. 직접 락 파일이나 분산 트랜잭션 프레임워크를 만들지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| DB 시도/감사 선행 커밋 → 노드 관측 → 결과 보존 | 결과가 사라진 외부 호출을 미해결로 식별 | 커밋·암호·감사 쓰기 증가. 결과 저장 장애 때 시작만 남으며 같은 후보로 재확인해야 한다 |
| OBSERVED/UNKNOWN과 배정 정리 여부 구분 | 실제 관측과 현재 금융 귀속의 혼동 방지 | 호출자가 상태를 세분해 처리해야 한다. UNKNOWN은 성공/실패 송금이나 환급 근거가 아니다 |
| 잠금 시도가 있으면 명시 입력 해제 관측 필요 | 응답 유실을 근거로 입력을 재사용하지 않음 | 노드 장애가 입력 유동성을 보류한다. DB 배정만 먼저 지우지 않는다 |
| 잠금 시도 자체가 없으면 노드 호출 없이 미실행 정리 | 건드리지 않은 노드 상태를 바꾸지 않고 현재 배정 반환 | 취소·최신 승인 변경·만료와 미실행을 먼저 증명해야 한다 |
| 최초 해제 시도 이후 다시 잠금 금지 | 과거 권한/시각 변화로 정리 중 후보 부활 방지 | 같은 후보를 되살리는 대신 새 유효 검토와 후보가 필요하다 |

동일 후보의 시도는 마지막 시작/결과로부터30초 대기한다. 이 시간은 서버 쪽 이전 RPC의 종료를 증명하지 않으며 금융 잠금의 자동 만료도 아니다. 늦은 Core 명령은 코인 선택 제외 상태를 바꿀 수 있다. 전역 DB 배정이 금융 귀속의 권위이고, 원래 후보의 재시도로 다른 후보의 입력을 풀지 않아야 한다. 이미 정리된 후보의 반복 해제는 노드를 다시 호출하지 않는다. Core 상태와 DB가 동시에 바뀌었다고 주장하지 않는다.

## 구현과 실제 코드·원리

`BitcoinPayoutCandidates.forLock`은 현재 승인·원본·경제 범위를 다시 검사한다. 정리용 `active`는 취소 뒤에도 암호화 원본과 현재 배정을 읽되 그것만으로 새 잠금 권한을 부여하지 않는다. 두 경로는 호출자가 연 DB 트랜잭션 안에서만 실행한다.

출처: [BitcoinPayoutCandidateWallet.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCandidateWallet.java#L39), 현재 소스의 부분 발췌.

```java
    public Outcome lock(UUID candidateId){
        boundary();var start=tx.execute(t->{var c=candidates.forLock(candidateId);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=? AND operation='UNLOCK')",Boolean.class,candidateId)))throw held();
            return start(c,"LOCK");});return execute(start,false);
    }
    /** 취소·새 승인·만료와 미실행을 확인한 자기 배정만 정리한다. 금액 환급이나 금융 이력 삭제는 하지 않는다. */
```

현재 승인과 입력 귀속을 검사하고 시작 기록을 저장한 트랜잭션이 커밋된 뒤 `execute`로 넘어간다. `UNLOCK` 시도 이력이 하나라도 있으면 새 `LOCK`을 거절한다. Java 콜백이 반환됐다는 사실만으로 외부 노드 결과가 생긴 것은 아니다.

출처: [BitcoinPayoutCandidateWallet.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCandidateWallet.java#L68), 현재 소스의 부분 발췌.

```java
            observation=releasing?node.release(prepared.network(),prepared.wallet(),prepared.inputs()):node.ensure(prepared.network(),prepared.wallet(),prepared.inputs());
            if(!matches(prepared,observation,!releasing))observation=null;
        }catch(ResponseStatusException unknown){if(unknown.getStatusCode().value()!=503)throw unknown;}
        var observed=observation;
        return tx.execute(t->{
            // 취소나 권한 변경이 호출 도중 발생해도 외부 관측 사실은 남긴다. 그것이 현재 지급 허가를 되살리지는 않는다.
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",p.funds().customerId());
            String status=observed==null?"UNKNOWN":"OBSERVED",key=null,mac=null;byte[] cipher=null;
```

노드 입출력은 DB 트랜잭션 밖이다. 망·지갑·정확한 입력 목록·잠금 방향이 요청과 맞아야 관측으로 저장한다. 확인 불가503은 UNKNOWN이 된다. 예상하지 못한 프로그래밍/DB 오류를 노드 성공이나 확정 실패로 바꾸지 않는다.

출처: [BitcoinPayoutCandidateWallet.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCandidateWallet.java#L89), 현재 소스의 부분 발췌.

```java
    private void close(BitcoinPayoutCandidates.Candidate c,UUID observed,String reason){
        UUID id=c.payload().id();jdbc.update("INSERT INTO bitcoin_payout_candidate_releases(candidate_id,reason,wallet_attempt) VALUES(?,?,?)",id,reason,observed);
        jdbc.update("DELETE FROM bitcoin_payout_input_reservations WHERE candidate_id=?",id);jdbc.update("DELETE FROM bitcoin_payout_active_candidates WHERE candidate_id=?",id);
        audit.record(null,"BTC_PAYOUT_CANDIDATE_RELEASED","bitcoin-payout-candidate:"+id,"SUCCESS",reason);
    }
```

불변 해제 증거를 먼저 추가하고 현재 입력 배정/활성 후보만 삭제한다. 후보·입력 이력·이전 시도/관측·금융 원장은 남긴다. 지연 제약이 해제 증거, 전체 현재 배정 삭제, 필수 감사를 같은 커밋에서 확인하므로 중간 실패는 모두 롤백된다.

관측 원문은 후보 ID/해시, LOCK 또는 UNLOCK, 노드 Observation을 포함한다. 기존 CddCrypto로 고객 ID·시도 UUID에 결합해 암호화하고 별도 도메인 HMAC을 보존한다. 관측의 성공적 기록을 뜻하는 감사 SUCCESS와 노드 결과 UNKNOWN을 구분한다. 암호문·MAC이 없는 UNKNOWN을 잠금 증거로 사용할 수 없다.

현재 종료 근거는 취소, 더 최신 검토의 존재, 검토 기한 만료 중 하나이고 업무 실행 확정은 없어야 한다. 잠금 시도가 있으면 같은 후보의 최신·30초 이내 실제 UNLOCK/false 관측을 요구한다. 잠금 관측 뒤 고객이 취소한 경우에도 외부 사실은 남고 새 지급 권한을 되살리지 않는다. 해제 응답 이후 감사 실패가 발생하면 Core가 이미 풀렸더라도 DB 귀속은 남아 미해결 시도로 재확인한다.

고객 금액 환급은 기존 엔진의 정식 해제 이벤트/원장 업무다. 여기서 고객 금액이나 금융 이력을 삭제/수정하지 않는다. 원래 입출력 워커 분리는 유지하고 Rust 코어는 변경하지 않았다.

## 작성한 테스트와 기대 결과

새 `BitcoinPayoutCandidateWalletIntegrationTest`20개는 실제 PG·제품 원본/원장·고객/독립 운영 서명과 저장 후보를 사용한다. 노드 잠금 클라이언트만 대역이다.150의 실제 Core 재시작 시험을 이번에 재실행하거나 새50개에 합산하지 않았다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 후보 해시 결합 암호/HMAC 관측 | 정확한 입력 LOCK 사실 보존, 원문 로그/금액 이동 없음 |
| 노드503/다른 입력 관측 | UNKNOWN, DB 배정 보존, 성공 증거 없음 |
| 시작 전 취소/호출 중 취소 | 전자는 노드 미호출, 후자는 사실 보존·새 잠금 금지 |
| 즉시 중복 호출 | 새 노드 시도 없이30초 대기 |
| 시작 감사 누락 | 시작 롤백·노드 호출 없음 |
| 관측 감사 누락 | 선행 시작 보존·결과 롤백·입력 귀속 보존 |
| 종료 근거 없음/직접 배정 삭제 | 배정 정리 거절 |
| 잠금 미접촉 취소/반복 정리 | 노드 미호출, 현재 배정만 삭제·후보/입력 역사 보존 |
| 해제 감사 누락 | 해제 증거와 배정 삭제 함께 롤백 |
| UNKNOWN 뒤 실제 대기 후 재확인 | 같은 후보로 두 번째 시도/관측, 새 후보/입력 배정 없음 |
| LOCK 뒤 취소/실제 대기/UNLOCK 관측 | 시도/관측/이력 보존·현재 배정만 정리 |
| UNLOCK 결과 불명 | 배정 보존·재잠금 권한 금지 |
| 노드 UNLOCK 뒤 해제 감사 장애 | 노드의 이전 행위를 되돌렸다고 주장하지 않음, 배정/미해결 시작 보존 |
| 업무 실행 확정 뒤/외부 DB 트랜잭션 | 입력 해제 거절/노드 호출 전 거절 |
| 새 현재 위험 범위로 만든 더 최신 검토 | 기존 후보를 SUPERSEDED로 정리, 새 검토 보존, 노드 미호출 |
| 원래 승인 기한의 실제 만료 | DB/기록 시각을 조작하지 않고 EXPIRED 정리, 후보/입력 역사·금융 기록 보존 |

재시도 관련 네 테스트는 불변 타임스탬프를 수정하거나 트리거를 끄지 않고 실제31초를 기다린다. 기한 만료 검증은 원래 승인10분이 실제로 지나도록 기다린다. 기본 취소/기한 만료/새 검토 정리는 확인하되 모든 운영 조합이나 실제 보호 키/방송 실패를 이20개로 검증했다고 주장하지 않는다.151의 기존15개를 공유 fixture 추출 뒤 다시 실행해 중복 배정·부분 예약 롤백·필수 감사를 확인한다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_payout_candidate_wallet.py`: 최초18개 전체. 현재 기본 실행기는 추가 시나리오까지20개를 실행한다.
- `python3 scripts/verify_bitcoin_payout_candidate_wallet.py --method aNewCurrentReviewRetiresOnlyTheOldCandidateWithoutNodeContact --method actualReviewExpiryRetiresTheUnsignedCandidateWithoutChangingFinancialHistory`: 추가2개. 동일한 제품 구현에서 시험 helper/직접 시나리오만 보강한 뒤 선택 실행했다.
- `python3 scripts/verify_bitcoin_payout_candidates.py`: 기존15개.
- `python3 scripts/verify_bitcoin_withdrawal_valuations.py`: 기존15개, 미래 가격 거절 회귀 포함.
- 최종 선택 검증 **50개/50회, 실패·오류·제외0**. 메서드별 새 DB·호스트/DB 시각 검사·봇0·비활성 소비자·격리 외부 주소 유지. 두 JAR 성공, 소유 컨테이너 정리 확인.
- 기존 deprecated/unchecked/JVM 경고는 남아 있다. 이전151의34개 승인 회귀,150의 실제 Core 검증을 이번에 재실행했다고 표시하지 않는다.
- 최초 후보 회귀는 호스트가 DB보다 약5ms 앞서 합성 가격의 미래 시각 검사에서 중단됐다. 제품의 미래 시각 거절은 유지하고 BitcoinWithdrawalTestSupport.referenceQuote가 시험 가격의 기준 시각을 격리 DB에서 읽도록 수정했다. 명시적 미래 가격 테스트는 그대로 유지해15개 평가 회귀를 다시 실행했다. 준비한 첫 두 메서드 및 시각 fixture 수정 전 실행을 최종 합계에 중복 합산하지 않았다. 실행 ID·각 메서드·시간·명령·결과는 연결 증적을 따른다.

## 관련 선행 수정

- [시험 가격 시각 정합성 수정 88b9f45](https://github.com/GiHoon1123/Cex-Platform-Service/commit/88b9f45): 제품의 미래 가격 거절을 유지하고 합성 기준 시각만 격리 DB에서 읽는다.

## 남은 작업

실제 네이티브 지급 실행 권한은 원래 승인·현재 배정·최신의 인증된 잠금 관측·선택 UTXO의 출처/탐지 근거와 결합해야 한다. 고객의 현재 자격과 지갑 소유6확정만으로 모든 수탁 입력이 심사됐다고 주장하지 않는다. 그 뒤 보호된 서명·고정 거래 방송/불명·체인 확정·광부 비용 원장/대사를 연결한다. [출금 평가 테스트 기반](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalTestSupport.java)의 기준 시각 변경은 시험 전용이며 제품 API·시각 허용 범위를 바꾸지 않는다.

현재 서비스에는 자동 호출 워커와 지급 완료 경로가 없으며, 키 회전·노드 자격/독점 작성자·호스트 손실 백업은 별도 운영 조건이다.

## 작성한 테스트 코드


[BitcoinPayoutCandidateWalletIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCandidateWalletIntegrationTest.java)

- `lockObservationIsEncryptedAndBoundToTheOriginalCandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeFailureLeavesAnUnknownResultAndRetainsTheFinancialAllocation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMismatchedObservationCannotClaimTheRequestedInputsAreLocked`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentCancellationBeforeLockPreventsNodeIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationDuringLockPreservesTheObservationWithoutRevivingAuthority`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immediateRepeatedLockCannotOverlapTheSameCandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingStartAuditPreventsAllNodeCalls`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingObservationAuditLeavesTheStartedAttemptUnresolved`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `validApprovalCannotReleaseItsInputsWithoutRetirement`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `neverLockedCancellationClosesOnlyCurrentAllocationsAndPreservesAllHistory`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingReleaseAuditRollsBackTheReleaseAndCurrentAllocationDeletion`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directAllocationDeletionWithoutReleaseProofIsRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownLockCanBeReobservedUsingTheSameCandidateAfterThePersistentDelay`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `observedUnlockAfterCancellationClosesTheAllocationAndKeepsLockHistory`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownUnlockCannotReleaseTheAllocationOrRestoreLockPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `releaseAuditFailureAfterNodeUnlockKeepsTheAllocationAndUnresolvedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `committedPayoutCannotReleaseInputsEvenAfterCancellationIsAttempted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewCurrentReviewRetiresOnlyTheOldCandidateWithoutNodeContact`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualReviewExpiryRetiresTheUnsignedCandidateWithoutChangingFinancialHistory`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `outerDatabaseTransactionCannotStartWalletIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 e260a41](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e260a41d0e000a2c0e903931138c81b8dfd6b0ae)
- [격리 실행 증적](../reference/152-bitcoin-candidate-wallet-lifecycle-verification-2026-10-26.json)
