# 주소 증빙의 사람 관찰과 독립 서명 검토

제출된 실제 PNG에서 운영자가 읽은 이름·주소·발급기관·문서 종류·발급일을 기록하고, 다른 운영 인물이 같은 자료를 열람해 독립 관찰값과 업무별 보안키 서명으로 검토를 확정한다. 현재 고객 신고·KYC 평가·사건 변경 번호·자료·세션과의 연결을 최종 결정 때 다시 확인한다.

- 기준일: 2026-10-23. 구현·격리 로컬 검증 기록이다.
- [022 증빙 수집·암호화 보관·열람](022-edd-evidence-storage-and-access.md) 위에 **RESIDENTIAL_ADDRESS 문서의 내용 대조**를 추가한다.
- 문서 발급기관 확인·위변조 판정·실제 거주·자금 원천/재산·PEP/제재 해소·EDD 종결·거래 허용을 완료한 상태가 아니다. 최종 검토가 승인돼도 사건 OPEN과 거래 제한을 유지한다.

## 문제와 기존 동작

022는 실제 바이트를 보호하고 권한 있는 사람만 열람하게 했지만, 무엇을 읽고 무엇이 신고와 달랐는지 남기지 않았다. 기존 감사 기록에는 자료 ID와 열람 사유만 있어 같은 세션이 같은 현재 자료를 제공받았는지 승인과 직접 연결할 수도 없었다.

이번에는 주소 문서라는 작은 업무를 요청→자료 제출→각 인물의 자료 제공→최소 신고 열람→사람 관찰→독립 재입력→실제 서명→불변 결론까지 연결한다. 단순한 확인 체크박스나 임의 증빙 번호를 진위 검증으로 기록하지 않는다.

## 기존 제품과 공식 근거

2026-10-23 공개 공식 자료를 다시 확인했다. 제품의 비공개 DB 구조·내부 승인 절차를 추측하지 않는다.

