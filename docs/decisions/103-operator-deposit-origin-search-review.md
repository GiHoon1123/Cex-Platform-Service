# 운영 입금 심사에 현재 출처 검색과 미해결 근거를 최소 공개하기

운영자 입금 심사 조회에 현재 접속 주체의 출처 검색 상태와 추가 확인 사유를 붙인다. 현재 역할·최근 MFA·필수 감사를 통과한 읽기만 허용하고, 기술 검색 FOUND를 금융 승인으로 바꾸지 않는다.

후속 [111 운영 평가 근거 조회](111-deposit-valuation-operator-review.md)에서 당시 평가를 연결하고 FOUND 안내를 추가 판단 필요로 수정했다. 아래 코드 발췌는 현재 소스이며103 당시 실행 결과와 구분한다.

후속 [114 보호된 전송/비교 이력 조회](114-private-transfer-operator-metadata.md)는 복호화 없이 최소 상태·사유와 요청 당시 근거를 제공한다. 과거 실행 수치와 현재 응답 계약을 구분한다.

## 문제와 기존 동작

[094](094-operator-bitcoin-deposit-review-source.md)의 운영자 조회는 체인/고객/보류 원장/독립 대사를 보여준다. [102](102-automatic-pending-deposit-search-reservation.md)까지 검색이 자동화됐지만 운영자가 검색의 진행·실패·미해결을 같은 입금 근거에서 확인할 수는 없었다. 마지막 정상 결과만 읽거나 설정 변경 전 검색을 현재 자료로 재사용하면 미완료/실패가 가려진다.

## 기존 제품과 공식 자료의 접근

- [OWASP API1:2023](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/)는 UUID 사용만으로 객체별 인가가 보장되지 않으며 입력 ID마다 권한을 검증하도록 한다. 기존094의 현재 운영 역할/MFA·대상 입금/네트워크 확인을 먼저 수행하고 기술 조회를 그 TX 안에 둔다.
- [CodeVASP 검색 결과](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/12-Search-VASP-by-TXID-Result)는 검색된 사업자와 미확정 사유를 구분한다. FOUND는 송수신인 정보 교환/신원·금융 허가 완료가 아니다. [098](098-codevasp-transaction-origin-search.md)의 해석을 그대로 따른다.
- [094 접근 통제와 최소 투영](094-operator-bitcoin-deposit-review-source.md)·100의 최신 시작 원본 패턴을 재사용한다. 새 역할/승인 레이어/엔드포인트·추가 라이브러리는 만들지 않았다. 법령·신고/인증의 새 충족을 주장하는 작업이 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 기존 인가된 GET에 읽기 투영 추가 | 인증/인가 경계 재사용·입금별 근거를 함께 확인 | 응답 DTO/생성자 계약이 바뀐다. 사용자를 위한 기존 호환 별칭/옛 DTO는 유지하지 않음 |
| MANDATORY TX의 내부 조회 | 현재 역할/MFA·대상 확인과 같은 TX에서 읽기/감사 | 단독 호출할 수 없고 외부 인가를 대체하지 않음. 다른 컨트롤러에 그대로 노출하면 안 됨 |
| 현재 접속 주체의 검색만 현재란에 표시 | 원점/주체/키 변경 전 자료의 조용한 재사용 방지 | 과거 검색은 개수로만 알린다. 전체 상세/페이지 이력·운영 해결 동작은 아직 별도 미완료 |
| 최신 시작에 결과가 없으면 RUNNING | 옛 FOUND로 미완료/실패를 가리지 않음 | 새 외부 장애 때 과거 결과로 처리할 수 없음. 이력이 없어졌다는 뜻은 아님 |
| 원문 대신 상태/참조/시각/사업자 ID만 | 계정/키/주소/신원 원문 노출과 응답 크기 제한 | 분석 원문/전체 회원 목록을 이 API에서 조사할 수 없음 |
| 금융 근거와 검색 근거에 각각 읽기 시각 | 같은 TX의 범위와 SQL 스냅샷 시점을 정확히 표현 | 두 SQL을 전체 시스템의 원자적 금융 승인 스냅샷으로 주장할 수 없음 |

## 구현과 관련 코드

