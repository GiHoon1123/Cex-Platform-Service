# 관찰 전용 Bitcoin Core에서 BTC 주소 파생 검증

실제 Bitcoin Core의 관찰 전용 지갑에서 BTC 수신 주소와 지출 조건을 파생한다. 개인키 생성·서명·인코딩을 애플리케이션에서 구현하지 않는다. 이번 단계는 노드 연동부이며 고객별 주소 영속 연결·입금 감지·원장 반영 API는 아직 없다.

## 문제와 기존 동작

플랫폼에는 실제 BTC 수탁·입출금 경로가 없었다. 엔진의 오래된 비활성 솔라나 지갑·스왑 경로는 [엔진017](../../../Cex-Engine/docs/decisions/017-remove-retired-chain-integrations.md)에서 정리했다. BTC/USDT 거래 시장을 선택한 것만으로 BTC 체인의 입출금이 구현된 것은 아니다. 기존 금융 기록과 SOL 자산 이력은 변경하지 않는다.

현재 Java BalanceSyncService는 운영 코드의 호출자가 없고, 엔진 잔액 증감 API에는 외부 입금 식별자를 영속 중복 방지하는 계약이 없다. 응답 유실 후 재호출하면 같은 입금을 두 번 반영할 수 있어 새 입금에 연결하지 않았다. 이번 단계는 그 API나 거래 코어를 변경하지 않는다.

## 기존 제품과 공식 자료의 접근

