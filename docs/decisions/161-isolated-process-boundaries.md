# 고객 웹 플랫폼과 운영·서명·방송·확인 프로세스 경계 고정

이 결정 당시 같은 코드 저장소에서 만들던 다섯 실행 파일이 서로 다른 권한을 유지하도록 실제 Spring 컨텍스트와 JAR 진입점을 검증했다. 이 과정에서 비트코인 워커 세 개가 JPA 자동 구성 일부 때문에 기동하지 못하던 결함을 찾아 수정했다. 이후 ETH 서명·확인 경계가 추가되어 현재 실행기는 일곱 JAR와 여섯 비웹 경계 시험을 검증한다.

## 문제와 기존 동작

운영자 초기화, 비트코인 서명, 방송, 체인 확인은 고객 HTTP 플랫폼과 분리한 전용 진입점이었다. 그러나 서명·방송·확인 진입점은 `HibernateJpaAutoConfiguration`만 제외하고 `DataJpaRepositoriesAutoConfiguration`을 남겼다. 실제 컨텍스트는 존재하지 않는 `entityManagerFactory`를 찾다가 시작에 실패했다. 기존 전체 출금 시험은 서비스 객체를 직접 연결했기 때문에 JAR 진입점의 이 결함을 발견하지 못했다.

또한 `setWebApplicationType(NONE)`만 사용하면 외부 `spring.main.*` 설정이 자바 API 설정을 덮을 수 있다. 비웹 워커가 같은 의존성 집합의 Spring MVC를 보고 웹 컨텍스트를 만드는 회귀를 더 강하게 차단할 필요가 있었다.

## 기존 제품과 공식 자료의 접근

Spring Boot는 클래스 경로에 MVC가 있으면 기본적으로 서블릿 웹 컨텍스트를 선택하며, 비웹 실행에는 `WebApplicationType.NONE`을 사용할 수 있다고 설명한다. 컨텍스트 종류를 완전히 통제할 때는 `setApplicationContextFactory`를 제공한다. 외부 설정은 주 소스를 제외한 Java API 설정을 덮을 수 있으므로 실행 파일 자체의 경계를 별도로 고정했다.

