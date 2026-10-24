# 고객 재검토의 비공개 처리 메모와 본인 열람 근거를 보존하기

접수된 요청을 실제 담당자가 조사 중이거나 추가 확인이 필요하다는 관찰로 기록한다. 고객 사유를 최근 직접 제공받은 현재 지정 인물만 기록하며 내부 메모는 암호화한다. 이 메모는 금융 판단·고객 회신·업무 완료가 아니다.

## 기존 문제와 구현

037의 접수/사유 제공과 038의 감독 사이에 담당자가 한 일을 보존할 경로가 없었다. 일반 감사의 열람 시각만으로는 같은 인물의 현재 세션이 본문을 제공받았는지 확인할 수 없다. 새로운 불변 제공 영수증과 내부 기록 표 두 개를 추가했다. 과거 일반 감사를 소급 영수증으로 채우거나 폴백하지 않는다.

POST /api/admin/compliance/customer-review-requests/{id}/notes는 Idempotency-Key와 progress(INVESTIGATING 또는 ADDITIONAL_CHECK_REQUIRED), note를 받는다. GET 같은 경로는 메타데이터 목록, POST /api/admin/compliance/customer-review-notes/{id}/inspect는 감사 원문 제공이다. 지정된 현재 OPERATOR/APPROVER와 최근 MFA를 확인한다. 고객 경로에는 메모·진행 관찰·작성자를 합치지 않는다.

## 기존 제품과 공식 근거

