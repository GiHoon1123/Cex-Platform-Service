"""소유 Core·Rust WAL·Kafka·PostgreSQL을 한 BTC 출금 완료 시험으로 연결한다."""
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import uuid4
import base64,importlib.util,json,os,shutil,socket,subprocess,time,urllib.error,urllib.request,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent.parent
ENGINE=ROOT.parent/'Cex-Engine'
ARTIFACTS=ROOT.parent/'.local'
CORE=Path(os.environ['CEX_BITCOIN_CORE_HOME']).resolve()
DOCKER=shutil.which('docker') or '/usr/local/bin/docker'
run=uuid4().hex[:12];proof=ARTIFACTS/'bitcoin-payout-full-wire'/run;proof.mkdir(parents=True)
spec=importlib.util.spec_from_file_location('cex_isolated_kafka',ENGINE/'scripts/isolated_kafka.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
pg='cex-payout-full-wire-'+run;broker=module.IsolatedKafka(pg+'-kafka');node=None;result={'run_id':run};

def free_port():
    with socket.socket() as value:value.bind(('127.0.0.1',0));return value.getsockname()[1]

with TemporaryDirectory(prefix='cex-payout-full-wire-core-') as folder:
    data=Path(folder);port=free_port();roles={name:json.loads(subprocess.check_output(['python3',str(CORE/'rpcauth.py'),name,'--json'],text=True)) for name in ('signer','broadcaster','confirmer','miner')}
    lines=['regtest=1','server=1','listen=0','networkactive=0','dnsseed=0','discover=0','txindex=1','walletbroadcast=0','addresstype=bech32','changetype=bech32','rpcwhitelistdefault=0']
    for role in roles.values():lines.append('rpcauth='+role['rpcauth'])
    lines += ['rpcwhitelist=signer:getblockchaininfo,getwalletinfo,walletprocesspsbt,decoderawtransaction','rpcwhitelist=broadcaster:getblockchaininfo,getwalletinfo,gettransaction,testmempoolaccept,sendrawtransaction','rpcwhitelist=confirmer:getblockchaininfo,getrawtransaction,getblockheader','rpcwhitelist=miner:generatetoaddress','[regtest]','rpcbind=127.0.0.1','rpcallowip=127.0.0.1','rpcport='+str(port)]
    (data/'bitcoin.conf').write_text('\n'.join(lines)+'\n');(data/'bitcoin.conf').chmod(0o600)
    def rpc(method,params=None,wallet=None):
        cookie=(data/'regtest/.cookie').read_text().strip();url=f'http://127.0.0.1:{port}'+('/wallet/'+wallet if wallet else '/');request=urllib.request.Request(url,json.dumps(dict(jsonrpc='2.0',id='fixture',method=method,params=[] if params is None else params)).encode(),{'Authorization':'Basic '+base64.b64encode(cookie.encode()).decode(),'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(request,timeout=10) as response:body=json.load(response)
        except urllib.error.HTTPError as response:body=json.load(response)
        if 'error' in body:raise RuntimeError('RPC '+method+' code '+str(body['error']['code']))
        return body['result']
    try:
        subprocess.run([DOCKER,'run','--detach','--rm','--name',pg,'--publish','127.0.0.1::5432','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_USER=root','-e','POSTGRES_PASSWORD=1234','-e','POSTGRES_DB=cex','postgres:16'],check=True,stdout=subprocess.DEVNULL)
        deadline=time.monotonic()+45
        while subprocess.run([DOCKER,'exec',pg,'pg_isready','-h','127.0.0.1','-U','root','-d','cex'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
            if time.monotonic()>deadline:raise RuntimeError('격리 PostgreSQL 시작 시간 초과')
            time.sleep(.2)
        database=subprocess.check_output([DOCKER,'port',pg,'5432/tcp'],text=True).strip();broker.start()
        with (proof/'node.log').open('w') as log:node=subprocess.Popen([str(CORE/'bin/bitcoind'),'-datadir='+str(data)],stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+30
        while True:
            try:rpc('getblockchaininfo');break
            except (OSError,RuntimeError):
                if node.poll() is not None or time.monotonic()>deadline:raise RuntimeError('소유 Core 시작 실패')
                time.sleep(.1)
        rpc('createwallet',['keys']);descriptors=[d for d in rpc('listdescriptors',[False],'keys')['descriptors'] if d['active'] and d['desc'].startswith('wpkh(')];assert len(descriptors)==2
        rpc('createwallet',['watch',True,True]);assert all(d['success'] for d in rpc('importdescriptors',[[dict(desc=d['desc'],timestamp='now',active=True,internal=d['internal'],range=[0,1000]) for d in descriptors]],'watch'))
        rpc('createwallet',['funding']);mining=rpc('getnewaddress',[],'funding')
        for _ in range(11):rpc('generatetoaddress',[10,mining])
        source=rpc('getnewaddress',[],'watch');funding=rpc('sendtoaddress',dict(address=source,amount='0.05000000',fee_rate='2',replaceable=False),'funding');raw=rpc('gettransaction',[funding,True],'funding')['hex'];assert rpc('sendrawtransaction',[raw])==funding;rpc('generatetoaddress',[6,mining])
        address=rpc('getnewaddress',[],'funding');script=rpc('getaddressinfo',[address],'funding')['scriptPubKey'];created=rpc('walletcreatefundedpsbt',[[],[{address:'0.01000000'}],0,dict(add_inputs=True,include_unsafe=False,minconf=6,fee_rate='2',change_type='bech32',changePosition=1,lockUnspents=False,replaceable=False,subtractFeeFromOutputs=[]),True],'watch');decoded=rpc('decodepsbt',[created['psbt']],'watch');tx=decoded['tx'];inputs=[]
        for vin in tx['vin']:
            output=rpc('gettxout',[vin['txid'],vin['vout'],True]);info=rpc('getaddressinfo',[output['scriptPubKey']['address']],'watch');inputs.append(dict(txid=vin['txid'],vout=vin['vout'],address=info['address'],script=info['scriptPubKey'],amount=f"{output['value']:.8f}"))
        change=tx['vout'][1];fixture=dict(psbt=created['psbt'],txid=tx['txid'],address=address,script=script,fee=f"{created['fee']:.8f}",changeAddress=change['scriptPubKey']['address'],changeScript=change['scriptPubKey']['hex'],changeAmount=f"{change['value']:.8f}",inputs=inputs)
        with (proof/'engine-build.log').open('w') as log:subprocess.run(['/Users/dustin/.cargo/bin/cargo','build','--locked','--offline','--bin','cex-engine'],cwd=ENGINE,stdout=log,stderr=subprocess.STDOUT,check=True)
        env={k:v for k,v in os.environ.items() if not k.startswith(('CDD_','EDD_','DIDIT_','BITCOIN_','CEX_BITCOIN_','CEX_PAYOUT_','CODEVASP_','VALUATION_','SPRING_MAIL_','CUSTOMER_MAIL_','ENGINE_','DATABASE_','KAFKA_','SPRING_KAFKA_'))}
        env.update(JAVA_HOME='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home',JWT_SECRET='isolated-payout-wire-jwt-secret-at-least-32-bytes',ENGINE_SERVICE_TOKEN='isolated-payout-wire-service-identity-32-bytes',SPRING_KAFKA_BOOTSTRAP_SERVERS=broker.bootstrap,SPRING_KAFKA_LISTENER_AUTO_STARTUP='false',BOT_BOT_COUNT='0',CODEVASP_PROCESSING_ENABLED='false',CODEVASP_PRIVATE_TRANSFER_ENABLED='false',ORDER_DISPATCH_ENABLED='false',BITCOIN_PROCESSING_ENABLED='false',CEX_TEST_DATABASE_URL='jdbc:postgresql://'+database+'/cex',CEX_PAYOUT_WIRE_DATABASE_HOST=database,CEX_PAYOUT_WIRE_KAFKA=broker.bootstrap,CEX_PAYOUT_WIRE_ENGINE_ROOT=str(ENGINE),CEX_PAYOUT_WIRE_LOG_DIR=str(proof),CEX_PAYOUT_WIRE_CORE_URL='http://127.0.0.1:'+str(port),CEX_PAYOUT_WIRE_FIXTURE=json.dumps(fixture),CEX_PAYOUT_WIRE_MINING_ADDRESS=mining)
        for name,role in roles.items():env['CEX_PAYOUT_WIRE_'+name.upper()+'_USER']=role['username'];env['CEX_PAYOUT_WIRE_'+name.upper()+'_PASSWORD']=role['password']
        command=['./gradlew','test','--rerun','--tests','dustin.cex.domains.kyc.BitcoinPayoutFullWireIntegrationTest','bootJar','operatorAdminJar','bitcoinSignerJar','bitcoinBroadcasterJar','bitcoinConfirmerJar','--console=plain'];started=time.time()
        with (proof/'tests.log').open('w') as log:completed=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml') if p.stat().st_mtime>=started];counts={key:sum(int(s.attrib.get(key,0)) for s in suites) for key in ('tests','failures','errors','skipped')};shutil.copytree(ROOT/'build/test-results/test',proof/'xml')
        chain=rpc('getrawtransaction',[fixture['txid'],True]) if completed.returncode==0 else None;result.update(command=command,engine_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ENGINE,text=True).strip(),node_version=rpc('getnetworkinfo')['version'],network='regtest',connections=rpc('getconnectioncount'),txid=fixture['txid'],confirmations=chain['confirmations'] if chain else None,tests=counts,exit=completed.returncode,log=str((proof/'tests.log').relative_to(ARTIFACTS)))
        assert completed.returncode==0 and counts==dict(tests=1,failures=0,errors=0,skipped=0),result
        assert chain is not None and chain['confirmations']==6 and result['connections']==0
    finally:
        if node is not None:
            try:rpc('stop')
            except (OSError,RuntimeError):pass
            try:node.wait(timeout=10)
            except subprocess.TimeoutExpired:node.terminate();node.wait(timeout=10)
            result['node_stopped']=node.poll() is not None
        result['broker_removed']=broker.stop();subprocess.run([DOCKER,'rm','--force',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);result['database_removed']=subprocess.run([DOCKER,'inspect',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
        (proof/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');(ARTIFACTS/'bitcoin-payout-full-wire.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
