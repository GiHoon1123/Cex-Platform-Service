# BTC 지급 근거의 독립 서명 승인과 고객 취소·실행 확정의 배타성

BTC 출금의 암호 회신·원장·현재 고객/위험 근거를 다시 검사한 뒤 담당자 의견과 지정 독립 승인자의 건별 서명을 보존한다. 승인과 지급 실행 확정을 구분하여 실행 확정 전에는 고객 취소를 허용하고, 취소와 실행 확정이 경쟁하면 한 쪽만 성립한다. 실제 체인 거래 생성·서명·방송은 아직 후속이다.

## 문제와 기존 동작

147의 VERIFIED는 정확한 상대와 HTTP에서 관측한 정보 교환 결과다. 그 상태 문자열만 지급 토큰으로 쓰면 신원/위험 변화·원장 불일치·다른 의견/승인·고객 취소를 뒤늦게 발견할 수 있다. 공개 운영자 API로 금액이나 지급지를 다시 입력받거나 승인 즉시 잔액을 차감하는 경로도 피해야 한다.

## 기존 제품과 공식 자료의 접근

2026-10-26 [Coinbase Prime 공개 보안 설정](https://help.coinbase.com/en/prime/securing-your-account/security-settings)은 이전/출금의 별도 승인 통제를 설명한다. [Kraken 조직 기능](https://support.kraken.com/articles/about-organizations)과 [정책·승인](https://support.kraken.com/au/articles/organization-policies-approvals-governance)은 자금 이동의 시작·승인·실행 권한과 자기 요청 승인 금지를 구분한다. 공개 설명을 패턴의 근거로 사용하며 내부 구현이나 국내 법적 의무를 추측하지 않는다.

기존 OperatorMfaService의 내용·방향·세션 결합 WebAuthn 서명, 지정 인물/현재 버전 검사, CddCrypto와 TransactionTemplate, monitoring_case_closed/current scope를 재사용했다. 동적 승인 정책 엔진·새 암호/서명 라이브러리를 만들지 않았다. 실제 출금의 전체 AML/시장 감시·개인지갑/해외·독립 서명 영역·운영 조직 조건은 여전히 별도다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 상태 문자열 대신 봉인된 요청/응답과 현재 원본 대조 | 잘못된 지급지·신원·과거 근거를 현재 승인으로 재사용하지 않음 | 암호 해독/무결성 검사·DB 조회가 추가됨. 키 운영/보존은 필요 |
| 담당자 의견과 지정된 다른 인물의 건별 서명 | 일반 로그인 MFA를 특정 지급 승인으로 오인하지 않음 | 담당자/승인자 업무와 열람·서명 단계 필요. 실제 서로 다른 사람의 등록/운영은 조직 증적 필요 |
| 승인 후 별도 실행 확정 | 고객은 승인만 받은 상태에서 취소 가능 | 지급 워커가 현재 근거를 다시 검사해야 함. 실행 확정 후에는 고객 취소 불가 |
| 같은 고객 잠금·DB 배타 제약 | 동시 취소와 지급 권한이 함께 확정되지 않음 | 플랫폼의 짧은 TX에 대기 가능. Rust 코어에는 락/조회 추가 없음 |
| 새 유효 근거만 새 검토 허용 | 위험/입금 입력이 바뀌면 기존 서명은 무효이고 최신 범위로 재검토 가능 | 새 의견·독립 서명 필요. 같은 범위에서 요청 키/의견만 바꿔 미해결 승인을 우회할 수 없음 |
| 실제 원장 합계와 원래 두 분개를 대조 | 다른 주문 잠금으로 누락한 출금 분개를 덮지 않음 | 고객 BTC 원장 합계 조회 비용이 발생. 실제 수탁/체인 대사를 대신하지 않음 |
| 알려진 입금 모니터링의 최신 처리·열린 사건 검사 | 미처리 입력·열린 조사·새 신호를 지급 의견으로 덮지 않음 | 최대1000개 사건 범위 검사 비용과 보류가 발생. 이 규칙만으로 AML 전체 위험 없음이 증명되지 않음 |

원래 상대 회신은 불변 지급 대상의 정보 교환 관측 사실이다.147의15분은 새 개인정보 전송을 시작할 수 있는 의도 수명이며, 이후 지급 판단을 위해 같은 정보를 다시 전송하는 이유로 사용하지 않는다. 과거 회신을 검증하고 현재 신원/허용 상대·위험을 재검사한다. 새 지급 검토는10분의 별도 수명과 실제 현재 원본을 가진다. 공급사의 실제 응답 유효성/계약 조건은 운영 도입 전에 확인해야 한다.

## 구현과 실제 코드

출처: [BitcoinWithdrawalPayoutBasis.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalPayoutBasis.java#L64), 현재 소스의 부분 발췌.

```java
        var requested=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(open(owner,attempt,row,"key_id","ciphertext","content_mac","attempt"));checkOriginal(requested,current);
        var reply=json.readValue(open(owner,attempt,row,"result_key","result_cipher","result_mac","result"),CodeVaspWithdrawalAuthorizationClient.Result.class);
        var r=current.request();var data=reply.data();
        if(!"VERIFIED".equals(reply.status())||reply.reason()!=null||data==null||!intentId.equals(data.transferId())
            ||data.ciphertextHash()==null||!data.ciphertextHash().matches("[0-9a-f]{64}"))throw held();
        var sender=data.originator();var beneficiary=data.beneficiary();
        if(sender==null||beneficiary==null||!r.originatorName().equals(sender.primaryName())||sender.secondaryName()!=null||!r.originatorBirth().equals(sender.dateOfBirth())
            ||!List.of(r.originatorAccount()).equals(sender.accounts())||!r.beneficiaryName().equals(beneficiary.primaryName())||beneficiary.secondaryName()!=null
            ||!List.of(r.address()).equals(beneficiary.accounts()))throw held();
        var money=jdbc.queryForMap("""
```

현재 부분 발췌다. 기존 AES-GCM/HMAC으로 원본/회신을 열고 실제 이전 ID·이름·생일·계정·지급지를 현재 고객 원본과 대조한다. 과거 상대 키는 이미 만료됐을 수 있으므로 현재용 Request 생성자로 역직렬화하지 않는다. JSON 숫자는 JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS로 읽어 원화 곱의 끝자리를 이진 실수로 잃지 않는다.

출처: [BitcoinWithdrawalPayoutReviews.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalPayoutReviews.java#L137), 현재 소스의 부분 발췌.

```java
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>=clock_timestamp()-interval '5 minutes')",Boolean.class,id,actor,p.checker().person(),p.checker().version(),sid,hash)))throw denied(HttpStatus.CONFLICT);
        return r;
```

현재 부분 발췌다. 지정 승인자가 같은 세션으로 실제 열람한 내용만 옵션/결정 대상이다. 다른 계정이어도 operatorPersonId가 같으면 자기 승인으로 거절한다. 승인과 반려의 서명 방향·내용 해시·업무 종류·챌린지가 결합된다.

출처: [BitcoinWithdrawalPayoutReviews.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalPayoutReviews.java#L94), 현재 소스의 부분 발췌.

```java
        return work(actor,sid,"BTC_PAYOUT_REVIEW_DECIDE",()->{check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.BTC_WITHDRAWAL_PAYOUT,id,hash,approve,proof);
            jdbc.update("INSERT INTO bitcoin_withdrawal_payout_decisions(review_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
            audit.record(actor,"BTC_PAYOUT_REVIEW_DECIDED","bitcoin-payout-review:"+id,"SUCCESS",hash);return read(id);});
```

현재 부분 발췌다. 서명 소비·검증키 카운터·독립 결정·필수 감사가 같은 TX다. 감사가 실패하거나 누락되면 결정과 서명 소비를 롤백한다. TypeScript의 트랜잭션 콜백과 같은 경계지만 이 Java 콜백에는 외부 HTTP/체인 서명을 넣지 않는다.

출처: [withdrawal-payout-review-schema.sql](../../src/main/resources/bitcoin/withdrawal-payout-review-schema.sql#L127), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_payout_cancel_exclusion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=NEW.withdrawal_id) THEN RAISE EXCEPTION '지급 실행이 확정된 출금은 고객 취소할 수 없습니다';END IF;
 RETURN NEW;
END $$;
```

현재 부분 발췌다. 취소와 실행 확정은 같은 사용자 행을 잠그므로 한 쪽 커밋 이후 반대쪽 INSERT는 거절된다. 사용자 취소가 먼저면 지급 source 검사도 실패한다. 고객 취소는 기존 엔진 RELEASE/확정 Kafka를 기다리며 HTTP 응답만으로 환급하지 않는다.

BitcoinWithdrawalPayoutBasis는 자격·원본 견적/예약·인증된 이전 응답·현재 CDD/KYC·현재 허용 상대·원장 합계·모니터링 입력/사건을 검사한다. 기존 모니터링 입력이 없으면 NO_NATIVE_INPUT으로 명시하며 위험이 없다고 표현하지 않는다. 입력이 있으면 최신 head가 모든 입력을 처리했고 미확인 원화 자료가 없으며 열린 사건도 없어야 한다. 담당자가 노트에 안전하다고 적어 이 검사를 우회할 수 없다.

검토의 의미 지문에는 정확한 지급액/수수료/총액·지급지/스크립트·견적/예약 근거·인증된 이전 요청/회신 지문·현재 고객/위험 범위를 결합한다. 같은 내용의 디렉터리 정기 갱신처럼 경제적/위험 의미가 바뀌지 않은 관찰 순번만으로 서명을 무효화하지 않는다. 현재 허용 정책·신원·새 입력/사건이 바뀌면 다시 검토한다. 원래 신청 평가를 나중 가격으로 바꾸지 않는다.

bitcoin_withdrawal_payout_reviews/views/decisions/commitments는 변경/삭제/TRUNCATE를 막고 필수 감사를 요구한다. 의견과 상세 근거는 인증 암호화로 보관하고 지정된 현재 두 인물만 읽는다. 결정에는 일회 인증 ID와 영속 내용 결합/감사를 남긴다. 인증 챌린지는 단기 상태라 만료/다음 옵션 발급 시 정리할 수 있으며 영구 결정에서 임시 챌린지를 외래키로 붙잡지 않는다. 결정 INSERT 시점의 소비/내용·세션 검사는 DB에서도 시행한다. 실제 WebAuthn 서명은 검증 시점에 확인하며 현재 영속 자료는 업무 내용 결합·결정·감사다. 서명 원문과 공개키 스냅샷을 통한 독립 오프라인 서명 증명까지 구현했다고 주장하지 않는다.

## 운영 API와 고객 상태

| 경로 | 현재 권한·입력 |
| --- | --- |
| POST /api/admin/withdrawals/BTC/payout-reviews | 원래 이전 담당자 OPERATOR/MFA. transferId·checkerId·requestKey·note만 허용 |
| POST /api/admin/withdrawals/BTC/payout-reviews/{id}/inspect | 지정 담당자/승인자·현재 인물/버전/MFA, 빈 본문. 원문 열람과 감사 |
| POST .../{id}/approve/options 또는 reject/options | 지정 APPROVER/MFA·같은 세션의 열람·payloadHash |
| POST .../{id}/approve 또는 reject | 같은 내용/방향에 대한 실제 일회 서명 proof |

금액·주소·고객·원장/위험 판정 입력이나 공개 commit 경로는 없다. commit은 이후 신뢰된 지급 워커의 내부 경계이며 아직 자동 작업자나 실제 서명/방송에 연결하지 않았다. 검토 승인만으로는 취소/자금 이동이 확정되지 않는다. 실행 확정 뒤 고객 조회는 PAYOUT_COMMITTED로 표시하고 완료로 표시하지 않는다. 고객에게 내부 의견/조사 사유를 노출하지 않는다.

## 작성한 테스트

신규 BitcoinWithdrawalPayoutReviewIntegrationTest34개와 기존29개 이전 확인 시험을 공통 BitcoinWithdrawalTransferTestSupport로 준비한다. 실제 PG·고객 패스키 서명·지정 운영자 WebAuthn 서명·원장·암호·감사·MVC를 사용한다. 체인 관찰/블록은 합성 fixture이고 보류 원장·평가·모니터링 처리/사건 연결은 제품 코드다. 노드 파생/CODE HTTP는 기존 대역이며 실제 공급사/체인 지급을 검증한 것이 아니다.

정확한 지급액/수수료·암호화 원문·같은 사람의 다른 계정·지정 열람·잘못된 해시/방향/무서명·서명 소비 롤백·동시 결정·불변 기록·취소/실행 확정 경합·원장 불일치·미처리 입금/열린 사건·새 처리 범위의 재승인·현재 KYC 만료/미처리 입력의 직접 DB 실행 차단·단기 인증 정리 후 영구 이력 보존·엄격 REST를 검증한다. 모든 시험은 원래 고객 총액 예약을 보존하고 지급을 발생시키지 않는다.

OperatorMfaService의 새 실제 업무 종류를 추가하면서 BoundApprovalTest의 exhaustive switch에도 전용 통합 검증 경로를 명시했다. 기존 네 업무의 파라미터 시험을 새 업무가 통과한 것으로 계산하지 않는다. 실제 신규 함수와 기존 회귀의 입력별 결과는 아래 원본/증적에서 구분한다.

## 실행 명령과 결과

```sh
python3 scripts/verify_bitcoin_withdrawal_payout_reviews.py
python3 scripts/verify_bitcoin_withdrawal_transfer_reviews.py
python3 scripts/verify_bitcoin_withdrawal_valuations.py
python3 scripts/verify_bitcoin_withdrawal_reservations.py
python3 scripts/verify_bitcoin_withdrawal_cancellations.py
python3 scripts/verify_bound_approvals.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_bitcoin_withdrawal_wire.py
```

신규/기존 묶음별 결과는 지급34·이전29·평가15·예약19·취소20·공통승인191(보안41 포함)·금융217·실제연결3다. 고유528개/528회 통과·실패/오류/제외0. 현재 소스에서 각 선택 묶음을 검증했다. 취소20개는 시각 사전검사로 나뉜 첫10개와 마지막10개의 합산이며 실행하지 못한 시험을 성공으로 표시하지 않았다. 메서드별/설정별 새 소유 PG를 사용하고 시험 소유 프로세스·컨테이너를 종료했다. 고객/운영자 JAR 빌드 통과. 기존 deprecated/unchecked/JVM 클래스 공유 경고는 남고 새 성능 수치·실제 공급사·운영 지급·봇 실행은 없다.

개발 중 새 enum의 전용 통합 검증 분기를 누락한 컴파일 오류를 수정했다. 새 위험 처리 뒤 최신 근거 재승인 경로를 추가 검토하면서 단기 챌린지와 영구 금융 결정을 FK로 묶어 다음 인증 정리가 실패하는 문제도 발견했다. 임시 행 수명과 영구 서명 결합/결정/감사를 구분하고, 실제 정리 뒤 이력이 남는 회귀를 추가했다. 이 실패와 초기31개 통과는 최종34개/현재 소스의 결과에 섞어 성공으로 합산하지 않는다. 전체 재검증의 첫 시도는 준비 단계에 약115초 실행 지연이 발생한 뒤 가격 시각 제약이 원본을 거절해 중단됐다. 금융 시각 검사를 완화하지 않고 소유 DB에서 다시 실행했다. 재검증의 취소10개 통과 뒤에는 다음 시험 전 clock 사전검사가2.017초 차이를 측정해 개시를 거절했다. 동일 제품/시각 제한의 새 소유 DB에서 남은10개를 선택 실행하고 두 묶음을 고유20개로 합산했다.

## 남은 한계와 다음 연결

아직 지급 워커가 commit을 호출하지 않는다. 다음은 실제 Bitcoin Core의 거래 생성/검사·분리된 키 보호 영역의 서명·고정 거래 방송/결과 불명·원장/엔진 완료·독립 대사 연결이다. 실행 확정은 지급 완료가 아니며 확정 후 단순 장애/시간 초과로 고객 예약을 환급하지 않는다. 실제 송금이 불가능한지 검증하지 않은 수동 복구/자동 취소도 추가하지 않았다.

고객의 참고평가100만원 이상·국내 본인 CODE 경로의 기존 범위를 유지한다. 다른 지갑/프로토콜·지급에 필요한 추가 위험 정책·출금/거래 자체의 추가 감시 규칙·실제 공급사 응답 유효성·조직 실사/인물 확인·키 보호·법정 보존/외부 심사/운영 배포는 완료로 표시하지 않는다. UUID/소프트웨어 보안키 시험만으로 물리적으로 다른 사람이 승인했다거나 HSM/온체인 다중 서명이 적용됐다고 주장하지 않는다. Rust 코어는 변경하지 않았다.

## 작성한 테스트 코드


[BitcoinWithdrawalPayoutReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalPayoutReviewIntegrationTest.java)

- `independentSignatureApprovesExactFundsAndCommitDoesNotPay`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `approvalRemainsCancellableUntilExecutionCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseAlsoRejectsCancellationAfterExecutionCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `simultaneousCancellationAndExecutionCommitHaveExactlyOneWinner`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewCustomerRestrictionBlocksPreviouslyApprovedExecution`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedCustomerVersionInvalidatesTheUnsignedReviewBeforeSignatureConsumption`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `checkerMustInspectTheExactPayloadInItsCurrentSession`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherOperatorCannotInspectTheEncryptedOpinion`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `separateAccountsForTheSamePersonCannotSelfApprove`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingProofCannotApproveAnInspectedReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `rejectionSignatureCannotBeUsedAsPayoutApproval`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedHashCannotConsumeTheOriginalSignature`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sameRequestKeyReplaysTheSameOpinionButCannotChangeIt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `newRequestKeyCannotSupersedeAPendingOrApprovedReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `rejectionPreservesFundsAndAllowsANewExplicitReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingProposalAuditRollsBackTheEncryptedReviewAndBinding`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingInspectAuditCannotSatisfyTheApprovalPrerequisite`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `decisionAuditFailureRollsBackBothTheDecisionAndConsumedSignature`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedDecisionAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingExecutionAuditLeavesTheCustomerFreeToCancel`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `encryptedOpinionAndAllApprovalFactsAreImmutable`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `simultaneousDecisionConsumesTheBoundSignatureOnlyOnce`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `routineDirectoryRefreshDoesNotChangeTheApprovedEconomicScope`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `corruptedMaterializedBalanceCannotBecomeAPayoutPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterMarketReferenceDoesNotChangeTheSignedWithdrawal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingCurrentCounterpartyBlocksNewPayoutPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `strictHttpRejectsInjectedFundsUnknownFieldsAndPublicCommitRoute`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `headlessHttpUsesTheAssignedInspectionAndActualBoundSignature`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `recognizedButUnmonitoredNativeFundsBlockNewPayoutReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewNativeInputAfterApprovalBlocksExecutionUntilANewReview`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anOpenNativeMonitoringCaseCannotBeOverriddenByAPayoutOpinion`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseCannotCommitAnApprovalAfterTheCurrentKycExpires`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseCannotCommitPastAnUnprocessedNativeInput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `temporaryChallengeCleanupPreservesThePermanentBoundDecision`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 3f3fb1c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/3f3fb1c674e31ec99b3f3b4ecc38b982b0285f80)
- [격리 실행 증적](../reference/148-bitcoin-independent-payout-review-verification-2026-10-26.json)

현재 연결: [154 선택 입력의 확정 출처와 비용 정책](154-bitcoin-settled-input-provenance.md)에서 서명 범위와 후보의 별도 불변 출처 근거를 보강했다. 이 문서의 기존 검증 수치는 해당 구현 시점의 결과다.
