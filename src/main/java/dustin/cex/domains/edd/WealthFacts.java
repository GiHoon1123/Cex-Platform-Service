package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 선택 기간의 재산 증감에 대한 사람의 관찰이다. 고객의 전체 재산·자료 진위·적법성을 증명하지 않는다. */
public record WealthFacts(Balance opening,Changes changes,Balance closing) {
    public record Balance(String holderName,String issuer,LocalDate asOf,LocalDate issuedOn,BigDecimal assets,BigDecimal liabilities,String currency) {
        @Override public String toString() {return "Balance[개인정보·금액 생략]";}
    }
    public record Changes(String holderName,String issuer,LocalDate periodStart,LocalDate periodEnd,LocalDate issuedOn,List<Movement> movements) {
        public Changes {if(movements!=null)movements=Collections.unmodifiableList(new ArrayList<>(movements));}
        @Override public String toString() {return "Changes[개인정보·증감 생략]";}
    }
    public record Movement(LocalDate on,Kind kind,String reference,BigDecimal amount,String currency) {
        @Override public String toString() {return "Movement[날짜·참조·금액 생략]";}
    }
    /** 양수 금액의 경제적 방향을 고정한다. 대출·매각 총액·기타는 순재산 증가로 계산하지 않는다. */
    public enum Kind {
        EMPLOYMENT_NET_INCOME,BUSINESS_NET_PROFIT,INVESTMENT_NET_GAIN,INHERITANCE_NET_RECEIVED,GIFT_NET_RECEIVED,
        CONSUMPTION,INVESTMENT_NET_LOSS,VALUATION_INCREASE,VALUATION_DECREASE,LOAN_PROCEEDS,ASSET_SALE_GROSS,OTHER;
        int direction() {return switch(this) {
            case EMPLOYMENT_NET_INCOME,BUSINESS_NET_PROFIT,INVESTMENT_NET_GAIN,INHERITANCE_NET_RECEIVED,GIFT_NET_RECEIVED,VALUATION_INCREASE->1;
            case CONSUMPTION,INVESTMENT_NET_LOSS,VALUATION_DECREASE->-1;
            case LOAN_PROCEEDS,ASSET_SALE_GROSS,OTHER->0;
        };}
    }
    public enum Reason { OPENING_HOLDER_MISMATCH,CHANGES_HOLDER_MISMATCH,CLOSING_HOLDER_MISMATCH,
        FUTURE_DATE,DOCUMENT_PRECEDES_OBSERVATION,INCOMPLETE_PERIOD_COVERAGE,MOVEMENT_OUTSIDE_PERIOD,
        CURRENCY_CONVERSION_UNVERIFIED,ECONOMIC_EFFECT_REQUIRES_DECOMPOSITION,VALUATION_REQUIRES_INDEPENDENT_CHECK,UNEXPLAINED_NET_WORTH_DIFFERENCE }
    public record Comparison(String currency,BigDecimal openingNet,BigDecimal expectedClosingNet,BigDecimal observedClosingNet,BigDecimal unexplainedDifference,List<Reason> reasons) {
        @Override public String toString() {return "Comparison[금액 생략]";}
    }
    public WealthFacts validated() {
        if(opening==null || closing==null || changes==null || changes.movements()==null || changes.movements().size()>12)throw invalid();
        var start=balance(opening);var end=balance(closing);
        dates(changes.periodStart(),changes.periodEnd(),changes.issuedOn());
        if(!start.asOf().isBefore(end.asOf()) || changes.periodStart().isAfter(changes.periodEnd()))throw invalid();
        var rows=new ArrayList<Movement>();var references=new HashSet<String>();LocalDate previous=null;
        for(var m:changes.movements()) {
            if(m==null || m.kind()==null || m.reference()==null || !m.reference().matches("[A-Za-z0-9._:/-]{1,100}") || !references.add(m.reference()))throw invalid();
            dates(m.on());if(previous!=null && previous.isAfter(m.on()))throw invalid();previous=m.on();
            rows.add(new Movement(m.on(),m.kind(),m.reference(),amount(m.amount(),true),currency(m.currency())));
        }
        var result=new WealthFacts(start,new Changes(CustomerDeclaration.name(changes.holderName()),CustomerDeclaration.name(changes.issuer()),changes.periodStart(),changes.periodEnd(),changes.issuedOn(),List.copyOf(rows)),end);
        // 입력별 자릿수가 유효해도 누적 결과가 표현 범위를 넘을 수 있다. 임의 반올림 없이 입력 오류로 거절한다.
        result.compare(start.holderName(),LocalDate.of(2100,12,31));return result;
    }
    /** 누락된 소비·세금·취득 원가·환율을 추측하지 않는다. 차이와 조사 필요 사유는 승인 뒤에도 보존한다. */
    public Comparison compare(String customer,LocalDate today) {
        var reasons=new LinkedHashSet<Reason>();
        if(!opening.holderName().equals(customer))reasons.add(Reason.OPENING_HOLDER_MISMATCH);
        if(!changes.holderName().equals(customer))reasons.add(Reason.CHANGES_HOLDER_MISMATCH);
        if(!closing.holderName().equals(customer))reasons.add(Reason.CLOSING_HOLDER_MISMATCH);
        if(opening.asOf().isAfter(today)||closing.asOf().isAfter(today)||opening.issuedOn().isAfter(today)||closing.issuedOn().isAfter(today)||changes.issuedOn().isAfter(today)||changes.periodEnd().isAfter(today))reasons.add(Reason.FUTURE_DATE);
        if(opening.issuedOn().isBefore(opening.asOf())||closing.issuedOn().isBefore(closing.asOf())||changes.issuedOn().isBefore(changes.periodEnd()))reasons.add(Reason.DOCUMENT_PRECEDES_OBSERVATION);
        if(!changes.periodStart().equals(opening.asOf())||!changes.periodEnd().equals(closing.asOf()))reasons.add(Reason.INCOMPLETE_PERIOD_COVERAGE);
        boolean sameCurrency=opening.currency().equals(closing.currency());
        for(var m:changes.movements()) {
            sameCurrency &= opening.currency().equals(m.currency());
            if(m.on().isAfter(today))reasons.add(Reason.FUTURE_DATE);
            // 시작 스냅샷에 이미 들어간 당일 금액을 다시 더하지 않도록 (시작일, 종료일] 구간을 사용한다.
            if(!m.on().isAfter(opening.asOf())||m.on().isAfter(closing.asOf())||!m.on().isAfter(changes.periodStart())||m.on().isAfter(changes.periodEnd()))reasons.add(Reason.MOVEMENT_OUTSIDE_PERIOD);
            if(changes.issuedOn().isBefore(m.on()))reasons.add(Reason.DOCUMENT_PRECEDES_OBSERVATION);
            if(m.kind().direction()==0)reasons.add(Reason.ECONOMIC_EFFECT_REQUIRES_DECOMPOSITION);
            if(m.kind()==Kind.VALUATION_INCREASE||m.kind()==Kind.VALUATION_DECREASE)reasons.add(Reason.VALUATION_REQUIRES_INDEPENDENT_CHECK);
        }
        // 다른 통화를 합산한 숫자는 의미가 없으므로 계산 결과 자체를 반환하지 않는다.
        if(!sameCurrency) {reasons.add(Reason.CURRENCY_CONVERSION_UNVERIFIED);return new Comparison(null,null,null,null,null,List.copyOf(reasons));}
        BigDecimal start=signed(opening.assets().subtract(opening.liabilities())),end=signed(closing.assets().subtract(closing.liabilities()));
        BigDecimal expected=start;
        for(var m:changes.movements())if(m.on().isAfter(opening.asOf())&&!m.on().isAfter(closing.asOf())&&m.on().isAfter(changes.periodStart())&&!m.on().isAfter(changes.periodEnd()))expected=signed(expected.add(m.amount().multiply(BigDecimal.valueOf(m.kind().direction()))));
        BigDecimal difference=signed(end.subtract(expected));
        if(difference.signum()!=0)reasons.add(Reason.UNEXPLAINED_NET_WORTH_DIFFERENCE);
        return new Comparison(opening.currency(),start,expected,end,difference,List.copyOf(reasons));
    }
    private static Balance balance(Balance b) {dates(b.asOf(),b.issuedOn());return new Balance(CustomerDeclaration.name(b.holderName()),CustomerDeclaration.name(b.issuer()),b.asOf(),b.issuedOn(),amount(b.assets(),false),amount(b.liabilities(),false),currency(b.currency()));}
    private static BigDecimal signed(BigDecimal value) {return amount(value.abs(),false).multiply(BigDecimal.valueOf(value.signum()));}
    private static BigDecimal amount(BigDecimal value,boolean positive) {try{return positive?ExactAmount.requirePositive(value,"재산 증감액"):ExactAmount.requireNonNegative(value,"재산 관찰액");}catch(IllegalArgumentException e){throw invalid();}}
    private static String currency(String value) {
        if(value==null||!value.matches("[A-Za-z]{3,4}"))throw invalid();String code=value.toUpperCase(Locale.ROOT);
        try {if(!Set.of("BTC","USDT").contains(code))Currency.getInstance(code);}catch(IllegalArgumentException e){throw invalid();}return code;
    }
    private static void dates(LocalDate... dates) {for(var d:dates)if(d==null||d.isBefore(LocalDate.of(1900,1,1))||d.isAfter(LocalDate.of(2100,12,31)))throw invalid();}
    private static ResponseStatusException invalid() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"재산 시점·자산·부채·증감 관찰을 확인하세요");}
    @Override public String toString() {return "WealthFacts[개인정보·금액 생략]";}
}
