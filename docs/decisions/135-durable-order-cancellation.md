# 취소 요청을 영속 재확인하고 예약 해제는 금융 결과에만 결합

고객의 주문 취소 요청을 먼저 영속 기록하고, 응답 유실·프로세스 재시작에도 같은 주문의 취소를 재확인한다. 엔진의 정확한 취소 응답을 검증하되 플랫폼 주문 종료와 예약금 반환은 기존 Kafka 금융 결과 소비만 확정한다. 공통 사이클135다.

## 문제와 기존 동작

기존 OrderService.cancelOrder는 본인 주문을 읽고 의도 감사를 저장한 다음 엔진 HTTP를 한 번 호출했다. 응답을 잃거나 호출 전에 프로세스가 끝나면 자동으로 이어갈 일정이 없었다. 엔진 클라이언트는 모든2xx를 성공으로 받아 다른 주문 JSON/HTML/미확정 응답도 취소 확인으로 취급할 수 있었다. 취소 API에는 외부 트랜잭션에 합류하지 못하게 하는 NEVER도 없었다.

이는 체인 위험 때문에 제한한 고객의 기존 주문을 안전하게 정리할 때도 필요한 기초다. 이번에는 고객 취소의 작동하는 영속 경로를 완성한다. 자동 위험 취소를 고객이 직접 한 요청처럼 감사하지 않으며, 실제 위험 근거/시스템 주체에 연결하는 후속136은 별도다.

## 기존 제품과 공식 자료의 접근

2026-10-26 KST 확인한 [Coinbase 취소 API](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/orders/cancel-an-order)는 본인 프로필의 주문/시장 식별과 이미 체결된 주문의 취소 거부를 구분한다. 취소와 체결의 경쟁이 존재한다는 계약을 참고한다. 비공개 재시도 저장 구조는 추측하지 않는다.

[HTTP RFC9110 15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3)의202는 처리 접수와 완료를 구분한다. 이 API는 최초 요청과 필수 감사의 커밋을 접수 기준으로 삼는다. 이후의 즉시 출력/응답 기록 실패가 먼저 확정한 접수를 되돌린다고 설명하지 않는다. 고객은 기존 주문 조회/이벤트에서 실제 종료 결과를 확인한다.

[Spring 트랜잭션 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)와 [스케줄러](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)를 참고해 기존 OrderDispatch의 짧은 TX/외부 IO 분리와 BitcoinCreditWorker의 전용 장수 스케줄러·오류 지연·종료 패턴을 사용했다. 확인한 웹 문서는7.0.10이고 설치된 spring-context/spring-tx는7.0.9다. 의존성을 갱신하지 않았으며 설치 타입/컴파일과 실제 실행기로 사용하는 API를 확인했다. 이미 설치된 Jackson의 중복/후행 토큰 검사와 Awaitility4.2.0도 재사용했다.

