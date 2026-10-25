# BTC 송수신인 정보의 암호화 요청과 복호화 결과를 결합하기

공식 Cipher를 이용해 국내 CODE 개인 수취의 암호화 정보 교환 요청과 응답을 해석한다. 공개 거래 결합 필드, 선택한 상대 키, 암호문, 복호화 원본의 일치를 검증한다. 이 단계는 내부 통신 클라이언트이며 아직 자동 입금 승인에 연결하지 않았다.

## 문제와 기존 동작

출처 검색의 FOUND는 송신 사업자를 찾았다는 기술 결과다. 송수신인 확인이나 자금 사용 허가가 아니다. 검색 요청 ID와 실제 입금·수취인에 결합된 정보 교환 없이 거래를 허용해서는 안 된다. Java에 공급사 개인키나 별도 암호 알고리즘을 구현할 이유도 없다.

## 기존 제품과 공식 자료의 접근

2026-10-25 확인한 공식 공개 자료다.

- [자산 이전 정보 요청](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/13-Asset-Transfer-Data-Request): 송신 사업자 ID의 고정 PUT 경로, 검색 requestId를 transferId로 재사용하고 암호화 payload를 전달한다. 정상 응답의 currency/amount/transferId를 대조한다.
- [Cipher Addon](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/02-Create-Header-Payload-2-Addon): VERIFICATION_TRANSACTION_ID와 상대 공개키, 수취인 자연인 이름·지갑을 지정해 서명/암호화 본문을 받는다.
- [Cipher 복호화](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/04-Decrypt): 암호문과 상대 공개키를 비공개 모듈로 전달하고 body/bodyString을 받는다.
- [암호 가이드](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/02-Encryption-Decryption): 개인정보의 종단 간 인증 암호와 요청 서명을 구분한다. 공식 모듈을 사용하고 Java에서 개인키/암호 수학을 재구현하지 않는다.
- [IVMS101 안내](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/04-IVMS101-part1): 문서의2020 형식과 자연인 법적 이름·생일·계정 구조를 따른다. 이름을 분리할 수 없으면 전체 이름을 primaryIdentifier에 둔다. 계정 주소의 대소문자는 바꾸지 않는다.
- [상호 연동 안내](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/12-Interoperability-with-Other-Protocols): 기술 회원 목록과 상대 사업자 실사/계약 정책은 별개다. CODE와 GTR의 필수 입력도 다르다.

기존 JDK HttpClient/Jackson/제한 본문 수신기와 CodeVaspTransport를 재사용했다. 신규 의존성은 없다. 공개 저장소 codevasp-lab/ivms101의 한국어 README 구조도 대조했으나 전체 JSON 스키마나 암호 구현을 복사하지 않았다. 전체 IVMS 인증 검증기를 구현했다고 주장하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|공식 Cipher에서 암호화·복호화 | Java의 개인키·수학 구현 부담 감소 | 비공개 모듈/키·망·자원/버전 검증 필요. 모듈 자체의 침해를 이 코드가 해결하지 않음 |
|서명된 원래 본문 유지·공개 필드3개+payload만 허용 | 서명 바이트 보존·잘못된 거래/평문 개인정보 전송 차단 | 공급사 계약 변경은 명시적 재검토 필요 |
|국내 CODE 개인 한 명만 해석 | 현재 고객 업무와 일치·법인/다중 당사자 추측 제거 | 해외/GTR/법인·다중 송신인은 보류해야 함 |
|공개 응답 결합을 복호화보다 먼저 확인 | 다른 요청/자산/금액의 개인정보를 불필요하게 복호화하지 않음 | 정확한 노드 원본과 주소별 금액을 호출 업무가 제공해야 함 |
|읽기/서명 공통 경계 재사용 | 기존 호출도 동일한 시간/크기/JSON 검사 유지 | 서명 준비 코드를 비공개 메서드로 추출. 공개 범용 URL/호출 API 없음 |
|원문 오류/DTO 문자열 비노출 | 이름/생일/주소가 우연히 로그로 노출되는 위험 감소 | 진단은 제한 코드와 요청 ID 중심으로 운영해야 함 |

## 구현과 데이터 흐름

요청은 검증된 거래 요청·정확한8자리 BTC 금액·수취인 전체 이름·선택한 현재 사업자/키를 받는다. KR/CODE/가용 사업자·목록 소속이고 아직 만료되지 않은32바이트 공개키만 허용한다. 이는 실사 통과나 키 등록의 법적 확인이 아니다.

비공개 Cipher에만 수취인 이름을 보내고, 게이트웨이에는 공개 transferId/txid/beneficiaryAddress와 암호문을 보낸다. 선택한 상대 키는 암호화와 X-Code-Req-Remote-PubKey, 복호화에 일관되게 사용한다. 본문을 JSON 값으로 대조한 뒤 서명된 원래 문자열을 그대로 PUT한다. 모듈 왕복 뒤 키 만료도 재검사한다.

