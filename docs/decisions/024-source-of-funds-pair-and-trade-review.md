# 자금 발생·수취 증빙을 묶어 독립 검토하고 확정 체결과 연결하기

자금 원천을 신고한 것과 그 설명을 자료로 대조한 것은 다르다. 이번 단계는 **발생 자료 한 건과 수취 자료 한 건의 관찰 내용**을 현재 고객 신고와 대조하고, 별도 인물이 재입력·서명한 검토 이력을 보존한다. 기존 체결을 조사할 때는 해당 고객의 확정 원장 순지출액도 연결한다. 자금의 적법성·문서 진위·실제 외부 입금·EDD 해소를 자동 확정하지 않는다.

대상 독자는 TypeScript 백엔드를 주로 사용하는 개발자다. Java 문법 전체보다 업무의 근거·트랜잭션·실패 조건을 설명한다. 기준일은 2026-10-23이며 아래 코드는 현재 소스의 **부분 발췌**다.

## 기존 제품과 공식 근거

- [Kraken 자금 원천·재산 증빙 안내](https://support.kraken.com/articles/source-of-wealth-or-source-of-funds-documents), 표시 갱신일 2025-03-31: 특정 거래의 자금 원천과 전체 재산 형성을 구분한다. 발생 자료가 수취를 보여주지 못하면 수취 자료를 추가하며, 이미 입금한 자금에는 거래소로의 이전 근거도 요구한다. 한 문서가 여러 사실을 보여주는 경우도 있다. 이번 단계의 두 자료 관찰은 이 중 일부이며 거래소 입금 검증은 미완료다.
- [Coinbase 계정 검토 안내](https://help.coinbase.com/en/coinbase/managing-my-account/other/account-review): 계정 검토 중 추가 자료를 요청하는 공개 고객 절차를 참고했다. 공개 안내만으로 내부 승인 구조를 추정하지 않는다.
- [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 조회 화면 시행 2026-10-02·법률 제21857호: 위험에 따른 거래 목적·자금 원천 확인, 확인 불가 시 거절/종료 및 의심거래 보고 검토가 관련된다. 코드의 관찰 일치만으로 이 의무 전체를 충족하지 않는다. 하위 규정·사업 범위·보고 절차·법률 검토와 실제 운영 증적은 별도다.
- [AUSTRAC 자금 원천·재산 가이드](https://www.austrac.gov.au/industry-and-business/obligations-and-guidance/your-amlctf-program/customer-due-diligence/source-funds-and-source-wealth), 표시 갱신일 2026-10-13: 송금 계좌 자체와 돈을 얻은 경제적 원인을 구분하며, 고객 설명과 신뢰할 수 있는 독립 근거를 위험에 따라 대조한다. **호주 해석 자료를 설계 참고로 사용하며 국내 의무로 표시하지 않는다.**

- [FINRA Regulatory Notice 19-18](https://www.finra.org/rules-guidance/notices/19-18), 발행 2019-05-06: **미국 증권사 대상의 과거 모니터링·보고 안내**를 완료 전 재대조했다. 고객 설명과 자료의 불일치, 자금 원천 설명 거부 등을 추가 조사 신호로 보되 신호 자체를 범죄의 증거로 단정하지 않는다. 현재 국내 법적 의무나 최신 신고 기준으로 표시하지 않으며, 별도 조사/보고 업무는 아직 구현하지 않았다.

이 자료의 공개 원칙을 채택했지만 공급사 접속·은행 확인·규제기관 심사를 수행한 것은 아니다. 기존 접근 통제·ISMS/ISMS-P·신고·보안키·감사 요구사항은 [공통 준법 대조표](../reference/access-control-compliance.md)에 연결한다. 이번에 모든 법령·인증·증권 규정을 새로 전수 확인한 것은 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·한계 및 대안 |
| --- | --- | --- |
| 발생 자료 1 + 수취 자료 1 | 근거와 실제 수취 설명을 분리하고 두 제공 이력을 명확히 고정 | 한 문서로 둘 다 설명하는 경우도 두 별도 자료가 필요하다. 다중 자료 조합·분할 수취·세금·수수료·환전 모델은 이번에 넣지 않는다 |
| 명시적 ONBOARDING / RECORDED_TRADE | 거래 전 자료 수집과 과거 체결 조사를 구분 | ONBOARDING은 자금 유입이나 거래 허용을 뜻하지 않는다. 체결이 없거나 타인 거래면 오류이며 자동 전환하지 않는다 |
| 불일치를 지우지 않는 사유 목록 | 이름·지급인·금액·통화·기간 차이를 독립 검토 후에도 추적 | 차이 자체는 불법·위조 판정이 아니다. 발급기관 확인·추가 설명·준법 판단은 별도다 |
| 확정 원장의 순지출 | 예약 반환을 지출로 중복 계산하지 않는다 | 외부 입금, 같은 수취액의 여러 거래 배정, 당일 시간 순서까지 증명하지 않는다 |
| 기존 CDD 지문 키·증빙 암호 키 재사용 | 검증된 AES-GCM/HMAC 구현과 키 실패 정책을 유지, 의존성 추가 없음 | 실제 운영 KMS·키 교체·백업·권한 분리·보존/파기는 아직 필요하다 |
| 주소/자금의 공통 접근 검증만 추출 | 실제 두 업무가 공유하는 현재 평가·제공 영수증 검사를 한곳에서 유지 | 금액 비교·업무 상태는 별도 서비스다. 동적 규칙 엔진이나 설정 계층은 추가하지 않는다 |

재산 형성(SoW)은 여러 해의 경제 활동·자산·부채·축적 경위를 설명해야 한다. 단일 자금 수취 양식으로 대신하지 않고 다음 단계로 남긴다. SOURCE_OF_WEALTH 자료를 이 업무에 보내면 거절한다.

## 업무 흐름과 보장 범위

1. OPERATOR가 현재 평가에 SOURCE_OF_FUNDS 자료를 요청한다. 고객이 첫 자료를 제출한 뒤 같은 목적의 다음 자료를 요청할 수 있다. 두 제출은 각각 사건 변경 번호를 높인다.
2. 두 자료가 제출된 **후** 담당자가 두 열람본을 제공받는다. 최근 5분의 MFA와 자료별 열람 영수증, 인물·사용자 버전·세션·현재 평가·사건 변경 번호가 모두 필요하다.
3. 이름·현재 신고 자금 원천만 조회한다. 주소·생일·직업·원문 신원 번호를 추가로 반환하지 않는다.
4. 발생 유형/명의/지급인/발급기관/기간/발급일/순수취액/통화와 수취 명의/송금인/기관/참조/수취일/발급일/금액/통화를 정규화해 관찰한다. 관찰 참조는 외부기관 확인 번호가 아니다.
5. 선택한 본인 체결이 있으면 실제 수신 키·체결 투영·TRADE 원장을 함께 조회한다. 원장의 해당 고객 자산별 순변화를 지출액으로 사용한다.
6. 관찰값은 암호화하고, 자료 쌍·현재 평가·변경 번호·내용 지문·사유·작성자 인물/버전·10분 기한을 승인 내용 해시에 고정한다. 같은 사건/변경 번호의 미결 검토는 하나, 고객당 하루 10개다.
7. 별도 APPROVER가 **자신의 두 열람 영수증**으로 현재 신고/관찰을 확인하고 관찰값을 재입력한다. 기록과 일치해야 승인 옵션이 발급된다. 거절은 관찰값을 새로 꾸미지 않고 null로 보낸다.
8. EDD_FUNDS_REVIEW 전용 보안키 서명으로 결정한다. 요청 ID·내용 해시·승인/거절·세션에 결합된다. 마지막에 현재 자료/평가/원장 연결/사유를 다시 확인한다.
9. 결정·사건 변경·서명 소비·보안키 카운터·필수 감사가 한 트랜잭션에 확정된다. 실패하면 함께 취소한다. 승인/거절 모두 사건 OPEN·valid_until=NULL이며 거래 제한은 유지한다.

두 증빙 ID가 같거나 원본 해시가 같으면 자료 쌍으로 받지 않는다. 이 검사는 동일 파일 재제출을 막는 정도이며 이미지 변경·캡처·문서 위조를 탐지하는 기능은 아니다. 사람이 실제 읽었는지도 자동 증명하지 않는다.

## 실제 구현과 필요한 Java 문법

### 1. 순수 관찰값 비교

출처: [FundsFacts.java](../../src/main/java/dustin/cex/domains/edd/FundsFacts.java#L37)

```java
if(!origin.holderName().equals(customer)) reasons.add(Reason.ORIGIN_HOLDER_MISMATCH);
if(!receipt.holderName().equals(customer)) reasons.add(Reason.RECEIPT_HOLDER_MISMATCH);
if(origin.source()!=declared) reasons.add(Reason.DECLARED_SOURCE_DIFFERENCE);
if(origin.source()==CustomerDeclaration.SourceOfFunds.OTHER) reasons.add(Reason.ADDITIONAL_SOURCE_DETAIL_REQUIRED);
if(!origin.payerName().equals(receipt.senderName())) reasons.add(Reason.PAYER_SENDER_MISMATCH);
if(!origin.currency().equals(receipt.currency())) reasons.add(Reason.CURRENCY_MISMATCH);
else if(origin.netAmount().compareTo(receipt.amount())!=0) reasons.add(Reason.NET_RECEIPT_AMOUNT_MISMATCH);
```

`record`는 관찰값을 묶는 불변 값 타입이고 중첩 record는 발생/수취 항목을 분리한다. TypeScript의 타입 선언과 달리 생성자·접근자·동등성·직렬화할 실제 런타임 타입이 있다. 금액은 `number`나 `double` 대신 `BigDecimal`로 읽는다. `compareTo`는 소수점 표기 차이와 무관하게 수치가 같은지 비교한다. 입력은 기존 `ExactAmount`의 정밀도·범위 검사를 통과해야 하며 반올림으로 맞추지 않는다. ISO 4217 코드와 현재 지원 자산 BTC/USDT를 사용한다.

기간·수취일·발급일의 미래/역전, 신고 원천 차이, 통화/순수취액 차이는 명시적 사유가 된다. 통화가 다르면 환산해 일치시킬 수 없다. 지정 날짜는 1900~2100 범위이며 모든 날짜를 요구한다. 고객명을 다른 표기법으로 자동 추정하거나 유사도 점수로 승인하지 않는다.

### 2. 원장의 실제 순지출

출처: [FundsTradeLink.java](../../src/main/java/dustin/cex/domains/edd/FundsTradeLink.java#L39)

```java
var entries=jdbc.queryForList("SELECT asset,bucket,delta FROM ledger_entries WHERE operation_key=? AND owner_id=? ORDER BY asset,bucket",operation,user);
var totals=new TreeMap<String,BigDecimal>();for(var e:entries) totals.merge((String)e.get("asset"),(BigDecimal)e.get("delta"),BigDecimal::add);
var spent=totals.entrySet().stream().filter(e->e.getValue().signum()<0).toList();
if(spent.size()!=1 || totals.values().stream().filter(v->v.signum()>0).count()!=1) throw denied();
// 반환된 예약분을 별도 지출로 세지 않고 같은 자산의 AVAILABLE+LOCKED 순변화를 사용한다.
String digest=verifier.digest(json.valueToTree(entries));
return new Link(target.eventId(),operation,spent.getFirst().getKey(),spent.getFirst().getValue().negate().stripTrailingZeros(),((Timestamp)row.get("created_at")).toInstant(),(String)row.get("payload_hash"),digest);
```

`Map.merge`는 같은 자산의 AVAILABLE/LOCKED 변화를 더한다. 지정가 매수 예약이 50이고 반환이 5이면 순변화는 -45다. 사용한 예약금 50을 지출로 표시하지 않는다. 순지출 자산 하나와 순수취 자산 하나가 있어야 한다. SELECT는 불변 원장·안정적인 이벤트 ID를 사용하며, 최초 반입·주문 예약·취소를 자금 원천으로 취급하지 않는다.

`Link`의 간결 생성자는 금액을 `stripTrailingZeros()`로 정규화한다. Java record의 자동 동등성은 구성 요소의 `equals`를 사용하고 `BigDecimal.equals`는 소수점 자리까지 비교하기 때문이다. JSON을 거치며 일반 표기/지수 표기가 달라져도 동일 금액의 내부 체결 근거를 다르게 판단하지 않도록 생성과 역직렬화 양쪽에서 정규화한다.

`recordedAt`은 **플랫폼 원장 반영 시각**이다. 외부 은행의 확정 시각이나 엔진의 체결 시각으로 바꿔 설명하지 않는다. 수취일과 서울 날짜로만 대조하므로 같은 날짜 안의 선후·재발행 지연은 검증하지 못한다. 통화가 다르면 `TRADE_CURRENCY_CONVERSION_UNVERIFIED`, 금액이 작으면 `RECEIPT_AMOUNT_BELOW_TRADE`를 기록한다. 관찰된 수취액을 여러 거래에 배정해 소진시키는 회계 기능도 없다.

### 3. 두 열람본과 현재 평가의 결합

출처: [FundsReviewService.java](../../src/main/java/dustin/cex/domains/edd/FundsReviewService.java#L158)

```java
private void view(User actor,UUID sid,UUID origin,UUID receipt,Views views,ReviewEvidenceAccess.Snapshot current) {
    if(views==null || views.origin()==null || views.receipt()==null)throw denied(HttpStatus.BAD_REQUEST);
    access.view(actor,sid,origin,views.origin(),current.assessment(),current.revision());
    access.view(actor,sid,receipt,views.receipt(),current.assessment(),current.revision());
}
```

발생·수취 각각에 영수증이 필요하다. 주소 검토와 함께 사용하는 `ReviewEvidenceAccess`는 같은 업무 트랜잭션에서만 호출되며 사용자 잠금·최근 인증 이후 현재 평가/키/사건과 열람 영수증을 검사한다. 같은 사용자여도 옛 세션·다른 자료·옛 사건 변경 번호의 열람은 쓸 수 없다. 새 제출이나 신고가 있으면 두 자료를 새 기준으로 다시 열람한다.

### 4. 암호화 관찰과 불변 승인 근거

출처: [FundsReviewService.java](../../src/main/java/dustin/cex/domains/edd/FundsReviewService.java#L79)

```java
String hash=verifier.digest(json.valueToTree(p));byte[] encrypted=crypto.encrypt(factsContext(p),json.writeValueAsBytes(facts));
jdbc.update("INSERT INTO edd_funds_reviews(id,case_id,origin_evidence,receipt_evidence,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,current.caseId(),origin,receipt,user,actor,key,json.writeValueAsString(p),hash,encrypted,Timestamp.from(expires));
mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW,id,actor,maker.getVersion(),hash,expires);
```

AES-GCM의 추가 인증 문맥은 검토 ID·고객·두 자료·현재 평가·사건 변경 번호를 포함한다. 다른 검토에 암호문을 옮기면 인증 태그 확인부터 실패한다. HMAC 도메인도 고객/자료 쌍/평가에 결합한다. 이름·지급인·금액·수취 참조를 승인 내용의 평문 JSON에 넣지 않는다. 목록에는 자료/평가 ID·사유·해시·기한과 선택한 내부 체결의 순지출 근거가 보인다. 따라서 목록도 최근 인증한 권한 보유자에게만 제공한다.

### 5. 업무 결정의 원자성

출처: [FundsReviewService.java](../../src/main/java/dustin/cex/domains/edd/FundsReviewService.java#L102)

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW,id,hash,approve,proof);
var checker=users.findById(actor).orElseThrow();
jdbc.update("INSERT INTO edd_funds_decisions(review_id,approver_id,approver_person,origin_view,receipt_view,approved) VALUES(?,?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),views.origin(),views.receipt(),approve);
// 검토 결과도 새로운 사실이다. 오래된 사건 결론은 무효화하되 금융 위험을 해소한 것으로 취급하지 않는다.
jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",current.caseId());
audit.record(actor,"EDD_FUNDS_DECIDED","edd-funds-review:"+id,"SUCCESS",(approve?"APPROVED_CONTENT_REVIEW:":"REJECTED:")+hash);return read(id);
```

`TransactionTemplate.execute` 안의 DB 변경과 필수 감사는 함께 확정된다. Java에서 예외가 밖으로 나오면 서명 소비·카운터·사건·결정도 롤백한다. 고객/작성자/승인자 행은 ID 정렬 순서로 잠그고 사건 잠금 뒤 결정 상태를 다시 읽어 동시 승인/거절을 하나로 직렬화한다. 이는 플랫폼 금융·보안 업무 잠금이며 Rust 체결 코어에는 접근하지 않는다.

`APPROVED`는 **관찰 내용의 독립 확인**이다. 불일치 사유가 있는 관찰도 그 내용을 정확히 기록했다면 승인할 수 있으나, 불일치를 지우거나 자금 적법성을 확정하지 않는다. 모든 결과에 `DOCUMENT_OBSERVATIONS_ONLY_EXTERNAL_FUNDING_NOT_VERIFIED`라는 범위를 함께 반환한다. 기존 EDD의 증빙 보유 시 단순 신원 정정 종결 차단도 유지한다.

## API와 저장 경계

기본 경로는 `/api/admin/edd`이며 JSON API다. 기본 로그인 화면으로 전환하지 않는다.

| 메서드·경로 | 권한 | 목적 |
| --- | --- | --- |
| POST `/funds-reviews/context` | OPERATOR, APPROVER | 두 자료/본인 열람/target으로 최소 신고와 내부 체결 근거 읽기 |
| POST `/funds-reviews` | OPERATOR | Idempotency-Key와 현재 assessment/revision, 두 자료/열람/관찰/target으로 작성 |
| POST `/funds-reviews/{id}/inspect` | APPROVER | 본인의 두 열람으로 현재 관찰을 감사 열람 |
| POST `/funds-reviews/{id}/approve/options`, `/reject/options` | APPROVER | 내용 해시/두 열람/관찰과 결정 방향에 결합된 인증 옵션 |
| POST `/funds-reviews/{id}/approve`, `/reject` | APPROVER | 같은 관찰·해시·열람과 실제 서명으로 결정 |
| GET `/customers/{user}/funds-reviews?page=0` | OPERATOR, APPROVER, AUDITOR | 최근 인증한 목록, 페이지당 50·관찰 원문 제외 |

`edd_funds_reviews`와 `edd_funds_decisions`는 수정·삭제·TRUNCATE를 차단한다. 승인 종류는 기존 여섯 종류에 `EDD_FUNDS_REVIEW`를 추가한다. 옛 여섯 종류 제약이 있는 DB는 자동 변경하지 않고 기동을 거부한다. 이번에는 소유한 빈 테스트 DB에만 표를 만들었다. 기존 개발 DB·실제 금융 이력을 변환하거나 서비스를 재시작하지 않았다. 운영 전 이력을 보존하는 승인된 스키마 준비가 필요하다.

## 작성한 테스트

[FundsReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/FundsReviewIntegrationTest.java)와 [FundsFactsTest.java](../../src/test/java/dustin/cex/domains/kyc/FundsFactsTest.java)에 작성했다. 아래 함수는 실제 작성한 검증이며 매개변수 검증은 여러 사례로 실행된다. 전체 개별 사례·결과는 [확정 증적](../reference/funds-review-verification-2026-10-23.json)에 보존한다.

### FundsReviewIntegrationTest.java

- `minimalContextExcludesAddressBirthdayAndWealth`: 최소 신고 문맥을 조회하면 주소·생일·재산 항목이 없어야 한다.
- `independentSignatureConfirmsContentButNeverReleasesTrading`: 두 사람의 관찰·실제 서명이 맞으면 내용 검토만 승인하고 거래 차단을 유지한다.
- `mismatchReasonsSurviveApproval`: 명의·원천이 다른 관찰을 독립 확인해도 불일치 사유를 그대로 보존한다.
- `factsReferenceAndNamesAreEncryptedAndAuditedInspectionOnly`: 평문 승인 내용에 이름·참조를 남기지 않고 별도 감사 열람으로만 관찰을 반환한다.
- `singleEvidenceCannotPlayBothRoles`: 같은 증빙 ID를 두 역할에 사용하면 거절한다.
- `duplicateOriginalCannotBecomeIndependentSecondDocument`: 같은 원본 파일을 두 요청에 제출해도 독립된 자료 쌍으로 받지 않는다.
- `differentEvidencePurposeCannotSubstituteForFunds`: 재산·주소 목적 자료를 자금 원천 자료로 대체하면 거절한다.
- `pairViewsCannotBeSwappedOrBorrowed`: 두 자료의 영수증을 바꾸거나 타인 영수증을 사용하면 거절한다.
- `missingIndependentObservationCreatesNoChallenge`: 독립 관찰을 생략한 승인은 인증 옵션을 만들지 않는다.
- `changedObservationCannotConsumeSignedChallenge`: 서명 후 관찰을 바꾸면 인증을 소비하지 않고 원래 관찰로 재시도할 수 있다.
- `rejectionRequiresNoInventedObservationAndKeepsHold`: 거절에 임의 관찰을 보내면 오류이며 관찰 없는 거절은 차단을 유지한다.
- `requestKeyBindsBothDocumentsViewsTargetAndFacts`: 요청 키 재사용은 같은 내용만 반환하고 자료·열람·대상·관찰 변경을 거절한다.
- `newDeclarationInvalidatesOldPairAndSignature`: 새 신고는 옛 자료 쌍과 서명을 무효화한다.
- `newUploadInvalidatesBothOldViewsAndSignature`: 새 자료 제출은 두 옛 열람과 진행 중 서명을 무효화한다.
- `customerKycChangeInvalidatesReview`: 현재 KYC 요약이 바뀌면 옛 검토를 결정하지 못한다.
- `requesterVersionChangeInvalidatesPendingReview`: 작성자 비활성화·버전 변경은 진행 중 검토를 무효화한다.
- `samePersonDifferentAccountCannotInspect`: 동일 인물의 다른 계정은 독립 승인자로 열람하지 못한다.
- `staleMfaAndRevokedSessionCannotReadOrDecide`: 최근 MFA가 지났거나 세션이 회수되면 열람·결정하지 못한다.
- `proposalAuditFailureRollsBackReviewCipherAndBinding`: 작성 감사 실패는 암호문·승인 결합·검토 저장을 모두 취소한다.
- `privateReadAuditFailureReturnsNoPersonalData`: 문맥·관찰 열람 감사가 실패하면 개인 내용을 반환하지 않는다.
- `decisionAuditFailureRollsBackProofCounterRevisionAndConclusion`: 결정 감사 실패는 사건·서명 소비·카운터·결정을 취소하고 같은 서명 재시도를 허용한다.
- `directionReplayAndDifferentHashCannotConsumeProof`: 다른 방향·해시·재실행에는 같은 서명을 사용할 수 없다.
- `otherNamespaceCannotConsumeFundsSignature`: 자금 검토 서명은 EDD 사건·주소 업무에서 소비하지 못한다.
- `caseSignatureCannotAuthorizeFundsOrBeConsumedThere`: EDD 사건 서명은 자금 업무에서 소비하지 못하며 원래 사건에서 정상 사용한다.
- `invalidOriginOrMissingUserVerificationKeepsProofUnused`: 잘못된 출처 또는 UV 없는 실제 서명은 실패하고 인증을 미소비 상태로 둔다.
- `concurrentProposalKeysProduceOnePendingReport`: 같은/다른 요청 키가 경합해도 미결 보고서는 하나다.
- `simultaneousApproveRejectCommitOneDecision`: 동시 승인·거절은 하나의 결론만 확정한다.
- `immutableReportsAndDecisionsRejectMutation`: 보고서·결정의 수정·삭제·TRUNCATE는 DB에서 차단한다.
- `auditorGetsMetadataOnlyAndCustomerHasNoAdminAccess`: 감사자는 원문 없는 목록만 읽고 고객은 관리 API에 접근하지 못한다.
- `completeHttpPairReviewUsesOwnViewsAndRealSignature`: HTTP 작성→옵션→실제 서명→승인이 완료돼도 거래는 계속 차단한다.
- `previousSixKindSchemaFailsWithoutChangingHistory`: 옛 여섯 종류 승인 제약에서는 기동 SQL이 실패하고 기존 이력은 바뀌지 않는다.
- `actualConsumerLedgerLinksNetSpendAndIndependentApproval`: 실제 소비자·수신 키·원장으로 매수 순지출 45와 매도 BTC 0.5를 연결하고 독립 승인한다.
- `unknownOtherCustomerAndOpeningAreNeverTradeFallbacks`: 타인·미존재·최초 반입은 체결 연결로 쓰지 않고 우회하지 않는다.
- `changedTradeProjectionCannotUseOldBoundSignature`: 연결한 체결의 소유 투영이 바뀌면 옛 서명으로 확정하거나 인증을 소비하지 못한다.
- `expiredSecondViewPreventsPairContextDespiteFreshFirstView`: 두 번째 열람만 만료돼도 전체 자료 쌍 문맥을 반환하지 않는다.
- `tradeLinkSerializationPreservesNumericIdentity`: 일반/지수·최소 단위·큰 금액의 JSON 왕복 후 같은 연결 근거로 비교한다.
- `standardCddStillCannotBypassEvidenceHoldAfterFundsApproval`: 현재 STANDARD라도 자료 검토 승인으로 증빙 보류·거래 차단을 우회하지 못한다.

### FundsFactsTest.java

- `normalizedValuesRetainExactAmountAndHidePrivateString`: 공백·대소문자·정확한 금액을 정규화하고 문자열에서 개인정보를 제외한다.
- `nameSenderCurrencyAndAmountDifferencesRemainDistinct`: 명의·지급인·통화·금액 차이를 별개 사유로 남긴다.
- `invalidOrInexactAmountIsRejected`: 0·음수·과도한 소수·범위 초과는 입력 오류다.
- `unknownCurrencyIsRejected`: 알 수 없는 통화·공백/기호/잘못된 길이는 입력 오류다.
- `receiptRequiresNamesReferenceAndExactAmount`: 수취 참조 형식과 필수 항목 누락을 거절한다.
- `futureAndReversedChronologyRemainReasons`: 미래 발급·기간·수취/발급 역전은 사유를 보존한다.
- `documentAndReceiptBeforePeriodAreNotSilentlyAccepted`: 발생 기간 종료 전 문서 발급·수취는 사유를 보존한다.
- `invalidPeriodAndMissingDateAreRejected`: 뒤집힌 기간이나 빠진 날짜는 입력 오류다.
- `tradeComparisonDoesNotInventExchangeRateOrAllocateFunds`: 체결 통화가 다르면 환율을 추정하지 않고 금액 부족은 별도 사유로 남긴다.
- `recordedLedgerTimeIsComparedAsDateOnly`: 원장 반영 시각의 서울 날짜보다 늦은 수취일에 사유를 기록한다.
- `targetMustBeExplicitAndCannotFallBackFromMissingTrade`: 조사 범위를 명시하고 없는 체결을 가입 검토로 우회하지 못한다.

업무 검증은 다음 결과를 요구한다. 정상 서명은 관찰 결론 하나만 남기고 차단을 유지한다. 자료/관찰/해시/목적/세션/평가가 다르면 결정을 만들거나 인증을 소비하지 않는다. 감사 실패는 원문을 반환하지 않으며 업무 변경을 롤백한다. 같은 요청 키는 동일 내용을 재반환하고 다른 내용에는 재사용할 수 없다. 동시 요청은 하나의 미결 검토·하나의 결정만 남긴다. 실제 소비자로 확정한 매수/매도 원장은 각자의 순지출과 정확히 연결한다. 알 수 없는 체결·타인 체결·최초 반입은 오류이며 자동 우회하지 않는다.

순수 사실 검증은 정확한 소수·이름 정규화·비공개 문자열·통화·기간·수취 시점과 체결 연결 사유를 확인한다. 부정확하거나 범위 밖인 금액은 입력 오류이며 금융 원장처럼 소수점 9자리까지 정확하게 표현되는 계약을 사용한다.

### 실제 실패 주입 코드

출처: [FundsReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/FundsReviewIntegrationTest.java#L94)

```java
@Test void decisionAuditFailureRollsBackProofCounterRevisionAndConclusion() throws Exception {
    var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
    long before=service.status(customer.getId()).revision();
    // 최종 감사 저장에서 실패시킨다. 이미 검증한 서명도 이 거래와 함께 취소돼야 한다.
    doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_FUNDS_DECIDED"),anyString(),anyString(),anyString());
    assertThrows(IllegalStateException.class,()->decide(r,a,true,p.facts()));
    assertEquals(before,service.status(customer.getId()).revision());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_funds_decisions WHERE review_id=?",Long.class,r.payload().id()));
    assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));
    // 같은 실제 서명을 다시 사용해 정상 확정한다. 인증 소비가 남았다면 이 재시도는 실패한다.
    reset(audit);assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
}
@Test void directionReplayAndDifferentHashCannotConsumeProof() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());assertThrows(ResponseStatusException.class,()->decide(r,a,false,null));assertThrows(ResponseStatusException.class,()->reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),"0".repeat(64),a.views(),true,p.facts(),a.proof()));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());assertThrows(ResponseStatusException.class,()->decide(r,a,true,p.facts()));}
@Test void otherNamespaceCannotConsumeFundsSignature() throws Exception {var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());for(var kind:List.of(OperatorMfaService.ApprovalKind.EDD_REVIEW,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW))assertThrows(ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->mfa.authorizeApproval(checker.user().getId(),checker.sid(),kind,r.payload().id(),r.payloadHash(),true,a.proof())));assertEquals("APPROVED",decide(r,a,true,p.facts()).status());}
```

감사를 실패시키는 대역은 실패 주입에만 사용한다. 실제 PostgreSQL 트랜잭션과 WebAuthn 서명 검증을 거친 뒤 같은 서명을 재사용해 정상 확정할 수 있는지까지 확인한다. 서명 검증 자체를 참으로 대체한 테스트가 아니다. 유료 Didit 세션 생성 HTTP와 외부 엔진 호출/송신 등 기존 EDD fixture의 대역 범위는 그대로이며, 연결 체결은 실제 플랫폼 소비자·수신 키·원장 구현을 호출한다. 이 신규 검증에서 Rust 프로세스나 Kafka 브로커를 새로 거치는 것은 아니다. 실제 전체 시장 연결은 기존 별도 회귀로 실행한다.

## 실행 명령과 확정 결과

아래 명령을 Java 21·실행 소유 PostgreSQL 16/Kafka에서 실행했다. 기존 개발 DB/브로커에는 연결하지 않고 임의 loopback 포트를 할당했다.

- `caffeinate -dims python3 scripts/verify_edd.py`
- `python3 scripts/verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`
- `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`
- `python3 scripts/verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`
- `python3 scripts/audit_dependencies.py`, `verify_dependency_integrity.py`

선택 JUnit **766개**, 실패·오류·제외 0개다. EDD/증빙/주소/자금 200개(신규 자금 62 = 통합 43 + 순수 비교 19), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개다. 기존 별도 프로세스 15개도 통과했다. 실행 소유 DB/브로커·임시 DB/체크섬 표본을 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성을 통과했다. 의존성 222개 버전의 조회 시점 알려진 OSV 검출은 0개다. 의도적으로 변조한 체크섬은 예상한 Gradle 종료 코드 1로 거부됐다. 이는 운영 OS/JDK/컨테이너 전체의 취약점 부재를 의미하지 않는다. 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/funds-review-verification-2026-10-23.json)에 각 실행 명령·분리 집계·실제 테스트 사례·자원 정리·85개 현재 코드 발췌 재대조 결과와 남은 한계를 보존했다.

기존 경고는 사용 중인 폐기 예정 API·일부 unchecked 변환·테스트 계측에 따른 JVM 클래스 공유 경고다. JMH는 컴파일이며 성능 수치를 새로 측정한 것이 아니다. 신규 자금 검토의 JVM 강제 종료 시나리오는 추가하지 않았다. 기존 프로세스 회귀와 자금 검토의 동시성/감사 롤백을 구분한다.

## 남은 범위와 다음 단계

- 은행/체인/수탁 입금의 독립 확인, 외부 이전과 내부 자산의 연결, 자료의 발급기관·위변조 검증, 환전 경로·분할 수취·다중 자료·자금 배정은 미완료다.
- 재산 형성, PEP·제재/AML 실체 검토, 법인/실소유자, 지속 거래 모니터링·STR 보고·관계 종료/잔여 자산 처리는 다음 업무다. 관찰 사유 일치나 승인으로 대신하지 않는다.
- PNG 한정 입력·원본/열람본 암호화·공급사/보존/국외이전/검사 한계는 [022](022-edd-evidence-storage-and-access.md)를 따른다. 실물 보안키·실제 고객/공급사 자료·KMS/DB 권한/외부 감사 보존·운영 복원·인증/신고는 검증하지 않았다.
- 체결 코어·WAL/Kafka 출력 설계는 변경하지 않았다. 봇은 0명이며 기존 로컬 서비스를 재시작하거나 배포하지 않았다.

## 관련 커밋

- [`b446621`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/b446621f25a1bfa5d9eb5bfb0fdb16065d557c6c): 현재 평가·열람 접근 검사의 공통 추출과 주소 회귀 유지
- [`8e43920`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8e43920193f30e58370079b27f6189f9c85608f0): 자금 발생·수취 관찰·원장 체결 연결과 독립 서명·신규 테스트
- [`cd1f61c`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/cd1f61c70ac1d09b7898cd1f8901e5bcd51e3d8c): 연결 금액의 JSON 표기 정규화와 경계 3사례