2026-10-25 [공식 배포 페이지](https://bitcoincore.org/en/download/)에서 Bitcoin Core31.1을 확인했다. [31.1 JSON-RPC 문서](https://github.com/bitcoin/bitcoin/blob/v31.1/doc/JSON-RPC-interface.md)는 여러 지갑에서 명시적인 /wallet/지갑이름 엔드포인트를 사용하도록 설명한다. 모든 호출은 서버가 지정한 그 경로로 보낸다.

[관찰 지갑 상태](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/getwalletinfo/), [주소 발급](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/getnewaddress/), [주소 정보](https://bitcoincore.org/en/doc/31.0.0/rpc/wallet/getaddressinfo/)의 공개31.0 설명과 실제31.1 응답을 함께 확인했다. private_keys_enabled=false인 descriptor 지갑을 사용한다. RPC 호출 권한은 노드 설정의 rpcwhitelist로 별도 제한한다. 관찰 지갑이라는 사실이 노드의 모든 RPC 권한을 제한해 주지는 않는다.

기존 DiditClient는 JDK HttpClient와 본문 크기 제한 수신기를 이미 사용한다. 설치된 JDK21의 BodySubscribers 타입도 확인했다. 기존 제한 수신기를 두 실제 통신 경로가 공유하도록 추출했고 JSON/HTTP·연결 풀을 다시 구현하거나 라이브러리를 추가하지 않았다.

## 선택과 트레이드오프

| 선택 | 얻는 점 | 비용·제약과 대안 |
| --- | --- | --- |
| Bitcoin Core의 관찰 전용 descriptor 지갑 | 유지보수되는 노드가 키 파생·주소 인코딩·지출 조건 검증을 담당 | 노드와 공개 descriptor의 백업/가용성/지갑 운영이 필요. 애플리케이션에서 직접 HD 키를 관리하는 대안보다 개인키 노출 범위는 작음 |
| BTC 네이티브 SegWit v0 수신 주소 | 실제 파생 결과와0014+20바이트 지출 조건을 명확히 검증 | 처음부터 여러 주소 종류를 설정으로 열지 않음. Taproot·다중 서명 등은 별도 요구와 키 운영 검증이 필요 |
| 서버의 명시적 REGTEST/MAINNET 설정 | 같은 연동 코드에서 체인 혼동을 거부 | MAINNET은 설정 검증만 했고 실제 연동·입금 운영은 검증하지 않음. 미설정은 호출503이며 자동 로컬/외부 노드 대체 없음 |
| DB 트랜잭션 외부의 동기 노드 호출 | 노드 지연 동안 사용자 행 잠금·DB 연결을 유지하지 않음 | 한 파생에 순차 RPC4회. 각 호출 본문 전체5초, 최악에는 약20초와 연결/정리 비용. 고객 API에서 무제한 동시 발급하지 않도록 영속 연결 단계에서 제어 필요 |
| 실패 후 애플리케이션 자동 재호출 없음 | 주소를 파생한 뒤 응답만 잃은 경우를 성공으로 추측하지 않음 | 노드에는 사용하지 않는 파생 주소가 남을 수 있음. UUID label은 고객 소유 확정 근거가 아니며 주소를 고객에게 공개하기 전에 DB 확정이 필요 |
| 제한 수신기 공유 | KYC1MiB·노드64KiB 제한을 같은 코드로 검증 | 공통 코드 회귀가 두 통신 경로에 영향을 주므로 기존 KYC 회귀도 실행 |

## 구현과 실제 데이터 흐름

BitcoinSettings가 고정 노드 URL/지갑/네트워크·RPC 사용자/비밀번호를 받는다. 부분 설정은 부팅 실패, 전부 미설정은 기능 비활성이다. 평문 HTTP는 명시적 루프백에만 허용하고 원격은 표준 인증서 검증을 사용하는 HTTPS를 요구한다. Core 자체는 보통 HTTP RPC이므로 원격 HTTPS에는 별도 신뢰 경계/프록시 구성이 필요하다. 이번 실행은 루프백뿐이다. RPC 자격을 URL에 넣지 않으며 설정 문자열 출력과 잘못된 URI 예외에서도 원문을 숨긴다.

1. 현재 DB 트랜잭션이 있으면 호출 전에 거부한다.
2. getblockchaininfo의 chain과 초기 동기화 완료를 확인한다.
3. getwalletinfo에서 정확한 지갑 이름·개인키 비활성·descriptor·스캔 미진행을 확인한다.
4. UUID label과 bech32로 주소를 파생한다.
5. getaddressinfo에서 같은 주소·노드 소유·해결 가능한 조건·외부 수신용·증인 버전0·정확한 scriptPubKey를 확인한다.
6. Address에는 네트워크·주소·scriptPubKey만 반환한다. 확장 공개키/descriptor 등의 노드 응답은 API 결과에 싣지 않는다.

RPC 응답은 JSON-RPC2.0·요청 ID·result 존재와 error 부재를 확인한다. 응답이 HTTP200이어도 오류/불완전 응답은503이다. 원문 오류·인증 자료를 로그나 외부 예외로 전달하지 않는다. 리다이렉트를 따라가지 않고 장수 HttpClient 하나를 사용한다. 종료 시 클라이언트를 정리한다.

## 관련 코드와 문법

출처: [BitcoinRpcClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinRpcClient.java#L30), 077에서 두 노드 업무가 공유하도록 분리한 현재 소스의 부분 발췌.

```java
        var chain=call("getblockchaininfo",List.of());
        if(!text(chain,"chain").equals(settings.network.chain)||!isFalse(chain,"initialblockdownload"))throw unavailable();
        var wallet=call("getwalletinfo",List.of());
        if(!text(wallet,"walletname").equals(settings.wallet)||!isFalse(wallet,"private_keys_enabled")
                ||!isTrue(wallet,"descriptors")||!isFalse(wallet,"scanning"))throw unavailable();
        return new Ready(chain,wallet);
    }
    JsonNode call(String method,List<?> params){return call(method,params,65536);}
```

var는 초기화한 표현식의 정적 타입을 Java 컴파일러가 추론한다. TypeScript와 달리 실행 시 동적 타입으로 바뀌지 않는다. boolean 필드는 실제 JSON 불리언이어야 하며, 누락/문자열 값을 false로 추측하지 않는다. 네트워크나 지갑 검증 실패는 주소 파생 이전에 중단한다.

출처: [BitcoinRpcClient.java](../../src/main/java/dustin/cex/domains/bitcoin/BitcoinRpcClient.java#L47), 077에서 두 노드 업무가 공유하도록 분리한 현재 소스의 부분 발췌.

```java
            var pending=http.sendAsync(request,info->new LimitedResponseBody(limit));
            try{
                // 헤더 도착과 구분해 본문 전체에도 제한 시간을 적용한다.
                var response=pending.get(5,TimeUnit.SECONDS);
                if(response.statusCode()!=200)throw unavailable();
                var body=tree.readTree(response.body());
                if(body==null||!body.isObject()||!text(body,"jsonrpc").equals("2.0")||!text(body,"id").equals(id)
                        ||body.has("error")||!body.has("result")||body.path("result").isNull())throw unavailable();
                return body.path("result");
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException e){throw unavailable();}
```

sendAsync의 미래 값은 본문 전체 수신 완료를 포함한다. get의 시간 초과 뒤 아직 완료되지 않은 작업을 finally에서 취소한다. TypeScript Promise에도 유사한 비동기 결과가 있지만 Java InterruptedException은 스레드 중단 상태를 복원해야 한다. 이 대기는 엔진 코어와 DB 잠금 밖에서만 허용한다.

출처: [LimitedResponseBody.java](../../src/main/java/dustin/cex/shared/http/LimitedResponseBody.java#L24), 현재 소스의 부분 발췌.

```java
        for(var buffer:buffers){
            // 덧셈의 범위 초과도 피한다. 초과 묶음은 하위 수신기에 넘기기 전에 취소한다.
            if(buffer.remaining()>limit-received){
                terminated=true;upstream.cancel();delegate.onError(new java.io.IOException("외부 응답 크기 초과"));return;
            }
            received+=buffer.remaining();
        }
        delegate.onNext(buffers);
    }
```

기존 JDK 수신기에 넘기기 전에 남은 허용량과 비교한다. 수신 바이트가 제한을 넘는 묶음은 메모리 축적 전에 취소하고 실패로 완료한다. 덧셈 뒤 비교하는 방식의 범위 초과도 피한다.

## 작성한 테스트와 기대 결과

모든 노드 검증은 scripts/verify_bitcoin.py가 만든 임시 데이터 디렉터리·루프백 임의 포트·regtest 전용 프로세스를 사용한다. 기존 사용자 지갑과 개발 노드에 접속하지 않는다. listen=0/networkactive=0/dnsseed=0/discover=0이고 peer0을 실제 확인한다. 검증 뒤 소유 프로세스를 종료하고 임시 디렉터리를 제거한다.

시험용 개인키 지갑 fixture_keys의 공개 descriptor만 cex_watch로 반입한다. 시험 지갑으로 regtest 블록1개를 생성해 초기 동기화 상태를 확인한다. 이는 실제 자금이나 외부 송금이 아니다. fixture_keys와 관찰 지갑은 같은 **시험 노드**에 있으므로 운영용 격리 서명 인프라를 검증했다고 주장하지 않는다. Java에는 제한 RPC 계정만 전달한다. 쿠키 관리자 인증과 개인키는 전달·출력하지 않는다.

| 파일/함수 | 시나리오·기대 결과 |
| --- | --- |
| [BitcoinCoreIntegrationTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinCoreIntegrationTest.java) watchOnlyWalletDerivesDistinctNativeAddresses | 실제31.1 관찰 지갑에서 서로 다른 bcrt1q 주소/지출 조건, regtest 네트워크 반환 |
| 같은 파일 wrongNetworkAndPrivateKeyWalletAreRejected | regtest 노드를 MAINNET으로 선언하거나 개인키 지갑을 지정하면503 |
| 같은 파일 nodeCredentialsCannotSignExportImportOrTransfer | 제한 계정으로 전송·서명·descriptor 노출/반입·키 내보내기·지갑 생성 메서드6종을 시도하면 각각403 |
| [BitcoinCoreClientTest](../../src/test/java/dustin/cex/domains/bitcoin/BitcoinCoreClientTest.java) validResponsePreservesNetworkAndScript | 실제 HTTP 대역의 정상 응답에서 주소 파생1회와 지출 조건 보존 |
| 같은 파일 readinessFailureDoesNotAllocate | 잘못된 체인/IBD/개인키/구형 지갑/스캔 중에는 주소 파생0회 |
| 같은 파일 mismatchedReplyErrorAndUnownedScriptFailClosed | 다른 요청 ID·error·불완전 지출 조건·타인 주소·정수 범위 초과 증인 버전을503으로 거부 |
| 같은 파일 oversizedAndRedirectResponsesAreNotFollowedOrRetried | 본문64KiB 초과·302를 거부하고 주소 파생0회 |
| 같은 파일 fullBodyHasDeadline | 헤더 이후 멈춘 본문도 약5초 제한에 종료하며7초 이내 검증 |
| 같은 파일 nodeCallsCannotHoldDatabaseTransaction | 트랜잭션이 열렸으면 노드 파생 전에 IllegalStateException |
| 같은 파일 emptyConfigurationDisablesCallsAndPartialOrRemotePlaintextConfigurationFails | 미설정503, 원격 HTTP/사용자 정보/쿼리/비정상 경로·URI 거부, 비밀 원문 비노출 |
| [LimitedResponseBodyTest](../../src/test/java/dustin/cex/domains/bitcoin/LimitedResponseBodyTest.java) exactLimitIsAccepted | 여러 버퍼 합계가 제한과 같으면 그대로 성공 |
| 같은 파일 overflowCancelsAndLateSignalsCannotTurnFailureIntoSuccess | 누적 초과 시 취소/실패, 뒤늦은 완료가 성공으로 바꾸지 못함 |
| 같은 파일 invalidLimitIsRejected | 0 제한 거부 |

## 실행 명령과 결과

macOS arm64, Temurin21, Bitcoin Core31.1.0(node version310100), 격리 PostgreSQL16, Gradle wrapper로 실행했다.

```bash
CEX_BITCOIN_CORE_HOME=/Users/dustin/Desktop/common/project/.local/tools/bitcoin-31.1 python3 scripts/verify_bitcoin.py
python3 scripts/verify_kyc.py
```

두 번째 명령은 상위 실행에서 CDD/EDD/DIDIT/메일/BITCOIN 환경값을 제거하고 실행했다. 해당 스크립트가 소유 PG를 만들고 bot0·Kafka/engine 비활성 주소·test 프로필로 실행했다. KYC 검증은150개, BTC/수신기 검증은13개 통과했다. **고유 테스트163개, 실패/오류/제외0개.** 반복한13개 실행은 합산하지 않는다. bootJar와 operatorAdminJar를 빌드했다. 기존 deprecated/unchecked 컴파일 경고·테스트 JVM 클래스 공유 경고가 있으며 경고 없는 빌드라고 주장하지 않는다. 새 의존성은 없고 이번 사이클에서 OSV 전체 감사나 처리량 측정을 새로 실행하지 않았다.

노드 실행 전 [공식31.1 배포](https://bitcoincore.org/bin/bitcoin-core-31.1/)의 arm64 macOS 압축 파일과 SHA256SUMS/SHA256SUMS.asc를 받았다. 배포 파일 SHA256은16a097c09fbd7eb78b240ce1dae123663ea2e5e377cfd6a951e71e227e23cf2f로 일치했다. 공식 bitcoin-core/guix.sigs 공개키를 별도0700 GPG 디렉터리에 가져와11개 서로 다른 주키의 서명을 검증했고 gpg 검증 종료0을 확인했다. 이는 공개 배포 키와의 기술적 서명 대조이며 서명자의 오프라인 신원 확인까지 수행했다는 뜻은 아니다. 전체 지문과 바이너리 해시는 연결 증적에 남긴다.

검증 도구로 Homebrew GnuPG2.5.24를 설치했고 의존 readline8.3.6도 갱신됐다. 노드 바이너리는 .local 아래에 두며 시스템 Bitcoin 서비스/자동 실행/공개 체인 동기화는 설정하지 않았다. 실행기의 입력 디렉터리는 검증한 배포의 bin/bitcoind와 share/rpcauth/rpcauth.py를 루트의 rpcauth.py로 추출한 구조다. 실행기는 다운로드·신뢰 검증을 대신하지 않으므로 다른 운영체제에서는 해당 공식 배포를 먼저 검증해야 한다. 테스트 노드 종료와 소유 PG 제거를 확인했다.

## 남은 업무와 운영 한계

- 고객의 현재 인증·KYC/CDD/업무 제한을 확인하고 주소를 계정에 불변 연결한 뒤 공개하는 실제 API는 후속이다. UUID label만으로 고객 소유를 확정하지 않는다.
- 입금 감지/확정 수·체인 재조직/중복·원장/엔진 WAL·Kafka 영속 반영은 미구현이다. 기존 잔액 증감 경로를 입금 멱등 경로로 재사용하지 않는다.
- 개인키 생성/보관·격리 서명·출금·다중 승인·노드 권한·주소/descriptor 백업·수탁 자산 대사·실제 MAINNET 검증은 별도로 필요하다. 운영 RPC는 제한 계정·망 격리·TLS 경계를 실제로 확인해야 한다.
- 법령·사업 신고·보관/보호·트래블룰 충족은 이 기술 검증으로 완료되지 않는다. 기존 규제 로드맵에서 적용 조건과 운영/외부 심사 상태를 계속 구분한다.


## 관련 커밋과 증적

- [구현 8591f38](https://github.com/GiHoon1123/Cex-Platform-Service/commit/8591f38da8ab0937275b4a1c18467445ebf72d8d)
- [격리 실행 증적](../reference/073-bitcoin-watch-only-rpc-verification-2026-10-25.json)

- [공통 수신기 선행 커밋 be93cd8](https://github.com/GiHoon1123/Cex-Platform-Service/commit/be93cd8)

후속 구현: [074 고객 주소 영속 연결](074-customer-bitcoin-deposit-address.md). 073의 API 미구현 설명은 해당 사이클 시점이며, 현재 발급/조회는 074를 따른다. 입금 감지·잔액 반영은 여전히 미완료다.

077 현행 기준: 노드 전송/기본 준비 확인은 BitcoinRpcClient로 분리했다. 주소 파생은 BitcoinCoreClient, 거래 출력 관찰은 [077 기록](077-bitcoin-deposit-observation.md)을 따른다. 주소용64KiB 제한과 별도로 거래 원문/해독 결과용32MiB 제한을 사용한다.

082 현행 기준: 노드 설정이 있으면 [전용 발견 워커](082-bitcoin-discovery-processing-worker.md)가 완료 블록 조회와 입금 ID 생성을 자동 실행한다. `BITCOIN_PROCESSING_ENABLED=false`로 중지하며 test 프로필/격리 실행기는 false를 명시한다. 잔액 적립은 아직 연결하지 않았다.
