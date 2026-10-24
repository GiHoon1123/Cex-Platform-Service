package dustin.cex.domains.bitcoin;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static dustin.cex.domains.bitcoin.BitcoinRpcClient.*;

/** 노드의 실제 블록 출력 활동으로 수신을 발견한다. 블록/거래 해독기를 재구현하지 않는다. */
@Component
public class BitcoinBlockDiscovery {
    public record Receive(String txid,int vout,String scriptPubKey,BigDecimal amount){}
    public record Block(String network,long height,String hash,String previousHash,String tipHash,long tipHeight,List<Receive> received){
        public Block{received=List.copyOf(received);}
    }
    private final BitcoinRpcClient rpc;
    private final BitcoinSettings settings;
    public BitcoinBlockDiscovery(BitcoinRpcClient rpc,BitcoinSettings settings){this.rpc=rpc;this.settings=settings;}
    public Block scan(long height,List<String> scripts){
        // 현재 발급하는 수신 조건만 받는다. 한 번에 한 블록/200개 이하로 복구 비용을 나누며 잘라 반환하지 않는다.
        if(height<0||scripts==null||scripts.isEmpty()||scripts.size()>200||new HashSet<>(scripts).size()!=scripts.size()
                ||scripts.stream().anyMatch(s->s==null||!s.matches("0014[0-9a-f]{40}")))throw new IllegalArgumentException("검증할 블록과 수신 조건을 확인하세요");
        scripts=List.copyOf(scripts);
        var ready=rpc.ready();String tip=hash(ready.chain(),"bestblockhash");long tipHeight=integer(ready.chain(),"blocks");
        if(height>tipHeight)throw new IllegalArgumentException("아직 존재하지 않는 블록입니다");
        var blockValue=rpc.call("getblockhash",List.of(height));String block=hashValue(blockValue);
        var header=rpc.call("getblockheader",List.of(block,true));
        if(!hash(header,"hash").equals(block)||integer(header,"height")!=height||integer(header,"confirmations")!=tipHeight-height+1)throw unavailable();
        String previous=height==0?null:hash(header,"previousblockhash");
        var descriptors=scripts.stream().map(s->"raw("+s+")").toList();
        var activity=rpc.call("getdescriptoractivity",List.of(List.of(block),descriptors,false),33554432).path("activity");
        if(!activity.isArray())throw unavailable();
        var received=new ArrayList<Receive>();var seen=new HashSet<String>();
        for(var event:activity){
            if(!hash(event,"blockhash").equals(block)||integer(event,"height")!=height)throw unavailable();
            String kind=text(event,"type");
            if(kind.equals("spend"))continue;
            if(!kind.equals("receive"))throw unavailable();
            String txid=hash(event,"txid"),script=text(event.path("output_spk"),"hex");
            var vout=event.path("vout");
            if(!vout.isIntegralNumber()||!vout.canConvertToInt()||vout.intValue()<0||!scripts.contains(script)
                    ||!seen.add(txid+":"+vout.intValue()))throw unavailable();
            received.add(new Receive(txid,vout.intValue(),script,BitcoinAmounts.fromNode(event.path("amount"))));
        }
        // 전체 활동을 읽은 뒤의 빈 수신만 정상 결과다. 블록 읽기 실패를 빈 목록으로 대체하지 않는다.
        if(!hashValue(rpc.call("getblockhash",List.of(height))).equals(block))throw unavailable();
        var after=rpc.call("getblockchaininfo",List.of());
        if(!hash(after,"bestblockhash").equals(tip)||integer(after,"blocks")!=tipHeight
                ||!isFalse(after,"initialblockdownload")||!text(after,"chain").equals(settings.network.chain))throw unavailable();
        return new Block(settings.network.id,height,block,previous,tip,tipHeight,received);
    }
    private static String hash(JsonNode node,String field){return hashValue(node.path(field));}
    private static String hashValue(JsonNode node){if(!node.isString()||!node.asString().matches("[0-9a-f]{64}"))throw unavailable();return node.asString();}
    private static long integer(JsonNode node,String field){var value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong())throw unavailable();return value.longValue();}
}
