# 플랫폼 DB의 비트코인 네트워크를 고정해 시험망·실망 혼합 차단

플랫폼 DB를 하나의 비트코인 네트워크에 결합하고, 다른 네트워크 설정으로 재사용하거나 원본을 직접 섞어 넣으면 거부한다. 시험망 BTC와 실망 BTC가 같은 자산 코드의 원장으로 합쳐질 수 있는 경계를 막는다.

## 문제와 기존 동작

입금 원본은 network/txid/vout을 구분하지만 원장 항목의 asset은 BTC다. 실행 설정을 REGTEST에서 MAINNET으로 바꾼 뒤 같은 플랫폼 DB를 재사용하면 다른 네트워크의 보류 자산이 같은 BTC 계정에 누적될 수 있었다. 고객 조회에 네트워크 조건이 있다는 것만으로 금융 저장 환경을 분리할 수는 없다.

이번에는 DB의 체인 정체성을 고정한다. 사용하던 금융 기록의 네트워크·자산·금액을 수정하지 않고, 네트워크 변경용 마이그레이션/자동 변환/폴백을 만들지 않는다. 현재는 지원 체인이 regtest/mainnet이며 새로운 자산/체인을 추가하는 작업이 아니다.

## 기존 제품과 공식 자료의 접근

Bitcoin Core31.1의 [chainparamsbase.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/chainparamsbase.cpp)는 main과 regtest 등에 서로 다른 기본 데이터 디렉터리/포트를 선택한다. 서로 다른 체인의 저장 환경을 분리하는 원칙을 플랫폼 DB에도 적용한다. Core 내부가 이 프로젝트의 이중 항목 원장을 구현한다는 의미는 아니다.

Coinbase 시험 환경 문서는 이번 열람에서 접근하지 못해 근거로 사용하지 않았다. 네트워크별 별도 DB·엔진 저널·Kafka/키 분리는 프로젝트의 운영 정책이며 이번 코드는 그중 플랫폼 DB의 잘못된 재사용 방어다.

설치된 PostgreSQL16의 [트랜잭션 자문 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)으로 여러 프로세스의 최초 결합을 직렬화한다. 기존 JdbcTemplate/TransactionTemplate/AuditService와 불변 트리거를 사용하며 의존성을 추가하지 않았다. 플랫폼 초기화의 DB 락이며 Rust 코어의 공유 락 제거와 구분한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|DB마다 고정된 하나의 네트워크 | 같은 BTC 자산 코드에 다른 체인 사실이 섞이지 않음 | 네트워크 전환은 별도 DB/환경이 필요. 하나의 DB에 시험망/실망 자산을 함께 운용하지 않음 |
|최초 노드 사용 설정에서 결합 | 노드를 쓰지 않는 빈 플랫폼 DB가 기본 REGTEST 값으로 먼저 묶이지 않음 | 빈 DB라도 잘못된 네트워크로 한 번 결합하면 값 변경/삭제가 허용되지 않음. 처음 환경 설정을 확인해야 함 |
|노드 호출 없이 설정/원본 확인 | 노드 장애 중에도 이미 결합한 같은 네트워크에서 고객 과거 내역 조회 가능 | 결합 자체는 노드/제네시스/수탁 키 검증이 아니다. 실제 RPC 준비 검증은 기존 ready 경로 책임 |
|기존 원본 전체의 네트워크 검증 | 이미 다른 체인 사실이 있으면 추측 반입/자동 변환 없이 시작 실패 | 시작 시 기존 표 검사 비용. 큰 이력의 시작 시간/쿼리 예산은 운영에서 측정 필요 |
|원본 표의 DB 삽입 트리거 | 서비스 검사를 우회한 직접 삽입도 차단 | 플랫폼 원본 쓰기에 공유 행 잠금/DB 검사 비용. DB 관리자 권한 탈취까지 막는 보장은 아님 |
|결합/필수 감사 같은 TX | 감사 장애 때 결합만 조용히 확정되지 않음 | 감사/DB 장애는 초기화 실패로 이어짐 |

