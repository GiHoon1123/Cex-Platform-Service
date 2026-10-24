package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 로컬 노드·PG·현재 고객 확인·HTTP 인가를 연결한다. 실패 주입도 실제 주소 파생 경계를 사용한다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinDepositAddressIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->System.getenv("CEX_BITCOIN_TEST_URL"));r.add("bitcoin.rpc-user",()->System.getenv("CEX_BITCOIN_TEST_USER"));
        r.add("bitcoin.rpc-password",()->System.getenv("CEX_BITCOIN_TEST_PASSWORD"));r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @Autowired BitcoinDepositAddressService addresses;
    @MockitoSpyBean BitcoinCoreClient core;
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_addresses WHERE user_id=?",Long.class,customer.getId());}
    void ready()throws Exception{verify(false);allowed();}
    @Test void eligibleCustomerGetsOneDurableAddressAndReusesWithoutNode()throws Exception{
        ready();var first=addresses.issue(customer.getId(),sid);assertEquals("BTC",first.asset());assertEquals("bitcoin-regtest",first.network());assertTrue(first.address().startsWith("bcrt1q"));
        doThrow(new IllegalStateException("호출되면 안 되는 노드")).when(core).derive(any());
        assertEquals(first,addresses.issue(customer.getId(),sid));assertEquals(first,addresses.own(customer.getId(),sid));assertEquals(1,count());
        mvc.perform(get("/api/customer/deposit-addresses/BTC").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(first.id().toString())).andExpect(jsonPath("$.wallet").doesNotExist())
            .andExpect(jsonPath("$.scriptPubKey").doesNotExist()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void actualHttpPostPersistsBeforeReturningAndDoesNotChooseCallerUser()throws Exception{
        ready();mvc.perform(post("/api/customer/deposit-addresses/BTC").header("Authorization","Bearer "+customerAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.asset").value("BTC"));assertEquals(1,count());
        mvc.perform(get("/api/customer/deposit-addresses/ETH").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void unverifiedCustomerIsRejectedBeforeNode(){
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());
        org.mockito.Mockito.verify(core,never()).derive(any());assertEquals(0,count());
    }
    @Test void getWithoutAddressDoesNotCreateOne()throws Exception{
        ready();assertEquals(404,assertThrows(ResponseStatusException.class,()->addresses.own(customer.getId(),sid)).getStatusCode().value());org.mockito.Mockito.verify(core,never()).derive(any());assertEquals(0,count());
    }
    @Test void strangersOperatorsAndAnonymousCannotReadOrIssue()throws Exception{
        ready();addresses.issue(customer.getId(),sid);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.own(customer.getId(),maker.sid())).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(maker.user().getId(),maker.sid())).getStatusCode().value());
        mvc.perform(post("/api/customer/deposit-addresses/BTC")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customer/deposit-addresses/BTC").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
    }
    @Test void logoutDuringRealDerivationPreventsOwnershipCommit()throws Exception{
        ready();doAnswer(call->{var result=call.callRealMethod();new TransactionTemplate(manager).executeWithoutResult(t->{var token=tokens.findBySessionId(sid).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);});return result;}).when(core).derive(any());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(0,count());
    }
    @Test void restrictionDuringRealDerivationPreventsOwnershipCommit()throws Exception{
        ready();doAnswer(call->{var result=call.callRealMethod();new TransactionTemplate(manager).executeWithoutResult(t->{var user=users.lockById(customer.getId()).orElseThrow();user.setTradingRestricted(true);users.saveAndFlush(user);});return result;}).when(core).derive(any());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(0,count());
    }
    @Test void concurrentRealDerivationsPublishOnlyOneOwnerAddress()throws Exception{
        ready();var arrived=new CountDownLatch(2);
        doAnswer(call->{var result=call.callRealMethod();arrived.countDown();assertTrue(arrived.await(5,TimeUnit.SECONDS));return result;}).when(core).derive(any());
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->addresses.issue(customer.getId(),sid));var second=pool.submit(()->addresses.issue(customer.getId(),sid));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));assertEquals(1,count());
        }
    }
    @Test void auditFailureRollsBackAddressAndNeverReturnsSuccess()throws Exception{
        ready();doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(customer.getId()),eq("BTC_ADDRESS_ISSUED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->addresses.issue(customer.getId(),sid));assertEquals(0,count());
    }
    @Test void anotherCustomerAddressCollisionNeverChangesItsOwner()throws Exception{
        ready();var derived=core.derive(UUID.randomUUID());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,?,'cex_watch',?,?)",UUID.randomUUID(),maker.user().getId(),derived.network(),derived.address(),derived.scriptPubKey());
        doReturn(derived).when(core).derive(any());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(0,count());
        assertEquals(maker.user().getId(),jdbc.queryForObject("SELECT user_id FROM bitcoin_deposit_addresses WHERE address=?",Long.class,derived.address()));
        // 주소 문자열이 달라도 동일한 지출 조건을 다른 고객에게 연결할 수 없다.
        doReturn(new BitcoinCoreClient.Address(derived.network(),derived.address()+"different",derived.scriptPubKey())).when(core).derive(any());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(0,count());
    }
    @Test void ownershipIsImmutableAndCannotBeTruncated()throws Exception{
        ready();var one=addresses.issue(customer.getId(),sid);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_deposit_addresses SET user_id=? WHERE id=?",maker.user().getId(),one.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_deposit_addresses WHERE id=?",one.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE bitcoin_deposit_addresses"));assertEquals(one,addresses.own(customer.getId(),sid));
    }
    @Test void nodeFailureLeavesNoOwnershipAndOuterTransactionIsRejected()throws Exception{
        ready();doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(core).derive(any());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(0,count());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->addresses.issue(customer.getId(),sid)));
    }
    @Test void existingAddressCannotBypassCurrentRestriction()throws Exception{
        ready();addresses.issue(customer.getId(),sid);var user=users.findById(customer.getId()).orElseThrow();user.setTradingRestricted(true);users.saveAndFlush(user);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.own(customer.getId(),sid)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());assertEquals(1,count());
    }
    @Test void expiredIdentityAndStaleProviderConfirmationCannotCreateAddress()throws Exception{
        ready();jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());
        org.mockito.Mockito.verify(core,never()).derive(any());assertEquals(0,count());
    }
    @Test void staleProviderConfirmationCannotCreateAddress()throws Exception{
        ready();jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=(SELECT active_attempt FROM kyc_profiles WHERE user_id=?)",customer.getId());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->addresses.issue(customer.getId(),sid)).getStatusCode().value());
        org.mockito.Mockito.verify(core,never()).derive(any());assertEquals(0,count());
    }
}
