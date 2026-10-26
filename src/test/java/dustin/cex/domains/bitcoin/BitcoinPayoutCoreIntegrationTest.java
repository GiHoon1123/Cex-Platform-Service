package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 소유 Core31.1의 실제 공개 디스크립터/UTXO/PSBT만 사용한다. 준비용 비용2sat/vB는 합성 기준이며 실시간 노드 추정으로 주장하지 않는다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_PAYOUT_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinPayoutCoreIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    BitcoinRpcClient rpc;BitcoinPayoutClient client;BitcoinSettings settings;
    @BeforeEach void setup(){settings=settings("watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);client=new BitcoinPayoutClient(settings,rpc);}
    BitcoinSettings settings(String wallet,String network){return new BitcoinSettings(System.getenv("CEX_BITCOIN_PAYOUT_TEST_URL"),System.getenv("CEX_BITCOIN_PAYOUT_TEST_USER"),System.getenv("CEX_BITCOIN_PAYOUT_TEST_PASSWORD"),wallet,network);}
    @AfterEach void close(){rpc.close();}
    BitcoinPayoutClient.FeeQuote fixtureFee(){var chain=rpc.ready().chain();return new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2.000"),chain.path("bestblockhash").stringValue(),chain.path("blocks").longValue(),Instant.now(),Instant.now());}
    BitcoinPayoutClient.Command command(){var item=JSON.readTree(System.getenv("CEX_BITCOIN_PAYOUT_TEST_DESTINATIONS")).get(0);return new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",item.path("address").stringValue(),item.path("scriptPubKey").stringValue(),new BigDecimal("0.01000000"));}
    @Test void realWatchOnlyCorePreparesAnUnsignedExactPaymentAndOwnedChange() {
        assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());var p=client.prepare(command(),fixtureFee());assertEquals("0.01000000",p.amount());assertNotNull(p.changeAddress());assertTrue(p.changeAddress().startsWith("bcrt1q"));assertEquals(1,p.inputs().size());
        assertEquals(0,new BigDecimal("0.05000000").subtract(new BigDecimal(p.amount())).subtract(new BigDecimal(p.changeAmount())).compareTo(new BigDecimal(p.nativeFee())));
        assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());assertFalse(rpc.ready().wallet().path("private_keys_enabled").booleanValue());
        var decoded=rpc.call("decodepsbt",List.of(p.psbt()));assertEquals(p.unsignedTxid(),decoded.path("tx").path("txid").stringValue());
        assertFalse(decoded.path("inputs").get(0).has("partial_signatures"));assertFalse(decoded.path("inputs").get(0).has("final_scriptwitness"));
    }
    @Test void allFiveSupportedActualDestinationsKeepTheirExactNetAndScript() {
        var items=JSON.readTree(System.getenv("CEX_BITCOIN_PAYOUT_TEST_DESTINATIONS"));assertEquals(5,items.size());
        for(var item:items){var c=new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",item.path("address").stringValue(),item.path("scriptPubKey").stringValue(),new BigDecimal("0.01"));var p=client.prepare(c,fixtureFee());
            var tx=rpc.call("decodepsbt",List.of(p.psbt()));assertEquals(c.address(),tx.path("tx").path("vout").get(0).path("scriptPubKey").path("address").stringValue());assertEquals(c.scriptPubKey(),tx.path("tx").path("vout").get(0).path("scriptPubKey").path("hex").stringValue());}
    }
    @Test void actualMinerCostIsSeparateFromTheFixedCustomerFee() {
        var p=client.prepare(command(),fixtureFee());var customer=BitcoinWithdrawalAmounts.quote(p.amount());assertEquals(new BigDecimal("0.00000100"),customer.fee());assertNotEquals(0,customer.fee().compareTo(new BigDecimal(p.nativeFee())));
        assertEquals(new BigDecimal("0.01000100"),customer.total());
    }
    @Test void aDifferentExpectedScriptCannotRedirectTheRealTransaction() {
        var c=command();var wrong=new BitcoinPayoutClient.Command(c.withdrawalId(),c.network(),c.address(),"0014"+"00".repeat(20),c.amount());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.prepare(wrong,fixtureFee())).getStatusCode().value());
    }
    @Test void actualDustRejectionCannotRoundTheCustomerPaymentUp() {
        var c=command();var dust=new BitcoinPayoutClient.Command(c.withdrawalId(),c.network(),c.address(),c.scriptPubKey(),new BigDecimal("0.00000001"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.prepare(dust,fixtureFee())).getStatusCode().value());
    }
    @Test void unavailableActualFeeEstimateHasNoFixedFallback() {
        assertEquals(503,assertThrows(ResponseStatusException.class,client::quote).getStatusCode().value());
    }
    @Test void actualWrongNetworkAndPrivateKeyWalletCannotPrepare() {
        for(var s:List.of(settings("watch","MAINNET"),settings("keys","REGTEST")))try(var other=new BitcoinRpcClient(s,JSON)){
            // 입력끼리의 이름 불일치가 아니라 실제 노드의 체인/개인키 응답에서 거절되는지 확인한다.
            var observed=fixtureFee();var fee=new BitcoinPayoutClient.FeeQuote(s.network.id,s.wallet,observed.satPerVbyte(),observed.tipHash(),observed.tipHeight(),Instant.now(),Instant.now());
            var original=command();var compatible=new BitcoinPayoutClient.Command(original.withdrawalId(),s.network.id,original.address(),original.scriptPubKey(),original.amount());
            assertEquals(503,assertThrows(ResponseStatusException.class,()->new BitcoinPayoutClient(s,other).prepare(compatible,fee)).getStatusCode().value());
        }
    }
    @Test void preparationCredentialsCannotSignBroadcastOrExport()throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            String token=System.getenv("CEX_BITCOIN_PAYOUT_TEST_USER")+":"+System.getenv("CEX_BITCOIN_PAYOUT_TEST_PASSWORD");
            for(String method:List.of("walletprocesspsbt","signrawtransactionwithwallet","sendrawtransaction","sendtoaddress","listdescriptors","importdescriptors","dumpprivkey","createwallet","lockunspent")) {
                var request=HttpRequest.newBuilder(URI.create(System.getenv("CEX_BITCOIN_PAYOUT_TEST_URL")+"/wallet/keys")).header("Authorization","Basic "+Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","id","permissions","method",method,"params",List.of())))).build();
                assertEquals(403,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),method);
            }
        }
    }
    @Test void realCustodyAddressIsHeldForASeparateInternalTransferPath() {
        var prepared=client.prepare(command(),fixtureFee());var input=prepared.inputs().getFirst();
        var own=new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",input.address(),input.scriptPubKey(),new BigDecimal("0.01"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.prepare(own,fixtureFee())).getStatusCode().value());
    }

    @Test void aRealNoChangeTransactionCanHaveTheSameUnsignedIdentityBeforeAnySigning() {
        // 시험 입력0.05BTC에서282사토시를 비용으로 남긴다. 실제 Core가 거스름돈 없는 출력을 만들었는지 확인한다.
        // 서명/방송/입력 예약 없이 같은 내용을 다시 준비하면 거래 ID가 같을 수 있다는 회귀의 실제 근거다.
        var c=command();var exact=new BitcoinPayoutClient.Command(c.withdrawalId(),c.network(),c.address(),c.scriptPubKey(),new BigDecimal("0.04999718"));
        var first=client.prepare(exact,fixtureFee());var second=client.prepare(exact,fixtureFee());
        assertNull(first.changeAddress());assertNull(first.changeScript());assertNull(first.changeAmount());
        assertEquals(first.unsignedTxid(),second.unsignedTxid());assertEquals("0.00000282",first.nativeFee());
        var decoded=rpc.call("decodepsbt",List.of(first.psbt()));assertEquals(1,decoded.path("tx").path("vout").size());
        assertEquals("0.04999718",first.amount());assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());
        assertFalse(decoded.path("inputs").get(0).has("partial_signatures"));
    }

}
