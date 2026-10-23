package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** 문서에서 읽은 사실의 정규화와 불일치 사유만 검증한다. 일치가 진위 확인을 의미하지 않는다. */
class FundsFactsTest {
    final LocalDate today=LocalDate.of(2026,10,23);
    FundsFacts facts(String holder,String payer,String currency,String amount) {
        return new FundsFacts(new FundsFacts.Origin(CustomerDeclaration.SourceOfFunds.SALARY,holder,payer,"발생 기관",today.minusDays(30),today.minusDays(2),today.minusDays(1),new BigDecimal(amount),currency),new FundsFacts.Receipt("고객","지급인","수취 기관","R-1",today.minusDays(1),today,new BigDecimal("1000"),"USDT"));
    }
    List<FundsFacts.Reason> compare(FundsFacts f,FundsTradeLink.Link trade) {return f.validated().compare("고객",CustomerDeclaration.SourceOfFunds.SALARY,today,trade);}
    FundsTradeLink.Link link(String asset,String amount) {return new FundsTradeLink.Link(UUID.randomUUID()+":1:0","engine:test",asset,new BigDecimal(amount),today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),"0".repeat(64),"1".repeat(64));}
    @Test void normalizedValuesRetainExactAmountAndHidePrivateString() {var f=facts(" 고객 ","지급인","usdt","1000.000000000").validated();assertTrue(compare(f,null).isEmpty());assertEquals(new BigDecimal("1E+3"),f.origin().netAmount());assertFalse(f.toString().contains("고객"));assertFalse(f.receipt().toString().contains("R-1"));}
    @Test void nameSenderCurrencyAndAmountDifferencesRemainDistinct() {assertTrue(compare(facts("다른 이름","다른 지급인","USD","999"),null).containsAll(List.of(FundsFacts.Reason.ORIGIN_HOLDER_MISMATCH,FundsFacts.Reason.PAYER_SENDER_MISMATCH,FundsFacts.Reason.CURRENCY_MISMATCH)));assertTrue(compare(facts("고객","지급인","USDT","999"),null).contains(FundsFacts.Reason.NET_RECEIPT_AMOUNT_MISMATCH));}
    @ParameterizedTest @ValueSource(strings={"0","-1","0.0000000001","1E+22","792281625142643375935.43950336"})
    void invalidOrInexactAmountIsRejected(String amount) {assertThrows(ResponseStatusException.class,()->facts("고객","지급인","USDT",amount).validated());}
    @ParameterizedTest @ValueSource(strings={"XXXQ","usd ","U$D","","bitcoin"})
    void unknownCurrencyIsRejected(String code) {assertThrows(ResponseStatusException.class,()->facts("고객","지급인",code,"1").validated());}
    @Test void receiptRequiresNamesReferenceAndExactAmount() {var f=facts("고객","지급인","USDT","1000");var r=f.receipt();assertThrows(ResponseStatusException.class,()->new FundsFacts(f.origin(),new FundsFacts.Receipt(r.holderName(),r.senderName(),r.issuer(),"계좌 원문 / 공백",r.receivedOn(),r.issuedOn(),r.amount(),r.currency())).validated());assertThrows(ResponseStatusException.class,()->new FundsFacts(f.origin(),null).validated());}
    @Test void futureAndReversedChronologyRemainReasons() {var f=facts("고객","지급인","USDT","1000");var o=f.origin();var r=f.receipt();var changed=new FundsFacts(new FundsFacts.Origin(o.source(),o.holderName(),o.payerName(),o.issuer(),today.minusDays(30),today.plusDays(1),today.plusDays(2),o.netAmount(),o.currency()),new FundsFacts.Receipt(r.holderName(),r.senderName(),r.issuer(),r.reference(),today.plusDays(1),today,r.amount(),r.currency()));assertTrue(compare(changed,null).containsAll(List.of(FundsFacts.Reason.FUTURE_DOCUMENT_DATE,FundsFacts.Reason.FUTURE_PERIOD,FundsFacts.Reason.RECEIPT_DOCUMENT_PRECEDES_TRANSACTION)));}
    @Test void documentAndReceiptBeforePeriodAreNotSilentlyAccepted() {var f=facts("고객","지급인","USDT","1000");var o=f.origin();var r=f.receipt();var changed=new FundsFacts(new FundsFacts.Origin(o.source(),o.holderName(),o.payerName(),o.issuer(),o.periodStart(),o.periodEnd(),o.periodStart(),o.netAmount(),o.currency()),new FundsFacts.Receipt(r.holderName(),r.senderName(),r.issuer(),r.reference(),o.periodStart(),r.issuedOn(),r.amount(),r.currency()));assertTrue(compare(changed,null).containsAll(List.of(FundsFacts.Reason.ORIGIN_DOCUMENT_PRECEDES_PERIOD,FundsFacts.Reason.RECEIPT_PRECEDES_PERIOD)));}
    @Test void invalidPeriodAndMissingDateAreRejected() {var f=facts("고객","지급인","USDT","1000");var o=f.origin();assertThrows(ResponseStatusException.class,()->new FundsFacts(new FundsFacts.Origin(o.source(),o.holderName(),o.payerName(),o.issuer(),o.periodEnd(),o.periodStart(),o.issuedOn(),o.netAmount(),o.currency()),f.receipt()).validated());assertThrows(ResponseStatusException.class,()->new FundsFacts(new FundsFacts.Origin(o.source(),o.holderName(),o.payerName(),o.issuer(),o.periodStart(),o.periodEnd(),null,o.netAmount(),o.currency()),f.receipt()).validated());}
    @Test void tradeComparisonDoesNotInventExchangeRateOrAllocateFunds() {var f=facts("고객","지급인","USDT","1000");assertTrue(compare(f,link("BTC","1")).contains(FundsFacts.Reason.TRADE_CURRENCY_CONVERSION_UNVERIFIED));assertTrue(compare(f,link("USDT","1000.000000001")).contains(FundsFacts.Reason.RECEIPT_AMOUNT_BELOW_TRADE));assertTrue(compare(f,link("USDT","1000")).isEmpty());}
    @Test void recordedLedgerTimeIsComparedAsDateOnly() {var f=facts("고객","지급인","USDT","1000");var l=link("USDT","1000");var earlier=new FundsTradeLink.Link(l.eventId(),l.operationKey(),l.spentAsset(),l.spentAmount(),l.recordedAt().minusSeconds(172800),l.inboxHash(),l.ledgerDigest());assertTrue(compare(f,earlier).contains(FundsFacts.Reason.RECEIPT_AFTER_RECORDED_TRADE));}
    @Test void targetMustBeExplicitAndCannotFallBackFromMissingTrade() {assertThrows(ResponseStatusException.class,()->new FundsTradeLink.Target(null,null).validated());assertThrows(ResponseStatusException.class,()->new FundsTradeLink.Target(FundsTradeLink.Scope.ONBOARDING,"unexpected").validated());assertThrows(ResponseStatusException.class,()->new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,null).validated());assertThrows(ResponseStatusException.class,()->new FundsTradeLink.Target(FundsTradeLink.Scope.RECORDED_TRADE,"------------------------------------:1:0").validated());}
}