확인일 2026-10-24. [Zendesk 비공개/공개 댓글](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)과 [읽기 전용 변경 감사](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_audits/)를 참고해 내부 관찰과 고객 응답을 분리한다. 원래 내용을 덮어쓰지 않는다. [미래에셋증권](https://trading.securities.miraeasset.com/hki/hki3062/n18.do)의 접수·조사·회신 분리를 참고하되 증권사 기한/내부 조직을 거래소 법적 의무로 옮기지 않는다. [업비트](https://static.upbit.com/terms/private_data.html)·[빗썸](https://privacy.bithumb.com/ko/faq/)의 고객지원/관계 관리 목적을 구분했다.

[개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353), 법률 제21445호·시행 2026-09-11의 최소 수집과 [특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률 제21857호·시행 2026-10-02의 확인/종료·별도 보고 검토를 유지한다. 코드 기록만으로 신고·인증·심사·법적 조사 완료를 주장하지 않는다.

## 선택과 트레이드오프

불변 두 표·암호화/복호화·보존 비용을 감수해 원래 관찰과 제공 근거를 보존한다. 같은 요청은 고객 행과 행위자를 정렬해 잠그므로 원래 주문/철회와 순서를 맞추지만 원문/목록 조회도 고객 행 잠금과 경합할 수 있다. 최대 25개 목록·2,000 UTF-16 단위 메모로 단건 작업량을 제한하며 실운영 성능 수치는 별도로 측정해야 한다.

5분 내 본인 인물/버전/세션의 제공·24시간 요청별 새 메모 10개·기록 순번은 자체 정책이다. 반복 제공/기록의 인력·DB 비용이 늘어나지만 다른 사람의 열람 대체나 단순 승인 체크로 우회하지 않는다. 같은 키는 원래 기록만 재조회하고 새 순번을 쓰지 않는다. 일반 고객 텍스트의 정규화/길이 규칙을 기존 Submission으로 재사용한다. 새 패키지·승인 종류는 없다.

## 실제 코드와 문법

### 본인 제공과 논리 순번

[CustomerReviewNoteService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewNoteService.java#L36)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
requests.requirePersonalView(actor,sid,request);
if(jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_notes WHERE request_id=? AND recorded_at>clock_timestamp()-interval '1 day'",Long.class,request)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
// 시계가 역행하거나 같은 시각이어도 논리 순번은 증가한다. 고객 잠금 아래에서만 계산한다.
long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM customer_exit_review_notes WHERE request_id=?",Long.class,request);UUID id=UUID.randomUUID();
```

고정된 인물·버전·세션의 5분 내 제공 영수증을 확인한다. 고객 행을 잠그고 MAX(sequence)+1을 계산하므로 동시 입력도 서로 다른 순번이다. 시각 최대값으로 현재 순서를 추측하지 않는다.

### 내부 메모의 암호화·감사 원자성

[CustomerReviewNoteService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewNoteService.java#L40)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
byte[] cipher=crypto.encrypt(context(id,request,actor,sequence,mac),json.writeValueAsBytes(f));
jdbc.update("INSERT INTO customer_exit_review_notes(id,request_id,sequence,actor_id,request_key,progress,note_cipher,content_mac,evidence_key_id,fingerprint_key_id) VALUES(?,?,?,?,?,?,?,?,?,?)",id,request,sequence,actor,key,f.progress().name(),cipher,mac,crypto.keyId(),fingerprints.keyId());
audit.record(actor,"CUSTOMER_REVIEW_NOTE_RECORDED","customer-note:"+id,"SUCCESS","INTERNAL_ONLY");return metadata(read(id));
```

기존 EvidenceCrypto의 AES-GCM과 CddCrypto의 비밀 HMAC을 재사용한다. 행·고객 요청·작성자·논리 순번·내용 지문을 인증 문맥에 결합한다. 감사 실패는 행과 순번을 함께 롤백한다.

### 현재 인물과 세션에 결합한 제공

[CustomerExitReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerExitReviewService.java#L106)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
void requirePersonalView(long actor,UUID sid,UUID id) {
    transaction();var u=users.findById(actor).orElseThrow();var state=custody.current(id);
    long count=jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND custody_generation=? AND custody_stamp=? AND provided_at>clock_timestamp()-interval '5 minutes'",Long.class,id,actor,u.getOperatorPersonId(),u.getVersion(),sid,state.generation(),state.stamp());
    if(count==0)throw denied(HttpStatus.CONFLICT);
}
```

자바 long은 값 비교다. 기록 당시 actor_version과 현재 JPA version, 인물 UUID와 세션을 함께 검사한다. 단순히 같은 계정이 한 번 읽었거나 다른 담당자가 읽었다는 이력으로 새 기록을 허용하지 않는다.

## 작성한 테스트

[CustomerReviewNoteIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerReviewNoteIntegrationTest.java).

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `personalViewAllowsEncryptedInternalObservationWithoutCustomerStatusChange` | 본인 제공 뒤 암호화 메모 기록/지정자 해독, 고객 접수 상태·거래 차단 유지 |
| `absentOrOtherPersonsViewDoesNotAuthorizeNote` | 미열람/다른 인물 제공으로는 기록 불가 |
| `anotherSessionViewCannotAuthorizeNote` | 다른 세션 제공 대체 불가 |
| `expiredViewIsNotARecentPersonalReceipt` | 6분 전 표본은 최근 제공으로 인정하지 않음 |
| `sameKeyReplaysButDifferentProgressOrTextConflicts` | 같은 키 같은 내용 재조회, 진행/메모 변경 충돌 |
| `concurrentDistinctKeysReceiveConsecutiveLogicalSequences` | 실제 두 스레드에서 1·2 순번 각각 커밋 |
| `tenDailyNotesAreAllowedButReplayDoesNotSpendAnotherSlot` | 10개 한도/11번째 거부, 기존 키 재조회 유지 |
| `noteAuditFailureRollsBackSequenceAndAllowsSameKeyRetry` | 감사 실패는 행/순번 롤백, 동일 키 재시도 1번 |
| `reasonAuditFailureAlsoRollsBackPersonalReceipt` | 사유 제공 감사 실패는 제공 영수증도 롤백 |
| `noteViewAuditFailureDoesNotReturnPlaintext` | 메모 열람 감사 실패는 원문 반환 없음 |
| `outsiderAndCustomerCannotListOrReadInternalNotes` | 미배정 감독자/고객의 내부 접근 거부 |
| `newActorVersionRequiresNewPersonalView` | 인물 버전 변경 후 새 제공 필요 |
| `lostCurrentAuthorityCannotWrite` | 비활성/만료/복구/인물 교체 4개 기록 거부 |
| `copiedCipherFailsItsRowAndLogicalSequenceContext` | 다른 행/순번으로 복사한 암호문 인증 실패 |
| `bothNewTablesRejectUpdateDeleteAndTruncate` | 제공/메모 표 모두 수정·삭제·일괄 삭제 거부 |
| `withdrawalDoesNotEraseInternalHistory` | 종료 철회 후에도 내부 이력 보존 |
| `invalidFactsPagesAndAmbientTransactionAreRejected` | 잘못된 키/페이지·외부 트랜잭션 거부 |
| `httpInternalRoutesRequireAssignedCurrentPeople` | MVC 기록/해독·저장 금지 헤더, 고객/미배정자 거부 |

[CustomerReviewNoteFactsTest.java](../../src/test/java/dustin/cex/domains/edd/CustomerReviewNoteFactsTest.java)의 progressAndNonEmptyNoteAreRequired는 구분/공백 메모 거부, multilineNormalizationAndSafePrinting은 여러 줄 정리와 출력 비밀 보존을 검사한다. 기존 037 실제 주문 예약/원장 회귀와 함께 실행한다. 6분 전 제공·암호문 복사 표본은 추가 전용 부정 검사이며 정상 제공/승인을 위조한 예가 아니다.

## 실행 명령과 결과

선택 JUnit **102개**, 실패·오류·제외 0개. CustomerReviewNoteIntegrationTest 21개, ExitSupervisionIntegrationTest 18개, CustomerReviewSupervisionIntegrationTest 14개, ExitApprovalSupervisionIntegrationTest 16개, CustomerExitReviewSubmissionTest 7개, CustomerExitReviewIntegrationTest 24개, CustomerReviewNoteFactsTest 2개. 별도 실행 소유 PostgreSQL을 제거했고 bootJar 빌드도 성공했다. `python3 /tmp/verify-cycle39.py`는 기존 scripts/verify_edd.py의 봇 0명·외부 주소/DB 격리를 유지하고 이번 변경에 관련된 --tests 선택만 조정한 임시 실행기다. 실제 선택과 함수·입력별 사례 목록은 확정 증적에 남겼다.

중간 compileTestJava에서 새 테스트가 호출한 실제 철회 준비 함수의 checked Exception 선언 누락 1개를 수정했다. 제품 테스트 실행 전 오류이며 이전 XML을 이번 결과로 집계하지 않았다. 실제 PG·서명·MVC·감사와 격리 외부 대역을 구분하며 실패/제외를 숨기지 않는다. 기존 compiler/JVM 경고와 새로운 강제 종료·성능 측정 없음도 증적에 남긴다.

## 한계

진행 관찰은 외부 사실의 진위·법적 조사 완료·고객 통지·거래 허용이 아니다. 고객 회신과 처리 기한/알림·원래 담당자 부재 인계·키 분리/보존·조직/기관 심사는 별도다. 금융/원장·STR·기존 종료 판단은 바꾸지 않는다. 엔진 단일 스레드 코어·프론트·기존 개발 DB/서버/저널은 변경·변환·재시작/배포하지 않았고 봇은 0명이다.

## 관련 커밋과 증적

- [`fd138bf`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fd138bf781d204f9039ad2aa7c431ba3d35ce64e): 고객 재검토의 비공개 처리 기록과 본인 제공 근거와 관련 회귀.

[039 확정 증적](../reference/customer-review-notes-verification-2026-10-24.json), [038 감독](038-customer-review-supervision.md), [037 접수](037-customer-exit-review.md).


041 이후 현재 원문/후속 접근은 승인된 현재 고객 업무 배정으로 검사한다. 제공 영수증은 배정 세대와 stamp에도 결합하며 옛 세대 재사용을 거부한다. 원래 종료별 담당자 목록을 제거하고 요청별 현재 배정 컨텍스트로 전환했다. 과거 실행·증적은 당시 기록으로 보존하고 [041](041-customer-work-transfer.md)을 함께 읽는다.
