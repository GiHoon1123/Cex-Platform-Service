# 동시 조회가 역전되지 않는 불변 BTC 관찰 이력

실제 노드에서 확인한 BTC 거래 관찰을 수정·삭제할 수 없는 이력으로 저장한다. 최신 관찰 포인터·필수 감사를 같은 트랜잭션에서 확정하고, 늦게 완료한 이전 조회가 새로운 관찰을 덮어쓰지 못하도록 한다. 고객 잔액이나 엔진 상태는 변경하지 않는다.

## 문제와 검증된 접근

[077](077-bitcoin-deposit-observation.md)은 정확한 노드 관찰을 만들지만 재시작 뒤 남는 플랫폼 이력이 없었다. 두 조회가 같은 거래를 다른 시점에 읽고 역순 완료하면 단순 마지막 UPDATE는 오래된 결과를 최신으로 만들 수 있다. 블록 높이만 비교하면 실제 재조직의 높이 감소도 잘못 거부하므로 현재 관찰 버전을 별도로 비교해야 한다.

[PostgreSQL16 READ COMMITTED](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)는 명령 단위의 커밋된 상태를 읽고, [트랜잭션 범위 advisory lock](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)은 짧은 확정 구간을 직렬화할 수 있다. 기존 플랫폼의 불변 업무 이력·동일 TX 원본 연결·필수 감사 패턴을 사용했다. 별도 캐시/범용 작업 프레임워크/라이브러리를 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·대안·제약 |
| --- | --- | --- |
| 불변 관찰 이력+현재 포인터 | 과거 확정/미확정/충돌 사실을 지우지 않고 현재 판단 근거 식별 | 조회마다 원본/JSON 출력 목록을 추가하므로 저장 비용 증가. 수집 주기·대상 범위·보존은 후속 업무/운영 정책에서 제한해야 함 |
| 시작 버전 읽기 → 노드 조회 → 짧은 확정 TX | 네트워크 대기 동안 DB 연결·잠금 점유 없음 | 같은 버전에서 시작한 경쟁 조회 중 하나는409로 종료. 다음 업무 실행이 새 버전에서 다시 읽어야 하며 내부 무한 재시도/임의 대체 조회 없음 |
| 같은 거래의 확정만 advisory lock | 여러 프로세스의 포인터 변경 순서 통제 | 같은 거래 쓰기는 직렬화되며 해시 충돌은 불필요한 직렬화 비용을 더할 수 있음. 엔진의 코어 공유 락과 무관한 플랫폼 DB 제어 |
| DB가 조회 시작 시각·기록 시각 제공 | 앱 호스트와 DB 호스트의 시간 차이로 관찰 시작을 새로 꾸미지 않음 | DB 시계 자체의 운영 관리가 필요. 시각이 단조 증가한다고 가정하지 않고 논리 버전은 원본 ID로 관리 |
| 원본/포인터/감사 같은 TX | 어느 하나 실패해도 성공으로 공개하지 않음 | 필수 감사 장애가 관찰 저장 가용성에도 영향을 줌. 실제 체인 상황을 추측해 성공 처리하지 않는 선택 |
| Spring 설정별 별도 PG 서버 | JPA 재생성과 유지되는 JDBC 불변 이력의 충돌 방지 | 검증용 컨테이너 기동/종료 비용. 이력을 지우거나 트리거를 끄는 시험 편법을 사용하지 않음 |

## 실제 구현과 보장 범위

BitcoinObservationService.refresh(txid)는 내부 업무용이며 REST 경로·스케줄러를 새로 공개하지 않는다. 공개 입력에서 거래를 임의로 주입하는 API가 아니다.

1. 외부 DB 트랜잭션을 거부하고 정확한64자리 소문자 거래 ID를 검증한다.
2. 첫 짧은 TX에서 해당 네트워크/거래의 현재 관찰 ID와 DB 현재 시각을 읽는다.
3. TX 밖에서077의 실제 노드 검증과 출력 JSON 직렬화를 수행한다.
4. 두 번째 READ COMMITTED TX에서 같은 네트워크/거래의 advisory lock을 잡는다. 현재 ID가 시작 ID와 다르면409이며 이 조회 결과를 저장하지 않는다.
5. 불변 원본, 현재 포인터, 시스템 행위자 감사 기록을 같은 TX에 추가하고 커밋 뒤 반환한다.

bitcoin_transaction_observations는 네트워크·지갑·txid·상태/확정 수·코인베이스·포함 블록/높이·기준 tip/높이·수신 출력 JSON·읽기 시작/기록 시각을 보존한다. 지갑의 raw hex/descriptor/개인키를 복제하지 않는다. 출력 금액은 검증된 BigDecimal의 JSON 숫자로 저장하고 실제 저장 후8자리 값을 다시 확인했다.

