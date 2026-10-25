# 고정한 거래 구간의 판단을 지정 인물의 독립 서명에 결합하기

담당자가 명시한 거래 탐지 구간에 대해 판단을 제안하고, 지정한 다른 인물이 실제 자료와 사유를 확인한 뒤 내용에 결합된 보안키 서명으로 승인·반려한다. 판단 원문은 암호화하고 과거 결정은 수정하지 않는다. 이 승인 자체가 사건 종결·외부 보고·고객 자금 사용을 실행하지는 않는다.

## 문제와 기존 동작

120은 관련 신호를 사건으로 모았고118은 담당자의 의견을 남겼다. 하지만 다른 인물이 어떤 자료 범위를 보고 어떤 사유를 승인했는지, 판단 중에 의견이 추가되면 이전 서명을 어떻게 막을지 정한 업무는 없었다.

최신 상태 전체만 승인 대상으로 삼으면 계속 입금되는 고객의 검토가 매번 무효가 될 수 있다. 반대로 이전 판단에 뒤늦게 들어온 신호를 자동 포함하면 승인자가 보지 않은 사실까지 승인한 것처럼 된다. **원장 순번의 명시적 접두 구간**을 고정하고 그 범위의 의견 추가만 이전 요청을 무효화한다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [OWASP 거래 인가 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)을 확인했다. 로그인과 민감 업무의 인가를 구분하고 중요한 업무 내용을 서버가 고정하며, 바뀐 내용·다른 목적·만료된 인증으로 실행하지 않도록 하는 접근을 따른다. 전체 체크리스트 준수나 법적 전자서명 인증을 완료했다는 뜻은 아니다.

