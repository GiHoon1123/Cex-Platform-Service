package dustin.cex.domains.ethereum;

import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 관찰 노드·체인·확정 깊이와 외부 키 관리 계층이 미리 만든 주소만 운영 설정에서 받는다. */
@Component
public class EthereumSettings {
    final URI endpoint;final BigInteger chainId,startBlock;final int confirmations;final List<String> addressPool;
    public EthereumSettings(@Value("${ethereum.rpc-url:}") String url,@Value("${ethereum.chain-id:1}") String chain,
            @Value("${ethereum.start-block:0}") String start,@Value("${ethereum.confirmations:12}") int confirmations,
            @Value("${ethereum.deposit-addresses:}") String addresses) {
        if(url.isBlank()){endpoint=null;chainId=BigInteger.ZERO;startBlock=BigInteger.ZERO;this.confirmations=confirmations;addressPool=List.of();return;}
        URI parsed;
        try{parsed=URI.create(url);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("이더리움 노드 설정을 확인하세요");}
        boolean local="http".equals(parsed.getScheme())&&Set.of("localhost","127.0.0.1","[::1]").contains(parsed.getHost());
        if(!(local||"https".equals(parsed.getScheme()))||parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getFragment()!=null)
            throw new IllegalArgumentException("이더리움 노드 설정을 확인하세요");
        endpoint=parsed;chainId=positive(chain,"체인 ID");startBlock=new BigInteger(start);
        if(startBlock.signum()<0||confirmations<1||confirmations>2048)throw new IllegalArgumentException("이더리움 확정 설정을 확인하세요");
        this.confirmations=confirmations;
        addressPool=addresses.isBlank()?List.of():Arrays.stream(addresses.split(",",-1)).map(EthereumSettings::address).distinct().toList();
        if(!addresses.isBlank()&&addressPool.size()!=addresses.split(",",-1).length)throw new IllegalArgumentException("이더리움 입금 주소가 중복됐습니다");
    }
    boolean configured(){return endpoint!=null;}
    public BigInteger chainId(){return chainId;}
    public static String address(String value){if(value==null||!value.matches("0x[0-9a-fA-F]{40}"))throw new IllegalArgumentException("이더리움 주소를 확인하세요");return value.toLowerCase(Locale.ROOT);}
    private static BigInteger positive(String value,String field){try{var number=new BigInteger(value);if(number.signum()<=0)throw new IllegalArgumentException();return number;}catch(RuntimeException invalid){throw new IllegalArgumentException(field+"를 확인하세요");}}
    @Override public String toString(){return "EthereumSettings[노드 주소 생략]";}
}
