package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 실제 PG·고객 서명·운영 MFA·암호화·원본/감사를 검증한다. 노드·CODE HTTP만 대역이며 실제 개인정보나 지급은 전송하지 않는다. */
abstract class BitcoinWithdrawalTransferTestSupport extends BitcoinWithdrawalTestSupport {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]),PEER="beneficiary",OWN="bcrt1qownsyntheticaccount";
    @DynamicPropertySource static void transfer(DynamicPropertyRegistry r) {
        r.add("codevasp.api-url",()->"http://127.0.0.1:1");r.add("codevasp.cipher-url",()->"http://127.0.0.1:2");
        r.add("codevasp.entity-id",()->"withdrawal-review-owner");r.add("codevasp.public-key",()->KEY);r.add("codevasp.private-transfer.enabled",()->true);
        r.add("codevasp.approved-counterparties",()->"[{\"entityId\":\"beneficiary\",\"legalName\":\"가상 수취 사업자\",\"reviewReference\":\"LOCAL-ONLY\",\"evidenceSha256\":\""+"a".repeat(64)+"\",\"validFrom\":\""+Instant.now().minusSeconds(60)+"\",\"validUntil\":\""+Instant.now().plusSeconds(3600)+"\"}]");
    }
    @Autowired BitcoinWithdrawalTransferReview reviews;
    @Autowired BitcoinDepositAddressService ownAddresses;
    @Autowired BitcoinWithdrawalCancellations cancellations;
    @Autowired CodeVaspDirectoryService directory;
    @Autowired CddCrypto crypto;
    @MockitoBean BitcoinCoreClient core;
    @MockitoBean CodeVaspDirectoryClient directoryClient;
    @MockitoBean CodeVaspWithdrawalAddressClient addressClient;
    @MockitoBean CodeVaspWithdrawalAuthorizationClient transferClient;
    @Autowired BitcoinWithdrawalTransferBasis source;
    @Autowired dustin.cex.domains.security.OperatorSessionPolicy operatorSessions;
    @Autowired BitcoinSettings bitcoinSettings;
    UUID withdrawal,reserveEngine;
    BigDecimal expectedHold=GROSS;
    @BeforeEach void preparedOriginal()throws Exception {
        when(core.derive(any())).thenReturn(new BitcoinCoreClient.Address("bitcoin-regtest",OWN,"0014"+"cd".repeat(20)));
        ownAddresses.issue(customer.getId(),sid);
        when(directoryClient.read()).thenAnswer(call->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of(new CodeVaspDirectoryClient.Vasp(PEER,"가상 표시","가상 수취 사업자","KR","CODE",true,List.of(new CodeVaspDirectoryClient.Key(KEY,Instant.now().plusSeconds(3600)))))));
        directory.refresh();withdrawal=price().payload().id();submit(withdrawal);reserveOriginal();
        when(addressClient.verify(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1,count("bitcoin_withdrawal_transfer_attempts"));return new CodeVaspWithdrawalAddressClient.Result("VALID",null,PEER);});
        when(transferClient.authorize(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return verified(call.getArgument(0));});
        clearInvocations(core,directoryClient,engineWithdrawal);
    }
    /** 기본 시험은 합성 엔진 확정을 사용하고, 전체 연결 시험은 이 지점에서 실제 엔진의 WAL/Kafka 예약을 사용한다. */
    void reserveOriginal(){reserveEngine=UUID.randomUUID();events.consumeOrderEvent(event(withdrawal,reserveEngine,"withdrawal_reserved",1));}
    CodeVaspWithdrawalAuthorizationClient.Result verified(CodeVaspWithdrawalAuthorizationClient.Request r) {
        return new CodeVaspWithdrawalAuthorizationClient.Result("VERIFIED",null,new CodeVaspWithdrawalAuthorizationClient.Data(r.transferId(),
            new CodeVaspTransferDataClient.Person(r.originatorName(),null,r.originatorBirth(),List.of(r.originatorAccount())),
            new CodeVaspTransferDataClient.Person(r.beneficiaryName(),null,null,List.of(r.address())),"b".repeat(64)));
    }
    BitcoinWithdrawalTransferReview.State prepare(){return reviews.prepare(maker.user().getId(),maker.sid(),withdrawal,PEER,UUID.randomUUID());}
    BitcoinWithdrawalTransferReview.State execute(UUID id){return reviews.execute(maker.user().getId(),maker.sid(),id);}
    @AfterEach void neverPayOrDiscloseInAudit() {
        amount(FUNDS.subtract(expectedHold),expectedHold);verifyNoInteractions(engineWithdrawal,core,directoryClient);
        for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action LIKE 'BTC_WITHDRAWAL_TRANSFER_%'"))for(String secret:List.of(withdrawalAddress(),OWN,"1990-01-01",declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName()))assertFalse(row.get("detail").toString().contains(secret));
    }
}
