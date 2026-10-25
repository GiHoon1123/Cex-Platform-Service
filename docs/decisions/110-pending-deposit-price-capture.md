# BTC 보류 입금과 당시 평가·미평가 사실을 함께 보존하기

보류 원장에 실제 BTC 수량을 기록하는 순간의 원화 참고가격을 함께 보존한다. 가격 부재·인식 중 만료도 원본 사실로 남기며, 이후 받은 가격으로 과거 입금을 다시 평가하지 않는다. 이 기록은 내부 참고 평가이며 트래블룰 면제·개인정보 제공·자금 사용 승인이 아니다.

후속 [116 거래 기반 모니터링](116-durable-native-deposit-monitoring.md)은 실제 보류 원장과 당시 평가를 처리 예약에 결합하고, 별도 워커에서 단일 규칙의 검토 근거를 남긴다. 금융 허가는 아니다.

## 문제와 기존 동작

109는 가격 원본의 시각과 실패를 보존했지만 어느 입금에 어떤 가격을 사용했는지는 연결하지 않았다. 업무 처리 시마다 현재가를 다시 읽으면 같은 입금의 환산액이 달라지고, 가격 장애 때문에 실제 입금 수량까지 기록하지 못하면 수탁 자산과 원장이 어긋날 수 있다.

이번 변경은 기존 `BitcoinPendingDepositService`의 수탁 보류 인식 트랜잭션에 평가 사실을 결합한다. 사용 가능 잔액·엔진·Kafka로 전달하는 변경은 없다.

## 기존 제품과 공식 자료의 접근

