# 고객 관계 종료 절차 개시를 승인하고 신규 주문을 차단하기

고객확인을 완료할 수 없다는 현재 판단을 실제 준법 회부와 묶어 두 사람이 독립 승인한다. 승인된 종료 개시 결정은 신규 주문의 별도 차단 근거로 남기고, 고객 로그인·기존 주문 취소·잔액·원장·보고 판단을 보존한다. 개발자와 준법 업무 담당자가 이 초기 집행 계층의 보장과 미완료 업무를 설명하도록 작성했다.

## 문제와 기존 동작

기존 고객확인·EDD는 새 주문을 제한했지만 관계 종료 절차를 시작한다는 별도 결정은 없었다. 일반 tradingRestricted는 독립 승인으로 켜고 끌 수 있으며 고객확인 위험 해소와 별개의 생명주기가 필요하다. 사용자를 enabled=false로 바꾸면 로그인·본인 조회·기존 주문 취소까지 영향을 받는다. 잔액을 0으로 덮어쓰거나 단순 상태 변경으로 금융 의무를 없앨 수도 없다.

준법 회부는 강화된 고객확인(EDD) 사건을 추가 준법 판단으로 넘긴 실제 승인 결정(COMPLIANCE_ESCALATION)이다. 의심거래보고(STR) 판단과 관계 종료 판단은 이 같은 근거에서 각각 별도로 이어진다.

이번 범위는 **기존 고객 계정의 종료 절차 개시와 신규 주문 접수 차단**이다. 최종 거래관계 종료·잔여 자산 반환·신규 계정 개설 거절 전체가 아니다. 등록 계정이 법률상 언제 거래관계를 수립한 것인지는 계약과 실제 영업 구조로 확인해야 한다.

## 공식 근거와 기존 제품의 접근

확인일은 2026-10-24다. 국내 법률·공개 사업자 사례·외국 제품 패턴과 자체 기술 정책을 구분한다.

