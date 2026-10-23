# EDD 증빙의 요청·암호화 보관과 필요한 사람의 감사 열람

현재 검토 사건에 필요한 자료만 요청하고 고객이 자기 요청에 실제 PNG를 제출한다. 원본은 격리해 암호화 보관하고 열람본을 별도로 만든다. 최근 운영 인증과 역할·담당 인물·업무 사유를 확인하고, 감사가 저장된 경우에만 열람본을 반환한다.

- 기준일: 2026-10-23. 구현·격리 로컬 검증 기록이다. 운영 배포·실제 고객 자료 제출·독립 사실 검증 완료 기록이 아니다.
- 앞선 [사건과 독립 서명](021-edd-case-and-independent-review.md) 위에 자료 수집·보관·열람 단계를 추가한다. 엔진 제품 코드·체결 코어·프론트 제품 코드는 수정하지 않는다.

## 문제와 기존 동작

021은 실제 KYC 서명과 CDD 평가를 근거로 사건을 추적하지만, 고객의 급여·자금 유입·재산 형성·주소 같은 추가 확인 자료를 받을 경로가 없었다. 임의의 외부 문서 ID를 입력하고 검증됐다고 표시하면 존재 여부·내용·열람 이력을 입증할 수 없다.

이번에는 요청부터 실제 바이트 저장과 제한된 열람까지 연결한다. 자료는 `RECEIVED_UNVERIFIED`다. 파일 존재·복호화 성공·이미지 판독 성공은 소득·재산·주소의 진실성 확인과 다르다. 제출만으로 STANDARD나 거래 가능 상태로 바꾸지 않는다.

## 기존 제품과 공식 자료

2026-10-23 공식 공개 자료를 다시 대조했다. 제품 내부 DB·암호화·심사 구현을 추측하지 않는다.

