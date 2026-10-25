package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 운영 인증 경계에서 전송·비교·작업의 참조만 읽는다. 조회가 개인정보 교환이나 금융 처리를 시작하지 않는다. */
class CodeVaspTransferReviewIntegrationTest extends CodeVaspTransferTestSupport {
    @Autowired dustin.cex.domains.monitoring.DepositMonitoringReview accountMonitoring;
    @Autowired BitcoinDepositReviewSource source;
    @Autowired dustin.cex.domains.security.OperatorSessionPolicy operatorSessions;
    @Autowired BitcoinSettings bitcoin;
    @Autowired dustin.cex.domains.valuation.BitcoinDepositValuationReview valuation;
    CodeVaspTransferExecution exchange(){return new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments);}
    String name(){return declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName();}
    CodeVaspTransferDataClient.Result normal(String beneficiary){
        var sender=new CodeVaspTransferDataClient.Person(name(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));var receiver=new CodeVaspTransferDataClient.Person(beneficiary,null,LocalDate.of(1990,1,1),List.of(receipt.address()));return new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),sender,receiver,"b".repeat(64)));
    }
    Map<String,Long> counts(){var result=new TreeMap<String,Long>();for(String table:List.of("ledger_entries","codevasp_transfer_intents","codevasp_transfer_attempts","codevasp_transfer_results","codevasp_transfer_detail_assessments","codevasp_transfer_jobs"))result.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));return result;}
    CodeVaspDepositSearchReview.Review read(){var before=counts();var view=source.read(maker.user().getId(),maker.sid(),receipt.id()).originSearch();assertEquals(before,counts());assertEquals("METADATA_ONLY_NOT_A_FINANCIAL_DECISION",view.assurance());return view;}
    CodeVaspTransferExecution.State receive(String beneficiary){var intent=intents.reserve(search.id());when(transfers.exchange(any())).thenReturn(normal(beneficiary));var state=exchange().execute(intent.id());clearInvocations(transfers);return state;}
    @Test void foundSearchAloneDoesNotInventTransferOrAssessment(){var view=read();assertEquals("ORIGIN_FOUND_REVIEW_REQUIRED",view.reason());assertNull(view.transfer());assertNull(view.job());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void reservedIntentShowsPurposeAndExplicitlyUnstartedTransfer(){var intent=intents.reserve(search.id());var transfer=read().transfer();assertEquals(intent.id(),transfer.intentId());assertEquals(CodeVaspTransferPurposePolicy.POLICY,transfer.purposePolicy());assertEquals("DEPOSIT_ORIGIN_VERIFICATION",transfer.purpose());assertEquals("UNSTARTED",transfer.status());assertNull(transfer.attemptId());assertNull(transfer.assessment());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void receivedConsistentSummaryContainsOnlyReferencesAndCodes()throws Exception{
        var state=receive(name());var view=read();assertEquals(state.attemptId(),view.transfer().attemptId());assertEquals("DATA_RECEIVED",view.transfer().status());assertEquals(CodeVaspTransferDetailAssessment.POLICY,view.transfer().assessment().policy());assertEquals("CONSISTENT",view.transfer().assessment().status());assertEquals("DETAILS_CONSISTENT",view.transfer().assessment().reason());
        String response=mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.originSearch.transfer.assessment.status").value("CONSISTENT")).andReturn().getResponse().getContentAsString();
        for(String secret:List.of(name(),"1990-01-01",receipt.address(),"ciphertext","contentMac","identityMac"))assertFalse(response.contains(secret),secret);org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);
    }
    @Test void mismatchedDetailsRemainReviewDespiteSuccessfulReceipt(){receive("다른 수취인");var view=read();assertEquals("DATA_RECEIVED",view.transfer().status());assertEquals("REVIEW_REQUIRED",view.transfer().assessment().status());assertEquals("BENEFICIARY_NAME_MISMATCH",view.transfer().assessment().reason());assertEquals("ORIGIN_FOUND_REVIEW_REQUIRED",view.reason());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void unknownOutcomeCannotBorrowAssessmentOrTriggerAnotherRequest(){var intent=intents.reserve(search.id());when(transfers.exchange(any())).thenThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN));exchange().execute(intent.id());clearInvocations(transfers);var transfer=read().transfer();assertEquals("OUTCOME_UNKNOWN",transfer.status());assertEquals("PROVIDER_UNAVAILABLE",transfer.reason());assertNull(transfer.assessment());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void inFlightReadShowsCurrentStartWithoutWaitingForExternalCall(){
        var intent=intents.reserve(search.id());when(transfers.exchange(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var transfer=read().transfer();assertEquals("RUNNING",transfer.status());assertNotNull(transfer.attemptId());assertNull(transfer.recordedAt());assertNull(transfer.assessment());return normal(name());});assertEquals("DATA_RECEIVED",exchange().execute(intent.id()).status());clearInvocations(transfers);assertEquals("CONSISTENT",read().transfer().assessment().status());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);
    }
    @Test void manualReviewJobCanExistWithoutDisclosableIntent(){jdbc.update("INSERT INTO codevasp_transfer_jobs(search_id,state,last_reason,next_attempt_at) VALUES(?,'REVIEW_REQUIRED','CURRENT_CONDITIONS_NOT_MET',NULL)",search.id());var view=read();assertNull(view.transfer());assertEquals("REVIEW_REQUIRED",view.job().state());assertEquals("CURRENT_CONDITIONS_NOT_MET",view.job().reason());assertNull(view.job().nextAttemptAt());assertNull(view.job().leaseUntil());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void changedConnectionAuthorityDoesNotExposeOldPrivateRecords(){
        receive(name());var otherSettings=new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2","other-review-authority",KEY);var otherDirectory=new CodeVaspDirectoryService(otherSettings,directoryClient,jdbc,json,audit,manager);var otherReview=new CodeVaspDepositSearchReview(otherSettings,otherDirectory,jdbc);var otherSource=new BitcoinDepositReviewSource(new BitcoinDepositOperatorAccess(users,operatorSessions,jdbc),eligibility,bitcoin,jdbc,audit,manager,otherReview,valuation,accountMonitoring);var view=otherSource.read(maker.user().getId(),maker.sid(),receipt.id()).originSearch();assertNull(view.search());assertNull(view.transfer());assertNull(view.job());assertEquals(1,view.historicalSearches());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);
    }
    @Test void customerCannotUsePrivateMetadataView()throws Exception{receive(name());for(UUID id:List.of(receipt.id(),UUID.randomUUID()))mvc.perform(get("/api/admin/deposits/BTC/"+id).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void auditFailureCannotReturnPrivateMetadata(){receive(name());doThrow(new IllegalStateException("격리 운영 메타데이터 감사 장애")).when(audit).record(any(),eq("BTC_DEPOSIT_REVIEW_SOURCE_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::read);org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);}
    @Test void newerChainObservationDoesNotRelabelHistoricalRequestBasis(){
        receive(name());long original=read().transfer().requestBasis().observationId();
        BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);
        var full=source.read(maker.user().getId(),maker.sid(),receipt.id());assertNotEquals(original,full.chain().observationId());assertEquals(original,full.originSearch().transfer().requestBasis().observationId());assertNotNull(full.originSearch().transfer().requestBasis().searchAttemptId());assertNotNull(full.originSearch().transfer().requestBasis().directoryAttemptId());assertNotNull(full.originSearch().transfer().requestBasis().chainDecisionId());assertNotNull(full.originSearch().transfer().requestBasis().cddAssessmentId());org.mockito.Mockito.verifyNoInteractions(transfers,priceClient);
    }
}
