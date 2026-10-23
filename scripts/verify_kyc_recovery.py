"""제품 빈을 사용한 별도 JVM의 강제 종료·임대 회수·서명 결과 복원을 새 PostgreSQL에서 검증한다."""
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT.parent / ".local"
DOCKER = shutil.which("docker") or "/usr/local/bin/docker"
JAVA_HOME = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
APP = "11111111-2222-3333-4444-555555555555"
WORKFLOW = "66666666-7777-8888-9999-000000000000"
SECRET = "isolated-didit-webhook-secret-at-least-32-characters"
name = "cex-kyc-recovery-" + uuid4().hex[:12]
state = {}
entered = threading.Event()
release = threading.Event()
process = None
server = None
report = {"container": name, "checks": [], "provider": "loopback", "bots_disabled": True}


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, code=200):
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        assert self.path == "/v3/session/"
        assert self.headers["x-api-key"] == "isolated-api-key-at-least-16"
        command = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        state["posts"] = state.get("posts", 0) + 1
        state["attempt"] = command["vendor_data"]
        session = state.setdefault("session", str(uuid4()))
        decision = {"session_id": session, "status": "Approved",
                    "id_verifications": [{"status": "Approved", "verification_method": "document", "assurance": "documentary", "full_name": "가상 복원 고객", "date_of_birth": "1990-01-01", "nationality": "KOR"}],
                    "liveness_checks": [{"status": "Approved"}], "face_matches": [{"status": "Approved"}],
                    "aml_screenings": [{"status": "Approved", "total_hits": 0, "entity_type": "person", "score": 0, "hits": [], "warnings": [],
                        "screened_data": {"full_name": "가상 복원 고객", "date_of_birth": "1990-01-01", "nationality": "KR"}}]}
        now = int(time.time())
        event = {"event_id": str(uuid4()), "webhook_type": "status.updated", "timestamp": now, "created_at": now - 10,
                 "application_id": APP, "environment": "live", "workflow_id": WORKFLOW, "workflow_version": 4,
                 "vendor_data": command["vendor_data"], "session_id": session, "status": "Approved", "decision": decision}
        body = json.dumps(event, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        signature = hmac.new(SECRET.encode(), body.encode(), hashlib.sha256).hexdigest()
        state["delivery"] = {"uuid": str(uuid4()), "session_id": session, "webhook_type": "status.updated",
                             "request_body": body, "request_header": json.dumps({"X-Signature-V2": signature, "X-Timestamp": str(now)})}
        state["decision"] = {**decision, "vendor_data": command["vendor_data"], "session_kind": "user", "environment": "live", "workflow_id": WORKFLOW}
        self.reply({"session_id": session, "vendor_data": command["vendor_data"], "workflow_id": WORKFLOW,
                    "workflow_version": 4, "url": "https://verify.didit.me/session/" + session}, 201)

    def do_GET(self):
        assert self.headers["x-api-key"] == "isolated-api-key-at-least-16"
        if self.path.endswith("/webhooks/"):
            entered.set()
            assert release.wait(40), "강제 종료 검증 대기 시간 초과"
            self.reply([state["delivery"]])
        else:
            assert self.path.endswith("/decision/")
            self.reply(state["decision"])


def sql(statement):
    return subprocess.check_output([DOCKER, "exec", name, "psql", "-U", "root", "-d", "cex_test",
                                    "-v", "ON_ERROR_STOP=1", "-At", "-c", statement], text=True).strip()


def start(phase, classpath, address):
    env = {key: value for key, value in os.environ.items() if not key.startswith(("DIDIT_","CDD_"))}
    env.update(CEX_KYC_PROBE_PROVIDER=f"http://127.0.0.1:{server.server_port}", CEX_KYC_PROBE_PHASE=phase)
    command = [str(Path(JAVA_HOME) / "bin/java"), "-cp", classpath, "dustin.cex.domains.kyc.KycRecoveryProcessProbe",
               "--server.address=127.0.0.1", "--server.port=0", f"--spring.datasource.url=jdbc:postgresql://{address}/cex_test",
               "--spring.datasource.username=root", "--spring.datasource.password=1234", "--spring.datasource.driver-class-name=org.postgresql.Driver",
               "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
               "--spring.jpa.hibernate.ddl-auto=" + ("update" if phase == "prepare" else "validate"),
               "--bot.bot-count=0", "--order.dispatch.enabled=false", "--kyc.reconciliation.enabled=false",
               "--engine.http.url=http://127.0.0.1:1", "--spring.kafka.bootstrap-servers=127.0.0.1:1", "--spring.kafka.listener.auto-startup=false",
               "--didit.api-key=isolated-api-key-at-least-16", f"--didit.webhook-secret={SECRET}", f"--didit.application-id={APP}",
               f"--didit.workflow-id={WORKFLOW}", "--didit.workflow-version=4", "--didit.environment=live",
               "--didit.callback=https://exchange.example.test/kyc/return", "--didit.encryption-key=" + base64.b64encode(bytes(32)).decode()]
    with (ARTIFACTS / f"kyc-recovery-{phase}.log").open("w") as log:
        return subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)


