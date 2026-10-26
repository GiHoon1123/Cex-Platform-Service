package dustin.cex.domains.bitcoin;

import dustin.cex.shared.model.ExactAmount;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** 관찰 전용 Core의 미서명 거래만 만든다. 금액·UTXO·거스름돈·비용을 검증하며 서명·방송·지급 확정은 하지 않는다. */
@Component
public class BitcoinPayoutClient {
    // 자동 준비 경로의 자체 지출/자원 상한이다. 고객의 고정0.01% 요율이나 실제 시장 수수료와는 별개다.
    public static final String POLICY="BTC_WATCH_ONLY_PSBT_V1";
    public static final BigDecimal MAX_RATE=new BigDecimal("100.000"),MAX_FEE=new BigDecimal("0.00010000");
    public static final int MAX_INPUTS=100;
    private static final long BODY_LIMIT=1048576;
    public record Command(UUID withdrawalId,String network,String address,String scriptPubKey,BigDecimal amount) {
        public Command {
            if(withdrawalId==null||network==null||address==null||!address.matches("[A-Za-z0-9]{14,100}")||scriptPubKey==null
                ||!scriptPubKey.matches("(?:[0-9a-f]{2}){2,64}"))throw new IllegalArgumentException("고정 BTC 지급 원본을 확인하세요");
            amount=bitcoin(amount);
        }
        @Override public String toString(){return "BitcoinPayoutCommand[지급 원본 생략]";}
    }
    public record FeeQuote(String network,String wallet,BigDecimal satPerVbyte,String tipHash,long tipHeight,Instant startedAt,Instant finishedAt) {
        public FeeQuote {
            if(network==null||wallet==null||tipHash==null||!tipHash.matches("[0-9a-f]{64}")||tipHeight<0||startedAt==null||finishedAt==null
                ||finishedAt.isBefore(startedAt)||satPerVbyte==null)throw new IllegalArgumentException("현재 노드 비용 근거를 확인하세요");
            satPerVbyte=ExactAmount.requirePositive(satPerVbyte,"노드 바이트당 수수료").setScale(3,RoundingMode.UNNECESSARY);
            if(satPerVbyte.compareTo(MAX_RATE)>0)throw held();
        }
    }
    public record Input(String txid,int vout,String address,String scriptPubKey,String amount) {
        @Override public String toString(){return "BitcoinPayoutInput[수탁 입력 생략]";}
    }
    public record Prepared(UUID withdrawalId,String policy,String network,String wallet,String psbt,String unsignedTxid,String amount,String nativeFee,
            String changeAddress,String changeScript,String changeAmount,List<Input> inputs,FeeQuote quote,Instant finishedAt) {
        public Prepared {inputs=List.copyOf(inputs);}
        @Override public String toString(){return "BitcoinPayoutPrepared[거래 원문 생략]";}
    }
    private final BitcoinSettings settings;
    private final BitcoinRpcClient rpc;
    public BitcoinPayoutClient(BitcoinSettings settings,BitcoinRpcClient rpc){this.settings=settings;this.rpc=rpc;}

