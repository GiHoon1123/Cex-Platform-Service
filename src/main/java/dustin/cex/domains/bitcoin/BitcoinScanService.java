package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static dustin.cex.domains.bitcoin.BitcoinRpcClient.*;

/** 등록 주소의 한 블록 조회를 끝낸 뒤에만 진행 위치를 확정한다. 적립이나 엔진 호출은 맡지 않는다. */
@Service
public class BitcoinScanService {
    public record Completed(long id,long height,String hash,int received){}
    private record Address(UUID id,long userId,String scriptPubKey,String wallet){}
    private record Head(long id,long height,String hash){}
    private record Snapshot(Head head,List<Address> addresses){}
    private record Receive(UUID addressId,String txid,int vout,java.math.BigDecimal amount){}
    private final BitcoinRpcClient rpc;
    private final BitcoinBlockDiscovery discovery;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate snapshotTx,commitTx;
    public BitcoinScanService(BitcoinRpcClient rpc,BitcoinBlockDiscovery discovery,BitcoinSettings settings,
            JdbcTemplate jdbc,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.rpc=rpc;this.discovery=discovery;this.settings=settings;this.jdbc=jdbc;this.json=json;this.audit=audit;
        snapshotTx=new TransactionTemplate(manager);snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshotTx.setReadOnly(true);
        commitTx=new TransactionTemplate(manager);commitTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Optional<Completed> scanNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("블록 조회는 외부 트랜잭션 없이 실행하세요");
        // 기준 tip을 주소 목록보다 먼저 읽는다. 이후 새로 공개된 주소의 정상 입금은 이 과거 블록에 들어갈 수 없다.
        var initial=rpc.ready().chain();String tip=hash(initial.path("bestblockhash"));long tipHeight=height(initial.path("blocks"));
        var snapshot=snapshotTx.execute(t->new Snapshot(head(),jdbc.query("""
            SELECT id,user_id,script_pub_key,wallet FROM bitcoin_deposit_addresses WHERE network=? ORDER BY id
            """,(row,n)->new Address(row.getObject("id",UUID.class),row.getLong("user_id"),row.getString("script_pub_key"),row.getString("wallet")),settings.network.id)));
        if(snapshot.addresses().stream().anyMatch(a->!a.wallet().equals(settings.wallet)))throw unavailable();
        Head expected=snapshot.head();
        if(expected!=null){
            // 높이만 비교하면 같은 높이의 다른 체인을 놓친다. 재조직 복구 전에는 확정 기록을 덮어쓰지 않고 중단한다.
            if(expected.height()>tipHeight||!hash(rpc.call("getblockhash",List.of(expected.height()))).equals(expected.hash()))throw reorganized();
            if(expected.height()==tipHeight)return Optional.empty();
        }
        if(snapshot.addresses().isEmpty())return Optional.empty();
        long next=expected==null?0:Math.addExact(expected.height(),1);
        BitcoinBlockDiscovery.Block block=null;var receives=new ArrayList<Receive>();var seen=new HashSet<String>();
        for(int offset=0;offset<snapshot.addresses().size();offset+=200){
            // 한 번에 조회하는 수신 조건은 제한하지만 전체 주소를 조용히 자르지 않는다. 어느 페이지라도 실패하면 진행하지 않는다.
            var page=snapshot.addresses().subList(offset,Math.min(offset+200,snapshot.addresses().size()));
            var byScript=new HashMap<String,Address>();for(var address:page)byScript.put(address.scriptPubKey(),address);
            var current=discovery.scan(next,page.stream().map(Address::scriptPubKey).toList());
            if(!current.network().equals(settings.network.id)||current.height()!=next||!current.tipHash().equals(tip)||current.tipHeight()!=tipHeight
                    ||(block!=null&&(!block.hash().equals(current.hash())||!Objects.equals(block.previousHash(),current.previousHash())))
                    ||(expected!=null&&!expected.hash().equals(current.previousHash())))throw unavailable();
            block=current;
            for(var output:current.received()){
                var address=byScript.get(output.scriptPubKey());
                if(address==null||!seen.add(output.txid()+":"+output.vout()))throw unavailable();
                receives.add(new Receive(address.id(),output.txid(),output.vout(),output.amount()));
            }
        }
        // 마지막 페이지 이후의 정규 해시와 tip도 확인한다. 노드 호출 전체는 짧은 DB 확정 구간 밖에 있다.
        if(!hash(rpc.call("getblockhash",List.of(next))).equals(block.hash()))throw unavailable();
        var after=rpc.ready().chain();
        if(!hash(after.path("bestblockhash")).equals(tip)||height(after.path("blocks"))!=tipHeight)throw unavailable();
        String registry=json.writeValueAsString(snapshot.addresses()),outputs=json.writeValueAsString(receives),digest=sha256(registry);
        var verified=block;
        return Optional.of(commitTx.execute(t->{
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-scan-v1:"+settings.network.id+":"+settings.wallet);
            if(!Objects.equals(head(),expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 조회가 먼저 확정되어 다시 실행해야 합니다");
            // 같은 불변 주소 목록은 한 번만 저장한다. 해시가 같아도 원문이 다르면 재사용하지 않는다.
            jdbc.update("""
                INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES(?,?,?,?::jsonb)
                ON CONFLICT(network,wallet,digest) DO NOTHING
                """,settings.network.id,settings.wallet,digest,registry);
            var registries=jdbc.queryForList("SELECT id FROM bitcoin_scan_registries WHERE network=? AND wallet=? AND digest=? AND addresses=?::jsonb",
                settings.network.id,settings.wallet,digest,registry);
            if(registries.size()!=1)throw new IllegalStateException("수신 주소 목록의 원본이 다릅니다");
            long registryId=((Number)registries.getFirst().get("id")).longValue();
            long id=jdbc.queryForObject("""
                INSERT INTO bitcoin_block_scans(network,wallet,previous_scan_id,height,block_hash,parent_hash,tip_hash,tip_height,registry_id,received)
                VALUES(?,?,?,?,?,?,?,?,?,?::jsonb) RETURNING id
                """,Long.class,settings.network.id,settings.wallet,expected==null?null:expected.id(),next,verified.hash(),verified.previousHash(),tip,tipHeight,registryId,outputs);
            // 삽입 트리거는 충돌 갱신보다 먼저 실행된다. 최초 생성과 기존 진행을 명시적으로 나눠 각 DB 계약을 지킨다.
            if(expected==null)jdbc.update("INSERT INTO bitcoin_scan_heads(network,wallet,scan_id) VALUES(?,?,?)",settings.network.id,settings.wallet,id);
            else jdbc.update("UPDATE bitcoin_scan_heads SET scan_id=? WHERE network=? AND wallet=?",id,settings.network.id,settings.wallet);
            audit.record(null,"BTC_BLOCK_SCANNED","bitcoin:"+settings.network.id+":"+settings.wallet,"SUCCESS","SCAN:"+id);
            return new Completed(id,next,verified.hash(),receives.size());
        }));
    }
    private Head head(){
        var rows=jdbc.query("""
            SELECT s.id,s.height,s.block_hash FROM bitcoin_scan_heads h JOIN bitcoin_block_scans s ON s.id=h.scan_id
            WHERE h.network=? AND h.wallet=?
            """,(row,n)->new Head(row.getLong("id"),row.getLong("height"),row.getString("block_hash")),settings.network.id,settings.wallet);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static String hash(tools.jackson.databind.JsonNode node){if(!node.isString()||!node.asString().matches("[0-9a-f]{64}"))throw unavailable();return node.asString();}
    private static long height(tools.jackson.databind.JsonNode node){if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0)throw unavailable();return node.longValue();}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("필수 해시 알고리즘을 사용할 수 없습니다",e);}}
    private static ResponseStatusException reorganized(){return new ResponseStatusException(HttpStatus.CONFLICT,"체인 재조직을 먼저 재검증해야 합니다");}
}
