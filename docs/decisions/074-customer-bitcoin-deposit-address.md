# 현재 고객 자격을 재검증하는 BTC 입금 주소 영속 발급

현재 고객 세션과 KYC/CDD 자격을 확인해 BTC 입금 주소를 본인에게 영속 연결한다. 노드 호출은 DB 트랜잭션 밖에서 수행하고, 주소 소유·필수 감사가 함께 커밋된 뒤에만 응답한다. 입금 감지·잔액 반영은 아직 연결하지 않았다.

## 문제와 기존 동작

[073](073-bitcoin-watch-only-rpc.md)은 실제 관찰 전용 노드의 주소 파생을 검증했지만 고객 소유를 저장하거나 HTTP로 제공하지 않았다. 노드의 label은 서버 내부 파생 식별자일 뿐 고객 소유 확정 기록이 아니다. 주소를 먼저 고객에게 보여준 뒤 DB 저장이 실패하면 이후 입금의 소유자를 찾을 수 없으므로 공개 순서를 반대로 해야 한다.

## 제품과 공식 자료의 접근

2026-10-25 [Coinbase Exchange의 주소 생성 API](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/coinbase-accounts/generate-crypto-address)는 인증된 계정과 네트워크를 결합한 주소 생성 업무를 공개한다. 해당 제품의 내부 트랜잭션/키 관리 구조는 공개 문서로 확인하지 않았고 추측하지 않는다. 이번 프로젝트는 지원 자산 BTC와 서버가 정한 네트워크만 사용하며 임의 고객/체인 입력을 받지 않는다.

