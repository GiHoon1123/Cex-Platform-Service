"""소유 PG 서버의 메서드별 새 DB에서 보류 인식과 당시 원화 가격/미평가 결합을 검증한다. 금융 이력을 삭제해 fixture를 재사용하지 않는다."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT.parent / ".local"
ARTIFACTS.mkdir(exist_ok=True)
DOCKER = shutil.which("docker") or "/usr/local/bin/docker"
run_id = uuid4().hex[:12]
name = "cex-deposit-valuation-" + run_id
logs = ARTIFACTS / "bitcoin-deposit-valuations" / run_id
logs.mkdir(parents=True)
source = ROOT / "src/test/java/dustin/cex/domains/kyc/BitcoinDepositValuationIntegrationTest.java"
methods = re.findall(r"@Test\s+void\s+(\w+)\(", source.read_text())
assert len(methods) == 14 and len(set(methods)) == 14
result = {"container": name, "run_id": run_id, "groups": [], "cases": []}
try:
    subprocess.run([DOCKER,"run","--detach","--rm","--name",name,"--publish","127.0.0.1::5432",
        "--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234",
        "-e","POSTGRES_DB=postgres","postgres:16"],check=True,stdout=subprocess.DEVNULL)
    deadline = time.monotonic() + 45
    while subprocess.run([DOCKER,"exec",name,"pg_isready","-h","127.0.0.1","-U","root","-d","postgres"],
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if time.monotonic() > deadline:
            raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
        time.sleep(0.2)
    address = subprocess.check_output([DOCKER,"port",name,"5432/tcp"],text=True).strip()
    env = {k:v for k,v in os.environ.items() if not k.startswith(("CDD_","EDD_","DIDIT_","SPRING_MAIL_","CUSTOMER_MAIL_","BITCOIN_","CEX_BITCOIN_TEST_","CODEVASP_","VALUATION_"))}
    env.update(JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes",ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",
        SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1",SPRING_KAFKA_LISTENER_AUTO_STARTUP="false",BOT_BOT_COUNT="0",
        ORDER_DISPATCH_ENABLED="false",ENGINE_HTTP_URL="http://127.0.0.1:1",BITCOIN_PROCESSING_ENABLED="false")
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    for index, method in enumerate(methods):
        database = "valuation_case_" + str(index)
        subprocess.run([DOCKER,"exec",name,"psql","-U","root","-d","postgres","-v","ON_ERROR_STOP=1","-c","CREATE DATABASE " + database],check=True,stdout=subprocess.DEVNULL)
        env["CEX_TEST_DATABASE_URL"] = "jdbc:postgresql://" + address + "/" + database
        started = time.time()
        with (logs / (method + ".log")).open("w") as log:
            completed = subprocess.run(["./gradlew","test","--tests","dustin.cex.domains.kyc.BitcoinDepositValuationIntegrationTest."+method,
                "--rerun-tasks","bootJar","operatorAdminJar","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        group = {"database": database,"method": method,"exit": completed.returncode,"log":str((logs / (method+".log")).relative_to(ARTIFACTS))}
        result["groups"].append(group)
        if completed.returncode:
            raise RuntimeError("입금 평가 결합 실패: " + group["log"])
        suites = [ET.parse(p).getroot() for p in (ROOT/"build/test-results/test").glob("TEST-*.xml") if p.stat().st_mtime >= started]
        counts = {key:sum(int(s.attrib.get(key,0)) for s in suites) for key in ["tests","failures","errors","skipped"]}
        assert counts == dict(tests=1,failures=0,errors=0,skipped=0), counts
        group["tests"] = counts
        cases = [{"class":c.attrib["classname"],"name":c.attrib["name"]} for s in suites for c in s.findall("testcase")]
        assert len(cases)==1 and cases[0]["name"]==method+"()", cases
        result["cases"].extend(cases)
        shutil.copytree(ROOT/"build/test-results/test",logs/(method+"-xml"))
        print(method + ": 1 통과",flush=True)
    result["tests"] = {key:sum(g["tests"][key] for g in result["groups"]) for key in ["tests","failures","errors","skipped"]}
    assert len({(c["class"],c["name"]) for c in result["cases"]})==14
    result["exit"] = 0
finally:
    removed = subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"] = removed.returncode == 0
    (ARTIFACTS/"bitcoin-deposit-valuations.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
