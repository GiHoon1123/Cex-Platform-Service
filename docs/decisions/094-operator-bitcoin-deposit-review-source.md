# 현재 운영 인증으로 입금 심사 근거를 최소 범위 조회

운영자가 입금 한 건의 고객 자격, 현재 체인 관찰/판단, 보류 원장 연결, 가장 최근 독립 대사를 함께 조회한다. 민감한 신원 원문과 노드 설정을 제외하고 현재 운영 인증/필수 감사를 적용한다. 이 조회는 금융 해제나 이전거래 심사 완료를 승인하지 않는다.

후속 [115 별도 개인정보 검토](115-authorized-private-transfer-inspection.md)는 같은 현재 접근 정책을 재사용하며 OPERATOR/APPROVER에게만 인증된 자료와 불변 접근 증적을 제공한다. AUDITOR의 요약 권한과 구분한다.

## 문제와 기존 동작

고객은091에서 본인 진행 내역을 볼 수 있지만 운영 심사를 위한 근거 조회가 없었다. 조회자가 과거 체인 판단을 현재 관찰로 오인하거나, 새 대사가 진행/실패 중인데 예전 MATCHED만 고르면 잘못된 금융 판단으로 이어질 수 있다.

이번 범위는 단일 입금의 심사 근거 조회다. 고객 자격의 현재 결과와 각 원본 참조를 제공하며, 아직 없는 이전거래/출처 심사를 통과했다고 표시하지 않는다. 후속 승인 구현은 클라이언트가 이 DTO를 되보낸 것만 신뢰하지 않고 현재 원본 재검사와 불변 요청/서명 결합을 구현해야 한다.

## 기존 제품과 공식 자료의 접근

[OWASP API Security2023의 객체별 인가](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/)는 UUID를 안다는 것과 접근 권한을 구분한다. 기존 고객 API와 다른 운영자 전용 경로에서 현재 역할/영속 세션/다중 인증/권한 수명을 확인한다. OPERATOR·APPROVER·AUDITOR의 입금 금융 메타데이터 조회 정책이며 고객 또는 익명에게 공개하지 않는다.

