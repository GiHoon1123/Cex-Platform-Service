"""격리 PostgreSQL에서 수탁 대사·승인 정정·공통 원장 회귀를 서로 다른 DB로 검증한다."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from uuid import uuid4

ROOT=Path(__file__).resolve().parent.parent
ARTIFACTS=ROOT.parent/".local"
DOCKER=shutil.which("docker") or "/usr/local/bin/docker"
name="cex-custody-corrections-"+uuid4().hex[:12]
run_id=name.rsplit("-",1)[1]
proof=ARTIFACTS/"bitcoin-custody-corrections"/run_id
proof.mkdir(parents=True,exist_ok=True)
result={"container":name,"run_id":run_id,"groups":[]}
try:
    subprocess.run([DOCKER,"run","--detach","--rm","--name",name,"--publish","127.0.0.1::5432","--tmpfs","/var/lib/postgresql/data",
        "-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234","-e","POSTGRES_DB=cex_test","postgres:16"],check=True,stdout=subprocess.DEVNULL)
    deadline=time.monotonic()+45
    while subprocess.run([DOCKER,"exec",name,"pg_isready","-h","127.0.0.1","-U","root","-d","cex_test"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if time.monotonic()>deadline:raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
        time.sleep(.2)
    address=subprocess.check_output([DOCKER,"port",name,"5432/tcp"],text=True).strip()
    env={key:value for key,value in os.environ.items() if not key.startswith(("CDD_","EDD_","DIDIT_","SPRING_MAIL_","CUSTOMER_MAIL_","BITCOIN_","CEX_BITCOIN_TEST_","CODEVASP_","VALUATION_"))}
    env.update(JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes",ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",
        SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1",SPRING_KAFKA_LISTENER_AUTO_STARTUP="false",BOT_BOT_COUNT="0",
        BITCOIN_PROCESSING_ENABLED="false",CUSTOMER_MAIL_DISPATCH_ENABLED="false",ORDER_DISPATCH_ENABLED="false",ENGINE_HTTP_URL="http://127.0.0.1:1")
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    # 서로 다른 스키마 수명과 초기 잔고를 가진 시험을 한 DB에 섞지 않는다. 각 묶음은 제품 코드의 실제 경계를 독립 검증한다.
    groups=[("correction",["*BitcoinCustodyCorrectionIntegrationTest"]),("reconciliation",["*BitcoinCustodyReconciliationIntegrationTest"]),("ledger",["*OrderLedgerTest"])]
    for group,patterns in groups:
        database="custody_"+group
        subprocess.run([DOCKER,"exec",name,"createdb","-h","127.0.0.1","-U","root",database],check=True)
        env["CEX_TEST_DATABASE_URL"]="jdbc:postgresql://"+address+"/"+database
        command=["./gradlew","test"]
        for pattern in patterns:command.extend(["--tests",pattern])
        command.extend(["--rerun-tasks","bootJar","operatorAdminJar","--console=plain"])
        log=proof/(group+".log")
        with log.open("w") as output:completed=subprocess.run(command,cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT)
        suites=[ET.parse(path).getroot() for path in (ROOT/"build/test-results/test").glob("TEST-*.xml")]
        counts={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]}
        result["groups"].append({"name":group,"database":database,"exit":completed.returncode,"tests":counts,"log":str(log.relative_to(ARTIFACTS))})
        if completed.returncode or counts["tests"]==0 or counts["failures"] or counts["errors"] or counts["skipped"]:raise RuntimeError("수탁 정정 회귀 실패: "+str(log))
        print(group+": "+json.dumps(counts),flush=True)
    result["tests"]={key:sum(group["tests"][key] for group in result["groups"]) for key in ["tests","failures","errors","skipped"]}
    result["exit"]=0
    print(json.dumps(result,indent=2),flush=True)
finally:
    removed=subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"]=removed.returncode==0
    ARTIFACTS.mkdir(exist_ok=True)
    (ARTIFACTS/"bitcoin-custody-corrections.json").write_text(json.dumps(result,indent=2))