[ComplyAdvantage 거래 사건 조사](https://support.complyadvantage.com/articles/3606136172-understanding-and-investigating-a-transaction-monitoring-case)의 개별 신호/거래 확인과 기록된 판단 구분도 참고했다. 이 제품이 내부에서 같은 SQL/서명 구조를 쓴다고 추정하지 않는다. 국내 적용 근거와 단일 규칙의 한계는 [116](116-durable-native-deposit-monitoring.md), 개인정보 접근/접속기록 적용 조건은 [115](115-authorized-private-transfer-inspection.md)에 연결한다.

실제 구현은 기존 [ReportingReviewService](../../src/main/java/dustin/cex/domains/edd/ReportingReviewService.java)의 지정 인물·암호화 사유·불변 요청·독립 서명·감사 패턴과 OperatorMfaService를 재사용한다. 새 인증/암호화 라이브러리를 추가하지 않는다. EDD의 신원/보고 자료를 거래 패턴의 근거로 잘못 연결하지 않고 거래 사건에 맞는 데이터만 사용한다.

[Java21 Clock 공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html)는 현재 시각이 필요한 곳에 표준 Clock을 전달해 다른 시계를 시험에 사용할 수 있는 방식을 설명한다. 기존 코드에서 주입 가능한 시계를 검색했으며 없어서 이번 만료 검증에만 이름이 정해진 systemUTC 빈을 둔다. 실행 설정·시간 서버·자체 시계 프레임워크는 추가하지 않았다.

## 검토 구간의 의미

| 항목 | 의미 |
| --- | --- |
| caseId·고객·탐지 정책 | 어떤 조사 파일의 자료인지 식별 |
| throughCursor | 실제 사건 신호 중 마지막 원장 순번. 그 순번 이하가 이번 범위 |
| signals | 해당 접두 구간의 불변 신호 수 |
| opinions | 그 신호들에 붙은 불변 담당자 의견 수 |
| version | 위 식별자·순번·개수로 만든 변경 위치 토큰 |

신호/의견은 추가만 가능하고 기존 내용을 바꿀 수 없다. 고객 행을 잠근 상태에서 같은 접두 구간의 위치를 다시 계산해 요청과 비교한다. 범위 안 의견이 추가되면 버전이 달라져 기존 요청/서명을 승인하지 못한다. 범위 밖의 새 입금·신호/의견은 이번 서명에 포함하지 않으며 별도 미검토로 남는다.

opinions는118의 원본에 연결된 담당자 의견만 센다. 열람/감사·판단 요청/결정 자체를 다시 세어 스스로 검토 위치를 바꾸지는 않는다.

위 토큰은 **원본 전체의 암호학적 커밋이나 위험 해소 증명 자체가 아니다**. 각 원본은118에서 지문·실제 금융 입력·규칙 결과를 검증하고 각 의견은 인증 복호화/HMAC 검증을 통과해야 한다. 판단 내용의 무결성은 별도의 정확한 UTF-8 payloadHash·사유 HMAC·전체 payloadHash를 인증 부가 데이터로 쓰는 암호화·내용 결합 서명이 보장하는 범위를 구분한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 명시한 접두 구간의 판단 | 계속 유입돼도 본 범위까지의 결과를 남기고 새 신호를 몰래 승인하지 않는다 | 과거 승인만으로 현재 사건 전체가 해결되지 않는다. 이후 범위와 후속 업무가 필요하다 |
| 서로 다른 계정·실제 인물의 지정 | 다른 계정으로 같은 사람이 요청/승인을 겸하는 우회 차단 | 부재·퇴사/권한 회수 시 같은 요청을 제3자가 임의로 이어받지 못한다 |
| 두 인물의 실제 원본/메모 열람 확인 | 요약만 읽고 원문을 검토했다고 처리하지 않는다 | 겹치는 신호가 많으면 열람/조사 부담이 커진다. 화면의 묶음 검토/운영 역량과 적체 관리가 필요하다 |
| 사유는 지정 두 인물만 복호화 | 역할만으로 다른 사건 판단의 원문에 접근하지 않는다 | 일반 사건 조회와 달리 별도 지정/인계 절차가 필요하다 |
| 불변 요청+소비된 일회 인증+결정 같은 TX | 내용·목적 교체/재사용·동시 이중 결정·감사 없는 성공 차단 | 감사/DB 장애 시 서명 소비와 결정도 함께 롤백한다 |
| 임시 인증과 영구 결정의 수명 분리 | 다음 업무에서 인증 옵션을 정리해도 결정은 보존한다 | UUID와 결정/감사 추적은 보존하지만 임시 옵션 행을 영구 금융 근거로 간주하지 않는다 |
| 표준 Clock 주입 | 호스트 시계나 불변 요청을 수정하지 않고 정확한 만료 경계를 시험 | 작은 시간 의존성이 생긴다. 운영은 시스템 UTC와 DB/호스트 시간 조건을 확인해야 한다 |

현재 인증5분·판단 요청10분·인증 옵션2분은 기존 운영 패턴에 따른 자체 정책이며 법정 지정 시간이 아니다. 승인/반려의 방향·판단 사유·대상·자료 범위는 서로 바꾸어 쓸 수 없다. 실제 운영 인물의 자격·조직 권한·키 보존/인계·외부 심사 확인과 코드의 역할/서명 검증은 구분한다.

## 상태와 권한

- 사건의 OPEN은 아직 해당 조사 업무가 종결되지 않았다는 뜻이다.
- Review의 PENDING/APPROVED/REJECTED/EXPIRED는 **그 요청의 지정된 범위에 대한 내부 판단 상태**다.
- 사유 안의 EXPLAINED_ACTIVITY/REQUIRES_FURTHER_REVIEW/REFER_FOR_REPORTING_REVIEW는 내부 설명·추가 검토·보고 필요성 검토 회부 의견이다. 외부 보고가 접수되거나 고객 제한이 풀린 상태가 아니다.

GET `/api/admin/monitoring/case-reviews/context/{case}?through={cursor}`는 실제 검토 위치를 제공한다. OPERATOR/APPROVER만 사용하며 기본값은 현재 마지막 신호다. 존재하지 않는 중간 원장 위치·0/음수는 유효한 범위로 사용하지 않는다.

POST `/api/admin/monitoring/case-reviews`는 OPERATOR의 Idempotency-Key·caseId·checkerId·expected 위치·facts를 받는다. 실제 생성 전에 현재 계정/인물·지정 승인자·입력 크기·위치·필요한 원본/메모 열람을 확인한다. 같은 범위의 미결/승인된 판단은 중복 생성하지 않고 반려/만료 후 새 요청을 남길 수 있다.

GET 목록과 POST `/{review}/inspect`는 현재 역할이 있어도 원래 지정된 계정·인물만 제공한다. 사유 열람은 별도 불변 열람/감사를 남긴다. 감사자/고객/익명이나 지정되지 않은 다른 운영자는 원문을 읽지 못한다. 과거/만료 자료도 현재 지정 인물은 읽을 수 있지만 승인에 쓸 수 있다는 뜻은 아니다.

POST `/{review}/approve/options`, `/reject/options`, `/approve`, `/reject`는 지정 APPROVER의 현재5분 인증·요청 당시 인물/버전·미결/만료·해시·현재 구간·원본/의견 열람·최근 정확한 사유 열람을 확인한다. 마지막 실행은 기존 실제 WebAuthn 검증과 일회 소비를 통과해야 한다. 역할만으로 승인하거나 공개 SHA값만으로 서명하지 않는다.

## 구현과 관련 코드·문법

출처: [CaseReviewEvidence.java](../../src/main/java/dustin/cex/domains/monitoring/CaseReviewEvidence.java#L27), 현재 소스의 부분 발췌.

```java
        var rows=jdbc.queryForList("SELECT * FROM monitoring_case_scope(?,?)",id,through==null?Long.MAX_VALUE:through);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var r=rows.getFirst();long last=((Number)r.get("through_cursor")).longValue();
        if(last<=0||(through!=null&&last!=through))throw denied(HttpStatus.CONFLICT);
        return new Position(id,((Number)r.get("user_id")).longValue(),(String)r.get("policy_id"),((Number)r.get("signals")).longValue(),Long.toString(last),((Number)r.get("opinions")).longValue(),(String)r.get("scope_version"));
    }
```

SQL 함수는 실제 CaseMember의 접두 구간과 그 안 의견을 센다. 입력 위치를 고객이 보낸 임의 숫자로 인정하지 않고 실제 마지막 원장 위치인지 확인한다. String 순번은 TypeScript number의 정수 정밀도 문제를 피하고 Java 내부에서는 long으로 검증한다.

출처: [DepositMonitoringCaseReviews.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseReviews.java#L73), 현재 소스의 부분 발췌.

```java
        var current=evidence.position(id,CaseReviewEvidence.cursor(expected));if(!current.equals(expected)||current.opinions()<1)throw denied(HttpStatus.CONFLICT);evidence.requireViewed(maker,sid,current);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_reviews r LEFT JOIN deposit_monitoring_case_review_decisions d ON d.review_id=r.id WHERE r.case_id=? AND r.scope_version=? AND(d.approved=true OR(d.review_id IS NULL AND r.expires_at>?)))",Boolean.class,id,current.version(),Timestamp.from(clock.instant()))))throw denied(HttpStatus.CONFLICT);
        UUID review=UUID.randomUUID();Instant expires=clock.instant().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        var payload=new Payload(review,id,current.customerId(),current,POLICY,ASSURANCE,mac,crypto.keyId(),cdd.keyId(),actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expires);
```

요청 생성 시 해당 구간이 expected와 같은지와 실제 열람을 확인한다. 같은 고객의 병렬 요청은 정렬된 사용자 잠금 아래 미결/승인 존재를 검사해 중복을 막는다. 금액·고객·판정 권위를 요청 바깥의 임의 필드로 바꾸지 않는다.

출처: [DepositMonitoringCaseReviews.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseReviews.java#L77), 현재 소스의 부분 발췌.

```java
        String raw=json.writeValueAsString(payload),hash=hash(raw);byte[] cipher=crypto.encrypt(context(review,hash),json.writeValueAsBytes(facts));
        jdbc.update("INSERT INTO deposit_monitoring_case_reviews(id,case_id,user_id,requester_id,checker_id,request_key,through_cursor,scope_version,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",review,id,current.customerId(),actor,checker,key,CaseReviewEvidence.cursor(current),current.version(),raw,hash,cipher,Timestamp.from(expires));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.TM_CASE_REVIEW,review,actor,maker.getVersion(),hash,expires);
```

정확히 저장한 UTF-8 요청 문자열의 SHA-256을 사용한다. 전체 해시를 AES-GCM 인증 부가 데이터에 넣어 본문/만료/범위 등을 바꾸고 공개 해시만 다시 계산해도 원래 사유 암호문을 읽지 못한다. 공개 해시와 사유 HMAC/암호화의 역할은 다르다.

출처: [DepositMonitoringCaseReviews.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseReviews.java#L94), 현재 소스의 부분 발췌.

```java
        mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_REVIEW,id,hash,approve,proof);
        jdbc.update("INSERT INTO deposit_monitoring_case_review_decisions(review_id,approver_id,approver_person,approver_version,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),checker.getVersion(),sid,approve,hash,proof.challengeId());
        audit.record(actor,"MONITORING_CASE_REVIEW_DECIDED","monitoring-case-review:"+id,"SUCCESS",(approve?"APPROVED_INTERNAL_ONLY:":"REJECTED:")+hash);return read(id);
```

인증은 TM_CASE_REVIEW·요청 UUID·정확한 내용 해시·승인/반려·승인자 세션에 결합한다. 원래 요청과 다른 목적/해시/방향에는 사용할 수 없다. 인증 소비·공개키 카운터·결정·감사가 같은 실제 TX에 있어 실패 시 함께 롤백된다.

출처: [DepositMonitoringCaseReviews.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseReviews.java#L84), 현재 소스의 부분 발췌.

```java
        // 불변 요청으로 잠글 사용자만 파악한다. 역할·버전은 정렬된 잠금에서 처음 읽고 원문은 인증 뒤에 복호화한다.
        var r=read(id);var people=lock(actor,r.payload().userId());var person=assigned(actor,sid,r,people);var content=facts(r);UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO deposit_monitoring_case_review_views(id,review_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",view,id,actor,person.getOperatorPersonId(),person.getVersion(),sid,r.payloadHash());
```

암호화 요청의 불변 식별자로 잠글 사람을 파악한 뒤 현재 역할/버전을 정렬된 잠금에서 처음 읽는다. 잠금 전 JPA 엔티티를 읽어 캐시에 남기는 새 경로를 없앴다. 실제 원문은 현재 인가 뒤에만 복호화한다.

출처: [MonitoringReviewTime.java](../../src/main/java/dustin/cex/domains/monitoring/MonitoringReviewTime.java#L9), 현재 소스의 부분 발췌.

```java
    @Bean("monitoringReviewClock") Clock clock(){return Clock.systemUTC();}
```

Clock.systemUTC는 운영의 표준 시각 공급자다. 시험에서는 이름으로 이 빈만 대역으로 교체해 요청 만료 순간을 만든다. 원래 불변 요청·감사·호스트 시계는 건드리지 않는다.

새 승인 종류 TM_CASE_REVIEW를 enum과 DB 허용 목록에 추가했다. 기존 CHECK를 실제 현재 종류로 확장하지만 과거 승인 요청/결정 내용을 변환하거나 삭제하지 않는다. `kindExpansionPreservesExistingImmutableBindings`가 원래 ROLE 결합 행 전체의 보존과 불변 트리거 유지·새 종류의 정상 사용을 검증한다.

기존 BoundApprovalTest의 Java switch 식6곳은 새 enum 값 때문에 컴파일이 거부됐다. default로 모든 새 종류를 받아들이지 않고 TM_CASE_REVIEW를 명시적인 업무별 전용 검증 그룹에 넣었다. 실제 거래 사건 서명은 새39개 회귀가 담당한다. TypeScript의 never를 이용한 빠짐없는 분기 검사와 비교해 이해할 수 있다.

인증 옵션은 다음 업무/만료 때 삭제하는 단기 상태다. 영구 결정은 인증 UUID를 값으로 보존하고 등록 시점에 소비된 목적/해시/주체와 연결하되 그 임시 행에 영구 FK를 걸지 않는다. 다음 업무 인증이 막히거나 결정 이력이 옵션 정리와 함께 사라지지 않는다. 원래 승인 결합·결정·감사 기록은 불변 보존한다. 이 기록을 법적 비부인성 인증이나 오래된 옵션만으로 서명 수학을 독립 재검증하는 완성된 외부 증명으로 과장하지 않는다.

## 작성한 테스트와 기대 결과

[DepositMonitoringCaseReviewIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringCaseReviewIntegrationTest.java)에 실제 PG·운영 소프트웨어 보안키·원본/사유 인증 암호화·서명·트랜잭션을 사용하는39개를 작성했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| assignedPeopleCompleteAnActualContentBoundDecision | 실제 HTTP 제안/no-store·지정 두 사람의 자료/사유 열람·실제 보안키 승인·원문 비노출 |
| rejectionKeepsHistoryAndAllowsANewRequest | 반려는 불변 이력, 같은 범위의 새 요청 가능 |
| proposalNeedsTheMakersActualSourceAndNoteViews | 요청자 원본/메모 열람 없으면409, 열람 뒤 생성 |
| approvalRequiresEvidenceAndTheExactPrivateReason | 승인자 자료/최근 정확한 사유 확인 없으면409 |
| laterSignalIsNotSilentlyIncludedInTheSignedScope | 새 신호가 와도 원래 구간만 승인, 새 신호는 여전히 별도 미검토 |
| newOpinionInsideTheScopeInvalidatesPreparedSignature | 범위 안 의견 추가 후 기존 서명409·미소비 |
| newOpinionOutsideTheScopeDoesNotChangeItsFacts | 범위 밖 새 의견을 원래 승인에 포함하지 않음 |
| wrongScopeAndAnotherCaseCannotCreateARequest | 위조 위치 토큰/다른 사건의 expected 거부 |
| anotherPersonsViewsDoNotAuthorizeTheMaker | 타인의 열람 기록을 요청자 확인으로 재사용하지 못함 |
| samePersonCannotActAsTwoDifferentAccounts | 같은 인물의 다른 계정으로 독립 승인자 지정403 |
| sameRequestIsIdempotentButDifferentFactsConflict | 같은 키 같은 원본은 동일 요청, 다른 사유409 |
| oneScopeCannotHaveMultiplePendingOrApprovedRequests | 한 범위의 미결/승인 중복 요청409 |
| exactExpiryBoundaryBlocksOptionsWithoutRewritingTheRequest | Clock으로 만료 순간을 재현, 불변 요청 수정 없이409/EXPIRED |
| wrongHashCannotGetOrUseAnAuthorization | 다른 해시의 옵션/실행409·서명 미소비 |
| approvalSignatureCannotAuthorizeRejection | 승인용 서명으로 반려403, 원래 승인에는 사용 가능 |
| consumedSignatureCannotCreateAnotherDecision | 이미 확정한 서명/요청 재사용409·결정1개 |
| decisionAuditFailureRollsBackSignatureAndDecision | 감사 예외 시 결정·인증 소비/카운터 롤백, 원래 서명 재시도 정상 |
| omittedDecisionAuditCannotCommit | 감사 호출 누락도 구체 지연 제약 오류·인증 미소비 |
| proposalAuditFailureCannotLeaveAnApprovalBinding | 제안 감사 실패 시 요청·인증 결합 모두 없음 |
| omittedProposalAuditCannotCommit | 제안 감사 누락의 구체 제약 거부 |
| privateReasonAuditFailureDoesNotLeaveAView | 사유 열람 감사 예외 시 열람 증적/성공 응답 없음 |
| omittedPrivateReasonAuditCannotCommit | 사유 열람 감사 누락도 커밋 차단 |
| unassignedPeopleCannotReadThePrivateFacts | 지정되지 않은 OPERATOR/APPROVER는404/빈 목록, AUDITOR/고객403 |
| staleMfaAndRevokedPeopleCannotReuseAnAssignedReview | 오래된 인증/비활성 계정은 과거 지정으로 접근하지 못함 |
| changedMakerVersionBlocksTheOldRequest | 요청자 운영 버전 변경 후 승인403 |
| changedCheckerVersionBlocksTheOldRequest | 승인자 운영 버전 변경 후 승인403 |
| aDifferentSessionCannotBorrowPriorViewReceipts | 새로 인증한 다른 세션도 이전 세션의 열람 증적을 빌리지 못함 |
| damagedCipherIsRejectedBeforePrivateResponse | 인증 태그 손상503·열람 증적 없음 |
| newPublicHashCannotAuthenticateATamperedPayload | 만료 본문/공개 해시를 바꿔도 원래 암호문의 전체 해시 결합 검증503 |
| unsupportedFactKeysCannotFallBack | 다른 현재 키로 과거 사유를 추측해서 읽지 않고503 |
| privateHistoriesCannotBeUpdatedDeletedOrTruncated | 요청/열람/결정의 수정·삭제·함께 비우기 거부 |
| kindExpansionPreservesExistingImmutableBindings | 허용 종류 CHECK 확장 뒤 원래 ROLE 결합 행 그대로·불변성 유지·새 종류 사용 |
| aDifferentApprovalKindCannotConsumeTheSignature | 거래 제한 등 다른 종류의 업무는 같은 서명을 소비하지 못함 |
| unsupportedMethodsAndAmbientTransactionsStayDenied | GET 열람/임의 하위 경로·외부 TX 거부 |
| aCaseWithoutAnyOpinionCannotBecomeAReviewFromCountsAlone | 실제 별도 고객/사건에서 의견0이면 판단 생성409 |
| malformedFactsAndScopeFieldsAreRejectedBeforeStorage | 빈/과대/숨은 방향 문자·누락 토큰/순번·0위치400, 저장 없음 |
| discardingConsumedAuthorizationDoesNotEraseThePermanentDecision | 다음 범위 판단의 인증 옵션이 이전 단기 옵션을 정리해도 과거 결정 UUID/새 승인 보존 |
| concurrentReuseOfOneSignatureCommitsOnlyOneDecision | 실제 동시 호출에서 승인1개/409하나·결정1개/일회 소비 |
| revocationAfterReadingTheRequestIsCheckedUnderTheUserLock | 불변 요청 조회 직후 별도 TX가 계정을 회수하면 잠금 뒤403·복호화 호출 없음 |

후처리로 원장 합계0·사용 가능 잔액0·고객 거래 제한 변경 없음·사건 OPEN 유지·노드/엔진/Kafka/추가 가격 호출 없음을 확인한다. 사유의 실제 손상은 읽은 복사본/암호화 대역에만 주입한다. 운영 상태인 인증 회수·허용 종류 제약 변경을 검증하되 원래 금융/감사 이력을 지우거나 불변 트리거를 끄지 않는다.

초기 컴파일에서 시험 도우미와 정적 함수의 이름 충돌·JDBC 오버로드 및 새 enum의 빠진 분기를 발견해 명시적으로 수정했다. 첫 실제 PG 시도1건은 감사 함수의 변수 target과 표 컬럼의 충돌로 커밋 실패했다. 변수 이름을 구분하고 감사 제약을 유지했다. 중간35개 성공 뒤 입력 경계·단기 인증 정리·경합/권한 회수를 보강해 최종39개를 모두 다시 실행했다. 초기/중간 실행은 최종 수에 합산하지 않는다.

## 실행 명령과 결과

| 최종 실행 | 결과 |
| --- | --- |
| `python3 scripts/verify_deposit_monitoring_case_reviews.py` | 신규39개 통과, 함수별39 DB |
| `python3 scripts/verify_bound_approvals.py` | 기존 승인/역할/세션/보안191개 통과, 묶음별4 DB |
| `python3 scripts/verify_deposit_monitoring_cases.py` | 기존18개 통과 |
| `python3 scripts/verify_deposit_monitoring.py` | 기존16개 통과,1001개 실제 금융 입력/분석 경계 포함 |
| `python3 scripts/verify_deposit_monitoring_investigations.py` | 기존29개 통과 |
| `python3 scripts/verify_deposit_monitoring_drain.py` | 실제 워커2개 통과 |
| `python3 scripts/verify_codevasp_transfer_inspections.py` | 기존58개 통과 |
| `python3 scripts/verify_engine_inbox.py` | 기존 금융182개 통과 |

최종 **고유535개/535회**, 실패·오류·제외0. 실행기들을 순서대로 실행해 Gradle 결과 폴더 경합을 피했다. 소유 PostgreSQL16·함수/묶음별 DB·봇0·자동 Kafka 소비/체인 자동 처리/메일 전달 금지·외부 환경 제거/격리 주소를 적용했다. 소유 컨테이너는 정리했고 기존5개 컨테이너를 유지했다. 최종 XML/로그/JSON·중복 제거 집계는 `.local/cycle121-proof`에 보존한다.

최초 집계에서 JUnit 매개변수 시험의 표시 이름이 서로 다른 메서드 사이에서 겹치는 문제가 드러났다. 시험은 각각 실행됐지만 XML에서 메서드를 구분할 수 없었다. 설치된 JUnit 6.0.3의 표시 이름에 메서드·입력 종류·순번을 함께 넣고 승인 회귀191개만 다시 실행했다. 이 최종 결과로 증적을 교체했으며 이전 실행은 합산하지 않았다. 실행기도 중복된 사례 식별자를 거부한다.

검증 격리 선행 커밋은 [`44a0da2`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/44a0da2)다. 기존 승인 실행기는 실행별 UUID 폴더에 로그/XML을 분리하고 실제 사례 목록/DB·정리 결과를 남기도록 보완했다. 이전 실행의 XML을 새 결과에 섞거나 실제 외부 환경을 상속하지 않는다. 별도 제품 동작 변경이 아니라 검증 격리/증적 개선이다.

플랫폼 웹 JAR와 운영 계정 초기 권한 부여 CLI JAR 빌드·새 클래스/SQL 포함·시험 제외, Python 구문·현재 코드 발췌/링크·`git diff --check`를 확인했다. CLI는 추가 HTTP 서버가 아니며 이번 검증에서 실제 운영 계정 권한을 부여하지 않았다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

MockMvc는 실제 필터/컨트롤러를 실행하지만 외부 TCP/메인넷/공급사 시험은 아니다. 서명은 실제 검증 코드를 사용하는 시험 소프트웨어 보안키이며 하드웨어 인증·법적 서명/신고·실사·장기 부하 검증 완료로 표시하지 않는다. Rust/프론트는 변경하지 않았고 실제 Core 전체 회귀를 이번 변경 뒤 다시 실행했다고 표시하지 않는다.

## 남은 범위

내부 판단의 승인과 사건의 최종 종결·회부/보고 후속·외부 제출/접수는 구분된다. 새 신호를 이미 판단된 것으로 간주하지 않으며, 명시한 구간 밖 자료와 변경된 의견은 후속 검토해야 한다. 현재 위험/출처·고객 자격·입금 금융 판단·사용 가능 잔액/엔진 반입도 별도다.

지정 인물의 부재/퇴사 뒤 역사 자료 인계·감독과 승인된 판단에 따른 실제 후속 처리는 계속 구현해야 한다. 키 보존/회전·법적 보류/파기·접속기록 점검·DB 관리자/호스트 보호는 운영/인프라 검증 상태다. 코드만으로 모든 준수/신고 요건을 충족했다고 주장하지 않는다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 05cff04](https://github.com/GiHoon1123/Cex-Platform-Service/commit/05cff0455fb44ad79689a0506ca155038720e8f8)
- [격리 실행 증적](../reference/121-bound-independent-transaction-case-reviews-verification-2026-10-25.json)
