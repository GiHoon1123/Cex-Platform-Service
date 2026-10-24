package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

/** 두 이메일 변경 회귀가 실제 등록·서명과 분리된 메일 fixture를 공유한다. 제품 검증을 대체하지 않는다. */
abstract class CustomerEmailChangeTestSupport extends CustomerMailTestSupport {
    @Autowired CustomerEmailChangeService changes;
    @Autowired CustomerMfaService factor;
    @Autowired CustomerDeviceService devices;
    @Autowired CustomerEmailVerification email;
    @Autowired CustomerEmailChallenges challenges;
    @Autowired CustomerMailRecipientPolicy recipients;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean dustin.cex.domains.cdd.CddCrypto crypto;
    final String password="security-test-password";
    long counter;
    String destination;
    SoftwareAuthenticator browser;
    void suppress(){for(int i=0;i<30;i++){var lease=outbox.claim();if(lease==null)return;outbox.finish(lease,"SUPPRESSED");}fail("격리 예약 정리 한도 초과");}
    void login() throws Exception {
        String address=users.findById(customer.getId()).orElseThrow().getEmail();var r=auth.signin(new SigninRequest(address,password));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));
        var o=factor.authenticationOptions(customer.getId(),sid);var verified=factor.authenticate(customer.getId(),sid,o.challengeId(),browser.assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,++counter,null));sid=UUID.fromString(jwt.verifyAccessToken(verified.accessToken()).get("sid",String.class));customerAccess=verified.accessToken();
    }
    void enroll() throws Exception {
        browser=new SoftwareAuthenticator();counter=0;var o=factor.registrationOptions(customer.getId(),sid,password);factor.register(customer.getId(),sid,o.challengeId(),browser.registration(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true));login();suppress();destination=UUID.randomUUID()+"@new-address.invalid";server().setUser(destination,"sandbox-only");
    }
    void initial() throws Exception {
        // 초기 확인은 과거의 정당한 사실 fixture다. 이후60초 제한을 낮추거나 불변 원본을 수정하지 않는다.
        UUID id=UUID.randomUUID();String token=token();jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,clock_timestamp()+interval '10 minutes',clock_timestamp()-interval '2 minutes')",id,customer.getId(),UUID.randomUUID(),recipients.addressMac(customer.getEmail()),crypto.fingerprint("customer-email-rate-v1",customer.getEmail().toLowerCase(Locale.ROOT)),crypto.keyId(),sha(id+":"+token));
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id) VALUES(?,?,?,?)",id,customer.getId(),recipients.addressMac(customer.getEmail()),crypto.keyId());jdbc.update("INSERT INTO customer_email_confirmation_sessions(challenge_id,session_id) VALUES(?,?)",id,sid);enroll();
    }
    CustomerEmailChangeService.Options options(){return changes.options(customer.getId(),sid,password,destination);}
    CustomerEmailChangeService.Proof proof(CustomerEmailChangeService.Options o) throws Exception {return new CustomerEmailChangeService.Proof(o.challengeId(),browser.assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,++counter,null));}
    CustomerEmailChallenges.Receipt request() throws Exception {var o=options();return changes.request(customer.getId(),sid,destination,proof(o));}
    UUID job(UUID id,CustomerMailOutbox.Kind kind){return jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=? AND kind=?",UUID.class,id,kind.name());}
    String secret(CustomerEmailChallenges.Receipt receipt){return outbox.open(job(receipt.challengeId(),CustomerMailOutbox.Kind.EMAIL_CHANGE_VERIFICATION)).token();}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM customer_email_changes WHERE user_id=?",Long.class,customer.getId());}
    void unchanged(){assertEquals(customer.getEmail(),users.findById(customer.getId()).orElseThrow().getEmail());assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertEquals(0,count());}
}
