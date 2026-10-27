package dustin.cex.domains.ethereum;

import dustin.cex.domains.auth.repository.UserRepository;import dustin.cex.domains.security.AuditService;import dustin.cex.domains.security.CustomerSessionPolicy;import dustin.cex.shared.http.EngineDepositClient;
import java.math.*;import java.nio.charset.StandardCharsets;import java.time.Instant;import java.util.*;
import org.springframework.context.annotation.DependsOn;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.*;import org.springframework.transaction.support.*;import tools.jackson.databind.JsonNode;
import org.springframework.transaction.annotation.Transactional;

/** 확정 블록을 순서대로 관찰하고 영속 원본을 만든 뒤 별도 단계에서 엔진 저널에 같은 ID를 전달한다. */
@Service @DependsOn("ethereumSchemaInitializer")
public class EthereumDepositService {
    public record Deposit(UUID id,String asset,String network,String txHash,String amount,String state,Instant detectedAt){}
    private record Checkpoint(BigInteger number,String hash){}
    private record Found(UUID id,long user,UUID addressId,String address,String txHash,BigInteger number,String blockHash,String sender,BigDecimal amount){}
    private record Claim(UUID attempt,UUID deposit,long user,BigDecimal amount){}
    private final JdbcTemplate jdbc;private final EthereumSettings settings;private final EthereumJsonRpcClient rpc;private final EngineDepositClient engine;private final UserRepository users;private final CustomerSessionPolicy sessions;private final AuditService audit;private final TransactionTemplate tx;
    public EthereumDepositService(JdbcTemplate jdbc,EthereumSettings settings,EthereumJsonRpcClient rpc,EngineDepositClient engine,UserRepository users,CustomerSessionPolicy sessions,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.settings=settings;this.rpc=rpc;this.engine=engine;this.users=users;this.sessions=sessions;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);}
    public boolean scanNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("ETH 관찰은 외부 트랜잭션 없이 실행하세요");
        Checkpoint before=checkpoint();BigInteger tip=EthereumSchemaInitializer.quantity(rpc.call("eth_blockNumber",List.of()).asString());BigInteger confirmed=tip.subtract(BigInteger.valueOf(settings.confirmations-1L));BigInteger next=before.number.add(BigInteger.ONE);if(next.compareTo(confirmed)>0)return false;
        JsonNode block=rpc.call("eth_getBlockByNumber",List.of(EthereumSchemaInitializer.hex(next),true));String hash=EthereumSchemaInitializer.data(block.path("hash").asString(),64),parent=EthereumSchemaInitializer.data(block.path("parentHash").asString(),64);
        if(!next.equals(EthereumSchemaInitializer.quantity(block.path("number").asString()))||!parent.equals(before.hash))throw new IllegalStateException("이더리움 체인 재구성을 확인해야 합니다");
        Map<String,Map<String,Object>> owned=new HashMap<>();for(var row:jdbc.queryForList("SELECT id,user_id,address FROM ethereum_deposit_addresses WHERE user_id IS NOT NULL"))owned.put((String)row.get("address"),row);
        List<Found> found=new ArrayList<>();for(JsonNode item:block.path("transactions")){
            String to=item.path("to").isString()?item.path("to").asString().toLowerCase(Locale.ROOT):"";var address=owned.get(to);if(address==null)continue;
            BigInteger wei=EthereumSchemaInitializer.quantity(item.path("value").asString());if(wei.signum()==0)continue;BigDecimal amount=new BigDecimal(wei,18);if(amount.precision()>30)throw new IllegalStateException("ETH 입금 금액 저장 범위를 벗어났습니다");
            String txHash=EthereumSchemaInitializer.data(item.path("hash").asString(),64),sender=EthereumSettings.address(item.path("from").asString());var receipt=rpc.call("eth_getTransactionReceipt",List.of(txHash));
            if(!"0x1".equals(receipt.path("status").asString())||!hash.equals(receipt.path("blockHash").asString()))continue;
            UUID id=UUID.nameUUIDFromBytes((settings.chainId+":"+txHash).getBytes(StandardCharsets.UTF_8));found.add(new Found(id,((Number)address.get("user_id")).longValue(),(UUID)address.get("id"),to,txHash,next,hash,sender,amount));
        }
        tx.executeWithoutResult(status->{var locked=jdbc.queryForMap("SELECT block_number,block_hash FROM ethereum_scan_checkpoint WHERE singleton FOR UPDATE");if(new BigInteger(locked.get("block_number").toString()).compareTo(before.number)!=0||!before.hash.equals(locked.get("block_hash")))throw new IllegalStateException("ETH 관찰 위치가 변경됐습니다");
            for(Found deposit:found){
                int inserted=jdbc.update("INSERT INTO ethereum_deposits(id,user_id,address_id,tx_hash,block_number,block_hash,sender,amount,state) VALUES(?,?,?,?,?,?,?,?, 'CONFIRMED') ON CONFLICT(tx_hash) DO NOTHING",deposit.id,deposit.user,deposit.addressId,deposit.txHash,deposit.number,deposit.blockHash,deposit.sender,deposit.amount);
                if(inserted==1){
                    // 현재 공통 원장과 Rust 엔진은 소수점 아홉 자리까지 정확하게 표현한다. 더 작은 단위는 버리거나 반올림하지 않고 영속 보류한다.
                    if(deposit.amount.stripTrailingZeros().scale()>9){
                        jdbc.update("INSERT INTO ethereum_deposit_holds(deposit_id,reason) VALUES(?, 'ENGINE_PRECISION_LIMIT')",deposit.id);
                        audit.record(null,"ETH_DEPOSIT_HELD","ethereum-deposit:"+deposit.id,"SUCCESS","ENGINE_PRECISION_LIMIT");
                    }else{
                        audit.record(null,"ETH_DEPOSIT_CONFIRMED","ethereum-deposit:"+deposit.id,"SUCCESS","BLOCK:"+hash);
                    }
                }
            }
            jdbc.update("UPDATE ethereum_scan_checkpoint SET block_number=?,block_hash=?,updated_at=clock_timestamp() WHERE singleton",next,hash);
        });return true;
    }
    public Optional<EngineDepositClient.Outcome> dispatchNext(){
        Claim claim=tx.execute(status->{var due=jdbc.queryForList("SELECT d.id,d.user_id,d.amount FROM ethereum_deposits d WHERE NOT EXISTS(SELECT 1 FROM ethereum_deposit_holds h WHERE h.deposit_id=d.id) AND NOT EXISTS(SELECT 1 FROM ethereum_credited_deposits c WHERE c.deposit_id=d.id) AND NOT EXISTS(SELECT 1 FROM ethereum_credit_attempts a JOIN ethereum_credit_results r ON r.attempt_id=a.id WHERE a.deposit_id=d.id AND r.outcome IN('DURABLY_CREDITED','DURABLY_REJECTED')) AND NOT EXISTS(SELECT 1 FROM ethereum_credit_attempts a WHERE a.deposit_id=d.id AND a.started_at>clock_timestamp()-interval '30 seconds') ORDER BY d.block_number,d.tx_hash LIMIT 1 FOR UPDATE OF d SKIP LOCKED");if(due.isEmpty())return null;var row=due.getFirst();long user=((Number)row.get("user_id")).longValue();users.lockById(user).orElseThrow();UUID deposit=(UUID)row.get("id"),attempt=UUID.randomUUID();BigDecimal amount=(BigDecimal)row.get("amount");jdbc.update("INSERT INTO ethereum_credit_authorizations(deposit_id,user_id,asset,amount) VALUES(?,?, 'ETH',?) ON CONFLICT DO NOTHING",deposit,user,amount);jdbc.update("INSERT INTO ethereum_credit_attempts(id,deposit_id) VALUES(?,?)",attempt,deposit);audit.record(null,"ETH_CREDIT_STARTED","ethereum-deposit:"+deposit,"SUCCESS","ATTEMPT:"+attempt);return new Claim(attempt,deposit,user,amount);});
        if(claim==null)return Optional.empty();EngineDepositClient.Outcome outcome;try{outcome=engine.credit(new EngineDepositClient.Credit(claim.deposit,claim.user,"ETH",claim.amount));if(outcome==null)outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}catch(RuntimeException uncertain){outcome=EngineDepositClient.Outcome.OUTCOME_UNKNOWN;}var result=outcome;
        tx.executeWithoutResult(status->{users.lockById(claim.user).orElseThrow();jdbc.update("INSERT INTO ethereum_credit_results(attempt_id,outcome) VALUES(?,?)",claim.attempt,result.name());audit.record(null,"ETH_CREDIT_FINISHED","ethereum-deposit:"+claim.deposit,"SUCCESS","OUTCOME:"+result.name());});return Optional.of(result);
    }
    @Transactional(readOnly=true) public List<Deposit> own(long user,UUID session){sessions.require(user,session);return jdbc.query("SELECT d.id,d.tx_hash,d.amount,CASE WHEN c.deposit_id IS NOT NULL THEN 'CREDITED' WHEN h.deposit_id IS NOT NULL THEN 'HELD' WHEN EXISTS(SELECT 1 FROM ethereum_credit_attempts a WHERE a.deposit_id=d.id) THEN 'CREDITING' ELSE d.state END current_state,d.detected_at FROM ethereum_deposits d LEFT JOIN ethereum_credited_deposits c ON c.deposit_id=d.id LEFT JOIN ethereum_deposit_holds h ON h.deposit_id=d.id WHERE d.user_id=? ORDER BY d.detected_at DESC,d.id DESC LIMIT 100",(rs,n)->new Deposit(rs.getObject("id",UUID.class),"ETH","ethereum-"+settings.chainId,rs.getString("tx_hash"),rs.getBigDecimal("amount").toPlainString(),rs.getString("current_state"),rs.getTimestamp("detected_at").toInstant()),user);}
    private Checkpoint checkpoint(){var row=jdbc.queryForMap("SELECT block_number,block_hash FROM ethereum_scan_checkpoint WHERE singleton");return new Checkpoint(new BigInteger(row.get("block_number").toString()),(String)row.get("block_hash"));}
}
