"""실행 소유 로컬 체인과 제한 RPC 계정으로 주소 파생을 검증하고 노드를 종료한다."""
import argparse
import base64
import json
import os
from pathlib import Path
import socket
import shutil
from uuid import uuid4
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

# 운영 조회 변경은 실제 노드가 필요한 해당 묶음만 반복 검증할 수 있다.
# 기본 실행은 기존 전체 검증이며, 선택 모드도 노드/DB 격리와 금융 원본을 그대로 사용한다.
parser=argparse.ArgumentParser(description="소유 Bitcoin Core와 DB의 전체 또는 운영 입금 조회 검증")
selection=parser.add_mutually_exclusive_group()
selection.add_argument("--deposit-review-only",action="store_true",help="운영 입금 조회의 기존 실제 PG 회귀만 실행")
selection.add_argument("--destinations-only",action="store_true",help="지급 주소 검증과 기존 제한 노드/본문 경계를 실제 노드로 실행")
options=parser.parse_args()
ROOT=Path(__file__).resolve().parent.parent
ARTIFACTS=ROOT.parent/".local"
CORE_HOME=Path(os.environ["CEX_BITCOIN_CORE_HOME"]).resolve()
ARTIFACTS.mkdir(exist_ok=True)
result={"network":"regtest","exit":None,"node_stopped":False}

def port():
    with socket.socket() as bound:
        bound.bind(("127.0.0.1",0))
        return bound.getsockname()[1]

