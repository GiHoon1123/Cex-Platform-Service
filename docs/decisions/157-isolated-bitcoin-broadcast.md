# 서명된 BTC 거래를 별도 프로세스에서 안전하게 방송

서명 워커가 보존한 정확한 원시 거래만 별도 방송 프로세스가 읽는다. 이전 방송 응답이 유실됐는지 먼저 지갑 이력에서 거래 ID로 확인하고, 미발견일 때만 사전 검증과 방송을 수행한다. 개인키·거래 준비·웹·Kafka 권한은 방송 프로세스에 주지 않는다.

## 문제와 기존 동작

156까지 원시 거래의 서명과 암호화 저장은 완료했지만 네트워크에는 공개하지 않았다. 단순히 `sendrawtransaction`을 재시도하면 첫 요청이 성공하고 응답만 유실된 경우를 실패와 구분할 수 없다. 반대로 결과 불명을 성공으로 기록하면 실제로 공개되지 않은 출금을 완료 처리할 위험이 있다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core31의 [`testmempoolaccept`](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/testmempoolaccept/)는 원시 거래를 공개하지 않고 멤풀 수락 가능성을 검사한다. [`sendrawtransaction`](https://bitcoincore.org/en/doc/31.0.0/rpc/rawtransactions/sendrawtransaction/)은 원시 거래를 노드와 네트워크에 제출하며 반환값으로 거래 ID를 준다. [`gettransaction`](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/gettransaction/)은 지갑과 관련된 거래를 거래 ID로 조회한다.

이 계약을 따라 `gettransaction` 재확인 → `testmempoolaccept` → `sendrawtransaction` 순서로 구성했다. 이미 관찰 지갑 이력에 같은 거래 ID가 있으면 다시 보내지 않는다. 자료 확인일은 2026-10-27이며 실제 Core31.1 regtest로 이 흐름과 RPC 허용 목록을 검증했다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약 |
| --- | --- | --- |
| `bitcoin-broadcaster` 별도 Boot JAR | 개인키 서명과 외부 공개 권한의 침해 범위를 분리 | 별도 프로세스·DB 계정·RPC 계정·봉투 키를 운영해야 함 |
| 지갑 이력 선조회 | 성공 응답 유실 뒤 같은 거래를 재전송하지 않고 수신 사실 복구 | 관찰 지갑의 descriptor와 거래 이력이 정확히 유지돼야 함 |
| 사전 검증 뒤 방송 | 확정 거절을 공개 전에 보류 | 사전 검사와 실제 전송 사이에 멤풀 상태가 바뀔 수 있어 최종 호출은 여전히 결과 불명 가능 |
| `UNKNOWN` 5초 뒤 재시도, 시작만 남은 시도 10초 뒤 재시도 | 노드 지연·프로세스 종료가 영구 정지로 이어지지 않음 | 불명 기간에는 고객 완료가 늦고 최대 100회 뒤 운영 개입 필요 |
| `REJECTED` 자동 재시도 금지 | 의미가 잘못된 거래를 반복 공개하지 않음 | 운영자가 원인과 새 승인 필요 여부를 판단해야 함 |

동일한 서명 거래의 재전송 자체는 새 지급을 만들지는 않지만 네트워크 오류를 무제한 재시도로 숨기지 않는다. 거래 ID가 같더라도 체인 확정과 고객 금융 완료는 별도 단계다.

## 구현과 관련 코드

1. 방송 서비스가 `FOR UPDATE SKIP LOCKED`로 서명 머리 하나를 선점하고 시도·시작 감사를 먼저 커밋한다.
2. 서명 봉투의 AES-GCM과 HMAC을 확인해 정확한 원시 거래만 복호화한다.
3. DB 트랜잭션 밖에서 관찰 전용 Core 계정으로 기존 거래를 조회한다.
4. 미발견 오류 코드 `-5`일 때만 사전 검증 후 방송한다. 다른 노드·전송 오류는 `UNKNOWN`이다.
5. `ACCEPTED`일 때만 결과·현재 방송 머리·필수 감사를 같은 커밋에 저장한다. `UNKNOWN`과 `REJECTED`에는 머리가 없다.
6. 시도·결과·머리는 갱신·삭제·전체 삭제할 수 없는 금융 이력이다.

## 이해에 필요한 문법과 원리

출처: [BitcoinBroadcastRpcClient.java](../../src/main/java/dustin/broadcaster/BitcoinBroadcastRpcClient.java), 현재 소스의 부분 발췌.

```java
            try{var existing=rpc.call("gettransaction",List.of(expected,true,true),1048576);if(expected.equals(text(existing,"txid"))&&integer(existing,"confirmations")>=0)return new Result(Status.ACCEPTED,expected);return new Result(Status.REJECTED,null);}catch(BitcoinJsonRpcClient.RpcFailure missing){if(missing.code!=-5)return new Result(Status.UNKNOWN,null);}
```

`try/catch`는 모든 실패를 미발견으로 간주하지 않는다. Core의 안정적인 숫자 오류 코드 `-5`만 아직 지갑 이력에 없는 경우로 좁혀 다음 단계로 진행한다. 문자열 오류 메시지에 의존하지 않는다. 음수 확인 수는 충돌 거래이므로 수락으로 바꾸지 않는다.

출처: [BitcoinBroadcastService.java](../../src/main/java/dustin/broadcaster/BitcoinBroadcastService.java), 현재 소스의 부분 발췌.

```java
    public Optional<Outcome> processNext(){Start start=tx.execute(t->start());if(start==null)return Optional.empty();return Optional.of(finish(start,node.publish(start.txid(),start.raw())));}
```

첫 `tx.execute`는 시도를 커밋한 뒤 끝난다. `node.publish`는 트랜잭션 밖에서 실행되므로 느린 노드 I/O가 금융 행 잠금을 붙잡지 않는다. 마지막 `finish`는 노드 결과를 새 트랜잭션으로 저장한다. TypeScript의 `await`와 비슷하게 단계가 보이지만 Java의 `TransactionTemplate` 경계는 람다가 반환되는 순간 실제 커밋된다는 차이가 있다.

출처: [BitcoinBroadcasterApplication.java](../../src/main/java/dustin/broadcaster/BitcoinBroadcasterApplication.java), 현재 소스의 부분 발췌.

```java
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class})
```

방송 실행점은 웹 서버를 열지 않고 Kafka와 JPA도 자동 구성하지 않는다. JDBC 불변 이력과 제한된 Core RPC만 사용한다. Gradle의 `bitcoinBroadcasterJar`가 이 진입점을 별도 실행 파일로 만든다.

## 작성한 테스트

- [BitcoinBroadcastServiceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinBroadcastServiceIntegrationTest.java), 8개: 최초 수락 머리, 반복 폴링, 결과 불명 보존, 확정 거절 보류, 감사 누락 롤백, 다른 거래 ID 차단, 불변성, 두 소비자 경쟁.
- [BitcoinBroadcasterCoreIntegrationTest.java](../../src/test/java/dustin/broadcaster/BitcoinBroadcasterCoreIntegrationTest.java), 3개: 실제 Core 방송, 같은 거래 재조회 복구, 준비·서명·키 조회·지갑 생성 권한 거부.
- 기존 실제 서명 3개와 관찰 전용 준비·주소·입금 판독·응답 제한 회귀 66개를 다시 실행했다.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_broadcast_service.py`: 메서드별 독립 PostgreSQL에서 8개 통과, 실패·오류·제외 0, 컨테이너 정리 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_broadcaster_core.py`: Core31.1 regtest 3개 통과, 외부 연결 0, 노드 종료 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_signer_core.py`: 실제 서명 회귀 3개 통과, 외부 연결 0, 노드 종료 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_payout_preparation.py`: 기존 회귀 66개 통과, 노드 종료 성공.
- 합계 고유 80개/80회이며 실패·오류·제외는 0이다. 고객·운영자·서명·방송 JAR 빌드도 통과했다.

## 남은 한계와 다음 연결

방송 수락은 블록 포함이나 충분한 확인 수가 아니다. 별도 읽기 전용 관찰과 여섯 확인 증거는 [158](158-bitcoin-chain-confirmation.md)에서 구현했다. 이제 이 확정 머리 뒤에만 엔진 `COMPLETE`, 고객 잠금 차감, 고객 수수료 수익과 실제 광부 비용의 회사 분개를 하나의 재실행 가능한 금융 흐름으로 연결한다.

단일 호스트 디스크·지갑 이력 손실은 이 코드만으로 보호하지 못한다. 외부 복제·백업과 복원 훈련은 공통 운영 조건으로 남긴다.

## 관련 커밋과 증적

- [방송 워커 구현 35c2cd1](https://github.com/GiHoon1123/Cex-Platform-Service/commit/35c2cd1)
- [격리 검증 증적](../reference/157-isolated-bitcoin-broadcast-verification-2026-10-27.json)
