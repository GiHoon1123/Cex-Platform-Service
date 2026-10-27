package dustin.cex.domains.ethereum;

import jakarta.annotation.PostConstruct;import java.math.BigInteger;import java.nio.charset.StandardCharsets;import java.util.*;
import org.springframework.context.annotation.DependsOn;import org.springframework.core.io.ClassPathResource;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;

/** 공통 금융 표 뒤에 ETH 원본을 만들고 관찰 노드의 체인 정체성을 처음부터 고정한다. */
@Component
@DependsOn({"kycSchemaInitializer","ledgerService","securitySchemaInitializer"})
public class EthereumSchemaInitializer {
    private final JdbcTemplate jdbc;private final EthereumSettings settings;private final EthereumJsonRpcClient rpc;
    public EthereumSchemaInitializer(JdbcTemplate jdbc,EthereumSettings settings,EthereumJsonRpcClient rpc){this.jdbc=jdbc;this.settings=settings;this.rpc=rpc;}
    @PostConstruct void initialize()throws Exception{
        jdbc.execute(new ClassPathResource("ethereum/schema.sql").getContentAsString(StandardCharsets.UTF_8));
        if(!settings.configured())return;
        BigInteger actual=quantity(rpc.call("eth_chainId",List.of()).asString());
        var genesis=rpc.call("eth_getBlockByNumber",List.of("0x0",false));String hash=data(genesis.path("hash").asString(),64);
        if(!actual.equals(settings.chainId))throw new IllegalStateException("설정한 이더리움 체인과 노드가 다릅니다");
        var bound=jdbc.queryForList("SELECT chain_id,genesis_hash FROM ethereum_network_identity WHERE singleton");
        if(bound.isEmpty())jdbc.update("INSERT INTO ethereum_network_identity(singleton,chain_id,genesis_hash) VALUES(true,?,?)",actual,hash);
        else if(new BigInteger(bound.getFirst().get("chain_id").toString()).compareTo(actual)!=0||!hash.equals(bound.getFirst().get("genesis_hash")))throw new IllegalStateException("저장된 이더리움 체인 정체성과 노드가 다릅니다");
        if(jdbc.queryForObject("SELECT count(*) FROM ethereum_scan_checkpoint",Long.class)==0){var block=rpc.call("eth_getBlockByNumber",List.of(hex(settings.startBlock),false));jdbc.update("INSERT INTO ethereum_scan_checkpoint(singleton,block_number,block_hash) VALUES(true,?,?)",settings.startBlock,data(block.path("hash").asString(),64));}
        for(String address:settings.addressPool)jdbc.update("INSERT INTO ethereum_deposit_addresses(id,address,network) VALUES(?,?,?) ON CONFLICT(address) DO NOTHING",UUID.nameUUIDFromBytes((actual+":"+address).getBytes(StandardCharsets.UTF_8)),address,"ethereum-"+actual);
    }
    static BigInteger quantity(String value){if(value==null||!value.matches("0x(?:0|[1-9a-f][0-9a-f]*)"))throw new IllegalArgumentException("이더리움 수량 형식을 확인하세요");return new BigInteger(value.substring(2),16);}
    static String hex(BigInteger value){return "0x"+value.toString(16);}
    static String data(String value,int digits){if(value==null||!value.matches("0x[0-9a-f]{"+digits+"}"))throw new IllegalArgumentException("이더리움 데이터 형식을 확인하세요");return value;}
}
