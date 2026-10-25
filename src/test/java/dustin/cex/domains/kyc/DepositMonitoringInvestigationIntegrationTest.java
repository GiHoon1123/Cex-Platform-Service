package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.valuation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** 메서드별 새 PG에서 실제 금융 입력·운영 인증·암호화·필수 감사를 확인한다. 손상은 조회 복사본에만 주입한다. */
class DepositMonitoringInvestigationIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-monitoring-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);r.add("valuation.upbit.enabled",()->true);}
    @Autowired DepositMonitoringInvestigations investigations;
    @Autowired DepositMonitoringService monitoring;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinDepositOperatorAccess access;
    @Autowired BitcoinSettings bitcoin;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired CddCrypto declarationCrypto;
    @Autowired EvidenceCrypto evidenceCrypto;
    @MockitoBean BitcoinKrwPriceClient price;
    @MockitoBean BitcoinDepositReader reader;
    BitcoinDepositFixtures.Seed receipt;
    UUID assessment;
    @BeforeEach void input(){ledger.initializeOpening();receipt=deposit(customer.getId());assessment=monitoring.processNext().orElseThrow().id();assertEquals("INCOMPLETE",jdbc.queryForObject("SELECT outcome FROM deposit_monitoring_assessments WHERE id=?",String.class,assessment));}
    BitcoinDepositFixtures.Seed deposit(long owner){var r=BitcoinDepositFixtures.seed(jdbc,json,owner,Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);pending.recognize(r.id());return r;}
    String path(){return "/api/admin/deposits/BTC/"+receipt.id()+"/monitoring-assessments/"+assessment;}
    DepositMonitoringInvestigations.Viewed inspect(){return investigations.inspect(maker.user().getId(),maker.sid(),receipt.id(),assessment);}
    DepositMonitoringInvestigations.Write request(UUID view,UUID key,String text){return new DepositMonitoringInvestigations.Write(view,key,DepositMonitoringInvestigations.Finding.INSUFFICIENT_INFORMATION,text);}
    DepositMonitoringInvestigations.Observation record(){var view=inspect();return investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"추가 송금 근거를 확인해야 한다"));}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    DepositMonitoringInvestigations service(JdbcTemplate database,CddCrypto cdd,EvidenceCrypto crypto){return new DepositMonitoringInvestigations(access,bitcoin,database,audit,cdd,crypto,json,manager);}
    DepositMonitoringInvestigations corrupted(String sql,Consumer<Map<String,Object>> corrupt){var database=spy(jdbc);doAnswer(call->{Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);var rows=jdbc.queryForList(call.getArgument(0,String.class),args);var copy=new HashMap<>(rows.getFirst());corrupt.accept(copy);return List.of(copy);}).when(database).queryForList(eq(sql),any(Object[].class));return service(database,declarationCrypto,evidenceCrypto);}
    @AfterEach void noFinancialMutation(){verifyNoInteractions(engine,producer,reader,price);assertEquals(0,count("user_balances"));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",BigDecimal.class).signum());assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());}
    @Test void operatorAndApproverInspectExactInputsAndRecordAccessThroughHttp()throws Exception{
        for(var actor:List.of(maker,checker))mvc.perform(post(path()+"/inspect").header("Authorization","Bearer "+actor.access()).with(r->{r.setRemoteAddr("192.0.2.55");return r;})).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.inputs[0].cursor").isString()).andExpect(jsonPath("$.inputs[0].txid").value(receipt.txid())).andExpect(jsonPath("$.inputs[0].krwValue").doesNotExist()).andExpect(jsonPath("$.assurance").value(DepositMonitoringInvestigations.ASSURANCE));
        assertEquals(2,count("deposit_monitoring_source_views"));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='MONITORING_EVIDENCE_INSPECTED' AND source_address='192.0.2.55'",Long.class));assertFalse(inspect().toString().contains(receipt.txid()));
    }
    @Test void auditorListsMetadataButCannotInspectOrWriteNotes()throws Exception{
        var auditor=account(UserRole.AUDITOR);mvc.perform(get(path().substring(0,path().lastIndexOf('/'))).header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(assessment.toString())).andExpect(jsonPath("$.items[0].inputs").doesNotExist());
        mvc.perform(post(path()+"/inspect").header("Authorization","Bearer "+auditor.access())).andExpect(status().isForbidden());
        mvc.perform(post(path()+"/observations").header("Authorization","Bearer "+auditor.access()).contentType("application/json").content("{}")).andExpect(status().isForbidden());assertEquals(0,count("deposit_monitoring_source_views"));
    }
    @Test void customerAndAnonymousCannotProbeExistingOrMissingTargets()throws Exception{
        for(UUID id:List.of(assessment,UUID.randomUUID())){String p="/api/admin/deposits/BTC/"+receipt.id()+"/monitoring-assessments/"+id+"/inspect";mvc.perform(post(p)).andExpect(status().isUnauthorized());mvc.perform(post(p).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());}assertEquals(0,count("deposit_monitoring_source_views"));
    }
    @Test void staleMfaAndForeignSessionAreRejectedBeforeSourceRead(){
        var database=spy(jdbc);var target=service(database,declarationCrypto,evidenceCrypto);jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=mfa_verified_at-interval '6 minutes' WHERE session_id=?",maker.sid());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),assessment)).getStatusCode().value());verifyNoInteractions(database);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->investigations.inspect(checker.user().getId(),maker.sid(),receipt.id(),assessment)).getStatusCode().value());
    }
    @Test void revokedOperatorCannotReadStoredEvidence(){jdbc.update("UPDATE users SET enabled=false WHERE id=?",maker.user().getId());assertEquals(403,assertThrows(ResponseStatusException.class,this::inspect).getStatusCode().value());assertEquals(0,count("deposit_monitoring_source_views"));}
    @Test void foreignCustomerReceiptCannotBorrowAssessment(){var another=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var foreign=deposit(another.getId());assertEquals(404,assertThrows(ResponseStatusException.class,()->investigations.inspect(maker.user().getId(),maker.sid(),foreign.id(),assessment)).getStatusCode().value());assertEquals(0,count("deposit_monitoring_source_views"));}
    @Test void sourceHashAndRecomputedPolicyMustAgree(){
        for(var target:List.of(corrupted("SELECT * FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",r->r.put("source_hash","0".repeat(64))),corrupted("SELECT * FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",r->r.put("unknown_transfers",99)),corrupted("SELECT * FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",r->r.put("reference_at",Timestamp.from(((Timestamp)r.get("reference_at")).toInstant().plusSeconds(1))))))assertEquals(503,assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),assessment)).getStatusCode().value());assertEquals(0,count("deposit_monitoring_source_views"));
    }
    @Test void forgedDocumentWithNewHashStillMustMatchRealFinancialSource(){
        var target=corrupted("SELECT * FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",r->{var nodes=json.readTree((String)r.get("source_document"));((tools.jackson.databind.node.ObjectNode)nodes.get(0)).put("txid","a".repeat(64));String doc=json.writeValueAsString(nodes);r.put("source_document",doc);r.put("source_hash",sha(doc));});assertEquals(503,assertThrows(ResponseStatusException.class,()->target.inspect(maker.user().getId(),maker.sid(),receipt.id(),assessment)).getStatusCode().value());assertEquals(0,count("deposit_monitoring_source_views"));
    }
    @Test void recordAndReadEncryptedNotesWithoutChangingRiskOrMoney()throws Exception{
        var view=inspect();var request=request(view.viewId(),UUID.randomUUID(),"추가 송금 근거\n확인 필요");String raw=json.writeValueAsString(request);
        var result=mvc.perform(post(path()+"/observations").header("Authorization","Bearer "+maker.access()).contentType("application/json").content(raw)).andExpect(status().isOk()).andExpect(jsonPath("$.finding").value("INSUFFICIENT_INFORMATION")).andExpect(jsonPath("$.notes").doesNotExist()).andReturn();UUID id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post(path()+"/observations/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.notes").value(request.notes())).andExpect(jsonPath("$.observation.assurance").value(DepositMonitoringInvestigations.ASSURANCE));
        assertEquals(1,count("deposit_monitoring_observation_views"));var row=jdbc.queryForMap("SELECT * FROM deposit_monitoring_observations WHERE id=?",id);assertFalse(new String((byte[])row.get("notes_cipher"),java.nio.charset.StandardCharsets.UTF_8).contains("추가 송금"));
        for(var audit:jdbc.queryForList("SELECT detail FROM security_audit WHERE action LIKE 'MONITORING_%'"))assertFalse(audit.toString().contains("추가 송금"));assertFalse(request.toString().contains("추가 송금"));
    }
    @Test void sameRequestKeyIsIdempotentAndChangedContentConflicts(){var view=inspect();var key=UUID.randomUUID();var request=request(view.viewId(),key,"같은 요청 원문");var one=investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request);assertEquals(one.id(),investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request).id());assertEquals(409,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),key,"다른 내용"))).getStatusCode().value());assertEquals(1,count("deposit_monitoring_observations"));}
    @Test void concurrentSameRequestCreatesOnlyOneObservation()throws Exception{var view=inspect();var request=request(view.viewId(),UUID.randomUUID(),"동시 재전송 원문");try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request));var b=pool.submit(()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());}assertEquals(1,count("deposit_monitoring_observations"));}
    @Test void observationRequiresOwnSourceViewAndOperatorRole()throws Exception{var view=investigations.inspect(checker.user().getId(),checker.sid(),receipt.id(),assessment);assertEquals(409,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"다른 사람 열람"))).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->investigations.record(checker.user().getId(),checker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"승인자 작성"))).getStatusCode().value());mvc.perform(post(path()+"/observations").header("Authorization","Bearer "+checker.access()).contentType("application/json").content(json.writeValueAsString(request(view.viewId(),UUID.randomUUID(),"승인자의 HTTP 작성")))).andExpect(status().isForbidden());assertEquals(0,count("deposit_monitoring_observations"));}
    @Test void malformedNotesAndMissingFieldsCannotWrite(){var view=inspect();for(String text:List.of(""," ","x".repeat(2001),"숨김\u0000문자","표시\u202e전환"))assertEquals(400,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),text))).getStatusCode().value());assertEquals(400,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,null)).getStatusCode().value());assertEquals(0,count("deposit_monitoring_observations"));}
    @Test void auditorGetsObservationMetadataWithoutPlaintext()throws Exception{var note=record();var auditor=account(UserRole.AUDITOR);mvc.perform(get(path()+"/observations").header("Authorization","Bearer "+auditor.access())).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(note.id().toString())).andExpect(jsonPath("$[0].notes").doesNotExist());mvc.perform(post(path()+"/observations/"+note.id()+"/inspect").header("Authorization","Bearer "+auditor.access())).andExpect(status().isForbidden());assertEquals(0,count("deposit_monitoring_observation_views"));}
    @Test void ciphertextTamperingAndUnsupportedKeysAreRejected(){var note=record();String query="SELECT * FROM deposit_monitoring_observations WHERE id=? AND assessment_id=?";for(var target:List.of(corrupted(query,r->{var cipher=((byte[])r.get("notes_cipher")).clone();cipher[cipher.length-1]^=1;r.put("notes_cipher",cipher);}),corrupted(query,r->r.put("evidence_key_id","0".repeat(64))),corrupted(query,r->r.put("finding","EXPLAINED_ACTIVITY")),corrupted(query,r->r.put("fingerprint_key_id","0".repeat(64)))))assertEquals(503,assertThrows(ResponseStatusException.class,()->target.inspectObservation(checker.user().getId(),checker.sid(),receipt.id(),assessment,note.id())).getStatusCode().value());assertEquals(0,count("deposit_monitoring_observation_views"));}
    @Test void auditFailureRollsBackSourceInspection(){doThrow(new IllegalStateException("격리 필수 감사 실패")).when(audit).record(anyLong(),eq("MONITORING_EVIDENCE_INSPECTED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::inspect);assertEquals(0,count("deposit_monitoring_source_views"));assertEquals(1,count("deposit_monitoring_assessments"));}
    @Test void missingAuditCannotCommitSourceInspection(){doNothing().when(audit).record(anyLong(),eq("MONITORING_EVIDENCE_INSPECTED"),anyString(),anyString(),anyString());auditConstraint(()->inspect());assertEquals(0,count("deposit_monitoring_source_views"));}
    @Test void auditFailureRollsBackObservationButKeepsItsView(){var view=inspect();doThrow(new IllegalStateException("격리 의견 감사 실패")).when(audit).record(anyLong(),eq("MONITORING_OBSERVATION_RECORDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"보존할 조사 메모")));assertEquals(0,count("deposit_monitoring_observations"));assertEquals(1,count("deposit_monitoring_source_views"));}
    @Test void missingAuditCannotCommitObservationOrNoteAccess(){var view=inspect();doNothing().when(audit).record(anyLong(),eq("MONITORING_OBSERVATION_RECORDED"),anyString(),anyString(),anyString());auditConstraint(()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"감사 누락 메모")));assertEquals(0,count("deposit_monitoring_observations"));doCallRealMethod().when(audit).record(anyLong(),eq("MONITORING_OBSERVATION_RECORDED"),anyString(),anyString(),anyString());var note=record();doNothing().when(audit).record(anyLong(),eq("MONITORING_OBSERVATION_INSPECTED"),anyString(),anyString(),anyString());auditConstraint(()->investigations.inspectObservation(checker.user().getId(),checker.sid(),receipt.id(),assessment,note.id()));assertEquals(0,count("deposit_monitoring_observation_views"));}
    @Test void historiesCannotBeUpdatedDeletedOrTruncated(){var note=record();investigations.inspectObservation(checker.user().getId(),checker.sid(),receipt.id(),assessment,note.id());for(String table:List.of("deposit_monitoring_source_views","deposit_monitoring_observations","deposit_monitoring_observation_views"))for(String operation:List.of("UPDATE "+table+" SET actor_id=actor_id","DELETE FROM "+table,"TRUNCATE "+table))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(operation));assertEquals(1,count("deposit_monitoring_observations"));}
    @Test void metadataUsesBoundedKeysetPaginationWithoutDuplicates(){for(int i=0;i<26;i++){deposit(customer.getId());monitoring.processNext().orElseThrow();}var first=investigations.list(maker.user().getId(),maker.sid(),receipt.id(),null);assertEquals(25,first.items().size());assertNotNull(first.nextBefore());var second=investigations.list(maker.user().getId(),maker.sid(),receipt.id(),Long.parseLong(first.nextBefore()));assertEquals(2,second.items().size());assertNull(second.nextBefore());var ids=new HashSet<UUID>();first.items().forEach(m->assertTrue(ids.add(m.id())));second.items().forEach(m->assertTrue(ids.add(m.id())));assertEquals(27,ids.size());assertEquals(400,assertThrows(ResponseStatusException.class,()->investigations.list(maker.user().getId(),maker.sid(),receipt.id(),0L)).getStatusCode().value());}
    @Test void unsupportedMethodsAndExtraPathsRemainDenied()throws Exception{for(var request:List.of(get(path()+"/inspect"),put(path()+"/inspect"),post(path()+"/inspect/extra")))mvc.perform(request.header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());assertEquals(0,count("deposit_monitoring_source_views"));}
    @Test void surroundingTransactionIsRejectedWithoutPartialAccess(){assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->inspect()));assertEquals(0,count("deposit_monitoring_source_views"));}
    @Test void noSignalInputIsInspectableButCannotBeMadeAnUnresolvedInvestigation()throws Exception{
        when(price.read()).thenAnswer(call->{Instant now=Instant.ofEpochMilli(Instant.now().toEpochMilli());String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",1700000,"trade_timestamp",now.toEpochMilli(),"timestamp",now.toEpochMilli())));return new BitcoinKrwPriceClient.Quote(new BigDecimal("1700000"),now,now,now,now,payload,sha(payload));});
        assertEquals("SUCCESS",prices.refresh().status());clearInvocations(price);
        var owner=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var r=deposit(owner.getId());var a=monitoring.processNext().orElseThrow();assertEquals("NO_SIGNAL",a.result().outcome());
        var view=investigations.inspect(maker.user().getId(),maker.sid(),r.id(),a.id());assertEquals(0,new BigDecimal("209876.526").compareTo(new BigDecimal(view.inputs().getFirst().krwValue())));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),r.id(),a.id(),request(view.viewId(),UUID.randomUUID(),"신호 없는 자료"))).getStatusCode().value());assertEquals(0,count("deposit_monitoring_observations"));
    }
    @Test void newerInputDoesNotRelabelHistoricalEvidenceOrOpinion(){
        var old=record();deposit(customer.getId());var next=monitoring.processNext().orElseThrow();assertNotEquals(assessment,next.id());var view=inspect();assertEquals(1,view.inputs().size());var text=investigations.inspectObservation(checker.user().getId(),checker.sid(),receipt.id(),assessment,old.id());assertEquals("추가 송금 근거를 확인해야 한다",text.notes());assertEquals(old.id(),text.observation().id());
    }
    @Test void changedOperatorVersionCannotReuseEarlierSourceView(){
        var view=inspect();var operator=users.findById(maker.user().getId()).orElseThrow();operator.setOperatorAccessExpiresAt(Instant.now().plusSeconds(7200));users.saveAndFlush(operator);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"운영 버전 변경"))).getStatusCode().value());assertEquals(0,count("deposit_monitoring_observations"));
    }

    @Test void idempotentKeyCannotBorrowObservationThroughAnotherCustomerReceipt(){
        var view=inspect();var request=request(view.viewId(),UUID.randomUUID(),"대상 결합 의견");var original=investigations.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request);
        var another=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var foreign=deposit(another.getId());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->investigations.record(maker.user().getId(),maker.sid(),foreign.id(),assessment,request)).getStatusCode().value());assertEquals(original.id(),investigations.observations(maker.user().getId(),maker.sid(),receipt.id(),assessment,0).getFirst().id());assertEquals(1,count("deposit_monitoring_observations"));
    }
    @Test void missingEvidenceKeyCannotWriteAnUnprotectedNote(){var view=inspect();var unconfigured=new EvidenceCrypto("","","","");var target=service(jdbc,declarationCrypto,unconfigured);assertEquals(503,assertThrows(ResponseStatusException.class,()->target.record(maker.user().getId(),maker.sid(),receipt.id(),assessment,request(view.viewId(),UUID.randomUUID(),"암호화 설정 확인"))).getStatusCode().value());assertEquals(0,count("deposit_monitoring_observations"));assertEquals(1,count("deposit_monitoring_source_views"));}

    void auditConstraint(Runnable work){Throwable failure=assertThrows(RuntimeException.class,work::run);while(failure.getCause()!=null)failure=failure.getCause();assertTrue(failure.getMessage().contains("거래 조사 업무에는 해당 운영자의 필수 감사가 필요합니다"),failure.toString());}
    @Test void databaseRejectsForgedActorAndBorrowedSourceView(){
        var note=record();var checked=investigations.inspect(checker.user().getId(),checker.sid(),receipt.id(),assessment);
        var wrongActor=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO deposit_monitoring_source_views(id,assessment_id,actor_id,actor_person,actor_version,session_id,source_hash) SELECT ?,assessment_id,actor_id,?,actor_version,session_id,source_hash FROM deposit_monitoring_source_views WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),note.viewId()));
        assertTrue(wrongActor.getMostSpecificCause().getMessage().contains("현재 운영자·세션·원본이 다릅니다"));
        var wrongView=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO deposit_monitoring_observations(id,assessment_id,view_id,actor_id,actor_person,actor_version,session_id,request_key,finding,source_hash,notes_mac,fingerprint_key_id,evidence_key_id,notes_cipher) SELECT ?,assessment_id,?,actor_id,actor_person,actor_version,session_id,?,finding,source_hash,notes_mac,fingerprint_key_id,evidence_key_id,notes_cipher FROM deposit_monitoring_observations WHERE id=?",UUID.randomUUID(),checked.viewId(),UUID.randomUUID(),note.id()));
        assertTrue(wrongView.getMostSpecificCause().getMessage().contains("실제 열람·담당자·탐지 근거가 다릅니다"));assertEquals(1,count("deposit_monitoring_observations"));
    }

}
