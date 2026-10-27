"""실행기 소유 Anvil에서 네이티브 ETH 송신과 정식 영수증을 검증한다."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ARTIFACT = ROOT.parent / ".local" / "ethereum-rpc-verification.json"
ANVIL = shutil.which("anvil") or str(Path.home() / ".foundry" / "bin" / "anvil")

with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]

process = subprocess.Popen([ANVIL, "--host", "127.0.0.1", "--port", str(port), "--chain-id", "31337", "--silent"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
result = {"port": port, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
try:
    deadline = time.monotonic() + 20
    while True:
        with socket.socket() as client:
            if client.connect_ex(("127.0.0.1", port)) == 0:
                break
        if time.monotonic() > deadline:
            raise RuntimeError("격리 Anvil 시작 시간 초과")
        time.sleep(0.1)
    env = dict(os.environ)
    env["CEX_ETHEREUM_RPC"] = f"http://127.0.0.1:{port}"
    execution = subprocess.run(["./gradlew", "test", "--tests", "dustin.cex.domains.ethereum.EthereumAnvilIntegrationTest", "--rerun-tasks", "--no-daemon", "--console=plain"], cwd=ROOT, env=env)
    for report in (ROOT / "build/test-results/test").glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        if "EthereumAnvilIntegrationTest" in suite.attrib.get("name", ""):
            for key in result:
                if key in {"tests", "failures", "errors", "skipped"}:
                    result[key] += int(suite.attrib.get(key, 0))
    result["exit"] = execution.returncode
    if execution.returncode or result["tests"] != 1 or any(result[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError("실제 Anvil 검증 실패")
finally:
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    result["process_stopped"] = process.poll() is not None
    ARTIFACT.parent.mkdir(parents=True, exist_ok=True)
    ARTIFACT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

print(json.dumps(result, ensure_ascii=False, indent=2))
