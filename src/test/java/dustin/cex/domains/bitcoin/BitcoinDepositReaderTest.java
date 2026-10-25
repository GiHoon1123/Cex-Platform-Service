package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실물 HTTP와 전용 십진수 판독기를 사용해 노드 응답의 모순·정밀도·범위를 확인한다. */
class BitcoinDepositReaderTest {
    static final String TX="11".repeat(32),TIP="aa".repeat(32),BLOCK="bb".repeat(32),ADDRESS="bcrt1qknown";
    static final JsonMapper JSON=JsonMapper.builder().build();
    HttpServer server;ExecutorService executor;BitcoinRpcClient rpc;BitcoinDepositReader reader;
    String mode="NORMAL",amount="0.12345678";AtomicInteger chains=new AtomicInteger(),transactions=new AtomicInteger();
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/wallet/watch",exchange->{
            try{
                var request=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=request.path("method").asString();
                var coin=new BigDecimal(amount);
                Object value=switch(method){
                    case "getblockchaininfo"->Map.of("chain","regtest","initialblockdownload",false,"blocks",104,"bestblockhash",mode.equals("TIP_CHANGED")&&chains.incrementAndGet()>1?BLOCK:TIP);
                    case "getwalletinfo"->Map.of("walletname","watch","private_keys_enabled",false,"descriptors",true,"scanning",false,"lastprocessedblock",Map.of("hash",mode.equals("WALLET_LAG")?BLOCK:TIP,"height",104));
                    case "gettransaction"->{
                        transactions.incrementAndGet();
                        var detail=new HashMap<String,Object>(Map.of("vout",0,"address",ADDRESS,"category","receive","amount",coin));
                        if(mode.equals("DETAIL_MISMATCH"))detail.put("amount",coin.add(BigDecimal.ONE));
                        if(mode.equals("INDEX_OVERFLOW"))detail.put("vout",4294967296L);
                        var output=Map.of("n",0,"value",coin,"scriptPubKey",Map.of("hex","0014"+"ab".repeat(20),"address",mode.equals("ADDRESS_MISMATCH")?"different":ADDRESS));
                        var details=mode.equals("DUPLICATE_OUTPUT")?List.of(detail,detail):List.of(detail);
                        var result=new HashMap<String,Object>(Map.of("txid",TX,"confirmations",3,"blockhash",BLOCK,"blockheight",102,
                            "details",details,"decoded",Map.of("txid",mode.equals("TX_MISMATCH")?TIP:TX,"vin",List.of(Map.of("txid",TIP,"vout",0)),"vout",List.of(output))));
                        result.put("lastprocessedblock",Map.of("hash",mode.equals("TX_VIEW_CHANGED")?BLOCK:TIP,"height",104));
                        if(mode.equals("DEPTH_OVERFLOW"))result.put("confirmations",new java.math.BigInteger("9223372036854775808"));
                        if(mode.equals("BAD_GENERATED"))result.put("generated",true);
                        if(mode.equals("UNCONFIRMED")||mode.equals("CONFLICTED")){result.put("confirmations",mode.equals("CONFLICTED")?-2:0);result.remove("blockhash");result.remove("blockheight");}
                        if(mode.startsWith("FEE_")||mode.equals("SEND_AND_FEE")||mode.equals("COINBASE_WITH_FEE")){
                            Object fee=switch(mode){case "FEE_NULL"->null;case "FEE_STRING"->"-0.0001";case "FEE_PRECISION"->new BigDecimal("0.000000001");case "FEE_OVERFLOW"->new BigDecimal("21000001");case "FEE_POSITIVE"->new BigDecimal("0.25");default->new BigDecimal("-0.0001");};
                            result.put("fee",fee);
                        }
                        if(mode.equals("SEND_AND_FEE")||mode.equals("SEND_WITHOUT_FEE"))result.put("details",List.of(detail,Map.of("category","send","vout",0,"amount",new BigDecimal("-0.12345678"))));
                        if(mode.equals("UNKNOWN_CATEGORY"))result.put("details",List.of(Map.of("category","unknown")));
                        if(mode.equals("COINBASE_WITH_FEE")){result.put("generated",true);result.put("decoded",Map.of("txid",TX,"vin",List.of(Map.of("coinbase","00")),"vout",List.of(output)));}
                        yield result;
                    }
                    case "getblockheader"->Map.of("hash",BLOCK,"height",102,"confirmations",mode.equals("HEADER_DEPTH")?2:3);
                    case "getblockhash"->mode.equals("NON_CANONICAL")?TIP:BLOCK;
                    default->throw new AssertionError(method);
                };
                byte[] response=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.path("id").asString(),"result",value));
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Exception|AssertionError error){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();
        var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"addresses","isolated-bitcoin-reader-password-at-least-32-bytes","watch","REGTEST");
        rpc=new BitcoinRpcClient(settings,JSON);reader=new BitcoinDepositReader(rpc,settings);
    }
    @AfterEach void close(){rpc.close();server.stop(0);executor.shutdownNow();}
    @Test void confirmedOutputHasExactAmountAndExplicitCanonicalPosition(){
        var result=reader.inspect(TX);assertEquals(BitcoinDepositReader.Status.CONFIRMED,result.status());assertEquals(3,result.confirmations());
        assertEquals(102,result.blockHeight());assertEquals(BLOCK,result.blockHash());assertEquals(TIP,result.tipHash());
        assertEquals(new BigDecimal(amount),result.outputs().getFirst().amount());
        assertThrows(UnsupportedOperationException.class,()->result.outputs().clear());
    }
    @Test void largeSatoshiAmountsNeverPassThroughDoubleRounding(){
        for(String value:List.of("20999999.99999999","0.00000001","21000000.00000000","0.00000000")){
            amount=value;var result=reader.inspect(TX);assertEquals(new BigDecimal(value),result.outputs().getFirst().amount());
        }
    }
    @Test void unsupportedPrecisionNegativeAndSupplyOverflowAreRejected(){
        for(String value:List.of("0.000000001","-0.00000001","21000000.00000001","1E+999999999","1E-999999999")){amount=value;unavailable();}
    }
    @Test void decodedTransactionOutputAndReceiveDetailsMustAgree(){
        for(String failure:List.of("DETAIL_MISMATCH","ADDRESS_MISMATCH","TX_MISMATCH","DUPLICATE_OUTPUT","INDEX_OVERFLOW","BAD_GENERATED")){mode=failure;unavailable();}
    }
    @Test void conflictingDepthCanonicalHashAndMovingTipAreRejected(){
        for(String failure:List.of("HEADER_DEPTH","NON_CANONICAL","DEPTH_OVERFLOW","TIP_CHANGED","TX_VIEW_CHANGED")){mode=failure;chains.set(0);unavailable();}
    }
    @Test void laggingWalletCannotGiveApparentlyCurrentObservation(){mode="WALLET_LAG";unavailable();assertEquals(0,transactions.get());}
    @Test void unconfirmedAndConflictedAreNeverGivenCanonicalBlock(){
        for(String state:List.of("UNCONFIRMED","CONFLICTED")){mode=state;var result=reader.inspect(TX);assertNull(result.blockHash());assertNull(result.blockHeight());assertEquals(BitcoinDepositReader.Status.valueOf(state),result.status());}
    }
    @Test void invalidIdentifierAndOuterTransactionDoNotContactNode(){
        for(String invalid:List.of("",TX.toUpperCase(Locale.ROOT)+"x","../wallet","11"))assertThrows(IllegalArgumentException.class,()->reader.inspect(invalid));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->reader.inspect(TX));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0,transactions.get());
    }
    @Test void feeAndSendSignalsNeverBecomeApparentlyExternalReceive(){
        assertFalse(reader.inspect(TX).walletDebit());
        for(String marker:List.of("SEND_AND_FEE","FEE_ONLY")){mode=marker;assertTrue(reader.inspect(TX).walletDebit());}
    }
    @Test void missingContradictoryAndUnparseableWalletDebitSignalsAreRejected(){
        for(String invalid:List.of("SEND_WITHOUT_FEE","UNKNOWN_CATEGORY","FEE_NULL","FEE_STRING","FEE_PRECISION","FEE_OVERFLOW","COINBASE_WITH_FEE")){mode=invalid;unavailable();}
    }
    @Test void signedFeeEvidenceIsSeparateFromReceivedFinancialAmount(){
        mode="FEE_POSITIVE";var result=reader.inspect(TX);assertTrue(result.walletDebit());assertEquals(new BigDecimal("0.12345678"),result.outputs().getFirst().amount());
    }
    void unavailable(){assertEquals(503,assertThrows(ResponseStatusException.class,()->reader.inspect(TX)).getStatusCode().value());}
}
