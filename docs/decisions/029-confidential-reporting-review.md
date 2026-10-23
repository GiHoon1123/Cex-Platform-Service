# 준법 회부에서 비공개 보고 여부를 독립 서명으로 확정하기

준법 회부 상태만으로는 누가 어떤 근거로 의심거래 보고 필요 여부를 판단했는지 알 수 없었다. 실제 승인된 회부와 당시 전체 근거를 선택하고, 지정된 두 인물만 읽는 암호화 사유·독립 보안키 서명으로 내부 판단을 불변 보존한다. 이 문서는 구현과 테스트를 설명할 개발자·업무 담당자를 위한 기록이다. **내부 결정은 실제 FIU 제출·접수 확인이 아니다.**

## 문제와 기존 동작

[028](028-aml-false-positive-resolution.md)은 제한된 AML 오탐만 기한부 종결했다. 나머지는 OPEN/REFERRED와 거래 차단이 유지되지만, 준법 회부는 의심거래 보고 여부 검토·외부 보고·거래 관계 유지/거절/종료를 수행하지 않았다. 일반 EDD 메모에 보고 사유를 넣으면 넓은 열람 역할에 민감한 내부 판단을 복사할 수도 있다.

이번 범위는 **현재 승인된 COMPLIANCE_ESCALATION 회부에 대한 비공개 내부 보고 여부 판단**이다. REPORT_REQUIRED 또는 NO_REPORT_ON_CURRENT_FACTS와 구체 사유를 지정 승인자가 채택하거나 반려한다. 추가 조사가 필요하면 현재 EDD의 자료 대기·회부 업무를 계속하며, 미보고를 임시 대기 표시로 사용하지 않는다. 실제 외부 제출·접수·보완 보고·제출 기한 관리·관계 종료·잔여 자산 처리는 다음 업무로 남긴다. 코드만으로 전체 AML 의무를 이행했다고 표시하지 않는다.

## 공식 근거와 기존 제품의 접근

확인일은 2026-10-23이다. 국내 적용 의무, 해외 참고, 사업자 공개 사례와 이번 자체 구현을 구분한다.

