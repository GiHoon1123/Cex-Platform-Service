# 블록 조회의 전체 완료만 진행 위치로 확정하기

등록 주소의 모든 묶음을 조회한 블록만 완료로 기록한다. 프로세스가 다시 생성돼도 같은 DB의 완료 위치에서 바로 다음 블록을 읽는다. 이 문서는 실제 블록 수신 발견에 영속 진행 위치를 연결한 범위와 실패 시 보존 계약을 설명한다. 고객 잔액 적립이나 자동 스케줄러를 완성했다는 기록이 아니다.

## 문제와 기존 동작

079의 `BitcoinBlockDiscovery.scan`은 한 블록/최대200개 조건의 정확한 수신을 반환했지만 다음에 어디를 읽을지 영속적으로 관리하지 않았다. 조회 성공을 메모리의 높이로만 관리하면 재시작에 누락/불필요한 재조회가 생기며, 두 실행이 겹치거나 뒤쪽 주소 묶음에서 실패했을 때 잘못 전진할 수 있다. 같은 높이도 재조직으로 다른 블록일 수 있다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core31의 [getdescriptoractivity](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/getdescriptoractivity/)는 지정한 정규 체인 블록의 수신/지출을 판독한다. [getblockhash](https://bitcoincore.org/en/doc/31.0.0/rpc/blockchain/getblockhash/)의 현재 높이→블록 해시와 실제 부모 해시를 함께 대조한다. 제품 내부의 숨겨진 동작을 추측하지 않고 공개 RPC와 실제31.1 동작을 사용한다.

PostgreSQL16 [트랜잭션 자문 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)은 같은 지갑의 짧은 DB 확정 구간만 직렬화한다. 긴 노드 조회는 트랜잭션 밖에서 수행하고, 확정 직전에 시작 당시 진행 위치가 그대로인지 검사한다. 이 플랫폼 DB 잠금은 Rust 체결 코어와 무관하다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용/제약·대안 |
| --- | --- | --- |
| 최초 블록부터 연속 조회 | 최초 시작점을 추측하거나 과거 입금을 건너뛰지 않음 | 실제 MAINNET 과거 전체를 읽는 비용이 크고 블록/undo 보관이 필요. 검증된 초기 반입 기준점은 아직 도입하지 않음 |
| 한 블록의 전체 주소를200개씩 조회한 뒤 한 번에 확정 | 일부 주소만 확인하고 높이를 넘기지 않음 | 뒤 페이지 장애면 그 블록 전체 재조회. 독립 페이지 체크포인트보다 상태가 단순함 |
| 전체 등록 목록의 불변 원본을 해시로 재사용 | 실제 조회 조건과 소유 ID를 설명/재현하며 동일 목록의 블록별 중복 저장을 줄임 | 주소 목록을 한 번에 읽는 메모리/직렬화는 O(등록 주소 수). 목록 변경마다 전체 원본을 저장하므로 등록 빈도와 저장 비용을 측정해야 함 |
| 기준 tip을 주소 목록보다 먼저 고정 | 이미 공개된 주소의 입금을 빠뜨리는 조회 창을 방지 | 조회 중 tip이 바뀌면 다시 실행. 주소를 비공개 단계에서 외부로 유출하는 경로는 이 보장의 전제가 아님 |
| 재조직 발견 시 중단 | 과거 완료/금융 근거를 덮어쓰거나 잘못 이어가지 않음 | 현재 자동 되감기/재검증은 후속. 중단을 재조직 복구 완료라고 부르지 않음 |
| 완료 원본·현재 위치·감사 같은 TX | 감사 장애/프로세스 실패로 일부만 완료되지 않음 | 짧은 확정 구간 경합. 늦은 동시 실행은409로 실패하며 결과를 덮어쓰지 않음 |

공식 RPC는 오래 걸릴 수 있다고 안내한다. 기존 전송 제한5초를 유지하므로 실제 큰 블록/주소 수에서는 시간 초과가 날 수 있다. 이때 빈 결과/완료로 바꾸지 않고 전진을 멈춘다. 운영 수탁 노드의 자원/시간 예산을 측정해야 하며 현재 regtest 시간으로 MAINNET 처리 성능을 주장하지 않는다.

## 구현과 데이터 흐름

1. 외부 DB 트랜잭션 호출을 거부하고 관찰 전용 노드의 네트워크/지갑과 기준 tip을 확인한다.
2. 짧은 반복 읽기 스냅샷에서 현재 진행 위치와 네트워크의 모든 등록 주소를 얻는다. 다른 지갑 소유가 섞이면 중단한다.
3. 기존 완료 해시가 아직 정규 체인인지 검사한다. 주소가 없으면 진행 위치를 만들지 않는다. tip까지 완료면 새 원본을 만들지 않는다.
4. 최초 높이0 또는 마지막 완료 높이+1을200개 이하 묶음으로 모두 읽는다. 모든 묶음의 블록/부모/tip/네트워크가 같아야 한다. 출력은 주소 ID와 거래 ID/출력 번호/정확한 금액으로 연결한다.
5. 마지막 묶음 뒤에 정규 해시와 tip을 다시 확인한다. 그 사이 체인이 바뀌면 실패한다.
6. DB 확정 잠금을 잡고 시작 위치를 비교한다. 주소 목록 원본·블록 결과·현재 위치·감사를 함께 확정한 뒤 반환한다.

마지막 노드 확인과 DB 커밋 사이에 체인이 바뀔 가능성을 완전히 없앴다고 주장하지 않는다. 다음 실행에서 저장 해시를 다시 확인하며, 미래 적립 정책도 최신 관찰과 재조직 처리를 별도로 요구한다. 이 블록 완료만으로 자산을 지급하지 않는다.


출처: [BitcoinScanService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinScanService.java#L40), 현재 소스의 부분 발췌.

```java
        var initial=rpc.ready().chain();String tip=hash(initial.path("bestblockhash"));long tipHeight=height(initial.path("blocks"));
        var snapshot=snapshotTx.execute(t->new Snapshot(head(),jdbc.query("""
            SELECT id,user_id,script_pub_key,wallet FROM bitcoin_deposit_addresses WHERE network=? ORDER BY id
            """,(row,n)->new Address(row.getObject("id",UUID.class),row.getLong("user_id"),row.getString("script_pub_key"),row.getString("wallet")),settings.network.id)));
        if(snapshot.addresses().stream().anyMatch(a->!a.wallet().equals(settings.wallet)))throw unavailable();
        Head expected=snapshot.head();
        if(expected!=null){
```

`record`는 이 호출이 사용할 값 묶음이다. `REPEATABLE_READ`의 짧은 트랜잭션이 주소 목록/시작 위치를 읽고 종료한 다음 RPC를 수행한다. TypeScript 객체와 달리 Java record의 구성 요소 참조는 재할당할 수 없지만 목록의 깊은 불변성까지 자동 보장하지는 않는다. 여기서는 JDBC가 반환한 목록을 외부로 공개하지 않는다.

출처: [BitcoinScanService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinScanService.java#L54), 현재 소스의 부분 발췌.

```java
        for(int offset=0;offset<snapshot.addresses().size();offset+=200){
            // 한 번에 조회하는 수신 조건은 제한하지만 전체 주소를 조용히 자르지 않는다. 어느 페이지라도 실패하면 진행하지 않는다.
            var page=snapshot.addresses().subList(offset,Math.min(offset+200,snapshot.addresses().size()));
            var byScript=new HashMap<String,Address>();for(var address:page)byScript.put(address.scriptPubKey(),address);
            var current=discovery.scan(next,page.stream().map(Address::scriptPubKey).toList());
            if(!current.network().equals(settings.network.id)||current.height()!=next||!current.tipHash().equals(tip)||current.tipHeight()!=tipHeight
                    ||(block!=null&&(!block.hash().equals(current.hash())||!Objects.equals(block.previousHash(),current.previousHash())))
                    ||(expected!=null&&!expected.hash().equals(current.previousHash())))throw unavailable();
            block=current;
            for(var output:current.received()){
                var address=byScript.get(output.scriptPubKey());
                if(address==null||!seen.add(output.txid()+":"+output.vout()))throw unavailable();
                receives.add(new Receive(address.id(),output.txid(),output.vout(),output.amount()));
            }
```

`subList`는 같은 읽기 목록의 일부다. 각 묶음의 조건에서 찾은 출력만 해당 주소 ID에 연결하며 중복 txid/vout를 거부한다. 마지막 묶음까지 모두 성공하지 않으면 DB 완료 원본을 생성하지 않는다.

출처: [BitcoinScanService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinScanService.java#L76), 현재 소스의 부분 발췌.

```java
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-scan-v1:"+settings.network.id+":"+settings.wallet);
            if(!Objects.equals(head(),expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 조회가 먼저 확정되어 다시 실행해야 합니다");
            // 같은 불변 주소 목록은 한 번만 저장한다. 해시가 같아도 원문이 다르면 재사용하지 않는다.
            jdbc.update("""
                INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES(?,?,?,?::jsonb)
                ON CONFLICT(network,wallet,digest) DO NOTHING
                """,settings.network.id,settings.wallet,digest,registry);
            var registries=jdbc.queryForList("SELECT id FROM bitcoin_scan_registries WHERE network=? AND wallet=? AND digest=? AND addresses=?::jsonb",
                settings.network.id,settings.wallet,digest,registry);
```

조회 중에는 잠금을 잡지 않는다. 같은 시작 위치를 읽은 두 실행 중 첫 확정만 성공하고 나머지는409로 돌아간다. 실패한 트랜잭션의 원본/현재 위치/감사는 모두 롤백된다. 이는 Java 플랫폼의 입출력 업무이며 엔진 코어 상태를 공유하지 않는다.

출처: [BitcoinScanService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinScanService.java#L91), 현재 소스의 부분 발췌.

```java
            // 삽입 트리거는 충돌 갱신보다 먼저 실행된다. 최초 생성과 기존 진행을 명시적으로 나눠 각 DB 계약을 지킨다.
            if(expected==null)jdbc.update("INSERT INTO bitcoin_scan_heads(network,wallet,scan_id) VALUES(?,?,?)",settings.network.id,settings.wallet,id);
            else jdbc.update("UPDATE bitcoin_scan_heads SET scan_id=? WHERE network=? AND wallet=?",id,settings.network.id,settings.wallet);
            audit.record(null,"BTC_BLOCK_SCANNED","bitcoin:"+settings.network.id+":"+settings.wallet,"SUCCESS","SCAN:"+id);
            return new Completed(id,next,verified.hash(),receives.size());
```

PostgreSQL의 INSERT ON CONFLICT는 충돌 갱신 전에도 삽입 전 트리거를 실행한다. 최초 생성의 높이0 계약과 이후 갱신의 부모 연결 계약을 별도 명령으로 지킨다. 아래 실패 기록은 이 검사를 실제 시험에서 발견해 수정한 내용이다.

## DB 보장

[스키마](../../src/main/resources/bitcoin/schema.sql)는 주소 목록과 완료 원본의 수정/삭제/전체 비우기를 거부한다. 현재 위치도 삭제하지 않는다. 현재 위치는 같은 트랜잭션의 새 원본으로만 갱신하고 네트워크/지갑·직전 ID·높이+1·부모 해시가 모두 이어져야 한다. 원본 ID의 숫자가 연속할 필요는 없다. 롤백된 PostgreSQL sequence 값은 재사용하지 않으므로 높이와 ID를 혼동하지 않는다.

수신 목록은 현재 원본의 JSON이며 별도 고객 입금 영수증/영속 중복 수신 키는 아직 아니다. 후속 적립은 네트워크/txid/vout의 안정 입금 ID와 주소의 불변 소유를 다시 연결해야 한다. 자료를 저장했다는 이유만으로 거래 가능 잔액을 늘리지 않는다.

## 작성한 테스트

[BitcoinScanIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinScanIntegrationTest.java)는 실제31.1 regtest와 별도 PG16에서 다음12개를 실행한다. 앞 단계의 확정 이력을 지우지 않고 순서가 있는 실제 진행 시나리오로 다음 단계를 검증한다. 실패 주입은 Spring의 실제 서비스 경계에만 적용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| noAddressDoesNotAdvanceThenGenesisCommitsSnapshotSourceHeadAndAudit | 주소 없으면 위치 없음, 등록 후 최초 블록/목록/감사 같은 확정, 잔액0 |
| freshServiceResumesCommittedNextBlockAndSharesRegistry | 새 서비스 객체가 DB에서 재개, 높이1/부모 일치/목록 원본 재사용 |
| secondPageFailureNeverSkipsAddressesOrCommitsPartialBlock | 201주소의 두 번째 묶음 장애면 전체 블록 미확정, 재시도 후 목록201/완료 |
| auditFailureRollsBackCompletedSourceAndHead | 감사 오류 시 원본 수와 현재 위치 모두 유지 |
| concurrentScansHaveOneWinnerAndLoserCannotOverwriteHead | 같은 시작 위치 두 실행에서 하나만 성공, 나머지409, 원본 한 개 증가 |
| wrongParentOrPageTipNeverChangesCheckpoint | 다른 부모 또는 tip이면503/원본·위치 보존 |
| surroundingDatabaseTransactionIsRejectedBeforeNode | 외부 TX 거부, RPC 호출0회 |
| originalRegistriesBlocksAndCurrentPointerCannotBeChangedDeletedOrReactivated | 원본 변경/삭제/비우기와 과거 위치 재활성 거부 |
| allRemainingRealBlocksFindOutpointAndTipIsIdleWithoutDuplicateSource | 실제 남은 블록 모두 조회,0.12345678BTC 출력 하나, tip 반복 호출에 중복 원본/원장·잔액 없음 |
| changedCanonicalHashStopsWithoutReplacingOriginalCheckpoint | 저장 높이 정규 해시가 다르면409, 기존 원본 보존 |
| unavailableNodeDoesNotProduceIdleSuccess | 노드 장애를 정상 대기 결과로 바꾸지 않고503 |
| databaseGuardRejectsFreshSourceThatSkipsBlock | 직접 SQL의 두 블록 점프도 트리거 거부/전체 롤백 |

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1/PG16에서 **기존64개+신규12개=고유76개 통과**, 최종 실패/오류/제외0개다. addresses53/observations11/scans12는 별도 PG 서버에서 순서대로 실행했다. bootJar/operatorAdminJar를 빌드하고 소유 노드 종료/PG3개 제거를 확인했다. 봇0/peer0/regtest/외부 주소 격리를 유지했다. 기존 deprecated/unchecked/JVM 계측 경고가 있으며 실제 MAINNET/OSV 감사/성능 벤치는 실행하지 않았다.

첫 실행은 기존64개 통과 후 신규12개 중5개가 충돌 갱신의 삽입 전 트리거에 걸렸다. 최초 생성과 갱신을 나눠 수정한 뒤 새 노드/새 DB로76개를 다시 실행했다. 첫 실패를 삭제하거나 최종 통과 수에 합산하지 않는다. 새 객체로 재시작 경계를 검증했으며 운영 프로세스 강제 종료/실제 체인 재조직을 자동 복구하는 검증은 아직 아니다.

## 남은 연결과 운영 한계

`scanNext`는 내부 단일 블록 업무다. 자동 실행 워커·재조직 공통 조상 복구·출력별 안정 입금 ID·독립 거래 관찰/확정/성숙도·고객 원장/엔진 HTTP/Kafka 연동은 후속이다. 중단한 재조직에서 기존 원본을 변경하거나 플랫폼 DB로 추측 복구하지 않는다. 기존 거래 기능/엔진 코어를 롤백하지 않았다.

큰 주소 목록/오래된 체인의 메모리·디스크·RPC 시간을 운영 환경에서 측정해야 한다. 노드가 필요한 과거 블록/undo를 보관해야 한다. 관찰 전용/서명 경계·사업 신고/심사·외부 복제/백업은 별도 운영 확인이며 이번에 배포하지 않았다.


## 관련 커밋과 증적

- [구현 0b475f1](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0b475f12ff43309f665959120298be8dce44c1b6)
- [격리 실행 증적](../reference/080-durable-bitcoin-block-checkpoint-verification-2026-10-25.json)
