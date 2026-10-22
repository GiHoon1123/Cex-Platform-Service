"""실행 소유 PostgreSQL에서 네 승인 업무의 실제 서명과 기존 복구·역할·거래 제한 회귀을 검증한다."""
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
name = "cex-bound-approvals-" + uuid4().hex[:12]
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
    env=os.environ.copy()
    env["JWT_SECRET"]="isolated-process-jwt-secret-at-least-32-bytes"
    env["ENGINE_SERVICE_TOKEN"]="isolated-engine-service-token-32-bytes"
    env["SPRING_KAFKA_BOOTSTRAP_SERVERS"]="127.0.0.1:1"
    env["BOT_BOT_COUNT"]="0"
    env["ORDER_DISPATCH_ENABLED"]="false"
    env["ENGINE_HTTP_URL"]="http://127.0.0.1:1"
    env["CEX_TEST_DATABASE_URL"]="jdbc:postgresql://"+address+"/cex_test"
    env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
    ARTIFACTS.mkdir(exist_ok=True)
    # 서로 다른 Spring 설정은 JPA 표를 새로 만들지만 JDBC 보안 이력은 보존한다.
    # ID만 재설정하는 여러 fixture가 충돌하지 않도록 테스트 묶음마다 새 DB를 만든다.
    groups=[("bound",["*BoundApprovalTest"]),("roles",["*OperatorRoleTest"]),
        ("lifecycle",["*OperatorLifecycleTest","*OperatorSessionPolicyTest"]),("security",["*PlatformSecurityTest"])]
    result["groups"]=[]
    for group,patterns in groups:
        database="approval_"+group
        subprocess.run([DOCKER,"exec",name,"createdb","-h","127.0.0.1","-U","root",database],check=True)
        env["CEX_TEST_DATABASE_URL"]="jdbc:postgresql://"+address+"/"+database
        command=["./gradlew","test"]
        for pattern in patterns:command.extend(["--tests",pattern])
        command.extend(["--rerun-tasks","bootJar","--console=plain"])
        with (ARTIFACTS/("bound-approvals-"+group+".log")).open("w") as log:
            completed=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        evidence=ARTIFACTS/"bound-approval-suites"/group
        evidence.mkdir(parents=True,exist_ok=True)
        suites=[]
        for path in (ROOT/"build/test-results/test").glob("TEST-*.xml"):
            shutil.copy(path,evidence/path.name);suites.append(ET.parse(path).getroot())
        counts={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]}
        result["groups"].append({"name":group,"exit":completed.returncode,"tests":counts})
        result["exit"]=completed.returncode
        if completed.returncode:
            raise RuntimeError("승인 회귀 실패: .local/bound-approvals-"+group+".log 확인")
        assert counts["tests"]>0 and counts["skipped"]==0,counts
        print(group+": "+json.dumps(counts),flush=True)
    result["tests"]={key:sum(group["tests"][key] for group in result["groups"]) for key in ["tests","failures","errors","skipped"]}
    print(json.dumps(result,indent=2),flush=True)
finally:
    removed=subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"]=removed.returncode==0
    (ARTIFACTS/"bound-approvals.json").write_text(json.dumps(result,indent=2))
