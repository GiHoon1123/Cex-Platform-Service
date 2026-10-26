package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 입금 심사·실행 권한·원장·수신 키를 사용해 출금 입력의 출처를 확인한다. 지급을 허가하거나 노드를 호출하지 않는다. */
class BitcoinPayoutInputSourcesIntegrationTest extends BitcoinCreditTestSupport {
    @Autowired BitcoinPayoutInputSources sources;
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired BitcoinObservationService observations;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer consumer;
    void settle(){
        credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_CREDITED);dispatch.execute(receipt.id()).orElseThrow();
        UUID engineId=UUID.randomUUID();String raw=json.writeValueAsString(Map.of("event_id",engineId+":1:0","engine_id",engineId,"event_type","deposit_credited","deposit_id",receipt.id(),"user_id",customer.getId(),"asset","BTC","amount","0.12345678","timestamp",Instant.now().toString()));
        new TransactionTemplate(manager).executeWithoutResult(t->consumer.consumeOrderEvent(raw));clearInvocations(depositEngine);
    }
    BitcoinPayoutClient.Prepared fundedCandidate(){return new BitcoinPayoutClient.Prepared(UUID.randomUUID(),BitcoinPayoutClient.POLICY,"bitcoin-regtest","cex_watch","cHNidP8BAAA=","a".repeat(64),"0.01000000","0.00000282","bcrt1qsyntheticchange","0014"+"cd".repeat(20),"0.11345396",
        List.of(new BitcoinPayoutClient.Input(receipt.txid(),0,receipt.address(),receipt.script(),"0.12345678")),new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","cex_watch",new BigDecimal("2"),"b".repeat(64),5,Instant.now(),Instant.now()),Instant.now());}
    List<BitcoinPayoutInputSources.Certificate> read(BitcoinPayoutClient.Prepared p){return new TransactionTemplate(manager).execute(t->{users.lockById(customer.getId()).orElseThrow();return sources.require(p);});}
    void held(BitcoinPayoutInputSources.Reason reason,BitcoinPayoutClient.Prepared p){assertEquals(reason,assertThrows(BitcoinPayoutInputSources.Held.class,()->read(p)).reason());}
    @Override @AfterEach void noExternalOrFinancialRelease(){
        verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer,priceClient,node,depositEngine);
        BigDecimal total=jdbc.queryForObject("SELECT coalesce(sum(r.amount),0) FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE r.user_id=?",BigDecimal.class,customer.getId());
        BigDecimal pending=jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=? AND asset='BTC' AND bucket='PENDING'",BigDecimal.class,customer.getId());
        BigDecimal available=jdbc.queryForObject("SELECT coalesce(sum(available),0) FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId());assertEquals(0,total.compareTo(pending.add(available)));
    }
    @Test void canonicalCreditAndCurrentChainRiskProduceTheExactOriginCertificate(){settle();var c=read(fundedCandidate()).getFirst();assertEquals(receipt.id(),c.origin().receiptId());assertEquals(customer.getId(),c.origin().customerId());assertEquals(receipt.txid(),c.point().txid());assertEquals("0.12345678",c.amount());assertEquals(jdbc.queryForObject("SELECT basis_hash FROM bitcoin_credit_authorizations WHERE receipt_id=?",String.class,receipt.id()),c.origin().authorizationHash());assertEquals(jdbc.queryForObject("SELECT event_id FROM bitcoin_credited_deposits WHERE receipt_id=?",String.class,receipt.id()),c.origin().creditEvent());assertNotNull(c.risk().assessmentId());assertTrue(c.origin().originMac().matches("[0-9a-f]{64}"));assertFalse(c.toString().contains(receipt.address()));}
    @Test void anHttpAcknowledgementWithoutTheCanonicalFinancialEventIsNotSpendable(){credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_CREDITED);dispatch.execute(receipt.id());clearInvocations(depositEngine);held(BitcoinPayoutInputSources.Reason.UNSETTLED_INPUT,fundedCandidate());}
    @Test void recognizedPendingFundsCannotBecomeASettlementCertificate(){held(BitcoinPayoutInputSources.Reason.UNSETTLED_INPUT,fundedCandidate());}
    @Test void unclassifiedWalletInputsHaveNoSourceFallback(){var p=fundedCandidate();var input=new BitcoinPayoutClient.Input("f".repeat(64),0,receipt.address(),receipt.script(),"0.12345678");var q=new BitcoinPayoutClient.Prepared(p.withdrawalId(),p.policy(),p.network(),p.wallet(),p.psbt(),p.unsignedTxid(),p.amount(),p.nativeFee(),p.changeAddress(),p.changeScript(),p.changeAmount(),List.of(input),p.quote(),p.finishedAt());held(BitcoinPayoutInputSources.Reason.UNKNOWN_INPUT,q);}
    @Test void selectedAmountMustMatchTheActualCustodyReceipt(){var p=fundedCandidate();var input=new BitcoinPayoutClient.Input(receipt.txid(),0,receipt.address(),receipt.script(),"0.12345679");var q=new BitcoinPayoutClient.Prepared(p.withdrawalId(),p.policy(),p.network(),p.wallet(),p.psbt(),p.unsignedTxid(),p.amount(),p.nativeFee(),p.changeAddress(),p.changeScript(),p.changeAmount(),List.of(input),p.quote(),p.finishedAt());held(BitcoinPayoutInputSources.Reason.INPUT_MISMATCH,q);}
    @Test void staleCurrentChainEvidenceCannotReuseThePastCredit(){settle();BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);held(BitcoinPayoutInputSources.Reason.CHAIN_NOT_READY,fundedCandidate());}
    @Test void newerUnprocessedMonitoringInputsHoldPreviouslyCreditedOutputs(){settle();var old=fundedCandidate();nativeReceipt();held(BitcoinPayoutInputSources.Reason.MONITORING_PENDING,old);}
    @Test void expiredOriginCustomerKycDoesNotEraseAlreadyRecordedFundsOrigin(){settle();jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());assertEquals(1,read(fundedCandidate()).size());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credited_deposits",Long.class));}
    @Test void aNormalNewObservationKeepsTheOriginalFundingIdentity(){settle();var first=read(fundedCandidate()).getFirst();BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);var second=read(fundedCandidate()).getFirst();assertEquals(first.origin(),second.origin());assertNotEquals(first.chain().observationId(),second.chain().observationId());}
    @Test void aMissingRiskViewCannotBeTreatedAsAClearInput(){settle();jdbc.execute("DROP VIEW bitcoin_credit_chain_risks");assertThrows(DataAccessException.class,()->read(fundedCandidate()));}
    @Test void inputOriginReadsRequireAnExistingFinancialTransaction(){assertThrows(IllegalTransactionStateException.class,()->sources.require(fundedCandidate()));}
    @Test void aChangedOriginReferenceCannotReuseTheStoredAuthentication(){
        settle();var p=fundedCandidate();var c=read(p).getFirst();var o=c.origin();
        var altered=new BitcoinPayoutInputSources.Certificate(c.point(),c.amount(),c.scriptPubKey(),new BitcoinPayoutInputSources.Origin(UUID.randomUUID(),o.customerId(),o.authorizationHash(),o.creditEvent(),o.creditHash(),o.originMac()),c.chain(),c.risk());
        new TransactionTemplate(manager).executeWithoutResult(t->sources.verifyStored(p,List.of(c)));
        assertThrows(BitcoinPayoutInputSources.Held.class,()->new TransactionTemplate(manager).executeWithoutResult(t->sources.verifyStored(p,List.of(altered))));
    }
    @Test void restoredConfirmationsCannotEraseAnUnclearedPostCreditRisk(){
        settle();var healthy=node.inspect(receipt.txid());clearInvocations(node);
        doReturn(new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),BitcoinDepositReader.Status.UNCONFIRMED,0,false,false,null,null,healthy.tipHash(),healthy.tipHeight(),healthy.outputs())).when(node).inspect(receipt.txid());
        long risk=observations.refresh(receipt.txid()).id();new TransactionTemplate(manager).executeWithoutResult(t->qualification.evaluate(risk));
        doReturn(healthy).when(node).inspect(receipt.txid());long normal=observations.refresh(receipt.txid()).id();new TransactionTemplate(manager).executeWithoutResult(t->qualification.evaluate(normal));clearInvocations(node);
        held(BitcoinPayoutInputSources.Reason.CHAIN_RISK,fundedCandidate());
    }

    @Test void sourceOwnersAreLockedTogetherWithTheReviewActorsInNumericOrder(){
        UUID review=UUID.randomUUID();var database=spy(jdbc);
        // JPA 프록시로 실제 호출을 위임하고 호출 순서만 기록한다.
        var repository=mock(dustin.cex.domains.auth.repository.UserRepository.class,org.mockito.AdditionalAnswers.delegatesTo(users));
        // 잠금용 검토 메타데이터만 대역이다. 출처 고객 조회와 각 JPA 잠금은 실제 격리 DB에서 실행한다.
        // 이 단계는 권한을 만들지 않는다. 실제 업무는 그 뒤 현재 검토/서명과 출처 원문을 확인한다.
        doReturn(List.of(Map.of("user_id",maker.user().getId(),"requester_id",maker.user().getId(),"checker_id",checker.user().getId())))
            .when(database).queryForList("SELECT user_id,requester_id,checker_id FROM bitcoin_withdrawal_payout_reviews WHERE id=?",review);
        var gate=new BitcoinPayoutInputSources(database,repository,monitoringView,crypto,json,bitcoin(),audit);
        new TransactionTemplate(manager).executeWithoutResult(t->gate.lockForReview(review,"bitcoin-regtest","cex_watch",List.of(new BitcoinPayoutInputSources.Point(receipt.txid(),0))));
        var ordered=inOrder(repository);for(long id:java.util.stream.LongStream.of(customer.getId(),maker.user().getId(),checker.user().getId()).distinct().sorted().toArray())ordered.verify(repository).lockById(id);
        ordered.verifyNoMoreInteractions();
    }

}
