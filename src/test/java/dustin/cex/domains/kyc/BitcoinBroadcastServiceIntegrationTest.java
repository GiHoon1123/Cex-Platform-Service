package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.broadcaster.*;
import dustin.cex.domains.bitcoin.BitcoinSignerEnvelopeCrypto;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** 실제 지급·서명 이력 위에서 방송 시도, 불변 결과, 중복 방지와 동시 소비 경계를 검증한다. */
class BitcoinBroadcastServiceIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    @Autowired BitcoinSignerEnvelopeCrypto signerCrypto;
    BitcoinBroadcastRpcClient broadcaster;BitcoinBroadcastService service;String txid;
    @BeforeEach void signedReady(){var candidate=readyNative(approved.payload().id());payouts.commit(approved.payload().id());txid=candidate.payload().prepared().unsignedTxid();var signer=mock(dustin.signer.BitcoinSignerRpcClient.class);when(signer.sign(any())).thenReturn(new dustin.signer.BitcoinSignerRpcClient.Signed(txid,"020000000001"+"00".repeat(100),"cHNidP8BAAA="));new dustin.signer.BitcoinSigningService(jdbc,signerCrypto,json,audit,signer,manager).processNext();broadcaster=mock(BitcoinBroadcastRpcClient.class);service=new BitcoinBroadcastService(jdbc,signerCrypto,audit,broadcaster,manager);when(broadcaster.publish(eq(txid),anyString())).thenReturn(new BitcoinBroadcastRpcClient.Result(BitcoinBroadcastRpcClient.Status.ACCEPTED,txid));}
    @Test void fixedSignedTransactionProducesOneAcceptedHead(){var result=service.processNext().orElseThrow();assertEquals("ACCEPTED",result.status());assertEquals(txid,result.txid());assertEquals(1,count("bitcoin_payout_broadcast_attempts"));assertEquals(1,count("bitcoin_payout_broadcast_results"));assertEquals(1,count("bitcoin_payout_broadcast_heads"));org.mockito.Mockito.verify(broadcaster).publish(eq(txid),anyString());}
    @Test void repeatedPollCannotBroadcastTheSameTransactionTwice(){service.processNext();assertTrue(service.processNext().isEmpty());org.mockito.Mockito.verify(broadcaster,times(1)).publish(anyString(),anyString());}
    @Test void unknownOutcomeKeepsTheSignedTransactionForDelayedRetry(){when(broadcaster.publish(anyString(),anyString())).thenReturn(new BitcoinBroadcastRpcClient.Result(BitcoinBroadcastRpcClient.Status.UNKNOWN,null));assertEquals("UNKNOWN",service.processNext().orElseThrow().status());assertTrue(service.processNext().isEmpty());assertEquals(0,count("bitcoin_payout_broadcast_heads"));}
    @Test void rejectedTransactionIsHeldForManualReview(){when(broadcaster.publish(anyString(),anyString())).thenReturn(new BitcoinBroadcastRpcClient.Result(BitcoinBroadcastRpcClient.Status.REJECTED,null));assertEquals("REJECTED",service.processNext().orElseThrow().status());assertTrue(service.processNext().isEmpty());assertEquals(0,count("bitcoin_payout_broadcast_heads"));}
    @Test void aMissingAcceptedAuditRollsBackResultAndHead(){doNothing().when(audit).record(isNull(),eq("BTC_NATIVE_PAYOUT_BROADCAST"),anyString(),eq("SUCCESS"),eq("ACCEPTED"));try{assertThrows(DataAccessException.class,service::processNext);}finally{reset(audit);}assertEquals(1,count("bitcoin_payout_broadcast_attempts"));assertEquals(0,count("bitcoin_payout_broadcast_results"));assertEquals(0,count("bitcoin_payout_broadcast_heads"));}
    @Test void directAcceptedResultWithAnotherTransactionIdIsRejected(){UUID attempt=UUID.randomUUID();new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("INSERT INTO bitcoin_payout_broadcast_attempts(id,request_id,position) VALUES(?,?,1)",attempt,withdrawal);audit.record(null,"BTC_NATIVE_BROADCAST_STARTED","bitcoin-broadcast-attempt:"+attempt,"SUCCESS","POSITION:1");});assertThrows(DataAccessException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_payout_broadcast_results(attempt_id,status,txid) VALUES(?,'ACCEPTED',?)",attempt,"f".repeat(64))));}
    @Test void broadcastFinancialRecordsCannotBeChangedOrDeleted(){service.processNext();for(String table:List.of("bitcoin_payout_broadcast_attempts","bitcoin_payout_broadcast_results","bitcoin_payout_broadcast_heads"))for(String sql:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));}
    @Test void twoConsumersClaimTheStableSignedTransactionOnlyOnce()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<Optional<BitcoinBroadcastService.Outcome>> call=()->{start.await();return service.processNext();};var one=pool.submit(call);var two=pool.submit(call);start.countDown();var results=List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Optional::isPresent).count());}assertEquals(1,count("bitcoin_payout_broadcast_heads"));org.mockito.Mockito.verify(broadcaster,times(1)).publish(anyString(),anyString());}
}