| 출처 | 확인한 내용·버전 | 이번 적용과 한계 |
|---|---|---|
| [Kraken 주소 문서 요구사항](https://support.kraken.com/articles/360000672203-document-requirements-for-verification) | 갱신 2026-08-19. 이름·주소·최근 발급일, 금융기관 자료·공과금·정부 문서 등 | 문서 종류·관찰 필드와 시점 대조를 명시한다. 프로젝트의 90일은 Kraken의 3개월과 동일하지 않으며 국내 법정 기간이 아니다 |
| [Coinbase 계정 검토](https://help.coinbase.com/en/coinbase/managing-my-account/other/account-review) | 주소·원천 등 필요한 자료를 요청하고 만료·불명확·신고를 뒷받침하지 못하는 자료는 추가 확인 | 제출과 검토 결론을 분리하고 불일치·부족을 보존한다 |
| [FFIEC CIP 검사 절차](https://bsaaml.ffiec.gov/manual/AssessingComplianceWithBSARegulatoryRequirements/01_ep) | 열람일 기준 공개 절차. 사용한 문서·방법·결과와 중요한 불일치의 검증 기록 | 자료·사람 관찰·비교 결과·결론을 연결한다. 미국 은행 검사 기준을 한국 거래소의 법적 의무로 복사하지 않는다 |
| [FINRA 보관 NASD 03-34](https://www.finra.org/sites/default/files/NoticeDocument/p003246.pdf) | 2003-06 역사 자료. 증권사 CIP의 문서/비문서 확인·기록 구분 | 금융권에서 확인 방법과 결과를 구별하는 설계 배경이다. 오래된 자료만으로 미국 현행 법령 전체를 확인했다고 주장하지 않는다 |
| [특금법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887) | 시행 2026-10-02·법률 21857호 표기 | 위험 기반 확인과 확인 불가 시 제한을 대조한다. 주소 문서 내용 대조 하나로 고객 확인 전체를 충족하지 않는다 |
| [개인정보 보호법 제29조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0029&lsiSeq=283839&urlMode=lsScJoRltInfoR) | 시행 2026-09-11·법률 21445호 표기 | 최소 개인 정보 열람·암호화·접속 증적을 연결한다. 전체 시행령/고시·보존/파기·조직 조치와 인증 심사 완료가 아니다 |

Fed의 증권사 CIP 페이지는 탐색 목차 중심으로 제공돼 본문 전체를 검증한 근거로 사용하지 않았다. 접근 가능한 공식 FINRA PDF·FFIEC 문서와 국내 조문을 구분해 읽었다. 전체 사업 신고/인증·조직·하위 규정 대조는 [근거표](../reference/access-control-compliance.md)의 남은 항목으로 유지한다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·대안·한계 |
|---|---|---|
| 주소 문서부터 완성 | 실제 신고의 이름·주소와 비교할 필드와 실패 조건을 작게 정의 | 급여 유입·재산 형성·실소유자·제재 후보의 판단은 별도 업무다. 같은 확인 양식으로 모두 처리하지 않는다 |
| 두 인물의 관찰 입력·실제 서명 | 관찰 내용·확정한 담당자·자료를 추적하고 서로 다른 값을 읽으면 승인을 거부 | 중복 입력 비용과 철자/날짜 오류가 늘어난다. 두 사람이 실제 읽었거나 공모하지 않았음을 코드로 입증할 수는 없다 |
| 이름·주소만 최소 열람 | 생일·직업·신원 번호 등 불필요한 신고 원문 노출을 줄임 | 운영자가 전체 신고를 한 번에 볼 수 없다. 자료의 원본 다운로드는 여전히 없다 |
| 개인 관찰은 암호화, 승인에는 HMAC 지문 | 서명·감사·목록에 이름/주소의 공개 해시나 평문을 남기지 않는다 | 증빙 암호화 키와 CDD 지문 키 수명을 함께 관리해야 한다. 키가 달라지면 옛 자료로 승인하지 않는다 |
| 사건 변경 번호로 보수적 무효화 | 새 자료·평가·결론 뒤의 오래된 서명을 차단 | 다른 문서의 미결 검토도 다시 열람·요청해야 한다. 검토 병렬성보다 현재 근거의 명확성을 우선한다 |
| 전용 EDD_ADDRESS_REVIEW 승인 종류 | 기존 EDD/역할/복구 서명을 주소 검토에 재사용하지 못함 | 새 CHECK를 지원하는 스키마가 필요하다. 이전 5종 제약을 자동 변경·이전하지 않는다 |
| 수명·범위가 정해진 기존 Spring/JDBC/JCE/WebAuthn 재사용 | 새로운 큐·OCR·공급사·범용 규칙 엔진 없이 한 업무를 연결 | 자동 진위 판정·발급기관 연락/검증 시스템을 제공하지 않는다 |

엔진·프론트 제품 코드는 변경하지 않는다. 플랫폼의 고객 행/사건 행 잠금과 검토 처리는 단일 스레드 Rust 체결 코어의 공유 락 제거와 다른 경계다. 기존 DB·Kafka·개발 서비스를 재기동하거나 사용자 자료를 반입하지 않는다.

## 업무 흐름과 상태

1. 고객이 현재 평가에 요청된 RESIDENTIAL_ADDRESS 자료를 제출한다. 일반 자료 수신 상태는 계속 `RECEIVED_UNVERIFIED`다.
2. 요청한 OPERATOR와 별도 인물의 APPROVER가 각자 최근 MFA 세션에서 같은 자료의 열람본을 받는다. 별도 불변 영수증에 자료·인물·사용자 버전·세션·평가·사건 변경 번호·키·시각을 저장한다.
3. `X-Evidence-View-Id`를 context API에 보내 현재 신고의 이름·주소만 조회한다. 다른 인물/세션/자료·오래된 영수증·새 신고/평가/사건에는 사용할 수 없다. 허용된 프론트 출처가 영수증 헤더를 읽을 수 있게 CORS 노출 헤더도 명시한다.
4. 운영자가 문서 종류·표시된 이름/주소·발급기관·발급일을 입력한다. 서버가 현재 신고와 대조한 이유 목록을 만들고 암호화 관찰 내용·현재 선언/평가/사건·작성자 버전·기한을 고정한 요청을 저장한다.
5. 승인자가 자신의 영수증으로 inspect API를 호출하면 현재 최소 신고와 작성자의 관찰 내용을 볼 수 있다. 이 열람도 최근 인증·다른 인물·필수 감사가 필요하다.
6. 승인하려면 승인자가 읽은 다섯 값을 다시 제출해야 한다. 정규화 후 작성자의 값과 모두 같아야 한다. 불일치 관찰 결과 자체에 동의하는 승인도 가능하며, 이를 고객 검증 성공으로 해석하지 않는다. 거절에는 관찰값을 꾸며 넣지 않고 null을 보낸다.
7. 보안키 옵션은 업무 종류·검토 ID·고정 payload 해시·승인/거절·세션·기한에 결합한다. 실제 서명 확인과 결정·사건 OPEN 재검토·revision 증가·일회용 옵션 소비·공개키 카운터·감사는 같은 트랜잭션에서 확정한다.

서명은 보고서의 관찰 지문·현재 근거·방향에 결합한다. 승인자의 영수증은 최종 실행에서도 같은 인물/세션/자료의 유효한 최근 제공 기록인지 검사하고 결정에 보존한다. 특정 승인자 영수증 UUID를 별도 서명 payload로 만드는 추가 확인 요청 계층은 두지 않았다. 같은 자료·같은 현재 근거에 대한 자기 영수증으로만 바꿀 수 있다.

### 비교 결과와 기한

| 항목 | 정책과 의미 |
|---|---|
| 문서 종류 | UTILITY_BILL, BANK_STATEMENT, GOVERNMENT_RESIDENCE 세 종류. 허용 종류가 진위를 보증하지 않는다 |
| 이름 | 기존 CustomerDeclaration.name 정규화. 유니코드 NFC·공백 정리·대문자, 발음·번역·유사도 추측 없음 |
| 주소 | 기존 신고와 같은 NFC/공백 정규화, 문장 부호·도로명 별칭·번역을 임의 변환하지 않음 |
| 날짜 | 서울 기준 현재 날짜에서 90일 전까지. 미래 발급일과 더 오래된 날짜는 사유로 보존 |
| 사유 목록 | NAME_MISMATCH, ADDRESS_MISMATCH, DOCUMENT_OUTDATED, FUTURE_ISSUE_DATE. 비어 있으면 입력 관찰 내용상 일치 |
| 영수증/현재 인증 | 둘 다 최근 5분. 같은 세션·인물·사용자 버전·현재 평가·사건 변경 번호 필요 |
| 검토 요청/업무 서명 | 요청 10분, 서명 옵션 2분·일회성. 같은 사건 변경 번호에는 미결 요청 하나, 고객당 하루 최대 10개 |
| 승인 의미 | **관찰 내용과 비교 결과에 대한 독립 검토 승인**. 주소/신원/EDD/거래 승인 아님 |

전부 초기 제품 정책이다. 법정 보존 기간이나 해외 거래소의 수용 기준과 같다고 설명하지 않는다. 지난 날짜도 삭제·정정하지 않고 부정적인 검토 사유로 남긴다. 읽을 수 없는 문서에서 값을 추측해 입력하는 업무를 지원하지 않는다. 기존 자료 대기/준법 회부 경로로 남기고 새 자료를 요청한다.

목록의 PENDING/EXPIRED/APPROVED/REJECTED는 보고서의 당시 처리 상태다. 과거 APPROVED가 현재 유효한 주소나 거래 허가를 뜻하지 않는다. 신규 신고·새 서명 평가·KYC 판정 변경·추가 자료·다른 사건 결론은 오래된 미결 요청을 무효화한다. 승인과 거절 모두 사건 revision을 올리고 OPEN으로 남긴다.

## API와 저장 계약

기본 경로는 `/api/admin/edd`다.

| 메서드·경로 | 권한과 반환 |
|---|---|
| POST `/evidence/{id}/address-review/context` | OPERATOR/APPROVER·자신의 최근 viewId. 최소 신고 이름/주소·현재 근거 |
| POST `/evidence/{id}/address-reviews` | OPERATOR·Idempotency-Key. viewId/assessmentId/caseRevision/facts로 고정 보고서 |
| POST `/address-reviews/{id}/inspect` | 별도 APPROVER·자신의 viewId. 현재 신고와 작성자 관찰, 필수 감사 |
| POST `/address-reviews/{id}/approve/options`, `/reject/options` | APPROVER. payloadHash/viewId/observed(거절은 null) |
| POST `/address-reviews/{id}/approve`, `/reject` | 동일한 내용과 실제 보안키 proof. 실행 전 현재 조건 재검사 |
| GET `/customers/{user}/address-reviews?page=0` | 최근 MFA OPERATOR/APPROVER/AUDITOR. 평문 관찰 없는 메타데이터, 페이지당 50개 |

[스키마](../../src/main/resources/edd/address-review-schema.sql): `edd_evidence_views`, `edd_address_reviews`, `edd_address_decisions`는 UPDATE/DELETE/TRUNCATE를 거부한다. 결정은 review_id PK로 하나만 확정된다. 사용자들을 ID 순서로 잠근 뒤 사건을 잠그고 결정 상태를 다시 읽는다. 오래된 조회로 동시 승인·거절의 두 번째 결론을 쓰지 않는다.

이전 5종 승인 CHECK는 새 주소 검토를 지원하지 않으면 기동을 거부한다. 자동 ALTER·호환 승인·옛 열람/관찰의 추측 반입은 없다. 기존 실제 개발 DB는 바꾸지 않았고 지원 스키마 준비·금융/감사 이력 보존·승인된 배포 절차는 운영 전 조건이다. 비웹 운영자 관리 JAR가 업무 스키마를 준비하거나 새 워커를 띄우지 않는 회귀도 유지한다.

## 실제 코드와 이해에 필요한 문법

### 두 사람이 읽은 값을 같은 방식으로 비교한다

출처: [AddressFacts.java](../../src/main/java/dustin/cex/domains/edd/AddressFacts.java#L20). 메서드 일부이며 독립 실행 예제가 아니다.

```java
    var reasons=new ArrayList<Reason>();
    if(!holderName.equals(declaredName)) reasons.add(Reason.NAME_MISMATCH);
    if(!address.equals(declaredAddress)) reasons.add(Reason.ADDRESS_MISMATCH);
    if(issuedOn.isBefore(today.minusDays(90))) reasons.add(Reason.DOCUMENT_OUTDATED);
    if(issuedOn.isAfter(today)) reasons.add(Reason.FUTURE_ISSUE_DATE);
    return List.copyOf(reasons);
}
```

`List.copyOf`는 결과 목록을 외부에서 바꾸지 못하게 만든다. TypeScript에서 배열을 반환한 뒤 호출자가 push할 수 있는 형태와 다르다. LocalDate는 시간대 없는 날짜이며 비교 기준 today를 서울 날짜로 만든다. 90일보다 오래되거나 미래여도 관찰 사실을 버리지 않고 이유를 덧붙인다.

### 열람 기록은 당시 현재 근거와 묶는다

출처: [EvidenceService.java](../../src/main/java/dustin/cex/domains/edd/EvidenceService.java#L91). 메서드 일부이며 독립 실행 예제가 아니다.

```java
// 실제 반환 시점의 평가·사건·운영 인물·세션을 묶는다. 옛 열람을 새 심사의 근거로 재사용하지 않는다.
var c=jdbc.queryForMap("SELECT latest_assessment,revision FROM edd_cases WHERE id=?",r.get("case_id"));UUID receipt=UUID.randomUUID();
jdbc.update("INSERT INTO edd_evidence_views(id,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id) VALUES(?,?,?,?,?,?,?,?,?)",
    receipt,id,actor,operator.getOperatorPersonId(),operator.getVersion(),sid,c.get("latest_assessment"),c.get("revision"),crypto.keyId());
audit.record(actor,"EDD_EVIDENCE_VIEWED","edd-evidence:"+id,"SUCCESS",reason.name());return new View(id,receipt,view);
```

영수증과 기존 열람 감사가 같은 tx.execute 안에 있다. 감사가 실패하면 영수증도 롤백되고 이미지가 반환되지 않는다. UUID는 추적 식별자이며 영수증 자체가 권한 토큰은 아니다. 사용할 때마다 현재 사용자·세션·평가를 DB에서 검사한다.

### 관찰 원문 대신 지문을 승인 내용에 넣는다

출처: [AddressReviewService.java](../../src/main/java/dustin/cex/domains/edd/AddressReviewService.java#L75). 메서드 일부이며 독립 실행 예제가 아니다.

```java
    String hash=verifier.digest(json.valueToTree(p));byte[] encrypted=crypto.encrypt(factsContext(p),json.writeValueAsBytes(facts));
    jdbc.update("INSERT INTO edd_address_reviews(id,case_id,evidence_id,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,current.caseId(),evidence,user,actor,key,json.writeValueAsString(p),hash,encrypted,Timestamp.from(expires));
    mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,id,actor,maker.getVersion(),hash,expires);
    audit.record(actor,"EDD_ADDRESS_PROPOSED","edd-address-review:"+id,"SUCCESS",p.reasons().isEmpty()?"CONTENT_CONSISTENT":"FURTHER_CHECK_REQUIRED");return read(id);
}));
```

`factsMac`은 CDD의 독립 지문 키로 만든 HMAC이고, facts_cipher는 증빙 키의 AES-GCM 암호문이다. 낮은 엔트로피의 주소·이름에 공개 SHA-256만 쓰지 않는다. 암호화 문맥은 ADDRESS_REVIEW_V1·보고서/고객/자료/평가/revision에 묶여 다른 이미지나 관찰 행의 암호문을 옮겨 쓸 수 없다. 기존 Spring JDBC와 트랜잭션을 사용한다.

### 승인자의 재입력이 달라지면 서명을 소비하지 않는다

출처: [AddressReviewService.java](../../src/main/java/dustin/cex/domains/edd/AddressReviewService.java#L127). 메서드 일부이며 독립 실행 예제가 아니다.

```java
private void independent(Review r,boolean approve,AddressFacts observed) {
    // 승인자는 같은 열람본에서 독립적으로 읽은 값을 보낸다. 단순 승인 체크박스나 작성자의 열람 영수증으로 대신하지 않는다.
    if(approve) {if(observed==null || !facts(r).equals(observed.validated())) throw denied(HttpStatus.CONFLICT);}
    else if(observed!=null) throw denied(HttpStatus.BAD_REQUEST);
}
```

Java record의 equals는 필드별 값 비교다. 두 관찰을 같은 규칙으로 validated한 뒤 문서 종류·이름·주소·발급기관·날짜를 비교한다. 이 검사는 옵션 발급과 최종 결정에서 반복한다. 서명 이후 관찰값을 바꾸면 검증을 거부하고 인증 옵션을 소비하지 않는다. 사람의 실제 주의력이나 발급기관 진위를 확인하는 OCR/외부 호출은 아니다.

### 확정은 서명과 감사까지 함께 커밋한다

출처: [AddressReviewService.java](../../src/main/java/dustin/cex/domains/edd/AddressReviewService.java#L98). 메서드 일부이며 독립 실행 예제가 아니다.

```java
mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,id,hash,approve,proof);
var checker=users.findById(actor).orElseThrow();
jdbc.update("INSERT INTO edd_address_decisions(review_id,approver_id,approver_person,view_id,approved) VALUES(?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),view,approve);
// 검토 결과도 새로운 사실이다. 오래된 사건 결론은 무효화하되 금융 위험을 해소한 것으로 취급하지 않는다.
jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",current.caseId());
audit.record(actor,"EDD_ADDRESS_DECIDED","edd-address-review:"+id,"SUCCESS",(approve?"APPROVED_CONTENT_REVIEW:":"REJECTED:")+hash);return read(id);
```

`authorizeApproval`은 열린 업무 트랜잭션에서 별도 승인 종류의 서명을 확인하고 인증 옵션·공개키 카운터를 갱신한다. 뒤의 감사나 결정 INSERT가 실패하면 같이 롤백된다. 성공할 때도 state는 OPEN이며 valid_until은 지운다. 단일 코어 엔진에 질의하거나 상태 락을 추가하지 않는다.

## 작성한 테스트

가상 고객·합성 PNG·관찰 입력을 사용한다. 신원 생성 유료 HTTP만 기존 대역이며 실제 KYC HMAC·PostgreSQL·WebAuthn ECDSA 서명·보안 필터를 사용한다. 이미지를 OCR로 읽거나 발급기관을 조회한 테스트가 아니다. 실제 사람이 읽은 값을 입력한다는 업무를 합성 입력으로 재현한다.

### AddressReviewIntegrationTest · 신규 42개 실행 사례

[테스트 파일](../../src/test/java/dustin/cex/domains/kyc/AddressReviewIntegrationTest.java)

| 함수 | 시나리오와 기대 결과 |
|---|---|
| `contextExposesOnlyNameAndAddressAfterOwnedFreshView` | 자기 현재 열람 뒤 이름/주소만 조회·감사·기본 출력 비밀 배제 |
| `independentTypedObservationAndSignatureApproveOnlyContentReview` | 별도 인물의 재입력·실제 서명 승인, 사건 OPEN·거래 차단 유지 |
| `negativeObservationCanBeIndependentlyConfirmedWithoutOpeningTrading` | 이름/주소/오래된 날짜의 부정적 관찰도 보존·합의·차단 유지 |
| `factsAreEncryptedAndOnlyIndependentInspectionReturnsThem` | DB 관찰 암호화·메타데이터 평문 배제·독립 inspect만 원문 반환 |
| `sourceOfFundsEvidenceIsNotProcessedAsAddress` | 자금 원천 자료를 주소 검토로 우회 거부 |
| `missingReceiptAndAnotherPersonsReceiptCannotAuthorizeReview` | 없는/다른 인물 영수증으로 맥락 조회·요청·승인 거부 |
| `approverMustSubmitIndependentMatchingFields` | 독립 입력 누락·다르게 읽은 주소면 옵션 미발급 |
| `alteredObservationAfterOptionsDoesNotConsumeProof` | 옵션 뒤 발급기관 변경 거부·서명 미소비·정상 값 재시도 성공 |
| `rejectionRequiresNoInventedObservationAndPreservesHold` | 거절에 관찰값을 꾸며 넣으면 거부·정상 거절도 제한 유지 |
| `samePersonDifferentAccountCannotIndependentlyInspect` | 다른 계정이어도 같은 실제 인물의 독립 검토 거부 |
| `requestKeyBindsExactObservationAndCurrentFacts` | 동일 요청 재조회·내용 변경/같은 현재 근거 미결 중복 거부 |
| `newDeclarationInvalidatesOptionsAndOldReceipt` | 옵션 이후 신고 변경은 결정/옛 조회 거부 |
| `newSignedAssessmentCannotReuseOldDocumentReview` | 새 실제 서명 평가 뒤 옛 보고서 결정 거부 |
| `newEvidenceInvalidatesPendingReviewAndRequiresFreshView` | 다른 자료 도착도 옛 보고서/영수증 거부·새 열람/요청 가능 |
| `requesterVersionChangeInvalidatesPendingReview` | 작성자 버전 변경은 이전 서명 근거 무효 |
| `oldSessionCannotReadCustomerContextOrApprove` | 회수/5분 초과 MFA로 조회·결정 거부 |
| `proposalAuditFailureRollsBackCipherBindingAndReview` | 요청 감사 실패 시 관찰/결합 저장 롤백·재시도 성공 |
| `viewAuditFailureRollsBackNewReceipt` | 열람 감사 실패 시 새 영수증 0개 증가 |
| `inspectionAuditFailureNeverReturnsPrivateFacts` | inspect 감사 실패 시 개인 관찰 반환 없음 |
| `decisionAuditFailureRollsBackChallengeCounterDecisionAndRevision` | 감사 실패 시 결정/사건/옵션/카운터 롤백·같은 서명 재시도 |
| `approvalProofCannotAuthorizeRejectionOrReplay` | 승인 서명으로 거절·확정 뒤 재실행 거부 |
| `otherEddNamespaceCannotConsumeAddressSignature` | 주소 서명을 기존 EDD 종류로 소비할 수 없음 |
| `concurrentApprovalAndRejectionCommitOneConclusion` | 다른 실제 인물 동시 승인/거절 최종 1개 |
| `concurrentProposalKeysNeverCreateTwoPendingReports` | 같은 키/다른 키 동시 요청 두 실행 사례, 미결 보고서 1개 |
| `immutableViewsReportsAndDecisionsRejectMutation` | 영수증/보고서/결정의 UPDATE/DELETE/TRUNCATE 거부 |
| `auditorSeesMetadataButCannotReadFactsAndCustomerHasNoAdminAccess` | 감사자 메타데이터만, 개인 관찰·고객 운영 접근 차단 |
| `completeHttpReviewUsesStoredPayloadAndOwnObservation` | 실제 HTTP 요청→옵션→서명→결정, JSON/캐시 정책 확인 |
| `expiredViewIsRejectedEvenWithFreshMfa` | 별도 과거 영수증 표본, 현재 MFA라도 만료 열람 거부 |
| `customerKycChangeCannotLeaveOldReviewExecutable` | 현재 KYC 판정 지문 변경이면 실행 거부 |
| `invalidOriginOrMissingUserVerificationDoesNotConsumeDecision` | 잘못된 출처/사용자 확인 없음 두 실행 사례, 서명 미소비 후 정상 재시도 |
| `expiredImmutableReviewCannotIssueOptions` | 과거 메타데이터 표본 EXPIRED·옵션 발급 전 거부 |
| `previousFiveKindSchemaFailsWithoutRewritingAnyHistory` | 격리 트랜잭션에서 옛 5종 CHECK 재현, 기동 가드·롤백·이력 보존 |
| `anotherEvidenceReceiptCannotSubstituteForReviewedImage` | 다른 자료 영수증으로 같은 검토 진행 불가 |
| `differentHashCannotConsumeValidSignature` | 변경한 payload 해시 거부·정상 해시 재시도 |
| `contextAuditFailureDoesNotReturnDeclaredPersonalData` | 최소 신고 열람도 감사 실패 시 반환 없음 |
| `sameRequestKeyCannotBeReboundToAnotherView` | 같은 키를 새 열람 영수증에 바꿔 사용 불가 |
| `httpViewReceiptEnablesOnlyMinimalAuditedContextAndInspection` | 실제 제공 헤더→맥락→inspect의 최소 필드·캐시 정책 |
| `existingEddSignatureCannotApproveAddressReviewOrBeConsumedThere` | 기존 EDD 서명의 역방향 재사용 차단·원래 업무에서 재시도 성공 |
| `allowedFrontendCanReadReceiptHeaderAndForeignOriginCannot` | 허용 프론트 출처에 영수증 헤더만 노출·다른 출처 거부 |
| `approvedAddressReviewCannotBypassEvidenceHoldOnStandardCustomer` | 현재 CDD STANDARD라도 승인된 주소 검토로 옛 신원 정정 종결·거래 제한 우회 불가 |

### AddressFactsTest · 신규 12개 실행 사례

[테스트 파일](../../src/test/java/dustin/cex/domains/edd/AddressFactsTest.java)

| 함수 | 시나리오·기대 결과 |
|---|---|
| `normalizesUnicodeSpacesAndNameCaseButKeepsAddressPunctuation` | NFC/공백/이름 대소문자 정리·주소 문장 부호 보존·기본 출력 비밀 배제 |
| `acceptsFreshnessBoundaryAsPolicyNotLegalPeriod` | 0/1/89/90일 경계 네 실행 사례 |
| `recordsEveryMismatchAndOldDateInsteadOfPretendingToVerify` | 이름·주소·91일의 세 사유를 모두 보존 |
| `futureDateHasIndependentReason` | 미래 발급일 사유 |
| `rejectsEmptyOrHiddenControls` | 빈 값/개행/숨김 제어 문자 세 실행 사례 거부 |
| `differentAddressPunctuationIsNotSilentlyMerged` | 주소 12와 1-2를 같다고 추측하지 않음 |
| `requiresDocumentTypeIssuerNameAndBoundedDate` | 종류/발급기관/날짜 누락·비정상 연도 거부 |

함수 안의 여러 assert를 개별 테스트로 가산하지 않는다. 기존 네 승인 업무 62개는 기존 EnumSource 네 종류 그대로 실행한다. 새 enum은 기존 switch의 명시적인 전용 검증 분기에 추가하고 주소 검토는 실제 전용 흐름으로 검증한다.

## 실행 명령과 결과

```sh
python3 scripts/verify_edd.py
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

Temurin 21·macOS·실행 소유 동적 루프백 PostgreSQL 16과 필요한 격리 Kafka·봇 0명으로 검증한다. 기존 5432/5433/5435와 9092를 쓰지 않는다. 확정 수치·명령별 결과·정리·실제 테스트 사례는 [검증 JSON](../reference/address-review-verification-2026-10-23.json)에 기록한다. JMH는 컴파일이며 새 성능 측정이 아니다. 중간 전용 128/135개 통과를 최종 합계에 중복 가산하지 않는다.

기존 deprecated/unchecked 컴파일·Mockito/JVM 클래스 공유 안내는 남아 있다. 전용 과정에서 최종 코드 점검으로 개인 관찰의 해독/JSON 오류 메시지 보호와 허용 프론트의 영수증 헤더 노출·양방향 업무 서명 거부를 보강한 뒤 최종 전용 검증을 다시 실행한다.

## 남은 범위

다음은 자금 원천/재산 증빙에 대해 특정 거래·금액·시점·이름·발급 주체·신고된 소득/자금 흐름을 구체적으로 연결하고 독립 검토하는 단계다. 문서 내용 일치와 발급기관의 확인·증거의 신뢰성·실제 거래와의 연결을 분리한다. 가짜 외부 검증 ID나 사람이 누른 체크박스를 자동 진위 검증이라고 표시하지 않는다.

이번 주소 승인 뒤에도 증빙 요청이 있는 사건은 기존 신원 정정 종결 경로를 사용할 수 없다. 전체 위험 사유·PEP/제재 후보·실소유자·국가/목적·지속 감시·고위 경영진·STR/관계 종료/자산 처리와 종결 정책은 남아 있다. 현재 승인된 주소 관찰을 금융 예외 권한으로 사용하지 않는다.

법정 최소 수집·고지·원문 마스킹·보유/분리 보존·법적 보류·파기·키 보관/교체·KMS/HSM·반출/DLP·팀별 사건 배정·실제 조직/인물 확인은 운영 전 과제다. 불변 트리거는 영구 보존 정책이 아니다. 이미 결정한 개인 관찰의 별도 역사 열람 API는 제공하지 않으며 감사자에게는 메타데이터만 제공한다.

합성 입력 검증은 실제 사람이 문서를 읽었거나 내용이 진실임을 입증하지 않는다. 실제 공급사/발급기관·고객 자료·실물 보안키·운영 심사·새 주소 검토 전용 JVM 강제 종료는 검증하지 않았다. 이미지 파서 격리·악성 코드 검사·TLS/요청 시간/전역 자원 제한은 022의 운영 조건을 유지한다. 디스크 손실 외부 백업/복제도 사용자 지시대로 문서상 필요 조건으로 유지한다.


## 확정 수치와 관련 커밋

선택 JUnit **704개**, 실패·오류·제외 0개다. 전용 138개(신규 주소 검토 54+기존 EDD/증빙 84), KYC/CDD 150, 승인/접근 190, MFA 42, 금융 183, 실제 엔진 연결 1개다. 기존 별도 프로세스 **15개**도 통과했다. 두 JAR·JMH 컴파일·222개 의존성 버전의 알려진 OSV 검출 0·체크섬 변조 차단을 확인했고 실행 소유 컨테이너/임시 DB를 정리했다. 새 주소 검토 전용 JVM 강제 종료 시나리오는 0개다.

구현·테스트는 [`9c8ba2d`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9c8ba2d)에 담았다. 문서와 확정 증적은 별도 커밋으로 같은 작업 단위에서 일반 푸시한다. 작성자·커미터는 Gihoon이다. 현재 소스 발췌 79개를 다시 대조했고 022의 열람 영수증 추가로 달라진 예제도 갱신했다.
