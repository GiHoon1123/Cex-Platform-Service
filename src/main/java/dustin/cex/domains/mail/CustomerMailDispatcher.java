package dustin.cex.domains.mail;
import org.springframework.stereotype.Service;

/** 영속 임대를 얻고 DB 트랜잭션 밖에서 한 건을 전송한다. SMTP 결과와 고객 확인을 혼동하지 않는다. */
@Service
public class CustomerMailDispatcher {
    private final CustomerMailOutbox outbox;private final CustomerSmtpTransport transport;
    public CustomerMailDispatcher(CustomerMailOutbox outbox,CustomerSmtpTransport transport){this.outbox=outbox;this.transport=transport;}
    public boolean dispatchOnce(){
        if(!transport.configured())return false;transport.requireReady();var lease=outbox.claim();if(lease==null)return false;
        String outcome;try{var p=outbox.open(lease.id());if(!outbox.eligible(p))outcome="SUPPRESSED";else{transport.send(p);outcome="SMTP_ACCEPTED";}}catch(RuntimeException e){outcome="SMTP_UNKNOWN";}
        // SMTP가 접수한 뒤 감사/DB 확정에 실패하면 임대 복구 시 중복 전송될 수 있다. 같은 메시지 ID와 일회 토큰을 유지한다.
        outbox.finish(lease,outcome);return true;
    }
}
