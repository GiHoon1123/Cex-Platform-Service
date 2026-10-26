# 신원 제공 전에 선택한 사업자의 BTC 지급 주소 소속 확인

이름·생일 등 당사자 정보를 보내기 전에 선택한 국내 사업자가 지급 주소를 관리하는지 확인하는 클라이언트를 추가했다. 요청 타입부터 신원 정보를 받지 않고, valid 응답의 사업자 ID가 실제 선택과 일치해야 한다. 주소 소속을 동일인 확인이나 지급 허가로 해석하지 않는다.

## 문제와 기존 동작

139의 Bitcoin Core 주소 검증은 BTC 네트워크·스크립트/정규 표현을 확인한다. 사업자 목록에 있다는 사실도 특정 지급 주소의 소속을 증명하지 않는다. 이 상태에서144의 이전 승인에 이름/생일을 먼저 보내면 잘못 선택한 상대에게 신원 정보를 제공할 수 있다.

## 기존 제품과 공식 자료의 접근

2026-10-26 [CodeVASP 주소 검색 API06](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/06-Virtual-Asset-Address-Search)의 고정 VerifyAddress POST와 valid/invalid·해당 사업자 ID를 대조했다. [Cipher Core의 SEARCH_ADDRESS](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/01-Create-Header-Payload-1-Core)는 주소와 상대 키를 사용하며 이름·생일을 요구하지 않는 경로다. [개발 요청 흐름](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/08-Developing-the-Request-Process)과 함께 개인정보 제공 전 소속 확인 순서를 적용한다.

이미 설치된 비공개 모듈·고정 엔드포인트·현재 상대 키/실사 정책·서명 본문 검증·본문/시간 제한을 재사용했다. 공급사 내부 주소 데이터베이스나 확인 범위를 추측하지 않는다. Bitcoin 주소 자체의 체크섬/네트워크 검사는 기존139의 책임이다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·대안 |
| --- | --- | --- |
| 신원 필드가 없는 별도 요청 타입 | 주소 소속을 모르는 상대에게 이름/생일을 먼저 보내지 않음 | 이전 승인 전에 외부 확인1회가 추가된다. 주소가 공개 자료여도 요청과 서비스 관계 노출·보관 정책은 검토 필요 |
| 현재 운영 허용된 국내 CODE 상대/키 재확인 | 단순히 기술적으로 접속 가능한 상대와 개인정보 교환을 구분 | 실사/정책/키 갱신과 실제 목록의 최신성은 업무가 확보해야 함 |
| valid의 사업자 ID를 정확히 대조 | 다른 사업자 응답을 현재 선택의 근거로 쓰지 않음 | 다른 사업자 추천을 자동 추종하지 않음. 새 선택은 새 업무 검증 대상 |
| 주소/네트워크/통화의 서명 본문 검사 | 모듈이 다른 주소를 서명하거나 평문 개인정보 필드를 추가하면 차단 | 실제 모듈이 선택 필드 address/network를 보존하는 계약은 운영 버전에서 확인해야 함 |
| 미전송/불명/문서상 invalid 분리 | 시간 초과를 주소 미존재로 단정하거나 신원 제공을 진행하지 못함 | 영속 실행/재확인/보류 처리와 운영 조회가 다음 단계에서 필요 |

주소 확인은 수신인의 이름/KYC·고객 동일인·AML 위험·이전 승인·체인 지급을 확정하지 않는다. VALID를 곧바로144의 VERIFIED나 플랫폼 지급 승인으로 바꾸는 코드는 추가하지 않았다.

## 구현과 이해에 필요한 실제 코드

출처: [CodeVaspWithdrawalAddressClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAddressClient.java#L11), 현재 소스의 부분 발췌.

```java
    public record Request(String address,CodeVaspDirectoryClient.Vasp peer,CodeVaspDirectoryClient.Key key) {
        public Request {
            if(address==null||!address.matches("[A-Za-z0-9]{14,120}")||peer==null||!peer.reachable()
                    ||!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())
                    ||peer.entityId()==null||!peer.entityId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    ||key==null||key.publicKey()==null||!CodeVaspSettings.key(key.publicKey(),32)
                    ||key.expiresAt()==null||!key.expiresAt().isAfter(Instant.now())||peer.keys()==null||!peer.keys().contains(key))
                throw new IllegalArgumentException("고정 BTC 지급 주소와 현재 허용된 국내 사업자 키를 확인하세요");
        }
```

현재 소스의 부분 발췌다. 이 Java record에는 이름·생일 입력이 없다. 생성자 검사는 전송 형식/현재 키의 최소 조건이며 법적 실사나 Bitcoin 체크섬 검사를 대신하지 않는다. TypeScript의 객체 타입과 달리 Java의 record 생성자에서도 실제 런타임 입력을 검사한다. 코드에 없는 신원 필드를 자동 직렬화하지 않는다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L114), 현재 소스의 부분 발췌.

```java
            // 이 단계의 타입에는 개인 이름·생일이 없다. 소속을 모르는 상대에게 신원 평문을 먼저 보내지 않는다.
            var request=json.createObjectNode().put("currency","BTC").put("beneficiaryWalletAddress",input.address())
                .put("address",input.address()).put("network","BTC");
            var command=json.createObjectNode().put("allianceName","CODE").put("apiType","SEARCH_ADDRESS")
                .put("remotePublicKey",input.key().publicKey()).set("request",request);
```

현재 소스의 부분 발췌다. 상대 키로 비공개 모듈에 SEARCH_ADDRESS를 요청한다. 반환된 서명 본문은 BTC 통화·주소·네트워크·암호문 네 필드만 허용하고, 원래 문자열을 수정하지 않은 채 고정 경로로 전송한다. 이름이 추가되거나 다른 주소가 들어가면 게이트웨이 전송 전에 실패한다.

출처: [CodeVaspWithdrawalAddressClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAddressClient.java#L39), 현재 소스의 부분 발췌.

```java
            if(result.equals("valid")) {
                String entity=text(response,"beneficiaryVaspEntityId",128);
                if(!entity.equals(request.peer().entityId())||response.hasNonNull("reasonType")
                    &&(!response.path("reasonType").isString()||!response.path("reasonType").asText().isEmpty()))throw unavailable();
                return new Result("VALID",null,entity);
            }
```

현재 소스의 부분 발췌다. 서버의 valid 문자열만 읽지 않고 요청의 정확한 사업자 ID와 일치하는지 검사한다. 문서에 없는 결과/사유, 다른 사업자로 바꾸는 응답과 모순 응답은 불명으로 처리한다. 이 결과에는 고객 자산을 움직이는 권한이 없다.

## 작성한 테스트

신규 [CodeVaspWithdrawalAddressClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAddressClientTest.java)20개다. 시험이 소유한 모듈/게이트웨이 HTTP 서버와 JDK Ed25519 서명을 사용한다. 모든 시험 종료 때 복호화 API 호출0을 확인한다. 실제 공급사 암호 모듈·실제 주소 소유권 데이터의 신뢰성을 검증한 시험은 아니다.

| 함수 묶음 | 시나리오·기대 결과 |
| --- | --- |
| signedMinimal / validWithEmptyReason | 이름/생일 없는 네 필드의 모듈 요청, 원래 서명 바이트·헤더·정확한 사업자 ID 검증 |
| moduleCannotChange / unencryptedOrShort / duplicateOrTrailingSigned / wrongSigningIdentity | 주소·네트워크·통화 변조/개인정보 추가·비정상 암호문·중복 JSON/오래된 서명은 미전송 |
| otherOrMissingEntity / documentedInvalid / invalidReplyCannotSelect | 다른/누락 사업자 거절, 문서상6개 invalid 사유 분류, 다른 상대 자동 선택 없음 |
| unknownReason / contradictoryReason / duplicateResponse | 미확인/모순/중복·추가 개인정보 응답을 성공으로 해석하지 않음 |
| unapprovedPeer / missingSettings / invalidAddressForeignPeer | 미검토 상대·미설정·형식 오류·해외/만료 키에서 외부 전송 없음 |
| databaseTransaction | DB 잠금을 보유한 채 공급사 호출하지 못함 |
| signerBodyDeadline / gatewayBodyDeadline / gatewayFailureAndRedirect | 본문 수신까지 실제5초 제한, 서명 전 미전송과 송신 후 불명 구분, 추적/자동 재시도/복호화 없음 |
| requestAndResponseDiagnostics | 진단 문자열에 지급 주소를 복제하지 않음 |

전체 실제 함수명은 아래 목록과 원본 파일에서 확인한다. 주소 체크섬/Bitcoin Core·금융 원장·고객/운영자 REST는 이 시험의 대상이 아니다.

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*CodeVaspWithdrawalAddressClientTest' --tests '*CodeVaspWithdrawalAuthorizationClientTest' --tests '*CodeVaspTransferDataClientTest' --tests '*CodeVaspTransactionClientTest' --tests '*CodeVaspDirectoryClientTest' bootJar operatorAdminJar --console=plain
```

신규20·기존 출금 승인21·개인정보 교환18·거래 조회13·사업자 목록15, 고유87개/87회 통과·실패/제외0. 고객/운영자 JAR 빌드 통과. 기존 deprecated API/JVM 클래스 공유 경고는 남는다. 외부 공급사/유료 모듈·DB·체인·봇을 호출하지 않았으며 시험 소유 HTTP 서버/실행기를 종료했다. 기존382개/실제 엔진 연결 결과를 이번87개에 다시 합산하지 않는다.

## 다음 연결과 외부 조건

다음은 불변 출금 원본·현재 고객 확인·허용 목록·서버 신청 참고평가에 주소 조회의 영속 의도/시도/관측 결과를 연결하고, 정확한 주소 소속 확인 뒤에만 신원 기반 이전 승인을 실행하는 작업이다. 응답은 독립 서명 증명서가 아니라 해당 공급사 HTTP에서 관측한 결과로 기록해야 한다.

주소 소속 응답에는 원래 주소를 그대로 돌려주는 필드가 없으므로 고정된 요청·선택 상대·실제 전송/응답의 인과관계를 영속 실행 원본에 보존해야 한다. 최신성/중복/취소·현재 고객 제한과의 경합도 업무가 확인한다. 임의 운영 입력의 valid 문자열만 저장해 개인정보 전송 권한으로 사용하는 경로는 만들지 않는다.

국내 CODE 개인 경로만 다뤘다. 다른 프로토콜·해외/개인지갑·실제 모듈 버전/키·실사/이용 조건·개인정보 제공 적합성은 별도다. 현재 컴포넌트는 고객/운영 API에 직접 노출하거나 자동 실행하지 않으며 실제 지급을 완료한 것으로 표시하지 않는다. Rust 코어는 변경하지 않았다.

## 작성한 테스트 코드


[CodeVaspWithdrawalAddressClientTest.java](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspWithdrawalAddressClientTest.java)

- `signedMinimalAddressRequestChecksTheSelectedEntity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `validWithAnEmptyReasonIsStillOnlyAnAddressResult`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `moduleCannotChangeTheBtcDestinationOrAddPrivateFields`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unencryptedOrShortPayloadCannotReachTheGateway`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateOrTrailingSignedJsonCannotReachTheGateway`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongSigningIdentityOrStaleTimestampIsKnownNotSent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `otherOrMissingBeneficiaryEntityCannotVerifyTheDestination`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `documentedInvalidReasonsHaveNoRawMessageOrEntity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidReplyCannotSelectADifferentCounterparty`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unknownReasonAndUnknownResultStayUnconfirmed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `contradictoryReasonOrPrivateReplyFieldCannotPass`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `duplicateAndTrailingResponseJsonStayUnconfirmed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unapprovedButReachablePeerCannotStartDisclosure`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `missingSettingsCannotStartAddressLookup`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidAddressForeignPeerAndExpiredKeyCannotConstructRequests`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseTransactionCannotRetainLocksDuringTheLookup`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `signerBodyDeadlineIsKnownNotSent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `gatewayBodyDeadlineStaysUnknownAfterOneRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `gatewayFailureAndRedirectCannotRetryOrOpenPrivateIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requestAndResponseDiagnosticsDoNotEchoTheWallet`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 2bac656](https://github.com/GiHoon1123/Cex-Platform-Service/commit/2bac656cc6b20ef6bcd69511209091de6f0585b5)
- [격리 실행 증적](../reference/146-codevasp-withdrawal-address-verification-verification-2026-10-26.json)