[CodeVaspDepositSearchReview](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java)는 @Transactional(MANDATORY)로 호출자의 TX를 요구한다. 네트워크/노드/엔진 호출·검색 예약·돈의 상태 변경은 하지 않는다. 하나의 SQL에서 현재 주체 검색·최신 시작/결과·최초 시작/자동 선택 기한·다른 주체 검색 수를 읽는다. 결과 본문이 있으면 UTF-8 해시를 다시 대조한다. 응답에는 원문 분석/공급사 주소/키/자격 증명이 없다.

[BitcoinDepositReviewSource](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java)의 기존 사용자 잠금/현재 역할·MFA와 입금/네트워크 확인이 먼저다. 그 뒤 originSearch를 읽고 기존 필수 감사에 검색/시도 ID만 추가한다. 기술 조회503은 SOURCE_UNAVAILABLE_503으로 거부 감사에 구분하고 권한 거부처럼 표시하지 않는다. 필수 성공 감사가 실패하면 응답하지 않는다.

| 응답 필드 | 의미 |
| --- | --- |
|originSearch.providerConfigured/sourceRef | 현재 설정 유무/불투명한 접속 주체 참조. 실제 공급사 검증/기동 성공 보증이 아님 |
|historicalSearches | 현재 주체 외의 검색 수. 미설정이면 전체 과거 검색 수. 현재 정상 결과로 대체하지 않음 |
|search.id/attemptId/phase/status/failureCode | 현재 주체의 검색과 최신 시작/결과 참조. 미착수는 UNSTARTED, 결과 없는 최신 시작은 RUNNING |
|createdAt/startedAt/recordedAt/automaticUntil/automaticWindowEnded | 원본 시각과101의15분 자동 선택 기준. 실제 워커의 기동/생존 상태를 보증하지 않음 |
|originEntityId | FOUND에서만 표시하는 사업자 식별 단서. 정책상 연동 허가/송수신인 확인은 아님 |
|originSearch.readAt | 검색 투영 SQL 시각. 금융 기본 근거의 readAt과 별도 |

reason은 다음의 **기술 처리/추가 확인 안내**다. 승인/반환/금융 거절 결정값이 아니다.

| reason | 의미 |
| --- | --- |
|PROVIDER_NOT_CONFIGURED | 현재 공급사 설정 없음. 과거 검색을 현재란에 쓰지 않음 |
|PENDING_RECOGNITION_REQUIRED | 현재 주체 검색이 없으며 보류 반입 원본도 없음 |
|COINBASE_REVIEW_REQUIRED | 코인베이스 보류의 별도 출처 검토 필요 |
|AWAITING_RESERVATION/AWAITING_SUBMISSION | 예약 전/예약 후 최초 접수 대기 |
|IN_PROGRESS/AWAITING_RESULT | 최신 시작 진행/결과 재확인 대기 |
|AUTO_WINDOW_EXPIRED | 미해결 검색의 자동 선택 기한 종료. 원본은 남음 |
|MANUAL_REVIEW_REQUIRED | NOT_FOUND/UNKNOWN_REQUEST/AMBIGUOUS 추가 검토. 개인지갑 승인/실패 확정 아님 |
|ORIGIN_FOUND_REVIEW_REQUIRED | FOUND지만 송수신인 정보 확인은 별도 |

