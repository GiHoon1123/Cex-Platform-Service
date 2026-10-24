package dustin.cex.domains.bitcoin;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static dustin.cex.domains.bitcoin.BitcoinRpcClient.*;

/** 노드의 지갑 수신 결과를 실제 거래 출력과 대조한다. 잔액 반영·확정 수 정책·고객 허용은 결정하지 않는다. */
@Component
public class BitcoinDepositReader {
    public enum Status {UNCONFIRMED,CONFIRMED,CONFLICTED}
    public record Output(int vout,String address,String scriptPubKey,BigDecimal amount){}
    public record Observation(String network,String txid,Status status,long confirmations,boolean coinbase,
            String blockHash,Long blockHeight,String tipHash,long tipHeight,List<Output> outputs){
        public Observation{outputs=List.copyOf(outputs);}
    }
    private final BitcoinRpcClient rpc;
    private final BitcoinSettings settings;
    public BitcoinDepositReader(BitcoinRpcClient rpc,BitcoinSettings settings){this.rpc=rpc;this.settings=settings;}
    public Observation inspect(String txid){
        if(txid==null||!txid.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("정확한 비트코인 거래 식별자가 필요합니다");
        var ready=rpc.ready();String tip=hash(ready.chain(),"bestblockhash");long height=integer(ready.chain(),"blocks");
        if(height<0||!hash(ready.wallet().path("lastprocessedblock"),"hash").equals(tip)
                ||integer(ready.wallet().path("lastprocessedblock"),"height")!=height)throw unavailable();
        // 원문 거래와 해독 결과를 함께 받는다. 큰 거래에도 상한을 두고 초과 시 일부 결과를 반영하지 않는다.
        var transaction=rpc.call("gettransaction",List.of(txid,true,true),33554432);
        var decoded=transaction.path("decoded");
        if(!hash(transaction,"txid").equals(txid)||!hash(decoded,"txid").equals(txid)
                ||!hash(transaction.path("lastprocessedblock"),"hash").equals(tip)
                ||integer(transaction.path("lastprocessedblock"),"height")!=height
                ||!decoded.path("vin").isArray()||decoded.path("vin").isEmpty()
                ||!decoded.path("vout").isArray()||!transaction.path("details").isArray())throw unavailable();
        long confirmations=integer(transaction,"confirmations");
        boolean coinbase=decoded.path("vin").get(0).path("coinbase").isString();
        if(transaction.has("generated")&&(!transaction.path("generated").isBoolean()||transaction.path("generated").booleanValue()!=coinbase))throw unavailable();
        Status status=confirmations>0?Status.CONFIRMED:confirmations<0?Status.CONFLICTED:Status.UNCONFIRMED;
        String block=null;Long blockHeight=null;
        if(status==Status.CONFIRMED){
            block=hash(transaction,"blockhash");blockHeight=integer(transaction,"blockheight");
            var header=rpc.call("getblockheader",List.of(block,true));
            if(blockHeight<0||blockHeight>height||!hash(header,"hash").equals(block)||integer(header,"height")!=blockHeight
                    ||integer(header,"confirmations")!=confirmations||height-blockHeight+1!=confirmations)throw unavailable();
            var canonical=rpc.call("getblockhash",List.of(blockHeight));
            if(!canonical.isString()||!canonical.asString().equals(block))throw unavailable();
        }
        var byIndex=new HashMap<Integer,tools.jackson.databind.JsonNode>();
        int ordinal=0;
        for(var output:decoded.path("vout")){
            var n=output.path("n");
            if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()!=ordinal++||byIndex.put(n.intValue(),output)!=null)throw unavailable();
        }
        var received=new ArrayList<Output>();var seen=new HashSet<Integer>();
        for(var detail:transaction.path("details")){
            String category=text(detail,"category");
            if(!Set.of("receive","generate","immature","orphan").contains(category))continue;
            long index=integer(detail,"vout");
            if(index<0||index>Integer.MAX_VALUE||!seen.add((int)index))throw unavailable();
            var original=byIndex.get((int)index);if(original==null)throw unavailable();
            String address=text(detail,"address");var script=original.path("scriptPubKey");
            String hex=text(script,"hex");
            if(address.isEmpty()||!text(script,"address").equals(address)||hex.isEmpty()||hex.length()>20000||!hex.matches("[0-9a-f]+")||(hex.length()%2)!=0)throw unavailable();
            BigDecimal amount=money(detail.path("amount"));
            if(amount.compareTo(money(original.path("value")))!=0)throw unavailable();
            // 내부 거스름돈이나 임의의 외부 출력은 지갑의 수신 상세 원본에 없으면 선택하지 않는다.
            received.add(new Output((int)index,address,hex,amount));
        }
        var after=rpc.call("getblockchaininfo",List.of());
        // 읽기 도중 블록이 바뀌면 일관된 관찰로 저장하지 않는다. 다음 조회가 같은 거래를 다시 확인한다.
        if(!hash(after,"bestblockhash").equals(tip)||integer(after,"blocks")!=height||!isFalse(after,"initialblockdownload")
                ||!text(after,"chain").equals(settings.network.chain))throw unavailable();
        return new Observation(settings.network.id,txid,status,confirmations,coinbase,block,blockHeight,tip,height,received);
    }
    private static long integer(tools.jackson.databind.JsonNode node,String field){
        var value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong())throw unavailable();return value.longValue();
    }
    private static String hash(tools.jackson.databind.JsonNode node,String field){
        String value=text(node,field);if(!value.matches("[0-9a-f]{64}"))throw unavailable();return value;
    }
    private static BigDecimal money(tools.jackson.databind.JsonNode node){
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
