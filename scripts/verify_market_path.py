"""봇 주문부터 플랫폼 HTTP·WAL·Kafka·공개 소켓까지 실행 소유 자원에서 검증한다."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from uuid import uuid4
ROOT=Path(__file__).resolve().parent.parent
ARTIFACTS=ROOT.parent/".local"
spec=importlib.util.spec_from_file_location("isolated_kafka",ROOT.parent/"Cex-Engine/scripts/isolated_kafka.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
kafka=module.IsolatedKafka("cex-market-broker")
docker=shutil.which("docker") or "/usr/local/bin/docker"
pg="cex-market-pg-"+uuid4().hex[:10]
result={"postgres":pg,"kafka":kafka.name}
try:
    subprocess.run([docker,"run","--detach","--rm","--name",pg,"--publish","127.0.0.1::5432","--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234","-e","POSTGRES_DB=cex","postgres:16"],check=True,stdout=subprocess.DEVNULL)
    deadline=time.monotonic()+40
    while subprocess.run([docker,"exec",pg,"pg_isready","-h","127.0.0.1","-U","root","-d","cex"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if time.monotonic()>deadline:raise RuntimeError("격리 PostgreSQL 시작 시간 초과")
        time.sleep(0.2)
    address=subprocess.check_output([docker,"port",pg,"5432/tcp"],text=True).strip()
    kafka.start()
    with socket.socket() as reserve:reserve.bind(("127.0.0.1",0));engine_port=reserve.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="cex-market-journal-") as directory:
        env=os.environ.copy();env.update(CEX_TEST_SERVICE_TOKEN=uuid4().hex+uuid4().hex,CEX_TEST_AUDIT_TOKEN=uuid4().hex+uuid4().hex,CEX_MARKET_E2E="1",CEX_TEST_DATABASE_URL=f"jdbc:postgresql://{address}/cex",CEX_TEST_KAFKA=kafka.bootstrap,CEX_TEST_ENGINE_URL=f"http://127.0.0.1:{engine_port}",CEX_ENGINE_DATA_DIR=directory,CEX_ENGINE_BINARY=str(ROOT.parent/"Cex-Engine/target/debug/cex-engine"),CEX_ENGINE_LOG=str(ARTIFACTS/"market-path-engine.log"),BOT_BOT_COUNT="0",ENGINE_HTTP_URL=f"http://127.0.0.1:{engine_port}",SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka.bootstrap)
        env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
        with (ARTIFACTS/"market-path-platform.log").open("w") as log:
            completed=subprocess.run(["./gradlew","test","--tests","*MarketPathTest","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        result["exit"]=completed.returncode
        if completed.returncode:raise RuntimeError("전체 경로 검증 실패: .local/market-path-platform.log")
finally:
    result["kafka_removed"]=kafka.stop()
    subprocess.run([docker,"rm","--force",pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    result["postgres_removed"]=subprocess.run([docker,"inspect",pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
    (ARTIFACTS/"market-path-results.json").write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
