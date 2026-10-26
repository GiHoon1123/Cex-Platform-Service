# 고정 BTC 지급 원본과 최초 서명 요청을 한 커밋으로 확정

승인된 지급 검토·미서명 거래·입력 출처·실제 지갑 잠금 관측·회사 비용 여력을 다시 결합한 뒤, 변경할 수 없는 지급 실행 원본과 최초 서명 요청을 같은 금융 커밋에 저장한다. 이번 단계는 보호된 서명기가 읽을 입력을 확정하며 개인키 서명·방송·체인 완료는 아직 수행하지 않는다.

## 문제와 기존 동작

154까지는 실제 입금에서 온 입력만 미서명 거래 후보로 저장하고 Bitcoin Core에 영속 잠금할 수 있었다. 그러나 기존 `commit`은 독립 승인과 고객 취소의 배타성만 확정했다. 잠금이 실제로 관찰됐는지, 승인 이후 입력 출처가 바뀌지 않았는지, 고객 고정 수수료를 초과하는 광부 비용을 회사가 감당할 수 있는지를 하나의 서명 요청과 결합하지 않았다.

서명기가 후보 표만 읽으면 취소되었거나 잠금이 풀린 입력, 오래된 위험 상태, 회사 비용 여력이 없는 거래에도 키 사용을 허가할 수 있다. 반대로 이 검사를 서명기마다 다시 조합하면 동일 출금에서 서로 다른 판단과 요청을 만들 수 있다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core의 [PSBT 처리](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletprocesspsbt/), [PSBT 완성](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/finalizepsbt/) 흐름처럼 미서명 거래 준비와 서명 단계를 분리한다. 이번 플랫폼은 관찰 전용 준비 역할을 유지하고, 서명기가 받아야 할 고정 원본만 먼저 저장한다.

