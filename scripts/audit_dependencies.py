"""실제 의존성을 OSV 공식 API와 대조한다. 조회 실패를 안전 판정으로 숨기지 않는다."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT.parent / ".local/dependency-audit.json"
result = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "status": "INCOMPLETE"}
try:
    subprocess.run(["./gradlew", "writeDependencyInventory", "--rerun-tasks", "--console=plain"], cwd=ROOT, check=True)
    inventory = ROOT / "build/reports/dependencies/inventory.json"
    result["inventory_sha256"] = hashlib.sha256(inventory.read_bytes()).hexdigest()
    entries = json.loads(inventory.read_text())
    packages = {}
    for entry in entries:
        key = (entry["group"] + ":" + entry["name"], entry["version"])
        packages.setdefault(key, set()).add(entry["scope"])
    ordered = sorted(packages)
    findings = []
    for start in range(0, len(ordered), 128):
        batch = ordered[start:start + 128]
        body = {"queries": [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in batch]}
        request = Request("https://api.osv.dev/v1/querybatch", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=30) as response:
            answers = json.load(response)["results"]
        if len(answers) != len(batch):
            raise RuntimeError("조회 결과 개수가 요청과 다릅니다")
        for key, answer in zip(batch, answers):
            if answer.get("next_page_token"):
                raise RuntimeError("취약점 결과 페이지가 남아 있습니다")
            for vuln in answer.get("vulns", []):
                findings.append({"package": key[0], "version": key[1], "scopes": sorted(packages[key]), "id": vuln["id"]})
    result.update(status="FINDINGS" if findings else "NO_KNOWN_FINDINGS", package_versions=len(ordered), findings=findings)
except Exception as failure:
    result["error"] = type(failure).__name__ + ": " + str(failure)
finally:
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False, indent=2))
if result["status"] != "NO_KNOWN_FINDINGS":
    raise SystemExit(1)
