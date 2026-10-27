# 빈 데이터베이스를 버전 이력으로 재현하기

신규 호스트에서 애플리케이션이 우연히 표를 생성하기를 기다리지 않고, 플랫폼 표와 엔진 주문 접수 표를 순서가 고정된 Flyway 이력으로 만든다. 이 문서는 스키마 소유 경계와 빈 데이터베이스 실제 기동 검증을 설명한다.

## 문제와 기존 동작

플랫폼은 `ddl-auto=validate`로 실행하지만 버전이 지정된 기본 스키마가 없었다. 이미 표가 있는 개발 데이터베이스에서는 시작할 수 있었지만 빈 운영 데이터베이스에서는 검증할 대상이 없어 실패했다. 엔진도 시작할 때 `engine_order_receipts`가 이미 존재한다고 가정했기 때문에 단일 호스트의 올바른 최초 기동 순서를 코드와 배포 설정만으로 재현할 수 없었다.

## 기존 제품과 공식 자료의 접근

- [Spring Boot 데이터베이스 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)는 Hibernate와 Flyway를 함께 스키마 생성 주체로 사용하지 않고 한 가지 메커니즘을 선택하도록 안내한다.
- [Spring Boot Flyway 지원](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.migration.flyway)는 `db/migration`의 버전 파일을 시작 시 적용하는 표준 통합을 제공한다.
- [Flyway PostgreSQL 지원](https://documentation.red-gate.com/fd/postgresql-277579325.html)은 PostgreSQL 지원 모듈을 별도 런타임 의존성으로 제공한다.

따라서 제품 스키마는 Flyway가 만들고 Hibernate는 엔티티와 실제 스키마가 같은지만 검증한다.

## 선택과 트레이드오프

선택한 방식은 현재 JPA 표를 `V1` 기준선으로 만들고 엔진 접수 표를 의존 순서가 분명한 `V2`로 분리하는 것이다.

- 이점: 빈 DB 재현, 적용 이력과 체크섬, 플랫폼 표가 먼저 생긴다는 순서를 보장한다.
- 비용: 엔티티를 바꿀 때 SQL 이력도 명시적으로 추가해야 하며, 이미 운영에 적용된 파일은 수정하지 않아야 한다.
- 대안: Hibernate 자동 생성은 초기 구현은 짧지만 변경 이력과 운영 재현성이 없다. 시작 스크립트 한 장은 순서 이력과 적용 체크섬을 제공하지 않는다.
- 제한: `V1`은 현재 기준선이다. 기존 운영 데이터의 무중단 이전을 제공하는 호환 계층은 만들지 않았으며, 빈 신규 환경을 현재 요구사항으로 검증했다.

엔진 접수 DDL은 엔진 독립 테스트에도 필요해 두 저장소에 존재한다. 패키징된 마이그레이션이 외부 파일을 런타임에 참조하도록 만들지 않고, 검증 스크립트가 주석을 제외한 실행 SQL의 동일성을 검사한다. 중복 파일 유지 비용을 감수하는 대신 배포 산출물의 독립성과 회귀 탐지를 얻는다.

## 구현과 관련 코드

- [`V1__baseline_entities.sql`](../../src/main/resources/db/migration/V1__baseline_entities.sql): 현재 23개 JPA 표와 제약의 기준선이다.
- [`V2__engine_order_receipts.sql`](../../src/main/resources/db/migration/V2__engine_order_receipts.sql): `order_submissions` 뒤에 엔진 접수 증명 표를 만든다.
- [`verify_fresh_database.py`](../../scripts/verify_fresh_database.py): 실행마다 별도 DB를 만들고 실제 플랫폼을 시작한 뒤 이력과 접수 표를 확인하고 그 DB만 삭제한다.
- 격리 서명·방송·확인·운영자 프로세스는 Flyway 자동 구성을 제외한다. 스키마 변경 권한은 고객 플랫폼 프로세스 하나에만 둔다.

## 이해에 필요한 문법과 원리

아래는 실제 `V2` 일부다.

```sql
CREATE TABLE engine_order_receipts (
    order_id BIGINT PRIMARY KEY REFERENCES order_submissions(order_id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    fee_rate NUMERIC(10,6) NOT NULL CHECK (fee_rate >= 0 AND fee_rate < 1)
);
```

`REFERENCES`는 존재하는 플랫폼 주문 접수만 엔진 접수로 기록하게 한다. `ON DELETE RESTRICT`는 플랫폼 원본을 지워 금융 접수 근거가 고아가 되는 것을 막는다. `NUMERIC`은 이진 부동소수점 반올림 대신 지정한 십진 정밀도를 사용한다.

격리 프로세스에는 다음 자동 구성을 명시적으로 제외한다.

```java
@EnableAutoConfiguration(exclude = {
    org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class
})
```

Spring의 자동 구성 후보에서 Flyway를 빼므로 해당 프로세스는 스키마 이력을 조회하거나 적용하지 않는다. TypeScript 프로세스가 역할별 진입 파일에서 불필요한 초기화 모듈을 가져오지 않는 것과 목적은 같지만, Java에서는 자동 구성 제외를 실행 경계에 명시한다.

## 작성한 테스트

- `scripts/verify_fresh_database.py`: 엔진 DDL 동일성, 실제 플랫폼 생존 응답, Flyway `1,2` 순차 성공, 접수 표 존재를 검증한다.
- `IsolatedProcessBoundaryTest.assertBeanBoundary`: 다섯 수탁 워커와 운영자 관리 진입점에 `Flyway` 빈이 없음을 검증한다.
- Gradle `test` 작업은 Flyway를 항상 끈다. 기존 회귀는 실행기별 소유 PostgreSQL 또는 H2 임시 스키마를 만들며 제품 마이그레이션 검증은 위 빈 DB 실행기만 소유한다.
- 컨테이너 검증: 빈 PostgreSQL DB를 대상으로 플랫폼 이미지를 비특권 사용자 `cex`로 실행하고 생존 응답과 이력 `1,2`를 확인했다.

## 실행 명령과 결과

```bash
DATABASE_ADMIN_URL='postgresql://root:1234@127.0.0.1:5433/postgres' \
  python3 scripts/verify_fresh_database.py
./gradlew --no-daemon test --tests dustin.runtime.IsolatedProcessBoundaryTest
docker build -t cex-platform:deployment-review .
```

- 빈 DB 검증: 4개 확인 모두 통과했고 스크립트가 자신이 만든 DB를 삭제했다.
- 격리 경계: 6개 진입점의 비웹·비Kafka·비JPA·비Flyway 경계가 통과했다.
- 이미지: 일곱 실행 JAR 생성, 비특권 사용자 실행, 실제 생존 응답과 Flyway `1,2` 적용을 확인했다.
- 의존성 감사: 233개 패키지 버전을 OSV와 대조해 알려진 검출 0건이었다. 이는 운영 OS·JDK 이미지·향후 공지를 포함한 취약점 부재 보장이 아니다.

최초 전체 Gradle 실행은 `CEX_TEST_DATABASE_URL` 없이 PostgreSQL 소유 회귀까지 한 DB에서 시작해 실패했고, Flyway를 끈 뒤에도 각 실행기가 요구하는 격리 DB가 없어 실패했다. 이 저장소의 PostgreSQL 회귀는 테스트 묶음별 DB를 만드는 `scripts/verify_*.py`가 기준이다. 실패를 제품 통과에 합산하지 않고 관련 격리 실행기와 빈 DB 실행기로 다시 검증한다.

## 남은 한계

기준선은 신규 환경을 위한 것이다. 실제 운영 데이터가 생긴 뒤에는 새 버전 파일과 백업·복원 훈련이 필요하다. 단일 호스트 디스크 손실은 Flyway가 보호하지 않으며 외부 복제와 백업이 별도 운영 조건이다.

## 관련 커밋과 참고 자료

스키마 이력과 검증은 `feat(platform): 빈 데이터베이스 마이그레이션 고정` 커밋, 컨테이너 실행 경계는 뒤따르는 배포 커밋에서 확인한다. 공식 근거는 위 Spring Boot와 Flyway 문서를 사용했다.
