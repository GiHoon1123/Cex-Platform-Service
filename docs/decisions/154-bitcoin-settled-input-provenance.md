# 선택한 출금 입력을 확정 입금 원장과 현재 근거에 연결

실제로 선택한 비트코인 입력이 정식 입금 확정·원장 반영을 거쳤는지 확인하고, 준비 당시의 출처 근거를 별도 불변 기록으로 보존한다. 기존 입금·현재 위험 검사와 암호/감사를 재사용하며 후보 저장과 새 잠금 앞에 연결한다.

## 문제와 범위

지갑이 소유하고6확정인 입력이라도 플랫폼에서 아직 보류 중이거나 출처가 확인되지 않은 자금일 수 있다. 출금 고객의 현재 자격과 기존 잔액 확인만으로 풀 지갑의 모든 입력을 허용하지 않는다. 이번 단계는 기존 입금 확정 사실과 현재 제한을 선택 입력에 대조하는 연결이다. 새로운 외부 심사나 규제 업무를 추가하지 않았다.

실제 키 호출·송금·체인 완료/비용 정산은 아직 구현 범위에 남아 있다. 이 기록을 금융권 인증이나 자금세탁 위험 전체에 대한 확인으로 사용하지 않는다.

## 기존 자료와 재사용한 구현

[Core31 gettxout](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/gettxout/)과 [walletcreatefundedpsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletcreatefundedpsbt/)를 사용하는149 클라이언트는 현재 입력의 금액·스크립트·소유/확정 상태를 확인한다. 플랫폼의 입금 확정 여부는 기존 `BitcoinCreditBasis`, `BitcoinCreditDispatches`, `BitcoinCreditedEvents`, `DepositMonitoringReview`와 해당 불변 표를 재사용한다. 노드 결과로 원장 반영을 추측하지 않는다.

