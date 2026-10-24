package dustin.cex.domains.bitcoin;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 노드와 관찰 전용 지갑은 운영자가 고정한다. 요청자가 접속 주소나 네트워크를 고를 수 없다. */
@Component
public class BitcoinSettings {
    enum Network {
        REGTEST("regtest","bitcoin-regtest"), MAINNET("main","bitcoin-mainnet");
        final String chain,id;
        Network(String chain,String id){this.chain=chain;this.id=id;}
    }
    final URI endpoint;
    final String username,password,wallet;
    final Network network;
    public BitcoinSettings(@Value("${bitcoin.rpc-url:}") String url,@Value("${bitcoin.rpc-user:}") String username,
            @Value("${bitcoin.rpc-password:}") String password,@Value("${bitcoin.wallet:}") String wallet,
            @Value("${bitcoin.network:REGTEST}") String network){
        this.username=username;this.password=password;this.wallet=wallet;this.network=Network.valueOf(network);
        if(url.isEmpty()&&username.isEmpty()&&password.isEmpty()&&wallet.isEmpty()){endpoint=null;return;}
        final URI base;
        // 잘못된 URI 예외는 입력 원문을 포함할 수 있다. 부팅 로그에도 설정 원문을 전파하지 않는다.
        try{base=URI.create(url);}catch(IllegalArgumentException e){throw new IllegalArgumentException("비트코인 노드 설정을 확인하세요");}
        // HTTP의 기본 인증은 암호화가 아니다. 평문 전송은 같은 호스트의 명시적 루프백에만 허용한다.
        boolean local="http".equals(base.getScheme())&&("127.0.0.1".equals(base.getHost())||"[::1]".equals(base.getHost()));
        if(!(local||"https".equals(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null
                ||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/"))
                ||!username.matches("[A-Za-z0-9_-]{1,64}")||password.length()<32||password.length()>256
                ||!wallet.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("비트코인 노드 설정을 확인하세요");
        endpoint=base.resolve("/wallet/"+wallet);
    }
    boolean configured(){return endpoint!=null;}
    @Override public String toString(){return "BitcoinSettings[비밀 생략]";}
}
