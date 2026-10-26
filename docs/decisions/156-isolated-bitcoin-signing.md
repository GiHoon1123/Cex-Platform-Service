# 고정 BTC 요청을 별도 프로세스에서 실제 서명

웹 플랫폼과 다른 실행 파일이 155의 고정 서명 요청만 소비하고, 개인키가 있는 Bitcoin Core 지갑으로 PSBT를 서명한 뒤 원시 거래를 암호화해 불변 저장한다. 준비·서명·방송 권한을 분리했으며 이번 단계는 방송을 수행하지 않는다.

## 문제와 기존 동작

155는 승인·출처·입력 잠금·회사 비용을 결합한 최초 서명 요청을 확정했지만 개인키를 사용하는 실행점은 없었다. 웹 플랫폼에 개인키 지갑 RPC 자격을 추가하면 외부 요청 처리와 키 사용의 장애·침해 범위가 합쳐진다. 반대로 별도 프로세스가 DB 원본을 제대로 인증하지 않으면 승인되지 않은 PSBT를 서명할 수 있다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core31의 `walletprocesspsbt`는 지갑이 서명할 수 있는 입력을 서명하고 완성된 경우 원시 거래를 반환한다. `sign=true`, `sighashtype=ALL`, `bip32derivs=false`, `finalize=true`를 명시했다. [공식 walletprocesspsbt 문서](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/walletprocesspsbt/)의 인자와 결과를 기준으로 한다.

관찰 전용 프로세스는 `walletcreatefundedpsbt`, 서명 프로세스는 `walletprocesspsbt`, 후속 방송 프로세스는 `testmempoolaccept`·`sendrawtransaction`을 맡는 최소 권한 구조를 선택했다. 실제 regtest의 RPC 허용 목록으로 서명 계정의 준비·방송·키 내보내기 요청이 403인지 확인했다. 자료 확인일은 2026-10-27이다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약 |
| --- | --- | --- |
| `bitcoin-signer` 별도 Boot JAR | 웹·Kafka·JPA 없이 키 사용 장애 범위를 분리 | 프로세스와 제한 DB/RPC 계정을 따로 운영해야 함 |
| Core 개인키 지갑만 서명 | Java 프로세스에 비트코인 개인키가 전달되지 않음 | Core 지갑 보호·백업·잠금 해제와 호스트 격리는 운영 책임 |
| 고정 봉투 전용 AES/HMAC 키 | 고객확인 키를 서명 프로세스에 노출하지 않음 | 플랫폼과 서명 프로세스에 별도 봉투 키 두 개를 안전하게 배포해야 함 |
| 서명 결과 불명은 5초 뒤, 시작 후 결과가 없는 시도는 10초 뒤 재시도 | 프로세스 종료 뒤 요청이 영원히 멈추지 않음 | 같은 고정 PSBT에 복수 서명이 생길 수 있으나 P2WPKH witness 밖 거래 ID와 지급 의미는 같음 |
| 서명과 방송 분리 | 서명 응답 유실이 체인 지급 여부 불명으로 번지지 않음 | 실제 출금은 방송·확정 단계까지 완료해야 함 |

현재 구현은 별도 프로세스이지만 같은 EC2 호스트에서 실행하면 호스트 관리자 침해까지 격리하지 못한다. HSM·외부 서명기·별도 호스트는 운영 강화 선택이며 이번 코드가 그 보장을 주장하지 않는다.

## 구현과 데이터 흐름

1. 플랫폼은 고정 실행 원본을 서명 봉투 전용 키로 다시 암호화·인증해 출금 UUID 기반 요청에 저장한다.
2. 서명 프로세스의 단일 소비자가 `FOR UPDATE SKIP LOCKED`로 처리할 요청 하나를 선택하고 불변 시도와 감사를 먼저 커밋한다.
3. DB 트랜잭션 밖에서 별도 Core 계정으로 정확한 PSBT를 `ALL` 서명한다.
4. 완성 거래를 다시 해석해 거래 ID, 입력, 목적지, 순 지급액, 거스름돈, 광부 비용, 비 RBF 순서가 고정 원본과 같은지 확인한다.
5. 원시 거래를 전용 봉투 키로 암호화하고 결과·현재 머리·필수 감사를 같은 커밋에 저장한다.
6. 성공 머리가 있으면 다시 서명하지 않는다. 결과 불명은 대기 후 재시도하고 의미가 다른 응답은 `REJECTED`로 보류한다.

## 실제 코드와 문법

