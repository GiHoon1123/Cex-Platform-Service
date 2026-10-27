# 플랫폼 운영 설정과 로컬 설정 분리

운영 기본 설정에서 개발용 DB 계정·로컬 Kafka·로컬 엔진·로컬 WebAuthn 출처를 제거했다. 로컬 편의값은 명시적으로 선택하는 `local` 프로필에만 남기고 운영 스키마는 자동 변경하지 않는다.

## 문제와 기존 동작

기본 `application.properties`가 `root/1234`, `localhost:5432`, 로컬 Kafka와 엔진 주소를 포함하고 `ddl-auto=update`를 사용했다. 프로필을 빠뜨린 운영 실행이 개발용 주소로 연결을 시도하고 Hibernate가 스키마를 자동 변경할 수 있었다.

## 기존 제품과 공식 자료의 접근

Spring Boot는 환경 변수와 명령행 인자를 포함한 외부 설정을 지원하며 `application-{profile}.properties`가 공통 설정을 환경별로 덮어쓰게 한다. 운영 비밀 저장소 자체는 이번 범위가 아니지만 애플리케이션이 외부 주입을 요구하는 경계는 코드로 고정했다.

참고: [Spring Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html), [Spring Boot 프로필](https://docs.spring.io/spring-boot/reference/features/profiles.html)

## 선택과 트레이드오프

공통 설정은 필수 환경 변수와 `ddl-auto=validate`를 사용하고, 로컬 값만 `application-local.properties`에 둔다. 설정 없는 실행이 실패하는 비용이 생기지만 운영 오접속과 런타임 스키마 변경보다 원인을 빠르게 드러낸다.

Flyway를 즉시 도입하는 대안은 현재 여러 초기화기와 전체 기준선을 한 사이클에 바꾸면 금융 스키마 회귀 범위가 지나치게 커져 채택하지 않았다. `validate`는 마이그레이션 체계를 대신하지 않는다. 운영 배포 전에 별도 스키마 적용 절차가 필요하다.

## 구현과 관련 코드

`src/main/resources/application.properties`의 실제 발췌다.

```properties
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DATABASE_USERNAME}
spring.datasource.password=${DATABASE_PASSWORD}
spring.jpa.hibernate.ddl-auto=validate
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}
engine.http.url=${ENGINE_HTTP_URL}
```

중괄호 자리표시는 Spring 환경에서 값을 찾는다. 기본값을 뜻하는 콜론 뒤 값을 두지 않았으므로 누락 시 시작 과정에서 해석 오류가 난다. 테스트 프로필은 H2 또는 실행 소유 PostgreSQL을 명시하고, 로컬 실행은 `--spring.profiles.active=local`을 사용한다.

## 작성한 테스트

- `DeploymentConfigurationTest.productionDefaultsRequireExternalEndpointsAndCredentials`: 공통 설정이 모든 외부 주소와 자격 증명을 요구하고 기존 개발용 문자열을 포함하지 않는지 검사한다.
- `DeploymentConfigurationTest.localCredentialsExistOnlyInTheExplicitLocalProfile`: 로컬 포트와 자동 스키마 갱신이 명시적 로컬 프로필에만 있는지 검사한다.
- `PlatformSecurityTest` 전 회귀: 설정 분리가 등록 API의 인증·업무 경계를 바꾸지 않는지 실행 소유 PostgreSQL에서 확인한다.

## 실행 명령과 결과

```text
./gradlew test --tests '*DeploymentConfigurationTest'
2 passed, 0 failed, 0 skipped

python3 scripts/verify_platform_security.py
42 passed, 0 failed, 0 errors, 0 skipped

python3 scripts/verify_market_path.py
실제 플랫폼 HTTP·PostgreSQL·Rust WAL·Kafka·공개 소켓·재시작·독립 대사 통과
실행 소유 PostgreSQL·Kafka 회수 성공
```

최초 보안 회귀에서는 테스트 WebAuthn 출처를 새 어드민 포트로 잘못 바꿔 13건이 실패했다. 제품 설정 문제가 아니라 기존 테스트 증명의 고정 출처와 불일치한 것이므로 테스트 프로필을 원래 격리 출처로 복구하고 위 전 회귀를 통과시켰다. 실패 실행을 성공 결과에 포함하지 않는다.

전체 시장 경로의 첫 실행은 새 필수 DB 계정을 통합 시험에 주입하지 않아 인증 단계에서 실패했고, 두 번째 실행은 통합 시험이 엔진의 폐기된 `DATABASE_HOST` 계약을 사용해 실패했다. 세 번째 실행은 시험 안에서 다시 띄우는 대사 명령이 같은 필수 설정을 전달하지 않아 실패했다. 모든 실제 프로세스 경계를 `DATABASE_URL`과 명시적 Spring 계정으로 통일한 뒤 위 전체 경로를 통과시켰다.

## 관련 커밋과 참고 자료

관련 커밋과 최종 보안 회귀 수치는 재실행 완료 뒤 기록한다.
