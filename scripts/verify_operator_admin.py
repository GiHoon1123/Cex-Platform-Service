"""실제 관리 JAR을 DDL 권한 없는 DB 계정으로 실행하고 웹·업무 워커·비밀 노출을 검사한다."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import unittest
from uuid import uuid4
import verify_order_idempotency as fixtures

ROOT=Path(__file__).resolve().parent.parent
# 기존 프로세스 검증의 실행 소유 PostgreSQL·서버 준비를 재사용하되 증적 경로는 분리한다.
fixtures.ARTIFACTS=ROOT.parent/".local/operator-admin-process"
fixtures.ARTIFACTS.mkdir(parents=True,exist_ok=True)


class OperatorAdminProcessTest(fixtures.OrderIdempotencyProcessTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.sql("""CREATE ROLE isolated_operator_admin LOGIN PASSWORD 'isolated-admin-password';
            GRANT USAGE ON SCHEMA public TO isolated_operator_admin;
            GRANT SELECT,UPDATE ON users,refresh_tokens TO isolated_operator_admin;
            GRANT SELECT ON orders,ledger_entries,user_balances TO isolated_operator_admin;
            GRANT SELECT,INSERT ON operator_enrollment,security_audit TO isolated_operator_admin;
        """)

    def setUp(self):
        super().setUp()
        self.stop_platform()
        self.secret=secrets.token_urlsafe(32)
        self.person=str(uuid4())

    def invoke(self,target,label):
        env=os.environ.copy()
        # 비밀을 명령 인수로 전달하지 않는다. 프로세스 환경에도 운영 비밀은 사용하지 않는다.
        env["SPRING_APPLICATION_JSON"]=json.dumps({"spring":{"datasource":{
            "url":"jdbc:postgresql://"+self.database_address+"/"+self.database,
            "username":"isolated_operator_admin","password":"isolated-admin-password"},"jpa":{"show-sql":False}},
            "operator":{"bootstrap":{"user-id":target,"person-id":self.person,"role":"OPERATOR","enrollment-token":self.secret}}})
        java=Path(env.get("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"))/"bin/java"
        result=subprocess.run([str(java),"-jar",str(ROOT/"build/libs/cex-0.0.1-SNAPSHOT-operator-admin.jar"),
            "--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.web-application-type=servlet"],
            cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=40)
        (fixtures.ARTIFACTS/(label+".log")).write_text(result.stdout)
        self.assertNotIn(self.secret,result.stdout)
        for text in ["Tomcat started","BotTradingService","TradeSettlementScheduler","KafkaOrderEventConsumer"]:
            self.assertNotIn(text,result.stdout)
        self.assertNotIn("Error executing DDL",result.stdout)
        return result

    def test_dedicated_jar_grants_without_ddl_or_business_workers(self):
        self.sql("INSERT INTO users(id,email,password_hash,role,enabled,trading_restricted,version,created_at,updated_at) VALUES(103,'operator@test.invalid','unused','CUSTOMER',true,false,0,now(),now())")
        result=self.invoke(103,"grant")
        self.assertEqual(result.returncode,0,result.stdout[-2500:])
        self.assertEqual(self.sql("SELECT role||':'||operator_person_id::text||':'||(operator_access_expires_at>now())::text FROM users WHERE id=103"),"OPERATOR:"+self.person+":true")
        self.assertEqual(self.sql("SELECT token_hash FROM operator_enrollment WHERE user_id=103"),hashlib.sha256(self.secret.encode()).hexdigest())
        self.assertEqual(self.sql("SELECT count(*) FROM security_audit WHERE action='OPERATOR_BOOTSTRAP' AND target='user:103'"),"1")
        self.assertEqual(self.sql("SELECT has_schema_privilege('isolated_operator_admin','public','CREATE')"),"f")
        self.assertEqual(self.sql("SELECT count(*) FROM security_audit a JOIN users u ON a.target='user:'||u.id WHERE a.action='OPERATOR_BOOTSTRAP' AND u.id=103 AND split_part(a.detail,':until=',2)::timestamptz=u.operator_access_expires_at"),"1")
        self.results.append({"test":"dedicated_grant_without_ddl_or_workers","passed":True})

    def test_financial_customer_and_repeated_grant_are_rejected(self):
        self.assertNotEqual(self.invoke(101,"financial-customer-rejected").returncode,0)
        self.assertEqual(self.sql("SELECT role FROM users WHERE id=101"),"CUSTOMER")
        self.sql("INSERT INTO users(id,email,password_hash,role,enabled,trading_restricted,version,created_at,updated_at) VALUES(104,'second-operator@test.invalid','unused','CUSTOMER',true,false,0,now(),now())")
        self.assertEqual(self.invoke(104,"first-grant").returncode,0)
        self.assertNotEqual(self.invoke(104,"repeated-grant-rejected").returncode,0)
        self.assertEqual(self.sql("SELECT count(*) FROM security_audit WHERE action='OPERATOR_BOOTSTRAP' AND target='user:104'"),"1")
        self.results.append({"test":"reject_financial_customer_and_duplicate_grant","passed":True})


if __name__=="__main__":
    # 부모 클래스의 주문 시나리오는 별도 실행기에 남기고 관리 명령 두 시나리오만 수행한다.
    suite=unittest.TestSuite(OperatorAdminProcessTest(name) for name in [
        "test_dedicated_jar_grants_without_ddl_or_business_workers","test_financial_customer_and_repeated_grant_are_rejected"])
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
