-- 실행 소유 회귀 디비에만 적용하는 최초 자금 자료다. 제품의 기존 금융 이력을 초기화하는 절차가 아니다.
-- 오래된 접수 회귀는 다른 주문의 예약 25를 가정하므로 해당 테스트 시작값까지 명시적으로 기록한다.
TRUNCATE ledger_entries,ledger_transactions;
BEGIN;
INSERT INTO ledger_transactions(operation_key,kind) VALUES('opening','OPENING');
INSERT INTO ledger_entries(operation_key,owner_id,bucket,asset,delta)
SELECT 'opening',user_id,'AVAILABLE',mint_address,available FROM user_balances WHERE available<>0
UNION ALL SELECT 'opening',user_id,'LOCKED',mint_address,locked FROM user_balances WHERE locked<>0
UNION ALL SELECT 'opening',0,'OPENING',mint_address,-sum(available+locked) FROM user_balances GROUP BY mint_address HAVING sum(available+locked)<>0;
COMMIT;
