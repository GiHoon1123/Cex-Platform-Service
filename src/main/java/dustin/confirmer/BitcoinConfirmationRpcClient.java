package dustin.confirmer;

import dustin.cex.domains.bitcoin.BitcoinJsonRpcClient;
import java.util.*;
import tools.jackson.databind.*;

/** 움직이지 않은 하나의 체인 끝에서 거래 블록과 여섯 확인을 서로 대조한다. */
public final class BitcoinConfirmationRpcClient implements AutoCloseable {
    public enum Status { CONFIRMED,WAITING,UNKNOWN,CONFLICT }
    public record Result(Status status,String txid,String blockHash,long blockHeight,int confirmations,String observedTip){}
    private final BitcoinConfirmationSettings settings;private final BitcoinJsonRpcClient rpc;
    public BitcoinConfirmationRpcClient(BitcoinConfirmationSettings settings,ObjectMapper json){this.settings=settings;rpc=new BitcoinJsonRpcClient(settings.endpoint,settings.username,settings.password,json);}
    public Result observe(String expected){
        try{var before=rpc.call("getblockchaininfo",List.of());if(!settings.chain.equals(text(before,"chain"))||!isFalse(before,"initialblockdownload"))return empty(Status.UNKNOWN);String tip=text(before,"bestblockhash");long tipHeight=integer(before,"blocks");
            var transaction=rpc.call("getrawtransaction",List.of(expected,true),1048576);if(!expected.equals(text(transaction,"txid")))return empty(Status.CONFLICT);long confirmations=integer(transaction,"confirmations");if(confirmations<0)return empty(Status.CONFLICT);if(confirmations<settings.required||!hex(transaction,"blockhash"))return empty(Status.WAITING);String block=text(transaction,"blockhash");
            var header=rpc.call("getblockheader",List.of(block,true),1048576);var after=rpc.call("getblockchaininfo",List.of());long height=integer(header,"height");long headerConfirmations=integer(header,"confirmations");if(!tip.equals(text(after,"bestblockhash"))||tipHeight!=integer(after,"blocks")||!block.equals(text(header,"hash"))||headerConfirmations!=confirmations||tipHeight-height+1!=confirmations||confirmations>Integer.MAX_VALUE)return empty(Status.UNKNOWN);
            return new Result(Status.CONFIRMED,expected,block,height,(int)confirmations,tip);
        }catch(BitcoinJsonRpcClient.RpcFailure missing){return empty(missing.code==-5?Status.WAITING:Status.UNKNOWN);}catch(RuntimeException uncertain){return empty(Status.UNKNOWN);}
    }
    private static Result empty(Status status){return new Result(status,null,null,-1,0,null);}private static String text(JsonNode n,String k){return n.path(k).isString()?n.path(k).asString():"";}private static boolean hex(JsonNode n,String k){return text(n,k).matches("[0-9a-f]{64}");}private static boolean isFalse(JsonNode n,String k){return n.path(k).isBoolean()&&!n.path(k).booleanValue();}private static long integer(JsonNode n,String k){return n.path(k).isIntegralNumber()?n.path(k).longValue():Long.MIN_VALUE;}
    @Override public void close(){rpc.close();}
}
