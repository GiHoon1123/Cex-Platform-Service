package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.valuation.*;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 새 DB에서 수량 원장/인식 시점/가격 원본·미평가의 원자성을 검증한다. 합성 체인/가격 원본은 실제 공급사 증거가 아니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class BitcoinDepositValuationIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");r.add("customer-mail.dispatch-enabled",()->false);r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-custody-valuation-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);r.add("valuation.upbit.enabled",()->true);}
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean DiditClient didit;
    @MockitoBean BitcoinKrwPriceClient client;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.monitoring.DepositMonitoringQueue monitoring;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositValuationService valuations;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired BitcoinSettings bitcoin;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    BitcoinDepositFixtures.Seed receipt;
    BigDecimal sourcePrice=new BigDecimal("123456789.12345678");long tradeAge;
    @BeforeEach void original(){assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM btc_krw_price_attempts",Long.class));var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리 미사용 해시").build());receipt=BitcoinDepositFixtures.seed(jdbc,json,user.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);ledger.initializeOpening();when(client.read()).thenAnswer(call->quote());}
    BitcoinKrwPriceClient.Quote quote(){Instant read=Instant.now(),provider=Instant.ofEpochMilli(read.toEpochMilli()),trade=provider.minusSeconds(tradeAge);String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",sourcePrice,"trade_timestamp",trade.toEpochMilli(),"timestamp",provider.toEpochMilli())));try{return new BitcoinKrwPriceClient.Quote(sourcePrice,trade,provider,read,read,payload,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));}catch(GeneralSecurityException failed){throw new IllegalStateException(failed);}}
    Map<String,Object> value(){return jdbc.queryForMap("SELECT * FROM bitcoin_deposit_valuations WHERE receipt_id=?",receipt.id());}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @AfterEach void noAvailableOrEngineCredit(){verifyNoInteractions(engine,producer);assertEquals(0,count("user_balances"));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",BigDecimal.class).signum());}
    void custody(){assertEquals(0,new BigDecimal("0.12345678").compareTo(jdbc.queryForObject("SELECT delta FROM ledger_entries WHERE owner_id=? AND bucket='PENDING' AND asset='BTC'",BigDecimal.class,receipt.owner())));}
    @Test void validPriceIsCapturedWithExactCustodyValueAndSameTransaction(){long source=prices.refresh().attemptId();pending.recognize(receipt.id());var v=value();assertEquals("VALUED",v.get("status"));assertEquals(source,((Number)v.get("price_attempt_id")).longValue());assertEquals(0,new BigDecimal("15241577.6543209965279684").compareTo((BigDecimal)v.get("krw_value")));assertEquals(v.get("reference_at"),jdbc.queryForObject("SELECT recorded_at FROM bitcoin_pending_deposits WHERE receipt_id=?",java.sql.Timestamp.class,receipt.id()));assertTrue(jdbc.queryForObject("SELECT v.created_txid=l.created_txid FROM bitcoin_deposit_valuations v JOIN bitcoin_pending_deposits p ON p.receipt_id=v.receipt_id JOIN ledger_transactions l ON l.operation_key=p.operation_key WHERE v.receipt_id=?",Boolean.class,receipt.id()));custody();}
    @Test void unavailablePriceDoesNotRollBackActualAssetsOrInventZeroValue(){pending.recognize(receipt.id());assertEquals("UNVALUED",value().get("status"));assertEquals("PRICE_UNAVAILABLE",value().get("reason"));assertNull(value().get("krw_value"));assertNull(value().get("price_attempt_id"));custody();verifyNoInteractions(client);}
    @Test void disabledPriceCannotUseEvenFreshExistingSource(){prices.refresh();var disabled=new BitcoinKrwPriceService(false,client,jdbc,audit,manager);var binder=new BitcoinDepositValuationService(disabled,jdbc,audit);var isolated=new BitcoinPendingDepositService(jdbc,ledger,users,audit,bitcoin,binder,monitoring,manager);clearInvocations(client);isolated.recognize(receipt.id());assertEquals("UNVALUED",value().get("status"));custody();verifyNoInteractions(client);}
    @Test void latestPriceFailureCannotBorrowEarlierSuccess(){prices.refresh();doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 현재 가격 실패")).when(client).read();assertEquals("FAILED",prices.refresh().status());clearInvocations(client);pending.recognize(receipt.id());assertEquals("UNVALUED",value().get("status"));assertNull(value().get("krw_value"));custody();verifyNoInteractions(client);}
    @Test void retryKeepsOriginalPriceInsteadOfRevaluing(){long first=prices.refresh().attemptId();pending.recognize(receipt.id());var before=value();sourcePrice=new BigDecimal("99999999.00000000");assertNotEquals(first,prices.refresh().attemptId());clearInvocations(client);assertFalse(pending.recognize(receipt.id()).created());assertEquals(before,value());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_DEPOSIT_VALUATION_CAPTURED'",Long.class));custody();verifyNoInteractions(client);}
    @Test void laterSourceCannotFillEarlierMissingCapture(){pending.recognize(receipt.id());var before=value();prices.refresh();clearInvocations(client);assertFalse(pending.recognize(receipt.id()).created());assertEquals(before,value());assertNull(value().get("krw_value"));custody();verifyNoInteractions(client);}
    @Test void priceThatAgesDuringRecognitionIsRetainedAsUnusableReference()throws Exception{tradeAge=118;long source=prices.refresh().attemptId();dustin.cex.domains.ledger.LedgerService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);doAnswer(call->{Thread.sleep(3000);return call.callRealMethod();}).when(target).record(anyString(),eq("BTC_DEPOSIT_PENDING"),anyList());pending.recognize(receipt.id());assertEquals("UNVALUED",value().get("status"));assertEquals("PRICE_NOT_USABLE_AT_REFERENCE",value().get("reason"));assertEquals(source,((Number)value().get("price_attempt_id")).longValue());assertNull(value().get("krw_value"));custody();}
    @Test void valuationAuditFailureRollsBackPendingAndPostingsButPreservesReceipt(){prices.refresh();doThrow(new IllegalStateException("격리 평가 감사 실패")).when(audit).record(isNull(),eq("BTC_DEPOSIT_VALUATION_CAPTURED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->pending.recognize(receipt.id()));assertEquals(0,count("bitcoin_pending_deposits"));assertEquals(0,count("bitcoin_deposit_valuations"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE kind='BTC_DEPOSIT_PENDING'",Long.class));assertEquals(1,count("bitcoin_deposit_receipts"));assertEquals(1,count("btc_krw_price_results"));}
    @Test void valuationCannotBeChangedDeletedOrFalsifiedBySql(){long source=prices.refresh().attemptId();pending.recognize(receipt.id());for(String sql:List.of("UPDATE bitcoin_deposit_valuations SET krw_value=0","DELETE FROM bitcoin_deposit_valuations","TRUNCATE bitcoin_deposit_valuations"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));var wrong=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_valuations(receipt_id,reference_at,policy_id,status,reason,price_attempt_id,krw_value) SELECT receipt_id,reference_at,policy_id,'VALUED','PRICE_AVAILABLE',?,1 FROM bitcoin_deposit_valuations WHERE receipt_id=?",source,receipt.id()));assertTrue(wrong.getMostSpecificCause().getMessage().contains("같은 트랜잭션의 보류 인식 시점"));custody();}
    @Test void pendingCannotCommitWithoutRequiredValuationFact(){var omitted=mock(BitcoinDepositValuationService.class);var bypass=new BitcoinPendingDepositService(jdbc,ledger,users,audit,bitcoin,omitted,monitoring,manager);assertThrows(RuntimeException.class,()->bypass.recognize(receipt.id()));assertEquals(0,count("bitcoin_pending_deposits"));assertEquals(0,count("bitcoin_deposit_valuations"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE kind='BTC_DEPOSIT_PENDING'",Long.class));}
    @Test void concurrentRecognitionKeepsOnePriceAndAudit()throws Exception{prices.refresh();clearInvocations(client);var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<BitcoinPendingDepositService.Recognized> action=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return pending.recognize(receipt.id());};var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS).created(),b.get(10,TimeUnit.SECONDS).created());}assertEquals(1,count("bitcoin_deposit_valuations"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_DEPOSIT_VALUATION_CAPTURED'",Long.class));custody();verifyNoInteractions(client);}
    @Test void pricePublisherWaitsForRecognitionAndCannotMoveCapturedSource()throws Exception{long first=prices.refresh().attemptId();clearInvocations(client);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);dustin.cex.domains.ledger.LedgerService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(target).record(anyString(),eq("BTC_DEPOSIT_PENDING"),anyList());try(var pool=Executors.newFixedThreadPool(2)){var recognizing=pool.submit(()->pending.recognize(receipt.id()));assertTrue(entered.await(5,TimeUnit.SECONDS));var publishing=pool.submit(prices::refresh);org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%btc_krw_price_heads%')",Boolean.class));verifyNoInteractions(client);release.countDown();assertTrue(recognizing.get(10,TimeUnit.SECONDS).created());assertNotEquals(first,publishing.get(10,TimeUnit.SECONDS).attemptId());}finally{release.countDown();}assertEquals(first,((Number)value().get("price_attempt_id")).longValue());custody();}
    @Test void valuationHooksRequireExistingBusinessTransaction(){assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,valuations::captureCurrentPrice);assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->valuations.recordRecognized(receipt.id(),null));assertEquals(0,count("bitcoin_pending_deposits"));verifyNoInteractions(client);}
    @Test void wrongConversionCannotBeInsertedEvenInsideOriginalRecognitionTransaction(){long source=prices.refresh().attemptId();var forged=mock(BitcoinDepositValuationService.class);when(forged.captureCurrentPrice()).thenReturn(source);doAnswer(call->{jdbc.update("INSERT INTO bitcoin_deposit_valuations(receipt_id,reference_at,policy_id,status,reason,price_attempt_id,krw_value) SELECT receipt_id,recorded_at,'BTC_PENDING_KRW_REFERENCE_V1','VALUED','PRICE_AVAILABLE',?,1 FROM bitcoin_pending_deposits WHERE receipt_id=?",source,receipt.id());return null;}).when(forged).recordRecognized(any(),eq(source));var bypass=new BitcoinPendingDepositService(jdbc,ledger,users,audit,bitcoin,forged,monitoring,manager);var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->bypass.recognize(receipt.id()));assertTrue(failure.getMostSpecificCause().getMessage().contains("입금 환산액과 당시 가격 원본이 다릅니다"));assertEquals(0,count("bitcoin_pending_deposits"));assertEquals(0,count("bitcoin_deposit_valuations"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE kind='BTC_DEPOSIT_PENDING'",Long.class));}

}
