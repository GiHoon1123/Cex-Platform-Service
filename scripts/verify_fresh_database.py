"""소유한 빈 PostgreSQL 데이터베이스에서 마이그레이션과 실제 플랫폼 기동을 검증한다."""
from pathlib import Path
from urllib.parse import urlparse, urlunparse
from uuid import uuid4
import json
import os
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
admin_url = os.environ.get("DATABASE_ADMIN_URL")
if not admin_url:
    raise SystemExit("DATABASE_ADMIN_URL이 필요합니다")
parsed = urlparse(admin_url)
if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname:
    raise SystemExit("DATABASE_ADMIN_URL 형식을 확인하세요")

name = "cex_fresh_" + uuid4().hex[:12]
database_url = urlunparse(parsed._replace(path="/" + name, query="", fragment=""))
host = f"[{parsed.hostname}]" if ":" in parsed.hostname else parsed.hostname
jdbc_url = f"jdbc:postgresql://{host}:{parsed.port or 5432}/{name}"

with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]

def run(*command: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=check)

log_dir = ROOT.parent / ".local" / "fresh-database"
log_dir.mkdir(parents=True, exist_ok=True)
log_path = log_dir / f"{name}.log"
process = None
checks: list[str] = []
try:
    # 배포 마이그레이션과 엔진의 독립 시험 스키마가 갈라지면 접수 증명의 의미도 달라진다.
    # 설명 주석은 각 저장소의 독자에 맞게 둘 수 있으므로 실행 SQL만 정규화해 비교한다.
    def executable_sql(path: Path) -> str:
        lines = (line for line in path.read_text().splitlines() if not line.lstrip().startswith("--"))
        return " ".join(" ".join(lines).split())

    migration = ROOT / "src/main/resources/db/migration/V2__engine_order_receipts.sql"
    engine_schema = ROOT.parent / "Cex-Engine/schema/order_receipts.sql"
    if executable_sql(migration) != executable_sql(engine_schema):
        raise AssertionError("플랫폼 마이그레이션과 엔진 접수 스키마가 다릅니다")
    checks.append("엔진 접수 스키마 원본 대조")

    # 이름은 이 스크립트가 만든 영숫자와 밑줄뿐이므로 식별자 인용 없이도 외부 입력이 들어오지 않는다.
    run("psql", admin_url, "-v", "ON_ERROR_STOP=1", "-c", f"CREATE DATABASE {name}")
    environment = os.environ.copy()
    environment.update({
        "DATABASE_URL": jdbc_url,
        "DATABASE_USERNAME": parsed.username or "",
        "DATABASE_PASSWORD": parsed.password or "",
        "JWT_SECRET": "fresh-database-test-secret-at-least-32-bytes",
        "KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:1",
        "ENGINE_HTTP_URL": "http://127.0.0.1:1",
        "ENGINE_SERVICE_TOKEN": "fresh-database-engine-token-at-least-32-bytes",
        "OPERATOR_WEBAUTHN_RP_ID": "localhost",
        "OPERATOR_WEBAUTHN_ORIGIN": "http://localhost:3004",
        "KYC_RECONCILIATION_ENABLED": "false",
    })
    log = log_path.open("w")
    process = subprocess.Popen([
        "./gradlew", "bootRun", "--console=plain",
        f"--args=--server.port={port} --spring.kafka.listener.auto-startup=false --bot.bot-count=0",
    ], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 45
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("플랫폼이 준비되기 전에 종료됐습니다")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health/live", timeout=1) as response:
                if response.status == 200:
                    checks.append("실제 플랫폼 생존 응답")
                    break
        except OSError:
            time.sleep(0.25)
    else:
        raise RuntimeError("플랫폼 준비 시간을 초과했습니다")

    versions = run("psql", database_url, "-Atc", "SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success").stdout.strip()
    if versions != "1,2":
        raise AssertionError({"versions": versions})
    checks.append("Flyway 1·2 순차 적용")
    receipt = run("psql", database_url, "-Atc", "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='engine_order_receipts'").stdout.strip()
    if receipt != "1":
        raise AssertionError({"engine_order_receipts": receipt})
    checks.append("엔진 접수 표 선행 준비")
finally:
    if process is not None and process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=35)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    if 'log' in locals():
        log.close()
    # 이 실행이 만든 정확한 데이터베이스만 연결을 끊고 삭제한다.
    run("psql", admin_url, "-v", "ON_ERROR_STOP=1", "-c", f"DROP DATABASE IF EXISTS {name} WITH (FORCE)", check=False)

result = {"database": name, "checks": checks, "passed": len(checks), "failed": 0, "log": str(log_path)}
print(json.dumps(result, ensure_ascii=False, indent=2))
