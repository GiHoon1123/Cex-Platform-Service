# 지원되는 Spring 런타임과 보안 패치로 전환하기

Spring Boot 3.2.5·Security 6.2.4를 지원되는 4.1/7.1 계열로 교체했다. 단순 버전 변경에 그치지 않고 JSON·테스트·재시도 API를 전환하고, 실제 선택된 전이 의존성의 취약점·버전·체크섬을 검증했다.

[문서 목차](../README.md) · [고정된 의존성 검토 결과](../reference/dependency-review-2026-10-22.json) · [접근 통제 선행 기록](012-platform-access-approval-audit.md)

## 문제와 선택 기준

선행 접근 통제 사이클은 역할·승인·감사 동작을 검증했지만 기존 Boot 3.2.5가 선택한 오래된 라이브러리까지 운영 기준으로 승인하지 않았다. 2026-10-22 공식 문서와 Maven Central을 확인해 안정 버전 Boot 4.1.1·Security 7.1.1을 선택했다. 사전 버전 4.2와 지원이 끝난 3.x를 목표로 삼지 않았다. Java는 현재 프로젝트의 21을 유지하고 Gradle을 공식 요구 범위인 8.14.6으로 올렸다.

Boot 4는 모듈별 스타터·Jackson 3·Hibernate 7·새 테스트 패키지를 사용한다. 구형 API를 감싼 호환 계층이나 이전 매퍼로 돌아가는 경로를 만들지 않고 현재 코드·의존성을 직접 수정했다. 기존 데이터의 스키마 변경이나 상시 서비스 배포는 이번 범위에 포함하지 않았다.

## 공식 자료의 접근

