package dustin.cex.domains.kyc;

import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 조회 계약용 합성 원본이다. 부모부터 정상 제약으로 추가하며 실제 체인 증거로 사용하지 않는다. */
final class BitcoinDepositFixtures {
    record Seed(UUID id,long owner,String txid,String address,String script){}
    static String hex(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    static Seed seed(JdbcTemplate jdbc,ObjectMapper json,long owner,Instant time){
        String network="bitcoin-regtest";
        // 부모 원본부터 정상 제약 안에서 추가한다. 기존 금융 이력 삭제/불변 트리거 해제를 하지 않는다.
        var addresses=jdbc.queryForList("SELECT id,address,script_pub_key FROM bitcoin_deposit_addresses WHERE user_id=? AND network=?",owner,network);
        if(addresses.isEmpty()){
            jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,?,'cex_watch',?,?)",UUID.randomUUID(),owner,network,"합성조회주소-"+UUID.randomUUID(),"0014"+hex().substring(0,40));
            addresses=jdbc.queryForList("SELECT id,address,script_pub_key FROM bitcoin_deposit_addresses WHERE user_id=? AND network=?",owner,network);
        }
        var address=addresses.getFirst();String txid=hex();UUID receipt=UUID.randomUUID();String received=json.writeValueAsString(List.of(Map.of("addressId",address.get("id"),"txid",txid,"vout",0,"amount",new BigDecimal("0.12345678"))));
        long registry=jdbc.queryForObject("INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES(?,'cex_watch',?,?::jsonb) RETURNING id",Long.class,network,hex(),json.writeValueAsString(List.of(Map.of("id",address.get("id")))));
        String hash=hex();long scan=jdbc.queryForObject("INSERT INTO bitcoin_block_scans(network,wallet,height,block_hash,tip_hash,tip_height,registry_id,received) VALUES(?,'cex_watch',0,?,?,0,?,?::jsonb) RETURNING id",Long.class,network,hash,hash,registry,received);
        jdbc.update("INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id,recorded_at) VALUES(?,?,?,0,?,?,'BTC',0.12345678,?,?)",receipt,network,txid,address.get("id"),owner,scan,Timestamp.from(time));
        return new Seed(receipt,owner,txid,(String)address.get("address"),(String)address.get("script_pub_key"));
    }
    static long observe(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,BitcoinDepositQualificationService qualification,Seed seed,boolean stale){return new TransactionTemplate(manager).execute(t->{
        String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,seed.address(),seed.script(),new BigDecimal("0.12345678"))));
        long observation=jdbc.queryForObject("""
            INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
             VALUES('bitcoin-regtest','cex_watch',?,'CONFIRMED',6,false,?,0,?,5,?::jsonb,clock_timestamp()-CASE WHEN ? THEN interval '3 minutes' ELSE interval '0 seconds' END) RETURNING id
            """,Long.class,seed.txid(),hex(),hex(),outputs,stale);
        jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);
        jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?) ON CONFLICT(network,txid) DO UPDATE SET observation_id=excluded.observation_id",seed.txid(),observation);
        qualification.evaluate(observation);return observation;
    });}
}
