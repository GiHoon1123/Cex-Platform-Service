"""새 실행 JAR·임시 PostgreSQL DB·HTTP 엔진 대역으로 접수 멱등성을 검증한다.

사전 조건: Docker, 빌드된 JAR, Java 21, Python 3.
기존 데이터베이스·실제 엔진·Kafka에는 접근하지 않는다. 실행 소유 컨테이너만 정리한다.
실제 매칭·정산 검증은 아니며, 저장·HTTP 전송 경계와 재시작·동시 요청을 검증한다.
"""
import atexit
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4


PROJECT = Path(__file__).resolve().parent.parent
WORKSPACE = PROJECT.parent
ARTIFACTS = WORKSPACE / ".local"


class EngineStub(BaseHTTPRequestHandler):
    mode = 202
    calls = []
    lock = threading.Lock()

    def do_POST(self):
        if self.headers.get("x-engine-service-token") != self.service_token:
            self.send_error(401)
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        with self.lock:
            self.calls.append(body)
            mode = self.mode
        if mode == "timeout":
            # 요청은 수신했다. 고객 응답을 잃는 상황을 재현하며 주문 효과는 만들지 않는다.
            time.sleep(1)
            self.close_connection = True
            return
        self.send_response(mode)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"order_id": str(body["order_id"]), "user_id": str(body["user_id"]),
                                      "receipt_status": "accepted", "accepted_at": "2026-10-21T00:00:00Z"}).encode())

    def log_message(self, *_):
        pass


class OrderIdempotencyProcessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ARTIFACTS.mkdir(exist_ok=True)
        cls.docker = shutil.which("docker") or "/usr/local/bin/docker"
        cls.database = "cex_idempotency_" + uuid4().hex[:12]
        cls.secret = secrets.token_hex(32)
        cls.service_token = secrets.token_hex(32)
        EngineStub.service_token = cls.service_token
        cls.process = None
        cls.engine = None
        cls.created_database = False
        cls.database_removed = False
        cls.platform_runs = 0
        cls.results = []
        # 설정 중 예외가 나도 이 클래스가 만든 리소스만 정리한다.
        cls.addClassCleanup(cls.cleanup)
        atexit.register(cls.cleanup)
        # 공유 로컬 서버 대신 이 실행이 소유한 PostgreSQL 서버를 만든다.
        cls.container = "cex-idempotency-" + uuid4().hex[:12]
        subprocess.run([cls.docker,"run","--detach","--rm","--name",cls.container,"--publish","127.0.0.1::5432",
                        "--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234",
                        "-e","POSTGRES_DB="+cls.database,"postgres:16"],check=True,stdout=subprocess.DEVNULL)
        deadline=time.monotonic()+45
        while subprocess.run([cls.docker,"exec",cls.container,"pg_isready","-h","127.0.0.1","-U","root","-d",cls.database],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
            if time.monotonic()>deadline: raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
            time.sleep(0.2)
        cls.database_address=subprocess.check_output([cls.docker,"port",cls.container,"5432/tcp"],text=True).strip()
        cls.sql("CREATE TABLE fee_configs(id BIGSERIAL PRIMARY KEY, base_mint VARCHAR(255), quote_mint VARCHAR(255), fee_rate NUMERIC(10,6) NOT NULL, fee_type VARCHAR(20) NOT NULL, is_active BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL); INSERT INTO fee_configs(fee_rate,fee_type,is_active,created_at,updated_at) VALUES(0.001,'both',true,now(),now());")
        cls.created_database = True
        cls.engine = ThreadingHTTPServer(("127.0.0.1", 0), EngineStub)
        threading.Thread(target=cls.engine.serve_forever, daemon=True).start()
        cls.start_platform()

    @classmethod
    def sql(cls, statement, database=None):
        # DB 이름은 내부 UUID로만 생성한다. SQL은 테스트 상수·검증한 정수 ID만 사용한다.
        return subprocess.check_output([
            cls.docker, "exec", cls.container, "psql", "-U", "root", "-d", database or cls.database,
            "-v", "ON_ERROR_STOP=1", "-At", "-c", statement
        ], text=True).strip()

    @classmethod
    def start_platform(cls):
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            cls.port = reserve.getsockname()[1]
        cls.platform_runs += 1
        java_home = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
        log_path = ARTIFACTS / f"order-idempotency-platform-{cls.platform_runs}.log"
        command = [str(Path(java_home) / "bin/java"), "-jar", str(PROJECT / "build/libs/cex-0.0.1-SNAPSHOT.jar"),
                   f"--spring.datasource.url=jdbc:postgresql://{cls.database_address}/{cls.database}",
                   "--spring.kafka.bootstrap-servers=127.0.0.1:1", "--spring.kafka.listener.auto-startup=false",
                   "--spring.kafka.producer.properties.max.block.ms=500",
                   f"--engine.http.url=http://127.0.0.1:{cls.engine.server_port}", "--engine.http.timeout=300",
                   "--bot.bot-count=0", "--order.dispatch.enabled=false", "--bot.binance-ws-url=ws://127.0.0.1:1",
                   "--server.address=127.0.0.1", f"--server.port={cls.port}", f"--jwt.secret={cls.secret}", f"--engine.service.token={cls.service_token}",
                   "--spring.jpa.hibernate.ddl-auto=" + ("update" if cls.platform_runs == 1 else "validate")]
        with log_path.open("w") as log:
            cls.process = subprocess.Popen(command, cwd=PROJECT, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            if cls.process.poll() is not None:
                raise RuntimeError(f"임시 플랫폼 시작 실패: {log_path}")
            try:
                with urlopen(f"http://127.0.0.1:{cls.port}/swagger-ui/index.html", timeout=1) as response:
                    if response.status == 200 and "Started CexApplication" in log_path.read_text():
                        return
            except OSError:
                pass
            time.sleep(0.2)
        raise RuntimeError(f"임시 플랫폼 시작 시간 초과: {log_path}")

    @classmethod
    def stop_platform(cls):
        if cls.process is not None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait()
            cls.process = None

    @classmethod
    def cleanup(cls):
        cls.stop_platform()
        if cls.engine is not None:
            cls.engine.shutdown()
            cls.engine.server_close()
            cls.engine = None
        if cls.created_database:
            # 운영 DB 이름을 받을 수 없으며 생성한 UUID DB만 삭제한다.
            assert cls.database.startswith("cex_idempotency_")
            cls.sql(f"DROP DATABASE {cls.database} WITH (FORCE)", database="postgres")
            cls.created_database = False
            cls.database_removed = True
        if getattr(cls,"container",None):
            subprocess.run([cls.docker,"rm","--force",cls.container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        (ARTIFACTS / "order-idempotency-process.json").write_text(json.dumps({
            "database": cls.database, "temporary_database_removed": cls.database_removed,
            "platform_runs": cls.platform_runs, "bots_disabled": True, "checks": cls.results
        }, ensure_ascii=False, indent=2))

    def setUp(self):
        EngineStub.mode = 202
        with EngineStub.lock:
            EngineStub.calls.clear()
        # 이 스크립트가 소유한 임시 DB에만 테스트 데이터를 만든다.
        self.sql("DELETE FROM order_dispatches; DELETE FROM order_submissions; DELETE FROM orders; DELETE FROM user_balances")
        self.sql("INSERT INTO users(id,email,password_hash,role,enabled,trading_restricted,version,created_at,updated_at) VALUES(101,'one@test.invalid','unused','CUSTOMER',true,false,0,now(),now()),(102,'two@test.invalid','unused','CUSTOMER',true,false,0,now(),now()) ON CONFLICT(id) DO NOTHING")
        self.sql("""INSERT INTO user_balances (user_id,mint_address,available,locked,created_at,updated_at)
                    VALUES (101,'USDT',1000,25,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                           (102,'USDT',1000,25,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""")
        self.sql((PROJECT/"src/test/resources/ledger-opening-fixture.sql").read_text())
        self.sql((PROJECT/"src/test/resources/kyc-verified-fixture.sql").read_text())

    def token(self, user):
        sid = str(uuid4())
        self.sql(f"INSERT INTO refresh_tokens(user_id,session_id,token_hash,expires_at,revoked,created_at,updated_at) VALUES({int(user)},'{sid}','{sid}',now()+interval '1 day',false,now(),now())")
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()
        unsigned = encode({"alg": "HS256", "typ": "JWT"}) + "." + encode({
            "iss":"cex-platform", "aud":"cex-api", "type":"access", "sid":sid, "sub": str(user), "iat": int(time.time()), "exp": int(time.time()) + 600})
        signature = base64.urlsafe_b64encode(hmac.new(self.secret.encode(), unsigned.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
        return unsigned + "." + signature

    def post(self, key, payload=None, user=101):
        headers = {"Authorization": "Bearer " + self.token(user), "Content-Type": "application/json"}
        if key is not None:
            headers["Idempotency-Key"] = str(key)
        request = Request(f"http://127.0.0.1:{self.port}/api/cex/orders", headers=headers,
                          data=json.dumps(payload or self.limit()).encode())
        try:
            response = urlopen(request, timeout=8)
        except HTTPError as exception:
            response = exception
        with response:
            return response.status, dict(response.headers), json.loads(response.read())

    @staticmethod
    def limit():
        return {"orderType": "buy", "orderSide": "limit", "baseMint": "SOL", "quoteMint": "USDT",
                "price": "100", "amount": "2"}

    def assert_single(self, calls=1):
        self.assertEqual(self.sql("SELECT count(*) FROM orders"), "1")
        self.assertEqual(self.sql("SELECT count(*) FROM order_submissions"), "1")
        self.assertEqual(self.sql("SELECT available::text||'|'||locked::text FROM user_balances WHERE user_id=101"),
                         "800.000000000|225.000000000")
        self.assertEqual(len(EngineStub.calls), calls)

    def record(self, **evidence):
        self.results.append({"test": self._testMethodName, **evidence})

    def test_lost_response_then_same_key(self):
        EngineStub.mode = "timeout"
        key = uuid4()
        first, _, created = self.post(key)
        repeated, location, existing = self.post(key)
        self.assertEqual((first, repeated), (202, 200))
        self.assertEqual(created["orderId"], existing["order"]["id"])
        self.assertEqual(location["Location"], "/api/cex/orders/" + created["orderId"])
        self.assert_single()
        self.record(statuses=[first, repeated], engine_posts=1)

    def test_same_key_different_content(self):
        key = uuid4()
        self.assertEqual(self.post(key)[0], 201)
        changed = self.limit()
        changed["amount"] = "3"
        status, _, response = self.post(key, changed)
        self.assertEqual(status, 409)
        self.assertEqual(response["code"], "ORDER_REQUEST_CONFLICT")
        self.assert_single()
        self.record(conflict=status, engine_posts=1)

    def test_concurrent_same_key(self):
        key = uuid4()
        gate = threading.Barrier(12)
        def submit(_):
            gate.wait(timeout=5)
            return self.post(key)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
            responses = list(executor.map(submit, range(12)))
        statuses = [r[0] for r in responses]
        self.assertEqual(statuses.count(201), 1)
        self.assertEqual(statuses.count(200), 11)
        self.assertEqual(len({r[2]["order"]["id"] for r in responses}), 1)
        self.assert_single()
        self.record(new=1, existing=11, engine_posts=1)

    def test_concurrent_same_key_different_content(self):
        key = uuid4()
        gate = threading.Barrier(12)
        def submit(index):
            body = self.limit()
            body["amount"] = str(1 + index % 2)
            gate.wait(timeout=5)
            return self.post(key, body)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
            responses = list(executor.map(submit, range(12)))
        statuses = [r[0] for r in responses]
        self.assertEqual((statuses.count(201), statuses.count(200), statuses.count(409)), (1, 5, 6))
        self.assertEqual(self.sql("SELECT count(*) FROM orders"), "1")
        self.assertEqual(self.sql("SELECT count(*) FROM order_submissions"), "1")
        self.assertEqual(len(EngineStub.calls), 1)
        reserved = int(EngineStub.calls[0]["amount"]) * 100
        self.assertEqual(self.sql("SELECT available::text||'|'||locked::text FROM user_balances WHERE user_id=101"),
                         f"{1000-reserved}.000000000|{25+reserved}.000000000")
        self.record(new=1, existing=5, conflicts=6, engine_posts=1)

    def test_key_is_scoped_to_authenticated_user(self):
        key = uuid4()
        first = self.post(key, user=101)
        second = self.post(key, user=102)
        self.assertEqual((first[0], second[0]), (201, 201))
        self.assertNotEqual(first[2]["order"]["id"], second[2]["order"]["id"])
        self.assertEqual(len(EngineStub.calls), 2)
        self.record(users=2, engine_posts=2)

    def test_invalid_header_and_command_have_no_financial_effect(self):
        invalid = self.limit()
        invalid["quoteAmount"] = "1"
        statuses = [self.post(None)[0], self.post("not-a-uuid")[0], self.post(uuid4(), invalid)[0]]
        self.assertEqual(statuses, [400, 400, 400])
        self.assertEqual(self.sql("SELECT count(*) FROM orders"), "0")
        self.assertEqual(self.sql("SELECT count(*) FROM order_submissions"), "0")
        self.assertEqual(len(EngineStub.calls), 0)
        self.record(statuses=statuses, engine_posts=0)

    def test_unrepresentable_commands_are_rejected_without_financial_effect(self):
        # 실제 Java HTTP 경계에서 BigDecimal 검증이 예약·저장·전송보다 먼저 끝나는지 본다.
        commands = [dict(self.limit(), price="1", amount="999999999999999999999.123456789"),
                    dict(self.limit(), price="0.000000001", amount="0.1", orderType="sell")]
        for command in commands:
            self.assertEqual(self.post(uuid4(), command)[0], 400)
        self.assertEqual(self.sql("SELECT count(*) FROM orders"), "0")
        self.assertEqual(self.sql("SELECT count(*) FROM order_submissions"), "0")
        self.assertEqual(len(EngineStub.calls), 0)
        self.assertEqual(self.sql("SELECT available::text||'|'||locked::text FROM user_balances WHERE user_id=101"),
                         "1000.000000000|25.000000000")
        self.record(rejections=2, engine_posts=0)

    def test_invalid_balance_and_unrepresentable_split_roll_back_everything(self):
        for available, locked, command in [
                ("1000", "-25", self.limit()),
                ("100000000000000000000", "25", dict(self.limit(), price="0.000000001", amount="1"))]:
            self.sql(f"UPDATE user_balances SET available={available},locked={locked} WHERE user_id=101")
            before = self.sql("SELECT available::text||'|'||locked::text FROM user_balances WHERE user_id=101")
            self.assertEqual(self.post(uuid4(), command)[0], 400)
            self.assertEqual(self.sql("SELECT available::text||'|'||locked::text FROM user_balances WHERE user_id=101"), before)
            self.assertEqual(self.sql("SELECT count(*) FROM orders"), "0")
            self.assertEqual(self.sql("SELECT count(*) FROM order_submissions"), "0")
            self.assertEqual(len(EngineStub.calls), 0)
        self.record(rollback_cases=2, engine_posts=0)

    def test_market_original_budget_survives_order_changes(self):
        key = uuid4()
        market = {"orderType": "buy", "orderSide": "market", "baseMint": "SOL", "quoteAmount": "123.250000001"}
        status, _, result = self.post(key, market)
        self.assertEqual(status, 201)
        order_id = int(result["order"]["id"])
        # 체결이 원본 요청을 바꾸지 않는지 보려는 DB 변경 주입이다. 정산 검증은 아니다.
        self.sql(f"UPDATE orders SET status='filled', amount=9 WHERE id={order_id}")
        self.assertEqual(self.sql("SELECT quote_amount::text FROM order_submissions"), "123.250000001")
        self.assertEqual(self.sql("SELECT count(*) FROM order_submissions WHERE amount IS NULL"), "1")
        replay_status, _, replay = self.post(key, market)
        self.assertEqual(replay_status, 200)
        self.assertEqual(replay["order"]["status"], "filled")
        changed = dict(market, quoteAmount="124")
        self.assertEqual(self.post(key, changed)[0], 409)
        self.assertEqual(len(EngineStub.calls), 1)
        self.record(quote_amount="123.250000001", replay=replay_status, conflict=409, engine_posts=1)

    def test_process_restart_uses_committed_key(self):
        EngineStub.mode = "timeout"
        key = uuid4()
        first, _, created = self.post(key)
        self.assertEqual(first, 202)
        self.stop_platform()
        self.start_platform()
        status, _, repeated = self.post(key)
        self.assertEqual(status, 200)
        self.assertEqual(repeated["order"]["id"], created["orderId"])
        self.assert_single()
        self.record(after_restart=status, engine_posts=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
