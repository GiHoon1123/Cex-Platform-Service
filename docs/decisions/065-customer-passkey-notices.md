# 키 변경 원본 조회와 검증 주소 보안 통지

불변 키 추가·폐기 사실을 본인 조회와 검증 주소 보안 통지에 연결한다. 변경·예약·필수 감사의 원자성, 외부 SMTP와 고객 확인의 차이, 주소 변경·계정 중지·재시도 한계를 설명한다.

## 문제와 기존 동작

064까지 키 수명과 원본은 저장했지만 고객이 삭제된 키의 변경 사실을 조회하거나 외부 보안 안내를 받지는 못했다. 현재 키 목록만 보면 폐기한 키는 사라지므로 과거 사실과 구분해야 한다. 선택한 일반 서비스 답변 알림을 끄면 보안 변경까지 숨겨져서도 안 된다.

## 기존 제품과 공식 자료의 접근

[OWASP MFA 지침](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)은 인증 요소 변경의 재확인/통지를 다룬다. [Coinbase 공개 2단계 설정](https://help.coinbase.com/en/coinbase/getting-started/getting-started-with-coinbase/2-step-verification)의 키 관리 기능은 사용자 흐름 참고이며 해당 제품 내부 큐·DB 구현을 추측하지 않는다.

이미 설치된 Spring Mail·검증 주소·암호화·영속 메일 예약/별도 워커와061의 비밀번호 보안 안내 정책을 재사용한다. 새 패키지나 별도 메일 전송기를 추가하지 않는다. 보안 통지의24시간 만료·기본5회 시도와 고객 조회25개는 자체 정책이며 국내 법정 수치라고 표시하지 않는다.

## 선택과 트레이드오프

- 원본 변경 ID에 MFA_CHANGED 예약을1건씩 묶는다. 현재 키 존재 여부로 예약하지 않으므로 폐기한 키의 보안 통지도 근거를 유지한다. 기록/예약 저장 실패는 키 업무 전체를 실패시키는 비용을 감수해 반쪽 결과를 막는다.
- 검증된 현재 주소에 고정 보안 안내를 보낸다. 서비스 답변 알림 선택과 구분하며 계정 중지는 이 경고를 숨기지 않는다. 발송 전 주소가 달라지면 취소한다. 확인하지 않은 주소로 보내지 않는 대신 그 주소의 소유자는 통지를 받지 못한다.
- 주소를 바꿔 아직 확인하지 않았어도 살아 있는 다른 키로 분실 키를 폐기할 수 있다. 외부 메일은 생략하고 불변 원본은 보존한다. 주소 확인을 강제해 침해 키 폐기까지 막는 대안은 선택하지 않았다. 확인 주소의 암호화 키/무결성 오류는 조용히 삼키지 않으므로 이 장애는 업무를 막을 수 있다.
- 메일은 고정 문구와 /account/security 링크만 포함한다. 이름/지문/공개키/사용자 입력/인증 서명은 넣지 않는다. 최소 노출을 얻는 대신 자세한 내용은 로그인 후 조회해야 한다.
- 기본5회/만료 계약을 사용하고 운영자의 추가 재발송 승인 대상에는 넣지 않는다. 오래된 경고 반복을 줄이는 대신 소진 이후 수신을 보장하지 않는다. SMTP 수신 확인은 최종 배달이나 고객 열람 확인이 아니다.

## 구현과 API

`CustomerMfaKeyChanges.append`가 같은 트랜잭션에서 실제 원본을 만든 뒤 `CustomerMfaNotice.reserve`를 호출한다. 소유자와 DB 생성 시각을 원본에서 읽고 정확한 확인 주소에만 예약한다. 원문 비밀번호·키 이름/지문·서명 자료를 외부 전달용 본문에 복사하지 않는다. 외부 SMTP는 기존 독립 워커에서 처리하고 고객 사용자 잠금/키 업무 트랜잭션을 잡은 채 전송하지 않는다.

`GET /api/auth/customer-mfa/changes`는 현재 인증 수준을 통과한 본인의 변경 ID·시각·ADDED/REVOKED·키 지문을25개씩 제공한다. `afterAt`/`afterId` 복합 커서로 동일 시각도 빠뜨리지 않는다. 현재 키를 삭제해도 원본 변경 사실은 조회된다. 원본 credential ID·키 자료·주소·고객/세션 ID는 반환하지 않는다. 조회는 필수 감사를 남기지만 고객이 변경을 승인/확인했다는 영수증을 만들지 않는다.

발송 직전에는 현재 CUSTOMER 역할·동일 주소·확인 상태·만료와 실제 불변 변경 소유자를 재검사한다. 계정 중지 뒤 보안 통지는 계속하지만 일반 서비스 답변 알림의 활성/수신 선택 검사는 유지한다. 최초/예비 등록·폐기 모두 동일 예약 경로를 사용한다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMfaKeyChanges.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaKeyChanges.java#L15), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID append(long actor,UUID sid,Kind kind,String fingerprint){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_mfa_key_changes(id,user_id,session_id,kind,credential_fingerprint) VALUES(?,?,?,?,?)",id,actor,sid,kind.name(),fingerprint);
        notice.reserve(actor,id);
        return id;
    }
