# 소진된 서비스 알림의 한 번뿐인 독립 재시도 승인

소진된 서비스 알림의 재발송을 현재 수신 자격·같은 원문·독립 인물·업무별 보안키 서명에 묶는다. 누적 횟수와 금융 기록을 보존하면서 추가 시도 예산을 딱 한 번 확정하는 원리를 설명한다.

## 문제와 기존 동작

기본5회가 소진돼도 플랫폼에는 원래 고객 답변이 남지만 외부 알림의 재시도 경로가 없었다. 운영자가 횟수를0으로 되돌리거나 새 작업을 복제하면 과거 실패/중복 가능성·수신 철회·같은 업무의 최대 발송 수를 추적하기 어렵다. 이메일 확인 토큰을 운영자가 다시 발급하는 경로도 만들면 안 된다.

## 기존 제품과 공식 자료의 접근

[AWS SQS 재처리](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-configure-dead-letter-queue-redrive.html)의 권한 분리와 원래 메시지를 수정하지 않는 재처리 범위를 참고했다. 이 프로젝트는 SMTP라서 큐의 정확히 한 번 처리나 실제 고객 수신을 주장하지 않는다. [AWS outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 중복 가능성과 원래 업무/출력 확정 경계를 유지한다. [OWASP 로그 지침](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)에 따라 원문·수신 주소·토큰·서명을 감사 문구로 복사하지 않는다.

독립 인물 승인/업무별 WebAuthn 바인딩은 새 인증 구현 대신 기존 OperatorMfaService를 쓴다. 기존 국내 법/최소 수집·비공개 보고 적용 검토는 [046](046-customer-final-receipt.md), 실제 보안키 바인딩은 [016](016-operator-role-approval-and-bound-authentication.md)을 따른다. 메일에 두 인물을 두는 기준·추가5회/한 번/10분은 자체 정책이며 법정 재발송 절차가 아니다.

## 선택과 트레이드오프

| 선택 | 얻는 점 | 비용/제약과 검토한 대안 |
| --- | --- | --- |
| OPERATOR 요청·다른 인물의 지정 APPROVER 승인 | 단독 계정 오용으로 무제한 알림을 보내지 못하게 한다. | 두 명의 현재 권한·본인 검토·보안키 동작이 필요하다. 단독 재시도 API보다 운영 부담이 크다. 고객의 일상 거래에는 추가 단계를 넣지 않는다. |
| 누적5회를 보존하고 승인된 작업만 최대10회 | 실패 이력/동일 작업 식별을 잃지 않고 최대량을 제한한다. | 추가5회도 결과 불명 시 중복 메일이 가능하다. 무제한 리셋/새 작업 복제는 허용하지 않는다. |
| 같은 원문·메시지 ID·기한·현재 수신 자격 유지 | 과거 계획을 다른 주소/새 내용으로 바꿔 재사용하지 못한다. | 만료/철회/중지/주소 변경이면 거절한다. 인물/고객 버전의 관련 없는 변경도 새 계획이 필요할 수 있는 보수적 비용이 있다. |
| 사용자 정렬 잠금 뒤 출력 행 잠금·READ COMMITTED 재확인 | 동시 요청/결정의 옛 PENDING 관측을 근거로 두 번 승인하지 않는다. | 플랫폼 DB 잠금/조회·저장 비용이 추가된다. SMTP 대기 동안 잠금을 잡거나 Rust 코어를 변경하지 않는다. |

## 구현과 API

`POST /api/admin/operations/customer-mail/{job}/retry-requests`는 지정 checkerId와 Idempotency-Key만 받는다. 같은 키는 당시 요청만 반환한다. 한 작업의 살아 있는 요청은1개, 새 요청은 하루10개까지다. STATUS_NOTICE/FINAL_NOTICE의 EXHAUSTED·누적5회·기한 내 작업만 가능하며 EMAIL_VERIFICATION은 제외한다. 고객은 기존 이메일 확인 API로 새 요청할 수 있다.

`/api/admin/operations/customer-mail-retries/{id}`는 참여자의 현재 세션으로 상태를 조회한다. POST /inspect에서 원문 없는 계획을 제공하고 본인 세션·인물·버전·해시를 기록한다. /approve/options 또는 /reject/options, 이어 /approve 또는 /reject는 지정 승인자의 업무별 보안키 증명을 요구한다. 다른 업무/반대 방향/다른 사람의 서명은 허용하지 않는다.

요청 payload에는 원래 작업의 MAC/키/종류/기한·누적 시도·고객 버전과 두 인물/버전·승인 기한을 결합한다. 원문 주소/토큰은 반환하지 않는다. 별도 HMAC과 정규 JSON 해시로 저장 내용을 검증한다. 승인 기한은10분과 원래 메일 기한 중 먼저 도래하는 값이다. options/decide 직전 현재 계정·수신 선택·주소·소진 상태·키·기한·개인 검토를 다시 확인한다.

결정·서명 소모·불변 grant·RETRY 전환·필수 감사는 같은 트랜잭션이다. grant의 작업 기본 키와 승인 결정 외래 키로 한 번의 추가 예산을 제한한다. 누적 attempts는5를 그대로 두고 다음 claim이6이 된다.10번째 결과 불명 뒤에는 다시 소진된다. 승인 뒤 수신을 철회하면 출력 직전에 억제한다. 반려는 새 예산/발송을 만들지 않는다.

현재 새 스키마 정의와 허용 승인 종류를 갱신했다. 기존 개발/금융 DB 변환을 실행하거나 이력을 삭제하지 않았다. 이전 스키마에 대한 호환 레이어/자동 마이그레이션은 추가하지 않았다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMailRetryService.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailRetryService.java#L64), 현재 소스의 부분 발췌.

```java
            // 누적 시도 수를 초기화하지 않는다. 원래5회에 딱 한 번5회를 더하며 메시지 ID/본문/기한은 유지한다.
            jdbc.update("INSERT INTO customer_mail_retry_grants(job_id,request_id,approved,max_attempts) VALUES(?,?,true,10)",p.jobId(),id);
            jdbc.update("UPDATE customer_mail_delivery SET status='RETRY',next_attempt_at=clock_timestamp(),last_reason='MANUAL_RETRY_APPROVED' WHERE job_id=?",p.jobId());
        }
```

승인된 결정과 grant가 한 트랜잭션에 저장된다. 재시도 상태만 바꾸며 원문/Message-ID/기한과 기존 attempts는 건드리지 않는다. 감사 예외가 나면 WebAuthn 카운터/증명 소모까지 함께 롤백한다.

출처: [CustomerMailRetryService.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailRetryService.java#L80), 현재 소스의 부분 발췌.

```java
        // 출력 행 잠금을 기다린 뒤 READ COMMITTED로 현재 결정/자격을 다시 읽는다. 옛 PENDING 관측을 승인 근거로 쓰지 않는다.
        if(!read(p.id()).status().equals("PENDING")||!p.policy().equals(POLICY)||!p.keyId().equals(crypto.keyId())||!p.jobMac().equals(job.get("content_mac"))||!p.jobKeyId().equals(job.get("key_id"))||!p.kind().equals(job.get("kind"))||!p.mailExpiresAt().equals(((Timestamp)job.get("expires_at")).toInstant())||p.attempts()!=5)throw denied(HttpStatus.CONFLICT);
        eligible(job,delivery);
        if(viewed&&jdbc.queryForObject("SELECT count(*) FROM customer_mail_retry_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,caller.getOperatorPersonId(),caller.getVersion(),sid,r.payloadHash())==0)throw denied(HttpStatus.CONFLICT);
```

최초 읽기의 PENDING은 동시 승인 뒤 낡을 수 있다. 현재 출력 행 잠금 획득 뒤 새 READ COMMITTED 문장으로 결정을 재조회한다. TypeScript Promise의 동시 실행만으로 이 DB 직렬화와 재확인이 생기는 것은 아니다.

## 실행 명령과 결과

`python3 scripts/verify_customer_mail.py`: 현재95개(기존70+재시도25), 실패/오류/제외0·bootJar 통과. 실제 공개 회신·수신 확인/선택·서명·로컬 SMTPS/DB로 검증했다. 초기 enum 추가 때 기존 BoundApprovalTest의 명시적 switch6곳을 빠뜨려 컴파일이 실패했으며 신규 종류를 전용 통합 검증 대상으로 명시했다. 다음88개 실행 중 재시도18개 중17개가 DB의 승인 종류 허용 목록 누락으로 실패했다. 제약을 제거하지 않고 CUSTOMER_MAIL_RETRY를 명시적으로 추가했다.25개 경계까지 보강한95개를 통과했고 불변 트리거는 기존 kyc_event_immutable을 재사용하도록 정리한 뒤95개를 다시 통과했다.

`python3 scripts/verify_bound_approvals.py`190개와 `python3 scripts/verify_operator_mfa.py`42개를 별도 실행 소유 DB에서 통과했다. 공통 승인 종류/DB 허용 목록 변경에 대한 기존 회귀다. 메일95개와 고유 선택327개이며 실패·오류·제외0이다. 각 실행의 중복 선택을 합산하지 않았다.

기존 deprecated/unchecked/JVM 공유 경고가 남는다. 신규 라이브러리/외부 발송/운영 배포는 없고 봇0명·기존 DB/서버·엔진/프론트는 유지한다. SMTP 서버 접수와 수신함 도달/고객 확인을 구분한다.

## 한계

운영자가 검토해야 하는 원인이 실제 공급사에서 해결됐는지는 자동 보증하지 않는다. 고정된 추가 예산 이후의 재발송·기한 연장·원문 수정은 지원하지 않으며 원래 자체 알림을 계속 사용한다. 실제 공급사 계약/외부 경보·바운스·운영 시간 동기화·보존/파기·법적 고지·운영자 교육은 별도다. 실제 스케줄러 강제 종료는 다음058에서 검증한다.

## 작성한 테스트 코드


[CustomerMailRetryIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMailRetryIntegrationTest.java)

- `signedApprovalKeepsOriginalEnvelopeAndIncreasesCumulativeAttempt`: 실제 독립 보안키 승인 뒤 같은 원문/Message-ID로 발송하고 누적 시도는6, 기존 금융 제한은 유지된다.
- `extraBudgetStopsAtTenAndCannotBeGrantedTwice`: 추가5회의 결과 불명은 누적10회에서 소진되며 두 번째 grant/새 승인 요청은 거부한다.
- `rejectionDoesNotReleaseAndAllowsNewRequest`: 반려는 소진 상태를 유지하며 새 계획을 요청할 수 있다.
- `sameKeyReplaysAfterApprovalWithoutAnotherGrant`: 승인 후 같은 키는 옛 승인 사실만 반환하고 grant는1건이다.
- `pendingRequestBlocksAnotherAndChangedKeyTargetConflicts`: 살아 있는 요청은 중복 생성하지 않고 같은 키의 다른 승인자는 충돌한다.
- `ownInspectionAndExactHashAreRequired`: 지정 승인자의 본인 검토/정확한 해시가 없으면 인증 옵션을 거부한다.
- `requesterAndUnassignedCheckerCannotApprove`: 요청자/미배정 승인자는 거부하고 지정 승인자만 결정한다.
- `oppositeDirectionProofCannotApprove`: 반려용 서명은 승인에 쓸 수 없다.
- `anotherBusinessProofCannotApprove`: 최종 답변용 서명은 메일 재시도에 쓸 수 없다.
- `changedRequesterOrCheckerVersionInvalidatesPlan`: 요청자 버전 변경은 이미 발급한 증명을 무효화한다.
- `unsubscribeInvalidatesPlanAndDoesNotConsumeProof`: 수신 철회 후 승인을 거부하고 grant를 만들지 않는다.
- `unsubscribeAfterApprovalStillSuppressesSmtp`: 승인 뒤 철회도 출력 시 억제하며 실제 SMTP 수신은0건이다.
- `decisionAuditFailureRestoresProofAndGrantState`: 결정 감사 실패는 예산/상태/서명 소모를 롤백해 같은 증명 재시도가 성공한다.
- `proposalAuditFailureRollsBackBindingAndCanRetry`: 요청 감사 실패는 요청/바인딩을 롤백하고 같은 키 재시도를 허용한다.
- `concurrentDecisionsCommitOneGrant`: 동시 같은 결정/증명은 하나만 확정하며 grant는1건이다.
- `approvalRecordsRejectUpdateDeleteTruncate`: 요청/열람/결정/grant의 변경·삭제·전체 비우기를 거부한다.
- `verificationMailCannotBeReleasedByOperators`: 이메일 확인 메일이 소진돼도 운영자 재발송 요청은 거부한다.
- `actualHttpRequiresDesignatedApproverAndBoundProof`: 실제 HTTP에서 요청자는403, 지정 승인자의 맞는 서명은 승인된다.
- `changedCheckerVersionInvalidatesProof`: 승인자 버전 변경 뒤 기존 증명은 거부한다.
- `changedRecipientInvalidatesProof`: 현재 주소가 바뀌면 승인하지 않고 원래 소진 상태를 보존한다.
- `disabledCustomerCannotBeReleased`: 고객 중지 뒤에는 재시도 계획을 만들지 않는다.
- `sameHumanAcrossTwoAccountsCannotPropose`: 계정이 달라도 같은 인물 UUID면 독립 승인자가 될 수 없다.
- `expiredSignedPlanIsReadableButCannotBeConfirmed`: 원본을 수정하지 않은 과거 요청 표본은 EXPIRED로 조회되고 승인 검토는 거부한다.
- `repeatedApprovalCannotCreateSecondDecision`: 이미 승인한 증명의 재사용으로 두 번째 결정을 만들 수 없다.
- `wrongSessionAndAmbientTransactionCannotPropose`: 다른 사람 세션과 외부 트랜잭션 참여는 거부한다.

## 관련 커밋과 증적

- [구현 ebb4423](https://github.com/GiHoon1123/Cex-Platform-Service/commit/ebb44239906476fbd784a40863b486d1b420c985)
- [격리 실행 증적](../reference/057-customer-mail-retry-approval-verification-2026-10-24.json)