try:
    ARTIFACTS.mkdir(exist_ok=True)
    subprocess.run([DOCKER, "run", "--detach", "--rm", "--name", name, "--publish", "127.0.0.1::5432",
                    "--tmpfs", "/var/lib/postgresql/data", "-e", "POSTGRES_USER=root", "-e", "POSTGRES_PASSWORD=1234",
                    "-e", "POSTGRES_DB=cex_test", "postgres:16"], check=True, stdout=subprocess.DEVNULL)
    deadline = time.monotonic() + 45
    while subprocess.run([DOCKER, "exec", name, "pg_isready", "-U", "root", "-d", "cex_test"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        if time.monotonic() > deadline:
            raise RuntimeError("검증 소유 PostgreSQL 시작 시간 초과")
        time.sleep(0.2)
    address = subprocess.check_output([DOCKER, "port", name, "5432/tcp"], text=True).strip()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix="cex-kyc-classpath-") as temporary:
        init = Path(temporary) / "probe.gradle"
        classpath_file = Path(temporary) / "classpath"
        init.write_text("allprojects { tasks.register('writeKycProbeClasspath') { doLast { file(System.getenv('CEX_KYC_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath } } }\n")
        env = os.environ.copy()
        env.update(JAVA_HOME=JAVA_HOME, CEX_KYC_CLASSPATH=str(classpath_file))
        with (ARTIFACTS / "kyc-recovery-build.log").open("w") as log:
            subprocess.run(["./gradlew", "testClasses", "-I", str(init), "writeKycProbeClasspath", "--console=plain"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        classpath = classpath_file.read_text()
        process = start("prepare", classpath, address)
        deadline = time.monotonic() + 45
        while not entered.wait(0.2):
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("첫 JVM의 공급사 조회 진입 실패: .local/kyc-recovery-prepare.log")
        assert sql("SELECT count(*) FROM kyc_reconciliations WHERE lease_id IS NOT NULL") == "1"
        assert sql("SELECT identity_status FROM kyc_profiles") == "PENDING"
        process.kill()
        process.wait(timeout=10)
        report["killed_exit"] = process.returncode
        assert process.returncode < 0
        assert sql("SELECT count(*) FROM kyc_events") == "0"
        report["checks"].append({"test": "kill_during_provider_get_preserves_pending_attempt_and_lease", "passed": True})
        release.set()
        # 실제 2분을 기다리는 대신 임대 시각만 과거로 옮긴다. 금융·감사·이벤트 이력은 변경하지 않는다.
        sql("UPDATE kyc_reconciliations SET lease_until=clock_timestamp()-interval '1 second'")
        process = start("resume", classpath, address)
        code = process.wait(timeout=45)
        assert code == 0, ".local/kyc-recovery-resume.log 확인"
        assert "KYC_PROCESS_RECOVERY_CONFIRMED" in (ARTIFACTS / "kyc-recovery-resume.log").read_text()
        assert state["posts"] == 1
        report["checks"].extend([
            {"test": "fresh_jvm_recovers_expired_lease_and_original_signed_approval", "passed": True},
            {"test": "subsequent_reconciliation_keeps_one_receipt_and_original_expiry", "passed": True}])
    report["exit"] = 0
    report["jvm_runs"] = 2
finally:
    release.set()
    if process is not None and process.poll() is None:
        process.kill()
        process.wait(timeout=10)
    if server is not None:
        server.shutdown()
        server.server_close()
    report["container_removed"] = subprocess.run([DOCKER, "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    (ARTIFACTS / "kyc-recovery-process.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(report, ensure_ascii=False, indent=2))
