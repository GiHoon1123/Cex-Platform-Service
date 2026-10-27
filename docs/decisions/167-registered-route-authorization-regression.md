# 등록된 모든 API 경로의 인증·역할 경계 회귀 검증

컨트롤러가 실제로 등록한 경로를 보안 회귀의 입력으로 사용한다. 새 API가 생겼는데 개별 보안 시험을 빼먹더라도 미인증 접근, 고객의 운영 경로 접근, 운영자의 고객 업무 접근을 자동으로 거절하는지 확인한다.

## 문제와 기존 동작

플랫폼은 Spring Security의 순서 있는 경로 규칙과 마지막 기본 거절을 사용한다. 그러나 보안 시험이 알고 있는 경로만 나열하면 새 컨트롤러가 추가됐을 때 시험 목록과 실제 실행 경로가 달라질 수 있다. 특히 입출금·시장감시·정정 업무가 늘면서 고객과 운영자 경계를 공통으로 다시 확인할 필요가 생겼다.

## 기존 제품과 공식 자료의 접근

- [Spring Security 요청 인가](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)는 규칙을 선언 순서대로 평가하며 마지막 `anyRequest().denyAll()` 같은 기본 거절을 제시한다.
- [OWASP 인가 지침](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)은 기본 거절과 모든 요청의 권한 검사를 권고한다.

이번 변경은 제품의 기존 기본 거절을 유지하면서, 실제 Spring MVC 등록 목록을 시험 입력으로 삼아 설정과 컨트롤러의 차이를 줄인다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 한계 |
|---|---|---|
| 실제 `RequestMappingHandlerMapping` 순회 | 새 경로가 시험 목록에 자동 포함됨 | 역할별 모든 세부 업무 권한까지 증명하지는 않음 |
| 미인증·교차 역할을 컨트롤러 진입 전에 검사 | 데이터 생성 없이 공통 경계를 빠르게 검증 | 정상 역할의 객체 소유권·내용 결합 승인은 업무별 통합 시험이 계속 담당함 |
| 공개 경로를 메서드와 정확한 경로로 제한 | 비슷한 이름·중첩 경로의 우발 공개를 막음 | 공개 API를 추가하면 의도적으로 허용 목록과 근거를 갱신해야 함 |

정적 소스 파서나 별도 경로 목록은 실제 런타임 등록과 어긋날 수 있어 선택하지 않았다. 이 회귀 하나로 금융 업무 인가가 완성됐다고 보지 않고 기존 업무별 시험과 함께 사용한다.

## 구현과 관련 코드

[PlatformSecurityTest.java](../../src/test/java/dustin/cex/domains/security/PlatformSecurityTest.java)의 일부다.

```java
mappings.getHandlerMethods().keySet().forEach(info->{
    var patterns=info.getPathPatternsCondition();
    if(patterns==null)return;
    var methods=info.getMethodsCondition().getMethods();
    for(var pattern:patterns.getPatternValues()) {
        if(!pattern.startsWith("/api/"))continue;
        if(methods.isEmpty())routes.add(Map.entry(HttpMethod.GET,concretePath(pattern)));
        else methods.forEach(method->routes.add(Map.entry(HttpMethod.valueOf(method.name()),concretePath(pattern))));
    }
});
```

Spring이 최종 조합한 컨트롤러와 HTTP 메서드를 읽는다. 경로 변수는 인증 필터까지 도달할 형식상 값으로 치환한다. 미인증 요청은 모두 `401`, 고객 토큰의 `/api/admin/**`와 운영자 토큰의 고객 업무는 `403`이어야 한다. 공개 가입·로그인·갱신·KYC 웹훅·시장 조회만 정확한 메서드와 경로로 제외한다.

## 이해에 필요한 문법과 원리

`Map.Entry<HttpMethod,String>`은 TypeScript의 `[method, path]` 튜플과 비슷한 두 값 묶음이다. `getHandlerMethods()`는 애플리케이션 컨텍스트에 실제 등록된 매핑을 반환하므로 소스 파일 이름이나 문서가 아니라 실행 구성을 검사한다. 람다 안의 `return`은 전체 시험을 끝내지 않고 현재 매핑 처리만 건너뛴다.

보안 필터는 컨트롤러보다 앞에서 실행된다. 따라서 잘못된 역할은 요청 본문 파싱이나 업무 서비스 호출 전에 거절되어야 한다. 이 시험에서 경로 변수의 고정값이 실제 레코드를 가리키지 않아도 되는 이유다.

## 작성한 테스트

| 파일·함수 | 시나리오 | 기대 결과 |
|---|---|---|
| `PlatformSecurityTest.everyRegisteredApiRouteKeepsTheAuthenticationAndApplicationBoundary` | 실제 등록된 모든 `/api/**` 경로에 미인증 요청 | 명시한 공개 경로를 제외하고 전부 `401` |
| 같은 함수 | 고객 토큰으로 모든 운영자 경로 호출 | 전부 `403` |
| 같은 함수 | 운영자 토큰으로 고객·거래·KYC 업무 호출 | 전부 `403` |
| 기존 `newPathMatchingDoesNotOpenSimilarOrNestedPrivateRoutes` | 공개 이름과 비슷한 미등록 경로 | 미인증 `401`, 잘못된 역할 `403` |

## 실행 명령과 결과

```bash
python3 scripts/verify_platform_security.py
```

2026-10-27 격리 PostgreSQL 16과 봇 0명, Kafka·엔진 외부 연결 비활성 조건에서 `PlatformSecurityTest` 42개가 통과했다. 실패·오류·제외는 0개이며 `bootJar`도 성공했다. 첫 컴파일에서는 시험 클래스의 기존 `request()` 메서드와 정적 빌더 이름이 충돌했고, 호출 대상을 완전한 클래스 이름으로 명시한 뒤 전체를 다시 실행했다. 이 중간 실패를 통과 수에 포함하지 않는다.

이 검증은 현재 등록 경로의 HTTP 경계를 확인한다. 운영 프록시, 네트워크 방화벽, 데이터베이스 역할, 업무별 객체 소유권과 실제 배포 권한은 별도 검증 대상이다.

## 관련 커밋과 참고 자료

- 구현·시험: 이 문서를 추가한 직전 기능 커밋
- [Spring Security 요청 인가](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
