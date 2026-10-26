package dustin.cex.domains.bitcoin;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** 고정 관찰 노드가 판독한 출금 목적지다. 주소 유효성은 수신인 소유권·심사·지급 승인이 아니다. */
@Component
public class BitcoinDestinationClient {
    private static final Set<String> SUPPORTED=Set.of("pubkeyhash","scripthash","witness_v0_keyhash","witness_v0_scripthash","witness_v1_taproot");
    public record Destination(String network,String address,String scriptPubKey,String scriptType){
        @Override public String toString(){return "BitcoinDestination[목적지 원본 생략]";}
    }
    private final BitcoinSettings settings;private final BitcoinRpcClient rpc;
    public BitcoinDestinationClient(BitcoinSettings settings,BitcoinRpcClient rpc){this.settings=settings;this.rpc=rpc;}
    public Destination validate(String address){
        // 주소 판독도 DB 잠금을 잡고 기다리지 않는다. 이후 업무가 현재 사용자/서명 범위를 다시 결합한다.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("출금 주소 검증은 DB 트랜잭션 밖에서 실행하세요");
        if(address==null||address.isEmpty()||address.length()>100||address.chars().anyMatch(c->c<33||c>126))throw invalid();
        rpc.ready();var decoded=rpc.call("validateaddress",List.of(address));
        if(!decoded.isObject()||!decoded.path("isvalid").isBoolean())throw BitcoinRpcClient.unavailable();
        if(!decoded.path("isvalid").booleanValue())throw invalid();
        String canonical=BitcoinRpcClient.text(decoded,"address"),script=BitcoinRpcClient.text(decoded,"scriptPubKey");
        if(canonical.isEmpty()||canonical.length()>100||canonical.chars().anyMatch(c->c<33||c>126)
            ||script.isEmpty()||script.length()>128||script.length()%2!=0||!script.matches("[0-9a-f]+"))throw BitcoinRpcClient.unavailable();
        // 스크립트 접두사/체크섬을 재구현하지 않는다. 실제 노드가 판독한 종류와 같은 목적지인지 결합한다.
        var output=rpc.call("decodescript",List.of(script));
        if(!output.isObject()||!output.path("type").isString())throw BitcoinRpcClient.unavailable();
        String type=output.path("type").stringValue();
        if(!SUPPORTED.contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하는 비트코인 출금 주소 형식을 확인하세요");
        if(!canonical.equals(BitcoinRpcClient.text(output,"address")))throw BitcoinRpcClient.unavailable();
        // 정규화는 같은 지급지의 대소문자 표현에만 한정한다. 다른 유효 주소로 바뀐 노드 응답을 허용하지 않는다.
        boolean same=canonical.equals(address)||(type.startsWith("witness_")&&address.equals(address.toUpperCase(Locale.ROOT))&&canonical.equals(address.toLowerCase(Locale.ROOT)));
        if(!same)throw BitcoinRpcClient.unavailable();
        // 대문자 Bech32 등 유효한 표현은 노드의 정규 주소로 고정한다. 입력 원문을 감사/서명 원본으로 재사용하지 않는다.
        return new Destination(settings.network.id,canonical,script,type);
    }
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"현재 비트코인 네트워크의 지급 주소를 확인하세요");}
}
