# CodeVASP Cipher 계약으로 서명 자료와 사업자 목록 읽기

CodeVASP의 공식 Cipher 요청 계약으로 서명 자료를 받은 뒤 기술 연동 사업자 목록을 조회한다. 플랫폼이 서명 개인키나 자체 암호 구현을 보유하지 않고, 잘못된 자료·응답·시간·크기·리다이렉트를 거부하는 최소 외부 연동 경계를 설명한다.

## 문제와 기존 동작

094까지 운영자는 입금 근거를 조회하지만 이전거래의 상대 사업자 확인을 위한 외부 공급사 경로는 없다. 임의 사업자 목록이나 고객의 자체 신고만으로 상대가 확인됐다고 판단할 수 없으며, 공식 통신 계약 없이 암호를 직접 구현하는 것도 전체 복잡도와 위험을 높인다.

이번 단계는 **사업자 목록 읽기 클라이언트**다. Directory 포함/health up을 국내 신고·인가·고객 신원·자금 출처·이전거래 검증 통과 또는 금융 승인으로 바꾸지 않는다. 공급사 가입/실환경 접속·송수신인 정보 교환·입금 해제는 아직 완료하지 않았다.

## 기존 제품과 공식 자료의 접근

2026-10-25 다음 공개 공식 계약을 확인했다.

- [VASP List Search v1](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/01-VASP-List-Search): GET 사업자 목록, 서명 헤더와 사업자/키 메타데이터.
- [Cipher Core v1](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/01-Create-Header-Payload-1-Core): SEARCH_VASP_LIST 요청으로 서명·nonce·시각·본인 공개키를 받는다.
- [암호 처리 가이드](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/02-Encryption-Decryption): 직접 암호 구현과 공식 Cipher 중 선택 가능하며 서명/개인정보 암호화의 역할을 설명한다.
- [Cipher 배포 가이드](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/11-CodeVASP-Cipher-Server-Module-Guide): 공급사 배포 모듈과 내부 실행/접근 권한 조건. 이번에 실제 이미지를 실행하지 않았다.

이미 설치된 JDK21 HTTP와 Jackson3.1.8의 타입/기존 DiditWebhookVerifier를 확인했다. 중복 JSON 필드/중첩·문자열 제한은 Jackson의 제공 기능을 사용하고, 본문 바이트 제한은 기존 [LimitedResponseBody](../../src/main/java/dustin/cex/shared/http/LimitedResponseBody.java)를 재사용했다. 새 패키지·암호 프레임워크·범용 공급사 추상화는 추가하지 않았다.