출처: [BitcoinSigningService.java](../../src/main/java/dustin/signer/BitcoinSigningService.java#L22), 현재 소스의 부분 발췌.

```java
    public Optional<Outcome> processNext(){
        Start start=tx.execute(t->start());if(start==null)return Optional.empty();
        try{return Optional.of(finish(start,node.sign(start.payload())));}catch(BitcoinSignerRpcClient.SignFailure failure){return Optional.of(fail(start,failure.failure.name()));}
    }
```

시도 생성과 결과 저장은 서로 다른 트랜잭션이다. 가운데 `node.sign`은 DB 트랜잭션 밖에서 실행된다. `Optional.empty()`는 현재 처리할 요청이 없다는 뜻이며 오류를 성공으로 바꾸지 않는다.

출처: [BitcoinSignerRpcClient.java](../../src/main/java/dustin/signer/BitcoinSignerRpcClient.java#L15), 현재 소스의 부분 발췌.

```java
    public Signed sign(BitcoinPayoutExecutionRecords.Payload execution){
        try{
            var prepared=execution.prepared();var funds=execution.funds();
            if(!settings.network.equals(funds.network())||!settings.network.equals(prepared.network())||!funds.withdrawalId().equals(execution.withdrawalId()))throw rejected();
            var chain=rpc.call("getblockchaininfo",List.of());var wallet=rpc.call("getwalletinfo",List.of());
```

서명 전에 고정 망·출금 ID와 실제 노드/지갑 신원을 확인한다. 이후 완성 거래를 해석해 PSBT를 불투명 성공값으로 취급하지 않는다. 전송 계층은 기존 준비 클라이언트와 공유하지만 RPC 허용 목록과 지갑 자격은 프로세스별 설정이다.

출처: [BitcoinSignerEnvelopeCrypto.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinSignerEnvelopeCrypto.java#L14), 현재 소스의 부분 발췌.

```java
public final class BitcoinSignerEnvelopeCrypto {
    private final byte[] encryption,fingerprint;private final String keyId;
    public BitcoinSignerEnvelopeCrypto(@Value("${bitcoin.signer.envelope-encryption-key:}") String encryption,@Value("${bitcoin.signer.envelope-fingerprint-key:}") String fingerprint){
```

서명 봉투는 AES-GCM 암호화 키와 HMAC 지문 키를 분리한다. 고객확인 `CddCrypto`를 주입하지 않는다. 두 키는 각각 256비트여야 하고 서로 같으면 시작을 거절한다. 운영에서는 다른 서비스 키와도 별도 비밀로 배포해야 한다.

## 작성한 테스트

- [BitcoinSigningServiceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinSigningServiceIntegrationTest.java), 9개: 한 번 서명, 중복 폴링, 결과 불명, 프로세스 종료 뒤 재시도, 의미 거절, 감사 누락 롤백, 다른 거래 ID 거절, 불변성, 두 소비자 경쟁.
- [BitcoinSignerCoreIntegrationTest.java](../../src/test/java/dustin/signer/BitcoinSignerCoreIntegrationTest.java), 3개: 실제 Core31.1 P2WPKH 서명, 잘못된 망 거절, 준비·방송·키 내보내기 권한 거부.
- 기존 준비/주소/입금 판독/제한 본문 회귀 66개: 공통 JSON-RPC 전송부 추출 후 관찰 전용 역할과 DB 트랜잭션 경계 유지.

## 실행 명령과 결과

- `python3 scripts/verify_bitcoin_signing_service.py`: 9개, 실패·오류·제외 0, 메서드별 PostgreSQL DB와 세 JAR 빌드, 컨테이너 정리 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_signer_core.py`: Core31.1 regtest 3개, 실패·오류·제외 0, 외부 연결 0, 노드 종료 성공.
- `CEX_BITCOIN_CORE_HOME=... python3 scripts/verify_bitcoin_payout_preparation.py`: 기존 66개, 실패·오류·제외 0, 관찰 지갑 개인키 없음과 노드 종료 확인.
- 합계 고유 78개/78회 통과다. 개발 중 공통 RPC 추출이 활성 DB 트랜잭션 오류를 일반 503으로 바꾼 회귀 2개를 발견해 기존 즉시 차단 계약으로 수정하고 66개 전체를 재실행했다. 실패한 중간 실행은 최종 합계에서 제외했다.

## 남은 한계와 다음 연결

서명 자체의 다음 연결인 별도 방송과 응답 유실 재확인은 [157](157-isolated-bitcoin-broadcast.md)에서 구현했다. 체인 확정, 엔진 `COMPLETE`, 고객 잠금 차감과 회사 비용 원장 정산은 아직 남아 있으며 이를 연결해야 BTC 출금이 끝난다.

## 관련 커밋과 증적

- [격리 서명 구현 8872356](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8872356d0873f6c58fc4277223ad9fa8612132a1)
- [독립 봉투 키 04d33b7](https://github.com/GiHoon1123/Cex-Platform-Service/commit/04d33b73d83d7842ab0e28f8b777004038a0f16b)
- [격리 실행 증적](../reference/156-isolated-bitcoin-signing-verification-2026-10-27.json)