[NIST SP800-53 Rev.5](https://csrc.nist.gov/pubs/sp/800/53/r5/upd1/final)의 접근 통제·최소 권한·감사 원칙은 참고 기준이다. 공식 페이지의2025-08-27 Release5.2.0 안내를 확인했다. 국내 거래소에 NIST 전체 통제가 법적 의무라거나 이 API로 인증을 충족한다고 주장하지 않는다. 기존 OperatorSessionPolicy·CustomerFinancialEligibility·AuditService와 운영 검토 서비스의 사용자 잠금/현재 사실 재검사 패턴을 사용한다.

설치된 Spring Security/JPA/JdbcTemplate/TransactionTemplate과 기존 인덱스를 사용하며 패키지를 추가하지 않았다. 검토하지 않은 거래소의 내부 심사 화면이나 권한 구조를 사실처럼 가정하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|입금 UUID 하나/운영 역할 전용 | 조회 범위를 제한하고 고객/익명·임의 메서드의 내부 조회 차단 | 운영 세 역할은 현재 네트워크의 입금 금융 메타데이터를 조회할 수 있음. 신원 증빙 원문/업무 승인 권한은 주지 않음 |
|현재 역할·최근5분 다중 인증 재검사 | 회수/만료/타 세션으로 옛 역할을 재사용하지 못함 | 운영자는 최근 인증을 유지해야 함. 고객 기본 거래 흐름에 이 추가 확인을 요구하지 않음 |
|사용자 ID 오름차순 잠금 | 기존 권한/고객 정책 갱신과 같은 순서를 따름 | 짧은 플랫폼 DB 경합이 생김. Rust 코어 상태에는 접근/락 추가 없음 |
|체인/판단/보류/최신 대사 단일 SQL | 백그라운드 관찰 사이에 다른 시점의 행을 따로 조립하는 위험 감소 | 노드 최신 상태가 아닌 저장된 현재 사실. 고객 자격 확인 직후 시간 만료도 가능하므로 조회를 금융 승인으로 재사용하지 않음 |
|최신 대사 그대로 표시 | 새 RUNNING/FAILED를 옛 MATCHED로 가리지 않음 | 운영자는 미완료/차이를 별도 조사해야 함. 조회가 자동 대사를 시작하지 않음 |
|상태별 원본 참조와 최소 필드 | 정책/관찰/대사 시점과 이후 변경을 추적 | 별도 신원 증빙 열람/이전거래 심사·서명 승인 업무는 후속 |
|합성 조회 fixture 공유 | 동일 관계 제약을 고객/운영 조회가 함께 검증 | 실제 체인 검증은 별도 Core 회귀로 해야 함. 합성 출력 자체가 진짜 입금 증거는 아님 |

## API와 해석

`GET /api/admin/deposits/BTC/{id}`는 현재 유효한 OPERATOR·APPROVER·AUDITOR 세션을 받는다. 최근 다중 인증5분/현재 역할 수명은 서비스 직접 호출에서도 검사한다. 익명401, 고객403, 없는/다른 네트워크 입금404, 잘못된 UUID400, 미지원 자산/POST403을 검증한다. 응답은 기존 공통 보안 설정의 no-store를 따른다.

- customer: 고객 번호/현재 버전·공통 금융 자격의 현재 결과·CDD 평가/KYC 시도 참조. 이름/생일/증빙 원문은 제공하지 않는다.
- chain: 현재 관찰/확인 수/관찰 시각/최신 여부·지갑 사용 근거·현재 판단 참조. 미관찰/미확인 지갑 사용은 null/UNKNOWN이며 false로 추측하지 않는다.
- decisionCurrent: 현재 판단이 **지금 가리키는 관찰**을 근거로 하는지다. 과거 CHAIN_READY 문자열만으로 현재 준비를 표시하지 않는다.
- pending: 보류 반입 여부와 원장 업무 키. 거래 가능한 잔액/승인 결과와 다르다.
- reconciliation: 가장 최근 실행의 ID/상태/플랫폼 캡처/완료 시각. 없으면 null이며 이전 MATCHED로 우회하지 않는다.
- readAt: 조회 SQL의 시각. 금융 해제의 유효기간/영속 승인 토큰이 아니다.

지갑 접속 이름/주소·RPC 비밀·지출 조건·고객 이메일/신분증·신고 원문을 반환하지 않는다. 체인 사용 여부와 금융 메타데이터는 운영 판단에 필요한 최소 범위다. 이 API에는 승인/거절/원장 변경/엔진 반입 동작이 없다.

## 구현과 이해에 필요한 코드

출처: [BitcoinDepositOperatorAccess.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositOperatorAccess.java#L29), 현재 소스의 부분 발췌.

```java
        var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=? AND network=?",Long.class,receipt,settings.network.id);
        var locked=new HashMap<Long,User>();var ids=new TreeSet<Long>();ids.add(actor);if(!owners.isEmpty())ids.add(owners.getFirst());
        // 다른 운영/고객 업무와 같은 오름차순 잠금으로 교차 조회의 잠금 순서 역전을 피한다.
        for(long id:ids)users.lockById(id).ifPresent(user->locked.put(id,user));
        sessions.requireFresh(locked.get(actor),sid,roles);
        if(receipt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"입금 식별자를 확인하세요");
        if(owners.isEmpty()||locked.get(owners.getFirst())==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 심사 근거가 없습니다");
        return new Access(locked.get(actor),locked.get(owners.getFirst()));
```

115 후속 작업에서 현재 접근 정책을 별도 모듈로 분리했다. 최소 이력 조회는 OPERATOR/APPROVER/AUDITOR를, 개인정보 검토는 OPERATOR/APPROVER만 전달한다. 원본 존재 여부보다 현재 역할·추가 인증을 먼저 판정하는 계약은 유지한다.

TreeSet<Long>은 ID를 중복 없이 오름차순으로 정렬한다. 이 순서로 현재 운영자와 고객을 잠근 뒤 영속 운영 세션을 다시 검사한다. 원본을 못 찾았다는 응답도 운영 인증 뒤에만 반환한다. TypeScript의 Set은 이 숫자 정렬을 자동으로 해주지 않는다.

출처: [BitcoinDepositReviewSource.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java#L59), 현재 소스의 부분 발췌.

```java
                 LEFT JOIN LATERAL(SELECT id,status,snapshot_at,completed_at FROM bitcoin_deposit_reconciliation_runs WHERE receipt_id=r.id ORDER BY started_at DESC,id DESC LIMIT 1) q ON true
                 WHERE r.id=? AND r.network=?
                """,deposit,settings.network.id);
```

LATERAL은 앞의 입금 r.id에 대해 최신 대사 한 건을 선택한다. status=MATCHED 같은 필터를 넣지 않으므로 새 RUNNING/FAILED가 있으면 그 결과를 그대로 보여준다. 조회가 새 대사를 만들거나 원장을 정정하지 않는다.

출처: [BitcoinDepositReviewSource.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java#L69), 현재 소스의 부분 발췌.

```java
            // 조회의 대상과 관찰/대사 참조를 남긴다. 이름/신분증/신고 원문을 감사 문자열에 복사하지 않는다.
            audit.record(actor,"BTC_DEPOSIT_REVIEW_SOURCE_READ","bitcoin-deposit:"+deposit,"SUCCESS","CUSTOMER_VERSION:"+result.customer().version()+":ELIGIBLE:"+result.customer().financiallyEligible()+":CDD:"+result.customer().cddAssessmentId()+":KYC:"+result.customer().kycAttemptId()+":OBSERVATION:"+result.chain().observationId()+":DECISION:"+result.chain().decisionId()+":RECONCILIATION:"+(result.reconciliation()==null?null:result.reconciliation().id())+":ORIGIN_SEARCH:"+(result.originSearch().search()==null?null:result.originSearch().search().id())+":ORIGIN_ATTEMPT:"+(result.originSearch().search()==null?null:result.originSearch().search().attemptId()));return result;
```

감사는 고객 버전/자격 결과/CDD·KYC·관찰·판단·대사 참조를 기록한다. 이름·생일·신고 원문은 복사하지 않는다. 감사 쓰기가 실패하면 DTO를 성공 반환하지 않는다.

출처: [BitcoinDepositReviewSource.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositReviewSource.java#L73), 현재 소스의 부분 발췌.

```java
    private static Long number(Map<String,Object> row,String key){var value=(Number)row.get(key);return value==null?null:value.longValue();}
    private static Instant at(Map<String,Object> row,String key){var value=(Timestamp)row.get(key);return value==null?null:value.toInstant();}
```

Long과 Instant의 null은 미관찰/미실행을 뜻한다. 없음을 false나0회 확인으로 바꾸면 실제 관찰로 오인할 수 있어 null을 유지한다. Java record는 응답에 노출할 필드를 명시하며 JPA User 엔티티 자체를 직렬화하지 않는다.

## 작성한 테스트

신규 [BitcoinDepositReviewSourceIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositReviewSourceIntegrationTest.java)의14개를 작성했다. 실제 운영자 소프트웨어 보안키 등록/인증·JWT·HTTP 필터·현재 영속 세션/PG/감사와 공통 고객 자격을 실행한다. 조회용 블록/출력은 합성 fixture이며 실제 체인 검증으로 표시하지 않는다.

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
|operatorApproverAndAuditorCanReadOnlyCurrentFinancialReferences | 세 운영 역할의 최근 인증으로 조회 성공/no-store·정확 금액·현재 자격 미충족 표시 |
|customerAnonymousAndForeignSessionCannotUseReviewEndpoint | 익명401/고객403, 고객 직접 호출과 타 운영 세션403·원본 존재 여부 숨김 |
|staleMfaOrRevokedSessionCannotReadStoredEvidence |5분 지난 인증/회수 세션의 직접 호출 거부 |
|expiredOperatorGrantCannotReusePreviouslyValidRole | 현재 권한 수명이 끝나면 옛 역할로 조회 불가 |
|absentObservationAndWalletDebitProofStayUnknown | 미관찰/미확인 근거는 UNKNOWN/null·보류 미반입·대사 없음 |
|customerEligibilityReflectsCurrentIdentityRestrictionAndExpiry | 현재 KYC/CDD 통과·거래 제한/고객 버전 변경·KYC 기한 만료를 즉시 구분 |
|currentChainAndPendingLedgerAreReportedWithoutClaimingRelease | 현재 관찰/판단과 실제 보류 원장 참조 표시·원장 미변경·고객 자격과 독립·감사 원본 참조 |
|staleObservationIsVisibleWithFreshnessFalse | 오래된 관찰은 최신false/STALE_OBSERVATION |
|newlyObservedHeadCannotBorrowOldChainDecisionOrMissingWalletProof | 새 관찰 뒤 옛 CHAIN_READY는 decisionCurrent=false, 새 지갑 사용 근거 없음은 null |
|newestRunningReconciliationDoesNotFallBackToOlderMatchedResult | 실제 대사 저장 후 완료 감사 장애로 새 RUNNING 생성, 예전 MATCHED로 우회하지 않음 |
|responseOmitsIdentityDocumentsWalletSettingsAndSecrets | 신원 원문/지출 조건/세션/지갑 설정 제외·실제 조회 감사1개 |
|auditFailurePreventsSuccessfulReadWithoutChangingFinance | 필수 조회 감사 장애면 성공 금지·원장 행 수 그대로 |
|invalidMissingAndUnsupportedInputsAreRejectedAndAmbientTransactionIsForbidden | null400/없는 입금404/외부 TX 거부·HTTP 잘못된 UUID400·미지원 자산/POST403 |
|differentConfiguredNetworkCannotReadReceiptThroughOperatorRole | 운영 역할도 다른 네트워크 원본404 |

모든 시험 종료 시 실제 BitcoinDepositReader/EngineHttpClient 호출0과 사용 가능 잔액 행0을 확인한다. 대사 선택 시험은 별도의 mock reader로 합성 응답을 제공하고 실제 대사 원본/증거/최종 감사 실패를 저장한다. 실제 외부 노드와 일치했다고 주장하지 않는다.

[BitcoinDepositFixtures](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositFixtures.java)로 고객/운영 조회의 합성 부모 원본과 관찰 생성을 분리했다. 기존091 고객 조회13개를 그대로 실행해 추출로 의미가 달라지지 않았는지 확인했다. fixture는 시험 소스에만 두고 제품 JAR에 포함하지 않는다. 불변 행 삭제나 트리거 해제로 준비하지 않는다.

## 실행 명령과 결과

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew testClasses --console=plain
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_platform_security.py
```

마지막 명령은 상위 호출에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 환경을 제거하고 SPRING_KAFKA_LISTENER_AUTO_STARTUP=false를 고정했다.

macOS arm64/Temurin21/PG16/Core31.1. BTC/원장221개와 기존 플랫폼 보안41개, **고유262개 통과**, 실패/오류/제외0개다. 신규14개와 기존 고객 조회13개, 입금별 독립 대사/네트워크 결합/원장 회귀를 포함한다. 각 Spring 설정 묶음과 보안 회귀는 소유한 별도 PG 서버에서 실행했다.

소유 노드 종료/PG 제거·봇0·소비자 자동 시작 금지·외부 주소 격리·bootJar/operatorAdminJar 빌드/시험 fixture 제외를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다.093 금융182개·092 Rust158/Python5개는 직전 실행이며 이번 합계에 다시 넣지 않았다. 프론트/실제 메인넷/이전거래 공급사 실연동·새 성능/OSV 감사는 실행하지 않았다.

## 남은 연결

이 조회는 서명된 승인 원본도, 트래블룰/개인 지갑 소유/자금 출처 검증 결과도 아니다. 후속으로 실제 이전거래 근거를 검증·결합하고, 요청/독립 승인/실행 직전 현재 사실 재검사, 플랫폼 영속 발송/엔진 HTTP/WAL/Kafka 금융 반영과 재조직 조치를 이어야 한다. 현재 보여준 원본이 나중에도 유효하다고 가정하거나 customer.financiallyEligible만으로 입금을 해제하지 않는다.


## 관련 커밋과 증적

- [구현 61a2a5f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/61a2a5f96f217bc254c3986368ffad59bbaa9929)
- [격리 실행 증적](../reference/094-operator-bitcoin-deposit-review-source-verification-2026-10-25.json)

## 후속 검색 근거 연결 시점

[103](103-operator-deposit-origin-search-review.md)에서 같은 인가/감사 경계에 출처 검색 최소 투영을 연결했다. 금융 기본 근거와 기술 검색은 각각 SQL 읽기 시각을 제공하며 단일 승인 토큰이 아니다. 위 감사 발췌는 현재 검색/시도 참조를 포함하도록 갱신했다.094 당시 구현/검증은 원래 커밋과 증적이다.
