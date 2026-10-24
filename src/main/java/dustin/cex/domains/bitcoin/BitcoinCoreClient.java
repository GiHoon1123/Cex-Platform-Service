package dustin.cex.domains.bitcoin;

import java.util.*;
import org.springframework.stereotype.Component;

/** 수신 주소 파생만 맡는다. 서명·전송·잔액 반영과 개인키 가져오기 기능은 제공하지 않는다. */
@Component
public class BitcoinCoreClient {
    public record Address(String network,String address,String scriptPubKey){}
    private final BitcoinSettings settings;
    private final BitcoinRpcClient rpc;
    public BitcoinCoreClient(BitcoinSettings settings,BitcoinRpcClient rpc){this.settings=settings;this.rpc=rpc;}
    public Address derive(UUID allocation){
        if(allocation==null)throw BitcoinRpcClient.unavailable();
        rpc.ready();
        var generated=rpc.call("getnewaddress",List.of(allocation.toString(),"bech32"));
        if(!generated.isString()||generated.asString().length()>100)throw BitcoinRpcClient.unavailable();
        String address=generated.asString();
        var info=rpc.call("getaddressinfo",List.of(address));
        // 인코딩·체크섬·키 파생을 직접 구현하지 않는다. 실제 노드가 소유한 외부 수신 주소인지 확인한다.
        String script=BitcoinRpcClient.text(info,"scriptPubKey");
        if(!BitcoinRpcClient.text(info,"address").equals(address)||!BitcoinRpcClient.isTrue(info,"ismine")||!BitcoinRpcClient.isTrue(info,"solvable")
                ||!BitcoinRpcClient.isFalse(info,"ischange")||!BitcoinRpcClient.isTrue(info,"iswitness")||!info.path("witness_version").isIntegralNumber()
                ||!info.path("witness_version").canConvertToInt()||info.path("witness_version").intValue()!=0||!script.matches("0014[0-9a-f]{40}"))throw BitcoinRpcClient.unavailable();
        return new Address(settings.network.id,address,script);
    }
}
