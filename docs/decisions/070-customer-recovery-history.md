# 고객 본인의 확정 복구 이력을 안전하게 조회

고객이 실제로 확정된 코드 교체·패스키 복구 사실을 본인 범위에서 조회한다. 원문 코드나 인증 자료를 재노출하지 않으면서 어떤 보안 변경이 있었는지 확인하도록 한다.

## 문제와 기존 동작

069는 복구와 알림 예약을 원자적으로 처리하지만 고객이 복구 사실을 직접 조회하는 API가 없었다. 메일 수신을 실제 복구 확정이나 고객 확인으로 간주하지 않는다. 불변 복구 원본과 개별 키 변경의 연결을 읽어 시각·종류·키 변경 개수만 제공한다.

## 기존 제품과 공식 자료의 접근

[069](069-customer-saved-code-recovery.md)에서 조사한 사전 코드·변경 통지 계약과 기존 [061 비밀번호 이력](061-customer-password-notice.md), [065 키 이력](065-customer-passkey-notices.md)의 본인 조회 패턴을 따른다. [PostgreSQL16 행 비교](https://www.postgresql.org/docs/16/functions-comparisons.html#ROW-WISE-COMPARISON)는 여러 필드를 왼쪽부터 비교하므로 시각이 같은 행을 UUID로 구분해 순회할 수 있다. 새 페이지 프레임워크나 고객별 DB 복사본을 만들지 않는다.

## 선택과 트레이드오프

- 본인 세션의 현재 자격을 서비스에서도 확인한다. 별도 업무 서명이나5분 재인증을 요구하지 않아 단순 보안 이력 조회의 고객 부담을 줄인다. 등록 고객의 비밀번호 대기 세션은 읽을 수 없다.
- 코드·해시 표를 조인하지 않고 불변 원본과 연결 개수만 읽는다. 자세한 키 지문은 기존 키 변경 이력에서 확인한다. 상세 정보를 한 응답에 합치는 대안보다 조회 범위가 작고 화면이 필요한 경우 API를 따로 호출해야 한다.
- 시각/UUID 복합 커서와 고정25건을 사용한다. 26번째 행을 미리 읽어 정확히25건일 때 빈 다음 페이지를 요청하지 않는다. 한 행을 더 읽는 비용을 감수한다. 대규모 성능 벤치는 하지 않았다.
- 기존 사용자 행 잠금과 필수 감사가 조회에도 참여한다. 같은 고객의 보안 변경과 직렬화되는 비용이 있지만 현재 권한 검사를 생략하지 않는다. Rust 거래 코어·저널 큐에는 접근하지 않는다.

## 구현과 API

`GET /api/auth/customer-recovery/changes`는 `items`와 선택적인 `next`를 반환한다. 항목은 `id`, `createdAt`, `kind`, `keysChanged`다. 종류는 CODE_REPLACED/MFA_RECOVERED이며 키 변경 수는 이전 키 폐기와 새 키 추가의 합이다. 코드 교체만 한 원본은0이다.

다음 페이지는 `afterAt`/`afterId` 두 값을 함께 전달한다. 둘 중 하나 누락·미래 시각은400이다. 고객·운영자·타인·대기·회수 세션의 권한은 현재 계정/세션으로 재검사하고 응답은 no-store다. 조회 성공 감사가 실패하면 성공 응답도 반환하지 않는다. 조회 사실은 복구 승인이나 고객 열람 확약이 아니다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerRecoveryHistory.java](../../src/main/java/dustin/cex/domains/security/CustomerRecoveryHistory.java#L33), 현재 소스의 부분 발췌.

```java
            String sql="""
                SELECT e.id,e.created_at,e.kind,
                  (SELECT count(*) FROM customer_recovery_key_changes k WHERE k.event_id=e.id AND k.user_id=e.user_id) key_count
                  FROM customer_recovery_events e WHERE e.user_id=?
                """;
            String order=" ORDER BY e.created_at DESC,e.id DESC LIMIT 26";
            var rows=after==null?jdbc.queryForList(sql+order,actor):jdbc.queryForList(sql+" AND (e.created_at,e.id)<(?,?)"+order,actor,Timestamp.from(after.createdAt()),after.id());
            // 한 행을 더 읽어 정확히25건일 때 빈 다음 페이지를 요청하지 않게 한다.
            var items=rows.stream().limit(25).map(row->new Change((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant(),(String)row.get("kind"),Math.toIntExact(((Number)row.get("key_count")).longValue()))).toList();
            var last=items.isEmpty()?null:items.getLast();
            audit.record(actor,"CUSTOMER_RECOVERY_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CONFIRMED_FACTS_ONLY");
            return new Page(items,rows.size()>25?new Cursor(last.createdAt(),last.id()):null);
```

작업 원본 e의 user_id를 먼저 제한하고, 상관 하위 조회도 동일 원본/고객만 센다. ORDER BY와 커서 조건의 필드 순서를 같게 유지한다. Stream.limit(25)는 응답에26번째 행을 노출하지 않는다. Math.toIntExact는 정수 변환의 손실을 조용히 허용하지 않는다.

출처: [CustomerRecoveryHistory.java](../../src/main/java/dustin/cex/domains/security/CustomerRecoveryHistory.java#L16), 현재 소스의 부분 발췌.

```java
    public record Cursor(Instant createdAt,UUID id) {}
    public record Change(UUID id,Instant createdAt,String kind,int keysChanged) {}
    public record Page(List<Change> items,Cursor next) {public Page {items=List.copyOf(items);}}
```

Java record는 고정 응답 모양을 정의한다. List.copyOf는 반환 목록 수정을 막지만 원본 데이터의 불변성은 기존 DB 트리거/외래키가 보장한다. TypeScript의 readonly 타입만으로 런타임 변경이 막히는 것으로 혼동하지 않는다.

출처: [schema.sql](../../src/main/resources/account/schema.sql#L95), 현재 소스의 부분 발췌.

```sql
-- 본인 이력의 복합 커서와 한 복구에 속한 변경 수 조회를 각각 제한된 범위로 읽는다.
CREATE INDEX IF NOT EXISTS customer_recovery_history ON customer_recovery_events(user_id,created_at,id);
CREATE INDEX IF NOT EXISTS customer_recovery_key_source ON customer_recovery_key_changes(event_id,user_id);
```

현재 조회 조건과 정렬에 맞춘 인덱스다. 실제 대규모 쿼리 비용이나 처리량 개선을 측정했다고 주장하지 않는다.

## 실행 명령과 결과

```sh
python3 scripts/verify_customer_account.py --group recovery --group recovery-history
python3 scripts/verify_customer_account.py --group recovery-history
./gradlew operatorAdminJar bootJar --console=plain
```

JDK21·실행 소유 PostgreSQL16·GreenMail 로컬 SMTPS에서 기존 복구23개와 신규 이력9개, **고유32개 통과**, 실패/오류/제외0이다. 첫32개 성공 뒤 타인 이력 fixture를 실제 별도 CUSTOMER 계정으로 명확히 하고 이력9개를 재실행했다. 반복한9개를 더하지 않는다. 기존 복구 등록·서명 fixture를 CustomerRecoveryTestSupport로 분리해 두 회귀가 공유하며 제품 WebAuthn 검증을 대체하지 않는다.

고객·운영자 JAR 빌드 성공. 신규 패키지/버전 변경은 없다. 기존 deprecated/unchecked/JVM 경고는 남는다. Kafka/엔진은 닫힌 루프백이며 봇0명, 기존 로컬 서비스/데이터를 사용하지 않았다. 이번에 메일95개나 엔진 회귀를 다시 실행한 것은 아니며069/068의 별도 시점 결과로 구분한다.

프론트·실물 패스키·대규모 성능·운영 배포/외부 심사·전체 복구 자격 분실은 이번 범위가 아니다. 정렬은 저장된 시각/UUID 기준이며 업무상 현재 유효 코드는069의 sequence 기준을 유지한다. 시스템 시각 동기화와 운영 검증은 별도다.

## 작성한 테스트 코드


[CustomerRecoveryHistoryIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerRecoveryHistoryIntegrationTest.java)

- `emptyHistoryIsAvailableWithoutCreatingRecoveryState`: 미설정 고객의 빈 목록/다음 없음을 반환하며 복구 원본을 새로 만들지 않는다.
- `confirmedRecoveryIncludesLinkedKeyCountAfterNewKeyLogin`: 복구로 회수한 옛 JWT는401이고 새 키 로그인 후 복구1건·코드 교체1건과 연결 키 개수를 읽는다.
- `sameTimestampPagesHaveNoDuplicatesOrOmissions`: 동일 시각29개를 포함한30개를25+5로 중복/누락 없이 순회한다.
- `exactlyOnePageHasNoEmptyNextRequest`: 정확히25개면 next가 없어 빈 다음 페이지 호출을 만들지 않는다.
- `invalidCursorIsRejectedAndAudited`: 누락/미래 커서와 HTTP 단일 필드는400이고 거부 감사가 남는다.
- `pendingForeignAndOperatorSessionsCannotReadHistory`: 대기 세션·타인 세션·운영자는 서비스와 실제 HTTP에서 거부한다.
- `auditFailurePreventsSuccessfulHistoryResponse`: 필수 조회 감사 실패 때 결과를 성공으로 반환하지 않는다.
- `httpReturnsOnlyOwnPublicFactsWithoutRequiringFreshSignature`: 6분 경과한 유효 강한 세션으로 조회하고 no-store/최소 필드/비밀 비노출을 확인한다.
- `anotherCustomersFactsAreNeverIncluded`: 별도 CUSTOMER 계정의 실제 소유 원본을 넣어도 본인 목록에는 나오지 않는다.

## 관련 커밋과 증적

- [구현 6db28a1](https://github.com/GiHoon1123/Cex-Platform-Service/commit/6db28a13d5bb6cf7ce1e6e4b9520d73d0e62bf98)
- [격리 실행 증적](../reference/070-customer-recovery-history-verification-2026-10-25.json)

[072 커서 경계 검증](072-customer-email-change-history.md)에서 보안 이력 커서의 자체 범위를 UTC1970년~현재로 제한하고 JDBC 변환 전 범위 오류를400으로 거부했다. 기존 기록의 시각/내용은 보존한다.
