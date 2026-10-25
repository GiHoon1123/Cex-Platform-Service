package dustin.cex.domains.monitoring;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 규칙의 실제 경계·거래 단위 묶음·자료 부재를 검증한다. 경보는 범죄나 신고 결정이 아니다. */
class SmallDepositMonitoringPolicyTest {
    static final Instant NOW=Instant.parse("2026-10-25T00:00:00Z");
    SmallDepositMonitoringPolicy.Input input(long id,String network,String tx,String value,String status){return new SmallDepositMonitoringPolicy.Input(id,"deposit:"+id,UUID.randomUUID(),"BTC",network,tx,NOW,"KRW","REFERENCE_V1",status,"VALUED".equals(status)?1L:null,value==null?null:new BigDecimal(value));}
    List<SmallDepositMonitoringPolicy.Input> many(int count,String value){return IntStream.range(0,count).mapToObj(i->input(i+1,"bitcoin-regtest","tx"+i,value,"VALUED")).toList();}
    @Test void fiveSmallTransfersAtExactAggregateBoundaryRequireReview(){var result=SmallDepositMonitoringPolicy.assess(NOW,many(5,"200000"));assertEquals("REVIEW_REQUIRED",result.outcome());assertEquals(5,result.smallTransfers());assertEquals(0,new BigDecimal("1000000").compareTo(result.smallTotalKrw()));}
    @Test void aggregateJustBelowBoundaryIsNotRoundedUp(){assertEquals("NO_SIGNAL",SmallDepositMonitoringPolicy.assess(NOW,many(5,"199999.9999999999999999")).outcome());}
    @Test void fewerTransfersDoNotMeetFrequencyEvenWhenSumIsLarge(){assertEquals("NO_SIGNAL",SmallDepositMonitoringPolicy.assess(NOW,many(4,"300000")).outcome());}
    @Test void oneTransactionWithFiveOutputsIsCountedOnce(){var list=IntStream.range(0,5).mapToObj(i->input(i+1,"bitcoin-regtest","one-tx","210000","VALUED")).toList();var result=SmallDepositMonitoringPolicy.assess(NOW,list);assertEquals("NO_SIGNAL",result.outcome());assertEquals(0,result.smallTransfers());}
    @Test void unknownOutputMakesWholeTransactionUnclassified(){var list=new ArrayList<>(many(4,"250000"));list.add(input(5,"bitcoin-regtest","tx0",null,"UNVALUED"));var result=SmallDepositMonitoringPolicy.assess(NOW,list);assertEquals("INCOMPLETE",result.outcome());assertEquals(3,result.smallTransfers());assertEquals(1,result.unknownTransfers());}
    @Test void knownSignalIsPreservedAlongsideUnknownTransaction(){var list=new ArrayList<>(many(5,"200000"));list.add(input(6,"bitcoin-regtest","unknown",null,"UNVALUED"));var result=SmallDepositMonitoringPolicy.assess(NOW,list);assertEquals("REVIEW_REQUIRED",result.outcome());assertEquals(1,result.unknownTransfers());}
    @Test void missingPriceDoesNotBecomeZeroOrLowRisk(){assertEquals("INCOMPLETE",SmallDepositMonitoringPolicy.assess(NOW,List.of(input(1,"bitcoin-regtest","missing",null,"UNVALUED"))).outcome());}
    @Test void transferAtIndividualBoundaryIsOutsideSmallBand(){var result=SmallDepositMonitoringPolicy.assess(NOW,many(5,"1000000"));assertEquals("NO_SIGNAL",result.outcome());assertEquals(0,result.smallTransfers());}
    @Test void sameHashOnDifferentNetworksIsNotCollapsed(){var list=new ArrayList<>(many(4,"200000"));list.set(0,input(1,"bitcoin-regtest","same","200000","VALUED"));list.add(input(5,"bitcoin-mainnet","same","200000","VALUED"));assertEquals("REVIEW_REQUIRED",SmallDepositMonitoringPolicy.assess(NOW,list).outcome());}
    @Test void sourceLimitNeverProducesPartialNoSignal(){var result=SmallDepositMonitoringPolicy.assess(NOW,many(1001,"1"));assertEquals("INCOMPLETE",result.outcome());assertEquals("INPUT_LIMIT_EXCEEDED",result.reason());}
    @Test void duplicateLedgerCursorIsNotDoubleCounted(){var one=input(1,"bitcoin-regtest","same","300000","VALUED");assertEquals("INCOMPLETE",SmallDepositMonitoringPolicy.assess(NOW,List.of(one,one)).outcome());}
    @Test void anotherCurrencyCannotBeComparedAsWon(){var old=many(1,"1").getFirst();var other=new SmallDepositMonitoringPolicy.Input(old.cursor(),old.operationKey(),old.receiptId(),old.asset(),old.network(),old.txid(),NOW,"USDT",old.valuationPolicy(),old.valuationStatus(),old.priceAttemptId(),old.krwValue());assertEquals("INVALID_REFERENCE_SOURCE",SmallDepositMonitoringPolicy.assess(NOW,List.of(other)).reason());}
    @Test void backwardsRecognitionClockDoesNotSilentlyDropFutureDatedInput(){var old=many(1,"1").getFirst();assertEquals("CLOCK_DISCONTINUITY",SmallDepositMonitoringPolicy.assess(NOW.minusSeconds(1),List.of(old)).reason());}
    @Test void outsideWindowIsNotAcceptedAsCurrentEvidence(){assertEquals("OUTSIDE_REFERENCE_WINDOW",SmallDepositMonitoringPolicy.assess(NOW.plusSeconds(86401),many(1,"1")).reason());}
    @Test void invalidValuedAmountDoesNotBecomeSmall(){assertEquals("INVALID_REFERENCE_SOURCE",SmallDepositMonitoringPolicy.assess(NOW,List.of(input(1,"bitcoin-regtest","bad","0","VALUED"))).reason());}
    @Test void absentSourceIsIncomplete(){assertEquals("NO_RECOGNIZED_INPUT",SmallDepositMonitoringPolicy.assess(NOW,List.of()).reason());}
}
