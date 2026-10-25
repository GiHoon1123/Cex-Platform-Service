# 현재 고객과 입금·송신 출처를 암호화된 정보 제공 의도로 결합하기

외부에 수취인 이름을 제공하기 전에 현재 고객 확인·입금/체인·송신 검색·상대 정책을 하나의 암호화된 불변 의도로 확정한다. 동시 예약/감사 실패와 최신 근거 교체를 검증한다. 이 기록은 과거 제공 의도이며 현재 전송 허가나 금융 승인 토큰이 아니다.

## 문제와 기존 동작

104는 정보 교환 통신 계약,105는 정보 제공 상대의 운영 허용 정책을 구현했다. 아직 실제 고객 확인/입금 원본과 연결하지 않았다. 요청자가 적어 준 이름이나 이전의 FOUND를 그대로 가져오면 현재 신원/제한/체인·검색 실패를 건너뛸 수 있다. 외부에 보낸 뒤에만 근거를 저장하면 중간 장애에서 제공 의도와 감사가 남지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)의 외부 전달 전에 업무/전달 의도를 같은 DB 트랜잭션으로 남기는 원칙을 다시 대조했다. 원격 호출 자체를 DB와 하나의 원자적 커밋으로 만들었다고 주장하지 않는다. 이번 단계는 영속 의도만이며 자동 전달기는 아직 없다.

[CodeVASP 정보 요청](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request)은 검색 ID·거래 ID·수취 주소와 수취인 정보를 요구한다. [상호 연동 안내](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/12-Interoperability-with-Other-Protocols)의 기술 회원/내부 실사 정책 구분도 유지한다.

[JDK21 Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)의 인증 암호/AAD·nonce 조건과 이미 설치된 CddCrypto를 확인했다. 암호 알고리즘/별도 키 관리 프레임워크를 재구현하지 않고 기존 AES-256-GCM·독립 HMAC 키·사용자/행 AAD를 재사용한다. 정보 제공 의도 HMAC의 도메인은 codevasp-transfer-intent-v1로 분리한다. 키 회전/장기 보존/파기 운영까지 새로 완성한 것은 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|현재 사용자 행 잠금 안에서 자격과 원본 결합 | KYC/제한 변경·동시 예약의 순서가 명확 | 짧은 플랫폼 DB 경합. 외부 I/O는 없지만 복호화/JSON 검사는 이 짧은 TX 안에서 수행 |
|현재 체인·검색·목록 위치 공유 잠금 | 최신 실패를 과거 성공으로 숨기지 않음 | 외부 자료 위치 갱신은 예약 TX가 끝날 때까지 잠시 기다릴 수 있음 |
|검증된 현재 신고 복호화·신원 HMAC 일치 | 고객 입력 이름을 신원 사실로 바로 사용하지 않음 | 현재 키·신고·평가/공급사 확인이 없으면 보류. 암호키 접근과 복호화 비용 필요 |
|같은 검색의 단일 불변 의도 | 동시/재시작 예약이 같은 ID를 공유 | 기존 의도를 현재 허가로 재사용할 수 없음. 현재 근거가 달라진 의도의 실행/재심은 별도 업무에서 다뤄야 함 |
|개인정보 포함 전체 요청은 암호문, 공개 행은 원본 참조/지문만 | 외부 전달 근거 추적과 원문 최소 노출 | 암호문도 개인정보다. 접근·보존/파기·키 수명 조건을 관리해야 함 |
|같은 주소의 여러 양수 출력은 보류 | vout을 받지 않는 외부 API에서 귀속 금액 추측을 막음 | 일부 정상 배치 입금도 별도 심사 필요. 이를 불법/자금 손실로 판정하지 않음 |

## 구현과 데이터 흐름

CodeVaspTransferIntents.reserve는 내부 업무 진입점이며 HTTP에 노출하지 않는다. 기존 검색을 현재 접속 주체로 읽고, 실제 수취 고객의 행을 잠근다. 같은 검색의 기존 의도가 있으면 개인정보 없는 과거 ID/원본 참조만 반환한다. 이 멱등 반환은 현재 고객 자격을 인증하지 않으며 외부 전송을 수행하지 않는다.