bitcoin_observation_heads는 같은 네트워크/txid 원본만 참조한다. DB 트리거는 새 포인터가 원래 ID보다 커야 하고 **같은 TX에서 새로 만든 사실**이어야 한다고 강제한다. 과거에 따로 확정한 큰 ID를 나중에 가리켜 새 관찰처럼 꾸밀 수도 없다. 원본 UPDATE/DELETE/TRUNCATE와 포인터 DELETE/TRUNCATE는 거부한다.

반복한 독립 조회는 값이 같아도 서로 다른 실제 확인 사실이다. 과거 시각을 retime하지 않고 새 원본을 추가한다. ID의 큰 값은 마지막 확정 관찰을 가리키며 블록 높이나 금융 최종성을 뜻하지 않는다. PostgreSQL sequence는 롤백 뒤 빈 번호가 생길 수 있고 이를 WAL 누락으로 오해하지 않는다. 재조직의 낮은 확정 수를 새 사실로 보존할 수 있으며 원래 기록을 수정하지 않는다.

고객이 비활성/KYC 제한 상태여도 외부 체인 사실을 지워서는 안 되므로 이 관찰 수집에 고객 로그인/거래 허용을 요구하지 않는다. 고객 귀속·적립 정책·거래/출금 제한은 후속 원본 연결 업무에서 별도로 판단한다. 현재 단계는 user_balances/원장/엔진에 금융 효과를 만들지 않는다.

## 실제 코드와 필요한 문법

