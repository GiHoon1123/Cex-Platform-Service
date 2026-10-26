package dustin.signer;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;

/** 서명 프로세스만 사용하는 개인키 지갑 끝점이다. 플랫폼의 관찰 전용 지갑 설정과 이름·자격을 공유하지 않는다. */
public final class BitcoinSignerSettings {
    final URI endpoint;final String username,password,wallet,chain,network;
    public BitcoinSignerSettings(@Value("${bitcoin.signer.rpc-url:}") String url,@Value("${bitcoin.signer.rpc-user:}") String username,
            @Value("${bitcoin.signer.rpc-password:}") String password,@Value("${bitcoin.signer.wallet:}") String wallet,
            @Value("${bitcoin.signer.network:REGTEST}") String network){
        this.username=username;this.password=password;this.wallet=wallet;
        if("REGTEST".equals(network)){chain="regtest";this.network="bitcoin-regtest";}
        else if("MAINNET".equals(network)){chain="main";this.network="bitcoin-mainnet";}
        else throw new IllegalArgumentException("비트코인 서명 망 설정을 확인하세요");
        final URI base;try{base=URI.create(url);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("비트코인 서명 노드 설정을 확인하세요");}
        boolean local="http".equals(base.getScheme())&&("127.0.0.1".equals(base.getHost())||"[::1]".equals(base.getHost()));
        if(!(local||"https".equals(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
                ||!(base.getPath().isEmpty()||"/".equals(base.getPath()))||!username.matches("[A-Za-z0-9_-]{1,64}")
                ||password.length()<32||password.length()>256||!wallet.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("비트코인 서명 노드 설정을 확인하세요");
        endpoint=base.resolve("/wallet/"+wallet);
    }
    @Override public String toString(){return "BitcoinSignerSettings[비밀 생략]";}
}
