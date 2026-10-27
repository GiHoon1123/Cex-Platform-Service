# 자동 시장 신호를 담당자 조사 사건과 불변 이력으로 잇기

자동 적출 결과를 위반 확정으로 취급하지 않고, 담당자가 배정받아 원본을 검토하고 관찰을 남긴 뒤 무조치 종결 또는 추가 검토 회부로 끝내는 사건 수명주기를 추가했다.

## 문제와 기존 동작

164의 신호는 목록에서 확인할 수 있었지만 누가 조사 중인지, 어떤 확인을 했는지, 왜 닫거나 회부했는지 남길 구조가 없었다. 165의 시장 중단 승인은 신호를 근거로 삼지만 신호에 대한 사람의 조사 기록을 대신하지 않는다. 단순 `status` 덮어쓰기는 과거 판단과 변경 주체를 잃는다.

## 기존 제품과 공식 자료의 접근

[가상자산이용자보호법 제12조](https://www.law.go.kr/LSW/LsiJoLinkP.do?docType=&joNo=&languageType=KO&lsNm=%EA%B0%80%EC%83%81%EC%9E%90%EC%82%B0+%EC%9D%B4%EC%9A%A9%EC%9E%90+%EB%B3%B4%ED%98%B8+%EB%93%B1%EC%97%90+%EA%B4%80%ED%95%9C+%EB%B2%95%EB%A5%A0&paras=1)은 상시 감시와 조치를, [시행령 제13조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lspttninfSeq=188951)는 감시·조치 결과 기록을 요구한다. 금융위원회의 [시장감시 설명](https://www.fsc.go.kr/po010105/87357)은 적출 뒤 신속한 심리와 당국 통보를 별도 단계로 설명한다. 이에 맞춰 자동 평가, 조사 사건, 시장 통제 승인을 서로 다른 기록으로 뒀다.

## 선택과 트레이드오프

검토 신호마다 사건 하나를 자동으로 열고 `OPEN → UNDER_REVIEW → CLOSED_NO_ACTION | ESCALATED`만 허용한다. 관찰은 상태를 바꾸지 않는 별도 사건이고 현재 담당자만 쓸 수 있다. 현재 상태 행은 빠른 작업 큐용으로 갱신하지만, 모든 변화의 원본은 삭제할 수 없는 사건 표다.

단일 담당자는 조사 속도와 책임 범위를 단순하게 만든다. 담당자 부재 시 재배정 절차가 아직 없다는 비용이 있다. 결론에 독립 승인까지 붙이면 모든 오탐 종결이 과도하게 느려지므로 사건 종결은 담당자 책임으로 두고, 실제 시장 중단·고객 제한·외부 보고는 각자의 별도 승인 경계를 사용한다.

## 구현과 관련 코드

- [MarketSurveillanceService.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceService.java)는 `REVIEW_REQUIRED` 평가와 신호·열린 사건·첫 사건 기록을 같은 트랜잭션에 만든다.
- [MarketSurveillanceCases.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceCases.java)는 현재 운영 세션과 역할을 재검사해 배정·관찰·종결을 수행한다.
- [surveillance-schema.sql](../../src/main/resources/market/surveillance-schema.sql)은 상태 전이와 같은 트랜잭션의 사건을 강제하고 사건 삭제·수정을 거부한다.
- [MarketSurveillanceController.java](../../src/main/java/dustin/cex/domains/market/MarketSurveillanceController.java)는 목록·상세·이력 읽기와 운영자 변경 API를 분리한다.

## 이해에 필요한 문법과 원리

제품 코드의 일부다.

```java
UUID signal=UUID.randomUUID(),investigation=UUID.randomUUID();
jdbc.update("INSERT INTO market_surveillance_signals(id,assessment_id) VALUES(?,?)",signal,assessment);
jdbc.update("INSERT INTO market_surveillance_cases(id,signal_id,status) VALUES(?,?,'OPEN')",investigation,signal);
jdbc.update("INSERT INTO market_surveillance_case_events(...) VALUES(...,'OPENED','OPEN',?)", ...);
```

세 삽입과 감사는 하나의 Spring 트랜잭션에 참여한다. PostgreSQL의 지연 제약은 커밋 시 사건과 첫 이력이 모두 존재하는지 검사하므로 열린 사건만 남거나 신호만 남는 부분 성공을 막는다.

```java
event(current,actor,sid,outcome,outcome,actor,detail);
jdbc.update("UPDATE market_surveillance_cases SET status=?,version=version+1,...",outcome,id);
```

이력부터 추가하고 현재 상태를 바꾼다. DB 트리거는 같은 트랜잭션의 사건이 다음 상태와 담당자를 정확히 가리키는지 검사한다. TypeScript의 낙관적 버전 필드와 비슷하지만 서비스 비교뿐 아니라 DB 제약까지 포함한다.

## 작성한 테스트

[MarketSurveillanceIntegrationTest.java](../../src/test/java/dustin/cex/domains/kyc/MarketSurveillanceIntegrationTest.java)의 전체 흐름에서 신호 두 개가 사건 두 개를 만들고, 배정 전 관찰 거부, 현재 운영자 배정, 관찰, 다른 운영자의 종결 거부, `ESCALATED` 종결, 사건 네 건의 순서, 사건·현재 상태 직접 변조 거부를 검증한다. 기존 탐지·역할별 HTTP·고객 자동 제한 없음도 함께 회귀한다.

## 실행 명령과 결과

```sh
python3 scripts/verify_market_surveillance.py
```

최종 실행은 감시1·시장 통제4·취소1·소비자 단위14, 총20개가 통과했고 실패·오류·제외는0이다. 각 묶음에서 두 JAR도 빌드했고 격리 컨테이너를 회수했다. 결과는 [검증 JSON](../reference/166-market-case-verification-2026-10-27.json)에 남겼다.

현재 `ESCALATED`는 내부 추가 검토 상태이며 감독기관 신고 완료를 뜻하지 않는다. 담당자 재배정, 사건 간 연관 계정 묶기, 규칙 효과·오탐률 측정은 후속 운영 고도화다.

## 관련 커밋과 참고 자료

구현·테스트 커밋은 이 문서 직전 이력이다. 164의 신호와 165의 별도 시장 통제 승인을 함께 읽는다.
