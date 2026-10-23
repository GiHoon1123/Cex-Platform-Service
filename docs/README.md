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

- [운영 역할 부여·변경 승인과 업무별 보안키 서명](decisions/016-operator-role-approval-and-bound-authentication.md)

- [복구·재검토·거래 제한 승인에 업무별 보안키 서명 적용](decisions/017-bound-approval-for-existing-operations.md)

- [018. Didit 신분 확인·결과 불명 복원과 신규 주문 정책](decisions/018-didit-identity-verification-and-trading-policy.md): 서명·환경/고객 결합·중복/역순/충돌·필수 감사·로컬 대역 검증. 실제 공급사 연동과 전체 CDD/AML은 별도 미완료.

- [019. 플랫폼 KYC 누락 웹훅 복원·확인 기한·재시작](decisions/019-platform-kyc-reconciliation.md): 원래 서명 이력과 현재 결정 대조, 영속 임대·경합·호출 제한·주문 차단과 실제 JVM 강제 종료 검증. 엔진 워커 변경은 아님.

- [020. 고객 신고·서명 신원 대조와 초기 위험 평가](decisions/020-customer-declaration-and-initial-risk.md): 신고 암호화·신원/AML 일치·중복 신호·현재 평가의 신규 주문 조건. 전체 CDD/AML 완료는 아님.

- [021. EDD 지속 사건과 독립 서명 결론](decisions/021-edd-case-and-independent-review.md): 과거 검토 유지·새 근거 결합·제한적 종결·기한·실제 코드/서명/동시성 검증. 전체 EDD 완료는 아님.

## 2026-10-23 실제 코드 발췌 보강

기존 결정 문서 20개의 문법/원리 설명에 현재 소스의 실제 발췌·출처 위치·처리 순서 설명을 추가했다. 코드 내용은 유지하고 공통 들여쓰기/행 끝 공백만 정리했다. 과거 구현·검증 수치를 현재 소스와 혼동하지 않도록 기준일과 부분 발췌임을 표시했다. [소스 대조 기록](reference/code-examples-verification-2026-10-23.json)을 함께 읽는다. 이 보강은 문서만 변경했으며 제품 테스트를 새로 실행한 결과가 아니다.

## 실제 코드 예시를 찾는 방법

[전체 문서 적용 범위와 소스 대조 결과](../../docs/reviews/2026-10-23-document-code-excerpts.md)에서 이 문서와 연결된 구현 설명·실제 코드·문법·작성한 테스트를 확인한다. 목차·작업 규칙·Git 이력에는 같은 제품 코드를 복사하지 않고 관련 구현 기록을 연결한다. 계획은 구현 결과와, 문서 검증은 제품 테스트와 구분한다.

- [EDD 실제 증빙 요청·보관·감사 열람](decisions/022-edd-evidence-storage-and-access.md): 요청한 PNG의 독립 키 암호화·원본/열람본 분리·최소 열람·감사 롤백·미검증 상태와 추가 확인 차단, 실제 코드와 테스트.

- [주소 문서의 사람 관찰·독립 서명 검토](decisions/023-address-document-independent-review.md): 현재 신고의 최소 열람, 세션/평가/자료에 결합된 영수증·암호화 관찰·재입력·업무별 실제 서명과 감사 원자성. 거래 제한은 유지한다.

- [자금 발생·수취 증빙의 독립 검토와 확정 체결 연결](decisions/024-source-of-funds-pair-and-trade-review.md): 두 자료의 현재 열람·암호화 관찰·명의/순수취액/통화/시점 대조, 실제 원장 순지출 연결과 독립 서명. 외부 입금·자료 진위·자금 배정·재산 형성·거래 허용은 별도다.