정상 응답은 같은 transferId/BTC/정확한 금액이어야 한다. 복호화 결과 body와 bodyString의 JSON 의미가 같아야 하고, 자연인 한 명·법적 이름·송신인 생일·수취 계정 하나의 정확한 일치를 검사한다. DTO의 이름은 현재 KYC와 일치한다고 가정하지 않는다. NORMAL은 파싱된 사실이다.

모든 I/O는 DB TX 밖이다. 단계별 본문까지5초, 연결2초, 서명64KiB/게이트웨이2MiB/복호화256KiB를 제한한다. 미전송 증명은 서명/요청 검사 단계뿐이며 PUT 시작 이후·응답/복호화 실패는 OUTCOME_UNKNOWN이다. 응용 재시도·리다이렉트는 없다. 정상 오류의 공개 사유만 보존하고 reasonMsg는 버린다. 공식 오류 표의 UNKNOWN과 예제의 UNKNOWN_TRANSFER_ID는 각각 명시된 사유로 구분해 허용하며 임의 사유는 거부한다.

## 이해에 필요한 문법과 실제 코드

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L91), 현재 소스의 부분 발췌.

```java
            var signed=sign(command);String raw=text(signed.raw(),"body",65536);var body=json.readTree(raw);
            if(body==null||!body.isObject()||body.size()!=4||!input.query().requestId().toString().equals(text(body,"transferId",36))
                ||!input.query().txid().equals(text(body,"txid",64))||!input.query().beneficiaryAddress().equals(text(body,"beneficiaryAddress",120)))throw unavailable();
            // 추가 평문 개인정보 필드가 생겨도 조용히 전송하지 않는다. 본문에는 공개 결합 필드3개와 암호문만 허용한다.
            for(String field:body.propertyNames())if(!Set.of("transferId","txid","beneficiaryAddress","payload").contains(field))throw unavailable();
            ciphertext(text(body,"payload",65536));
            if(!input.key().expiresAt().isAfter(Instant.now()))throw unavailable();
```

Jackson JsonNode는 TypeScript의 타입 단언과 달리 실제 수신 JSON의 구조를 검사한다. body.size와 허용 필드 검사는 평문 개인정보의 추가 전송도 차단한다. 암호문 형식 검사는 암호 인증 성공을 증명하지 않으며 실제 인증 복호화는 모듈 책임이다.

출처: [CodeVaspTransferDataClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferDataClient.java#L54), 현재 소스의 부분 발췌.

```java
            BigDecimal amount=new BigDecimal(value).setScale(8,RoundingMode.UNNECESSARY);
            if(amount.compareTo(request.expectedAddressAmount())!=0)throw unavailable();
            // 공개 결합 필드가 다르면 개인정보를 복호화하기 전에 중단한다.
            var reply=transport.decryptTransfer(request,response);
            var ivms=reply.identity().path("ivms101");
```

BigDecimal.setScale의 UNNECESSARY는9번째 소수 자리에 실제 값이 있으면 실패한다. 0으로 끝나는 추가 자리는 값 손실 없이8자리로 표현한다. float/double로 금액을 비교하지 않는다. 공개 필드가 다르면 복호화 호출이 없다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L115), 현재 소스의 부분 발췌.

```java
            var identity=decoded.path("body");String original=text(decoded,"bodyString",65536);
            if(!identity.isObject()||!identity.equals(json.readTree(original)))throw unavailable();
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encryptedBytes));
            return new DecodedTransfer(identity,hash);
```

원본 문자열과 모듈이 제공한 객체가 다르면 실패한다. SHA-256은 암호문의 식별용 지문이며 고객 동일인 판정·독립 서명 증명·금융 승인 토큰이 아니다.

## 작성한 테스트

[CodeVaspTransferDataClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransferDataClientTest.java)의17개다. 각 시험이 소유 HTTP 모듈/게이트웨이와 가상 스레드 실행기를 생성·종료한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|signedEncryptedRequestAndBoundPrivateReplyUseFixedContract | 실제 JDK Ed25519 서명으로 원래 본문·헤더·고정 PUT·선택 키 확인, 응답 ID/금액/당사자 해석 |
|cipherCannotChangePublicBindingOrLeakPlainPersonalFields | 다른 ID/거래/주소·평문 추가·불량 암호문·중복/연속 JSON은 게이트웨이 호출0/NOT_SENT |
|publicReplyMustMatchBeforeDecryption | 다른 ID/자산/금액·소수 오차·숫자/지수·평문 추가는 복호화0 |
|documentedErrorReasonsKeepNoRawMessageOrDecryptedData | 문서 사유4종은 제한된 오류로만 반환·원문/개인정보 없음 |
|unknownErrorAndMalformedCiphertextAreUnusable | 미지 사유/불량 암호문은 불명·복호화0 |
|decryptedObjectAndOriginalStringMustAgree | 모듈 원본/객체 불일치·연속 문서·복호화 오류는 불명 |
|requiredNaturalPersonFieldsAndRecipientAccountCannotBeGuessed | 누락/미래/불량 생일·다중/법인·다른/중복 계정·필수 구조 누락 거절 |
|parsedNamesAreFactsAndDoNotPerformCurrentKycApproval | 다른 수취인 이름도 사실로 해석·업무의 현재 KYC 판정과 분리 |
|sensitiveDtosDoNotEchoNamesBirthOrAccounts | 요청/결과/당사자 문자열에 이름·생일·계정 없음 |
|unsupportedPeerOrKeyNeverStartsIo | 외국/GTR/중단/지난 키는 I/O0 |
|invalidIdentityOrSubSatoshiExpectationNeverStartsIo | 빈 이름·제어 문자·사토시 미만은 I/O0 |
|databaseTransactionCannotDisclosePrivateIdentity | 실제 TX 표시가 있으면 외부 호출0 |
|signingBodyDeadlineIsKnownNotSent | 서명 본문 정지는5초 경계/미전송·게이트웨이0 |
|gatewayBodyDeadlineIsUnknownAndDoesNotDecrypt | PUT 본문 정지는 불명·게이트웨이1/복호화0 |
|decryptBodyDeadlineIsUnknownAfterOneGatewayRequest | 복호화 정지는 불명·게이트웨이1 |
|gatewayFailureAndRedirectAreNotRetriedOrFollowed | 오류/302 각각 한 번·따라가지 않음 |
|missingSettingsNeverSendPrivateData | 미설정은 미전송·개인정보 I/O0 |

암호문과 복호화는 계약 대역이다. 실제 X25519/XSalsa20/Poly1305 암호 처리나 공식 Cipher 이미지의 동작을 시험한 결과가 아니다. 실제 요청 서명은 임시 JDK 키로 검증하며 대역 콜백의 실패도 최종 확인한다.

## 실행 명령과 결과

Java21/macOS arm64/소유 PostgreSQL16·두 HTTP 대역. `python3 scripts/verify_codevasp_directory.py` 최종71개(신규17+기존54), 실패/오류/제외0. `bootJar operatorAdminJar` 성공. 소유 PG 제거 확인, XML/manifest/log를 `.local/cycle104-proof`에 보존했다. 기존 deprecated/unchecked/JVM CDS 경고가 남는다. 제품/의존성 스키마 변경 없음. 실행 후 한국어 설명 주석만 보강했다.

`git diff --check`·실제 발췌/상대 링크 대조를 수행한다. BTC 실제 노드/금융182/보안41/자동 검색58/운영 심사27/Rust 전체/Kafka/브라우저는 이번에 재실행하지 않았다. 다른 회차 검증을 현재 합계에 더하지 않는다.

## 남은 업무와 운영 조건

이 클라이언트는 자동 워커나 HTTP 업무에 아직 연결하지 않았다. 현재 KYC·실사/계약된 상대 허용 정책·정보 교환 의도/결과의 암호화 보관·현재 원본 결합·다른 이름/개인지갑/미해결 보류 처리·금융 해제/엔진 반영은 후속이다. API는 거래 ID+수취 주소를 받으며 vout은 받지 않는다. 같은 주소로 여러 출력이 있으면 단일 입금 금액으로 추측해 사용하면 안 된다. 상위에서 명확한 결합 정책이 필요하다.

응답의 금액 등 공개 필드는 게이트웨이 TLS/응답 계약을 신뢰한다. 별도 서명 증명이 문서에 확인되지 않은 응답을 독립적인 거래 부인 방지 증명으로 설명하지 않는다. 원격 historicalCost/isExceedingThreshold는 자체 법정 기준액 판정으로 대체하지 않는다. 국내 CODE 범위이며 GTR/Binance 전체 연동을 완료한 상태가 아니다.

실제 공급사 계약·비공개 Cipher 버전/키/망·Sandbox·LIVE 검증, 개인정보 제공/국외 이전·보존/법적 적용 조건은 외부 확인 상태다.2026-08 개정의 유예 조항을 현재 금액 무관 의무라고 주장하지 않는다. 별도 고객/운영 화면이나 공급사/노드 실제 전송을 이번에 실행하지 않았다.


## 관련 커밋과 증적

- [구현 c9ffae6](https://github.com/GiHoon1123/Cex-Platform-Service/commit/c9ffae6e17c88b5c4370d61d6bc5d08485e56479)
- [격리 실행 증적](../reference/104-codevasp-private-transfer-data-verification-2026-10-25.json)
