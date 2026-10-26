"""소유 Core31.1 관찰 지갑과 제한 계정으로 실제 방송·중복 복구·권한 거부를 검증한다."""
from pathlib import Path
from tempfile import TemporaryDirectory
import base64,json,os,shutil,socket,subprocess,time,urllib.request,urllib.error,xml.etree.ElementTree as ET
from uuid import uuid4
ROOT=Path(__file__).resolve().parent.parent;core=Path(os.environ['CEX_BITCOIN_CORE_HOME']).resolve();run=uuid4().hex[:12];proof=ROOT.parent/'.local/bitcoin-broadcaster-core'/run;proof.mkdir(parents=True)
credentials=json.loads(subprocess.check_output(['python3',str(core/'rpcauth.py'),'broadcaster','--json'],text=True));result={};process=None
with TemporaryDirectory(prefix='cex-bitcoin-broadcaster-') as folder:
 data=Path(folder)
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 (data/'bitcoin.conf').write_text('\n'.join(['regtest=1','server=1','listen=0','networkactive=0','dnsseed=0','discover=0','walletbroadcast=0','addresstype=bech32','changetype=bech32','rpcauth='+credentials['rpcauth'],'rpcwhitelistdefault=0','rpcwhitelist=broadcaster:getblockchaininfo,getwalletinfo,gettransaction,testmempoolaccept,sendrawtransaction','[regtest]','rpcbind=127.0.0.1','rpcallowip=127.0.0.1','rpcport='+str(port)])+'\n');(data/'bitcoin.conf').chmod(0o600)
 def rpc(method,params=None,wallet=None):
  cookie=(data/'regtest/.cookie').read_text().strip();url=f'http://127.0.0.1:{port}'+('/wallet/'+wallet if wallet else '/');request=urllib.request.Request(url,json.dumps(dict(jsonrpc='2.0',id='fixture',method=method,params=[] if params is None else params)).encode(),{'Authorization':'Basic '+base64.b64encode(cookie.encode()).decode(),'Content-Type':'application/json'})
  try:
   with urllib.request.urlopen(request,timeout=10) as response:body=json.load(response)
  except urllib.error.HTTPError as response:body=json.load(response)
  if 'error' in body:raise RuntimeError('RPC '+method+' code '+str(body['error']['code']))
  return body['result']
 try:
  with (proof/'node.log').open('w') as log:process=subprocess.Popen([str(core/'bin/bitcoind'),'-datadir='+str(data)],stdout=log,stderr=subprocess.STDOUT)
  deadline=time.monotonic()+30
  while True:
   try:rpc('getblockchaininfo');break
   except (OSError,RuntimeError):
    if process.poll() is not None or time.monotonic()>deadline:raise RuntimeError('소유 노드 시작 실패')
    time.sleep(.1)
  rpc('createwallet',['keys']);public=rpc('listdescriptors',[False],'keys')['descriptors'];descs=[d for d in public if d['active'] and d['desc'].startswith('wpkh(')];assert len(descs)==2
  rpc('createwallet',['watch',True,True]);assert all(d['success'] for d in rpc('importdescriptors',[[dict(desc=d['desc'],timestamp='now',active=True,internal=d['internal'],range=[0,1000]) for d in descs]],'watch'))
  rpc('createwallet',['funding']);mining=rpc('getnewaddress',[],'funding')
  for _ in range(11):rpc('generatetoaddress',[10,mining])
  source=rpc('getnewaddress',[],'watch');funded=rpc('sendtoaddress',dict(address=source,amount='0.05000000',fee_rate='2',replaceable=False),'funding');funded_raw=rpc('gettransaction',[funded,True],'funding')['hex'];assert rpc('sendrawtransaction',[funded_raw])==funded;rpc('generatetoaddress',[6,mining])
  destination=rpc('getnewaddress',[],'funding');prepared=rpc('walletcreatefundedpsbt',[[],[{destination:'0.01000000'}],0,dict(add_inputs=True,include_unsafe=False,minconf=6,fee_rate='2',change_type='bech32',changePosition=1,lockUnspents=False,replaceable=False,subtractFeeFromOutputs=[]),True],'watch');signed=rpc('walletprocesspsbt',[prepared['psbt'],True,'ALL',True],'keys');assert signed['complete'];finalized=rpc('finalizepsbt',[signed['psbt'],True]);assert finalized['complete'];decoded=rpc('decoderawtransaction',[finalized['hex']]);fixture=dict(txid=decoded['txid'],raw=finalized['hex'])
  env={k:v for k,v in os.environ.items() if not k.startswith(('BITCOIN_','CEX_BITCOIN_','CDD_','EDD_','DIDIT_'))};env.update(JAVA_HOME='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home',CEX_BITCOIN_BROADCAST_TEST_URL='http://127.0.0.1:'+str(port),CEX_BITCOIN_BROADCAST_TEST_USER=credentials['username'],CEX_BITCOIN_BROADCAST_TEST_PASSWORD=credentials['password'],CEX_BITCOIN_BROADCAST_TEST_FIXTURE=json.dumps(fixture),BOT_BOT_COUNT='0',SPRING_KAFKA_LISTENER_AUTO_STARTUP='false');started=time.time();command=['./gradlew','test','--rerun','--tests','dustin.broadcaster.BitcoinBroadcasterCoreIntegrationTest','bitcoinBroadcasterJar','--console=plain']
  with (proof/'tests.log').open('w') as log:completed=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
  suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml') if p.stat().st_mtime>=started];counts={k:sum(int(s.attrib.get(k,0)) for s in suites) for k in ['tests','failures','errors','skipped']};result=dict(run_id=run,node_version=rpc('getnetworkinfo')['version'],network='regtest',connections=rpc('getconnectioncount'),tests=counts,exit=completed.returncode,command=command,log=str((proof/'tests.log').relative_to(ROOT.parent/'.local')));shutil.copytree(ROOT/'build/test-results/test',proof/'xml');assert completed.returncode==0 and counts==dict(tests=3,failures=0,errors=0,skipped=0),result;assert rpc('getconnectioncount')==0
 finally:
  if process is not None:
   try:rpc('stop')
   except (OSError,RuntimeError):pass
   try:process.wait(timeout=10)
   except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=10)
   result['node_stopped']=process.poll() is not None
  (proof/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');(ROOT.parent/'.local/bitcoin-broadcaster-core.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
