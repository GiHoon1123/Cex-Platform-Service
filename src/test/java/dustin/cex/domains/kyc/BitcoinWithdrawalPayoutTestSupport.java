package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.security.OperatorMfaService;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** 지급 승인 시험의 실제 고객·검토자 서명과 원본 준비를 공유한다. 체인 지급은 하지 않는다. */
abstract class BitcoinWithdrawalPayoutTestSupport extends BitcoinWithdrawalTransferTestSupport {
    @Autowired BitcoinPayoutCandidates candidates;
    @MockitoBean BitcoinPayoutClient node;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BitcoinPayoutInputSources inputSources;
    @Autowired BitcoinPayoutCandidateWallet nativeWallet;
    @Autowired BitcoinPayoutExecutionRecords executions;
    @MockitoBean BitcoinPayoutInputLocks lockNode;
    static final String PSBT="cHNidP8BAAA=",INPUT="a".repeat(64);
    @Autowired BitcoinWithdrawalPayoutReviews payouts;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired dustin.cex.domains.monitoring.DepositMonitoringService monitoring;
    UUID transfer;
    long approvalCounter=2;
    static final String NOTE="고정 지급 원본과 현재 고객 및 위험 근거를 검토했습니다";
    @BeforeEach void verifiedTransfer() {
        transfer=prepare().intentId();assertEquals("VERIFIED",execute(transfer).status());clearInvocations(addressClient,transferClient);
    }
    @BeforeEach void nativeFixture() {
        // 출처 자격 판정만 대역으로 두고 암호화 저장·원본 검증·필수 감사는 실제 서비스를 사용한다.
        inputSources=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inputSources);
        doNothing().when(inputSources).lockForReview(any(),anyString(),anyString(),anyList());
        doAnswer(call->{var p=call.getArgument(0,BitcoinPayoutClient.Prepared.class);
            return p.inputs().stream().map(i->{var point=new BitcoinPayoutInputSources.Point(i.txid(),i.vout());UUID root=UUID.nameUUIDFromBytes((i.txid()+":"+i.vout()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String event="synthetic-credit:"+i.vout(),authorization="d".repeat(64),credit="e".repeat(64);
                String mac=crypto.fingerprint("btc-native-input-origin-v1",json.writeValueAsString(List.of(BitcoinPayoutInputSources.POLICY,p.network(),p.wallet(),point,i.amount(),i.scriptPubKey(),root,customer.getId(),authorization,event,credit)));
                return new BitcoinPayoutInputSources.Certificate(point,i.amount(),i.scriptPubKey(),new BitcoinPayoutInputSources.Origin(root,customer.getId(),authorization,event,credit,mac),
                    new BitcoinPayoutInputSources.Chain(1,1,"b".repeat(64),0),new BitcoinPayoutInputSources.Risk(UUID.fromString("11111111-1111-1111-1111-111111111111"),"a".repeat(64),"a".repeat(64)));}).toList();
        }).when(inputSources).require(any());
        when(node.quote()).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(count("bitcoin_payout_preparations")>0);return fee();});
        when(node.prepare(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return prepared(call.getArgument(0));});
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<BitcoinPayoutClient.Input> inputs=call.getArgument(2);return new BitcoinPayoutInputLocks.Observation(call.getArgument(0),call.getArgument(1),inputs.stream().map(i->i.txid()+":"+i.vout()).toList(),true,Instant.now());}).when(lockNode).ensure(anyString(),anyString(),anyList());
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());List<BitcoinPayoutClient.Input> inputs=call.getArgument(2);return new BitcoinPayoutInputLocks.Observation(call.getArgument(0),call.getArgument(1),inputs.stream().map(i->i.txid()+":"+i.vout()).toList(),false,Instant.now());}).when(lockNode).release(anyString(),anyString(),anyList());
    }
    BitcoinPayoutClient.FeeQuote fee(){return new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),"b".repeat(64),200,Instant.now(),Instant.now());}
    BitcoinPayoutClient.Prepared prepared(BitcoinPayoutClient.Command c){return new BitcoinPayoutClient.Prepared(c.withdrawalId(),BitcoinPayoutClient.POLICY,c.network(),"watch",PSBT,c.withdrawalId().toString().replace("-", "").repeat(2),c.amount().toPlainString(),"0.00000282","bcrt1qsyntheticchange","0014"+"ef".repeat(20),new BigDecimal("0.05").subtract(c.amount()).subtract(new BigDecimal("0.00000282")).toPlainString(),List.of(new BitcoinPayoutClient.Input(INPUT,0,OWN,"0014"+"cd".repeat(20),"0.05000000")),fee(),Instant.now());}
    BitcoinPayoutCandidates.Candidate readyNative(UUID review) {
        var prepared=candidates.prepare(review);if(!"SAVED".equals(prepared.status()))throw new ResponseStatusException(HttpStatus.CONFLICT);
        var c=prepared.candidate();boolean observed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts a JOIN bitcoin_payout_wallet_results r ON r.attempt_id=a.id WHERE a.candidate_id=? AND a.operation='LOCK' AND r.status='OBSERVED' AND r.locked AND r.recorded_at>=clock_timestamp()-interval '30 seconds')",Boolean.class,c.payload().id()));
        if(!observed&& !"OBSERVED".equals(nativeWallet.lock(c.payload().id()).status()))throw new ResponseStatusException(HttpStatus.CONFLICT);
        // 과거 승인 회귀의 회사 예산은 합성 최초 자본이다. 실제 회사 입금/수익 생성 경로를 검증했다고 주장하지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.execute("SELECT pg_advisory_xact_lock(721552026)");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key='fixture:company-native-budget')",Boolean.class)))
                ledger.record("fixture:company-native-budget","FIXTURE_COMPANY_CAPITAL",List.of(dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","BTC",new BigDecimal("-0.001")),dustin.cex.domains.ledger.LedgerService.p(0,"FEE","BTC",new BigDecimal("0.001"))));});return c;
    }
    BitcoinWithdrawalPayoutReviews.Request commit(UUID review){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE review_id=?)",Boolean.class,review)))readyNative(review);
        return payouts.commit(review);
    }
    @AfterEach void noPrivateOrFinancialEffects(){verifyNoInteractions(addressClient,transferClient);for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action LIKE 'BTC_PAYOUT_%'"))assertFalse(row.get("detail").toString().contains(NOTE));}
    BitcoinWithdrawalPayoutReviews.Request proposal(){return payouts.propose(maker.user().getId(),maker.sid(),transfer,checker.user().getId(),UUID.randomUUID(),NOTE);}
    OperatorMfaService.AuthorizationProof signature(BitcoinWithdrawalPayoutReviews.Request r,boolean approve)throws Exception {
        var options=payouts.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,approvalCounter++,null));
    }
    BitcoinWithdrawalPayoutReviews.Request decide(BitcoinWithdrawalPayoutReviews.Request r,boolean approve)throws Exception {
        payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());return payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,signature(r,approve));
    }
    void cancel(){cancellations.cancel(customer.getId(),sid,withdrawal);clearInvocations(engineWithdrawal);}
    /** 관찰/블록은 합성이고 보류 원장·평가·탐지·사건 연결은 제품 코드다. 기존 고객의 관찰 전용 지갑만 사용한다. */
    UUID nativeInput() {
        return new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->{
            var address=jdbc.queryForMap("SELECT * FROM bitcoin_deposit_addresses WHERE user_id=?",customer.getId());
            UUID id=UUID.randomUUID();String txid=BitcoinDepositFixtures.hex(),block=BitcoinDepositFixtures.hex();
            long registry=jdbc.queryForObject("INSERT INTO bitcoin_scan_registries(network,wallet,digest,addresses) VALUES('bitcoin-regtest','watch',?,?::jsonb) RETURNING id",Long.class,BitcoinDepositFixtures.hex(),json.writeValueAsString(List.of(Map.of("id",address.get("id")))));
            String received=json.writeValueAsString(List.of(Map.of("addressId",address.get("id"),"txid",txid,"vout",0,"amount",new BigDecimal("0.12345678"))));
            long scan=jdbc.queryForObject("INSERT INTO bitcoin_block_scans(network,wallet,height,block_hash,tip_hash,tip_height,registry_id,received) VALUES('bitcoin-regtest','watch',0,?,?,0,?,?::jsonb) RETURNING id",Long.class,block,block,registry,received);
            jdbc.update("INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id) VALUES(?,'bitcoin-regtest',?,0,?,?,'BTC',0.12345678,?)",id,txid,address.get("id"),customer.getId(),scan);
            String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,OWN,(String)address.get("script_pub_key"),new BigDecimal("0.12345678"))));
            long observation=jdbc.queryForObject("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) VALUES('bitcoin-regtest','watch',?,'CONFIRMED',6,false,?,0,?,5,?::jsonb,clock_timestamp()) RETURNING id",Long.class,txid,block,block,outputs);
            jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);
            jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?)",txid,observation);qualification.evaluate(observation);return id;
        });
    }
}
