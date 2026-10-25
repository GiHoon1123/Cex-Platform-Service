# 거래 탐지 원본과 암호화된 담당자 조사 의견 연결하기

담당자가 거래 탐지 당시의 입력을 확인하고, 그 자료에 근거한 의견을 암호화해 남길 수 있도록 한다. 원본·정책 결과를 다시 검증하고 현재 운영 인증·열람 이력·필수 감사를 결합한다. 의견 기록은 조사 종결·의심거래보고·고객 제한 해제·금융 허가가 아니다.

## 문제와 기존 동작

116/117은 반복 소액 입금 규칙과 순차 분석 기록을 만들었지만 담당자가 분석 근거 전체를 확인하고 설명을 남기는 업무는 없었다. 과거 EDD의 AML 조사는 신원 후보 비교에 연결돼 있어 거래 패턴 의견을 그대로 넣으면 사건과 대상의 의미가 달라진다.

이번에는 실제 거래 탐지 평가에 열람과 담당자 의견을 연결한다. 현재 고객 자격이 부족하거나 제한돼 있어도 조사 자체를 막지 않는다. 과거 평가를 현재 상태처럼 바꾸지 않으며 NO_SIGNAL에 미해결 조사 의견을 생성하지 않는다. 미확인 INCOMPLETE와 검토 신호 REVIEW_REQUIRED는 근거를 확인하고 의견을 남길 수 있다.

## 기존 제품과 공식 자료의 접근

