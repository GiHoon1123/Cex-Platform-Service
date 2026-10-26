package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 승인/후보/암호 관측·예산 예약·서명 요청의 원자성을 확인한다. 개인키나 체인 송금은 사용하지 않는다. */
class BitcoinPayoutExecutionIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    BitcoinPayoutCandidates.Candidate candidate;
    @BeforeEach void candidateReady(){candidate=prepareCandidate().candidate();clearInvocations(node);}
    @AfterEach void noExternalKeyOrNodeCalls(){verifyNoInteractions(node,lockNode);}
    void income(String asset,String value){new TransactionTemplate(manager).executeWithoutResult(t->ledger.record("fixture:company:"+UUID.randomUUID(),"FIXTURE_COMPANY_INCOME",List.of(dustin.cex.domains.ledger.LedgerService.p(0,"OPENING",asset,new BigDecimal(value).negate()),dustin.cex.domains.ledger.LedgerService.p(0,"FEE",asset,new BigDecimal(value)))));}
    void observe(){try{assertEquals("OBSERVED",nativeWallet.lock(candidate.payload().id()).status());}finally{clearInvocations(lockNode);}}
    BitcoinWithdrawalPayoutReviews.Request executeNative(){return payouts.commit(approved.payload().id());}
    void none(){assertEquals(0,count("bitcoin_payout_executions"));assertEquals(0,count("bitcoin_payout_sign_requests"));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));}
    @Test void fixedExecutionAndFirstSigningRequestShareTheFinancialCommit(){observe();income("BTC","0.00000182");assertEquals("APPROVED",executeNative().status());var p=new TransactionTemplate(manager).execute(t->executions.read(withdrawal));assertEquals(candidate.payload().id(),p.candidateId());assertEquals(approved.payload().id(),p.reviewId());assertEquals("0.00000182",p.companyExposure());assertEquals("0.00000100",p.funds().fee());assertEquals("0.01000100",p.funds().total());assertEquals(candidate.payload().prepared().psbt(),p.prepared().psbt());assertEquals(1,count("bitcoin_payout_executions"));assertEquals(withdrawal,jdbc.queryForObject("SELECT id FROM bitcoin_payout_sign_requests",UUID.class));assertEquals("PAYOUT_COMMITTED",withdrawals.status(customer.getId(),sid,withdrawal).state());}
    @Test void customerFeeIsNotIncreasedWhenCompanyBudgetIsMissing(){observe();assertEquals(409,assertThrows(ResponseStatusException.class,this::executeNative).getStatusCode().value());none();assertEquals(new BigDecimal("0.00000100"),jdbc.queryForObject("SELECT fee FROM bitcoin_withdrawals WHERE id=?",BigDecimal.class,withdrawal));assertEquals(1,count("bitcoin_payout_input_reservations"));}
    @Test void companyIncomeInAnotherAssetCannotFundTheBitcoinCost(){observe();income("USDT","100000");assertThrows(ResponseStatusException.class,this::executeNative);none();}
    @Test void approvalWithoutAnObservedInputLockCannotIssueASigningRequest(){income("BTC","0.001");assertThrows(ResponseStatusException.class,this::executeNative);none();}
    @Test void unknownLockOutcomeCannotIssueASigningRequest(){income("BTC","0.001");doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(lockNode).ensure(anyString(),anyString(),anyList());assertEquals("UNKNOWN",nativeWallet.lock(candidate.payload().id()).status());clearInvocations(lockNode);assertThrows(ResponseStatusException.class,this::executeNative);none();}
    @Test void changedInputOriginAfterLockBlocksTheFinancialCommit(){observe();income("BTC","0.001");doThrow(BitcoinPayoutInputSources.Held.class).when(inputSources).require(any());assertThrows(BitcoinPayoutInputSources.Held.class,this::executeNative);none();}
    @Test void missingExecutionAuditRollsBackTheSigningRequestAndBudgetReservation(){observe();income("BTC","0.001");doNothing().when(audit).record(isNull(),eq("BTC_NATIVE_PAYOUT_AUTHORIZED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,this::executeNative);}finally{reset(audit);}none();assertEquals(1,count("bitcoin_payout_input_reservations"));}
    @Test void missingSigningRequestAuditCannotFreezeTheCustomerWithdrawal(){observe();income("BTC","0.001");doNothing().when(audit).record(isNull(),eq("BTC_NATIVE_SIGN_REQUESTED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,this::executeNative);}finally{reset(audit);}none();cancel();}
    @Test void repeatedFinancialCommitDoesNotRecreateTheExecutionOrTheSigningRequest(){observe();income("BTC","0.001");executeNative();executeNative();assertEquals(1,count("bitcoin_payout_executions"));assertEquals(1,count("bitcoin_payout_sign_requests"));assertEquals(1,count("bitcoin_withdrawal_payout_commitments"));}
    @Test void concurrentCommitKeepsOneStableSigningRequest()throws Exception{observe();income("BTC","0.001");var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<String> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return executeNative().status();};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals("APPROVED",a.get(20,TimeUnit.SECONDS));assertEquals("APPROVED",b.get(20,TimeUnit.SECONDS));}assertEquals(1,count("bitcoin_payout_executions"));assertEquals(1,count("bitcoin_payout_sign_requests"));}
    @Test void fixedExecutionAndSigningRequestsAreImmutable(){observe();income("BTC","0.001");executeNative();for(String table:List.of("bitcoin_payout_executions","bitcoin_payout_sign_requests"))for(String sql:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));}
    @Test void activeExecutionsReserveCompanyIncomeBeforeAnyNetworkCostIsBooked()throws Exception {
        observe();income("BTC","0.00000272");executeNative();
        doAnswer(call->referenceQuote(new BigDecimal("1000000000"))).when(priceClient).read();referencePrices.refresh();withdrawal=quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.001").payload().id();submit(withdrawal);
        expectedHold=expectedHold.add(new BigDecimal("0.00100010"));events.consumeOrderEvent(event(withdrawal,reserveEngine,"withdrawal_reserved",2));
        transfer=prepare().intentId();doReturn(new dustin.cex.domains.travelrule.CodeVaspWithdrawalAddressClient.Result("VALID",null,PEER)).when(addressClient).verify(any());assertEquals("VERIFIED",execute(transfer).status());clearInvocations(addressClient,transferClient,engineWithdrawal);
        approved=decide(proposal(),true);doAnswer(call->{var p=prepared(call.getArgument(0));return new BitcoinPayoutClient.Prepared(p.withdrawalId(),p.policy(),p.network(),p.wallet(),p.psbt(),p.unsignedTxid(),p.amount(),p.nativeFee(),p.changeAddress(),p.changeScript(),p.changeAmount(),List.of(new BitcoinPayoutClient.Input("b".repeat(64),0,OWN,"0014"+"cd".repeat(20),"0.05000000")),p.quote(),p.finishedAt());}).when(node).prepare(any(),any());
        candidate=prepareCandidate().candidate();clearInvocations(node);observe();assertThrows(ResponseStatusException.class,this::executeNative);
        assertEquals(1,count("bitcoin_payout_executions"));assertEquals(1,count("bitcoin_payout_sign_requests"));assertEquals(2,count("bitcoin_payout_input_reservations"));
        assertEquals(0,new BigDecimal("0.00000272").compareTo(jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE owner_id=0 AND bucket='FEE' AND asset='BTC'",BigDecimal.class)));
    }
}
