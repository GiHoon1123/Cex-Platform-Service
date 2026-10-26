# 미실행 정리 뒤 동일 미서명 거래를 다시 준비할 수 있도록 수정

미실행 정리된 후보와 같은 내용의 비트코인 거래를 다시 준비할 때 과거 미서명 거래 ID가 새 승인을 막는 문제를 수정한다. 후보 이력과 현재 입력 배정의 역할, 동일 거래 ID 재현, 실제 지급 실행에 남은 검증 경계를 설명한다.

## 문제와 기존 동작

151은 후보에 `network+unsigned_txid` 전역 고유 제약을 두었다.152가 미실행 후보의 현재 입력 배정을 정리한 뒤에도 후보 이력은 남으므로 같은 내용의 거래를 다시 준비하면 이 제약에 걸릴 수 있었다. 특히 거스름돈 없는 거래는 지급 주소/금액·입력·버전·locktime·sequence가 같으면 다시 준비해도 거래 ID가 같다. 새 검토 UUID나 후보 UUID가 비트코인 거래 바이트를 바꾸지는 않는다.

그 결과 고객·현재 위험 범위를 다시 검토해 승인하고 이전 입력 배정을 정상 정리했는데도, 새 후보가 INPUT_RESERVED로 보류될 수 있었다. 현재 입력이 비어 있는데 과거 준비 이력의 전역 고유성이 재준비를 막는 것이 원인이다. 실제 선택 입력의 출처/지급 권한을 연결하기 전에 이 복구 경로를 바로잡았다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [BIP174 PSBT 역할](https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki)은 생성·정보 갱신·서명·최종화를 구분한다. [Core31 walletcreatefundedpsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletcreatefundedpsbt/)는 생성/갱신을 수행한다. [walletprocesspsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletprocesspsbt/)와 [finalizepsbt](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/finalizepsbt/)의 별도 단계를 준비 완료와 혼동하지 않는다.

이번 선택은 이 역할 분리와 실제 Core 재현을 바탕으로 한 프로젝트의 판단이다. 특정 거래소 내부 DB가 같은 표 구조를 사용한다고 추측하지 않는다. 기존의 현재 입력 복합 기본 키와 미실행 종료/감사 계약을 사용하며 새 거래 ID 레지스트리나 자체 비트코인 해시 알고리즘을 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 후보의 전역 unsigned_txid 고유 제약 제거 | 정상 미실행 정리 후 같은 내용의 거래 재준비 가능 | 같은 거래 ID를 가진 여러 후보 이력이 존재할 수 있어 거래 ID만으로 승인/금융 원본을 식별하면 안 된다 |
| 후보 UUID·출금 UUID·검토 UUID·원문 HMAC 유지 | 어느 승인으로 어떤 후보를 만들고 정리했는지 보존 | 이력 조회는 결합 키와 현재 배정을 확인해야 한다 |
| 현재 입력의 `network+txid+vout` 기본 키 유지 | 같은 실제 입력을 두 현재 후보에 배정하지 못함 | 이전 후보가 아직 활성 상태이면 새 승인도 기다린다 |
| 실제 미실행 해제 증거 뒤에만 재배정 | 서명/실행이 불명인 후보의 입력 재사용 방지 | 기한/네트워크 오류만으로 배정을 지우지 않는다. 실행 확정 후보는152 규칙상 단순 정리할 수 없다 |

거래 ID를 억지로 바꾸려고 locktime·sequence·지급액을 조작하거나 의미 없는 nonce 출력을 추가하는 대안은 채택하지 않았다. 별도 거래 ID 레지스트리는 현재 후보 준비의 중복 입력 제약을 반복하고 이력을 복잡하게 만든다. 실제 지급 실행 권한과 체인 확정 기록은 후속 업무에서 원래 금융 UUID/후보/고정 거래와 결합해야 한다.

## 구현과 관련 코드·문법

`payout-candidate-schema.sql`에서 후보의 전역 거래 ID 고유 제약을 제거한다. 이미 생성된 표에서도 같은 규칙이 제거되도록 해당 제약만 삭제한다. 금융/후보/입력 이력은 삭제하거나 바꾸지 않는다. 해석할 수 없는 옛 제약 위반을 정상 중복 배정으로 취급하던 Java 분기도 삭제했다.

