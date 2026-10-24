"""서로 다른 Spring 설정은 별도 DB 서버에 두고 실제 로컬 SMTPS 계약을 검증한다."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT.parent / ".local"
DOCKER = shutil.which("docker") or "/usr/local/bin/docker"
GROUPS = {
    "mail": ["*CustomerMailOutboxIntegrationTest", "*CustomerEmailVerificationIntegrationTest", "*CustomerSmtpTransportTest", "*CustomerMailOperationsIntegrationTest"],
    "notices": ["*CustomerServiceMailIntegrationTest"],
}
result = {"groups": [], "exit": None}
ARTIFACTS.mkdir(exist_ok=True)
try:
    for group, patterns in GROUPS.items():
        name = "cex-mail-" + uuid4().hex[:12]
        proof = {"group": group, "container": name, "selection": patterns}
        result["groups"].append(proof)
        try:
            subprocess.run([DOCKER, "run", "--detach", "--rm", "--name", name,
                "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data",
                "-e", "POSTGRES_USER=root", "-e", "POSTGRES_PASSWORD=1234",
                "-e", "POSTGRES_DB=cex_test", "postgres:16"], check=True, stdout=subprocess.DEVNULL)
            deadline = time.monotonic() + 45
            while subprocess.run([DOCKER, "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "root", "-d", "cex_test"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
                time.sleep(0.2)
            address = subprocess.check_output([DOCKER, "port", name, "5432/tcp"], text=True).strip()
            env = {key: value for key, value in os.environ.items() if not key.startswith(("CDD_", "EDD_", "DIDIT_", "SPRING_MAIL_", "CUSTOMER_MAIL_"))}
            env.update(JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes",
                ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",
                SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1", BOT_BOT_COUNT="0",
                ORDER_DISPATCH_ENABLED="false", ENGINE_HTTP_URL="http://127.0.0.1:1",
                CEX_TEST_DATABASE_URL="jdbc:postgresql://" + address + "/cex_test")
            env.setdefault("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
            command = ["./gradlew", "test"]
            for pattern in patterns:
                command.extend(["--tests", pattern])
            command.extend(["--rerun-tasks", "bootJar", "--console=plain"])
            started = time.time()
            with (ARTIFACTS / ("mail-" + group + ".log")).open("w") as log:
                completed = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            proof["exit"] = completed.returncode
            # 컴파일 오류 때 이전 실행의 XML을 이번 결과로 집계하지 않는다.
            suites = [ET.parse(path).getroot() for path in (ROOT / "build/test-results/test").glob("TEST-*.xml") if path.stat().st_mtime >= started]
            proof["tests"] = {key: sum(int(s.attrib.get(key, 0)) for s in suites) for key in ["tests", "failures", "errors", "skipped"]}
            proof["cases"] = [{"class": s.attrib["name"], "name": t.attrib["name"]} for s in suites for t in s.findall("testcase")]
            if completed.returncode or not suites or any(proof["tests"][key] for key in ["failures", "errors", "skipped"]):
                raise RuntimeError("메일 회귀 실패: .local/mail-" + group + ".log 확인")
        finally:
            proof["container_removed"] = subprocess.run([DOCKER, "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    result["tests"] = {key: sum(g["tests"][key] for g in result["groups"]) for key in ["tests", "failures", "errors", "skipped"]}
    result["exit"] = 0
finally:
    (ARTIFACTS / "mail.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps({k: v for k, v in result.items() if k != "groups"}, indent=2))