2026-10-25 공개 자료를 확인했다. [ComplyAdvantage의 거래 모니터링 사건 조사](https://support.complyadvantage.com/articles/3606136172-understanding-and-investigating-a-transaction-monitoring-case)는 거래·탐지 조건을 확인하고 의견을 남기는 단계와 사건 전체의 결정을 구분한다. 공개 문서의 갱신일은2026-08-21이다. [사건 조회 API](https://docs.mesh.complyadvantage.com/reference/casesservicev2casesget)는 거래 모니터링 등 업무별 열람 권한과 페이지 조회를 설명한다. 업체의 비공개 내부 저장 구조를 추정하지 않는다.

[Elastic 탐지 경보 관리](https://www.elastic.co/docs/solutions/security/detect-and-alert/manage-detection-alerts)도 경보 근거 확인·사건 연결·진행 상태를 나눈다. 이는 보안 경보 제품의 운영 참고이며 국내 금융 규제 근거가 아니다. 실제 금융 탐지의 배경은 [116 FATF/KoFIU 근거](116-durable-native-deposit-monitoring.md)를 따른다.

채택한 것은 원본 확인→의견 보존→별도 결론이라는 검증된 업무 구분이다. 사건 묶음·종결/보고 결정을 이번 의견 API가 이미 구현했다고 표시하지 않는다. 전체 제품을 그대로 복제하거나 공급사 SDK를 도입하지 않았다.

기존 [115 현재 접근 정책/필수 감사](115-authorized-private-transfer-inspection.md), [AmlInvestigationService](../../src/main/java/dustin/cex/domains/edd/AmlInvestigationService.java)의 암호화 의견 패턴, 설치된 EvidenceCrypto/CddCrypto·Jackson·JDBC를 재사용한다. 새 암호화 패키지·키 설정·별도 인증 체계를 만들지 않았다. 개인정보 안전성 확보조치의 적용 버전·시행 조건·접속기록 보관/점검의 외부 미완료 범위는115에 연결한다. 의견 표와 접근 표만으로 인증·신고·운영 준수 완료를 주장하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 최소 이력과 원본/메모 열람 분리 | 감사자에게 메모 원문·전체 거래 입력을 노출하지 않는다 | 담당자는 원본·메모에 별도 명시적 열람 요청을 수행한다 |
| 현재 역할·5분 추가 인증·대상 고객 잠금 | 회수된 권한/세션·다른 고객의 분석/의견 차용 차단 | 플랫폼 사용자 행의 짧은 경합. Rust 엔진의 락 제거와 다른 영역이다 |
| 저장 지문·실제 불변 금융 입력·정책 결과 재검증 | 지문만 다시 계산한 위조 문서·바뀐 기준 시각·잘못된 분류도 거부 | 상한1001개 창을 다시 읽고 비교한다. 미지원/손상 자료는503으로 닫는다 |
| 직접 열람한 자료에만 담당자 의견 | 다른 담당자의 열람·다른 운영 버전으로 조사 근거를 대체하지 않는다 | 새 의견에는 본인 열람이 필요하다. 과거 평가에 대한 의견은 과거 자료임을 유지한다 |
| 기존 독립 증빙 키 AES-GCM·고객 확인 HMAC | 메모 기밀성과 대상/담당자/분류 결합, 공개 해시의 메모 추측 위험 감소 | 해당 키 보존/회전·접근 통제 필요. 현재 지원 키가 아니면 읽기/재전송을 거부하며 추측 폴백하지 않는다 |
| 불변 의견·접근 증적·지연 필수 감사 | 후속 수정·삭제·TRUNCATE/감사 호출 누락으로 경로를 우회하지 못한다 | 추가 저장·커밋 시 검증 비용, 감사 장애 시 업무도 실패한다 |

5분 인증은 자체 정책이다. 키 보존/법적 보류·암호화 저장소·외부 접속 통제와 운영 점검은 별도 조건이다. 단일 규칙의 범위를 전체 AML 위험 평가로 확대하지 않는다. EXPLAINED_ACTIVITY도 담당자의 설명 가능성 의견이며 오탐 종결이나 거래 승인 자체가 아니다.

## API와 실제 데이터 범위

공통 경로는 `/api/admin/deposits/BTC/{receipt}`다. 실제 입금의 소유자로 대상 계정을 찾는다. 분석 목록은 그 고객 계정의 이력이며 해당 입금1건의 분석만 반환하는 경로가 아니다. 다른 고객 입금으로 분석 ID·의견 ID·재전송 키를 빌릴 수 없다.

| 메서드·하위 경로 | 역할 | 의미 |
| --- | --- | --- |
| GET `/monitoring-assessments?before={cursor}` | OPERATOR/APPROVER/AUDITOR | 원장 순번 기준 최근25개+다음 위치. 원본 문서/거래 입력/메모 없음 |
| POST `/monitoring-assessments/{assessment}/inspect` | OPERATOR/APPROVER | 검증된 당시 입력 최대1001개와 새 불변 열람 ID |
| POST `/monitoring-assessments/{assessment}/observations` | OPERATOR | 본인 viewId·requestKey·정해진 의견 분류·메모로 불변 의견 생성 |
| GET `/monitoring-assessments/{assessment}/observations?page={n}` | OPERATOR/APPROVER/AUDITOR |25개 최소 의견 이력, 메모·암호문·키/HMAC 미반환 |
| POST `/monitoring-assessments/{assessment}/observations/{observation}/inspect` | OPERATOR/APPROVER | 인증 복호화한 메모와 별도 접근 증적 |

목록 순번·입력 순번/가격 식별자는 문자열로, 원화 참고값은 정확한 십진 문자열로 반환한다. 미평가 금액은 null이며0으로 대체하지 않는다. 각 입력은 실제 입금 ID·자산/네트워크·거래 ID·인식 시각·원화 평가 상태에 연결된다. 원래 BTC 수량/온체인 상세는 해당 입금 ID의 기존 [094 보호된 원본 조회](094-operator-bitcoin-deposit-review-source.md)에서 확인한다. 위 창은 체인 송신 시각이 아니라 인식 시각 기준이다.

GET/POST 선택 자체가 권한을 보장하는 것은 아니다. 정확한 메서드/경로별 필터·서비스의 현재 인증·대상 결합·no-store·필수 감사가 경계다. 임의 메서드/하위 경로는 허용하지 않는다. 새 화면·봇·외부 공급사 호출은 추가하지 않았다.

## 구현과 관련 코드·문법

출처: [DepositMonitoringInputs.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringInputs.java#L15), 현재 소스의 부분 발췌.

```java
        var rows=jdbc.queryForList("""
            SELECT * FROM deposit_monitoring_inputs WHERE user_id=? AND input_cursor<=? AND recognized_at>=?
             ORDER BY input_cursor DESC LIMIT 1001
            """,owner,cursor,Timestamp.from(reference.minusSeconds(86400)));
        var inputs=new ArrayList<SmallDepositMonitoringPolicy.Input>();
```

117의 계산과 이번 조사가 같은 읽기 함수를 사용한다. 고객·원장 상한·시간 창·최대1001개를 실제 SQL로 제한한다. 외부 네트워크를 기다리거나 당시 가격을 다시 만들지 않는다. List.copyOf는 반환 목록의 구조 변경을 막는다. 입력 record 안의 값도 문자열·불변 금액/시각으로 구성한다.

출처: [DepositMonitoringInvestigations.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringInvestigations.java#L113), 현재 소스의 부분 발췌.

```java
            var original=DepositMonitoringInputs.read(jdbc,owner,Long.parseLong(meta.cursor()),meta.referenceAt());
            var result=SmallDepositMonitoringPolicy.assess(meta.referenceAt(),stored);
            // 저장 문서의 지문만 맞아도 기준 시각·순번이 실제 마지막 원장 입력과 다르면 거부한다.
            if(original.isEmpty()||original.getLast().cursor()!=Long.parseLong(meta.cursor())||!original.getLast().recognizedAt().equals(meta.referenceAt()))throw new IllegalStateException();
            if(!meta.id().equals(id)||!SmallDepositMonitoringPolicy.POLICY.equals(meta.policy())||!hash.equals(meta.sourceHash())||!stored.equals(original)||stored.size()!=((Number)row.get("source_count")).intValue()
                ||!result.outcome().equals(meta.outcome())||!result.reason().equals(meta.reason())||result.smallTransfers()!=meta.smallTransfers()||result.unknownTransfers()!=meta.unknownTransfers()||result.smallTotalKrw().compareTo(new BigDecimal(meta.smallTotalKrw()))!=0)throw new IllegalStateException();
            return new Verified(meta,stored);
```

저장 JSON을 Input[]의 실제 타입으로 읽어 BigDecimal·Instant를 보존하고 실제 불변 원본과 비교한다. 기준 순번/시각·정책·원본 지문·입력 수·재계산한 결과가 모두 일치해야 열람한다. TypeScript에서 JSON.parse 뒤 타입 단언만 붙이는 것과 달리 여기서는 타입 복원에 더해 실제 업무 검증을 실행한다. Java의 var는 컴파일 시 지역 변수 타입을 추론하며 동적 타입으로 바꾸지 않는다. record의 접근자는 필드명() 형태다. Java21의 List.getLast()는 마지막 원소를 읽고, 앞의 isEmpty() 단락 평가가 빈 목록 접근을 막는다.

출처: [DepositMonitoringInvestigations.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringInvestigations.java#L80), 현재 소스의 부분 발췌.

```java
        // 재전송도 현재 요청 경로의 실제 고객·분석 원본에 속해야 한다. 키 일치만으로 다른 고객의 의견을 빌려주지 않는다.
        var checked=verified(allowed.customer().getId(),assessment);
        if("NO_SIGNAL".equals(checked.metadata().outcome()))throw denied(HttpStatus.CONFLICT);
        var old=jdbc.queryForList("SELECT * FROM deposit_monitoring_observations WHERE actor_id=? AND request_key=?",actor,request.requestKey());
        if(!old.isEmpty()){
```

동일 requestKey 재전송도 현재 역할·실제 고객·분석 원본을 먼저 확인한다. UUID가 같다는 이유로 다른 고객 경로의 기존 의견을 반환하지 않는다. 동일 담당자/키의 동시 실행은 기존 사용자 잠금과 DB 고유 제약으로 의견 하나에 수렴한다.

출처: [DepositMonitoringInvestigations.java](../../src/main/java/dustin/cex/domains/monitoring/DepositMonitoringInvestigations.java#L128), 현재 소스의 부분 발췌.

```java
        String text=new String(crypto.decrypt((String)r.get("evidence_key_id"),context,(byte[])r.get("notes_cipher")),StandardCharsets.UTF_8);
        if(!cdd.fingerprint("monitoring-observation-notes",text).equals(r.get("notes_mac")))throw new IllegalStateException();return text;
```

AES-GCM 인증 부가 데이터에는 의견 ID·분석·열람·작성자/운영 버전·세션·분류·원본 지문·메모 HMAC/키 식별자를 묶는다. 메모를 복호화한 뒤 HMAC도 재검증한다. 다른 행/분류로 암호문을 옮기거나 미지원 키를 쓰면503으로 거부한다. 메모 원문은 감사 문자열·toString에 넣지 않는다.

출처: [investigation-schema.sql](../../src/main/resources/monitoring/investigation-schema.sql#L71), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER monitoring_observation_audit_check AFTER INSERT ON deposit_monitoring_observations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_investigation_audit_complete('MONITORING_OBSERVATION_RECORDED','monitoring-observation:');
DROP TRIGGER IF EXISTS monitoring_observation_view_audit_check ON deposit_monitoring_observation_views;
```

DEFERRABLE INITIALLY DEFERRED 제약은 커밋 시 의견/메모 열람과 같은 운영자·인물·대상·접속지의 성공 감사를 요구한다. 감사 호출이 예외를 내는 경우뿐 아니라 조용히 누락된 경우도 커밋하지 못한다. 확정 의견/접근 표의 UPDATE·DELETE·TRUNCATE도 막는다.

메모는 최대2000문자이고 빈 내용·허용하지 않은 제어/방향 문자 등을 거부한다. NFC 정규화와 양끝 공백 제거 뒤 암호화한다. 같은 요청 키의 메모·자료·열람·분류가 달라지면409다. 현재 역할과 요청 대상은 재전송에서도 다시 확인하며 원래 확정 의견을 새 작성자/버전으로 덮지 않는다.

## 작성한 테스트와 기대 결과

[DepositMonitoringInvestigationIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/DepositMonitoringInvestigationIntegrationTest.java)29개를 추가했다. 각 함수는 새 PostgreSQL DB에서 실제 운영 소프트웨어 보안키·세션·원장·탐지·JDBC·암호화·감사를 실행한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| operatorAndApproverInspectExactInputsAndRecordAccessThroughHttp | 실제 필터/컨트롤러에서 두 역할 원본 열람·정확한 문자열 순번·미평가 null·no-store·실제 접속지 증적 |
| auditorListsMetadataButCannotInspectOrWriteNotes | 감사자는 목록만200, 원본·의견 생성403 |
| customerAndAnonymousCannotProbeExistingOrMissingTargets | 고객/익명은 존재/부재 모두 거부 |
| staleMfaAndForeignSessionAreRejectedBeforeSourceRead | 오래된 인증/타인 세션을 원본 조회 전에 거부 |
| revokedOperatorCannotReadStoredEvidence | 현재 운영 계정 비활성 시 과거 자료 접근 거부 |
| foreignCustomerReceiptCannotBorrowAssessment | 다른 고객의 입금 경로로 분석 차용404 |
| sourceHashAndRecomputedPolicyMustAgree | 잘못된 지문·미확인 횟수·변경 기준 시각503 |
| forgedDocumentWithNewHashStillMustMatchRealFinancialSource | 위조 거래 ID의 지문을 다시 계산해도 실제 금융 원본과 달라503 |
| recordAndReadEncryptedNotesWithoutChangingRiskOrMoney | HTTP 의견 생성/승인자 열람·암호화/감사 원문 비노출·no-store·비금융 보증 |
| sameRequestKeyIsIdempotentAndChangedContentConflicts | 같은 요청은 같은 의견, 내용 변경409 |
| concurrentSameRequestCreatesOnlyOneObservation | 실제 동시 요청이 불변 의견1개로 수렴 |
| observationRequiresOwnSourceViewAndOperatorRole | 타인 열람 차용409, 승인자 작성은 서비스와 HTTP 모두403 |
| malformedNotesAndMissingFieldsCannotWrite | 빈/과대/제어·방향 문자·필수 필드 누락400 |
| auditorGetsObservationMetadataWithoutPlaintext | 감사자는 의견 이력만, 메모 접근403 |
| ciphertextTamperingAndUnsupportedKeysAreRejected | 태그 손상·분류 교체·미지원 증빙/지문 키503, 접근 증적 없음 |
| auditFailureRollsBackSourceInspection | 감사 예외 시 열람 원자적 롤백·원래 분석 보존 |
| missingAuditCannotCommitSourceInspection | 감사 호출 누락도 구체 지연 제약 오류로 커밋 차단 |
| auditFailureRollsBackObservationButKeepsItsView | 의견 감사 실패 시 의견 롤백, 선행 열람은 보존 |
| missingAuditCannotCommitObservationOrNoteAccess | 의견/메모 접근 각각 감사 누락 시 구체 제약 오류·증적 롤백 |
| historiesCannotBeUpdatedDeletedOrTruncated | 세 이력 표의 변경·삭제·비우기 거부 |
| metadataUsesBoundedKeysetPaginationWithoutDuplicates |27개 분석을25+2로 중복 없이 탐색, 잘못된 위치400 |
| unsupportedMethodsAndExtraPathsRemainDenied | GET/PUT 열람과 임의 하위 경로403·증적 없음 |
| surroundingTransactionIsRejectedWithoutPartialAccess | 외부 트랜잭션 거부·부분 기록 없음 |
| noSignalInputIsInspectableButCannotBeMadeAnUnresolvedInvestigation | 실제 평가된 무신호 입력은 정확한 원화 참고값으로 열람, 의견 생성409 |
| newerInputDoesNotRelabelHistoricalEvidenceOrOpinion | 새 입금/분석 뒤에도 옛 입력/의견의 대상과 원문 유지 |
| changedOperatorVersionCannotReuseEarlierSourceView | 운영 버전 변경 뒤 이전 열람으로 새 의견409 |
| idempotentKeyCannotBorrowObservationThroughAnotherCustomerReceipt | 같은 요청 키도 다른 고객 입금 경로에서는404·원본 의견 보존 |
| missingEvidenceKeyCannotWriteAnUnprotectedNote | 키 미설정503·평문 의견 생성 없음·선행 열람 보존 |
| databaseRejectsForgedActorAndBorrowedSourceView | 직접 SQL의 잘못된 운영 인물·타인 열람 관계 삽입을 구체 제약 오류로 거부 |

손상 시험은 조회한 행의 복사본에만 오류를 주입한다. 금융 원본이나 불변 이력을 수정하거나 트리거를 끄지 않았다. 모든 함수 후처리에서 BTC 원장 합계0·사용 가능 잔액0·거래 제한 변경 없음과 엔진/Kafka/노드/가격 호출 없음도 확인한다. 가격 성공이 필요한 단일 시나리오만 고정 공급사 대역을 명시적으로 사용하고 호출 검증을 분리한다.

## 실행 명령과 결과

| 최종 실행 | 결과 |
| --- | --- |
| `python3 scripts/verify_deposit_monitoring_investigations.py` | 신규29개 통과, 함수별29 DB |
| `python3 scripts/verify_deposit_monitoring.py` | 기존16개 통과, 함수별16 DB·1001개 실제 원장/순번 경계 포함 |
| `python3 scripts/verify_codevasp_transfer_inspections.py` | 기존58개 통과 |
| `python3 scripts/verify_engine_inbox.py` | 기존 금융182개 통과 |

네 실행기를 순서대로 실행해 Gradle 출력/빌드 폴더가 경합하지 않도록 했다. 각 실행은 소유 PG 서버로 격리하고 외부 공급사/메일/가격 환경을 제거해 봇0·자동 소비 금지·격리 주소를 적용했다. 최종 **고유285개/285회**, 실패·오류·제외0. 중간23/28개 실행은 최종에 재합산하지 않는다. `.local/cycle118-proof`에 최종 XML/로그·실행기 JSON을 보존한다. 실제 Core31.1 전체 회귀는116의223개가 마지막 실행이며 이번 수정 뒤 다시 실행했다고 표시하지 않는다.

고객/운영자 JAR 빌드와 새 조사 SQL/클래스 포함·시험 제외, Python 구문·코드 발췌·로컬 링크·`git diff --check`를 확인했다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다. MockMvc는 실제 Spring 필터/컨트롤러를 실행하지만 외부 TCP/공급사/메인넷 검증은 아니다. 실사·외부 신고/심사·운영 보존/점검 완료로 표시하지 않는다.

## 남은 범위

이제 탐지 원본과 담당자 의견을 추적할 수 있지만, 여러 신호의 사건 묶음·담당/우선순위·독립 종결/보고 판단과 실제 후속 업무는 별도다. INSUFFICIENT_INFORMATION·REQUIRES_FURTHER_REVIEW를 기록했다고 부족한 근거가 해결되거나 외부 보고가 접수된 것이 아니다. 고객의 현재 위험·자격·출처와 금융 판단을 결합하고 사용 가능 잔액/엔진으로 반입하는 입금 완성도 남는다.

현재 지원 키 밖의 과거 메모는 추측해 복호화하지 않는다. 키 보존/회전·법적 보류·기간별 보관/파기·실제 접속기록 점검은 전역 보안/운영 과제다. 자체 저널/엔진 단일 소유 코어·출력 워커에는 변경이 없다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 e487991](https://github.com/GiHoon1123/Cex-Platform-Service/commit/e4879914ef252a42dc0a71add0856842f7b83d14)
- [격리 실행 증적](../reference/118-verified-monitoring-evidence-and-analyst-observations-verification-2026-10-25.json)
