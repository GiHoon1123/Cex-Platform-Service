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
    public CustomerSmtpTransport(ObjectProvider<JavaMailSender> provider,@Value("${customer-mail.from:}") String from,@Value("${customer-mail.public-base:}") URI base){this.provider=provider;this.from=from;this.base=base;}
    public boolean configured(){return provider.getIfAvailable()!=null&&!from.isBlank();}
    public void requireReady(){
        if(!(provider.getIfAvailable() instanceof JavaMailSenderImpl sender)||from.isBlank()||!Set.of("smtp","smtps").contains(sender.getProtocol())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)throw new IllegalStateException("고객 메일 발신/TLS/공개 주소 설정이 필요합니다");
        var properties=sender.getJavaMailProperties();String protocol=sender.getProtocol(),prefix="mail."+protocol+".";boolean local=Set.of("127.0.0.1","::1","localhost").contains(sender.getHost());
        boolean localPublic=Set.of("127.0.0.1","::1","localhost").contains(base.getHost());
        // 로컬 Mailpit은 실제 외부 전송을 하지 않고 루프백에서만 메일을 잡는다. 이 조합에만
        // 평문 SMTP와 HTTP 복귀 주소를 허용하고, 한쪽이라도 외부 주소면 운영 TLS 계약을 유지한다.
        boolean isolatedLocal="smtp".equals(protocol)&&local&&localPublic&&"http".equals(base.getScheme());
        if(!isolatedLocal&&!"https".equals(base.getScheme()))throw new IllegalStateException("고객 메일 발신/TLS/공개 주소 설정이 필요합니다");
        boolean tls="smtps".equals(protocol)||Boolean.parseBoolean(properties.getProperty(prefix+"ssl.enable"))||(Boolean.parseBoolean(properties.getProperty(prefix+"starttls.enable"))&&Boolean.parseBoolean(properties.getProperty(prefix+"starttls.required")));
        if((!isolatedLocal&&!tls)||(!local&&properties.getProperty(prefix+"ssl.trust")!=null))throw new IllegalStateException("검증된 TLS 메일 연결이 필요합니다");
        if(!local){properties.setProperty(prefix+"ssl.checkserveridentity","true");if(sender.getUsername()==null||sender.getUsername().isBlank()||sender.getPassword()==null||sender.getPassword().isBlank())throw new IllegalStateException("메일 발신 인증이 필요합니다");properties.setProperty(prefix+"auth","true");}
        properties.setProperty("mail.debug","false");properties.setProperty("mail.debug.auth","false");sender.getSession().setDebug(false);
        properties.setProperty(prefix+"connectiontimeout","5000");properties.setProperty(prefix+"timeout","5000");properties.setProperty(prefix+"writetimeout","5000");
    }
    public void send(CustomerMailOutbox.Payload p){
        requireReady();try{var sender=provider.getObject();var address=new InternetAddress(p.email(),true);address.validate();if(!address.getAddress().equals(p.email())||p.email().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            var m=sender.createMimeMessage();var helper=new MimeMessageHelper(m,"UTF-8");helper.setFrom(from);helper.setTo(address);
            if(p.kind()==CustomerMailOutbox.Kind.EMAIL_VERIFICATION){helper.setSubject("이메일 주소 확인");helper.setText("직접 요청한 이메일 확인을 계속하려면 로그인한 상태에서 아래 주소를 여세요. 요청하지 않았다면 무시하세요.\n"+base.resolve("/account/verify-email")+"?challenge="+p.reference()+"&token="+p.token(),false);}
            else if(p.kind()==CustomerMailOutbox.Kind.EMAIL_CHANGE_VERIFICATION){
                helper.setSubject("새 이메일 주소 확인");helper.setText("직접 요청한 이메일 변경이라면 로그인 후 아래 주소에서 새 주소를 확인하세요. 요청하지 않았다면 링크를 사용하지 마세요.\n"+base.resolve("/account/change-email")+"?challenge="+p.reference()+"&token="+p.token(),false);
            }
            else if(p.kind()==CustomerMailOutbox.Kind.EMAIL_CHANGE_REQUESTED){
                helper.setSubject("이메일 변경 요청 안내");helper.setText("계정의 이메일 변경이 요청되었습니다. 본인이 요청하지 않았다면 계정 보안 안내를 확인하세요.\n"+base.resolve("/account/security"),false);
            }
            else if(p.kind()==CustomerMailOutbox.Kind.EMAIL_CHANGED_PREVIOUS||p.kind()==CustomerMailOutbox.Kind.EMAIL_CHANGED_CURRENT){
                helper.setSubject("이메일 변경 완료 안내");helper.setText("계정 이메일이 변경되어 기존 세션이 종료되었습니다. 본인이 변경하지 않았다면 계정 보안 안내를 확인하세요.\n"+base.resolve("/account/security"),false);
            }
            else if(p.kind()==CustomerMailOutbox.Kind.PASSWORD_CHANGED){
                helper.setSubject("비밀번호 변경 안내");helper.setText("계정 비밀번호가 변경되었습니다. 본인이 변경하지 않았다면 계정 보안 안내를 확인하세요.\n"+base.resolve("/account/security"),false);
            }
            else if(p.kind()==CustomerMailOutbox.Kind.MFA_CHANGED){
                helper.setSubject("계정 보안키 변경 안내");helper.setText("계정 보안키가 추가되거나 폐기되었습니다. 본인이 변경하지 않았다면 계정 보안 안내를 확인하세요.\n"+base.resolve("/account/security"),false);
            }
            else if(p.kind()==CustomerMailOutbox.Kind.ACCOUNT_RECOVERY){
                helper.setSubject("계정 복구 보안 안내");helper.setText("계정의 복구 코드가 교체되었거나 보안키 복구가 완료되었습니다. 본인이 요청하지 않았다면 계정 보안 안내를 확인하세요.\n"+base.resolve("/account/security"),false);
            }
            else{
                // 내부 메모/보고 여부/승인 사유/금액/고객 입력은 메일에 복사하지 않는다.
                helper.setSubject("새 서비스 알림");helper.setText("로그인 후 서비스 알림에서 새 안내를 확인하세요.\n"+base.resolve("/account/notices"),false);
            }
            var source=new InternetAddress(from,true);source.validate();m.setHeader("Message-ID","<"+p.id()+"@"+source.getAddress().substring(source.getAddress().lastIndexOf('@')+1)+">");sender.send(m);
        }catch(Exception e){throw new IllegalStateException("고객 메일 SMTP 결과 불명: "+e.getClass().getSimpleName());}
    }
}
