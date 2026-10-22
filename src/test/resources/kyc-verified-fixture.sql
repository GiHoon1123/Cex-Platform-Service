-- 이 실행기가 소유한 주문 프로세스 회귀 DB의 두 고객만 신원 확인 완료로 준비한다.
INSERT INTO kyc_attempts(id,user_id,request_id,application_id,environment,workflow_id,workflow_version,creation_state)
 SELECT gen_random_uuid(),u.id,gen_random_uuid(),gen_random_uuid(),'live',gen_random_uuid(),1,'READY' FROM users u
 WHERE u.id IN (101,102) AND NOT EXISTS(SELECT 1 FROM kyc_profiles p WHERE p.user_id=u.id);
INSERT INTO kyc_profiles(user_id,active_attempt,identity_status,provider_status,valid_until)
 SELECT a.user_id,a.id,'VERIFIED','Approved',clock_timestamp()+interval '1 day' FROM kyc_attempts a
 WHERE a.user_id IN (101,102) ON CONFLICT(user_id) DO NOTHING;