새 의도는 기존 CustomerFinancialEligibility를 통해 현재 역할/활성·거래 제한/관계 종료·CDD/EDD·현재 live 신원과 최신 공급사 재확인을 검사한다. 여기서 live는 제품의 환경 필드이며 로컬 시험에서는 합성 서명 자료다. 실제 공급사 운영 계정을 사용한 증명이 아니다.

현재 고객 신고의 암호키·원문 HMAC을 확인하고 복호화한 정상 신고의 이름/생일/국적 HMAC이 현재 평가의 확인된 신원 지문과 같아야 한다. KYC를 재구현하거나 호출자가 이름/생일을 넣는 새 API를 만들지 않았다. 기존 기준을 재사용한다.

현재 보류 원장이 존재하고 CHAIN_READY/현재 관찰 위치가 일치하며 일반 거래/지갑 사용 아님·관찰2분 이내여야 한다. 현재 검색은 FOUND이고 기록15분 이내라는 자체 제공 준비 정책을 적용한다. 현재 디렉터리5분·정확한 source·105 상대 정책/키를 확인한다. 현재 관찰의 같은 수취 주소 양수 출력이 정확히 하나일 때만 원본 receipt 금액과 요청을 결합한다.

무작위 의도 ID·현재 검색/목록/체인/관찰·신고/평가·상대 정책 지문/키·현재 암호키 ID를 저장한다. 수취인 이름/생일·신원 지문·사용자 변경 번호와 실제 요청 DTO는 AES-GCM 암호문에 넣고 도메인 HMAC을 남긴다. Intent 반환에는 이름/생일/주소/개인키가 없다. 필수 감사와 의도는 같은 TX로 커밋하고 감사 실패면 둘 다 롤백한다. Rust 코어·원장/잔액·Kafka에는 보내지 않는다.

새 codevasp_transfer_intents 표는 FK·암호문/지문 상한·같은 검색 고유 제약, 현재 입금/검색/목록/체인/고객 평가의 결합 트리거, UPDATE/DELETE/TRUNCATE 금지를 둔다. REGTEST는 명시적 루프백 공급사만 허용하는 기존 경계도 유지한다. 실제 운영 정책 설정/KYC 자격 검사는 애플리케이션 책임이며 DB 트리거가 모든 실사/법적 적합성을 판정한다고 주장하지 않는다. 서비스는 현재 위치를 잠근 뒤 삽입하고, DB의 원본 관계 검사도 우회를 거절한다.

## 이해에 필요한 실제 코드와 문법

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L51), 현재 소스의 부분 발췌.

```java
            var user=users.lockById(owner).orElseThrow(CodeVaspTransferIntents::held);
            var old=jdbc.queryForList("SELECT id FROM codevasp_transfer_intents WHERE search_id=?",UUID.class,searchId);
            if(!old.isEmpty())return load(old.getFirst());
            crypto.requireConfigured();if(!eligibility.permitted(user))throw held();
            var person=jdbc.queryForMap("""
```

사용자 잠금을 취득한 후 현재 고객 자격을 검사한다. 기존 의도의 반환은 개인정보 없는 과거 참조다. TypeScript에서도 멱등 키의 기존 결과를 반환하는 것과 새 금융/외부 작업을 승인하는 것은 별도 의미여야 한다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L61), 현재 소스의 부분 발췌.

```java
            UUID declaration=(UUID)person.get("declaration_id");String plain=new String(crypto.decrypt(owner,declaration,(byte[])person.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("declaration",plain).equals(person.get("content_mac")))throw held();
            var customer=json.readValue(plain,CustomerDeclaration.class).validated();String identity=crypto.fingerprint("identity",json.writeValueAsString(List.of(customer.fullName(),customer.dateOfBirth().toString(),customer.nationality())));
            if(!identity.equals(person.get("identity_mac")))throw held();
```

