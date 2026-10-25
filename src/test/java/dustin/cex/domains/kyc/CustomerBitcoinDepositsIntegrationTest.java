package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 세션/HTTP/PG로 조회 경계를 검사한다. 블록/관찰은 이력 조회 전용 합성 원본이며 실제 체인 검증을 주장하지 않는다. */
class CustomerBitcoinDepositsIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->System.getenv("CEX_BITCOIN_TEST_URL"));r.add("bitcoin.rpc-user",()->System.getenv("CEX_BITCOIN_TEST_USER"));
        r.add("bitcoin.rpc-password",()->System.getenv("CEX_BITCOIN_TEST_PASSWORD"));r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @Autowired CustomerBitcoinDeposits deposits;
    @Autowired dustin.cex.domains.security.CustomerSessionPolicy sessions;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @MockitoSpyBean BitcoinDepositReader reader;
    record Seed(UUID id,long owner,String txid,String address,String script){}
    String hex(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    Seed seed(long owner,Instant time){
        String network="bitcoin-regtest";
        // 부모 원본부터 정상 제약 안에서 추가한다. 기존 금융 이력 삭제/불변 트리거 해제를 하지 않는다.
        var addresses=jdbc.queryForList("SELECT id,address,script_pub_key FROM bitcoin_deposit_addresses WHERE user_id=? AND network=?",owner,network);
        if(addresses.isEmpty()){
            jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,?,'cex_watch',?,?)",UUID.randomUUID(),owner,network,"합성조회주소-"+UUID.randomUUID(),"0014"+hex().substring(0,40));
            addresses=jdbc.queryForList("SELECT id,address,script_pub_key FROM bitcoin_deposit_addresses WHERE user_id=? AND network=?",owner,network);
        }
        var address=addresses.getFirst();String txid=hex();UUID receipt=UUID.randomUUID();String received=json.writeValueAsString(List.of(Map.of("addressId",address.get("id"),"txid",txid,"vout",0,"amount",new BigDecimal("0.12345678"))));
        long registry=jdbc.queryForObject("INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES(?,'cex_watch',?,?::jsonb) RETURNING id",Long.class,network,hex(),json.writeValueAsString(List.of(Map.of("id",address.get("id")))));
        String hash=hex();long scan=jdbc.queryForObject("INSERT INTO bitcoin_block_scans(network,wallet,height,block_hash,tip_hash,tip_height,registry_id,received) VALUES(?,'cex_watch',0,?,?,0,?,?::jsonb) RETURNING id",Long.class,network,hash,hash,registry,received);
        jdbc.update("INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id,recorded_at) VALUES(?,?,?,0,?,?,'BTC',0.12345678,?,?)",receipt,network,txid,address.get("id"),owner,scan,Timestamp.from(time));
        return new Seed(receipt,owner,txid,(String)address.get("address"),(String)address.get("script_pub_key"));
    }
    long observe(Seed seed,boolean stale){return new TransactionTemplate(manager).execute(t->{
        String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,seed.address(),seed.script(),new BigDecimal("0.12345678"))));
        long observation=jdbc.queryForObject("""
            INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
             VALUES('bitcoin-regtest','cex_watch',?,'CONFIRMED',6,false,?,0,?,5,?::jsonb,clock_timestamp()-CASE WHEN ? THEN interval '3 minutes' ELSE interval '0 seconds' END) RETURNING id
            """,Long.class,seed.txid(),hex(),hex(),outputs,stale);
        jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);
        jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?) ON CONFLICT(network,txid) DO UPDATE SET observation_id=excluded.observation_id",seed.txid(),observation);
        qualification.evaluate(observation);return observation;
    });}
    @org.junit.jupiter.api.AfterEach void queryNeverCallsEngineOrNode(){verifyNoInteractions(reader,engine);}
    @Test void emptyHistoryCreatesNoAddressOrReceipt(){var page=deposits.own(customer.getId(),sid,null);assertTrue(page.items().isEmpty());assertNull(page.next());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_addresses WHERE user_id=?",Long.class,customer.getId()));}
    @Test void equalTimestampCursorHasNoOmissionsOrDuplicates(){
        Instant at=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);Set<UUID> expected=new HashSet<>();for(int i=0;i<29;i++)expected.add(seed(customer.getId(),at).id());
        var first=deposits.own(customer.getId(),sid,null);assertEquals(25,first.items().size());assertNotNull(first.next());var next=deposits.own(customer.getId(),sid,first.next());assertEquals(4,next.items().size());assertNull(next.next());Set<UUID> actual=new HashSet<>();first.items().forEach(d->assertTrue(actual.add(d.id())));next.items().forEach(d->assertTrue(actual.add(d.id())));assertEquals(expected,actual);
    }
    @Test void exactlyTwentyFiveHasNoEmptyNextPage(){for(int i=0;i<25;i++)seed(customer.getId(),Instant.now().minusSeconds(1));var page=deposits.own(customer.getId(),sid,null);assertEquals(25,page.items().size());assertNull(page.next());}
    @Test void httpProvidesExactAmountAndMinimumOwnFactsWithoutInternalIdentifiers()throws Exception{
        var seed=seed(customer.getId(),Instant.now().minusSeconds(1));
        String body=mvc.perform(get("/api/customer/deposits/BTC/"+seed.id()).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.amount").value("0.12345678")).andExpect(jsonPath("$.chainStatus").value("UNKNOWN")).andExpect(jsonPath("$.fundingStatus").value("UNRECOGNIZED")).andExpect(jsonPath("$.observationFresh").value(false)).andReturn().getResponse().getContentAsString();
        for(String hidden:List.of("wallet","scriptPubKey","decisionId","userId","sessionId",customer.getEmail(),sid.toString(),seed.script()))assertFalse(body.contains(hidden));
        mvc.perform(get("/api/customer/deposits/BTC").header("Authorization","Bearer "+customerAccess).param("userId",maker.user().getId().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(seed.id().toString()));
    }
    @Test void foreignAndMissingIdsBothReturn404AndForeignFactsNeverEnterList()throws Exception{
        var foreign=seed(maker.user().getId(),Instant.now().minusSeconds(1));assertTrue(deposits.own(customer.getId(),sid,null).items().isEmpty());
        for(UUID id:List.of(foreign.id(),UUID.randomUUID())){assertEquals(404,assertThrows(ResponseStatusException.class,()->deposits.detail(customer.getId(),sid,id)).getStatusCode().value());mvc.perform(get("/api/customer/deposits/BTC/"+id).header("Authorization","Bearer "+customerAccess)).andExpect(status().isNotFound());}
    }
    @Test void anonymousAndOperatorAreDeniedAndForeignOrRevokedSessionCannotBypassService()throws Exception{
        mvc.perform(get("/api/customer/deposits/BTC")).andExpect(status().isUnauthorized());mvc.perform(get("/api/customer/deposits/BTC").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->deposits.own(customer.getId(),maker.sid(),null)).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->deposits.own(maker.user().getId(),maker.sid(),null)).getStatusCode().value());
        var token=tokens.findBySessionId(sid).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);assertEquals(403,assertThrows(ResponseStatusException.class,()->deposits.own(customer.getId(),sid,null)).getStatusCode().value());mvc.perform(get("/api/customer/deposits/BTC").header("Authorization","Bearer "+customerAccess)).andExpect(status().isUnauthorized());
    }
    @Test void tradingRestrictionAndMissingVerifiedIdentityDoNotHidePastReceipt(){
        var seed=seed(customer.getId(),Instant.now().minusSeconds(1));var owner=users.findById(customer.getId()).orElseThrow();owner.setTradingRestricted(true);users.saveAndFlush(owner);
        assertEquals(seed.id(),deposits.detail(customer.getId(),sid,seed.id()).id());assertEquals(1,deposits.own(customer.getId(),sid,null).items().size());
    }
    @Test void lastObservationIsTimestampedAndStaleEvidenceIsExplicit(){
        var seed=seed(customer.getId(),Instant.now().minusSeconds(1));observe(seed,false);var fresh=deposits.detail(customer.getId(),sid,seed.id());assertEquals("CONFIRMED",fresh.chainStatus());assertEquals(6,fresh.confirmations());assertTrue(fresh.observationFresh());assertNotNull(fresh.observedAt());
        observe(seed,true);var stale=deposits.detail(customer.getId(),sid,seed.id());assertFalse(stale.observationFresh());assertEquals(6,stale.confirmations());assertEquals("UNRECOGNIZED",stale.fundingStatus());
    }
    @Test void recognizedPendingIsNotAdvertisedAsAvailableBalance(){
        var seed=seed(customer.getId(),Instant.now().minusSeconds(1));observe(seed,false);ledger.initializeOpening();pending.recognize(seed.id());var detail=deposits.detail(customer.getId(),sid,seed.id());assertEquals("PENDING",detail.fundingStatus());assertEquals("0.12345678",detail.amount());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
    }
    @Test void invalidCursorNullDetailAndAmbientTransactionAreRejected()throws Exception{
        for(var cursor:List.of(new CustomerBitcoinDeposits.Cursor(null,UUID.randomUUID()),new CustomerBitcoinDeposits.Cursor(Instant.now(),null),new CustomerBitcoinDeposits.Cursor(Instant.EPOCH.minusSeconds(1),UUID.randomUUID()),new CustomerBitcoinDeposits.Cursor(Instant.now().plusSeconds(300),UUID.randomUUID())))assertEquals(400,assertThrows(ResponseStatusException.class,()->deposits.own(customer.getId(),sid,cursor)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->deposits.detail(customer.getId(),sid,null)).getStatusCode().value());assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->deposits.own(customer.getId(),sid,null)));
        mvc.perform(get("/api/customer/deposits/BTC").param("afterId",UUID.randomUUID().toString()).header("Authorization","Bearer "+customerAccess)).andExpect(status().isBadRequest());mvc.perform(get("/api/customer/deposits/BTC/not-a-uuid").header("Authorization","Bearer "+customerAccess)).andExpect(status().isBadRequest());mvc.perform(get("/api/customer/deposits/ETH").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void mandatoryReadAuditFailurePreventsSuccessfulResponse(){
        var seed=seed(customer.getId(),Instant.now().minusSeconds(1));doThrow(new IllegalStateException("격리 조회 감사 장애")).when(audit).record(any(),eq("CUSTOMER_BTC_DEPOSITS_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->deposits.own(customer.getId(),sid,null));
        doThrow(new IllegalStateException("격리 조회 감사 장애")).when(audit).record(any(),eq("CUSTOMER_BTC_DEPOSIT_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->deposits.detail(customer.getId(),sid,seed.id()));
    }
    @Test void anotherNetworkOfSameCustomerIsNotMixedIntoCurrentHistory(){
        var own=seed(customer.getId(),Instant.now().minusSeconds(1));
        var other=new CustomerBitcoinDeposits(sessions,new BitcoinSettings("","","","","MAINNET"),jdbc,audit,manager);
        assertEquals(List.of(own.id()),deposits.own(customer.getId(),sid,null).items().stream().map(CustomerBitcoinDeposits.Deposit::id).toList());
        assertTrue(other.own(customer.getId(),sid,null).items().isEmpty());assertEquals(404,assertThrows(ResponseStatusException.class,()->other.detail(customer.getId(),sid,own.id())).getStatusCode().value());
    }

    @Test void enrolledCustomerWithoutVerifiedMfaCannotDowngradeToPasswordOnlyHistory()throws Exception{
        // 키를 잃어도 불변 등록 사실이 남는 상태를 만든다. 실제 인증 검증기를 대역으로 바꾸지 않는다.
        jdbc.update("INSERT INTO customer_mfa_enrollments(id,user_id,credential_fingerprint) VALUES(?,?,?)",UUID.randomUUID(),customer.getId(),hex());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->deposits.own(customer.getId(),sid,null)).getStatusCode().value());mvc.perform(get("/api/customer/deposits/BTC").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }

}
