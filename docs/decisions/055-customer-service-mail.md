# 검증 주소의 선택형 고객 회신·최종 승인 메일

고객이 명시적으로 선택한 검증 주소에만 공개 회신/승인 결과의 새 안내가 있다는 사실을 메일로 알린다. 원래 자체 알림·공개 답변·명시적 고객 확인은 유지하고 외부 서버 접수와 고객 열람을 구분한다.

## 문제와 기존 동작

053은 영속 발송 기반,054는 현재 주소의 소유 확인을 제공한다. 이를 실제 고객 업무와 연결하지 않으면 재검토 회신이 있어도 고객이 로그인하기 전에는 알 수 없다. 반대로 등록 주소에 자동 발송하거나 내부 메모/보고 사유를 복사하면 잘못된 수신자·원치 않는 발송·비공개 업무 유출 위험이 생긴다.

## 기존 제품과 공식 자료의 접근

[Zendesk 공개 회신/비공개 메모](https://developer.zendesk.com/api-reference/ticketing/tickets/ticket_comments/)의 공개 범위를 나누는 방식을 기존 고객 회신에 적용했고 이번에는 공개 사실의 고정 알림만 외부로 연결한다. [AWS outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 같은 트랜잭션 예약과 재시도 중복 가능성, [OWASP 이메일 지침](https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html)의 현재 주소 검증을 적용했다. 실제 공급사의 내부 구조를 이 구현과 같다고 주장하지 않는다. 법령/공개 금융 서비스의 적용 배경과 비공개 보고·최소 수집 범위는 [046 검토](046-customer-final-receipt.md)를 따른다. 이번 수신 선택·24시간 알림 기한은 자체 정책이고 법적 의무나 마케팅 동의의 대체가 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 기본 미선택·현재 검증 주소에 결합한 불변 선택 이력 | 잘못된 주소/원치 않는 발송을 줄이고 당시 선택을 추적한다. | 고객은 이메일 확인과 별도 선택을 해야 한다. 모든 가입 주소 자동 발송은 채택하지 않는다. 주소 변경 뒤에는 다시 검증/선택이 필요하다. |
| 공개 회신/최종 승인과 같은 DB 트랜잭션에서 예약 | 업무 확정 뒤 예약 유실 틈을 없앤다. | 선택 고객의 예약/암호화/필수 감사 실패는 공개 업무도 롤백한다. SMTP 장애 자체는 업무 트랜잭션을 기다리게 하지 않는다. |
| 발송 직전 현재 계정·주소·동의·공개 근거 재검사 | 철회/중지/주소 변경 뒤 대기 중인 메일을 억제한다. | DB 조회 비용이 있고 검사 직후 이미 전송 중인 메일을 회수할 수 없다. SMTP 동안 고객/금융 잠금을 유지하는 대안은 채택하지 않는다. |
| 고정 제목/본문·로그인 후 자체 알림 안내 | 내부 메모·보고 여부·사유·금액·고객 입력을 메일에 넣지 않는다. | 고객은 본문을 보려면 로그인해야 한다. SMTP 접수/수신함 도달/열람/명시적 확인은 각각 다르다. |

메일은24시간 유효한 보조 알림이며 과거 업무를 소급 발송하지 않는다. 여러 워커/재시도 때문에 외부 접수 순서가 바뀔 수 있지만 본문은 같은 일반 안내이며 실제 이력 순서는 로그인한 자체 알림에서 확인한다. Message-ID 고정은 중복 제거 보장이 아니다. 외부 서버 결과 불명 뒤 일부 중복은 감수한다.

## 구현과 API

`GET /api/customer/email/preferences`는 현재 유효 수신 여부/선택 순번을 제공한다. `PUT`은 Idempotency-Key와 enabled를 받으며 본인 CUSTOMER 세션으로만 실행한다. 새 선택은 사용자 잠금 아래 논리 순번을 증가시켜 보존한다. 같은 키는 원래 선택 영수증만 반환하며 이후 철회를 되돌리지 않는다. 현재 상태는 GET으로 확인한다. 기존 승인 User.version은 바꾸지 않는다.

`CustomerMailRecipientPolicy`는 주소 확인/선택의 읽기 전용 판정이다. 토큰 발급·발송 예약과 분리해 순환 의존 없이 예약/발송 양쪽에서 사용한다. STATUS_NOTICE는 새 공개 회신 ID, FINAL_NOTICE는 실제 승인된 최종 결정 ID를 안정적인 근거로 쓴다. 거절/초안/내부 메모는 예약하지 않는다. 출력 워커는 그 근거와 본인 고객 결합을 다시 조회한다. 상태 회신의 같은 키 재전송은 새 예약을 만들지 않는다.

프론트의 PUT 사전 요청을 허용 출처에서 사용할 수 있도록 CORS 메서드에 PUT을 추가했다. 인증/역할 검사는 그대로 적용하며 다른 출처는 거부한다. SMTP 설정에서 빈 자격증명·누락 공개 HTTPS 주소·평문/선택적 TLS/외부 신뢰 우회는 거부한다. 기본 메일 스케줄러는 계속 꺼져 있고 실제 공급사/운영 자격증명을 추가하지 않았다.

## 이해에 필요한 문법과 실제 코드

출처: [CustomerMailRecipientPolicy.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailRecipientPolicy.java#L15), 현재 소스의 부분 발췌.

```java
    public boolean allowed(User user){
        // 설정 시 확인한 주소에만 동의가 적용된다. 나중에 주소를 바꿔 재검증해도 옛 동의를 이전하지 않는다.
        if(user==null||user.getRole()!=UserRole.CUSTOMER||!user.isAuthenticationAllowed())return false;
        var rows=jdbc.queryForList("SELECT enabled,email_mac,key_id FROM customer_service_mail_preferences WHERE user_id=? ORDER BY sequence DESC LIMIT 1",user.getId());
        if(rows.isEmpty())return false;var p=rows.getFirst();return Boolean.TRUE.equals(p.get("enabled"))&&verified(user)&&addressMac(user.getEmail()).equals(p.get("email_mac"))&&crypto.keyId().equals(p.get("key_id"));
    }
}
```

순번 DESC의 마지막 선택을 읽고 현재 주소 지문/키/소유 확인을 대조한다. 메일 예약 시점의 참을 영구 권한으로 취급하지 않는다. boolean 단락 평가로 미선택 고객은 추가 검증 조회를 하지 않는다.

출처: [CustomerReviewReplyService.java](../../src/main/java/dustin/cex/domains/edd/CustomerReviewReplyService.java#L50), 현재 소스의 부분 발췌.

```java
        // 공개 사실과 출력 예약은 함께 확정한다. 외부 SMTP는 이 트랜잭션에서 호출하지 않는다.
        mail.reserve(user,CustomerMailOutbox.Kind.STATUS_NOTICE,id,payload.observedAt());
        audit.record(actor,"CUSTOMER_REVIEW_REPLY_RECORDED","customer-reply:"+id,"SUCCESS","STATUS_OBSERVED_ONLY");return safe(payload);
```

트랜잭션이 끝나기 전 reserve가 실행되므로 예약 또는 뒤 감사 실패는 공개 회신도 롤백한다. TypeScript에서 DB 커밋 뒤 Promise로 전송만 시작하는 방식과 다른 보장이다. 실제 SMTP는 출력 워커에서 수행한다.

출처: [CustomerServiceMail.java](../../src/main/java/dustin/cex/domains/mail/CustomerServiceMail.java#L36), 현재 소스의 부분 발췌.

```java
    public void reserve(long user,CustomerMailOutbox.Kind kind,UUID reference,Instant observedAt){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("공개 알림 업무 트랜잭션이 필요합니다");
        if(kind==null||kind==CustomerMailOutbox.Kind.EMAIL_VERIFICATION||reference==null||observedAt==null)throw new IllegalArgumentException("공개 알림 근거가 필요합니다");
        var owner=users.findById(user).orElseThrow();if(!recipients.allowed(owner))return;
        // 예약 이후 동의를 철회해도 원본 예약을 지우지 않는다. 출력 직전에 다시 검사하고 억제 결과를 남긴다.
        outbox.enqueue(user,kind,reference,owner.getEmail(),null,observedAt.plusSeconds(86400));
    }
```

공개 근거와 관측 시각을 받아 고정24시간 기한을 계산하고 토큰 없는 알림을 예약한다. 같은 종류/근거는 같은 예약으로 제한한다. 사용자 입력 문구를 매개변수로 받지 않는다.

출처: [CustomerSmtpTransport.java](../../src/main/java/dustin/cex/domains/mail/CustomerSmtpTransport.java#L29), 현재 소스의 부분 발췌.

```java
            else{
                // 내부 메모/보고 여부/승인 사유/금액/고객 입력은 메일에 복사하지 않는다.
                helper.setSubject("새 서비스 알림");helper.setText("로그인 후 서비스 알림에서 새 안내를 확인하세요.\n"+base.resolve("/account/notices"),false);
            }
            var source=new InternetAddress(from,true);source.validate();m.setHeader("Message-ID","<"+p.id()+"@"+source.getAddress().substring(source.getAddress().lastIndexOf('@')+1)+">");sender.send(m);
```

검증 메일 외의 두 알림은 동일 고정 문구만 담는다. 메일 링크의 실제 프론트 화면은 백엔드 완료 후 구현할 범위이며 현재 화면을 제공한다고 주장하지 않는다.

## 검증 오류 로그에 포함된 비밀 토큰 제거

마지막 검토에서 컨트롤러의 Bean Validation 정규식이 잘못 입력한 토큰을 rejected value로 경고 로그에 남기는 문제를 발견했다. 유효 토큰 뒤에 문자를 붙인 입력·토큰 대신 배열을 준 JSON·challengeId 누락을 실제 HTTP로 보내고 CapturedOutput으로 원문이 남는지 검사했다. 추가1개 테스트가 수정 전 실제 실패해 위험을 재현했다. 로그 비노출 실패 메시지만 증적에 기록하며 원문 토큰/로그는 문서에 복사하지 않는다.

서비스는 이미 null/43자 URL-safe 형식과 실제 토큰 해시를 검사하므로 컨트롤러의 토큰 정규식/빈 문자열 중복 검증을 제거했다. UUID의 필수 검증과 본인 세션 검사, 요청의400 거부는 유지한다. Confirmation.toString도 토큰을 생략한다. 기존 EDD/금융 광역 검증 후 이 컨트롤러만 보완하고 메일58개·bootJar를 다시 검증하는 순서다. 광역 결과를 이 후속 변경 뒤 전부 재실행했다고 표시하지 않는다.


출처: [CustomerEmailController.java](../../src/main/java/dustin/cex/domains/mail/CustomerEmailController.java#L11), 현재 소스의 부분 발췌.

```java
    // 비밀 토큰의 형식은 서비스에서 검사한다. 기본 바인딩 오류의 거부 값 로그에 원문이 남지 않게 한다.
    public record Confirmation(@NotNull UUID challengeId,String token){@Override public String toString(){return "Confirmation[토큰 생략]";}}
```

비밀 값의 검증을 서비스 한 곳에 둔다. Bean Validation이 잘못된 원문을 예외에 복사하는 경로를 제거하면서 서비스의 형식·기한·해시 검사는 그대로 적용한다. 입력 String은 신뢰된 토큰이라는 뜻이 아니다.

신규 `CustomerEmailVerificationIntegrationTest.malformedTokenIsRejectedWithoutAppearingInLogs`: 세 종류의 잘못된 HTTP 입력은400, 확인 효과는 없고 출력 로그에 원래 토큰 문자열이 없어야 한다. [실제 테스트](../../src/test/java/dustin/cex/domains/kyc/CustomerEmailVerificationIntegrationTest.java)를 따른다.

## 작성한 테스트의 범위

이번055 신규25개는 실제 업무 연결16개·SMTP 설정7개·만료 예약 발송 억제1개·토큰 로그 비노출1개다.053의15개와054의18개까지 현재 고유 메일 테스트58개다. 테스트는 외부로 전달하지 않는 루프백 GreenMail과 실행마다 새 PostgreSQL16 서버를 사용한다. 메일 기반/실제 최종 승인 계층은 서로 다른 Spring 설정이므로 서버를 따로 생성한다. 실행기는 컴파일 실패 때 남은 옛 XML을 이번 통과로 집계하지 않는다.

## 실행 명령과 결과

`python3 scripts/verify_customer_mail.py`: 현재58개, 실패/오류/제외0·bootJar 통과. 브라우저 PUT 사전 요청의 허용 출처/외부 출처 거부까지 확인했다. 첫 연결 검증48개 뒤 만료/설정/CORS 경계를 보강했고 57개를 재실행했고 아래 비노출 보완 뒤58개를 재실행해 통과했다. 앞선 결과를 중복 합산하지 않는다.

전체 EDD992·KYC150·승인/접근190·MFA42·금융183·실제 시장 연결1개=기존 고유 JUnit1,558개를 통과했다. 마지막 토큰 로그 보완 뒤 현재 메일58개를 다시 검증해 이번 세 사이클 최종 고유 선택은 **1,616개**, 실패·오류·제외0개다. 신규58개는053의15·054의18·055의25개다. 앞선33/48/55/57개·기존 고객24개·동일 선택의 재실행은 중복 합산하지 않는다.

기존 프로세스15개(KYC 강제 종료/복원3·비웹 운영자 관리2·주문 중복/재시작10)를 통과했다. 실제 시장 연결은 실행 소유 Kafka/PostgreSQL과 Rust 엔진 경로를 사용했고 봇 거래의 제어된 테스트 명령을 검증한다. 기본 봇 스케줄러를 켠 것은 아니다. 실제 운영 공급사 Didit는 루프백 대역이며 로컬 DB/보안키 서명과 외부 공급사 연동 검증을 구분한다.

광역 명령은 `python3 scripts/verify_edd.py`, `verify_kyc.py`, `verify_kyc_recovery.py`, `verify_bound_approvals.py`, `verify_operator_mfa.py`, `verify_engine_inbox.py`, `verify_operator_admin.py`, `verify_order_idempotency.py`, `verify_market_path.py`, `audit_dependencies.py`, `verify_dependency_integrity.py`이며 모두 scripts/ 아래 실행기다. 두 실행 JAR·JMH 클래스·의존성 목록도 생성했다. 광역 회귀는7e14618 기준이고 이후 토큰 컨트롤러만 보완한 cf41cf6에서 메일58개와 두 JAR/JMH/목록을 다시 검증했다. 실행 시점과 실제 명령·사례·컨테이너 정리는 확정 JSON에 보존한다.

실제229개 패키지 버전을 OSV와 대조해 조회 시점 알려진 검출0개, 임시 복사본 체크섬 변조는 예상 Gradle 종료1로 차단했다. 목록 SHA-256은 `59eff23d67eae8de6a20e2cc3cf7cae8d28e48e758ddfccba0ec8122653eb57b`다. 현재 실제 코드 발췌212개와 별도 역사4/범위2개도 대조했다. 기존 deprecated/unchecked/JVM 공유 경고는 남는다. 실행 소유 컨테이너/임시 DB/체크섬 복사본을 정리하며 기존 개발 서비스는 유지했다.

## 운영 설정 예시와 상태 해석

아래는 운영 준비 시 검토할 설정 예시이며 실제 자격증명을 추가하거나 실행하지 않았다. 비밀번호는 저장소에 쓰지 않고 운영 비밀 관리에서 주입한다. 서버·도메인·위탁 조건을 확인한 뒤에만 발송 스케줄러를 켠다.

```yaml
customer-mail:
  dispatch-enabled: true
  from: no-reply@owned.example
  public-base: https://exchange.owned.example
spring:
  mail:
    host: smtp.owned.example
    port: 587
    protocol: smtp
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      "[mail.smtp.starttls.enable]": true
      "[mail.smtp.starttls.required]": true
```

PENDING/RETRY는 아직 접수 확정이 아니고 SENDING은 임대 상태다. SMTP_ACCEPTED도 외부 메일 서버 접수일 뿐이다. SUPPRESSED는 현재 자격/기한 검사에 따른 억제, EXHAUSTED는 최대 시도 소진이다. 원래 공개 답변과 로그인한 고객의 자체 알림은 남는다. EXHAUSTED 운영 경보/승인된 재발송·바운스 자동 처리·주기 스케줄러의 실제 프로세스 장애 주입은 이번 검증에 포함하지 않았다. 별도 운영 후속 항목이다.

## 운영 경계와 미완료

실제 외부 수신함 전달·바운스/반송·DKIM/SPF/DMARC·공급사 계약/위탁 조건·주소 검증 전 계정 활성 정책 전체·대량 발송/가입 남용·정보주체 삭제/보존 운영·법적으로 유효한 고지 수단/기한은 별도 확인 사항이다.58개는 로컬 통합 검증이며 신규 성능/강제 종료 검증은 없다. 메일 발송 예약이 법정 고지나 고객 확인을 충족한다고 주장하지 않는다. 기본 스케줄러/봇은 꺼져 있고 개발 서버/기존 DB/엔진/프론트/배포는 변경하지 않는다. Rust 단일 스레드·공유 락 없는 체결 코어는 그대로다.

## 작성한 테스트 코드


[CustomerServiceMailIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerServiceMailIntegrationTest.java)

- `defaultOffAndUnverifiedCannotEnable`: 기본은 미선택이며 미검증 주소 활성화는409, 공개 회신은 메일0건이다.
- `verificationAloneDoesNotSubscribe`: 주소 확인만 완료한 고객의 공개 회신도 메일0건이다.
- `actualStatusMailIsGenericAndCannotLeakInternalReason`: 실제 로컬 SMTPS 본문은 고정 링크/안내이며 내부 사유·고객 입력·금액을 포함하지 않고 금융 차단은 유지된다.
- `finalApprovalReservesOneGenericNotice`: 실제 업무별 서명 최종 승인 후 토큰 없는 메일1건이 예약/접수되고 금융 상태는 유지된다.
- `draftAndRejectionNeverReserveMail`: 초안과 실제 서명 거절은 모두 알림 예약0건이다.
- `sameReplyKeyCannotReserveTwice`: 같은 회신 키의 재전송은 원래 회신과 메일1건만 유지한다.
- `unsubscribeBeforeSendSuppressesQueuedMail`: 예약 후 수신 철회는 SMTP0건·SUPPRESSED이며 원본 예약은 보존한다.
- `disableBeforeSendSuppressesQueuedMail`: 예약 후 계정 중지는 발송을 억제한다.
- `changedAddressInvalidatesConsentAndSuppressesOldTarget`: 주소 변경은 현재 검증/선택을 무효화하고 옛 주소 발송을 억제한다.
- `replyAuditFailureRollsBackMailAndBusinessThenRetries`: 공개 회신 감사 실패는 회신/예약을 롤백하고 같은 키 재시도에 예약1건을 만든다.
- `finalDecisionAuditFailureRestoresSignatureAndMailReservation`: 최종 승인 감사 실패는 서명 소모/결정/예약을 함께 롤백하고 같은 증명 재시도가 성공한다.
- `preferenceAuditFailureDoesNotChangeCurrentChoice`: 설정 감사 실패 뒤 현재 수신 선택은 미선택이다.
- `preferenceHistoryReplayDoesNotUndoLaterUnsubscribe`: 옛 활성화 키 재송신은 옛 영수증만 반환하고 나중 철회를 되돌리지 않는다. 같은 키 다른 값은 충돌한다.
- `preferenceHistoryIsImmutable`: 선택 이력 UPDATE/DELETE/TRUNCATE를 DB가 거부한다.
- `httpPreferenceRequiresCustomerAndReturnsCurrentEligibility`: 본인 고객 PUT/GET은 정상이고 운영자의 변경은403이다.
- `browserPutPreflightAcceptsOnlyConfiguredOrigin`: 설정 출처의 PUT 사전 요청은 허용하고 외부 출처는403이다.

[CustomerSmtpTransportTest.java](../../src/test/java/dustin/cex/domains/kyc/CustomerSmtpTransportTest.java)

- `cleartextSmtpIsRejectedBeforeNetwork`: 평문 SMTP를 소켓 연결 전에 거부한다.
- `optionalStarttlsIsRejectedBeforeNetwork`: 선택적 STARTTLS만 켠 설정은 거부한다.
- `remoteTrustOverrideIsRejected`: 외부 호스트의 신뢰 우회 설정을 거부한다.
- `remoteAuthenticationIsRequired`: 외부 SMTPS에서 발신 인증 누락은 거부한다.
- `requiredStarttlsEnforcesIdentityAuthAndFiniteTimeouts`: 필수 STARTTLS의 인증/호스트 검사·각5초 제한·debug 비활성 설정을 확인한다.
- `unsafeOrMissingPublicBaseIsRejected`: 누락/HTTP/사용자 정보/쿼리/fragment 공개 주소를 거부한다.
- `blankRemoteCredentialsAreRejected`: 공백만 있는 외부 발신 자격증명을 거부한다.

## 관련 커밋과 증적

- [구현 7e14618](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7e14618a066e18979bb88578be9d360b0e083159)
- [격리 실행 증적](../reference/055-customer-service-mail-verification-2026-10-24.json)

기존 [CustomerMailOutboxIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerMailOutboxIntegrationTest.java)의 신규 `expiredQueuedPayloadIsSuppressedWithoutSmtp`는 과거 만료 접수 표본이 SMTP 발송 없이 SUPPRESSED로 확정됨을 검사한다. 나머지 기존15개 시나리오는 [053](053-durable-customer-mail.md), 확인18개는 [054](054-customer-email-ownership.md)를 따른다.

- [토큰 로그 비노출 보완 cf41cf6](https://github.com/GiHoon1123/Cex-Platform-Service/commit/cf41cf6b0b767a5660518e9f3b8ece5453371cc6)
