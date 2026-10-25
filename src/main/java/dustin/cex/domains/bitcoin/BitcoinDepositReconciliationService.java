package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 입금 한 건의 원본/보류 원장과 노드의 새 읽기를 비교한다. 금융 기록을 보정하거나 엔진을 호출하지 않는다. */
@Service
public class BitcoinDepositReconciliationService {
    public enum Status {MATCHED,MISMATCH,INCOMPLETE,HELD,FAILED}
    public record Issue(String code,String expected,String actual){}
    public record Report(UUID id,UUID depositId,Status status,List<Issue> issues){public Report{issues=List.copyOf(issues);}}
    record Entry(long owner,String bucket,String asset,BigDecimal delta){}
    private record Receipt(UUID id,long user,String asset,BigDecimal amount,String network,String wallet,String txid,int vout,String address,String script){}
    private record Snapshot(Receipt receipt,Instant at,String recognizedKey,Long decisionId,String ledgerKind,List<Entry> entries){
        Snapshot{entries=List.copyOf(entries);}
    }
    private record NodeEvidence(String contract,BitcoinDepositReader.Observation metadata,List<BitcoinDepositReader.Output> outputs){}
    private final JdbcTemplate jdbc;
    private final BitcoinDepositReader reader;
    private final BitcoinSettings settings;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate snapshot,write;
    public BitcoinDepositReconciliationService(JdbcTemplate jdbc,BitcoinDepositReader reader,BitcoinSettings settings,
            ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.reader=reader;this.settings=settings;this.json=json;this.audit=audit;
        snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setTimeout(10);
        write=new TransactionTemplate(manager);write.setTimeout(10);
    }
    public Report reconcile(UUID deposit){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 대사는 외부 트랜잭션 없이 실행하세요");
        if(deposit==null)throw new IllegalArgumentException("대사할 입금 원본이 필요합니다");
        // 같은 시점의 플랫폼 원본을 값으로 고정한 다음 DB 연결을 돌려준다. 노드를 기다리며 스냅샷을 붙잡지 않는다.
        Snapshot captured=snapshot.execute(t->capture(deposit));UUID run=UUID.randomUUID();String platform=json.writeValueAsString(captured);
        write.executeWithoutResult(t->{
            // 시작 공개와 금융 판단은 같은 플랫폼 고객 잠금을 따른다. 이 잠금은 아래 노드 조회 전에 해제한다.
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,captured.receipt().user());
            jdbc.update("""
                INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status)
                 VALUES(?,?,?,?,'PENDING_RECEIPT',?,'RUNNING')
                """,run,deposit,captured.receipt().network(),captured.receipt().wallet(),Timestamp.from(captured.at()));
            evidence(run,"PLATFORM",platform);
            audit.record(null,"BTC_DEPOSIT_RECONCILIATION_STARTED","bitcoin-reconciliation:"+run,"SUCCESS","RECEIPT:"+deposit);
        });
        var issues=new ArrayList<Issue>();Receipt r=captured.receipt();
        boolean platformMismatch=platformIssues(captured,issues);Status status;String node=null;
        if(!r.network().equals(settings.network.id)||!r.wallet().equals(settings.wallet)){
            issues.add(new Issue("SOURCE_SCOPE_MISMATCH",r.network()+":"+r.wallet(),settings.network.id+":"+settings.wallet));status=Status.FAILED;
        }else{
            try{
                var current=reader.inspect(r.txid());
                if(!current.network().equals(r.network())||!current.txid().equals(r.txid())){
                    issues.add(new Issue("NODE_IDENTITY_MISMATCH",r.network()+":"+r.txid(),current.network()+":"+current.txid()));status=Status.FAILED;
                }else{
                    var relevant=current.outputs().stream().filter(o->o.vout()==r.vout()).toList();
                    // 다른 고객의 같은 거래 출력을 불필요하게 복사하지 않는다. 거래 메타데이터와 해당 출력만 증거로 남긴다.
                    var metadata=new BitcoinDepositReader.Observation(current.network(),current.txid(),current.status(),current.confirmations(),current.coinbase(),current.walletDebit(),current.blockHash(),current.blockHeight(),current.tipHash(),current.tipHeight(),List.of());
                    node=json.writeValueAsString(new NodeEvidence("BTC_RECEIPT_AUDIT_V1",metadata,relevant));
                    status=compareNode(captured,current,relevant,platformMismatch,issues);
                }
            }catch(RuntimeException failure){
                // 원문 오류/접속 설정을 증거와 운영 로그에 복사하지 않는다. 조회 실패는 정상 대사로 바꾸지 않는다.
                issues.add(new Issue("NODE_UNAVAILABLE","verified fresh node read","unavailable"));status=Status.FAILED;
            }
        }
        Status result=status;String nodeEvidence=node;
        write.executeWithoutResult(t->{
            // 외부 조회가 끝난 뒤에만 다시 잠근다. 오래 걸린 옛 실행의 완료가 새 시작 순번을 바꾸지는 않는다.
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,captured.receipt().user());
            if(nodeEvidence!=null)evidence(run,"NODE",nodeEvidence);
            for(var issue:issues)jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_issues(run_id,code,expected,actual) VALUES(?,?,?,?)",run,issue.code(),issue.expected(),issue.actual());
            jdbc.update("UPDATE bitcoin_deposit_reconciliation_runs SET status=?,completed_at=clock_timestamp() WHERE id=?",result.name(),run);
            audit.record(null,"BTC_DEPOSIT_RECONCILIATION_FINISHED","bitcoin-reconciliation:"+run,result==Status.MATCHED?"SUCCESS":"DENIED","STATUS:"+result+":ISSUES:"+issues.size());
        });
        return new Report(run,deposit,result,issues);
    }
    private Snapshot capture(UUID deposit){
        Instant at=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        var rows=jdbc.query("""
            SELECT r.*,a.address,a.script_pub_key,a.wallet FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id WHERE r.id=?
            """,(row,n)->new Receipt(row.getObject("id",UUID.class),row.getLong("user_id"),row.getString("asset"),row.getBigDecimal("amount"),row.getString("network"),row.getString("wallet"),row.getString("txid"),row.getInt("vout"),row.getString("address"),row.getString("script_pub_key")),deposit);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 원본이 없습니다");
        var recognition=jdbc.queryForList("SELECT operation_key,decision_id FROM bitcoin_pending_deposits WHERE receipt_id=?",deposit);
        String key=recognition.isEmpty()?null:(String)recognition.getFirst().get("operation_key");Long decision=recognition.isEmpty()?null:((Number)recognition.getFirst().get("decision_id")).longValue();
        String operation=key==null?"btc:pending:"+deposit:key;
        var kinds=jdbc.queryForList("SELECT kind FROM ledger_transactions WHERE operation_key=?",String.class,operation);
        var entries=jdbc.query("SELECT owner_id,bucket,asset,delta FROM ledger_entries WHERE operation_key=? ORDER BY owner_id,bucket,asset",
            (row,n)->new Entry(row.getLong("owner_id"),row.getString("bucket"),row.getString("asset"),row.getBigDecimal("delta")),operation);
        return new Snapshot(rows.getFirst(),at,key,decision,kinds.isEmpty()?null:kinds.getFirst(),entries);
    }
    private static boolean platformIssues(Snapshot s,List<Issue> issues){
        if(s.recognizedKey()==null){
            if(s.ledgerKind()!=null||!s.entries().isEmpty())issues.add(new Issue("LEDGER_WITHOUT_RECOGNITION","bound pending recognition","unbound ledger"));
        }else{
            if(!s.recognizedKey().equals("btc:pending:"+s.receipt().id())||!"BTC_DEPOSIT_PENDING".equals(s.ledgerKind()))issues.add(new Issue("LEDGER_OPERATION_MISMATCH","bound BTC pending operation",String.valueOf(s.ledgerKind())));
            if(!matchesEntries(s.receipt().user(),s.receipt().asset(),s.receipt().amount(),s.entries()))issues.add(new Issue("LEDGER_POSTINGS_MISMATCH","exact customer pending and custody pair","different entries"));
        }
        return !issues.isEmpty();
    }
    private static Status compareNode(Snapshot s,BitcoinDepositReader.Observation current,List<BitcoinDepositReader.Output> outputs,boolean platformMismatch,List<Issue> issues){
        Receipt r=s.receipt();
        if(outputs.size()!=1||!outputs.getFirst().address().equals(r.address())||!outputs.getFirst().scriptPubKey().equals(r.script())||outputs.getFirst().amount().compareTo(r.amount())!=0){
            issues.add(new Issue("NODE_OUTPUT_MISMATCH","same vout/address/script/amount","missing, duplicated or different output"));return Status.MISMATCH;
        }
        // 쓰기 경로의 결정 행을 정답으로 삼지 않고 새 노드 읽기의 조건을 직접 비교한다.
        boolean held=false;
        if(current.walletDebit()){issues.add(new Issue("NODE_WALLET_DEBIT","no debit in configured custody wallet","wallet debit"));held=true;}
        if(current.status()!=BitcoinDepositReader.Status.CONFIRMED||current.confirmations()<6||(current.coinbase()&&current.confirmations()<101)){
            issues.add(new Issue("NODE_CHAIN_NOT_READY","ordinary >=6 / coinbase depth >=101",current.status()+":"+current.confirmations()));held=true;
        }
        if(held)return s.recognizedKey()!=null||platformMismatch?Status.MISMATCH:Status.HELD;
        if(platformMismatch)return Status.MISMATCH;
        if(s.recognizedKey()==null){issues.add(new Issue("PENDING_RECOGNITION_MISSING","recognized pending asset","not recognized at snapshot"));return Status.INCOMPLETE;}
        return Status.MATCHED;
    }
    static boolean matchesEntries(long owner,String asset,BigDecimal amount,List<Entry> entries){
        return entries.size()==2&&entries.stream().anyMatch(e->e.owner()==owner&&e.bucket().equals("PENDING")&&e.asset().equals(asset)&&e.delta().compareTo(amount)==0)
            &&entries.stream().anyMatch(e->e.owner()==0&&e.bucket().equals("CUSTODY")&&e.asset().equals(asset)&&e.delta().compareTo(amount.negate())==0);
    }
    private void evidence(UUID run,String kind,String payload){
        jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_evidence(run_id,kind,payload_hash,payload) VALUES(?,?,?,?)",run,kind,hash(payload),payload);
    }
    private static String hash(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("필수 증거 해시를 계산할 수 없습니다",e);}}
}