출처: [payout-candidate-schema.sql](../../src/main/resources/bitcoin/payout-candidate-schema.sql#L19), 현재 소스의 부분 발췌.

```sql
-- 미실행 종료한 후보는 같은 지급 내용으로 다시 준비할 수 있다. 후보 이력의 거래 ID를 지급 확정 키로 사용하지 않는다.
-- 현재 입력의 전역 기본 키와 미실행 해제 증거가 중복 배정을 막는다. 기존 금융/후보 기록은 삭제하지 않는다.
ALTER TABLE bitcoin_payout_candidates DROP CONSTRAINT IF EXISTS bitcoin_payout_candidates_network_unsigned_txid_key;
```

`ALTER TABLE ... DROP CONSTRAINT`는 해당 고유 규칙만 제거한다. 기존 행이나 고객 금액을 삭제하지 않는다. 검토별 후보 고유성, 준비 시도 고유성, 활성 후보/입력 배정 기본 키, 외래 키, 불변/필수 감사 제약은 그대로 적용된다. 호환 폴백이나 별도 저장 경로를 추가하지 않았다.

출처: [BitcoinPayoutCandidateReapprovalIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCandidateReapprovalIntegrationTest.java#L31), 현재 소스의 부분 발췌.

```java
    @Test void aNewApprovalReusesTheUnsignedIdentityOnlyAfterTheOldAllocationIsReleased()throws Exception {
        var first=prepareCandidate().candidate();approved=newerReview();assertTrue(wallet.release(first.payload().id()).allocationReleased());
        var next=prepareCandidate();assertEquals("SAVED",next.status());assertNotEquals(first.payload().id(),next.candidate().payload().id());
        assertEquals(first.payload().prepared().unsignedTxid(),next.candidate().payload().prepared().unsignedTxid());assertEquals(first.payload().prepared().psbt(),next.candidate().payload().prepared().psbt());
        assertNotEquals(first.payload().reviewHash(),next.candidate().payload().reviewHash());assertEquals(2,count("bitcoin_payout_candidates"));assertEquals(2,count("bitcoin_payout_candidate_inputs"));assertEquals(1,count("bitcoin_payout_candidate_releases"));
        assertEquals(next.candidate().payload().id(),jdbc.queryForObject("SELECT candidate_id FROM bitcoin_payout_input_reservations",UUID.class));
        assertThrows(ResponseStatusException.class,()->wallet.lock(first.payload().id()));
    }
```

새 실제 검토/내용 결합 서명을 만든 뒤 이전 배정을 정리하고 준비한다. `assertNotEquals`는 후보/검토가 달라졌음을, `assertEquals`는 미서명 거래 내용이 같을 수 있음을 확인한다. 과거 후보/입력 이력은 두 개이고 현재 배정은 새 후보 하나여야 한다. TypeScript 객체의 임의 요청 ID와 온체인 거래 ID도 같은 의미가 아니며 여기서는 각각의 결합을 검증한다.

출처: [BitcoinPayoutCoreIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutCoreIntegrationTest.java#L76), 현재 소스의 부분 발췌.

```java
    @Test void aRealNoChangeTransactionCanHaveTheSameUnsignedIdentityBeforeAnySigning() {
        // 시험 입력0.05BTC에서282사토시를 비용으로 남긴다. 실제 Core가 거스름돈 없는 출력을 만들었는지 확인한다.
        // 서명/방송/입력 예약 없이 같은 내용을 다시 준비하면 거래 ID가 같을 수 있다는 회귀의 실제 근거다.
        var c=command();var exact=new BitcoinPayoutClient.Command(c.withdrawalId(),c.network(),c.address(),c.scriptPubKey(),new BigDecimal("0.04999718"));
        var first=client.prepare(exact,fixtureFee());var second=client.prepare(exact,fixtureFee());
        assertNull(first.changeAddress());assertNull(first.changeScript());assertNull(first.changeAmount());
        assertEquals(first.unsignedTxid(),second.unsignedTxid());assertEquals("0.00000282",first.nativeFee());
        var decoded=rpc.call("decodepsbt",List.of(first.psbt()));assertEquals(1,decoded.path("tx").path("vout").size());
        assertEquals("0.04999718",first.amount());assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());
        assertFalse(decoded.path("inputs").get(0).has("partial_signatures"));
    }
```

실제 Core의0.05BTC 시험 입력에서0.04999718BTC 지급과0.00000282BTC 비용으로 거스름돈 없는 거래를 만든다. 새 노드 추정값을 주장하지 않고149와 같은 합성 시험 비용2sat/vB를 사용한다. 출력 하나·정확 금액·동일 거래 ID·서명 없음·잠금 없음이 실제 노드에서 확인돼야 한다. 거스름돈이 없으면 관련 Prepared 필드는 null이다.

## 작성한 테스트와 기대 결과

새 `BitcoinPayoutCandidateReapprovalIntegrationTest`는 실제 소유 PG·원장·고객 패스키·서로 다른 운영자의 내용 결합 서명·현재 위험 검토와 후보/입력 제약을 사용한다. 노드 준비만 대역이며 합성 PSBT의 비트코인 유효성은 별도의 실제 Core 시험으로 확인한다.

| 테스트 | 시나리오와 기대 결과 |
| --- | --- |
| aNewApprovalReusesTheUnsignedIdentityOnlyAfterTheOldAllocationIsReleased | 새 탐지 입력을 검토한 최신 승인 뒤 이전 후보를 SUPERSEDED 정리. 동일 미서명 거래 ID/PSBT로 새 후보 저장, 과거 이력 보존·현재 배정은 새 후보 하나 |
| aNewReviewCannotReplaceAnUnreleasedActiveCandidate | 새 승인이 있어도 이전 배정 미정리이면409, 새 노드 호출/후보 없음 |
| anotherWithdrawalWithTheSameUnsignedIdentityStillWaitsForTheOldInputRelease | 첫 출금의 정식 엔진 해제 이벤트로 고객 금액이 돌아와도 과거 후보의 현재 입력 배정이 남으면 다른 출금은 INPUT_RESERVED. 미실행 정리와 실제31초 재시도 대기 뒤에만 새 후보 저장 |
| aRealNoChangeTransactionCanHaveTheSameUnsignedIdentityBeforeAnySigning | 실제 관찰 전용 Core에서 동일 내용의 거스름돈 없는 거래를 두 번 준비. 같은 거래 ID·출력 하나·정확 금액/비용·서명/잠금 없음 |

두 번째 출금 검증은 엔진 이벤트의 안정적 ID/순서와 플랫폼 수신 키를 사용한다. 테스트에서 금융 이력/불변 트리거를 삭제하거나 시각을 바꾸지 않는다. 실제 엔진 프로세스를 새로 띄운 시험은 아니며 이미 합의한 이벤트 수신 경로를 사용한다.

## 실행 명령과 결과

- `CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin_payout_preparation.py`: 준비 단위22·실제 Core10·공통 노드34, 총66개.
- `python3 scripts/verify_bitcoin_payout_candidate_reapproval.py`: 신규 재승인/배정3개.
- `python3 scripts/verify_bitcoin_payout_candidates.py`: 기존 후보15개.
- 최종 **고유84개/84회, 실패·오류·제외0**. 첫 예비3개는 최종 합계에 중복 합산하지 않는다. 실제 Core 비용의 추정 불가 거절도 기존 회귀로 확인했으며 자동 고정 비용 폴백은 추가하지 않았다.
- 두 JAR 성공. 기존 deprecated/unchecked/JVM 경고는 남아 있다. 소유 Core 종료·임시 키 디렉터리 제거·소유 PG 정리, 외부 연결0·관찰 지갑 개인키 비활성·서명/방송 권한 거절을 확인했다.
- 149의 당시 실제 Core9개/전체65개 기록은 해당 시점의 측정으로 유지한다. 현재 Core 클래스는 새 실제 시나리오를 포함한10개이며 이번 실행은66개다.152의 실제10분 만료 테스트를 이번에 다시 실행했다고 합산하지 않는다.

## 남은 경계

현재 입력 배정과 미실행 종료 계약은 후보 준비의 안전 조건이다. 실제 키 호출은 원래 승인·현재 후보·최신 인증된 잠금 관측·선택 입력 출처·비용 정책을 결합한 실행 권한이 필요하다. 이번 수정은 그 검증을 생략하거나 지급을 완료하지 않는다. 이어서 해당 출처/정책 결합과 보호된 서명·고정 거래 방송·결과 불명·원장/대사를 구현한다.

## 작성한 테스트 코드


[BitcoinPayoutCandidateReapprovalIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCandidateReapprovalIntegrationTest.java)

- `aNewApprovalReusesTheUnsignedIdentityOnlyAfterTheOldAllocationIsReleased`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewReviewCannotReplaceAnUnreleasedActiveCandidate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherWithdrawalWithTheSameUnsignedIdentityStillWaitsForTheOldInputRelease`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinPayoutCoreIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutCoreIntegrationTest.java)

- `realWatchOnlyCorePreparesAnUnsignedExactPaymentAndOwnedChange`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `allFiveSupportedActualDestinationsKeepTheirExactNetAndScript`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualMinerCostIsSeparateFromTheFixedCustomerFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aDifferentExpectedScriptCannotRedirectTheRealTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualDustRejectionCannotRoundTheCustomerPaymentUp`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unavailableActualFeeEstimateHasNoFixedFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualWrongNetworkAndPrivateKeyWalletCannotPrepare`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `preparationCredentialsCannotSignBroadcastOrExport`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realCustodyAddressIsHeldForASeparateInternalTransferPath`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aRealNoChangeTransactionCanHaveTheSameUnsignedIdentityBeforeAnySigning`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 76fa437](https://github.com/GiHoon1123/Cex-Platform-Service/commit/76fa4371e3df528896a44134f5bc67c35ab49b3b)
- [격리 실행 증적](../reference/153-bitcoin-unsigned-candidate-reapproval-verification-2026-10-26.json)
