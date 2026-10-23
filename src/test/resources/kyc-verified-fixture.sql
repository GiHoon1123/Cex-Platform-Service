-- 이 실행기가 소유한 주문 프로세스 회귀 DB의 두 고객만 신원 확인 완료로 준비한다.
INSERT INTO kyc_attempts(id,user_id,request_id,application_id,environment,workflow_id,workflow_version,creation_state)
 SELECT gen_random_uuid(),u.id,gen_random_uuid(),gen_random_uuid(),'live',gen_random_uuid(),1,'READY' FROM users u
 WHERE u.id IN (101,102) AND NOT EXISTS(SELECT 1 FROM kyc_profiles p WHERE p.user_id=u.id);
INSERT INTO kyc_profiles(user_id,active_attempt,identity_status,provider_status,valid_until)
 SELECT a.user_id,a.id,'VERIFIED','Approved',clock_timestamp()+interval '1 day' FROM kyc_attempts a
 WHERE a.user_id IN (101,102) ON CONFLICT(user_id) DO NOTHING;

INSERT INTO kyc_reconciliations(attempt_id,fresh_at)
 SELECT p.active_attempt,clock_timestamp() FROM kyc_profiles p WHERE p.user_id IN (101,102)
 ON CONFLICT(attempt_id) DO NOTHING;

-- 이 실행기가 소유한 두 고객의 금융 처리만 위한 합성 사전 조건이다. 실제 CDD 증적이 아니다.
UPDATE kyc_profiles SET status_digest='financial-fixture' WHERE user_id IN (101,102);
INSERT INTO cdd_declarations(id,user_id,request_id,ciphertext,content_mac,key_id)
 SELECT gen_random_uuid(),u.id,gen_random_uuid(),decode('00','hex'),'financial-fixture','ff55c97976a840b4ced964ed49e3794594ba3f675238b5fd25d282b60f70a194'
 FROM users u WHERE u.id IN (101,102) AND NOT EXISTS(SELECT 1 FROM cdd_declarations d WHERE d.user_id=u.id);
INSERT INTO cdd_attempt_bindings(attempt_id,declaration_id)
 SELECT k.active_attempt,d.id FROM kyc_profiles k JOIN cdd_declarations d ON d.user_id=k.user_id WHERE k.user_id IN (101,102) ON CONFLICT(attempt_id) DO NOTHING;
INSERT INTO kyc_events(event_id,attempt_id,payload_hash,provider_time,provider_status,disposition)
 SELECT gen_random_uuid(),k.active_attempt,'financial-fixture',0,'Approved','APPLIED' FROM kyc_profiles k WHERE k.user_id IN (101,102) AND NOT EXISTS(SELECT 1 FROM kyc_events e WHERE e.attempt_id=k.active_attempt AND e.payload_hash='financial-fixture');
INSERT INTO cdd_assessments(id,user_id,declaration_id,attempt_id,event_id,status_digest,key_id,policy_version,outcome,reasons)
 SELECT gen_random_uuid(),d.user_id,d.id,k.active_attempt,e.event_id,'financial-fixture',d.key_id,'CDD_DOMESTIC_INDIVIDUAL_V1','STANDARD','FINANCIAL_FIXTURE'
 FROM cdd_declarations d JOIN kyc_profiles k ON k.user_id=d.user_id JOIN kyc_events e ON e.attempt_id=k.active_attempt WHERE d.user_id IN (101,102) AND NOT EXISTS(SELECT 1 FROM cdd_assessments a WHERE a.event_id=e.event_id);
INSERT INTO cdd_profiles(user_id,declaration_id,assessment_id)
 SELECT a.user_id,a.declaration_id,a.id FROM cdd_assessments a WHERE a.user_id IN (101,102) ON CONFLICT(user_id) DO NOTHING;
