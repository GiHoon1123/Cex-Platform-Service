# 정보 교환의 시작과 암호화 응답을 보존하고 불명 재전송을 차단하기

암호화 제공 의도에서 실제 정보 교환의 시작·현재 근거·응답까지 영속 연결한다. 외부 대기 동안 DB 잠금을 해제하고, 미전송 증명 이후에만 재시도한다. 성공 응답 수신과 금융 승인·사용 가능 잔액은 분리한다.

후속 [113 수신 자료 일관성 분류](113-received-transfer-detail-consistency.md)는 정상 수신과 요청 당시 고객 자료의 비교 결과를 분리한다. 현재 고객 적격성·동일인 확정·금융 해제는 별도다.

## 문제와 기존 동작

106까지 현재 고객/입금·출처에 결합된 제공 의도를 저장했지만 외부로 전송하지 않았다. 의도 ID만 읽고 전송하면 예약 뒤 고객 제한·신고/체인/검색·목록/키가 바뀌었는지 알 수 없다. 시작 기록 없이 전송하면 응답 유실/프로세스 종료 시 제공 사실을 추적하기 어렵고, 응답이 없다고 재전송하면 공급사가 이미 처리한 요청을 반복할 수 있다.

## 공식 자료와 기존 구현의 접근

[CodeVASP 정보 요청](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)과 [Cipher 계약](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/02-Create-Header-Payload-2-Addon)의 검색 ID/거래·수취 주소 결합은104 클라이언트를 그대로 사용한다. 정상/오류 응답의 실제 공급사 동작과 PUT의 멱등 보장은 공개 계약 확인만으로 추측하지 않는다.

