package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 실제 출금 원본과 두 외부 확인 단계의 실패·암호·동시 실행·접근 경계를 검증한다. */
class BitcoinWithdrawalTransferReviewIntegrationTest extends BitcoinWithdrawalTransferTestSupport {
    @Test void confirmedAddressPrecedesExactNetIdentityExchangeWithoutMovingMoney() {
        var intent=prepare();assertEquals("PREPARED",intent.status());assertEquals("VERIFIED",execute(intent.intentId()).status());
        var order=inOrder(addressClient,transferClient);order.verify(addressClient).verify(argThat(r->r.address().equals(ADDRESS)&&r.peer().entityId().equals(PEER)));
        order.verify(transferClient).authorize(argThat(r->r.transferId().equals(intent.intentId())&&r.originatorAccount().equals(OWN)&&r.address().equals(ADDRESS)
            &&r.amount().compareTo(new BigDecimal("0.01"))==0&&r.krwValue().compareTo(new BigDecimal("1000000.0012345678"))==0&&r.thresholdExceeded()));
        assertEquals(2,count("bitcoin_withdrawal_transfer_attempts"));assertEquals(2,count("bitcoin_withdrawal_transfer_results"));
    }
    @Test void originalIntentAndObservedIdentityAreEncryptedAndMacBound() {
        var intent=prepare();execute(intent.intentId());var row=jdbc.queryForMap("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE id=?",intent.intentId());
        String plain=new String(crypto.decrypt(customer.getId(),intent.intentId(),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
        assertEquals(crypto.fingerprint("btc-withdrawal-transfer-intent-v1",plain),row.get("content_mac"));
        assertEquals(ADDRESS,json.readTree(plain).path("request").path("address").asString());
        assertFalse(new String((byte[])row.get("ciphertext"),StandardCharsets.UTF_8).contains(ADDRESS));assertFalse(intent.toString().contains(ADDRESS));
        var result=jdbc.queryForMap("SELECT r.* FROM bitcoin_withdrawal_transfer_results r JOIN bitcoin_withdrawal_transfer_attempts a ON a.id=r.attempt_id WHERE a.stage='TRANSFER'");
        String received=new String(crypto.decrypt(customer.getId(),(UUID)result.get("attempt_id"),(byte[])result.get("ciphertext")),StandardCharsets.UTF_8);
        assertEquals(crypto.fingerprint("btc-withdrawal-transfer-result-v1",received),result.get("content_mac"));assertTrue(received.contains("1990-01-01"));
    }
    @Test void sameRequestKeyReplaysMetadataButChangedInputIsRejected() {
        UUID key=UUID.randomUUID();var a=reviews.prepare(maker.user().getId(),maker.sid(),withdrawal,PEER,key);
        assertEquals(a,reviews.prepare(maker.user().getId(),maker.sid(),withdrawal,PEER,key));
        assertThrows(ResponseStatusException.class,()->reviews.prepare(maker.user().getId(),maker.sid(),withdrawal,"other",key));
        assertEquals(1,count("bitcoin_withdrawal_transfer_intents"));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void duplicateExecutionCannotRepeatAVerifiedTransfer() {
        UUID id=prepare().intentId();assertEquals("VERIFIED",execute(id).status());assertEquals("VERIFIED",execute(id).status());
        org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());org.mockito.Mockito.verify(addressClient,times(1)).verify(any());
    }
    @Test void deniedAddressCannotDiscloseIdentityOrBeTreatedAsPaymentApproval() {
        doReturn(new CodeVaspWithdrawalAddressClient.Result("INVALID","NOT_FOUND_ADDRESS",null)).when(addressClient).verify(any());
        UUID id=prepare().intentId();assertEquals("INVALID",execute(id).status());assertEquals("INVALID",execute(id).status());verifyNoInteractions(transferClient);
        assertEquals(1,count("bitcoin_withdrawal_transfer_attempts"));
    }
    @Test void knownInvalidAddressAllowsANewExplicitIntentWithoutErasingTheOldOne() {
        doReturn(new CodeVaspWithdrawalAddressClient.Result("INVALID","NOT_FOUND_ADDRESS",null)).when(addressClient).verify(any());
        UUID old=prepare().intentId();execute(old);UUID next=prepare().intentId();assertNotEquals(old,next);assertThrows(ResponseStatusException.class,()->execute(old));
        assertEquals(2,count("bitcoin_withdrawal_transfer_intents"));verifyNoInteractions(transferClient);
    }
    @Test void unknownTransferCannotRetryOrEscapeThroughANewIntentId() {
        doThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN)).when(transferClient).authorize(any());
        UUID id=prepare().intentId();assertEquals("OUTCOME_UNKNOWN",execute(id).status());assertEquals("OUTCOME_UNKNOWN",execute(id).status());
        assertThrows(ResponseStatusException.class,this::prepare);org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());assertEquals(1,count("bitcoin_withdrawal_transfer_intents"));
    }
    @Test void provenNonSendIsSavedAndImmediateRetryIsSuppressed() {
        doThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT)).when(transferClient).authorize(any());
        UUID id=prepare().intentId();assertEquals("NOT_SENT",execute(id).status());assertEquals("NOT_SENT",execute(id).status());org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_withdrawal_transfer_results WHERE status='NOT_SENT'");assertNull(row.get("ciphertext"));assertNull(row.get("content_mac"));
    }
    @Test void malformedVerifiedIdentityBecomesUnknownAndCannotRetry() {
        doAnswer(call->{var r=(CodeVaspWithdrawalAuthorizationClient.Request)call.getArgument(0);var good=verified(r).data();return new CodeVaspWithdrawalAuthorizationClient.Result("VERIFIED",null,new CodeVaspWithdrawalAuthorizationClient.Data(UUID.randomUUID(),good.originator(),good.beneficiary(),good.ciphertextHash()));}).when(transferClient).authorize(any());
        UUID id=prepare().intentId();assertEquals("OUTCOME_UNKNOWN",execute(id).status());assertEquals("INVALID_SOURCE",execute(id).reason());org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());
    }
    @Test void wrongAddressEntityCannotStartThePrivateStage() {
        doReturn(new CodeVaspWithdrawalAddressClient.Result("VALID",null,"other")).when(addressClient).verify(any());
        assertEquals("OUTCOME_UNKNOWN",execute(prepare().intentId()).status());verifyNoInteractions(transferClient);
    }
    @Test void currentCustomerRestrictionBlocksOpeningTheStoredIdentity() {
        UUID id=prepare().intentId();jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());
        assertThrows(ResponseStatusException.class,()->execute(id));verifyNoInteractions(addressClient,transferClient);assertEquals(0,count("bitcoin_withdrawal_transfer_attempts"));
    }
    @Test void cancellationBeforeStartBlocksBothStages() {
        UUID id=prepare().intentId();cancellations.cancel(customer.getId(),sid,withdrawal);clearInvocations(engineWithdrawal);
        assertThrows(ResponseStatusException.class,()->execute(id));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void cancellationDuringAddressIoPreservesTheFactButBlocksIdentity() {
        doAnswer(call->{cancellations.cancel(customer.getId(),sid,withdrawal);clearInvocations(engineWithdrawal);return new CodeVaspWithdrawalAddressClient.Result("VALID",null,PEER);}).when(addressClient).verify(any());
        UUID id=prepare().intentId();assertThrows(ResponseStatusException.class,()->execute(id));
        assertEquals("VALID",reviews.read(maker.user().getId(),maker.sid(),id).status());verifyNoInteractions(transferClient);assertEquals(1,count("bitcoin_withdrawal_transfer_results"));
    }
    @Test void cancellationDuringTransferPreservesTheReplyWithoutPaying() {
        doAnswer(call->{cancellations.cancel(customer.getId(),sid,withdrawal);clearInvocations(engineWithdrawal);return verified(call.getArgument(0));}).when(transferClient).authorize(any());
        assertEquals("VERIFIED",execute(prepare().intentId()).status());assertEquals(1,count("bitcoin_withdrawal_cancellations"));
    }
    @Test void currentOperatorSessionAndOriginalPersonAreRequired()throws Exception {
        UUID id=prepare().intentId();var other=account(dustin.cex.domains.auth.model.entity.UserRole.OPERATOR);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->reviews.execute(other.user().getId(),other.sid(),id)).getStatusCode().value());
        jdbc.update("UPDATE refresh_tokens SET revoked=true WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->execute(id));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void prepareAuditFailureRollsBackTheIntentBeforeAnyIo() {
        doThrow(new IllegalStateException("격리 의도 감사 장애")).when(audit).record(eq(maker.user().getId()),eq("BTC_WITHDRAWAL_TRANSFER_PREPARED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,this::prepare);reset(audit);assertEquals(0,count("bitcoin_withdrawal_transfer_intents"));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void missingPrepareAuditIsRejectedAtCommit() {
        doNothing().when(audit).record(eq(maker.user().getId()),eq("BTC_WITHDRAWAL_TRANSFER_PREPARED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,this::prepare);reset(audit);assertEquals(0,count("bitcoin_withdrawal_transfer_intents"));
    }
    @Test void startAuditFailureCannotSendEvenTheAddress() {
        UUID id=prepare().intentId();doThrow(new IllegalStateException("격리 시작 감사 장애")).when(audit).record(anyLong(),eq("BTC_WITHDRAWAL_TRANSFER_STARTED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->execute(id));reset(audit);assertEquals(0,count("bitcoin_withdrawal_transfer_attempts"));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void privateReplyCommitFailureLeavesAnUnresolvedAttemptAndCannotResend() {
        UUID id=prepare().intentId();doAnswer(call->{if(call.getArgument(4).toString().contains("STATUS:VERIFIED"))throw new IllegalStateException("격리 완료 감사 장애");call.callRealMethod();return null;}).when(audit).record(isNull(),eq("BTC_WITHDRAWAL_TRANSFER_FINISHED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->execute(id));reset(audit);assertEquals("RUNNING",execute(id).status());org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());assertEquals(1,count("bitcoin_withdrawal_transfer_results"));
    }
    @Test void immutableIntentAttemptAndReplyCannotBeRewrittenOrRemoved() {
        execute(prepare().intentId());
        for(String table:List.of("bitcoin_withdrawal_transfer_intents","bitcoin_withdrawal_transfer_attempts","bitcoin_withdrawal_transfer_results"))
            for(String sql:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));
    }
    @Test void simultaneousExecutionStartsEachStageOnlyOnce()throws Exception {
        UUID id=prepare().intentId();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<BitcoinWithdrawalTransferReview.State> work=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return execute(id);};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}
        assertEquals("VERIFIED",execute(id).status());org.mockito.Mockito.verify(addressClient,times(1)).verify(any());org.mockito.Mockito.verify(transferClient,times(1)).authorize(any());
    }
    @Test void laterPriceDropDoesNotRewriteTheOriginalPrivacyPurpose() {
        UUID id=prepare().intentId();when(priceClient.read()).thenAnswer(call->referenceQuote(new BigDecimal("1")));referencePrices.refresh();
        assertEquals("VERIFIED",execute(id).status());org.mockito.Mockito.verify(transferClient).authorize(argThat(r->r.krwValue().compareTo(new BigDecimal("1000000.0012345678"))==0));
    }
    @Test void strictOperatorHttpRejectsUnknownDuplicateAndInjectedFields()throws Exception {
        String url="/api/admin/withdrawals/BTC/"+withdrawal+"/transfer-intents";
        for(String body:List.of("{\"peerId\":\"beneficiary\",\"requestKey\":\""+UUID.randomUUID()+"\",\"amount\":\"100\"}","{\"peerId\":\"beneficiary\",\"peerId\":\"other\",\"requestKey\":\""+UUID.randomUUID()+"\"}","{} {}"))
            mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization","Bearer "+customerAccess).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        assertEquals(0,count("bitcoin_withdrawal_transfer_intents"));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void headlessHttpCanPrepareExecuteAndReadOnlyMinimalMetadata()throws Exception {
        String url="/api/admin/withdrawals/BTC/"+withdrawal+"/transfer-intents";
        var response=mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("peerId",PEER,"requestKey",UUID.randomUUID())))).andExpect(status().isOk()).andReturn().getResponse();
        UUID id=UUID.fromString(json.readTree(response.getContentAsString()).path("intentId").asString());String action="/api/admin/withdrawals/BTC/transfer-intents/"+id;
        mvc.perform(post(action+"/execute").header("Authorization","Bearer "+maker.access()).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(action+"/execute").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        var read=mvc.perform(get(action).header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String secret:List.of(ADDRESS,OWN,"1990-01-01",declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName()))assertFalse(read.contains(secret));
    }
    @Test void belowPurposeThresholdDoesNotBorrowTheOlderLargerRequest()throws Exception {
        UUID small=quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.001").payload().id();submit(small);
        expectedHold=GROSS.add(new BigDecimal("0.00100010"));events.consumeOrderEvent(event(small,reserveEngine,"withdrawal_reserved",2));clearInvocations(engineWithdrawal);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->reviews.prepare(maker.user().getId(),maker.sid(),small,PEER,UUID.randomUUID())).getStatusCode().value());
        assertEquals(0,count("bitcoin_withdrawal_transfer_intents"));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void httpAcknowledgementWithoutCanonicalReserveCannotDiscloseIdentity()throws Exception {
        UUID pending=quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.001").payload().id();submit(pending);expectedHold=GROSS.add(new BigDecimal("0.00100010"));clearInvocations(engineWithdrawal);
        assertThrows(ResponseStatusException.class,()->reviews.prepare(maker.user().getId(),maker.sid(),pending,PEER,UUID.randomUUID()));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void changedCustomerVersionInvalidatesTheOriginalSignedPurpose() {
        UUID id=prepare().intentId();jdbc.update("UPDATE users SET version=version+1 WHERE id=?",customer.getId());
        assertThrows(ResponseStatusException.class,()->execute(id));verifyNoInteractions(addressClient,transferClient);
    }
    @Test void disabledPrivateTransferStopsAfterAddressValidation() {
        var addressOnly=new BitcoinWithdrawalTransferReview(source,users,operatorSessions,jdbc,crypto,json,audit,addressClient,transferClient,bitcoinSettings,manager,false);
        UUID id=prepare().intentId();assertEquals("VALID",addressOnly.execute(maker.user().getId(),maker.sid(),id).status());
        assertEquals("VALID",addressOnly.execute(maker.user().getId(),maker.sid(),id).status());verifyNoInteractions(transferClient);assertEquals(1,count("bitcoin_withdrawal_transfer_attempts"));
    }
    @Test void provenNonSendCanRetryOnlyAfterDurableDelayWithTheSameTransferId() {
        doThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT)).when(transferClient).authorize(any());
        UUID id=prepare().intentId();assertEquals("NOT_SENT",execute(id).status());
        // 불변 결과 시각을 시험 편의로 고치지 않는다. 실제 DB의 재시도 간격을 기다린다.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(35)).pollInterval(Duration.ofSeconds(1)).until(()->jdbc.queryForObject("SELECT recorded_at<=clock_timestamp()-interval '30 seconds' FROM bitcoin_withdrawal_transfer_results WHERE status='NOT_SENT'",Boolean.class));
        doAnswer(call->verified(call.getArgument(0))).when(transferClient).authorize(any());
        assertEquals("VERIFIED",execute(id).status());org.mockito.Mockito.verify(transferClient,times(2)).authorize(argThat(r->r.transferId().equals(id)));
        org.mockito.Mockito.verify(addressClient,times(1)).verify(any());assertEquals(1,count("bitcoin_withdrawal_transfer_intents"));
    }

}
