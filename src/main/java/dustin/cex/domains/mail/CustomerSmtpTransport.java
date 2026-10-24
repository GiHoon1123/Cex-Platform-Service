package dustin.cex.domains.mail;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;

/** MIME·TLS·SMTP는 표준 구현을 사용한다. 토큰/수신 주소를 로그나 예외 설명에 복사하지 않는다. */
@Component
public class CustomerSmtpTransport {
    private final ObjectProvider<JavaMailSender> provider;private final String from;private final URI base;
    public CustomerSmtpTransport(ObjectProvider<JavaMailSender> provider,@Value("${customer-mail.from:}") String from,@Value("${customer-mail.public-base:https://exchange.example.invalid}") URI base){this.provider=provider;this.from=from;this.base=base;}
    public boolean configured(){return provider.getIfAvailable()!=null&&!from.isBlank();}
    public void requireReady(){
        if(!(provider.getIfAvailable() instanceof JavaMailSenderImpl sender)||from.isBlank()||!"https".equals(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)throw new IllegalStateException("고객 메일 발신/TLS/공개 주소 설정이 필요합니다");
        var properties=sender.getJavaMailProperties();String protocol=sender.getProtocol(),prefix="mail."+protocol+".";boolean local=Set.of("127.0.0.1","::1","localhost").contains(sender.getHost());
        boolean tls="smtps".equals(protocol)||Boolean.parseBoolean(properties.getProperty(prefix+"ssl.enable"))||(Boolean.parseBoolean(properties.getProperty(prefix+"starttls.enable"))&&Boolean.parseBoolean(properties.getProperty(prefix+"starttls.required")));
        if(!tls||(!local&&properties.getProperty(prefix+"ssl.trust")!=null))throw new IllegalStateException("검증된 TLS 메일 연결이 필요합니다");
        if(!local){properties.setProperty(prefix+"ssl.checkserveridentity","true");if(sender.getUsername()==null||sender.getPassword()==null)throw new IllegalStateException("메일 발신 인증이 필요합니다");properties.setProperty(prefix+"auth","true");}
        properties.setProperty("mail.debug","false");properties.setProperty("mail.debug.auth","false");sender.getSession().setDebug(false);
        properties.setProperty(prefix+"connectiontimeout","5000");properties.setProperty(prefix+"timeout","5000");properties.setProperty(prefix+"writetimeout","5000");
    }
    public void send(CustomerMailOutbox.Payload p){
        requireReady();try{var sender=provider.getObject();var address=new InternetAddress(p.email(),true);address.validate();if(!address.getAddress().equals(p.email())||p.email().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            var m=sender.createMimeMessage();var helper=new MimeMessageHelper(m,"UTF-8");helper.setFrom(from);helper.setTo(address);
            helper.setSubject("이메일 주소 확인");helper.setText("직접 요청한 이메일 확인을 계속하려면 로그인한 상태에서 아래 주소를 여세요. 요청하지 않았다면 무시하세요.\n"+base.resolve("/account/verify-email")+"?challenge="+p.reference()+"&token="+p.token(),false);
            var source=new InternetAddress(from,true);source.validate();m.setHeader("Message-ID","<"+p.id()+"@"+source.getAddress().substring(source.getAddress().lastIndexOf('@')+1)+">");sender.send(m);
        }catch(Exception e){throw new IllegalStateException("고객 메일 SMTP 결과 불명: "+e.getClass().getSimpleName());}
    }
}
