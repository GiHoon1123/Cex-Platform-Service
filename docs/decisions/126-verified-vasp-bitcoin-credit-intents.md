# 현재 금융 근거를 결합해 정상 VASP 입금 의도를 한 번만 기록하기

현재 고객·체인·원장·출처·조사·독립 대사를 모두 확인한 VASP 입금에 불변 반입 의도를 기록한다. 같은 입금 UUID를 재사용하며 이 기록만으로 엔진이나 사용 가능 잔액을 늘리지 않는다. 독자는 자동 정상 경로의 허용 조건과 과거 승인 재사용의 한계를 이해할 수 있다.

후속 [127 영속 전달과 확정 이벤트 반영](127-durable-bitcoin-credit-dispatch-and-settlement.md)은 최초 전송 권한을 재검증하고 같은 원본의 전송/재확인·Kafka 원장/잔액·고객 완료 조회·독립 대사를 연결한다. 아래 기록의 수치는 당시 범위이며 자동 처리 워커/전체 브로커 연동은 별도다.

## 문제와 기존 동작

기존 단계는 실제 온체인 수탁 사실을 고객 PENDING 원장에 기록하고 출처 자료 교환·탐지·사건 조사·대사를 개별 검증했다. 각각의 성공을 금융 허가로 간주해서는 안 된다. 과거 정상 고객이 제한됐거나 새 고객확인 자료가 제출됐는데 옛 출처 자료를 사용하는 경우, 최신 대사가 실패했는데 과거 성공을 가져오는 경우를 함께 막아야 한다.