기록 보존은 기존 [통제 근거 대조표](../reference/access-control-compliance.md)와 [가상자산이용자보호법 제9조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0009&lsiSeq=290735&urlMode=lsScJoRltInfoR)에 연결한다. 이번 확인한 법률 제21857호의 시행일은2026-10-02다. 원본·실행·수신 결과를 삭제하지 않고 추적하는 코드 통제이며, 법정 기록 종류/보관·파기 절차·조직/인프라·외부 심사를 충족했다는 뜻은 아니다. 취소 임대/재확인 간격·응답 검증·접수 기준은 금융 실패 위험에 대한 자체 설계다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·제약과 대안 |
| --- | --- | --- |
| 주문 ID 한 개의 영속 취소 요청 | 프로세스 종료 뒤 원본을 찾고 같은 취소를 이어감 | DB/감사 쓰기와 저장 비용 발생. 별도 UUID·메모리 재시도·새 명령 Kafka 큐는 추가하지 않음 |
| 요청→청구→응답 기록의 짧은 TX 분리 | 외부 응답 동안 DB 잠금/연결을 보유하지 않음 | 정상 취소도 세 번의 짧은 TX/감사 비용이 있다. 핵심 매칭/신규 주문 입력 경로에 새 큐를 넣지 않음 |
| 시도30초 임대·불명 재확인2/4/8…최대60초 | 종료/통신 장애 뒤 재시도와 부하 제한 | 종료 직후 최장 임대 대기와 중복 HTTP 가능. 같은 원본의 엔진 WAL 취소 멱등성을 전제로 하며 응답 실패로 예약을 풀지 않음 |
| 전용 장수 워커 한 스레드 | 취소 IO가 다른 주기 작업/코어 계산에 직접 대기를 추가하지 않음 | 느린 요청은 같은 워커의 후속 취소를 지연시킨다. 기존 order.dispatch.enabled를 쓰고 건별 스레드/임의 설정을 추가하지 않음 |
| HTTP200+정확 ID/소유자/시장/취소 상태 | 다른 대상/HTML/모호한 JSON을 확정으로 승격하지 않음 | 실제 엔진 계약을 바꾸면 양쪽을 함께 바꿔야 한다. 숫자 ID/문자열 사용자 ID/다른2xx를 받아 주는 호환 경로 없음 |
| 운영 일정과 금융 확정 분리 | ACK가 와도 앞선 체결/취소 이벤트 순서를 건너뛰지 않음 | Kafka 지연 동안 플랫폼 예약이 남는다. 화면/운영 조회가 접수와 확정을 구분해야 함 |
| 불변 원본+변경 가능한 일정+추가 전용 감사 | 일정을 이어가면서 당시 소유자/시장/시도를 보존 | 상태 검증/감사 SQL과 보관 비용이 든다. 새 금융 진실원이나 원장 자동 정정을 만들지 않음 |
| JPA flush 뒤 DB 변경 시각과 감사 결합 | 감사 예외뿐 아니라 조용한 누락/옛 감사 재사용도 차단 | 감사 저장 장애면 해당 청구/ACK 기록이 롤백된다. 최초 접수가 이미 커밋됐다면202 접수 상태로 남고 재확인을 기다림 |

## 구현과 처리 순서

1. 현재 API의 본인 주문을 잠그고 불변 order_submissions에서 소유자/거래쌍을 고정한다. 같은 주문에는 취소 원본을 하나만 만든다. 미종결 주문의 고객 취소에 신규 거래 자격 조건을 잘못 적용하지 않는다.
2. 요청과 ORDER_CANCEL_REQUESTED 감사를 함께 커밋한다. 이 단계가 실패하면 HTTP를 보내지 않는다. 이미 취소된 주문은 원래 결과를 반환하고 filled/rejected/expired에는 새 취소를 만들지 않는다.
3. 주문→취소 행 순으로 잠그고 시도 번호/30초 임대와 STARTED 감사를 커밋한다. API 즉시 전송과 워커가 같은 행에서 경쟁한다.
4. 불변 Claim의 ID/사용자/시장/시도 번호만 넘겨 TX 밖에서 HTTP를 호출한다. 실제 취소 JSON의 id는 문자열, user_id는 정수라는 엔진 계약을 그대로 검증한다.
5. ACK 또는 UNCERTAIN과 FINISHED 감사를 같은 짧은 TX로 저장한다. 오래 걸린 이전 응답이 새 시도의 결과를 덮어쓰지 않는다. 저장 실패는 ACK로 남지 않는다.
6. 최초 요청이 커밋됐으면 출력 단계 실패에도 응답은 요청 접수202다. 이 의미를 취소 완료로 바꾸지 않는다. 최초 요청/감사 실패와 소유권/종료 거부는 성공 접수로 바꾸지 않는다.
7. 전용 워커가 저장된 일정으로 한 건씩 이어간다. 금융 상태/예약 반환은 order-events의 기존 수신 키·원장·잔액 TX가 한 번만 반영한다. 엔진 응답만으로 orders를 cancelled로 쓰지 않는다.

