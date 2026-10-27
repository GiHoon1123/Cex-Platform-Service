# 이더리움 확정 블록 입금 관찰과 엔진 적립

ETH/USDT 거래에 사용할 고객별 입금 주소를 배정하고, 확정 블록의 네이티브 ETH 전송을 정확한 wei 단위로 관찰한 뒤 Rust 엔진의 영속 적립 명령으로 연결한다. 이 문서는 웹 플랫폼이 개인키를 소유하지 않는 경계, 재조직 시 실패 폐쇄 동작, 현재 공통 잔액 정밀도의 한계를 설명한다.

## 문제와 기존 동작

플랫폼과 엔진은 ETH 거래쌍을 처리할 수 있었지만 체인 입금 원본이 없었다. 단순히 최신 블록만 조회하거나 플랫폼 DB 잔액을 직접 올리면 블록 누락, 재조직, 중복 적립, HTTP 응답 유실을 구분할 수 없다. 웹 프로세스가 고객 입금 개인키까지 만들면 인터넷 노출 업무와 키 사용 권한도 한 장애 영역에 놓인다.

## 기존 제품과 공식 자료의 접근

- [Ethereum JSON-RPC 명세](https://ethereum.org/developers/apis/json-rpc/)의 `eth_chainId`, `eth_getBlockByNumber`, `eth_getTransactionReceipt`와 16진 수량 규칙을 사용한다.
- [EIP-1474](https://eips.ethereum.org/EIPS/eip-1474)의 수량·데이터 표현을 따라 선행 0이 있는 수량을 거부하고 부동소수점 변환을 하지 않는다.
- [Geth JSON-RPC 일괄 처리와 구독 문서](https://geth.ethereum.org/docs/interacting-with-geth/rpc/batch)는 과거 블록 대량 조회와 구독의 재조직 주의를 설명한다. 현재 구현은 단순하고 재현 가능한 순차 폴링을 선택했다.
- [Foundry Anvil 문서](https://getfoundry.sh/anvil/overview/)의 로컬 개발 노드는 이후 실제 체인 경계 통합 검증에 사용한다.

## 선택과 트레이드오프

플랫폼은 한 번에 다음 확정 블록만 읽고, 저장한 이전 블록 해시와 새 블록의 부모 해시가 다르면 전진하지 않는다. 이 방식은 일괄 조회보다 따라잡기가 느리지만 누락 위치와 재조직 대응 지점이 명확하다. 모토인 “거래 처리는 빠르게, 복구는 느려도 안전하고 정확하게”에 맞는다.

입금 주소는 외부 키 관리 계층에서 만든 공개 주소 풀만 설정으로 받는다. 웹 플랫폼이 개인키를 생성하거나 보관하지 않는 대신 주소 풀이 소진되면 신규 발급은 503으로 실패한다. 운영자는 키 보관·백업·주소 보충을 별도 보호 영역에서 수행해야 한다.

Ethereum은 18자리 소수 단위인 wei를 사용하지만 현재 공통 원장과 Rust `Decimal` 계약은 9자리까지다. 관찰 원본은 `NUMERIC(30,18)`에 정확히 저장하고, 9자리를 넘는 입금은 반올림하거나 버리지 않고 `ENGINE_PRECISION_LIMIT` 보류 이력으로 남긴다. 이는 자산 손실을 피하는 안전한 제한이지만 1 gwei 미만 단위가 포함된 입금을 즉시 거래 잔액으로 쓸 수 없다는 비용이 있다. 전체 잔액 계약을 18자리로 확대할 때에는 엔진 저널, 플랫폼 원장, DB 열, 주문·수수료·대사 전부를 함께 바꿔야 한다.

## 구현과 관련 코드

- `EthereumSettings`: 고정 RPC 주소, 체인 ID, 시작 블록, 확정 깊이, 외부 생성 주소 풀을 검증한다.
- `EthereumSchemaInitializer`: 실제 노드의 체인 ID와 제네시스 해시를 DB에 한 번 결합한다. 재시작 때 다른 체인을 가리키면 시작하지 않는다.
- `EthereumDepositAddressService`: 현재 고객 세션과 금융 자격을 확인하고 `FOR UPDATE SKIP LOCKED`로 미배정 주소 하나만 배정한다.
- `EthereumDepositService`: 확정 블록을 순서대로 읽고 영수증의 성공 상태·블록 해시를 재검증한다. 입금 원본과 관찰 위치는 같은 DB 트랜잭션에서 확정한다.
- `BitcoinCreditedEvents`: 이름은 기존 BTC 소비자를 유지하지만 허용 자산을 BTC/ETH로 명시하고, 각 자산의 영속 실행 권한과 수신 표를 선택한다. Kafka 이벤트만으로 임의 입금을 만들 수 없다.

실제 작성한 코드 일부다. 독립 실행 예제가 아니다.

```java
if (!next.equals(quantity(block.path("number").asString())) || !parent.equals(before.hash)) {
    throw new IllegalStateException("이더리움 체인 재구성을 확인해야 합니다");
}
```

`BigInteger`는 JavaScript `number`처럼 큰 정수를 이진 부동소수점으로 바꾸지 않는다. 다음 블록 번호와 부모 해시가 저장 위치에 정확히 이어질 때만 관찰을 진행한다. 재조직을 자동으로 추측해 되감지 않으므로 운영 검토 없이 다른 체인 이력을 덮지 않는다.

```java
if (deposit.amount.stripTrailingZeros().scale() > 9) {
    jdbc.update("INSERT INTO ethereum_deposit_holds(deposit_id,reason) VALUES(?, 'ENGINE_PRECISION_LIMIT')", deposit.id);
}
```

`stripTrailingZeros()`는 값이 같은 불필요한 0을 제거한 뒤 실제 필요한 소수 자릿수를 알려준다. `1.000000000000000000 ETH`는 보류하지 않지만 `0.000000000000000001 ETH`는 보류한다. 반올림은 없다.

## 작성한 테스트

- `EthereumSettingsTest.acceptsLoopbackNodeAndCanonicalizesExternallyProvisionedAddresses`: 로컬 노드·체인·주소 정규화를 검증한다.
- `EthereumSettingsTest.rejectsPlainHttpRemoteNodeAndDuplicateAddresses`: 평문 원격 RPC와 중복 주소 풀을 거부한다.
- `EthereumSettingsTest.parsesEthereumQuantitiesWithoutFloatingPointConversion`: 1 ETH의 wei 수량과 비정규 16진 수량 거부를 검증한다.
- `EthereumJsonRpcClientTest.acceptsOnlyResultBoundToTheRequestIdentifier`: 실제 로컬 HTTP 서버에서 요청 ID와 결과 결합을 검증한다.
- `EthereumJsonRpcClientTest.rejectsRpcErrorsInsteadOfTreatingThemAsEmptyResults`: RPC 오류·다른 응답 ID를 성공으로 취급하지 않는다.
- 기존 `PlatformSecurityTest` 전체를 다시 실행해 신규 고객 API가 미인증·역할 경계를 우회하지 않는지 확인했다.

## 실행 명령과 결과

```text
./gradlew test --tests 'dustin.cex.domains.ethereum.*' \
  --tests 'dustin.cex.domains.security.PlatformSecurityTest' \
  bootJar operatorAdminJar --no-daemon --console=plain
```

신규 5개와 플랫폼 보안 42개, 총 47개가 실패·오류·제외 없이 통과했고 고객/운영자 JAR 두 개가 생성됐다. 실제 Anvil→Rust WAL→Kafka→플랫폼 원장 전체 연결과 메인넷 노드, 외부 키 관리·호스트 손실 복원은 이 단위 검증의 범위가 아니다.

## 관련 커밋과 참고 자료

커밋은 이 문서와 구현·테스트를 함께 기록한다. 외부 디스크·호스트 손실에는 별도 복제와 백업이 필요하며 이번 코드가 이를 구현했다고 주장하지 않는다.
