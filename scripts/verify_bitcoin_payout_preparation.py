"""소유 Core31.1의 미서명 지급 거래와 제한 RPC·정확한 수탁 입력/거스름돈을 검증한다. 개인키를 Java에 전달하지 않는다."""
from pathlib import Path
from tempfile import TemporaryDirectory
import base64,json,subprocess,time,socket,urllib.request,urllib.error,os,shutil
import xml.etree.ElementTree as ET
from uuid import uuid4
ROOT=Path(__file__).resolve().parent.parent
core=Path(os.environ['CEX_BITCOIN_CORE_HOME']).resolve();run_id=uuid4().hex[:12];proof=ROOT.parent/'.local/bitcoin-payout-preparation'/run_id;proof.mkdir(parents=True)
credentials=json.loads(subprocess.check_output(['python3',str(core/'rpcauth.py'),'payout','--json'],text=True))
# 시험 노드/키 지갑은 임시 디렉터리에만 둔다. 기존 개발 노드나 공개 체인에 붙지 않는다.
with TemporaryDirectory(prefix='cex-bitcoin-payout-') as folder:
 data=Path(folder)
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 (data/'bitcoin.conf').write_text('\n'.join(['regtest=1','server=1','listen=0','networkactive=0','dnsseed=0','discover=0','walletbroadcast=0','addresstype=bech32','changetype=bech32','rpcauth='+credentials['rpcauth'],'rpcwhitelistdefault=0','rpcwhitelist=payout:getblockchaininfo,getwalletinfo,estimatesmartfee,walletcreatefundedpsbt,decodepsbt,gettxout,getaddressinfo,listlockunspent','[regtest]','rpcbind=127.0.0.1','rpcallowip=127.0.0.1','rpcport='+str(port)])+'\n')
 (data/'bitcoin.conf').chmod(0o600)
 # 준비 프로세스의 관리자 쿠키는 Java에 주지 않는다. Java에는 공개 관찰/준비 RPC만 허용한 별도 계정을 준다.
 def rpc(method,params=None,wallet=None):
  cookie=(data/'regtest/.cookie').read_text().strip();url=f'http://127.0.0.1:{port}'+('/wallet/'+wallet if wallet else '/')
  req=urllib.request.Request(url,json.dumps(dict(jsonrpc='2.0',id='owned-probe',method=method,params=[] if params is None else params)).encode(),{'Authorization':'Basic '+base64.b64encode(cookie.encode()).decode(),'Content-Type':'application/json'})
  try:
   with urllib.request.urlopen(req,timeout=10) as response:body=json.load(response)
  except urllib.error.HTTPError as response:body=json.load(response)
  if 'error' in body:raise RuntimeError('RPC '+method+' code '+str(body['error']['code']))
  return body['result']
 process=None;result={}
 try:
  with (proof/'node.log').open('w') as log:process=subprocess.Popen([str(core/'bin/bitcoind'),'-datadir='+str(data)],stdout=log,stderr=subprocess.STDOUT)
  deadline=time.monotonic()+30
  while True:
   try:rpc('getblockchaininfo');break
   except (OSError,RuntimeError):
    if process.poll() is not None or time.monotonic()>deadline:raise RuntimeError('소유 노드 시작 실패')
    time.sleep(.1)
  # 개인키는 fixture의 별도 Core 지갑에만 있다. 공개 디스크립터만 관찰 지갑으로 반입한다.
  rpc('createwallet',['keys']);public=rpc('listdescriptors',[False],'keys')['descriptors'];descs=[d for d in public if d['active'] and d['desc'].startswith('wpkh(')];assert len(descs)==2
  rpc('createwallet',['watch',True,True]);assert all(d['success'] for d in rpc('importdescriptors',[[dict(desc=d['desc'],timestamp='now',active=True,internal=d['internal'],range=[0,1000]) for d in descs]],'watch'))
  rpc('createwallet',['funding']);mining=rpc('getnewaddress',[], 'funding')
  for n in range(11):rpc('generatetoaddress',[10,mining])
  address=rpc('getnewaddress',[],'watch');sent=rpc('sendtoaddress',dict(address=address,amount='0.05000000',fee_rate='2',replaceable=False),'funding')
  raw=rpc('gettransaction',[sent,True],'funding')['hex'];assert rpc('sendrawtransaction',[raw])==sent;rpc('generatetoaddress',[6,mining])
  target=rpc('getnewaddress',[],'funding')
  destinations=[]
  for kind in ['legacy','p2sh-segwit','bech32','bech32m']:
   recipient=rpc('getnewaddress',['',kind],'funding');valid=rpc('validateaddress',[recipient]);destinations.append(dict(address=valid['address'],scriptPubKey=valid['scriptPubKey']))
  pubkey=rpc('getaddressinfo',[target],'funding')['pubkey'];multi=rpc('createmultisig',[1,[pubkey],'bech32']);valid=rpc('validateaddress',[multi['address']]);destinations.append(dict(address=valid['address'],scriptPubKey=valid['scriptPubKey']))
  # 이 짧은 합성 체인은 추정 표본이 없다. 준비 시험의2sat/vB와 실제 추정 불가를 구분해 검증한다.
  observed=rpc('estimatesmartfee',[6,'CONSERVATIVE']);assert 'errors' in observed and 'feerate' not in observed
  env={k:v for k,v in os.environ.items() if not k.startswith(('BITCOIN_','CEX_BITCOIN_TEST_','CEX_BITCOIN_PAYOUT_TEST_','CDD_','EDD_','DIDIT_','SPRING_MAIL_','CUSTOMER_MAIL_','CODEVASP_','VALUATION_'))}
  env.update(CEX_BITCOIN_PAYOUT_TEST_URL='http://127.0.0.1:'+str(port),CEX_BITCOIN_PAYOUT_TEST_USER=credentials['username'],CEX_BITCOIN_PAYOUT_TEST_PASSWORD=credentials['password'],CEX_BITCOIN_PAYOUT_TEST_DESTINATIONS=json.dumps(destinations),BOT_BOT_COUNT='0',SPRING_KAFKA_LISTENER_AUTO_STARTUP='false')
  env.setdefault('JAVA_HOME','/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home')
  # 제품의 HTTP 판독과 실제 Core PSBT를 함께 확인한다. 봇/공개 체인/다른 DB는 사용하지 않는다.
  command=['./gradlew','test','--tests','*BitcoinPayoutClientTest','--tests','*BitcoinPayoutCoreIntegrationTest','--tests','*BitcoinCoreClientTest','--tests','*BitcoinDestinationClientTest','--tests','*BitcoinDepositReaderTest','--tests','*LimitedResponseBodyTest','bootJar','operatorAdminJar','--console=plain']
  started=time.time()
  with (proof/'tests.log').open('w') as log:completed=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
  suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml') if p.stat().st_mtime>=started]
  counts={k:sum(int(s.attrib.get(k,0)) for s in suites) for k in ['tests','failures','errors','skipped']}
  cases=[dict(classname=c.attrib['classname'],name=c.attrib['name']) for s in suites for c in s.findall('testcase')]
  result=dict(run_id=run_id,node_version=rpc('getnetworkinfo')['version'],network='regtest',connections=rpc('getconnectioncount'),private_keys_in_watch=rpc('getwalletinfo',[],'watch')['private_keys_enabled'],actual_fee_estimator_unavailable=True,tests=counts,cases=cases,command=command,log=str((proof/'tests.log').relative_to(ROOT.parent/'.local')),exit=completed.returncode)
  shutil.copytree(ROOT/'build/test-results/test',proof/'xml')
  assert completed.returncode==0 and counts['tests']>0 and counts['failures']==counts['errors']==counts['skipped']==0,result
  assert len(set((c['classname'],c['name']) for c in cases))==counts['tests']
  assert not rpc('getwalletinfo',[],'watch')['private_keys_enabled'];assert rpc('getconnectioncount')==0;assert rpc('listlockunspent',[],'watch')==[]
 finally:
  # 성공/실패와 무관하게 소유 노드를 종료한 뒤 임시 개인키 디렉터리를 지운다.
  if process is not None:
   try:rpc('stop')
   except (OSError,RuntimeError):pass
   try:process.wait(timeout=10)
   except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=10)
   result['node_stopped']=process.poll() is not None
  (proof/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
  (ROOT.parent/'.local/bitcoin-payout-preparation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