[특정금융정보법 시행령 제36592호 개정문/부칙](https://law.go.kr/LSW/lsRvsDocListP.do?chrClsCd=010202&lsId=009256)도 대조했다.2026-08-18 개정의 일부 이전거래 규정은 공포 후6개월 유예가 있으므로 현재 금액 무관 의무로 표시하지 않는다. 이 클라이언트는 적용 금액/심사 의무의 최종 정책 구현이 아니며 CodeVASP 사용 자체가 법에서 정한 특정 제품 도입 의무라는 뜻도 아니다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|공식 Cipher에 서명 위임 | 플랫폼의 개인키/직접 암호 구현을 줄이고 공급사 계약을 그대로 사용 | 실제 도입 시 별도 모듈/자원·키 관리·접근 통제가 필요. 공급사 이미지 접근 권한과 버전 검증은 미완료 |
|Java에 본인 등록 공개키 고정 | 다른 키의 모듈 응답을 외부 호출 전에 차단 | 모듈 키/공급사 등록/앱 공개키 설정을 함께 교체해야 함. 자동 구형 키 폴백 없음 |
|고정 종류의 읽기만 제공 | 고객 원문/임의 API 종류를 서명하게 하는 우회 경로 방지 | 다른 이전거래 API는 공개 계약을 확인하고 별도 업무로 추가해야 함 |
|두 HTTP 단계/단계별 절대5초 | 본문 수신 정지·외부 장애를 무한 대기로 두지 않음 | 두 네트워크 호출의 비용과 외부 모듈/공급사 장애 의존성. 전체 지연 SLA나 처리량 보장 아님 |
|내부 모듈의 새 서명 자료를 매번 사용 | 서명/nonce를 앱 캐시에서 재사용하지 않음 | 목록 갱신에도 모듈 호출 필요. nonce 생성/공급사 재사용 검사는 모듈/공급사 책임 |
|명시적 실패/전체 목록 검증 | 잘못된 일부 원본을 숨기거나 오류를 빈 성공으로 바꾸지 않음 | 한 항목이 잘못돼도 전체 읽기를 실패시켜 운영 확인 필요 |
|중단 사업자·지난 키 보존 | 공급사 상태를 임의 삭제/정상화하지 않음 | 후속 업무가 현재 키/기술 상태/법적·고객 위험을 따로 판단해야 함 |
|노드/엔진/DB TX와 분리 | 주문·매칭 코어에 공급사 대기/공유 락 추가 없음 | 호출자가 작업 임대/재시도·저장/현재성 계약을 이어야 함 |

## 현재 호출과 설정

설정4개를 모두 제공할 때만 사용한다. 모두 비어 있으면 비활성이고 기본 LIVE 주소를 선택하지 않는다. 일부만 제공하거나 주소/등록 공개키가 잘못되면 설정 오류로 시작이 실패한다.

```properties
codevasp.api-url=
codevasp.cipher-url=
codevasp.entity-id=
codevasp.public-key=
```

실제 공급사 계약에서 정한 접속 주소·본인 entity ID/등록 공개키가 필요하다. Java에는 서명 개인키 설정이 없다. 공개키/서명은 공급사가 사용하는 표준 Base6432/64바이트인지 검사하고 본인 공개키는 설정 값과 일치해야 한다. 이 검사는 키의 법적/등록 적합성을 증명하는 것은 아니다.

외부는 HTTPS, 시험용 평문은 명시적 루프백만 허용한다. Cipher는 고정된 `code-cipher` 내부 서비스 이름도 허용하며 이 평문 경로는 비공개 Compose 망에서만 운영해야 한다. URL 형식 검사만으로 DNS/내부 망 격리·컨테이너 권한을 증명하지 않는다. 사용자 입력으로 접속 주소를 바꾸는 API는 없다. 실제 Compose 서비스/포트 공개/이미지 실행은 이번에 추가하지 않았다.

순서는 내부 Cipher POST → 새 서명 자료 검사 → CodeVASP GET → 전체 목록 검증이다. 모듈의 서명 시각은 지난60초/미래10초 이내라는 자체 수신 정책을 적용한다. 공급사 전체 인증 허용 창을 구현했다고 주장하지 않는다. 본문은 Cipher64KiB/목록2MiB, 목록5000/사업자별 키100, JSON 중첩32·문자열65536으로 제한한다. 응용 코드의 재시도/리다이렉트/이전 결과 캐시는 없으며 JDK 내부 전송 계층의 재전송까지 정확히1회라고 보장하지 않는다.

## UTC 키 유효기한

공식 목록 문서는 expiresAt을 UTC로 설명하지만 예제에는 시간대 표시가 없다. 이 필드는 문서 규칙대로 **UTC 하나의 의미**로 해석한다. JDK 포맷터로 시차 없는 표기와 UTC 표기를 처리하며, JVM의 시스템 시간대를 적용하지 않는다. 명시적으로 다른 시차가 붙으면 거부한다. 서명 시각은 별도로 명시적인 UTC 시차가 있어야 한다. 실제 공급사 이미지/계정으로 형식을 확인한 검증은 후속이다.

## 구현과 이해에 필요한 코드

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L64), 현재 소스의 부분 발췌.

