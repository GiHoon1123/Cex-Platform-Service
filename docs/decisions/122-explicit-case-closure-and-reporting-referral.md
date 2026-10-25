# 승인된 거래 판단의 종결·내부 회부를 별도 실행 서명으로 확정하기

승인된 조사 의견을 실제 사건 종결 또는 내부 보고 검토 회부에 연결한다. 의견 승인과 실행 의도를 별도 요청·서명으로 구분하고, 검토되지 않은 새 신호나 의견이 있으면 실행하지 않는다. 종결 이후 새 신호는 새 사건으로 연결하며 종결 기록은 보존한다. 외부 신고·고객 자금 사용은 실행하지 않는다.

## 문제와 기존 동작

[121](121-bound-independent-transaction-case-reviews.md)은 지정한 두 인물이 고정한 자료 구간과 사유를 확인하는 의견 승인이다. 승인 당시에도 사건 종결·외부 보고·금융 허가가 아니라고 명시했다. 그 서명을 사건 종결에 재사용하면 승인자가 확인한 실행 범위를 바꾸게 된다.

120의 고객별 현재 사건 위치는 최초 생성 후 바뀌지 않았다. 사건을 종결하기만 하고 이 위치를 유지하면 새 신호가 이미 종결된 사건에 섞인다. 반대로 과거 사건을 지우고 새로 만들면 당시 검토 근거와 감사 추적이 끊긴다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [ComplyAdvantage 사건 조사 문서](https://support.complyadvantage.com/articles/3606136172-understanding-and-investigating-a-transaction-monitoring-case)를 다시 확인했다. 고객의 미종결 사건에 신호를 모으고 개별 신호 확인 뒤 사건 전체를 판단하는 업무 흐름을 참고했다. 이 제품 내부의 테이블·잠금·서명 방식을 추정하지 않는다.

[OWASP 거래 인가 지침](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)의 서버 고정 업무 내용, 목적별 일회 인증, 현재 실행 상태 검증을 따른다. 기존 OperatorMfaService·실제 WebAuthn 검증·불변 감사·CaseReviewEvidence를 사용하며 새 인증·큐·암호화 의존성을 추가하지 않는다.

이번 변경은 내부 업무 통제다. 종결 조건·인증5분·실행 요청10분은 자체 정책이며 법정 시간이나 보고 면제를 뜻하지 않는다. 적용 근거·외부 운영 조건은 [116](116-durable-native-deposit-monitoring.md), [115](115-authorized-private-transfer-inspection.md)와 연결한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 의견 승인과 실행 서명 분리 | 원래 NOT_CLOSURE 약속을 지키고 실제 행위를 명시적으로 승인 | 운영자의 추가 확인이 필요하다. 기존 의견 서명의 자동 승격은 선택하지 않았다 |
| 현재 사건 전체가 승인 범위와 같아야 실행 | 보지 않은 새 자료가 종결·회부에 포함되지 않음 | 계속 들어오는 신호/의견은 판단을 다시 준비해야 한다. 부분 구간의 의견 승인은121에서 계속 가능 |
| 불변 결정으로 종결 상태 계산 | 과거 사건·구성원·사유를 수정하지 않고 당시 근거 보존 | 범위·현재 상태 조회에 SQL 검증 비용이 든다 |
| 현재 위치만 제한적으로 이동 | 종결 후 새 신호를 새 사건에 수용 | 원래 불변 포인터를 조건부 갱신으로 바꾼다. 같은 고객·이전 사건의 유효한 종결·같은 TX의 새 사건 생성일 때만 허용 |
| 원래 사건에 새 의견이 붙으면 재검토 | 종결 뒤 새 자료가 묻히지 않음 | 현재 상태가 OPEN으로 돌아올 수 있다. 과거 종결 결정은 지우지 않으며 후속 판단으로 다시 종결해야 한다 |
| 회부는 영속 결정의 별도 조회 | 처리 대기 업무가 재시작 후 남음 | 외부 보고 접수/완료가 아니다. 사건은 OPEN이며 보고 담당자의 실질 판단/제출 후속이 필요 |
| 사유는 원래 암호화 판단 참조 | 민감 원문을 요청·감사·회부 목록에 복제하지 않음 | 지정 인물과 현재 열람 조건을 다시 확인해야 하며 부재 인계는 별도 업무 |

## 업무 계약

OPERATOR는 자신이 제안하고 지정 APPROVER가 승인한 판단에서만 후속 요청을 생성한다. 두 계정의 인물·역할·버전·활성/만료 상태를 고객과 함께 정렬된 사용자 잠금 아래 확인한다. 해당 행위자의 현재 추가 인증·원본/의견 열람·최근 정확한 사유 열람이 필요하다.

`CLOSE_EXPLAINED`는 EXPLAINED_ACTIVITY 의견에만, `REFER_FOR_REPORTING_REVIEW`는 REFER_FOR_REPORTING_REVIEW 의견에만 허용한다. 추가 검토 의견·미결·반려 의견을 종결로 바꿀 수 없다. 요청은 원래 판단 UUID/해시·고객/사건·전체 범위 버전·실행 내용·두 계정·만료·정책/보장 범위를 고정한다.

지정 APPROVER는 새 TM_CASE_ACTION 인증으로 승인 또는 반려한다. TM_CASE_REVIEW 인증과 서로 바꾸어 쓸 수 없다. 미결 상태·현재 전체 범위를 잠금 뒤 다시 확인하고 인증 소비·결정·감사를 하나의 TX로 확정한다. 결정 자체가 종결 또는 회부의 근거이므로 별도 워커가 뒤늦게 상태를 적용하는 중간 상태가 없다. 반려는 종결하지 않고 새 요청을 허용한다.

종결된 사건에 새 검토 신호가 오면 새 사건·첫 구성원·현재 위치 이동·필수 감사를 같은 자동 분석 TX로 확정한다. 이미 종결된 사건에 신호를 직접 붙이는 SQL도 거부한다. 사건/구성원/종결 결정은 불변이다. 현재 위치 표의 임의 변경·삭제·TRUNCATE는 거부한다.

기존 사건에 추가 의견이 생기면 당시 종결 범위와 현재 범위가 달라져 OPEN으로 보인다. 당시 종결의 성공 이력은 유지한다. 이미 새 사건으로 위치가 이동한 뒤라면 과거 사건의 재검토와 현재 사건이 동시에 있을 수 있고, 목록은 각각의 사건 범위를 보여 준다. 이를 고객 전체의 금융 위험 해소로 해석하지 않는다.

보고 검토 회부는 AWAITING_REPORTING_REVIEW의 최소 내부 목록에 나타나며 외부 제출·고객 통지·신고 완료·자금 해제를 수행하지 않는다. OPERATOR/APPROVER/AUDITOR의 현재 인증과 조회 감사를 요구한다. 고객은 접근할 수 없다. 회부된 사건의 추가 신호는 같은 미종결 사건에 모이며 기존 회부를 중복 실행하지 않는다.

## 실제 코드와 이해에 필요한 문법

출처: [DepositMonitoringCaseReviews.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseReviews.java#L117), 현재 소스의 부분 발췌.

```java
        var r=read(id);var p=r.payload();var people=lock(actor,p.requesterId(),p.checkerId(),p.userId());var person=assigned(actor,sid,r,people);
        var maker=people.get(p.requesterId());var checker=people.get(p.checkerId());
        if(maker==null||checker==null||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()
```

불변 요청에서 잠글 식별자를 읽고 정렬된 잠금 아래 현재 인가를 확인한다. 후속 업무에 쓰는 근거 검증이며 그 자체로 종결하지 않는다. Java의 record는 고정 필드 묶음이고 TypeScript 타입과 달리 실제 런타임 값/생성자/접근자를 가진다.

출처: [DepositMonitoringCaseDispositions.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseDispositions.java#L53), 현재 소스의 부분 발췌.

```java
        var payload=new Payload(id,reviewId,review.payloadHash(),p.caseId(),p.userId(),p.scope().version(),action,actor,p.checkerId(),POLICY,ASSURANCE,expiry);
        String raw=json.writeValueAsString(payload),hash=hash(raw);
        jdbc.update("INSERT INTO deposit_monitoring_case_actions(id,review_id,case_id,user_id,requester_id,checker_id,request_key,action,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,reviewId,p.caseId(),p.userId(),actor,p.checkerId(),key,action.name(),raw,hash,Timestamp.from(expiry));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.TM_CASE_ACTION,id,actor,p.requesterVersion(),hash,expiry);
```

원래 의견과 다른 실행 정책/행위/해시를 고정한다. 공개 SHA는 무결성 식별자이며 승인 자격이 아니다. 실제 실행은 서버의 결합 정보와 보안키 서명을 검사한다.

출처: [DepositMonitoringCaseDispositions.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCaseDispositions.java#L64), 현재 소스의 부분 발췌.

```java
        check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_ACTION,id,hash,approve,proof);
        jdbc.update("INSERT INTO deposit_monitoring_case_action_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        audit.record(actor,"MONITORING_CASE_ACTION_DECIDED","monitoring-case-action:"+id,"SUCCESS",hash);return read(id);
```

동일 TX 안에서 실제 서명 소비·결정·필수 감사를 묶는다. 감사 실패/누락으로 커밋하지 못하면 인증의 소비와 결정도 되돌아간다. Java 메서드를 순서대로 호출하는 것만으로 원자성이 생기는 것이 아니라 TransactionTemplate과 실제 PG 트랜잭션이 필요하다.

출처: [case-action-schema.sql](../../src/main/resources/monitoring/case-action-schema.sql#L20), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION monitoring_case_closed(target uuid) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_actions a JOIN deposit_monitoring_case_action_decisions d ON d.request_id=a.id AND d.approved
 JOIN monitoring_case_scope(target,9223372036854775807) s ON a.payload::jsonb->>'scopeVersion'=s.scope_version
 WHERE a.case_id=target AND a.action='CLOSE_EXPLAINED');
$$;
```

종결 결정은 고정된 당시 범위와 현재 전체 범위가 같은 경우에만 현재 종결로 인정한다. 새 의견은 기존 결정의 수정 없이 현재 검토 상태를 바꾼다.

출처: [DepositMonitoringCases.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringCases.java#L47), 현재 소스의 부분 발췌.

```java
        if(heads.isEmpty()||Boolean.TRUE.equals(jdbc.queryForObject("SELECT monitoring_case_closed(?)",Boolean.class,heads.getFirst()))){
            id=UUID.randomUUID();jdbc.update("INSERT INTO deposit_monitoring_cases(id,user_id,policy_id,first_assessment_id) VALUES(?,?,?,?)",id,owner,source.get("policy_id"),assessment);
            if(heads.isEmpty())jdbc.update("INSERT INTO deposit_monitoring_case_heads(user_id,case_id) VALUES(?,?)",owner,id);
            else jdbc.update("UPDATE deposit_monitoring_case_heads SET case_id=? WHERE user_id=?",id,owner);
        }else id=heads.getFirst();
```

같은 고객 잠금 아래 새 사건을 만들고 현재 위치만 옮긴다. SQL 트리거는 같은 고객·이전의 실제 종결·새 사건의 현재 생성 TX를 다시 확인한다. 금전 원본/판단 이력을 UPDATE하는 경로가 아니다.

MonitoringCaseTestSupport로121에서 작성한 실제 수탁 입력·조회/메모·소프트웨어 보안키 준비를 분리했다. 두 시험이 같은 준비를 사용하지만 DB는 함수마다 새로 만든다. 기존39개 검토 회귀의 동작·검증 조건을 줄이지 않았다. 새 enum 값은 기존 승인 시험의 빠짐없는 switch 분기에 명시하며 실제 사건 실행은 별도32개가 검증한다.

## 작성한 테스트

[DepositMonitoringCaseDispositionIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringCaseDispositionIntegrationTest.java)의 모든 함수는 실제 PG·실제 서명 검증·불변 판단·감사를 사용한다. 공통 후처리는 사용 가능 잔액0·원장 합계0·고객 제한 변경 없음·엔진/Kafka/노드/가격 호출 없음도 검증한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| `explicitSeparateSignatureClosesTheWholeCase` | 실제 HTTP·별도 서명·no-store, 전체 사건 CLOSED |
| `nextSignalCreatesANewCaseAndPreservesTheClosedHistory` | 종결 후 새 입금 신호는 새 사건, 과거 구성원/종결 보존 |
| `reportingReferralIsDurableAndDoesNotCloseOrFile` | 영속 내부 회부 목록, OPEN 유지·외부 호출 없음 |
| `opinionApprovalAloneNeverExecutesTheAction` | 의견 승인만으로 종결/실행 기록 없음 |
| `wrongFindingCannotBePromotedToClosureOrReferral` | 추가 검토 의견을 종결/회부로 변경409 |
| `explainedFindingCannotAuthorizeAReportingReferral` | 다른 방향으로 후속 행위 교체409 |
| `pendingOrRejectedJudgmentCannotExecute` | 미결/반려 의견409 |
| `newSignalAfterJudgmentRequiresAWholeCaseReview` | 의견 승인 후 새 신호가 있으면409 |
| `newSignalAfterPreparingTheActionBlocksItsSignature` | 실행 준비 후 새 신호409·서명 미소비 |
| `newOpinionAfterPreparingTheActionBlocksItsSignature` | 새 의견409·종결 없음 |
| `laterOpinionReopensCurrentWorkWithoutErasingClosure` | 종결 후 새 자료는 OPEN, 과거 종결 보존·새 판단 뒤 재종결 |
| `originalJudgmentSignatureCannotExecuteAnAction` | 의견용 서명을 실행에 사용403 |
| `actionSignatureCannotChangeDecisionDirection` | 승인 서명으로 반려403, 원래 승인에는 사용 가능 |
| `rejectionDoesNotCloseAndAllowsANewAction` | 반려는 OPEN·역사 보존, 새 실행 가능 |
| `sameKeyHasOneImmutableIntent` | 동일 키 같은 내용의 요청 하나 |
| `consumedActionSignatureCannotBeReplayed` | 사용한 인증/결정 재사용409 |
| `wrongPayloadHashCannotConsumeTheSignature` | 내용 해시 교체409·서명 미소비 |
| `expiryIsCheckedWithoutChangingTheIntent` | Clock 만료 순간409·원본 수정 없음 |
| `revokedMakerCannotBeUsedForExecution` | 제안자 회수 뒤403 |
| `anotherApproverCannotExecuteTheAction` | 지정되지 않은 승인자404 |
| `anotherOperatorCannotProposeFromThePrivateJudgment` | 다른 운영자가 비공개 판단 재사용404 |
| `missingCurrentReasonViewPreventsTheIntent` | 정확한 사유를 열람하지 않은 실행 제안409 |
| `proposalAuditFailureLeavesNoIntentOrBinding` | 감사 예외 시 요청/인증 결합 모두 롤백 |
| `omittedProposalAuditCannotCommit` | 감사 호출이 조용히 누락돼도 DB 커밋 거부 |
| `executionAuditFailurePreservesOpenCaseAndUnconsumedProof` | 실행 감사 예외 시 OPEN·인증 미소비 |
| `omittedExecutionAuditCannotCommit` | 실행 감사 누락 커밋 거부·서명 미소비 |
| `historiesAndCurrentHeadCannotBeArbitrarilyRewritten` | 결정 삭제/TRUNCATE·현재 위치 임의 변경 거부 |
| `customersAndAuditorsCannotPerformCaseActions` | 고객/감사자의 실행 API403·고객 회부 목록403 |
| `referralReadsRequireFreshAuthenticationAndMandatoryAudit` | 현재 인증과 감사 없는 조회 거부 |
| `parallelReuseOfOneExecutionProofHasOneWinner` | 병렬 실행은 승인 하나/409 하나 |
| `twoPendingIntentsCannotBothExecute` | 동일 사건 두 요청 중 하나만 실행 |
| `ambientTransactionsAndUnsupportedRoutesAreRejected` | 외부 TX 혼합·허용하지 않은 경로 거부 |

## 실행 명령과 결과

검증은2026-10-25~26에 실행했다. 첫 회귀 중 가격 시각 제약에서1건 실패했으며 해당 시험은911초가 걸렸다. 실패 XML/로그를 `.local/cycle122-intermediate/review-time-gap`에 보존했다. 재확인 시점의 호스트/컨테이너 시각 차이를 약0.02초로 확인했고 금융 시각 조건·타임아웃·불변 원본을 바꾸지 않았다. 후속 실행 동안만 절전을 방지하고 기존 검토39개를 처음부터 다시 실행했다. 장시간 중단과 실패의 인과를 확정했다고 주장하지 않는다. 최초 컴파일에서 기존 switch 식2곳의 새 enum 분기도 명시적으로 보완했다. 초기 실패·중간 실행은 최종 통과 수에 합산하지 않는다.

`python3 scripts/verify_deposit_monitoring_case_actions.py`의 신규32개, 기존 검토39개·사건18개·승인191개·모니터링16개·조사29개·실제 워커2개·개인정보58개·금융182개를 검증했다. 최종 고유567개/567회, 실패·오류·제외0이다. 기존535개 결과를 그대로 가져온 수가 아니라 이번 소스에서 다시 실행한 결과다.

실제 회귀 명령은 다음과 같다.

```sh
python3 scripts/verify_deposit_monitoring_case_actions.py
python3 scripts/verify_deposit_monitoring_case_reviews.py
python3 scripts/verify_bound_approvals.py
python3 scripts/verify_deposit_monitoring_cases.py
python3 scripts/verify_deposit_monitoring.py
python3 scripts/verify_deposit_monitoring_investigations.py
python3 scripts/verify_deposit_monitoring_drain.py
python3 scripts/verify_codevasp_transfer_inspections.py
python3 scripts/verify_engine_inbox.py
```

환경은 소유 PostgreSQL16·함수/묶음별 DB, 봇0·자동 체인/Kafka 소비/외부 전송 금지다. 자기 컨테이너만 정리하고 기존5개는 유지했다. 실제 명령·함수·DB·로그/XML·집계는 `.local/cycle122-proof`와 연결된 기계 판독 증적에 남긴다. 플랫폼 웹/운영 계정 초기화 CLI JAR 빌드, Python 구문, 소스 발췌/상대 링크와 diff 공백 검사를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고가 남는다.

이번 회귀는 실제 노드·메인넷·공급사·하드웨어 보안키·외부 신고 시스템 검증이 아니다. Rust/프론트를 변경하지 않았으며 엔진/실제 Bitcoin Core 전체 회귀를 이번에 재실행한 것으로 합산하지 않는다.

## 남은 범위

내부 회부 이후 실제 보고 여부 판단/외부 제출·접수와 사건의 후속 처리는 계속 구현해야 한다. 현재 회부는 중복을 막지만 이 기록만으로 신고 의무를 이행했다고 주장하지 않는다. 인물 부재 인계·키 보존·현재 고객/자산 위험과 금융 허가·AVAILABLE 원장/엔진 반영도 별도다.

현재 단일 탐지 정책의 설명된 사건 종결은 다른 정책·미분석 입력·고객 전체 위험을 해소하지 않는다. 현재 사건 위치가 이동했더라도 과거 사건의 추가 의견으로 생긴 재검토를 빠뜨리면 안 된다. 개인정보 접근 점검/운영 보존·DB 계정 권한/호스트 보호와 법적 외부 확인도 코드 검증과 구분한다.

## 관련 커밋과 증적

- [구현 9a1697e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/9a1697e391d11d2a5d3836fc7dc9ea3d20e213f5)
- [격리 실행 증적](../reference/122-explicit-case-closure-and-reporting-referral-verification-2026-10-26.json)
