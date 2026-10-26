# 거래 출처 검색의 서명 본문과 요청·결과를 결합하기

입금의 거래 ID와 수신 주소로 송신 사업자 검색을 접수하고 같은 요청 ID의 결과를 읽는다. 접수·검색 성공·송수신인 정보 확인·금융 승인을 구분하고, 공식 모듈이 서명한 본문이 요청한 입금 원본과 달라지지 않도록 검사한다.

## 문제와 기존 동작

[097](097-automatic-codevasp-directory.md)은 기술 연동 사업자 목록을 자동 갱신한다. 목록만으로 특정 BTC 입금이 그 사업자에서 왔다고 볼 수는 없다. 해당 거래의 검색 접수와 결과 조회가 별도로 필요하다. 외부 검색 타임아웃을 미접수로 취급하거나 NOT_FOUND를 개인지갑/안전 판정으로 바꾸면 불명 상태를 잘못 확정할 위험이 있다.

## 기존 제품과 공식 자료의 접근

확인일2026-10-25. 아래는 CodeVASP 공개 계약이며 실제 가입 계정·버전 고정 Cipher 이미지·샌드박스로 확인한 계약이 아니다.

- [TXID 검색 접수](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/11-Search-VASP-by-TXID-Request): POST `/v1/code/verification/tx`, 거래 ID/수신 주소/고유 요청 ID를 사용한다. 즉시 응답은 검색 완료가 아니다. 결과는 콜백 또는 별도 조회로 얻는다. NOT_FOUND는 비회원 또는 일시 장애일 수도 있으므로 개인지갑 판정으로 사용하지 않는다.
- [TXID 검색 결과](https://docs.codevasp.com/en/docs/travel-rule/api-reference/03-Request-API/12-Search-VASP-by-TXID-Result): GET `/v1/code/verification/tx/{requestId}`, valid/invalid·사유·사업자별 분석을 구분한다. PROCESSING은10초보다 긴 간격으로 재조회하도록 권고한다. 이번 클라이언트에는 자동 폴링/재시도가 없다.
- [Cipher Addon](https://docs.codevasp.com/en/docs/travel-rule/api-reference/04-CodeVASP-Cipher/02-Create-Header-Payload-2-Addon): 접수는 SEARCH_TRANSACTION_ID와 request, 결과는 FETCH_TRANSACTION_ID의 서명 자료를 받는다. 본문은 모듈이 반환한 원래 문자열을 그대로 전달한다. 서명 키와 암호 기능을 Java에서 재구현하지 않는다.
- [타 프로토콜 연동](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/12-Interoperability-with-Other-Protocols): 기술 목록과 정책상 연동은 다르며 타 연합은 추가 네트워크/자산 조건이 있다. 이번 검색은 선택 인자 currency/network를 보내지 않는 CODE 기본 범위다. GTR·바이낸스까지 모두 검색했다고 주장하지 않는다.

설치된 JDK HttpClient/Jackson3.1.8·기존 LimitedResponseBody를 확인하고 재사용했다. 의존성을 추가하거나 락 파일을 변경하지 않았다. 실재하는 목록/거래 검색 두 업무가 동일한 전송 정책을 필요로 하므로 그 경계만 분리했다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약·검토한 대안 |
| --- | --- | --- |
| 접수/결과 조회를 별도 메서드로 제공 | 비동기 검색을 즉시 성공 판정으로 오해하지 않음 | 안정적인 요청 ID의 영속 예약·결과 불명·폴링 큐는 후속 연결 필요. 네트워크 오류는 미접수나 금융 거절을 증명하지 않음 |
| 요청 ID·BTC 해시·수신 주소를 모듈 본문과 대조 | 다른 입금/주소/콜백이 서명돼도 전송 전 차단 | 모듈 변환 규약의 실제 버전 확인 필요. 현재 세 필드와 다른 내용을 조용히 허용하지 않음 |
| 의미 대조 뒤 서명된 원문 문자열 전송 | JSON 공백/순서로 서명을 깨뜨리지 않음 | 재직렬화한 본문을 전송하지 않으며 중복 필드/연속 JSON 문서는 거부 |
| FOUND는 valid+한 곳의 일치 분석을 요구 | 모순·복수·잘못된 응답을 확정 출처로 승격하지 않음 | 공개 계약보다 보수적인 수신 정책. 실제 공급사 valid에 분석 생략이 가능한지는 확인 필요. 생략을 성공으로 추측하지 않음 |
| 공식 필드 표의 분석 코드만 허용 | 알 수 없는 코드/문서 오기를 임의 호환 처리하지 않음 | 공식 예제의 NOT_FOUND_ADDRESS와 표의 NOT_FOUND_TXID가 불일치한다. 전자는 현재 거절하며 실제 계정 계약으로 해소할 사항 |
| 공유 전송 모듈과 업무 파서 분리 | 제한/서명/종료 정책이 목록·거래에서 일치 | 컴포넌트가 하나 늘지만 임의 URL/종류를 받는 공개 범용 API·새 인터페이스 계층은 만들지 않음 |
| 콜백 대신 명시적 결과 조회 | 현재 단계에 미검증 외부 수신 경로를 노출하지 않음 | 자동 처리의 지연/호출 비용·임대/재시도는 후속 DB 업무로 관리해야 함 |

## 구현과 관련 코드

[CodeVaspTransport](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java)는 목록·검색 접수·검색 결과의 세 고정 경로만 제공한다. 단일 장수 HttpClient를 소유/종료한다. 기존2초 연결/5초 전체 본문 대기·Cipher64KiB/게이트웨이2MiB·깊이32/문자열65536·리다이렉트 금지·중복 필드 거부를 유지했다. 같은 JSON 값 뒤 다른 토큰/문서가 붙는 경우도 FAIL_ON_TRAILING_TOKENS로 거부한다. HTTP2xx 전체를 추측해 받지 않고 현재 공개 계약의200 응답을 요구한다.

[CodeVaspDirectoryClient](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspDirectoryClient.java)는 타입 목록 해석만 담당한다. 자체 HttpClient/close/서명 조립을 삭제했고 이전 생성자에 대한 호환 경로는 남기지 않았다. 기존15개 실제 HTTP 대역 시험은 공유 전송을 직접 소유하고 종료하도록 바꿨다. 현재 실패 안내는 원문 없는 공급사 요청 실패로 통일한다. [095](095-codevasp-cipher-directory-client.md)의 실제 발췌도 현재 소스 위치로 갱신하고 당시 커밋/증적과 시점을 구분했다.

[CodeVaspTransactionClient](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransactionClient.java)의 Request는 UUID·노드 표현 그대로의 소문자64자리 BTC 해시·14~120자 영숫자 수신 주소를 받는다. 주소의 체크섬/소유/네트워크/실제 출력 일치는 상위의 검증된 입금 원본 책임이다. 임의 사용자 주소를 검증한 것으로 주장하지 않는다. Request.toString에는 입금 원문을 넣지 않는다.

| Status | 의미·금지할 해석 |
| --- | --- |
|FOUND | 요청 ID가 같고 단일 일치 사업자 분석을 얻음. 송수신인 확인/정책 승인/법적 신고 확인은 아님 |
|NOT_FOUND | NOT_FOUND_TXID. 비회원·일시 장애 가능. 개인지갑/자금 출처 적합 판정이 아님 |
|PROCESSING | 검색 미완료. 실패 확정/새 입금으로 취급하지 않음 |
|UNKNOWN_REQUEST | LACK_OF_INFORMATION. 서버가 요청 ID를 못 찾음. 이전 접수/금융 효과 미발생의 증명이 아님 |
|AMBIGUOUS | ALLIANCE_DATA_NON_UNIQUE_RESULT. 상충 출처를 임의 한 곳으로 선택하지 않음 |

성공/실패 응답 모두 요청 ID를 대조한다. 분석 목록은 최대5000개/중복 사업자 금지다. valid의 FOUND 분석은 정확히 한 곳이고 최상위 사업자와 같아야 한다. NOT_FOUND와 양성 분석이 함께 있으면 전체 실패다. 알 수 없는 최상위 결과/사유/분석은503이며 원문 reasonMsg를 결과/예외/로그에 보존하지 않는다. 외부 DB TX 안에서는 서명 모듈/공급사 호출을 금지한다.

## 이해에 필요한 문법과 원리

출처: [CodeVaspTransport.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransport.java#L48), 현재 소스의 부분 발췌.

```java
                String body=text(signed.raw(),"body",65536);
                // 모듈이 다른 거래/수신 주소를 서명했으면 전송하지 않는다. JSON 의미를 대조한 뒤 서명된 원래 바이트를 그대로 보낸다.
                if(!json.readTree(body).equals(expectedBody))throw unavailable();
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            }
```

expectedBody는 요청한 세 필드의 JSON 값이다. Jackson으로 의미가 같은지 확인하되 실제 HTTP에는 원래 body 문자열을 보낸다. TypeScript에서도 JSON.parse로 비교한 뒤 JSON.stringify한 결과를 보내면 서명 바이트가 달라질 수 있다. 모듈의 서명 자체는 게이트웨이가 검증하며 여기서는 형식/본인 공개키/시각/본문 결합을 검사한다.

출처: [CodeVaspTransactionClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransactionClient.java#L42), 현재 소스의 부분 발췌.

```java
        else if(outcome.equals("invalid"))status=switch(text(response,"reasonType",64)){
            case "NOT_FOUND_TXID"->Status.NOT_FOUND;
            case "PROCESSING"->Status.PROCESSING;
            case "LACK_OF_INFORMATION"->Status.UNKNOWN_REQUEST;
            case "ALLIANCE_DATA_NON_UNIQUE_RESULT"->Status.AMBIGUOUS;
            default->throw unavailable();
        };
        else throw unavailable();
```

Java switch 식은 문자열 사유를 다섯 상태 중 해당 값으로 변환한다. default를 정상 상태로 채우지 않고 예외로 거절한다. TypeScript의 유니언 타입도 실제 JSON 문자열의 런타임 검증 없이 안전해지지 않는다는 점은 같다.

출처: [CodeVaspTransactionClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransactionClient.java#L63), 현재 소스의 부분 발췌.

```java
        var found=analysis.stream().filter(row->row.result().equals("FOUND")).toList();
        // valid라는 문자열만으로 사업자를 확정하지 않는다. 한 곳의 일치 근거가 필요하며 상충·부분 결과를 성공으로 승격하지 않는다.
        if(status==Status.FOUND&&(found.size()!=1||!found.getFirst().entityId().equals(entity)))throw unavailable();
        if(status==Status.NOT_FOUND&&!found.isEmpty())throw unavailable();
        return new Result(requestId,status,entity,analysis);
```

stream의 filter/toList는 양성 분석만 모아 일치 근거를 확인한다. 이 임시 목록은 플랫폼 외부 조회 경로이며 Rust 매칭 루프의 할당/성능 계약과 구분한다. 목록에 FOUND가 있다고 고객 입금을 승인하거나 원장을 바꾸지 않는다.

## 작성한 테스트

[CodeVaspTransactionClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransactionClientTest.java)12개를 추가했다. 소유한 HTTP 서버2개와 시험용 JDK Ed25519 키를 사용하며 실제 공급사 계정/공식 모듈 이미지는 사용하지 않는다. 서버 대역에서 발생한 검증 실패도 AfterEach에서 확인한다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|signedSubmissionPreservesExactBodyAndStableRequestIdentity | 공백 있는 서명 본문을 게이트웨이에서 실제 검증, 같은 요청 ID 재접수에도 새 nonce·요청 원문 출력 제외 |
|foundResultIsBoundToRequestedIdAndOneConsistentOrigin | FETCH 종류/빈 GET/정확한 경로·서명, 단일 일치 FOUND |
|unresolvedResultsRemainSeparateWithoutClaimingPersonalWalletOrFinancialApproval | 네 미확정 사유를 별도 상태로 유지·원문 reasonMsg 미보존 |
|mismatchedSignedBodyNeverReachesGateway | 다른 거래/주소/요청·추가/누락 필드·비문자열·중복 필드·연속 JSON 본문을 서명해도 게이트웨이 호출0 |
|acknowledgementMustEchoSuccessAndSameRequestId | 잘못된 접수 결과/다른 ID/중복 JSON 필드는 전체 실패 |
|inconsistentOrUnknownResultsNeverBecomeFound | 다른 ID/상충 출처/중복·복수 FOUND/미지 분석/누락 분석/틀린 타입/미지 결과/중복·연속 JSON 응답 거부 |
|notFoundWithPositiveAnalysisAndUnknownReasonsAreRejected | NOT_FOUND와 FOUND 동시 표기/알 수 없는 사유 거부 |
|invalidInputNeverRequestsSigningMaterial | 변경된 BTC 해시 표현/잘못된 주소/null 등은 서명 호출0 |
|databaseTransactionCannotSubmitOrPoll | DB TX 내부 접수/조회 모두 I/O0 |
|providerErrorsAreNotRetriedOrExposed | 각429에 응용 재시도 없음·원문/원인 미노출 |
|submissionTimeoutDoesNotProveRequestWasNeverReceived | 실제 게이트웨이 수신 뒤 본문 정지,4~8초 내 실패하며 수신1회 확인. 미접수라고 판정하지 않음 |
|submissionRedirectIsNotFollowed | POST302를 따라가지 않음·모듈/게이트웨이 각1회 |

기존095 설정4/목록 HTTP15,096/097 PG14,097 워커8개도 재실행했다. 새로운 공통 JSON 제한을 추가한 뒤 같은53개를 다시 실행했으며 고유 테스트 수를 두 배로 세지 않았다.

## 실행 명령과 결과

- Java21/소유 PostgreSQL16, `python3 scripts/verify_codevasp_directory.py`: 최종 고유53개, 실패/오류/제외0. `bootJar operatorAdminJar` 성공. `.local/cycle98-proof`에 최종 JSON/XML/로그·빌드 확인을 보존한다.
- 격리 DB 컨테이너 제거 확인. 두 JAR에 공유 전송/검색/목록/워커 포함·테스트 제외. 봇0/실제 Kafka 소비 없음. deprecated/unchecked/JVM CDS 경고는 기존대로 남는다.
- `git diff --check`·현재 문서 발췌/상대 링크 대조. 실제 공급사/Sandbox/Cipher 이미지·메인넷·브라우저·Rust·BTC 전체·금융 전체 회귀는 이번에 실행하지 않았다.

## 남은 한계

클라이언트와 프로토콜 대역 검증 단계다. 입금 원본에 묶인 영속 검색 예약·재접수/결과 불명·폴링 임대·독립 위험 심사·송수신인 정보 교환·개인지갑 소유 확인·금융 승인/엔진 반영은 후속이다. 현재는 이 클라이언트의 HTTP 진입점/자동 금융 호출을 공개하지 않았다. stable requestId를 호출자가 영속 보관한 뒤 사용해야 하며,503은 원인/시점과 관계없이 금융 거절·재송금 근거가 아니다. CODE 기본 검색 범위를 타 연합 전체/해외 사업자 승인으로 확대 해석하지 않는다. 실제 버전/계약의 사유값·분석 제공 여부·쿼터/오류 처리 확인은 외부 조건이다.


## 관련 커밋과 증적

- [구현 d5be64f](https://github.com/GiHoon1123/Cex-Platform-Service/commit/d5be64f4131ebb2f1606595bdd43d9cd76278da1)
- [격리 실행 증적](../reference/098-codevasp-transaction-origin-search-verification-2026-10-25.json)

- [검색·전송 분리 c129427](https://github.com/GiHoon1123/Cex-Platform-Service/commit/c129427) 및 위 연결된 연속 JSON 거부 수정은 같은098 작업의 작은 커밋이다.

## 후속 실행 연결 시점

[100](100-durable-deposit-search-execution.md)에서 영속한 검색 ID의 접수/조회와 불변 실행 결과를 연결했다. 게이트웨이에 보내기 전 실패를 증명하는 타입만 같은 ID 재접수에 사용하며, 전송 이후 불명·중단은 조회로 이어진다. 위의 실행 미연결 설명과 검증 개수는 원래 시점이다. 자동 처리/정보 교환·금융 허가는 여전히 미완료다.

## 정보 교환 추가 시점

[104](104-codevasp-private-transfer-data.md)에서 고정 정보 교환/복호화를 추가하고 공통 서명/헤더 준비를 추출했다. 위 본문 발췌는 현재 Signed 값의 raw() 접근을 반영한다.098 당시 세 검색 경로/검증은 원래 커밋이며 금융 승인과 구분한다.
