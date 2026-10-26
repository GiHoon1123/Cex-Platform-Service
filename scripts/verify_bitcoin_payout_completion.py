"""메서드별 격리 PostgreSQL에서 체인 확정 뒤 엔진 완료와 정확한 금융 분개를 검증한다."""
import json,os,re,shutil,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
from uuid import uuid4
ROOT=Path(__file__).resolve().parent.parent;ARTIFACTS=ROOT.parent/'.local';run=uuid4().hex[:12];name='cex-bitcoin-completion-'+run;logs=ARTIFACTS/'bitcoin-completion'/run;logs.mkdir(parents=True)
methods=re.findall(r'@Test\s+void\s+(\w+)\(', (ROOT/'src/test/java/dustin/cex/domains/kyc/BitcoinPayoutCompletionIntegrationTest.java').read_text());assert len(methods)==9 and len(set(methods))==9
result=dict(run_id=run,container=name,groups=[],cases=[])
try:
 subprocess.run(['docker','run','--detach','--rm','--name',name,'--publish','127.0.0.1::5432','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_USER=root','-e','POSTGRES_PASSWORD=1234','-e','POSTGRES_DB=postgres','postgres:16'],check=True,stdout=subprocess.DEVNULL)
 deadline=time.monotonic()+45
 while subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','root','-d','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
  if time.monotonic()>deadline:raise RuntimeError('격리 PostgreSQL 시작 시간 초과')
  time.sleep(.2)
 address=subprocess.check_output(['docker','port',name,'5432/tcp'],text=True).strip();env={k:v for k,v in os.environ.items() if not k.startswith(('CDD_','EDD_','DIDIT_','BITCOIN_','CEX_BITCOIN_','CODEVASP_','VALUATION_','SPRING_MAIL_','CUSTOMER_MAIL_'))};env.update(JAVA_HOME='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home',JWT_SECRET='isolated-process-jwt-secret-at-least-32-bytes',ENGINE_SERVICE_TOKEN='isolated-engine-service-token-32-bytes',SPRING_KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:1',SPRING_KAFKA_LISTENER_AUTO_STARTUP='false',BOT_BOT_COUNT='0',CODEVASP_PROCESSING_ENABLED='false',CODEVASP_PRIVATE_TRANSFER_ENABLED='false',ORDER_DISPATCH_ENABLED='false',ENGINE_HTTP_URL='http://127.0.0.1:1',BITCOIN_PROCESSING_ENABLED='false')
 for index,method in enumerate(methods):
  database='completion_case_'+str(index);subprocess.run(['docker','exec',name,'psql','-U','root','-d','postgres','-v','ON_ERROR_STOP=1','-c','CREATE DATABASE '+database],check=True,stdout=subprocess.DEVNULL);env['CEX_TEST_DATABASE_URL']='jdbc:postgresql://'+address+'/'+database;started=time.time()
  with (logs/(method+'.log')).open('w') as log:completed=subprocess.run(['./gradlew','test','--rerun','--tests','dustin.cex.domains.kyc.BitcoinPayoutCompletionIntegrationTest.'+method,'bootJar','operatorAdminJar','bitcoinSignerJar','bitcoinBroadcasterJar','bitcoinConfirmerJar','--console=plain'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
  suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml') if p.stat().st_mtime>=started];counts={k:sum(int(s.attrib.get(k,0)) for s in suites) for k in ['tests','failures','errors','skipped']};group=dict(database=database,method=method,exit=completed.returncode,tests=counts,log=str((logs/(method+'.log')).relative_to(ARTIFACTS)));result['groups'].append(group)
  if completed.returncode or counts!=dict(tests=1,failures=0,errors=0,skipped=0):raise RuntimeError('완료 검증 실패: '+group['log'])
  result['cases'] += [dict(classname=c.attrib['classname'],name=c.attrib['name']) for s in suites for c in s.findall('testcase')];shutil.copytree(ROOT/'build/test-results/test',logs/(method+'-xml'));print(method+': 1 통과',flush=True)
 result['tests']={k:sum(g['tests'][k] for g in result['groups']) for k in ['tests','failures','errors','skipped']};result['exit']=0
finally:
 result['container_removed']=subprocess.run(['docker','rm','--force',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0;(ARTIFACTS/'bitcoin-payout-completion.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
