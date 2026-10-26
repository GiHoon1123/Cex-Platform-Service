package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.security.OperatorMfaService;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** 지급 승인 시험의 실제 고객·검토자 서명과 원본 준비를 공유한다. 체인 지급은 하지 않는다. */
abstract class BitcoinWithdrawalPayoutTestSupport extends BitcoinWithdrawalTransferTestSupport {
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