취소와 체결은 경쟁한다. 원래 제출이 불명이면 뒤늦게 접수/체결될 수도 있다. 취소 재확인은 같은 주문을 대상으로 하며 체결 취소·강제 역분개·항상 취소가 이기는 보장을 하지 않는다. 제한 해제의133/134는 계속 미종결 주문/예약 없음과 현재 금융 근거를 요구한다.

## 실제 코드와 문법

출처: [OrderCancellationService.java](../../src/main/java/dustin/cex/domains/order/service/OrderCancellationService.java#L34), 현재 소스의 부분 발췌.

```java
        boolean pending=Boolean.TRUE.equals(tx.execute(t->{
            var owned=orders.findByUserIdAndIdForUpdate(userId,orderId).orElseThrow(()->new IllegalArgumentException("본인 주문을 확인하세요"));
            if("cancelled".equals(owned.getStatus()))return false;
            if(TERMINAL.contains(owned.getStatus()))throw new IllegalArgumentException("종료된 주문은 취소할 수 없습니다");
            var existing=cancellations.findForUpdate(orderId);
            if(existing.isEmpty()){
                var original=submissions.findByOrderId(orderId).orElseThrow(()->new IllegalStateException("취소할 불변 접수 원본이 없습니다"));
```

TransactionTemplate 콜백이 끝날 때 요청과 감사가 함께 확정된다. 이후 HTTP는 이 콜백 밖에 있다. NEVER는 호출자의 TX를 끊어 주는 기능이 아니라 TX가 이미 있으면 진입을 거부하는 조건이다. TypeScript의 await만으로 DB TX/잠금이 풀리지 않는 것과 구분한다.

출처: [OrderCancellationService.java](../../src/main/java/dustin/cex/domains/order/service/OrderCancellationService.java#L58), 현재 소스의 부분 발췌.

```java
            cancellations.flush();
            audit.record(null,"ORDER_CANCEL_ATTEMPT_STARTED","order:"+id,"SUCCESS","ATTEMPT:"+row.getAttempts());
            return new Claim(id,row.getUserId(),row.getBaseMint()+"/"+row.getQuoteMint(),row.getAttempts());
```

JPA는 필드 변경 즉시 SQL을 보내지 않을 수 있다. flush로 실제 UPDATE/DB 시각을 먼저 정한 뒤 같은 TX에 감사를 기록한다. flush는 커밋이 아니므로 감사나 지연 제약 실패 때 청구도 함께 롤백된다.

출처: [EngineHttpClient.java](../../src/main/java/dustin/cex/shared/http/EngineHttpClient.java#L183), 현재 소스의 부분 발췌.

```java
            if(response.getStatusCode().value()!=200||response.getHeaders().getContentType()==null
                ||!MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())||response.getBody()==null)throw new IllegalStateException("취소 응답 형식이 다릅니다");
            JsonNode result=objectMapper.readTree(response.getBody());
            if(!result.isObject()||!result.path("id").isString()||!orderId.toString().equals(result.path("id").asString())
                ||!result.path("user_id").isIntegralNumber()||!result.path("user_id").canConvertToLong()||result.path("user_id").longValue()!=userId
                ||!result.path("base_mint").isString()||!pair[0].equals(result.path("base_mint").asString())
                ||!result.path("quote_mint").isString()||!pair[1].equals(result.path("quote_mint").asString())
                ||!result.path("status").isString()||!"cancelled".equals(result.path("status").asString()))throw new IllegalStateException("취소 응답과 원본 범위가 다릅니다");
```

실제 계약의 식별자 타입과 값을 함께 검사한다. asString/asLong 같은 변환만 사용해 다른 타입을 받아 주지 않는다. 엄격한 중복/후행 토큰 검사도 설치된 Jackson 기능을 사용한다. 성공 확인 자체가 플랫폼 금융 변경 권한은 아니다.

출처: [OrderCancellation.java](../../src/main/java/dustin/cex/domains/order/model/entity/OrderCancellation.java#L40), 현재 소스의 부분 발췌.

```java
    public void finish(long attempt,boolean acknowledged,Instant now){
        if(acknowledgedAt!=null||attempt!=attempts)return;
        if(acknowledged)acknowledgedAt=now;
        else nextAttemptAt=now.plusSeconds(Math.min(60,1L<<Math.min(attempts,6)));
    }
```

임대 만료 뒤 더 최신 시도가 있다면 이전 성공 응답도 현재 일정을 덮어쓰지 않는다. Java Instant는 UTC 시점이며 DB 기록 시각의 권위는 PG 트리거가 맡는다. 자산 금액을 이 큐에서 계산하지 않는다.

출처: [cancellation-schema.sql](../../src/main/resources/order/cancellation-schema.sql#L24), 현재 소스의 부분 발췌.

```sql
 IF TG_OP='INSERT' THEN action_name:='ORDER_CANCEL_REQUESTED';required_detail:='INTENT_ONLY_ENGINE_RESULT_FOLLOWS';stamp:=NEW.requested_at;actor:=NEW.user_id;
 ELSE
  stamp:=NEW.updated_at;actor:=NULL;
  IF NEW.attempts>OLD.attempts THEN action_name:='ORDER_CANCEL_ATTEMPT_STARTED';required_detail:='ATTEMPT:'||NEW.attempts::text;
  ELSIF NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at THEN action_name:='ORDER_CANCEL_ATTEMPT_FINISHED';required_detail:='ATTEMPT:'||NEW.attempts::text||':ACKNOWLEDGED';
  ELSIF NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at THEN action_name:='ORDER_CANCEL_ATTEMPT_FINISHED';required_detail:='ATTEMPT:'||NEW.attempts::text||':UNCERTAIN';
  ELSE RETURN NULL;END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NOT DISTINCT FROM actor AND a.action=action_name AND a.target='order:'||NEW.order_id::text
```

SQL 트리거는 최초 요청과 시도/응답 변경을 구분해 정확한 행위자·대상·시도/결과의 필수 감사를 검사한다. DB가 정한 이번 변경 시각 이후의 감사만 인정해 과거 UNCERTAIN 감사로 새 일정 변경을 승인하지 못하게 한다. 운영 시각/DB 권한 관리는 별도 인프라 조건이다.

출처: [OrderCancellationWorker.java](../../src/main/java/dustin/cex/domains/order/service/OrderCancellationWorker.java#L36), 현재 소스의 부분 발췌.

```java
        while(active&&service.retryNext()){}
        if(!active)return;
        retries=backoff.start();delayNanos=0;if(failed){failed=false;log.info("저장된 취소 요청의 재확인을 재개했습니다");}
    }
```

volatile active는 종료 신호가 다른 스레드에 보이도록 한다. 일반 데이터 구조를 lock-free로 만드는 문법은 아니다. 워커의 수명 관리만 synchronized이며 Rust 코어의 단일 독점 상태에는 어떤 공유 락도 추가하지 않았다.

출처: [BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java#L164), 현재 소스의 부분 발췌.

```java
        var after=actualAudit.page(0,null,null);assertEquals(2,after.path("events").size());assertEquals(cancellationEvent,after.path("events").get(1).asString());listeners.start();await(()->"cancelled".equals(orderRows.findById(order).orElseThrow().getStatus()),20);
        // 실제 취소 재발행의 소비 커밋 위치까지 확인한다. 잔액이 잠시 같다는 관찰로 중복 소비를 대신하지 않는다.
        var duplicate=kafka.send("order-events",0,"same-cancellation",cancellationEvent).get(10,TimeUnit.SECONDS).getRecordMetadata();
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("CEX_WIRE_KAFKA")))){await(()->{try{var offset=admin.listConsumerGroupOffsets("cex-consumer-group").partitionsToOffsetAndMetadata().get(2,TimeUnit.SECONDS).get(new TopicPartition("order-events",0));return offset!=null&&offset.offset()>duplicate.offset();}catch(Exception waiting){return false;}},20);}
        amount("0.12345678","AVAILABLE");amount("0","LOCKED");// 호가 스냅샷 수신 키도 같은 표에 저장된다. 금융 이벤트만 별도로 세어 재발행이 새 금융 결과를 만들지 않았는지 확인한다.
```

실제 취소 WAL 이후 프로세스를 강제 종료/복원하고 같은 취소를 재확인한다. 소비를 멈춘 동안 ACK 후에도 플랫폼 예약이 남는지 검사하고, 재발행을 소비한 그룹의 커밋 위치까지 기다린다. 수신 키 표의 호가 이벤트와 금융 이벤트는 별도로 센다.

## 작성한 테스트와 검증 범위

| 파일/함수 | 시나리오·기대 결과 |
| --- | --- |
| OrderCancellationIntegrationTest24개 | 실제 PG의 요청/청구/응답 감사 예외·조용한 누락, 원본/스케줄/ACK 조작·보존, 옛 감사 재사용, 실제 금융 이벤트의 단 한 번 반환, 현재 제한 고객의 취소, 소유권, IO 중 다른 TX의 주문 잠금 획득, API 접수/오류 원문 비노출 |
| OrderCancellationTest13개 | H2 실제 커밋·새 서비스 인스턴스의 저장 일정 재시도·동시 한 생산자·현재 예약 불변·외부 TX 거부·종료4상태·늦은 ACK·감사 실패. PG 제약을 H2로 검증했다고 하지 않음 |
| OrderCancellationWorkerTest4개 | 실제 스케줄러의 비활성·중복 시작/하나의 실제 스레드 ID·준비 작업 연속 처리·저장 장애 지연·종료의 실제 인터럽트/다음 작업 금지 |
| EngineHttpClientTest.ambiguousOrDifferentCancellationIsNeverAnAcknowledgement12개 | 빈/HTML/필드 누락·다른 주문/사용자/시장·숫자 ID/문자열 사용자·다른 종료 상태·중복/후행 JSON 거부 |
| EngineHttpClientTest.otherCancellationHttpStatusesDoNotBecomeDurableAcknowledgements5개 | 201/202/204/400/500을 엔진의 확정 취소200으로 해석하지 않음 |
| 기존 OrderSubmission/Idempotency | 불변 원본/전달 행을 실제 준비해 새 취소 접수 의미와 생성·예약·원래 재전송 회귀 유지 |
| BitcoinCreditWireIntegrationTest.cancellationResponseLossAndEngineRestartPreserveReservationUntilTheRealKafkaResult | 실제 적립→실제 HTTP 지정가 주문/취소→실제200뒤504→예약 보존→같은 WAL 강제 종료/복원→같은 취소 ACK→소비 재개/원래 취소 재발행→그룹 커밋 위치·금융 반환 한 번·독립 대사 MATCHED |

PG24개의 엔진 응답은 합성이고 실제 수신 소비자에 넣는 금융 이벤트도 합성이다. MockMvc는 실제 Spring Security/컨트롤러를 실행하지만 플랫폼 외부 TCP 연결 검증은 아니다. HTTP 클라이언트 시험은 소유 임의 포트의 실제 JDK HTTP 서버를 사용한다.

실제 연결5개는 각 함수별 소유 PostgreSQL/Kafka/Rust와 실제 클라이언트/금융 원장·수신 키를 사용한다. 신규 취소 시험은 상속된 외부 경계 대역을 바꾸는 대신 실제 주문/취소 서비스를 소유 실제 HTTP 클라이언트로 조립했다. 비금융 생성 로그 대역1회는 별도로 확인한다. 노드/외부 공급사/수수료 정책은 합성이며 실제 BTC 네트워크 입출금이나 플랫폼 외부 프록시/브라우저 연결 전체를 검증했다고 하지 않는다.

## 실행 명령과 실제 결과

```sh
python3 scripts/verify_order_cancellations.py
python3 scripts/verify_engine_inbox.py
python3 scripts/verify_platform_security.py
python3 scripts/verify_bitcoin_credit_wire.py
```

환경은 macOS/JDK21/Gradle8.14.6·소유 PostgreSQL16, 실제 연결은 소유 Kafka와 현재 Rust 소스의 cargo --locked --offline 빌드다. 봇0·소비자 자동 시작 금지·외부 공급사 비활성화를 유지했고 자신의 컨테이너/프로세스만 제거했다. 기존5개 서버는 유지한다.

/tmp/run-cycle135-regressions.py와 /tmp/resume-cycle135-regressions.py가 .local/cycle135-proof에 원시 로그/JSON/JUnit XML을 보존했다. PG24·금융216·보안41·실제 연결5, **고유286개/286회 통과**, 실패·오류·제외0이다. 개발 중 H2/클라이언트93개·이전20/23개·첫 연결 실행·133/134의 수치를 중복 합산하지 않는다.

금융 실행기의 광범위한 Order 이름 패턴을 실제 주문 도메인과 OrderLedgerTest의 명시 범위로 바꿨다. 전용 PG 통합 시험을 같은 공유 시험 DB로 끌어와 금융 fixture/보존 이력을 충돌시키지 않는다. 기존 금융182개가 모두216개에 보존됐는지도 실제 클래스/입력 순번/표시 이름으로 대조했다. JVM 실행마다 바뀌는 람다 객체의 메모리 주소만 제외한 비교에서 기존182개 누락0·신규34개를 확인했으며 원시 JUnit 표시는 그대로 보존했다.

초기 실패의 원인/수정과 남아 있는 원시 자료를 구분해 기록한다. 단일 파일로 출력했던 일부 초기 H2/집계 로그는 재실행으로 덮어써 전체 원시 기록이 남아 있지 않다. 관측 요약과 실제 남은 PG/연결 원시 경로를 증적의 development_failures에서 구분하며, 없는 원시 로그를 재구성한 것으로 표시하지 않는다. H2 취소 실패 fixture에는 과거 예외 전에 사용하지 않던 전달 행이 없었고 새202 DTO 경로에서 드러나 실제 원본/전달을 준비했다. 정산 실행 기록 전용 H2 두 시험은 새 PG 초기화를 대역으로 명시해 원래 범위를 유지했다. 제품에 H2 폴백을 넣지 않았다. 실제 연결의 최종 수신 키를 금융2개로 기대했으나 호가2개도 같은 표에 있어 금융 수신 키만 정확히 구분했다. 마지막으로 다른 두 매개변수 시험의 표시 이름이 겹쳐 함수명/입력을 명시했고, 합계만 통과한 것으로 처리하지 않고 고유 식별자까지 재검증했다.

두 JAR의 실행 클래스/스키마 포함과 시험 제외·Python 구문·실제 발췌/문서 링크·git diff --check도 확인했다. 기존 deprecated/unchecked/JDK 계측 경고는 남는다. 실제 운영 부하/종료 신호/인력/보존 기간·DB 관리자/키 권한·외부 심사 충족은 별도다.

## 남은 범위

현재는 고객 취소의 영속 경로다. 체인 위험 관찰을 근거로 기존 주문을 자동 정리하는136은 시스템 주체와 실제 위험 범위에 별도로 결합한다. 그 전에는 자동 정리가 구현됐다고 표시하지 않는다. 손실/정정·다른 출처 입금/반환·출금·수탁 대사·전체 백엔드 검토·프론트·마지막 ETH는 계속 후속이다. Rust 코어/자체 WAL·Kafka 공개 계약/외부 복제·백업 배포를 변경하지 않았다.

## 작성한 테스트 코드


[OrderCancellationIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/OrderCancellationIntegrationTest.java)

- `durableAcknowledgementDoesNotReleaseReservationBeforeKafka`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theActualFinancialEventReleasesOnceAndStopsFurtherRetry`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aMissingInitialAuditRollsBackTheRequestBeforeEveryExternalCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aThrowingInitialAuditRollsBackTheRequest`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `claimAuditFailureLeavesACommittedIntentWithoutExternalDelivery`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `responseAuditFailureDoesNotCommitAnAcknowledgement`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `responseLossKeepsTheIntentAndTheFinancialReservation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `currentTradingRestrictionDoesNotPreventRiskReducingOwnCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `theSameRequestDoesNotAppendAnotherIntentOrEngineCommand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directSqlCannotCreateARequestForAnotherCustomer`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directSqlCannotReplaceTheOriginalMarket`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `directSqlCannotOmitTheOriginalRequestAudit`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `originalIdentityAndHistoryCannotBeOverwrittenOrDeleted`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anAcknowledgedQueueCannotBeResetToUnconfirmed`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `attemptCountersCannotBeMovedBackwards`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpAcceptsPersistedUncertaintyWithoutLeakingTheEngineMessage`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `customerHttpDoesNotLeakAFailedPersistenceMessage`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCustomersHttpRequestNeverStartsOrCreatesTheIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `externalIoReleasesTheOrderLockAndTransaction`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anAlreadyFinishedOrderCannotCreateNewCancellationWork`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedClaimAuditCannotCommitOrSendTheAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `silentlyOmittedResponseAuditCannotCommitTheAcknowledgement`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aPreviousUncertainAuditCannotAuthorizeANewScheduleMutation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `outputAuditFailureKeepsAcceptedIntentButNeverAFalsePositiveAcknowledgement`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[OrderCancellationTest.java](../../src/test/java/dustin/cex/domains/order/service/OrderCancellationTest.java)

- `requestAndClaimAreCommittedBeforeTheExternalCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `aFreshDispatchServiceRetriesTheSamePersistedUnknownCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `repeatedAcceptedCancellationDoesNotSendAnotherFinancialCommand`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anAmbientTransactionIsRejectedBeforeCreatingAnIntent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anotherCustomerCannotCreateOrSendTheCancellation`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requestAuditFailurePreventsEveryExternalCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `claimAuditFailureLeavesTheCommittedIntentRetryableWithoutSending`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `anOldResponseCannotOverwriteANewerLeasedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `concurrentCustomerRequestsHaveOneProducerAndOneOriginal`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[OrderCancellationWorkerTest.java](../../src/test/java/dustin/cex/domains/order/service/OrderCancellationWorkerTest.java)

- `disabledWorkerDoesNotContactTheDispatchService`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `oneLongLivedWorkerDrainsReadyRequestsAndDoesNotStartTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `storageFailureBacksOffBeforeTheNextOwnedAttempt`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `shutdownDoesNotTakeAnotherRequestAfterTheInFlightCall`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[EngineHttpClientTest.java](../../src/test/java/dustin/cex/shared/http/EngineHttpClientTest.java)

- `receivedRequestWithoutResponseTimesOutWithoutResubmitting`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `allEngineCommandsCarryServiceIdentity`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

[BitcoinCreditWireIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java)

- `actualHttpAcknowledgementWaitsForKafkaAndDuplicateDeliveryDoesNotCreditTwice`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `lostHttpSuccessAndForcedEngineRestartPreserveTheSameDepositAndEvent`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `requiredAuditFailureStopsTheRealConsumerAndReplaysWithoutLosingFunds`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `realJournalReconciliationBuildsRecoveryEvidenceWithoutClearingTheRisk`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.
- `cancellationResponseLossAndEngineRestartPreserveReservationUntilTheRealKafkaResult`: 아래 테스트 파일에서 해당 이름의 시나리오·기대 결과를 확인한다.

## 관련 커밋과 증적

- [구현 626e7b2](https://github.com/GiHoon1123/Cex-Platform-Service/commit/626e7b2a83e398b109e50f806268a2dea459b2cf)
- [격리 실행 증적](../reference/135-durable-order-cancellation-verification-2026-10-26.json)
