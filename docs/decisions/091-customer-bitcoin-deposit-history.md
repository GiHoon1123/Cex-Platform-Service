# 본인 BTC 입금 목록과 보류 진행 내역 조회

고객이 플랫폼의 목록·개별 API에서 본인 BTC 입금 원본과 마지막 체인 관찰, 보류 원장 반입 상태를 확인한다. 다른 고객의 입금·내부 지갑 설정을 노출하지 않고 체인 확정과 거래 가능한 잔액을 구분한다.

## 문제와 기존 동작

기존 고객 API는 입금 주소 발급/조회까지 제공했다. 실제 감지/보류 원장 반입이 있어도 고객이 자기 입금의 진행 내역을 조회하는 경로가 없었다. 주소 신규 발급의 고객 확인 조건을 과거 내역 조회에 그대로 적용하면 고객 확인 만료나 거래 제한 중 자기 자산 기록까지 숨기게 된다.

이번 변경은 조회만 제공한다. 자산을 사용 가능으로 해제하거나 심사/입금/엔진 명령을 실행하지 않는다. 재조직 때 과거 보류 기록을 삭제하지도 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 확인한 업비트의 [입금 목록](https://docs.upbit.com/kr/reference/list-deposits)과 [개별 입금](https://docs.upbit.com/kr/reference/get-deposit)은 인증된 조회 권한과 입금 식별자/네트워크/금액/처리 상태를 다룬다. 여기서는 고객 세션과 본인 범위의 목록·UUID 개별 조회 패턴을 채택한다. 업비트 Open API의 키 인증·모든 필터·내부 상태 전이를 복제하지 않는다.

이미 있는 CustomerRecoveryHistory/CustomerEmailChangeHistory의 현재 세션 재검사, 시각+UUID 커서,25건+추가1건, 필수 조회 감사 패턴을 사용한다. 설치된 Spring JDBC/트랜잭션/시큐리티와 기존 bitcoin_receipt_customer 인덱스를 활용하며 의존성을 추가하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
| 플랫폼 고객 세션과 서비스 직접 호출에서 재검사 | 필터 이후 회수/타 세션 사용·운영자 우회 차단 | 짧은 사용자 행 잠금과 필수 감사 쓰기로 읽기도 DB 비용/경합이 있음 |
| 현재 고객 확인과 거래 제한을 조회 조건에 넣지 않음 | 거래 제한 중에도 본인 과거 자산 사실 확인 | 유효한 고객 계정/세션·등록한 경우 다중 인증 수준은 계속 필요. 사용 중지 계정 우회 기능은 아님 |
|25건 고정·시각/UUID 커서 | 무제한 조회/큰 OFFSET을 피하고 같은 시각의 누락 방지 | 전체 건수·페이지 번호·임의 정렬/필터 없음. 페이지 사이 새 관찰/반입 상태는 달라질 수 있음 |
| 금액8자리 문자열 | 브라우저 Number의 정밀도 손실 없이 표시 | 프론트에서 계산 시 십진 라이브러리/문자열 계약을 따라야 함 |
| 관찰 상태와 보류 반입 별도 필드 | CONFIRMED를 금융 완료로 오인하지 않음 | 화면은 두 개념과 관찰 시각/최신 여부를 함께 설명해야 함 |
| 타인/없는 UUID 모두404 | 존재 여부를 이용한 타 고객 정보 추측 축소 | 고객은 두 경우를 구분할 수 없음 |
| 노드/엔진 실시간 호출 없음 | 고객 조회 트래픽이 체인/매칭 코어를 직접 기다리게 하지 않음 | 저장된 마지막 관찰이며 실시간 체인 최종성을 보장하지 않음 |

## API 계약

- `GET /api/customer/deposits/BTC`: 최신25건. 다음 커서가 있으면 `afterAt=<next.detectedAt>&afterId=<next.id>`를 함께 보낸다.
- `GET /api/customer/deposits/BTC/{id}`: 본인·현재 설정 네트워크의 입금 한 건.
- 잘못된 UUID/커서400, 타인 또는 없는 입금404, 역할/현재 세션 조건 위반은 공통 보안 정책을 따른다. 익명401·운영자403·회수된 HTTP 세션401을 실제 검증했다.
- 성공 응답은 기존 공통 보안 헤더로 `Cache-Control: no-store`다.
- 고객 번호·네트워크·노드 접속 주소를 요청자가 선택하지 않는다. 임의 userId 쿼리는 소유 범위를 바꾸지 않는다.

| 필드 | 의미 |
| --- | --- |
| id/txid/vout | 안정적인 입금 UUID와 체인 출력 식별자 |
| asset/network/amount | 자산·명시적 네트워크·8자리 십진 문자열 |
| detectedAt | 플랫폼 입금 원본 생성 시점. 체인 블록 시간과 다름 |
| chainStatus | 저장된 현재 관찰의 UNCONFIRMED/CONFIRMED/CONFLICTED, 미관찰은 UNKNOWN |
| confirmations/observedAt | 마지막 관찰의 확인 수/읽기 시작 시각. 미관찰은 null |
| observationFresh | DB 시각 기준 과거2분 이내/미래가 아닌 읽기인지. 체인의 절대 최종성이나 업무 승인 아님 |
| fundingStatus | 보류 원장 반입 연결이 있으면 PENDING, 없으면 UNRECOGNIZED. 사용 가능/완료 상태를 구현했다고 표시하지 않음 |

RPC 지갑 이름·지출 조건·원장 키·고객 번호·세션·심사 근거·개인정보는 응답에 없다. 제재/AML 상세 사유를 고객 DTO에 복사하지 않는다. 기존 보류 반입 뒤 체인이 바뀌면 보류 사실과 새 체인 관찰이 함께 표시되며 자동 금융 수정은 하지 않는다.

## 구현과 이해에 필요한 코드

출처: [CustomerBitcoinDeposits.java](../../src/main/java/dustin/cex/domains/bitcoin/CustomerBitcoinDeposits.java#L41), 현재 소스의 부분 발췌.

```java
    public Page own(long actor,UUID sid,Cursor after){return attempt(actor,()->{
        sessions.require(actor,sid);validate(after);
        // 주소 신규 발급의 고객 확인 조건과 달리 과거 사실 조회는 거래 제한/고객 확인 만료로 숨기지 않는다.
        String order=" ORDER BY r.recorded_at DESC,r.id DESC LIMIT 26";
        var rows=after==null?jdbc.queryForList(SELECT+order,actor,settings.network.id):jdbc.queryForList(SELECT+" AND (r.recorded_at,r.id)<(?,?)"+order,actor,settings.network.id,Timestamp.from(after.detectedAt()),after.id());
        var items=rows.stream().limit(25).map(CustomerBitcoinDeposits::deposit).toList();var last=items.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_BTC_DEPOSITS_READ","customer:"+actor,"SUCCESS","OWN_RECEIPTS_ONLY");
        return new Page(items,rows.size()>25?new Cursor(last.detectedAt(),last.id()):null);
    });}
    public Deposit detail(long actor,UUID sid,UUID id){return attempt(actor,()->{
```

Java Supplier<T>는 값을 반환하는 콜백으로 TypeScript의 () => T와 비슷하다. attempt가 짧은 TX를 열고 실제 세션을 다시 확인한다. 더 읽은1건은 응답에서 제외하고 다음 커서 유무에만 사용한다. 시각이 같아도 UUID까지 정렬하므로 중복/누락을 막는다.

출처: [CustomerBitcoinDeposits.java](../../src/main/java/dustin/cex/domains/bitcoin/CustomerBitcoinDeposits.java#L52), 현재 소스의 부분 발췌.

```java
        // 존재 여부를 먼저 조회하지 않는다. 타인의 UUID와 없는 UUID는 같은404로 처리한다.
        var rows=jdbc.queryForList(SELECT+" AND r.id=?",actor,settings.network.id,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"본인 입금 내역이 없습니다");
        var result=deposit(rows.getFirst());audit.record(actor,"CUSTOMER_BTC_DEPOSIT_READ","bitcoin-deposit:"+id,"SUCCESS","OWN_RECEIPT_ONLY");return result;
    });}
```

기본 SELECT 자체에 사용자와 네트워크 조건이 있다. 타인 원본을 먼저 읽은 뒤403으로 바꾸지 않고 같은 쿼리에서 범위를 제한한다. 감사 기록이 실패하면 성공 응답을 반환하지 않는다.

출처: [CustomerBitcoinDeposits.java](../../src/main/java/dustin/cex/domains/bitcoin/CustomerBitcoinDeposits.java#L61), 현재 소스의 부분 발췌.

```java
    private static Deposit deposit(Map<String,Object> row){
        Timestamp observed=(Timestamp)row.get("read_started_at");Number confirmations=(Number)row.get("confirmations");
        // JSON 숫자를 브라우저의 이진 부동소수점으로 바꾸지 않도록 금액은8자리 십진 문자열로 공개한다.
        return new Deposit((UUID)row.get("id"),(String)row.get("asset"),(String)row.get("network"),(String)row.get("txid"),((Number)row.get("vout")).intValue(),
            ((BigDecimal)row.get("amount")).setScale(8,RoundingMode.UNNECESSARY).toPlainString(),((Timestamp)row.get("recorded_at")).toInstant(),
            row.get("status")==null?"UNKNOWN":(String)row.get("status"),confirmations==null?null:confirmations.longValue(),observed==null?null:observed.toInstant(),Boolean.TRUE.equals(row.get("fresh")),(String)row.get("funding_status"));
    }
    private static void validate(Cursor after){if(after!=null&&(after.id()==null||after.detectedAt()==null||after.detectedAt().isBefore(Instant.EPOCH)||after.detectedAt().isAfter(Instant.now())))throw badCursor();}
```

Long과 Instant는 미관찰에서 null일 수 있다. Number.longValue는 DB의 정수 확인 수에만 사용한다. 금액은 BigDecimal.setScale(8,UNNECESSARY)로 정확한8자리 문자열을 만든다. 버림/반올림이 필요한 값이면 숨기지 않고 실패한다. TypeScript는 이 문자열을 Number로 강제 변환하면 정밀도를 잃을 수 있다.

출처: [SecurityConfiguration.java](../../src/main/java/dustin/cex/domains/security/SecurityConfiguration.java#L40), 현재 소스의 부분 발췌.

```java
                .requestMatchers(HttpMethod.GET,"/api/customer/deposits/BTC","/api/customer/deposits/BTC/*").access(audited(audit,sessions,"CUSTOMER"))
```

목록/개별 GET에 고객 역할만 허용한다. 이 필터와 별개로 서비스는 현재 영속 세션과 등록된 다중 인증 수준을 다시 확인한다. 미지원 자산/다른 HTTP 메서드를 열지 않는다.

## 작성한 테스트

[CustomerBitcoinDepositsIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerBitcoinDepositsIntegrationTest.java)의13개를 작성했다. 실제 Spring/HTTP 필터·JWT/세션·PG·필수 감사와 보류 원장 서비스를 실행한다. 고객 이력 조회 fixture의 블록/출력/관찰은 합성 원본이다. 부모 원본부터 정상 제약 안에서 넣으며 금융 이력 삭제/불변 트리거 해제를 하지 않는다. 이 시험을 실제 체인 확정/메인넷 검증이라고 주장하지 않는다. 이전 실제 Core 수신→보류 검증은 BTC 회귀에서 별도로 실행한다.

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
| emptyHistoryCreatesNoAddressOrReceipt | 빈 목록/다음 커서 없음, 주소 생성0 |
| equalTimestampCursorHasNoOmissionsOrDuplicates | 같은 시각29건을25+4건으로 누락/중복 없이 순회 |
| exactlyTwentyFiveHasNoEmptyNextPage | 정확히25건이면 불필요한 빈 다음 페이지 없음 |
| httpProvidesExactAmountAndMinimumOwnFactsWithoutInternalIdentifiers | 정확한8자리 문자열·상태/no-store, 지갑/지출 조건/사용자/세션 비노출·userId 입력 무효 |
| foreignAndMissingIdsBothReturn404AndForeignFactsNeverEnterList | 타인 목록 배제·타인/없는 UUID 서비스와 HTTP 모두404 |
| anonymousAndOperatorAreDeniedAndForeignOrRevokedSessionCannotBypassService | 익명401/운영자403, 타 세션·회수 세션 직접 호출 차단 |
| tradingRestrictionAndMissingVerifiedIdentityDoNotHidePastReceipt | 거래 제한/미확인 신원 중에도 유효 본인 세션으로 과거 기록 조회 |
| lastObservationIsTimestampedAndStaleEvidenceIsExplicit | 마지막 관찰6회/시각 표시,3분 지난 관찰은 최신false |
| recognizedPendingIsNotAdvertisedAsAvailableBalance | 실제 보류 원장 반입 후 PENDING·금액 유지, 사용 가능 잔액 행0 |
| invalidCursorNullDetailAndAmbientTransactionAreRejected | 불완전/과거 범위/미래 커서·null·외부 TX 거부, HTTP 잘못된 UUID400·미지원 자산403 |
| mandatoryReadAuditFailurePreventsSuccessfulResponse | 목록/개별 필수 감사 실패 시 성공 반환 금지 |
| anotherNetworkOfSameCustomerIsNotMixedIntoCurrentHistory | 현재 다른 네트워크의 조회에서 기존 체인 기록 목록 배제/개별404 |
| enrolledCustomerWithoutVerifiedMfaCannotDowngradeToPasswordOnlyHistory | 등록 사실만 남고 현재 다중 인증이 없는 세션은 직접 호출/HTTP403 |

각 시험 종료 시 reader/engine 호출0을 확인한다. 신규 [독립 실행기](../../scripts/verify_customer_deposits.py)는 소유 PG를 만들고 응답할 수 없는 루프백 RPC 주소를 사용한다. 따라서 조회 성공이 실행 중인 비트코인 노드에 의존하지 않음을 별도로 확인한다. 설치 의존성은 기존 Python 표준 라이브러리/Docker/Gradle이다.

## 실행 명령과 결과

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew testClasses --console=plain
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_customer_deposits.py
python3 scripts/verify_platform_security.py
```

마지막 명령의 상위 호출은 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 환경을 제거하고 SPRING_KAFKA_LISTENER_AUTO_STARTUP=false를 고정했다. 첫 컴파일 시 로그를 공통 루트가 아닌 그 부모에 지정한 셸 리디렉션 오류가 있었으며 Gradle 실행 전 실패했다. 절대 로그 경로로 고친 뒤 컴파일 성공했다.

macOS arm64/Temurin21/PG16/Core31.1. BTC 통합 실행199개·노드 없이 조회13개·보안 회귀41개가 통과했다. BTC 실행 중 마지막 다중 인증 회귀를 추가했으므로 첫 묶음에는 당시 조회12개만 포함됐고, 최종 소스의 조회13개를 독립 PG에서 다시 실행했다. 중복12개를 클래스/함수명으로 대조한 **253회 실행/고유241개**, 실패/오류/제외0개다. 숫자를 최종13개를 포함한 BTC200개로 바꿔 쓰지 않았다.

소유 노드 종료/PG 제거·봇0·소비자 자동 시작 금지·외부 엔진/Kafka 주소 격리·두 제품 JAR 빌드/시험 제외를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다.090의 금융182개는 직전 기록이며 이번 새 실행 수치에 재사용하지 않는다. 브라우저/메인넷/성능/새 OSV 감사는 실행하지 않았다.

## 남은 연결

프론트는 이 API의 체인 관찰과 보류 상태를 구분해 표시해야 한다. 아직 사용 가능 해제·고객/거래 위험 승인·엔진 반입·재조직 보상·입금 심사/반환 상태는 완성되지 않았다. 현재의 UNRECOGNIZED/PENDING을 거래 완료로 표시하지 않는다. 마지막 관찰과 이후의 체인은 달라질 수 있으므로 고객 조회 자체를 금융 승인 근거로 사용하지 않는다.


## 관련 커밋과 증적

- [구현 79d89ca](https://github.com/GiHoon1123/Cex-Platform-Service/commit/79d89cab3775a3f88d2e76ff4065cf45853d17e8)
- [격리 실행 증적](../reference/091-customer-bitcoin-deposit-history-verification-2026-10-25.json)

093 현행 변경: [플랫폼 DB 네트워크 결합](093-bitcoin-database-network-identity.md)으로 같은 DB에 두 체인 원본을 함께 넣을 수 없게 했다. 현재 네트워크 조회 회귀는 regtest 원본을 다른 네트워크 설정의 조회 서비스가 읽지 못하는지 확인한다.091 당시의 두 체인 합성 원본/실행 증적은 당시 범위로 보존한다.
