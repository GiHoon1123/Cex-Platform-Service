package dustin.confirmer;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;

/** 방송·서명 권한이 없는 별도 읽기 노드의 고정 망과 최소 확인 수를 보존한다. */
public final class BitcoinConfirmationSettings {
    final URI endpoint;final String username,password,chain,network;final int required;
    public BitcoinConfirmationSettings(@Value("${bitcoin.confirmation.rpc-url:}") String url,@Value("${bitcoin.confirmation.rpc-user:}") String user,@Value("${bitcoin.confirmation.rpc-password:}") String password,@Value("${bitcoin.confirmation.network:REGTEST}") String network,@Value("${bitcoin.confirmation.required:6}") int required){
        this.username=user;this.password=password;this.required=required;if("REGTEST".equals(network)){chain="regtest";this.network="bitcoin-regtest";}else if("MAINNET".equals(network)){chain="main";this.network="bitcoin-mainnet";}else throw new IllegalArgumentException("비트코인 확인 망 설정을 확인하세요");
        final URI base;try{base=URI.create(url);}catch(RuntimeException invalid){throw new IllegalArgumentException("비트코인 확인 노드 설정을 확인하세요");}boolean local="http".equals(base.getScheme())&&"127.0.0.1".equals(base.getHost());if(!(local||"https".equals(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||"/".equals(base.getPath()))||!user.matches("[A-Za-z0-9_-]{1,64}")||password.length()<32||required!=6)throw new IllegalArgumentException("비트코인 확인 노드 설정을 확인하세요");endpoint=base;
    }
}
