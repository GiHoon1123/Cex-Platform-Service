package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static dustin.cex.domains.bitcoin.BitcoinDepositReader.Status.CONFIRMED;
import static dustin.cex.domains.bitcoin.BitcoinDepositReader.Status.UNCONFIRMED;
import static dustin.cex.domains.bitcoin.BitcoinDepositQualificationService.Reason.*;
import org.junit.jupiter.api.Test;

/** 일반 확인 수·보상 성숙도·출력 연결·관찰 최신성을 서로 다른 조건으로 검증한다. */
class BitcoinDepositQualificationTest {
    @Test void ordinaryDepositWaitsUntilSixConfirmations(){
        for(long depth:new long[]{1,2,5})assertEquals(WAITING_CONFIRMATIONS,BitcoinDepositQualificationService.reason(true,CONFIRMED,depth,false,true));
        assertEquals(CHAIN_READY,BitcoinDepositQualificationService.reason(true,CONFIRMED,6,false,true));
    }
    @Test void coinbaseDepthOneHundredIsStillImmatureAndOneHundredOneIsReady(){
        for(long depth:new long[]{1,6,99,100})assertEquals(IMMATURE_COINBASE,BitcoinDepositQualificationService.reason(true,CONFIRMED,depth,true,true));
        assertEquals(CHAIN_READY,BitcoinDepositQualificationService.reason(true,CONFIRMED,101,true,true));
    }
    @Test void unconfirmedAndConflictedNeverBecomeChainReady(){
        assertEquals(WAITING_CHAIN,BitcoinDepositQualificationService.reason(true,UNCONFIRMED,0,false,true));
        assertEquals(CONFLICTED,BitcoinDepositQualificationService.reason(true,BitcoinDepositReader.Status.CONFLICTED,-1,false,true));
    }
    @Test void staleOrFutureObservationCannotUseDeepConfirmations(){
        assertEquals(STALE_OBSERVATION,BitcoinDepositQualificationService.reason(true,CONFIRMED,1000,false,false));
        assertEquals(STALE_OBSERVATION,BitcoinDepositQualificationService.reason(true,CONFIRMED,1000,true,false));
    }
    @Test void missingWrongOrDuplicatedOutputIsHeldEvenWithEnoughDepth(){
        assertEquals(OUTPUT_MISMATCH,BitcoinDepositQualificationService.reason(false,CONFIRMED,1000,false,true));
    }
    @Test void inconsistentStatusAndConfirmationSignAreRejected(){
        assertThrows(IllegalArgumentException.class,()->BitcoinDepositQualificationService.reason(true,CONFIRMED,0,false,true));
        assertThrows(IllegalArgumentException.class,()->BitcoinDepositQualificationService.reason(true,UNCONFIRMED,1,false,true));
        assertThrows(IllegalArgumentException.class,()->BitcoinDepositQualificationService.reason(true,BitcoinDepositReader.Status.CONFLICTED,1,false,true));
        assertThrows(IllegalArgumentException.class,()->BitcoinDepositQualificationService.reason(true,null,0,false,true));
    }
}