[Bitcoin Core getnewaddress](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/getnewaddress/)의 label과 주소 파생은 그대로 사용한다. DB 소유 확정은 별개다. [PostgreSQL16 고유 제약](https://www.postgresql.org/docs/16/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS), [ON CONFLICT](https://www.postgresql.org/docs/16/sql-insert.html), 기존 CustomerSessionPolicy와 필수 감사 트랜잭션을 조합한다. 신규 의존성을 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·대안·제약 |
| --- | --- | --- |
| 고객/네트워크당 주소 하나 | 새로고침·응답 유실 재시도에도 같은 소유 원본을 반환 | 매 입금마다 새 주소를 발급하는 대안보다 고객 흐름/입금 소유 추적이 단순하지만 온체인 주소 재사용에 따른 연관성 노출이 있음. 향후 주소 회전은 별도 요구/보존 계약 필요 |
| 짧은 확인 TX → 외부 RPC → 짧은 확정 TX | 느린 노드가 사용자 행 잠금·DB 연결을 점유하지 않음 | 두 동시 요청은 서로 다른 주소를 파생할 수 있음. 최종 고객 잠금과 고유 제약으로 하나만 저장/공개하며 나머지는 미공개 주소로 남음. 별도 작업 큐·임대·복구 상태 머신을 처음부터 추가하는 대안보다 단순함 |
| 현재 주문과 같은 고객 자격 사실 사용 | 신규 업무가 기존 KYC/CDD/종료/제한 정책을 우회하지 않음 | 공급사 최신 확인15분 정책의 가용성 비용도 공유. 이는 자체 정책이며 법정 유효기간이나 모든 사업자의 의무로 주장하지 않음 |
| GET·기존 주소 재사용도 현재 자격 재검사 | 과거 발급 결과로 현재 제한을 우회해 주소를 다시 공개하지 않음 | 제한된 고객은 이 API에서 주소를 다시 읽지 못함. 이미 알려진 온체인 주소를 폐기하거나 그 주소로 송금하는 것을 막을 수는 없으므로 후속 입금 감지/보류 처리가 반드시 필요 |
| 불변 소유 기록과 필수 감사의 원자성 | 성공 응답 뒤 주소의 고객 연결을 잃거나 바꾸지 않음 | 연결 삭제/타 고객 재배정 불가. 개인정보 최소화·보존/법적 보류를 별도 관리하고 금융 이력을 편의상 삭제하지 않음 |

## 실제 API와 데이터 흐름

| 메서드/경로 | 동작 |
| --- | --- |
| POST /api/customer/deposit-addresses/BTC | 현재 본인 주소가 있으면 재사용. 없으면 실제 노드 파생 뒤 소유/감사를 저장하고200 반환 |
| GET /api/customer/deposit-addresses/BTC | 현재 본인 주소를 DB에서만 조회. 없으면404이며 주소를 새로 만들지 않음 |

응답은 id, asset=BTC, network, address, createdAt이다. regtest는 bitcoin-regtest로 명확하게 표시한다. wallet, scriptPubKey, 공개 descriptor, 키·RPC 자격은 응답에 포함하지 않는다. 자산·네트워크·타인의 고객 ID를 본문이나 경로 변수로 고를 수 없다. 운영자/익명은 거부하며 현재 고객 세션과 등록 고객의 MFA 수준을 서비스에서도 재확인한다. 응답은 기존 보안 필터의 no-store를 유지한다. 주소 조회를 위해 불필요한 별도5분 추가 인증은 만들지 않았다.

기존 주소가 없을 때 첫 TX에서 현재 사용자 행을 잠그고 자격을 검사한다. TX가 끝나면 073의 노드 연동으로 주소를 파생한다. 두 번째 TX는 같은 사용자 행을 다시 잠그고 현재 세션/자격을 검사한다. 다른 요청이 이미 주소를 확정했으면 그 주소를 반환한다. 새 소유 기록을 넣을 때는 고객/네트워크·주소/네트워크·지출 조건/네트워크의 고유 제약을 모두 지킨다. 타인의 주소와 충돌하면503이며 소유자를 변경하거나 타인 주소를 반환하지 않는다. 사용자에게 응답하는 시점에는 TransactionTemplate의 커밋도 끝나 있어야 한다.

CustomerFinancialEligibility는 원래 TradingAccessPolicy의 사실 검사를 그대로 추출했다. 활성 CUSTOMER·수동 제한·관계 종료·현재 CDD 허용·live 신원 검증·유효기한·최근 공급사 확인을 검사한다. 신규 주문은 기존 사용자 잠금/거부 감사/예약 계약을 유지하고 같은 컴포넌트에 위임한다. 일반적인 정책 엔진이나 임의 업무별 설정을 추가하지 않았다.

bitcoin_deposit_addresses에는 고객·네트워크·원래 지갑 이름·주소·지출 조건·생성 시각을 저장한다. 원래 지갑 이름은 운영 추적용이며 비밀 키가 아니다. 지갑 설정을 바꿔도 과거 소유 기록은 덮어쓰지 않는다. 공개 descriptor와 파생 지갑·DB 소유 원본의 백업/복원이 운영에서 함께 필요하다. 기존 불변 원본 트리거로 UPDATE/DELETE/TRUNCATE를 거부한다. 일반 서버의 초기화만 실행하며 별도 운영자 CLI 진입점에 블록체인 연결을 추가하지 않는다.

## 실제 코드와 문법

출처: [BitcoinDepositAddressService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositAddressService.java#L48), 현재 소스의 부분 발췌.

```java
            var candidate=core.derive(allocation);
            return tx.execute(t->{
                // 그 사이의 로그아웃·회수·고객 확인 만료·업무 제한을 최신 사용자 잠금 아래 다시 확인한다.
                require(actor,sid);var winner=find(actor);
                if(winner!=null){audit.record(actor,"BTC_ADDRESS_REUSED","address:"+winner.id(),"SUCCESS","CONCURRENT_DURABLE_WINNER");return winner;}
                if(!settings.network.id.equals(candidate.network()))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"입금 네트워크를 확인할 수 없습니다");
                int inserted=jdbc.update("""
                    INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key)
```

var candidate는 노드가 검증한 수신 주소 결과다. 노드 대기는 앞선 tx.execute가 끝난 뒤에 일어난다. 다음 TransactionTemplate 콜백은 사용자 행을 다시 잠그므로 동시 요청 중 한 건의 소유만 최종 공개된다. TypeScript에서도 함수 호출의 순서만으로 DB 트랜잭션이 보장되는 것은 아니다.

출처: [BitcoinDepositAddressService.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinDepositAddressService.java#L54), 현재 소스의 부분 발췌.

```java
                int inserted=jdbc.update("""
                    INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key)
                    VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """,allocation,actor,candidate.network(),settings.wallet,candidate.address(),candidate.scriptPubKey());
                // 다른 고객의 주소/지출 조건과 충돌하면 소유권을 바꾸거나 타인의 행을 반환하지 않는다.
                if(inserted!=1)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"입금 주소 소유를 확정할 수 없습니다");
                var saved=find(actor);audit.record(actor,"BTC_ADDRESS_ISSUED","address:"+allocation,"SUCCESS","OWNERSHIP_COMMITTED");return saved;
            });
        }catch(ResponseStatusException e){denied(actor,e);throw e;}
```

ON CONFLICT DO NOTHING은 기존 소유자를 바꾸지 않는다. 충돌 뒤 임의로 주소 소유를 보정하지 않고503으로 중단한다. audit.record가 실패하면 같은 TX의 소유 INSERT도 롤백된다. Address record는 응답에 필요한 값만 전달하며 저장 엔티티나 노드 응답을 그대로 노출하지 않는다.

출처: [schema.sql](../../src/main/resources/bitcoin/schema.sql#L11), 현재 소스의 부분 발췌.

```sql
CREATE TABLE IF NOT EXISTS bitcoin_deposit_addresses (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),
 network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 wallet varchar(64) NOT NULL,address varchar(100) NOT NULL,
 script_pub_key varchar(44) NOT NULL CHECK(script_pub_key ~ '^0014[0-9a-f]{40}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,network),UNIQUE(network,address),UNIQUE(network,script_pub_key)
);
DROP TRIGGER IF EXISTS bitcoin_address_no_change ON bitcoin_deposit_addresses;
```

고유 제약은 애플리케이션 외의 동시 프로세스에서도 적용된다. 고객별 원본 하나와 주소/지출 조건별 소유자 하나를 독립적으로 보장한다. CHECK는 현재 지원하는 네트워크와 SegWit v0 지출 조건의 형식을 제한하며 주소 체크섬/키 파생 검증은 실제 노드가 담당한다.

## 작성한 테스트

[BitcoinDepositAddressIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinDepositAddressIntegrationTest.java)는 실제 PG·로컬31.1 노드·고객 확인 판정·HTTP 필터를 연결한다. 유료 Didit 세션 생성만 기존 대역을 사용하고 서명된 KYC 판정과 CDD 검증은 제품 코드를 실행한다. core spy는 실제 노드 호출을 보존하면서 실패·경쟁·회수 시점을 주입한다.

| 함수 | 시나리오와 기대 결과 |
| --- | --- |
| eligibleCustomerGetsOneDurableAddressAndReusesWithoutNode | 실제 노드로 처음 발급하고 이후 노드 장애 주입에도 같은 DB 원본을 POST/GET으로 반환. 내부 지갑/지출 조건 비노출·no-store |
| actualHttpPostPersistsBeforeReturningAndDoesNotChooseCallerUser | 실제 고객 HTTP POST200 뒤 DB 소유1건. 미지원 ETH 경로403 |
| unverifiedCustomerIsRejectedBeforeNode | KYC 미확인403, 노드 파생0회/소유0건 |
| getWithoutAddressDoesNotCreateOne | GET404, 노드 파생0회/소유0건 |
| strangersOperatorsAndAnonymousCannotReadOrIssue | 타인 세션·운영자403, 익명 HTTP401 |
| logoutDuringRealDerivationPreventsOwnershipCommit | 실제 파생 도중 세션 회수 후403, 소유0건 |
| restrictionDuringRealDerivationPreventsOwnershipCommit | 실제 파생 도중 업무 제한 후403, 소유0건 |
| concurrentRealDerivationsPublishOnlyOneOwnerAddress | 실제 파생2건을 같은 시점에 확정 경계로 보내도 두 응답이 같고 소유1건 |
| auditFailureRollsBackAddressAndNeverReturnsSuccess | INSERT 이후 감사 실패 주입, 응답 실패/소유0건 |
| anotherCustomerAddressCollisionNeverChangesItsOwner | 같은 주소 또는 같은 지출 조건 충돌503, 원래 소유자 유지/새 소유0건 |
| ownershipIsImmutableAndCannotBeTruncated | UPDATE/DELETE/TRUNCATE 실패, 원본 그대로 조회 |
| nodeFailureLeavesNoOwnershipAndOuterTransactionIsRejected | 노드503에 소유0건, 외부 TX 안의 호출은 네트워크 대기 전에 거부 |
| existingAddressCannotBypassCurrentRestriction | 발급 후 제한 시 GET/POST403, 과거 원본1건은 보존 |
| expiredIdentityAndStaleProviderConfirmationCannotCreateAddress | 신원 유효기한 만료403, 노드 파생0회 |
| staleProviderConfirmationCannotCreateAddress | 공급사 확인16분 경과403, 노드 파생0회 |

첫 신규12개와 기존13개를 합친25개 실행이 통과한 뒤 만료·조회 제한 등3개를 보강했다. 최종 집계는 아래28개이며 중간25개를 합산하지 않는다. 기존 자격 정책의 추출은 KYC150개와 플랫폼 보안41개로 회귀 확인했다.

## 실행 명령과 결과

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_kyc.py
python3 scripts/verify_platform_security.py
```

macOS arm64/Temurin21/Core31.1/소유 PostgreSQL16에서 실행했다. 뒤의 두 실행도 상위 환경에서 개인 CDD/EDD/Didit/메일/BITCOIN 설정을 제거했다. BTC 실행기는 자체 임시 regtest·peer0·제한 RPC 계정과 별도 PG 서버를 생성하고 종료/제거한다. Spring test의 bot0·Kafka 소비 자동 시작 금지·엔진 비활성 주소를 확인했으며 기존 개발 DB/컨테이너를 사용하지 않았다.

**최종28개(신규15+기존13), KYC150개, 플랫폼 보안41개: 고유219개 통과, 실패/오류/제외0개.** bootJar/operatorAdminJar 빌드와 제품 JAR의 시험 클래스 제외를 확인했다. 기존 deprecated/unchecked/JVM 계측 경고는 남아 있다. 새 의존성/OSV 전체 감사/성능 측정/메인넷 검증을 수행했다고 주장하지 않는다.

## 미완료와 다음 연결

주소 발급이 입금 완료나 거래 가능 잔액을 뜻하지 않는다. 실제 입금 감지·확정 수·재조직/중복·보류·원장/엔진 WAL/Kafka 반영은 후속이다. 기존 엔진 잔액 증감 API는 안정적인 입금 ID로 중복을 방지하지 않으므로 바로 연결하지 않는다. 이전 SOL 금융 기록은 그대로 보존한다. 디스크/호스트 손실의 외부 복제·백업은 기존 지시대로 운영 필요 조건만 문서화한다.


## 관련 커밋과 증적

- [구현 42c86c3](https://github.com/GiHoon1123/Cex-Platform-Service/commit/42c86c344ebf06df0df73759fd936ad8872a322c)
- [격리 실행 증적](../reference/074-customer-bitcoin-deposit-address-verification-2026-10-25.json)

- [고객 자격 추출 0251a1d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0251a1d)