| 근거 | 확인과 적용 |
| --- | --- |
| [특정금융정보법 제4조](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=290725) | 법률 제21857호·시행 2026-10-02의 실제 조문 응답을 대조했다. 합당한 의심 근거의 보고·사유 명시·보고하려는 사실과 보고 사실의 누설 금지를 확인했다. 같은 회사 내부의 AML 목적 제공 예외도 있으므로 법이 모든 내부 제공을 금지한다고 설명하지 않는다. |
| [특정 금융거래정보 보고 및 감독규정](https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq=2100000285986&chrClsCd=010201) | 고시 제2026-3호·시행 2026-10-02의 제3조·제13조를 실제 본문 응답으로 대조했다. 보고책임자의 보고대상 결정 후 3영업일 이내 제출과 판단 이유 등 관련 자료 보존을 확인했다. 이번 코드는 제출 달력·접수 증적을 구현하지 않는다. |
| [KoFIU 의심거래보고 안내](https://www.kofiu.go.kr/kor/policy/amls03.do) | 직원의 내부 보고·보고책임자의 판단·외부 제출을 구분하는 공식 설명을 따른다. 안내의 과태료 금액을 새 코드 임계값으로 사용하지 않는다. |
| [KONA PLATE 공개 AML 가이드](https://www.konaplate.com/platforms/onboardingguide/guidetab2/compliance03) | 업무담당자 검토·보고 여부 검토·보고책임자 승인·별도 외부 제출의 공개 흐름을 참고한다. 전자금융업 사례이며 거래소의 적용 의무를 이 회사의 문서로 확정하지 않는다. |
| [빗썸 AML 시스템 공개](https://feed.bithumb.com/notice/1645245), [업비트 교육 자료](https://upbitcare.com/academy/education/common/76) | 고객확인과 STR/모니터링이 구분되는 업무라는 참고다. 빗썸 공지는 2024-11-21 작성·2026-01-20 갱신 표시를 확인했다. 업비트는 검색 결과의 공개 요약만 확인했고 본문 조회는 실패했으므로 세부 설계의 근거로 쓰지 않는다. 내부 DB/승인 알고리즘은 공개 자료로 확인하지 못했다. |
| [삼성증권 AML 공개 자료](https://www.samsungsecurities.co.kr/download/kor/pdf/control_file05.pdf) | CDD·거래 모니터링/STR·내부통제의 별도 구성과 적출 시나리오 점검·교육을 확인했다. PDF의 2025년까지 컨설팅 표시는 과거 계획이며 현행 이행 완료로 해석하지 않는다. |
| [영국 FCA AML 안내](https://www.fca.org.uk/firms/financial-crime/money-laundering-terrorist-financing) | 책임 있는 보고 담당과 위험에 비례한 절차의 해외 참고다. 영국 SAR 체계나 기한을 국내 의무로 적용하지 않는다. |

행정규칙 부칙의 유예·시행 조건은 해당 조항별로 구분해야 한다. 위 확인이 전체 사업 신고·인증·법령/하위 규정의 전수 충족을 의미하지 않는다. 지정된 두 계정·인물, 10분 요청 기한·5분 전체 페이지 제공·하루 10회 제한과 독립 업무별 서명은 자체 기술 정책이다. 승인자는 코드의 APPROVER이며 법정 보고책임자 선임·전문성·조직 독립성을 이 역할 하나로 증명하지 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 것 | 감수하는 비용·제약 |
| --- | --- | --- |
| 일반 EDD 목록과 비공개 판단 분리 | 넓은 EDD/감사 열람에 판단 방향·사유를 복사하지 않는다. | 별도 조회·배정과 암호화/보존 비용이 든다. 내부 감사·권한 이관을 운영 절차로 준비해야 한다. |
| 작성자와 특정 승인 계정·인물만 상세 제공 | 같은 역할의 다른 사람이 모든 보고 판단을 조회하지 못한다. | 승인자 퇴사/회수/교대 때 과거 요청을 바꾸지 않고 새 회부·요청이 필요하다. 미배정 사건 탐지와 조직의 전담 권한은 별도 운영 과제다. |
| STR_REVIEW 승인 종류 추가 | EDD 종결/회부 서명을 비공개 보고 판단에 재사용하는 혼동을 막는다. | 승인 종류가 8개에서 9개로 늘고 지원 스키마 준비가 필요하다. 옛 제약 자동 변환·호환 실행은 하지 않는다. |
| 실제 승인된 현재 회부와 전체 근거 결합 | 단순 임의 고객 ID·외부 근거 번호 대신 실제 결정 이력으로 추적한다. | 새 근거/신고/사건 결론이 생기면 옛 요청이 무효다. 검토를 다시 해야 하고 실제 긴급 보고는 별도 절차가 필요하다. |
| 내부 승인과 외부 제출 분리 | 내부 승인만으로 제출 성공이나 법적 면책을 주장하지 않는다. | 실서비스 전 보고서 작성·실제 채널·제출/접수·기한·실패 대응을 반드시 완성해야 한다. |

기존 EDD_REVIEW에 사유를 더해 모든 열람자에게 배포하는 대안은 피했다. 새 규칙 엔진·권한 프레임워크·외부 공급사는 추가하지 않고 현재 Spring 트랜잭션·JdbcTemplate·Jackson·AES-GCM/HMAC·WebAuthn을 재사용한다. KYC/자금 증빙과 같은 EvidenceCrypto 키를 사용하므로 권한 분리는 애플리케이션 수준이며 DB·KMS의 별도 키/계정 격리는 운영 검증 대상이다.

## 업무 흐름과 보장

1. 현재 고객·평가·사건의 실제 독립 승인된 COMPLIANCE_ESCALATION을 선택한다. 현행 EDD 실행 정책, REFERRED 상태, 원래 회부 후 변경 번호, 당시 전체 근거와 현재 해시가 같아야 한다.
2. OPERATOR가 특정 APPROVER를 지정하고 결론·구체 사유를 제출한다. 같은 인물의 다른 계정도 작성/승인 조합으로 받지 않는다.
3. 사유와 결론은 암호화하고 공개 메타데이터에는 전용 HMAC만 넣는다. 요청·대상·두 계정/인물/버전·기한·근거와 STR_REVIEW 결합을 같은 트랜잭션으로 보존한다.
4. 지정 승인자는 본인 세션으로 모든 고정 페이지와 판단을 제공받는다. 일반 EDD의 열람 영수증·다른 계정·다른 세션·만료 영수증은 대신 사용할 수 없다. 제공 완료는 사람이 실제 이해했다는 자동 증명이 아니다.
5. options와 실제 결정에서 현재 배정·역할·계정 버전·최근 MFA·현재 회부·전체 근거·사유 무결성을 재검사한다. 업무 종류·요청·해시·승인/반려 방향에 묶인 실제 보안키 서명만 소비한다.
6. 결정·공개키 카운터·서명 소비·필수 감사가 함께 커밋된다. 같은 사건을 잠그므로 승인/반려 경합에서 한 결정만 확정한다. 감사 실패는 함께 롤백하고 같은 증명으로 정상 재시도할 수 있다.
7. REPORT_REQUIRED와 미보고 판단 모두 사건·원장·잔액·신규 주문 권한을 변경하지 않는다. 외부 접수 번호나 SENT 상태를 생성하지 않는다. 승인된 보고 필요를 같은 회부의 새 미보고 판단으로 덮어쓰지 않는다.
8. 새 사실로 과거 요청이 무효가 돼도 과거 사유·결정·제공 이력은 남는다. 담당 계정·인물의 역사 조회와 현재 결정 권한은 구분한다.

일반 EDD 목록에는 비공개 판단을 합치지 않는다. 대신 기존 좁은 AML/신원 정정 종결이 별도 보고 검토를 건너뛰지 못하게 실제 `compliance_reviews` 존재를 검사한다. 현재는 반려/미보고를 포함해 기록이 있는 사건을 그 좁은 경로로 해소하지 않는 보수적 정책이다. 향후 책임 있는 위험 해소·관계 결정이 필요하며 임의 수동 면제나 기존 기록 삭제로 풀지 않는다.

## 실제 코드와 이해에 필요한 문법

다음은 2026-10-23 현재 소스의 부분 발췌다. 클래스·주입 객체·업무 트랜잭션이 필요한 코드이며 독립 실행 예제가 아니다.

### 판단 사유의 형식과 출력

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L33)

```java
public Facts validated() {
    if(conclusion==null||rationale==null)throw denied(HttpStatus.BAD_REQUEST);
    String value=Normalizer.normalize(rationale,Normalizer.Form.NFC).strip();
    // 한 줄의 명시적인 사유만 받는다. 로그 위조·숨은 방향 전환 문자와 과도한 원문 수집을 막는다.
    if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
    return new Facts(conclusion,value);
```

Java record는 값과 equals를 제공하지만 유효성을 자동 검사하지 않는다. `validated()`가 NFC와 바깥 공백을 정리하고, 빈 사유·2,000자 초과·남아 있는 제어/방향 제어 문자를 거절한다. override한 toString은 원문이 로그에 그대로 찍히지 않게 한다. TypeScript 인터페이스만으로 실제 입력이 검증되지 않는 점과 같다. 사유 문장이 참이거나 충분히 합리적인지는 사람이 판단한다.

### 암호화와 다른 업무용 승인 결합

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L79)

```java
String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(context(p),json.writeValueAsBytes(facts));
jdbc.update("INSERT INTO compliance_reviews(id,case_id,user_id,referral_id,requester_id,checker_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,p.caseId(),p.userId(),referral,actor,checker,key,json.writeValueAsString(p),hash,cipher,Timestamp.from(expires));
mfa.bindApproval(OperatorMfaService.ApprovalKind.STR_REVIEW,id,actor,maker.getVersion(),hash,expires);
```

payload에는 결론/사유 대신 HMAC이 있다. AES-GCM의 문맥은 검토·고객·사건·회부·내용 MAC에 묶인다. 다른 기록의 암호문을 복사하면 인증에 실패한다. STR_REVIEW는 기존 금융/권한 업무와 같은 보안키 라이브러리를 사용하되 서명을 이 업무에만 결합한다. 별도 라이브러리를 직접 구현하지 않는다.

### 역할 이후에 실제 지정 인물 확인

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L130)

```java
private User assigned(long actor,UUID sid,Review r,Map<Long,User> locked) {
    var person=locked.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);var p=r.payload();
    if((actor==p.requesterId()&&p.requesterPerson().equals(person.getOperatorPersonId()))||(actor==p.checkerId()&&p.checkerPerson().equals(person.getOperatorPersonId())))return person;
    throw denied(HttpStatus.NOT_FOUND);
```

HTTP 필터의 OPERATOR/APPROVER만으로 자료를 내주지 않는다. 계정 ID와 지속적인 인물 UUID가 모두 맞아야 한다. 다른 지정되지 않은 역할 보유자에게는 404를 반환한다. 목록도 현재 본인이 지정된 기록만 조회한다. 과거 열람에는 계정 버전 변경만으로 원문을 없애지 않지만 실제 결정에서는 요청자/승인자 버전을 엄격히 확인한다. 역할 회수·접근 만료·오래된 MFA는 열람도 차단한다.

### 회부 이후 변한 사실 거절

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L147)

```java
if(!"REFERRED".equals(row.get("state"))||revision!=r.revision()+1||!r.assessment().equals(row.get("latest_assessment"))||!r.assessment().equals(row.get("assessment_id")))throw denied(HttpStatus.CONFLICT);
dossiers.verify(r.dossier(),r.caseId(),r.user());if(!r.dossier().digest().equals(dossiers.liveDigest(r.caseId(),r.user())))throw denied(HttpStatus.CONFLICT);return revision;
```

바로 앞 SQL은 사용자 잠금 후 해당 사건 행을 잠근다. 원래 승인으로 사건 변경 번호가 1 증가한 상태와 현재 평가·전체 자료를 대조한다. 새 자료가 추가되면 사건 번호가 같아도 목록 해시가 달라져 거절한다. 보고 내부 판단 자체는 일반 근거 목록을 바꾸지 않는다.

### 현재 본인의 전체 페이지 제공 확인

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L127)

