"""이번 실행 소유 PostgreSQL에서 엔진 이벤트 중복·롤백·경쟁 회귀를 실행한다."""
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
name = "cex-inbox-" + uuid4().hex[:12]
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
    with (ARTIFACTS/"wal-java-inbox.log").open("w") as log:
        completed=subprocess.run(["./gradlew","test","--tests","*FrameworkContractTest","--tests","*EngineEventInboxTest","--tests","*FeeConfigServiceTest","--tests","*FeeRevenueTest","--tests","*TradeFeeSettlementTest","--tests","dustin.cex.domains.market.*","--tests","*BotSafetyTest","--tests","*KafkaOrderEventConsumerTest","--tests","*Order*Test","--tests","*EngineHttpClientTest","--tests","*PlatformBalanceStartupTest","--tests","*TradeSettlement*Test","bootJar","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    result["exit"]=completed.returncode
    if completed.returncode:
        raise RuntimeError("플랫폼 회귀 실패: .local/wal-java-inbox.log 확인")
    suites=[ET.parse(path).getroot() for path in (ROOT/"build/test-results/test").glob("TEST-*.xml")]
    result["tests"]={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]}
    assert result["tests"]["skipped"]==0, result
    print(json.dumps(result,indent=2),flush=True)
finally:
    removed=subprocess.run([DOCKER,"rm","--force",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["container_removed"]=removed.returncode==0
    (ARTIFACTS/"wal-java-inbox.json").write_text(json.dumps(result,indent=2))
