# 고정0.01% 수수료와 고객 출금 견적 원본·감사를 함께 확정

고객 출금 요율을 고정 **0.01%**로 계산하고, 정규 지급 주소·네트워크·지급액·수수료·총 차감액을 변경할 수 없는 견적으로 남긴다. 고객이 서명할 정확한 내용을 만드는 단계다. **견적 발급은 자산 예약·출금 승인·체인 전송이 아니다.** 공통 사이클140이다.

## 문제와 기존 동작

엔진137의 출금 예약과 플랫폼138의 동작별 통신,139의 실제 주소 검증만으로는 고객이 어떤 주소에 얼마를 보내고 총 얼마를 부담할지 확정할 수 없었다. 고객이 보낸 수수료를 믿거나 금액에 부동소수점을 사용하면 변조·정밀도 손실이 생긴다. 노드 호출 중 계속 고객 행을 잠그면 다른 금융 업무도 외부 응답을 기다린다.

사용자는2026-10-26 고정 비율0.1%와0.01% 중 선택을 위임했다. **0.01%=0.0001=1bp**를 선택했다. 정액 BTC 수수료가 아니며, 거래 체결 수수료 설정을 바꾼 것도 아니다. 고객 수수료와 실제 채굴자 비용은 서로 다른 항목이다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [Stripe의 멱등 요청](https://docs.stripe.com/api/idempotent_requests)은 같은 키의 원본 재사용과 변경된 매개변수 거절을 공개한다. 이번 구현은 이 두 원칙을 적용한다. Stripe의 보존 기간이나 실패 응답 저장 방식까지 동일하다고 주장하지 않는다. 여기서는 실패한 견적을 금융 원본처럼 확정하지 않고, 확정된 동일 키의 만료 견적도 새 견적으로 바꾸지 않는다.

[Bitcoin Core31.0 estimatesmartfee](https://bitcoincore.org/en/doc/31.0.0/rpc/util/estimatesmartfee/)는 가상 크기 기준 BTC/kvB 요율을 다룬다. 출금액의0.01%가 실제 전송 비용이라는 뜻은 아니다. 이 RPC를 이번에 실행하거나 실제 송금을 수행하지 않았다. 주소 판독은139에서 실제 Core31.1로 검증한 기존 라이브러리 경계를 재사용한다.

설치된 JDK21의 [BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html), 기존 ExactAmount·Jackson3·Spring의 세션/금융 자격·JdbcTemplate/TransactionTemplate·PostgreSQL16의 [지연 제약 트리거](https://www.postgresql.org/docs/16/sql-createtrigger.html)를 사용했다. 새 패키지·요율 관리 프레임워크·범용 가격 계산 계층은 추가하지 않았다.0.01%는 이 프로젝트의 승인된 정책이며 법정 요율이나 타 거래소의 실제 요율로 제시하지 않는다.

## 선택과 트레이드오프

| 선택 | 얻는 효과 | 감수하는 비용·검토한 대안 |
| --- | --- | --- |
|0.1% 대신 고정0.01%| 고객이 부담하는 비례 수수료 감소 | 혼잡 시 채굴자 비용보다 수입이 작아질 수 있음. 실제 전송 비용과 차이는 이후 거래소 수익·비용으로 기록해야 하며 현재 견적은 이를 측정하지 않음 |
|1사토시 단위 올림| 표시·원장·엔진의 최종 금액을 정확히 합산 | 아주 작은 지급액의 실효 수수료 비율은0.01%보다 커짐. 예:1사토시 지급 견적의 수수료도1사토시. 이 견적이 먼지 출력의 실제 송금 가능성을 보장하지 않음 |
|코드에 고정 정책 식별자| 고객 입력·동적 설정·승인 상태에 따라 요율이 흔들리지 않음 | 정책 변경은 코드/검증 변경이 필요. 실제 요구 없는 요율 관리 화면/설정 테이블은 만들지 않음 |
|사용자 잠금→노드 밖→다시 잠금| 외부 응답을 기다리는 동안 금융 행 잠금 해제 | 두 번의 짧은 트랜잭션과 현재 버전/자격 재검사 필요. 변경되면409로 새 원본 생성을 거절 |
|2분 원본·동일 키 재조회| 고객 확인 내용이 조용히 갱신되지 않음 | 만료 후 새 확인에는 새 키가 필요. 기존 키는 EXPIRED로 원본을 유지.2분은 자체 보안 정책이며 외부 법정 시간 아님 |
|견적/필수 감사 같은 커밋| 누락되거나 실패한 감사가 있으면 견적도 미확정 | 감사 저장 장애 시 견적 기능도 실패. 감사 실패를 무시하지 않음 |
|미사용 견적도 현 단계에서 변경/삭제 금지| 이후 서명과 연결할 원본의 추적 가능성 | 주소·금액 저장 비용/개인정보 보존 부담. 사용된 금융 원본과 미사용 견적의 보존/승인된 파기는 B19에서 구분할 후속이며 무기한 보존의 적법성을 주장하지 않음 |

## API와 원본 계약

- POST `/api/customer/withdrawals/BTC/quotes`, `Idempotency-Key: UUID`, 본문은 문자열 address/amount 두 필드만 허용한다. 실제 세션의 고객을 사용하며 쿼리 userId·요율·총액·네트워크를 신뢰하지 않는다.
- GET `/api/customer/withdrawals/BTC/quotes/{id}`는 현재 본인 세션만 읽는다. 타인/없는 UUID는404, 운영자는 고객 API를 사용할 수 없다.
- 지급액0.01000000 BTC → 수수료0.00000100 BTC → 총 차감액0.01000100 BTC. 지급액을 줄여 수수료를 몰래 공제하지 않는다.
- 응답에는 지급 주소·스크립트·네트워크·BTC·정확한 금액 세 개·요율/정책·고객 버전·DB 생성/만료 시각·해시가 있다. 세션 ID는 공개하지 않는다.
- state는 PRICED/EXPIRED다. assurance=`QUOTE_ONLY_FUNDS_NOT_RESERVED`다. 공개 SHA-256은 원본 대조용으로, 고객의 전자서명이나 출금 허가가 아니다.
- 같은 고객/키라도 세션이나 입력이 달라지면409다. 동등한 후행0 금액 표기는 같은 입력으로 취급하며 같은 견적에 새 노드 호출을 하지 않는다. 최초 동시 요청은 각자 노드를 읽을 수 있지만 한 원본/생성 감사만 확정한다.
- 계정 비활성/세션 회수는 과거 조회도 차단한다. 거래 제한 고객의 기존 원본 조회와 신규 금융 권한은 구분한다. 새 견적은 최신 KYC/CDD/종료/체인 위험 조건을 확인하고, 실제 출금 승인 때도 다시 확인해야 한다.

## 구현과 실제 코드·문법

출처: [BitcoinWithdrawalAmounts.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalAmounts.java#L35), 현재 소스의 부분 발췌.

```java
        BigDecimal fee = amount.multiply(RATE).setScale(8, RoundingMode.CEILING);
        BigDecimal total = amount.add(fee);
        if (total.compareTo(MAXIMUM) > 0) {
            throw invalid();
        }
        ExactAmount.requirePositive(total, "BTC 총 차감액");
        return new Amounts(amount, fee, total);
    }
```

BigDecimal은 문자열에서 만든 정확한 십진수다. TypeScript의 number 곱셈과 달리 이진 소수 오차가 없다. setScale(8, CEILING)은 양수 수수료를 사토시 단위로 올리며 total은 이 최종 수수료를 더한다. BTC 지급액 자체는 UNNECESSARY로 반올림하지 않는다. 입력은 BTC8자리와 공통 엔진/DB 범위를 함께 통과해야 한다.

출처: [BitcoinWithdrawalQuotes.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalQuotes.java#L43), 현재 소스의 부분 발췌.

```java
            Start start=tx.execute(t->{
                var customer=sessions.require(actor,sid);var old=existing(actor,sid,key,inputHash);if(old!=null)return new Start(customer.getVersion(),old);
                if(!eligibility.permitted(customer))throw denied(HttpStatus.FORBIDDEN);return new Start(customer.getVersion(),null);
            });
            if(start.existing()!=null)return start.existing();
```

첫 트랜잭션은 실제 고객/세션을 잠근 뒤 원본 재조회 또는 현재 금융 자격을 확인한다. record Start는 검사 당시 버전과 기존 원본을 담는 불변 값이다. 노드 판독은 이 콜백이 반환되어 커밋/잠금 해제가 끝난 뒤에 실행된다.

출처: [BitcoinWithdrawalQuotes.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalQuotes.java#L49), 현재 소스의 부분 발췌.

```java
            var destination=destinations.validate(address);
            return tx.execute(t->{
                var customer=sessions.require(actor,sid);var old=existing(actor,sid,key,inputHash);if(old!=null)return old;
                // 노드 판독 사이 계정·고객 확인·위험 상태가 달라졌으면 이전 검사 결과로 새 견적을 만들지 않는다.
                if(customer.getVersion()!=start.version()||!eligibility.permitted(customer))throw denied(HttpStatus.CONFLICT);
                Timestamp at=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class);UUID id=UUID.randomUUID();Instant expires=at.toInstant().plusSeconds(120);
                var payload=new Payload(id,Long.toString(actor),customer.getVersion(),destination.network(),"BTC",destination.address(),destination.scriptPubKey(),destination.scriptType(),
```

두 번째 트랜잭션은 같은 사용자 잠금으로 동시 요청을 직렬화한다. 노드 대기 사이 변경된 계정 버전·거래 제한·고객 확인 조건을 재검사한다. 이전 검사가 통과했다는 이유만으로 새 원본을 확정하지 않는다. SQL이 아니라 기존 TransactionTemplate의 수명으로 경계를 나눈다.

출처: [withdrawal-quote-schema.sql](../../src/main/resources/bitcoin/withdrawal-quote-schema.sql#L9), 현재 소스의 부분 발췌.

```sql
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '2 minutes'),
 created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(user_id,request_key),
 CHECK(fee=ceil(amount*0.0001*100000000)/100000000)
);
```

PostgreSQL의 numeric 열은 서버가 계산한 금액을 보존한다. 총액=지급액+수수료, 수수료=정책 요율의 사토시 올림을 DB에서도 확인한다. UNIQUE(user_id,request_key)는 고객별 멱등 원본을 강제한다.

출처: [withdrawal-quote-schema.sql](../../src/main/resources/bitcoin/withdrawal-quote-schema.sql#L44), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_quote_audit_check AFTER INSERT ON bitcoin_withdrawal_quotes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_quote_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_quote_preserve ON bitcoin_withdrawal_quotes;
CREATE TRIGGER bitcoin_withdrawal_quote_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_withdrawal_quotes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
```

지연 제약은 INSERT 직후가 아니라 커밋 때 필수 감사까지 검사한다. 같은 트랜잭션의 감사 저장 실패나 조용한 생략도 견적을 확정하지 못한다. UPDATE/DELETE/TRUNCATE는 실제 데이터 보존 트리거로 막는다.

출처: [CustomerBitcoinWithdrawalQuoteController.java](../../src/main/java/dustin/cex/domains/bitcoin/CustomerBitcoinWithdrawalQuoteController.java#L26), 현재 소스의 부분 발췌.

```java
        byte[] body=request.getInputStream().readNBytes(2049);if(body.length>2048){audit.denied(userId,"BTC_WITHDRAWAL_QUOTE_REQUEST","customer:"+userId,"INVALID_BODY_SIZE");throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);}
        String address,amount;
        try{var node=json.readTree(body);if(node==null||!node.isObject()||node.size()!=2||!node.path("address").isString()||!node.path("amount").isString())throw new IllegalArgumentException();address=node.path("address").stringValue();amount=node.path("amount").stringValue();}
        catch(RuntimeException invalid){audit.denied(userId,"BTC_WITHDRAWAL_QUOTE_REQUEST","customer:"+userId,"INVALID_BODY");throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"정확한 지급 주소와 금액을 확인하세요");}
```

HTTP 본문은2048바이트로 제한하고 실제 JSON 문자열 두 개만 허용한다. 중복 키·뒤따르는 두 번째 JSON·추가 fee 필드·숫자 amount를 거부하며 원본 본문을 감사에 복사하지 않는다.

### 감사 실패와 권한 거부 구분

개발 검증에서 외부 노드503을 FAILURE로 남기려던 코드가 기존 감사 결과 제약(SUCCESS/DENIED/ALLOWED)에 막혔다. 기존 제약을 확인하지 않은 구현 오류였고 실제 PG가 검출했다. 현재 제약에 FAILURE를 명시적으로 추가하고 전체 보안 회귀를 실행했다. 감사 원본 행이나 불변 트리거를 지우지 않았고, 실패를 ALLOWED/SUCCESS로 바꿔 통과시키지 않았다. 초기화 SQL이 현재 제약을 적용하므로 실제 운영 시작에는 DDL 잠금/변경 절차 검증이 필요하다.

외부 출처 장애는 BTC_WITHDRAWAL_QUOTE_FAILED/FAILURE/SOURCE_UNAVAILABLE로 남는다. 잘못된 고객 입력/권한 거부의 DENIED와 달라 인증 실패 횟수로 오인하지 않는다. 감사 자체가 실패하면 성공으로 숨기지 않는다.

## 테스트 시나리오와 기대 결과

| 테스트 파일/함수 범위 | 시나리오와 기대 결과 |
| --- | --- |
| BitcoinWithdrawalAmountsTest 7개 |0.01% 고정, 올림 앞뒤/1사토시, 후행0, 지수·부호·9자리·상한 거절, 공급량 상한에 수수료 포함, 정수 사토시 식으로 금액+수수료 일치 |
| quoteRecordsTheExactFeeAndCanonicalRecipientWithoutReservingFunds | 정확한 지급액/수수료/총액·주소·스크립트·상태. 원장/잔액/엔진 쓰기0 |
| repeatedRequestKeepsTheSameQuoteAndDoesNotRepeatNodeWork / theSameKeyCannotSwitchAmountOrDestination / theSameKeyCannotMoveToAnotherSession | 동등 금액은 같은 원본/노드1회, 바뀐 금액/주소/세션409 |
| mandatoryQuoteAuditFailureRollsBackTheQuote / silentlyOmittedQuoteAuditCannotCommit | 감사 예외와 대역의 무기록 성공 모두 견적0 |
| invalidKeyAmountAddressAndAmbientTransactionDoNotCallTheNode | 잘못된 키/금액/주소와 외부 트랜잭션은 노드 호출 전에 거절 |
| anotherCustomersAndMissingQuotesAreBothNotFound / operatorForeignRevokedAndMissingSessionsCannotPrepare / unverifiedCustomerCannotReceiveANewQuote | 실제 세션·HTTP·역할·KYC/CDD 경계와 타인404 |
| tradingRestrictionBlocksNewQuotesWithoutHidingThePast / currentAccountDisableAlsoStopsHistoricalAccess | 거래 제한 뒤 신규만 차단, 계정 비활성은 조회도 차단 |
| nodeIoDoesNotHoldTheCustomerRowOrDatabaseTransaction | 노드 대역 안에서 실제 별도 PG 연결의 사용자 FOR UPDATE NOWAIT 성공·현재 트랜잭션 없음 |
| customerVersionChangeDuringNodeReadPreventsTheNewQuote / currentTradingRestrictionDuringNodeReadCannotUseTheEarlierPermission | 노드 판독 사이 실제 계정 버전/거래 제한 변경 시409·견적0 |
| sourceFailureIsNotAUserAuthenticationDenial |503·출처 실패 감사1·인증 거부 감사0·견적0 |
| replayAndReadAuditFailuresDoNotHideTheCommittedOriginal | 필수 조회 감사 실패는 성공 응답하지 않으며 원래 원본은1개 보존 |
| quoteCannotBeEditedDeletedOrTruncated | 실제 PG UPDATE/DELETE/TRUNCATE 차단 |
| aNewPublicHashCannotAuthorizeAWrongFeeOrTotal / extraMissingAndCoercedQuoteFieldsCannotEnterTheRecord / anotherRoleOrNetworkCannotForgeACustomerQuote | 해시를 새로 만든 원본도 잘못된 요율/총액/필드·타입·역할·네트워크는 DB 거절 |
| sourceNetworkCannotSilentlyMixWithTheBoundDatabase | 노드 대역이 다른 네트워크 반환 시 견적0 |
| quoteReadRejectsAlteredMoneyEvenWithARecomputedPublicHash | 조회 대역에서 내용/해시를 함께 변조해도 정책/열 재계산 실패 |
| concurrentSameRequestsKeepOneQuoteAndOneCreationAudit | 실제 동시 요청/PG 잠금에서 원본·생성 감사 각1 |
| customerHttpReturnsOnlyAServerPricedQuote / customerHttpRejectsNumericAmountsAndInjectedFeesUnknownFieldsOrDuplicateBodies / customerHttpRejectsOversizedBodiesAndDoesNotCallTheNode / anonymousAndOperatorCannotReachTheQuoteController | 실제 보안 체인/HTTP 파싱·고객 번호·수수료 주입·본문 크기·미인증/운영자 차단 |
| anExpiredQuoteIsHistoricalAndCannotBeSilentlyRenewed | 실제2분 경과 뒤 EXPIRED·같은 원본/해시·노드 추가 호출0. DB 시각/불변 기록을 조작하지 않음 |

## 실행 명령과 결과

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/verify_bitcoin_withdrawal_quotes.py
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test --tests '*BitcoinWithdrawalAmountsTest' --rerun --console=plain
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
```

macOS/JDK21/Gradle8.14.6/PostgreSQL16. 견적29개는 실행 소유 PG의 메서드별 새 DB에서 실행하고 각각의 실제 XML을 보존했다. 같은 제품 소스에서19개 통과 후 누락 JSON을 만들던 테스트 보조 함수가 DB 도달 전에 실패한 점을 수정하고 나머지10개를 --method로 선택 검증했다. 이 두 실행의 실제 통과 함수29개를 합산하며 중간 실패와 개발 반복은 제외했다. 기본 실행기는29개 전체를 선택한다. 고객 확인·세션·현재 자격·사용자 버전·감사·제약/잠금은 실제 PG/제품 코드이며 목적지 노드 판독은139에서 실제 Core로 별도 검증한 구성요소의 대역이다. 합성 주소를 실제 체인 주소 검증의 증거로 설명하지 않는다. 봇0·외부 소비 자동 시작 금지·127.0.0.1:1 외부 대역 경계를 사용했다.

최종 대표 실행은 수수료7 + 견적29 + 금융217 + 보안41 = **294개 통과**, 실패/오류/제외0이다. 고유 함수 수는294개이며 개발 반복/초기3개 smoke/실패 실행을 합산하지 않았다. 두 제품 JAR와 문서 실제 발췌/링크·Python 구문·차이 검사도 수행했다. 개발 시 테스트의 비공개 해시 함수 접근/모호한 JdbcTemplate 오버로드 컴파일 오류와 감사 결과 제약 실패가 있었으며 이를 최종 통과와 구분해 개발 증적에 남겼다. 기존 deprecated/unchecked/JDK 계측 경고는 남는다.

실행 증적은 .local/cycle140-proof에 명령/실제 함수/각 XML/로그와 함께 보관한다. 테스트 종료 후 소유 PG는 제거하고 기존5개 서비스를 유지했다. 실제 mainnet/출금 원장 예약·Kafka 금융 수신·서명/전송·인프라 배포는 수행하지 않았다.

## 남은 연결과 운영 조건

견적은 부족한 잔액을 예약하지 않는다. 다음 출금 요청은 이 견적의 기한/현재 자격을 다시 검사하고, 목적·고객·세션·원본 ID/해시를 묶은 업무별 일회 패스키 서명과 총액 예약/영속 출력·네 종류 엔진 확정 금융 수신으로 연결한다. 공개 해시만 제출해 지급할 수 있는 경로는 만들지 않았다. 실제 수신인/트래블룰·AML·지갑 소유 경계·최소 지급/먼지·실제 채굴자 비용·수탁 승인/키 사용·불명 전송 복구는 별도 완료 조건이다.

미사용 견적 보존/파기·고객별 및 경계 프록시의 요청량 제한/노드 자원 예산·운영 DDL 절차는 운영 검증이 필요하다. 이번 고정 요율 선택이나 견적 기능으로 규제·인증 또는 출금 전체 완료를 주장하지 않는다. Rust 코어는 이번에 수정하지 않았다. 기존 백엔드 완료→전체 검토/수정→프론트→마지막 ETH 순서를 유지한다.

## 작성한 테스트 코드


[BitcoinWithdrawalAmountsTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalAmountsTest.java)

- `oneBasisPointUsesTheFixedUserChosenRate`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `fractionalSatoshiFeeIsRoundedUpBeforeComputingTheTotal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oneSatoshiQuoteStillDisclosesItsWholeSatoshiFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `equivalentTrailingZeroNotationHasTheSameAmounts`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedPrecisionAndRangeNeverBecomeRoundedPaymentAmounts`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `protocolSupplyBoundIncludesTheFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `manyExactIntegerSatoshiInputsPreserveAmountPlusFee`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinWithdrawalQuoteIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinWithdrawalQuoteIntegrationTest.java)

- `quoteRecordsTheExactFeeAndCanonicalRecipientWithoutReservingFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedRequestKeepsTheSameQuoteAndDoesNotRepeatNodeWork`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `mandatoryQuoteAuditFailureRollsBackTheQuote`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedQuoteAuditCannotCommit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidKeyAmountAddressAndAmbientTransactionDoNotCallTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theSameKeyCannotSwitchAmountOrDestination`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theSameKeyCannotMoveToAnotherSession`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCustomersAndMissingQuotesAreBothNotFound`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `operatorForeignRevokedAndMissingSessionsCannotPrepare`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unverifiedCustomerCannotReceiveANewQuote`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `tradingRestrictionBlocksNewQuotesWithoutHidingThePast`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentAccountDisableAlsoStopsHistoricalAccess`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `nodeIoDoesNotHoldTheCustomerRowOrDatabaseTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerVersionChangeDuringNodeReadPreventsTheNewQuote`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentTradingRestrictionDuringNodeReadCannotUseTheEarlierPermission`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceFailureIsNotAUserAuthenticationDenial`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `replayAndReadAuditFailuresDoNotHideTheCommittedOriginal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `quoteCannotBeEditedDeletedOrTruncated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aNewPublicHashCannotAuthorizeAWrongFeeOrTotal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `extraMissingAndCoercedQuoteFieldsCannotEnterTheRecord`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherRoleOrNetworkCannotForgeACustomerQuote`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `sourceNetworkCannotSilentlyMixWithTheBoundDatabase`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `quoteReadRejectsAlteredMoneyEvenWithARecomputedPublicHash`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentSameRequestsKeepOneQuoteAndOneCreationAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpReturnsOnlyAServerPricedQuote`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpRejectsNumericAmountsAndInjectedFeesUnknownFieldsOrDuplicateBodies`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpRejectsOversizedBodiesAndDoesNotCallTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anonymousAndOperatorCannotReachTheQuoteController`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anExpiredQuoteIsHistoricalAndCannotBeSilentlyRenewed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

수수료 계산/경계 테스트는 [선행 구현 db3c209](https://github.com/GiHoon1123/Cex-Platform-Service/commit/db3c209)에서 독립적으로 추가했다.

## 관련 커밋과 증적

- [구현 4195f7e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/4195f7e3f964768a219c9861bd13e10eb5731207)
- [격리 실행 증적](../reference/140-bitcoin-fixed-fee-withdrawal-quotes-verification-2026-10-26.json)
