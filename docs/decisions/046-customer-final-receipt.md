# 고객 최종 결과 제공과 본인 확인 기록

승인된 답변의 원본 공개 문구를 본인에게 제공하고, 본인이 최종 답변 ID를 지정해 확인한 사실을 별도 영수증으로 보존한다. 담당 업무 목록은 확정 결과와 고객 확인을 구분해서 표시한다.

## 문제와 기존 동작

040 회신은 실제 종료 상태의 관측이며 고객 재검토의 최종 처리 판단이 아니었다. 041~043에서 정상 인계·담당자 부재 복구·개인 업무 발견을 구현했지만 최종 설명·별도 검토/확정·고객 확인은 없었다. 금융 효과를 바꾸거나 내부 메모를 고객 문구로 복사하지 않고 후속 계층을 얹는다.

## 기존 제품과 공식 자료의 접근

2026-10-24 확인한 [Zendesk 공개 회신/비공개 메모](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 목적 분리와 [Stripe 분쟁 응답](https://docs.stripe.com/disputes/responding)의 최종 제출 전 근거 검토를 참고했다. 제품 내부의 DB/서명 구조가 이 코드와 같다고 추측하지 않는다. [미래에셋증권 민원 절차](https://securities.miraeasset.com/mw/mki/mki3062/n10.do)의 접수·조사·결과 구분은 참고하되 증권사 공개 14영업일을 거래소 법정 기한으로 복사하지 않는다. [업비트 개인정보 안내](https://privacy.upbit.com/easy)의 거래관계 관리와 민원 처리 목적을 대조했다.

[특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률21857호·시행2026-10-02의 고객확인 의무와 [개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353), 법률21445호·시행2026-09-11의 최소 수집을 확인했다. 최종 답변은 이 의무·원래 종료/철회·STR·원장/예약을 바꾸지 않는다. 이번 승인 방식·인원·분/일 단위 한도는 자체 정책이고 신고/인증·기관의 최종 판단을 대체하지 않는다. 법정 처리기한·외부 통지 수단과 고지 적법성은 별도 확인 사항이다.

[특정금융정보법 제4조제6항](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290725&efYd=20261002&chrClsCd=010202&joNo=000400)의 현행 본문도 다시 확인했다. 보고하려는 사실/보고 사실을 고객 등에게 누설하지 않아야 하며 같은 금융회사 내부의 자금세탁 방지 목적 제공 등 예외는 구분한다. 공개 답변 경로는 STR 기록을 조회하지 않고 내부 메모를 문구에 자동 복사하지 않는다. 자유 문구의 의미적 누설을 코드만으로 완전히 차단할 수 없으므로 지정 검토자의 실제 내용 확인·직원 교육/운영 점검은 남는다. 웹 도구가 본문을 가져오지 못해 같은 공식 본문 URL을 직접 요청했고 응답 해시와 확인 결과를 별도 보존했다.

## 선택과 트레이드오프

현재 배정 OPERATOR가 고객 공개 문구를 작성하고 현재 다른 인물의 APPROVER가 실제 사유·최신 내부 메모·문구를 검토한다. 초안과 고객 문구를 분리해 우발적 내부 기록 복사를 줄이지만 사람의 부적절한 자유 문구를 자동으로 완벽히 차단하는 장치는 아니다. 운영자는 제3자 개인정보·STR/조사 사실을 공개하지 않는 절차와 교육이 필요하다. 이 통제는 추가 검토 인력·반복 제공/서명·복호화/보존·사용자 행 잠금 비용을 요구한다.

원래 종료/독립 철회가 현재 어떤 상태인지 서버가 근거 ID와 함께 고정한다. 새 근거·계정 버전·현재 배정 변경은 옛 초안을 무효화하므로 무해한 계정 변경에도 새 요청/서명이 필요하다. 초안은10분, 본인 검토는5분, 접수별 하루10개로 제한한다. 미결 초안이 있으면 만료/반려 전 다른 초안을 막는다. 법정 기한이나 금융 해제 권한은 추가하지 않는다. 과거 기록은 수정·삭제·변환하지 않으며 승인 종류14개/새 표를 지원하지 않는 과거 스키마는 시작을 거부한다.

기존 Spring/JDBC·AES-GCM/HMAC·WebAuthn·감사 트랜잭션을 재사용하며 새 패키지·범용 규칙 엔진·동적 승인 프레임워크를 추가하지 않았다. Rust 엔진 코어와 프론트는 변경하지 않는다.

## 구현과 실제 코드

### 승인된 답변만 고객 본인에게 제공

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L101)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public FinalReceipt ownFinal(long actor,UUID sid,UUID review){return attempt(actor,()->tx.execute(t->{
    requests.own(actor,sid,review);var result=approvedReceipt(actor,review);
    // 제공 감사가 실패하면 문구를 반환하지 않는다. 이 GET으로 고객 확인 영수증을 만들지 않는다.
    audit.record(actor,"CUSTOMER_FINAL_PROVIDED","customer-review:"+review,"SUCCESS","SERVER_PROVIDED_ONLY");return result;
}));}
```

기존 고객 세션/소유권 검사를 재사용한다. 승인된 초안의 원본 공개 문구만 복호화하고 내부 메모·보고 사유는 반환하지 않는다. 감사 성공은 서버의 제공 기록이고 고객이 읽었다는 증명이 아니다.

### 명시적 본인 확인을 별도 보존

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L107)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Acknowledgment acknowledge(long actor,UUID sid,UUID review,UUID expectedFinal,UUID key){return attempt(actor,()->tx.execute(t->{
    requests.own(actor,sid,review);if(expectedFinal==null||key==null)throw denied(HttpStatus.BAD_REQUEST);var result=approvedReceipt(actor,review);
    if(!result.finalId().equals(expectedFinal))throw denied(HttpStatus.CONFLICT);
    var old=jdbc.queryForList("SELECT * FROM customer_review_final_acknowledgments WHERE user_id=? AND request_key=?",actor,key);
    if(!old.isEmpty()){var prior=ack(old.getFirst());if(!prior.finalId().equals(expectedFinal))throw denied(HttpStatus.CONFLICT);audit.record(actor,"CUSTOMER_FINAL_ACK_REPLAYED","customer-final:"+expectedFinal,"SUCCESS","SAME_ACK");return prior;}
    if(result.acknowledgedAt()!=null)throw denied(HttpStatus.CONFLICT);
    UUID id=UUID.randomUUID();jdbc.update("INSERT INTO customer_review_final_acknowledgments(id,final_id,user_id,request_key) VALUES(?,?,?,?)",id,expectedFinal,actor,key);
    audit.record(actor,"CUSTOMER_FINAL_ACKNOWLEDGED","customer-final:"+expectedFinal,"SUCCESS","EXPLICIT_ACTION_NOT_WAIVER");
    return ack(jdbc.queryForMap("SELECT * FROM customer_review_final_acknowledgments WHERE id=?",id));
}));}
```

최종 답변 ID를 지정한 POST만 확인 이력을 만든다. GET이나 운영자 열람으로 대체하지 않는다. 같은 키 재시도는 같은 영수증이고 다른 결과/키 충돌은 거부한다.

## API와 목록 계약

GET `/api/customer/relationship/review-requests/{review}/final`은 본인 승인 결과만 제공한다. POST `/final/acknowledgments`는 Idempotency-Key·finalId를 받는다. GET은 서버 제공 감사만 만들며 POST는 확인 영수증을 만든다. 같은 키/결과 재시도는 원래 영수증, 다른 키로 같은 결과 또는 다른 결과에 이미 쓴 키는409다. 확인은 권리 포기나 내용 이해의 증명이 아니므로 고객에게 추가 금융 업무 인증을 요구하지 않는다.

개인 업무 목록의 finalReviewId·finalDecidedAt·customerAcknowledgedAt는 승인·고객 확인을 분리한다. 닫힌 접수도 이력에서 사라지지 않는다. 040의 latestReplyNoteSequence는 과거 상태 회신 근거이고 최종 승인과 별개다. 최종 승인 후 새 메모가 금지되므로 최신 근거를 다룬 최종 답변이 있으면 hasUnreflectedNote는 false다. 폐쇄된 고객 계정은 기존 로그인 통제를 우회하지 않으며 다른 신원 확인 지원 채널/법적 권리 대응은 운영 과제로 남는다.

외부 이메일/SMS/기관 제출은 구현·발송하지 않았다. 법적으로 유효한 고지 수단·기한·보존/파기·조직/기관 확인과 실물 키/운영 검증도 별도다. 최종 답변을 다시 쓰지 않고 후속 문제는 별도의 새 고객 접수로 처리할 수 있으며 기존 일일 접수 한도는 유지한다.

## 작성한 테스트

[CustomerFinalReceiptIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerFinalReceiptIntegrationTest.java)에서 실제 격리 PostgreSQL·고객 종료/접수·내부 기록·감사·MVC를 검증한다. 승인 검증은 소프트웨어 인증기의 실제 암호 서명을 사용하며 실물 키/운영 검증과 구분한다.

| 테스트 함수 | 검증 시나리오와 기대 결과 |
| --- | --- |
| `pendingAndRejectedDraftsNeverReachCustomer` | 미결·반려 초안은 본인에게도 최종 결과로 제공하지 않음 |
| `approvedCustomerGetsExactPublicTextWithoutInternalMaterial` | 승인 원문과 당시 구조화 상태만 공개, 내부 메모/배정/원문 해시 미제공, 금융 차단 보존 |
| `sameKeyCannotAcknowledgeAnotherOwnedFinal` | 실제 본인 접수2개를 각각 확정한 뒤 첫 확인 키로 두 번째 결과 확인 불가 |
| `repeatedGetsAfterApprovalDoNotCreateAcknowledgment` | 승인 뒤 반복 GET은 확인 영수증을 만들지 않음 |
| `explicitAckReplaysOnlySameKeyAndResult` | 같은 키·결과의 재전송은 같은 영수증, 다른 키/결과 충돌 |
| `wrongCustomerAndOperatorCannotReadOrAcknowledge` | 다른 고객·운영자는 고객 본인 결과/확인 권한 없음 |
| `foreignOrRevokedCustomerSessionCannotRead` | 타인 세션·철회된 고객 세션 거부 |
| `provideAuditFailureReturnsNoPublicText` | 제공 감사 실패 시 고객 문구 반환 없음, 정상 재시도 |
| `ackAuditFailureRollsBackAndSameKeyRetries` | 확인 감사 실패 시 영수증 롤백, 같은 키로 재시도 |
| `laterFinancialRescissionDoesNotRewriteHistoricalAnswer` | 나중에 원래 종료가 철회돼도 당시 승인 답변/상태 보존 |
| `laterCustodyTransferDoesNotRewritePublicAnswer` | 나중에 담당자를 인계해도 당시 고객 답변 보존 |
| `queueSeparatesFinalApprovalFromCustomerAcknowledgment` | 실제 커서로 배정 건을 찾아 최종 확정 시각과 고객 확인 시각 구분 |
| `concurrentSameKeyAckCommitsOneReceipt` | 실제 두 스레드의 같은 확인 요청은 같은 영수증 하나만 커밋 |
| `ackHistoryRejectsUpdateDeleteAndTruncate` | 명시적 확인 이력의 수정·삭제·일괄 삭제 거부 |
| `httpOwnFinalAndExplicitAckAreNoStoreAndCustomerOnly` | 본인 GET/확인POST와 no-store, 운영자403/익명401 검증 |

테스트를 구현과 동일한 분기 확인에만 한정하지 않고 다른 인물/세션·근거 변경·감사 실패 롤백·HTTP 경계와 금융 상태 보존을 함께 검사한다.

## 중간 실패와 수정

첫108개 실행에서2개가 실패했다. 개인 목록의 첫 페이지에 현재 접수가 있다고 가정했고 일반 고객에게 운영자용 보안키 등록 헬퍼를 사용했다. 실제 커서 순회와 일반 고객 세션 준비로 고쳤으며 기존 금융/감사 이력을 지우지 않았다. 실패 실행기는 실패 시 tests 필드를 쓰지 않아 집계 대기 도구에도 KeyError가 발생했다. 실제 JUnit XML108개/실패2개를 별도 보존하고 대기 도구가 XML 집계 증적을 읽도록 수정했다. 이 도구 오류를 제품 테스트 실패로 더하지 않는다.

## 실행 명령과 결과

선택 JUnit **909개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle46.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

최종 선택 JUnit **1,475개**, 실패·오류·제외0개다. 전체 EDD/보고/종료/고객 업무909개, KYC150·승인/접근190·MFA42·금융183·실제 시장 연결1개를 통과했다. 이번 세 사이클 신규 **48개**(044 17 + 045 16 + 046 15)는 최종909개에 이미 포함된다. 수정 후 중간 선택108개와 각 사이클 검증을 더하지 않는다. 기존 프로세스 **15개**도 통과했고 신규 강제 종료 시나리오는0개다.

두 JAR·JMH 컴파일·의존성 목록 생성, 조회 시점222개 버전의 알려진 OSV 검출0개·변조 체크섬의 예상 Gradle 종료1 차단을 확인했다. 의존성 목록 SHA-256은 `590da8748d7e2940dbc56dfe714c8c4cab4d1e5ed9b0be72dbf14e024e79268a`다. 실행 소유 컨테이너/임시 DB/체크섬 표본을 정리했다. 현재 실제 코드 발췌 **192개**와 별도 역사/범위6개를 소스와 대조했다. 실제 명령·개별 사례·초기 실패/수정·환경·공식 자료·기존 경고/한계는 확정 증적에서 확인한다.

외부 Didit 생성·엔진 HTTP·Kafka 등의 공통 대역과 실제 DB/암호 서명/감사/MVC를 구분한다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용하고 기존 서비스/사용자 데이터는 변경하지 않는다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 새 성능 수치·배포·개발 서버 재시작·기존 DB 변환은 없다.

저장소에서 동일한 전체 EDD 선택을 재현하는 명령은 `python3 scripts/verify_edd.py`다. 이 실행기는 새 PostgreSQL 컨테이너를 만들고 제거하며 봇0명·격리 외부 주소를 적용한다. 다른 광역 검증은 확정 JSON의 extended_commands에 기록한 명령을 따른다.

## 관련 커밋과 증적

- [`6c061fc`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/6c061fc51c98a781979cdceab4884a8748b78d4c): 구현과 관련 회귀.

[확정 증적](../reference/customer-final-receipt-verification-2026-10-24.json), [043 담당 업무](043-customer-work-queue.md), [040 관측 회신](040-customer-review-replies.md).
