package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.WealthFacts;
import dustin.cex.domains.edd.WealthFacts.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;

/** 단순 계산의 일치와 전체 재산 검증을 구분하며 선택한 기간의 정확한 증감·누락 사유와 입력 경계를 검증한다. */
class WealthFactsTest {
    final LocalDate from=LocalDate.of(2024,1,1),to=LocalDate.of(2026,1,1),today=LocalDate.of(2026,10,23);
    BigDecimal n(String value) {return new BigDecimal(value);}
    Balance balance(String assets,String liabilities,LocalDate day,String currency) {return new Balance("고객","가상 기관",day,to,n(assets),n(liabilities),currency);}
    Movement row(Kind kind,String amount) {return new Movement(to,kind,"R-1",n(amount),"KRW");}
    WealthFacts facts(List<Movement> rows,String assets,String liabilities) {return new WealthFacts(balance("1000","200",from,"KRW"),new Changes("고객","가상 기관",from,to,to,rows),balance(assets,liabilities,to,"KRW"));}
    Comparison compare(WealthFacts f) {return f.validated().compare("고객",today);}
    @Test void assetsMinusLiabilitiesAndNetChangesExplainClosingWealth() {
        var result=compare(facts(List.of(row(Kind.EMPLOYMENT_NET_INCOME,"500"),new Movement(to,Kind.CONSUMPTION,"R-2",n("100"),"KRW")),"1500","300"));
        assertEquals(0,n("800").compareTo(result.openingNet()));assertEquals(0,n("1200").compareTo(result.observedClosingNet()));assertTrue(result.reasons().isEmpty());assertEquals(0,result.unexplainedDifference().signum());
    }
    @ParameterizedTest @EnumSource(value=Kind.class,names={"EMPLOYMENT_NET_INCOME","BUSINESS_NET_PROFIT","INVESTMENT_NET_GAIN","INHERITANCE_NET_RECEIVED","GIFT_NET_RECEIVED"})
    void positiveNetFormationAddsOnlyExactObservedAmount(Kind kind) {var c=compare(facts(List.of(row(kind,"100")),"1100","200"));assertTrue(c.reasons().isEmpty());assertEquals(0,n("900").compareTo(c.expectedClosingNet()));}
    @ParameterizedTest @EnumSource(value=Kind.class,names={"CONSUMPTION","INVESTMENT_NET_LOSS"})
    void expensesAndNetLossReduceWealth(Kind kind) {assertTrue(compare(facts(List.of(row(kind,"100")),"900","200")).reasons().isEmpty());}
    @ParameterizedTest @EnumSource(value=Kind.class,names={"LOAN_PROCEEDS","ASSET_SALE_GROSS","OTHER"})
    void loanAndGrossSalesAreNotNewNetWealth(Kind kind) {var c=compare(facts(List.of(row(kind,"100")),"1100","200"));assertEquals(0,n("800").compareTo(c.expectedClosingNet()));assertTrue(c.reasons().containsAll(List.of(Reason.ECONOMIC_EFFECT_REQUIRES_DECOMPOSITION,Reason.UNEXPLAINED_NET_WORTH_DIFFERENCE)));}
    @ParameterizedTest @EnumSource(value=Kind.class,names={"VALUATION_INCREASE","VALUATION_DECREASE"})
    void valuationCanExplainArithmeticButAlwaysNeedsIndependentCheck(Kind kind) {var c=compare(facts(List.of(row(kind,"100")),kind==Kind.VALUATION_INCREASE?"1100":"900","200"));assertEquals(List.of(Reason.VALUATION_REQUIRES_INDEPENDENT_CHECK),c.reasons());}
    @Test void newLoanIncreasesAssetsAndLiabilitiesTogetherWithoutCreatingWealth() {var c=compare(facts(List.of(row(Kind.LOAN_PROCEEDS,"100")),"1100","300"));assertEquals(0,c.unexplainedDifference().signum());assertEquals(List.of(Reason.ECONOMIC_EFFECT_REQUIRES_DECOMPOSITION),c.reasons());}
    @Test void negativeNetWorthIsValidAndNotSilentlyClampedToZero() {var f=facts(List.of(),"100","1200");var c=compare(f);assertEquals(0,n("-1100").compareTo(c.observedClosingNet()));assertEquals(0,n("-1900").compareTo(c.unexplainedDifference()));}
    @Test void emptyMovementStatementCanDescribeUnchangedPeriodButNotProveOpeningOrigin() {assertTrue(compare(facts(List.of(),"1000","200")).reasons().isEmpty());}
    @Test void currencyMismatchSuppressesEveryAggregateInsteadOfGuessingRate() {var c=compare(facts(List.of(new Movement(to,Kind.EMPLOYMENT_NET_INCOME,"R-1",n("100"),"USD")),"1100","200"));assertNull(c.expectedClosingNet());assertNull(c.openingNet());assertNull(c.observedClosingNet());assertNull(c.unexplainedDifference());assertEquals(List.of(Reason.CURRENCY_CONVERSION_UNVERIFIED),c.reasons());}
    @Test void missingCoverageAndOutOfPeriodMovementsRemainExplicit() {var f=facts(List.of(new Movement(from,Kind.EMPLOYMENT_NET_INCOME,"R-1",n("100"),"KRW")),"1000","200");var c=compare(new WealthFacts(f.opening(),new Changes("고객","가상 기관",from.plusDays(1),to,to,f.changes().movements()),f.closing()));assertTrue(c.reasons().containsAll(List.of(Reason.INCOMPLETE_PERIOD_COVERAGE,Reason.MOVEMENT_OUTSIDE_PERIOD)));assertEquals(0,c.unexplainedDifference().signum());}
    @Test void futureDatesAndPrematureDocumentAreNotHidden() {var f=facts(List.of(row(Kind.EMPLOYMENT_NET_INCOME,"100")),"1100","200");var c=f.validated().compare("다른 고객",from);assertTrue(c.reasons().containsAll(List.of(Reason.FUTURE_DATE,Reason.OPENING_HOLDER_MISMATCH,Reason.CHANGES_HOLDER_MISMATCH,Reason.CLOSING_HOLDER_MISMATCH)));var b=f.closing();var early=new Balance(b.holderName(),b.issuer(),b.asOf(),from,b.assets(),b.liabilities(),b.currency());assertTrue(compare(new WealthFacts(f.opening(),f.changes(),early)).reasons().contains(Reason.DOCUMENT_PRECEDES_OBSERVATION));}
    @ParameterizedTest @ValueSource(strings={"0","-1","0.0000000001","1E+22","792281625142643375935.43950336"})
    void nonpositiveOrUnrepresentableMovementIsRejected(String amount) {assertThrows(ResponseStatusException.class,()->facts(List.of(row(Kind.EMPLOYMENT_NET_INCOME,amount)),"1100","200").validated());}
    @Test void aggregateOverflowIsRejectedBeforePersistence() {var f=facts(List.of(row(Kind.EMPLOYMENT_NET_INCOME,"999999999999999999999")),"1100","200");assertThrows(ResponseStatusException.class,f::validated);}
    @Test void duplicatesOrReorderedTimelineAreRejectedInsteadOfDeduplicatedOrSorted() {var r=row(Kind.EMPLOYMENT_NET_INCOME,"100");assertThrows(ResponseStatusException.class,()->facts(List.of(r,r),"1100","200").validated());assertThrows(ResponseStatusException.class,()->facts(List.of(r,new Movement(from.plusDays(1),Kind.EMPLOYMENT_NET_INCOME,"R-2",n("100"),"KRW")),"1100","200").validated());}
    @Test void normalizedNamesAmountsAndCurrencyAreEqualWithoutLosingNinthDecimal() {var f=facts(List.of(new Movement(to,Kind.EMPLOYMENT_NET_INCOME,"R-1",n("0.000000001"),"krw")),"1000.000000001","200").validated();assertTrue(f.compare("고객",today).reasons().isEmpty());assertEquals(n("0.000000001"),f.changes().movements().getFirst().amount());assertFalse(f.toString().contains("고객"));assertFalse(f.opening().toString().contains("1000"));assertFalse(f.changes().movements().getFirst().toString().contains("R-1"));}
    @Test void movementListIsDefensivelyCopiedAndImmutable() {var rows=new ArrayList<>(List.of(row(Kind.EMPLOYMENT_NET_INCOME,"100")));var f=facts(rows,"1100","200");rows.clear();assertEquals(1,f.changes().movements().size());assertThrows(UnsupportedOperationException.class,()->f.changes().movements().clear());}
    @Test void missingComponentsAndInvalidPeriodsAreRejected() {assertThrows(ResponseStatusException.class,()->new WealthFacts(null,null,null).validated());var f=facts(List.of(),"1000","200");assertThrows(ResponseStatusException.class,()->new WealthFacts(f.closing(),f.changes(),f.opening()).validated());assertThrows(ResponseStatusException.class,()->facts(Collections.singletonList(null),"1000","200").validated());}
    @Test void moreThanTwelveMovementsIsRejected() {var rows=new ArrayList<Movement>();for(int i=0;i<13;i++)rows.add(new Movement(to,Kind.EMPLOYMENT_NET_INCOME,"R-"+i,n("1"),"KRW"));assertThrows(ResponseStatusException.class,()->facts(rows,"1013","200").validated());}
}
