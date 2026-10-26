package dustin.signer;

import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.bitcoin.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 소유 Core31.1의 개인키 지갑이 고정 PSBT만 서명하고 방송·준비·키 내보내기 권한은 갖지 않는지 확인한다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_SIGNER_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinSignerCoreIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();BitcoinSignerRpcClient client;tools.jackson.databind.JsonNode fixture;
    @BeforeEach void setup(){fixture=JSON.readTree(System.getenv("CEX_BITCOIN_SIGNER_TEST_FIXTURE"));client=new BitcoinSignerRpcClient(settings("REGTEST"),JSON);}
    @AfterEach void close(){client.close();}
    BitcoinSignerSettings settings(String network){return new BitcoinSignerSettings(System.getenv("CEX_BITCOIN_SIGNER_TEST_URL"),System.getenv("CEX_BITCOIN_SIGNER_TEST_USER"),System.getenv("CEX_BITCOIN_SIGNER_TEST_PASSWORD"),"keys",network);}
    BitcoinPayoutExecutionRecords.Payload payload(){
        var inputs=new ArrayList<BitcoinPayoutClient.Input>();for(var i:fixture.path("inputs"))inputs.add(new BitcoinPayoutClient.Input(i.path("txid").asString(),i.path("vout").asInt(),i.path("address").asString(),i.path("script").asString(),i.path("amount").asString()));
        var prepared=new BitcoinPayoutClient.Prepared(UUID.fromString(fixture.path("withdrawal").asString()),BitcoinPayoutClient.POLICY,"bitcoin-regtest","watch",fixture.path("psbt").asString(),fixture.path("txid").asString(),fixture.path("amount").asString(),fixture.path("fee").asString(),fixture.path("changeAddress").isNull()?null:fixture.path("changeAddress").asString(),fixture.path("changeScript").isNull()?null:fixture.path("changeScript").asString(),fixture.path("changeAmount").isNull()?null:fixture.path("changeAmount").asString(),inputs,new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new java.math.BigDecimal("2.000"),"a".repeat(64),1,Instant.now(),Instant.now()),Instant.now());
        var funds=new BitcoinWithdrawalPayoutBasis.Funds(prepared.withdrawalId(),1,1,"bitcoin-regtest",fixture.path("address").asString(),fixture.path("script").asString(),prepared.amount(),"0.00000100",new java.math.BigDecimal(prepared.amount()).add(new java.math.BigDecimal("0.00000100")).toPlainString(),"b".repeat(64),"event","c".repeat(64),UUID.randomUUID());
        return new BitcoinPayoutExecutionRecords.Payload(BitcoinPayoutExecutionRecords.POLICY,prepared.withdrawalId(),UUID.randomUUID(),"d".repeat(64),UUID.randomUUID(),"e".repeat(64),"f".repeat(64),UUID.randomUUID(),"a".repeat(64),null,funds,prepared,List.of(),"0.00000182",Instant.now());
    }
    @Test void realPrivateKeyWalletSignsTheExactFixedPayment(){var signed=client.sign(payload());assertEquals(fixture.path("txid").asString(),signed.txid());assertTrue(signed.rawTransaction().matches("[0-9a-f]+"));assertTrue(signed.signedPsbt().startsWith("cHNidP8"));}
    @Test void anotherExpectedNetworkCannotUseTheSigner(){try(var wrong=new BitcoinSignerRpcClient(settings("MAINNET"),JSON)){assertEquals(BitcoinSignerRpcClient.Failure.REJECTED,assertThrows(BitcoinSignerRpcClient.SignFailure.class,()->wrong.sign(payload())).failure);}}
    @Test void signerCredentialsCannotPrepareBroadcastOrExportKeys()throws Exception{
        try(var http=HttpClient.newHttpClient()){String auth=System.getenv("CEX_BITCOIN_SIGNER_TEST_USER")+":"+System.getenv("CEX_BITCOIN_SIGNER_TEST_PASSWORD");
            for(String method:List.of("walletcreatefundedpsbt","sendrawtransaction","sendtoaddress","listdescriptors","dumpprivkey","createwallet","lockunspent")){
                var request=HttpRequest.newBuilder(URI.create(System.getenv("CEX_BITCOIN_SIGNER_TEST_URL")+"/wallet/keys")).header("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","id","permissions","method",method,"params",List.of())))).build();assertEquals(403,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),method);
            }
        }
    }
}
