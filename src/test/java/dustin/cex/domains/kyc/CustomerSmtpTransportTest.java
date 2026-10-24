package dustin.cex.domains.kyc;
import dustin.cex.domains.mail.CustomerSmtpTransport;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 소켓을 열기 전 TLS·인증·공개 링크·시간 제한 설정이 안전하게 거부/확정되는지 검사한다. */
class CustomerSmtpTransportTest {
    JavaMailSenderImpl sender(String host,String protocol){var s=new JavaMailSenderImpl();s.setHost(host);s.setProtocol(protocol);return s;}
    CustomerSmtpTransport transport(JavaMailSenderImpl sender,String base){var beans=new DefaultListableBeanFactory();beans.registerSingleton("mail",sender);return new CustomerSmtpTransport(beans.getBeanProvider(JavaMailSender.class),"no-reply@exchange.test",URI.create(base));}
    @Test void cleartextSmtpIsRejectedBeforeNetwork(){var s=sender("smtp.example.test","smtp");assertThrows(IllegalStateException.class,()->transport(s,"https://exchange.example.test").requireReady());}
    @Test void optionalStarttlsIsRejectedBeforeNetwork(){var s=sender("smtp.example.test","smtp");s.getJavaMailProperties().setProperty("mail.smtp.starttls.enable","true");assertThrows(IllegalStateException.class,()->transport(s,"https://exchange.example.test").requireReady());}
    @Test void remoteTrustOverrideIsRejected(){var s=sender("smtp.example.test","smtps");s.getJavaMailProperties().setProperty("mail.smtps.ssl.trust","*");assertThrows(IllegalStateException.class,()->transport(s,"https://exchange.example.test").requireReady());}
    @Test void remoteAuthenticationIsRequired(){var s=sender("smtp.example.test","smtps");assertThrows(IllegalStateException.class,()->transport(s,"https://exchange.example.test").requireReady());}
    @Test void requiredStarttlsEnforcesIdentityAuthAndFiniteTimeouts(){var s=sender("smtp.example.test","smtp");s.setUsername("owned-smtp-account");s.setPassword("test-only-no-network");var p=s.getJavaMailProperties();p.setProperty("mail.smtp.starttls.enable","true");p.setProperty("mail.smtp.starttls.required","true");p.setProperty("mail.debug","true");transport(s,"https://exchange.example.test").requireReady();assertEquals("true",p.getProperty("mail.smtp.ssl.checkserveridentity"));assertEquals("true",p.getProperty("mail.smtp.auth"));for(String key:new String[]{"connectiontimeout","timeout","writetimeout"})assertEquals("5000",p.getProperty("mail.smtp."+key));assertEquals("false",p.getProperty("mail.debug"));assertFalse(s.getSession().getDebug());}
    @Test void unsafeOrMissingPublicBaseIsRejected(){var s=sender("127.0.0.1","smtps");for(String base:new String[]{"","http://exchange.example.test","https://user@exchange.example.test","https://exchange.example.test?token=x","https://exchange.example.test#x"})assertThrows(IllegalStateException.class,()->transport(s,base).requireReady());}
    @Test void blankRemoteCredentialsAreRejected(){var s=sender("smtp.example.test","smtps");s.setUsername(" ");s.setPassword(" ");assertThrows(IllegalStateException.class,()->transport(s,"https://exchange.example.test").requireReady());}

}