| 근거 | 확인·적용 범위 |
| --- | --- |
| [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887) | 법률 제21857호·시행 2026-10-02의 실제 공식 본문 제4항·제5항을 재대조했다. 고객확인을 할 수 없는 경우 등의 신규 거래 거절/기존 거래 종료와 STR 보고 여부 검토를 구분한다. 단순 고위험이나 공급사 지연을 확인 불가로 자동 단정하지 않는다. 이번 pending 단계가 법정 종료 의무 전체의 이행은 아니다. |
| [이용자보호법 제11조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1024558707) | 같은 시행일의 실제 본문에서 정당한 사유 없는 입출금 차단 금지와 차단 시 통지/보고를 확인했다. 신규 주문·로그인·취소·입출금·자산 반환을 전역 플래그 하나로 합치지 않는다. 출금 허용이나 예외 사유 전체를 이 코드로 판단하지 않는다. |
| [Spring Security 기본 응답 헤더](https://docs.spring.io/spring-security/reference/features/exploits/headers.html) | 설치된 7.1.1의 [공식 구현](https://raw.githubusercontent.com/spring-projects/spring-security/7.1.1/web/src/main/java/org/springframework/security/web/header/writers/CacheControlHeadersWriter.java)과 실제 응답을 대조해 기본 Cache-Control의 no-store를 재사용한다. 문자열 전체가 아니라 실제 저장 금지 지시를 HTTP 회귀에서 검증한다. |
| [Stripe 계정 거절 API](https://docs.stripe.com/api/account/reject?create_charge-transfer_data-amount=) | 의심 계정의 처리와 별도 지급 처리 옵션을 구분하는 공개 API 패턴이다. 원래 경로 조회 실패 후 정상 반환되는 공식 API 경로의 payouts_action 파라미터를 읽었다. 국내 거래소의 출금 정책으로 이식하거나 Stripe API를 호출하지 않는다. |
| [업비트 개인정보 처리방침](https://static.upbit.com/terms/private_data.html) | 거래관계 설정·유지·해지와 고객확인/서비스 관리/고충 처리의 목적 구분을 재확인했다. 이 공개 문서로 내부 폐쇄 테이블·승인 절차를 추측하지 않는다. |
| [빗썸 공개 고객확인/원화 입금 안내](https://feed.bithumb.com/notice/1644351) | 2023-12-29 안내의 특정 은행 고객확인과 원화 입금 제한 범위를 확인했다. 과거 은행명·원화 상품을 현재 프로젝트 지원으로 적용하지 않고, 제한 업무의 범위를 구분하는 참고로만 쓴다. |
| [삼성증권 AML 공개 자료](https://www.samsungsecurities.co.kr/download/kor/pdf/control_file05.pdf) | CDD·거래 모니터링/STR·내부통제 목적이 별개임을 다시 확인했다. 공개된 과거 계획을 현행 이행 완료나 내부 종료 구현의 증거로 사용하지 않는다. |

국내 법령 조문 조회 화면뿐 아니라 실제 본문 응답을 읽고 해시를 확정 증적에 남긴다. 실제 법정 책임자·조직·통지·계약·제출과 법령/하위 규정 전체 충족을 주장하지 않는다. 두 인물·10분 요청·5분 본인 전체 페이지 제공·하루 10회 한도·별도 RELATIONSHIP_EXIT 서명은 자체 기술 정책이다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 감수하는 비용·한계 |
| --- | --- | --- |
| 실제 현재 준법 회부와 명시적 확인 불가 판단 | 임의 고객 번호나 STR 판단만으로 종료를 시작하지 않고 근거·요청자를 추적한다. | bool과 사유는 직원의 판단이다. 외부 사실의 진위·법적 요건을 자동 인증하지 못하고 조사·독립 심사 인력이 필요하다. |
| 원래 결정과 별도의 불변 종료 개시 승인 | KYC 정상화·일반 제한 해제로 자동 재개되지 않는다. STR/EDD 이력은 그대로 남는다. | 잘못된 종료 개시의 독립 철회/이의 처리와 최종 종료 집행은 추가 업무다. 현재 자동 만료·자동 해제 경로가 없어 고객의 거래 제한과 운영 대응 부담이 남는다. |
| 새 요청·제공·결정 세 표와 11번째 승인 종류 | 업무 서명 재사용을 막고 단일 고객의 한 승인 효과를 보장한다. | 보존·복호화·인력/서명·DB 조회 비용, 지원 스키마 준비가 필요하다. 옛 10종 제약을 자동 변환하거나 과거 금융 이력을 채워 넣지 않는다. |
| 로그인·조회·취소와 새 주문 접수 분리 | 고객이 기존 주문과 자산을 확인하고 취소를 요청할 수 있다. | 이미 접수된 명령과 기존 주문의 체결은 계속될 수 있다. 종료 개시가 모든 체결을 즉시 멈추거나 취소를 확정하지 않는다. |
| 고객에게 종료 진행 상태만 제공 | 내부 사유·담당자·보고 여부를 API에 복사하지 않는다. | 고객의 구체 안내·이의 접수·법적 통지/비밀 유지 검토가 별도로 필요하다. 상태 제공만으로 적법한 통지 완료를 주장하지 않는다. |

기존 Spring 트랜잭션·JPA 사용자 잠금·JdbcTemplate·AES-GCM/HMAC·WebAuthn을 재사용했다. 원래 ReportingReviewService의 회부 읽기/현재성 검사를 ComplianceReferralBasis로 옮겨 두 업무가 같은 실제 근거를 검증한다. 보고 여부 판단과 종료 판단 자체는 합치지 않으며 새 라이브러리·범용 워크플로 엔진·설정 계층은 없다.

## 업무 상태와 금융 효과

| 영역 | 이번 승인 이후 |
| --- | --- |
| 고객 상태 | EXIT_PENDING. 종료 완료가 아니다. 승인 효과는 요청 만료 뒤에도 유지한다. |
| 신규 주문 | 플랫폼 접수/예약 트랜잭션에서 현재 고객 행을 잠근 뒤 차단한다. |
| 기존 동일 키 요청 | 원래 접수 기록을 재조회한다. 새 예약·엔진 재전송을 만들지 않는다. |
| 기존 주문·취소 | 별도 권한과 기존 경로를 유지한다. 먼저 접수된 전송/체결은 이어질 수 있다. 취소 확정은 엔진 결과 소비로 반영한다. |
| 잔액·예약·원장 | 승인으로 차감·해제·삭제하지 않는다. 자산 반환·미확정 의무 정리는 별도다. |
| 로그인·세션 | 종료 개시로 enabled를 바꾸거나 세션을 회수하지 않는다. 다른 보안 사유의 기존 회수·비활성 통제는 유지한다. |
| 고객확인·EDD | 원래 평가·사건/종결은 보존한다. 나중에 실제 CDD/EDD가 해소돼도 이 종료 개시 결정을 지우지 않는다. |
| STR | 별도의 비공개 판단·후속/확정 필요를 보존한다. 종료 승인이 보고 검토·제출·접수 성공을 대신하지 않는다. |

신규 주문과 종료 개시 승인은 같은 고객 행으로 순서를 정한다. 주문 예약이 먼저 커밋됐으면 기존 접수이고, 종료 개시 승인이 먼저 커밋됐으면 이후의 새 접수는 거절한다. 플랫폼의 행 잠금이며 Rust 체결 코어에 락·조회·네트워크 대기를 추가하지 않는다.

## API와 권한

- POST /api/admin/compliance/relationship-exits: 현재 OPERATOR가 실제 referralId, 별도 checkerId, Idempotency-Key, 확인 불가 판단과 사유를 제출한다.
- GET 같은 경로: 현재 지정 요청자/승인 계정과 인물이 본인의 메타데이터 이력을 페이지로 조회한다.
- POST /{id}/inspect?page=...: 현재 지정 인물의 원문/고정 근거 페이지 제공. 승인자는 본인 세션·인물·버전으로 모든 페이지를 최근 5분 내 제공받아야 한다.
- POST /{id}/approve/options, /reject/options, /approve, /reject: 지정된 현재 APPROVER와 업무별 보안키 서명. 요청자와 같은 인물의 다른 계정도 승인하지 못한다.
- GET /api/customer/relationship: 현재 인증 고객 자신의 NO_EXIT_DECISION 또는 EXIT_PENDING만 반환한다. 다른 고객 ID 입력·내부 원문은 받지 않는다. NO_EXIT_DECISION은 다른 KYC/거래 정책의 허용을 뜻하지 않는다.

요청자·고객·지정 승인자 행을 정렬해 잠그고 계정/인물/버전·현재 회부/사건/평가·전체 근거 해시·요청 상태/기한·암호화 사유·본인 제공·서명 방향을 재검사한다. 멱등 키의 같은 내용은 같은 요청이며 다른 사유·대상은 충돌한다. 반려/만료 이력은 지우지 않고 새로운 요청을 남긴다.

## 실제 코드와 이해에 필요한 문법

모두 2026-10-24 실제 소스의 부분 발췌다. 주변 주입 객체·트랜잭션이 필요하며 독립 실행 예제가 아니다.

### 위험과 확인 불가 판단 구분

출처: [RelationshipExitService.java](../../src/main/java/dustin/cex/domains/edd/RelationshipExitService.java#L31)

```java
public Facts validated() {
    if(!dueDiligenceUnable||rationale==null)throw denied(HttpStatus.BAD_REQUEST);
    String value=Normalizer.normalize(rationale,Normalizer.Form.NFC).strip();
    // 한 줄의 명시적인 사유만 받는다. 로그 위조·숨은 방향 전환 문자와 과도한 원문 수집을 막는다.
    if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
    return new Facts(true,value);
}
```

boolean의 기본값 false는 승인되지 않은 판단이다. JSON에서 값이 빠져도 false이므로 유효한 종료 개시 사유로 받지 않는다. 형식 검사와 직원 판단의 진위·법적 충분성은 다르다. 실제 사실 검증은 고정 근거·독립 심사와 운영 절차로 뒷받침해야 한다.

### 두 업무가 공유하는 회부의 현재성

출처: [ComplianceReferralBasis.java](../../src/main/java/dustin/cex/domains/edd/ComplianceReferralBasis.java#L41)

```java
if(!"REFERRED".equals(row.get("state"))||revision!=r.revision()+1||!r.assessment().equals(row.get("latest_assessment"))||!r.assessment().equals(row.get("assessment_id")))throw denied(HttpStatus.CONFLICT);
dossiers.verify(r.dossier(),r.caseId(),r.user());if(!r.dossier().digest().equals(dossiers.liveDigest(r.caseId(),r.user())))throw denied(HttpStatus.CONFLICT);return revision;
```

보고/종료 서비스가 각자 근거 검사를 발명하지 않는다. 원래 승인된 COMPLIANCE_ESCALATION과 현재 사건/평가·전체 근거 해시를 검증한다. 이 부품 자체가 호출자의 업무 권한을 부여하지는 않으며 실제 트랜잭션 없이는 호출을 거절한다.

### 서명 후 변한 고객과 근거 재검사

출처: [RelationshipExitService.java](../../src/main/java/dustin/cex/domains/edd/RelationshipExitService.java#L153)

```java
var origin=referrals.read(p.referralId());long revision=referrals.current(origin);
if(revision!=p.caseRevision()||!origin.hash().equals(p.referralHash())||!origin.dossier().equals(p.dossier())||!origin.assessment().equals(p.assessmentId()))throw denied(HttpStatus.CONFLICT);
if(exitInitiated(p.userId())||locked.get(p.userId()).getVersion()!=p.customerVersion())throw denied(HttpStatus.CONFLICT);
facts(r);
```

고객 버전은 일반 계정·거래 제한 등 상태 변경을 감지하고, 사건 변경 번호/평가/전체 해시는 새 신고·확인/증빙을 감지한다. 두 값은 서로 대신하지 않는다. 새 사실이 들어오면 옛 서명을 소비하지 않고 새 판단을 요청한다.

### 승인·서명 소비·감사의 원자적 확정

출처: [RelationshipExitService.java](../../src/main/java/dustin/cex/domains/edd/RelationshipExitService.java#L110)

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,id,hash,approve,proof);
jdbc.update("INSERT INTO relationship_exit_decisions(review_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?)",id,r.payload().userId(),actor,checker.getOperatorPersonId(),approve);
// 불변 승인 자체가 신규 주문의 차단 근거다. 잔액·로그인·보고 판단은 수정하지 않는다.
audit.record(actor,"RELATIONSHIP_EXIT_DECIDED","relationship-exit:"+id,"SUCCESS",(approve?"APPROVED_EXIT_PENDING:":"REJECTED:")+hash);return read(id);
```

TransactionTemplate 콜백 안에서 업무별 증명을 검증하고 불변 결정을 추가한다. 감사 실패 시 결정·증명 소비·보안키 카운터가 함께 롤백된다. 중간 성공 응답은 내보내지 않는다. TypeScript에서 여러 await 호출을 순서대로 쓴 것만으로 DB 트랜잭션이 생기지 않는 것과 구분해야 한다.

### 원래 신규 주문 경로의 추가 차단 근거

출처: [CustomerFinancialEligibility.java](../../src/main/java/dustin/cex/domains/security/CustomerFinancialEligibility.java#L21), 074에서 신규 주문과 주소 공개가 공유하도록 추출한 현재 코드.

```java
// 종료 개시 결정은 일반 제한 해제와 독립된 차단 근거다. 외부 공급사의 최신 확인도 필요하다.
return !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=?)",Boolean.class,id))
```

EXISTS는 승인된 종료 개시 결정의 존재를 확인한다. 신규 예약과 같은 고객 행 잠금 뒤 수행하는 SQL이므로 승인과 주문 접수의 순서를 정한다. 종료 판정을 엔진으로 보내거나 코어의 메모리 상태를 읽지 않는다. 일반 거래 제한 플래그를 해제해도 이 결정은 남는다.

출처: [relationship-exit-rescission-schema.sql](../../src/main/resources/edd/relationship-exit-rescission-schema.sql#L27)

```sql
CREATE OR REPLACE FUNCTION relationship_exit_require_single_active() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
 IF NEW.approved THEN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
   RAISE EXCEPTION '종료 업무는 READ COMMITTED 격리 수준이 필요합니다' USING ERRCODE='25000';
  END IF;
  PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=NEW.user_id) THEN
   RAISE EXCEPTION '이미 유효한 종료 개시 결정이 있습니다' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END $$;
```

034 이후의 현재 코드다. 고객 행을 잠근 뒤 승인되고 아직 철회되지 않은 활성 결정이 있는지 검사한다. 과거 승인 이력은 여러 개 보존하되 활성 효과는 고객별 하나다. 복합 외래 키로 고객과 원래 요청을 결합하고 DB에서도 단일 활성 효과를 강제한다. 033 당시의 영구 단일 승인 인덱스는 더 이상 지원하지 않는다. 기존 DB를 자동 변환하지 않았으며 자세한 변경은 [034](034-exit-rescission.md)를 따른다.

### 고객 응답과 캐시 경계

출처: [RelationshipExitService.java](../../src/main/java/dustin/cex/domains/edd/RelationshipExitService.java#L125)

```java
public CustomerStatus customerStatus(long actor) {
    return tx.execute(s->{
        var person=users.lockById(actor).orElse(null);
        if(person==null||person.getRole()!=UserRole.CUSTOMER||!person.isAuthenticationAllowed())throw denied(HttpStatus.FORBIDDEN);
        audit.record(actor,"RELATIONSHIP_STATUS_READ","customer:"+actor,"SUCCESS","CUSTOMER_SAFE_STATUS");
        if(exitInitiated(actor))return new CustomerStatus("EXIT_PENDING");
        boolean history=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_exit_decisions WHERE user_id=? AND approved)",Boolean.class,actor));
        return new CustomerStatus(history?"EXIT_RESCINDED":"NO_EXIT_DECISION");
    });
}
```

record의 응답 필드는 state 하나다. 현재 로그인 고객의 ID를 필터가 주입하며 다른 고객 번호나 내부 판단 사유를 받지 않는다. 응답 전에 필수 감사를 기록한다.

출처: [RelationshipExitIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/RelationshipExitIntegrationTest.java#L155)

```java
var response=mvc.perform(get("/api/customer/relationship").header("Authorization","Bearer "+customerAccess))
    .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
    .andExpect(jsonPath("$.state").value("EXIT_PENDING"));
String body=response.andReturn().getResponse().getContentAsString();
```

Spring Security 기본 복합 헤더에 no-store가 포함되는지 검증한다. 프레임워크가 이미 제공하는 캐시 보호를 다시 구현하거나 정확한 헤더 문자열 하나로 제한하지 않는다. 인가는 별도의 정확한 경로와 현재 역할로 검사한다.

## 작성한 테스트

[RelationshipExitIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/RelationshipExitIntegrationTest.java)에 44개 사례를 작성했다. 실제 함수별 기대 결과는 다음과 같다.

| 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `approvedExitInitiatesWithoutMarkingClosedOrChangingOriginalCase` | 실제 승인 후 EXIT_PENDING과 신규 주문 차단을 확인하고 로그인 활성·원래 사건 상태/변경 번호를 보존한다. |
| `unapprovedOrNonReferralCannotCreateReview` | 미승인 회부에서 종료 요청을 만들지 못한다. |
| `awaitingEvidenceDecisionIsNotExitReferral` | 자료 대기 결론은 준법 회부로 대체하지 못한다. |
| `anotherOperatorsAssignedReviewIsNotVisibleEvenWithCorrectRole` | 같은 역할의 미지정 인물도 목록·원문·승인에 접근하지 못한다. |
| `requesterCannotApproveByChangingRole` | 요청자가 나중에 승인 역할을 얻어도 자기 요청을 승인하지 못한다. |
| `sameHumanDifferentAccountsCannotBeMakerAndChecker` | 같은 사람의 다른 계정으로 요청/승인을 분리하지 못한다. |
| `assignedMetadataNeverIncludesRationaleOrUnableJudgment` | 지정 목록/저장 payload/암호문에 사유·확인 불가 판단 원문을 복사하지 않는다. |
| `noPageInspectionCannotIssueApprovalOptions` | 본인 원문/근거 제공 없이 승인 옵션을 만들지 못한다. |
| `makersInspectionCannotReplaceCheckersOwnReceipt` | 요청자의 제공 이력이 승인자의 본인 제공을 대신하지 못한다. |
| `receiptFromAnotherSessionIsNotUsable` | 다른 세션의 제공 이력은 사용할 수 없다. |
| `oldReceiptCannotAuthorizeDecision` | 최근 5분이 지난 제공 이력만으로 서명하지 못한다. |
| `newEvidenceAfterSignatureRejectsDecisionWithoutConsumingProof` | 서명 뒤 새 증빙 요청이 들어오면 결정을 거절하고 증명은 소비하지 않는다. |
| `newDeclarationMakesOldReferralUnusableButHistoryStaysReadable` | 새 신고가 옛 회부 결정을 무효화하되 불변 역사 원문은 현재 지정 인물에게 남는다. |
| `newCaseConclusionInvalidatesOldExitReview` | 새 사건 결론은 옛 종료 요청의 결정을 거절한다. |
| `changedMakerVersionInvalidatesBoundRequest` | 요청자 버전 변경은 옛 요청/서명을 무효화한다. |
| `changedCheckerVersionInvalidatesOptionsAndOldReceipts` | 지정 승인자 버전 변경은 옛 제공과 승인 옵션을 무효화한다. |
| `staleMfaCannotReadOrDecide` | 최근 MFA가 없으면 원문 제공을 거절한다. |
| `sameKeyReplaysExactTargetAndFactsButChangedReasonConflicts` | 같은 키/내용은 같은 요청, 바뀐 사유는 충돌한다. |
| `simultaneousSameKeyCreatesOneReview` | 실제 두 스레드의 같은 키 요청은 한 요청으로 수렴한다. |
| `oneLiveRequestAndOneApprovedDecisionPerCustomer` | 미결 요청 또는 승인된 종료 개시가 있는 고객에게 중복 요청을 생성하지 않는다. |
| `rejectionPreservesReasonAndAllowsNewRequest` | 반려해도 원래 요청 사유와 이력을 보존하고 새로운 요청을 허용한다. |
| `proposalAuditFailureRollsBackCipherAndBinding` | 요청 감사 실패 시 암호문·요청·승인 결합을 롤백한다. |
| `inspectionAuditFailureRollsBackReceiptAndReturnsNoReason` | 제공 감사 실패 시 원문을 반환하지 않고 제공 이력도 롤백한다. |
| `decisionAuditFailureRollsBackProofAndDecisionThenRetrySucceeds` | 결정 감사 실패 시 결정·증명 소비를 되돌리고 같은 증명으로 재시도한다. |
| `oppositeDecisionCannotUseApprovalSignature` | 승인 방향의 서명으로 반려하지 못하고 증명을 소비하지 않는다. |
| `actualApproveRejectRaceCommitsExactlyOneDecision` | 실제 승인/반려 경합에서 한 결정만 확정한다. |
| `auditAndGeneralDossierDoNotCopyDecisionOrReason` | 감사와 일반 EDD 묶음에 종료 사유/판단 원문을 복사하지 않는다. |
| `newTablesRejectAllMutationStatements` | 세 불변 표의 UPDATE/DELETE/TRUNCATE를 거절하고 결정을 보존한다. |
| `oldTenKindSchemaFailsWithoutTransformingExistingHistory` | 옛 10종 승인 제약을 자동 변환하지 않고 기동 검사에서 거절한다. 기존 이력은 유지한다. |
| `laterActualCddAndEddClearanceCannotUndoApprovedExit` | 실제 새 KYC·CDD·좁은 EDD 해소 후 cdd.permitted=true여도 종료 개시만으로 신규 주문 차단을 유지한다. CDD를 대역으로 열지 않는다. |
| `unauthorizedCustomerAndAuditorCannotReadAnyReviewEndpoint` | 고객·AUDITOR는 내부 목록과 원문을 읽지 못한다. |
| `realHttpProposalInspectionOptionsAndBoundSignatureUseStoredFacts` | 실제 MVC 요청→원문 제공→옵션→소프트웨어 보안키 서명→승인 흐름을 검증한다. |
| `everyFrozenPageIsRequiredIncludingFinalPartialPage` | 25개 첫 페이지와 마지막 3개 페이지를 모두 제공받아야 옵션을 만든다. |
| `expiredRequestIsReadableButCannotIssueNewDecision` | 만료 표본은 역사 원문을 읽되 새 결정을 만들지 못한다. |
| `copiedCipherFromAnotherReviewFailsWithoutPlaintextOrReceipt` | 다른 요청 문맥의 암호문 복사는 원문·영수증 반환 없이 실패한다. |
| `signatureForDifferentBusinessKindCannotApproveExitReview` | 다른 업무 종류의 유효한 서명을 종료 승인에 재사용하지 못한다. |
| `changedCustomerVersionInvalidatesSignatureWithoutConsumingIt` | 서명 뒤 고객 버전이 변하면 결정을 거절하고 증명은 소비하지 않는다. |
| `rejectionAddsNoExitHoldAfterActualCddClearance` | 반려 뒤 실제 KYC/CDD/EDD 해소로 주문이 가능하며 새 종료 차단이 생기지 않는다. |
| `explicitUnableJudgmentIsRequiredInsteadOfTreatingAnyRiskAsTermination` | 단순 고위험을 확인 불가로 단정하는 false 값은 요청으로 받지 않는다. |
| `reportingAndExitJudgmentsUseIndependentDecisionsAndNeitherErasesOther` | 실제 별도 STR 승인과 종료 개시를 함께 확정해도 원래 보고 필요·후속 대기를 지우지 않는다. |
| `customerHttpStatusOmitsInternalReasonAndOtherIdentities` | 고객에게 state 하나와 저장 금지 헤더만 제공하고 내부 사유/타인 정보를 제외한다. 기존 로그인 세션의 본인 조회는 유지한다. |
| `customerStatusAuditFailureDoesNotReturnState` | 고객 상태 감사 실패 시 상태를 반환하지 않는다. |
| `commonReferralVerifierRequiresBusinessTransaction` | 공통 회부 검증을 트랜잭션 밖에서 호출하지 못한다. |
| `existingReservationReplayAndCancellationSurviveWhileNewOrderIsDenied` | 실제 정상 주문/예약/원장 이후 종료 개시를 승인한다. 신규 주문 HTTP 403·같은 키 재조회 200·기존 조회 200·취소 접수 202와 예약/원장 불변·엔진 중복 전송 방지를 검증한다. |

[RelationshipExitFactsTest.java](../../src/test/java/dustin/cex/domains/edd/RelationshipExitFactsTest.java)의 추가 7개는 다음과 같다.

| 테스트 함수 | 시나리오·기대 결과 |
| --- | --- |
| `invalidRationaleIsRejected` | null·빈 값·공백·개행·방향 전환 제어 문자의 5종을 거절한다. |
| `unableJudgmentCannotBeOmittedOrSubstitutedByRisk` | 확인 불가 false는 사유가 있어도 거절한다. |
| `normalizationLengthBoundaryAndSafePrinting` | 사유 정규화·2,000자 경계/초과·안전한 toString을 확인한다. |

## 실행 명령과 결과

macOS·Temurin JDK 21·격리 PostgreSQL 16과 실행 소유 Kafka를 사용했다. 기존 DB·Kafka·서버와 분리하고 봇은 0명으로 실행한다.

```sh
caffeinate -dims python3 scripts/verify_edd.py
python3 scripts/verify_kyc.py
python3 scripts/verify_kyc_recovery.py
python3 scripts/verify_bound_approvals.py
python3 scripts/verify_operator_mfa.py
python3 scripts/verify_engine_inbox.py
./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain
python3 scripts/verify_operator_admin.py
python3 scripts/verify_order_idempotency.py
python3 scripts/verify_market_path.py
python3 scripts/audit_dependencies.py
python3 scripts/verify_dependency_integrity.py
```

최종 선택 JUnit **1,198개**, 실패·오류·제외 0개다. EDD/보고/인계/감독/종료 개시 632개(신규 종료 통합 44 + 형식 7), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했고 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 집중 51개는 최종 632개와 중복되므로 총수에 더하지 않는다.

두 JAR·JMH 컴파일·의존성 목록 생성, 222개 버전의 조회 시점 알려진 OSV 검출 0개와 변조 체크섬의 예상 Gradle 종료 코드 1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/relationship-exit-verification-2026-10-24.json)에 실제 명령·개별 사례·중간 실패/집중/최종 실행 분리·자원 정리·공식 본문/설치 라이브러리 대조·현재 실제 발췌 146개와 별도 6개 대조·한계를 남겼다.

중간 컴파일에서는 새 테스트의 Boolean 제네릭 결과와 assertTrue 오버로드 추론, 상속된 verify와 Mockito.verify 이름 충돌의 2개 오류를 수정했다. 제품 테스트 실행 전의 실패다. 첫 전체 실행 632개 중 2개는 기본 Cache-Control 복합 헤더를 단일 문자열로만 기대한 검사와 공통 수수료 대역의 반환값 누락이었다. 실제 헤더에는 이미 no-store가 포함되어 있었으며 캐시 보호 누락으로 보지 않는다. 설치된 Spring Security 7.1.1의 공식 구현·실제 응답을 확인해 기본 보호를 그대로 쓰고 테스트를 수정했다. 유효한 KYC 뒤에는 실제 새 신고가 있어야 다시 확인하는 fixture도 명시했다.

이어 집중 51개 중 1개는 기존 취소 접수 계약 HTTP 202를 200으로 기대한 오류였다. 기존 비동기 접수 의미를 유지하고 테스트를 202로 고친 뒤 집중 51개와 최종 전체 632개를 통과했다. 중간 실패 결과는 최종 통과로 숨기거나 합산하지 않았다.

기존 deprecated/unchecked 컴파일 경고와 테스트 JVM의 클래스 공유/도구 경고는 남아 있다. JMH 컴파일은 성능 측정이 아니며 과거 엔진 TPS를 이 변경의 성능으로 재사용하지 않는다.

## 남은 범위와 운영 조건

- 이 단계는 새 주문 접수를 막는 종료 절차 개시다. 신규 관계 수립 거절 전체·최종 종료·미체결 일괄 취소·미확정 체결/정산·미집행 명령·보관 자산/잔여 자산 반환·입출금 정책은 완성되지 않았다.
- 확인 불가 bool과 사유는 직원의 명시적 판단이다. 실제 사실·충분한 조사·법적 요건 충족을 자동 인증하지 않는다. 요청 만료는 승인 효과를 없애지 않으며 오판의 독립 철회/이의와 업무 연속성도 후속으로 완성해야 한다.
- 법 제5조의2제5항의 STR 검토는 별도 업무다. 기존 보고 필요와 판단은 보존하지만 이번 승인만으로 보고 검토/제출/접수를 완료하지 않는다. 운영 전 미결 보고 검토와 종료 업무의 누락 없는 연결·통지/비밀 유지·이의 처리·약관/법적 완료 기준을 확인해야 한다.
- 고객 상태 API는 내부 사유/보고 여부를 복사하지 않는다. 이것만으로 법적 통지나 모든 비밀 누설 위험을 해소하지 않는다. 실제 메시지·시점·조직 분장·법정 책임자·계약/운영 증적은 별도다.
- 신규 11종 승인 제약·세 표가 필요하다. 기존 개발 DB·금융/보안 이력을 자동 변환하지 않았고 서버 재시작·배포도 하지 않았다. 과거 10종 스키마는 명시적으로 기동을 거부한다.
- 키는 기존 EvidenceCrypto/CddCrypto를 재사용하므로 앱 권한 분리와 DB/KMS 계정/키 격리를 구분한다. DB 슈퍼유저 침해·보존/파기·백업/복제·국외 이전·운영 수용량/감독·인증/신고는 별도다.
- 새 주문 검증의 엔진 HTTP·Kafka·수수료 설정·Didit 생성은 대역이다. 신규 접수/예약/원장·실제 HMAC·소프트웨어 WebAuthn과 MVC 인가는 제품 코드다. 실제 엔진 WAL 취소·체결이나 실제 기관·실물 보안키의 검증으로 확대하지 않는다.
- 승인/반려는 원래 요청 해시·판단·인물·서명 방향에 대한 결정이다. 승인자의 별도 반려 의견 원문은 이번 입력에 없으며 최종 통지/이의 업무와 구분한다.
- 신규 주문과 종료 승인 자체의 전용 병렬 경합 시험은 없다. 공유 고객 행 잠금 계약과 승인/반려·동일 키 실제 경합 및 기존 금융 회귀의 범위를 구분한다.
- 신규 종료 개시 전용 JVM 강제 종료 검증은 없다. 기존 프로세스 회귀와 감사 실패/실제 동시성 검증을 구분한다.
- 엔진·프론트는 변경하지 않았고 봇은 0명이다. 운영 배포·전체 법령/신고/ISMS 충족은 아니다.

## 관련 커밋과 자료

- [`8de215e`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8de215ee5657315c0a55865871a6f4a74a267b21): 공통 회부 검증 분리·종료 개시 독립 서명·새 접수 차단/기존 권리 보존과 신규 테스트 51개.

[029 비공개 보고 판단](029-confidential-reporting-review.md)·[030 후속 관찰](030-reporting-queue-and-followup.md)·[031 실제 인계](031-reporting-custody-and-orphan-recovery.md)·[032 전사 점검](032-reporting-supervision.md)은 종료 판단과 다른 업무다. 공통 로드맵의 F1·R2·R3를 함께 읽는다.


2026-10-24 후속: [034 독립 철회와 재개시](034-exit-rescission.md)는 원래 승인 기록을 지우지 않고 차단 효과만 거둔다. 고객 상태에 EXIT_RESCINDED를 추가하고 원래 근거의 반복 재사용을 막으며, 새로운 현재 회부에 따른 재개시는 허용한다. 과거 이 문서의 실행 수치·확정 증적은 당시 결과로 보존하고 현재 발췌와 원문/권한/상태 설명은 연결한 후속 기준을 함께 읽는다.