```

불변 원본과 통지 예약은 상위 키 업무 트랜잭션에 참여한다. 마지막 감사가 실패하면 키 변경과 이 행·메일까지 함께 롤백된다. 외부 SMTP 성공을 DB 트랜잭션으로 원자화했다고 설명하지 않는다.

출처: [CustomerMfaNotice.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaNotice.java#L18), 현재 소스의 부분 발췌.

```java
        var row=jdbc.queryForMap("SELECT user_id,created_at FROM customer_mfa_key_changes WHERE id=?",id);
        if(((Number)row.get("user_id")).longValue()!=actor)throw new IllegalStateException("키 변경 통지의 소유자가 다릅니다");
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE user_id=?)",Boolean.class,actor))return;
        var user=users.findById(actor).orElseThrow();if(!recipients.verified(user))return;
        // 원본 확정 시각을 기준으로 만료한다. 수신 선택과 구분하되 확인 주소의 키 오류는 삼키지 않는다.
        outbox.enqueue(actor,CustomerMailOutbox.Kind.MFA_CHANGED,id,user.getEmail(),null,((Timestamp)row.get("created_at")).toInstant().plusSeconds(86400));
```

클라이언트가 준 변경 종류/시각/주소를 그대로 믿지 않는다. 실제 원본에서 시각을 얻고 현재 검증 주소를 확인한다. 주소 확인 이력이 없으면 불필요한 키 요구를 만들지 않지만 확인 주소의 무결성 장애를 폴백으로 숨기지 않는다.

출처: [CustomerMfaHistory.java](../../src/main/java/dustin/cex/domains/security/CustomerMfaHistory.java#L23), 현재 소스의 부분 발췌.

```java
        String sql="SELECT id,created_at,kind,credential_fingerprint FROM customer_mfa_key_changes WHERE user_id=? ";
        var rows=after==null?jdbc.queryForList(sql+"ORDER BY created_at DESC,id DESC LIMIT 25",actor):jdbc.queryForList(sql+"AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT 25",actor,Timestamp.from(after.createdAt()),after.id());
        var items=rows.stream().map(r->new Change((UUID)r.get("id"),((Timestamp)r.get("created_at")).toInstant(),CustomerMfaKeyChanges.Kind.valueOf((String)r.get("kind")),(String)r.get("credential_fingerprint"))).toList();var last=items.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_MFA_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CHANGE_FACTS_ONLY");return new Page(items,rows.size()==25?new Cursor(last.createdAt(),last.id()):null);