기존 고객 원문 암호화와 HMAC을 재사용한다. 공개 SHA-256으로 이름/생일을 보관하지 않고, 검증된 평가의 신원 지문과 대조한다. 신고 자체가 신원 증명인 것은 아니며 앞의 현재 자격 검사와 함께 사용한다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L81), 현재 소스의 부분 발췌.

```java
            var outputs=json.readTree(chain.getFirst().get("outputs").toString());int matches=0;
            for(var output:outputs)if(search.request().beneficiaryAddress().equals(output.path("address").asString())&&output.path("amount").isNumber()&&output.path("amount").decimalValue().signum()>0)matches++;
            if(matches!=1)throw held();
```

공급사에는 vout을 보내지 못하므로 주소에 양수 출력이 하나인지 별도 확인한다. Decimal 값을 float로 계산하지 않는다. 여러 출력이면 임의 합산·분할 매칭을 하지 않고 보류한다.

출처: [CodeVaspTransferIntents.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferIntents.java#L92), 현재 소스의 부분 발췌.

```java
            audit.record(null,"CODE_TRANSFER_INTENT_RESERVED","codevasp-transfer:"+id,"SUCCESS","SEARCH:"+searchId+":ATTEMPT:"+origin.attemptId());
            return load(id);
```

암호문 INSERT와 감사는 같은 TransactionTemplate 안에 있다. 감사가 실패하면 저장도 롤백된다. 성공 감사에는 이름/생일/원문 대신 의도·검색·시도 식별자만 넣는다.

## 작성한 테스트

[CodeVaspTransferIntentIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CodeVaspTransferIntentIntegrationTest.java)13개를 추가했다. 실제 PG, 기존 KYC HMAC 검증/신고 평가·금융 자격·행 잠금·암호화/원장/감사를 사용한다. 체인/CodeVASP 원본은 정상 제약을 통과시킨 합성 fixture이며 실제 노드/공식 모듈·공급사 검증이 아니다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|currentVerifiedRecipientAndAllOriginalSourcesAreSealedWithMandatoryAudit | 실제 확인된 이름/생일·입금 ID/거래·HMAC/암호문·필수 감사 결합, 다른 사용자/행 AAD로 복호화 실패 |
|existingIntentIsHistoricalMetadataAndDoesNotBecomeNewPermission | 예약 뒤 제한 변경에도 과거 ID만 멱등 반환·새 의도/전송 없음 |
|concurrentReservationsKeepOneIdentityAndAudit | 두 스레드 동시 예약은 같은 불변 의도 |
|mandatoryAuditFailureRollsBackEncryptedIntent | 필수 감사 장애는 암호문 예약0 |
|unapprovedCounterpartyCannotCreateDisclosableIntent | 기술 FOUND여도 미허용이면 새 의도0 |
|restrictedCustomerAndOldKycReconciliationCannotPrepare | 거래 제한·낡은 공급사 재확인은 각각 차단 |
|newCustomerDeclarationCannotBorrowOldVerifiedIdentity | 신고가 바뀌면 이전 신원으로 예약 불가 |
|newerStaleChainCannotBorrowOriginalPendingDecision | 원래 보류 판단이 있어도 현재 오래된 관찰은 차단 |
|latestFailedSearchCannotBorrowOlderFound | 최신 조회 실패는 옛 FOUND로 우회 불가 |
|ambientTransactionCannotPrepareOrExposeIdentity | 호출자 TX 안에서 시작 거부 |
|originalIntentCannotBeUpdatedDeletedOrTruncated | 불변 원본3종 변경 차단 |
|directSqlCannotBindIntentToDifferentCustomer | 직접 SQL의 다른 고객 귀속은 고유키 실패가 아닌 원본 결합 트리거 안내로 거부 |
|multiplePositiveOutputsToSameRecipientAddressRequireSeparateReview | 온체인 CHAIN_READY여도 같은 주소2개 출력은 정보 제공 의도 보류 |

모든 시험 뒤 공급사 목록/검색/정보 교환·Didit HTTP·엔진/Kafka 호출0과 user_balances0, 원래 고객 PENDING0.12345678 유지·감사 상세의 이름/생일/주소 비노출을 확인한다. 동시 예약은 같은 의도뿐 아니라 필수 감사도1건인지 확인했다. 준비 과정은 보류 자산을 사용 가능 잔액으로 바꾸지 않는다. 기존 SearchIntegration38+Worker8의46개, 금융182개도 별도 소유 DB에서 재실행했다.

## 실행 명령과 결과

- 첫 compileJava에서 실제 Result 필드명을 originEntityId로 잘못 참조해 실패했고 entityId로 수정했다. 초기12개 fixture는 SUBMIT 단계에 FOUND를 직접 넣어 기존 단계 제약에 의해 모두 실패했다. 제약을 약화하지 않고 SUBMIT_ACKNOWLEDGED→POLL/FOUND의 정상 이력을 구성했다. 감사 컬럼도 실제 target을 사용하고, 다중 출력 시험을 추가해 최종13개로 검증했다. 최초 실패 로그/manifest는 `.local/cycle106-failed`에 남겼다.
- `python3 scripts/verify_codevasp_transfer_intents.py`: 최종13개 통과.
- `python3 scripts/verify_codevasp_deposit_searches.py`: 기존46개 통과.
- `python3 scripts/verify_engine_inbox.py`: 기존 금융182개 통과. 상위 실행에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_를 제거하고 소비자 자동 시작 금지를 고정했다.
- Java21/macOS arm64/각 소유 PG16. 고유241개/최종 검증 집합241실행·중복0, 실패/오류/제외0. 각 XML의 클래스+함수 집합을 대조했다. 중간 실패/앞 회차81개를 이번 합계에 더하지 않는다. 신규13개는 보류 자산/감사 비노출·동시 감사1건 단언 보강 뒤 다시 통과했고 이 마지막 XML/로그를 보존했다. 앞의13개 실행을 중복 합산하지 않았다. 세 소유 PG 제거 확인.
- `.local/cycle106-proof`에 신규13 XML/로그/manifest, search46과 finance182 각각의 XML/로그/manifest 및 중복 대조를 보존했다.
- bootJar/operatorAdminJar 성공·두 JAR에 제품 클래스/스키마 포함·시험 제외 확인. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 의존성 추가 없음.
- `git diff --check`·현재 발췌/상대 링크 대조. 실제 BTC 노드 전체·개인정보 HTTP81·운영 심사/보안 전체·Rust/Kafka/브라우저는 이번에 재실행하지 않았다.

## 남은 한계와 다음 연결

아직 이 의도를 자동 전달하지 않는다. 전송 시작의 영속 기록·실행 직전 현재 근거 재확인·봉인 원문/메타데이터 대조·불명 처리·응답 암호화 보관·이름/생일 업무 검증이 필요하다. 과거 의도를 읽고 그대로 외부 전송하는 경로를 추가하면 안 된다. 기존 의도 이후 원본이 바뀐 경우의 재심/재예약도 명시적으로 결정해야 한다.

금융 해제·개인지갑/미확인 사업자·동일인 판정/운영 해결·엔진 HTTP/WAL/Kafka 연결과 입금 재조직 복구는 미완료다. 실제 실사/계약/법정 적용·개인정보 제공 근거·장기 보존/파기/키 관리와 공급사 Sandbox/LIVE는 외부 확인 상태다. 같은 키 체계를 재사용하므로 목적별 키 관리·보존 종료 시 암호문 파기·법적 보류는 현재 전체 개인정보 작업과 함께 운영에서 검증해야 한다. 코드 작성만으로 심사/법적 의무 완료를 주장하지 않는다.


## 관련 커밋과 증적

- [구현 fb38c04](https://github.com/GiHoon1123/Cex-Platform-Service/commit/fb38c04cfb97e3aa02da0d7b60e6aceb3b54847b)
- [격리 실행 증적](../reference/106-sealed-deposit-transfer-intent-verification-2026-10-25.json)
