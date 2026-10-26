package dustin.confirmer;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 소유 Core31.1의 실제 블록에서 거래 ID·블록·끝 높이·여섯 확인을 함께 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_CONFIRM_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinConfirmerCoreIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();BitcoinConfirmationRpcClient client;String txid;
    @BeforeEach void setup(){txid=System.getenv("CEX_BITCOIN_CONFIRM_TEST_TXID");client=new BitcoinConfirmationRpcClient(settings("REGTEST"),JSON);}
    @AfterEach void close(){client.close();}
    BitcoinConfirmationSettings settings(String network){return new BitcoinConfirmationSettings(System.getenv("CEX_BITCOIN_CONFIRM_TEST_URL"),System.getenv("CEX_BITCOIN_CONFIRM_TEST_USER"),System.getenv("CEX_BITCOIN_CONFIRM_TEST_PASSWORD"),network,6);}
    @Test void realSixConfirmationTransactionProducesExactBlockEvidence(){var result=client.observe(txid);assertEquals(BitcoinConfirmationRpcClient.Status.CONFIRMED,result.status());assertEquals(txid,result.txid());assertTrue(result.blockHash().matches("[0-9a-f]{64}"));assertEquals(6,result.confirmations());assertTrue(result.blockHeight()>0);}
    @Test void anotherExpectedNetworkCannotConfirmTheTransaction(){try(var wrong=new BitcoinConfirmationRpcClient(settings("MAINNET"),JSON)){assertEquals(BitcoinConfirmationRpcClient.Status.UNKNOWN,wrong.observe(txid).status());}}
    @Test void confirmerCredentialsCannotPrepareSignBroadcastOrCreateWallet()throws Exception{try(var http=HttpClient.newHttpClient()){String auth=System.getenv("CEX_BITCOIN_CONFIRM_TEST_USER")+":"+System.getenv("CEX_BITCOIN_CONFIRM_TEST_PASSWORD");for(String method:List.of("walletcreatefundedpsbt","walletprocesspsbt","sendrawtransaction","listdescriptors","dumpprivkey","createwallet","lockunspent")){var request=HttpRequest.newBuilder(URI.create(System.getenv("CEX_BITCOIN_CONFIRM_TEST_URL"))).header("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","id","permissions","method",method,"params",List.of())))).build();assertEquals(403,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),method);}}}
}
