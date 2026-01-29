# 거래 정산 프로세스

## 개요

- **실행 시점**: 매일 자정(00:00:00)에 **전일** 데이터를 정산한다.
- **정산 기준 시간**: KST(Asia/Seoul) 기준 해당일 00:00:00 ~ 23:59:59.999999.
- **일별 정산**은 4단계로 이루어지며, 각 단계 완료 시 `trade_settlement_runs`의 `completed_step`이 갱신된다.

---

## 일별 정산 4단계

### 1단계: 스냅샷 생성

**하는 일**

- 해당 정산일 기준으로 **잔고 스냅샷**과 **포지션 스냅샷**을 만든다.
- 이미 그 날짜 스냅샷이 있으면 스킵(멱등).

**데이터가 저장되는 테이블**

| 테이블                     | 내용                                       |
| -------------------------- | ------------------------------------------ |
| `trade_balance_snapshots`  | 사용자별 잔고(available, locked 등) 스냅샷 |
| `trade_position_snapshots` | 사용자별 포지션 스냅샷                     |

**실패 시**

- `trade_settlement_failures`에 1단계 실패 1건 기록(step=1, userId=null).
- 1단계 실패만 기록하고 **다음 단계는 그대로 진행**한다(2·3·4단계 계속 실행).
- `trade_settlement_runs.completed_step`은 1로 올라간 뒤 2단계로 넘어간다.

---

### 2단계: 정산 집계

**하는 일**

- 정산일 하루 동안의 **거래·수수료·사용자 수**를 집계한다.
- 이미 해당일 일별 정산이 있으면 **기존 레코드 반환**(삭제하지 않음). → 실패 후 재실행 시 2단계는 스킵되고 3·4단계만 이어서 실행 가능.

**데이터가 저장되는 테이블**

| 테이블                        | 내용                                                                                 |
| ----------------------------- | ------------------------------------------------------------------------------------ |
| `trade_settlements`           | 1행. 총 거래 건수, 총 거래량, 총 수수료 수익, 거래한 사용자 수, validation_status 등 |
| `trade_settlement_items`      | 거래 1건당 1행. trade_id, 거래량, 매수자/매도자 수수료, 총 수수료, 정산일            |
| `trade_settlement_audit_logs` | 정산 생성 액션(CREATE) 로그                                                          |

**실패 시**

- `trade_settlement_failures`에 2단계 실패 1건 기록(step=2, userId=null).
- **예외를 다시 던져** 전체 일별 정산을 중단한다.
- `trade_settlement_runs`는 `completed_step=1`까지만 갱신된 상태에서, catch 시 `failRun`으로 상태를 FAILED로 바꾸고 `failed_user_ids_json`은 null(또는 빈 배열)로 둔다.
- 재시도 시 1단계는 스킵(스냅샷 이미 있음), 2단계부터 다시 시도한다.

---

### 3단계: 사용자별 정산 집계

**하는 일**

- 2단계에서 구한 “해당일에 거래한 사용자 목록”을 기준으로, **사용자마다** 그날 거래 건수·거래량·낸 수수료를 집계한다.
- 사용자별로 이미 해당일 일별 정산이 있으면 **그 사용자는 스킵**(멱등).

**데이터가 저장되는 테이블**

| 테이블                   | 내용                                                                      |
| ------------------------ | ------------------------------------------------------------------------- |
| `trade_user_settlements` | 사용자 1명당 1행. 사용자 ID, 정산일, 거래 건수, 거래량, 낸 수수료 합계 등 |

**실패 시**

- **한 사용자**에서만 예외가 나면: `trade_settlement_failures`에 3단계 실패 1건 기록(step=3, userId=해당 사용자 ID), `failedUserIds` 리스트에 그 사용자 추가, **나머지 사용자는 계속 처리**.
- **전체 사용자 처리 끝난 뒤** “처리한 사용자 수 < 대상 사용자 수”이면: `RuntimeException`을 던져 일별 정산 전체를 실패 처리한다.
- 이때 `trade_settlement_runs`는 `failRun(runId, 메시지, failedUserIds)`로 갱신된다(상태=FAILED, `failed_user_ids_json`에 실패한 사용자 ID 목록).
- 재시도 시 1·2단계는 스킵(또는 기존 반환), 3단계는 “이미 레코드 있는 사용자”는 스킵되고 **실패했던 사용자만 다시 시도**할 수 있다.