이번 정책 BTC_VERIFIED_VASP_CREDIT_V1은 **실제 VASP 출처 증빙이 마련된 표준 고객의 자동 경로**다. 모든 고객 입금에 새로운 운영자 서명이나 수동 승인을 요구하지 않는다. 소액·개인지갑·자료 부재·강화 고객확인의 다른 업무 경로는 별도로 완성해야 한다. 112의 개인정보 교환 목적에 쓰는 내부 원화 기준을 법적 면제나 출처 생략 허가로 해석하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 Coinbase의 [미확정 입금 안내](https://help.coinbase.com/en/exchange/crypto-transfers/deposit-pending-unconfirmed-transaction)에서 입금 발견과 네트워크 확인을 기다리는 상태를 구분하는 것을 확인했다. Kraken의 [입출금 보류 안내](https://support.kraken.com/articles/360000380966-why-is-my-deposit-or-withdrawal-on-hold-?mode=consumerapp)는 자동 해소와 별도 검토가 필요한 보류를 구분한다. 이 공개 업무 관례를 참고하되 두 제품의 내부 SQL·정책·확인 횟수를 복제한다고 주장하지 않는다.

국내 [업비트 트래블룰 안내](https://support.upbit.com/hc/ko/articles/4498679629337)도 같은 날 다시 확인했다. 자료가 갖춰진 연동 사업자 입금과 추가 확인이 필요한 경로를 구분하므로 모든 정상 입금을 운영자가 건별 승인하는 방식으로 만들지 않는다. [CodeVASP 정보 요청 계약](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)은 누락된 이전 자료 요청과 NORMAL 응답을 정의한다. 응답 수신 자체를 우리 금융 허가로 바꾸지는 않는다. 국내 조문/개정 유예·환산 시점의 확인 범위는 [109 공식 자료와 적용 시점](109-durable-bitcoin-krw-reference-price.md#공식-자료와-적용-시점)과 [112 목적 제한](112-private-transfer-purpose-boundary.md)을 연결하며 이번 단계에서 새로운 법정 금액/면제 조건을 도입하지 않는다.

멱등 원본은 [AWS의 안전한 재시도](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) 관례와 기존 주문/개인정보 전송 의도를 따른다. 이미 설치된 JdbcTemplate·TransactionTemplate·Jackson·CddCrypto와 기존 원장/출처 검증기를 사용한다. 새로운 DB·큐·키 관리 계층·패키지를 추가하지 않는다. 이번 자동 정책은 자체 안전 통제이며 법률·신고·인증의 충족 판정이 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 감수하는 비용·대안·선택 이유 |
| --- | --- | --- |
| 여러 독립 근거를 현재 고객 잠금 아래 결합 | 서로 다른 시점/고객/정책의 성공을 섞어 승인하는 문제 차단 | 플랫폼 DB의 짧은 잠금과 여러 읽기 비용. 외부 RPC를 잠금 안에 넣지 않음 |
| 정상 VASP 입금은 자동 정책 | 모든 정상 고객에게 운영자 승인/추가 인증을 강요하지 않음 | 자동 지원은 검증된 출처 자료가 있는 표준 고객 경로로 제한. 다른 경로는 PENDING 보존 |
| 입금 UUID를 그대로 고유 키로 사용 | 중복/동시 요청의 경제적 원본이 같고 재시작 후 유지 | 원본·금액을 바꾸려면 새 UUID로 우회할 수 없음. 현재 의도는 수정/삭제하지 않음 |
| 최신 대사와 실제 원본 증거를 검증 | 새 RUNNING/FAILED 뒤 옛 MATCHED 사용 차단, 재해시만 한 다른 출력도 차단 | 별도 대사가 미완료·오래됐으면 보류. 플랫폼 PENDING_RECEIPT 대사가 엔진/전체 부채 검증은 아님 |
| 서명된 현재 전체 범위 사건 종결만 인정 | 정상화된 설명 사건은 자동 경로로 재평가 가능 | 열린 과거 사건 하나라도 있으면 보류. 종결은 출처·체인·고객 조건을 대신하지 않음 |
| 근거에는 식별자/MAC/지문만 보존 | 주소·이름·생년월일·원문 신원 복제를 줄임 | 재검증은 권한 있는 실제 원본/현재 키를 필요로 함. 공개 SHA만으로 신원 진위를 보장하지 않음 |
| 의도와 외부 금융 확정 분리 | 네트워크 오류를 잔액 증가로 오판하지 않음 | 이후 영속 전송·불명 처리·Kafka 금융 반영 단계를 구현해야 함 |

## 허용 조건과 실패 처리

고객은 현재 활성 CUSTOMER이며 거래 제한/관계 종료가 없고 현재 CDD가 STANDARD여야 한다. 실제 live KYC가 VERIFIED이고 유효해야 하며 공급사 재확인 시각이 과거 15분 범위 안이어야 한다. 공유 고객 자격 코드에 미래 fresh_at 거부를 추가해 주문도 같은 비정상 시각을 사용하지 못한다.

실제 BTC PENDING 원장은 고객 보류 증가와 수탁 상대 계정 감소의 정확한 두 항목이어야 한다. 최신 체인 판단은 현재 관찰에 연결되고 2분 이내이며 최소6확인·비코인베이스·지갑 출금 아님이어야 한다. 최초 인식 시 동결한 원화 참고평가는 유지하며 새 가격으로 불리한 과거 평가를 바꾸지 않는다.

고객의 알려진 탐지 입력을 모두 처리했고 불완전 평가/미평가 전송이 없어야 한다. 현재 머리뿐 아니라 **모든 사건에서 미종결이 없어야 한다**. REVIEW_REQUIRED는 실제 독립 판단과 별도 서명 종결 이후 다른 조건까지 모두 만족할 때만 통과한다. 내부 보고 검토 회부는 종결이 아니다.

125의 최대 불변 시작 순번에서 고른 최신 대사는 MATCHED이고 2분 이내여야 한다. 원장과 노드 증거의 실제 저장 바이트 SHA를 대조한 뒤 typed record로 읽어 고객/금액/주소/스크립트/체인 앵커/해당 출력까지 재검증한다. 같은 블록의 새 관찰은 새 개인정보 요청을 요구하지 않지만 블록 앵커가 달라지면 새 실제 대사가 필요하다.

현재 출처 검색/전송 머리의 DATA_RECEIVED·CONSISTENT를 실제 인증 암호문/HMAC로 검증한다. 현재 고객 선언/평가와 같아야 하며 현재 사업자 목록·허용 정책·법인명·유효기간도 확인한다. 현재 CDD 원문을 복호화하고 선언 MAC/신원 MAC와 이미 검증한 전송의 동일인 자료를 대조한다. 113의 고정된 문자열 비교 규약을 임의 완화하지 않는다.

조건 부족은 구체 HELD 이유로, 원본·현재 키·사업자 정책을 신뢰할 수 없으면503으로 차단한다. 원본을 바꾸거나 플랫폼/엔진 잔액을 추측해 보완하지 않는다. 성공 의도와 동일한 근거 지문의 필수 감사가 같은 TX에 없으면 커밋할 수 없다.

## 실제 코드와 문법

출처: [BitcoinCreditBasis.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditBasis.java#L35), 현재 소스의 부분 발췌.

```java
    public record Funds(UUID depositId,long customerId,String network,String wallet,String asset,String amount,String txid,int vout){@Override public String toString(){return "BitcoinCreditFunds[원본 생략]";}}
```

Java record는 원본 필드의 불변 값을 묶는다. 여기서는 엔진에 전달할 경제적 원본과 시점별 판단 근거를 분리한다. TypeScript readonly 타입은 보통 컴파일 시점의 제약이며, 이 record는 생성 후 필드를 다시 대입할 수 없다. 중첩 참조의 깊은 불변성을 자동 보장하는 것은 아니다. 금액은 정확한 십진 문자열이다. 디버그 출력은 원본을 생략한다.

출처: [BitcoinCreditIntents.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditIntents.java#L37), 현재 소스의 부분 발췌.

```java
            var customer=users.lockById(owners.getFirst()).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
            // 과거에 확정한 의도를 다시 읽을 뿐 현재 금융 조건을 다시 승인했다는 뜻이 아니다.
            // 향후 첫 전송은 같은 고객 잠금 아래 현재 조건을 별도로 재검증해야 한다.
            var old=jdbc.queryForList("SELECT receipt_id FROM bitcoin_credit_intents WHERE receipt_id=?",UUID.class,receipt);
            if(!old.isEmpty())return new Reserved(read(receipt),false);
            // 읽기·판단·의도 INSERT·필수 감사는 하나의 TX다. 감사 예외/누락이면 새 의도를 확정하지 않는다.
            // JSON을 만든 실제 UTF-8 바이트의 지문을 보존해 재조회 때 다른 본문을 같은 의도로 취급하지 않는다.
            var basis=policy.require(receipt,customer);String raw=json.writeValueAsString(basis),hash=BitcoinCreditBasis.digest(raw);var funds=basis.funds();
```

고객 행을 잠근 뒤 기존 의도를 먼저 조회한다. 재호출은 과거 메타데이터를 반환하며 새 금융 허가가 아니다. 처음 생성할 때만 현재 정책을 검증한다. 이후 최초 전송 역시 별도 현재 검증이 필요하다. DB 트랜잭션 밖에서 HTTP를 호출할 다음 단계와 구분한다.

출처: [BitcoinCreditBasis.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCreditBasis.java#L96), 현재 소스의 부분 발췌.

```java
        if(risk.assessmentId()==null||risk.newerInputsPending()||!SmallDepositMonitoringPolicy.POLICY.equals(risk.policy()))throw held(Reason.MONITORING_PENDING);
        if(!Set.of("NO_SIGNAL","REVIEW_REQUIRED").contains(risk.outcome())||risk.unknownTransfers()!=0)throw held(Reason.MONITORING_INCOMPLETE);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_cases WHERE user_id=? AND NOT monitoring_case_closed(id))",Boolean.class,customer.getId())))throw held(Reason.MONITORING_REVIEW_REQUIRED);
```

현재 입력 처리 범위와 불완전 평가를 먼저 검사한다. 사건 머리 하나가 아니라 전체 미종결 사건을 검사하므로 새 사건으로 머리가 옮겨도 옛 사건을 숨기지 않는다. Java Set.of와 equals는 허용 결과를 명시하며 문자열에 포함된 단어로 추측하지 않는다.

출처: [credit-schema.sql](../../src/main/resources/bitcoin/credit-schema.sql#L79), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER bitcoin_credit_intent_audit_check AFTER INSERT ON bitcoin_credit_intents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_intent_audit();
DROP TRIGGER IF EXISTS bitcoin_credit_intents_immutable ON bitcoin_credit_intents;
CREATE TRIGGER bitcoin_credit_intents_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credit_intents FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
```

DEFERRABLE INITIALLY DEFERRED 제약 트리거는 TX 끝에서 의도에 대응하는 필수 감사를 검사한다. 감사 메서드가 예외를 내는 경우뿐 아니라 조용히 호출을 생략한 경우도 커밋을 막는다. 불변 트리거는 수정/삭제와 TRUNCATE를 차단한다. 운영 DBA/DDL 권한 통제까지 애플리케이션 코드로 충족했다고 주장하지 않는다.

## 작성한 테스트와 기대 결과

[BitcoinCreditIntentIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditIntentIntegrationTest.java)의 각 테스트는 소유 PostgreSQL16 컨테이너 안의 **별도 DB**에서 실행한다. 실제 CDD 암호화·출처 자료 결합·보류 원장·탐지·사건/서명 종결·대사를 사용한다. 노드와 외부 공급사는 대역이며 이 시험이 실제 공급사 연동을 검증한 것은 아니다. 모든 테스트 뒤 엔진/가격 재호출과 AVAILABLE 원장 증가가 없음을 확인한다.

| 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `verifiedNormalRouteRecordsOneIntentWithoutPublishingFunds` | 정상 VASP 경로는 정확한 원본의 의도1개와 필수 감사1개만 생성 |
| `sameReceiptKeepsOneIntentAndOriginalBasis` | 같은 입금 재호출은 생성false와 같은 근거/원본 반환 |
| `concurrentReservationsHaveOneCreator` | 두 동시 예약 중 생성자는 정확히 하나 |
| `basisAndAuditDoNotCopyPrivateIdentityOrAddress` | 저장 근거·감사·디버그에 이름/생일/이메일/주소/스크립트 원문을 복제하지 않음 |
| `disabledCustomerCannotCreateAnIntent` | 비활성 고객 거부 |
| `restrictedCustomerCannotCreateAnIntent` | 거래 제한 고객 거부 |
| `expiredKycFreshnessCannotAuthorizeMoney` | 15분을 지난 KYC 확인 거부 |
| `futureKycFreshnessCannotAuthorizeMoneyOrOrders` | 미래 KYC 확인은 입금과 신규 주문 모두 거부 |
| `newUnverifiedDeclarationCannotReuseTheOldCustomerPermission` | 새 미검증 고객 선언은 옛 자격 사용 불가 |
| `newlyVerifiedDeclarationCannotBorrowOldOriginData` | 새 검증 선언도 옛 출처 전송의 선언/평가를 빌릴 수 없음 |
| `staleChainObservationCannotBorrowOldConfirmation` | 오래된 체인 관찰은 보류 |
| `refreshedObservationOfTheSameBlockDoesNotRequireNewPrivateData` | 같은 블록의 새 관찰은 추가 개인정보 요청 없이 허용 |
| `changedBlockAnchorRequiresNewIndependentReconciliation` | 블록 앵커 변경은 보류하고 새 실제 대사 뒤 허용 |
| `latestRunningReconciliationMasksEarlierSuccess` | 최신 RUNNING은 옛 대사 성공을 가림 |
| `latestFailedReconciliationMasksEarlierSuccess` | 최신 FAILED는 옛 대사 성공을 가림 |
| `pendingMonitoringInputCannotBorrowEarlierNoSignal` | 새 미처리 탐지 입력은 옛 NO_SIGNAL 사용 차단 |
| `missingOriginDataIsNotWaivedForASmallReceipt` | 소액 입금도 출처 자료가 없으면 면제하지 않음 |
| `unavailableFrozenValuationDoesNotUseAnOldPrice` | 실패한 동결 평가는 과거 가격으로 대체하지 않음 |
| `anotherUnvaluedInputKeepsAccountMonitoringIncomplete` | 다른 미평가 입력이 있으면 계정 탐지 불완전으로 보류 |
| `openHistoricalMonitoringCaseBlocksNormalOrigin` | 열린 기존 조사 사건은 정상 출처여도 보류 |
| `independentlyClosedExplainedCaseAllowsFreshPolicyEvaluation` | 실제 독립 판단과 별도 MFA 종결 뒤 현재 정책으로 허용 |
| `closingCaseDoesNotOverrideCurrentCounterpartyPolicy` | 사건 종결도 현재 상대 사업자 거부를 덮지 않음 |
| `removedCounterpartyCannotUseOldReceivedData` | 허용 목록에서 제거된 사업자는 옛 응답을 사용하지 못함 |
| `changedLegalCounterpartyCannotUseOldData` | 현재 법인명이 다른 사업자 정책은 거부 |
| `failedCurrentDirectoryCannotFallBackToOldList` | 최신 사업자 목록 실패는 옛 목록으로 우회하지 않음 |
| `modifiedDeclarationMacIsRejectedWithoutTouchingOriginal` | 조회 대역의 선언 MAC 손상은 거부하고 실제 원본은 보존 |
| `unsupportedKeyEpochCannotUseAnotherKey` | 현재와 다른 키로 옛 자료를 신뢰하지 않음 |
| `changedReconciliationHashCannotBeTrusted` | 대사 증거 SHA 손상 거부 |
| `newPublicHashCannotHideAnotherNodeOutput` | 노드 출력을 바꾸고 새 SHA를 붙여도 원본 출력 불일치로 거부 |
| `intentAuditExceptionRollsBackOnlyTheNewIntent` | 의도 감사 예외는 새 의도만 롤백하고 옛 출처 결과 보존 |
| `silentlyOmittedIntentAuditCannotCommit` | 조용한 감사 누락도 DB 커밋 거부 |
| `forgedAmountAndAssessmentCannotPassWithANewHash` | 금액/평가 ID 위조와 재해시도 DB 원본 결합에서 거부 |
| `immutableIntentCannotBeRewrittenDeletedOrCleared` | 의도 UPDATE/DELETE/TRUNCATE 거부 |
| `historicalIntentIsNotFreshFinancialPermission` | 기존 의도 메타데이터 재조회가 현재 제한을 해제하지 않음 |
| `nullMissingAndAmbientTransactionsNeverCreateAnIntent` | null/미존재/이미 열린 TX와 필수 TX 없는 정책 호출 거부 |

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew compileTestJava --console=plain
/usr/bin/caffeinate -i python3 scripts/verify_bitcoin_credit_intents.py
/usr/bin/caffeinate -i python3 scripts/verify_engine_inbox.py
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 /usr/bin/caffeinate -i python3 scripts/verify_bitcoin.py
```

2026-10-26 최종 신규35개·기존 금융182개·실제 Bitcoin Core31.1 회귀231개를 실행했다. 기존 OrderLedgerTest18개가 두 회귀 묶음에 겹치므로 전체는 **고유430개/448회**다. 각 묶음의 고유 함수/매개변수 식별자를 확인하고 실패·오류·제외0을 확인했다. 신규 의도 시험은 실제 PostgreSQL에서 금융 근거 결합과 원장 무변경을 검증하며 실제 Core 회귀는 기존 체인/주소/재조직/보류/대사의 기반 경로를 검증한다. 두 범위를 합쳐 실제 외부 VASP부터 엔진/Kafka까지 입금 완료를 검증했다고 주장하지 않는다.

초기 기존 금융182개 실행은 정산 기록 전용 H2 컨텍스트 두 곳에서 새 PG 전용 스키마 초기화를 대체하지 않아9개가 실패했다. 해당 두 fixture에만 기존 방식의 초기화 대역을 추가하고182개를 다시 실행했다. 제품의 PG 제약이나 실제 PG 입금 시험을 약화하지 않았다. 실패 로그/결과는 `.local/cycle126-intermediate/finance-h2-initializer`에 보존한다. 초기 단일 정상 시험의1통과는 최종35개에 중복 합산하지 않는다. 테스트 확장 과정의 타입/호출 체인 컴파일 오류는 수정 후 compileTestJava와 최종35개를 다시 실행했다. 빌드의 기존 deprecated/unchecked/JVM CDS 경고는 남는다. 최종 의미 변경 없는 학습용 주석 보강 뒤 compileTestJava·웹/운영 초기화 CLI JAR를 다시 빌드했고 구현 클래스/SQL 포함·테스트 클래스 제외를 확인했다. 소유 컨테이너·Core 프로세스는 각 실행 후 제거/종료하며 기존5개 컨테이너와 사용자 DB를 시험에 사용하지 않는다. 봇0·외부 소비자/자동 전송 비활성 조건을 유지한다.

## 현재 한계와 다음 연결

이번 단계는 내부 서비스의 영속 의도 생성까지다. 새로운 공개 HTTP 승인 경로·자동 실행 워커·엔진 호출·Kafka 적용·AVAILABLE 전환을 만들지 않았다. 고객 정상 화면이나 운영 완료로 표시하지 않는다. 다음 단계에서 최초 전송 전 현재 근거 재검증, 영속 시도 원본, 같은 입금 ID 재확인, 엔진 확정 이벤트와 플랫폼 원장/잔액의 원자적 반영을 연결한다.

사업자 실제 계약/실사·외부 공급사 검증, 비VASP/소액/개인지갑·강화 고객의 별도 처리, 실제 AML 보고 후속, 코드 밖 운영 권한/비밀 관리·모니터링·용량 검증은 별도 미완료다. 코어의 단일 스레드·공유 락 제거·WAL 확정 계약은 변경하지 않았다. 디스크/호스트 손실의 외부 복제·백업은 사용자 지시대로 문서 조건만 유지한다.


## 관련 커밋과 증적

- [구현 9ce1b4c](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9ce1b4c53db6fb0106ed7f9bd61bda5054b4924c)
- [격리 실행 증적](../reference/126-verified-vasp-bitcoin-credit-intents-verification-2026-10-26.json)
