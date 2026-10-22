# 플랫폼 개발 문서

Java 플랫폼의 업무·정합성·보안 결정과 구현·테스트 결과를 관리한다.

## 결정과 검증 기록

- [001. 플랫폼 시작 시 기존 잔액 재입금 제거](decisions/001-remove-startup-balance-replay.md): 시작 이벤트가 자산 증감 요청을 만들던 경로 삭제와 회귀 검증.
- [002. 엔진 전송 결과 불명 시 주문·예약 보존](decisions/002-preserve-uncertain-order-submission.md): 응답 실패를 거부로 처리하던 보상 삭제, 202·조회 계약, HTTP 대기 제한과 검증.
- [003. 같은 고객 요청의 주문 중복 생성 방지](decisions/003-idempotent-order-submission.md): 고객별 요청 키·원본 명령 보존, 동시성·응답 유실·재시작 검증과 새 API 계약.
- [004. 엔진 영속 접수 응답 검증](decisions/004-verify-durable-engine-receipt.md): 상태 코드와 접수 증명 구분, 요청 식별자·상태·시각 검증과 실제 연결 결과.

- [Java·DB·Rust 공통 금액 계약과 예약 검증](decisions/005-common-exact-amount-contract.md): 입력·저장·조회 경계 오류 재현, 수정과 회귀 검증.

- [006. 엔진 WAL 재발행의 영속 중복 방지](decisions/006-engine-event-inbox.md): 이벤트 ID·본문 검사, 금융 변경과 수신 키의 동시 커밋, 소비 실패 중단과 격리 검증.

## 기존 설계·측정 문서

- [정산 설계](SETTLEMENT_DESIGN.md)
- [벤치마크](BENCHMARK.md)

기존 설계와 측정은 현재 코드·측정 환경과 대조해서 읽는다. 문서 존재만으로 금융 정합성과 운영 안전성이 검증됐다고 해석하지 않는다.

## 공통 기준

- [거래소 장기 로드맵](../../docs/roadmap/README.md)
- [공통 작업 규칙·문서 양식](../../AGENTS.md)

- [007. BTC 주문·자체 시세의 플랫폼 진입점](decisions/007-platform-market-gateway.md): 영속 호가·공개 체결, 전체 스냅샷 스트림, 봇 제한, 취소 반영 순서와 실제 전체 경로 검증.

- [008. 수수료 권위와 체결 변화량](decisions/008-fee-authority-and-balance-deltas.md): 원본 적용률·후속 예약 보존·자산별 수익·실제 통합 검증.

- [009. 시장가 예산·IOC 종료·예약 반환](decisions/009-market-ioc-settlement.md): 수량 미정 처리·만료·정밀도 수정·실제 재발행 검증.

- [응답 불명 자동 재전송과 확정 거절 예약 반환](decisions/010-durable-order-resolution.md): 원본 검증·재시작·중복 처리·실제 장애 주입 검증.

- [주문 자산 원장과 독립 대사](decisions/011-asset-ledger-and-reconciliation.md): 확정 근거·불변 기록·오류 탐지·실제 프로세스 검증.

- [세션 회수·운영 요청/승인·필수 감사](decisions/012-platform-access-approval-audit.md): API 전용 인증·서비스 경계·실제 검증·남은 운영 요건.

- [지원 런타임·전이 의존성 보안 패치와 검증](decisions/013-supported-runtime-and-dependency-audit.md): Boot 4.1·Security 7.1, Jackson 3·내장 재시도, 취약점 대조·버전 잠금·체크섬 검증.

## 운영 인증과 권한 수명

- [014. 운영자 WebAuthn 등록·인증](decisions/014-operator-webauthn-mfa.md): 비밀번호 대기 세션, 실제 보안키 서명, 최근 MFA·예비 키와 검증.
- [015. 분실 기기 폐기·계정 복구·권한 만료와 회수](decisions/015-operator-recovery-and-access-lifecycle.md): 독립 인물 승인·기한 재검토·필수 감사, 비웹 관리 JAR과 실제 프로세스 검증.
