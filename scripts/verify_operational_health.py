"""격리 PostgreSQL에서 공개 상태·운영 적체·DB 장애와 실제 보안 경계를 검증한다."""
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
name="cex-operational-health-"+uuid4().hex[:12]
result={"container":name}
try:
    subprocess.run([DOCKER,"run","--detach","--rm","--name",name,"--publish","127.0.0.1::5432","--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234","-e","POSTGRES_DB=cex_test","postgres:16"],check=True,stdout=subprocess.DEVNULL)
    deadline=time.monotonic()+45
    while subprocess.run([DOCKER,"exec",name,"pg_isready","-h","127.0.0.1","-U","root","-d","cex_test"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if time.monotonic()>deadline:raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
        time.sleep(.2)
    address=subprocess.check_output([DOCKER,"port",name,"5432/tcp"],text=True).strip()
    env={key:value for key,value in os.environ.items() if not key.startswith(("CDD_","EDD_","DIDIT_","SPRING_MAIL_","CUSTOMER_MAIL_","BITCOIN_","CEX_BITCOIN_TEST_","CODEVASP_","VALUATION_","AUDIT_"))}
    env.update(JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes",ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1",SPRING_KAFKA_LISTENER_AUTO_STARTUP="false",BOT_BOT_COUNT="0",BITCOIN_PROCESSING_ENABLED="false",CUSTOMER_MAIL_DISPATCH_ENABLED="false",ORDER_DISPATCH_ENABLED="false",ENGINE_HTTP_URL="http://127.0.0.1:1",CEX_TEST_DATABASE_URL="jdbc:postgresql://"+address+"/cex_test")
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    log=ARTIFACTS/"operational-health.log";ARTIFACTS.mkdir(exist_ok=True)
    with log.open("w") as output:completed=subprocess.run(["./gradlew","test","--tests","*OperationalHealthIntegrationTest","--rerun-tasks","bootJar","operatorAdminJar","--console=plain"],cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT)
    suites=[ET.parse(path).getroot() for path in (ROOT/"build/test-results/test").glob("TEST-*.xml")]
    result["tests"]={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]};result["exit"]=completed.returncode
    if completed.returncode or result["tests"]["tests"]==0 or any(result["tests"][key] for key in ["failures","errors","skipped"]):raise RuntimeError("운영 상태 회귀 실패: "+str(log))
    print(json.dumps(result,indent=2),flush=True)
finally:
    removed=subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);result["container_removed"]=removed.returncode==0
    ARTIFACTS.mkdir(exist_ok=True);(ARTIFACTS/"operational-health.json").write_text(json.dumps(result,indent=2))