---

### 4단계: 복식부기 검증

**하는 일**

- 해당일 `trade_settlements` 1건에 대해 **검증**만 수행한다.
- 이미 `validation_status = VALIDATED`이면 **4단계 전체 스킵**.

**검증 내용**

- 거래: 매수자 지출 = 매도자 수입 등 복식부기 일치.
- 수수료: 매수자 수수료 + 매도자 수수료 = 거래소 수익.
- 교차 검증: `trade_settlement_items`의 total_fee 합계 = `trade_settlements.total_fee_revenue`.

**데이터가 갱신되는 테이블**

| 테이블              | 내용                                                                                                |
| ------------------- | --------------------------------------------------------------------------------------------------- |
| `trade_settlements` | `validation_status`를 VALIDATING → VALIDATED 또는 FAILED로, 실패 시 `validation_error_message` 저장 |

**실패 시**

- `trade_settlements.validation_status`를 FAILED로, `validation_error_message`에 메시지 저장.
- `trade_settlement_failures`에 4단계 실패 1건 기록(step=4, userId=null).
- **예외를 다시 던져** 일별 정산을 실패로 끝낸다.
- `trade_settlement_runs`는 `failRun`으로 FAILED 기록(일반적으로 failedUserIds는 null).
- 재시도 시 1·2·3단계는 스킵(이미 완료), 4단계만 다시 실행된다.

---

## 실패 시 공통 동작

- **재시도**: `@Retryable`로 최대 3회, 지수 백오프(2초 → 4초 → 8초). 전부 실패하면 `@Recover`로 복구 로직 실행(현재는 로그/알림용).
- **런 기록**: 매 일별 정산 시작 시 `trade_settlement_runs`에 1행 생성(status=RUNNING, completed_step=0). 성공 시 `completeRun`으로 SUCCESS, 실패 시 `failRun`으로 FAILED와 에러 메시지·(3단계인 경우) `failed_user_ids_json` 저장.
- **실패 상세 조회**: `GET /api/settlement/trade/failures?date=yyyy-MM-dd`로 해당 날짜의 `trade_settlement_failures` 목록 조회 가능.

---

## 테이블 요약

| 테이블                        | 용도                                                                                                   |
| ----------------------------- | ------------------------------------------------------------------------------------------------------ |
| `trade_balance_snapshots`     | 1단계: 잔고 스냅샷                                                                                     |
| `trade_position_snapshots`    | 1단계: 포지션 스냅샷                                                                                   |
| `trade_settlements`           | 2단계: 일별 정산 1건, 4단계: validation_status 갱신                                                    |
| `trade_settlement_items`      | 2단계: 거래 단위 정산(재현성·감사)                                                                     |
| `trade_settlement_audit_logs` | 2단계 등: 정산 액션 로그                                                                               |
| `trade_user_settlements`      | 3단계: 사용자별 일별 정산                                                                              |
| `trade_settlement_runs`       | 매 실행마다 1행: 진행 단계(completed_step), 성공/실패(status), 3단계 실패 사용자(failed_user_ids_json) |
| `trade_settlement_failures`   | 단계별 실패 1건당 1행: 정산일, step, userId(3단계만), error_message                                    |

---

## 수동 실행

- `POST /api/settlement/trade/manual/daily?date=yyyy-MM-dd&forceRecreate=false`
  - `forceRecreate=false`: 이미 있으면 2단계는 기존 반환, 3·4단계만 이어서 실행(실패 지점부터 재개).
  - `forceRecreate=true`: 해당일 일별 정산을 삭제한 뒤 1단계부터 전부 다시 실행.
