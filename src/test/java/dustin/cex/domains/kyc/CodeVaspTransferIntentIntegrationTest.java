package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.cdd.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 KYC 서명·현재 고객 조건·PG 원장/잠금·암호화·필수 감사로 개인정보 제공 의도의 원본 결합을 확인한다. */
class CodeVaspTransferIntentIntegrationTest extends EddTestSupport {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-transfer-intent-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
        r.add("codevasp.api-url",()->"http://127.0.0.1:1");r.add("codevasp.cipher-url",()->"http://127.0.0.1:2");r.add("codevasp.entity-id",()->"transfer-intent-owner");r.add("codevasp.public-key",()->KEY);
    }
    @MockitoBean CodeVaspDirectoryClient directoryClient;
    @MockitoBean CodeVaspTransactionClient transactions;
    @MockitoBean CodeVaspTransferDataClient transfers;
    @Autowired CodeVaspDepositSearches searches;
    @Autowired CodeVaspDirectoryService directory;
    @Autowired CodeVaspDepositSearchExecution execution;
    @Autowired dustin.cex.domains.security.CustomerFinancialEligibility eligibility;
    @Autowired CddCrypto crypto;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    CodeVaspTransferIntents intents;CodeVaspDepositSearches.Search search;BitcoinDepositFixtures.Seed receipt;
    CodeVaspCounterpartyPolicy policy(){return new CodeVaspCounterpartyPolicy(json.writeValueAsString(List.of(Map.of("entityId","origin","legalName","가상 송신 사업자","reviewReference","LOCAL-ONLY","evidenceSha256","a".repeat(64),"validFrom",Instant.now().minusSeconds(60).toString(),"validUntil",Instant.now().plusSeconds(3600).toString()))));}
    CodeVaspTransferIntents service(CodeVaspCounterpartyPolicy p){return new CodeVaspTransferIntents(searches,execution,directory,p,users,eligibility,crypto,jdbc,json,audit,manager);}
    @BeforeEach void original()throws Exception{
        verify(false);allowed();
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),customer.getId(),"bcrt1q"+BitcoinDepositFixtures.hex().substring(0,38),"0014"+BitcoinDepositFixtures.hex().substring(0,40));
        receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);
        if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();pending.recognize(receipt.id());
        when(directoryClient.read()).thenAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of(new CodeVaspDirectoryClient.Vasp("origin","가상 표시","가상 송신 사업자","KR","CODE",true,List.of(new CodeVaspDirectoryClient.Key(KEY,Instant.now().plusSeconds(3600)))))));directory.refresh();
        search=searches.reserve(receipt.id());new TransactionTemplate(manager).executeWithoutResult(t->{long a=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'SUBMIT',clock_timestamp()-interval '181 seconds') RETURNING id",Long.class,search.id());jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?)",search.id(),a);jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,recorded_at) VALUES(?,'SUBMIT_ACKNOWLEDGED',clock_timestamp()-interval '121 seconds')",a);a=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'POLL',clock_timestamp()-interval '61 seconds') RETURNING id",Long.class,search.id());jdbc.update("UPDATE codevasp_search_heads SET attempt_id=? WHERE search_id=?",a,search.id());String payload=json.writeValueAsString(new CodeVaspTransactionClient.Result(search.id(),CodeVaspTransactionClient.Status.FOUND,"origin",List.of(new CodeVaspTransactionClient.Analysis("origin","FOUND"))));jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash,recorded_at) VALUES(?,'FOUND',?,encode(sha256(convert_to(?,'UTF8')),'hex'),clock_timestamp()-interval '31 seconds')",a,payload,payload);});
        intents=service(policy());clearInvocations(directoryClient,transactions,client,engine,producer);
    }
    @AfterEach void noExternalOrFinancialRelease(){verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,new java.math.BigDecimal("0.12345678").compareTo(jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE owner_id=? AND bucket='PENDING' AND asset='BTC'",java.math.BigDecimal.class,customer.getId())));
        for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action='CODE_TRANSFER_INTENT_RESERVED'"))for(String secret:List.of(declaration(CustomerDeclaration.Pep.NO).fullName(),"1990-01-01",receipt.address()))assertFalse(row.get("detail").toString().contains(secret));}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_intents WHERE search_id=?",Long.class,search.id());}
    @Test void currentVerifiedRecipientAndAllOriginalSourcesAreSealedWithMandatoryAudit(){
        var intent=intents.reserve(search.id());assertEquals(receipt.id(),intent.receiptId());assertEquals(customer.getId(),intent.userId());var row=jdbc.queryForMap("SELECT * FROM codevasp_transfer_intents WHERE id=?",intent.id());
        String plain=new String(crypto.decrypt(customer.getId(),intent.id(),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);var prepared=json.readTree(plain);assertEquals(declaration(CustomerDeclaration.Pep.NO).fullName(),prepared.path("request").path("recipientName").asString());assertEquals("1990-01-01",prepared.path("dateOfBirth").asString());assertEquals(receipt.txid(),prepared.path("request").path("query").path("txid").asString());assertEquals(crypto.fingerprint("codevasp-transfer-intent-v1",plain),row.get("content_mac"));assertFalse(new String((byte[])row.get("ciphertext"),StandardCharsets.UTF_8).contains("가상 심사 고객"));assertFalse(intent.toString().contains("1990-01-01"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='CODE_TRANSFER_INTENT_RESERVED' AND target=?",Long.class,"codevasp-transfer:"+intent.id()));
        assertThrows(IllegalStateException.class,()->crypto.decrypt(customer.getId()+1,intent.id(),(byte[])row.get("ciphertext")));assertThrows(IllegalStateException.class,()->crypto.decrypt(customer.getId(),UUID.randomUUID(),(byte[])row.get("ciphertext")));
    }
    @Test void existingIntentIsHistoricalMetadataAndDoesNotBecomeNewPermission(){var first=intents.reserve(search.id());jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertEquals(first,intents.reserve(search.id()));assertEquals(1,count());}
    @Test void concurrentReservationsKeepOneIdentityAndAudit()throws Exception{var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<CodeVaspTransferIntents.Intent> action=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return intents.reserve(search.id());};var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}assertEquals(1,count());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='CODE_TRANSFER_INTENT_RESERVED' AND target=(SELECT 'codevasp-transfer:'||id::text FROM codevasp_transfer_intents WHERE search_id=?)",Long.class,search.id()));}
    @Test void mandatoryAuditFailureRollsBackEncryptedIntent(){doThrow(new IllegalStateException("격리 의도 감사 실패")).when(audit).record(isNull(),eq("CODE_TRANSFER_INTENT_RESERVED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->intents.reserve(search.id()));assertEquals(0,count());}
    @Test void unapprovedCounterpartyCannotCreateDisclosableIntent(){assertThrows(ResponseStatusException.class,()->service(new CodeVaspCounterpartyPolicy("[]")).reserve(search.id()));assertEquals(0,count());}
    @Test void restrictedCustomerAndOldKycReconciliationCannotPrepare(){jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));jdbc.update("UPDATE users SET trading_restricted=false WHERE id=?",customer.getId());jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=(SELECT active_attempt FROM kyc_profiles WHERE user_id=?)",customer.getId());assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));assertEquals(0,count());}
    @Test void newCustomerDeclarationCannotBorrowOldVerifiedIdentity(){cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));assertEquals(0,count());}
    @Test void newerStaleChainCannotBorrowOriginalPendingDecision(){BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));assertEquals(0,count());}
    @Test void latestFailedSearchCannotBorrowOlderFound(){when(transactions.result(search.id())).thenThrow(new IllegalStateException("격리 새 검색 실패"));execution.refresh(search.id());clearInvocations(transactions);assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));assertEquals(0,count());}
    @Test void ambientTransactionCannotPrepareOrExposeIdentity(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->intents.reserve(search.id())));assertEquals(0,count());}
    @Test void originalIntentCannotBeUpdatedDeletedOrTruncated(){var intent=intents.reserve(search.id());for(String sql:List.of("UPDATE codevasp_transfer_intents SET peer_id='other' WHERE id='"+intent.id()+"'","DELETE FROM codevasp_transfer_intents WHERE id='"+intent.id()+"'","TRUNCATE codevasp_transfer_intents"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,count());}
    @Test void directSqlCannotBindIntentToDifferentCustomer(){var intent=intents.reserve(search.id());var failure=assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_transfer_intents SELECT ?,search_id,?,receipt_id,search_attempt_id,directory_attempt_id,decision_id,observation_id,declaration_id,assessment_id,policy_hash,peer_id,peer_public_key,key_id,ciphertext,content_mac,created_at FROM codevasp_transfer_intents WHERE id=?",UUID.randomUUID(),maker.user().getId(),intent.id()));assertTrue(failure.getMostSpecificCause().getMessage().contains("정보 제공 의도가 현재 입금·검색·수취인 원본과 다릅니다"));assertEquals(1,count());}
    @Test void multiplePositiveOutputsToSameRecipientAddressRequireSeparateReview(){
        new TransactionTemplate(manager).executeWithoutResult(t->{String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new java.math.BigDecimal("0.12345678")),new BitcoinDepositReader.Output(1,receipt.address(),receipt.script(),java.math.BigDecimal.ONE)));
            long o=jdbc.queryForObject("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) VALUES('bitcoin-regtest','cex_watch',?,'CONFIRMED',6,false,?,0,?,5,?::jsonb,clock_timestamp()) RETURNING id",Long.class,receipt.txid(),BitcoinDepositFixtures.hex(),BitcoinDepositFixtures.hex(),outputs);
            jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",o);jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE network='bitcoin-regtest' AND txid=?",o,receipt.txid());qualification.evaluate(o);
        });assertEquals("CHAIN_READY",jdbc.queryForObject("SELECT d.reason FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id WHERE h.receipt_id=?",String.class,receipt.id()));assertThrows(ResponseStatusException.class,()->intents.reserve(search.id()));assertEquals(0,count());
    }

}
