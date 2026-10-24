# 고객 최종 답변의 업무별 서명 승인

지정된 다른 인물의 현재 APPROVER가 정확한 초안과 근거를 확인한 뒤 해당 업무·방향의 실제 보안키 서명으로 최종 답변을 승인하거나 반려한다. 최종 답변 확정은 거래 제한 해제나 원래 금융 판단 변경이 아니다.

## 문제와 기존 동작

040 회신은 실제 종료 상태의 관측이며 고객 재검토의 최종 처리 판단이 아니었다. 041~043에서 정상 인계·담당자 부재 복구·개인 업무 발견을 구현했지만 최종 설명·별도 검토/확정·고객 확인은 없었다. 금융 효과를 바꾸거나 내부 메모를 고객 문구로 복사하지 않고 후속 계층을 얹는다.

## 기존 제품과 공식 자료의 접근

2026-10-24 확인한 [Zendesk 공개 회신/비공개 메모](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 목적 분리와 [Stripe 분쟁 응답](https://docs.stripe.com/disputes/responding)의 최종 제출 전 근거 검토를 참고했다. 제품 내부의 DB/서명 구조가 이 코드와 같다고 추측하지 않는다. [미래에셋증권 민원 절차](https://securities.miraeasset.com/mw/mki/mki3062/n10.do)의 접수·조사·결과 구분은 참고하되 증권사 공개 14영업일을 거래소 법정 기한으로 복사하지 않는다. [업비트 개인정보 안내](https://privacy.upbit.com/easy)의 거래관계 관리와 민원 처리 목적을 대조했다.

[특정금융정보법 제5조의2](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022860887), 법률21857호·시행2026-10-02의 고객확인 의무와 [개인정보 보호법 제16조](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1020399353), 법률21445호·시행2026-09-11의 최소 수집을 확인했다. 최종 답변은 이 의무·원래 종료/철회·STR·원장/예약을 바꾸지 않는다. 이번 승인 방식·인원·분/일 단위 한도는 자체 정책이고 신고/인증·기관의 최종 판단을 대체하지 않는다. 법정 처리기한·외부 통지 수단과 고지 적법성은 별도 확인 사항이다.

## 선택과 트레이드오프

현재 배정 OPERATOR가 고객 공개 문구를 작성하고 현재 다른 인물의 APPROVER가 실제 사유·최신 내부 메모·문구를 검토한다. 초안과 고객 문구를 분리해 우발적 내부 기록 복사를 줄이지만 사람의 부적절한 자유 문구를 자동으로 완벽히 차단하는 장치는 아니다. 운영자는 제3자 개인정보·STR/조사 사실을 공개하지 않는 절차와 교육이 필요하다. 이 통제는 추가 검토 인력·반복 제공/서명·복호화/보존·사용자 행 잠금 비용을 요구한다.

원래 종료/독립 철회가 현재 어떤 상태인지 서버가 근거 ID와 함께 고정한다. 새 근거·계정 버전·현재 배정 변경은 옛 초안을 무효화하므로 무해한 계정 변경에도 새 요청/서명이 필요하다. 초안은10분, 본인 검토는5분, 접수별 하루10개로 제한한다. 미결 초안이 있으면 만료/반려 전 다른 초안을 막는다. 법정 기한이나 금융 해제 권한은 추가하지 않는다. 과거 기록은 수정·삭제·변환하지 않으며 승인 종류14개/새 표를 지원하지 않는 과거 스키마는 시작을 거부한다.

기존 Spring/JDBC·AES-GCM/HMAC·WebAuthn·감사 트랜잭션을 재사용하며 새 패키지·범용 규칙 엔진·동적 승인 프레임워크를 추가하지 않았다. Rust 엔진 코어와 프론트는 변경하지 않는다.

## 구현과 실제 코드

### 본인 검토 뒤 업무별 옵션

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L83)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return attempt(actor,()->tx.execute(t->{
    var r=checked(actor,sid,id,true);if(!r.payloadHash().equals(hash))throw denied(HttpStatus.CONFLICT);
    return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.CUSTOMER_REVIEW_FINAL,id,hash,approve));
}));}
```

검토한 같은 초안 해시·승인/반려 방향·현재 인물/세션을 확인한 뒤 WebAuthn 옵션을 제공한다. 다른 업무나 반대 방향의 증명을 받아들이지 않는다.

### 판단·증명 소모·감사의 단일 커밋

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L88)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return attempt(actor,()->tx.execute(t->{
    var r=checked(actor,sid,id,true);if(!r.payloadHash().equals(hash))throw denied(HttpStatus.CONFLICT);
    // 실제 인증기 카운터·증명 소모·최종 결정·감사를 모두 같은 트랜잭션에 확정한다.
    mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.CUSTOMER_REVIEW_FINAL,id,hash,approve,proof);
    jdbc.update("INSERT INTO customer_review_final_decisions(request_id,review_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?)",id,r.payload().reviewId(),actor,r.payload().checker().person(),approve);
    // 승인된 결과만 예약한다. 거절/초안/내부 메모는 외부 알림을 만들지 않는다.
    if(approve){var decided=jdbc.queryForObject("SELECT decided_at FROM customer_review_final_decisions WHERE request_id=?",Timestamp.class,id);mail.reserve(r.payload().userId(),CustomerMailOutbox.Kind.FINAL_NOTICE,id,decided.toInstant());}
    audit.record(actor,"CUSTOMER_FINAL_DECIDED","customer-final:"+id,"SUCCESS",approve?"APPROVED":"REJECTED");return read(id);
}));}
```

