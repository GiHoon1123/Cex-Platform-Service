# 선택 기간의 순재산 증감을 세 자료와 독립 서명으로 대조하기

시작·증감·종료 자료에서 읽은 자산과 부채, 기간 중 변화를 대조한다. **선택한 기간의 관찰과 계산이 일치하는지**를 검토하는 기능이다. 기초 재산의 원천·미신고 자산·전체 생애의 재산 형성·문서 진위·적법성까지 확인했다고 표시하지 않는다. 독립 승인 뒤에도 사건 OPEN·거래 차단을 유지한다.

기준일은 2026-10-23이다. TypeScript 백엔드 개발자가 업무 흐름·Java 코드·보장 범위·테스트를 설명할 수 있도록 작성했다. 아래 코드는 실제 소스의 부분 발췌이며 독립 실행 예제가 아니다.

## 문제와 기존 동작

[024](024-source-of-funds-pair-and-trade-review.md)는 특정 자금 발생/수취와 선택한 내부 체결의 순지출을 연결한다. 고객이 장기간 어떤 경제 활동으로 재산을 형성했는지까지 설명하지 못한다. 기존 CDD 신고에도 별도의 재산 형성 진술은 없다. 자금 원천 필드를 재산 신고로 이름만 바꾸거나 단일 수취액을 전체 재산으로 취급하지 않는다.

이번에는 재산 관찰 전용 타입·API·불변 이력·승인 종류를 추가했다. 자료 수집·암호화·현재 평가·열람 검증·표준 WebAuthn·원자적 감사는 이미 설치된 구현을 사용한다. 새 패키지·큐·워커·동적 규칙 엔진은 추가하지 않았다.

## 기존 제품과 공식 자료

모두 기준일에 다시 조회했다. 공개 고객 절차를 참고하며 제품 내부 승인 구현을 추정하지 않는다.

