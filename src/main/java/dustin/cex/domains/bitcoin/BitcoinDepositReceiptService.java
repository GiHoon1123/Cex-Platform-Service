package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 완료 블록의 양수 수신 출력을 고객 입금 원본으로 식별한다. 확정/성숙도/사용 가능 잔액은 별도 업무다. */
@Service
public class BitcoinDepositReceiptService {
    public record Projected(long scanId,int outputs,int created){}
    private record Output(UUID addressId,long userId,String txid,int vout,BigDecimal amount){}
    private final JdbcTemplate jdbc;
    private final BitcoinSettings settings;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinDepositReceiptService(JdbcTemplate jdbc,BitcoinSettings settings,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Optional<Projected> projectNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 원본 생성은 독립 트랜잭션으로 실행하세요");
        return tx.execute(t->{
            // 조회 진행과 입금 식별 진행을 구분한다. 중간에 프로세스가 종료돼도 미처리 완료 블록부터 다시 읽는다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-receipt-v1:"+settings.network.id+":"+settings.wallet);
            var pending=jdbc.queryForList("""
                SELECT s.id FROM bitcoin_block_scans s WHERE s.network=? AND s.wallet=?
                 AND NOT EXISTS(SELECT 1 FROM bitcoin_scan_receipt_projections p WHERE p.scan_id=s.id) ORDER BY s.id LIMIT 1
                """,settings.network.id,settings.wallet);
            if(pending.isEmpty())return Optional.empty();
            long scan=((Number)pending.getFirst().get("id")).longValue();
            // JSON 숫자는 PostgreSQL numeric과 JDBC BigDecimal로 읽는다. 이진 부동소수점으로 금액을 변환하지 않는다.
            var outputs=jdbc.query("""
                SELECT (o->>'addressId')::uuid AS address_id,a.user_id,o->>'txid' AS txid,
                       (o->>'vout')::integer AS vout,(o->>'amount')::numeric AS amount
                 FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) o
                 LEFT JOIN bitcoin_deposit_addresses a ON a.id=(o->>'addressId')::uuid AND a.network=s.network AND a.wallet=s.wallet
                 WHERE s.id=? AND (o->>'amount')::numeric>0
                """,(row,n)->{
                    var owner=row.getObject("user_id",Long.class);if(owner==null)throw conflict();
                    return new Output(row.getObject("address_id",UUID.class),owner,row.getString("txid"),row.getInt("vout"),row.getBigDecimal("amount"));
                },scan);
            int created=0;
            for(var output:outputs){
                // 재실행마다 UUID를 바꿔 전달하지 않는다. 유일한 온체인 출력 키가 먼저 확정한 입금 ID를 보존한다.
                var inserted=jdbc.queryForList("""
                    INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id)
                     VALUES(?,?,?,?,?,?,'BTC',?,?) ON CONFLICT(network,txid,vout) DO NOTHING RETURNING id
                    """,UUID.randomUUID(),settings.network.id,output.txid(),output.vout(),output.addressId(),output.userId(),output.amount(),scan);
                var receipt=jdbc.queryForMap("SELECT id,address_id,user_id,asset,amount FROM bitcoin_deposit_receipts WHERE network=? AND txid=? AND vout=?",
                    settings.network.id,output.txid(),output.vout());
                if(!receipt.get("address_id").equals(output.addressId())||((Number)receipt.get("user_id")).longValue()!=output.userId()
                        ||!receipt.get("asset").equals("BTC")||((BigDecimal)receipt.get("amount")).compareTo(output.amount())!=0)throw conflict();
                if(!inserted.isEmpty()){
                    created++;
                    audit.record(null,"BTC_DEPOSIT_DISCOVERED","bitcoin-deposit:"+receipt.get("id"),"SUCCESS","SCAN:"+scan);
                }
            }
            // 새 입금과 재확인 작업을 같은 TX에서 남긴다. 작업 전달만 성공하고 입금 원본이 빠지는 틈을 만들지 않는다.
            jdbc.update("""
                INSERT INTO bitcoin_deposit_observation_jobs(network,wallet,txid)
                 SELECT DISTINCT network,?,txid FROM bitcoin_deposit_receipts WHERE first_scan_id=?
                 ON CONFLICT(network,wallet,txid) DO NOTHING
                """,settings.wallet,scan);
            // 수신이 없는 블록도 완료로 남긴다. 출력 원본/모든 입금 ID/완료 표시/감사가 한 번에 확정된다.
            jdbc.update("INSERT INTO bitcoin_scan_receipt_projections(scan_id,output_count,new_count) VALUES(?,?,?)",scan,outputs.size(),created);
            audit.record(null,"BTC_SCAN_RECEIPTS_PROJECTED","bitcoin-scan:"+scan,"SUCCESS","OUTPUTS:"+outputs.size()+":NEW:"+created);
            return Optional.of(new Projected(scan,outputs.size(),created));
        });
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"이미 식별한 입금 출력의 소유자 또는 금액이 다릅니다");}
}
