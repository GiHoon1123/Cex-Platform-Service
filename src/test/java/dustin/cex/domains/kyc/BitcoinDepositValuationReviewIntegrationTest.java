package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.valuation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** 실제 운영 세션·PG·HTTP에서 가격 근거 조회를 검증한다. 가격 HTTP만 대역이며 조회는 금융 허가를 만들지 않는다. */
class BitcoinDepositValuationReviewIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("bitcoin.rpc-url", () -> "http://127.0.0.1:1");
        r.add("bitcoin.rpc-user", () -> "isolated");
        r.add("bitcoin.rpc-password", () -> "isolated-valuation-review-password-at-least-32-bytes");
        r.add("bitcoin.wallet", () -> "cex_watch");
        r.add("bitcoin.network", () -> "REGTEST");
        r.add("bitcoin.processing.enabled", () -> false);
        r.add("valuation.upbit.enabled", () -> true);
    }
    @MockitoBean BitcoinKrwPriceClient pricesClient;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired BitcoinDepositReviewSource source;
    @Autowired BitcoinDepositValuationReview valuations;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    BitcoinDepositFixtures.Seed receipt;

    @BeforeEach void deposit() {
        receipt = BitcoinDepositFixtures.seed(jdbc, json, customer.getId(), Instant.now());
        BitcoinDepositFixtures.observe(jdbc, json, manager, qualification, receipt, false);
        // 이미 반입한 최초 원장은 보존한다. 시험마다 기존 금융 이력을 지우고 재생성하지 않는다.
        if (jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'", Long.class) == 0) ledger.initializeOpening();
    }
    void publish(String price) {
        when(pricesClient.read()).thenAnswer(call -> {
            Instant now = Instant.now(), provider = Instant.ofEpochMilli(now.toEpochMilli());
            BigDecimal exact = new BigDecimal(price);
            String payload = json.writeValueAsString(List.of(Map.of("market", "KRW-BTC", "trade_price", exact,
                "trade_timestamp", provider.toEpochMilli(), "timestamp", provider.toEpochMilli())));
            return new BitcoinKrwPriceClient.Quote(exact, provider, provider, now, now, payload,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));
        });
        assertEquals("SUCCESS", prices.refresh().status());
    }
    BitcoinDepositValuationReview.Review read() {
        return source.read(maker.user().getId(), maker.sid(), receipt.id()).valuation();
    }
    @AfterEach void noFinancialRelease() {
        verifyNoInteractions(engine, producer);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_balances", Long.class));
    }

    @Test void missingCaptureIsUnknownAndDoesNotBorrowCurrentPrice() {
        publish("123456789.12345678");
        clearInvocations(pricesClient);
        var view = read();
        assertFalse(view.captured());
        assertEquals("NOT_CAPTURED", view.status());
        assertEquals("UNKNOWN", view.referenceBand());
        assertNull(view.price());
        assertNull(view.krwValue());
        assertEquals(BitcoinDepositValuationReview.ASSURANCE, view.assurance());
        verifyNoInteractions(pricesClient);
    }
    @Test void unavailableCaptureStaysUnknownAfterLaterPriceArrives() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "격리 가격 장애")).when(pricesClient).read();
        assertEquals("FAILED", prices.refresh().status());
        pending.recognize(receipt.id());
        reset(pricesClient);
        publish("123456789.12345678");
        clearInvocations(pricesClient);
        var view = read();
        assertTrue(view.captured());
        assertEquals("UNVALUED", view.status());
        assertEquals("UNKNOWN", view.referenceBand());
        assertNull(view.priceAttemptId());
        assertNull(view.krwValue());
        verifyNoInteractions(pricesClient);
    }
    @Test void exactHistoricalValueAndSourceSurviveNewCurrentPrice() throws Exception {
        publish("123456789.12345678");
        pending.recognize(receipt.id());
        var original = read();
        publish("200000000.00000000");
        clearInvocations(pricesClient);
        assertEquals(original, read());
        assertEquals("15241577.6543209965279684", original.krwValue());
        assertEquals("123456789.12345678", original.price());
        assertEquals("AT_LEAST_1M_KRW_REFERENCE", original.referenceBand());
        mvc.perform(get("/api/admin/deposits/BTC/" + receipt.id()).header("Authorization", "Bearer " + maker.access()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.valuation.krwValue").value(original.krwValue()))
            .andExpect(jsonPath("$.valuation.assurance").value(BitcoinDepositValuationReview.ASSURANCE));
        verifyNoInteractions(pricesClient);
    }
    @Test void belowBoundaryIsReferenceOnlyAndDoesNotGrantExemption() {
        publish("8100000.00000000");
        pending.recognize(receipt.id());
        clearInvocations(pricesClient);
        var view = read();
        assertEquals(0, new BigDecimal("999999.918").compareTo(new BigDecimal(view.krwValue())));
        assertEquals("BELOW_1M_KRW_REFERENCE", view.referenceBand());
        assertEquals(BitcoinDepositValuationReview.ASSURANCE, view.assurance());
        assertFalse(source.read(maker.user().getId(), maker.sid(), receipt.id()).customer().financiallyEligible());
        verifyNoInteractions(pricesClient);
    }
    @Test void customerCannotReadValuationAndInternalReaderNeedsTransaction() throws Exception {
        for (UUID id : List.of(receipt.id(), UUID.randomUUID())) {
            mvc.perform(get("/api/admin/deposits/BTC/" + id).header("Authorization", "Bearer " + customerAccess))
                .andExpect(status().isForbidden());
        }
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> valuations.read(receipt.id()));
        verifyNoInteractions(pricesClient);
    }
    @Test void auditFailurePreventsSuccessfulValuationResponse() {
        publish("123456789.12345678");
        pending.recognize(receipt.id());
        clearInvocations(pricesClient);
        doThrow(new IllegalStateException("격리 운영 조회 감사 실패")).when(audit).record(any(), eq("BTC_DEPOSIT_REVIEW_SOURCE_READ"), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, this::read);
        verifyNoInteractions(pricesClient);
    }
}
