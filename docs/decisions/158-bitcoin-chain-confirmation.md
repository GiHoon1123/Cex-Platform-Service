# 방송된 BTC 거래의 여섯 확인을 별도 읽기 워커로 확정

방송 노드의 수신 사실과 체인 완료를 분리한다. 별도 읽기 전용 프로세스가 거래·포함 블록·현재 체인 끝을 한 스냅샷에서 대조하고 여섯 확인을 충족한 경우에만 불변 확정 머리를 만든다.

## 문제와 기존 동작

157의 `ACCEPTED`는 노드가 거래를 받았거나 관찰 지갑에서 찾았다는 뜻이다. 멤풀 거래는 아직 블록에 없고, 블록 포함 뒤에도 재조직으로 사라질 수 있다. 이 상태에서 엔진 `COMPLETE`를 호출하면 실제 체인 확정 전에 고객 잠금을 영구 차감한다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core31의 [`getrawtransaction`](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/getrawtransaction/) 상세 응답은 거래 ID, 포함 블록, 확인 수를 제공한다. [`getblockheader`](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/getblockheader/)는 해당 블록의 높이와 현재 활성 체인 확인 수를 제공하고, [`getblockchaininfo`](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/getblockchaininfo/)는 현재 tip과 높이를 제공한다.

한 번의 관찰 동안 tip이 움직이지 않았는지 전후로 확인하고 `tip 높이 - 블록 높이 + 1 = 거래 확인 수 = 헤더 확인 수`를 검사한다. 국내 거래소별 공개 확인 정책은 변경될 수 있으므로 특정 회사 UI 값을 복사하지 않고 현재 정책을 여섯 확인으로 고정했다. 자료 확인일은 2026-10-27이다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약 |
| --- | --- | --- |
| `bitcoin-confirmer` 별도 JAR·RPC 계정 | 방송·서명 없이 읽기 사실만 확정 | 프로세스와 읽기 노드 운영 필요 |
| 여섯 확인 고정 | 얕은 재조직 위험을 낮춤 | 정상 출금 완료가 평균적으로 늦어짐 |
| tip 전후 동일성 검사 | 서로 다른 시점의 높이·헤더 혼합 차단 | 블록이 관찰 중 도착하면 실패가 아니라 다음 폴링까지 대기 |
| `WAITING`·`UNKNOWN` 60초 대기 | 노드에 과도한 조회를 하지 않음 | 확인 직후에도 최대 폴링 간격만큼 완료 지연 |
| 충돌 자동 재시도 금지 | 이중지불·재조직 이상을 운영 검토로 전환 | 자동 복구보다 운영 부담 증가 |

실제 시험은 한 격리 Core 프로세스에서 기능과 최소 권한을 검증했다. 운영상 독립 관찰을 주장하려면 방송 노드와 다른 장애 영역의 완전 동기화 노드, `txindex`, 접근 통제와 모니터링을 별도로 검증해야 한다.

## 구현과 관련 코드

출처: [BitcoinConfirmationRpcClient.java](../../src/main/java/dustin/confirmer/BitcoinConfirmationRpcClient.java), 현재 소스의 부분 발췌.

```java
            var header=rpc.call("getblockheader",List.of(block,true),1048576);var after=rpc.call("getblockchaininfo",List.of());long height=integer(header,"height");long headerConfirmations=integer(header,"confirmations");if(!tip.equals(text(after,"bestblockhash"))||tipHeight!=integer(after,"blocks")||!block.equals(text(header,"hash"))||headerConfirmations!=confirmations||tipHeight-height+1!=confirmations||confirmations>Integer.MAX_VALUE)return empty(Status.UNKNOWN);
```

세 숫자를 독립적으로 읽고 서로 같은 체인 스냅샷인지 확인한다. 전후 tip이 다르면 일부 값이 각각 정상이더라도 `UNKNOWN`으로 보존한다. 음수 확인은 `CONFLICT`, 0~5는 `WAITING`, 6 이상만 `CONFIRMED`다.

출처: [BitcoinConfirmationService.java](../../src/main/java/dustin/confirmer/BitcoinConfirmationService.java), 현재 소스의 부분 발췌.

```java
    public Optional<Outcome> processNext(){Start start=tx.execute(t->start());if(start==null)return Optional.empty();return Optional.of(finish(start,node.observe(start.txid())));}
```

체인 I/O는 시도 커밋과 결과 커밋 사이에 있어 DB 행 잠금을 유지하지 않는다. 결과 불명이 프로세스 종료와 섞이지 않으며, 성공 머리가 있으면 같은 거래를 다시 확정하지 않는다.

## 작성한 테스트

- [BitcoinConfirmationServiceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinConfirmationServiceIntegrationTest.java), 9개: 여섯 확인, 반복 폴링, 대기, 결과 불명, 충돌 보류, 감사 누락 롤백, 다른 거래 ID 차단, 불변성, 두 소비자 경쟁.
- [BitcoinConfirmerCoreIntegrationTest.java](../../src/test/java/dustin/confirmer/BitcoinConfirmerCoreIntegrationTest.java), 3개: 실제 6확인 블록 증거, 다른 망 거절, 준비·서명·방송·키 조회·지갑 생성 권한 거부.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_confirmation_service.py`: 메서드별 소유 PostgreSQL에서 9개 통과, 실패·오류·제외 0, 다섯 JAR 빌드와 컨테이너 정리 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_confirmer_core.py`: Core31.1 regtest 3개 통과, 외부 연결 0, 노드 종료 성공.
- 고유 12개/12회다. 실제 별도 호스트·별도 노드 동기화와 메인넷 재조직 시험은 실행하지 않았다.

## 남은 한계와 다음 연결

확정 머리는 그 자체로 고객 원장을 바꾸지 않는다. 이 머리를 유일한 권한으로 삼은 엔진 `COMPLETE`와 `withdrawal_completed` Kafka 금융 정산은 [159](159-bitcoin-payout-financial-completion.md)에서 구현했다.

## 관련 커밋과 증적

- [체인 확인 구현 6806787](https://github.com/GiHoon1123/Cex-Platform-Service/commit/6806787)
- [검증 증적](../reference/158-bitcoin-chain-confirmation-verification-2026-10-27.json)
