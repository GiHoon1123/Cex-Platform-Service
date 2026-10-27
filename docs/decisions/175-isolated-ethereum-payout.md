# 독립 승인과 별도 프로세스의 ETH 지급·확정

엔진에 예약된 ETH 출금을 담당자 제안과 다른 승인자의 내용 결합 WebAuthn 서명으로 확정하고, 고객 웹 플랫폼 밖의 전용 프로세스가 서명 RPC를 호출한다. 별도 읽기 프로세스가 정식 체인 확정과 실제 가스 비용을 확인한 뒤에만 엔진 COMPLETE와 고객 원장 정산을 진행한다.

## 문제와 기존 동작

174에서는 고객 총액을 안전하게 예약했지만 체인 지급은 의도적으로 닫혀 있었다. 예약 이벤트만 보고 웹 플랫폼이 `eth_sendTransaction`을 호출하면 인터넷 노출 프로세스가 키 사용 권한까지 갖고, 단일 운영자가 고객 출금을 바로 실행하며, 체인 포함 전에 고객 잠금을 차감할 수 있다.

## 기존 제품과 공식 자료의 접근

- [Ethereum JSON-RPC](https://ethereum.org/developers/apis/json-rpc/)의 `eth_sendTransaction`, `eth_getTransactionReceipt`, `eth_getBlockByNumber`, `eth_blockNumber` 계약을 사용한다.
- [EIP-1474](https://eips.ethereum.org/EIPS/eip-1474)의 16진 수량을 `BigInteger`로 읽어 가스 사용량·가격·확정 깊이를 계산한다.
- [Geth 보안 문서](https://geth.ethereum.org/docs/fundamentals/security)는 JSON-RPC 노출과 계정 권한을 최소화하도록 권고한다. 고객/어드민 웹과 서명 RPC 자격을 같은 프로세스에 두지 않는다.
- 로컬 실제 체인 검증에는 [Foundry Anvil](https://getfoundry.sh/anvil/overview/)을 사용했다. Anvil의 잠금 해제 개발 계정을 운영 키 보호 완료로 해석하지 않는다.

## 선택과 트레이드오프

`EthereumSignerApplication`과 `EthereumConfirmerApplication`은 웹·Kafka·JPA 자동 구성을 제외한 일반 JVM 프로세스다. 전자는 서명 RPC만, 후자는 읽기 RPC만 가진다. 실행 파일과 비밀·OS 계정을 별도로 운영해야 하는 비용이 생기지만 고객 HTTP 침해가 곧 키 사용 권한이 되는 경로를 없앤다.

송신 RPC 응답이 유실되면 새 nonce로 자동 재전송하지 않고 `UNKNOWN`으로 보류한다. 단순 자동 재시도보다 수동 조사 부담이 크지만 이중 지급을 피한다. 운영 단계에서는 서명 전 고정 nonce와 원시 거래 해시를 영속화하는 보호 서명 서비스를 붙여 불명 상태를 안전하게 조회해야 한다.

고객 지급액과 0.01% 수수료는 공통 9자리 원장에 정산한다. 실제 가스 비용은 wei 정확성을 잃지 않도록 별도 18자리 균형 기록의 `CUSTODY`와 `EXPENSE`에 남긴다. 원장이 둘로 나뉘는 조회 비용을 감수하고 반올림으로 수탁 차이를 숨기지 않는다.

## 구현과 관련 코드

- `EthereumPayoutReviews`: 예약 원본, 담당자·지정 승인자, 사유, 만료를 한 해시에 결합한다. 승인자는 최근 열람과 업무별 WebAuthn 서명이 필요하다.
- `EthereumSignerApplication`/`EthereumPayoutService`: 현재 역할·인물·버전이 그대로인 승인만 읽고, 체인 ID를 다시 확인한 뒤 고정 송신 주소로 지급한다.
- `EthereumConfirmerApplication`/`EthereumConfirmationService`: 제출 거래의 성공 영수증, 현재 정식 블록 해시, 요구 확정 깊이, `gasUsed × effectiveGasPrice`를 확인한다.
- `EthereumCompletionDispatches`: 체인 확정이 있는 출금만 동일 UUID와 총액으로 엔진 COMPLETE를 전송한다.
- `BitcoinWithdrawalEvents.completeEthereum`: 이름은 기존 공통 금융 소비자를 유지하지만 ETH 완료는 별도 체인 확정·원장·가스 원본에만 반영한다.

실제 작성한 코드 일부다.

```java
if (!blockHash.equals(canonical.path("hash").asString())) {
    throw new IllegalStateException("이더리움 지급 블록이 현재 정식 체인과 다릅니다");
}
```

영수증에 블록 번호가 있다는 사실만으로 완료하지 않는다. 같은 높이를 현재 읽어 해시가 일치해야 하므로 재조직으로 사라진 지급을 완료 처리하지 않는다.

```java
BigInteger gas = quantity(receipt.path("gasUsed").asString())
    .multiply(quantity(receipt.path("effectiveGasPrice").asString()));
BigDecimal fee = new BigDecimal(gas, 18);
```

`BigInteger` 곱은 JavaScript `number`와 달리 정수 정밀도를 잃지 않는다. 마지막에 18자리 ETH 단위로 표현하며 반올림하지 않는다.

## 작성한 테스트

- `EthereumAnvilIntegrationTest.sendsNativeEtherAndReadsCanonicalReceiptWithoutFloatingPoint`: 실행기 소유 실제 Anvil에서 1 ETH 송신, 성공 영수증, 정식 블록 해시, 정확한 잔액 증가를 검증한다.
- `IsolatedProcessBoundaryTest.ethereumSignerLoadsNoCustomerWebOrKafkaBoundary`: 서명 프로세스에 HTTP·Kafka·JPA·확인 워커가 없음을 검증한다.
- `IsolatedProcessBoundaryTest.ethereumConfirmerHasNoSignerOrCustomerWebBoundary`: 확인 프로세스에 서명·HTTP·Kafka·JPA 권한이 없음을 검증한다.
- 기존 ETH 금액·RPC·예약 이벤트 9개와 등록 API 보안 42개를 함께 재실행했다.

## 실행 명령과 결과

```text
./gradlew test --tests 'dustin.cex.domains.ethereum.*' \
  --tests 'dustin.runtime.IsolatedProcessBoundaryTest' \
  bootJar operatorAdminJar ethereumSignerJar ethereumConfirmerJar --no-daemon --console=plain
python3 scripts/verify_ethereum_rpc.py
python3 scripts/verify_platform_security.py
```

ETH 단위 9개와 프로세스 경계 6개가 통과했고, 실제 Anvil 1개와 격리 PostgreSQL 보안 42개가 실패·오류·제외 없이 통과했다. 고객/운영자/ETH 서명/ETH 확인 JAR 네 개가 생성됐다.

## 관련 커밋과 참고 자료

구현·테스트·문서를 같은 기능 커밋에 기록한다. 운영 메인넷, HSM 또는 보호 서명 제품, 실제 복수 인력, 별도 장애 영역 읽기 노드, 호스트 손실 복원은 코드 검증과 구분한 배포·운영 과제다. 디스크 자체 손실을 막으려면 별도 복제·백업이 필요하다.