고객과 현재 담당자 잠금 뒤 근거를 다시 검사하고 실제 보안키 증명을 소모한다. 유일 인덱스가 접수별 최종 승인 하나를 보장한다. 감사 실패는 결정과 인증기 카운터·증명 소모를 함께 롤백한다.

## API와 확정 후 동작

POST `/api/admin/compliance/customer-final-requests/{id}/approve/options`, `/reject/options`는 payloadHash를 받고, `/approve`, `/reject`는 payloadHash·proof를 받는다. 지정 APPROVER 본인의 최근 검토가 필요하다. 결정 응답 유실 뒤 GET `/{id}`로 실제 결과를 확인하며 이미 결정한 요청에 다른 결정을 쓰지 않는다. 확정 뒤 새 메모/상태 회신/최종 초안은 거부하고 기존 동일 키 재조회와 불변 이력은 유지한다. 다음046에서 승인된 문구의 고객 조회·본인 확인을 추가한다.

## 작성한 테스트

[CustomerFinalApprovalIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerFinalApprovalIntegrationTest.java)에서 실제 격리 PostgreSQL·고객 종료/접수·내부 기록·감사·MVC를 검증한다. 승인 검증은 소프트웨어 인증기의 실제 암호 서명을 사용하며 실물 키/운영 검증과 구분한다.

| 테스트 함수 | 검증 시나리오와 기대 결과 |
| --- | --- |
| `actualSignedApprovalClosesReviewWithoutChangingFinancialEffects` | 실제 서명 승인 뒤 해당 고객 접수만 확정하고 원래 금융 차단/공개 관측 이력 보존 |
| `rejectionRetainsHistoryAndAllowsNewDraft` | 반려 이력을 남기고 새 초안 허용 |
| `ownInspectionAndExactHashAreRequiredForOptions` | 본인 초안 제공 없는 옵션 및 다른 해시 거부 |
| `requesterAndUnassignedApproverCannotDecide` | 요청자·미배정 승인자는 결정 불가, 지정 검토자는 같은 정상 증명 사용 가능 |
| `oppositeDirectionProofCannotApprove` | 반려 방향 증명으로 승인 불가하고 원래 반려에는 사용 가능 |
| `anotherBusinessProofCannotApproveFinalReply` | 인계 업무 증명으로 최종 답변 승인 불가 |
| `changedRequesterVersionInvalidatesExistingProof` | 작성자 버전 변경 뒤 이전 증명 거부 |
| `changedCheckerVersionInvalidatesExistingProof` | 검토자 버전 변경 뒤 이전 증명 거부 |
| `newNoteInvalidatesAlreadySignedProof` | 새 내부 메모 뒤 서명 완료한 이전 초안도 거부 |
| `transferInvalidatesAlreadySignedProof` | 현재 배정 인계 뒤 이전 증명 거부 |
| `actualRescissionInvalidatesAlreadySignedProof` | 실제 종료 철회 뒤 이전 상태에 묶인 증명 거부 |
| `decisionAuditFailureRestoresProofForSameRetry` | 결정 감사 실패 뒤 결정·인증기/증명 소모 롤백, 같은 증명 재시도 |
| `repeatedDecisionCannotConsumeAnotherFinalResult` | 결정 재전송은 두 번째 결과를 만들지 못하며 상태 GET으로 원래 결과 확인 |
| `concurrentApprovalAndRejectionCommitOneDecision` | 실제 두 스레드 승인/반려 경합에서 한 결정만 커밋 |
| `closedReviewRejectsNewNotesAndStatusReplies` | 확정 뒤 새 메모·상태 회신 추가 차단 |
| `httpDecisionUsesActualProofAndNoStore` | 실제 암호 증명을 MVC 승인 API로 사용하고 no-store/원래 결과 확인 |

테스트를 구현과 동일한 분기 확인에만 한정하지 않고 다른 인물/세션·근거 변경·감사 실패 롤백·HTTP 경계와 금융 상태 보존을 함께 검사한다.

## 실행 명령과 결과

선택 JUnit **77개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle45.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

외부 Didit 생성·엔진 HTTP·Kafka 등의 공통 대역과 실제 DB/암호 서명/감사/MVC를 구분한다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용하고 기존 서비스/사용자 데이터는 변경하지 않는다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 새 성능 수치·배포·개발 서버 재시작·기존 DB 변환은 없다.

## 관련 커밋과 증적

- [`d723413`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d723413eb587012ac28d3a4bf50d5d189bb35dcb): 구현과 관련 회귀.

[확정 증적](../reference/customer-final-approval-verification-2026-10-24.json), [043 담당 업무](043-customer-work-queue.md), [040 관측 회신](040-customer-review-replies.md).


현재 고객 결과 조회·명시적 확인과 운영 목록 반영은 [046 구현](046-customer-final-receipt.md)을 따른다. 이 문서의 실행 결과는 당시 단계로 보존한다.

2026-10-24 현재 발췌는 메일 예약 연결을 포함한다. 당시 검증 수치는 원래 실행 기준이며 현재 메일 검증은 [055 기록](055-customer-service-mail.md)을 따른다.
