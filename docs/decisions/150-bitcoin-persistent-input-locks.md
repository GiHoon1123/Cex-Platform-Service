# 고정 수탁 입력의 영속 잠금과 실제 노드 재시작 검증

미서명 지급 거래가 선택한 수탁 입력을 Core의 영속 잠금으로 자동 코인 선택에서 제외하고, 응답 유실 시 실제 상태를 다시 확인한다. 이 문서는 입력 잠금과 금융 배정 권한의 차이, 실제 노드 재시작 검증, 다음 영속 후보 단계에 필요한 조건을 설명한다.

## 문제와 기존 동작

[149 준비](149-bitcoin-watch-only-payout-preparation.md)는 `lockUnspents=false`로 후보를 계산한다. 따라서 준비 결과만으로 입력 예약을 주장할 수 없다. 반대로 Core 잠금만 먼저 만들면 어느 출금에 귀속되는지 알 수 없고 실패 시 전체 잠금을 풀어 다른 업무를 방해할 수 있다.

이번 단계는 독립적인 노드 입출력 경계를 완성한다. 아직 고객·운영자 API나 지급 워커에서 자동 호출하지 않는다. 영속 후보/입력 배정 저장소는 후속 단계이며, 초안 SQL을 애플리케이션 초기화에 넣거나 기존 실행 확정 경로를 미완성 조건으로 바꾸지 않았다. 실서비스 출금 완성이 아니다.

## 기존 제품과 공식 자료의 접근

2026-10-26 확인한 [Core31 lockunspent](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/lockunspent/)의 기존 영속 잠금 옵션과 [listlockunspent](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/listlockunspent/) 상태 조회를 사용한다. 별도 파일 잠금이나 직접 코인 선택 알고리즘을 만들지 않는다. 잠금은 자동 입력 선택에서 제외하는 기능이며 명시적으로 지정한 입력까지 금융적으로 독점하는 권한은 아니다. 목록에는 입력 식별자만 있고 배정 주인·영속 여부는 없다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 누락 입력만 `persistent=true`로 잠금 | 프로세스 재시작 후에도 새 잠금 복원 | 노드 지갑 DB 쓰기와 조회 비용. 기존 메모리 잠금을 해제/재잠금해 영속화하지 않는다. 그 틈에 입력 선택이 가능하기 때문이다 |
| 변경 전·후 상태 조회 | 응답 유실/거짓 성공을 실제 상태와 구분 | RPC 왕복 증가. 조회 실패는 미해결이며 성공·환급으로 추측하지 않는다 |
| 고정 입력의 교집합만 해제 | 다른 출금의 입력 보존 | 해당 입력의 소유 배정/취소 가능 여부는 후속 DB 업무가 먼저 증명해야 한다 |
| 기존 고정 RPC 클라이언트 재사용 | 자격·본문 상한·기한·엄격 JSON 판독 유지 | 관찰/준비 자격에 잠금 권한을 부여하는 운영 검토가 필요하다. 서명·방송 권한은 추가하지 않는다 |

잠금 목록은 1MiB·10,000개, 한 요청의 입력은 기존 준비 정책의 100개로 제한한다. 초과 시 무시하거나 일부만 승인하지 않는다. 이는 자체 처리 예산이며 법적 한도가 아니다. 지갑 잠금 작성자를 독점 관리하고 DB의 `network+txid+vout` 배정을 금융 권위로 삼아야 한다. 단순 Core 잠금 관측은 배정 증거·지급 허가·완료 증거가 아니다.

## 구현과 실제 코드·원리

`BitcoinPayoutInputLocks`는 고정 망/지갑·중복 없는 outpoint·정확 금액을 확인한다. 잠금 전 현재 미사용 출력의 6확정·비코인베이스·금액·주소·스크립트와 지갑 소유/해결 가능 여부를 다시 대조한다. 해제는 이미 지출된 입력의 정리를 막지 않도록 현재 UTXO 존재 검사를 요구하지 않는다.

