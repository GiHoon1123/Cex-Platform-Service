"""임시 복사본의 기대 체크섬만 바꾸고 Gradle의 실제 거부를 검증한다."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT.parent / ".local"
ARTIFACTS.mkdir(exist_ok=True)
result = {"check": "gradle_rejects_altered_checksum", "original_cache_modified": False}
try:
    with tempfile.TemporaryDirectory(prefix="cex-checksum-") as directory:
        copy = Path(directory) / "platform"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns(".git", ".gradle", "build", "docs", "__pycache__"))
        metadata = copy / "gradle/verification-metadata.xml"
        tree = ET.parse(metadata)
        ns = {"g": "https://schema.gradle.org/dependency-verification"}
        target = tree.find(".//g:component[@group='org.springframework.security'][@name='spring-security-core']/g:artifact[@name='spring-security-core-7.1.1.jar']", ns)
        if target is None:
            raise RuntimeError("검증할 고정 보안 라이브러리의 체크섬이 없습니다")
        for digest in list(target):
            if digest.tag.endswith("sha256"):
                target.remove(digest)
        ET.SubElement(target, "{https://schema.gradle.org/dependency-verification}sha256", {"value": "0" * 64, "origin": "격리된 실패 주입"})
        ET.register_namespace("", ns["g"])
        tree.write(metadata, encoding="utf-8", xml_declaration=True)
        log = ARTIFACTS / "dependency-integrity.log"
        with log.open("w") as output:
            process = subprocess.run(["./gradlew", "writeDependencyInventory", "--console=plain"], cwd=copy, stdout=output, stderr=subprocess.STDOUT, timeout=90)
        result["gradle_exit"] = process.returncode
        text = log.read_text()
        if process.returncode == 0 or "Dependency verification failed" not in text or "spring-security-core" not in text:
            raise RuntimeError("체크섬 불일치가 예상한 검증 오류로 거부되지 않았습니다")
        result["passed"] = True
    result["temporary_copy_removed"] = not Path(directory).exists()
finally:
    (ARTIFACTS / "dependency-integrity.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False, indent=2))