```java
    private Signed sign(JsonNode command){
        var signed=call(HttpRequest.newBuilder(settings.cipher.resolve("/api/v1/code/api-payloads"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))),65536);
        String signature=text(signed,"signature",88),key=text(signed,"publicKey",44),date=text(signed,"dateTime",64);
        var nonce=signed.path("nonce");
        if(!CodeVaspSettings.key(signature,64)||!key.equals(settings.publicKey)||!nonce.isIntegralNumber()||!nonce.canConvertToLong()||nonce.longValue()<0)throw unavailable();
        var time=OffsetDateTime.parse(date,DateTimeFormatter.ISO_OFFSET_DATE_TIME);Instant now=Instant.now();
        if(!time.getOffset().equals(ZoneOffset.UTC)||time.toInstant().isBefore(now.minusSeconds(60))||time.toInstant().isAfter(now.plusSeconds(10)))throw unavailable();
        return new Signed(signed,signature,date,nonce.longValue());
```

098에서 목록/거래 검색이 공유하는 전송 경계로 옮긴 현재 코드다. 당시 세 가지 고정 호출을 제공했으며104에서 정보 교환을 추가했다. 현재도 외부 요청에서 임의 API 종류/URL을 받지 않는다. 서명 개인키는 공식 모듈에만 둔다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L74), 현재 소스의 부분 발췌.

```java
    private HttpRequest.Builder headers(String path,Signed signed){
        return HttpRequest.newBuilder(settings.api.resolve(path)).header("X-Code-Req-Datetime",signed.date())
            .header("X-Code-Req-Nonce",Long.toString(signed.nonce())).header("X-Code-Req-PubKey",settings.publicKey)
            .header("X-Code-Req-Signature",signed.signature()).header("X-Request-Origin","code:"+settings.entity);
    }
```

공식 헤더 원문을 유지한다. GET은 본문 없음 계약을 지키며 서명 모듈이 GET에 임의 본문을 반환하면 거절한다. 104에서 서명/헤더의 공통 비공개 메서드를 추출했고 게이트웨이 서명 검증은 공급사 역할이다.

출처: [CodeVaspDirectoryClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryClient.java#L17), 현재 소스의 부분 발췌.

```java
    // 공식 유효기한은 UTC이며 문서 예제에는 시차 표기가 없다. JVM의 로컬 시간대를 추측해 사용하지 않는다.
    private static final DateTimeFormatter KEY_EXPIRY=new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .optionalStart().appendOffsetId().optionalEnd().parseDefaulting(ChronoField.OFFSET_SECONDS,0).toFormatter();
```

JDK 포맷터로 공식 UTC 유효기한을 읽는다. 시차가 없는 문서 예제도 UTC0으로 처리하며 JVM 로컬 시각이나 임의 시간대의 폴백으로 해석하지 않는다.

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L164), 현재 소스의 부분 발췌.

```java
            var pending=http.sendAsync(request.timeout(Duration.ofSeconds(5)).header("Accept","application/json").build(),info->new LimitedResponseBody(limit));
            try{
                // 헤더뿐 아니라 본문 수신까지5초다. 리다이렉트/응용 코드의 재시도/오류 본문 반환을 하지 않는다.
                var response=pending.get(5,TimeUnit.SECONDS);if(response.statusCode()!=200)throw unavailable();
                var value=json.readTree(response.body());if(value==null||!value.isObject())throw unavailable();return value;
            }finally{if(!pending.isDone())pending.cancel(true);}
```

기존 제한 수신기와 전체 응답5초 대기를 공유 전송 경계에서 유지한다. 완료되지 않은 요청은 취소하며 이 대기는 플랫폼 전용 호출자에서 수행한다. 098의 POST도 같은 본문/시간 제한을 재사용한다.

Directory/Vasp/Key는 Java record의 값 묶음이며 목록은 List.copyOf로 고정한다. TypeScript의 타입 단언으로 임의 JSON을 신뢰하는 방식과 다르게 필드 타입/범위를 실제로 검사한 뒤 이 타입을 만든다. 날짜는 Instant, 기술 가용성은 reachable이며 이름에 approved/financiallyAllowed 같은 의미를 넣지 않았다.

## 작성한 테스트

[CodeVaspSettingsTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspSettingsTest.java)4개를 작성했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|absentConfigurationIsDisabledAndHasNoDefaultLiveAddress | 미설정이면 비활성/기본 공급사 주소 없음 |
|explicitHttpsAndPrivateComposeCipherAreAccepted | 명시적 HTTPS/고정 내부 모듈 이름 허용, 설정 문자열 출력에서 주소 제외 |
|partialUnsafeOrUnboundedConfigurationIsRejectedWithoutEcho | 부분 설정·외부 평문·사용자 정보/경로/쿼리·잘못된 포트/헤더 주입형 ID·불량 키 거부, 원문/원인 비노출 |
|publicKeyUsesCanonicalThirtyTwoByteBase64 | 길이/표현이 다른 공개키·패딩 누락 거부 |

[CodeVaspDirectoryClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspDirectoryClientTest.java)15개를 작성했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|officialCipherAndGatewayContractPreservesRealSignatureAndTypedDirectory | 소유 모듈 대역의 서명을 게이트웨이 대역에서 실제 JDK Ed25519로 검증. Z/+00:00 원문 전달·정확한 헤더/빈 GET·타입 결과 |
|eachReadObtainsNewSigningMaterialInsteadOfReusingNonce | 읽기2회마다 새 모듈 자료/nonce, 앱 서명 재사용 없음 |
|unavailableCounterpartyAndExpiredKeyArePreservedWithoutApprovingThem | down 사업자/지난 키 원본을 지우거나 정상화하지 않음 |
|emptySuccessfulDirectoryIsDifferentFromFailedCall | 정상200의 빈 목록과 외부 실패를 구분 |
|invalidCipherMaterialNeverReachesGateway | 다른 본인 키·낡은/미래/비UTC 시각·문자열/소수/음수 nonce·불량 서명·GET 본문·중복 필드는 게이트웨이 호출0 |
|alteredButWellFormedSignatureIsRejectedByGateway | 형식만 맞는 변조 서명은 대역 공급사401, 성공으로 해석하지 않음 |
|malformedDirectoryNeverBecomesPartialOrEmptySuccess | 누락 국가·알 수 없는 health·불량 키/시차·중복 키/사업자/JSON 필드이면 전체 실패 |
|redirectsAreNotFollowedAcrossSigningAndGatewayBoundaries | 두 단계302를 따라가지 않음 |
|gatewayErrorsHaveNoApplicationRetryOrRawBodyDisclosure |429에 응용 재시도 없음, 민감한 오류 원문/원인 제외 |
|cipherAndGatewayBodiesAreBounded | 정상 필드/작은 문자열 배열을 가진 유효 JSON을64KiB/2MiB 밖으로 만들어 크기 제한 검증. 단순 JSON 파싱 실패와 혼동하지 않음 |
|stalledCipherBodyHasAbsoluteDeadline | 모듈 헤더 뒤 본문 정지는 실제4~8초 안에 실패, 공급사 호출0 |
|stalledGatewayBodyHasAbsoluteDeadline | 공급사 본문 정지는 실제4~8초 안에 실패 |
|ambientDatabaseTransactionCannotPerformProviderIo | DB TX 안에서는 모듈/게이트웨이 호출0 |
|absentSettingsNeverCallAnyDefaultProvider | 미설정은 외부/기본 공급사 호출0 |
|documentedUtcExpiryWithoutOffsetDoesNotUseJvmLocalTimezone | JVM을 Asia/Seoul로 바꿔도 시차 없는 문서 예제를UTC로 읽고 시험 뒤 원래 설정 복원 |

두 HTTP 서버와 가상 스레드 실행기는 각 시험이 소유/종료한다. 시험 키는 JDK가 생성한 임시 Ed25519 키다. 실제 공급사의 개인키·가입 계정·신원 데이터·공식 Cipher 이미지를 사용한 시험이 아니다. 모든 대역의 콜백 오류도 시험 종료에 확인해 잘못된 요청 때문에 우연히 실패한 경우를 통과로 숨기지 않는다.

