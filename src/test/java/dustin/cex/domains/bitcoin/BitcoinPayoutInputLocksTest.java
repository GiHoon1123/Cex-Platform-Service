package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 소유 HTTP 대역으로 응답 유실과 고정 입력만의 잠금/해제를 확인한다. 금융 배정 권한은 이 클라이언트의 역할이 아니다. */
@org.junit.jupiter.api.parallel.Isolated
class BitcoinPayoutInputLocksTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String ID="a".repeat(64),OTHER="b".repeat(64),ADDRESS="bcrt1qsyntheticcustody",SCRIPT="0014"+"cd".repeat(20);
    HttpServer server;ExecutorService executor;BitcoinRpcClient rpc;BitcoinPayoutInputLocks locks;
    List<String> calls=new CopyOnWriteArrayList<>();List<JsonNode> mutations=new CopyOnWriteArrayList<>();
    Set<String> held=ConcurrentHashMap.newKeySet();String mode="NORMAL";
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/wallet/watch",exchange->{
            try {
                var request=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=request.path("method").stringValue();calls.add(method);
                Object result=switch(method) {
                    case "getblockchaininfo" -> Map.of("chain",mode.equals("CHAIN")?"main":"regtest","initialblockdownload",false);
                    case "getwalletinfo" -> Map.of("walletname","watch","private_keys_enabled",mode.equals("PRIVATE"),"descriptors",true,"scanning",false);
                    case "gettxout" -> mode.equals("SPENT")?null:Map.of("confirmations",mode.equals("SHALLOW")?5:6,"coinbase",mode.equals("COINBASE"),"value",new BigDecimal(mode.equals("VALUE")?"0.04999999":"0.05"),"scriptPubKey",Map.of("hex",mode.equals("SCRIPT")?"0014"+"00".repeat(20):SCRIPT,"address",mode.equals("ADDRESS")?"bcrt1qothercustody":ADDRESS));
                    case "getaddressinfo" -> Map.of("address",ADDRESS,"scriptPubKey",SCRIPT,"ismine",!mode.equals("FOREIGN"),"solvable",!mode.equals("UNSOLVABLE"));
                    case "listlockunspent" -> {
                        if(mode.equals("READ_FAILURE")||mode.equals("AFTER_FAILURE")&&!mutations.isEmpty()){throw new java.io.IOException("시험용 응답 유실");}
                        if(mode.equals("DUPLICATE_LIST"))yield List.of(Map.of("txid",ID,"vout",0),Map.of("txid",ID,"vout",0));
                        if(mode.equals("NEGATIVE_LIST"))yield List.of(Map.of("txid",ID,"vout",-1));
                        if(mode.equals("HUGE_LIST"))yield Collections.nCopies(10001,Map.of("txid",ID,"vout",0));
                        if(mode.equals("BAD_LIST"))yield Map.of();
                        yield held.stream().sorted().map(p->Map.of("txid",p.substring(0,64),"vout",Integer.parseInt(p.substring(65)))).toList();
                    }
                    case "lockunspent" -> {
                        var params=request.path("params");mutations.add(params);boolean unlock=params.get(0).booleanValue();assertFalse(params.get(1).isEmpty());
                        if(!unlock)assertTrue(params.get(2).booleanValue());
                        if(!mode.equals("NO_EFFECT"))for(var point:params.get(1)){String p=point.path("txid").stringValue()+":"+point.path("vout").intValue();if(unlock)held.remove(p);else held.add(p);}
                        if(mode.equals("LOST_ACK")){throw new java.io.IOException("시험용 응답 유실");}
                        yield !mode.equals("FALSE_ACK");
                    }
                    default -> throw new AssertionError(method);
                };
                var body=new LinkedHashMap<String,Object>();body.put("jsonrpc","2.0");body.put("id",request.path("id").stringValue());body.put("result",result);
                byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception|AssertionError failure){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"payout","isolated-lock-password-at-least-32-bytes","watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);locks=new BitcoinPayoutInputLocks(settings,rpc);
    }
    @AfterEach void stop(){rpc.close();server.stop(0);executor.shutdownNow();assertTrue(calls.stream().allMatch(Set.of("getblockchaininfo","getwalletinfo","gettxout","getaddressinfo","listlockunspent","lockunspent")::contains));}
    BitcoinPayoutClient.Input input(){return new BitcoinPayoutClient.Input(ID,0,ADDRESS,SCRIPT,"0.05000000");}
    BitcoinPayoutInputLocks.Observation ensure(){return locks.ensure("bitcoin-regtest","watch",List.of(input()));}
    BitcoinPayoutInputLocks.Observation release(){return locks.release("bitcoin-regtest","watch",List.of(input()));}
    void unavailable(Runnable action){assertEquals(503,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
    @Test void aNewLockRequestsPersistenceAndObservesTheExactInput(){var o=ensure();assertTrue(o.locked());assertEquals(List.of(ID+":0"),o.inputs());assertEquals("bitcoin-regtest",o.network());assertEquals("watch",o.wallet());assertNotNull(o.observedAt());assertEquals(1,mutations.size());assertTrue(mutations.getFirst().get(2).booleanValue());assertThrows(UnsupportedOperationException.class,()->o.inputs().clear());assertFalse(o.toString().contains(ID));}
    @Test void repeatEnsureReadsExistingLocksWithoutUnlockingOrMutating(){ensure();mutations.clear();ensure();assertTrue(mutations.isEmpty());assertEquals(Set.of(ID+":0"),held);}
    @Test void existingOtherLocksAreNeitherAdoptedNorReleased(){held.add(OTHER+":1");ensure();assertEquals(1,mutations.getFirst().get(1).size());release();assertEquals(Set.of(OTHER+":1"),held);}
    @Test void onlyMissingInputsAreSentToCore(){held.add(ID+":0");var second=new BitcoinPayoutClient.Input(OTHER,2,ADDRESS,SCRIPT,"0.05000000");var o=locks.ensure("bitcoin-regtest","watch",List.of(second,input()));assertEquals(2,o.inputs().size());assertEquals(OTHER,mutations.getFirst().get(1).get(0).path("txid").stringValue());assertEquals(1,mutations.getFirst().get(1).size());}
    @Test void lostLockResponseRequiresAnActualStateObservation(){mode="LOST_ACK";assertTrue(ensure().locked());assertEquals(1,mutations.size());}
    @Test void falseAcknowledgementIsResolvedByActualState(){mode="FALSE_ACK";assertTrue(ensure().locked());}
    @Test void aSuccessfulAcknowledgementWithNoStateChangeCannotClaimALock(){mode="NO_EFFECT";unavailable(this::ensure);assertEquals(1,mutations.size());}
    @Test void anUnreadablePostMutationStateStaysUnknown(){mode="AFTER_FAILURE";unavailable(this::ensure);assertEquals(Set.of(ID+":0"),held);}
    @Test void anUnreadableInitialStateCannotStartAMutation(){mode="READ_FAILURE";unavailable(this::ensure);assertTrue(mutations.isEmpty());}
    @Test void lostUnlockResponseIsResolvedWithoutClearingForeignLocks(){held.addAll(Set.of(ID+":0",OTHER+":1"));mode="LOST_ACK";assertFalse(release().locked());assertEquals(Set.of(OTHER+":1"),held);assertEquals(2,mutations.getFirst().size());}
    @Test void absentLocksNeverSendTheWalletWideUnlockForm(){assertFalse(release().locked());assertTrue(mutations.isEmpty());}
    @Test void failedUnlockLeavesTheInputHeld(){held.add(ID+":0");mode="NO_EFFECT";unavailable(this::release);assertEquals(Set.of(ID+":0"),held);}
    @Test void spentOrUnsafeOrForeignInputsCannotBeLocked(){for(String m:List.of("SPENT","SHALLOW","COINBASE","VALUE","SCRIPT","ADDRESS","FOREIGN","UNSOLVABLE")){mode=m;unavailable(this::ensure);}assertTrue(mutations.isEmpty());}
    @Test void malformedLockListsFailBeforeMutation(){for(String m:List.of("BAD_LIST","DUPLICATE_LIST","NEGATIVE_LIST","HUGE_LIST")){mode=m;unavailable(this::ensure);}assertTrue(mutations.isEmpty());}
    @Test void wrongChainOrPrivateKeyWalletCannotBeMutated(){for(String m:List.of("CHAIN","PRIVATE")){mode=m;unavailable(this::ensure);unavailable(this::release);}assertTrue(mutations.isEmpty());}
    @Test void wrongScopeEmptyAndDuplicateInputsNeverReachTheNode(){unavailable(()->locks.ensure("bitcoin-mainnet","watch",List.of(input())));unavailable(()->locks.release("bitcoin-regtest","other",List.of(input())));unavailable(()->locks.release("bitcoin-regtest","watch",List.of()));unavailable(()->locks.ensure("bitcoin-regtest","watch",List.of(input(),input())));assertTrue(calls.isEmpty());}
    @Test void invalidAmountsAndOutpointsNeverReachTheNode(){for(String amount:List.of("0","-1","0.000000001","21000000.00000001","1e999999999","1e-999999999")){var i=new BitcoinPayoutClient.Input(ID,0,ADDRESS,SCRIPT,amount);unavailable(()->locks.ensure("bitcoin-regtest","watch",List.of(i)));}var invalid=new BitcoinPayoutClient.Input(ID,-1,ADDRESS,SCRIPT,"0.05");unavailable(()->locks.release("bitcoin-regtest","watch",List.of(invalid)));assertTrue(calls.isEmpty());}
    @Test void databaseTransactionsCannotHoldLocksAcrossNetworkIo(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,this::ensure);assertThrows(IllegalStateException.class,this::release);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertTrue(calls.isEmpty());}
}
