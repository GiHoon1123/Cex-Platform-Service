package dustin.cex.domains.travelrule;

import java.net.URI;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 공급사/내부 서명 모듈과 등록된 본인 공개키를 고정한다. 플랫폼에는 서명 개인키를 받지 않는다. */
@Component
public class CodeVaspSettings {
    final URI api,cipher;
    final String entity,publicKey;
    public CodeVaspSettings(@Value("${codevasp.api-url:}")String api,@Value("${codevasp.cipher-url:}")String cipher,
        @Value("${codevasp.entity-id:}")String entity,@Value("${codevasp.public-key:}")String publicKey){
        if(api==null||cipher==null||entity==null||publicKey==null)throw invalid();
        this.entity=entity;this.publicKey=publicKey;
        if(api.isEmpty()&&cipher.isEmpty()&&entity.isEmpty()&&publicKey.isEmpty()){this.api=null;this.cipher=null;return;}
        if(!entity.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!key(publicKey,32))throw invalid();
        this.api=base(api,false);this.cipher=base(cipher,true);
    }
    private static URI base(String text,boolean internal){
        try{
            var value=URI.create(text);String host=value.getHost();
            boolean loopback="127.0.0.1".equals(host)||"[::1]".equals(host);
            // 고정된 내부 모듈 이름은 비공개 Compose 망에서만 사용한다. 외부 공개/동적 요청 주소는 허용하지 않는다.
            boolean privateModule=internal&&"code-cipher".equals(host);
            if(host==null||value.getPort()==0||value.getPort()>65535||!("https".equals(value.getScheme())||("http".equals(value.getScheme())&&(loopback||privateModule)))
                ||value.getUserInfo()!=null||value.getQuery()!=null||value.getFragment()!=null
                ||!(value.getPath().isEmpty()||value.getPath().equals("/")))throw invalid();
            return value;
        }catch(IllegalArgumentException failure){throw invalid();}
    }
    static boolean key(String value,int size){
        try{byte[] decoded=Base64.getDecoder().decode(value);return decoded.length==size&&Base64.getEncoder().encodeToString(decoded).equals(value);}
        catch(IllegalArgumentException failure){return false;}
    }
    boolean configured(){return api!=null;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("이전거래 공급사 접속/등록 공개키 설정을 확인하세요");}
    @Override public String toString(){return "CodeVaspSettings[접속 설정 생략]";}
}
