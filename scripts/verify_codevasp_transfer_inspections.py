"""소유 PG 서버를 묶음별로 분리해 인증된 개인정보 검토·접근 증적과 기존 운영 조회 회귀를 검증한다."""
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
run_id = uuid4().hex[:12]
proof = ARTIFACTS / "transfer-inspection" / run_id
proof.mkdir(parents=True)
result = {"run_id": run_id, "groups": [], "cases": []}
groups = [
    ("inspection", ["*CodeVaspTransferInspectionIntegrationTest"], 18),
    ("transfer", ["*CodeVaspTransferReviewIntegrationTest"], 11),
    ("origin", ["*BitcoinDepositOriginSearchReviewIntegrationTest"], 13),
    ("authorization", ["*BitcoinDepositReviewSourceIntegrationTest"], 14),
]
for group, selections, expected in groups:
    name = "cex-transfer-inspection-" + uuid4().hex[:12]
    current = {"group": group, "container": name, "selection": selections}
    result["groups"].append(current)
    try:
        subprocess.run([DOCKER, "run", "--detach", "--rm", "--name", name, "--publish", "127.0.0.1::5432",
            "--tmpfs", "/var/lib/postgresql/data", "-e", "POSTGRES_USER=root", "-e", "POSTGRES_PASSWORD=1234",
            "-e", "POSTGRES_DB=cex_test", "postgres:16"], check=True, stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 45
        while subprocess.run([DOCKER, "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "root", "-d", "cex_test"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline: raise RuntimeError("격리 PG 시작 시간 초과")
            time.sleep(.2)
        address = subprocess.check_output([DOCKER, "port", name, "5432/tcp"], text=True).strip()
        env = {k:v for k,v in os.environ.items() if not k.startswith(("CDD_", "EDD_", "DIDIT_", "SPRING_MAIL_", "CUSTOMER_MAIL_", "BITCOIN_", "CEX_BITCOIN_TEST_", "CODEVASP_", "VALUATION_"))}
        env.update(CEX_TEST_DATABASE_URL="jdbc:postgresql://" + address + "/cex_test",
            JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes", ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",
            SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1", SPRING_KAFKA_LISTENER_AUTO_STARTUP="false",
            BOT_BOT_COUNT="0", ORDER_DISPATCH_ENABLED="false", ENGINE_HTTP_URL="http://127.0.0.1:1",
            BITCOIN_PROCESSING_ENABLED="false", CEX_BITCOIN_TEST_URL="http://127.0.0.1:1",
            CEX_BITCOIN_TEST_USER="isolated", CEX_BITCOIN_TEST_PASSWORD="isolated-valuation-review-password-at-least-32-bytes")
        env.setdefault("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
        command = ["./gradlew", "test"]
        for selection in selections: command += ["--tests", selection]
        command += ["--rerun-tasks", "bootJar", "operatorAdminJar", "--console=plain"]
        started = time.time()
        with (proof / (group + ".log")).open("w") as log:
            completed = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        current["exit"] = completed.returncode
        if completed.returncode: raise RuntimeError("개인정보 검토 검증 실패: " + group)
        suites = [ET.parse(p).getroot() for p in (ROOT / "build/test-results/test").glob("TEST-*.xml") if p.stat().st_mtime >= started]
        counts = {key:sum(int(s.attrib.get(key, 0)) for s in suites) for key in ["tests", "failures", "errors", "skipped"]}
        current["tests"] = counts
        assert counts == dict(tests=expected, failures=0, errors=0, skipped=0), counts
        result["cases"].extend({"class":c.attrib["classname"], "name":c.attrib["name"]} for s in suites for c in s.findall("testcase"))
        shutil.copytree(ROOT / "build/test-results/test", proof / (group + "-xml"))
        print(group, counts, flush=True)
    finally:
        removed = subprocess.run([DOCKER, "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        current["container_removed"] = removed.returncode == 0
        (ARTIFACTS / "transfer-inspection.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
result["tests"] = {key:sum(g["tests"][key] for g in result["groups"]) for key in ["tests", "failures", "errors", "skipped"]}
assert len({(c["class"], c["name"]) for c in result["cases"]}) == 56
result["exit"] = 0
(ARTIFACTS / "transfer-inspection.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
