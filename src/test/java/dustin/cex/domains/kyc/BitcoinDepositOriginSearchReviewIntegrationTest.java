package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 운영 인증/MFA/감사 경계에서 검색 근거만 읽는다. 출처 문자열을 금융 승인으로 바꾸지 않음을 검증한다. */
class BitcoinDepositOriginSearchReviewIntegrationTest extends EddTestSupport {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-origin-review-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
        r.add("codevasp.api-url",()->"http://127.0.0.1:1");r.add("codevasp.cipher-url",()->"http://127.0.0.1:2");r.add("codevasp.entity-id",()->"operator-source");r.add("codevasp.public-key",()->KEY);
    }
    @MockitoBean CodeVaspDirectoryClient directoryClient;
    @MockitoBean CodeVaspTransactionClient transactions;
    @MockitoSpyBean CodeVaspDepositSearchReview review;
    @Autowired CodeVaspDirectoryService directory;
    @Autowired CodeVaspDepositSearches searches;
    @Autowired CodeVaspDepositSearchExecution execution;
    @Autowired BitcoinDepositReviewSource source;
    @Autowired dustin.cex.domains.security.OperatorSessionPolicy operatorSessions;
    @Autowired dustin.cex.domains.security.CustomerFinancialEligibility eligibility;
    @Autowired BitcoinSettings bitcoinSettings;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    BitcoinDepositFixtures.Seed receipt;
    @BeforeEach void original(){
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),customer.getId(),"bcrt1q"+BitcoinDepositFixtures.hex().substring(0,38),"0014"+BitcoinDepositFixtures.hex().substring(0,40));
        receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());when(directoryClient.read()).thenAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of()));directory.refresh();clearInvocations(directoryClient,transactions,review);
    }
    @AfterEach void noReadSideEffects(){verifyNoInteractions(directoryClient,transactions,engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}
    CodeVaspDepositSearchReview.Review read(){return source.read(maker.user().getId(),maker.sid(),receipt.id()).originSearch();}
    CodeVaspTransactionClient.Result found(UUID id){return new CodeVaspTransactionClient.Result(id,CodeVaspTransactionClient.Status.FOUND,"origin-member",List.of(new CodeVaspTransactionClient.Analysis("origin-member","FOUND")));}
    long history(CodeVaspDepositSearches.Search search,String phase,int age,String status,int recordedAge,CodeVaspTransactionClient.Result result){return new TransactionTemplate(manager).execute(t->{
        long attempt=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,?,clock_timestamp()-make_interval(secs=>?)) RETURNING id",Long.class,search.id(),phase,age);jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?) ON CONFLICT(search_id) DO UPDATE SET attempt_id=excluded.attempt_id",search.id(),attempt);
        if(status!=null){String payload=result==null?null:json.writeValueAsString(result);jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash,recorded_at) VALUES(?,?,?,?,clock_timestamp()-make_interval(secs=>?))",attempt,status,payload,payload==null?null:sha(payload),recordedAge);}return attempt;
    });}
    @Test void currentOperatorApproverAndAuditorReadScopedSearchWithNoStore()throws Exception{
        var search=searches.reserve(receipt.id());var auditor=account(UserRole.AUDITOR);
        for(var actor:List.of(maker,checker,auditor))mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+actor.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.originSearch.search.id").value(search.id().toString())).andExpect(jsonPath("$.originSearch.reason").value("AWAITING_SUBMISSION"));
        assertEquals(0,read().historicalSearches());assertEquals("UNSTARTED",read().search().status());
    }
    @Test void foundOriginRemainsInformationExchangeRequiredAndNotFinancialPermission(){
        var search=searches.reserve(receipt.id());history(search,"SUBMIT",181,null,0,null);when(transactions.result(search.id())).thenReturn(found(search.id()));execution.refresh(search.id());clearInvocations(transactions);
        var view=source.read(maker.user().getId(),maker.sid(),receipt.id());assertEquals("FOUND",view.originSearch().search().status());assertEquals("origin-member",view.originSearch().search().originEntityId());assertEquals("INFORMATION_EXCHANGE_REQUIRED",view.originSearch().reason());assertFalse(view.customer().financiallyEligible());assertFalse(view.pending().recognized());
        String auditDetail=jdbc.queryForObject("SELECT detail FROM security_audit WHERE action='BTC_DEPOSIT_REVIEW_SOURCE_READ' AND actor_id=? AND target=? ORDER BY occurred_at DESC LIMIT 1",String.class,maker.user().getId(),"bitcoin-deposit:"+receipt.id());assertTrue(auditDetail.contains("ORIGIN_SEARCH:"+search.id()));assertTrue(auditDetail.contains("ORIGIN_ATTEMPT:"+view.originSearch().search().attemptId()));
    }
    @Test void latestRunningSearchCannotBorrowOlderFound(){
        var search=searches.reserve(receipt.id());history(search,"SUBMIT",241,"SUBMIT_ACKNOWLEDGED",181,null);history(search,"POLL",121,"FOUND",61,found(search.id()));long pending=history(search,"POLL",0,null,0,null);var view=read();assertEquals(pending,view.search().attemptId());assertEquals("RUNNING",view.search().status());assertNull(view.search().originEntityId());assertEquals("IN_PROGRESS",view.reason());
    }
    @Test void latestFailedPollCannotBorrowOlderFound(){
        var search=searches.reserve(receipt.id());history(search,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",121,null);history(search,"POLL",61,"FOUND",31,found(search.id()));when(transactions.result(search.id())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 원문"));execution.refresh(search.id());clearInvocations(transactions);var view=read();assertEquals("POLL_FAILED",view.search().status());assertEquals("PROVIDER_UNAVAILABLE",view.search().failureCode());assertNull(view.search().originEntityId());
    }
    @Test void expiredAutomaticWindowIsVisibleWithoutDeletingEvidence(){
        var search=searches.reserve(receipt.id());history(search,"SUBMIT",901,null,0,null);var view=read();assertEquals("AUTO_WINDOW_EXPIRED",view.reason());assertTrue(view.search().automaticWindowEnded());assertNotNull(view.search().automaticUntil());assertEquals("RUNNING",view.search().status());assertEquals(search.id(),view.search().id());
    }
    @Test void differentSourceHistoryIsCountedWithoutBecomingCurrentSearch(){
        var settings=new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2","other-"+UUID.randomUUID(),KEY);var other=new CodeVaspDirectoryService(settings,directoryClient,jdbc,json,audit,manager);other.refresh();new CodeVaspDepositSearches(other,jdbc,audit,manager).reserve(receipt.id());clearInvocations(directoryClient);var view=read();assertNull(view.search());assertEquals(1,view.historicalSearches());assertTrue(view.providerConfigured());
    }
    @Test void disabledProviderDoesNotFallbackToHistoricalSuccessfulSearch(){
        var search=searches.reserve(receipt.id());history(search,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",121,null);history(search,"POLL",61,"FOUND",31,found(search.id()));
        var disabled=new CodeVaspDepositSearchReview(new CodeVaspSettings("","","",""),directory,jdbc);var other=new BitcoinDepositReviewSource(users,operatorSessions,eligibility,bitcoinSettings,jdbc,audit,manager,disabled);var view=other.read(maker.user().getId(),maker.sid(),receipt.id()).originSearch();assertFalse(view.providerConfigured());assertNull(view.sourceRef());assertNull(view.search());assertEquals(1,view.historicalSearches());assertEquals("PROVIDER_NOT_CONFIGURED",view.reason());
    }
    @Test void unauthorizedOrStaleMfaCannotReadOriginProjection()throws Exception{
        searches.reserve(receipt.id());assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(customer.getId(),sid,receipt.id())).getStatusCode().value());var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);assertEquals(403,assertThrows(ResponseStatusException.class,this::read).getStatusCode().value());org.mockito.Mockito.verify(review,never()).read(any());
        mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void responseAndAuditOmitProviderAddressesKeysAndSourcePayload()throws Exception{
        searches.reserve(receipt.id());String body=mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String hidden:List.of(KEY,"http://127.0.0.1",receipt.address(),receipt.script(),maker.sid().toString(),"analysis","reasonMsg","publicKey","releaseReady","approved"))assertFalse(body.contains(hidden),hidden);
    }
    @Test void sourceFailureIsAuditedAsUnavailableAndMandatoryAuditStillBlocksRead(){
        searches.reserve(receipt.id());CodeVaspDepositSearchReview target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(review);doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"격리 근거 장애")).when(target).read(receipt.id());assertEquals(503,assertThrows(ResponseStatusException.class,this::read).getStatusCode().value());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='BTC_DEPOSIT_REVIEW_SOURCE' AND detail='SOURCE_UNAVAILABLE_503'",Long.class,maker.user().getId()));
        doCallRealMethod().when(target).read(any());doThrow(new IllegalStateException("격리 조회 감사 장애")).when(audit).record(any(),eq("BTC_DEPOSIT_REVIEW_SOURCE_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::read);
    }
    @Test void internalProjectionRequiresCallerTransaction(){assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->review.read(receipt.id()));}
    @Test void normalPendingWaitsForReservationAndCoinbaseRequiresSeparateReview(){
        if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();
        BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);pending.recognize(receipt.id());assertEquals("AWAITING_RESERVATION",read().reason());
        var coin=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());
        new TransactionTemplate(manager).executeWithoutResult(t->{
            String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,coin.address(),coin.script(),new java.math.BigDecimal("0.12345678"))));
            long observation=jdbc.queryForObject("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) VALUES('bitcoin-regtest','cex_watch',?,'CONFIRMED',101,true,?,0,?,100,?::jsonb,clock_timestamp()) RETURNING id",Long.class,coin.txid(),BitcoinDepositFixtures.hex(),BitcoinDepositFixtures.hex(),outputs);
            jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?)",coin.txid(),observation);qualification.evaluate(observation);
        });pending.recognize(coin.id());var view=source.read(maker.user().getId(),maker.sid(),coin.id());assertEquals("COINBASE_REVIEW_REQUIRED",view.originSearch().reason());assertTrue(view.pending().recognized());assertNull(view.originSearch().search());
    }
    @Test void unresolvedTechnicalSearchesRemainManualReviewWithoutOriginOrApproval(){
        for(var status:List.of(CodeVaspTransactionClient.Status.NOT_FOUND,CodeVaspTransactionClient.Status.UNKNOWN_REQUEST,CodeVaspTransactionClient.Status.AMBIGUOUS)){
            var deposit=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());var search=searches.reserve(deposit.id());history(search,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",121,null);history(search,"POLL",61,status.name(),31,new CodeVaspTransactionClient.Result(search.id(),status,null,List.of()));
            var view=source.read(maker.user().getId(),maker.sid(),deposit.id()).originSearch();assertEquals(status.name(),view.search().status());assertNull(view.search().originEntityId());assertEquals("MANUAL_REVIEW_REQUIRED",view.reason());
        }
    }
}
