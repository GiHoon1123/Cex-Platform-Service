package dustin.cex.domains.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dustin.cex.domains.ledger.LedgerService.p;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import dustin.cex.domains.order.model.OrderCommand;
import dustin.cex.domains.order.model.entity.OrderSubmission;
import dustin.cex.domains.order.repository.OrderSubmissionRepository;
import dustin.cex.domains.reconciliation.*;

/** 실제 PostgreSQL의 지연 제약·불변 트리거·독립 대사를 실행 소유 스키마에서 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class OrderLedgerTest {
    String schema,engine;JdbcTemplate admin,jdbc;TransactionTemplate tx;LedgerService ledger;
    OrderSubmissionRepository originals;EngineAuditClient source;ReconciliationService reconciliation;
    ObjectMapper mapper=new ObjectMapper();
    BigDecimal n(String value) {return new BigDecimal(value);}
    @BeforeEach void setup(TestInfo test) throws Exception {
        String url=System.getenv("CEX_TEST_DATABASE_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"root","1234"));
        schema="ledger_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"root","1234");
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE user_balances(user_id bigint,mint_address text,available numeric(30,9),locked numeric(30,9),PRIMARY KEY(user_id,mint_address))");
        jdbc.execute("CREATE TABLE orders(id bigint PRIMARY KEY,user_id bigint,status text,filled_amount numeric(30,9),filled_quote_amount numeric(30,9))");
        jdbc.execute("CREATE TABLE engine_event_receipts(event_id text PRIMARY KEY,engine_id text,payload_hash text)");
        jdbc.execute("CREATE TABLE trades(id bigserial PRIMARY KEY,engine_event_id text,buy_order_id bigint,sell_order_id bigint,amount numeric,price numeric)");
        for(String table:List.of("order_submissions","order_dispatches","trade_fees")) jdbc.execute("CREATE TABLE "+table+"(id bigint)");
        // 주문 전용 독립 대사에도 다른 금융 예약의 빈 원본 표를 명시한다. 표 누락을 제품의 폴백으로 숨기지 않는다.
        jdbc.execute("CREATE TABLE bitcoin_withdrawals(id uuid,user_id bigint,asset text,total numeric,operation_key text,quote_hash text)");
        jdbc.execute("CREATE TABLE bitcoin_withdrawal_dispatch_attempts(id uuid)");
        jdbc.execute("CREATE TABLE bitcoin_withdrawal_quotes(id uuid,payload_hash text)");
        jdbc.execute("CREATE TABLE customer_withdrawal_authorizations(withdrawal_id uuid,challenge_id uuid)");
        jdbc.execute("CREATE TABLE customer_withdrawal_challenges(id uuid,consumed boolean)");
        jdbc.execute("CREATE TABLE bitcoin_withdrawal_reservation_events(withdrawal_id uuid,event_type text)");
        jdbc.execute("CREATE TABLE bitcoin_withdrawal_cancellations(withdrawal_id uuid)");
        jdbc.execute("CREATE TABLE bitcoin_withdrawal_release_events(withdrawal_id uuid)");
        ledger=new LedgerService(jdbc);ledger.schema();
        jdbc.execute("INSERT INTO user_balances VALUES(1,'USDT',1000,0),(2,'BTC',1,0)");
        // 최초 반입 전 시나리오는 처음부터 빈 원장으로 시작한다. 확정 원본을 지워 시험 상태를 만들지 않는다.
        if(!test.getTags().contains("without-opening"))tx.executeWithoutResult(status->ledger.initializeOpening());
        originals=mock(OrderSubmissionRepository.class);source=mock(EngineAuditClient.class);engine=UUID.randomUUID().toString();
        when(source.page(anyLong(),any(),any())).thenReturn(page(0,List.of()));
        reconciliation=new ReconciliationService(jdbc,source,originals,manager);
    }
    @AfterEach void cleanup() {if(admin!=null && schema!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    JsonNode page(long through,List<String> events) {
        var page=mapper.createObjectNode();page.put("engine_id",engine);page.put("through",through);page.put("next_after",through);
        var list=page.putArray("events");events.forEach(list::add);return page;
    }
    void reserve() {
        var c=new OrderCommand("buy","limit","BTC","USDT",n("100"),n("2"),null);
        when(originals.findByOrderId(1L)).thenReturn(Optional.of(new OrderSubmission(1L,UUID.randomUUID(),1L,c,n("0.001"))));
        tx.executeWithoutResult(status->{
            jdbc.execute("INSERT INTO orders VALUES(1,1,'pending',0,0)");
            jdbc.execute("UPDATE user_balances SET available=800,locked=200 WHERE user_id=1");
            ledger.record("reserve:1","RESERVE",List.of(p(1,"AVAILABLE","USDT",n("-200")),p(1,"LOCKED","USDT",n("200"))));
        });
    }
    String rejection() {
        return "{\"event_type\":\"order_rejected\",\"engine_id\":\""+engine+"\",\"event_id\":\""+engine+":1:0\",\"order_id\":1}";
    }
    long issues(ReconciliationService.Report report,String category) {return jdbc.queryForObject("SELECT count(*) FROM reconciliation_issues WHERE run_id=? AND category=?",Long.class,report.id(),category);}
    @Test void openingAndReservationMatchLedgerProjectionAndOriginalOrder() {
        reserve();
        jdbc.update("INSERT INTO engine_event_receipts VALUES(?,?,?)",engine+":1:market",engine,"market-only");
        assertEquals("MATCHED",reconciliation.run().status());
        assertEquals(0,jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE asset='USDT'",BigDecimal.class).signum());
    }
    @Test void serviceRejectsUnbalancedPostingWithoutAnyFinancialWrite() {
        assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(status->ledger.record("bad","TEST",List.of(p(1,"AVAILABLE","USDT",n("-1"))))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='bad'",Long.class));
    }
    @Test void databaseRejectsUnbalancedDirectInsertAtCommitAndRollsBackBalance() {
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->{
            jdbc.execute("UPDATE user_balances SET available=999 WHERE user_id=1");
            jdbc.execute("INSERT INTO ledger_transactions(operation_key,kind) VALUES('bad','TEST')");
            jdbc.execute("INSERT INTO ledger_entries(operation_key,owner_id,bucket,asset,delta) VALUES('bad',1,'AVAILABLE','USDT',-1)");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=1",BigDecimal.class).compareTo(n("1000")));
    }
    @Test void customerOverdraftRollsBackBalancedPosting() {
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->ledger.record("overdraft","TEST",List.of(p(1,"AVAILABLE","USDT",n("-1001")),p(0,"FEE","USDT",n("1001"))))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='overdraft'",Long.class));
    }
    @Test void committedJournalCannotBeEditedDeletedOrExtended() {
        assertThrows(RuntimeException.class,()->jdbc.execute("UPDATE ledger_entries SET delta=0"));
        assertThrows(RuntimeException.class,()->jdbc.execute("DELETE FROM ledger_transactions"));
        assertThrows(RuntimeException.class,()->jdbc.execute("INSERT INTO ledger_entries(operation_key,owner_id,bucket,asset,delta) VALUES('opening',1,'LOCKED','USDT',1)"));
        assertEquals("MATCHED",reconciliation.run().status());
    }
    @Test void corruptedProjectionIsDetectedAndNeverRepaired() {
        jdbc.execute("UPDATE user_balances SET available=999 WHERE user_id=1");var report=reconciliation.run();
        assertEquals("MISMATCH",report.status());assertEquals(1,issues(report,"BALANCE_AVAILABLE"));
        assertEquals(0,jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=1",BigDecimal.class).compareTo(n("999")));
    }
    @Test void unreceivedEngineResultIsIncompleteRatherThanMatched() {
        when(source.page(anyLong(),any(),any())).thenReturn(page(1,List.of(rejection())));
        var report=reconciliation.run();assertEquals("INCOMPLETE",report.status());assertEquals(1,issues(report,"EVENT_PENDING"));
    }
    @Test void unavailableSourceRecordsFailedRunWithoutChangingMoney() {
        when(source.page(anyLong(),any(),any())).thenThrow(new IllegalStateException("의도한 조회 실패"));
        var report=reconciliation.run();assertEquals("FAILED",report.status());assertEquals(1,issues(report,"SOURCE_UNAVAILABLE"));
        assertEquals(0,jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=1",BigDecimal.class).compareTo(n("1000")));
    }
    @Test void wrongReceiptAndCoherentlyWrongRefundCannotPassIndependentSourceComparison() throws Exception {
        reserve();String event=rejection();
        tx.executeWithoutResult(status->{
            ledger.record("engine:"+engine+":1:0","REJECTED",List.of(p(1,"AVAILABLE","USDT",n("190")),p(1,"LOCKED","USDT",n("-190"))));
            jdbc.execute("UPDATE user_balances SET available=990,locked=10 WHERE user_id=1");jdbc.execute("UPDATE orders SET status='rejected'");
            jdbc.update("INSERT INTO engine_event_receipts VALUES(?,?,?)",engine+":1:0",engine,"wrong-hash");
        });
        when(source.page(anyLong(),any(),any())).thenReturn(page(1,List.of(event)));
        var report=reconciliation.run();assertEquals("MISMATCH",report.status());
        assertEquals(1,issues(report,"EVENT_PAYLOAD"));assertEquals(1,issues(report,"LEDGER_POSTINGS"));assertEquals(1,issues(report,"RESERVATION"));
        assertEquals(0,issues(report,"BALANCE_AVAILABLE"));
    }
    @Test void changedPageIdentityCannotProduceSuccessfulReport() {
        var first=(tools.jackson.databind.node.ObjectNode)page(2,List.of());first.put("next_after",1);
        var second=(tools.jackson.databind.node.ObjectNode)page(2,List.of());second.put("engine_id",UUID.randomUUID().toString());
        when(source.page(anyLong(),any(),any())).thenReturn(first,second);
        assertEquals("FAILED",reconciliation.run().status());
    }
    @Test void finishedReportAndEvidenceCannotBeRewritten() {
        var report=reconciliation.run();assertEquals("MATCHED",report.status());
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE reconciliation_runs SET status='MISMATCH' WHERE id=?",report.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO reconciliation_issues(run_id,category,reference,expected,actual) VALUES(?,'fake','fake','fake','fake')",report.id()));
    }
    @Test void openingCannotBeRepeatedOrInferredFromExistingOrders() {
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->ledger.initializeOpening()));
    }
    @Test @Tag("without-opening") void missingOpeningNeverAdoptsCurrentBalanceAutomatically() {
        // 이 시험은 준비 단계에서 최초 반입을 하지 않는다. 현재 잔고를 보고 원장을 자동 생성해서는 안 된다.
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->ledger.record("reserve:1","RESERVE",List.of(p(1,"AVAILABLE","USDT",n("-1")),p(1,"LOCKED","USDT",n("1"))))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=1",BigDecimal.class).compareTo(n("1000")));
    }
    @Test @Tag("without-opening") void openingRefusesFinancialHistoryEvenWhenBalancesLookValid() {
        jdbc.execute("INSERT INTO trades(engine_event_id) VALUES('old-result')");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->ledger.initializeOpening()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
    }

    @Test void terminalStateCannotDisagreeWithDurableResult() throws Exception {
        reserve();String event=rejection();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        tx.executeWithoutResult(status->{
            ledger.record("engine:"+engine+":1:0","REJECTED",List.of(p(1,"AVAILABLE","USDT",n("200")),p(1,"LOCKED","USDT",n("-200"))));
            jdbc.execute("UPDATE user_balances SET available=1000,locked=0 WHERE user_id=1");
            jdbc.execute("UPDATE orders SET status='cancelled'");
            jdbc.update("INSERT INTO engine_event_receipts VALUES(?,?,?)",engine+":1:0",engine,hash);
        });
        when(source.page(anyLong(),any(),any())).thenReturn(page(1,List.of(event)));
        var report=reconciliation.run();assertEquals("MISMATCH",report.status());assertEquals(1,issues(report,"EVENT_CONTRACT"));
        assertEquals(0,issues(report,"BALANCE_AVAILABLE"));assertEquals(0,issues(report,"RESERVATION"));
    }

    @Test void committedLedgerCannotBeTruncatedDirectlyOrThroughCascade() {
        long transactions=jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class);
        long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE ledger_entries"));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE ledger_transactions CASCADE"));
        assertEquals(transactions,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));
        assertEquals("MATCHED",reconciliation.run().status());
    }

    @Test void accountConstraintExpansionPreservesCommittedRows() throws Exception {
        var before=jdbc.queryForList("SELECT * FROM ledger_entries ORDER BY id");
        // 이전 제약만 있는 소유 시험 스키마를 구성한다. 금융 행과 불변 트리거는 바꾸지 않는다.
        jdbc.execute("ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entry_bucket_owner");
        jdbc.execute("ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_check CHECK((owner_id>0 AND bucket IN ('AVAILABLE','LOCKED')) OR(owner_id=0 AND bucket IN ('FEE','OPENING')))");
        ledger.schema();assertEquals(before,jdbc.queryForList("SELECT * FROM ledger_entries ORDER BY id"));
        String definition=jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ledger_entry_bucket_owner' AND conrelid='ledger_entries'::regclass",String.class);
        assertTrue(definition.contains("PENDING"));assertTrue(definition.contains("CUSTODY"));assertEquals("MATCHED",reconciliation.run().status());
    }
    @Test void customerCustodyAndSystemPendingAreInvalidAccounts() {
        assertThrows(IllegalArgumentException.class,()->p(1,"CUSTODY","BTC",n("1")));
        assertThrows(IllegalArgumentException.class,()->p(0,"PENDING","BTC",n("1")));
        assertEquals("PENDING",p(1,"PENDING","BTC",n("1")).bucket());assertEquals("CUSTODY",p(0,"CUSTODY","BTC",n("-1")).bucket());
    }

}
