"""실행 소유 로컬 체인과 제한 RPC 계정으로 주소 파생을 검증하고 노드를 종료한다."""
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent.parent
ARTIFACTS=ROOT.parent/".local"
HOME=Path(os.environ["CEX_BITCOIN_CORE_HOME"]).resolve()
ARTIFACTS.mkdir(exist_ok=True)
result={"network":"regtest","exit":None,"node_stopped":False}

def port():
    with socket.socket() as bound:
        bound.bind(("127.0.0.1",0))
        return bound.getsockname()[1]

with tempfile.TemporaryDirectory(prefix="cex-bitcoin-") as temporary:
    data=Path(temporary)
    # 공식 배포의 도구로 인증 정보를 생성한다. 비밀번호는 명령행·증적·로그에 쓰지 않는다.
    credentials=json.loads(subprocess.check_output(["python3",str(HOME/"rpcauth.py"),"cex_addresses","--json"],text=True))
    rpc_port=port()
    config=data/"bitcoin.conf"
    config.write_text("\n".join(["regtest=1","server=1","listen=0","networkactive=0","dnsseed=0","discover=0",
        "walletbroadcast=0","printtoconsole=0","rpcauth="+credentials["rpcauth"],"rpcwhitelistdefault=0",
        "rpcwhitelist=cex_addresses:getblockchaininfo,getwalletinfo,getnewaddress,getaddressinfo",
        "[regtest]","rpcbind=127.0.0.1","rpcallowip=127.0.0.1","rpcport="+str(rpc_port)])+"\n")
    config.chmod(0o600)
    daemon=None
    def rpc(method,params=(),wallet=None):
        cookie=(data/"regtest/.cookie").read_text().strip()
        url="http://127.0.0.1:"+str(rpc_port)+(('/wallet/'+wallet) if wallet else "/")
        request=urllib.request.Request(url,json.dumps({"jsonrpc":"2.0","id":"fixture","method":method,"params":list(params)}).encode(),
            {"Authorization":"Basic "+base64.b64encode(cookie.encode()).decode(),"Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=10) as response:
            body=json.load(response)
        if "error" in body:raise RuntimeError("격리 노드 준비 실패: "+method)
        return body["result"]
    try:
        with (ARTIFACTS/"bitcoin-node-start.log").open("w") as node_log:
            daemon=subprocess.Popen([str(HOME/"bin/bitcoind"),"-datadir="+str(data)],stdout=node_log,stderr=subprocess.STDOUT)
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
        descriptor=next(d for d in public if d["active"] and not d["internal"] and d["desc"].startswith("wpkh("))
        rpc("createwallet",["cex_watch",True,True])
        imported=rpc("importdescriptors",[[{"desc":descriptor["desc"],"timestamp":"now","active":True,"internal":False,"range":[0,1000]}]],"cex_watch")
        if not imported[0]["success"]:raise RuntimeError("관찰 지갑 디스크립터 반입 실패")
        mining=rpc("getnewaddress",[],"fixture_keys")
        rpc("generatetoaddress",[1,mining])
        info=rpc("getblockchaininfo")
        assert info["chain"]=="regtest" and not info["initialblockdownload"]
        assert rpc("getconnectioncount")==0
        assert not rpc("getwalletinfo",[],"cex_watch")["private_keys_enabled"]
        result["node_version"]=rpc("getnetworkinfo")["version"]
        env={k:v for k,v in os.environ.items() if not k.startswith(("BITCOIN_","CEX_BITCOIN_TEST_"))}
        env.update(CEX_BITCOIN_TEST_URL="http://127.0.0.1:"+str(rpc_port),CEX_BITCOIN_TEST_USER=credentials["username"],
            CEX_BITCOIN_TEST_PASSWORD=credentials["password"])
        env.setdefault("JAVA_HOME","/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
        started=time.time()
        with (ARTIFACTS/"bitcoin.log").open("w") as log:
            completed=subprocess.run(["./gradlew","test","--tests","*BitcoinCoreClientTest","--tests","*BitcoinCoreIntegrationTest",
                "--tests","*LimitedResponseBodyTest","--rerun-tasks","bootJar","operatorAdminJar","--console=plain"],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        result["exit"]=completed.returncode
        suites=[ET.parse(p).getroot() for p in (ROOT/"build/test-results/test").glob("TEST-*.xml") if p.stat().st_mtime>=started]
        result["tests"]={k:sum(int(s.attrib.get(k,0)) for s in suites) for k in ["tests","failures","errors","skipped"]}
        result["cases"]=[{"class":s.attrib["name"],"name":t.attrib["name"]} for s in suites for t in s.findall("testcase")]
        if completed.returncode or not suites or any(result["tests"][k] for k in ["failures","errors","skipped"]):raise RuntimeError("비트코인 검증 실패: .local/bitcoin.log 확인")
    finally:
        if daemon is not None:
            if daemon.poll() is None:
                daemon.terminate()
                try:daemon.wait(timeout=20)
                except subprocess.TimeoutExpired:daemon.kill();daemon.wait(timeout=5)
            result["node_stopped"]=daemon.poll() is not None
        (ARTIFACTS/"bitcoin.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
print(json.dumps({k:v for k,v in result.items() if k!="cases"},ensure_ascii=False,indent=2))
