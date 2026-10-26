package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.mail.CustomerEmailVerification;
import dustin.cex.domains.security.*;
import dustin.cex.shared.http.EngineWithdrawalClient;
import dustin.cex.shared.kafka.KafkaOrderEventConsumer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 출금 예약·취소 시험이 실제 고객·패스키·원장을 같은 방식으로 준비하도록 공유하는 시험 기반이다. */
abstract class BitcoinWithdrawalTestSupport extends CustomerMailTestSupport {
    @org.springframework.test.context.DynamicPropertySource
    static void bitcoin(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("bitcoin.rpc-url", () -> "http://127.0.0.1:1");
        r.add("bitcoin.rpc-user", () -> "isolated");
        r.add("bitcoin.rpc-password", () -> "isolated-withdrawal-reservation-password-32-bytes");
        r.add("bitcoin.wallet", () -> "watch");
        r.add("bitcoin.network", () -> "REGTEST");
    }
    @Autowired CustomerEmailVerification email;
    @Autowired CustomerMfaService factor;
    @Autowired BitcoinWithdrawalQuotes quotes;
    @Autowired BitcoinWithdrawalRequests withdrawals;
    @Autowired KafkaOrderEventConsumer events;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.order.repository.OrderSubmissionRepository submissions;
    @Autowired dustin.cex.shared.kafka.EngineEventInbox inbox;
    @Autowired dustin.cex.domains.balance.repository.UserBalanceRepository balances;
    @MockitoBean BitcoinDestinationClient destination;
    @MockitoBean EngineWithdrawalClient engineWithdrawal;
    SoftwareAuthenticator browser;
    static final String ADDRESS="bcrt1qwithdrawalsynthetic",SCRIPT="0014"+"ab".repeat(20);
    static final BigDecimal FUNDS=new BigDecimal("0.02000000"),GROSS=new BigDecimal("0.01000100");
    long counter=2;

    @BeforeEach void readyCustomerWithFundedLedger()throws Exception {
        verify(false);
        var mail=email.request(customer.getId(),sid,UUID.randomUUID());
        UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,mail.challengeId());
        String secret=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");
        email.confirm(customer.getId(),sid,mail.challengeId(),secret);
        browser=new SoftwareAuthenticator();
        var registration=factor.registrationOptions(customer.getId(),sid,"security-test-password");
        factor.register(customer.getId(),sid,registration.challengeId(),browser.registration(
            new OperatorMfaService.Options(registration.challengeId(),registration.publicKey()),"http://localhost:3003",true));
        var login=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));
        sid=UUID.fromString(jwt.verifyAccessToken(login.getAccessToken()).get("sid",String.class));
        var options=factor.authenticationOptions(customer.getId(),sid);
        var signed=factor.authenticate(customer.getId(),sid,options.challengeId(),browser.assertion(
            new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true,1,null));
        sid=UUID.fromString(jwt.verifyAccessToken(signed.accessToken()).get("sid",String.class));
        customerAccess=signed.accessToken();
        when(destination.validate(ADDRESS)).thenReturn(new BitcoinDestinationClient.Destination("bitcoin-regtest",ADDRESS,SCRIPT,"witness_v0_keyhash"));
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.DURABLY_RESERVED);
        balances.saveAndFlush(UserBalance.builder().userId(customer.getId()).mintAddress("BTC")
            .available(FUNDS).locked(BigDecimal.ZERO).build());
        new TransactionTemplate(manager).executeWithoutResult(t->ledger.initializeOpening());
    }

    BitcoinWithdrawalQuotes.Quote price(){return quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.01");}
    CustomerWithdrawalAuthorization.Options challenge(UUID quote){return withdrawals.options(customer.getId(),sid,quote);}
    CustomerWithdrawalAuthorization.Proof proof(CustomerWithdrawalAuthorization.Options options)throws Exception {
        return new CustomerWithdrawalAuthorization.Proof(options.challengeId(),browser.assertion(
            new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true,counter++,null));
    }
    BitcoinWithdrawalRequests.Receipt submit(UUID id)throws Exception{return withdrawals.submit(customer.getId(),sid,id,proof(challenge(id)));}
    UserBalance balance(){return balances.findByUserIdAndMintAddress(customer.getId(),"BTC").orElseThrow();}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void amount(BigDecimal available,BigDecimal locked){assertEquals(0,available.compareTo(balance().getAvailable()));assertEquals(0,locked.compareTo(balance().getLocked()));}
    String event(UUID id,UUID engine,String type,long sequence){
        var original=jdbc.queryForMap("SELECT total FROM bitcoin_withdrawals WHERE id=?",id);
        var data=new java.util.LinkedHashMap<String,Object>();
        data.put("event_id",engine+":"+sequence+":0");data.put("engine_id",engine);data.put("event_type",type);
        data.put("withdrawal_id",id);data.put("user_id",customer.getId());data.put("asset","BTC");
        data.put("amount",((BigDecimal)original.get("total")).toPlainString());
        data.put("timestamp",java.time.Instant.now().toString());
        if(type.equals("withdrawal_rejected"))data.put("reason_code","RESERVATION_REJECTED");
        return json.writeValueAsString(data);
    }

}
