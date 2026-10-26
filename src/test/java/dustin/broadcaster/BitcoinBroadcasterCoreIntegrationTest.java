package dustin.broadcaster;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 소유 Core31.1 관찰 지갑으로 실제 방송과 응답 유실 뒤의 식별자 재조회를 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_BROADCAST_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinBroadcasterCoreIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();BitcoinBroadcastRpcClient client;tools.jackson.databind.JsonNode fixture;
    @BeforeEach void setup(){fixture=JSON.readTree(System.getenv("CEX_BITCOIN_BROADCAST_TEST_FIXTURE"));client=new BitcoinBroadcastRpcClient(new BitcoinBroadcastSettings(System.getenv("CEX_BITCOIN_BROADCAST_TEST_URL"),System.getenv("CEX_BITCOIN_BROADCAST_TEST_USER"),System.getenv("CEX_BITCOIN_BROADCAST_TEST_PASSWORD"),"watch","REGTEST"),JSON);}
    @AfterEach void close(){client.close();}
    @Test void signedTransactionIsAcceptedByTheRealNode(){var result=client.publish(fixture.path("txid").asString(),fixture.path("raw").asString());assertEquals(BitcoinBroadcastRpcClient.Status.ACCEPTED,result.status());assertEquals(fixture.path("txid").asString(),result.txid());}
    @Test void aRepeatedPublishUsesWalletHistoryInsteadOfSendingAgain(){assertEquals(BitcoinBroadcastRpcClient.Status.ACCEPTED,client.publish(fixture.path("txid").asString(),fixture.path("raw").asString()).status());assertEquals(BitcoinBroadcastRpcClient.Status.ACCEPTED,client.publish(fixture.path("txid").asString(),fixture.path("raw").asString()).status());}
    @Test void broadcasterCredentialsCannotPrepareSignExportOrCreateWallet()throws Exception{try(var http=HttpClient.newHttpClient()){String auth=System.getenv("CEX_BITCOIN_BROADCAST_TEST_USER")+":"+System.getenv("CEX_BITCOIN_BROADCAST_TEST_PASSWORD");for(String method:List.of("walletcreatefundedpsbt","walletprocesspsbt","listdescriptors","dumpprivkey","createwallet","sendtoaddress","lockunspent")){var request=HttpRequest.newBuilder(URI.create(System.getenv("CEX_BITCOIN_BROADCAST_TEST_URL")+"/wallet/watch")).header("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","id","permissions","method",method,"params",List.of())))).build();assertEquals(403,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),method);}}}
}