기존 네트워크/고객/원장/대사 SQL과 검색 투영은 서로 다른 문장 시각이다. 사용자 자격은 기존 사용자 잠금 계약으로 고정하지만 체인/검색의 모든 외부 상태가 하나로 정지했다는 의미는 아니다. 조회 응답을 금융 승인 토큰처럼 보관해 나중 사용하지 말고 실제 업무 시 현재 근거와 버전을 다시 확인해야 한다. 코어 큐·실행 잔액을 조회하지 않는다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L29), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public Review read(UUID receipt){
        // 호출자는 같은 TX에서 현재 운영 역할/MFA와 입금 소유자를 확인한다. 이 내부 메서드를 단독 HTTP로 노출하지 않는다.
        boolean configured=settings.configured();String source=configured?directory.configuredSourceId():null;
        var row=jdbc.queryForMap("""
```

MANDATORY는 이미 열린 트랜잭션에만 참여한다. Spring 프록시가 경계를 적용하므로 실제 권한 확인을 수행하는 기존 입금 심사 서비스가 같은 TX에서 호출한다. 이 내부 메서드는 단독 HTTP 인가 API가 아니다. TypeScript에서도 트랜잭션 객체를 받는 내부 조회와 외부 인증 경계를 분리해야 한다.

출처: [CodeVaspDepositSearchReview.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDepositSearchReview.java#L66), 현재 소스의 부분 발췌.

```java
            reason=switch(status){
                // 출처 발견은 기술적 검색 결과다. 금액·제공 목적·상대 경로 판단을 완료했다고 표시하지 않는다.
                case "FOUND"->"ORIGIN_FOUND_REVIEW_REQUIRED";
                case "NOT_FOUND","UNKNOWN_REQUEST","AMBIGUOUS"->"MANUAL_REVIEW_REQUIRED";
                default->ended?"AUTO_WINDOW_EXPIRED":status.equals("UNSTARTED")?"AWAITING_SUBMISSION":status.equals("RUNNING")?"IN_PROGRESS":"AWAITING_RESULT";
            };
            search=new Search((UUID)row.get("id"),number(row,"attempt_id"),(String)row.get("phase"),status,(String)row.get("failure_code"),at(row,"created_at"),at(row,"started_at"),at(row,"recorded_at"),until,ended,(String)row.get("origin_entity_id"));
```

자바 switch 식은 기술 상태를 추가 확인 안내로 변환한다. FOUND는 ORIGIN_FOUND_REVIEW_REQUIRED이며 승인이라는 값을 만들지 않는다. 자동 선택 기한과 실제 워커의 생존 상태도 구분한다.

출처: [BitcoinDepositReviewSource.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java#L69), 현재 소스의 부분 발췌.

```java
            // 조회의 대상과 관찰/대사 참조를 남긴다. 이름/신분증/신고 원문을 감사 문자열에 복사하지 않는다.
            audit.record(actor,"BTC_DEPOSIT_REVIEW_SOURCE_READ","bitcoin-deposit:"+deposit,"SUCCESS","CUSTOMER_VERSION:"+result.customer().version()+":ELIGIBLE:"+result.customer().financiallyEligible()+":CDD:"+result.customer().cddAssessmentId()+":KYC:"+result.customer().kycAttemptId()+":OBSERVATION:"+result.chain().observationId()+":DECISION:"+result.chain().decisionId()+":RECONCILIATION:"+(result.reconciliation()==null?null:result.reconciliation().id())+":ORIGIN_SEARCH:"+(result.originSearch().search()==null?null:result.originSearch().search().id())+":ORIGIN_ATTEMPT:"+(result.originSearch().search()==null?null:result.originSearch().search().attemptId()));return result;
```

기존 현재 역할/MFA/대상 검증을 통과한 조회에 검색/시도 ID를 추가로 감사한다. 공급사 주소/키/고객 원문을 붙이지 않고 감사 필드 길이 제한을 유지한다. 감사 실패는 성공 응답을 막는다.

## 작성한 테스트

[BitcoinDepositOriginSearchReviewIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositOriginSearchReviewIntegrationTest.java)13개를 추가했다. 실제 PG·JWT·현재 운영 역할/세션·소프트웨어 보안키 MFA·필수 감사와 HTTP 응답을 검증한다. 합성 입금/과거 시각 fixture는 실제 노드/공식 공급사/프로세스 재기동 시험이 아니다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|currentOperatorApproverAndAuditorReadScopedSearchWithNoStore | 세 역할의 현재 인증으로 같은 입금의 현재 검색 참조·no-store·미착수 안내 |
|foundOriginRemainsInformationExchangeRequiredAndNotFinancialPermission | FOUND도 정보 교환 필요·고객 자격/보류는 별도, 감사에 정확한 검색/시도 ID |
|latestRunningSearchCannotBorrowOlderFound | 최신 시작 미완료는 RUNNING/현재 시도, 옛 사업자 ID 미반환 |
|latestFailedPollCannotBorrowOlderFound | 최신 실패/제한 오류 코드, 옛 FOUND 우회 없음 |
|expiredAutomaticWindowIsVisibleWithoutDeletingEvidence | 자동 기한 종료·원본 RUNNING/요청 ID 유지 |
|differentSourceHistoryIsCountedWithoutBecomingCurrentSearch | 다른 주체 검색은 과거 수로만 표시·현재 검색 없음 |
|disabledProviderDoesNotFallbackToHistoricalSuccessfulSearch | 미설정이면 현재 참조/검색 없음·과거 수 보존 |
|unauthorizedOrStaleMfaCannotReadOriginProjection | 고객/낡은 MFA는403·내부 검색 조회 자체를 수행하지 않음 |
|responseAndAuditOmitProviderAddressesKeysAndSourcePayload | 공급사 주소/키·수신 주소/스크립트/세션·전체 분석/승인 플래그 미반환 |
|sourceFailureIsAuditedAsUnavailableAndMandatoryAuditStillBlocksRead | 기술503은 SOURCE_UNAVAILABLE_503 감사, 필수 조회 감사 실패도 응답 차단 |
|internalProjectionRequiresCallerTransaction | 내부 투영 단독 호출은 MANDATORY 경계에서 거절 |
|normalPendingWaitsForReservationAndCoinbaseRequiresSeparateReview | 실제 보류 원장에 반입한 일반/101확인 코인베이스의 안내를 구분·자금 반영 없음 |
|unresolvedTechnicalSearchesRemainManualReviewWithoutOriginOrApproval | NOT_FOUND/UNKNOWN_REQUEST/AMBIGUOUS 모두 검토 필요·사업자/승인 미추측 |

기존 [BitcoinDepositReviewSourceIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositReviewSourceIntegrationTest.java)14개도 재실행했다. 직접 구성한 다른 네트워크 서비스의 생성자에 새 내부 투영을 전달하도록 고쳤고, 역할/세션/체인/보류/대사·비밀 비노출/감사 실패 시나리오는 유지했다. 모든 새 시험 뒤 노드 대신 쓰는 거래/목록 클라이언트·엔진·Kafka 생산자 호출0과 user_balances0을 확인한다. 설정/결과 생성 대역 호출은 fixture 구성 후 지우고 조회 구간의 추가 I/O를 확인한다.

## 실행 명령과 결과

- 첫 실행은 상속된 EddTestSupport.verify와 Mockito.verify의 이름 충돌로 compileTestJava가 실패했다. org.mockito.Mockito.verify로 명시해 수정했다. 이후11개 실행에서1개는 MANDATORY 프록시에 TX 밖에서 대역 설정을 하여 실패했다. AopTestUtils.getUltimateTargetObject로 시험 대상을 설정하고 실제 호출 프록시는 유지했다. 실패를 성공으로 세거나 제품의 TX 경계를 약화하지 않았다.
- Java21/소유 PostgreSQL16. `python3 scripts/verify_origin_review.py`: 최종 신규13개, 실패/오류/제외0.11개 통과 뒤 실제 보류/코인베이스·미해결 상태 시나리오2개를 보강해13개를 재실행했다.
- `python3 /tmp/verify-deposit-review103.py`: 기존14개, 실패/오류/제외0. 저장소의 같은 격리 실행기 구조에서 선택 테스트만 바꾼 로컬 실행본이며 별도 소유 서버/DB를 사용했다. 두 Spring 설정을 같은 DB에 섞지 않았다.
- 고유27개/최종 실행27개·중복0. `.local/cycle103-proof`에 신규13 XML/로그/manifest와 기존14의 사례 목록 manifest/로그·빌드 대조를 보존한다. 기존14 XML은 뒤 Gradle 실행에서 교체돼 별도 보존하지 않았으며 보존했다고 주장하지 않는다. 소유한 두 최종 PG 컨테이너 제거 확인.
- `bootJar operatorAdminJar` 성공, 새 투영 클래스 포함/테스트 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. `git diff --check`·현재 발췌/상대 링크 대조. 의존성/제품 스키마 변경 없음.
- 금융182/자동 검색58/공급사 HTTP54/BTC 실제 노드 전체/Rust/Kafka/브라우저는 이번에 재실행하지 않았다. 이전 실행을 현재 검증으로 재사용하지 않는다.

## 남은 한계

이 단계는 보호된 읽기다. 운영 보류 해결/실행·전체 검색 이력 상세·송수신인 정보 교환/개인지갑·고객/위험/금융 승인·엔진/원장 반영은 미완료다. Source가 설정됐다는 값이나 FOUND·읽기 시각으로 실계정/법적 신고/자금 출처/실제 체인 확정·금융 권한을 증명하지 않는다. 서로 다른 SQL 시점의 조회 결과를 단일 승인 스냅샷처럼 재사용하면 안 된다. 실제 공급사/Sandbox/비공개 Cipher 이미지·법적/운영 적합성은 별도 확인 상태다.


## 관련 커밋과 증적

- [구현 95b2749](https://github.com/GiHoon1123/Cex-Platform-Service/commit/95b27499cd26640e60440083a61ddae14a4744a4)
- [격리 실행 증적](../reference/103-operator-deposit-origin-search-review-verification-2026-10-25.json)
