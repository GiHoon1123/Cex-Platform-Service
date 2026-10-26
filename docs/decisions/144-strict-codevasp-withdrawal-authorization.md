# 실제 BTC 지급액과 당사자를 결합한 이전 승인 클라이언트

BTC 지급 전 상대 사업자에게 실제 지급액과 당사자 정보를 결합해 이전 승인을 요청하는 클라이언트를 추가했다. 신원 평문은 비공개 암호 모듈에만 전달하고 상대의 verified 응답도 실제 송·수신인/계정과 대조한다. 금융 원장·체인 전송·플랫폼 지급 승인 권한은 만들지 않는다.

## 문제와 기존 동작

기존 공급사 클라이언트는 입금 출처 조회와 정보 교환만 제공했다. 고객이 서명한 주소가 BTC 문법에 맞거나 CodeVASP 목록에 상대가 있다고 실제 수신인·거래 허가가 확인된 것은 아니다. 고객 총 차감액에는0.01% 고객 수수료가 들어가므로 이전 승인에 보낼 실제 지급액과도 구분해야 한다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [CodeVASP 기술 FAQ](https://docs.codevasp.com/en/docs/travel-rule/guides/01-General/05-Technical-FAQ)는 출금 API에서 수신 사업자/수신인 정보를 받고 상대 확인 뒤 온체인 전송하는 흐름을 권고한다. [공식 이전 승인 API](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/07-Asset-Transfer-Authorization)의 고정 POST 경로·UUID·수수료 제외 지급액·암호 응답을 따랐다. [공식 Cipher Core](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/01-Create-Header-Payload-1-Core)의 TRANSFER_AUTHORIZATION과 기존 설치 모듈 연결을 재사용한다. 인증 암호/IVMS 전체를 새로 구현하지 않았다.

[IVMS101 안내](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/04-IVMS101-part1)를 대조해 회신의 Originator/Beneficiary/두 VASP 객체를 확인한다. 현재 개인 한 명·전체 법적 이름 하나·국내 CODE 사업자 경로만 해석한다. 이름 분리·음역·다른 프로토콜/해외·법인을 추측해 통과시키지 않는다.

국내 적용 근거는 [금융위2026-08-11 안내](https://www.fsc.go.kr/po010102/87499)와 최종 법령/부칙을 구분한다. 이 안내는 이전거래 규율의 후속 시행을 공포 후6개월로 설명한다. 발표만으로 전 거래 확대가2026-10-26 이미 시행됐다고 표시하지 않는다. 정확한 시행 법령/적용일은 지급 권한 연결 전에 대조해야 한다. 이 클라이언트는 법정 임계값을 하드코딩하지 않으며 모든 요청에서 회신 당사자를 검사한다. 호출 업무가 현재 정책에 따른 원화 평가/thresholdExceeded를 결정해야 하고 고객 입력을 그대로 전달하면 안 된다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 기존 비공개 Cipher 모듈과 검토된 국내 상대만 사용 | 공급사 암호 규약을 직접 재구현하지 않고 개인정보 공개 상대를 제한 | 모듈·키·실사 정책에 의존. 현재 목록/운영 허용 확인은 실제 업무가 다시 검사해야 함 |
| 실제 지급액·원화 평가·주소/네트워크·UUID를 서명 본문에 결합 | 수수료 포함 총액이나 다른 지급지를 잘못 검증하는 오류 차단 | 모듈이 선택 필드를 보존하는 정확한 계약이 필요하며 실제 배포 버전에서 확인 필요 |
| 공개 필드 먼저 대조, 암호 회신 뒤 당사자 대조 | 다른 거래/원본의 응답으로 개인정보를 열거나 통과시키지 못함 | 불명/형식 변경은 자동 허가가 아닌 보류. 외부 변경을 감시해야 함 |
| 소액 플래그와 무관한 실제 이름/계정 확인 | verified 한 단어·소액이라는 이유의 우회 방지 | 분리 이름/다른 표현은 오탐 보류가 생김. 임의 조합/음역으로 동일인을 추측하지 않음 |
| 미전송과 결과 불명 구분 | 송신 뒤 응답 유실을 새 이전 요청으로 만들지 않음 | 업무의 영속 시도/상태 조회/운영 해소가 필요. 이 클라이언트는 자동 재시도하지 않음 |
| JVM 전역 프록시를 사용하지 않음 | 신원·등록 키·서명·암호문이 전역 프록시에 전달되지 않음 | 공급사 접속은 지정 주소로 직접 가능해야 함. 내부 모듈의 실제 망 격리/권한은 인프라 조건 |

## 구현과 관련 코드·문법

신규 Request는 정확한 BTC8자리 금액, 제한된 원화 값, UUIDv4, 실제 개인 이름/생일·계정, 현재 상대 키를 검사한다. API의 amount는 지급액이며 gross/fee를 받지 않는다. DTO의 toString은 신원을 생략한다. 개인정보 전송 전 기존 CodeVaspCounterpartyPolicy를 다시 확인한다.

기존 입금/신규 출금은 동일한 제한 복호화와 개인 자료 파서를 공유한다. 이전 decryptTransfer 호출을 제거하고 두 업무가 실제로 쓰는 decrypt(envelope,key)로 바꿨다. 호환 메서드/대체 키/실패 폴백을 남기지 않았다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L115), 현재 소스의 부분 발췌.

```java
            var publicFields=json.createObjectNode().put("transferId",input.transferId().toString()).put("currency","BTC")
                .put("amount",input.amount().toPlainString()).put("tradePrice",input.krwValue().toPlainString())
                .put("tradeCurrency","KRW").put("isExceedingThreshold",input.thresholdExceeded())
                .put("address",input.address()).put("network","BTC");
```

현재 소스의 부분 발췌다. BigDecimal의 toPlainString은 지수나 이진 실수로 바꾸지 않는다. 고객 수수료 포함 총액 대신 실제 지급액을 사용하고 모듈이 돌려준 서명 본문의 필드 값이 하나라도 달라지면 게이트웨이 전송 전에 실패한다.

출처: [CodeVaspWithdrawalAuthorizationClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAuthorizationClient.java#L65), 현재 소스의 부분 발췌.

```java
            var decoded=transport.decrypt(response,request.key());var ivms=decoded.identity().path("ivms101");
            if(!ivms.isObject()||!ivms.path("OriginatingVASP").isObject()||!ivms.path("BeneficiaryVASP").isObject())throw unavailable();
            var sender=CodeVaspTransferDataClient.person(ivms.path("Originator"),"originatorPersons",true);
            var recipient=CodeVaspTransferDataClient.person(ivms.path("Beneficiary"),"beneficiaryPersons",false);
            // 소액의 플래그와 관계없이 회신의 실제 이름·원래 계정·지급지를 확인한다. verified 문자열만으로 통과하지 않는다.
```

현재 소스의 부분 발췌다. 검증된 공개 원본 뒤에만 비공개 복호화를 호출한다. 기존 파서가 한 개인·법적 이름·계정 목록을 해석하며 이후 코드가 요청의 이름/생일/계정/수신 주소와 비교한다. 파싱 성공을 지급 승인으로 해석하지 않는다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L140), 현재 소스의 부분 발췌.

```java
            // 게이트웨이 전송 뒤에는 검증 응답을 잃었을 수 있다. 새 이전 UUID로 자동 재요청하지 않는다.
            throw new CodeVaspSubmissionFailure(gatewayStarted?CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN:CodeVaspSubmissionFailure.Admission.NOT_SENT);
```

현재 소스의 부분 발췌다. boolean은 전송 시작 경계의 보수적인 표시다. 공급사가 오류/시간 초과를 반환했어도 이미 검증을 수행했을 수 있으므로 OUTCOME_UNKNOWN으로 보존한다. 예외의 원문/개인정보를 상위 응답에 붙이지 않는다. TypeScript에서도 HTTP await 실패만으로 원격 부작용 미실행을 증명할 수 없다는 점과 같다.

## 작성한 테스트

신규 [CodeVaspWithdrawalAuthorizationClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAuthorizationClientTest.java)20개다. 모두 시험이 소유한 두 HTTP 서버를 사용한다. 모듈 대역의 JDK Ed25519 서명을 게이트웨이 대역이 실제 검증해 서명한 본문/헤더의 바이트가 바뀌지 않음을 확인한다. 공식 모듈의 실제 인증 암호·실제 공급사 승인/계약을 검증한 것은 아니다.

| 함수 묶음 | 검증 시나리오와 기대 결과 |
| --- | --- |
| exactNetAmount | 고객 수수료 제외 실제 지급액·송신인/수신인·주소·현재 상대 키의 정확한 전송/회신 |
| signerCannotChange / malformedSignedBody | UUID/금액/평가/네트워크/주소/플래그 변조, 평문 신원 추가, 짧은 암호문·중복 JSON·만료 서명은 미전송 |
| publicResponse / documentedDenials / unknownDenial | 다른 원본/허용 밖 응답·모순 사유 거절, 문서상8개 거절은 원문/복호화 없이 분류, 미확인 사유는 불명 |
| privateSenderAndBeneficiary / decodedBody | 소액이어도 이름·계정·생일·주소/객체 대조, 복호화 객체와 원래 문자열 불일치 거절 |
| unapprovedCounterparty / missingSettings / unsupportedNetworkPeer | 미검토 상대·미설정·해외/만료 키에서 개인정보 전송 없음 |
| subSatoshi / invalidName | 사토시 미만·과도한 지수/원화 값·잘못된 이름/생일/ID 거절 |
| databaseTransaction | 금융 DB 트랜잭션에서 외부 개인정보 호출 금지 |
| signingBodyDeadline / gatewayBodyDeadline / decryptBodyDeadline | 본문 수신까지 실제5초 제한, 모듈 미전송과 송신 후 불명 구분·자동 반복 없음 |
| gatewayErrorAndRedirect | 오류/리다이렉트 추적·재전송·원문 공개 없음 |
| sensitiveRecords / globalProxy | DTO에서 이름/생일/계정 생략, JVM 전역 프록시의 호출 횟수0 |

전체 함수명은 아래 목록과 테스트 파일을 따른다. 금액 변조 시험에는 고객 총액0.010001 BTC를 실제 지급액0.01 BTC 대신 넣는 경우도 포함한다.

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*CodeVaspWithdrawalAuthorizationClientTest' --tests '*CodeVaspTransferDataClientTest' --tests '*CodeVaspTransactionClientTest' --tests '*CodeVaspDirectoryClientTest' bootJar operatorAdminJar --console=plain
```

신규20·기존 개인정보 교환18·거래 조회13·사업자 목록15, 고유66개/66회 통과·실패/제외0. 고객/운영자 JAR 빌드 통과. 새 의존성·잠금 파일 변경이 없고 외부 공급사·유료 환경·DB·체인·봇은 호출하지 않았다. 기존 Java deprecated API/JVM 클래스 공유 경고는 남는다. 이전 사이클297개나 실제 엔진3개를 이번66개 실행에 합산하지 않는다.

문서의 과거 입금 코드 발췌도 현재 복호화 호출로 맞추며 과거 검증 시점/숫자를 재실행 결과로 바꾸지 않는다.

## 남은 연결과 외부 확인

아직 고객/운영 API에서 이 클라이언트를 호출하지 않는다. 다음 연결은 현재 KYC/CDD·허용된 상대 목록·고객 서명 원본/정확한 지급액·서버 원화 평가로 영속 이전 의도를 만들고 DB 잠금 밖에서 실행·회신 근거를 암호 보존하는 작업이다. 고객이 금액·법정 적용 플래그·이름을 임의 지정해 검토를 우회하게 해서는 안 된다.

이전 승인 자체도 플랫폼 독립 지급 승인·지갑/키 사용 권한이 아니다. 취소/지급의 배타적 결정, 위험 심사, 실제 서명·전송·체인 확정·완료 원장은 후속이다. verified 응답만으로 예약금을 차감하거나 BTC를 전송하지 않는다.

현재 국내 CODE 개인 경로만 구현했다. 해외/다른 프로토콜·개인지갑·분리 이름·법인·사업자 상세 IVMS 항목과 실제 Cipher 배포 버전/키·응답 결합·개인정보 제공/위탁/국외 이전·보유 기간·사업 실사/계약은 확인이 필요하다. 공식 문서의 선택 필드 address/network 보존 및 비어 있는 originatingVasp 처리도 실제 모듈에서 검증해야 한다. 실패를 다른 응답/이름/키로 추측해 통과시키지 않는다.

## 작성한 테스트 코드


[CodeVaspWithdrawalAuthorizationClientTest.java](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAuthorizationClientTest.java)

- `exactNetAmountAndPrivateIdentityAreBoundToTheApprovedCounterparty`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `signerCannotChangeTheWithdrawalOrDisclosePlainNames`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedSignedBodyNeverReachesTheGateway`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `publicResponseMustMatchBeforeOpeningPrivateIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `documentedDenialsDoNotReturnRawMessagesOrDecryptData`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownDenialAndMalformedCiphertextStayUnconfirmed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `privateSenderAndBeneficiaryMustMatchEvenBelowTheThreshold`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `decodedBodyAndOriginalBytesCannotDisagree`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unapprovedCounterpartyNeverReceivesIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingSettingsCannotStartAnAuthorization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unsupportedNetworkPeerOrExpiredKeyCannotConstructARequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `subSatoshiOrUnboundedFiatCannotReachTheModule`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidNameBirthOrIdentifierCannotStartIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseTransactionCannotKeepLocksDuringDisclosure`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `signingBodyDeadlineIsKnownNotSent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `gatewayBodyDeadlineIsUnknownAndNeverRetries`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `decryptBodyDeadlineIsUnknownAfterTheOnlyGatewayRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `gatewayErrorAndRedirectCannotRetryOrLeakResponseText`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sensitiveRecordsDoNotPrintIdentityOrAccount`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `globalProxyCannotObserveModuleOrGatewayIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 f8390fa](https://github.com/GiHoon1123/Cex-Platform-Service/commit/f8390fae57993174620b3041b03a8f95ef5788f0)
- [격리 실행 증적](../reference/144-strict-codevasp-withdrawal-authorization-verification-2026-10-26.json)
