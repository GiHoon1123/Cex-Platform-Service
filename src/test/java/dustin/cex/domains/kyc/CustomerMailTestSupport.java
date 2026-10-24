package dustin.cex.domains.kyc;
import com.icegreen.greenmail.util.*;
import dustin.cex.domains.mail.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.reset;

/** 메일 검증은 별도 PostgreSQL 서버와 외부 전달이 없는 로컬 SMTPS 수신함만 사용한다. */
abstract class CustomerMailTestSupport extends EddTestSupport {
    static GreenMail green;
    static synchronized GreenMail server(){if(green==null){green=new GreenMail(new ServerSetup(0,"127.0.0.1",ServerSetup.PROTOCOL_SMTPS));green.start();Runtime.getRuntime().addShutdownHook(new Thread(()->green.stop()));}return green;}
    @DynamicPropertySource static void mailProperties(DynamicPropertyRegistry r){var g=server();r.add("spring.mail.host",()->"127.0.0.1");r.add("spring.mail.port",()->g.getSmtps().getPort());r.add("spring.mail.protocol",()->"smtps");r.add("spring.mail.properties.mail.smtps.ssl.trust",()->"127.0.0.1");r.add("spring.mail.properties.mail.smtps.ssl.checkserveridentity",()->"false");r.add("customer-mail.from",()->"no-reply@exchange.test");r.add("customer-mail.public-base",()->"https://exchange.example.test");r.add("customer-mail.dispatch-enabled",()->false);}
    @Autowired CustomerMailOutbox outbox;@Autowired CustomerMailDispatcher dispatcher;@Autowired CustomerSmtpTransport smtp;
    String token(){byte[] raw=new byte[32];new java.security.SecureRandom().nextBytes(raw);return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
    UUID queue(UUID reference,String secret,Instant expires){return new TransactionTemplate(manager).execute(t->outbox.enqueue(customer.getId(),CustomerMailOutbox.Kind.EMAIL_VERIFICATION,reference,customer.getEmail(),secret,expires));}
    UUID queued(){return queue(UUID.randomUUID(),token(),Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
    @BeforeEach void mailbox() throws Exception {server().purgeEmailFromAllMailboxes();server().setUser(customer.getEmail(),"sandbox-only");}
    @AfterEach void drainOwnedQueue(){
        // 불변 요청/감사를 지우지 않는다. 테스트 전용 운영 임대만 만료시키고 서비스로 잔여 예약을 억제한다.
        reset(audit);jdbc.update("UPDATE customer_mail_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE status='SENDING'");jdbc.update("UPDATE customer_mail_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE status='RETRY'");
        for(int i=0;i<100&&jdbc.queryForObject("SELECT count(*) FROM customer_mail_delivery WHERE status IN ('PENDING','RETRY','SENDING')",Long.class)>0;i++){var lease=outbox.claim();if(lease!=null)outbox.finish(lease,"SUPPRESSED");}
    }
}