    /** 추정 불가/다른 체인·키 지갑은 보류한다. 임의 고정 가격이나 고객 수수료로 대체하지 않는다. */
    public FeeQuote quote() {
        boundary();Instant started=Instant.now();var ready=rpc.ready();var fee=rpc.call("estimatesmartfee",List.of(6,"CONSERVATIVE"));
        if(!fee.isObject()||fee.has("errors")||!fee.path("blocks").isIntegralNumber()||!fee.path("blocks").canConvertToInt()
            ||fee.path("blocks").intValue()<1||fee.path("blocks").intValue()>1008)throw held();
        BigDecimal rate=nodeMoney(fee,"feerate").movePointRight(5);
        // Core의 추정 단위는 BTC/kvB이고 준비 RPC의 fee_rate는 sat/vB다. 이진 실수나 반올림 없이100000배 한다.
        return new FeeQuote(settings.network.id,settings.wallet,rate,hex(ready.chain(),"bestblockhash"),integer(ready.chain(),"blocks"),started,Instant.now());
    }
    /** 입력 선택·PSBT 직렬화는 Core가 담당한다. 이 결과는 아직 영속 후보/UTXO 예약·승인/서명/체인 지급이 아니다. */
    public Prepared prepare(Command command,FeeQuote quote) {
        boundary();Objects.requireNonNull(command,"BTC 지급 원본이 필요합니다");Objects.requireNonNull(quote,"현재 노드 비용 근거가 필요합니다");
        fresh(quote);if(!command.network().equals(settings.network.id))throw held();sameTip(rpc.ready().chain(),quote);
        // 현재 경로는 외부 사업자 출금이다. 자체 수탁 주소를 외부 유출로 분개하는 내부 이체 우회를 막는다.
        var destination=rpc.call("getaddressinfo",List.of(command.address()));
        if(!command.address().equals(text(destination,"address"))||!command.scriptPubKey().equals(text(destination,"scriptPubKey"))
            ||!BitcoinRpcClient.isFalse(destination,"ismine"))throw held();
        var options=new LinkedHashMap<String,Object>();options.put("add_inputs",true);options.put("include_unsafe",false);options.put("minconf",6);
        options.put("fee_rate",quote.satPerVbyte().toPlainString());options.put("change_type","bech32");options.put("changePosition",1);
        // 잠금은 영속 후보와 연결한 다음 단계의 책임이다. RPC 응답 유실로 출처 없는 메모리 잠금을 만들지 않는다.
        options.put("lockUnspents",false);options.put("replaceable",false);options.put("subtractFeeFromOutputs",List.of());
        var created=rpc.call("walletcreatefundedpsbt",List.of(List.of(),List.of(Map.of(command.address(),command.amount().toPlainString())),0,options,true),BODY_LIMIT);
        String psbt=text(created,"psbt");if(psbt.length()>700000||!base64(psbt))throw held();
        BigDecimal fee=nodeMoney(created,"fee");if(fee.compareTo(MAX_FEE)>0)throw held();
        int change=smallInteger(created,"changepos");if(change!=1&&change!=-1)throw held();
        var decoded=rpc.call("decodepsbt",List.of(psbt),BODY_LIMIT);var tx=decoded.path("tx");
        if(smallInteger(tx,"version")!=2||integer(tx,"locktime")!=0||fee.compareTo(nodeMoney(decoded,"fee"))!=0)throw held();
        String txid=hex(tx,"txid");var inputs=tx.path("vin");var outputs=tx.path("vout");var metadata=decoded.path("inputs");
        if(!inputs.isArray()||inputs.isEmpty()||inputs.size()>MAX_INPUTS||!metadata.isArray()||metadata.size()!=inputs.size()
            ||!outputs.isArray()||outputs.size()!=(change==-1?1:2))throw held();
        BigDecimal outputSum=BigDecimal.ZERO;String changeAddress=null,changeScript=null,changeAmount=null;
        for(int index=0;index<outputs.size();index++) {
            var output=outputs.get(index);if(smallInteger(output,"n")!=index)throw held();
            BigDecimal value=nodeMoney(output,"value");outputSum=outputSum.add(value);var script=output.path("scriptPubKey");
            if(index==0) {
                if(value.compareTo(command.amount())!=0||!command.scriptPubKey().equals(text(script,"hex"))||!command.address().equals(text(script,"address")))throw held();
            } else {
                changeAddress=text(script,"address");changeScript=text(script,"hex");changeAmount=value.toPlainString();
                var own=rpc.call("getaddressinfo",List.of(changeAddress));
                if(!changeScript.matches("0014[0-9a-f]{40}")||!changeScript.equals(text(own,"scriptPubKey"))||!changeAddress.equals(text(own,"address"))
                    ||!BitcoinRpcClient.isTrue(own,"ismine")||!BitcoinRpcClient.isTrue(own,"ischange")||!BitcoinRpcClient.isTrue(own,"solvable")
                    ||!BitcoinRpcClient.isTrue(own,"iswitness")||smallInteger(own,"witness_version")!=0)throw held();
            }
        }
        var used=new HashSet<String>();var verified=new ArrayList<Input>();BigDecimal inputSum=BigDecimal.ZERO;
        for(int index=0;index<inputs.size();index++) {
            fresh(quote);var input=inputs.get(index);var info=metadata.get(index);String previous=hex(input,"txid");int vout=smallInteger(input,"vout");
            if(vout<0||!used.add(previous+":"+vout)||integer(input,"sequence")<4294967294L||integer(input,"sequence")>4294967295L
                ||!input.path("scriptSig").isObject()||!"".equals(text(input.path("scriptSig"),"hex"))||input.has("txinwitness")
                ||info.has("partial_signatures")||info.has("final_scriptSig")||info.has("final_scriptwitness")||info.has("taproot_key_path_sig")
                ||info.has("sighash")&&!"ALL".equals(text(info,"sighash")))throw held();
            for(String field:info.propertyNames())if(!Set.of("non_witness_utxo","witness_utxo","bip32_derivs","sighash").contains(field))throw held();
            var output=rpc.call("gettxout",List.of(previous,vout,true));if(!hex(output,"bestblock").equals(quote.tipHash()))throw held();
            if(integer(output,"confirmations")<6||!BitcoinRpcClient.isFalse(output,"coinbase"))throw held();
            BigDecimal amount=nodeMoney(output,"value");var script=output.path("scriptPubKey");String address=text(script,"address"),locking=text(script,"hex");
            var witness=info.path("witness_utxo");
            if(!locking.matches("0014[0-9a-f]{40}")||amount.compareTo(nodeMoney(witness,"amount"))!=0||!locking.equals(text(witness.path("scriptPubKey"),"hex")))throw held();
            if(info.has("non_witness_utxo")) {
                var parent=info.path("non_witness_utxo");var previousOutputs=parent.path("vout");
                if(!previous.equals(hex(parent,"txid"))||!previousOutputs.isArray()||vout>=previousOutputs.size())throw held();
                var spent=previousOutputs.get(vout);
                if(smallInteger(spent,"n")!=vout||amount.compareTo(nodeMoney(spent,"value"))!=0||!locking.equals(text(spent.path("scriptPubKey"),"hex")))throw held();
            }
            var own=rpc.call("getaddressinfo",List.of(address));
            if(!address.equals(text(own,"address"))||!locking.equals(text(own,"scriptPubKey"))||!BitcoinRpcClient.isTrue(own,"ismine")||!BitcoinRpcClient.isTrue(own,"solvable"))throw held();
            inputSum=inputSum.add(amount);verified.add(new Input(previous,vout,address,locking,amount.toPlainString()));
        }
        if(inputSum.compareTo(new BigDecimal("21000000"))>0||inputSum.subtract(outputSum).compareTo(fee)!=0)throw held();
        fresh(quote);sameTip(rpc.call("getblockchaininfo",List.of()),quote);
        return new Prepared(command.withdrawalId(),POLICY,command.network(),settings.wallet,psbt,txid,command.amount().toPlainString(),fee.toPlainString(),changeAddress,changeScript,changeAmount,verified,quote,Instant.now());
    }
    private void fresh(FeeQuote quote) {
        Instant now=Instant.now();if(!quote.network().equals(settings.network.id)||!quote.wallet().equals(settings.wallet)
            ||quote.startedAt().isAfter(now)||quote.finishedAt().isAfter(now)||quote.startedAt().isBefore(now.minusSeconds(30)))throw held();
    }
    private static void sameTip(JsonNode chain,FeeQuote quote){if(!hex(chain,"bestblockhash").equals(quote.tipHash())||integer(chain,"blocks")!=quote.tipHeight())throw held();}
    private static boolean base64(String value){try{return Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value)).equals(value)&&value.startsWith("cHNidP8");}catch(IllegalArgumentException invalid){return false;}}
    private static BigDecimal bitcoin(BigDecimal value){try{var result=ExactAmount.requirePositive(value,"BTC 금액");if(result.compareTo(new BigDecimal("21000000"))>0)throw held();return result.setScale(8,RoundingMode.UNNECESSARY);}catch(RuntimeException invalid){throw held();}}
    // 기존 입금 판독과 같은 BTC/정밀도 계약을 쓴다. 현재 지급 준비에서 필요한 양수 조건만 덧붙인다.
    private static BigDecimal nodeMoney(JsonNode node,String name){var value=BitcoinAmounts.fromNode(node.path(name));if(value.signum()<=0)throw held();return value;}
    private static String text(JsonNode node,String name){var value=node.path(name);if(!value.isString())throw held();return value.stringValue();}
    private static String hex(JsonNode node,String name){String value=text(node,name);if(!value.matches("[0-9a-f]{64}"))throw held();return value;}
    private static long integer(JsonNode node,String name){var value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw held();return value.longValue();}
    private static int smallInteger(JsonNode node,String name){var value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToInt())throw held();return value.intValue();}
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("지급 거래 준비는 DB 트랜잭션 밖에서 실행하세요");}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"현재 노드 비용·고정 지급·수탁 입력/거스름돈을 확인하세요");}
}
