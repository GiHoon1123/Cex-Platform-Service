# 고정 Bitcoin Core로 출금 목적지의 주소·네트워크·스크립트를 결합

출금 원본에 실제 지급 주소와 네트워크·스크립트를 고정하기 전에 Bitcoin Core의 판독으로 이를 검증한다. 주소 인코딩/체크섬/스크립트 판독을 직접 구현하지 않고, 같은 입력·정규 주소·스크립트·형식을 결합한다. 노드 RPC도 JVM 전역 프록시에 인증 자료를 보내지 않도록 보완했다. 공통 사이클139다.

## 문제와 기존 동작

기존 BitcoinCoreClient는 거래소의 네이티브 SegWit 수신 주소를 파생한다. 출금 수신인은 거래소가 소유하지 않은 여러 표준 주소를 사용할 수 있어 같은 ismine 조건을 적용하면 안 된다. 단순 문자열/정규식이나 isvalid=true 하나만으로는 잘못된 네트워크·지원하지 않는 향후 형식·다른 지급지로 바뀐 응답을 구분하기 어렵다.

139는 플랫폼 출금 원본/서명에 넣을 주소의 최소 검증 경계다. 고객/운영 인가·자산 예약·수신인/이전거래 심사·실제 지급과 구분한다. 기존 엔진137·플랫폼 통신138의 금융 원본만으로 수신인 확인이 끝났다고 설명하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST 확인한 [Bitcoin Core31.0 validateaddress](https://bitcoincore.org/en/doc/31.0.0/rpc/util/validateaddress/)와 [decodescript](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/decodescript/)의 정규 주소·scriptPubKey·스크립트 종류/주소 출력을 사용했다. 실제31.1 regtest에서 응답과 다섯 형식을 검증했다. 공개31.0 문서와 설치31.1을 구분한다.

추가 주소/키 라이브러리를 설치하지 않았다. 이미 설치·검증한 Bitcoin Core가 체크섬/Bech32·Bech32m/Base58/스크립트를 판독하고 Java는 범위/타입/동일성·지원 정책을 확인한다. 기존 BitcoinRpcClient·LimitedResponseBody·JDK21 HttpClient를 재사용한다. JDK의 명시적 NO_PROXY는138의 실제 설치 타입/문서 확인과 같은 방식이다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 실제 노드 판독 | 암호학적 주소 형식/체크섬을 재구현하지 않음 | 노드 가용성/설정에 의존. 자바 주소 라이브러리 추가나 단순 접두사/정규식 검증 대신 현재 노드 사용 |
| 준비 확인2회+검증/스크립트 판독2회 | 고정 네트워크/관찰 지갑과 주소·스크립트 범위 결합 | 순차 RPC4회. 각 본문5초 제한이고 전체를 단일5초로 보장하지 않음. 최악 약20초 수준과 정리 비용이므로 DB 잠금 밖에서 실행 |
| 다섯 표준 출력만 지원 | P2PKH/P2SH/SegWit v0/Taproot를 실제 검증한 범위로 수용 | 유효한 미래 witness도 현재400. 지원을 넓히려면 실제 노드/서명·지급 시험을 추가 |
| 같은 입력·정규 주소·스크립트 판독 주소 결합 | 다른 유효 주소의 응답으로 지급지가 바뀌는 것 방지 | Base58 대소문자 변경은 허용하지 않음. 전체 대문자 witness만 노드의 같은 정규 주소로 고정 |
| 출금 목적지 DTO와 수신 주소 파생 분리 | 외부 지급지에 거래소 소유 조건을 잘못 적용하지 않음 | 문법 검증만으로 수신인 소유·외부 주소·AML 심사를 보장하지 않으며 후속 업무가 이를 추가해야 함 |
| 전역 프록시 상속 금지 | 기본 인증 자료를 JVM의 임의 전역 프록시에 넘기지 않음 | 노드는 명시된 주소로 직접 접속. 프록시가 필요한 배포는 명시적인 별도 신뢰 경계 설계 필요 |
| 고정된 제한 RPC 계정 | 검증/판독에 필요한 읽기 명령만 추가 | whitelist 운영 갱신 필요. 지갑 생성·키 반입/내보내기·서명·전송 권한은 추가하지 않음 |

## 구현과 경계

입력의 빈 값/공백/제어 문자/비ASCII/과도한 길이는 노드 호출 전에400이다. 현재 DB 트랜잭션이 있으면 호출 전에 거부한다. 입력을 임의로 trim하거나 지급 URI의 주소를 추측 추출하지 않는다.

1. 기존 ready로 고정 chain/동기화·정확한 관찰 지갑/개인키 비활성/descriptor를 확인한다.
2. validateaddress에서 boolean isvalid를 요구한다. false는 현재 네트워크의 주소 거절400, 손상/타입 오류는 출처 확인 불가503이다.
3. 정규 주소와 제한된16진수 스크립트를 읽고 decodescript로 판독한다.
4. pubkeyhash/scripthash/witness_v0_keyhash/witness_v0_scripthash/witness_v1_taproot만 수용한다. 미래/미지원 종류는400이다.
5. 스크립트 판독 주소와 정규 주소를 비교하고, 정규화된 주소가 원래 입력과 같은 지급지인지 비교한다. witness 전체 대문자 표현만 같은 소문자 정규 주소로 수용한다.
6. 고정 네트워크·정규 주소·scriptPubKey·종류를 반환한다. 고객에게 해당 주소 소유권이나 지급 권한을 부여하는 함수가 아니다.

RPC는 validateaddress/decodescript만 기존 제한 계정에 추가했다. 애플리케이션에서 개인키·서명·전송을 요청하지 않는다. 실제 노드 fixture의 키/쿠키 권한은 시험 준비 프로세스가 소유하고 Java에는 제한 사용자만 전달한다. 공유 전송기의 NO_PROXY로 기존 입금 관찰도 전역 프록시를 상속하지 않는다.

## 실제 코드와 이해에 필요한 문법

출처: [BitcoinDestinationClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDestinationClient.java#L22), 현재 소스의 부분 발췌.

```java
        rpc.ready();var decoded=rpc.call("validateaddress",List.of(address));
        if(!decoded.isObject()||!decoded.path("isvalid").isBoolean())throw BitcoinRpcClient.unavailable();
        if(!decoded.path("isvalid").booleanValue())throw invalid();
        String canonical=BitcoinRpcClient.text(decoded,"address"),script=BitcoinRpcClient.text(decoded,"scriptPubKey");
        if(canonical.isEmpty()||canonical.length()>100||canonical.chars().anyMatch(c->c<33||c>126)
            ||script.isEmpty()||script.length()>128||script.length()%2!=0||!script.matches("[0-9a-f]+"))throw BitcoinRpcClient.unavailable();
```

노드가 반환한 실제 boolean을 검사한다. TypeScript의 타입 선언만으로 런타임 JSON이 검증되지 않는 것처럼, Java도 JsonNode 타입과 값을 명시적으로 확인한다. false는 주소 오류, 누락/문자열 true는 출처 오류다.

출처: [BitcoinDestinationClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDestinationClient.java#L29), 현재 소스의 부분 발췌.

```java
        var output=rpc.call("decodescript",List.of(script));
        if(!output.isObject()||!output.path("type").isString())throw BitcoinRpcClient.unavailable();
        String type=output.path("type").stringValue();
        if(!SUPPORTED.contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하는 비트코인 출금 주소 형식을 확인하세요");
        if(!canonical.equals(BitcoinRpcClient.text(output,"address")))throw BitcoinRpcClient.unavailable();
```

Set.of의 불변 지원 목록은 실제 노드로 검증한 종류만 허용한다. 스크립트 접두사를 자바에서 해석하지 않고 Core가 판독한 종류와 같은 주소를 결합한다.

출처: [BitcoinDestinationClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDestinationClient.java#L35), 현재 소스의 부분 발췌.

```java
        boolean same=canonical.equals(address)||(type.startsWith("witness_")&&address.equals(address.toUpperCase(Locale.ROOT))&&canonical.equals(address.toLowerCase(Locale.ROOT)));
        if(!same)throw BitcoinRpcClient.unavailable();
        // 대문자 Bech32 등 유효한 표현은 노드의 정규 주소로 고정한다. 입력 원문을 감사/서명 원본으로 재사용하지 않는다.
        return new Destination(settings.network.id,canonical,script,type);
```

정규화는 다른 지급지 허용이 아니다. Base58은 정확히 같아야 하고, 지원 witness의 전체 대문자 입력만 같은 정규 소문자 주소로 결합한다. Locale.ROOT로 서버 언어 설정에 따라 비교가 달라지지 않게 한다.

출처: [BitcoinJsonRpcClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinJsonRpcClient.java#L22), 156에서 준비·서명 역할이 공유하도록 옮긴 현재 소스의 부분 발췌.

```java
    private final HttpClient http=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public BitcoinJsonRpcClient(URI endpoint,String username,String password,ObjectMapper json){
```

JDK가 관리하는 연결/본문 처리를 사용하되 전역 프록시 선택기를 상속하지 않는다. 새로운 전송 라이브러리나 건별 클라이언트를 생성하지 않는다.

출처: [BitcoinDestinationIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDestinationIntegrationTest.java#L17), 현재 소스의 부분 발췌.

```java
    @Test void fiveActualStandardOutputTypesKeepTheExactNodeScript(){
        var fixtures=JSON.readTree(System.getenv("CEX_BITCOIN_TEST_DESTINATIONS"));assertEquals(5,fixtures.size());Set<String> types=new HashSet<>();
        for(var item:fixtures){var destination=client.validate(item.path("address").stringValue());assertEquals("bitcoin-regtest",destination.network());assertEquals(item.path("address").stringValue(),destination.address());assertEquals(item.path("scriptPubKey").stringValue(),destination.scriptPubKey());assertEquals(item.path("scriptType").stringValue(),destination.scriptType());types.add(destination.scriptType());}
        assertEquals(Set.of("pubkeyhash","scripthash","witness_v0_keyhash","witness_v0_scripthash","witness_v1_taproot"),types);
    }
```

실제 Core가 준비한 다섯 형식의 주소·스크립트·종류를 비교한다. 테스트는 주소별 실제 개인키 소유/서명/지급을 증명하는 것이 아니라 노드 판독과 클라이언트의 결합을 검증한다.

## 작성한 테스트

| 파일/함수 | 시나리오·기대 결과 |
| --- | --- |
| BitcoinDestinationClientTest13개 | 소유 실제 HTTP 서버로 고정 네트워크/지갑·정규 주소/스크립트/형식·대소문자 범위·미지원 형식·다른 지급지/손상 응답·RPC ID/오류 비공개·입력/DB TX의 전송 전 거부·전역 프록시 선택0회를 확인 |
| BitcoinDestinationIntegrationTest.fiveActualStandardOutputTypesKeepTheExactNodeScript | 실제31.1의 P2PKH/P2SH/SegWit v0 keyhash/SegWit v0 scripthash/Taproot의 정확한 스크립트·네트워크·종류 |
| actualUppercaseWitnessAddressesHaveTheSameCanonicalDestination | 실제 전체 대문자 witness 주소가 같은 정규 지급지로 반환 |
| mainnetCorruptMixedCaseAndPaymentUrisAreRejectedByTheCurrentNode | regtest에서 mainnet/체크섬 손상/혼합 대소문자/지급 URI 거부 |
| validFutureWitnessOutputIsExplicitlyUnsupported | Core가 유효한 witness_unknown으로 만든 주소도 현재 미지원400 |
| destinationValidityDoesNotClaimForeignOwnership | 관찰 지갑 주소도 문법상 유효할 수 있으므로 외부 수신인 확인으로 오인하지 않음 |
| 기존 BitcoinCoreIntegrationTest.nodeCredentialsCannotSignExportImportOrTransfer | 제한 계정으로 지갑 생성/키/서명/전송/블록 변경이 계속403 |
| 기존 Node/금융/보안 회귀 | 주소/입금 발견·관찰·진행점·정규/충돌/재조직·보류 원장/대사/조회·지갑 사용 근거와 금융·보안 유지 |

단위 서버의 주소 텍스트는 합성이고 암호학적 체크섬의 증명이 아니다. 다섯 실제 형식과 미래 witness·주소 오류는 실제 Core 시험으로 검증한다. 기존 블록체인 fixture에서 공개 descriptor만 관찰 지갑으로 반입하며 실제 키를 Java나 제품 설정으로 전달하지 않는다.

## 실행 명령과 실제 결과

```sh
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/verify_bitcoin.py --destinations-only
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
```

macOS/JDK21/Gradle8.14.6·실제 Core31.1 regtest/네트워크 연결0·소유 PostgreSQL16. 선택31개로 신규18개와 기존 노드/본문 경계를 먼저 확인한 뒤, 공유 전송의 영향을 검증하기 위해 기존 BTC 전체를 실행했다. 전체16묶음249개·금융217개·보안41개, **고유489개/507회 통과**, 실패·오류·제외0이다. OrderLedgerTest18개가 두 묶음에 있어 고유 수에서 제외했고 초기 선택31개/개발 반복을 합산하지 않았다. 두 제품 JAR·Python 구문·실제 코드 발췌/링크·git diff --check도 확인했다.

.local/cycle139-proof에 묶음별 명령/집계/실제 함수 목록·원시 실행 로그와 해당 대표 XML을 보존했다. 전체 BTC 실행기는 묶음별 XML 집계를 JSON에 기록하며 실제 XML 디렉터리는 다음 선택이 덮어쓰므로 마지막 묶음의 원시 XML과, 별도로 보존한 최초 선택31개의 XML을 구분한다. 없는 전체 그룹별 XML을 있다고 표시하지 않는다. 소유 노드/DB는 종료·제거됐고 기존5개 서비스·봇0을 유지했다.

현재 기본 계정 권한 검증은 실제 제한 계정의403까지 확인했지만 운영 네트워크 방화벽/프록시·키 보관·실제 메인넷 정책/외부 심사까지 검증한 것이 아니다. 기존 deprecated/unchecked/JDK 계측 경고는 남는다. 새 패키지/체인 전송·배포를 추가하지 않았다.

## 다음 원본 연결

주소 유효성은 수신인의 소유권/자전 입출금·수신 사업자/트래블룰·AML·실제 지급 승인이 아니다. 출금 원본은 정규 목적지·네트워크·금액·고객 수수료/총 차감액과 사전 서명 범위를 함께 고정해야 한다.

2026-10-26 사용자는 고정 요율0.1%/0.01% 중 선택을 위임했고 **0.01%**를 선택했다. BTC 고객 수수료는1사토시 올림·총 차감액 사전 확인에 결합할 예정이며, 실제 네트워크 비용과 차이는 거래소 수익/비용으로 관리한다. 이 정책 합의는 본 사이클의 수수료 구현 완료가 아니다. 출금 원본/예약·네 종류 금융 수신·독립 대사·서명/전송과 기존 남은 입금/반환·전체 검토·프론트·마지막 ETH는 계속 후속이다. Rust 코어는 변경하지 않았다.

## 작성한 테스트 코드


[BitcoinDestinationClientTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDestinationClientTest.java)

- `nodeValidatedDestinationKeepsNetworkCanonicalAddressScriptAndType`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `eachSupportedStandardOutputIsAcceptedFromTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `entirelyUppercaseWitnessAddressUsesTheSameCanonicalDestination`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `legacyCaseChangesCannotBeSilentlyNormalized`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedInputNeverCallsTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `activeDatabaseTransactionIsRejectedBeforeTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongNetworkOrPrivateWalletCannotReachAddressValidation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidAddressIsAUserRejectionAndDoesNotDecodeAnyScript`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedValidityAndScriptFailAsSourceErrors`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `differentAddressRepliesCannotRedirectTheOriginalDestination`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownOutputTypeIsNotACompatibilityFallback`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeFailureAndResponseIdMismatchDoNotExposeTheSource`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `globalProxyCannotReceiveTheNodeAuthorization`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinDestinationIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinDestinationIntegrationTest.java)

- `fiveActualStandardOutputTypesKeepTheExactNodeScript`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `actualUppercaseWitnessAddressesHaveTheSameCanonicalDestination`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mainnetCorruptMixedCaseAndPaymentUrisAreRejectedByTheCurrentNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `validFutureWitnessOutputIsExplicitlyUnsupported`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `destinationValidityDoesNotClaimForeignOwnership`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 d1aeb1d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d1aeb1d42c30c7d86fcb38ded99b86aa110de542)
- [격리 실행 증적](../reference/139-bitcoin-withdrawal-destination-validation-verification-2026-10-26.json)
