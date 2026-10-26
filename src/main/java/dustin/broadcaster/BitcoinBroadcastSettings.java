package dustin.broadcaster;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;

/** 방송 프로세스의 관찰 지갑 끝점이다. 개인키·거래 준비 권한과 분리한 계정을 사용한다. */
public final class BitcoinBroadcastSettings {
    final URI endpoint;final String username,password,wallet,chain,network;
    public BitcoinBroadcastSettings(@Value("${bitcoin.broadcaster.rpc-url:}") String url,@Value("${bitcoin.broadcaster.rpc-user:}") String user,@Value("${bitcoin.broadcaster.rpc-password:}") String password,@Value("${bitcoin.broadcaster.wallet:}") String wallet,@Value("${bitcoin.broadcaster.network:REGTEST}") String network){
        this.username=user;this.password=password;this.wallet=wallet;if("REGTEST".equals(network)){chain="regtest";this.network="bitcoin-regtest";}else if("MAINNET".equals(network)){chain="main";this.network="bitcoin-mainnet";}else throw new IllegalArgumentException("비트코인 방송 망 설정을 확인하세요");
        final URI base;try{base=URI.create(url);}catch(RuntimeException invalid){throw new IllegalArgumentException("비트코인 방송 노드 설정을 확인하세요");}boolean local="http".equals(base.getScheme())&&"127.0.0.1".equals(base.getHost());
        if(!(local||"https".equals(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||"/".equals(base.getPath()))||!user.matches("[A-Za-z0-9_-]{1,64}")||password.length()<32||!wallet.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("비트코인 방송 노드 설정을 확인하세요");endpoint=base.resolve("/wallet/"+wallet);
    }
}
