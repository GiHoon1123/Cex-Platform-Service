package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.signer.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

/** 실제 지급 실행 위에서 서명 시도·결과·감사·재시도 경계를 검증한다. 개인키 노드는 대역이며 실제 Core는 별도 시험이 담당한다. */
class BitcoinSigningServiceIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    BitcoinSignerRpcClient signerNode;BitcoinSigningService signing;String txid,raw;
    @BeforeEach void executionReady(){
        var candidate=readyNative(approved.payload().id());payouts.commit(approved.payload().id());txid=candidate.payload().prepared().unsignedTxid();raw="020000000001"+"00".repeat(100);
        signerNode=mock(BitcoinSignerRpcClient.class);signing=new BitcoinSigningService(jdbc,crypto,json,audit,signerNode,manager);
        when(signerNode.sign(any())).thenReturn(new BitcoinSignerRpcClient.Signed(txid,raw,"cHNidP8BAAA="));
    }
    @Test void fixedRequestProducesOneEncryptedSignedHead(){var result=signing.processNext().orElseThrow();assertEquals("SIGNED",result.status());assertEquals(txid,result.txid());assertEquals(raw,signing.readSigned(withdrawal).rawTransaction());assertEquals(1,count("bitcoin_payout_sign_attempts"));assertEquals(1,count("bitcoin_payout_sign_results"));assertEquals(1,count("bitcoin_payout_sign_heads"));org.mockito.Mockito.verify(signerNode).sign(any());}
    @Test void repeatedPollCannotSignTheSameRequestTwice(){signing.processNext();assertTrue(signing.processNext().isEmpty());org.mockito.Mockito.verify(signerNode,times(1)).sign(any());}
    @Test void unknownOutcomeKeepsTheRequestWithoutInventingAHead(){when(signerNode.sign(any())).thenThrow(new BitcoinSignerRpcClient.SignFailure(BitcoinSignerRpcClient.Failure.UNKNOWN));assertEquals("UNKNOWN",signing.processNext().orElseThrow().status());assertEquals(0,count("bitcoin_payout_sign_heads"));assertTrue(signing.processNext().isEmpty());}
    @Test void aCrashedSignerAttemptCanBeRetriedAfterItsCallDeadline()throws Exception{new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_sign_attempts(id,request_id,position) VALUES(?,?,1)",attempt,withdrawal);audit.record(null,"BTC_NATIVE_SIGN_STARTED","bitcoin-sign-attempt:"+attempt,"SUCCESS","POSITION:1");});Thread.sleep(10100);assertEquals("SIGNED",signing.processNext().orElseThrow().status());assertEquals(2,count("bitcoin_payout_sign_attempts"));assertEquals(1,count("bitcoin_payout_sign_heads"));}
    @Test void rejectedTransactionIsHeldWithoutAutomaticRetry(){when(signerNode.sign(any())).thenThrow(new BitcoinSignerRpcClient.SignFailure(BitcoinSignerRpcClient.Failure.REJECTED));assertEquals("REJECTED",signing.processNext().orElseThrow().status());assertTrue(signing.processNext().isEmpty());assertEquals(0,count("bitcoin_payout_sign_heads"));}
    @Test void aMissingSignedAuditRollsBackTheResultAndHead(){doNothing().when(audit).record(isNull(),eq("BTC_NATIVE_PAYOUT_SIGNED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,signing::processNext);}finally{reset(audit);}assertEquals(1,count("bitcoin_payout_sign_attempts"));assertEquals(0,count("bitcoin_payout_sign_results"));assertEquals(0,count("bitcoin_payout_sign_heads"));}
    @Test void directResultWithAnotherTransactionIdIsRejected(){UUID attempt=UUID.randomUUID();new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("INSERT INTO bitcoin_payout_sign_attempts(id,request_id,position) VALUES(?,?,1)",attempt,withdrawal);audit.record(null,"BTC_NATIVE_SIGN_STARTED","bitcoin-sign-attempt:"+attempt,"SUCCESS","POSITION:1");});assertThrows(DataAccessException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_payout_sign_results(attempt_id,status,txid,key_id,ciphertext,content_mac) VALUES(?,'SIGNED',?,?,?,?)",attempt,"f".repeat(64),crypto.keyId(),new byte[28],"e".repeat(64))));}
    @Test void signedFinancialRecordsCannotBeChangedOrDeleted(){signing.processNext();for(String table:List.of("bitcoin_payout_sign_attempts","bitcoin_payout_sign_results","bitcoin_payout_sign_heads"))for(String sql:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));}
    @Test void twoConsumersClaimTheStableRequestOnlyOnce()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<Optional<BitcoinSigningService.Outcome>> call=()->{start.await();return signing.processNext();};var one=pool.submit(call);var two=pool.submit(call);start.countDown();var results=List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Optional::isPresent).count());}assertEquals(1,count("bitcoin_payout_sign_heads"));org.mockito.Mockito.verify(signerNode,times(1)).sign(any());}
}