- [Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [4.0 전환 안내](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide): 버전·모듈별 스타터·JSON·테스트 API 전환.
- [Spring Security 공식 문서](https://docs.spring.io/spring-security/reference/), [보안 공지](https://spring.io/security/): 영향 버전과 수정 버전 확인. WebAuthn·DPoP·OAuth 동의 화면처럼 현재 사용하지 않는 기능의 공지도 전체 서비스 안전 판정과 혼동하지 않는다.
- [Spring Framework 재시도](https://docs.spring.io/spring-framework/reference/core/resilience.html), [기존 Spring Retry 보관 상태](https://github.com/spring-attic/spring-retry): 별도 중단 프로젝트 대신 설치된 Framework 7의 기능 채택.
- [Jackson 3 전환](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0), [springdoc](https://springdoc.org/), [JJWT JSON 지원](https://github.com/jwtk/jjwt#json-support): 실제 지원 기능과 전이 의존성을 확인한 뒤 JSON 구현 선택.
- [Gradle 의존성 잠금](https://docs.gradle.org/current/userguide/dependency_locking.html), [파일 검증](https://docs.gradle.org/current/userguide/dependency_verification.html), [OSV API](https://google.github.io/osv.dev/api/): 버전 재현·파일 무결성·알려진 취약점 대조를 서로 다른 통제로 관리.

## 버전 결정과 보안 검토

| 구성 | 이전/기본 선택 | 이번 확정 | 이유 |
|---|---|---|---|
| Spring Boot / Security | 3.2.5 / 6.2.4 | 4.1.1 / 7.1.1 | 지원되는 안정 계열과 공식 보안 수정 |
| Gradle Wrapper | 8.5 | 8.14.6 | Boot 요구 범위·Java 21 유지, 배포 SHA-256·공식 Wrapper 재생성 |
| Jackson 3 | Boot 기본 3.1.5 | 3.1.8 | 같은 계열 후속 보안 패치 |
| Jackson 2 | Boot 기본 2.21.5 | 2.21.8 | Swagger·JJWT 내부 사용 의존성의 보안 패치 |
| Tomcat | Boot 기본 11.0.24 | 11.0.27 | 공식 수정 범위 이후 패치 |
| Kafka client / LZ4 | Boot 4.1.1 관리 client 4.2.1 / LZ4 1.10.1 | 4.2.1 / 1.11.4 | 압축 입력·네이티브 추출 관련 후속 보안 수정 |
| JJWT | 0.12.5 | 0.13.0 | 유지되는 릴리스, 기존 검증 계약 회귀 |
| Argon2 / Java-WebSocket | 2.11 / 1.5.7 | 2.12 / 1.6.0 | 공식 안정 릴리스 |
| springdoc | 2.3.0 | 3.1.1 | Boot 4.1 대응 문서화 모듈 |
| H2 테스트 DB | 기본 2.4.240 | 2.5.252 | 연결 간 CHECK/IDENTITY 회귀의 공식 수정 계열 |
| JMH plugin / 빌드 Commons Lang | 0.7.2 / 3.16.0 | 0.7.3 / 3.21.0 | 유지 릴리스와 빌드 범위의 알려진 취약점 수정 |

초기 Boot 4.1.1 구성의 실제 219개 패키지·버전을 OSV로 조회하자 24개 패키지/공지 조합이 검출됐다. 같은 취약점이 Jackson 2와 3 양쪽에 해당할 수 있으므로 이를 서로 다른 취약점 24개라고 부르지 않는다. 기본 BOM만 최신으로 바꿨다고 검토를 끝내지 않았다.

| 범위 | 확인한 공지 | 조치 |
|---|---|---|
| Jackson core | [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf), [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) | 2.21.8 / 3.1.8로 계열별 패치 |
| Jackson databind | [유형 ID 보존](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54), [전방 참조 처리](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) 및 `GHSA-gx83-3vf8-gh7j`, `GHSA-q4xh-88c3-wmh7`, `GHSA-wjgm-6hv5-3cvf` | 2.21.8 / 3.1.8로 패치. 사용하지 않는 다형성 기능의 영향 여부와 버전 대조를 구분 |
| Tomcat | [공식 보안 목록](https://tomcat.apache.org/security-11.html)의 `GHSA-9xv2-5v5q-p794`, `GHSA-gcx9-497g-6cp6`, `GHSA-h3x4-894j-xpx5`에 대응하는 수정 | 11.0.27로 통일. FORM/DIGEST 미사용을 전체 취약점 면제로 간주하지 않음 |
| LZ4 | [네이티브 추출](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g), `GHSA-343h-94h5-c4wr`, `GHSA-4v53-57pg-c464`, `GHSA-6cx8-rjf8-pr8g`, `GHSA-gm45-99xc-r7wv`, `GHSA-xx22-p4ch-683r` | 1.11.4, Kafka 실제 연결 재검증 |
| 빌드 Commons Lang | `GHSA-j288-q9x7-2f5v` | 애플리케이션과 별개인 빌드 클래스 경로도 수정 |

[최종 조회 증적](../reference/dependency-review-2026-10-22.json)은 219개 패키지·버전에서 **조회 시점 OSV의 알려진 검출 0건**이다. 미등록·신규 취약점, JDK·OS·컨테이너·JNI 내부 바이너리의 전체 안전성이나 외부 침투 검증을 의미하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용·한계 |
|---|---|---|
| Boot 4.1로 직접 전환 | 지원 계열의 단일 코드 경로 | JSON·JPA·테스트 변경 범위가 커 전체 금융 회귀 필요 |
| 기본 BOM 이후 보안 패치 명시 | 이미 발표된 수정 버전 반영 | 기본 조합과 달라 호환성 회귀·버전 근거 유지 필요. 후속 BOM이 같은 수정 이상을 포함하면 중복 고정을 다시 검토 |
| 앱 JSON은 Jackson 3, Swagger/JWT 내부는 필요한 Jackson 2 | 별도 구형 앱 매퍼·Gson 추가 없이 설치된 라이브러리 활용 | 두 Jackson 계열의 패치를 함께 관리. 앱 요청을 이전 매퍼로 재시도하는 계층은 없음 |
| Framework 내장 재시도 | 중단된 별도 Spring Retry 제거 | 시도 수 의미·소진 예외·프록시 호출 차이를 검사해야 함 |
| 버전 잠금 + SHA-256 검증 | 이후 빌드의 버전/파일 변경 탐지 | 버전 교체 때 잠금·메타데이터의 의도적 검토 필요. 최초 받은 파일의 출처를 독립 서명으로 증명한 것은 아님 |
| OSV 조회 실패·검출 시 실행 실패 | 조회 장애를 취약점 없음으로 오인하지 않음 | 외부 API 가용성·등록 지연에 의존. 전체 보안 심사를 대체하지 않음 |

## 구현과 코드

[build.gradle](../../build.gradle)은 중복된 구형 BOM 고정을 삭제하고 모듈별 MVC·Kafka·테스트 스타터를 사용한다. 불필요했던 직접 Bouncy Castle 의존성은 코드 참조가 없어 제거했다. 버전 변경과 보안 패치·이유는 같은 파일에 명시한다.

- [CexApplication](../../src/main/java/dustin/cex/CexApplication.java): 현재 보안 자동 설정 패키지와 `@EnableResilientMethods` 사용. 기본 로그인 화면·Basic 인증을 다시 켜지 않는다.
- [SecurityConfiguration](../../src/main/java/dustin/cex/domains/security/SecurityConfiguration.java): 제거된 Ant 매처를 새 표준 요청 매처로 바꾼다. 경로·메서드별 기본 거부 계약은 유지한다.
- 애플리케이션 JSON import를 `tools.jackson`으로 전환했다. `com.fasterxml.jackson.annotation`은 Jackson 3도 공식 사용하는 주석 패키지이므로 무작정 치환하지 않았다. 시간 모듈·삭제된 날짜 설정을 제거하고 ISO 문자열 계약을 테스트했다.
- 테스트의 `@MockBean`/`@SpyBean`을 `@MockitoBean`/`@MockitoSpyBean`으로, JPA/MVC 테스트와 엔티티 탐색의 패키지를 새 API로 바꿨다. 구형 API를 재노출하지 않았다.
- [TradeSettlementScheduler](../../src/main/java/dustin/cex/domains/settlement/trade/scheduler/TradeSettlementScheduler.java): 총 3회 시도, 실패 사이 2초·4초 대기를 유지한다. 실제 복구 없이 로그만 남기던 `@Recover` 메서드를 제거하고 마지막 오류를 전파한다. 기존 월별 집계/사용자 실패가 성공처럼 종료되던 경로도 오류를 전파해 재시도 대상이 되도록 수정했다. 각 금융 트랜잭션·단계별 기록은 기존 서비스가 담당한다.

[gradle.lockfile](../../gradle.lockfile)은 앱·테스트·벤치 해석 버전, [buildscript-gradle.lockfile](../../buildscript-gradle.lockfile)은 빌드 플러그인의 전이 버전을 고정한다. [verification-metadata.xml](../../gradle/verification-metadata.xml)은 해석한 JAR·POM 등의 파일 체크섬을 검증한다. Wrapper도 공식 배포 SHA-256과 생성된 파일을 사용한다. 생성 파일은 도구의 원문 형식을 유지한다.

`writeDependencyInventory`는 실행·테스트·벤치·빌드 범위의 좌표·파일 SHA-256을 내보낸다. [audit_dependencies.py](../../scripts/audit_dependencies.py)는 좌표·버전을 중복 제거해 OSV 공식 API에 조회한다. 네트워크·응답 개수·남은 페이지·파싱 오류는 `INCOMPLETE`와 비정상 종료이며, 취약점 검출은 `FINDINGS`와 비정상 종료다. 이 목록은 CycloneDX SBOM이나 라이선스/OS 전체 검사라고 부르지 않는다.

## 이해에 필요한 문법과 동작

`BOM`은 여러 라이브러리 버전의 기준 묶음이고, 잠금 파일은 그 기준과 직접 의존성으로 **실제 선택된 전이 버전**을 기록한다. TypeScript의 패키지 버전 범위와 lockfile 관계에 대응하지만 Gradle의 여러 클래스 경로는 별도로 해석된다. 버전 잠금만으로 같은 버전 파일의 변조를 막을 수 없어 체크섬 검증도 사용한다.

새 재시도의 `maxRetries=2`는 최초 실행 이후 두 번을 뜻한다. 이전 `maxAttempts=3`과 같은 총 세 번이다. 재시도는 프록시를 통한 호출에 적용되므로 실제 Spring 프록시를 생성해 검사했다. 오류를 내부에서 삼키면 프록시는 성공으로 판단하므로 내부 실패 전파도 계약의 일부다.

`@MockitoBean`은 테스트 컨텍스트의 빈을 모킹하고 `@MockitoSpyBean`은 실제 빈을 감싸 관찰한다. JSON·트랜잭션·서비스 동작의 실제 통합 검증과 외부 송신 모킹을 구분해야 한다. `@TestConfiguration`은 재시도 시험용 모킹 설정이 전체 앱의 탐색에 섞이지 않게 한다.

### 현재 코드로 연결해 읽기

지원 런타임의 내장 재시도 어노테이션을 사용하는 현재 설정이다. 아래는 2026-10-23 현재 [실제 소스](../../src/main/java/dustin/cex/domains/settlement/trade/scheduler/TradeSettlementScheduler.java#L78)의 연속 행 발췌다. 메서드 일부일 수 있으므로 독립 실행 예제로 사용하지 않고 앞뒤 검증과 함께 읽는다. 과거 사이클의 코드/측정 시점은 본문의 기록을 유지한다.

```java
@Retryable(
        includes = RuntimeException.class,
        // 최초 실행 한 번과 추가 재시도 두 번으로 기존 총 세 번을 유지한다.
        maxRetries = 2,
        delay = 2000,
        multiplier = 2
)
```

어노테이션에 재시도 횟수·지연·예외 범위를 고정한다. 재시도는 멱등성이나 금융 원자성을 대신하지 않으며, 스케줄러의 잠금·정산 실행 기록은 본문의 별도 통제를 따른다.

## 작성·수정한 테스트

[FrameworkContractTest](../../src/test/java/dustin/cex/domains/runtime/FrameworkContractTest.java)의 신규 7개:

| 함수 | 시나리오·기대 결과 |
|---|---|
| `jacksonThreePreservesExactAmountAndIsoTime` | Rust Decimal 경계의 큰 9자리 소수와 ISO 시각을 JSON 왕복해 정확히 보존 |
| `applicationUsesSupportedBootAndSecurityWithoutOldMapperModule` | Boot 4.1·Security 7.1, 구형 앱 Jackson 모듈·Spring Retry 없음 |
| `checkConstraintStillWorksAfterSchemaConnectionCloses` | DDL 연결을 닫고 다른 연결에서 정상 상태 삽입 성공, 잘못된 상태 거부 |
| `dailyRetryMakesThreeAttemptsAndPropagatesLastFailure` | 실제 프록시의 총 3회 시도·마지막 일별 오류 전파 |
| `monthlyAggregateFailureIsNotReportedAsSuccess` | 월별 집계 실패가 성공처럼 끝나지 않고 3회 시도 후 오류 전파 |
| `monthlyRetryStopsAfterTransientFailureSucceeds` | 일시 실패 후 두 번째 시도에서 성공하면 즉시 종료하고 추가 재시도 없음 |
| `monthlyUserFailureDoesNotBecomeSuccessfulCompletion` | 사용자별 월별 정산 미완료도 성공 처리하지 않음 |

[PlatformSecurityTest](../../src/test/java/dustin/cex/domains/security/PlatformSecurityTest.java)의 신규 `newPathMatchingDoesNotOpenSimilarOrNestedPrivateRoutes`는 유사/중첩 경로가 미인증 401·고객 권한 403으로 차단되는지 확인한다. `corsAllowsOnlyConfiguredFrontendAndReviewedHeaders`는 허용 프론트·헤더의 사전 요청만 통과하고 다른 출처를 거부하는지 확인한다. 기존 35개 보안 검사를 새 라이브러리에서 함께 실행했다.

기존 JSON·JPA·트랜잭션·원장·대사 테스트도 새 API로 수정했다. 실제 프로세스 시험은 제품의 JWT·세션 검사를 유지하며 봇과 외부 의존성을 격리한다. 이전 제약을 삭제하거나 테스트를 제외해 버전 변경을 통과시키지 않았다.

## 실행 명령과 결과

| 명령 | 결과 |
|---|---|
| `./gradlew compileTestJava jmhClasses bootJar writeDependencyInventory` | 제품/테스트/벤치 컴파일, 실행 JAR·의존성 목록 생성 통과. 성능 벤치는 실행하지 않음 |
| `python3 scripts/audit_dependencies.py` | 219개 패키지·버전, 조회 시점 알려진 OSV 검출 0건 |
| `python3 scripts/verify_engine_inbox.py` | 기존 176개 + 신규 런타임 7개 = 183개 통과, 실패·제외 0 |
| `python3 scripts/verify_platform_security.py` | 37개 통과, 실패·제외 0 |
| `python3 scripts/verify_order_idempotency.py` | 새 JAR 요청 키·경쟁·재시작 10개 시나리오 통과 |
| `python3 scripts/verify_market_path.py` | 실제 체결·시세·원장·대사·별도 CLI 전체 경로 1개 통과 |
| `python3 ../Cex-Engine/scripts/verify_durable_acceptance.py` | 실제 응답 유실·재시작·영속 접수 5개 시나리오 통과 |

[verify_dependency_integrity.py](../../scripts/verify_dependency_integrity.py)는 임시 복사본에서 Security JAR의 기대 체크섬만 변조하고 실제 Gradle 검증 실패를 확인했다. 원본 저장소·라이브러리 캐시는 변경하지 않았고 임시 복사본은 삭제했다. 기대한 Gradle 종료 1을 테스트 성공으로 구분한다. 검증 정책의 음수 경로를 실제로 실행한 결과다.

초기 검증에서는 삭제된 패키지/시간 설정, H2 2.4.240의 연결 간 CHECK 제약 회귀를 발견했다. H2 문제는 [공식 이슈 4302](https://github.com/h2database/h2database/issues/4302), [동일 회귀 재현 4308](https://github.com/h2database/h2database/issues/4308)과 대조했다. 최초 금융 회귀의 54개 실패를 숨기지 않고 원인을 수정한 뒤 183개를 재실행했다. 초기 OSV 24개 검출도 수정 후 다시 조회했다.

모든 DB·Kafka·프로세스는 실행기가 소유한 자원이다. 기존 로컬 금융 DB·Kafka·상시 서비스는 수정하지 않았으며 봇은 계속 비활성화했다. Rust 체결 코어와 엔진 저장·출력 코드, 프론트는 변경하지 않았다. Java의 일부 deprecated API·Gradle 향후 버전 경고와 JVM 공유 경고는 남아 있다. 새 런타임의 성능 수치는 측정하지 않았다. 현재 로컬 Java 실행을 검증했으며 Linux EC2·배포 이미지·해당 환경의 네이티브 동작은 배포 전에 별도로 검증해야 한다.

## 남은 범위와 다음 작업

운영자 MFA·기기/계정 복구·권한 생명주기를 다음 업무로 진행한다. KYC/AML·출금/정산/정정 승인, 실제 운영 증적·네트워크·키·외부 보존과 인허가/인증 심사는 별도 과제다. 이번 의존성 결과는 해당 범위의 완료를 의미하지 않는다.

OSV에 등록되지 않은 취약점과 JNI·JDK·OS·컨테이너, 취약 기능의 실제 도달 가능성·운영 설정은 추가 검토 대상이다. 다음 버전 변경 때는 공식 지원·보안 공지를 다시 대조하고 의존성/검증 메타데이터를 검토한 뒤 같은 회귀를 실행한다. 자동 갱신을 무조건 승인하는 설정은 추가하지 않았다.

기능·테스트·빌드 변경과 문서를 나눠 커밋한다. 실제 해시는 공통 사이클 기록에 남긴다.

기능·테스트·빌드 커밋: [`1ee1bf3`](https://github.com/GiHoon1123/Cex-Platform-Service/commit/1ee1bf3ffc547d751fbabab70394eb843f2f535e).
