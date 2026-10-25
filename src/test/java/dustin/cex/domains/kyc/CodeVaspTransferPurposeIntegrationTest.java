package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 불변 평가·목적 봉인·지연 제약으로 자동 정보 요청의 범위를 검증한다. 외부 전송·법정 면제 판정은 하지 않는다. */
class CodeVaspTransferPurposeIntegrationTest extends CodeVaspTransferTestSupport {
    void anotherReceipt() {
        receipt = BitcoinDepositFixtures.seed(jdbc, json, customer.getId(), Instant.now());
        BitcoinDepositFixtures.observe(jdbc, json, manager, qualification, receipt, false);
        pending.recognize(receipt.id());
        search = searches.reserve(receipt.id());
        found(search);
        clearInvocations(directoryClient, transactions, transfers, priceClient);
    }
    @Test void exactPurposeAndOriginalValuationAreSealedWithIntent() {
        var intent = intents.reserve(search.id());
        var row = jdbc.queryForMap("SELECT * FROM codevasp_transfer_purposes WHERE intent_id=?", intent.id());
        assertEquals(CodeVaspTransferPurposePolicy.POLICY, row.get("policy_id"));
        assertEquals("DEPOSIT_ORIGIN_VERIFICATION", row.get("purpose"));
        assertEquals(receipt.id(), row.get("receipt_id"));
        assertEquals(0, new BigDecimal("15241577.6543209965279684").compareTo((BigDecimal) row.get("krw_value")));
        var encrypted = jdbc.queryForMap("SELECT ciphertext FROM codevasp_transfer_intents WHERE id=?", intent.id());
        String plain = new String(crypto.decrypt(customer.getId(), intent.id(), (byte[]) encrypted.get("ciphertext")), StandardCharsets.UTF_8);
        var sealed = json.readTree(plain).path("purpose");
        assertEquals(CodeVaspTransferPurposePolicy.POLICY, sealed.path("policy").asString());
        assertEquals(receipt.id().toString(), sealed.path("receiptId").asString());
        assertEquals(((Number) row.get("price_attempt_id")).longValue(), sealed.path("priceAttemptId").asLong());
        assertEquals(intent, intents.reserve(search.id()));
        verifyNoInteractions(priceClient);
    }
    @Test void belowMinimumCannotBorrowLaterHighPriceOrOpenCustomerPlaintext() {
        publishPrice("8100000.00000000");
        anotherReceipt();
        publishPrice("200000000.00000000");
        var guarded = spy(crypto);
        var service = new CodeVaspTransferIntents(searches, execution, directory, policy(), users, eligibility, guarded, jdbc, json, audit, manager, purposes);
        assertThrows(ResponseStatusException.class, () -> service.reserve(search.id()));
        org.mockito.Mockito.verify(guarded, never()).decrypt(anyLong(), any(), any());
        assertEquals(0, count());
        verifyNoInteractions(priceClient);
    }
    @Test void unvaluedCannotBorrowLaterPriceForAutomaticRequest() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "격리 가격 장애")).when(priceClient).read();
        assertEquals("FAILED", prices.refresh().status());
        anotherReceipt();
        publishPrice("200000000.00000000");
        assertThrows(ResponseStatusException.class, () -> intents.reserve(search.id()));
        assertEquals(0, count());
        verifyNoInteractions(priceClient);
    }
    @Test void noCapturedValuationCannotBecomePurpose() {
        UUID unknown = UUID.randomUUID();
        assertThrows(ResponseStatusException.class, () -> new TransactionTemplate(manager).execute(t -> purposes.require(unknown)));
        verifyNoInteractions(priceClient);
    }
    @Test void unrecordedIntentCannotBeUsedForExecution() {
        assertThrows(ResponseStatusException.class, () -> new TransactionTemplate(manager).executeWithoutResult(t -> purposes.requireRecorded(UUID.randomUUID(), receipt.id())));
        verifyNoInteractions(priceClient);
    }
    @Test void purposeFactsCannotBeUpdatedDeletedOrTruncated() {
        var intent = intents.reserve(search.id());
        for (String sql : new String[]{"UPDATE codevasp_transfer_purposes SET krw_value=1 WHERE intent_id='"+intent.id()+"'", "DELETE FROM codevasp_transfer_purposes WHERE intent_id='"+intent.id()+"'", "TRUNCATE codevasp_transfer_purposes"}) {
            assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
        }
        verifyNoInteractions(priceClient);
    }
    @Test void forgedPurposeValueIsRejectedByDatabase() {
        var intent = intents.reserve(search.id());
        var failure = assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO codevasp_transfer_purposes(intent_id,receipt_id,policy_id,purpose,price_attempt_id,reference_at,krw_value)
             SELECT intent_id,receipt_id,policy_id,purpose,price_attempt_id,reference_at,1000000
              FROM codevasp_transfer_purposes WHERE intent_id=?
            """, intent.id()));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("평가 원본이 다릅니다"));
        verifyNoInteractions(priceClient);
    }
    @Test void intentAuditFailureRollsBackPurposeFactToo() {
        doThrow(new IllegalStateException("격리 의도 감사 실패")).when(audit).record(isNull(), eq("CODE_TRANSFER_INTENT_RESERVED"), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> intents.reserve(search.id()));
        assertEquals(0, count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_purposes WHERE receipt_id=?", Long.class, receipt.id()));
        verifyNoInteractions(priceClient);
    }
    @Test void omittedPurposeFactPreventsIntentCommit() {
        var omitted = mock(CodeVaspTransferPurposePolicy.class);
        var purpose = new TransactionTemplate(manager).execute(t -> purposes.require(receipt.id()));
        when(omitted.require(receipt.id())).thenReturn(purpose);
        var broken = new CodeVaspTransferIntents(searches, execution, directory, policy(), users, eligibility, crypto, jdbc, json, audit, manager, omitted);
        var failed = assertThrows(RuntimeException.class, () -> broken.reserve(search.id()));
        assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failed).getMessage().contains("같은 트랜잭션의 목적 근거"));
        assertEquals(0, count());
        verifyNoInteractions(priceClient);
    }
    @Test void purposeHooksRequireExistingBusinessTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> purposes.require(receipt.id()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> purposes.requireRecorded(UUID.randomUUID(), receipt.id()));
        verifyNoInteractions(priceClient);
    }
}
