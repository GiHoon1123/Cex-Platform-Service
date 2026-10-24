package dustin.cex.domains.bitcoin;

import java.math.*;
import static dustin.cex.domains.bitcoin.BitcoinRpcClient.unavailable;

/** 노드의 수신/블록 활동 금액에 같은 BTC 정밀도·범위 계약을 적용한다. 이진 실수로 보정하지 않는다. */
final class BitcoinAmounts {
    private BitcoinAmounts(){}
    static BigDecimal fromNode(tools.jackson.databind.JsonNode node){
        // 실수 토큰은 전용 ObjectReader가 BigDecimal로 만든다. DoubleNode로 바뀌면 잘못된 정밀도를 추측하지 않는다.
        if(!node.isNumber()||(!node.isIntegralNumber()&&!node.isBigDecimal()))throw unavailable();
        try{
            BigDecimal value=node.decimalValue();
            // 거대한 지수/소수 자릿수를 먼저 거부한다. 범위 검사 전에 setScale로 큰 정수를 만들지 않는다.
            if(value.signum()<0||value.compareTo(new BigDecimal("21000000"))>0||value.stripTrailingZeros().scale()>8)throw unavailable();
            return value.setScale(8,RoundingMode.UNNECESSARY);
        }catch(ArithmeticException e){throw unavailable();}
    }
}