```java
long viewed=jdbc.queryForObject("SELECT count(DISTINCT page) FROM compliance_review_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND page>=0 AND page<? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,checker.getOperatorPersonId(),checker.getVersion(),sid,hash,p.dossier().pageCount());
if(viewed!=p.dossier().pageCount())throw denied(HttpStatus.CONFLICT);return checker;
```

25개 단위 페이지마다 같은 요청 해시·본인 인물·계정 버전·세션의 최근 영수증을 센다. `count(DISTINCT page)`이므로 첫 페이지를 반복해서 받아도 마지막 페이지를 대신하지 않는다. 공급된 원문/페이지는 감사가 성공해야만 응답한다. 기존 EDD용 제공 기록과 이 비공개 제공 기록은 업무상 구분된다.

### 내부 결정과 감사의 원자성

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L101)

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.STR_REVIEW,id,hash,approve,proof);
jdbc.update("INSERT INTO compliance_review_decisions(review_id,approver_id,approver_person,approved) VALUES(?,?,?,?)",id,actor,checker.getOperatorPersonId(),approve);
// 내부 보고 판단은 사건·잔액·거래 허용 상태를 바꾸지 않는다. 외부 제출도 수행하지 않는다.
audit.record(actor,"COMPLIANCE_REVIEW_DECIDED","compliance-review:"+id,"SUCCESS",(approve?"APPROVED_INTERNAL_ONLY:":"REJECTED:")+hash);return read(id);
```

`TransactionTemplate.execute` 안의 부분이다. 검증된 증명 소비 뒤 불변 결정과 최종 감사를 쓴다. 예외가 나면 같은 트랜잭션을 롤백한다. 이 코드에는 사건 CLOSED 갱신·원장 변경·Kafka 전송·FIU 제출이 없다. 출력의 ASSURANCE도 INTERNAL_DECISION_ONLY_EXTERNAL_SUBMISSION_NOT_RECORDED다.

### 역사 원문도 무결성 검증 후 제공

출처: [ReportingReviewService.java](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java#L153)

```java
    var value=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),Facts.class).validated();
    if(!p.factsMac().equals(fingerprint(p.userId(),p.referralId(),value)))throw new IllegalStateException();return value;
} catch(RuntimeException e) {throw new IllegalStateException("비공개 판단의 해독·무결성 검증 실패");}
```

평문과 결론을 복원한 뒤 다시 형식·전용 MAC을 검증한다. 실패 원인 예외를 바깥에 붙이지 않아 원문이나 JSON이 오류 응답에 실리지 않게 한다. 아직 내부 보존 키가 공유되므로 키 교체·사고 대응·접근 계정 분리는 운영 조건이다.

## API·보존과 실행 스키마

| API | 권한/결과 |
| --- | --- |
| POST `/api/admin/compliance/reviews` | OPERATOR, Idempotency-Key·승인된 referralId·checkerId·facts로 요청. 실제 두 인물·현재 근거 검사 |
| GET `/api/admin/compliance/reviews?page=0` | OPERATOR/APPROVER, 본인 지정 목록 25개. 결론/사유 제외 |
| POST `/api/admin/compliance/reviews/{id}/inspect?page=0` | 지정된 두 인물만 원문과 해당 고정 페이지·필수 감사 제공 |
| POST `…/{id}/approve/options`, `…/{id}/reject/options` | 지정 APPROVER의 현재 근거·전체 본인 제공 후 업무별 보안키 옵션 |
| POST `…/{id}/approve`, `…/{id}/reject` | 같은 현재 조건을 다시 검사하고 업무 서명과 함께 단일 결정 |

모든 경로는 최근 MFA와 no-store를 적용한다. 고객·AUDITOR에게 이 관찰 API를 열지 않는다. 일반 보안 감사에는 업무 식별자와 성공/실패·해시·서명 종류가 남으므로 업무 존재까지 모든 내부 감사자에게 숨기는 구조는 아니다. 법의 내부 AML 목적 제공 예외와 실제 조직의 최소 권한·감사 범위를 구분해 운영해야 한다. 고객 알림·고객 API·Kafka에 이 판단을 보내지 않는다.

`compliance_reviews`, `compliance_review_views`, `compliance_review_decisions` 세 표에 요청/암호문·제공·결정을 불변 보존한다. 수정·삭제·TRUNCATE를 DB에서 거절한다. 슈퍼유저 침해·DB 운영 계정·외부 감사·보유/파기·별도 보존은 트리거만으로 해결하지 않는다. 새 아홉 업무 승인 스키마가 필요하고 기존 8종 제약은 기동 때 명시적으로 거절한다. 현재 개발 DB를 변환하거나 실행 서버를 재시작하지 않았다.

## 작성한 테스트

### [ReportingFactsTest.java](../../src/test/java/dustin/cex/domains/edd/ReportingFactsTest.java)

10개 실행 사례다. 매개변수 입력은 각각 별도 JUnit 사례로 집계한다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `normalizesOuterWhitespaceWithoutDroppingReason` | 바깥 공백 정리와 사유 보존, toString 원문 제외 |
| `emptyControlOrHiddenDirectionIsRejected` | null·빈 값·공백·탭·줄바꿈·방향 제어·숨은 문자 7개 입력 거절 |
| `noConclusionCannotBecomeDecision` | 결론 누락 거절 |
| `reasonHasExplicitTwoThousandCharacterBoundary` | 2,000자 허용·2,001자 거절 |

### [ReportingReviewIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/ReportingReviewIntegrationTest.java)

37개 실행 사례다. 매개변수 입력은 각각 별도 JUnit 사례로 집계한다.

| 테스트 함수 | 시나리오와 기대 결과 |
| --- | --- |
| `eitherInternalConclusionKeepsFinancialHoldAndRecordsNoExternalReceipt` | 보고 필요/현재 근거 미보고 2종의 실제 승인 뒤에도 REFERRED·변경 번호·주문 차단 유지, 원문 조회 일치 |
| `unapprovedOrNonReferralCannotCreateReview` | 미승인 회부는 새 검토를 만들지 못함 |
| `awaitingEvidenceDecisionIsNotReportingReferral` | 실제 승인된 자료 대기 결론도 준법 회부를 대신하지 못함 |
| `anotherOperatorsAssignedReviewIsNotVisibleEvenWithCorrectRole` | 다른 OPERATOR/APPROVER의 목록은 비어 있고 상세 404·옵션 거절 |
| `requesterCannotApproveByChangingRole` | 작성자가 나중에 APPROVER가 되어도 자기 판단 승인 거절 |
| `sameHumanDifferentAccountsCannotBeMakerAndChecker` | 같은 인물 UUID의 별도 계정 조합 거절 |
| `actualAssignedListsAndMetadataNeverIncludeRationaleOrConclusion` | 본인 목록과 저장 payload에는 결론/사유 없음, 암호문 평문 제외 |
| `noPageInspectionCannotIssueApprovalOptions` | 아무 페이지도 제공받지 않은 승인 옵션 거절 |
| `makersInspectionCannotReplaceCheckersOwnReceipt` | 작성자의 제공 기록으로 승인자 제공을 대신하지 못함 |
| `receiptFromAnotherSessionIsNotUsable` | 현재 MFA를 가진 별도 세션도 옛 세션의 제공 기록 사용 불가 |
| `oldReceiptCannotAuthorizeDecision` | 같은 요청·계정·인물·세션의 6분 전 표본만 있으면 거절 |
| `newEvidenceAfterSignatureRejectsDecisionWithoutConsumingProof` | 새 실제 자료 요청이 옛 서명 무효화, 증명 미소비 |
| `newDeclarationMakesOldReferralUnusableButHistoryStaysReadable` | 새 고객 신고는 현재 결정을 막되 과거 원문 조회 유지 |
| `newCaseConclusionInvalidatesOldReportingReview` | 사건 재심사 결론으로 옛 보고 검토 실행 거절 |
| `changedMakerVersionInvalidatesBoundRequest` | 작성자 버전 변경 후 옛 요청 거절 |
| `changedCheckerVersionInvalidatesOptionsAndOldReceipts` | 지정 승인자 버전 변경 후 옵션/옛 제공 거절 |
| `staleMfaCannotReadOrDecide` | 오래된 MFA의 상세 열람 거절 |
| `sameKeyReplaysExactTargetAndFactsButChangedReasonConflicts` | 같은 키·현재 대상/내용은 같은 ID, 다른 사유는 충돌·추가 행 없음 |
| `simultaneousSameKeyCreatesOneReview` | 실제 두 스레드의 같은 키 경합도 한 검토만 생성 |
| `oneLiveRequestAndOneApprovedDecisionPerReferral` | 회부별 미결 중복·승인 후 반대 판단으로 덮기 거절 |
| `rejectionPreservesReasonAndAllowsNewRequest` | 반려 사유 보존, 새 요청 가능·주문은 차단 |
| `proposalAuditFailureRollsBackCipherAndBinding` | 최종 요청 감사 실패 시 새 암호문/검토 생성 롤백 |
| `inspectionAuditFailureRollsBackReceiptAndReturnsNoReason` | 제공 감사 실패 시 원문 응답과 제공 기록 불가 |
| `decisionAuditFailureRollsBackProofAndDecisionThenRetrySucceeds` | 결정 감사 실패로 증명/결정 롤백, 같은 실제 증명 정상 재시도 |
| `oppositeDecisionCannotUseApprovalSignature` | 승인 서명을 반려 방향으로 재사용 불가, 증명 미소비 |
| `actualApproveRejectRaceCommitsExactlyOneDecision` | 실제 두 스레드 승인/반려 경합은 하나만 확정, 차단 유지 |
| `auditAndGeneralDossierDoNotCopyDecisionOrReason` | 공통 감사/일반 고정 묶음에 결론·사유 원문 복사 없음 |
| `newTablesRejectAllMutationStatements` | 세 새 표의 UPDATE·DELETE·TRUNCATE를 실제 PG에서 거절 |
| `oldEightKindSchemaFailsWithoutTransformingExistingHistory` | 옛 8종 제약은 기동 SQL에서 실패, 트랜잭션 후 원래 이력/9종 제약 보존 |
| `reportReviewBlocksOldSimpleIdentityClosureAfterReassessment` | 후속 정상 신원으로 단순 종결해 별도 보고 검토를 우회하지 못함 |
| `unauthorizedCustomerAndAuditorCannotReadAnyReviewEndpoint` | 고객/AUDITOR의 목록·상세 HTTP 403 |
| `realHttpProposalInspectionOptionsAndBoundSignatureUseStoredFacts` | 실제 HTTP 요청→지정 상세→옵션→소프트웨어 보안키 서명→내부 결정, no-store·원문 일치·차단 유지 |
| `everyFrozenPageIsRequiredIncludingFinalPartialPage` | 28개 과거 합성 요청의 25+3 페이지, 첫 페이지만으로 거절·마지막 제공 뒤 옵션 허용 |
| `expiredRequestIsReadableButCannotIssueNewDecision` | 별도로 추가한 유효 암호화 과거 표본은 역사 조회 가능·만료 실행 불가 |
| `copiedCipherFromAnotherReviewFailsWithoutPlaintextOrReceipt` | 다른 검토 문맥의 암호문 복사 표본은 인증 실패·원인/원문 미노출·영수증 없음 |
| `signatureForDifferentBusinessKindCannotApproveReportingReview` | 다른 EDD_REVIEW 업무의 유효 서명은 STR_REVIEW를 확정하지 못함·증명 미소비 |

### 기존 AML 종결의 우회 회귀

[AmlResolutionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/AmlResolutionIntegrationTest.java)의 `confidentialReportingReviewCannotBeBypassedByNarrowFalsePositiveClosure` 1개를 추가했다. 실제 오탐 근거가 있는 사건을 준법 회부하고 별도 보고 검토를 만들면 COMPLIANCE_REVIEW_UNRESOLVED로 좁은 종결을 거절한다. 기존 34개를 유지하여 해당 클래스는 35개다.

신규 실행 사례 합계는 **48개 = 비공개 통합 37 + 순수 형식 10 + AML 우회 1**이다. 개별 실제 실행 이름과 결과는 확정 증적에 보존한다.

기존 BoundApprovalTest의 exhaustive switch에 STR_REVIEW를 별도 통합 검증 대상으로 추가했다. 이전 네 업무 테스트는 삭제하지 않았다. verify_edd.py의 기존 범위를 유지하고 새 두 클래스를 추가했다. 새 세션 fixture에서 실제 엔티티에 없는 필드명을 사용한 초기 compileTestJava 실패를 수정했으며 제품 코드를 시험에 맞춰 바꾸지 않았다.

## 실행 명령과 실제 결과

격리 실행은 macOS·Temurin 21, 실행기 소유 PostgreSQL/Kafka와 별도 임시 DB에서 수행했다. 공급사 HTTP는 대역, 서명은 실제 HMAC/소프트웨어 WebAuthn이며 실제 PG 트랜잭션·서비스·HTTP 권한을 검증한다. 기존 다른 프로젝트 DB와 로컬 고객 데이터는 사용하지 않는다.

- `caffeinate -dims python3 scripts/verify_edd.py`
- `python3 scripts/verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`
- `./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`
- `python3 scripts/verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`
- `python3 scripts/audit_dependencies.py`, `verify_dependency_integrity.py`

최종 선택 JUnit **1,032개**, 실패·오류·제외 0개다. EDD/증빙/근거/AML/비공개 보고 검토 466개(신규 48 = 보고 통합 37 + 형식 10 + AML 우회 1), KYC 150·승인/접근 190·MFA 42·금융 183·실제 시장 연결 1개를 통과했다. 기존 별도 프로세스 **15개**도 통과했고 실행 소유 컨테이너·임시 DB·체크섬 표본은 정리했다.

두 JAR·JMH 컴파일·의존성 목록 생성을 통과했다. 의존성 222개 버전의 조회 시점 알려진 OSV 검출은 0개이며 변조 체크섬은 예상한 Gradle 종료 코드 1로 차단됐다. 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다.

[확정 증적](../reference/reporting-review-verification-2026-10-23.json)에 실제 명령·개별 사례·분리 집계·자원 정리·공식 본문 대조·현재 코드 118개와 별도 6개 대조·한계를 남겼다.

첫 461개 EDD 선택 실행이 통과한 후 마지막 페이지·만료 요청·다른 문맥 암호문·다른 업무 서명·기존 AML 종결 우회 차단을 보강했다. 중간 실행 수는 최종 합계에 더하지 않는다. 신규 보고 검토의 거래 제한 검사는 실제 TradingAccessPolicy.requireNewOrder를 같은 업무 트랜잭션에서 호출하며, 보고 검토 고객으로 HTTP 주문부터 엔진 매칭까지 새로 연결한 전용 시험은 아니다. 별도 기존 실제 시장 연결과 구분한다.

## 운영 전 미충족과 다음 단계

실제 보고책임자 임명·전담 조직 권한/배정·교대·미처리/만료/보고 필요 사건의 적시 인수, 보고서 필수 필드·관련 실제 금융거래 자료·최초 의심 인지 시점·법정 기한 달력·긴급 제출·외부 제출/접수/보완·실패 재처리와 증적이 필요하다. 앱 승인 시각을 법적 기산점이라고 단정하거나 새 승인으로 제출 기한을 늦추면 안 된다. 승인 만료로 법적 보고 의무가 사라지지도 않는다. 새 회부·신고·현재 근거 변경이 과거 승인된 보고 필요나 외부 제출 의무를 취소하는 것은 아니며, 향후 제출 대기 집계는 현재 사건만이 아니라 미제출 확정 이력을 빠짐없이 다뤄야 한다. 현재 목록은 두 계정의 요청 목록이지 누락 없는 전사 보고 필요/제출 대기 작업 큐가 아니다.

범죄 확정이 아니라 합당한 의심으로 보고하는 업무이며 AML 후보·PEP 분류 하나를 자동 보고/미보고로 만들지 않는다. 다른 의무 보고 사유·외부 기관 신고와 지속 모니터링도 별도 검토해야 한다. 보고와 고객확인 불가 시 신규 거래 거절/관계 종료, 잔여 자산·출금 처리는 서로 다른 의무다. 이번 내부 미보고 승인으로 이를 해소하지 않는다.

실제 Didit·FIU·은행/체인·실물 보안키·법정 보고 형식/기한 준수·국내 전체 규제·사업 신고·ISMS 인증·운영 배포를 검증하지 않았다. 새 업무 전용 JVM 강제 종료 시나리오는 없으며 기존 프로세스 검증과 신규 감사 롤백/경합을 구분한다. 두 JAR/JMH 컴파일은 실제 처리량 재측정이 아니다. 엔진 단일 스레드 코어·출력 워커·프론트는 변경하지 않고 봇은 0명이다. 디스크/호스트 손실의 외부 복제·백업은 기존 문서의 필요 조건을 유지한다.

## 관련 커밋과 연결 문서

- [`5580c4b`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/5580c4bea57bb1271736c77318cc85c1e15a533d): 비공개 보고 여부 검토·지정 인물·현재 회부/전체 제공·별도 업무 서명과 신규 테스트 48개.

[028 제한적 오탐 종결](028-aml-false-positive-resolution.md), [027 후보 근거와 조사](027-signed-aml-evidence-and-investigation.md), [026 전체 근거](026-edd-dossier-and-independent-inspection.md), [공통 EDD 진행](../../../docs/roadmap/edd-case-review.md)을 함께 읽는다.
