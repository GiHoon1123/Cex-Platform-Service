package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실행기가 소유한 격리 Core의 실제 다섯 주소 형식만 사용한다. 공개 체인/기존 개발 노드로 대체하지 않는다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinDestinationIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    BitcoinRpcClient rpc;BitcoinDestinationClient client;
    @BeforeEach void setup(){var settings=new BitcoinSettings(System.getenv("CEX_BITCOIN_TEST_URL"),System.getenv("CEX_BITCOIN_TEST_USER"),System.getenv("CEX_BITCOIN_TEST_PASSWORD"),"cex_watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);client=new BitcoinDestinationClient(settings,rpc);}
    @AfterEach void close(){rpc.close();}
    @Test void fiveActualStandardOutputTypesKeepTheExactNodeScript(){
        var fixtures=JSON.readTree(System.getenv("CEX_BITCOIN_TEST_DESTINATIONS"));assertEquals(5,fixtures.size());Set<String> types=new HashSet<>();
        for(var item:fixtures){var destination=client.validate(item.path("address").stringValue());assertEquals("bitcoin-regtest",destination.network());assertEquals(item.path("address").stringValue(),destination.address());assertEquals(item.path("scriptPubKey").stringValue(),destination.scriptPubKey());assertEquals(item.path("scriptType").stringValue(),destination.scriptType());types.add(destination.scriptType());}
        assertEquals(Set.of("pubkeyhash","scripthash","witness_v0_keyhash","witness_v0_scripthash","witness_v1_taproot"),types);
    }
    @Test void actualUppercaseWitnessAddressesHaveTheSameCanonicalDestination(){for(var item:JSON.readTree(System.getenv("CEX_BITCOIN_TEST_DESTINATIONS"))){if(item.path("scriptType").stringValue().startsWith("witness_")){String address=item.path("address").stringValue();assertEquals(address,client.validate(address.toUpperCase(Locale.ROOT)).address());}}}
    @Test void mainnetCorruptMixedCaseAndPaymentUrisAreRejectedByTheCurrentNode(){
        String address=JSON.readTree(System.getenv("CEX_BITCOIN_TEST_DESTINATIONS")).get(2).path("address").stringValue();String corrupted=address.substring(0,address.length()-1)+(address.endsWith("q")?"p":"q");
        for(String invalid:List.of("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",corrupted,"B"+address.substring(1),"bitcoin:"+address+"?amount=1"))assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(invalid)).getStatusCode().value());
    }
    @Test void validFutureWitnessOutputIsExplicitlyUnsupported(){assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(System.getenv("CEX_BITCOIN_TEST_UNSUPPORTED_DESTINATION"))).getStatusCode().value());}
    @Test void destinationValidityDoesNotClaimForeignOwnership(){String address=System.getenv("CEX_BITCOIN_TEST_ADDRESS");assertEquals(address,client.validate(address).address());/* 관찰 지갑의 내부 주소도 문법상 유효하다. 외부 수신인/내부 이체 판정은 별도 업무다. */}
}
