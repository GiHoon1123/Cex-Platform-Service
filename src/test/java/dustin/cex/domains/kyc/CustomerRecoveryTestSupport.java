package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

/** 고객 복구 회귀에서 실제 등록·서명을 공유하는 fixture다. 제품 검증기를 대체하지 않는다. */
abstract class CustomerRecoveryTestSupport extends CustomerMailTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean dustin.cex.domains.cdd.CddCrypto crypto;
    @Autowired CustomerRecoveryService recovery;
    @Autowired CustomerMfaKeyChanges keyChanges;
    @Autowired CustomerMfaService mfa;
    @Autowired CustomerEmailVerification email;
    @Autowired CustomerMailRetryService retries;
    final Map<String,Long> counters=new HashMap<>();
    final String password="security-test-password";
    OperatorMfaService.Options option(CustomerRecoveryService.Options o){return new OperatorMfaService.Options(o.challengeId(),o.publicKey());}
    void suppress(){for(int i=0;i<30;i++){var lease=outbox.claim();if(lease==null)return;outbox.finish(lease,"SUPPRESSED");}fail("격리 예약 정리 초과");}
    void pending(){var r=auth.signin(new SigninRequest(customer.getEmail(),password));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));customerAccess=r.getAccessToken();}
    void login(SoftwareAuthenticator key) throws Exception {
        pending();var o=mfa.authenticationOptions(customer.getId(),sid);
        var v=mfa.authenticate(customer.getId(),sid,o.challengeId(),key.assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,counters.merge(key.id(),1L,Long::sum),null));
        sid=UUID.fromString(jwt.verifyAccessToken(v.accessToken()).get("sid",String.class));customerAccess=v.accessToken();
    }
    SoftwareAuthenticator initial() throws Exception {
        counters.clear();var e=email.request(customer.getId(),sid,UUID.randomUUID());UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,e.challengeId());String token=outbox.open(job).token();suppress();email.confirm(customer.getId(),sid,e.challengeId(),token);
        var key=new SoftwareAuthenticator();var o=mfa.registrationOptions(customer.getId(),sid,password);mfa.register(customer.getId(),sid,o.challengeId(),key.registration(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true));login(key);suppress();return key;
    }
    CustomerRecoveryService.Proof signed(CustomerRecoveryService.Options o,SoftwareAuthenticator key) throws Exception {
        return new CustomerRecoveryService.Proof(o.challengeId(),key.assertion(option(o),"http://localhost:3003",true,counters.merge(key.id(),1L,Long::sum),null));
    }
    CustomerRecoveryService.Code issue(SoftwareAuthenticator key) throws Exception {return recovery.replaceCode(customer.getId(),sid,signed(recovery.codeOptions(customer.getId(),sid,password),key));}
    CustomerRecoveryService.Proof registration(CustomerRecoveryService.Options o,SoftwareAuthenticator key) throws Exception {return new CustomerRecoveryService.Proof(o.challengeId(),key.registration(option(o),"http://localhost:3003",true));}
    long events(){return jdbc.queryForObject("SELECT count(*) FROM customer_recovery_events WHERE user_id=?",Long.class,customer.getId());}
    long keyCount(){return jdbc.queryForObject("SELECT count(*) FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.name=?",Long.class,customer.getId().toString());}
}