[109의 공식 근거·적용 시점 검토](109-durable-bitcoin-krw-reference-price.md#공식-자료와-적용-시점)를 함께 읽는다. 업비트의 공개 KRW-BTC 티커는 체결가와 체결/스냅샷 시각을 제공한다. 이번 변경은 이미 설치한 JDK·Jackson·Spring JDBC·PostgreSQL과109의 가격 원본을 재사용한다. 패키지는 추가하지 않았다.

[업비트 트래블룰 안내](https://support.upbit.com/hc/ko/articles/4498679629337)는 금액·상대 경로별 절차를 구분한다. 이를 모든 입금의 개인정보 자동 교환 의무로 일반화하지 않는다. [감독규정2024-1호](https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq=2100000243142&chrClsCd=010201) 제26조의 수취 시점과 송신 고객의 이전 요청 시점은 구분해야 한다. [2026-3호 및 부칙](https://www.law.go.kr/admRulLsInfoP.do?admRulSeq=2100000285986)의 유예 사항은109에서 확인했다. 현재 기록은 우리 보류 인식 시점의 참고 평가로서 송신자의 요청 시점 가격을 증명하지 않는다.

[PostgreSQL 행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#LOCKING-ROWS)과 지연 제약 트리거를 사용한다. 같은 금융 트랜잭션에서 현재 가격 위치를 고정하고, 커밋 때 새 보류 입금에 반드시 평가 또는 미평가 기록이 존재하는지 검사한다. 거래소 내부 구현을 공개 자료 없이 추정하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 수량은 기록하고 가격 부재는 UNVALUED로 보존 | 가격 장애에도 실제 수탁량과 보류 원장을 맞춘다 | 자동 사용 허용으로 해석할 수 없다. 평가 완료까지 수량 기록을 막는 대안은 자산 인식 누락 위험이 있다 |
| 원본 가격 위치를 같은 트랜잭션에서 고정 | 동시 수집으로 평가 근거가 바뀌지 않는다 | 플랫폼 가격 게시가 짧은 원장 트랜잭션 종료를 기다린다. 엔진 코어에는 잠금이나 조회를 추가하지 않는다 |
| 정확한 BigDecimal 곱과 불변 기록 | 수량·가격·환산액을 재현한다 | 표시용 반올림과 법정 판정은 별도 정책이다. double 계산이나 평가액 덮어쓰기는 채택하지 않는다 |
| 인식 중 가격이 만료되면 참조를 보존하고 미평가 | 유효했던 조회와 실제 인식 시점 차이를 남긴다 | 후속 판단이 필요하다. 자동으로 새로운 가격을 끌어오는 대안은 과거 근거를 바꾼다 |
| 같은 트랜잭션·필수 기록을 DB에서도 검사 | 서비스 우회/저장 누락 시 부분 원장 커밋을 막는다 | PostgreSQL 결합과 쓰기 순서 제약이 생긴다. 기존 이력은 보존하고 소급 평가하지 않으며 새 쓰기는 새 계약을 따라야 한다 |

2분 신선도·10초 미래 허용은 내부 참고가격 정책이며 법령이 지정한 수치가 아니다. 가격 수집과 개인정보 자동 교환은 계속 기본 비활성화다.

## 구현과 관련 코드·문법

출처: [BitcoinPendingDepositService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPendingDepositService.java#L80), 현재 소스의 부분 발췌.

```java
        Long priceId=valuations.captureCurrentPrice();
        ledger.record(key,"BTC_DEPOSIT_PENDING",List.of(p(owner,"PENDING",asset,amount),p(0,"CUSTODY",asset,amount.negate())));
        jdbc.update("INSERT INTO bitcoin_pending_deposits(receipt_id,decision_id,operation_key) VALUES(?,?,?)",deposit,decision,key);
        valuations.recordRecognized(deposit,priceId);
        // 잠근 고객의 새 수탁 순번을 같은 트랜잭션으로 알린다. 탐지 계산은 별도 워커가 원본을 읽어 수행한다.
        monitoring.enqueue(owner,key);
        audit.record(null,"BTC_PENDING_DEPOSIT_RECOGNIZED","bitcoin-deposit:"+deposit,"SUCCESS","DECISION:"+decision+":OP:"+key);
```

현재 가격을 먼저 고정한 뒤 수량 원장·보류 원본·평가·감사를 같은 트랜잭션에서 저장한다. 중간 저장이나 감사가 실패하면 모두 롤백한다. 앞서 접수한 온체인 입금 원본은 보존한다. 기존 인식의 재요청은 원래 기록을 반환하고 새 가격을 적용하지 않는다.

출처: [BitcoinDepositValuationService.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinDepositValuationService.java#L24), 현재 소스의 부분 발췌.

```java
    @Transactional(propagation=Propagation.MANDATORY)
    public Long captureCurrentPrice(){
        try{return prices.current().attemptId();}
        catch(ResponseStatusException unavailable){
            // 정상적인 가격 부재는 자금 사용을 보류할 이유다. 실제 들어온 자산의 수량 기록을 생략하거나 가격0으로 채우지 않는다.
            if(unavailable.getStatusCode().value()!=503)throw unavailable;return null;
        }
    }
```

MANDATORY는 호출자가 이미 트랜잭션을 시작했어야 한다는 뜻이다. TypeScript의 단순 함수 호출과 달리 Spring 프록시가 경계를 검사한다. 정상적인503 가격 부재만 null로 바꾼다. SQL 오류나 다른 업무 오류는 숨기지 않는다. 가격 서비스의 정상 부재 예외는 내부 트랜잭션 콜백 밖에서 발생하므로 원장 트랜잭션을 불필요하게 rollback-only로 만들지 않는다.

출처: [BitcoinDepositValuationService.java](../../src/main/java/dustin/cex/domains/valuation/BitcoinDepositValuationService.java#L46), 현재 소스의 부분 발췌.

```java
            boolean usable=!start.isAfter(reference)&&!stored.isAfter(reference)&&!start.isBefore(reference.minusSeconds(120))
                &&!trade.isBefore(reference.minusSeconds(120))&&!provider.isAfter(reference.plusSeconds(10));
            reason="PRICE_NOT_USABLE_AT_REFERENCE";
            if(usable){status="VALUED";reason="PRICE_AVAILABLE";value=((BigDecimal)deposit.get("amount")).multiply((BigDecimal)source.get("price"));}
```

Instant 비교로 실제 인식 시점의 유효성을 다시 검사한다. BigDecimal.multiply는 십진수의 정확한 곱을 구한다. 예를 들어0.12345678 BTC와123456789.12345678 KRW/BTC의 곱은15241577.6543209965279684다. JavaScript number의 이진 부동소수점 곱과 구분한다. 불가한 가격은0원으로 바꾸지 않는다.

출처: [bitcoin-deposit-schema.sql](../../src/main/resources/valuation/bitcoin-deposit-schema.sql#L42), 현재 소스의 부분 발췌.

```sql
CREATE CONSTRAINT TRIGGER bitcoin_pending_valuation_complete_check AFTER INSERT ON bitcoin_pending_deposits
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_pending_valuation_complete();
```

DEFERRABLE INITIALLY DEFERRED는 트랜잭션 안의 중간 삽입 순서는 허용하되 최종 커밋 때 평가 사실의 존재를 확인한다. 별도의 삽입 트리거는 원장과 같은 트랜잭션인지, 기준 시각·현재 가격·정확한 환산액이 일치하는지 검사한다. UPDATE·DELETE·TRUNCATE도 차단한다. 기존 금융 기록을 삭제하거나 트리거를 꺼서 시험을 맞추지 않는다.

후속116에서 탐지 작업 알림도 같은 트랜잭션에 연결했다. 현재 발췌와 아래 당시 검증 결과를 구분한다.

## 작성한 테스트

`src/test/java/dustin/cex/domains/kyc/BitcoinDepositValuationIntegrationTest.java`의14개 메서드를 각각 별도 DB에서 실행했다. 아래 순서와 함수명은 같은 순서다.

1. 정확한 곱·원장과 동일 트랜잭션·감사 결합.
2. 가격 부재에도 실제 수량 기록,0원 평가 금지.
3. 비활성화 시 기존 신선한 가격 재사용 금지.
4. 최신 가격 실패 시 과거 성공 사용 금지.
5. 재시도해도 기존 평가 유지.
6. 늦게 수집한 가격으로 과거 미평가 보충 금지.
7. 인식 중2분 창을 벗어나면 원본 참조와 미평가 사실 보존.
8. 평가 감사 실패 시 원장·보류·평가 롤백, 입금 원본 보존.
9. SQL 직접 수정·삭제·TRUNCATE·사후 위조 삽입 차단.
10. 평가 기록 누락은 지연 제약으로 커밋 차단.
11. 동시 인식 시 한 원장·한 평가·한 감사만 유지.
12. 실제 pg_stat_activity 잠금 대기로 가격 게시가 인식 종료를 기다리는지 확인.
13. 기존 트랜잭션 없는 평가 호출 거부.
14. 원래 인식 트랜잭션 안에서도 잘못된 곱의 직접 SQL 삽입 거부.

가격 클라이언트는 소유 대역이며 실제 업비트 API·실제 개인정보 교환·외부 고객 자산을 사용하지 않았다. 기존221개 회귀는 별도 Bitcoin Core31.1 regtest에서 수신·확정·충돌·재조직·감지·원장·조회·대사를 검사한다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_deposit_valuations.py`:14개 통과. 소유 PG16 서버·메서드별14 DB, 두 실행 JAR 빌드. 신규 테스트의 가격 HTTP는 대역이다.
- `CEX_BITCOIN_CORE_HOME=../.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py`:221개 통과. 실제 실행은 CODEVASP_/VALUATION_ 환경을 제거하고 소비자 자동 시작을 금지한 상위 실행기에서 수행했다. 각 그룹 XML을 복사하는 수집 동작만 추가했으며 시험 선택/명령은 동일하다. 소유 Core는 외부 연결0·regtest이며 소유16개 PG 서버와 함께 종료·정리됐다.
- `python3 scripts/verify_engine_inbox.py`:182개 통과. 상위 실행에서 외부 서비스 관련 환경 접두값을 제거하고 소비자 자동 시작을 금지했다. 소유 PG에서 중복 수신·원장·정산·실패·주문 경로를 검증했다.

총417회 실행, 클래스·함수명 기준 중복 제거399개다. BTC 묶음과 금융 묶음의 OrderLedgerTest 중복을 별도 성공 개수로 부풀리지 않았다. 실패·오류·제외는 모두0이다. `git diff --check`와 Python 구문 검사도 통과했다. 기존 deprecated/unchecked 및 JVM CDS 경고는 남아 있다. JAR 빌드는 서버 기동·운영 인증 검증과 구분한다.

## 남은 범위

가격 원본의 표시에 관한 운영 정책·적용 금액·제공 목적·상대/지갑 경로·현재 고객/위험 판정은 별도 업무로 완성해야 한다. 이 평가나 암호화 응답 수신만으로 사용 가능 잔액을 늘리지 않는다. 과거 미평가 입금의 재판단이 필요하면 원래 사실을 보존한 독립 판단 기록이 필요하다. 실제 공급사 계약·개인정보 처리 근거·메인넷 운영·법률/인증 심사는 이번 로컬 시험에 포함하지 않는다.

## 작성한 테스트 코드


[BitcoinDepositValuationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositValuationIntegrationTest.java)

- `validPriceIsCapturedWithExactCustodyValueAndSameTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `unavailablePriceDoesNotRollBackActualAssetsOrInventZeroValue`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `disabledPriceCannotUseEvenFreshExistingSource`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `latestPriceFailureCannotBorrowEarlierSuccess`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `retryKeepsOriginalPriceInsteadOfRevaluing`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `laterSourceCannotFillEarlierMissingCapture`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `priceThatAgesDuringRecognitionIsRetainedAsUnusableReference`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationAuditFailureRollsBackPendingAndPostingsButPreservesReceipt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationCannotBeChangedDeletedOrFalsifiedBySql`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `pendingCannotCommitWithoutRequiredValuationFact`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentRecognitionKeepsOnePriceAndAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `pricePublisherWaitsForRecognitionAndCannotMoveCapturedSource`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `valuationHooksRequireExistingBusinessTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongConversionCannotBeInsertedEvenInsideOriginalRecognitionTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 1632e2e](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1632e2e937984b55ffe6a84835da9c2ea4809c14)
- [격리 실행 증적](../reference/110-pending-deposit-price-capture-verification-2026-10-25.json)
