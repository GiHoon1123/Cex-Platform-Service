# 승인 원본과 미서명 후보의 불변 저장 및 입력 이중 배정 방지

승인된 출금 원본과 Core가 준비한 미서명 거래를 암호화된 불변 후보로 묶고, 같은 체인의 입력을 서로 다른 출금에 이중 배정하지 못하게 한다. 외부 호출 전 시도 보존, 호출 뒤 현재 근거 재검사, 전역 고유 배정과 감사의 원자성을 설명한다.

## 문제와 기존 동작

[149](149-bitcoin-watch-only-payout-preparation.md)의 미서명 거래는 메모리 결과이며 [150](150-bitcoin-persistent-input-locks.md)의 Core 잠금에는 금융 배정 주인이 없다. 둘만으로 어느 승인·출금에 어떤 PSBT와 입력이 귀속되는지 복구할 수 없다. 노드 호출을 DB 트랜잭션 안에서 실행하면 고객/운영자 락을 네트워크 대기 동안 붙잡게 된다.

이번 단계는 영속 준비와 배정을 구현한다. 자동 지급 워커·Core 잠금 연결·서명·방송을 활성화하지 않는다. 기존148의 내부 실행 확정은 실제 체인 서명을 뜻하지 않는다. 실제 지급 실행에 후보/잠금 관측을 결합하는 단계는 후속이며 완성된 지급 경로라고 주장하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 [PostgreSQL16 제약](https://www.postgresql.org/docs/16/ddl-constraints.html)의 복합 기본 키·외래 키와 [지연 제약 트리거](https://www.postgresql.org/docs/16/sql-createtrigger.html)를 재사용했다. 다른 행과의 고유성은 조회 후 확인이나 행 간 CHECK 함수로 대체하지 않는다. 트랜잭션 끝의 여러 행 완성 여부는 지연 트리거로 검사한다.

[Core31 walletcreatefundedpsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletcreatefundedpsbt/)로 준비하는 기존 클라이언트를 그대로 호출한다. 직접 코인 선택이나 별도 암호 라이브러리를 만들지 않고 기존 CddCrypto의 독립 HMAC·AES-GCM, 고정 RPC 연결, 실제 고객/운영 서명을 재사용한다. 운영자의 시작·독립 승인과 실행을 구분하는 공개 제품 근거는 [148](148-bitcoin-independent-payout-review.md)에 연결한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 준비 시도 커밋 → 노드 호출 → 현재 승인 재검사/후보 저장 | 응답 유실/취소 경합에서 시도와 금융 배정 구분 | DB 커밋·감사 쓰기 증가, 감사/DB 실패 시 시작만 남을 수 있다. 외부 호출을 원자 DB 트랜잭션인 척하지 않는다 |
| 입력 키는 `network+txid+vout` | 지갑 이름이 달라도 같은 체인 출력의 이중 배정 금지 | Core 선택 결과가 겹치면 해당 후보 전체 보류. 지갑별 키로 분리하면 같은 자산을 중복 배정할 수 있다 |
| 원본 후보와 입력/현재 배정/결과/감사를 같은 커밋에 저장 | 부분 입력 배정이나 감사 없는 성공 금지 | 여러 행과 제약 처리 비용. 입력을 정렬해 교차 대기를 줄인다 |
| 같은 승인 후보는 그대로 재조회 | 재시작/재호출 때 다른 PSBT로 대체하지 않음 | 현재 승인·자격이 달라지면 재조회로 지급 권한을 되살릴 수 없다 |
| 현재 단계는 배정 자동 삭제 금지 | 오류·만료를 이유로 입력 재사용하는 위험 차단 | 취소·기한 만료 후 미실행 해제와 Core 대조를 연결하기 전에는 입력이 보류된다. 운영 완성 상태가 아니다 |

재시도30초는 외부 준비 요청을 무한 생성하지 않기 위한 자체 제한이다. 오래 걸린 이전 호출이 끝났다는 증거, 입력 해제 조건, 지급 허가가 아니다. 고객0.01% 수수료와 실제 광부 비용은 후보에서 별도로 보존하고 고객 지급액을 줄이지 않는다. 비용의 원장 반영은 후속 지급 결과 업무다.

## 구현과 실제 코드·원리

`BitcoinWithdrawalPayoutReviews.currentForPreparation`은 이미 실제 내용 결합 서명으로 승인된 검토의 현재 담당자·다른 승인자·고객을 순서대로 잠근다. 암호 원본, 최신 승인·수명·미실행, 현재 KYC/CDD/거래 자격·원장·위험 범위를 다시 검사한다. 가짜 운영자 세션을 만들지 않고 내부 승인 권한을 검사한다.

출처: [BitcoinPayoutCandidates.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCandidates.java#L47), 현재 소스의 부분 발췌.

```java
    public Result prepare(UUID reviewId) {
        boundary();Start start=tx.execute(t->{
            var review=reviews.currentForPreparation(reviewId);var old=existing(reviewId);
            if(old!=null){
                if(!old.payload().reviewHash().equals(review.payloadHash())||!old.payload().scopeHash().equals(review.payload().basis().scopeHash())||!old.payload().funds().equals(review.payload().basis().funds()))throw held();
                return new Start(null,review,old);
            }
            UUID withdrawal=review.payload().basis().funds().withdrawalId();
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE withdrawal_id=?)",Boolean.class,withdrawal)))throw held();
            var recent=jdbc.queryForList("SELECT coalesce(r.recorded_at,a.started_at) AS stamp FROM bitcoin_payout_preparations a LEFT JOIN bitcoin_payout_preparation_results r ON r.preparation_id=a.id WHERE a.review_id=? ORDER BY a.position DESC LIMIT 1",reviewId);
            if(!recent.isEmpty()&&((Timestamp)recent.getFirst().get("stamp")).toInstant().isAfter(now().minusSeconds(30)))throw held();
            UUID attempt=UUID.randomUUID();var basis=review.payload().basis();
            jdbc.update("INSERT INTO bitcoin_payout_preparations(id,review_id,withdrawal_id,user_id,review_hash,scope_hash) VALUES(?,?,?,?,?,?)",attempt,reviewId,withdrawal,basis.funds().customerId(),review.payloadHash(),basis.scopeHash());
```

`TransactionTemplate.execute` 안에서 현재 승인을 검사한다. 기존 후보가 있으면 암호 원본의 검토 해시·경제 범위·금융 원본까지 대조하고 그대로 반환한다. 새 시도는 별도 UUID로 노드 호출 전에 저장한다. TypeScript의 Promise 완료와 달리 이 콜백이 반환돼 DB 커밋까지 성공해야 다음 외부 호출로 넘어간다.

출처: [BitcoinPayoutCandidates.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutCandidates.java#L84), 현재 소스의 부분 발췌.

```java
                var inputs=prepared.inputs().stream().sorted(Comparator.comparing(BitcoinPayoutClient.Input::txid).thenComparingInt(BitcoinPayoutClient.Input::vout)).toList();
                for(var input:inputs){
                    jdbc.update("INSERT INTO bitcoin_payout_candidate_inputs(candidate_id,txid,vout,amount,script_pub_key) VALUES(?,?,?,?,?)",id,input.txid(),input.vout(),new BigDecimal(input.amount()),input.scriptPubKey());
                    jdbc.update("INSERT INTO bitcoin_payout_input_reservations(network,txid,vout,candidate_id) VALUES(?,?,?,?)",prepared.network(),input.txid(),input.vout(),id);
                }
                jdbc.update("INSERT INTO bitcoin_payout_active_candidates(withdrawal_id,candidate_id) VALUES(?,?)",f.withdrawalId(),id);
                audit.record(null,"BTC_PAYOUT_CANDIDATE_SAVED","bitcoin-payout-candidate:"+id,"SUCCESS",hash);
                result(start,"SAVED",id,sha(prepared.psbt()));return new Result(start.attemptId(),"SAVED",read(id));
```

입력은 거래 ID와 숫자 출력 순번으로 정렬한다. 입력 이력과 전역 예약이 먼저 완성되고 출금의 활성 후보를 연결한다. 어느 INSERT라도 고유 제약에 걸리면 같은 트랜잭션의 앞선 입력 행도 함께 롤백된다. 이 경합은 Java 플랫폼 DB 업무이며 Rust 엔진 공유 락을 추가하지 않는다.

출처: [payout-candidate-schema.sql](../../src/main/resources/bitcoin/payout-candidate-schema.sql#L37), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS bitcoin_payout_input_reservations (
 network varchar(32) NOT NULL,txid varchar(64) NOT NULL,vout integer NOT NULL,candidate_id uuid NOT NULL,
 -- 같은 UTXO를 다른 지갑 이름으로 이중 배정하지 못한다. 지갑은 후보의 귀속 정보이고 UTXO의 식별자가 아니다.
 PRIMARY KEY(network,txid,vout),FOREIGN KEY(candidate_id,txid,vout) REFERENCES bitcoin_payout_candidate_inputs(candidate_id,txid,vout)
);
```

외래 키는 해당 후보의 입력 이력에 실제 있는 출력만 배정하도록 제한한다. 기본 키에 wallet을 넣지 않는다. 같은 `txid/vout`라도 다른 체인은 별도 자산이므로 network는 필요하다. 노드 지갑 이름은 후보의 귀속 정보다.

후보의 AES-GCM 추가 인증 데이터는 고객 ID와 후보 UUID다. 평문은 원본 Funds, 검토 ID/해시/범위, Prepared 전체를 포함하고 별도 도메인의 HMAC으로 검증한다. 검색용 열·입력 이력·현재 배정·활성 후보도 원문과 재대조한다. JSON 평문1MiB·기존 PSBT/입력 상한을 유지하고 기본 로그 문자열에는 원문을 내보내지 않는다.

준비 결과는 SAVED, NODE_UNAVAILABLE, BASIS_CHANGED, INPUT_RESERVED다. 노드 장애와 근거 변경을 구분하고, 알려진 고유 제약 충돌만 INPUT_RESERVED로 분류한다. 감사·다른 DB 오류는 노드 오류로 삼키지 않는다. 후보 저장/결과 감사 실패 시 먼저 확정한 시작만 남아 미해결을 식별할 수 있다.

## 작성한 테스트와 기대 결과

새 `BitcoinPayoutCandidatesIntegrationTest`는 실제 소유 PostgreSQL, 제품 원장·금융 원본·현재 자격, 고객/운영자 내용 결합 서명을 사용한다. 실제 노드 입출력만 대역이다. 합성 PSBT 문자열의 비트코인 유효성을 이 시험에서 증명한다고 주장하지 않는다. 실제 PSBT/노드 재시작은149·150의 별도 검증 범위다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 승인 원본/PSBT/입력 저장, 재호출 | 정확한 원본과 모든 입력 보존, 재호출은 새 노드 호출 없음, 송금/실행 확정 없음 |
| 노드 실패/즉시 재시도 | 불변 시작·장애 결과·감사 보존, 입력 없음, 30초 내 새 선택 금지 |
| 준비 전 취소/호출 중 취소·KYC 만료 | 전자는 노드 호출 없음, 후자는 근거 변경 기록·후보/배정 없음 |
| 시작·후보·결과 감사 누락 | 해당 트랜잭션 롤백, 후보 저장 실패 때 이미 커밋한 시작은 보존 |
| 원문 암호/HMAC·불변 행 | 원문 결합 검증, 원문 로그 생략, 이력/배정 DELETE 거절 |
| 동시 동일 승인 준비 | 준비 시도/노드 선택/후보 하나 |
| 별도로 서명한 두 출금의 동일 입력 | 두 번째 INPUT_RESERVED, 첫 배정 유지 |
| 두 번째 후보의 여러 입력 중 뒤쪽 충돌 | 앞서 추가한 입력까지 롤백, 후보/입력 이력/배정은 첫 원본만 유지 |
| 열린 외부 DB 트랜잭션 | 노드 호출과 시도 저장 전에 거절 |

148의 기존34개 테스트 함수는 유지하고 공유 setup/서명 helper만 `BitcoinWithdrawalPayoutTestSupport`로 분리했다. 같은 DB를 공유하는 fixture 충돌을 피하려고 각 메서드에 새 DB를 생성한다. 기존 고객 금융 이력/감사를 삭제하거나 불변 트리거를 끄지 않는다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_payout_candidates.py`: 신규15개.
- `python3 scripts/verify_bitcoin_withdrawal_payout_reviews.py`: 기존34개.
- 최종 선택 검증 **49개/49회, 실패·오류·제외0**, 각 메서드별 새 DB와 시각 사전 검사 유지. `bootJar`, `operatorAdminJar` 성공. 실행 ID와 명령·메서드·실행 시간·결과는 연결 증적에 보존한다.
- 기존 deprecated/unchecked/JVM 경고는 남아 있다. 이번 묶음을 전체 금융/보안/실제 체인 지급 검증으로 확장해 주장하지 않는다.
- 개발 중 테스트의 동명 verify 호출 컴파일 오류, PL/pgSQL record와 SQL 별칭 충돌, Mockito 재설정 때 기존 검증 answer가 실행되는 fixture 오류를 수정했다. 최종 실행 결과에 실패한 개발 실행이나 선행 부분 통과를 중복 합산하지 않았다.
- 소유 PG 컨테이너 정리와 기존 서비스 미사용을 실행기에서 확인한다. 봇0·소비자 자동 시작 금지·고정 격리 외부 주소를 유지한다.

## 남은 작업

후보 배정과150의 Core 잠금 사이 시도/관측 보존, 재시작 대조, 취소·새 승인·만료 시 미실행 해제, 실제 실행 확정과 후보 결합을 이어간다. 그 뒤 보호된 키로 고정 거래를 서명하고 응답 유실/방송/확정·실패·광부 비용 원장을 연결해야 한다. 저장된 SAVED는 역사적 준비 결과이며 현재 UTXO 미지출·서명 허가·고객 지급 완료를 의미하지 않는다.

## 작성한 테스트 코드


[BitcoinPayoutCandidatesIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCandidatesIntegrationTest.java)

- `savedCandidateBindsOriginalApprovalPsbtAndAllInputsWithoutPaying`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedPreparationReturnsTheImmutableCandidateWithoutAnotherNodeCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeFailurePreservesAttemptAndFailureAuditWithoutReservingInputs`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immediateRetryCannotCreateFreshCoinSelectionAttempts`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationBeforePreparationCannotReachTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationDuringNodeIoPreservesTheObservationWithoutSavingACandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedKycDuringNodeIoCannotCreateAnInputReservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingStartAuditPreventsNodeIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `omittedCandidateAuditRollsBackAllInputsButPreservesTheStartedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `omittedResultAuditCannotPretendToHaveFinishedTheAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `storedPayloadIsEncryptedAuthenticatedAndHistoryCannotBeDeleted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentPreparationCreatesOneAttemptAndOneCandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `twoApprovedWithdrawalsCannotReserveTheSameNativeInput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterInputCollisionRollsBackEarlierInputAssignments`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentApprovalCannotBeUsedFromAnOpenOuterTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 176756c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/176756c66af9a64da734fd1588159f209befd921)
- [격리 실행 증적](../reference/151-bitcoin-durable-payout-candidates-verification-2026-10-26.json)
