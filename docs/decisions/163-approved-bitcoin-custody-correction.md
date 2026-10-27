# 승인된 Bitcoin 수탁 차이의 불변 균형 보정

수탁 대사에서 확인한 BTC 차이를 원래 금융 기록의 수정·삭제 없이, 지정된 두 사람의 내용 결합 승인과 새 대사 재검증을 거쳐 `CUSTODY`와 `SUSPENSE` 사이의 새 균형 분개로만 보정한다.

## 문제와 기존 동작

독립 수탁 대사는 노드 잔액과 플랫폼 원장의 차이를 `MISMATCH`로 보존했지만 원인을 조사한 뒤 승인된 보정을 실행할 경계가 없었다. 원장 행을 직접 고치면 처음 오류와 보정 시점이 사라지고, 대사 직후 상태가 달라졌는데도 오래된 숫자로 보정하면 새 오류를 만든다.

## 기존 제품과 공식 자료의 접근

- [TigerBeetle Correcting Transfers](https://docs.tigerbeetle.com/coding/recipes/correcting-transfers/)는 확정 이력을 수정·삭제하지 않고 새 전송으로 역전하거나 조정하는 방식을 제시한다.
- [Modern Treasury Transaction Status and Balances](https://docs.moderntreasury.com/ledgers/docs/transaction-status-and-balances)는 게시된 거래와 항목을 불변으로 두고, 효과를 취소하려면 두 번째 역거래를 작성하도록 설명한다.
- [Modern Treasury Ledgers Guarantees](https://docs.moderntreasury.com/ledgers/docs/ledgers-guarantees)는 자산별 차변·대변 균형, 쓰기 원자성, 멱등 키를 원장 보장으로 둔다.
- [Bitcoin Core getbalances](https://developer.bitcoin.org/reference/rpc/getbalances.html)는 신뢰 잔액과 외부 미확정·미성숙 잔액을 구분한다. 보정 실행은 전이 잔액이 없는 안정된 `MISMATCH`만 사용한다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약 |
| --- | --- | --- |
| 과거 원장 수정 대신 새 `CUSTODY`/`SUSPENSE` 분개 | 오류와 보정의 시간 순서를 모두 보존하고 고객 잔액을 건드리지 않음 | 미해결 차이가 별도 계정에 남아 원인 조사와 후속 정리가 필요함 |
| 담당자 제안과 지정 승인자 서명 분리 | 한 사람이 차이를 만들고 숨기는 위험 감소 | 두 운영 인력과 최근 인증이 필요함 |
| 승인 직후 새 수탁 대사로 같은 차이 재검증 | 오래된 관찰로 현재 잔액을 보정하지 않음 | 노드 장애·전이 잔액이면 실행이 지연됨 |
| 실행 API 비공개 | 고객/운영 HTTP에서 금융 보정을 직접 호출하지 못함 | 별도 통제 워커나 운영 절차가 내부 메서드를 호출해야 함 |
| DB 지연 제약으로 감사·승인·원장 결합 | 서비스 코드 우회와 부분 커밋 차단 | 스키마가 업무 계약을 자세히 알아야 하고 변경 검증 비용이 증가함 |

자동으로 차이를 원인 없이 고객 또는 수수료 계정에 배분하는 대안은 선택하지 않았다. `SUSPENSE`는 해결이 끝났다는 뜻이 아니라 회사 장부에 남은 미해결 차이를 명시하는 계정이다.

## 구현과 관련 코드

- [BitcoinCustodyCorrections.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCustodyCorrections.java): 제안·열람·서명 결정·재검증·멱등 실행.
- [custody-correction-schema.sql](../../src/main/resources/bitcoin/custody-correction-schema.sql): 불변 업무 원본, 역할·세션·서명·감사·정확 분개의 데이터베이스 제약.
- [BitcoinCustodyCorrectionController.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCustodyCorrectionController.java): 제안과 승인 업무만 노출하며 실행 경로는 노출하지 않음.
- [LedgerService.java](../../src/main/java/dustin/cex/domains/ledger/LedgerService.java): 회사 소유 `SUSPENSE` 계정 허용.

현재 제품 코드의 부분 발췌다.

```java
BigDecimal difference = observed.subtract(before);
String operation = "btc:custody-correction:" + id;
jdbc.update("INSERT INTO bitcoin_custody_correction_executions...", ...);
ledger.record(operation, "BTC_CUSTODY_CORRECTION", List.of(
    p(0, "CUSTODY", "BTC", difference.negate()),
    p(0, "SUSPENSE", "BTC", difference)));
```

`BigDecimal`은 부동소수점 반올림 없이 8자리 BTC 차이를 보존한다. 두 `Posting`의 합은 항상 0이라 자산별 이중 원장 균형을 유지한다. `owner=0`은 고객이 아닌 회사 계정이다. 실행 행을 먼저 넣고 같은 트랜잭션에서 원장을 기록하는 이유는 지연 외래 키와 제약 트리거가 커밋 시 전체 묶음을 확인하게 하기 위해서다. 어느 한 항목이나 필수 감사가 빠지면 전부 롤백된다.

```java
var verification = reconciliation.run();
if (!"MISMATCH".equals(verification.status())) {
    return new Execution(id, verification.id(), null, "STALE");
}
```

노드 호출은 데이터베이스 트랜잭션 밖에서 수행한다. 그 뒤 요청 행을 잠그고 승인 만료, 새 대사 시각, 예상/관찰 금액의 정확한 동일성을 다시 확인한다. 실행이 이미 있으면 같은 업무 키를 반환하고 노드나 원장을 다시 호출하지 않는다.

TypeScript의 `number`는 BTC 금액에 필요한 십진 정확성을 보장하지 않는다. Java의 `BigDecimal.compareTo`로 값의 수치 동일성을 확인하고, 문자열 직렬화는 고정 소수점 표현으로 서명 원본을 안정화한다. Java `record`는 이 원본의 필드를 생성 뒤 바꾸지 않는 값 객체로 사용한다.

## 작성한 테스트

- [BitcoinCustodyCorrectionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCustodyCorrectionIntegrationTest.java)
  - 같은 차이를 새 대사로 확인한 승인 요청만 정확한 두 분개를 한 번 생성
  - 거부·다른 차이·미열람·다른 사용자·다른 본문 차단과 고객 잔액 무변경
  - 제안·결정·실행 감사 누락 시 승인 소비와 금융 쓰기 전체 롤백
  - 직접 SQL 실행 우회와 요청·열람·결정 변경·삭제·전체 비우기 거부
  - 역할별 무화면 HTTP와 공개 실행 경로 부재
- [OrderLedgerTest.java](../../src/test/java/dustin/cex/domains/ledger/OrderLedgerTest.java)
  - 기존 확정 행을 보존하며 `SUSPENSE` 계정 제약 확장
  - 독립 대사의 현재 완료 이벤트 최소 스키마를 보강해 제품 조회 계약과 시험 fixture를 일치시킴
- [verify_bitcoin_custody_corrections.py](../../scripts/verify_bitcoin_custody_corrections.py)
  - JPA 수명과 독립 원장 스키마가 충돌하지 않도록 정정6개·수탁 대사4개·원장18개를 서로 다른 DB에서 실행

## 실행 명령과 결과

```bash
python3 scripts/verify_bitcoin_custody_corrections.py
```

- 격리 PostgreSQL 16
- 시험 28개 통과, 실패0, 오류0, 제외0
- `bootJar`, `operatorAdminJar` 각 묶음 빌드 통과
- 임시 컨테이너 제거 성공

초기 검증에서는 문자열인 대사 쟁점 금액을 `BigDecimal`로 직접 형변환해 6개가 실패했다. 숫자 열과 문자 증거를 모두 명시적으로 십진 변환하도록 수정했다. 서로 다른 스키마 수명의 시험을 같은 DB에 합친 실행에서는 원장 시험9개가 실패했으며, 그중 현재 제품 조회가 요구하는 완료 이벤트 빈 표가 fixture에 빠진 실제 회귀도 발견했다. 표 계약을 보강하고 최종 실행기는 묶음별 DB를 사용해 28개 전체를 새로 통과시켰다.

[기계 판독 증적](../reference/163-bitcoin-custody-correction-verification-2026-10-27.json)을 함께 남긴다.

## 남은 한계

- `SUSPENSE` 금액의 원인 분류·회수·손실 인식·외부 회계 승인은 아직 별도 업무다.
- 단일 관찰 지갑 기준이며 핫·웜·콜드 지갑 합산과 별도 장애 영역 노드는 운영 검증이 필요하다.
- 실행은 내부 서비스 경계만 완성했다. 스케줄·운영 작업 큐·경보와 실제 조직 인물 분리는 배포 환경에서 확인해야 한다.
- 디스크·호스트 손실은 별도 복제·백업과 복원 시험이 필요하다.

## 관련 커밋과 참고 자료

- 구현·테스트: `0810ba5`
- 문서·증적: 현재 문서 커밋

