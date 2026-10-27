# Bitcoin Core 수탁 잔액과 플랫폼 원장의 독립 대사

관찰 전용 Bitcoin Core 지갑의 신뢰 잔액을 플랫폼의 불변 `CUSTODY` 원장과 비교한다. 차이를 자동 수정하지 않고 실행별 원본·지문·쟁점을 불변 증거로 남긴다.

## 문제와 기존 동작

입금과 출금 각각에는 체인·엔진·원장 대사가 있었지만, 현재 수탁 지갑 전체 잔액과 플랫폼이 기록한 전체 수탁 자산을 독립적으로 비교하는 경계가 없었다. 개별 업무가 모두 정상이어도 누락된 입금, 원장 밖 지급, 잘못된 초기 반입이 누적되면 전체 차이를 발견하기 어려웠다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core `getbalances`는 지갑이 신뢰하는 잔액과 외부 미확정·미성숙 잔액을 분리하고, 해당 관찰의 마지막 처리 블록을 함께 반환한다. `listunspent`는 개별 미사용 출력 조사에 적합하지만 `safe`, 확인 수와 잠금 상태를 함께 해석해야 한다. 이번 전체 합계 대사는 공식 `getbalances`의 `trusted`, `untrusted_pending`, `immature`만 사용하고 개별 원인 조사는 기존 UTXO·입출금 증거로 연결한다.

- [Bitcoin Core getbalances](https://bitcoincore.org/en/doc/30.0.0/rpc/wallet/getbalances/)
- [Bitcoin Core listunspent](https://bitcoincore.org/en/doc/30.0.0/rpc/wallet/listunspent/)

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| 관찰 전용 지갑의 `trusted`와 `-CUSTODY` 비교 | 개인키 없이 전체 수탁 차이 탐지 | 여러 지갑·콜드 스토리지는 아직 합산하지 않음 |
| 미확정·미성숙·진행 중 지급은 `INCOMPLETE` | 정상 전이 중 거짓 불일치와 자동 정정 방지 | 전이가 끝난 뒤 다시 실행해야 함 |
| 차이를 자동으로 고치지 않음 | 원본 손상과 공격자의 은폐 방지 | 운영자가 원인을 조사하고 별도 승인 정정을 해야 함 |
| 실행 원본과 SHA-256 지문을 불변 저장 | 어떤 블록과 응답으로 판단했는지 재검증 가능 | 증거 저장 공간 사용 |

## 구현과 관련 코드

출처: [BitcoinCustodyReconciliationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinCustodyReconciliationService.java)의 부분 발췌.

```java
BigDecimal expected = after.custody().negate();
if (trusted.compareTo(expected) != 0) {
    issue(id, "CUSTODY_BALANCE_MISMATCH",
        expected.toPlainString(), trusted.toPlainString());
    outcome = "MISMATCH";
}
```

플랫폼 원장에서 회사 수탁 계정은 고객 자산 유입 때 음수로 기록된다. 따라서 실제 지갑 자산은 수탁 원장 합계의 부호를 뒤집은 값과 같아야 한다. `BigDecimal.compareTo`는 문자열 표현이나 소수점 자리 수가 아니라 정확한 수치가 같은지 비교한다.

```java
if (untrusted.signum() != 0 || immature.signum() != 0) {
    issue(id, "NODE_BALANCE_PENDING", ...);
    outcome = "INCOMPLETE";
}
```

미확정 외부 입금이나 미성숙 코인베이스가 있으면 확정 원장과 바로 비교하지 않는다. 실패를 성공으로 바꾸는 폴백이 아니라, 비교 시점이 안정되지 않았다는 별도 상태다.

## 작성한 테스트

- [BitcoinCustodyReconciliationIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinCustodyReconciliationIntegrationTest.java)
  - 정확한 0 잔액의 `MATCHED`와 완료 증거 변경·삭제·전체 비우기 거부
  - 지갑 차이의 `MISMATCH`와 금융 원장 무변경
  - 외부 미확정 잔액의 `INCOMPLETE`
  - 노드 실패의 `FAILED`와 예외 원문 비노출
- [BitcoinCoreIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinCoreIntegrationTest.java)
  - 실제 Core31.1 관찰 지갑에서 공식 잔액 세 구간과 마지막 처리 블록 확인
- [verify_bitcoin.py](../../scripts/verify_bitcoin.py)의 `--custody-reconciliation-only`가 소유 노드와 PostgreSQL을 만들고 두 시험을 함께 실행한다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
python3 scripts/verify_bitcoin.py --custody-reconciliation-only
```

- 실제 Core31.1 regtest, 외부 연결 0
- 시험 8개 통과, 실패·오류·제외 0
- 노드와 격리 PostgreSQL 정리 성공

첫 실제 실행에서는 개발 중인 Core 소스에 보였던 `nonmempool` 필드를 배포31.1의 공식 계약으로 잘못 가정한 테스트가 실패했다. 배포판과 공식30 문서에서 보장하는 세 필드만 사용하도록 수정했다. 또 감사 `outcome`에 업무 상태를 직접 넣어 DB 제약이 거부한 문제를 발견해, 감사 결과는 `SUCCESS`로 두고 업무 상태를 상세에 기록했다. 실패를 숨기지 않고 최종 실행을 새로 수행했다.

[기계 판독 증적](../reference/162-bitcoin-custody-reconciliation-verification-2026-10-27.json)을 함께 남긴다.

## 남은 한계

현재는 단일 관찰 지갑이다. 운영의 핫·웜·콜드 지갑과 여러 노드를 합산하려면 각각 독립 출처와 동일한 블록 기준을 정의해야 한다. 이번 작업은 차이를 탐지할 뿐 정정하지 않는다. 정정은 원래 기록을 수정하지 않고 원인 증거·두 사람 승인·새 역분개로 별도 구현한다.

## 관련 커밋과 참고 자료

- 구현·테스트: `dd1c05d`
- 문서·증적: 현재 문서 커밋
