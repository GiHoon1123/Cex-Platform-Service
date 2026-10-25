package dustin.cex.domains.monitoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 반복 소액 참고값의 한 가지 위험 신호만 찾는다. 법정 기준 회피·범죄·신고 의무·금융 허가를 확정하지 않는다. */
public final class SmallDepositMonitoringPolicy {
    public static final String POLICY="NATIVE_SMALL_REFERENCE_INFLOW_V1";
    public static final int MAX_INPUTS=1000;
    private static final BigDecimal BOUNDARY=new BigDecimal("1000000");
    private static final int MIN_TRANSFERS=5;
    private SmallDepositMonitoringPolicy(){}

    /** 금액은 입금 인식 당시의 원화 참고값이다. 고객 신고의 USDT 월 예상 거래액을 여기에 섞지 않는다. */
    public record Input(long cursor,String operationKey,UUID receiptId,String asset,String network,String txid,
        Instant recognizedAt,String currency,String valuationPolicy,String valuationStatus,Long priceAttemptId,BigDecimal krwValue){
        @Override public String toString(){return "DepositMonitoringInput[금융 자료 생략]";}
    }
    public record Result(String outcome,String reason,int smallTransfers,BigDecimal smallTotalKrw,int unknownTransfers){}

    /** 입력은 호출자가 같은 고객의 원장 순번과24시간 인식 창으로 고정한다. 비교 중에는 외부 조회나 상태 변경을 하지 않는다. */
    public static Result assess(Instant reference,List<Input> inputs){
        if(inputs.isEmpty())return incomplete("NO_RECOGNIZED_INPUT");
        if(inputs.size()>MAX_INPUTS)return incomplete("INPUT_LIMIT_EXCEEDED");
        var seen=new HashSet<Long>();var transfers=new LinkedHashMap<String,List<Input>>();
        for(var input:inputs){
            if(input.cursor()<=0||!seen.add(input.cursor())||!"KRW".equals(input.currency()))return incomplete("INVALID_REFERENCE_SOURCE");
            if(input.recognizedAt()==null||input.recognizedAt().isAfter(reference))return incomplete("CLOCK_DISCONTINUITY");
            if(input.recognizedAt().isBefore(reference.minusSeconds(86400)))return incomplete("OUTSIDE_REFERENCE_WINDOW");
            // 하나의 온체인 거래가 여러 출력을 만들어도 횟수는 한 번이다. 다른 네트워크의 같은 문자열은 별개다.
            transfers.computeIfAbsent(input.network()+":"+input.txid(),key->new ArrayList<>()).add(input);
        }
        int small=0,unknown=0;BigDecimal total=BigDecimal.ZERO;
        for(var outputs:transfers.values()){
            boolean missing=false;BigDecimal value=BigDecimal.ZERO;
            for(var input:outputs){
                if(!"VALUED".equals(input.valuationStatus())||input.krwValue()==null||input.priceAttemptId()==null){missing=true;continue;}
                if(input.krwValue().signum()<=0)return incomplete("INVALID_REFERENCE_SOURCE");
                value=value.add(input.krwValue());
            }
            // 일부 출력이 미평가이면 전체 거래가 소액인지 알 수 없다. 알려진 일부만 더해 소액으로 분류하지 않는다.
            if(missing){unknown++;continue;}
            if(value.compareTo(BOUNDARY)<0){small++;total=total.add(value);}
        }
        if(small>=MIN_TRANSFERS&&total.compareTo(BOUNDARY)>=0)return new Result("REVIEW_REQUIRED","REPEATED_SMALL_REFERENCE_INFLOW",small,total,unknown);
        if(unknown>0)return new Result("INCOMPLETE","VALUATION_UNAVAILABLE",small,total,unknown);
        return new Result("NO_SIGNAL","NO_SIGNAL_IN_THIS_RULE",small,total,0);
    }
    private static Result incomplete(String reason){return new Result("INCOMPLETE",reason,0,BigDecimal.ZERO,0);}
}