동시 비용 예약에는 기존 원장과 PostgreSQL의 [트랜잭션 수준 권고 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)을 재사용한다. 불변 기록과 필수 감사의 동일 커밋은 [지연 제약 트리거](https://www.postgresql.org/docs/16/sql-createtrigger.html)로 다시 확인한다. 자료 확인일은 2026-10-27이다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약 |
| --- | --- | --- |
| 승인·후보·출처·최근 잠금 관측을 한 실행 원본에 결합 | 서명기가 지급 조건을 새로 추측하지 않고 정확히 한 원본을 사용 | 잠금 관측은 30초 안이어야 하며 만료되면 다시 관찰해야 함 |
| 출금 UUID를 최초 서명 요청 ID로 사용 | 응답 유실·재시도에도 새 거래나 새 요청이 생기지 않음 | 한 출금은 한 실행과 한 미서명 거래만 확정할 수 있음 |
| 고객 0.01% 수수료를 유지하고 초과 광부 비용만 회사 BTC 여력에서 예약 | 고객 서명 금액을 실행 직전에 올리지 않음 | 회사 BTC 수수료 수익이 부족하면 지급을 보류함 |
| 비용을 실제 분개 전에 실행 기록으로 예약 | 아직 발생하지 않은 비용을 원장 비용으로 잘못 기록하지 않음 | 완료·비용 분개가 구현될 때만 예약을 안전하게 해제해야 함 |
| DB 커밋 안에서 키·노드 호출 금지 | 트랜잭션 잠금 중 외부 대기와 결과 불명을 만들지 않음 | 실제 서명·방송은 다음 워커 단계가 필요함 |

회사 비용 예약은 `FEE` 잔액에서 아직 완료되지 않은 실행의 `company_exposure` 합계를 뺀 값으로 계산한다. `company_exposure = max(광부 수수료 - 고객 고정 수수료, 0)`이다. 다른 자산 수익은 BTC 비용으로 사용하지 않는다. 테스트의 회사 수익 분개는 격리 검증용 fixture이며 운영 자본 반입 기능이 아니다.

## 구현과 데이터 흐름

1. 출금 검토와 후보 입력에서 관련 사용자 전체를 숫자 순서로 잠근다.
2. 현재 승인·고객 자격·고정 금융 원본을 다시 검사한다.
3. 저장한 입력 출처와 현재 출처의 불변 입금 원본이 같은지 확인한다.
4. 최신 지갑 동작이 해당 후보의 `LOCK`이고, 정확한 입력·망·지갑에 대한 성공 관측이며 30초 안인지 인증한다.
5. 회사 BTC 수수료 계정과 기존 미완료 비용 예약을 한 권고 잠금 아래 계산한다.
6. 암호화 실행 원본, 안정적 서명 요청, 기존 업무 확정, 세 필수 감사를 같은 트랜잭션에 저장한다.
7. 같은 출금의 재시도는 저장한 원본을 인증해 반환하며 새 서명 요청을 만들지 않는다.

실행 원본과 서명 요청은 갱신·삭제·절단할 수 없다. 동일 망에서 같은 미서명 거래 ID를 둘 이상의 실제 실행으로 확정할 수도 없다. 미실행 후보를 정리하고 다시 준비하는 153의 동작은 유지되지만, 실제 실행으로 확정한 뒤에는 새 후보로 바꾸지 못한다.

## 실제 코드와 문법

출처: [BitcoinPayoutExecutionRecords.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutExecutionRecords.java#L35), 현재 소스의 부분 발췌.

```java
    public void create(BitcoinWithdrawalPayoutReviews.Request review,BitcoinPayoutCandidates.Candidate candidate,List<BitcoinPayoutInputSources.Certificate> sources){
        var p=candidate.payload();var f=p.funds();var prepared=p.prepared();var lock=lock(candidate);
        var archive=jdbc.queryForMap("SELECT content_mac FROM bitcoin_payout_candidate_sources WHERE candidate_id=?",p.id());
        BigDecimal exposure=new BigDecimal(prepared.nativeFee()).subtract(new BigDecimal(f.fee())).max(BigDecimal.ZERO).setScale(8);
        // 고객의 고정 수수료를 바꾸지 않는다. 회사 수수료 계정의 같은 자산 여력을 직렬화해 예약한다.
        // 거래 수수료의 양수 입금은 병렬로 가능하며, 모든 네이티브 비용 예약은 같은 잠금을 사용한다.
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('cex:btc-withdrawal-fee-budget',0))");
```

`BigDecimal`은 사토시 단위 금액에 이진 부동소수점 오차를 넣지 않는다. `max(BigDecimal.ZERO)`는 고객 수수료가 광부 비용보다 큰 경우 회사 노출을 0으로 만든다. `pg_advisory_xact_lock`은 현재 트랜잭션이 끝나면 자동 해제되어 예외 경로의 별도 해제 코드를 만들지 않는다.

출처: [BitcoinWithdrawalPayoutReviews.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinWithdrawalPayoutReviews.java#L108), 현재 소스의 부분 발췌.

```java
    public Request commit(UUID id) {
        boundary();return tx.execute(t->{var meta=meta(id);UUID withdrawal=(UUID)meta.get("withdrawal_id");
            var old=jdbc.queryForList("SELECT review_id FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=?",UUID.class,withdrawal);
            if(!old.isEmpty()){
                if(!id.equals(old.getFirst()))throw denied(HttpStatus.CONFLICT);
                // 과거 원본 확인은 동일 실행/서명 요청을 다시 만들지 않는다. 없는 새 실행 근거를 추정하지 않는다.
                var original=executions.read(withdrawal);if(!id.equals(original.reviewId()))throw denied(HttpStatus.CONFLICT);return read(id);
            }
```

`TransactionTemplate.execute` 안의 반환값은 트랜잭션 결과다. 기존 확정 행이 있으면 암호화 실행 원본까지 인증하고 반환한다. 과거 업무 확정만 있고 새 실행 원본이 없는 상태를 호환 폴백으로 승인하지 않는다.

출처: [payout-execution-schema.sql](../../src/main/resources/bitcoin/payout-execution-schema.sql#L30), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_native_execution_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;r record;w record;observed record;income numeric;allocated numeric;
BEGIN
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id;
 SELECT * INTO r FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 SELECT * INTO w FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;
 PERFORM id FROM users WHERE id IN(r.user_id,r.requester_id,r.checker_id) ORDER BY id FOR UPDATE;
```

애플리케이션 검사를 우회한 직접 SQL도 현재 후보·승인·출금·잠금·비용 조건을 통과해야 한다. 지연 제약 트리거는 실행, 서명 요청, 업무 확정, 필수 감사가 하나라도 빠지면 커밋을 거절한다.

## 작성한 테스트

[BitcoinPayoutExecutionIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinPayoutExecutionIntegrationTest.java)의 12개 시나리오를 작성했다.

- 고정 실행·첫 서명 요청·업무 확정이 같은 커밋이며 고객 총액/수수료와 PSBT가 그대로인지 확인한다.
- 회사 BTC 여력 부족과 다른 자산 수익, 잠금 부재·결과 불명·변경된 입력 출처를 모두 거절한다.
- 실행 감사 또는 서명 요청 감사가 빠지면 실행·요청·비용 예약이 함께 롤백되고 고객 취소 가능 상태가 유지되는지 확인한다.
- 같은 요청과 동시 두 요청이 실행/서명 요청을 하나만 만드는지 확인한다.
- 실행/서명 요청의 갱신·삭제·절단을 DB가 거절하는지 확인한다.
- 첫 실행의 회사 노출이 실제 비용 분개 전에도 두 번째 실행의 가용 여력을 줄이는지 확인한다.

기존 지급 검토 34개, 후보 17개, 잠금 후 확정 1개, 재승인 3개도 다시 실행해 취소/승인·미실행 정리·입력 배타성이 유지되는지 확인했다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_payout_execution.py`: 신규 12개.
- `python3 scripts/verify_bitcoin_withdrawal_payout_reviews.py`: 기존 34개.
- `python3 scripts/verify_bitcoin_payout_candidates.py`: 기존 17개.
- `python3 scripts/verify_bitcoin_payout_candidate_wallet.py --method committedPayoutCannotReleaseInputsEvenAfterCancellationIsAttempted`: 선택 1개.
- `python3 scripts/verify_bitcoin_payout_candidate_reapproval.py`: 기존 3개.
- 합계 **고유 67개/67회, 실패·오류·제외 0**, 각 메서드 전용 PostgreSQL DB와 두 JAR 빌드 성공, 소유 컨테이너 정리를 확인했다.
- 봇 0, Kafka 소비자 자동 시작 금지, 외부 노드·엔진 주소 격리를 유지했다. 실제 Bitcoin Core 개인키·서명·방송은 이번 검증에서 호출하지 않았다.

## 남은 한계와 다음 연결

현재 `bitcoin_payout_sign_requests`는 보호된 서명 워커가 소비할 고정 요청이다. 실제 서명 결과, 원시 거래·거래 ID 보존, 방송 응답 유실과 안전한 재전송, 체인 확정, 엔진 `COMPLETE`, 고객/회사 원장 비용 정산은 후속이다. 회사 비용 예약은 임의의 원장 행만으로 해제해서는 안 되며, 정식 체인 완료·엔진 완료·정확한 완료 분개를 같은 완료 근거에 결합해야 한다.

## 관련 커밋과 증적

- [구현 7cf60b4](https://github.com/GiHoon1123/Cex-Platform-Service/commit/7cf60b4199e2a8d6419cf9461fda6a38cfb2e275)
- [격리 실행 증적](../reference/155-bitcoin-fixed-payout-execution-verification-2026-10-27.json)
