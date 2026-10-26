package dustin.signer;

import dustin.cex.domains.bitcoin.*;
import java.math.*;
import java.util.*;
import tools.jackson.databind.*;

/** 고정 PSBT를 개인키 지갑으로 서명하고 거래의 금융 의미가 바뀌지 않았는지 독립적으로 검증한다. 방송 RPC는 호출하지 않는다. */
public final class BitcoinSignerRpcClient implements AutoCloseable {
    public enum Failure { UNKNOWN,REJECTED }
    public static final class SignFailure extends RuntimeException {public final Failure failure;public SignFailure(Failure failure){super("비트코인 서명 결과를 확인할 수 없습니다");this.failure=failure;}}
    public record Signed(String txid,String rawTransaction,String signedPsbt) {public Signed{if(txid==null||rawTransaction==null||signedPsbt==null)throw new IllegalArgumentException();}}
    private final BitcoinSignerSettings settings;private final BitcoinJsonRpcClient rpc;
    public BitcoinSignerRpcClient(BitcoinSignerSettings settings,ObjectMapper json){this.settings=settings;rpc=new BitcoinJsonRpcClient(settings.endpoint,settings.username,settings.password,json);}
    public Signed sign(BitcoinPayoutExecutionRecords.Payload execution){
        try{
            var prepared=execution.prepared();var funds=execution.funds();
            if(!settings.network.equals(funds.network())||!settings.network.equals(prepared.network())||!funds.withdrawalId().equals(execution.withdrawalId()))throw rejected();
            var chain=rpc.call("getblockchaininfo",List.of());var wallet=rpc.call("getwalletinfo",List.of());
            if(!settings.chain.equals(text(chain,"chain"))||!isFalse(chain,"initialblockdownload")||!settings.wallet.equals(text(wallet,"walletname"))
                    ||!isTrue(wallet,"private_keys_enabled")||!isTrue(wallet,"descriptors")||!isFalse(wallet,"scanning"))throw rejected();
            // 공개 파생 경로는 결과에 다시 넣지 않는다. ALL 서명과 완성된 거래만 허용한다.
            var result=rpc.call("walletprocesspsbt",List.of(prepared.psbt(),true,"ALL",false,true),2097152);
            if(!isTrue(result,"complete"))throw rejected();String psbt=text(result,"psbt"),raw=text(result,"hex");
            if(!base64(psbt)||raw.length()<20||raw.length()>2000000||!raw.matches("[0-9a-f]+")||(raw.length()&1)!=0)throw rejected();
            var tx=rpc.call("decoderawtransaction",List.of(raw),2097152);String txid=hex(tx,"txid");
            if(!prepared.unsignedTxid().equals(txid)||integer(tx,"version")!=2||integer(tx,"locktime")!=0)throw rejected();
            var vin=tx.path("vin");var vout=tx.path("vout");if(!vin.isArray()||vin.size()!=prepared.inputs().size()||!vout.isArray()||vout.size()!=(prepared.changeAddress()==null?1:2))throw rejected();
            BigDecimal input=BigDecimal.ZERO,output=BigDecimal.ZERO;
            for(int i=0;i<vin.size();i++){
                var actual=vin.get(i);var expected=prepared.inputs().get(i);
                if(!expected.txid().equals(hex(actual,"txid"))||expected.vout()!=integer(actual,"vout")||integer(actual,"sequence")<4294967294L
                        ||!actual.path("txinwitness").isArray()||actual.path("txinwitness").isEmpty()||!"".equals(text(actual.path("scriptSig"),"hex")))throw rejected();
                input=input.add(money(expected.amount()));
            }
            for(int i=0;i<vout.size();i++){
                var actual=vout.get(i);if(integer(actual,"n")!=i)throw rejected();BigDecimal value=money(actual.path("value"));output=output.add(value);var script=actual.path("scriptPubKey");
                String address=i==0?funds.address():prepared.changeAddress(),locking=i==0?funds.scriptPubKey():prepared.changeScript();
                BigDecimal expected=i==0?money(funds.amount()):money(prepared.changeAmount());
                if(value.compareTo(expected)!=0||!address.equals(text(script,"address"))||!locking.equals(text(script,"hex")))throw rejected();
            }
            if(input.subtract(output).compareTo(money(prepared.nativeFee()))!=0)throw rejected();return new Signed(txid,raw,psbt);
        }catch(SignFailure failure){throw failure;}catch(RuntimeException uncertain){throw new SignFailure(Failure.UNKNOWN);}
    }
    private static BigDecimal money(String value){try{return new BigDecimal(value).setScale(8,RoundingMode.UNNECESSARY);}catch(RuntimeException invalid){throw rejected();}}
    private static BigDecimal money(JsonNode value){if(!value.isNumber()||(!value.isIntegralNumber()&&!value.isBigDecimal()))throw rejected();return money(value.decimalValue().toPlainString());}
    private static String text(JsonNode node,String name){var value=node.path(name);if(!value.isString())throw rejected();return value.stringValue();}
    private static String hex(JsonNode node,String name){String value=text(node,name);if(!value.matches("[0-9a-f]{64}"))throw rejected();return value;}
    private static long integer(JsonNode node,String name){var value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw rejected();return value.longValue();}
    private static boolean isTrue(JsonNode node,String name){return node.path(name).isBoolean()&&node.path(name).booleanValue();}
    private static boolean isFalse(JsonNode node,String name){return node.path(name).isBoolean()&&!node.path(name).booleanValue();}
    private static boolean base64(String value){try{return value.startsWith("cHNidP8")&&Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value)).equals(value);}catch(RuntimeException invalid){return false;}}
    private static SignFailure rejected(){return new SignFailure(Failure.REJECTED);}
    @Override public void close(){rpc.close();}
}
