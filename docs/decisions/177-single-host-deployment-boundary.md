# 단일 호스트 배포의 외부 경계 고정

초기 운영 범위에 맞춰 PostgreSQL·Kafka·플랫폼·엔진·고객/운영 프론트·격리 수탁 워커를 한 Compose 스택으로 재현한다. 외부에는 TLS 프록시만 열고 엔진과 데이터 계층은 내부 네트워크에 둔다.

## 문제와 기존 동작

각 프로세스는 개별 실행할 수 있었지만 저장소 전체의 최초 기동 순서, 포트 노출, 영속 볼륨, 비밀 주입 항목이 실행 가능한 배포 정의로 연결되지 않았다. 특히 엔진을 호스트에 직접 공개하면 프론트의 유일한 진입점이 플랫폼이라는 설계와 충돌한다.

## 기존 제품과 공식 자료의 접근

- [Docker Compose 서비스 시작 순서](https://docs.docker.com/compose/how-tos/startup-order/)의 상태 기반 의존성을 사용한다.
- [Docker Compose 비밀 관리](https://docs.docker.com/compose/how-tos/use-secrets/)가 설명하듯 환경 변수는 최종 운영 비밀 저장소가 아니다. 현재 파일은 키 이름만 정의하고 실제 값은 저장소 밖에서 주입한다.
- [NGINX WebSocket 프록시](https://nginx.org/en/docs/http/websocket.html)의 명시적 Upgrade 전달을 적용한다.
- [Docker 컨테이너 재시작 정책](https://docs.docker.com/engine/containers/start-containers-automatically/)의 `unless-stopped`를 사용하되 데이터 복제나 다중 호스트 고가용성으로 과장하지 않는다.

## 선택과 트레이드오프

하나의 EC2와 Compose를 선택했다. 배포와 장애 분석이 단순하고 포트·볼륨·실행 경계를 코드로 재현할 수 있다. 반면 호스트 장애가 전체 서비스 장애가 되고 CPU·메모리·디스크가 경쟁한다. 엔진 CPU 고정과 영속 볼륨은 경쟁을 줄일 뿐 호스트 손실을 보호하지 않는다.

플랫폼을 먼저 시작해 Flyway를 적용하고 엔진을 뒤에 시작한다. 엔진은 플랫폼 표를 참조하는 접수 표를 필요로 하기 때문이다. 플랫폼의 생존 상태는 엔진 가용 상태가 아니므로 주문 준비 상태는 별도 API 의미를 유지한다.

온체인 처리는 기본값을 끈다. BTC/ETH 노드와 실제 키 경계가 준비된 경우에만 명시적으로 활성화한다. 지급 워커는 `payout` 프로필로 분리해 일반 API 컨테이너가 서명 RPC 자격을 받지 않게 한다. `.env`는 로컬 실행 형식일 뿐 운영 비밀 저장소가 아니며, 운영에서는 호스트 권한이 제한된 비밀 파일 또는 관리형 비밀 저장소가 필요하다.

## 구현과 관련 코드

- [`compose.yml`](../../deploy/single-host/compose.yml): 단일 파티션 Kafka, 내부 데이터 계층, 상태 기반 기동 순서, 엔진 CPU/권한, 별도 지급 워커를 정의한다.
- [`nginx.conf.template`](../../deploy/single-host/nginx.conf.template): 고객/운영 호스트를 나누고 고객 시세 WebSocket만 플랫폼으로 전달한다.
- [`.env.example`](../../deploy/single-host/.env.example): 필요한 설정 이름과 안전한 비활성 기본값만 둔다.
- [`Dockerfile`](../../Dockerfile): 일곱 JAR를 한 번 빌드하고 비특권 사용자로 원하는 진입 JAR를 실행한다.

외부 공개 포트는 프록시의 80/443뿐이다. 플랫폼 REST는 고객·운영 Next.js의 서버측 BFF가 내부 주소로 호출하고, 엔진 3002·플랫폼 8080·PostgreSQL 5432·Kafka 9092는 호스트에 게시하지 않는다.

## 이해에 필요한 문법과 원리

실제 Compose 일부다.

```yaml
engine:
  cpuset: ${ENGINE_CPUSET:-0}
  cap_add: [SYS_NICE]
  expose: ["3002"]
  depends_on:
    platform: {condition: service_healthy}
```

`expose`는 Compose 내부 통신 포트만 문서화하고 호스트 포트를 만들지 않는다. `cpuset`은 엔진 컨테이너가 사용할 CPU를 제한하며 `SYS_NICE`는 실시간 우선순위 요청에 필요한 권한이다. 이 설정도 같은 코어에서 호스트 작업이 실행되는 것을 막지는 못하므로 실제 호스트의 CPU 배치 검증이 필요하다.

격리 지급 워커는 다음처럼 선택 프로필에 둔다.

```yaml
ethereum-signer:
  profiles: [payout]
  command: ["java", "-jar", "/app/cex-0.0.1-SNAPSHOT-ethereum-signer.jar"]
```

기본 `docker compose up`에는 서명기가 포함되지 않는다. 실제 지급을 켤 때만 `--profile payout`과 별도 RPC 자격을 제공한다.

## 작성한 테스트

- Compose 전체와 `payout` 프로필을 실제 환경 변수 모양으로 렌더링해 필수값·경로·YAML 구문을 검사했다.
- NGINX 공식 이미지에 임시 자체 서명 인증서와 내부 호스트 해석을 제공해 `nginx -t`를 실행했다.
- 플랫폼 이미지를 빈 PostgreSQL DB에 실행해 생존 응답, Flyway `1,2`, 비특권 사용자 `cex`를 확인했다.
- 엔진과 고객 프론트 이미지는 각 저장소 결정 문서의 컨테이너 회귀에서 확인한다.

## 실행 명령과 결과

```bash
env $(sed 's/=$/=test/' deploy/single-host/.env.example | xargs) \
  docker compose -f deploy/single-host/compose.yml --profile payout config --quiet
docker build -t cex-platform:deployment-review .
```

Compose 렌더링, NGINX 구문, 플랫폼 이미지 빌드와 빈 DB 실제 기동이 모두 통과했다. 검증에는 실제 공인 인증서, AWS 보안 그룹, 운영 도메인, BTC/ETH 메인넷 노드, 보호된 키 저장소를 사용하지 않았다.

## 남은 한계

- 단일 호스트와 단일 Kafka 브로커·PostgreSQL은 고가용성이 아니다.
- 디스크·호스트 손실은 외부 백업과 복제, 실제 복원 훈련이 필요하다.
- TLS 인증서 발급·갱신, AWS IAM/보안 그룹, 로그 외부 보존, 이미지 서명과 배포 승인 절차는 운영 단계에서 추가 검증해야 한다.
- 실제 지급 프로필은 HSM 또는 보호된 서명 노드와 실제 운영자 승인 체계 없이는 활성화하면 안 된다.

## 관련 커밋과 참고 자료

배포 파일과 이 문서는 `build(platform): 단일 호스트 실행 경계 고정` 커밋에 포함한다. 공식 근거는 위 Docker와 NGINX 문서를 사용했다.
