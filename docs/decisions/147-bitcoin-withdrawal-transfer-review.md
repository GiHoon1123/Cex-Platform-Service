# 고정 BTC 출금의 주소 확인 뒤 신원 제공과 미해결 전송 보존

불변 BTC 출금 원본에 현재 고객 확인·신청 당시 참고평가·확정 예약·허용 상대를 결합하고, 주소 소속 확인 뒤에만 개인정보 기반 이전 승인을 요청한다. 의도·시도·회신은 암호화와 필수 감사로 보존하며 응답 불명을 새 ID로 재전송하지 않는다. 이 기록은 지급 승인이나 실제 체인 전송이 아니다.

## 문제와 기존 동작

141~145는 고객의 실제 서명과 정확한 총액 예약·취소·확정 수신·신청 당시 참고평가를 연결했다.144/146의 HTTP 클라이언트만 호출하면 누가 어떤 출금 원본으로 정보를 제공했는지, 취소·KYC 변경·동시 실행·완료 저장 실패 뒤 재전송을 어떻게 막는지 설명할 수 없다.

## 기존 제품과 공식 자료의 접근

[CodeVASP 주소 검색](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/06-Virtual-Asset-Address-Search), [이전 승인](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/07-Asset-Transfer-Authorization), [요청 개발 흐름](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/08-Developing-the-Request-Process)을 따라 주소 소속 확인과 개인정보 교환을 분리한다. 이전 ID는 UUIDv4이고 고객 수수료를 뺀 실제 지급액과 원래 평가를 보낸다. 기존 입금의 CodeVaspTransferIntents/Execution, CddCrypto, 현재 운영 MFA·허용 상대 정책을 재사용했다. 새 패키지나 암호 알고리즘을 추가하지 않았다.

법적 의무와 자체 목적 정책은 구분한다. 이 자동 경로는 국내 CODE·본인 명의 개인·신청 참고평가100만원 이상으로 제한한다. 소액·다른 명의·해외/개인지갑을 법적 면제나 안전으로 판정하지 않으며 별도 심사가 미구현인 상태로 보류한다.145의 가격은 서비스 참고가이며 법정 평가 완료를 주장하지 않는다. [109의 국내 적용 근거](109-durable-bitcoin-krw-reference-price.md)와 후속 시행일 검토를 함께 읽는다.2026년 발표 내용의 시행 예정 조항을 현재 의무로 단정하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 운영자가 고정 출금에 허용 사업자만 선택 | 서버 원본의 주소·NET·수수료·신원·평가를 임의 입력으로 바꾸지 못함 | 아직 고객이 상대를 직접 선택하는 UX가 없고 운영 업무가 필요함 |
| 주소 확인 → 새 현재 상태 검사 → 신원 제공 | 잘못 선택한 상대·취소/제한 고객에게 추가 신원 제공을 차단 | 외부 조회1회·단계별 DB 커밋·현재 목록 의존성이 추가됨 |
| 사용자 오름차순 잠금과 DB 제약 | 같은 고객·운영자 변경/동시 실행과 충돌을 직렬화 | 플랫폼의 짧은 금융/보안 TX에서 대기 가능. Rust 코어에는 잠금·조회·입출력 추가 없음 |
| 의도15분·주소 확인2분·재시도30초 | 오래된 확인을 새 제공 권한으로 재사용하지 않음 | 장시간 작업은 최신 근거가 필요하고 수동 복구 업무가 발생함 |
| 미전송만 같은 ID로 재시도 | 알려진 미전송과 송신 후 불명을 구분 | 불명/시작만 남은 이전 승인에는 자동 재시도가 없고 공급사 결과 확인이 필요함 |
| 실제 응답도 암호화·무결성 검사 | 원래 시도와 회신을 나중에 대조할 수 있음 | 암호 키/보존 정책·DB 저장 비용·권한 관리가 필요. 암호화만으로 적법 제공·보존이 확정되지 않음 |
| 명시적인 수동 실행 API | 자동 개인정보 제공 작업자 없이 두 단계의 실제 업무를 끝까지 검증 | 운영자가 실행해야 함. 현재 기능에 불필요한 작업 큐를 추가하지 않았음 |

공급사 회신은 우리 HTTP에서 관측한 사실이다. 독립적인 상대 전자서명 증명서, 고객 동일인 전체 검증, AML 위험 판정, 플랫폼 지급 허가로 부풀리지 않는다. VERIFIED 상태 조회 자체는 어느 자산도 움직일 수 없다.

