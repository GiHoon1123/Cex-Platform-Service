package dustin.cex.domains.kyc;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 공급사 계약에 필요한 설정만 둔다. 미설정은 API 503·신규 주문 거부이며 인증 생략 모드가 아니다. */
@Component
public class DiditSettings {
    final String apiKey,webhookSecret,environment;
    final UUID applicationId,workflowId;
    final int workflowVersion;
    final URI callback;
    final byte[] encryptionKey;
    public DiditSettings(@Value("${didit.api-key:}") String apiKey,@Value("${didit.webhook-secret:}") String secret,
            @Value("${didit.application-id:}") String app,@Value("${didit.workflow-id:}") String workflow,
            @Value("${didit.workflow-version:0}") int version,@Value("${didit.environment:}") String environment,
            @Value("${didit.callback:}") String callback,@Value("${didit.encryption-key:}") String encryptionKey) {
        this.apiKey=apiKey;this.webhookSecret=secret;this.environment=environment;workflowVersion=version;
        boolean empty=List.of(apiKey,secret,app,workflow,environment,callback,encryptionKey).stream().allMatch(String::isEmpty) && version==0;
        if(empty) {applicationId=null;workflowId=null;this.callback=null;this.encryptionKey=null;return;}
        if(apiKey.length()<16 || secret.length()<32 || version<1 || !Set.of("live","sandbox").contains(environment)) throw new IllegalArgumentException("Didit 설정을 확인하세요");
        applicationId=UUID.fromString(app);workflowId=UUID.fromString(workflow);this.callback=URI.create(callback);
        if(!"https".equals(this.callback.getScheme()) || this.callback.getHost()==null || this.callback.getUserInfo()!=null
                || this.callback.getFragment()!=null || this.callback.getQuery()!=null) throw new IllegalArgumentException("고정된 HTTPS 복귀 주소가 필요합니다");
        this.encryptionKey=Base64.getDecoder().decode(encryptionKey);
        if(this.encryptionKey.length!=32) throw new IllegalArgumentException("KYC 링크 저장용 독립적인 256비트 키가 필요합니다");
    }
    boolean configured() {return applicationId!=null;}
    @Override public String toString() {return "DiditSettings[비밀 생략]";}
}
