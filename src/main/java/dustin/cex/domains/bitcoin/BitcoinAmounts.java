package dustin.cex.domains.bitcoin;

import java.math.*;
import static dustin.cex.domains.bitcoin.BitcoinRpcClient.unavailable;

/** 노드의 수신/블록 활동 금액에 같은 BTC 정밀도·범위 계약을 적용한다. 이진 실수로 보정하지 않는다. */
final class BitcoinAmounts {
    private BitcoinAmounts(){}
    static BigDecimal fromNode(tools.jackson.databind.JsonNode node){
        BigDecimal value=signedFromNode(node);if(value.signum()<0)throw unavailable();return value;
    }
    // 지갑 수수료 표지는 일부 입력만 지갑 소유일 때 양수일 수도 있다. 입금액과 분리해 부호 있는 원문만 검증한다.
    static BigDecimal signedFromNode(tools.jackson.databind.JsonNode node){
        // 실수 토큰은 전용 ObjectReader가 BigDecimal로 만든다. DoubleNode로 바뀌면 잘못된 정밀도를 추측하지 않는다.
        if(!node.isNumber()||(!node.isIntegralNumber()&&!node.isBigDecimal()))throw unavailable();
        try{
            BigDecimal value=node.decimalValue();
            // 거대한 지수/소수 자릿수를 먼저 거부한다. 범위 검사 전에 setScale로 큰 정수를 만들지 않는다.
            if(value.abs().compareTo(new BigDecimal("21000000"))>0||value.stripTrailingZeros().scale()>8)throw unavailable();
            return value.setScale(8,RoundingMode.UNNECESSARY);
        }catch(ArithmeticException e){throw unavailable();}
    }
}