## 시작과 기록 계약

- 결합된 DB이면 노드 설정 유무와 관계없이 현재 network가 같은지 확인한다. MAINNET DB에서 기본 REGTEST로 시작하지 못한다.
- 결합 전이고 노드 설정도 없으며 원본이 없는 빈 DB이면 미결합 상태로 남는다. 이때 새 비트코인 사실 삽입은 거부한다.
- 결합 전인데 원본이 있으면 설정된 네트워크와 일치해야 한다. 노드 사용 설정도 없으면 체인을 추측해 결합하지 않고 실패한다.
- 설정된 네트워크가 기존 주소/관찰/주소 목록/완료 블록/입금 원본 중 어느 하나와 다르면 시작 실패다.
- 최초 결합과 필수 감사는 하나의 짧은 TX에서 확정하고 이후 같은 설정의 시작은 원본/감사를 추가하지 않는다.
- 결합 행의 UPDATE/DELETE/TRUNCATE는 금지한다. 주소/관찰/주소 목록/블록/입금 원본은 결합 전 또는 다른 네트워크일 때 INSERT할 수 없다.

플랫폼의 bitcoinSchemaInitializer가 필요한 KYC·원장·보안 스키마를 먼저 준비한 뒤 검사한다. 발견/관찰/보류 워커는 기존처럼 이 초기화 완료에 의존하므로 잘못된 설정에서 먼저 수신 작업을 시작하지 않는다.

## 구현과 이해에 필요한 코드

