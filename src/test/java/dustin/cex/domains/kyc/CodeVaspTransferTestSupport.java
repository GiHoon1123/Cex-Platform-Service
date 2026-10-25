package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.cdd.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 정보 제공 시험의 공통 합성 원본이다. 외부 공급사/노드/금융 출력을 실제로 호출하지 않는다. */
abstract class CodeVaspTransferTestSupport extends EddTestSupport {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-transfer-intent-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("valuation.upbit.enabled",()->true);
        r.add("codevasp.api-url",()->"http://127.0.0.1:1");r.add("codevasp.cipher-url",()->"http://127.0.0.1:2");r.add("codevasp.entity-id",()->"transfer-intent-owner");r.add("codevasp.public-key",()->KEY);
    }
    @Autowired CodeVaspTransferDetailAssessment assessments;
    @Autowired CodeVaspTransferPurposePolicy purposes;
    @Autowired dustin.cex.domains.valuation.BitcoinKrwPriceService prices;
    @MockitoBean dustin.cex.domains.valuation.BitcoinKrwPriceClient priceClient;
    @MockitoBean CodeVaspDirectoryClient directoryClient;
    @MockitoBean CodeVaspTransactionClient transactions;
    @MockitoBean CodeVaspTransferDataClient transfers;
    @Autowired CodeVaspDepositSearches searches;
    @Autowired CodeVaspDirectoryService directory;
    @Autowired CodeVaspDepositSearchExecution execution;
    @Autowired dustin.cex.domains.security.CustomerFinancialEligibility eligibility;
    @Autowired CddCrypto crypto;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    CodeVaspTransferIntents intents;CodeVaspDepositSearches.Search search;BitcoinDepositFixtures.Seed receipt;
    CodeVaspCounterpartyPolicy policy(){return new CodeVaspCounterpartyPolicy(json.writeValueAsString(List.of(Map.of("entityId","origin","legalName","가상 송신 사업자","reviewReference","LOCAL-ONLY","evidenceSha256","a".repeat(64),"validFrom",Instant.now().minusSeconds(60).toString(),"validUntil",Instant.now().plusSeconds(3600).toString()))));}
    CodeVaspTransferIntents service(CodeVaspCounterpartyPolicy p){return new CodeVaspTransferIntents(searches,execution,directory,p,users,eligibility,crypto,jdbc,json,audit,manager,purposes);}
    @BeforeEach void original()throws Exception{
        verify(false);allowed();
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),customer.getId(),"bcrt1q"+BitcoinDepositFixtures.hex().substring(0,38),"0014"+BitcoinDepositFixtures.hex().substring(0,40));
        receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);
        if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();publishPrice("123456789.12345678");pending.recognize(receipt.id());
        when(directoryClient.read()).thenAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of(new CodeVaspDirectoryClient.Vasp("origin","가상 표시","가상 송신 사업자","KR","CODE",true,List.of(new CodeVaspDirectoryClient.Key(KEY,Instant.now().plusSeconds(3600)))))));directory.refresh();
        search=searches.reserve(receipt.id());found(search);
        intents=service(policy());clearInvocations(directoryClient,transactions,client,engine,producer);
    }
    void found(CodeVaspDepositSearches.Search search){new TransactionTemplate(manager).executeWithoutResult(t->{long a=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'SUBMIT',clock_timestamp()-interval '181 seconds') RETURNING id",Long.class,search.id());jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?)",search.id(),a);jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,recorded_at) VALUES(?,'SUBMIT_ACKNOWLEDGED',clock_timestamp()-interval '121 seconds')",a);a=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'POLL',clock_timestamp()-interval '61 seconds') RETURNING id",Long.class,search.id());jdbc.update("UPDATE codevasp_search_heads SET attempt_id=? WHERE search_id=?",a,search.id());String payload=json.writeValueAsString(new CodeVaspTransactionClient.Result(search.id(),CodeVaspTransactionClient.Status.FOUND,"origin",List.of(new CodeVaspTransactionClient.Analysis("origin","FOUND"))));jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash,recorded_at) VALUES(?,'FOUND',?,encode(sha256(convert_to(?,'UTF8')),'hex'),clock_timestamp()-interval '31 seconds')",a,payload,payload);});}
    @AfterEach void noExternalOrFinancialRelease(){verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT sum(r.amount) FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE r.user_id=?",java.math.BigDecimal.class,customer.getId()).compareTo(jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE owner_id=? AND bucket='PENDING' AND asset='BTC'",java.math.BigDecimal.class,customer.getId())));
        for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action='CODE_TRANSFER_INTENT_RESERVED'"))for(String secret:List.of(declaration(CustomerDeclaration.Pep.NO).fullName(),"1990-01-01",receipt.address()))assertFalse(row.get("detail").toString().contains(secret));}
    /** 합성 가격 원본을 명시적으로 게시한다. 실제 공급사와 접속하지 않으며 금융 근거는 제품 서비스가 기록한다. */
    void publishPrice(String value){
        doAnswer(call->{Instant now=Instant.now(),provider=Instant.ofEpochMilli(now.toEpochMilli());var price=new java.math.BigDecimal(value);String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",price,"trade_timestamp",provider.toEpochMilli(),"timestamp",provider.toEpochMilli())));return new dustin.cex.domains.valuation.BitcoinKrwPriceClient.Quote(price,provider,provider,now,now,payload,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));}).when(priceClient).read();
        assertEquals("SUCCESS",prices.refresh().status());clearInvocations(priceClient);
    }
    long count(){return jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_intents WHERE search_id=?",Long.class,search.id());}
}