출처: [BitcoinPayoutInputLocks.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputLocks.java#L47), 현재 소스의 부분 발췌.

```java
        Set<Point> before=locked();var missing=points.stream().filter(p->!before.contains(p)).toList();
        if(!missing.isEmpty()) {
            try {
                // 기본 메모리 잠금과 구분해 wallet DB에 저장한다. 다른 지갑이나 다른 입력을 숨겨 넣지 않는다.
                var result=rpc.call("lockunspent",List.of(false,missing.stream().map(Point::rpc).toList(),true));
                if(!result.isBoolean()||!result.booleanValue())throw BitcoinRpcClient.unavailable();
            }catch(RuntimeException uncertain){
                // 성공으로 추측하지 않는다. 아래 실제 상태 조회가 실패하거나 한 입력이라도 빠지면 미해결을 전파한다.
            }
        }
        if(!locked().containsAll(points))throw BitcoinRpcClient.unavailable();return observed(points,true);
    }
    /** 미서명/실행 미확정 종료를 DB가 증명한 자기 배정만 전달한다. 빈 목록으로 전체 지갑을 해제하지 않는다. */
```

`stream().filter(...)`는 이미 잠긴 입력을 제외한다. `List.of(false, ..., true)`의 마지막 값이 영속 저장 요청이다. 오류를 잡은 뒤 바로 성공을 반환하지 않고 아래 상태 조회로 전부 잠겼는지 확인한다. 조회 자체가 실패하면 503을 전파한다. 이미 잠겨 있었다는 관측은 해당 잠금의 영속 여부를 추가로 증명하지 않는다.

출처: [BitcoinPayoutInputLocks.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputLocks.java#L60), 현재 소스의 부분 발췌.

```java
    public Observation release(String network,String wallet,List<BitcoinPayoutClient.Input> inputs) {
        var points=validate(network,wallet,inputs);rpc.ready();Set<Point> before=locked();var active=points.stream().filter(before::contains).toList();
        if(!active.isEmpty()) {
            try {
                var result=rpc.call("lockunspent",List.of(true,active.stream().map(Point::rpc).toList()));
                if(!result.isBoolean()||!result.booleanValue())throw BitcoinRpcClient.unavailable();
            }catch(RuntimeException uncertain){
                // 응답 유실을 전체 해제나 DB 배정 삭제로 바꾸지 않는다. 실제 해제가 관측돼야 완료할 수 있다.
            }
        }
        if(locked().stream().anyMatch(points::contains))throw BitcoinRpcClient.unavailable();return observed(points,false);
    }
    private List<Point> validate(String network,String wallet,List<BitcoinPayoutClient.Input> inputs) {
```

`before::contains`는 현재 잠금과 요청 입력의 교집합을 만든다. 빈 배열을 전체 지갑 해제로 해석하는 RPC를 막기 위해 교집합이 비어 있으면 변경 요청을 보내지 않는다. `anyMatch`로 하나라도 남았는지 확인하고 남았다면 완료를 거절한다. TypeScript의 배열 `filter`/`some`과 흐름은 비슷하지만 Java 결과의 고정 목록과 `record`는 타입·불변성 경계를 명시한다.

`Observation`은 목록을 복사하고 원문이 나오는 기본 `toString`을 대체한다. 관측 시각은 이 프로세스가 상태를 확인한 시각이며 블록 확정 시각이나 DB 확정 시각이 아니다. 실제 DB 트랜잭션이 열린 상태에서는 노드 호출을 거절한다. 네트워크 대기로 고객/원장 락을 붙잡지 않기 위한 경계다.

## 테스트 시나리오와 기대 결과

| 테스트 파일 | 시나리오 | 기대 결과 |
| --- | --- | --- |
| BitcoinPayoutInputLocksTest, 18개 | 새 영속 잠금, 반복 조회, 일부 기존 잠금, 다른 입력 보존 | 누락 입력에만 영속 요청, 고정 목록 유지 |
| 같은 파일 | 변경 응답 유실/false, 변경 없는 true, 변경 후 조회 실패 | 실제 상태가 확인될 때만 성공, 조회 불명은503 |
| 같은 파일 | 해제 응답 유실, 해제 실패, 잠금 없음 | 자신의 입력만 해제, 전체 해제 금지, 미해제 보존 |
| 같은 파일 | 지출됨/미성숙/코인베이스/다른 금액·주소·스크립트/비소유, 잘못된 잠금 목록 | 변경 RPC 전에 거절 |
| 같은 파일 | 다른 망/지갑, 빈·중복 입력, 부정확·극단 금액, 열린 DB 트랜잭션 | 노드 변경 금지, 트랜잭션은 호출 자체 금지 |
| BitcoinPayoutInputLocksCoreIntegrationTest, 동일 메서드 2단계 실행 | 실제 제품 클라이언트 잠금 → 소유 노드 종료/재시작 → 복원 관측 → 범위 해제 | 재시작 전후 자동 코인 선택 실패, 해제 후 정확한 NET 거래 준비 성공 |

실제 Core 시험에서 시험 실행기만 관리자 쿠키를 읽는다. Java의 잠금 계정은 관찰·준비·고정 입력 잠금 RPC만 허용한다. 실제 노드가 서명/방송/키 반출/지갑 관리 9종을403으로 거절하는 것도 실행기에서 확인한다. 기존 준비 회귀는 잠금 권한 없는 별도 계정으로 실행해 기존 권한 경계를 유지했다.

## 실행 명령과 결과

- `CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin_payout_input_locks.py`
- 실행 ID `5c23f1c04f8d`, Core31.1 실제 버전310100, regtest, 외부 연결0, 관찰 지갑 개인키 비활성.
- 신규 단위18 + 기존 준비22/실제 Core9/공통 노드34 =83회, 노드 재시작 전/후 동일 실제 메서드 각각1회 = **85회/고유84개, 실패·오류·제외0**. 재시작 전/후는 하나의 메서드 두 번 실행한 것으로 구분한다.
- `bootJar`, `operatorAdminJar` 성공. 기존 deprecated/unchecked 및 JVM 경고는 남아 있다. 이번 변경과 무관한 전체 금융/보안 묶음을 재실행했다고 주장하지 않는다.
- 개발 중 합성 HTTP 시험의 switch 표현식에서 외부 return을 사용한 컴파일 오류를 수정했다. 이후 최종83회 묶음 및 실제 두 단계가 통과했다. 최초 단위18회는 최종 합계에 중복 합산하지 않았다.
- 소유 Core 종료 확인, 임시 개인키 디렉터리는 TemporaryDirectory 종료로 제거. 기존 DB·Kafka·다른 프로젝트 컨테이너는 사용하지 않았다. 실제 고객 금액 변경·서명·방송은 없다.

## 남은 경계

영속 후보와 전역 입력 고유 배정, 외부 호출 전 시도 기록·후 관측 보존, 현재 승인·취소와의 배타적 실행 확정은 다음 단계다. DB와 Core는 원자 커밋이 아니다. Core에 이미 잠금이 있다는 이유로 DB 배정을 재구성하거나, 노드 오류/시간 초과로 예약을 지우거나 환급해서는 안 된다. 디스크·호스트 손실의 외부 복제/백업은 기존 방침대로 운영 필요 조건을 문서로만 유지한다.

## 작성한 테스트 코드


[BitcoinPayoutInputLocksTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputLocksTest.java)

- `aNewLockRequestsPersistenceAndObservesTheExactInput`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatEnsureReadsExistingLocksWithoutUnlockingOrMutating`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `existingOtherLocksAreNeitherAdoptedNorReleased`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `onlyMissingInputsAreSentToCore`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostLockResponseRequiresAnActualStateObservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `falseAcknowledgementIsResolvedByActualState`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aSuccessfulAcknowledgementWithNoStateChangeCannotClaimALock`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnreadablePostMutationStateStaysUnknown`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anUnreadableInitialStateCannotStartAMutation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostUnlockResponseIsResolvedWithoutClearingForeignLocks`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `absentLocksNeverSendTheWalletWideUnlockForm`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `failedUnlockLeavesTheInputHeld`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `spentOrUnsafeOrForeignInputsCannotBeLocked`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `malformedLockListsFailBeforeMutation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongChainOrPrivateKeyWalletCannotBeMutated`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `wrongScopeEmptyAndDuplicateInputsNeverReachTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `invalidAmountsAndOutpointsNeverReachTheNode`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `databaseTransactionsCannotHoldLocksAcrossNetworkIo`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinPayoutInputLocksCoreIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinPayoutInputLocksCoreIntegrationTest.java)

- `ownedInputLocksSurviveCoreRestartAndOnlyTheirReleaseRestoresCoinSelection`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 80f8e73](https://github.com/GiHoon1123/Cex-Platform-Service/commit/80f8e733fc3cf01ace943b2ebca9a40330ce1e0d)
- [격리 실행 증적](../reference/150-bitcoin-persistent-input-locks-verification-2026-10-26.json)