- [Coinbase 계정 검토](https://help.coinbase.com/en/coinbase/managing-my-account/other/account-review): 계정·거래·지역에 따라 필요한 원천·재산·주소 자료를 로그인한 고객에게 요청한다. 필요한 자료 종류를 정한 요청과 고객 제출 경로를 분리하는 근거다.
- [Kraken 자금·재산 원천 자료](https://support.kraken.com/articles/source-of-wealth-or-source-of-funds-documents): 특정 자금의 원천과 전체 재산 형성을 구분하고 자료와 신고 내용의 일치를 확인한다. 공개 문서 갱신일은 2025-03-31이다. 여러 파일 형식을 지원하지만 이번 구현은 PNG에 한정한다. 해외 사업자의 수용 기준·기간은 국내 법정 의무로 복사하지 않는다.
- [OWASP 파일 업로드 지침](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html): 헤더를 믿지 않는 내용 검사, 허용 형식, 크기·권한·비공개 저장·재작성·악성 코드 검사 등 여러 통제를 함께 제시한다. 이미지 재작성도 완전한 악성 코드 제거를 보장하지 않는다.
- [Java 21 ImageReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReader.html): 픽셀 읽기 전 폭·높이를 조회하고 메타데이터 무시 옵션을 사용한다. JDK 기본 PNG 코덱을 사용하고 ImageIO 입력·출력 모두 메모리 스트림으로 고정한다.
- [금융정보분석원 고객 확인](https://www.kofiu.go.kr/kor/policy/amls05.do)과 [특금법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887): 위험에 따른 확인과 확인 불가 시 업무 제한의 근거다. 확인한 조문 표기는 시행 2026-10-02·법률 21857호다. 자료 한 장을 받았다고 의무를 모두 이행한 것으로 해석하지 않는다.
- [개인정보 보호법 제29조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0029&lsiSeq=283839&urlMode=lsScJoRltInfoR), [제21조 제3항](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1029335627): 시행 2026-09-11·법률 21445호 표기를 확인했다. 안전조치와 법률상 보존 자료의 분리 관리를 대조한다. 후자는 제3항만 열람한 링크이며 제21조 전체·세부 고시·정확한 업종별 보존/파기 요건은 별도 검토 대상이다.

ISMS-P·가상자산사업자 신고·AML 심사·보존/파기 적법성의 통과 판정이 아니다. 거래소/증권사 공개 근거의 전체 대조는 [접근 통제 근거표](../reference/access-control-compliance.md)를 이어 사용한다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 감수하는 비용·대안 |
|---|---|---|
| 기존 PostgreSQL에 제한한 암호화 바이트 저장 | 외부 저장소·공개 URL·별도 객체와 DB의 부분 커밋 없이 요청·자료·감사를 함께 확정 | DB·백업 용량이 증가한다. 대규모 보관에는 객체 저장소가 유리하지만 지금은 고객별 10 MiB 상한의 작은 자료 경로다 |
| PNG만 받고 픽셀을 다시 인코딩 | PDF·Office·SVG·외부 링크·파일명 처리와 실행성 원문 제공을 제한 | 고객은 PNG로 준비해야 한다. OCR·파일 진위 판정·악성 코드 검사나 파서 취약점 격리의 대체가 아니다 |
| 제출 원본과 열람본을 각각 암호화 | 원래 바이트를 잃지 않고 메타데이터·뒤에 붙은 내용은 열람에서 배제 | 저장량·복호화 관리가 늘어난다. 변환본을 원본 증빙으로 가장하지 않는다 |
| 독립 증빙 키·문맥 인증 | 신고/공급사 연결 키 재사용을 막고 다른 행·고객·원본/열람본 교체를 검출 | 키 분실·교체 시 과거 자료는 읽을 수 없다. 키 보관·교체·보존/파기 절차는 운영 전 준비가 필요하다 |
| 최근 MFA·요청한 운영 인물 또는 별도 승인자·사유·필수 감사 | 단순 운영 역할만으로 다른 담당자의 자료를 읽지 못하고 추적 가능 | 업무 인계·팀별 배정·별도 반출 승인·단말 DLP는 아직 없다. 승인자는 독립 검토 역할로 읽으며 열람 자체는 승인 결정이 아니다 |
| 자료 요청이 있는 사건은 옛 신원 정정 종결 금지 | 추가 확인을 신원 재제출로 건너뛰지 못한다 | 자료 사실 검증·독립 승인 경로가 완성될 때까지 해당 사건은 차단을 유지한다 |

설치된 Spring MVC/JDBC/트랜잭션/보안과 JDK JCE·ImageIO를 확인했다. 새 라이브러리·큐·S3·범용 첨부 프레임워크를 추가하지 않았다. 엔진의 단일 스레드 코어와 무관한 플랫폼 이미지 디코딩에는 동시 2개 상한을 두고 포화 시 429를 반환한다. 코어에 락·디스크·네트워크 작업을 추가하지 않는다.

## 실제 업무 흐름

1. 최근 MFA를 가진 OPERATOR가 현재 평가·목적·Idempotency-Key로 자료를 요청한다. 현재 OPEN/REFERRED 사건만 받는다. 목적은 `SOURCE_OF_FUNDS`, `SOURCE_OF_WEALTH`, `RESIDENTIAL_ADDRESS`다. 임의 자유 입력이나 민감 신원 번호를 수집하지 않는다.
2. 고객은 자기 요청 목록에서 종류·만료·제출 상태만 보고 요청 UUID에 PNG 본문을 제출한다. 다른 고객 요청은 404다. 고객 응답에 내부 AML 사유·제재 후보·운영 인물·암호문·원본 해시를 싣지 않는다.
3. 원본 최대 1 MiB, 각 변 최대 4096, 전체 최대 400만 픽셀을 확인한다. 새 RGB 이미지에 흰 바탕을 만들고 첫 이미지를 그린 뒤 최대 4 MiB PNG 열람본을 만든다. 투명 영역·애니메이션·메타데이터를 원본과 동일하게 보존하는 열람본이 아니다. 원본은 암호화해 따로 보존한다.
4. 원본과 열람본을 AES-256-GCM으로 저장한다. 요청·행·고객·사건·평가·목적·ORIGINAL/VIEW를 AAD에 고정한다. 같은 요청의 재전달은 원본 SHA-256이 같아야 한다. 이 지문은 중복 판단용이며 익명화·발급기관 진위 증명이 아니다.
5. 저장·사건 revision 증가·OPEN 재검토·감사를 같은 트랜잭션으로 확정한다. 기존 보고서/서명은 revision 불일치로 거부된다. 같은 바이트 재시도는 행·revision을 다시 만들지 않는다.
6. 요청한 현재 운영 인물은 실사 사유로, 다른 실제 인물의 APPROVER는 독립 검토 사유로 열람한다. AUDITOR는 메타데이터만 읽는다. 모든 운영 조회도 현재 최근 5분 MFA를 확인한다.
7. 인증·복호화·열람 감사가 모두 성공해 커밋된 뒤 열람본을 반환한다. 응답은 고정 UUID 파일명·attachment·no-store·nosniff·CSP sandbox다. 고객 다운로드·공개 URL·원본 다운로드 API는 제공하지 않는다.

요청은 7일 만료, 고객당 하루 최대 5개, 같은 현재 평가/목적의 미제출 유효 요청 1개다. 운영자 열람은 5분 내 성공 20회다. 고객당 저장 암호문 합계 10 MiB를 제한하며 새 파일은 실제 입력+최대 열람본+태그/nonce를 보수적으로 예약 계산한다. 실제 파일이 작아도 남은 여유가 최대 열람본보다 작으면 일찍 거부될 수 있다. 모두 초기 제품 정책이며 법정 한도·보존 기간이 아니다.

현재 목록은 최근 50개다. 자료 미제출 만료·평가 변경은 EXPIRED/STALE로 읽으며 기록을 자동 수정하지 않는다. 이미 받은 자료는 과거 근거로 보존하고 새 평가에 자동 재귀속하지 않는다. 미제출 요청도 현재 평가가 바뀌면 사용할 수 없다.

## API와 저장 계약

| API | 권한·효과 |
|---|---|
| POST `/api/admin/edd/customers/{user}/evidence-requests` | 최근 MFA OPERATOR. 현재 assessmentId·purpose와 UUID Idempotency-Key |
| GET `/api/kyc/edd-evidence/requests` | 활성 고객의 자기 요청·제출 상태 |
| POST `/api/kyc/edd-evidence/requests/{id}` | 요청 소유 고객. Content-Type image/png의 제한한 바이트 본문 |
| GET `/api/admin/edd/customers/{user}/evidence-requests` | 최근 MFA OPERATOR/APPROVER/AUDITOR. 민감 원문 없는 최근 50개 메타데이터 |
| POST `/api/admin/edd/evidence/{id}/view` | 최근 MFA·담당 인물/독립 인물·고정 열람 사유. 감사 후 변환본 반환 |

[요청/증빙 스키마](../../src/main/resources/edd/evidence-schema.sql)는 UPDATE/DELETE/TRUNCATE를 거부한다. 요청당 증빙 1개를 UNIQUE로 강제하고 바이트 수와 암호문 길이를 CHECK로 맞춘다. 고객/운영자 행을 ID 순서로 잠그고 고객 행 다음 사건 행을 잠근다. 기존 승인 경로와 잠금 순서를 맞춘다.

`EDD_EVIDENCE_KEY`는 별도 생성한 Base64 32바이트 키다. CDD 암호화·지문·Didit 연결 키와 같으면 기동을 거부한다. 미설정이면 증빙 요청·제출·복호화는 503이다. 기존 CDD/KYC·거래 정책을 생략하지 않는다. 테스트 전용 공개 키를 제품 설정으로 옮기지 않는다.

## 실제 코드와 이해에 필요한 문법

### 픽셀을 읽기 전에 크기를 확인한다

출처: [EvidenceImage.java](../../src/main/java/dustin/cex/domains/edd/EvidenceImage.java#L35). 메서드 일부이며 독립 실행 예제가 아니다.

```java
reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
// 헤더의 크기를 픽셀 할당 전에 검사한다. 압축 파일 크기만 제한하면 작은 이미지 폭탄을 놓친다.
if(width<1 || height<1 || width>4096 || height>4096 || (long)width*height>4_000_000) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);
var decoded=reader.read(0);var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
```

`(long)width*height`는 곱셈 전에 64비트로 넓힌다. Java의 int 곱셈이 먼저 넘친 뒤 비교하는 실수를 피한다. TypeScript number처럼 모든 정수가 같은 표현 범위를 쓰지 않는다. `getWidth/getHeight` 다음에 `reader.read(0)`을 호출해 큰 픽셀 배열부터 만드는 순서를 피한다. 입력·출력 스트림은 메모리 전용이고 finally에서 디코더·그래픽 자원을 정리한다.

### 새 자료는 과거 판단을 무효화한다

출처: [EvidenceService.java](../../src/main/java/dustin/cex/domains/edd/EvidenceService.java#L73). 메서드 일부이며 독립 실행 예제가 아니다.

```java
// 새 자료가 도착하면 옛 판단·서명은 재사용할 수 없다. 자료 도착만으로 위험을 해소하지 않는다.
jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",r.get("case_id"));
audit.record(user,"EDD_EVIDENCE_SUBMITTED","edd-evidence:"+id,"SUCCESS",r.get("purpose").toString());
return new Receipt(id,request,original.length,view.length,"RECEIVED_UNVERIFIED");
```

`revision`은 새 자료와 옛 보고서의 근거를 구분한다. 위 SQL과 자료 INSERT·감사는 `TransactionTemplate.execute` 안에서 실행한다. 감사가 실패하면 자료와 사건 변경도 롤백된다. TypeScript에서 여러 Promise를 순서대로 실행하는 것만으로 얻을 수 있는 원자성이 아니다. 사용자 행 잠금은 동시 제출을 직렬화하고 UNIQUE는 마지막 중복 방어를 한다.

### 원문은 다른 고객이나 열람본으로 옮겨 쓸 수 없다

출처: [EvidenceCrypto.java](../../src/main/java/dustin/cex/domains/edd/EvidenceCrypto.java#L45). 메서드 일부이며 독립 실행 예제가 아니다.

```java
    var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
    // 행·고객·사건·요청·자료 종류·원본/열람본 구분을 인증한다. 다른 문맥으로 복사하면 실패한다.
    cipher.updateAAD(("EDD_EVIDENCE_V1:"+context).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(bytes);
} catch(GeneralSecurityException e) {throw new IllegalStateException("증빙 암호화 검증 실패");}
```

AES-GCM은 암호문과 AAD를 함께 인증한다. AAD에는 비밀이 아니어도 바뀌면 안 되는 업무 문맥을 넣는다. 키 ID 불일치·태그 손상은 복호화를 거부하고 다른 키·평문으로 우회하지 않는다. SecureRandom이 각 암호화에 새 12바이트 nonce를 만들며, 원본과 열람본은 다른 AAD를 사용한다.

### 감사 저장 실패 시 응답도 반환하지 않는다

출처: [EvidenceService.java](../../src/main/java/dustin/cex/domains/edd/EvidenceService.java#L90). 메서드 일부이며 독립 실행 예제가 아니다.

```java
    byte[] view=crypto.decrypt((String)e.get("key_id"),context(id,r)+":VIEW",(byte[])e.get("view_cipher"));
    audit.record(actor,"EDD_EVIDENCE_VIEWED","edd-evidence:"+id,"SUCCESS",reason.name());return new View(id,view);
}));
```

`tx.execute`가 성공적으로 커밋된 다음 서비스의 반환값이 컨트롤러로 간다. 위 return이 감사보다 먼저 실행되지 않는다. HTTP 송신 뒤 고객 단말이 실제 열었는지는 알 수 없으므로 이 기록은 열람본 제공 허가·반환 시도 증적이다. 다운로드 성공·사실 검증 완료라고 기록하지 않는다.

## 작성한 테스트

테스트에는 가상 픽셀·가상 고객·실제 HMAC·소프트웨어 보안키 서명·실행 소유 PostgreSQL만 사용한다. 실제 고객 자료·유료 Didit·운영 데이터·실물 장치를 사용하지 않는다. 기존 35개 사건 검증과 새 검증은 공통 [EddTestSupport](../../src/test/java/dustin/cex/domains/kyc/EddTestSupport.java)의 같은 실제 준비 경로를 사용한다.

### EddEvidenceIntegrationTest · 신규 26개

[테스트 파일](../../src/test/java/dustin/cex/domains/kyc/EddEvidenceIntegrationTest.java)

| 함수 | 시나리오와 기대 결과 |
|---|---|
| `authenticatedCustomerSubmitsOnlyRequestedEvidenceAndRemainsBlocked` | 실제 요청→저장→열람, 픽셀 보존·RECEIVED_UNVERIFIED·거래 차단 |
| `separateEncryptionPreservesOriginalAndRemovesTrailingPayloadFromView` | DB 원본 복호화 일치, 열람본에 추가 바이트 배제·암호문/메타데이터 구분 |
| `exactReplayDoesNotIncrementRevisionOrStoreTwice` | 같은 원본 재시도 동일 결과·다른 원본 409·revision 1회 |
| `requestKeyCannotBeReusedForDifferentPurposeAndPendingPurposeIsUnique` | 키/목적 결합·유효 미제출 요청 1개 |
| `unrelatedCustomerCannotSubmitOrEnumerateRequest` | 다른 고객 목록 비어 있음·제출 404 |
| `oldAssessmentCannotAcceptFileAndCustomerSeesStaleRequest` | 새 평가 뒤 STALE·제출 거부 |
| `newDeclarationWithoutAssessmentInvalidatesRequest` | 평가 없는 새 신고도 옛 요청 거부 |
| `revokedAndForeignCustomerSessionsCannotWrite` | 남의/회수한 세션 제출 거부 |
| `requestAlonePreventsBypassingUnreviewedMaterialsWithIdentityClosure` | 미제출 자료 요청부터 기존 단순 신원 종결 거부 |
| `uploadInvalidatesEarlierReportProofAndDoesNotOpenTrading` | 새 자료는 옛 서명 무효·챌린지 미소비·차단 유지 |
| `auditorAndUnassignedOperatorHaveNoContentAccess` | 감사자 메타데이터만·다른 담당 운영자 원문 거부 |
| `independentApproverMustGiveCorrectReasonAndBeDifferentPerson` | 올바른 독립 사유/다른 인물만 열람 |
| `revokedAndStaleMfaNeverReturnsImage` | 오래된 인증·회수 세션 열람 거부 |
| `requestAuditFailureRollsBackRequestAndRetryKeyRemainsUsable` | 요청 감사 실패 롤백·재요청 성공 |
| `submissionAuditFailureRollsBackCipherAndCaseRevision` | 제출 감사 실패 자료/revision 롤백·재시도 성공 |
| `readAuditFailureDoesNotReleaseContentOrConsumeQuota` | 열람 감사 실패 응답 없음·성공 카운터 증가 없음 |
| `immutableRequestsAndFilesRejectUpdateDeleteTruncate` | 요청/자료 변경·삭제·전체 삭제 실제 DB 거부 |
| `concurrentDuplicateSubmissionHasOneFileAndOneRevisionChange` | 두 스레드 실제 동시 요청·자료/증가 1개 |
| `readQuotaIsPersistedAndCustomerStillHasNoContentEndpoint` | 20회 허용·21회 429·고객 운영 열람 403 |
| `actualHttpUploadAndPrivateAttachmentHaveNoCacheAndNoOriginalFilename` | 실제 보안 체인 업로드/다운로드 헤더·PDF/GET 거부 |
| `malformedAndOversizedHttpBodyAreRejectedWithoutSaving` | 실제 HTTP SVG/초과 본문 거부·저장 없음 |
| `expiredRequestIsNeverAcceptedOrDeleted` | 과거 요청 합성 행은 EXPIRED·제출 거부·이력 보존 |
| `requestedAndReceivedLimitsDoNotPermitUnlimitedCollection` | 하루 5개 제출·여섯째 요청 429 |
| `enabledCustomerWithoutReviewCaseCannotBeAskedForEvidence` | 일반 정상 고객 불필요한 자료 수집 거부·기존 거래 유지 |
| `metadataAccessAlsoRequiresFreshMfaAndNeverHasOriginalBytes` | 실제 감사자 목록 최소 필드·MFA 5분 초과 403 |
| `actualRequestApiRequiresOperatorAndBindsRequestKey` | 실제 요청 API 역할·같은 키 재시도·미인증 제출 401 |

### 암호화·이미지 단위 검증 · 신규 23개

| 파일과 함수 | 검증 내용·실행 사례 수 |
|---|---|
| [EvidenceCryptoTest](../../src/test/java/dustin/cex/domains/edd/EvidenceCryptoTest.java) `randomizedNonceRoundTripAndRedactedDescription` | 왕복·새 nonce·비밀 출력 배제 1 |
| `ciphertextCannotMoveAcrossContexts` | 다른 인증 문맥 태그 거부 5 |
| `corruptionTruncationAndWrongKeyIdFailClosed` | 변조·짧은 암호문·다른 키 ID 거부 1 |
| `independentEncryptionKeyIsRequired` | 다른 세 업무의 키 재사용·잘못된 인코딩·길이 거부 1 |
| `missingKeyDoesNotOfferFallbackOrPlaintextStorage` | 키 없음 503·평문 경로 없음 1 |
| [EvidenceImageTest](../../src/test/java/dustin/cex/domains/edd/EvidenceImageTest.java) `boundedReadDoesNotTrustAvailableOrDeclaredLength` | MAX+1에서 읽기 중단 1 |
| `exactInputLimitCanBeReadAndEmptyInputCannot` | 정확한 읽기 상한·빈 입력 1 |
| `contentTypeCannotMakeForeignBytesIntoPng` | SVG/PDF/GIF/빈 입력/HTML 거부 5 |
| `truncatedPngIsRejectedAndPermitIsReleased` | 손상 입력 반복 뒤 정상 처리·동시 허용 수 회수 1 |
| `dimensionsAreCheckedBeforePixelAllocation` | 0/상한 초과/극대 폭 거부 4 |
| `totalPixelsHaveIndependentLimit` | 각 변 허용이어도 총 픽셀 초과 413 1 |
| `trailingBytesAreNotReturnedAsReviewImage` | 후행 내용 제외·정상 재해독 1 |

한 함수의 여러 assert를 별도 테스트 개수로 세지 않는다. 신규 실행 사례는 **49개**, 이전 사건 35개와 합친 전용 검증은 **84개**다.

## 실행 명령과 검증 범위

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

Temurin 21·macOS·실행 소유 동적 루프백 PostgreSQL 16·필요한 격리 Kafka와 봇 0명으로 실행한다. 기존 개발 DB·9092를 사용하지 않는다. 실행별 최종 수치·정리 결과는 [확정 검증 JSON](../reference/edd-evidence-verification-2026-10-23.json)을 따른다. JMH는 컴파일이며 새 성능 측정이 아니다.

첫 컴파일에서 Java var 복수 선언 오류를 수정했다. 이어 두 Spring 컨텍스트가 같은 임시 DB의 사용자 ID를 다시 생성하는 준비 충돌을 발견했다. 전용 테스트들의 공급사/암호화 설정을 공통 준비 클래스로 모아 같은 컨텍스트를 사용했고 기존 검증 35개를 모두 유지했다. 중간 79개 통과를 최종 실행 합계에 더하지 않는다. 기존 deprecated/unchecked·JVM 클래스 공유 안내는 남아 있다.

## 남은 범위와 운영 조건

다음은 **받은 실제 자료의 내용·발급기관·시점·고객/신고/거래와의 일치에 대한 사실 검증과 독립 승인**이다. 현재 열람은 사실 확인 결론이 아니고, 모든 자료 상태는 RECEIVED_UNVERIFIED다. 자금 원천·전체 재산·주소를 파일 하나로 동일하게 검증하지 않는다. PEP/제재 해소·고위 경영진 승인·지속 감시·법정 보고도 별도 업무다.

실제 개인정보를 받기 전 수집 근거/최소 필드·동의/고지·마스킹 기준·정확한 법정 보존/보유기간·분리보존·파기/법적 보류·권리 행사와 반출 통제를 확정해야 한다. 현재 불변 트리거와 미구현 파기를 영구 보존 정책으로 사용하지 않는다. 증빙용 DB가 물리적으로 분리된 개인정보 저장소라는 뜻도 아니다.

현재 이미지 처리는 플랫폼 프로세스 안에 있다. 입력/픽셀/동시 수 제한과 메모리 스트림은 완전한 파서 격리·악성 코드 검사·종단 CPU 시간 제한이 아니다. 운영에서는 패치된 JDK·프로세스 자원 제한·검사/격리 정책을 확인해야 한다. 느린 요청·TLS·업로드 시간·전역 용량·프록시 요청 제한·WAF는 이번 코드 밖의 운영 조건이다. 문서 재인코딩이나 보관을 인증 통과라고 설명하지 않는다.

키는 환경에서 주입하지만 KMS/HSM·키 교체·다중 키 호환·키 복구를 구현하지 않았다. 호스트나 실행 계정 탈취로부터의 완전한 격리가 아니다. DB·키·감사 기록의 접근 분리와 암호화 백업·복원·보유기간 연동은 운영 전 확인한다. 사용자 지시대로 디스크 손실 외부 복제/백업은 필요 조건만 문서에 남기고 배포하지 않았다.

실제 증빙 전용 JVM 강제 종료·디스크 손실·실물 장치·운영 조직·외부 심사 검증은 새로 작성/실행하지 않았다. 기존 프로세스 회귀와 새 DB 트랜잭션·암호화·API 검증을 구분한다. 현재 개발 서비스를 재기동하거나 스키마를 자동 변환·이전하지 않았다.

## 확정 수치와 관련 커밋

선택 JUnit **650개**, 실패·오류·제외 0개다. 전용 84개(신규 증빙 49+기존 EDD 35), KYC/CDD 150, 승인/접근 190, MFA 42, 금융 183, 실제 엔진 연결 1개다. 기존 별도 프로세스 **15개**도 통과했다. 두 JAR·JMH 컴파일 성공, 222개 의존성 버전의 알려진 OSV 검출 0·체크섬 변조 차단을 확인했고 실행 소유 자원을 정리했다. 신규 증빙 전용 프로세스 강제 종료 테스트는 0개다.

구현/테스트는 [`efd8a08`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/efd8a08)에 담았다. 문서/검증 증적은 별도 커밋으로 같은 작업 단위에서 일반 푸시한다. 작성자·커미터는 Gihoon이다. 이전 전체 문서 보강은 별도 문서 작업으로 먼저 완료했으며 관련 범위는 [전체 적용 목록](../reference/all-document-examples-verification-2026-10-23.json)에서 확인한다.
