package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 자산 코드가 같은 시험망/실망 기록을 같은 플랫폼 원장에 합치지 않도록 DB의 네트워크를 고정한다. */
@Component
public class BitcoinNetworkIdentity {
    private final JdbcTemplate jdbc;
    private final BitcoinSettings settings;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private static final String FACTS="""
        SELECT network FROM bitcoin_deposit_addresses UNION ALL SELECT network FROM bitcoin_transaction_observations
         UNION ALL SELECT network FROM bitcoin_scan_registries UNION ALL SELECT network FROM bitcoin_block_scans
         UNION ALL SELECT network FROM bitcoin_deposit_receipts
        """;
    public BitcoinNetworkIdentity(JdbcTemplate jdbc,BitcoinSettings settings,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.audit=audit;tx=new TransactionTemplate(manager);tx.setTimeout(10);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void verifyOrBind(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("네트워크 결합은 독립 초기화로 실행하세요");
        tx.executeWithoutResult(t->{
            // 여러 프로세스의 최초 결합도 같은 짧은 DB 구간에서 순서대로 결정한다. 노드를 기다리지 않는다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('btc-network-identity-v1',0))",row->{});
            var bound=jdbc.queryForList("SELECT network FROM bitcoin_network_identity WHERE singleton=1",String.class);
            if(!bound.isEmpty()&&!bound.getFirst().equals(settings.network.id))throw mismatch();
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ("+FACTS+") facts WHERE network<>?)",Boolean.class,settings.network.id)))throw mismatch();
            if(!bound.isEmpty())return;
            if(!settings.configured()){
                // 노드를 사용하지 않는 빈 플랫폼 DB를 기본 regtest 값으로 먼저 묶지 않는다.
                // 기존 원본이 있으면 실제 운영 설정 없이 네트워크를 추측해 초기화하지 않는다.
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ("+FACTS+") facts)",Boolean.class)))throw mismatch();
                return;
            }
            jdbc.update("INSERT INTO bitcoin_network_identity(singleton,network) VALUES(1,?)",settings.network.id);
            audit.record(null,"BTC_RUNTIME_NETWORK_BOUND","bitcoin-network:"+settings.network.id,"SUCCESS","CONFIGURED_NETWORK_NOT_NODE_ATTESTATION");
        });
    }
    private static IllegalStateException mismatch(){return new IllegalStateException("플랫폼 DB와 비트코인 실행 네트워크가 다릅니다. 네트워크별 DB와 설정을 확인하세요");}
}
