# 고객 최종 답변 초안과 검토 근거 보존

고객에게 보낼 최종 답변을 별도 암호화 초안으로 보존하고 지정 검토자가 실제 고객 사유·최신 내부 기록·공개 예정 문구를 함께 확인한다. 이 단계는 초안과 검토 제공까지이며 승인·고객 공개는 다음 단계다.

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

### 초안의 불변 근거

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L33)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
public record Payload(UUID id,UUID reviewId,long userId,long customerVersion,Person requester,Person checker,
    long custodyGeneration,String custodyStamp,UUID noteId,long noteSequence,Basis basis,String explanationMac,
    String evidenceKeyId,String fingerprintKeyId,String policy,Instant expiresAt) {}
```

현재 고객/두 담당자의 버전·배정 세대·최신 내부 기록·원래 종료/철회의 실제 상태·공개 예정 문구 지문·키·정책·기한을 묶는다. Java record는 값을 묶는 타입이며 TypeScript 인터페이스와 달리 런타임 생성자/값 비교도 제공한다.

### 문구 암호화와 승인 결합의 원자성

[CustomerFinalReviewService.java](../../src/main/java/dustin/cex/domains/edd/CustomerFinalReviewService.java#L67)의 현재 부분 발췌다. 독립 실행 예제가 아니며 주변 의존성·트랜잭션이 필요하다.

```java
String raw=json.writeValueAsString(payload),hash=verifier.digest(json.readTree(raw));byte[] cipher=crypto.encrypt(cipherContext(payload),explanation.text().getBytes(StandardCharsets.UTF_8));
jdbc.update("INSERT INTO customer_review_final_requests(id,review_id,requester_id,request_key,payload,payload_hash,explanation_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,review,actor,key,raw,hash,cipher,Timestamp.from(payload.expiresAt()));
mfa.bindApproval(OperatorMfaService.ApprovalKind.CUSTOMER_REVIEW_FINAL,id,actor,maker.getVersion(),hash,payload.expiresAt());
audit.record(actor,"CUSTOMER_FINAL_PROPOSED","customer-final:"+id,"SUCCESS","DRAFT_ONLY");return read(id);
```

공개 예정 문구는 따로 암호화한다. 메타데이터 해시는 SHA-256, 문구 지문은 비밀키 기반 HMAC이며 같은 의미가 아니다. 초안·14번째 승인 종류의 결합·감사 중 하나라도 실패하면 모두 롤백한다.

## API

POST `/api/admin/compliance/customer-review-requests/{review}/final-proposals`: Idempotency-Key와 latestNoteId·explanation.text를 받는다. POST `/api/admin/compliance/customer-final-requests/{id}/inspect`는 지정 검토자에게만 세 원문을 제공한다. GET `/api/admin/compliance/customer-final-requests/{id}`는 현재 담당자 또는 실제 원래 요청자의 최소 결과 확인이다. 승인·고객 공개 API는044에 아직 없으며 다음045/046에서 추가한다.

## 작성한 테스트

[CustomerFinalDraftIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerFinalDraftIntegrationTest.java)에서 실제 격리 PostgreSQL·고객 종료/접수·내부 기록·감사·MVC를 검증한다. 승인 검증은 소프트웨어 인증기의 실제 암호 서명을 사용하며 실물 키/운영 검증과 구분한다.

| 테스트 함수 | 검증 시나리오와 기대 결과 |
| --- | --- |
| `designatedCheckerReceivesExactDraftAndActualBasisOnly` | 지정 검토자에게 실제 고객 사유·최신 메모·공개 예정 문구만 제공하고 거래 차단은 보존 |
| `metadataAndDatabaseDoNotExposePublicDraftPlaintext` | 메타데이터/DB 평문/로그 표현에 공개 예정 문구 없음 |
| `sameKeyReplaysAndChangedTextOrNoteConflicts` | 응답 유실 뒤 같은 키·내용 재시도, 다른 문구·근거 재사용 충돌 |
| `outsidersAndMakerCannotInspectCheckerDraft` | 요청자/미배정 승인자는 검토 원문을 얻지 못하고 APPROVER는 작성 불가 |
| `anotherPendingDraftIsRejected` | 같은 접수의 동시 미결 초안 추가 거부 |
| `staleOrForeignNoteCannotBeProposed` | 다른/과거 근거로 새 초안 작성 불가 |
| `newNoteInvalidatesExistingInspection` | 새 내부 근거 후 옛 초안 검토 거부 |
| `changedOriginalFinancialBasisInvalidatesDraft` | 실제 종료 철회 후 이전 상태에 묶인 초안 거부 |
| `transferredCustodyInvalidatesDraft` | 인계 뒤 이전/새 검토자 모두 옛 초안 사용 불가 |
| `newOwnerWithoutPersonalReasonViewCannotCreateDraft` | 새 담당자는 본인 고객 사유 제공 없이 작성 불가 |
| `invalidPublicTextIsRejected` | 공백·제어문자·방향제어·2000자 초과 문구 거부 |
| `missingCurrentCheckerDoesNotChooseUnassignedApprover` | 배정 APPROVER 부재를 무관한 다른 승인자로 대체하지 않음 |
| `proposalAuditFailureRollsBackDraftAndApprovalBinding` | 작성 감사 실패 시 초안/업무별 결합 함께 롤백 후 정상 재시도 |
| `inspectionAuditFailureRollsBackBothPersonalAndDraftViews` | 검토 감사 실패 시 고객 사유/초안 제공 영수증 모두 롤백 |
| `currentSessionAndAccountVersionAreRequired` | 다른 세션 및 검토자 버전 변경 뒤 검토 차단 |
| `allNewHistoryTablesRejectUpdateDeleteAndTruncate` | 새 세 표 모두 수정·삭제·일괄 삭제 거부 |
| `httpDraftUsesRoleBoundaryAndNoStore` | 실제 MVC no-store와 작성자/고객 접근 거부 |

테스트를 구현과 동일한 분기 확인에만 한정하지 않고 다른 인물/세션·근거 변경·감사 실패 롤백·HTTP 경계와 금융 상태 보존을 함께 검사한다.

## 실행 명령과 결과

선택 JUnit **61개**, 실패·오류·제외0개와 bootJar 빌드 통과. `python3 /tmp/verify-cycle44.py`는 저장소 scripts/verify_edd.py의 격리를 유지하고 관련 --tests 선택만 조정한 실행기다. 실제 테스트 함수·입력별 사례·선택과 실행 소유 컨테이너 제거 결과는 확정 JSON에 보존했다.

신규 전용 프로세스 강제 종료/성능 검증은 실행하지 않았다.

외부 Didit 생성·엔진 HTTP·Kafka 등의 공통 대역과 실제 DB/암호 서명/감사/MVC를 구분한다. 봇0명·실행 소유 DB/컨테이너·격리 외부 주소를 적용하고 기존 서비스/사용자 데이터는 변경하지 않는다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 새 성능 수치·배포·개발 서버 재시작·기존 DB 변환은 없다.

## 관련 커밋과 증적

- [`89173b6`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/89173b6d107a4c5fba6b548cb697d06eb39ca659): 구현과 관련 회귀.

[확정 증적](../reference/customer-final-draft-verification-2026-10-24.json), [043 담당 업무](043-customer-work-queue.md), [040 관측 회신](040-customer-review-replies.md).
