package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 합성 HTTP 노드로 RPC의 고정 지급·정확 금액·입력/거스름돈·오류 경계를 검사한다. 실제 PSBT는 별도 Core 시험에서 확인한다. */
@org.junit.jupiter.api.parallel.Isolated
class BitcoinPayoutClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String TARGET="bcrt1qsyntheticrecipient",SOURCE="bcrt1qsyntheticcustody",CHANGE="bcrt1qsyntheticchange";
    static final String DEST_SCRIPT="0014"+"ab".repeat(20),INPUT_SCRIPT="0014"+"cd".repeat(20),CHANGE_SCRIPT="0014"+"ef".repeat(20);
    static final String TIP="a".repeat(64),INPUT_TX="b".repeat(64),UNSIGNED_TX="c".repeat(64),PSBT="cHNidP8BAAA=",PASSWORD="isolated-payout-node-password-at-least-32-bytes";
    HttpServer server;ExecutorService threads;BitcoinRpcClient rpc;BitcoinPayoutClient client;
    String mode="NORMAL";List<String> calls=new CopyOnWriteArrayList<>();List<JsonNode> funding=new CopyOnWriteArrayList<>();
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        server.createContext("/wallet/watch",exchange->{
            try {
                var request=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=request.path("method").stringValue();calls.add(method);
                assertEquals("Basic "+Base64.getEncoder().encodeToString(("payout:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                Object result=switch(method) {
                    case "getblockchaininfo" -> Map.of("chain",mode.equals("CHAIN")?"main":"regtest","initialblockdownload",false,"bestblockhash",mode.equals("TIP")&&calls.contains("decodepsbt")?"d".repeat(64):TIP,"blocks",200);
                    case "getwalletinfo" -> Map.of("walletname","watch","private_keys_enabled",mode.equals("PRIVATE"),"descriptors",true,"scanning",false);
                    case "estimatesmartfee" -> {assertEquals("6",request.path("params").get(0).asString());assertEquals("CONSERVATIVE",request.path("params").get(1).asString());yield mode.equals("NO_ESTIMATE")?Map.of("errors",List.of("Insufficient data"),"blocks",6):Map.of("feerate",new BigDecimal(mode.equals("HIGH_RATE")?"0.00100001":"0.00002000"),"blocks",6);}
                    case "walletcreatefundedpsbt" -> {funding.add(request.path("params"));yield Map.of("psbt",mode.equals("BAD_BASE64")?"not-psbt":PSBT,"fee",new BigDecimal(mode.equals("HIGH_FEE")?"0.00010001":"0.00000282"),"changepos",1);}
                    case "decodepsbt" -> decoded();
                    case "gettxout" -> Map.of("bestblock",mode.equals("INPUT_TIP")?"d".repeat(64):TIP,"confirmations",mode.equals("SHALLOW")?5:6,"coinbase",mode.equals("COINBASE"),"value",new BigDecimal(mode.equals("INPUT_VALUE")?"0.05000001":"0.05000000"),"scriptPubKey",script(SOURCE,INPUT_SCRIPT));
                    case "getaddressinfo" -> own(request.path("params").get(0).stringValue());
                    default -> throw new AssertionError("예상하지 않은 키 사용/방송 RPC: "+method);
                };
                String body=JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",mode.equals("ID")?"different":request.path("id").stringValue(),"result",result));
                if(mode.equals("DUPLICATE"))body=body.replaceFirst("\\{","{\"jsonrpc\":\"2.0\",");
                if(mode.equals("TRAILING"))body+=" {}";
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception|AssertionError failure){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"payout",PASSWORD,"watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);client=new BitcoinPayoutClient(settings,rpc);
    }
    @AfterEach void stop(){rpc.close();server.stop(0);threads.shutdownNow();assertTrue(calls.stream().noneMatch(m->Set.of("walletprocesspsbt","signrawtransactionwithwallet","sendrawtransaction","sendtoaddress","lockunspent").contains(m)));}
    Map<String,Object> script(String address,String hex){return Map.of("address",address,"hex",hex,"type","witness_v0_keyhash");}
    Map<String,Object> own(String address) {
        if(address.equals(TARGET))return Map.of("address",TARGET,"scriptPubKey",DEST_SCRIPT,"ismine",mode.equals("OWN_TARGET"),"solvable",false);
        boolean change=address.equals(CHANGE);return Map.of("address",address,"scriptPubKey",change?CHANGE_SCRIPT:INPUT_SCRIPT,
            "ismine",!(mode.equals("FOREIGN_CHANGE")&&change||mode.equals("FOREIGN_INPUT")&&!change),"ischange",change&&!mode.equals("EXTERNAL_CHANGE"),"solvable",!mode.equals("UNSOLVABLE"),"iswitness",true,"witness_version",0);
    }
    JsonNode decoded() {
        var source=script(SOURCE,INPUT_SCRIPT);var vin=new LinkedHashMap<String,Object>();vin.put("txid",INPUT_TX);vin.put("vout",0);vin.put("sequence",mode.equals("RBF")?4294967293L:4294967294L);vin.put("scriptSig",Map.of("asm","","hex",mode.equals("SIGNED_TX")?"abcd":""));
        if(mode.equals("WITNESS_TX"))vin.put("txinwitness",List.of("abcd"));
        var witness=new LinkedHashMap<String,Object>();witness.put("amount",new BigDecimal(mode.equals("WITNESS_VALUE")?"0.04999999":"0.05"));witness.put("scriptPubKey",source);
        var input=new LinkedHashMap<String,Object>();input.put("witness_utxo",witness);input.put("bip32_derivs",List.of());
        input.put("non_witness_utxo",Map.of("txid",mode.equals("PARENT_ID")?"d".repeat(64):INPUT_TX,"vout",List.of(Map.of("n",0,"value",new BigDecimal(mode.equals("PARENT_VALUE")?"0.04999999":"0.05"),"scriptPubKey",source))));
        if(mode.equals("PARTIAL"))input.put("partial_signatures",Map.of("pubkey","signature"));
        if(mode.equals("FINAL"))input.put("final_scriptwitness",List.of("signature"));
        if(mode.equals("UNKNOWN_INPUT"))input.put("unknown",Map.of("unreviewed","data"));
        if(mode.equals("SIGHASH"))input.put("sighash","NONE");
        var inputs=mode.equals("DUPLICATE_INPUT")?List.of(vin,vin):List.of(vin);var metadata=mode.equals("DUPLICATE_INPUT")?List.of(input,input):List.of(input);
        var outs=new ArrayList<Object>();outs.add(Map.of("n",0,"value",new BigDecimal(mode.equals("NET")?"0.00999999":"0.01"),"scriptPubKey",script(mode.equals("DESTINATION")?"bcrt1qotherrecipient":TARGET,mode.equals("SCRIPT")?INPUT_SCRIPT:DEST_SCRIPT)));
        outs.add(Map.of("n",1,"value",new BigDecimal(mode.equals("SUM")?"0.03999717":"0.03999718"),"scriptPubKey",script(CHANGE,CHANGE_SCRIPT)));
        if(mode.equals("EXTRA_OUTPUT"))outs.add(Map.of("n",2,"value",new BigDecimal("0.001"),"scriptPubKey",source));
        return JSON.valueToTree(Map.of("tx",Map.of("txid",UNSIGNED_TX,"version",mode.equals("VERSION")?3:2,"locktime",mode.equals("LOCKTIME")?1:0,"vin",inputs,"vout",outs),"inputs",metadata,"outputs",List.of(Map.of(),Map.of()),"fee",new BigDecimal(mode.equals("FEE_CONFLICT")?"0.00000281":"0.00000282")));
    }
    BitcoinPayoutClient.Command command(){return new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",TARGET,DEST_SCRIPT,new BigDecimal("0.01"));}
    BitcoinPayoutClient.Prepared prepare(){return client.prepare(command(),client.quote());}
    void unavailable(){assertEquals(503,assertThrows(ResponseStatusException.class,this::prepare).getStatusCode().value());}
    @Test void coreSelectsCoinsAndExactNetChangeAndNativeCostAreVerified() {
        var prepared=prepare();assertEquals("0.01000000",prepared.amount());assertEquals("0.00000282",prepared.nativeFee());assertEquals("0.03999718",prepared.changeAmount());assertEquals(CHANGE,prepared.changeAddress());assertEquals(1,prepared.inputs().size());assertEquals(INPUT_TX,prepared.inputs().getFirst().txid());assertEquals(UNSIGNED_TX,prepared.unsignedTxid());
        var args=funding.getFirst();assertTrue(args.get(0).isEmpty());assertEquals("0.01000000",args.get(1).get(0).path(TARGET).stringValue());assertEquals("2.000",args.get(3).path("fee_rate").stringValue());
        for(String key:List.of("lockUnspents","replaceable","include_unsafe"))assertFalse(args.get(3).path(key).booleanValue());assertEquals(6,args.get(3).path("minconf").intValue());assertTrue(args.get(3).path("subtractFeeFromOutputs").isEmpty());assertFalse(args.get(3).has("includeWatching"));
        assertThrows(UnsupportedOperationException.class,()->prepared.inputs().clear());assertFalse(prepared.toString().contains(TARGET));assertFalse(prepared.toString().contains(PSBT));assertFalse(command().toString().contains(TARGET));
    }
    @Test void nodeBtcPerKilobyteEstimateIsConvertedExactlyToSatPerVbyte(){var quote=client.quote();assertEquals(new BigDecimal("2.000"),quote.satPerVbyte());assertEquals(200,quote.tipHeight());assertEquals(TIP,quote.tipHash());}
    @Test void missingEstimateCannotUseCustomerFeeOrAFixedFallback(){mode="NO_ESTIMATE";unavailable();assertFalse(calls.contains("walletcreatefundedpsbt"));}
    @Test void ownRateBudgetRejectsPreparationBeforeCoinSelection(){mode="HIGH_RATE";unavailable();assertFalse(calls.contains("walletcreatefundedpsbt"));}
    @Test void nativeAbsoluteCostAboveTheBudgetIsHeld(){mode="HIGH_FEE";unavailable();assertFalse(calls.contains("decodepsbt"));}
    @Test void amountAndDestinationCannotBeChangedByTheNode(){for(String value:List.of("NET","DESTINATION","SCRIPT")){mode=value;unavailable();}}
    @Test void extraOutputsCannotHideAnotherRecipient(){mode="EXTRA_OUTPUT";unavailable();}
    @Test void changeMustBeAnOwnedInternalWalletOutput(){for(String value:List.of("FOREIGN_CHANGE","EXTERNAL_CHANGE","UNSOLVABLE")){mode=value;unavailable();}}
    @Test void actualInputsMustBeOwnedSafeAndMatureNonCoinbase(){for(String value:List.of("FOREIGN_INPUT","SHALLOW","COINBASE")){mode=value;unavailable();}}
    @Test void duplicateInputCannotBeCountedTwice(){mode="DUPLICATE_INPUT";unavailable();}
    @Test void inputAndWitnessValuesAndTheActualFeeMustAllAgree(){for(String value:List.of("INPUT_VALUE","WITNESS_VALUE","SUM","FEE_CONFLICT")){mode=value;unavailable();}}
    @Test void includedParentTransactionMustDescribeTheSameSpentOutput(){for(String value:List.of("PARENT_ID","PARENT_VALUE")){mode=value;unavailable();}}
    @Test void alreadySignedOrUnreviewedPsbtMetadataIsRejected(){for(String value:List.of("SIGNED_TX","WITNESS_TX","PARTIAL","FINAL","UNKNOWN_INPUT","SIGHASH")){mode=value;unavailable();}}
    @Test void rbfLocktimeOrVersionCannotChangeThePreparedPolicy(){for(String value:List.of("RBF","LOCKTIME","VERSION")){mode=value;unavailable();}}
    @Test void aChangedTipDuringReadsCannotMixChainSnapshots(){for(String value:List.of("TIP","INPUT_TIP")){mode=value;unavailable();}}
    @Test void malformedPsbtIsNotAcceptedAsAnOpaqueSuccess(){mode="BAD_BASE64";unavailable();}
    @Test void nodeIdentityAndPrivateWalletCannotReachCoinSelection(){for(String value:List.of("CHAIN","PRIVATE","ID")){mode=value;unavailable();}assertFalse(calls.contains("walletcreatefundedpsbt"));}
    @Test void duplicateAndTrailingRpcJsonAreNotSilentlyAccepted(){for(String value:List.of("DUPLICATE","TRAILING")){mode=value;unavailable();}assertFalse(calls.contains("walletcreatefundedpsbt"));}
    @Test void expiredFutureOrDifferentWalletFeeCannotStartPreparation() {
        for(var quote:List.of(new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),TIP,200,Instant.now().minusSeconds(31),Instant.now()),
            new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),TIP,200,Instant.now().plusSeconds(3600),Instant.now().plusSeconds(3601)),
            new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","other",new BigDecimal("2"),TIP,200,Instant.now(),Instant.now())))
            assertThrows(ResponseStatusException.class,()->client.prepare(command(),quote));assertTrue(calls.isEmpty());
    }
    @Test void activeDatabaseTransactionCannotStartNodeIo() {
        var quote=client.quote();calls.clear();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->client.quote());assertThrows(IllegalStateException.class,()->client.prepare(command(),quote));}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertTrue(calls.isEmpty());
    }
    @Test void inexactOrExtremeAmountsCannotAllocateUnboundedDecimalValues() {
        for(String value:List.of("0","-1","0.000000001","21000000.00000001","1e999999999","1e-999999999"))assertThrows(RuntimeException.class,()->new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",TARGET,DEST_SCRIPT,new BigDecimal(value)));
        assertTrue(calls.isEmpty());
    }
    @Test void anOwnedDestinationCannotBeTreatedAsAnExternalPayout(){mode="OWN_TARGET";unavailable();assertFalse(calls.contains("walletcreatefundedpsbt"));}

}