| 근거 | 확인한 원칙 | 이번 적용과 한계 |
| --- | --- | --- |
| [Kraken SoF/SoW 문서 안내](https://support.kraken.com/articles/source-of-wealth-or-source-of-funds-documents), 표시 갱신 2025-03-31 | 거래 자금의 원천과 시간에 걸친 전체 재산 형성을 구분 | 재산 관찰을 자금 수취와 별도 업무로 둔다. 세 자료의 계산만으로 전체 SoW 검증이 되지는 않는다 |
| [Coinbase 계정 검토 안내](https://help.coinbase.com/en/coinbase/managing-my-account/other/account-review) | 누적 금융 이력·일관된 기간·복수 자료를 통해 설명하고 부족하면 추가 요청 | 시작/증감/종료 자료를 현재 고객에게 묶는다. 공급사의 모든 입력 형식·심사 기준을 구현한 것은 아니다 |
| [AUSTRAC SoF/SoW 가이드](https://www.austrac.gov.au/industry-and-business/obligations-and-guidance/your-amlctf-program/customer-due-diligence/source-funds-and-source-wealth), 표시 갱신 2026-10-13 | 전체 재산의 형성 경위와 고객 설명·신뢰할 독립 근거를 위험에 맞게 대조 | 자료 관찰과 외부 진위/실체 판단을 분리한다. 호주 가이드를 국내 의무로 표시하지 않는다 |
| [SEC Investor.gov 순재산 설명](https://www.investor.gov/introduction-investing/investing-basics/save-and-invest/figure-out-your-finances) | 자산에서 부채를 빼며 음수 순재산도 가능 | 자산/부채를 각각 기록하고 음수를 0으로 바꾸지 않는다. 이 교육 자료는 AML 승인·가상자산 사업 인가 기준이 아니다 |
| [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 조회 화면 시행 2026-10-02·법률 제21857호 | 위험에 따른 목적·자금 원천 확인, 확인 불가 시 거절/종료 및 보고 검토 | 내용 승인만으로 고객확인·관계 종료·STR를 완료하지 않는다. 세 자료 양식과 12행 제한은 법정 요건이 아닌 제품 정책이다 |
| [FINRA Notice 19-18](https://www.finra.org/rules-guidance/notices/19-18), 발행 2019-05-06 | 고객 자료·설명 불일치를 추가 조사 신호로 다루며 신호 자체를 범죄로 단정하지 않음 | 미국 증권사 대상 과거 안내를 참고해 사유를 보존한다. 최신 국내 법적 의무로 표시하지 않는다 |

법령·하위 규정·사업 인허가·ISMS/ISMS-P의 전체 충족을 새로 전수 확인한 것은 아니다. 기존 요구사항·미완료 운영 조건은 [접근 통제·준법 대조표](../reference/access-control-compliance.md)에서 이어 관리한다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 비용·제약·대안 |
| --- | --- | --- |
| 시작/증감/종료의 세 자료 고정 | 현재 기준의 근거와 열람 대상을 명확히 고정 | 하나의 문서가 모든 사실을 담아도 세 개의 서로 다른 자료가 필요하다. 다수 기간/부속 문서·자료별 자산 명세 수집은 아직 지원하지 않는다 |
| 선택 기간과 같은 통화만 계산 | 환율·세금·취득 원가를 추정하는 오류를 줄임 | 통화가 다르면 모든 합계가 null이며 사유만 반환한다. 실제 시점별 환산 검증은 별도 업무다 |
| 자산·부채와 순증감 분리 | 대출·매각 총액을 재산 증가로 이중 계산하지 않음 | 표시된 순액과 분류의 진위는 사람이 추가 확인해야 한다. 순수익/평가액으로 중복 분류한 서로 다른 참조를 자동 탐지하지 못한다 |
| 증감 0~12행·현재 증빙 한도 유지 | 제한된 자료와 계산을 검토할 수 있고 무제한 입력을 막음 | 사건 전체 생애를 담는 모델은 아니다. 기존 하루 요청 5개·열람 20회/5분·고객 보관 한도를 그대로 따른다 |
| 업무별 타입과 승인 종류 분리 | 자금·주소 서명을 재산 검토에 재사용하지 못함 | 상태 흐름이 닮아도 업무 입력·정책·암호 문맥은 각각 명시한다. 공통 접근 검사는 기존 ReviewEvidenceAccess를 사용하며 범용 실행기를 만들지 않는다 |
| 정확한 decimal과 불변 이력 | 반올림·사후 사유 삭제 없이 재검토 가능 | 표현 범위를 넘는 누적값은 오류로 반환한다. 정정은 새 검토이며 과거 자료/결정은 수정하지 않는다 |

세 자료의 합계 일치는 사업상 재산 충분성·적법성 판단이 아니다. 기초 재산과 전체 자산의 완전성은 검증되지 않았다는 범위를 모든 검토 메타데이터에 고정한다.

## 업무 흐름

1. OPERATOR가 현재 평가에 SOURCE_OF_WEALTH 자료를 요청하고 고객이 세 자료를 제출한다. 자료 제출은 사건 변경 번호를 올리므로 **모두 제출한 후** 세 열람본을 제공받는다.
2. 담당자는 이름만 감사 열람한다. 주소·생일·직업·자금 원천을 재산 형성 신고로 반환하지 않는다.
3. 시작/종료 자료의 명의·기관·기준일·발급일·자산·부채·통화와 증감 자료의 명의·기관·기간·발급일·0~12개 증감 항목을 관찰한다. 각 항목은 날짜·종류·참조·양수 금액·통화다. 날짜는 기존 증빙과 같은 1900~2100 범위를 사용하며 정해진 몇 년의 이력만 사실로 간주하지 않는다.
4. 이름·기간 범위·미래/역전 날짜·환산 미검증·분해 필요·평가 확인 필요·순재산 차이를 사유로 보존한다. 관찰값은 암호화하며 공개 메타데이터에는 금액·참조를 넣지 않는다.
5. 다른 APPROVER가 자신의 세 열람본과 현재 신고를 대조한다. 관찰값 전체를 재입력해야 승인 옵션을 받을 수 있다. 거절에는 관찰을 null로 보낸다.
6. EDD_WEALTH_REVIEW 전용 보안키 서명이 검토 ID·해시·승인/거절·세션과 결합된다. 마지막에 현재 평가/사건 변경 번호/자료/키/작성자/관찰 사유를 다시 확인한다.
7. 결정·서명 소비·보안키 카운터·사건 변경·감사가 같은 트랜잭션에 확정된다. 두 결정이 경합하면 하나만 남는다. 양쪽 결과 모두 사건 OPEN·valid_until=NULL이며 거래 제한을 유지한다.

같은 사건/변경 번호의 미결 검토는 하나, 고객당 하루 10개, 검토 기한은 10분이다. 각 열람은 같은 인물·사용자 버전·세션·현재 평가·변경 번호·키에 결합되고 5분 이내여야 한다. 같은 자료 ID 또는 같은 원본 해시를 세 역할에 재사용할 수 없다. 이 검사는 원본 파일 동일성만 확인하며 위조/촬영 변형을 탐지하지 않는다.

## 순재산과 증감의 의미

```
시작 순재산 = 시작 자산 - 시작 부채
예상 종료 순재산 = 시작 순재산 + 기간 안의 설명된 순증감
미설명 차이 = 관찰한 종료 순재산 - 예상 종료 순재산
```

실제 테스트 표본은 시작 자산 1000/부채 200에서 근로 순소득 500·소비 100을 반영하고 종료 자산 1500/부채 300을 관찰한다. 시작 순재산 800 + 500 - 100 = 종료 순재산 1200이다. 부채의 증가를 지출로 또 빼지 않으며 이미 자산/부채의 종료값에 반영한다.

| 항목 | 계산 | 남기는 사유/주의 |
| --- | --- | --- |
| EMPLOYMENT_NET_INCOME | + | 세후·소비 차감 전 소득이다. 이미 소비를 뺀 저축액을 쓰면서 소비를 다시 빼면 안 된다 |
| BUSINESS_NET_PROFIT | + | 고객에게 귀속된 순재산 증가여야 한다. 매출·법인 전체 이익을 그대로 쓰지 않는다 |
| INVESTMENT_NET_GAIN / INVESTMENT_NET_LOSS | + / - | 원금 회수를 제외한 순손익이다. 실현 이익과 평가 이익을 중복 기재하지 않는다 |
| INHERITANCE_NET_RECEIVED / GIFT_NET_RECEIVED | + | 실제 귀속 순액이라는 관찰이며 이전·권리·세금 확인 완료가 아니다 |
| CONSUMPTION | - | 순재산을 줄이는 소비다. 자산 취득·부채 원금 상환은 그대로 소비로 분류하지 않는다 |
| VALUATION_INCREASE / VALUATION_DECREASE | + / - | 언제나 독립 가치 확인 필요 사유를 남긴다 |
| LOAN_PROCEEDS / ASSET_SALE_GROSS / OTHER | 0 | 분해 필요 사유를 남기고 순증감에 더하지 않는다. 차액을 숨겨 맞추지 않는다 |

대출 100을 받아 자산과 부채가 각각 100 늘면 순재산은 같다. 보유 자산을 팔아 현금으로 바꿔도 매각대금 전체가 순재산 증가는 아니다. 취득 원가·처분 직전 가치·손익·세금·부채 변동을 확인해 순액으로 설명해야 한다. 이 분류는 자료에서 읽은 사실이며 외부 권리·경제 실체를 자동 확인하지 않는다.

## 실제 코드와 필요한 Java 문법

### 1. 금액 종류의 방향을 고정하기

출처: [WealthFacts.java](../../src/main/java/dustin/cex/domains/edd/WealthFacts.java#L27)

```java
int direction() {return switch(this) {
    case EMPLOYMENT_NET_INCOME,BUSINESS_NET_PROFIT,INVESTMENT_NET_GAIN,INHERITANCE_NET_RECEIVED,GIFT_NET_RECEIVED,VALUATION_INCREASE->1;
    case CONSUMPTION,INVESTMENT_NET_LOSS,VALUATION_DECREASE->-1;
    case LOAN_PROCEEDS,ASSET_SALE_GROSS,OTHER->0;
};}
```

`enum`의 `switch`를 빠짐없이 작성해 종류별 계산 방향을 고정한다. TypeScript의 문자열 union처럼 종류를 제한하지만 Java에서는 실제 enum 값이다. 새로운 상수를 추가하면 분기 누락을 컴파일 단계에서 확인할 수 있다. 음수 금액을 입력하는 대신 손실/소비 종류와 양수 금액을 보낸다. 대출·매각 총액은 방향 0이고 분해 필요 사유가 남는다.

### 2. 통화가 다르면 합계 자체를 만들지 않기

출처: [WealthFacts.java](../../src/main/java/dustin/cex/domains/edd/WealthFacts.java#L73)

```java
// 다른 통화를 합산한 숫자는 의미가 없으므로 계산 결과 자체를 반환하지 않는다.
if(!sameCurrency) {reasons.add(Reason.CURRENCY_CONVERSION_UNVERIFIED);return new Comparison(null,null,null,null,null,List.copyOf(reasons));}
BigDecimal start=signed(opening.assets().subtract(opening.liabilities())),end=signed(closing.assets().subtract(closing.liabilities()));
BigDecimal expected=start;
for(var m:changes.movements())if(m.on().isAfter(opening.asOf())&&!m.on().isAfter(closing.asOf())&&m.on().isAfter(changes.periodStart())&&!m.on().isAfter(changes.periodEnd()))expected=signed(expected.add(m.amount().multiply(BigDecimal.valueOf(m.kind().direction()))));
BigDecimal difference=signed(end.subtract(expected));
if(difference.signum()!=0)reasons.add(Reason.UNEXPLAINED_NET_WORTH_DIFFERENCE);
return new Comparison(opening.currency(),start,expected,end,difference,List.copyOf(reasons));
```

금액은 `BigDecimal`이며 JavaScript `number`의 이진 부동소수점 합계를 사용하지 않는다. 기존 ExactAmount로 소수점 9자리·DB/Rust 공통 범위를 확인하며 누적합과 차이도 검증한다. 순재산은 음수일 수 있어 절댓값의 표현 가능성을 검사한 뒤 부호를 유지한다. 음수를 0으로 바꾸지 않는다.

자료 기준일은 해당 날짜 종료 시점의 잔액이라는 관찰 계약을 사용한다. 장중 시각이 필요한 자료나 기준 시점을 해석할 수 없는 자료를 이 양식으로 자동 환산하지 않는다. 증감 구간은 `(시작일, 종료일]`이다. 시작 스냅샷에 포함된 시작일 금액을 또 더하지 않는다. 구간 밖 항목은 합계에서 제외하고 사유를 남긴다. 증감 문서의 기간과 두 기준일이 다르면 불완전한 기간으로 표시한다. 입력 행을 몰래 정렬하거나 중복 제거하지 않고 역순·같은 참조의 중복을 거절한다. 참조를 바꾼 실질 중복까지 검증하는 기능은 아니다.

### 3. 관찰 목록을 외부 변경에서 보호하기

출처: [WealthFacts.java](../../src/main/java/dustin/cex/domains/edd/WealthFacts.java#L16)

```java
public record Changes(String holderName,String issuer,LocalDate periodStart,LocalDate periodEnd,LocalDate issuedOn,List<Movement> movements) {
    public Changes {if(movements!=null)movements=Collections.unmodifiableList(new ArrayList<>(movements));}
    @Override public String toString() {return "Changes[개인정보·증감 생략]";}
}
```

`record`는 값 타입의 접근자와 동등성 등을 제공하지만 List 내부까지 자동 불변으로 만들지는 않는다. 생성 시 복사한 뒤 수정 불가 목록으로 감싸 호출자가 원래 배열을 변경해도 관찰이 바뀌지 않게 한다. TypeScript의 `readonly`와 달리 목록 수정도 런타임에서 거절된다. 입력 검증 후 정규화한 record 전체를 암호화하고 독립 관찰과 비교한다. `toString`은 이름·날짜·금액·참조를 로그에 흘리지 않게 생략한다.

### 4. 세 자료별 현재 열람을 요구하기

출처: [WealthReviewService.java](../../src/main/java/dustin/cex/domains/edd/WealthReviewService.java#L155)

```java
private void view(User actor,UUID sid,UUID opening,UUID changes,UUID closing,Views views,ReviewEvidenceAccess.Snapshot current) {
    if(views==null||views.opening()==null||views.changes()==null||views.closing()==null)throw denied(HttpStatus.BAD_REQUEST);
    access.view(actor,sid,opening,views.opening(),current.assessment(),current.revision());
    access.view(actor,sid,changes,views.changes(),current.assessment(),current.revision());
    access.view(actor,sid,closing,views.closing(),current.assessment(),current.revision());
}
```

자료마다 별도 열람 영수증이 필요하다. 마지막 자료 하나를 열람했다는 사실로 나머지를 대체할 수 없다. 기존 ReviewEvidenceAccess가 현재 평가·키·인물/사용자 버전·세션·사건 변경 번호·5분 기한을 검사한다. 새 신고·KYC 변경·새 제출·다른 검토 결정은 옛 요청을 무효화한다. 기관이 외부에서 자료의 진위를 확인했다는 증명은 아니다.

### 5. 최종 결정과 감사의 원자성

출처: [WealthReviewService.java](../../src/main/java/dustin/cex/domains/edd/WealthReviewService.java#L101)

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_WEALTH_REVIEW,id,hash,approve,proof);
var checker=users.findById(actor).orElseThrow();
jdbc.update("INSERT INTO edd_wealth_decisions(review_id,approver_id,approver_person,opening_view,changes_view,closing_view,approved) VALUES(?,?,?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),views.opening(),views.changes(),views.closing(),approve);
// 검토 결과도 새로운 사실이다. 오래된 사건 결론은 무효화하되 금융 위험을 해소한 것으로 취급하지 않는다.
jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",current.caseId());
audit.record(actor,"EDD_WEALTH_DECIDED","edd-wealth-review:"+id,"SUCCESS",(approve?"APPROVED_CONTENT_REVIEW:":"REJECTED:")+hash);return read(id);
```

서비스의 TransactionTemplate 안에서 실행되는 코드다. 사용자 행을 정렬 순서로 잠그고 사건 잠금 뒤 결정 상태를 다시 읽는다. 서명 소비와 보안키 카운터도 같은 트랜잭션에 참여하므로 최종 감사 실패 시 함께 취소된다. 플랫폼 보안 업무의 DB 잠금이며 체결 코어·엔진 명령 큐에 접근하지 않는다.

AES-GCM 추가 인증 문맥은 검토 ID·고객·세 자료·평가·사건 변경 번호를 포함한다. HMAC 지문 도메인도 고객/세 자료/평가에 결합한다. 정책 버전은 EDD_WEALTH_PERIOD_V1이다. 계산·해석 계약을 바꿀 때는 이전 보고서가 새 해석으로 실행되지 않도록 정책 버전도 검토해야 한다.

## API와 보관 경계

기본 경로 `/api/admin/edd`, JSON API다. 웹 로그인 화면으로 이동하지 않는다.

| 경로 | 권한·입력 | 결과 |
| --- | --- | --- |
| POST `/wealth-reviews/context` | OPERATOR/APPROVER, 세 자료 ID·세 열람 | 이름·현재 평가/변경 번호·확인 범위 |
| POST `/wealth-reviews` | OPERATOR, 같은 자료/열람·assessment/revision·관찰·Idempotency-Key | 원문 없는 승인 메타데이터 |
| POST `/wealth-reviews/{id}/inspect` | APPROVER, 자신의 세 열람 | 감사 후 현재 관찰과 계산 결과 |
| POST `/wealth-reviews/{id}/approve/options`, `/reject/options` | APPROVER, 해시·열람·관찰/거절 null | 해당 결정의 추가 인증 옵션 |
| POST `/wealth-reviews/{id}/approve`, `/reject` | APPROVER, 같은 근거·서명 | 불변 내용 검토 결론 |
| GET `/customers/{user}/wealth-reviews?page=0` | OPERATOR/APPROVER/AUDITOR, 최근 MFA | 페이지당 50·관찰/금액 제외 목록 |

모든 검토 메타데이터의 확인 범위는 `PERIOD_OBSERVATIONS_ONLY_OPENING_WEALTH_AND_COMPLETENESS_UNVERIFIED`다. 시작 재산·전체 자산의 완전성이 미확인이라는 뜻이다. `APPROVED`는 내용 관찰의 독립 확인이며 차이를 지우거나 거래를 허용하지 않는다. 현재 STANDARD여도 기존 증빙 보류를 단순 신원 정정 종결로 우회하지 못한다.

`edd_wealth_reviews`와 `edd_wealth_decisions`는 UPDATE/DELETE/TRUNCATE를 거절한다. 승인 종류는 여덟 가지이며 EDD_WEALTH_REVIEW가 없는 기존 일곱 종류 스키마는 기동을 거부한다. 자동 변환·마이그레이션·폴백은 없다. 실행 소유 빈 DB에서만 신규 스키마를 사용했으며 기존 개발 DB와 실행 중 서버를 변경·재시작하지 않았다. 운영 적용 전에는 이력을 보존하는 승인된 스키마 준비가 필요하다.

## 작성한 테스트와 기대 결과

신규 [WealthReviewIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/WealthReviewIntegrationTest.java) 40개와 [WealthFactsTest](../../src/test/java/dustin/cex/domains/kyc/WealthFactsTest.java) 30개다. 실제 서명·PostgreSQL 트랜잭션과 순수 계산을 분리해 확인했다. 매개변수별 개별 사례와 결과는 [확정 증적](../reference/wealth-review-verification-2026-10-23.json)에 남긴다.

### WealthReviewIntegrationTest.java

- `minimalContextReturnsNameWithoutInventingWealthDeclaration`: 이름만 조회하며 생일·주소·존재하지 않는 재산 신고를 노출하지 않는다.
- `independentSignatureConfirmsContentButNeverReleasesTrading`: 두 사람의 관찰·실제 서명이 맞으면 내용 검토만 승인하고 거래 차단을 유지한다.
- `mismatchReasonsSurviveApproval`: 명의가 다른 관찰을 독립 확인해도 불일치 사유를 그대로 보존한다.
- `factsReferenceAndNamesAreEncryptedAndAuditedInspectionOnly`: 평문 승인 내용에 이름·참조를 남기지 않고 별도 감사 열람으로만 관찰을 반환한다.
- `singleEvidenceCannotPlayMultipleRoles`: 같은 증빙 ID를 세 역할에 사용하면 거절한다.
- `duplicateOriginalCannotBecomeIndependentThirdDocument`: 같은 원본 파일을 두 요청에 제출해도 독립된 세 자료으로 받지 않는다.
- `differentEvidencePurposeCannotSubstituteForWealth`: 자금·주소 목적 자료를 재산 자료로 대체하면 거절한다.
- `threeViewsCannotBeSwappedOrBorrowed`: 세 자료의 영수증을 바꾸거나 타인 영수증을 사용하면 거절한다.
- `missingIndependentObservationCreatesNoChallenge`: 독립 관찰을 생략한 승인은 인증 옵션을 만들지 않는다.
- `changedObservationCannotConsumeSignedChallenge`: 서명 후 관찰을 바꾸면 인증을 소비하지 않고 원래 관찰로 재시도할 수 있다.
- `rejectionRequiresNoInventedObservationAndKeepsHold`: 거절에 임의 관찰을 보내면 오류이며 관찰 없는 거절은 차단을 유지한다.
- `requestKeyBindsThreeDocumentsViewsAndFacts`: 요청 키 재사용은 같은 내용만 반환하고 자료·열람·관찰 변경을 거절한다.
- `newDeclarationInvalidatesThreeDocumentsAndSignature`: 새 신고는 옛 세 자료과 서명을 무효화한다.
- `newUploadInvalidatesThreeOldViewsAndSignature`: 새 자료 제출은 세 옛 열람과 진행 중 서명을 무효화한다.
- `customerKycChangeInvalidatesReview`: 현재 KYC 요약이 바뀌면 옛 검토를 결정하지 못한다.
- `requesterVersionChangeInvalidatesPendingReview`: 작성자 비활성화·버전 변경은 진행 중 검토를 무효화한다.
- `samePersonDifferentAccountCannotInspect`: 동일 인물의 다른 계정은 독립 승인자로 열람하지 못한다.
- `staleMfaAndRevokedSessionCannotReadOrDecide`: 최근 MFA가 지났거나 세션이 회수되면 열람·결정하지 못한다.
- `proposalAuditFailureRollsBackReviewCipherAndBinding`: 작성 감사 실패는 암호문·승인 결합·검토 저장을 모두 취소한다.
- `privateReadAuditFailureReturnsNoPersonalData`: 문맥·관찰 열람 감사가 실패하면 개인 내용을 반환하지 않는다.
- `decisionAuditFailureRollsBackProofCounterRevisionAndConclusion`: 결정 감사 실패는 사건·서명 소비·카운터·결정을 취소하고 같은 서명 재시도를 허용한다.
- `directionReplayAndDifferentHashCannotConsumeProof`: 다른 방향·해시·재실행에는 같은 서명을 사용할 수 없다.
- `otherNamespaceCannotConsumeWealthSignature`: 재산 검토 서명은 EDD 사건·주소·자금 업무에서 소비하지 못한다.
- `caseSignatureCannotAuthorizeWealthOrBeConsumedThere`: EDD 사건 서명은 재산 업무에서 소비하지 못하며 원래 사건에서 정상 사용한다.
- `invalidOriginOrMissingUserVerificationKeepsProofUnused`: 잘못된 출처 또는 UV 없는 실제 서명은 실패하고 인증을 미소비 상태로 둔다.
- `concurrentProposalKeysProduceOnePendingReport`: 같은/다른 요청 키가 경합해도 미결 보고서는 하나다.
- `simultaneousApproveRejectCommitOneDecision`: 동시 승인·거절은 하나의 결론만 확정한다.
- `immutableReportsAndDecisionsRejectMutation`: 보고서·결정의 수정·삭제·TRUNCATE는 DB에서 차단한다.
- `auditorGetsMetadataOnlyAndCustomerHasNoAdminAccess`: 감사자는 원문 없는 목록만 읽고 고객은 관리 API에 접근하지 못한다.
- `completeHttpWealthReviewUsesOwnViewsAndRealSignature`: HTTP 작성→옵션→실제 서명→승인이 완료돼도 거래는 계속 차단한다.
- `previousSevenKindSchemaFailsWithoutChangingHistory`: 옛 일곱 종류 스키마는 기동을 거절하고 이력은 변경하지 않는다.
- `comparisonIsPrivateAndReflectsAssetsLiabilitiesIncomeAndConsumption`: 감사 열람에서만 시작 800·예상 종료 1200·차이 0을 반환하고 공개 메타데이터에는 금액이 없다.
- `unexplainedDifferenceSurvivesIndependentApprovalAndTradingRemainsBlocked`: 종료 자산을 100 늘려 관찰해도 독립 승인 후 미설명 차이 사유와 차단을 유지한다.
- `approvedWealthObservationCannotBypassEvidenceHoldOnStandardCustomer`: 현재 STANDARD라도 재산 내용 승인으로 증빙 보류·거래 차단을 해제하지 못한다.
- `expiredClosingViewPreventsContextDespiteFreshOtherViews`: 종료 자료 열람만 만료돼도 세 자료 문맥을 반환하지 않는다.
- `expiredImmutableReportCannotIssueOptions`: 만료 표본에 인증 옵션을 발급하지 않는다.

### WealthFactsTest.java

- `assetsMinusLiabilitiesAndNetChangesExplainClosingWealth`: 자산·부채·소득·소비의 독립적인 표본 계산이 정확히 맞는다.
- `positiveNetFormationAddsOnlyExactObservedAmount`: 다섯 순증가 종류는 양수 관찰값만 더한다.
- `expensesAndNetLossReduceWealth`: 소비·순손실은 차감한다.
- `loanAndGrossSalesAreNotNewNetWealth`: 대출·매각 총액·기타는 합산하지 않고 분해/차이 사유를 남긴다.
- `valuationCanExplainArithmeticButAlwaysNeedsIndependentCheck`: 평가 증감은 계산과 별도로 독립 가치 확인 필요를 남긴다.
- `newLoanIncreasesAssetsAndLiabilitiesTogetherWithoutCreatingWealth`: 대출로 자산·부채가 함께 늘면 순재산을 증가시키지 않는다.
- `negativeNetWorthIsValidAndNotSilentlyClampedToZero`: 음수 종료 순재산과 음수 차이를 그대로 보존한다.
- `emptyMovementStatementCanDescribeUnchangedPeriodButNotProveOpeningOrigin`: 변화 0건인 기간은 계산할 수 있으나 기초 원천 확인으로 바꾸지 않는다.
- `currencyMismatchSuppressesEveryAggregateInsteadOfGuessingRate`: 통화가 다르면 모든 합계를 null로 반환하고 사유를 남긴다.
- `missingCoverageAndOutOfPeriodMovementsRemainExplicit`: 빠진 기간·구간 밖 항목을 사유로 남기고 범위 밖 금액을 더하지 않는다.
- `futureDatesAndPrematureDocumentAreNotHidden`: 명의 차이·미래 날짜·관찰 전 발급 사유를 보존한다.
- `nonpositiveOrUnrepresentableMovementIsRejected`: 0·음수·소수/표현 범위 초과 입력은 거절한다.
- `aggregateOverflowIsRejectedBeforePersistence`: 개별 금액은 유효해도 누적 범위 초과면 저장 전에 거절한다.
- `duplicatesOrReorderedTimelineAreRejectedInsteadOfDeduplicatedOrSorted`: 중복 참조·역순 날짜를 자동 보정하지 않고 거절한다.
- `normalizedNamesAmountsAndCurrencyAreEqualWithoutLosingNinthDecimal`: 통화/금액을 정규화하며 소수점 9자리를 보존하고 문자열은 원문을 생략한다.
- `movementListIsDefensivelyCopiedAndImmutable`: 원래 목록을 바꾸거나 내부 목록을 수정해도 관찰을 바꾸지 못한다.
- `missingComponentsAndInvalidPeriodsAreRejected`: 필수 자료·날짜·행이 없거나 기간이 역전되면 거절한다.
- `moreThanTwelveMovementsIsRejected`: 13행 입력은 거절한다.

### 실제 감사 실패 주입

출처: [WealthReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/WealthReviewIntegrationTest.java#L95)

```java
@Test void decisionAuditFailureRollsBackProofCounterRevisionAndConclusion() throws Exception {
    var p=prepared();var r=propose(p);var a=authorization(r,true,p.facts());
    long before=service.status(customer.getId()).revision();
    // 최종 감사 저장에서 실패시킨다. 이미 검증한 서명도 이 거래와 함께 취소돼야 한다.
    doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_WEALTH_DECIDED"),anyString(),anyString(),anyString());
    assertThrows(IllegalStateException.class,()->decide(r,a,true,p.facts()));
    assertEquals(before,service.status(customer.getId()).revision());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_wealth_decisions WHERE review_id=?",Long.class,r.payload().id()));
    assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,a.proof().challengeId()));
    // 같은 실제 서명을 다시 사용해 정상 확정한다. 인증 소비가 남았다면 이 재시도는 실패한다.
    reset(audit);assertEquals("APPROVED",decide(r,a,true,p.facts()).status());
}
```

최종 감사 저장만 실패시킨다. 실제 공개키 서명 검증과 PostgreSQL 트랜잭션을 거친 뒤 사건 변경·결정·인증 소비가 남지 않는지 확인한다. 같은 실제 서명으로 정상 재시도해 보안키 카운터까지 롤백됐는지 확인한다. 인증 자체를 참으로 대체한 검증이 아니다.

자료는 합성 PNG이고 유료 Didit 세션 생성 HTTP·외부 엔진 호출/송신 등 기존 EDD fixture의 대역 범위를 유지한다. 원본/열람본 암호화·서명 검증·현재 상태·DB·업무 트랜잭션은 제품 코드다. 실제 기관/고객 자료·실물 보안키를 확인한 것은 아니다.

## 실행 명령과 결과

Java 21, 실행 소유 PostgreSQL 16·Kafka의 임의 loopback 포트를 사용한다. 기존 5432/5433/5435 DB나 9092 브로커에 테스트하지 않는다.

- `caffeinate -dims python3 scripts/verify_edd.py`
- `python3 scripts/verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`
- `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`
- `python3 scripts/verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`
- `python3 scripts/audit_dependencies.py`, `verify_dependency_integrity.py`

선택 JUnit **836개**, 실패·오류·제외 0개다. EDD/증빙/주소/자금/재산 270개(신규 재산 70 = 통합 40 + 순수 비교 30), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개다. 기존 별도 프로세스 **15개**도 통과했다. 실행 소유 DB/브로커·임시 DB/체크섬 표본을 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성을 통과했다. 의존성 222개 버전은 조회 시점 알려진 OSV 검출 0개다. 변조 체크섬은 예상한 Gradle 종료 코드 1로 차단됐다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 운영 OS/JDK/컨테이너 전체에 취약점이 없다는 뜻은 아니다.

[확정 증적](../reference/wealth-review-verification-2026-10-23.json)에 실제 명령·분리 집계·개별 테스트 사례·실행 소유 자원 정리·현재 코드 발췌 91개 대조와 검증 한계를 남겼다.

폐기 예정 API·일부 unchecked 변환·테스트 계측에 따른 JVM 클래스 공유 경고는 기존 경고다. JMH는 컴파일이며 성능을 새로 측정한 것은 아니다. 재산 검토 전용 JVM 강제 종료 시나리오는 작성하지 않았다. 기존 별도 프로세스 검증과 신규 동시성/감사 롤백을 구분한다.

## 남은 업무와 운영 조건

- 기초 재산의 실제 형성 경위, 고객의 별도 재산 형성 진술, 전체 자산/부채 명세·소유권·평가·외부 거래와 귀속·환전·세금·자료별 다중 기간을 확인해야 한다. 선택 기간의 차이 0으로 이 조건을 대신하지 않는다.
- 발급기관·위변조·외부 독립 정보 대조, PEP/제재·실소유자·지속 모니터링·STR/관계 종료·잔여 자산 처리는 별도다. 다음 우선순위는 쌓인 자료 관찰을 독립 외부 근거/준법 실체 검토에 연결하는 것이다.
- 보존/파기·국외 이전·검사·KMS/키 교체·최소 DB 권한·외부 감사 보존·운영 복원·사업 신고/인증 조건은 [022](022-edd-evidence-storage-and-access.md)와 공통 준법 대조표를 따른다. 개발 코드·로컬 검증만으로 충족됐다고 표시하지 않는다.
- 엔진 단일 스레드 코어와 출력 워커, 프론트, 기존 로컬 서버는 변경하지 않았다. 봇은 0명이다. 배포·개발 DB 스키마 변환·서비스 재시작은 수행하지 않았다.

## 관련 커밋

- [`50393c0`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/50393c051165798362172455074977d21e50cfdb): 순재산 증감 모델과 순수 계산/경계 테스트
- [`fffbd98`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fffbd983e4ab5bb8088f5b97696818d8306223d0): 세 자료·현재 열람·독립 서명 검토와 실제 통합 테스트
