package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessException;

/** 실제 DB·암호화·감사를 사용하고 외부 정보 교환만 대역으로 분리한다. */
class CodeVaspTransferExecutionIntegrationTest extends CodeVaspTransferTestSupport {
    CodeVaspTransferIntents.Intent intent;CodeVaspTransferExecution exchange;
    @BeforeEach void prepared(){intent=intents.reserve(search.id());exchange=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments);}
    CodeVaspTransferDataClient.Result normal(){String name=declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName();var person=new CodeVaspTransferDataClient.Person(name,null,LocalDate.of(1990,1,1),List.of(receipt.address()));return new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),person,person,"b".repeat(64)));}
    void noExtraIo(){clearInvocations(transfers);}
    @Test void committedStartAndFreshBasisPrecedeIoAndReplyIsSealed(){
        when(transfers.exchange(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals("RUNNING",exchange.current(intent.id()).status());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_attempts WHERE intent_id=?",Long.class,intent.id()));return normal();});
        var state=exchange.execute(intent.id());assertEquals("DATA_RECEIVED",state.status());var row=jdbc.queryForMap("SELECT * FROM codevasp_transfer_results WHERE attempt_id=?",state.attemptId());String plain=new String(crypto.decrypt(customer.getId(),state.attemptId(),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);assertEquals(crypto.fingerprint("codevasp-transfer-result-v1",plain),row.get("content_mac"));assertEquals(search.id().toString(),json.readTree(plain).path("data").path("transferId").asString());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();
    }
    @Test void startAuditFailureRollsBackBeforeAnyDisclosure(){doThrow(new IllegalStateException("격리 시작 감사 장애")).when(audit).record(isNull(),eq("CODE_TRANSFER_STARTED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->exchange.execute(intent.id()));assertEquals("UNSTARTED",exchange.current(intent.id()).status());org.mockito.Mockito.verifyNoInteractions(transfers);}
    @Test void finishAuditFailureKeepsUnresolvedStartAndNeverBlindlyResends(){when(transfers.exchange(any())).thenReturn(normal());doThrow(new IllegalStateException("격리 완료 감사 장애")).when(audit).record(isNull(),eq("CODE_TRANSFER_FINISHED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->exchange.execute(intent.id()));assertEquals("RUNNING",exchange.current(intent.id()).status());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();}
    @Test void unknownOutcomeCannotBecomeAutomaticRetry(){when(transfers.exchange(any())).thenThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN));assertEquals("OUTCOME_UNKNOWN",exchange.execute(intent.id()).status());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();}
    @Test void notSentMustWaitBeforeRetryAndCurrentSourcesAreRechecked(){when(transfers.exchange(any())).thenThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT));assertEquals("NOT_SENT",exchange.execute(intent.id()).status());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();}
    @Test void changedCustomerCannotUseHistoricalIntentAsPermission(){jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));assertEquals("UNSTARTED",exchange.current(intent.id()).status());org.mockito.Mockito.verifyNoInteractions(transfers);}
    @Test void changedCurrentDirectoryFailsBeforeAnyDisclosure(){when(directoryClient.read()).thenThrow(new IllegalStateException("격리 최신 목록 실패"));directory.refresh();clearInvocations(directoryClient);assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verifyNoInteractions(transfers);}
    @Test void financialRestrictionDuringIoCannotEraseHistoricalReply(){when(transfers.exchange(any())).thenAnswer(call->{jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());return normal();});assertEquals("DATA_RECEIVED",exchange.execute(intent.id()).status());org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();}
    @Test void independentCallsCannotOverlapOneIntent()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(transfers.exchange(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return normal();});
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->exchange.execute(intent.id()));assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));release.countDown();assertEquals("DATA_RECEIVED",a.get(10,TimeUnit.SECONDS).status());}finally{release.countDown();}org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();
    }
    @Test void unboundReplyIsUnknownAndNotAReceivedFinancialFact(){var wrong=normal().data();when(transfers.exchange(any())).thenReturn(new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(UUID.randomUUID(),wrong.originEntityId(),wrong.amount(),wrong.originator(),wrong.beneficiary(),wrong.ciphertextHash())));assertEquals("OUTCOME_UNKNOWN",exchange.execute(intent.id()).status());assertNull(jdbc.queryForObject("SELECT ciphertext FROM codevasp_transfer_results WHERE attempt_id=?",byte[].class,exchange.current(intent.id()).attemptId()));noExtraIo();}
    @Test void ambientTransactionCannotStartExternalExchange(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->exchange.execute(intent.id())));org.mockito.Mockito.verifyNoInteractions(transfers);}
    @Test void finishedStartAndResultCannotBeMutatedOrHeadRewound(){when(transfers.exchange(any())).thenReturn(normal());exchange.execute(intent.id());for(String table:List.of("codevasp_transfer_attempts","codevasp_transfer_results"))for(String action:List.of("DELETE FROM ","TRUNCATE "))assertThrows(DataAccessException.class,()->jdbc.execute(action+table));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE codevasp_transfer_heads SET attempt_id=attempt_id WHERE intent_id=?",intent.id()));noExtraIo();}
    @Test void documentedRemoteErrorIsSealedButNeverAutomaticallyResubmitted(){when(transfers.exchange(any())).thenReturn(new CodeVaspTransferDataClient.Result("ERROR","NOT_FOUND_TXID",null));var state=exchange.execute(intent.id());assertEquals("REMOTE_ERROR",state.status());assertEquals("NOT_FOUND_TXID",state.reason());assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();}
    @Test void changedKeyAndNewCurrentObservationsAreExecutionBasisWithoutRewritingIntent(){
        String next=Base64.getEncoder().encodeToString("k".repeat(32).getBytes(StandardCharsets.US_ASCII));when(directoryClient.read()).thenAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of(new CodeVaspDirectoryClient.Vasp("origin","가상 표시","가상 송신 사업자","KR","CODE",true,List.of(new CodeVaspDirectoryClient.Key(next,Instant.now().plusSeconds(3600)))))));directory.refresh();clearInvocations(directoryClient);
        dustin.cex.domains.kyc.BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);
        when(transfers.exchange(any())).thenAnswer(call->{CodeVaspTransferDataClient.Request r=call.getArgument(0);assertEquals(next,r.key().publicKey());return normal();});var state=exchange.execute(intent.id());assertEquals("DATA_RECEIVED",state.status());assertEquals(KEY,jdbc.queryForObject("SELECT peer_public_key FROM codevasp_transfer_intents WHERE id=?",String.class,intent.id()));assertEquals(next,jdbc.queryForObject("SELECT peer_public_key FROM codevasp_transfer_attempts WHERE id=?",String.class,state.attemptId()));noExtraIo();
    }
    @Test void staleChainAfterReservationCannotUseHistoricalIntent(){BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);assertThrows(ResponseStatusException.class,()->exchange.execute(intent.id()));assertEquals("UNSTARTED",exchange.current(intent.id()).status());org.mockito.Mockito.verifyNoInteractions(transfers);}
    @Test void sqlNullReasonCannotBypassOutcomeConstraints(){when(transfers.exchange(any())).thenAnswer(call->{UUID attempt=exchange.current(intent.id()).attemptId();assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_transfer_results(attempt_id,status) VALUES(?,'OUTCOME_UNKNOWN')",attempt));assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_transfer_results(attempt_id,status,key_id,ciphertext,content_mac) VALUES(?,'REMOTE_ERROR',?,?,?)",attempt,crypto.keyId(),new byte[28],"a".repeat(64)));return normal();});assertEquals("DATA_RECEIVED",exchange.execute(intent.id()).status());noExtraIo();}
    @Test void retryAfterProvenNonSendCreatesNewAttemptWithSameTransferIdentity()throws Exception{
        when(transfers.exchange(any())).thenThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT));var first=exchange.execute(intent.id());assertEquals("NOT_SENT",first.status());
        // 실제 DB 시각으로 재시도 간격을 검증한다. 불변 이력 수정·검사 트리거 해제·제품 간격 축소로 시간을 가장하지 않는다.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(!jdbc.queryForObject("SELECT recorded_at<=clock_timestamp()-interval '30 seconds' FROM codevasp_transfer_results WHERE attempt_id=?",Boolean.class,first.attemptId())){assertTrue(System.nanoTime()<deadline);Thread.sleep(100);}
        doReturn(normal()).when(transfers).exchange(any());var second=exchange.execute(intent.id());assertEquals("DATA_RECEIVED",second.status());assertNotEquals(first.attemptId(),second.attemptId());assertEquals("NOT_SENT",jdbc.queryForObject("SELECT status FROM codevasp_transfer_results WHERE attempt_id=?",String.class,first.attemptId()));org.mockito.Mockito.verify(transfers,times(2)).exchange(argThat(r->search.id().equals(r.query().requestId())));noExtraIo();
    }

    @Test void historicalIdentityIsNotOpenedBeforeCurrentCustomerEligibility(){
        var guarded=spy(crypto);var guardedIntents=new CodeVaspTransferIntents(searches,execution,directory,policy(),users,eligibility,guarded,jdbc,json,audit,manager,purposes);var guardedExecution=new CodeVaspTransferExecution(guardedIntents,transfers,guarded,jdbc,json,audit,manager,assessments);
        jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertThrows(ResponseStatusException.class,()->guardedExecution.execute(intent.id()));org.mockito.Mockito.verify(guarded,never()).decrypt(anyLong(),any(),any());org.mockito.Mockito.verifyNoInteractions(transfers);
    }

    @Test void changedConnectionAuthorityCannotBypassUnknownTransferForSameReceipt(){
        when(transfers.exchange(any())).thenThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN));assertEquals("OUTCOME_UNKNOWN",exchange.execute(intent.id()).status());
        var otherDirectory=new CodeVaspDirectoryService(new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2","other-authority",KEY),directoryClient,jdbc,json,audit,manager);otherDirectory.refresh();clearInvocations(directoryClient);
        var otherSearches=new CodeVaspDepositSearches(otherDirectory,jdbc,audit,manager);var otherSearch=otherSearches.reserve(receipt.id());assertNotEquals(search.id(),otherSearch.id());found(otherSearch);
        var otherExecution=new CodeVaspDepositSearchExecution(otherSearches,transactions,jdbc,audit,json,manager);var otherIntents=new CodeVaspTransferIntents(otherSearches,otherExecution,otherDirectory,policy(),users,eligibility,crypto,jdbc,json,audit,manager,purposes);var otherIntent=otherIntents.reserve(otherSearch.id());var otherExchange=new CodeVaspTransferExecution(otherIntents,transfers,crypto,jdbc,json,audit,manager,assessments);
        assertThrows(ResponseStatusException.class,()->otherExchange.execute(otherIntent.id()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_attempts WHERE receipt_id=?",Long.class,receipt.id()));org.mockito.Mockito.verify(transfers,times(1)).exchange(any());noExtraIo();
    }

}