출처: [BitcoinNetworkIdentity.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinNetworkIdentity.java#L24), 현재 소스의 부분 발췌.

```java
    void verifyOrBind(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("네트워크 결합은 독립 초기화로 실행하세요");
        tx.executeWithoutResult(t->{
            // 여러 프로세스의 최초 결합도 같은 짧은 DB 구간에서 순서대로 결정한다. 노드를 기다리지 않는다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('btc-network-identity-v1',0))",row->{});
            var bound=jdbc.queryForList("SELECT network FROM bitcoin_network_identity WHERE singleton=1",String.class);
            if(!bound.isEmpty()&&!bound.getFirst().equals(settings.network.id))throw mismatch();
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ("+FACTS+") facts WHERE network<>?)",Boolean.class,settings.network.id)))throw mismatch();
            if(!bound.isEmpty())return;
            if(!settings.configured()){
                // 노드를 사용하지 않는 빈 플랫폼 DB를 기본 regtest 값으로 먼저 묶지 않는다.
                // 기존 원본이 있으면 실제 운영 설정 없이 네트워크를 추측해 초기화하지 않는다.
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ("+FACTS+") facts)",Boolean.class)))throw mismatch();
                return;
            }
            jdbc.update("INSERT INTO bitcoin_network_identity(singleton,network) VALUES(1,?)",settings.network.id);
            audit.record(null,"BTC_RUNTIME_NETWORK_BOUND","bitcoin-network:"+settings.network.id,"SUCCESS","CONFIGURED_NETWORK_NOT_NODE_ATTESTATION");
```

TransactionTemplate이 최초 확인/결합의 짧은 DB 트랜잭션을 소유한다. lambda 안의 return은 해당 콜백을 끝낸다. 노드 호출을 하는 클래스에 의존하지 않으며, 현재 설정/저장 사실을 비교한 뒤 필요한 최초1행만 삽입한다. Rust 체결 코어와 무관한 플랫폼 초기화 락이다.

출처: [BitcoinNetworkIdentity.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinNetworkIdentity.java#L39), 현재 소스의 부분 발췌.

```java
            jdbc.update("INSERT INTO bitcoin_network_identity(singleton,network) VALUES(1,?)",settings.network.id);
            audit.record(null,"BTC_RUNTIME_NETWORK_BOUND","bitcoin-network:"+settings.network.id,"SUCCESS","CONFIGURED_NETWORK_NOT_NODE_ATTESTATION");
        });
```

결합 원본과 필수 감사는 같은 TX다. 감사 실패를 삼키지 않아 모두 롤백한다. 정상 재시작은 새 감사나 새 결합 원본을 만들지 않는다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L410), 현재 소스의 부분 발췌.

```sql
CREATE OR REPLACE FUNCTION bitcoin_fact_require_network() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bound varchar;
BEGIN
 SELECT network INTO bound FROM bitcoin_network_identity WHERE singleton=1 FOR SHARE;
 IF NOT FOUND OR NEW.network<>bound THEN RAISE EXCEPTION '비트코인 원본의 네트워크가 플랫폼 DB와 다릅니다'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_address_network ON bitcoin_deposit_addresses;
```

PL/pgSQL 트리거의 NEW는 삽입하려는 행이다. FOR SHARE로 결합 행을 읽고, 행이 없거나 네트워크가 다르면 삽입을 거부한다. 같은 검증 함수를 다섯 원본 표에 명시적으로 연결한다. 설정을 테이블마다 추측/자동 보정하지 않는다.

출처: [BitcoinSchemaInitializer.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinSchemaInitializer.java#L12), 현재 소스의 부분 발췌.

```java
@DependsOn({"kycSchemaInitializer","ledgerService","securitySchemaInitializer"})
public class BitcoinSchemaInitializer {
    private final JdbcTemplate jdbc;
    private final BitcoinNetworkIdentity identity;
    public BitcoinSchemaInitializer(JdbcTemplate jdbc,BitcoinNetworkIdentity identity){this.jdbc=jdbc;this.identity=identity;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("bitcoin/schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/withdrawal-quote-schema.sql").getContentAsString(StandardCharsets.UTF_8));identity.verifyOrBind();}
}
```

@DependsOn은 초기화 순서를 명시한다. 보안 표 준비 전 최초 감사 쓰기가 실행되지 않도록 하고, 원본 표/트리거 준비 뒤 네트워크 검사를 수행한다. 단순 선언뿐 아니라 실제 Spring 기동 회귀로 확인한다. 위 발췌는140 현재 소스이며, [출금 견적](140-bitcoin-fixed-fee-withdrawal-quotes.md) 스키마도 준비한 뒤 네트워크를 검사한다.140에서 견적 원본의 네트워크도 초기화 확인에 추가했다. 아래093 당시 실행 결과와 현재 범위를 구분한다.

## 작성한 테스트

신규 [BitcoinNetworkIdentityIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinNetworkIdentityIntegrationTest.java)의7개를 작성했다. 별도 소유 PG에서 실행하며 노드 RPC는 필요하지 않다. 응답하지 않는 루프백 설정으로 명시적 최초 결합을 시험한다. 실제 MAINNET 노드를 사용하거나 자산을 전송한 검증은 아니다.

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
|emptyUnconfiguredDatabaseRemainsUnboundAndRejectsNewFacts | 노드 없는 빈 DB는 미결합, 결합 전 주소 삽입 거부 |
|mandatoryBindingAuditFailureRollsBackIdentity | 필수 감사 장애면 최초 결합0행으로 롤백 |
|concurrentFirstConfigurationBindsExactlyOnceWithoutNodeCall | 두 프로세스를 모사한 실제 별도 TX의 동시 최초 결합에서 원본1행/감사1개 |
|wrongConfiguredOrOfflineNetworkCannotReuseBoundDatabase | MAINNET 설정 및 노드 없는 MAINNET 설정으로 regtest DB 재사용 거부, 같은 regtest 오프라인 확인 허용 |
|boundIdentityAndEverySourceTableRejectDirectNetworkBypass | 결합 UPDATE/DELETE/TRUNCATE 차단, 다섯 원본 표의 mainnet 삽입을 네트워크 트리거가 거부 |
|sameNetworkStartupPreservesExistingFactAndBindingAudit | 같은 네트워크 재확인 뒤 기존 주소/최초 결합/감사 그대로 |
|ambientTransactionCannotOwnRuntimeBinding | 외부 TX에 결합 수명을 넘기지 않고 거부 |

091의 [CustomerBitcoinDepositsIntegrationTest](../../src/test/java/dustin/cex/domains/kyc/CustomerBitcoinDepositsIntegrationTest.java) `anotherNetworkOfSameCustomerIsNotMixedIntoCurrentHistory`도 수정했다. 이전처럼 같은 DB에 두 체인의 합성 원본을 넣지 않는다. regtest 원본을 두고 다른 네트워크의 조회 서비스가 목록에 섞거나 개별 UUID를 읽지 못하는지 확인한다. 나머지13개 조회 시나리오는 유지했다. 생성한 합성 원본을 금융/불변 트리거 해제로 편하게 넣지 않았다.

이미 다른 체인 원본이 섞인 과거 DB를 자동 변환하거나 실제 운영 DB를 가져와 확인한 시험은 아니다. 해당 조건의 시작 차단 코드는 있지만 이력 훼손 없이 그 과거 상태를 재현하는 업그레이드 시험은 이번 범위에 넣지 않았다. 빈/현재 결합·정상 원본 보존·우회 거부·기존 전체 업무의 현재 스키마 기동을 실제 검증했다.

## 실행 명령과 결과

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew testClasses --console=plain
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_engine_inbox.py
```

금융 회귀 명령의 상위 호출은 CDD_/EDD_/DIDIT_/SPRING_MAIL_/CUSTOMER_MAIL_/BITCOIN_/CEX_BITCOIN_TEST_ 환경을 제거했다. 첫 컴파일 호출은 공통 루트에서 없는 ./gradlew를 지정해 실행 전 실패했고, 플랫폼 작업 디렉터리로 바로잡은 뒤 컴파일 성공했다.

macOS arm64/Temurin21/PG16/Core31.1. BTC/원장207개와 기존 금융182개가 모두 통과했다. 공통 원장18개를 클래스/함수명으로 대조한 **389회 실행/고유371개**, 실패/오류/제외0개다. 신규7개를 포함하고091의 최종 다중 인증 조회 회귀도 이번 BTC 묶음에서 실행했다. 새 Spring 설정 묶음은 각각 별도 PG 서버를 사용한다.

소유 노드 종료/각 PG 제거·봇0·소비자 자동 시작 금지·격리 외부 주소·제품 bootJar/operatorAdminJar 빌드/시험 제외를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고가 있다. 새 Rust 변경/성능 측정/OSV 감사·실제 MAINNET은 없다.

## 운영 조건과 남은 경계

플랫폼 DB 결합은 환경 분리의 한 부분이다. 네트워크별 별도 DB, 엔진 자체 저널 디렉터리, Kafka/토픽 또는 클러스터, 서비스/RPC 키와 배포 설정을 분리해야 한다. **이 변경만으로 엔진 WAL이나 Kafka의 네트워크 혼용까지 프로그램으로 차단했다고 주장하지 않는다.** 현재 엔진은 거래 자산을 처리하고 블록체인 고객 확인을 맡지 않는다. 별도 환경/서비스 신원 연결을 전체 검토에서 확인해야 한다.

설정 오류로 이미 결합한 빈 DB도 자동으로 지워 재활용하지 않는다. 금융 원본이 있는 DB는 결합 검사 오류를 무시하거나 값을 수정해 넘기지 않고 조사해야 한다. 실제 스키마 권한/초기화 역할 분리, 큰 이력의 시작 시간, 재조직·승인·엔진 HTTP/Kafka 금융 연결과 운영 복제/백업은 각각 후속 조건이다.


## 관련 커밋과 증적

- [구현 1513f06](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1513f063499a1c36082ea266be53452eb2ecaaac)
- [격리 실행 증적](../reference/093-bitcoin-database-network-identity-verification-2026-10-25.json)
