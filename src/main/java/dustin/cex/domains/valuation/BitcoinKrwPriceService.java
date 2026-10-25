package dustin.cex.domains.valuation;

import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 원화 참고가격의 조회/원본·실패를 영속 보존한다. 수신가·법정 기준액·금융 허가는 호출 업무가 별도로 판단한다. */
@Service
@DependsOn("bitcoinKrwPriceSchemaInitializer")
public class BitcoinKrwPriceService {
    public record Report(long attemptId,String status,String failureCode){}
    // 가격·체결 시각·공급사 스냅샷 시각·로컬 읽기/저장 시각을 섞지 않는다. 이 값 묶음 자체가 특정 입금의 환산 결과는 아니다.
    public record Snapshot(long attemptId,BigDecimal price,Instant tradeAt,Instant providerAt,Instant readStartedAt,Instant recordedAt,String payloadHash){}
    private record Start(long id,Instant at){}
    private final boolean enabled;
    private final BitcoinKrwPriceClient client;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinKrwPriceService(@Value("${valuation.upbit.enabled:false}") boolean enabled,BitcoinKrwPriceClient client,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.enabled=enabled;this.client=client;this.jdbc=jdbc;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Report refresh(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("참고가격 갱신은 DB 트랜잭션 밖에서 시작하세요");
        requireEnabled();Start started=tx.execute(t->begin());BitcoinKrwPriceClient.Quote quote=null;String failure=null;
        try{
            quote=client.read();Instant now=Instant.now();
            // 공급사 호출은 앞선 시작 TX가 끝난 뒤다. 타입 대역/내부 오류도 현재 성공으로 저장하지 않도록 시각·금액·원문 지문을 다시 확인한다.
            if(quote==null||quote.price()==null||quote.price().signum()<=0||quote.price().scale()>8||quote.price().precision()-(long)quote.price().scale()>30
                ||quote.readStartedAt()==null||quote.readFinishedAt()==null||quote.tradeAt()==null||quote.providerAt()==null||quote.payload()==null||quote.payloadHash()==null
                ||quote.readStartedAt().isBefore(started.at().minusSeconds(10))||quote.readFinishedAt().isBefore(quote.readStartedAt())||quote.readFinishedAt().isAfter(now.plusSeconds(10))
                ||quote.tradeAt().isAfter(quote.providerAt())||quote.providerAt().isAfter(quote.readFinishedAt().plusSeconds(10))||quote.tradeAt().isBefore(quote.readStartedAt().minusSeconds(120))
                ||quote.payload().getBytes(StandardCharsets.UTF_8).length>65536||!hash(quote.payload()).equals(quote.payloadHash()))failure="INVALID_SOURCE";
        }catch(ResponseStatusException unavailable){failure="PROVIDER_UNAVAILABLE";}
        catch(RuntimeException unexpected){failure="INTERNAL_FAILURE";}
        var source=quote;String code=failure;
        // 완료 저장/감사 실패는 공급사 실패로 다시 저장하지 않는다. 미완료 현재 위치를 과거 가격으로 숨기지 않는다.
        return tx.execute(t->{
            boolean ok=code==null;
            jdbc.update("""
                INSERT INTO btc_krw_price_results(attempt_id,status,failure_code,price,trade_at,provider_at,read_started_at,read_finished_at,payload,payload_hash)
                 VALUES(?,?,?,?,?,?,?,?,?,?)
                """,started.id(),ok?"SUCCESS":"FAILED",code,ok?source.price():null,ok?Timestamp.from(source.tradeAt()):null,ok?Timestamp.from(source.providerAt()):null,
                ok?Timestamp.from(source.readStartedAt()):null,ok?Timestamp.from(source.readFinishedAt()):null,ok?source.payload():null,ok?source.payloadHash():null);
            audit.record(null,"BTC_KRW_PRICE_FINISHED","btc-krw-reference",ok?"SUCCESS":"DENIED","ATTEMPT:"+started.id()+":STATUS:"+(ok?"SUCCESS":"FAILED")+":CODE:"+code);
            return new Report(started.id(),ok?"SUCCESS":"FAILED",code);
        });
    }
    private Start begin(){
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('btc-krw-reference-v1',0))",row->{});
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM btc_krw_price_heads h JOIN btc_krw_price_attempts a ON a.id=h.attempt_id LEFT JOIN btc_krw_price_results r ON r.attempt_id=a.id WHERE r.attempt_id IS NULL AND a.started_at>clock_timestamp()-interval '2 minutes')",Boolean.class)))throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 진행 중인 참고가격 조회가 있습니다");
        var row=jdbc.queryForMap("INSERT INTO btc_krw_price_attempts DEFAULT VALUES RETURNING id,started_at");long id=((Number)row.get("id")).longValue();
        // 완료 때만 현재 위치를 옮기면 새 실패를 과거 성공으로 숨긴다. 시작 원본과 현재 위치를 같은 TX에서 먼저 확정한다.
        jdbc.update("INSERT INTO btc_krw_price_heads(singleton,attempt_id) VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET attempt_id=excluded.attempt_id",id);
        audit.record(null,"BTC_KRW_PRICE_STARTED","btc-krw-reference","SUCCESS","ATTEMPT:"+id);
        return new Start(id,((Timestamp)row.get("started_at")).toInstant());
    }
    /** 호출자 TX가 있으면 그 TX가 끝날 때까지 현재 위치를 고정한다. 기존에 특정 거래에 결합한 과거 가격은 별도 역사적 사실이다. */
    public Snapshot current(){
        requireEnabled();var snapshot=tx.execute(t->{
            var heads=jdbc.queryForList("SELECT attempt_id FROM btc_krw_price_heads WHERE singleton=true FOR SHARE",Long.class);if(heads.size()!=1)return null;
            var row=jdbc.queryForMap("""
                SELECT a.id,r.*,a.started_at<=clock_timestamp() AND a.started_at>=clock_timestamp()-interval '2 minutes'
                 AND r.trade_at>=clock_timestamp()-interval '2 minutes' AND r.provider_at<=clock_timestamp()+interval '10 seconds'
                 AND r.recorded_at<=clock_timestamp() fresh FROM btc_krw_price_attempts a LEFT JOIN btc_krw_price_results r ON r.attempt_id=a.id WHERE a.id=?
                """,heads.getFirst());
            if(!"SUCCESS".equals(row.get("status"))||!Boolean.TRUE.equals(row.get("fresh"))||!hash((String)row.get("payload")).equals(row.get("payload_hash")))return null;
            return new Snapshot(heads.getFirst(),(BigDecimal)row.get("price"),((Timestamp)row.get("trade_at")).toInstant(),((Timestamp)row.get("provider_at")).toInstant(),((Timestamp)row.get("read_started_at")).toInstant(),((Timestamp)row.get("recorded_at")).toInstant(),(String)row.get("payload_hash"));
        });
        // 가격 부재는 DB 오류가 아니다. 조회가 참여한 바깥 TX를 롤백 전용으로 오염시키지 않아 실제 수탁 자산은 별도로 기록할 수 있게 한다.
        // 고객 자금 사용은 호출 업무가 보류한다. SQL/무결성 제약 자체의 오류는 위 TX에서 그대로 실패시킨다.
        if(snapshot==null)throw BitcoinKrwPriceClient.unavailable();return snapshot;
    }
    private void requireEnabled(){if(!enabled)throw BitcoinKrwPriceClient.unavailable();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException failed){throw new IllegalStateException("참고가격 지문을 계산할 수 없습니다",failed);}}
}
