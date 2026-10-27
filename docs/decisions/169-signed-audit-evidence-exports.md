# 범위가 고정된 감사 증적 내보내기와 독립 키 서명

운영 감사 목록 조회와 외부 제출용 묶음 생성을 분리한다. 최대 31일·1,000건의 정렬된 사건을 정확한 조회 조건과 함께 고정하고, 별도 HMAC 키로 서명해 저장 뒤 다시 검증할 수 있게 한다.

## 문제와 기존 동작

`security_audit`는 DB 트리거로 수정·삭제가 금지되고 역할이 제한된 목록 조회가 있었다. 그러나 제출 시 어떤 시간 범위와 필터의 몇 건을 내보냈는지, 제출 파일이 저장 이후 바뀌지 않았는지 확인할 묶음 단위 증거가 없었다. 화면에서 복사하거나 임의 SQL 결과를 파일로 저장하면 정렬·누락·키 관리·접근 기록을 재현하기 어렵다.

## 기존 제품과 공식 자료의 접근

- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)는 로그의 무단 변경·삭제 탐지, 읽기 권한 제한, 로그 접근 기록, 추출 시 민감정보 최소화를 권고한다.
- [NIST SP 800-92](https://csrc.nist.gov/pubs/sp/800/92/final)는 로그 관리 인프라와 생성·전송·저장·접근·처분의 운영 절차를 다룬다.

현재 감사 사건은 비밀번호·토큰·요청 원문을 저장하지 않는 기존 규칙을 유지한다. 이번 묶음은 기존 사건을 새 범용 애플리케이션 로그와 합치지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 한계 |
|---|---|---|
| 정렬된 JSON 원문·SHA-256·HMAC을 함께 보존 | 같은 바이트의 내용과 별도 비밀키 소유를 검증 | 키 교체·보관 절차가 필요하고 HMAC은 공개 검증 서명이 아님 |
| 최대 31일·1,000건 | 과도한 메모리·응답·개인정보 복사를 제한 | 긴 기간은 여러 묶음으로 나눠야 함 |
| APPROVER·AUDITOR와 최근 MFA만 생성 | 제출 권한과 일반 운영을 분리 | 긴급 내보내기도 최근 인증이 필요함 |
| 생성 당시 원문을 불변 저장 | 나중의 직렬화 라이브러리 변경에도 당시 바이트 검증 | 감사 데이터를 한 번 더 저장하는 비용이 생김 |

DB 행의 해시만 반환하는 대안은 조회 조건과 사건 전체가 빠질 수 있어 선택하지 않았다. 공개키 서명은 제3자 검증에 유리하지만 현재 키 수명·HSM 운영이 없으므로, 현재 요구를 충족하는 가장 단순한 별도 HMAC 키를 사용하고 한계를 명시한다.

## 구현과 관련 코드

[AuditEvidenceService.java](../../src/main/java/dustin/cex/domains/security/AuditEvidenceService.java)의 일부다.

```java
var manifest=new Manifest("AUDIT_EVIDENCE_V1",id,start,end,action,created,List.copyOf(events));
String raw=json.writeValueAsString(manifest),hash=digest(raw),signature=key.sign(raw);
```

사건은 `occurred_at,id` 오름차순으로 읽는다. 정책 버전, 내보내기 ID, 반개구간 `[rangeStart, rangeEnd)`, 선택 action, 생성 시각과 사건 목록을 한 JSON에 넣는다. SHA-256은 내용 식별, HMAC-SHA-256은 설정된 별도 키를 가진 플랫폼이 만든 묶음인지 확인한다.

[AuditEvidenceKey.java](../../src/main/java/dustin/cex/domains/security/AuditEvidenceKey.java)는 Base64 디코딩 결과가 32바이트보다 짧거나 없으면 내보내기와 검증을 실패시킨다. JWT·CDD·메일·지급 키를 재사용하지 않는다. 키 ID는 키 자체가 아니라 SHA-256 지문이다.

## 이해에 필요한 문법과 원리

```java
var events=jdbc.query(sql,statement->{
    statement.setTimestamp(1,Timestamp.from(start));
    statement.setTimestamp(2,Timestamp.from(end));
    if(action!=null)statement.setString(3,action);
},rowMapper);
```

람다는 Java의 함수 값이며 TypeScript의 `(statement) => { ... }`와 비슷하다. `PreparedStatement`의 자리표시자에 시간과 선택 필터를 결합해 문자열 입력을 SQL에 이어 붙이지 않는다. action이 없을 때는 SQL 자체에도 세 번째 자리표시자가 없다.

HMAC은 해시와 다르다. 해시는 누구나 다시 계산할 수 있지만 HMAC은 비밀키가 있어야 같은 값을 만들 수 있다. 다만 같은 키를 가진 운영 주체는 새 서명을 만들 수 있으므로 운영 키 접근, 외부 보관, 제출 절차까지 자동으로 증명하지 않는다.

## 작성한 테스트

| 파일·함수 | 시나리오 | 기대 결과 |
|---|---|---|
| `AuditEvidenceIntegrationTest.boundedExportIsSignedImmutableAndVerifiable` | action 필터 한 건 내보내기·검증·UPDATE/DELETE | 서명·해시 64자리, 1건 검증, 변조 SQL 실패 |
| `rangeAndVolumeBoundariesFailClosed` | 역순 범위, 32일 범위, 키 미설정 | 모두 닫힌 상태로 실패 |
| `httpBoundaryRejectsCustomerAndOrdinaryOperator` | 고객·일반 운영자·승인자 생성 | 고객/운영자 `403`, 승인자 성공 |
| `PlatformSecurityTest.everyRegisteredApiRouteKeepsTheAuthenticationAndApplicationBoundary` | 새 경로 포함 실제 API 전수 | 미인증·교차 역할 우회 차단 |

## 실행 명령과 결과

```bash
python3 scripts/verify_audit_evidence.py
python3 scripts/verify_platform_security.py
```

2026-10-27 격리 PostgreSQL 16에서 감사 증적 3개와 등록 API 보안 42개가 각각 통과했다. 실패·오류·제외는 0개이고 첫 실행기는 고객 플랫폼·운영자 초기화 JAR, 두 번째 실행기는 고객 플랫폼 JAR을 빌드했다. 봇·Kafka 소비·엔진 전송은 비활성이고 소유 컨테이너를 제거했다.

현재 HMAC 키의 HSM 보관·교체·과거 검증 키 유지, 읽기 전용 외부 저장소/WORM, 제출 수신자의 공개 검증과 전송 암호화는 운영·인프라 과제다. 이번 코드는 외부 감독기관 제출 완료나 법정 증거능력을 주장하지 않는다.

## 관련 커밋과 참고 자료

- 구현·시험: 이 문서 직전 기능 커밋
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [NIST SP 800-92](https://csrc.nist.gov/pubs/sp/800/92/final)
