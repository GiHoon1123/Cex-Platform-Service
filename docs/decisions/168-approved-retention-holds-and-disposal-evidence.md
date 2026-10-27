# 독립 승인 보존 정지와 개인정보 암호문 파기 증적

금융·감사 원본을 보존하는 것과 목적이 끝난 개인정보를 파기하는 일을 분리한다. 현재 실제 파기 대상인 고객 메일 수신 주소·일회 토큰 암호문에 데이터별 기한, 보존 정지, 해제, 파기 실행 증적을 연결한다.

## 문제와 기존 동작

기존 메일 발송함은 만료 후 하루가 지나고 전달이 종료된 암호문을 최대 100개씩 지웠다. DB 트리거와 감사 롤백은 있었지만 조사·분쟁 때문에 파기를 일시 정지할 방법과 어떤 행이 어느 실행에서 파기됐는지 남기는 전용 증적이 없었다. 모든 금융·KYC·감사 표를 같은 기간 뒤 삭제하는 방식은 법정 보존과 추적 가능성을 훼손한다.

## 기존 제품과 공식 자료의 접근

- [개인정보 보호법 제21조](https://law.go.kr/LSW/lsLinkCommonInfo.do?lsJoLnkSeq=1029334977)는 보유기간 경과나 목적 달성으로 불필요해진 개인정보를 파기하되 다른 법령에 따른 보존을 구분한다. 확인한 법률 제21445호의 시행일은 2026-09-11이다.
- [특정금융정보법 개정 이유](https://law.go.kr/LSW/lsRvsRsnListP.do?chrClsCd=010102&lsId=009244)는 의심거래보고·고객확인 관련 자료를 금융거래 관계 종료 후 5년 보존하는 취지를 설명한다. 정확한 사업자 적용 자료·기산점은 법률 검토가 필요하다.
- [ISMS-P 제도 안내](https://isms.kisa.or.kr/sysm/intro/selectSysmCertDetail.do)는 개인정보 파기 보호조치를 인증 범위로 둔다.

법률 기간을 임의로 전체 테이블에 코딩하지 않았다. 이번 정책은 이미 목적이 끝난 메일 전달 암호문에 한정하고, 금융·감사·KYC 이력은 파기 대상으로 등록하지 않는다.

## 선택과 트레이드오프

| 선택 | 이점 | 비용과 제약 |
|---|---|---|
| 자료 종류별 허용 목록 | 금융 원본의 우발 삭제를 막음 | 새 개인정보 종류마다 근거·기한·파기 방법을 별도로 추가해야 함 |
| 보존 정지와 해제를 추가 전용 기록으로 분리 | 과거 정지 사유와 해제를 덮어쓰지 않음 | 조회 시 활성 여부를 조합해야 함 |
| 제안자와 지정 승인자 분리, 열람 후 내용 결합 보안키 서명 | 임의 보존·조기 해제 위험을 낮춤 | 긴급 처리에도 두 사람과 최근 인증이 필요함 |
| 파기 실행·대상 ID 증적 보존 | 삭제한 암호문을 되살리지 않고 실행 범위를 확인 가능 | 메타데이터 자체의 저장·접근 통제가 필요함 |

보존 정지를 한 사람의 토글 값으로 갱신하거나 만료 데이터를 전부 포괄 삭제하는 대안은 선택하지 않았다. 정지 자체도 불필요한 개인정보의 무기한 보유가 될 수 있으므로 근거 참조와 독립 승인을 요구한다.

## 구현과 관련 코드

[CustomerMailOutbox.java](../../src/main/java/dustin/cex/domains/mail/CustomerMailOutbox.java)의 현재 파기 후보 조건 일부다.

```java
AND NOT EXISTS(SELECT 1 FROM data_retention_holds h
  LEFT JOIN data_retention_hold_releases r ON r.hold_id=h.id
  WHERE r.hold_id IS NULL
    AND ((h.scope_type='MAIL_JOB' AND h.scope_id=j.id::text)
      OR (h.scope_type='USER' AND h.scope_id=j.user_id::text)))
```

메일 작업 하나 또는 고객 전체 범위의 활성 정지가 있으면 후보에서 제외한다. [retention/schema.sql](../../src/main/resources/retention/schema.sql)의 DB 트리거도 같은 조건을 다시 확인하므로 서비스 쿼리를 우회한 직접 삭제가 실패한다. 파기 전에 `data_disposal_runs`와 `data_disposal_items`를 같은 트랜잭션에 쓰고 감사 실패 시 삭제와 증적을 함께 롤백한다.

[DataRetentionService.java](../../src/main/java/dustin/cex/domains/security/DataRetentionService.java)는 `HOLD`와 `RELEASE` 요청 원본을 JSON·SHA-256으로 고정한다. 제안자 `OPERATOR`와 지정 `APPROVER`가 서로 다른 인물인지, 현재 역할·버전·세션이 같은지, 승인자가 최근 5분 안에 정확한 원본을 열람했는지 확인하고 WebAuthn 서명을 해당 원본에 결합한다.

## 이해에 필요한 문법과 원리

```java
for(UUID id:due){
    jdbc.update("INSERT INTO data_disposal_items(run_id,dataset,item_id) VALUES(?,'CUSTOMER_MAIL_PAYLOAD',?)",run,id);
    jdbc.update("DELETE FROM customer_mail_payloads WHERE job_id=?",id);
}
```

`for(UUID id:due)`는 TypeScript의 `for (const id of due)`와 같은 순회다. 메서드 전체가 `TransactionTemplate` 안에서 실행되므로 증적 삽입, 삭제, 감사 중 하나라도 실패하면 모두 롤백된다. 최대 100개로 제한해 한 트랜잭션의 잠금과 로그 증가를 제한한다. `SKIP LOCKED`는 다른 파기 워커가 이미 고른 행을 기다리지 않고 건너뛴다.

PostgreSQL 트리거는 애플리케이션 역할 검사의 대체가 아니다. 서비스는 누가 왜 정지·해제했는지 인증하고, 트리거는 승인된 활성 정지가 있는 자료가 다른 SQL 경로로 지워지는 마지막 방어선이다.

## 작성한 테스트

| 파일·함수 | 검증 시나리오 | 기대 결과 |
|---|---|---|
| `DataRetentionIntegrationTest.independentlyApprovedHoldBlocksDisposalUntilIndependentlyApprovedRelease` | 열람 전 승인, 독립 승인 정지, 직접 SQL 삭제, 독립 승인 해제, 파기 | 열람 전 거절, 활성 정지 중 보존, 해제 뒤 암호문만 삭제하고 실행·대상 증적 보존 |
| `rejectedOrTamperedControlCannotAlterRetentionState` | 반려와 요청·결정 변조 | 정지 미생성, UPDATE/DELETE 실패 |
| `decisionAuditFailureRollsBackHold` | 최종 감사 실패 | 결정·정지 모두 롤백 |
| `httpBoundaryAllowsOnlyTheAssignedRoles` | 운영자 제안, 고객 우회, 승인자 목록 | 운영자만 제안, 고객 `403`, 승인자 조회 허용 |
| `CustomerMailOutboxIntegrationTest`의 파기 3개 | 활성/최근/종료 기한, 감사 실패 | 조기 파기 차단, 적격 파기, 감사 실패 시 암호문 복원 |

## 실행 명령과 결과

```bash
python3 scripts/verify_data_retention.py
```

2026-10-27 소유 PostgreSQL 16의 그룹별 격리 DB에서 보존 통제 4개와 메일 파기 3개, 총 7개가 통과했다. 실패·오류·제외는 0개이며 고객 플랫폼·운영자 초기화 JAR도 각 그룹에서 빌드했다. 봇·Kafka 소비·엔진 전송·메일 워커는 비활성이고 소유 컨테이너를 제거했다. 실행 증적은 `.local/data-retention/076a5415d935/`와 `.local/data-retention.json`에 있다.

현재 실제 자동 파기는 메일 암호문 한 종류다. 법률·계약별 전 데이터 카탈로그와 기산점, 백업 매체 파기, 외부 KYC 공급사 삭제, 운영자 조직 절차는 코드만으로 완료됐다고 주장하지 않는다. 새 자료를 추가할 때 금융·감사 원본을 범용 삭제 대상으로 넣지 않고 별도 법적 근거와 시험을 추가해야 한다.

## 관련 커밋과 참고 자료

- 구현·시험: 이 문서 직전 기능 커밋
- [개인정보 보호법 제21조](https://law.go.kr/LSW/lsLinkCommonInfo.do?lsJoLnkSeq=1029334977)
- [특정금융정보법 개정 이유](https://law.go.kr/LSW/lsRvsRsnListP.do?chrClsCd=010102&lsId=009244)
- [ISMS-P 제도 안내](https://isms.kisa.or.kr/sysm/intro/selectSysmCertDetail.do)