- [Spring Boot SpringApplication과 웹 환경](https://docs.spring.io/spring-boot/reference/features/spring-application.html)
- [Spring Boot 외부 SpringApplication 설정 우선순위](https://docs.spring.io/spring-boot/how-to/properties-and-configuration.html)
- [Spring Boot 자동 구성 제외](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)

자동 구성은 클래스 경로에 따라 기능을 붙이므로 JDBC만 쓰는 워커는 Hibernate와 Spring Data JPA 저장소 자동 구성을 모두 제외한다. 고객 API는 별도의 `CexApplication`에서 웹·보안·Kafka·JPA를 소유한다.

## 선택과 트레이드오프

| 선택 | 얻는 이점 | 비용·제약 |
| --- | --- | --- |
| 실행 파일마다 명시적 `@Configuration`과 `@Import` 유지 | 컴포넌트 탐색으로 고객 API나 다른 권한이 섞이지 않음 | 필요한 빈을 직접 열거해야 함 |
| 일반 `AnnotationConfigApplicationContext`를 강제 | 외부 서블릿 설정에도 HTTP 서버가 생기지 않음 | 워커에 HTTP 상태 확인을 바로 붙일 수 없음 |
| JPA 저장소 자동 구성까지 제외 | JDBC 워커가 실제로 기동하고 영속 계층 범위가 작아짐 | 워커 안에서 JPA 저장소를 바로 쓸 수 없음 |
| 실제 컨텍스트 기동과 JAR 선언을 한 검증기로 확인 | 서비스 단위 시험이 놓치는 배포 진입점 결함을 탐지 | 시험 빌드 시간이 늘고 H2는 운영 PostgreSQL 권한을 증명하지 않음 |

HTTP 상태 확인이 필요해지면 개인키·방송 프로세스에 고객 웹 스택을 합치는 대신 프로세스 관리자와 별도 운영 채널을 사용한다. JPA가 필요해질 때도 자동 탐색을 열기 전에 해당 워커가 정말 엔티티 생명주기를 소유해야 하는지 다시 판단한다.

## 구현과 관련 코드

출처: [BitcoinSignerApplication.java](../../src/main/java/dustin/signer/BitcoinSignerApplication.java)의 부분 발췌.

```java
app.setWebApplicationType(WebApplicationType.NONE);
app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
app.setApplicationContextFactory(type ->
    new org.springframework.context.annotation.AnnotationConfigApplicationContext());
```

`WebApplicationType.NONE`은 자동 선택을 비웹으로 정한다. `StandardEnvironment`는 서블릿 전용 환경을 만들지 않으며, 컨텍스트 팩터리는 외부가 `servlet`을 요구해도 일반 애플리케이션 컨텍스트만 생성한다. 서명·방송·확인과 운영자 초기화가 같은 생성 규칙을 사용한다.

출처: 같은 파일의 자동 구성 제외 부분.

```java
@EnableAutoConfiguration(exclude = {
    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
})
```

두 자동 구성은 별개다. Hibernate 엔티티 관리자만 제외하고 저장소 자동 구성을 남기면 공유 엔티티 관리자 빈이 만들어지려다 기동이 실패한다. 세 비트코인 워커는 `JdbcTemplate`과 JDBC 트랜잭션 관리자만 사용한다.

## 이해에 필요한 문법과 원리

`application()`은 실제 `main`과 시험이 같은 `SpringApplication` 객체를 만들게 하는 정적 팩터리 메서드다. TypeScript에서 서버 생성 설정을 함수로 반환하고 실행 파일과 시험이 함께 호출하는 방식과 비슷하다. 차이는 Spring이 클래스 경로와 자동 구성 조건으로 많은 빈을 런타임에 추가하므로, 단순히 함수 반환값만 검사하지 않고 실제 컨텍스트를 시작해야 한다는 점이다.

`try (var context = ...)`는 Java의 자동 자원 정리 구문이다. 시험이 끝나면 컨텍스트와 데이터 소스, RPC 클라이언트를 닫아 다음 시험에 스레드나 연결을 남기지 않는다.

## 작성한 테스트

- 파일: [IsolatedProcessBoundaryTest.java](../../src/test/java/dustin/runtime/IsolatedProcessBoundaryTest.java)
- `isolatedEntrypointsUseExplicitConfigurationInsteadOfCustomerComponentScanning`: 네 격리 진입점이 `@SpringBootApplication` 탐색 대신 명시적 구성이고, 실제 팩터리가 정확한 주 소스와 비웹 유형을 갖는지 확인한다.
- `signerIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker`: 서블릿 명령행 설정에도 일반 컨텍스트와 서명 워커만 존재하고 HTTP·Kafka·JPA 빈은 없는지 확인한다.
- `broadcasterIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker`: 방송 워커만 존재하고 서명·확인 워커와 웹·Kafka·JPA 빈이 없는지 확인한다.
- `confirmerIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker`: 확인 워커만 존재하고 서명·방송 워커와 웹·Kafka·JPA 빈이 없는지 확인한다.
- 실행기: [verify_process_boundaries.py](../../scripts/verify_process_boundaries.py)는 현재 여섯 시험을 새로 실행하고 고객·운영·BTC 세 개·ETH 두 개, 총 일곱 JAR의 `Start-Class`를 확인한다.

## 실행 명령과 결과

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
python3 scripts/verify_process_boundaries.py
```

- 시험 4개 통과, 실패·오류·제외 0
- 고객 플랫폼, 운영자 초기화, 서명, 방송, 확인 JAR 다섯 개 빌드 성공
- 각 JAR의 실제 `Start-Class`가 기대 진입점과 일치
- 첫 컨텍스트 실행은 세 워커 모두 `entityManagerFactory` 부재로 실패했고, 누락된 Spring Data JPA 저장소 자동 구성 제외를 추가한 뒤 최종 실행이 통과했다.
- 첫 검증기 실행은 Gradle의 실제 아카이브 기본 이름 `cex`를 저장소 디렉터리 이름으로 잘못 가정해 JAR 판독 단계만 실패했다. 제품 시험과 빌드는 성공했으며 이름을 실제 산출물에 맞춘 후 검증기를 다시 실행했다.

[기계 판독 증적](../reference/161-platform-process-boundary-verification-2026-10-27.json)에 최종 명령, 시험 수, JAR 진입점과 로그 위치를 남겼다.

## 남은 한계

이번 검증은 프로세스 내부 빈과 배포 JAR 경계를 확인한다. 운영의 서로 다른 운영체제 계정·컨테이너·비밀 주입·PostgreSQL 역할·Bitcoin Core RPC 권한은 배포 설정과 실제 환경에서 별도로 검증해야 한다. H2 기동은 운영 DB 권한 최소화나 PostgreSQL SQL 호환성을 증명하지 않는다.

## 관련 커밋과 참고 자료

- 구현·시험: `233e266`
- 문서·검증 증적: 현재 문서 커밋
