"""플랫폼의 다섯 실행 파일과 비웹 권한 경계를 함께 검증한다."""
from pathlib import Path
from uuid import uuid4
import json,subprocess,time,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parent.parent
ARTIFACTS=ROOT.parent/'.local'/'platform-process-boundaries'
run=uuid4().hex[:12]
proof=ARTIFACTS/run
proof.mkdir(parents=True)
command=['./gradlew','test','--rerun','--tests','dustin.runtime.IsolatedProcessBoundaryTest',
         'bootJar','operatorAdminJar','bitcoinSignerJar','bitcoinBroadcasterJar','bitcoinConfirmerJar','--console=plain']
started=time.time()
with (proof/'verification.log').open('w') as log:
    completed=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)

# 이번 실행 이후에 생성된 대상 시험만 집계해 오래된 XML을 성공 근거에 섞지 않는다.
suites=[ET.parse(path).getroot() for path in (ROOT/'build/test-results/test').glob('TEST-*.xml')
        if path.stat().st_mtime>=started]
counts={key:sum(int(suite.attrib.get(key,0)) for suite in suites)
        for key in ('tests','failures','errors','skipped')}
expected={
    'cex-0.0.1-SNAPSHOT.jar':'dustin.cex.CexApplication',
    'cex-0.0.1-SNAPSHOT-operator-admin.jar':'dustin.operator.OperatorAdminApplication',
    'cex-0.0.1-SNAPSHOT-bitcoin-signer.jar':'dustin.signer.BitcoinSignerApplication',
    'cex-0.0.1-SNAPSHOT-bitcoin-broadcaster.jar':'dustin.broadcaster.BitcoinBroadcasterApplication',
    'cex-0.0.1-SNAPSHOT-bitcoin-confirmer.jar':'dustin.confirmer.BitcoinConfirmerApplication',
}
manifests={}
for name,main in expected.items():
    artifact=ROOT/'build/libs'/name
    with zipfile.ZipFile(artifact) as archive:
        manifest=archive.read('META-INF/MANIFEST.MF').decode()
    manifests[name]=next(line.split(': ',1)[1] for line in manifest.splitlines() if line.startswith('Start-Class: '))
    if manifests[name]!=main:raise AssertionError({name:manifests[name],'expected':main})

result={'run_id':run,'command':command,'tests':counts,'exit':completed.returncode,
        'jars':manifests,'log':str((proof/'verification.log').relative_to(ROOT.parent/'.local'))}
(proof/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
(ARTIFACTS/'latest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
if completed.returncode or counts!={'tests':4,'failures':0,'errors':0,'skipped':0}:raise SystemExit(json.dumps(result,ensure_ascii=False))
print(json.dumps(result,ensure_ascii=False,indent=2))