with tempfile.TemporaryDirectory(prefix="cex-bitcoin-") as temporary:
    data=Path(temporary)
    # 공식 배포의 도구로 인증 정보를 생성한다. 비밀번호는 명령행·증적·로그에 쓰지 않는다.
    credentials=json.loads(subprocess.check_output(["python3",str(CORE_HOME/"rpcauth.py"),"cex_addresses","--json"],text=True))
    rpc_port=port()
    config=data/"bitcoin.conf"
    config.write_text("\n".join(["regtest=1","server=1","listen=0","networkactive=0","dnsseed=0","discover=0",
        "walletbroadcast=0","addresstype=bech32","changetype=bech32","printtoconsole=0","rpcauth="+credentials["rpcauth"],"rpcwhitelistdefault=0",
        "rpcwhitelist=cex_addresses:getblockchaininfo,getwalletinfo,getnewaddress,getaddressinfo,gettransaction,getblockheader,getblockhash,getdescriptoractivity,validateaddress,decodescript",
        "[regtest]","rpcbind=127.0.0.1","rpcallowip=127.0.0.1","rpcport="+str(rpc_port)])+"\n")
    config.chmod(0o600)
    daemon=None
    database=None
    docker=shutil.which("docker") or "/usr/local/bin/docker"
    def rpc(method,params=(),wallet=None):
        cookie=(data/"regtest/.cookie").read_text().strip()
        url="http://127.0.0.1:"+str(rpc_port)+(('/wallet/'+wallet) if wallet else "/")
        request=urllib.request.Request(url,json.dumps({"jsonrpc":"2.0","id":"fixture","method":method,"params":params if isinstance(params,dict) else list(params)}).encode(),
            {"Authorization":"Basic "+base64.b64encode(cookie.encode()).decode(),"Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=10) as response:
            body=json.load(response)
        if "error" in body:raise RuntimeError("격리 노드 준비 실패: "+method+" 오류 코드 "+str(body["error"]["code"]))
        return body["result"]
    try:
        with (ARTIFACTS/"bitcoin-node-start.log").open("w") as node_log:
            daemon=subprocess.Popen([str(CORE_HOME/"bin/bitcoind"),"-datadir="+str(data)],stdout=node_log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+30
        while True:
            try:
                rpc("getblockchaininfo")
                break
            except (OSError,RuntimeError):
                if daemon.poll() is not None or time.monotonic()>deadline:raise RuntimeError("격리 노드 시작 실패")
                time.sleep(.1)
        # 로컬 시험용 키다. 공개 디스크립터만 관찰 지갑으로 반입하고 개인키는 Java에 넘기지 않는다.
        rpc("createwallet",["fixture_keys"])
        public=rpc("listdescriptors",[False],"fixture_keys")["descriptors"]
        descriptors=[d for d in public if d["active"] and d["desc"].startswith("wpkh(")]
        assert len(descriptors)==2 and {d["internal"] for d in descriptors}=={False,True}
        rpc("createwallet",["cex_watch",True,True])
        imported=rpc("importdescriptors",[[{"desc":d["desc"],"timestamp":"now","active":True,"internal":d["internal"],"range":[0,1000]} for d in descriptors]],"cex_watch")
        if not all(d["success"] for d in imported):raise RuntimeError("관찰 지갑 디스크립터 반입 실패")
        # 외부 자금 지갑과 수탁 키 지갑을 구분한다. 수탁의 수신/거스름돈 분기 모두 관찰하고 외부 지갑의 키는 반입하지 않는다.
        rpc("createwallet",["funding_keys"])
        mining=rpc("getnewaddress",[],"funding_keys")
        rpc("generatetoaddress",[1,mining])
        info=rpc("getblockchaininfo")
        assert info["chain"]=="regtest" and not info["initialblockdownload"]
        assert rpc("getconnectioncount")==0
        assert not rpc("getwalletinfo",[],"cex_watch")["private_keys_enabled"]
        result["node_version"]=rpc("getnetworkinfo")["version"]
        # 실제 로컬 체인 수신/확정/수수료 대체 충돌/재조직을 만든다. 개인키 RPC는 이 시험 준비 프로세스만 사용한다.
        # 개별 준비 호출도 제한 시간 안에 끝나도록 작은 묶음으로 생성한다. 시간 초과한 생성 명령을 재시도하지 않는다.
        for _ in range(10):rpc("generatetoaddress",[10,mining])
        watch=rpc("getnewaddress",[],"cex_watch")
        def broadcast(txid,wallet="funding_keys"):
            # 자동 전파는 끈 채 시험 프로세스가 서명된 공개 거래만 로컬 mempool에 명시적으로 넣는다.
            raw=rpc("gettransaction",[txid,True],wallet)["hex"]
            assert rpc("sendrawtransaction",[raw])==txid
        def send(amount):
            txid=rpc("sendtoaddress",{"address":watch,"amount":amount,"fee_rate":20,"replaceable":True},"funding_keys")
            broadcast(txid)
            return txid
        confirmed=send("0.12345678");rpc("generatetoaddress",[1,mining])
        second_watch=rpc("getnewaddress",["second_customer","bech32"],"cex_watch")
        second=rpc("sendtoaddress",{"address":second_watch,"amount":"0.2","fee_rate":20},"funding_keys");broadcast(second)
        conflicted=send("0.23456789")
        replacement=rpc("bumpfee",[conflicted,{"fee_rate":40}],"funding_keys")["txid"]
        broadcast(replacement)
        rpc("generatetoaddress",[1,mining])
        internal=rpc("sendtoaddress",{"address":watch,"amount":"0.01","fee_rate":20},"fixture_keys")
        broadcast(internal,"fixture_keys")
        coinbase_block=rpc("generatetoaddress",[1,watch])[0]
        coinbase=rpc("getblock",[coinbase_block,1])["tx"][0]
        reorg=send("0.34567891");orphan_block=rpc("generatetoaddress",[1,mining])[0]
        before_reorg=rpc("gettransaction",[reorg,True,True],"cex_watch")["confirmations"]
        rpc("invalidateblock",[orphan_block])
        after_reorg=rpc("gettransaction",[reorg,True,True],"cex_watch")["confirmations"]
        assert before_reorg>0 and after_reorg==0
        unconfirmed=send("0.45678912")
        deadline=time.monotonic()+10
        while rpc("getwalletinfo",[],"cex_watch")["lastprocessedblock"]["hash"]!=rpc("getbestblockhash"):
            if time.monotonic()>deadline:raise RuntimeError("관찰 지갑의 블록 반영 대기 실패")
            time.sleep(.1)
        result["chain_scenarios"]={"reorg_before_confirmations":before_reorg,"reorg_after_confirmations":after_reorg,
            "confirmed_confirmations":rpc("gettransaction",[confirmed,True,True],"cex_watch")["confirmations"],
            "conflicted_confirmations":rpc("gettransaction",[conflicted,True,True],"cex_watch")["confirmations"],
            "ordinary_wallet_debit": "fee" in rpc("gettransaction",[confirmed,True,True],"cex_watch"),
            "internal_wallet_debit": "fee" in rpc("gettransaction",[internal,True,True],"cex_watch")}
        assert not result["chain_scenarios"]["ordinary_wallet_debit"] and result["chain_scenarios"]["internal_wallet_debit"]

        script=rpc("getaddressinfo",[watch],"cex_watch")["scriptPubKey"]
        empty_address=rpc("getnewaddress",["unused_discovery","bech32"],"cex_watch")
        empty_script=rpc("getaddressinfo",[empty_address],"cex_watch")["scriptPubKey"]
        confirmed_height=rpc("gettransaction",[confirmed,True,True],"cex_watch")["blockheight"]
        # 지급 주소의 인코딩/체크섬/스크립트는 공식 노드가 생성한다. 준비 프로세스만 시험 키를 소유한다.
        destinations=[]
        for address_type,script_type in [("legacy","pubkeyhash"),("p2sh-segwit","scripthash"),("bech32","witness_v0_keyhash"),("bech32m","witness_v1_taproot")]:
            destination=rpc("getnewaddress",["withdrawal_fixture",address_type],"funding_keys")
            validation=rpc("validateaddress",[destination]);decoded=rpc("decodescript",[validation["scriptPubKey"]]);assert validation["isvalid"] and decoded["type"]==script_type
            destinations.append(dict(address=validation["address"],scriptPubKey=validation["scriptPubKey"],scriptType=script_type))
        public_key=rpc("getaddressinfo",[destinations[0]["address"]],"funding_keys")["pubkey"]
        multisig=rpc("createmultisig",[1,[public_key],"bech32"]);validation=rpc("validateaddress",[multisig["address"]]);assert rpc("decodescript",[validation["scriptPubKey"]])["type"]=="witness_v0_scripthash"
        destinations.append(dict(address=validation["address"],scriptPubKey=validation["scriptPubKey"],scriptType="witness_v0_scripthash"))
        unknown=rpc("decodescript",["5220"+"11"*32]);assert unknown["type"]=="witness_unknown" and rpc("validateaddress",[unknown["address"]])["isvalid"]
        result["destination_scenarios"]={"supported_types":[item["scriptType"] for item in destinations],"unsupported_valid_type":"witness_unknown"}
        groups={
            "addresses":["*BitcoinDestinationClientTest","*BitcoinDestinationIntegrationTest","*BitcoinCoreClientTest","*BitcoinCoreIntegrationTest","*LimitedResponseBodyTest",
                "*BitcoinDepositAddressIntegrationTest","*BitcoinDepositReaderTest","*BitcoinDepositReaderIntegrationTest",
                "*BitcoinBlockDiscoveryTest","*BitcoinBlockDiscoveryIntegrationTest"],
            "observations":["*BitcoinObservationIntegrationTest"],
            "scans":["*BitcoinScanIntegrationTest"],
            "receipts":["*BitcoinDepositReceiptIntegrationTest"],
            "worker":["*BitcoinProcessingWorkerTest","*BitcoinProcessingWorkerIntegrationTest"],
            "jobs":["*BitcoinDepositObservationJobsIntegrationTest"],
            "observation-worker":["*BitcoinDepositObservationWorkerTest","*BitcoinDepositObservationWorkerIntegrationTest"],
            "qualification":["*BitcoinDepositQualificationTest","*BitcoinDepositQualificationIntegrationTest"],
            "pending-ledger":["*BitcoinPendingDepositIntegrationTest"],
            "ledger":["*OrderLedgerTest"],
            "pending-worker":["*BitcoinPendingDepositWorkerTest","*BitcoinPendingDepositWorkerIntegrationTest"],
            "pending-selection":["*BitcoinPendingSelectionIntegrationTest"],
            "network-identity":["*BitcoinNetworkIdentityIntegrationTest"],
            "deposit-review":["*BitcoinDepositReviewSourceIntegrationTest"],
            "customer-deposits":["*CustomerBitcoinDepositsIntegrationTest"],
            "deposit-reconciliation":["*BitcoinDepositReconciliationTest","*BitcoinDepositReconciliationIntegrationTest"],
        }
        if options.deposit_review_only:groups={"deposit-review":groups["deposit-review"]}
        if options.destinations_only:groups={"destinations":["*BitcoinDestinationClientTest","*BitcoinDestinationIntegrationTest","*BitcoinCoreClientTest","*BitcoinCoreIntegrationTest","*LimitedResponseBodyTest"]}
        result["selection_mode"]="destinations-only" if options.destinations_only else "deposit-review-only" if options.deposit_review_only else "full"
        result["groups"]=[]
        for group,patterns in groups.items():
            result["exit"]=None
            database="cex-bitcoin-"+uuid4().hex[:12]
            group_result={"group":group,"database":database,"selection":patterns}
            result["groups"].append(group_result)
            try:
                if group=="qualification":
                    rpc("generatetoaddress",[3,mining])
                    deadline=time.monotonic()+10
                    while rpc("getwalletinfo",[],"cex_watch")["lastprocessedblock"]["hash"]!=rpc("getbestblockhash"):
                        if time.monotonic()>deadline:raise RuntimeError("정책 시험의 추가 블록 반영 대기 실패")
                        time.sleep(.1)
                    result["qualification_scenario"]={"tip_height":rpc("getblockcount"),"confirmed_confirmations":rpc("gettransaction",[confirmed,True,True],"cex_watch")["confirmations"]}
                    assert result["qualification_scenario"]["confirmed_confirmations"]==6
                if group=="pending-ledger":
                    rpc("generatetoaddress",[3,mining])
                    deadline=time.monotonic()+10
                    while rpc("getwalletinfo",[],"cex_watch")["lastprocessedblock"]["hash"]!=rpc("getbestblockhash"):
                        if time.monotonic()>deadline:raise RuntimeError("보류 원장 시험의 추가 블록 반영 대기 실패")
                        time.sleep(.1)
                    result["pending_ledger_scenario"]={"tip_height":rpc("getblockcount"),"internal_confirmations":rpc("gettransaction",[internal,True,True],"cex_watch")["confirmations"]}
                    assert result["pending_ledger_scenario"]["internal_confirmations"]>=6
                subprocess.run([docker,"run","--detach","--rm","--name",database,"--publish","127.0.0.1::5432",
                    "--tmpfs","/var/lib/postgresql/data","-e","POSTGRES_USER=root","-e","POSTGRES_PASSWORD=1234",
                    "-e","POSTGRES_DB=cex_test","postgres:16"],check=True,stdout=subprocess.DEVNULL)
                deadline=time.monotonic()+45
                while subprocess.run([docker,"exec",database,"pg_isready","-h","127.0.0.1","-U","root","-d","cex_test"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
                    if time.monotonic()>deadline:raise RuntimeError("격리 PostgreSQL 시작 실패")
                    time.sleep(.2)
                db_address=subprocess.check_output([docker,"port",database,"5432/tcp"],text=True).strip()
                env={k:v for k,v in os.environ.items() if not k.startswith(("BITCOIN_","CEX_BITCOIN_TEST_","CDD_","EDD_","DIDIT_","SPRING_MAIL_","CUSTOMER_MAIL_","CODEVASP_","VALUATION_"))}
                env.update(CEX_TEST_DATABASE_URL="jdbc:postgresql://"+db_address+"/cex_test",
                    BITCOIN_PROCESSING_ENABLED="false",JWT_SECRET="isolated-process-jwt-secret-at-least-32-bytes",ENGINE_SERVICE_TOKEN="isolated-engine-service-token-32-bytes",
                    SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1",SPRING_KAFKA_LISTENER_AUTO_STARTUP="false",BOT_BOT_COUNT="0",
                    CODEVASP_PROCESSING_ENABLED="false",CODEVASP_PRIVATE_TRANSFER_ENABLED="false",VALUATION_UPBIT_ENABLED="false",CUSTOMER_MAIL_DISPATCH_ENABLED="false",ORDER_DISPATCH_ENABLED="false",ENGINE_HTTP_URL="http://127.0.0.1:1")
                env.update(CEX_BITCOIN_TEST_URL="http://127.0.0.1:"+str(rpc_port),CEX_BITCOIN_TEST_USER=credentials["username"],
                    CEX_BITCOIN_TEST_PASSWORD=credentials["password"],
                    CEX_BITCOIN_TEST_CONFIRMED_TX=confirmed,CEX_BITCOIN_TEST_CONFLICTED_TX=conflicted,CEX_BITCOIN_TEST_REPLACEMENT_TX=replacement,
                    CEX_BITCOIN_TEST_UNCONFIRMED_TX=unconfirmed,CEX_BITCOIN_TEST_COINBASE_TX=coinbase,
                    CEX_BITCOIN_TEST_REORG_TX=reorg,CEX_BITCOIN_TEST_INTERNAL_TX=internal,CEX_BITCOIN_TEST_ADDRESS=watch,
                    CEX_BITCOIN_TEST_SCRIPT=script,CEX_BITCOIN_TEST_EMPTY_SCRIPT=empty_script,
                    CEX_BITCOIN_TEST_SECOND_ADDRESS=second_watch,CEX_BITCOIN_TEST_SECOND_SCRIPT=rpc("getaddressinfo",[second_watch],"cex_watch")["scriptPubKey"],CEX_BITCOIN_TEST_SECOND_TX=second,
                    CEX_BITCOIN_TEST_CONFIRMED_HEIGHT=str(confirmed_height),CEX_BITCOIN_TEST_DESTINATIONS=json.dumps(destinations),CEX_BITCOIN_TEST_UNSUPPORTED_DESTINATION=unknown["address"])
                env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
                command=["./gradlew","test"]
                for pattern in patterns:command.extend(["--tests",pattern])
                command.extend(["--rerun-tasks","bootJar","operatorAdminJar","--console=plain"])
                started=time.time()
                log_name="bitcoin.log" if group=="addresses" else "bitcoin-"+group+".log"
                with (ARTIFACTS/log_name).open("w") as log:
                    completed=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
                group_result["exit"]=completed.returncode
                suites=[ET.parse(p).getroot() for p in (ROOT/"build/test-results/test").glob("TEST-*.xml") if p.stat().st_mtime>=started]
                group_result["tests"]={k:sum(int(s.attrib.get(k,0)) for s in suites) for k in ["tests","failures","errors","skipped"]}
                group_result["cases"]=[{"class":s.attrib["name"],"name":t.attrib["name"]} for s in suites for t in s.findall("testcase")]
                result["exit"]=completed.returncode
                if completed.returncode or not suites or any(group_result["tests"][k] for k in ["failures","errors","skipped"]):raise RuntimeError("비트코인 검증 실패: .local/"+log_name+" 확인")
            finally:
                group_result["database_removed"]=subprocess.run([docker,"rm","--force",database],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
                database=None
        result["tests"]={k:sum(g["tests"][k] for g in result["groups"]) for k in ["tests","failures","errors","skipped"]}
        result["cases"]=[case for group in result["groups"] for case in group["cases"]]
        result["database_removed"]=all(g["database_removed"] for g in result["groups"])
        result["exit"]=0
    finally:
        if database is not None:
            result["database_removed"]=subprocess.run([docker,"rm","--force",database],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
        if daemon is not None:
            if daemon.poll() is None:
                daemon.terminate()
                try:daemon.wait(timeout=20)
                except subprocess.TimeoutExpired:daemon.kill();daemon.wait(timeout=5)
            result["node_stopped"]=daemon.poll() is not None
        (ARTIFACTS/"bitcoin.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
print(json.dumps({k:v for k,v in result.items() if k not in ("cases","groups")},ensure_ascii=False,indent=2))
