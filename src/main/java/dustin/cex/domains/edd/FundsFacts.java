package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 한 자금 발생과 한 수취를 관찰한 값이다. 자료의 진위·실제 외부 입금·자금 배정 완료를 뜻하지 않는다. */
public record FundsFacts(Origin origin,Receipt receipt) {
    public record Origin(CustomerDeclaration.SourceOfFunds source,String holderName,String payerName,String issuer,
        LocalDate periodStart,LocalDate periodEnd,LocalDate issuedOn,BigDecimal netAmount,String currency) {
        @Override public String toString() {return "Origin[개인정보 생략]";}
    }
    public record Receipt(String holderName,String senderName,String issuer,String reference,
        LocalDate receivedOn,LocalDate issuedOn,BigDecimal amount,String currency) {
        @Override public String toString() {return "Receipt[개인정보 생략]";}
    }
    public enum Reason { ORIGIN_HOLDER_MISMATCH,RECEIPT_HOLDER_MISMATCH,DECLARED_SOURCE_DIFFERENCE,ADDITIONAL_SOURCE_DETAIL_REQUIRED,
        PAYER_SENDER_MISMATCH,CURRENCY_MISMATCH,NET_RECEIPT_AMOUNT_MISMATCH,FUTURE_DOCUMENT_DATE,FUTURE_PERIOD,
        ORIGIN_DOCUMENT_PRECEDES_PERIOD,RECEIPT_PRECEDES_PERIOD,RECEIPT_DOCUMENT_PRECEDES_TRANSACTION,
        TRADE_CURRENCY_CONVERSION_UNVERIFIED,RECEIPT_AMOUNT_BELOW_TRADE,RECEIPT_AFTER_RECORDED_TRADE }
    public FundsFacts validated() {
        if(origin==null || receipt==null || origin.source()==null) throw invalid();
        dates(origin.periodStart(),origin.periodEnd(),origin.issuedOn(),receipt.receivedOn(),receipt.issuedOn());
        if(origin.periodEnd().isBefore(origin.periodStart()) || receipt.reference()==null || !receipt.reference().matches("[A-Za-z0-9._:/-]{1,100}")) throw invalid();
        try {
            return new FundsFacts(new Origin(origin.source(),name(origin.holderName()),name(origin.payerName()),name(origin.issuer()),origin.periodStart(),origin.periodEnd(),origin.issuedOn(),ExactAmount.requirePositive(origin.netAmount(),"자금 발생 순수취액"),currency(origin.currency())),
                new Receipt(name(receipt.holderName()),name(receipt.senderName()),name(receipt.issuer()),receipt.reference(),receipt.receivedOn(),receipt.issuedOn(),ExactAmount.requirePositive(receipt.amount(),"수취액"),currency(receipt.currency())));
        } catch(IllegalArgumentException e) {throw invalid();}
    }
    /** 총액·세금·수수료·환율을 추측해 맞추지 않는다. 차이는 사유로 기록하며 승인자가 지울 수 없다. */
    public List<Reason> compare(String customer,CustomerDeclaration.SourceOfFunds declared,LocalDate today,FundsTradeLink.Link trade) {
        var reasons=new ArrayList<Reason>();
        if(!origin.holderName().equals(customer)) reasons.add(Reason.ORIGIN_HOLDER_MISMATCH);
        if(!receipt.holderName().equals(customer)) reasons.add(Reason.RECEIPT_HOLDER_MISMATCH);
        if(origin.source()!=declared) reasons.add(Reason.DECLARED_SOURCE_DIFFERENCE);
        if(origin.source()==CustomerDeclaration.SourceOfFunds.OTHER) reasons.add(Reason.ADDITIONAL_SOURCE_DETAIL_REQUIRED);
        if(!origin.payerName().equals(receipt.senderName())) reasons.add(Reason.PAYER_SENDER_MISMATCH);
        if(!origin.currency().equals(receipt.currency())) reasons.add(Reason.CURRENCY_MISMATCH);
        else if(origin.netAmount().compareTo(receipt.amount())!=0) reasons.add(Reason.NET_RECEIPT_AMOUNT_MISMATCH);
        if(origin.issuedOn().isAfter(today)||receipt.issuedOn().isAfter(today)||receipt.receivedOn().isAfter(today)) reasons.add(Reason.FUTURE_DOCUMENT_DATE);
        if(origin.periodEnd().isAfter(today)) reasons.add(Reason.FUTURE_PERIOD);
        if(origin.issuedOn().isBefore(origin.periodEnd())) reasons.add(Reason.ORIGIN_DOCUMENT_PRECEDES_PERIOD);
        if(receipt.receivedOn().isBefore(origin.periodEnd())) reasons.add(Reason.RECEIPT_PRECEDES_PERIOD);
        if(receipt.issuedOn().isBefore(receipt.receivedOn())) reasons.add(Reason.RECEIPT_DOCUMENT_PRECEDES_TRANSACTION);
        if(trade!=null) {
            if(!receipt.currency().equals(trade.spentAsset())) reasons.add(Reason.TRADE_CURRENCY_CONVERSION_UNVERIFIED);
            else if(receipt.amount().compareTo(trade.spentAmount())<0) reasons.add(Reason.RECEIPT_AMOUNT_BELOW_TRADE);
            if(receipt.receivedOn().isAfter(trade.recordedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())) reasons.add(Reason.RECEIPT_AFTER_RECORDED_TRADE);
        }
        return List.copyOf(reasons);
    }
    private static String name(String value) {return CustomerDeclaration.name(value);}
    private static String currency(String value) {
        if(value==null || !value.matches("[A-Za-z]{3,4}")) throw invalid();String code=value.toUpperCase(Locale.ROOT);
        if(!Set.of("BTC","USDT").contains(code)) Currency.getInstance(code);return code;
    }
    private static void dates(LocalDate... dates) {for(var d:dates)if(d==null||d.isBefore(LocalDate.of(1900,1,1))||d.isAfter(LocalDate.of(2100,12,31)))throw invalid();}
    private static ResponseStatusException invalid() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"자금 발생·수취 관찰 항목을 확인하세요");}
    @Override public String toString() {return "FundsFacts[개인정보 생략]";}
}
