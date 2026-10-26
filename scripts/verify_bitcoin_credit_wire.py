"""실제 Rust·HTTP·WAL·Kafka·플랫폼 금융 연결을 시험별 소유 서버에서 검증한다."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from uuid import uuid4

ROOT=Path(__file__).resolve().parent.parent
ENGINE=ROOT.parent/'Cex-Engine'
ARTIFACTS=ROOT.parent/'.local'
# 이미 엔진 검증에 사용하는 소유 브로커 실행기를 재사용한다. 공유 브로커에는 접속하지 않는다.
spec=importlib.util.spec_from_file_location('cex_isolated_kafka',ENGINE/'scripts/isolated_kafka.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
DOCKER=shutil.which('docker') or '/usr/local/bin/docker'
run=uuid4().hex[:12];logs=ARTIFACTS/'bitcoin-credit-wire'/run;logs.mkdir(parents=True)
source=ROOT/'src/test/java/dustin/cex/domains/kyc/BitcoinCreditWireIntegrationTest.java'
methods=re.findall(r'@Test\s+void\s+(\w+)\(',source.read_text());assert len(methods)==7
# 형제 저장소는 현재 로컬 소스의 실행 파일을 새로 빌드한다. 결과를 과거 빌드로 대체하지 않는다.
with (logs/'engine-build.log').open('w') as log:
    subprocess.run(['/Users/dustin/.cargo/bin/cargo','build','--locked','--offline','--bin','cex-engine'],cwd=ENGINE,stdout=log,stderr=subprocess.STDOUT,check=True)
result={'run_id':run,'engine_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ENGINE,text=True).strip(),'groups':[],'cases':[]}
try:
    for index,method in enumerate(methods):
        pg='cex-credit-wire-'+run+'-'+str(index);broker=module.IsolatedKafka(pg+'-kafka');case=logs/method;case.mkdir();group={'method':method,'database_container':pg,'broker_container':broker.name};result['groups'].append(group)
        try:
            subprocess.run([DOCKER,'run','--detach','--rm','--name',pg,'--publish','127.0.0.1::5432','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_USER=root','-e','POSTGRES_PASSWORD=1234','-e','POSTGRES_DB=cex','postgres:16'],check=True,stdout=subprocess.DEVNULL)
            deadline=time.monotonic()+45
            while subprocess.run([DOCKER,'exec',pg,'pg_isready','-h','127.0.0.1','-U','root','-d','cex'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
                if time.monotonic()>deadline:raise RuntimeError('격리 DB 시작 시간 초과')
                time.sleep(.2)
            address=subprocess.check_output([DOCKER,'port',pg,'5432/tcp'],text=True).strip();broker.start()
            # 가상 머신 정지/호스트 시각 차이는 금융 자료의 신선도를 무효화한다. 검사 조건을 완화하지 않고 환경 차이를 증적으로 남긴다.
            db_epoch=float(subprocess.check_output([DOCKER,'exec',pg,'psql','-U','root','-d','cex','-Atc','SELECT extract(epoch from clock_timestamp())'],text=True).strip())
            group['database_host_clock_difference_seconds']=time.time()-db_epoch
            assert abs(group['database_host_clock_difference_seconds'])<2, group['database_host_clock_difference_seconds']
            env={k:v for k,v in os.environ.items() if not k.startswith(('CDD_','EDD_','DIDIT_','SPRING_MAIL_','CUSTOMER_MAIL_','BITCOIN_','CEX_BITCOIN_TEST_','CODEVASP_','VALUATION_','ENGINE_','DATABASE_','KAFKA_','SPRING_KAFKA_','CEX_WIRE_'))}
            env.update(JWT_SECRET='isolated-wire-jwt-secret-at-least-32-bytes',ENGINE_SERVICE_TOKEN='isolated-wire-service-identity-at-least-32-bytes',SPRING_KAFKA_BOOTSTRAP_SERVERS=broker.bootstrap,SPRING_KAFKA_LISTENER_AUTO_STARTUP='false',BOT_BOT_COUNT='0',CODEVASP_PROCESSING_ENABLED='false',CODEVASP_PRIVATE_TRANSFER_ENABLED='false',ORDER_DISPATCH_ENABLED='false',ENGINE_HTTP_URL='http://127.0.0.1:1',BITCOIN_PROCESSING_ENABLED='false',CEX_TEST_DATABASE_URL='jdbc:postgresql://'+address+'/cex',CEX_WIRE_DATABASE_HOST=address,CEX_WIRE_KAFKA=broker.bootstrap,CEX_WIRE_ENGINE_ROOT=str(ENGINE),CEX_WIRE_LOG_DIR=str(case))
            env.setdefault('JAVA_HOME','/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home')
            started=time.time()
            with (case/'gradle.log').open('w') as log:
                execution=subprocess.run(['./gradlew','test','--tests','dustin.cex.domains.kyc.BitcoinCreditWireIntegrationTest.'+method,'--rerun-tasks','bootJar','operatorAdminJar','--console=plain'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
            group['exit']=execution.returncode
            shutil.copytree(ROOT/'build/test-results/test',case/'xml')
            if execution.returncode:raise RuntimeError('실제 입금 연결 검증 실패: '+str(case/'gradle.log'))
            suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml') if p.stat().st_mtime>=started]
            counts={key:sum(int(s.attrib.get(key,0)) for s in suites) for key in ['tests','failures','errors','skipped']};assert counts==dict(tests=1,failures=0,errors=0,skipped=0),counts
            group['tests']=counts;cases=[{'class':c.attrib['classname'],'name':c.attrib['name']} for s in suites for c in s.findall('testcase')];assert len(cases)==1 and cases[0]['name']==method+'()';result['cases'].extend(cases);print(method+': 실제 연결 1 통과',flush=True)
        finally:
            group['broker_removed']=broker.stop()
            subprocess.run([DOCKER,'rm','--force',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            group['database_removed']=subprocess.run([DOCKER,'inspect',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
    result['tests']={key:sum(g['tests'][key] for g in result['groups']) for key in ['tests','failures','errors','skipped']};result['exit']=0
finally:
    (ARTIFACTS/'bitcoin-credit-wire.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
