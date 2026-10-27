# 공개 생존·준비 상태와 금융 작업 적체, 정상 종료 경계

로드밸런서가 사용할 최소 상태와 운영자가 볼 영속 작업 적체를 분리한다. 플랫폼 종료 시 신규 HTTP 접수를 닫고 진행 중 요청에 30초를 주며, 끝나지 않은 금융 작업은 기존 영속 큐에서 재개한다.

## 문제와 기존 동작

엔진은 코어·출력 워커가 닫히면 `503`을 내고 종료 시 저널·출력 큐를 회수했지만 플랫폼에는 공통 생존·준비 경로가 없었다. 각 워커는 실패를 로그에 남겼으나 주문 전송, 취소, BTC 입금, 관찰, 메일, 시장감시의 적체량과 가장 오래된 대기 시간을 한 번에 볼 수 없었다. 공개 상태에 내부 작업이나 고객 식별자를 노출해서도 안 된다.

## 기존 제품과 공식 자료의 접근

- [Spring Boot 정상 종료](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)는 애플리케이션 컨텍스트 종료 초기에 신규 요청을 막고 설정한 유예 시간 동안 진행 중 요청을 마치는 동작을 설명한다.
- [Spring Boot 상태 엔드포인트](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)는 생존 상태에 외부 시스템 장애를 섞어 연쇄 재시작을 만들지 말고, 준비 상태와 구분하도록 안내한다.

Actuator를 새로 추가하면 표준 지표 생태계를 얻지만 의존성·잠금·검증 메타데이터와 노출 설정이 함께 늘어난다. 현재 필요한 최소 HTTP 상태와 업무 큐 집계는 기존 Spring MVC·JDBC로 구현하고, 전사 지표 수집이 필요해질 때 검증된 Micrometer/Actuator를 별도 사이클에서 비교한다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 한계 |
|---|---|---|
| 공개 생존은 프로세스 상태만 반환 | DB 장애로 모든 인스턴스가 재시작되는 연쇄 실패를 피함 | 업무 가능 상태는 알 수 없음 |
| 공개 준비는 DB와 핵심 스키마만 확인 | 플랫폼의 권위 저장소 없이 트래픽을 받지 않음 | 엔진·Kafka·체인 공급사의 개별 장애는 운영 상태와 업무 응답에서 확인해야 함 |
| 인증된 큐별 건수·최장 대기만 노출 | 고객·작업 ID 없이 적체를 탐지 | 임계치·경보 전송·시계열 저장은 인프라에 연결해야 함 |
| 30초 정상 종료 | 진행 중 HTTP를 마칠 기회 제공 | 30초를 넘기면 영속 작업 복구 계약에 의존함 |

준비 상태에 모든 외부 공급사를 넣는 대안은 한 공급사 장애가 계정 조회·취소·출금 상태 확인까지 모두 라우팅 중단시키므로 선택하지 않았다. 운영 상태도 자동으로 금융 기록을 수정하거나 워커를 재시작하지 않는다.

## 구현과 관련 코드

[OperationalHealthService.java](../../src/main/java/dustin/cex/domains/security/OperationalHealthService.java)의 큐 집계 일부다.

```java
private QueueState queue(String name,String sql){
    var row=jdbc.queryForMap(sql);
    long pending=((Number)row.get("pending")).longValue();
    Object age=row.get("oldest_seconds");
    return new QueueState(name,pending,age==null?null:Math.max(0,((Number)age).longValue()));
}
```

각 SQL은 `count(*)`와 가장 오래된 대기 시각만 반환한다. 주문 ID, 고객 ID, 주소, 사유는 운영 응답에 넣지 않는다. 현재 집계 대상은 주문 전송, 취소, BTC 적립, BTC 관찰, 고객 메일, 시장감시다.

[application.properties](../../src/main/resources/application.properties)는 다음 종료 계약을 명시한다.

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

Spring이 신규 HTTP 요청을 먼저 막고 진행 중 요청을 기다린다. DB에 커밋된 뒤 아직 외부 전송되지 않은 일은 기존 작업 표와 임대 만료에서 재개된다. `SIGKILL`, 호스트 손실, 30초 초과를 정상 종료와 같다고 주장하지 않는다.

## 이해에 필요한 문법과 원리

Java `record QueueState(String name,long pending,Long oldestSeconds)`는 TypeScript의 읽기 전용 데이터 객체와 비슷하게 생성자·접근자·동등성 메서드를 만든다. `long`은 값이 반드시 있고, `Long`은 대기 작업이 0건일 때 `null`을 표현한다.

SQL의 `min(next_attempt_at)`은 가장 오래된 작업의 예정 시각이다. 현재 시각과의 차이를 초로 계산하며 미래에 예약된 작업은 화면에서 음수가 되지 않도록 0으로 제한한다. 건수만 보지 않고 나이를 함께 보는 이유는 작은 수의 작업이 오래 멈춘 상태를 놓치지 않기 위해서다.

## 작성한 테스트

| 파일·함수 | 시나리오 | 기대 결과 |
|---|---|---|
| `OperationalHealthIntegrationTest.publicLivenessAndReadinessExposeOnlyAStatus` | 미인증 생존·준비 조회 | `200`, `UP`, 내부 큐 없음 |
| `operationalBacklogsRequireAnOperatorRoleAndContainNoWorkIdentifiers` | 고객 우회와 운영자 조회 | 고객 `403`, 여섯 집계, 고객 메일/식별자 미노출 |
| `readinessFailsClosedWhenTheDatabaseIsUnavailable` | JDBC 장애 | 예외 원문 없이 `DOWN` |
| `PlatformSecurityTest.everyRegisteredApiRouteKeepsTheAuthenticationAndApplicationBoundary` | 실제 등록 경로 전수 | 공개 상태만 예외, 운영 상태는 역할 경계 유지 |

## 실행 명령과 결과

```bash
python3 scripts/verify_operational_health.py
python3 scripts/verify_platform_security.py
```

2026-10-27 격리 PostgreSQL 16에서 운영 상태 3개와 등록 API 보안 42개가 통과했다. 실패·오류·제외는 0개이고 고객 플랫폼·운영자 초기화 JAR 빌드도 성공했다. 첫 운영 상태 실행에서는 시장감시 시각 열 이름을 `occurred_at`으로 잘못 사용해 1건 실패했고 실제 스키마의 `created_at`으로 수정한 뒤 처음부터 다시 통과했다. 중간 실패는 최종 통과 수에 포함하지 않는다.

실제 EC2의 CPU·메모리·디스크·파일 디스크립터, Kafka consumer lag, DB 풀 포화, 경보 전달, 장시간 부하와 강제 종료 복구는 배포 환경에서 검증해야 한다. 이 엔드포인트만으로 운영 관측 체계나 SLA가 완성됐다고 주장하지 않는다.

## 관련 커밋과 참고 자료

- 구현·시험: 이 문서 직전 기능 커밋
- [Spring Boot 정상 종료](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
- [Spring Boot 상태 엔드포인트](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)
