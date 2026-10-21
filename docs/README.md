# 플랫폼 개발 문서

Java 플랫폼의 업무·정합성·보안 결정과 구현·테스트 결과를 관리한다.

## 결정과 검증 기록

- [001. 플랫폼 시작 시 기존 잔액 재입금 제거](decisions/001-remove-startup-balance-replay.md): 시작 이벤트가 자산 증감 요청을 만들던 경로 삭제와 회귀 검증.
- [002. 엔진 전송 결과 불명 시 주문·예약 보존](decisions/002-preserve-uncertain-order-submission.md): 응답 실패를 거부로 처리하던 보상 삭제, 202·조회 계약, HTTP 대기 제한과 검증.
- [003. 같은 고객 요청의 주문 중복 생성 방지](decisions/003-idempotent-order-submission.md): 고객별 요청 키·원본 명령 보존, 동시성·응답 유실·재시작 검증과 새 API 계약.
- [004. 엔진 영속 접수 응답 검증](decisions/004-verify-durable-engine-receipt.md): 상태 코드와 접수 증명 구분, 요청 식별자·상태·시각 검증과 실제 연결 결과.

## 기존 설계·측정 문서

- [정산 설계](SETTLEMENT_DESIGN.md)
- [벤치마크](BENCHMARK.md)

기존 설계와 측정은 현재 코드·측정 환경과 대조해서 읽는다. 문서 존재만으로 금융 정합성과 운영 안전성이 검증됐다고 해석하지 않는다.

## 공통 기준

- [거래소 장기 로드맵](../../docs/roadmap/README.md)
- [공통 작업 규칙·문서 양식](../../AGENTS.md)