[PostgreSQL16 제약](https://www.postgresql.org/docs/16/ddl-constraints.html), [지연 제약 트리거](https://www.postgresql.org/docs/16/sql-createtrigger.html)와 기존 CddCrypto를 사용해 후보/근거/입력 배정/필수 감사를 같은 커밋에 보존한다. 별도 비트코인 라이브러리·원장·코인 선택 알고리즘을 추가하지 않았다. 자료 확인일은2026-10-26이다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·한계 |
| --- | --- | --- |
| 선택 입력을 정식 입금 실행 권한·확정 이벤트·원장에 연결 | HTTP 응답이나 미반영 보류 자금을 지급 재원으로 쓰지 않음 | 추가 DB 조회/JSON·HMAC 검증. 미분류 입력이면 자금이 지갑에 있어도 자동 준비 보류 |
| 과거 출처 원본과 현재 위험 조건 구분 | 입금 사실을 보존하면서 현재 체인/탐지 제한 확인 | 과거 NO_SIGNAL로 새 입력/위험을 우회하지 않는다. 현재 사용하는 탐지 규칙 하나가 AML 전체 검증은 아니다 |
| 출처 기록을 금융 후보와 별도 불변 증거로 보존 | 새 검증이 과거 금융 원본의 정리를 막지 않음 | 암호 저장/감사 행 증가. 증거 없는 후보를 새 잠금에 사용하지 않으며 과거에 없던 증거를 만들어 채우지 않는다 |
| 관련 인물 전체를 모아 숫자 순서로 잠금 | 다른 고객 출처의 입력을 선택할 때 교차 대기 방지 | 플랫폼 잠금 범위가 늘어난다. 노드 호출은 TX 밖이고 엔진 코어는 변경하지 않는다 |

현재 자동 출처 정책은 정식 반입 완료한 외부 입금 출력이다. 회사 초기 자본·미분류 유입·이전 출금의 거스름돈은 이 정책으로 추정 승인하지 않는다. Core가 미분류 출력을 선택하면 INPUT_UNVERIFIED로 보류할 수 있다. 이 제한을 우회하는 폴백이나 자동 허용을 추가하지 않았다.

## 검사와 데이터 흐름

1. 노드 준비 응답 뒤 원래 출금/운영자와 선택 입력의 원래 고객 ID를 모아 정렬해 잠근다. 이때 개인 원문 대신 필요한 식별자만 조회한다.
2. 기존 현재 지급 검토·자격·서명 범위를 재검사한다.
3. 입력의 정확한 망/지갑/거래 ID/출력·주소/스크립트/금액을 입금 원본과 비교한다.
4. 불변 입금 실행 권한의 원본 해시, 정식 엔진 이벤트/수신 키, 같은 원장의 PENDING 차감·AVAILABLE 증가 두 분개를 확인한다.
5. 최신 체인 관찰/판단이 일치하고6확정·비코인베이스·최근2분인지 확인한다. 미해제 사후 체인 위험은 정상 재관찰만으로 지워지지 않는다.
6. 기존 탐지 머리가 현재 입력을 포함하고 미확인 항목이 없으며 관련 사건이 종결됐는지 확인한다.
7. 금융 후보와 별개로 암호화 출처 근거·HMAC·필수 감사를 같은 후보 저장 TX에 기록한다. 새 잠금 직전에는 현재 조건을 다시 검사하고 출처의 불변 원본을 비교한다.

입금 후 거래로 잔액 귀속이 바뀌어도 원래 수탁 입력의 출처는 남는다. 원래 입금 고객의 현재 AVAILABLE 잔액으로 그 입력을 다시 분류하지 않는다. 이미 확정한 출처 사실과 현재 출금 고객의 자격을 구분한다. 원래 고객의 KYC 만료가 과거 입금 사실을 지우지는 않지만, 출금 고객의 현재 KYC/CDD는 기존 지급 검토에서 계속 필수다.

## 실제 코드와 문법

출처: [BitcoinPayoutInputSources.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputSources.java#L53), 현재 소스의 부분 발췌.

```java
    public void lockForReview(UUID reviewId,String network,String wallet,List<Point> points){
        scope(network,wallet,points);
        var reviews=jdbc.queryForList("SELECT user_id,requester_id,checker_id FROM bitcoin_withdrawal_payout_reviews WHERE id=?",reviewId);
        if(reviews.size()!=1)throw held(Reason.SOURCE_UNAVAILABLE);var ids=new TreeSet<Long>();
        for(Object id:reviews.getFirst().values())ids.add(((Number)id).longValue());
        // 현재 포인터나 개인 원문은 아직 읽지 않는다. 출금/입금의 관련 사용자 ID만 조회한다.
        for(var point:points)for(var row:jdbc.queryForList("SELECT r.user_id FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id AND a.user_id=r.user_id AND a.network=r.network WHERE r.network=? AND a.wallet=? AND r.txid=? AND r.vout=?",network,wallet,point.txid(),point.vout()))ids.add(((Number)row.get("user_id")).longValue());
        for(long id:ids)users.lockById(id).orElseThrow(()->held(Reason.SOURCE_UNAVAILABLE));
    }
```

`TreeSet<Long>`은 중복을 제거하고 숫자 순서를 유지한다. 먼저 운영자/출금 고객과 선택 입력의 원래 고객을 모두 모은 뒤 기존 `UserRepository.lockById`를 호출한다. 출금 고객부터 잠근 후 더 작은 ID의 다른 고객을 추가하는 순서를 피한다. 이 플랫폼 DB 잠금은 엔진 공유 락 제거와 별개의 금융 트랜잭션이다.

출처: [BitcoinPayoutInputSources.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputSources.java#L69), 현재 소스의 부분 발췌.

```java
    public void save(UUID candidate,long owner,List<Certificate> certificates){
        String plain=json.writeValueAsString(certificates);if(plain.getBytes(StandardCharsets.UTF_8).length>131072)throw held(Reason.SOURCE_UNAVAILABLE);
        String mac=crypto.fingerprint("btc-payout-input-certificates-v1",plain);
        jdbc.update("INSERT INTO bitcoin_payout_candidate_sources(candidate_id,key_id,ciphertext,content_mac) VALUES(?,?,?,?)",candidate,crypto.keyId(),crypto.encrypt(owner,candidate,plain.getBytes(StandardCharsets.UTF_8)),mac);
        audit.record(null,"BTC_PAYOUT_INPUT_SOURCES_SAVED","bitcoin-payout-candidate:"+candidate,"SUCCESS",mac);
    }
```

출처 목록을 JSON으로 만들고131,072바이트를 넘으면 거절한다. 기존 AES-GCM은 출금 고객과 후보 UUID에 결합하고 HMAC은 별도 도메인 문자열을 사용한다. 검색 가능한 금융 후보 원본을 다시 쓰지 않고 새 증거를 추가한다. 필수 감사 누락은 같은 후보와 입력 배정까지 롤백시킨다.

출처: [BitcoinWithdrawalPayoutBasis.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalPayoutBasis.java#L114), 현재 소스의 부분 발췌.

```java
        var nativePolicy=new NativePolicy(BitcoinPayoutClient.POLICY,BitcoinPayoutInputSources.POLICY,BitcoinPayoutClient.MAX_RATE.toPlainString(),BitcoinPayoutClient.MAX_FEE.toPlainString(),BitcoinPayoutClient.MAX_INPUTS,6,"P2WPKH","ALL",false);
        String hash=crypto.fingerprint("btc-payout-basis-v1",json.writeValueAsString(List.of(POLICY,nativePolicy,funds,transfer.intentId(),transfer.attemptId(),transfer.meaningMac(),transfer.intentMac(),transfer.attemptMac(),transfer.resultMac(),transfer.directorySource(),transfer.currentPolicyHash(),risk)));
        return new Basis(POLICY,nativePolicy,funds,transfer,risk,hash);
```

운영자가 서명하는 범위에 실제 자동 준비 정책·출처 정책·요청 비용 상한·광부 비용 상한·입력 수·확정 수·입력 형식·ALL 서명 해시·교체 신호 정책을 포함한다. 정책 값이 바뀌면 현재 범위 해시도 달라진다. 요청 sat/vB 상한과 서명 후 실제 거래 크기로 계산하는 비용은 구분한다. 여기서 비활성화하는 것은 거래의 교체 신호다.

지급 검토 정책은V2다. 과거 금융/검토 원본은 보존하고 현재 새 서명/준비의 범위와 대조한다. 후보의 금융 Payload 형식은 유지하고 출처는 `bitcoin_payout_candidate_sources`에 추가한다. 과거 금융 원본의 미실행 정리는 새 출처 증거를 요구하지 않지만 새 잠금은 증거가 없으면 거절한다. 조용한 기본값이나 출처 추측으로 과거 후보를 새 정책에 통과시키지 않는다.

JDBC의 JSONB 출력은 문자열이 아닌 PGobject이므로 실제 값으로 읽고, Jackson의 BigDecimal 트리 판독을 사용한다. 금액 비교에 이진 실수를 끼우지 않는다. 원래 입금 증거의 현재 관찰/탐지 ID는 바뀔 수 있어 새로 검사하되, 불변 출처의 HMAC 결합은 유지한다.

## 작성한 테스트와 기대 결과

| 파일/범위 | 시나리오와 기대 결과 |
| --- | --- |
| BitcoinPayoutInputSourcesIntegrationTest,14개 | 실제 입금 심사/대사·실행 권한·정식 수신/원장을 준비해 정확한 출처 생성. HTTP 응답만 있음/보류/미분류/금액 불일치는 거절 |
| 같은 파일 | 오래된 체인 근거·미처리 탐지·위험 뷰 부재·미해제 사후 위험은 허용하지 않음. 정상 재관찰은 원래 출처를 유지 |
| 같은 파일 | 원래 고객 KYC 만료가 과거 확정 입금 사실을 지우지 않음. 별도 현재 출금 자격 검사를 대신하지 않음 |
| 같은 파일 | 출처 참조를 바꾼 HMAC 재사용 거절, 기존 TX 없는 호출 거절 |
| 같은 파일의 추가 잠금 순서 검증 | 잠금용 검토 메타데이터만 대역, 실제 입금 고객 조회와 JPA 잠금을 사용해 중복 제거/숫자 순서 확인 |
| BitcoinPayoutCandidatesIntegrationTest,17개 | 기존15+출처 거절 시 후보/배정 없음+출처 감사 누락 시 후보·증거·배정 롤백 |
| BitcoinPayoutCandidateWalletIntegrationTest,선택4개 | 저장 관측/취소/미접촉 정리 회귀, 현재 출처 변경이면 노드 잠금 시도 자체 없음 |
| BitcoinPayoutCandidateReapprovalIntegrationTest,3개 | 출처 증거를 보존하는 새 준비에서도 기존 배타성·미실행 정리 후 재승인 유지 |

출처 테스트는 제품의 실제 입금 실행/원장·수신 키를 사용하고 노드/외부 CODE 응답만 기존 대역으로 둔다. 후보/잠금 테스트는 출처 자격 판단만 대역으로 사용하고 출처 암호 저장·재검증·감사는 실제 서비스로 실행한다. 두 범위를 실제 체인 전체 통합으로 합쳐 주장하지 않는다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_payout_input_sources.py`: 최초13개 전체. 이후 같은 제품 동작의 잠금 메타데이터 조회를 최소화하고 `--method sourceOwnersAreLockedTogetherWithTheReviewActorsInNumericOrder`로 추가1개 실행했다. 현재 기본 실행기는14개다.
- `python3 scripts/verify_bitcoin_payout_candidates.py`:17개.
- `python3 scripts/verify_bitcoin_payout_candidate_wallet.py --method lockObservationIsEncryptedAndBoundToTheOriginalCandidate --method neverLockedCancellationClosesOnlyCurrentAllocationsAndPreservesAllHistory --method currentCancellationBeforeLockPreventsNodeIo --method changedInputSourceBlocksLockBeforeAnyExternalAttempt`:4개.
- `python3 scripts/verify_bitcoin_payout_candidate_reapproval.py`:3개.
- 최종 **고유38개/38회, 실패·오류·제외0**, 두 JAR 성공·소유 PG 컨테이너 정리 확인. 봇0·소비자 자동 시작 금지·외부 주소 격리를 유지했다. 실제 Core/엔진 프로세스를 이번에 새로 띄워 검증한 것은 아니다.
- 개발 중 괄호/상속 메서드 이름 컴파일 오류, 체인 조회 인자 순서·JSONB 타입 처리 오류를 수정했다. 변조 거절을 TX 안에서 잡은 시험의 rollback-only 오류도 외부에서 거절을 확인하도록 수정했다. JPA 프록시 순서 검증은 실제 호출을 위임하는 Mockito 기록 객체로 수정했다. 실패한 개발 실행과 예비 부분 실행은 최종 합계에서 제외했다.
- 기존 deprecated/unchecked/JVM 경고는 남아 있다. 실제 키·방송·체인 완료·비용 정산·전체 규제 준수까지 검증했다고 주장하지 않는다.

## 다음 연결

다음은 원래 출금·현재 후보/출처·잠금 관측을 실제 지급 실행 권한에 결합하고 보호된 키 호출·고정 거래 송금·결과 불명·체인 완료/원장 정산을 연결하는 것이다. 이번 근거 저장만으로 송금이나 금융 완료를 허용하지 않는다.

## 작성한 테스트 코드


[BitcoinPayoutInputSourcesIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutInputSourcesIntegrationTest.java)

- `canonicalCreditAndCurrentChainRiskProduceTheExactOriginCertificate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anHttpAcknowledgementWithoutTheCanonicalFinancialEventIsNotSpendable`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `recognizedPendingFundsCannotBecomeASettlementCertificate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unclassifiedWalletInputsHaveNoSourceFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `selectedAmountMustMatchTheActualCustodyReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `staleCurrentChainEvidenceCannotReuseThePastCredit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `newerUnprocessedMonitoringInputsHoldPreviouslyCreditedOutputs`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `expiredOriginCustomerKycDoesNotEraseAlreadyRecordedFundsOrigin`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNormalNewObservationKeepsTheOriginalFundingIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMissingRiskViewCannotBeTreatedAsAClearInput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `inputOriginReadsRequireAnExistingFinancialTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aChangedOriginReferenceCannotReuseTheStoredAuthentication`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `restoredConfirmationsCannotEraseAnUnclearedPostCreditRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceOwnersAreLockedTogetherWithTheReviewActorsInNumericOrder`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 360e5a2](https://github.com/GiHoon1123/Cex-Platform-Service/commit/360e5a2c39ea5681da9b320ced01fc0daa9ff109)
- [격리 실행 증적](../reference/154-bitcoin-settled-input-provenance-verification-2026-10-26.json)
