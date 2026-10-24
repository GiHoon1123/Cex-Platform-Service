# 서명된 이메일 변경 요청과 실제 완료를 구분해 조회

고객이 본인의 서명된 이메일 변경 요청과 실제 완료를 구분해 읽는다. 완료 원본이 없으면 취소/실패/현재 실행 가능 여부를 추측하지 않는다. 이력 입력 시각이 JDBC에서 잘못 변환되던 기존 경계도 함께 검증·수정한다.

## 문제와 기존 동작

071은 요청/완료를 각각 불변으로 저장하지만 고객용 조회가 없었다. 메일 예약/수신을 실제 변경 완료로 표시하면 안 된다. 한 요청에 선택적인 실제 완료를 연결해 처음의 요청을 중복 표시하지 않는다.

추가 검토에서 기존 비밀번호·키·복구 이력은 미래/누락 커서만 거부했다. 로컬 Temurin21에서 Timestamp.from(Instant.MIN).toInstant()는 원래 음수10억년을 양수169108098년으로 바꾸었다. 수정 전 새 회귀는400 대신 DB DataIntegrityViolationException으로 실패했다. 라이브러리가 반드시 입력 범위 오류를 대신 처리한다고 가정하지 않는다.

## 공식 자료와 기존 패턴

[071의 제품/공식 절차 비교](071-customer-email-change.md), [070 복구 이력](070-customer-recovery-history.md), [PostgreSQL16 행 비교](https://www.postgresql.org/docs/16/functions-comparisons.html#ROW-WISE-COMPARISON)를 따른다. 기존 JdbcTemplate·25건 복합 커서·26번째 행 확인을 사용한다. [JDK21 Timestamp](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Timestamp.html#from(java.time.Instant))의 JDBC 시간 타입과 실제 런타임 변환을 함께 확인했다.

## 선택과 트레이드오프

- 요청 원본을 기준으로 LEFT JOIN한 완료를 한 항목에 둔다. 화면이 요청과 완료를 따로 합칠 필요가 줄지만 정렬은 요청 시각 기준이다.
- 주소·서명·토큰·지문·암호문을 읽지 않는다. 최소 이력은 발송 키 장애에도 제공할 수 있다. 실제 이전/새 주소 문자열을 이 API에서 다시 복원하지 않는 제약이 있다.
- `completed=null`은 실제 완료 원본이 없다는 뜻이다. 권한/기한/서명 키를 모두 재평가해 실행 가능 상태를 만드는 대안보다 읽기가 단순하고, 확인 API가 현재 자격을 별도로 판정해야 한다.
- 커서는 이 서비스 보안 이력의 자체 정책으로 UTC1970년부터 현재까지 받는다. Timestamp 변환 전에 범위를 제한한다. Timestamp/DB가 모든1970년 이전 값을 지원하지 않는다는 주장이 아니다. 원본 기록/저장 시각은 변경하지 않는다.
- 페이지당25건과 복합 커서를 유지한다. 한 행을 더 읽는 비용으로 정확히25건일 때 빈 다음 요청을 줄인다. 여러 페이지를 고정한 장기 DB 스냅샷은 아니므로 완료 원본은 새 조회 시점에 보일 수 있다.

## API와 응답

`GET /api/customer/security/email-change-history`는 현재 본인 고객 세션으로만 읽는다. 별도의5분 추가 인증은 요구하지 않으며 등록 고객의 대기 세션·운영자·타인은 거부한다.

| 필드 | 의미 |
| --- | --- |
| items[].id | 불변 변경 요청 ID |
| requestedAt | 서명 요청 원본 확정 시각 |
| expiresAt | 해당 새 주소 확인 토큰의 원래 기한 |
| completed.id / changedAt | 실제 변경 원본이 있을 때의 ID/시각 |
| next.requestedAt / id | 다음 페이지에 그대로 전달할 복합 커서 |

`afterAt`/`afterId` 두 값을 함께 보내며 범위 밖/불완전 커서는400이다. 조회는 주소/세션/확인 원본을 변경하지 않고 필수 감사가 실패하면 성공 응답을 반환하지 않는다. 응답은 no-store다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerEmailChangeHistory.java](../../src/main/java/dustin/cex/domains/security/CustomerEmailChangeHistory.java#L34), 현재 소스의 부분 발췌.

```java
            String sql="""
                SELECT r.id,r.created_at,c.expires_at,e.id change_id,e.changed_at
                  FROM customer_email_change_requests r
                  JOIN customer_email_challenges c ON c.id=r.id AND c.user_id=r.user_id
                  LEFT JOIN customer_email_changes e ON e.request_id=r.id AND e.user_id=r.user_id
                 WHERE r.user_id=?
                """;
            String order=" ORDER BY r.created_at DESC,r.id DESC LIMIT 26";
            var rows=after==null?jdbc.queryForList(sql+order,actor):jdbc.queryForList(sql+" AND (r.created_at,r.id)<(?,?)"+order,actor,Timestamp.from(after.requestedAt()),after.id());
```

INNER JOIN으로 같은 고객의 토큰 원본을, LEFT JOIN으로 선택적인 실제 완료를 연결한다. 새 주소 메일이 전달되었어도 실제 변경 원본이 없으면 completed는 없다. 출처의 고객 조건을 각 연결에도 적용한다.

출처: [CustomerEmailChangeHistory.java](../../src/main/java/dustin/cex/domains/security/CustomerEmailChangeHistory.java#L43), 현재 소스의 부분 발췌.

```java
            var items=rows.stream().limit(25).map(row->{
                Completion completed=row.get("change_id")==null?null:new Completion((UUID)row.get("change_id"),((Timestamp)row.get("changed_at")).toInstant());
                return new Entry((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant(),((Timestamp)row.get("expires_at")).toInstant(),completed);
            }).toList();
            var last=items.isEmpty()?null:items.getLast();
            audit.record(actor,"CUSTOMER_EMAIL_CHANGE_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_REQUEST_AND_COMPLETION_FACTS");
            return new Page(items,rows.size()>25?new Cursor(last.requestedAt(),last.id()):null);
```

nullable 완료는 SQL의 실제 행 존재로만 만든다. Page 생성자는 List.copyOf로 응답 목록의 수정을 막으며 DB 원본 불변성은 기존 외래키/트리거가 보장한다. TypeScript의 선택적 속성과 비슷하지만 Java record의 값과 DB 원본을 구분한다.

출처: [CustomerPasswordHistory.java](../../src/main/java/dustin/cex/domains/account/CustomerPasswordHistory.java#L22), 현재 소스의 부분 발췌.

```java
        // 보안 이력 커서는 UTC의1970년부터 현재까지다. JDBC 변환의 범위 초과를 먼저 막는다.
        sessions.require(actor,sid);if(after!=null&&(after.id()==null||after.changedAt()==null||after.changedAt().isBefore(Instant.EPOCH)||after.changedAt().isAfter(Instant.now())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 위치를 확인하세요");
```

날짜를 JDBC 타입으로 바꾸기 전에 서비스 범위와 필수 ID를 확인한다. 클라이언트의 큰/작은 시간을 DB 장애로 분류하지 않는다. 같은 조건을 키/복구/새 이메일 이력에도 적용했다.

## 실행 명령과 실제 결과

```sh
# 수정 전 실패를 재현한 선택
python3 scripts/verify_customer_account.py --group security-cursors
# 수정 후 현재 코드
python3 scripts/verify_customer_account.py --group email-change --group email-change-history --group security-cursors
./gradlew operatorAdminJar bootJar --console=plain
```

JDK21·실행 소유 PostgreSQL16·GreenMail 로컬 SMTPS에서 기존 이메일 변경26개, 신규 이력11개, 공통 커서1개로 **고유38개 통과**, 실패/오류/제외0이다. 커서1개는 네 이력 서비스에 최소 시각/1970년 직전/미래 거부 및1970년·커서 없음의 정상 읽기를 확인한다. 신규 테스트 함수는12개다. 두 제품 JAR 빌드도 통과했다.

- 수정 전 커서 회귀1개는 의도한 오류 재현으로 실패했다. 실제 JDK 변환 프로브 출력과 별도 보존했다.
- 첫 이력11개 중1개는 보존 기한 안의 암호문을 삭제할 수 있다는 잘못된 fixture 가정 때문에 실패했다. 기존 DB 보존 통제가 삭제를 거부했다. 트리거를 끄지 않고 삭제 거부를 확인한 뒤 발송 키 장애만 주입하도록 수정했다.
- 수정 후38개 전체를 재실행했다. 앞선26개/실패한 이력 시도의 성공 개수를 다시 더하지 않는다.

로그는 공통 `.local/cycle72-proof/`, 기존 오류 재현은 `.local/cycle72-cursor-red/`, fixture 실패는 `.local/cycle72-initial/`이다. 071의 계정167/메일95/공통 보안41은 별도 시점이며 이번 결과로 재사용하지 않는다. 신규 의존성/버전·OSV·성능 검증·외부 전송/배포는 없다. 기존 deprecated/unchecked/JVM 경고가 남는다. 봇0명이고 기존 DB/브로커/서비스를 사용하지 않았다.

## 한계와 다음 연결

완료 원본 부재를 결과 실패로 확정하지 않는다. UI·외부 SMTP 최종 배달/열람·보존/키 회전·실물 패스키·운영 검증은 별개다. 비밀번호 분실/모든 복구 자격 분실과 수탁·입출금 등 남은 백엔드 범위는 공통 실행 대장을 따른다.

## 작성한 테스트 코드


[CustomerEmailChangeHistoryIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerEmailChangeHistoryIntegrationTest.java)

- `emptyHistoryCreatesNoRequestOrCompletion`: 미설정 고객은 빈 목록/다음 없음을 읽고 요청/완료 원본을 만들지 않는다.
- `signedRequestIsNotReportedAsCompleted`: 실제 서명 요청은 원래 기한을 반환하지만 완료가 없으면 completed는 비어 있다.
- `actualCompletionIsLinkedAfterNewLoginAndOldSessionCannotRead`: 변경 뒤 옛 JWT는401이고 새 로그인은 같은 요청에 실제 완료 ID/시각을 읽는다.
- `sameTimestampRequestsHaveNoPaginationLoss`: 동일 시각29개를 포함한30개를25+5로 중복/누락 없이 순회한다.
- `exactPageDoesNotProduceEmptyNextPage`: 정확히25개면 next가 없다.
- `partialAndFutureCursorAreRejectedAndAudited`: 누락/미래 커서와 HTTP 단일 필드는400이고 거부 감사가 남는다.
- `foreignOperatorAndPendingSessionsCannotRead`: 타인·운영자·비밀번호 대기 세션을 서비스와 실제 HTTP에서 거부한다.
- `auditFailureDoesNotReturnHistory`: 필수 감사 실패 때 이력을 성공 응답으로 반환하지 않는다.
- `httpExposesOnlyMetadataWithoutFreshSigningPrompt`: 6분 경과한 유효 강한 세션으로 읽고 no-store/최소 필드/비밀 비노출을 확인한다.
- `mailKeyFailureDoesNotHideSignedRequestFacts`: 보존 기한 암호문의 삭제 거부를 유지하고 발송 키503을 주입해도 최소 요청 이력은 읽는다.
- `anotherCustomersActualRequestIsNeverIncluded`: 별도 고객의 실제 등록/서명 요청이 있어도 본인 요청만 반환한다.

## 관련 커밋과 증적

- [구현 0b6a98e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0b6a98ee9178ea84e0ea8f270c72f9893fc84ff1)
- [격리 실행 증적](../reference/072-customer-email-change-history-verification-2026-10-25.json)

[CustomerSecurityCursorIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerSecurityCursorIntegrationTest.java)의 `cursorBoundsAreCheckedBeforeJdbcConversion`은 비밀번호/키/복구/이메일 이력 네 서비스의 범위 밖400과 정상 빈 조회를 확인한다. 금융/감사 원본을 지우는 방식으로 날짜 문제를 해결하지 않는다.

선행 커서 수정: [d107c96](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d107c964c38fc6f8cd3f4317fb9200c294b7dd04). 기존 세 API의 수정/회귀를 먼저 담고 새 이메일 이력에 같은 검증을 추가하는 커밋으로 나눴다.