```

복합 (created_at,id) 비교로 같은 시각의 여러 원본을25개씩 읽는다. enum은 저장된 허용 종류만 복원하며 동적 명령이나 임의 필터를 실행하지 않는다. List.copyOf로 반환 목록도 변경할 수 없게 만들지만 원본 보존은 DB 불변 트리거가 담당한다.

## 실행 명령과 결과

`python3 scripts/verify_customer_account.py`: 기존75개+신규10개=85개, 실패/오류/제외0. 첫84개가 통과한 뒤 미검증 새 주소가 분실 키 폐기까지 막지 않는 경계1개를 추가해85개 전체를 통과했다. 앞선84개를 다시 합산하지 않는다.

`python3 scripts/verify_customer_mail.py`: 기존 일반 알림/주소 확인/SMTP/운영자 메일/추가 재발송 회귀95개 통과. 이번 고유 선택180 JUnit, 실패/오류/제외0이다. 실제 PostgreSQL16·로컬 SMTP·공개키 서명·MVC·감사와 사용자 잠금을 사용하고 실행 소유 자원만 생성/정리했다. 봇은0명이다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain` 통과. 기존 deprecated/unchecked/JVM 경고는 남는다. 새로운 의존성/버전 변경·OSV 조회·성능 측정·외부 메일/배포·엔진/프론트 변경은 없다. 앞선063 금융/시장577개와061 메일 강제 종료6개는 당시 결과로 구분하며 이번 수치에 합산하지 않는다. 이번에는 메일 임대/재시도/워커 구현을 바꾸지 않았다.

## 한계와 다음 연결

고객의 모든 키/기기 분실에 대한 독립 복구와 비밀번호/이메일/출금의 업무 내용 결합 추가 인증이 남아 있다. 현재 확인 주소 변경 공격의 옛 주소 통지와 이메일 변경/복구 흐름은 B07에서 다룬다. SMTP 최종 배달/고객 열람·운영 TLS/키 보호·실물 패스키·보존/파기 및 인증/신고의 외부 확인은 별개다.

## 작성한 테스트 코드


[CustomerMfaNoticeIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMfaNoticeIntegrationTest.java)

- `verifiedAddressGetsFixedSecurityNoticeWithoutServiceOptIn`: 일반 알림 선택이 꺼져 있어도 실제 SMTP 보안 안내를 전달하고 키/서명/주소 관련 비밀을 본문에 넣지 않는다.
- `disabledCustomerStillReceivesSecurityWarning`: 계정 중지 뒤에도 현재 검증 주소의 보안 안내는 SMTP로 전달한다.
- `changedAddressSuppressesOriginalRecipient`: 발송 전 주소가 바뀌면 옛 주소 예약은 SUPPRESSED이며 실제 메일0건이다.
- `reservationFailureRollsBackKeyFactSessionsAndMail`: 메일 예약 감사 실패는 키/등록 사실/변경 원본/세션 회수/메일을 롤백하며 정상 재시도가 가능하다.
- `revokeAuditFailureAlsoRollsBackQueuedSecurityWarning`: 폐기 감사 실패는 이미 만든 보안 예약까지 롤백하고 기존 두 키/세션/두 예약을 유지한다.
- `ownHistoryIncludesRemovedKeyAndHttpRequiresVerifiedCustomer`: 폐기한 키의 사실도3건 조회되고 본인 HTTP200/no-store·운영자/타인403이며 원본 자격을 노출하지 않는다.
- `historyCursorHandlesSameTimeAndRejectsInvalidPositions`: 동일 시각29건 fixture와 실제 원본1건을25+5로 누락/중복 없이 읽고 불완전/미래 커서를400으로 거부한다.
- `historyAuditFailureDoesNotReturnFacts`: 필수 조회 감사 실패는 원본을 성공 응답으로 반환하지 않는다.
- `exhaustedKeyWarningCannotUseOperatorExtraRetry`: 키 보안 경고5회 소진을 운영자 추가 재발송 승인으로 우회하지 못한다.
- `unverifiedChangedAddressDoesNotPreventLostKeyRevocation`: 미검증 새 주소에 메일을 만들지 않으면서 다른 키를 사용한 분실 키 폐기/원본 보존/재로그인은 가능하다.

## 관련 커밋과 증적

- [구현 19df3d0](https://github.com/GiHoon1123/Cex-Platform-Service/commit/19df3d0d7f7a31e6cf3a46dc21d2e7b4ff75b5d0)
- [격리 실행 증적](../reference/065-customer-passkey-notices-verification-2026-10-25.json)