## 실행 명령과 결과

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew compileJava --console=plain
./gradlew test --tests '*CodeVasp*Test' --rerun-tasks bootJar operatorAdminJar --console=plain
python3 scripts/verify_platform_security.py
```

보안 회귀는 상위 호출에서 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_/CODEVASP_ 값을 제거하고 SPRING_KAFKA_LISTENER_AUTO_STARTUP=false를 고정했다. UTC 문서 예제와 유효한 대형 JSON 회귀를 보강한 최종 소스의 신규19개를 다시 실행했다. 중간 선택 실행을 최종 합계에 반복 합산하지 않는다.

macOS arm64/Temurin21/소유 HTTP 대역·PG16. 신규19개와 기존 보안41개, **고유60개 통과**, 실패/오류/제외0개다. 일반 Spring 기동에서 미설정 클라이언트의 구성과 기존 보안 동작을 검증했다. 두 제품 JAR 빌드/신규 시험 제외·소유 PG 제거·대역 서버/실행기 종료·봇0·소비자 자동 시작 금지를 확인했다. 의존성 잠금 파일 변경은 없다.

기존 deprecated/unchecked/JVM 계측 안내가 남는다. 실제 Cipher 이미지/CodeVASP Sandbox·LIVE·공급사 계약/신고 적합성·송수신인 정보 교환은 검증하지 않았다. 새 BTC/금융/Rust 전체 회귀·프론트·성능/OSV 감사도 이번에 다시 실행하지 않았다.094의 BTC221개·보안41개,093의 금융182개와092 Rust158/Python5개는 각각 당시 기록이다.

## 실제 도입 조건과 남은 연결

공식 가이드상 Cipher는 별도 Docker 접근 권한과 키 설정이 필요하다. 공개 배포 예제의 latest를 그대로 운영하지 않고 실제 승인받은 버전/다이제스트·보안 공지·호환 계약을 확인해야 한다. 가이드의 최소2G/권장4G 메모리(가이드 표기) 등 자원은 공급사 문서 조건이며 이 프로젝트의 실측 사용량이 아니다. 같은 호스트라도 내부 모듈 망/포트/권한·키 보관/백업·시각 동기화는 운영에서 검증해야 한다. 이번에는 로그인/이미지 내려받기·실행/배포·메시지 전송을 하지 않았다.

다음은 기술 목록의 불변 보관/현재성·실패 기록과 실제 상대 정보 확인 연결이다. 그 뒤에도 지갑/거래 귀속, 고객 확인·송수신인 정보/이전거래 검증·외부 장애 보류, 내용 결합 금융 승인·엔진 HTTP/WAL/Kafka 반영은 별도로 완성해야 한다. 기술 연동 사업자 목록을 법적 신고/허가 목록이나 고객 신원 검증 결과로 대신 사용하지 않는다.


## 관련 커밋과 증적

- [구현 cf4273a](https://github.com/GiHoon1123/Cex-Platform-Service/commit/cf4273ac326065fe4bbb24a0d0950db324882181)
- [격리 실행 증적](../reference/095-codevasp-cipher-directory-client-verification-2026-10-25.json)

## 후속 구현 시점

[097](097-automatic-codevasp-directory.md)에서 전용 자동 갱신 워커를 연결했다. 위의 자동 갱신 미구현 설명은 이 문서의 원래 구현 시점이며, 현재 일반 앱은 설정 완료/활성 시 주기 갱신한다. test 프로필은 자동 등록을 제외한다. 실제 공급사 확인·송수신인 교환·금융 승인은 여전히 별도 미완료다.

## 전송 모듈 분리 시점

098에서 실제 거래 출처 검색을 추가하면서 공통 서명/HTTP 경계를 CodeVaspTransport로 분리했다. 위 네 발췌는 현재 소스에 맞췄으며095 당시 원본/검증은 연결된 구현 커밋과 증적이다. 디렉터리 파싱과 네트워크 수명은 별도 빈이며, 공통 실패 안내도 원문을 포함하지 않는 공급사 요청 실패로 통일했다.

## 정보 교환 추가 시점

[104](104-codevasp-private-transfer-data.md)에서 암호화 정보 교환 클라이언트를 추가했다. 위 서명/헤더 발췌는 추출한 현재 메서드다.095의 검증·실제 공급사 미확인 상태는 당시 기록이며 정보 교환 업무 승인/자동 반영은 여전히 미완료다.
