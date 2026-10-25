"""노드 없이 소유 PostgreSQL에서 정보 제공 의도의 현재 원본·암호화·고객 잠금·감사를 검증한다."""
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
name = "cex-transfer-intent-" + uuid4().hex[:12]
result = {"container": name}
try:
    subprocess.run([DOCKER,"run","--detach","--rm","--name",name,"--publish","127.0.0.1::5432",
        "--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234",
        "-e","POSTGRES_DB=cex_test","postgres:16"],check=True,stdout=subprocess.DEVNULL)
    deadline=time.monotonic()+45
    while subprocess.run([DOCKER,"exec",name,"pg_isready","-h","127.0.0.1","-U","root","-d","cex_test"],
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if time.monotonic()>deadline:
            raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
        time.sleep(0.2)
    address=subprocess.check_output([DOCKER,"port",name,"5432/tcp"],text=True).strip()
    env={k:v for k,v in os.environ.items() if not k.startswith(("CDD_","EDD_","DIDIT_","SPRING_MAIL_","CUSTOMER_MAIL_","BITCOIN_","CEX_BITCOIN_TEST_","CODEVASP_","VALUATION_"))}
    env["JWT_SECRET"]="isolated-process-jwt-secret-at-least-32-bytes"
    env["ENGINE_SERVICE_TOKEN"]="isolated-engine-service-token-32-bytes"
    env["SPRING_KAFKA_BOOTSTRAP_SERVERS"]="127.0.0.1:1"
    env["BOT_BOT_COUNT"]="0"
    env["SPRING_KAFKA_LISTENER_AUTO_STARTUP"]="false"
    env["ORDER_DISPATCH_ENABLED"]="false"
    env["ENGINE_HTTP_URL"]="http://127.0.0.1:1"
    env["CEX_TEST_DATABASE_URL"]="jdbc:postgresql://"+address+"/cex_test"
    # 응답할 수 없는 루프백 주소다. 조회가 노드를 호출하면 실제 시험이 실패한다.
    env.update(BITCOIN_PROCESSING_ENABLED="false",CEX_BITCOIN_TEST_URL="http://127.0.0.1:1",
        CEX_BITCOIN_TEST_USER="isolated_reader",CEX_BITCOIN_TEST_PASSWORD="isolated-customer-query-password-at-least-32-bytes")
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    ARTIFACTS.mkdir(exist_ok=True)
    started=time.time()
    with (ARTIFACTS/"transfer-intents.log").open("w") as log:
        completed=subprocess.run(["./gradlew","test","--tests","*CodeVaspTransferIntentIntegrationTest","--tests","*CodeVaspTransferExecutionIntegrationTest","--tests","*CodeVaspTransferPurposeIntegrationTest","--rerun-tasks","bootJar","operatorAdminJar","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    result["exit"]=completed.returncode
    if completed.returncode:
        raise RuntimeError("정보 제공 의도 회귀 실패: .local/transfer-intents.log 확인")
    suites=[ET.parse(path).getroot() for path in (ROOT/"build/test-results/test").glob("TEST-*.xml") if path.stat().st_mtime>=started]
    result["tests"]={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]}
    result["cases"]=[{"class":case.attrib["classname"],"name":case.attrib["name"]} for suite in suites for case in suite.findall("testcase")]
    assert result["tests"]["tests"]>0 and all(result["tests"][k]==0 for k in ["failures","errors","skipped"]),result
    print(json.dumps(result,indent=2),flush=True)
finally:
    removed=subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"]=removed.returncode==0
    (ARTIFACTS/"transfer-intents.json").write_text(json.dumps(result,indent=2))
