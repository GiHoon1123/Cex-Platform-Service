# 메일 워커 격리와 실제 프로세스 종료·복원

메일 출력 워커를 실제 SMTP 전/후에 강제 종료해 영속 임대와 외부 결과의 불확실성을 검증했다. 이 과정에서 메일 스케줄러가 일반 예약 작업의 스케줄러까지 대체하던 문제를 고쳤다. 플랫폼 메일의 복구와 Rust 엔진 WAL 복구는 별개다.

## 문제와 기존 동작

053의 customerMailScheduler는 별도 이름의 TaskScheduler 빈이었지만 이것만으로 전역 기본 예약 작업과 격리되지 않았다. 실제 사용 버전 Boot4.1.1의 TaskSchedulingConfigurations.TaskSchedulerConfiguration은 TaskScheduler/ScheduledExecutorService 빈이 있으면 기본 자동 구성을 만들지 않는다. Spring은 유일한 TaskScheduler를 기본 예약 작업에도 사용할 수 있다. 따라서 메일 전송이 막히면 한 풀의 일반 예약 작업도 기다릴 수 있었다. 기존 수동 dispatcher 테스트/기본 비활성 기동만으로 발견하지 못한 실행 경로다.

실제 프로세스에서 SMTP 직전 메일 워커를 정지시키고 기본 TaskScheduler에 별도 검증 작업을 등록했다. 수정 전에는5초 동안 그 작업이 실행되지 않아 검증기가 실패했다. 이 실패를 통과로 처리하지 않았고 실행 소유 프로세스/컨테이너를 정리했다.

## 기존 제품·공식 자료·실제 의존성 확인

[Spring EnableScheduling](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/EnableScheduling.html)의 기본 스케줄러 선택과 [TaskScheduler 설명](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)의 ThreadPoolTaskScheduler/고정 지연 실행을 확인했다. 조회한 일반 설명은7.0.10, 프로젝트 실제 Framework는7.0.9다. [Boot4.1.1 API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/task/TaskSchedulingAutoConfiguration.html)와 설치된4.1.1 JAR의 javap 런타임 애너테이션도 대조했다. 참고 페이지 일부는 웹 도구로 가져오지 못했으며 이를 확인 완료로 표시하지 않고 실제 설치물과 재현 테스트로 판단했다.

