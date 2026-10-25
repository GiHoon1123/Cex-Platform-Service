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
class CodeVaspTransferIntentIntegrationTest extends CodeVaspTransferTestSupport {
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
