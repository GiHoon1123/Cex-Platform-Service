package dustin.broadcaster;

import dustin.cex.domains.bitcoin.BitcoinJsonRpcClient;
import java.util.*;
import tools.jackson.databind.*;

/** 저장된 원시 거래의 기존 수신 여부를 먼저 확인하고 없을 때만 공개한다. */
public final class BitcoinBroadcastRpcClient implements AutoCloseable {
    public enum Status { ACCEPTED,UNKNOWN,REJECTED }
    public record Result(Status status,String txid){}
    private final BitcoinBroadcastSettings settings;private final BitcoinJsonRpcClient rpc;
    public BitcoinBroadcastRpcClient(BitcoinBroadcastSettings settings,ObjectMapper json){this.settings=settings;rpc=new BitcoinJsonRpcClient(settings.endpoint,settings.username,settings.password,json);}
    /** 먼저 지갑 이력에서 같은 거래를 찾아 이전 방송 응답 유실을 복구하고, 없을 때만 사전 검증 후 한 번 공개한다. */
    public Result publish(String expected,String raw){
        try{var chain=rpc.call("getblockchaininfo",List.of());var wallet=rpc.call("getwalletinfo",List.of());if(!settings.chain.equals(text(chain,"chain"))||!isFalse(chain,"initialblockdownload")||!settings.wallet.equals(text(wallet,"walletname"))||!isFalse(wallet,"private_keys_enabled")||!isTrue(wallet,"descriptors")||!isFalse(wallet,"scanning"))return new Result(Status.REJECTED,null);
            try{var existing=rpc.call("gettransaction",List.of(expected,true,true),1048576);if(expected.equals(text(existing,"txid"))&&integer(existing,"confirmations")>=0)return new Result(Status.ACCEPTED,expected);return new Result(Status.REJECTED,null);}catch(BitcoinJsonRpcClient.RpcFailure missing){if(missing.code!=-5)return new Result(Status.UNKNOWN,null);}
            var checked=rpc.call("testmempoolaccept",List.of(List.of(raw)),1048576);if(!checked.isArray()||checked.size()!=1||!expected.equals(text(checked.get(0),"txid"))||!isTrue(checked.get(0),"allowed"))return new Result(Status.REJECTED,null);
            String sent=rpc.call("sendrawtransaction",List.of(raw),65536).asString();return expected.equals(sent)?new Result(Status.ACCEPTED,sent):new Result(Status.REJECTED,null);
        }catch(RuntimeException uncertain){return new Result(Status.UNKNOWN,null);}
    }
    private static String text(JsonNode n,String k){return n.path(k).isString()?n.path(k).asString():"";}private static boolean isTrue(JsonNode n,String k){return n.path(k).isBoolean()&&n.path(k).booleanValue();}private static boolean isFalse(JsonNode n,String k){return n.path(k).isBoolean()&&!n.path(k).booleanValue();}private static long integer(JsonNode n,String k){return n.path(k).isIntegralNumber()?n.path(k).longValue():Long.MIN_VALUE;}
    @Override public void close(){rpc.close();}
}