기존 프로젝트의 [KycReconciliationWorker](../../src/main/java/dustin/cex/domains/kyc/KycReconciliationWorker.java)는 수명이 긴 전용 인스턴스를 직접 관리한다. 같은 패턴과 이미 설치된 Spring 라이브러리를 채택했다. 별도 라이브러리/스케줄러 프레임워크는 추가하지 않았다. [GreenMail](https://greenmail-mail-test.github.io/greenmail/)은 외부 전달 없는 로컬 SMTPS에만 사용한다.

## 선택과 트레이드오프

- 메일 전용 ThreadPoolTaskScheduler를 전역 빈으로 노출하지 않고 컴포넌트가 소유한다. 기본 스케줄러 선택에 영향을 주지 않지만 시작/종료 수명을 직접 관리해야 한다. 새 전역 taskScheduler를 수동 정의해 Boot 기본을 대신하는 대안은 채택하지 않는다.
- 기록 표 초기화에 DependsOn을 명시한 뒤 PostConstruct에서 설정을 검증하고 수명이 긴1개 워커를 시작한다. PreDestroy에서 진행 중인 작업을 최대15초 기다린다. 강제 종료/확정 실패는 영속 임대 복구로 처리한다. 매 메일마다 스레드/런타임을 만들지 않는다.
- SMTP 접수 뒤 DB 확정 전 종료는 실제로 중복 메일이 가능하다. 같은 Message-ID와 원래 암호문을 유지하고 확정 기록을 추측하지 않는다. 공급사의 중복 제거/수신함 도달을 보장한다고 주장하지 않는다.
- 기본 예약 작업과 분리해도 같은 호스트의 CPU/디스크/DB 자원 경쟁은 남는다. 이번 검증은 기능/장애 경계이며 처리량·지연 보장은 아니다.

## 구현과 데이터 흐름

메일은 기본 비활성 상태를 유지한다. 명시적으로 켠 환경에만 전용 인스턴스를 초기화하고1초 고정 지연으로 기존 dispatcher/암호문 정리를 실행한다. 일반 @Scheduled 작업은 Boot 기본 스케줄러를 사용한다. 실제 테스트는 메일이 정지한 동안 기본 스케줄러의 검증 작업이 scheduling-* 스레드에서 진행되는지 확인한다. 자정/월말의 실제 정산 크론을 기다려 검증한 것은 아니다.

테스트 전용 Java 진입점은 제품 빈과 실제 출력 스케줄러를 사용한다. SMTP 전/후의 정지 지점만 Primary 전송기에 주입하며 매칭/업무/DB 로직을 복제하지 않는다. 외부로 전달하지 않는 별도 SMTPS JVM은 메시지 ID만 임시 파일로 공개한다. 검증기는 새 PostgreSQL16 tmpfs 서버·루프백 주소·공개 격리 키·봇0명/외부 엔진·Kafka 차단을 명시한다. 두 제품 JAR에 이 테스트 진입점이 포함되지 않는지도 확인했다.

복구는 플랫폼의 메일 예약/임대/감사 DB를 읽는다. Rust 체결 코어·자체 저널·Kafka 확정 경계에는 변경이 없다. 테스트에서 임대 시각을 만료시킬 뿐 기존 작업/시도/감사/금융 기록을 수정하거나 삭제하지 않는다.

## 실제 코드와 이해에 필요한 원리

출처: [CustomerMailScheduling.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailScheduling.java#L22), 현재 소스의 부분 발췌.

```java
    @PostConstruct void start(){
        transport.requireReady();
        // TaskScheduler 빈으로 등록하면 Spring의 기본 스케줄러 자동 구성이 물러날 수 있다.
        // 기존 KYC 워커처럼 수명이 긴 전용 인스턴스를 소유해 일반 정산/시세 작업과 분리한다.
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("customer-mail-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(15);scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
```

컴포넌트 초기화 때 한 번 전용 풀을 만들고 메서드 참조 this::tick을 반복 실행한다. @Bean으로 내보내지 않으므로 기본 스케줄러 후보가 되지 않는다. TypeScript setInterval과 달리 풀·종료 대기와 고정 지연 실행 수명을 명시한다.

출처: [CustomerMailScheduling.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailScheduling.java#L30), 현재 소스의 부분 발췌.

```java
    @PreDestroy void stop(){
        // 정상 종료에는 진행 중인 전송을 기다린다. 강제 종료/시간 초과 뒤 미확정 임대는 DB에서 복구한다.
        if(scheduler!=null)scheduler.shutdown();
    }
```

정상 종료에는 소유한 풀을 종료한다. SMTP가 이미 접수했더라도 결과 트랜잭션이 없으면 미확정 임대다. 이 상태를 성공으로 추측하거나 예약을 지우지 않는다.

출처: [CustomerMailRecoveryProcessProbe.java](../../src/test/java/dustin/cex/domains/kyc/CustomerMailRecoveryProcessProbe.java#L25), 현재 소스의 부분 발췌.

```java
                @Override public void send(CustomerMailOutbox.Payload p){
                    String phase=System.getenv("CEX_MAIL_PROBE_PHASE");if("pause-before".equals(phase))pause("MAIL_BEFORE_SMTP:"+p.id());
                    super.send(p);
                    // 실제 SMTP 접수 뒤 결과 트랜잭션 전의 장애를 재현한다. 업무 로직이나 결과 기록은 복사하지 않는다.
                    if("pause-after".equals(phase))pause("MAIL_AFTER_SMTP:"+p.id());
                }
```

익명 하위 클래스가 테스트 전송기를 구성한다. super.send는 실제 제품 SMTP 전송이며 그 앞/뒤만 멈춘다. Primary는 이 프로세스에서만 제품 빈보다 우선하도록 등록하는 테스트 장치다. 제품 JAR에는 들어가지 않는다.

## 작성한 자동화 검증

신규 JUnit 코드는0개, 신규 실제 프로세스 검증은6개다. [검증기](../../scripts/verify_customer_mail_recovery.py), [제품 빈 프로브](../../src/test/java/dustin/cex/domains/kyc/CustomerMailRecoveryProcessProbe.java), [별도 SMTP 수신함](../../src/test/java/dustin/cex/domains/kyc/CustomerMailSmtpSink.java)을 사용한다.

| 검증 이름 | 시나리오와 기대 결과 |
| --- | --- |
| pause-before_kill_preserves_lease_and_unconfirmed_result | SMTP 전 정지·기본 스케줄러 진행·SIGKILL, 수신0건·SENDING:1·RESULT0건 유지 |
| pause-before_fresh_jvm_recovers_original_message_id | 임대 만료 후 새 JVM 복구, 같은 메시지 ID1건 수신·SMTP_ACCEPTED:2·CLAIMED2/RESULT1 |
| pause-after_kill_preserves_lease_and_unconfirmed_result | 실제 SMTP 접수 후 결과 기록 전 정지·기본 스케줄러 진행·SIGKILL, 미확정 임대/RESULT0건 유지 |
| pause-after_fresh_jvm_recovers_original_message_id | 새 JVM이 같은 메시지 ID를 재전송해2개가 수신될 수 있으며 확정 결과는1건 |
| confirmed_jobs_are_not_resent_by_later_jvm | 다시 기동해 정상 틱을 진행해도 두 확정 작업은 추가 발송하지 않음, 합계3개 수신 유지 |
| unsafe_tls_prevents_scheduler_start_and_claim | 평문 SMTP 설정은 기동 실패, 기존 확정 작업의 시도 합계4와 수신3개가 바뀌지 않음 |

## 실행 명령과 결과

`python3 scripts/verify_customer_mail_recovery.py`: 실제 워커 JVM6개와 독립 SMTPS JVM에서 위6개 통과. 종료/복구 시점별 주장과 정리 상태를 확정 JSON에 보존했다. 테스트가 소유한 프로세스·컨테이너·SMTP 포트·임시 디렉터리를 정리했다. 수정 전 기본 스케줄러 정지 재현은 별도 실패 증적이다.

현재 소스에서 `python3 scripts/verify_customer_mail.py`95개, `verify_engine_inbox.py`183개, `verify_market_path.py`1개를 통과했다. 앞선057에서 공통 보안 변경을 `verify_bound_approvals.py`190개·`verify_operator_mfa.py`42개로 통과했다. 이번 세 사이클의 관련 고유 JUnit 선택은 **511개**, 신규37개(056의12+057의25) 포함, 실패·오류·제외0이다. 프로세스6개는 JUnit과 별도이며 과거1,616개 전체를 이번에 재실행했다고 표시하지 않는다. 최종058의 스케줄러 분리 뒤에는 메일/금융/시장 연결을 재검증했다. 이어 기록 표 준비 순서를 명시한39abf7d에서 실제 프로세스6개와 두 JAR/JMH/목록 생성을 다시 통과했다. 초기화 순서 보완 뒤511개 전체를 다시 실행했다고 표시하지 않는다. 공통 승인 코드는057과 같다.

`./gradlew operatorAdminJar bootJar jmhClasses writeDependencyInventory --console=plain`도 통과했고 두 제품 JAR의 검증 진입점 제외를 확인했다. 의존성 추가/버전 변경은 없고 목록229개 버전과 기존 해시가 유지된다. 이번 사이클에서 OSV 데이터베이스를 다시 조회하지 않았다. 기존 deprecated/unchecked/JVM 공유 경고와 테스트 프로필의 SpringDoc 개발 문서 활성 경고는 남는다. 엔진/프론트 변경·기존 개발 서버 재시작·운영 배포 없이 봇0명이다.

## 남은 운영 확인

실제 공급사 계약/외부 전달·수신함 도달/바운스·운영 경보 전달·운영자가 원인을 해결했는지 확인·법정 고지·대량 큐 성능·키 회전/보존·호스트/디스크 손실은 별도다. 외부 복제/백업은 기존 문서 조건만 유지한다. 정상 종료의 모든 네트워크 지연 조합이나 자정/월말 정산 크론·OS 장애까지 검증한 것은 아니다. 기본 스케줄러 진행과 SMTP 전/후 프로세스 강제 종료 범위만 주장한다.

## 작성한 테스트 코드


## 관련 커밋과 증적

- [구현 86b7709](https://github.com/GiHoon1123/Cex-Platform-Service/commit/86b7709d564fbc1620d6604ff3d53f3865e3b2b3)
- [격리 실행 증적](../reference/058-customer-mail-worker-recovery-verification-2026-10-24.json)

- [기록 표 준비 뒤 워커 시작 39abf7d](https://github.com/GiHoon1123/Cex-Platform-Service/commit/39abf7d4b1e3fd27fe196396f313897444c69325)