## 구현과 실제 코드

출처: [BitcoinWithdrawalTransferBasis.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalTransferBasis.java#L67), 현재 소스의 부분 발췌.

```java
        if(value.compareTo(new BigDecimal("1000000"))<0)throw purposeReview();
        if(((Number)source.get("user_version")).longValue()!=customer.getVersion()
                ||!eligibility.permitted(customer)||!"SUCCESS".equals(source.get("source_status"))
                ||!source.get("source_hash").equals(source.get("source_hash_original"))
                ||amount.compareTo((BigDecimal)source.get("valuation_amount"))!=0
                ||((BigDecimal)source.get("source_price")).compareTo((BigDecimal)source.get("valuation_price"))!=0
                ||value.compareTo(amount.multiply((BigDecimal)source.get("source_price")))!=0
                ||!source.get("created_at").equals(source.get("reference_at")))throw held();
        String quoteRaw=(String)source.get("payload");
```

현재 소스의 부분 발췌다. 고객의 현재 자격/버전과 기존 신청 당시의 NET×참고가를 사용한다. 나중에 가격이 상승했다고 원래 제공 목적을 넓히지 않는다. BigDecimal.compareTo는 십진 금액을 비교하며 JS Number의 이진 부동소수점으로 변환하지 않는다.

출처: [BitcoinWithdrawalTransferReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalTransferReview.java#L87), 현재 소스의 부분 발췌.

```java
        for(int phase=0;phase<2;phase++) {
            Started started=tx.execute(t->begin(actor,sid,intent));
            if(started==null)return tx.execute(t->state(intent));
            observe(started);
            if(started.stage().equals("TRANSFER"))break;
        }
        return tx.execute(t->state(intent));
```

현재 소스의 부분 발췌다. TransactionTemplate의 execute가 반환됐을 때 시작 근거는 커밋됐다. 그 뒤 observe의 HTTP를 실행하므로 DB 고객 잠금이나 엔진 코어를 잡고 기다리지 않는다. 두 번째 단계는 다시 현재 KYC·취소·원본 의미를 검사한다. 단일 DB 트랜잭션으로 외부 API 전체를 감쌀 수 없다는 점은 TypeScript 백엔드에서도 같다.

출처: [BitcoinWithdrawalTransferReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalTransferReview.java#L102), 현재 소스의 부분 발췌.

```java
        var prior=latest(id,"TRANSFER");
        if(prior!=null&&(!"NOT_SENT".equals(prior.get("status"))||!oldEnough(prior,30)))return null;
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(now())||!latestIntent(row))throw held();
        var basis=sources.capture(access.customer(),(UUID)row.get("withdrawal_id"),(String)row.get("peer_id"),id);
        if(!basis.meaningMac().equals(row.get("meaning_mac"))||!basis.directorySource().equals(row.get("directory_source_id")))throw held();
        verifySealed(row,basis.customerId(),id);
```

현재 소스의 부분 발췌다. 이전 승인의 시작만 남았거나 결과 불명/확정 응답이면 새 시도를 만들지 않는다. 미전송만 DB의 실제30초 대기 뒤 같은 intent UUID로 재시도한다. 요청 키나 담당자를 바꿔 새 ID로 우회하는 것도 서비스와 DB 양쪽에서 차단한다.

출처: [BitcoinWithdrawalTransferReview.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalTransferReview.java#L131), 현재 소스의 부분 발췌.

```java
                var result=addresses.verify(new CodeVaspWithdrawalAddressClient.Request(request.address(),request.peer(),request.key()));
                if(result==null)throw new IllegalStateException();
                if("VALID".equals(result.status())&&result.reason()==null&&request.peer().entityId().equals(result.entityId()))status="VALID";
                else if("INVALID".equals(result.status())&&result.entityId()==null&&ADDRESS_REASONS.contains(result.reason())){status="INVALID";reason=result.reason();}
                else throw new IllegalStateException();
                plain=plain(result);
            } else {
```

현재 소스의 부분 발췌다. 주소 단계의 타입에는 이름/생일이 없고 정확히 선택한 상대의 VALID만 다음 단계 근거가 된다. 주소가 INVALID면 신원 클라이언트 호출0이다. 현재 디렉터리의 상대 키가 바뀌면 주소를 다시 확인해야 한다.

새 표는 bitcoin_withdrawal_transfer_intents/attempts/results다. 의도/시도/회신마다 소유자와 행 ID를 결합한 기존 AES-GCM 및 서로 다른 HMAC 용도를 사용한다. 의도는 현재 고객 버전·CDD 신원·원본 견적/평가·자체 발급 계정·선택 상대/목록 출처의 의미를 고정한다. 실행마다 현재 근거를 새로 봉인하고 이전 키를 현재 요청 생성자로 역직렬화하지 않는다. 신원/주소/응답 본문은 진단 문자열·상태 API·감사 detail에 복제하지 않는다.

발급된 자체 입금 주소는 기존 소유 계정 식별자로 사용한다. 주소가 아직 없으면 제공 의도를 만들지 않고 먼저 기존 소유 주소 발급 업무를 거쳐야 한다. 이것을 실제 출금 트랜잭션 입력 주소나 특정 UTXO 소유권 증명으로 설명하지 않는다. 체인 지급은 아직 없다.

취소가 외부 대기 중 발생하면 이미 받은 주소/이전 응답은 보존한다. 주소 완료 후 취소를 발견하면 신원 단계는 시작하지 않는다. 신원 전송 시작 커밋 뒤에는 이미 전송된 정보를 되돌릴 수 없다. 회신 보존은 과거 사실이며 이후 지급 검사에서 현재 취소/제한을 다시 확인해야 한다. 완료 저장·필수 감사가 실패하면 시작만 남기고 미해결로 보존한다.

## 운영 API

| 경로 | 권한·입력·반환 |
| --- | --- |
| POST /api/admin/withdrawals/BTC/{withdrawalId}/transfer-intents | 현재 OPERATOR/MFA, peerId와 requestKey만 허용. 서버 고객/주소/금액을 변경하는 필드는 거절 |
| POST /api/admin/withdrawals/BTC/transfer-intents/{intentId}/execute | 현재 원래 담당자·인물·버전/MFA, 본문 없음. 최대 주소/이전 두 단계 |
| GET /api/admin/withdrawals/BTC/transfer-intents/{intentId} | 현재 OPERATOR/APPROVER/AUDITOR/MFA. 최소 상태·시도/시각만 반환 |

기본 codevasp.private-transfer.enabled=false다. 허용 목록/현재 기술 목록·실사·키가 준비돼도 이 설정이 꺼져 있으면 주소 VALID에서 멈춘다. 운영자 API에 로그인 페이지/리다이렉트를 추가하지 않았다. 토큰·신원·상대 목록을 고객 요청으로 받지 않는다.

## 작성한 테스트

BitcoinWithdrawalTransferReviewIntegrationTest의 실제 함수명은 아래에 전부 연결한다. 정상 순서/NET/참고평가·암호 원본·동시 실행·같은 요청 키·현재 자격/버전/세션·취소 전/IO 중·낮은 목적 금액·정식 예약 부재·비활성 설정·주소 거절/다른 사업자·이전 불명/잘못된 신원/미전송·필수 감사/불변 DB·엄격한 운영 REST를 검증한다. 미전송 재시도는 불변 시각을 고치지 않고 실제30초 DB 대기를 거쳐 같은 이전 ID인지 확인한다. 모든 시험은 원장 예약을 그대로 보존하고 엔진/노드 추가 출력0을 검사한다.

실제 PG와 소프트웨어 패스키 서명·운영자 MFA·제품 고객/원장/암호/감사를 사용한다. Bitcoin Core의 주소 파생과 공급사 HTTP만 대역이다. 실제 공급사/노드/하드웨어 보안키/체인 지급의 검증으로 표시하지 않는다.144/146의 실제 소유 HTTP 서버 계약 검증과 이 업무 시험의 범위를 구분한다.

## 실행 명령과 결과

```sh
python3 scripts/verify_bitcoin_withdrawal_transfer_reviews.py
python3 scripts/verify_bitcoin_withdrawal_valuations.py
python3 scripts/verify_bitcoin_withdrawal_reservations.py
python3 scripts/verify_bitcoin_withdrawal_cancellations.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
python3 scripts/verify_bitcoin_withdrawal_wire.py
```

신규29·기존 평가15/예약19/취소20·금융217·보안41·실제 Rust/WAL/Kafka3, 고유344개/344회 통과·실패/제외0. 신규29개는 동일 제품 소스의 최초24개 통과와 마지막5개 선택 실행을 합산했다. 마지막5개 중 추가 예약을 준비한 두 시험의 사전 엔진 호출을 검증 범위에서 분리했고 제품 동작은 바꾸지 않았다. 메서드별 소유 PG를 사용하며 시험 소유 컨테이너/프로세스를 종료했다. 고객/운영자 JAR 빌드 통과. JVM 클래스 공유 및 기존 deprecated 경고의 범위는 실행 증적을 따른다. 실제 공급사·운영 체인 지급·봇을 실행하지 않았다. 앞선 사이클 결과는 이번 합계에 재사용하지 않았다.

개발 중 테스트의 verify 이름이 기존 KYC 보조 함수와 충돌한 컴파일 오류, Mockito 대역 재설정 시 이전 Answer를 호출한 오류를 수정했다. 제품 암호 결과 검증에서는 전역 JSON 설정이 null 필드를 생략하므로 reason 필드가 없는 정상 VALID를 잘못 보류하던 문제를 수정했다. 실제 DB 불변 시각·금융 검사·암호 검증을 완화하지 않았다.

## 남은 연결과 한계

출금 전체 완료가 아니다. 다음은 현재 신원·이전 결과·고객/체인 위험을 확인하는 담당자 의견과 독립 내용 결합 승인, 취소/지급의 배타적 결정, 키 보호 영역의 BTC 거래 생성/서명·방송·결과 불명/체인 확정·재조직·원장/엔진 완료와 대사다. 소액·해외/개인지갑·다른 명의와 실제 공급사/모듈 버전·개인정보 제공/보존·실사 계약은 별도 검토다. 인프라 복제/백업은 기존 문서 범위이며 이번에 배포하지 않았다. Rust 단일 스레드 코어 변경은 없다.

## 작성한 테스트 코드


[BitcoinWithdrawalTransferReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalTransferReviewIntegrationTest.java)

- `confirmedAddressPrecedesExactNetIdentityExchangeWithoutMovingMoney`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `originalIntentAndObservedIdentityAreEncryptedAndMacBound`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sameRequestKeyReplaysMetadataButChangedInputIsRejected`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateExecutionCannotRepeatAVerifiedTransfer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `deniedAddressCannotDiscloseIdentityOrBeTreatedAsPaymentApproval`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `knownInvalidAddressAllowsANewExplicitIntentWithoutErasingTheOldOne`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownTransferCannotRetryOrEscapeThroughANewIntentId`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `provenNonSendIsSavedAndImmediateRetryIsSuppressed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedVerifiedIdentityBecomesUnknownAndCannotRetry`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongAddressEntityCannotStartThePrivateStage`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentCustomerRestrictionBlocksOpeningTheStoredIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationBeforeStartBlocksBothStages`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationDuringAddressIoPreservesTheFactButBlocksIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationDuringTransferPreservesTheReplyWithoutPaying`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentOperatorSessionAndOriginalPersonAreRequired`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `prepareAuditFailureRollsBackTheIntentBeforeAnyIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingPrepareAuditIsRejectedAtCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `startAuditFailureCannotSendEvenTheAddress`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `privateReplyCommitFailureLeavesAnUnresolvedAttemptAndCannotResend`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `immutableIntentAttemptAndReplyCannotBeRewrittenOrRemoved`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `simultaneousExecutionStartsEachStageOnlyOnce`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterPriceDropDoesNotRewriteTheOriginalPrivacyPurpose`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `strictOperatorHttpRejectsUnknownDuplicateAndInjectedFields`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `headlessHttpCanPrepareExecuteAndReadOnlyMinimalMetadata`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `belowPurposeThresholdDoesNotBorrowTheOlderLargerRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `httpAcknowledgementWithoutCanonicalReserveCannotDiscloseIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `changedCustomerVersionInvalidatesTheOriginalSignedPurpose`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disabledPrivateTransferStopsAfterAddressValidation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `provenNonSendCanRetryOnlyAfterDurableDelayWithTheSameTransferId`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 84c1e37](https://github.com/GiHoon1123/Cex-Platform-Service/commit/84c1e376856f6bce798326ad25794b80f251621b)
- [격리 실행 증적](../reference/147-bitcoin-withdrawal-transfer-review-verification-2026-10-26.json)
