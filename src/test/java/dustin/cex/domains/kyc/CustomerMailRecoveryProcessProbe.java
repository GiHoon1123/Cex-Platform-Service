package dustin.cex.domains.kyc;
import dustin.cex.CexApplication;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.mail.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 테스트 전용 JVM이다. 제품의 실제 스케줄러/발송함을 사용하고 SMTP 경계의 정지 지점만 주입한다. */
public class CustomerMailRecoveryProcessProbe {
    static class FaultPoint {
        @Bean @Primary CustomerSmtpTransport probeTransport(ObjectProvider<JavaMailSender> provider,Environment env,org.springframework.context.ApplicationContext context){
            if(!(provider.getIfAvailable() instanceof JavaMailSenderImpl sender)||!"127.0.0.1".equals(sender.getHost()))throw new IllegalArgumentException("검증 소유 루프백 SMTP가 필요합니다");
            return new CustomerSmtpTransport(provider,env.getRequiredProperty("customer-mail.from"),URI.create(env.getRequiredProperty("customer-mail.public-base"))){
                @Override public void send(CustomerMailOutbox.Payload p){
                    String phase=System.getenv("CEX_MAIL_PROBE_PHASE");if("pause-before".equals(phase))pause("MAIL_BEFORE_SMTP:"+p.id());
                    super.send(p);
                    // 실제 SMTP 접수 뒤 결과 트랜잭션 전의 장애를 재현한다. 업무 로직이나 결과 기록은 복사하지 않는다.
                    if("pause-after".equals(phase))pause("MAIL_AFTER_SMTP:"+p.id());
                }
                private void pause(String marker){System.out.println(marker);System.out.flush();context.getBean(org.springframework.scheduling.TaskScheduler.class).schedule(()->{System.out.println("MAIL_DEFAULT_SCHEDULER_ALIVE:"+Thread.currentThread().getName());System.out.flush();},Instant.now());try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("검증 정지 지점 중단");}}
            };
        }
    }
    public static void main(String[] args) throws Exception {
        var app=new SpringApplication(CexApplication.class,FaultPoint.class);app.setAdditionalProfiles("test");
        try(var context=app.run(args)){
            String phase=System.getenv("CEX_MAIL_PROBE_PHASE");var jdbc=context.getBean(JdbcTemplate.class);
            if(phase.startsWith("pause-")){
                var users=context.getBean(UserRepository.class);var user=users.findByEmail("customer@process.test.invalid").orElseGet(()->users.saveAndFlush(User.builder().email("customer@process.test.invalid").passwordHash("격리 프로세스 검증 계정").build()));
                // 같은 주소의 다음 장애 시나리오에는 새 고객을 만들지 않고 기존 검증용 계정을 재사용한다.
                var outbox=context.getBean(CustomerMailOutbox.class);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                UUID id=tx.execute(t->outbox.enqueue(user.getId(),CustomerMailOutbox.Kind.EMAIL_VERIFICATION,UUID.randomUUID(),user.getEmail(),token,Instant.now().plusSeconds(900)));
                System.out.println("MAIL_QUEUED:"+id);System.out.flush();new CountDownLatch(1).await();
            }else{
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while(jdbc.queryForObject("SELECT count(*) FROM customer_mail_delivery WHERE status<>'SMTP_ACCEPTED'",Long.class)>0){if(System.nanoTime()>deadline)throw new IllegalStateException("메일 복원 시간 초과");Thread.sleep(100);}
                if("verify".equals(phase))Thread.sleep(2200);
                if(jdbc.queryForObject("SELECT count(*) FROM customer_mail_delivery WHERE status<>'SMTP_ACCEPTED'",Long.class)!=0)throw new IllegalStateException("확정 상태가 변경됐습니다");
                System.out.println("MAIL_RECOVERY_CONFIRMED");System.out.flush();
            }
        }
    }
}