출처: [BitcoinObservationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinObservationService.java#L37), 현재 소스의 부분 발췌.

```java
            var started=tx.execute(t->new Started(head(network,txid),jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant()));
            var observation=reader.inspect(txid);
            if(!observation.network().equals(network)||!observation.txid().equals(txid))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"관찰의 거래 원본이 다릅니다");
            String outputs=json.writeValueAsString(observation.outputs());
            return tx.execute(t->{
                // 같은 거래의 확정 구간만 직렬화한다. 해시 충돌은 불필요한 직렬화 비용만 만들고 소유를 섞지 않는다.
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-observation-v1:"+network+":"+txid);
                if(!Objects.equals(head(network,txid),started.expected()))throw new ResponseStatusException(HttpStatus.CONFLICT,"더 최근 관찰이 확정되어 다시 조회해야 합니다");
```

Started의 expected는 nullable Long이다. 최초 관찰에는 현재 ID가 없으므로 null이며 Objects.equals로 비교한다. 노드 호출과 큰 출력 직렬화는 두 트랜잭션 사이에서 수행해 DB 잠금·연결을 붙잡지 않는다.

출처: [BitcoinObservationService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinObservationService.java#L43), 현재 소스의 부분 발췌.

```java
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-observation-v1:"+network+":"+txid);
                if(!Objects.equals(head(network,txid),started.expected()))throw new ResponseStatusException(HttpStatus.CONFLICT,"더 최근 관찰이 확정되어 다시 조회해야 합니다");
                var saved=jdbc.queryForMap("""
```

쿼리 파라미터로 잠금 키를 전달한다. 잠금 후 새 SQL 조회가 READ COMMITTED의 최신 커밋 버전을 읽는다. 버전이 달라지면 이전 결과가 실제로 더 새로워 보이더라도 보수적으로 중단하고 다음 조회를 요구한다. 여러 await를 순서대로 호출하는 것만으로 이런 DB 계약이 생기는 것은 아니다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L47), 현재 소스의 부분 발췌.

```sql
 IF NOT EXISTS(SELECT 1 FROM bitcoin_transaction_observations o WHERE o.id=NEW.observation_id
   AND o.network=NEW.network AND o.txid=NEW.txid AND o.created_txid=txid_current()) THEN
  RAISE EXCEPTION '현재 관찰과 원본은 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
```

created_txid는 PostgreSQL의 현재 트랜잭션과 원본을 결합한다. 단순 FK뿐 아니라 원본과 현재 포인터가 함께 확정되게 한다. 이 제약은 노드 합의/서명 검증을 대신하지 않으며 애플리케이션의 원본 생성 경계를 보강한다.

## 작성한 테스트

[BitcoinObservationIntegrationTest.java](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinObservationIntegrationTest.java)는 실제 regtest 노드·별도 PG·감사를 사용한다. 대역/spy는 순서·실패·잘못된 반환값을 주입하는 지점만 제한적으로 사용한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
| actualObservationCommitsSourceCurrentPointerAndAuditWithoutCredit | 실제 확정 거래의 원본/포인터/감사 확정, 저장한0.12345678BTC 정확성, 금융 잔액/원장0건 |
| repeatedIndependentReadsKeepOriginalAndAdvanceCurrentPointer | 반복 확인 원본2개 모두 보존, 포인터만 새 원본으로 이동, 과거 복귀 거부 |
| concurrentFirstReadsAllowOnlyOneVersionWinner | 같은 초기 버전에서 실제 노드를 읽은2개 중1개만 저장, 다른1개409 |
| slowEarlierReadCannotReplaceNewerCommittedObservation | 이전 조회를 실제 노드 읽기 뒤 지연하고 새 조회를 먼저 커밋. 지연 결과409·최신 포인터 보존 |
| auditFailureRollsBackNewSourceAndCurrentPointer | 원본/포인터 갱신 뒤 감사 장애 주입. 모두 롤백되고 이전 원본/포인터 유지 |
| nodeFailureAndAmbientTransactionCannotChangeCurrentPointer | 노드503/외부 TX 호출 거부, 원본/현재 상태 불변 |
| immutableSourceAndHeadDeletionAreRejected | 원본 수정/삭제/TRUNCATE 및 포인터 삭제 거부 |
| currentPointerCannotAttachOldFactToAnotherTransaction | 과거 타 거래 원본을 새 포인터로 연결하려는 SQL 거부 |
| conflictingAndReorgObservationsPersistAsNonCreditedFacts | 실제 대체 충돌/재조직 거래를 비확정 사실로 저장, 블록 없음·잔액0 유지 |
| readerResultForDifferentTransactionCannotBecomeRequestedHead | 잘못된 거래 DTO 반환 주입503, 기존 상태 유지 |
| higherButPreviouslyCommittedFactCannotBeActivatedAsFreshObservation | 별도 TX에 만든 더 큰 ID 원본도 새 현재 관찰로 재사용할 수 없음 |

## 실행과 실제 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
```

macOS arm64/Temurin21/Core31.1/소유 PostgreSQL16에서 실행했다. 실행기는 한 소유 regtest 노드와 두 업무 묶음별 **별도 PG 서버**를 사용한다. addresses 묶음은 기존41개, observations 묶음은 신규11개다. 다른 Spring 설정을 같은 서버/DB에 묶지 않고 실제로 두 컨테이너를 생성/제거했다. 개인 공급사/메일 환경 제거, bot0, 엔진 비활성 주소·소비자 자동 시작 금지를 유지한다.

최초 신규9개를 포함한50개 실행이 통과한 뒤 잘못된 거래 반환·따로 확정된 사실 재사용·저장 후 금액 검사를 보강했다. **최종 고유52개 통과, 실패/오류/제외0개.** 중간50개를 합산하지 않는다. 각 묶음에서 새 XML만 집계했고 개별 사례를 증적 JSON에 남겼다. bootJar/operatorAdminJar 빌드, 노드 종료와 두 PG 제거를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고는 남는다. 새 의존성·OSV 전체 감사·처리량 측정은 없다.

이 시험에서 같은 거래의 실제 노드 값은 정적으로 읽고, 동시성/실패 시점은 실제 RPC 뒤 spy로 제어한다. 재조직/충돌 입력은077 실행기에서 실제 로컬 블록 무효화/fee bump로 만든 거래다. 이를 실제 MAINNET이나 고객의 금융 적립 검증으로 해석하지 않는다.

## 다음 단계와 한계

알려진 거래 ID를 영속 관찰할 수 있지만 자동 발견/체크포인트·등록 주소의 고객 귀속·출력별 안정적인 입금 ID·확정 수/코인베이스 성숙도·보류/정정·원장·엔진 HTTP/Kafka 반영은 아직 없다. 지갑에 보이는 미지정 주소를 임의로 고객에게 귀속하지 않는다.

원본 이력은 계속 늘어나므로 실제 수집 주기/대상·보존/법적 보류·조회 제한·운영 지표와 DB 권한/백업을 검증해야 한다. 관찰 JSON은 서명된 외부 증적이나 수탁 자산 총합 검증이 아니다. 디스크/호스트 손실의 외부 복제/백업은 기존 지시대로 필요 조건만 기록하고 배포하지 않았다.


## 관련 커밋과 증적

- [구현 8372dba](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8372dbaf1b592822acfeeb27e52f5ede0ee8cfc1)
- [격리 실행 증적](../reference/078-durable-bitcoin-observations-verification-2026-10-25.json)