2026-10-25 다시 확인한 [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 커밋된 전달 의도·외부 전송 분리 원칙을 적용한다. 이 패턴이 원격 호출을 DB와 원자화하거나 자동으로 exactly-once를 만든다는 의미는 아니다. 기존100 검색 실행의 짧은 시작/완료 TX·필수 감사 패턴을 재사용하되, 정보 교환 PUT의 불명 상태를 검색 GET으로 해결할 수 있다고 추측하지 않는다.

기존 CddCrypto의 AES-GCM·사용자/행 AAD와 독립 HMAC 키를 사용한다. 새 암호/스케줄러/범용 외부 작업 프레임워크나 의존성은 추가하지 않았다. 실행 기록은 전용 표이며 아직 자동 워커/API를 연결하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|외부 호출 전에 시작/현재 근거/감사 커밋 | 제공 시도와 근거 추적·실패한 시작의 외부 전송 차단 | 짧은 DB 쓰기2회와 암호화 비용, DB/감사 장애 시 보류 |
|외부 대기 중 DB TX/사용자 잠금 해제 | 고객/DB 연결을 공급사 지연 동안 붙잡지 않음 | 전송 시작 뒤 고객 조건이 바뀔 수 있음. 결과는 역사적 수신 사실로 보관하고 금융 사용 전에 현재성을 다시 검증해야 함 |
|현재 원본 재포착·과거 의도의 의미 대조 | 낡은 예약을 현재 허가로 쓰지 않음·같은 상대의 유효한 키 회전 허용 | 현재 고객/체인/검색/디렉터리·정책이 확인되지 않으면 외부 제공 보류 |
|같은 입금 전체에서 실행 직렬화 | 접속 주체/검색 ID 변경으로 이전 불명을 우회할 수 없음 | 같은 입금의 미해결 제공은 운영 확인이 필요. 다른 접속 설정으로 자동 새 요청 불가 |
|미전송 증명+30초 이후에만 재시도 | 공급사에 도달한 요청을 추측해 반복하지 않음 | 시작만 남은 프로세스 종료도 재전송 차단. 제공 안 됐을 수도 있지만 공급사/운영 해결이 필요 |
|수신 원문을 암호화하고 제한 상태만 반환 | 송수신인 이름/생일/계정의 우발 노출 감소 | 암호문 접근/보존·키 수명/파기 운영 필요. 기존 CDD 키 체계를 재사용하므로 키 침해 범위/보존 관리가 결합됨 |
|DB 제약·수신 검사에서 불명/NULL 우회 차단 | 직접 SQL/잘못된 대역 결과도 성공으로 승격하지 않음 | DB 제약은 실제 공급사/법적·고객 위험 판정을 대신하지 않음 |

## 구현과 데이터 흐름

CodeVaspTransferIntents의 현재 원본 포착을 내부 capture로 추출해 예약과 실행에서 재사용한다. 실행에서는 현재 접속 주체·고객/체인/검색/상대가 허용되는지 먼저 확인하고 과거 개인정보 봉인을 연다. 과거 의도의 키가 이미 만료됐을 수 있으므로 현재 키 검사 생성자로 과거 요청을 역직렬화하지 않는다. 봉인의 HMAC/AAD를 검증하고 거래/수취 주소·금액·확인된 수취인 이름/생일/신원 지문과 같은 상대 ID가 현재 의미와 일치해야 한다.

같은 상대의 새 유효 키·새 체인 관찰/목록·재검토된 현재 허용 정책은 실행의 새 Basis로 보존한다. 원래 의도는 덮어쓰지 않는다. 키가 같다는 이유로 원본 최신성을 건너뛰지 않고, 상대 사업자가 달라졌다면 자동으로 전환하지 않는다. JSON 금액은 USE_BIG_DECIMAL_FOR_FLOATS로 읽고 실제 BigDecimal 값으로 비교한다. JSON 내부 노드 구현 클래스 차이나 float/double 반올림을 업무 동등성으로 쓰지 않는다.

CodeVaspTransferExecution.execute는 독립 시작 TX에서 같은 receipt 전체의 advisory lock을 획득한다. 현재 키/고객/체인/검색/정책 근거와 실제 요청을 암호화한 새 시도·현재 위치·필수 감사를 커밋한다. 이후 TX 밖에서104 클라이언트를 호출한다. 완료 TX는 반환된 DATA_RECEIVED/REMOTE_ERROR를 암호화하고 별도 HMAC·필수 감사와 함께 커밋한다. DATA_RECEIVED는 파싱한 응답 수신이며 동일인/자금 출처/금융 승인·입금 해제는 아니다. 송수신인의 업무상 대조와 현재 금융 조건은 후속 단계다.

외부 호출 중 고객 제한이 바뀌어도 이미 받은 역사적 응답은 보존한다. 이를 거래 가능 자산으로 바꾸지 않는다. 완료 저장/감사 실패는 시작만 남기고, 공급사 실패로 두 번째 결과를 쓰지 않는다. RUNNING은 시작 원본만 있다는 기술 상태이며 워커 생존/실제 전송 여부를 증명하지 않는다.

| 상태 | 다음 처리 |
| --- | --- |
|UNSTARTED | 해당 의도의 시작 기록 없음. 현재 원본 재검증 후 시작 |
|RUNNING | 시작만 확인. 자동 재전송 금지·미해결 보존 |
|DATA_RECEIVED | 정상 응답 원본 수신/암호화 저장. 금융 승인 아님·자동 재전송 금지 |
|REMOTE_ERROR | 문서 사유 오류 응답을 암호화 보존. 자금 거절/개인지갑 판정 아님·자동 재전송 금지 |
|NOT_SENT | 현재 시도가 게이트웨이에 도달하지 않았음을 확인.30초 뒤 현재 원본 재검증 후 같은 transferId로 새 시도 가능 |
|OUTCOME_UNKNOWN | 전송 후 오류/불량 결과/불명. 자동 재전송 금지 |

codevasp_transfer_attempts/heads/results를 추가했다. 시작/결과는 불변이며 현재 위치는 같은 TX의 새 시도로만 전진한다. 직접 SQL에도 같은 입금의 이전 시도/미전송 간격, 원래 의도/현재 원본 관계, 시작/결과 시각/키 결합을 검사한다. 결과의 사유·암호문 조합은 NULL을 명시적으로 배제한다. PostgreSQL CHECK가 NULL을 참으로 취급하지 않더라도 UNKNOWN이면 거부하지 않는다는 차이를 고려했다.

이 잠금·암호화/HTTP 대기는 플랫폼 입출력 경계다. Rust 체결 코어·공유 상태/락·WAL/엔진의 금융 상태에는 변경이 없다. 사용 가능 원장/잔액·엔진/Kafka 반영도 하지 않는다.

## 실제 코드와 이해에 필요한 문법

출처: [CodeVaspTransferExecution.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferExecution.java#L66), 현재 소스의 부분 발췌.

```java
        UUID receiptId=sources.getFirst();
        // 접속 주체/검색 ID를 바꿔도 같은 입금의 미해결 전송을 우회하지 못하도록 입금 전체를 직렬화한다.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-private-transfer-receipt-v1:"+receiptId);
        var prior=jdbc.queryForList("SELECT a.id,r.status,r.recorded_at FROM codevasp_transfer_attempts a LEFT JOIN codevasp_transfer_results r ON r.attempt_id=a.id WHERE a.receipt_id=? ORDER BY a.sequence DESC LIMIT 1",receiptId);
        if(!prior.isEmpty()){
```

시도 번호는 입금 전체의 순서를 정한다. 접속 주체/검색 ID가 달라도 이전 불명 시도를 숨길 수 없다. advisory lock은 이 플랫폼 업무의 짧은 TX 전용이며 엔진 공유 락이 아니다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L124), 현재 소스의 부분 발췌.

```java
        var original=json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(plain);var fresh=json.valueToTree(current.prepared());
        // 과거 키는 이미 만료됐을 수 있어 현재용 요청 생성자로 역직렬화하지 않는다. 의도의 거래·수취인 의미만 정확히 대조한다.
        // 같은 상대의 현재 허용 정책/회전된 유효 키·새 체인 관찰은 실행의 새 근거에 남긴다.
        for(String field:List.of("dateOfBirth","identityMac"))if(!original.path(field).equals(fresh.path(field)))throw held();
```

[112 목적 제한](112-private-transfer-purpose-boundary.md) 이후에는 이 다음에 봉인한 목적·가격 참조와 정확한 원화 평가액도 대조한다. 위 발췌는 현재 소스의 시작 부분이다. USE_BIG_DECIMAL_FOR_FLOATS는 JSON 소수를 이진 부동소수점으로 읽지 않도록 한다. BigDecimal.compareTo는 표현 자릿수와 관계없이 정확한 값의 동등성을 확인한다. 과거 요청의 만료된 키는 현재용 생성자에 다시 넣지 않고 현재 디렉터리에서 유효한 키를 선택한다.

출처: [CodeVaspTransferExecution.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferExecution.java#L54), 현재 소스의 부분 발췌.

```java
        String outcome=status,code=reason,plain=payload;var details=received;
        // 완료 저장/필수 감사 실패를 외부 실패로 다시 처리하지 않는다. 시작만 남으면 재전송하지 않고 미해결로 보존한다.
        return tx.execute(t->{
            jdbc.update("INSERT INTO codevasp_transfer_results(attempt_id,status,reason,key_id,ciphertext,content_mac) VALUES(?,?,?,?,?,?)",start.id(),outcome,code,plain==null?null:crypto.keyId(),plain==null?null:crypto.encrypt(start.owner(),start.id(),plain.getBytes(StandardCharsets.UTF_8)),plain==null?null:crypto.fingerprint("codevasp-transfer-result-v1",plain));
            if("DATA_RECEIVED".equals(outcome))assessments.record(start.id(),start.basis().prepared(),details);
            audit.record(null,"CODE_TRANSFER_FINISHED","codevasp-transfer:"+intentId,plain==null?"DENIED":"SUCCESS","ATTEMPT:"+start.id()+":STATUS:"+outcome+":REASON:"+code);
            return state(intentId);
```

후속113에서는 DATA_RECEIVED의 비교 분류도 같은 트랜잭션에 기록한다. 위 발췌는 현재 코드이며107 당시 검증과 구분한다. 자바 lambda가 바깥 값을 참조하도록 최종 지역 변수로 정리한다. 공급사 호출의 catch 블록 밖에서 결과/감사 TX를 수행해 DB 저장 실패를 원격 오류로 오인한 재저장을 막는다. 암호문에도 개인정보 보존/접근 통제가 필요하다.

출처: [schema.sql](../../src/main/resources/travelrule/schema.sql#L240), 현재 소스의 부분 발췌.

```sql
 CHECK((status='DATA_RECEIVED' AND reason IS NULL AND ciphertext IS NOT NULL)
  OR(status='REMOTE_ERROR' AND reason IS NOT NULL AND reason IN ('NOT_FOUND_TXID','LACK_OF_INFORMATION','UNKNOWN','UNKNOWN_TRANSFER_ID') AND ciphertext IS NOT NULL)
  OR(status IN ('NOT_SENT','OUTCOME_UNKNOWN') AND reason IS NOT NULL AND reason IN ('PROVIDER_UNAVAILABLE','INVALID_SOURCE') AND ciphertext IS NULL)),
 CHECK((ciphertext IS NULL AND key_id IS NULL AND content_mac IS NULL)
```

PostgreSQL CHECK는 FALSE만 거절하고 UNKNOWN은 허용할 수 있다. 따라서 오류 사유가 필요한 분기에 IS NOT NULL을 명시한다. TypeScript에서 null/undefined를 값 비교 전에 구분하는 것과 목적은 비슷하지만 SQL의3값 논리는 별도 규칙이다.

## 작성한 테스트

[CodeVaspTransferExecutionIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferExecutionIntegrationTest.java)19개를 추가했다. 외부 CodeVaspTransferDataClient만 대역이며, PG·현재 고객 확인/신고·원본/제공 의도·실제 암호화/행 잠금·감사는 제품 코드다.104/105의 HTTP 계약 시험과 역할이 다르며 공식 모듈/Sandbox 시험이 아니다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|committedStartAndFreshBasisPrecedeIoAndReplyIsSealed | 외부 호출 시 DB TX 없음·시작 커밋 확인·응답 암호문/지문·정상 수신 후 재전송 차단 |
|startAuditFailureRollsBackBeforeAnyDisclosure | 시작 감사 실패는 시작/위치 롤백·외부0 |
|finishAuditFailureKeepsUnresolvedStartAndNeverBlindlyResends | 원격 수신 후 완료 감사 실패는 RUNNING·다시 전송하지 않음 |
|unknownOutcomeCannotBecomeAutomaticRetry | 불명은 영속 상태·재전송0 |
|notSentMustWaitBeforeRetryAndCurrentSourcesAreRechecked | 미전송도 즉시 반복 불가·외부1 |
|changedCustomerCannotUseHistoricalIntentAsPermission | 예약 뒤 현재 고객 제한은 새 시작/전송 차단 |
|changedCurrentDirectoryFailsBeforeAnyDisclosure | 새 목록 실패가 옛 성공을 대신하지 못함 |
|financialRestrictionDuringIoCannotEraseHistoricalReply | 외부 대기 중 제한 변경에도 수신 사실은 보존·자금 반영 없음 |
|independentCallsCannotOverlapOneIntent | 한 실행이 외부에서 기다리는 동안 동시 실행409·전송1 |
|unboundReplyIsUnknownAndNotAReceivedFinancialFact | 다른 transferId 응답은 불명·개인정보 결과 암호문 없음 |
|ambientTransactionCannotStartExternalExchange | 호출자 TX 안에서 시작 거부 |
|finishedStartAndResultCannotBeMutatedOrHeadRewound | 시작/결과 DELETE/TRUNCATE 및 현재 위치 재사용 거부 |
|documentedRemoteErrorIsSealedButNeverAutomaticallyResubmitted | 공개 오류 사유/암호화 원본 보존·재전송 금지 |
|changedKeyAndNewCurrentObservationsAreExecutionBasisWithoutRewritingIntent | 같은 상대 새 키/관찰을 현재 실행에 사용·옛 의도 키 보존 |
|staleChainAfterReservationCannotUseHistoricalIntent | 예약 뒤 최신 낡은 체인 관찰은 차단 |
|sqlNullReasonCannotBypassOutcomeConstraints | 직접 SQL의 오류/불명 사유 NULL 거부 |
|retryAfterProvenNonSendCreatesNewAttemptWithSameTransferIdentity | 실제 DB 시각30초 경과 후 새 시도/같은 transferId·이전 미전송 이력 보존 |
|historicalIdentityIsNotOpenedBeforeCurrentCustomerEligibility | 현재 제한 고객은 과거 봉인 복호화0 |
|changedConnectionAuthorityCannotBypassUnknownTransferForSameReceipt | 다른 접속 주체/새 검색/의도를 만들어도 같은 입금의 불명 전송 우회 불가 |

기존13개 예약 시험과 새 실행 시험의 공통 fixture를 [CodeVaspTransferTestSupport](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferTestSupport.java)로 분리했다. 테스트 메서드는 두 구체 클래스에만 두어 상속으로 같은13개를 새 클래스에서 중복 실행하지 않는다. 같은 설정/컨텍스트를 공유하되 각 시험이 다른 고객/입금/검색을 만들고 금융·감사 이력을 삭제하지 않는다. 모든 시험 뒤 보류 자산0.12345678 유지·user_balances0·외부/엔진/Kafka의 의도하지 않은 호출 없음·의도 감사의 개인정보 비노출을 확인한다.

## 실행 명령과 결과

- 첫25개 실행은9개 실패했다. JSON 문자열에서 읽은 수치 노드와 valueToTree의 BigDecimal 노드를 객체 동등성으로 비교해 정상 예약까지 거부했다. 보안 검사를 빼지 않고 USE_BIG_DECIMAL_FOR_FLOATS와 정확한 값 비교로 수정해25개 통과했다.
- 이후30개에서1개는 시험 대역의 기존 thenThrow를 when으로 재설정하면서 시험 코드에서 예외가 발생했다. doReturn으로 바꿨다. 현재 조건 확인 전 과거 봉인 복호화 차단을 추가한31개가 통과했고, 접속 주체 변경 우회 회귀를 보강한 최종32개를 재실행했다. 실패/중간 기록은 `.local/cycle107-failed`/`.local/cycle107-intermediate`에 보존한다.
- Java21/macOS arm64/소유 PG16. `python3 scripts/verify_codevasp_transfer_intents.py`: 최종32개(새 실행19+기존 예약13) 통과, 실패/오류/제외0.30초 재시도는 실제 DB 시각으로 기다렸다. 이력 수정/트리거 해제/제품 간격 축소로 시간을 가장하지 않았다.
- `python3 scripts/verify_engine_inbox.py`: 금융182개 통과, 실패/오류/제외0. 상위 실행에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_를 제거하고 소비자 자동 시작 금지를 고정했다.
- 고유214개/최종 검증 집합214실행·중복0. 앞 회차241개·중간25/30/31개를 합산하지 않았다. 각 XML의 클래스/함수 집합 대조, 두 최종 소유 PG 제거 확인. `.local/cycle107-proof`에32 XML/로그/manifest와 finance182 XML/로그/manifest·중복 대조를 보존한다.
- bootJar/operatorAdminJar 성공·두 JAR에 제품 클래스/스키마 포함·시험 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 새 의존성/실제 공급사·개인키 배포 없음.
- `git diff --check`·현재 발췌/상대 링크 대조. 실제 BTC 노드·HTTP81·검색46/자동 예약12·운영 심사/보안 전체·Rust/Kafka/브라우저는 이번에 재실행하지 않았다.

## 남은 업무와 운영 조건

이 실행기는 내부 서비스이며 자동 워커/HTTP에 아직 연결하지 않았다. 준비된 요청을 실행하는 코드/PG 흐름을 검증한 것이며 사용자 입금이 모두 자동 완료되는 단계가 아니다. 수신 원문을 현재 고객/동일인·체인/위험 정책과 대조하는 업무, 미확인/제3자/개인지갑·원격 오류/불명 해결, 보호된 운영 조회/통제, 엔진 HTTP/WAL/Kafka·원장 반영과 재조직 복구는 남는다.

전체 호출 동안 조건을 원격 시스템과 원자적으로 고정할 수 없다. 시작 근거/시각과 역사적 응답을 보존하고 금융 승인/반영 직전에 현재 상태를 별도로 검사해야 한다. RUNNING/불명·원격 오류는 자동 반복하지 않으므로 실제 공급사의 재조회/멱등 계약과 운영 해결 절차 확인이 필요하다. 다른 접속 설정으로 반복하는 방식은 해결책이 아니다.

실제 CodeVASP 계약/개인정보 제공 근거·상대 실사/정책 승인·Cipher 키/망/버전·Sandbox/LIVE·장기 보존/파기·키 회전은 미확인 외부/운영 조건이다. 암호문 수신만으로 국내 신고/인증·제재/자금 출처 심사 완료를 주장하지 않는다. 디스크/호스트 손실 복제·백업은 기존 지시대로 문서 조건이며 이번에 배포하지 않았다.


## 관련 커밋과 증적

- [구현 491cbbd](https://github.com/GiHoon1123/Cex-Platform-Service/commit/491cbbdb0c1d6ab0e9b8a8567ffa9de5ad876430)
- [격리 실행 증적](../reference/107-durable-private-transfer-execution-verification-2026-10-25.json)

## 자동 일정 연결 시점

[108](108-automatic-private-transfer-processing.md)에서 검색/보류 원본에서 제공 의도·실행을 자동 연결하는 일정/전용 워커를 추가했다. 개인정보 자동 제공 설정은 기본 false이며 금액 환산/제공 목적·대상 결정과 금융 승인은 아직 미완료다.107 당시 내부 실행/검증은 원래 커밋과 증적이다.
