"""소유 PostgreSQL의 메서드별 새 DB에서 독립 내용 서명·정확한 위험 구간 해제·현재 금융 범위·필수 감사를 검증한다."""
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
name = "cex-bitcoin-chain-clearance-" + run_id
logs = ARTIFACTS / "bitcoin-chain-clearance" / run_id
logs.mkdir(parents=True)
source = ROOT / "src/test/java/dustin/cex/domains/kyc/BitcoinChainClearanceIntegrationTest.java"
methods = re.findall(r"@Test\s+void\s+(\w+)\(", source.read_text())
assert len(methods) == 32 and len(set(methods)) == 32
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
        CODEVASP_PROCESSING_ENABLED="false",CODEVASP_PRIVATE_TRANSFER_ENABLED="false",ORDER_DISPATCH_ENABLED="false",ENGINE_HTTP_URL="http://127.0.0.1:1",BITCOIN_PROCESSING_ENABLED="false")
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    for index, method in enumerate(methods):
        database = "monitoring_case_" + str(index)
        subprocess.run([DOCKER,"exec",name,"psql","-U","root","-d","postgres","-v","ON_ERROR_STOP=1","-c","CREATE DATABASE " + database],check=True,stdout=subprocess.DEVNULL)
        env["CEX_TEST_DATABASE_URL"] = "jdbc:postgresql://" + address + "/" + database
        # 금융 시각 검사를 완화하지 않는다. 호스트/소유 DB의 차이와 실행 시간을 남겨 환경 정지 후 실패를 구분한다.
        epoch=float(subprocess.check_output([DOCKER,"exec",name,"psql","-U","root","-d",database,"-Atc","SELECT extract(epoch from clock_timestamp())"],text=True).strip())
        clock_difference=time.time()-epoch
        if abs(clock_difference)>=2:raise RuntimeError("호스트와 격리 DB의 시각 차이가 큽니다: "+str(clock_difference))
        started = time.time()
        with (logs / (method + ".log")).open("w") as log:
            completed = subprocess.run(["./gradlew","test","--rerun","--tests","dustin.cex.domains.kyc.BitcoinChainClearanceIntegrationTest."+method,
                "bootJar","operatorAdminJar","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        group = {"database": database,"method": method,"exit": completed.returncode,"clock_difference_seconds":clock_difference,"duration_seconds":time.time()-started,"log":str((logs / (method+".log")).relative_to(ARTIFACTS))}
        result["groups"].append(group)
        if completed.returncode:
            raise RuntimeError("비트코인 체인 해제 검증 실패: " + group["log"])
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
    assert len({(c["class"],c["name"]) for c in result["cases"]})==len(methods)
    result["exit"] = 0
finally:
    removed = subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"] = removed.returncode == 0
    (ARTIFACTS/"bitcoin-chain-clearance.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
