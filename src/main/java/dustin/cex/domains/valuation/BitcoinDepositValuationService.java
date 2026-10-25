package dustin.cex.domains.valuation;

import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 수탁 보류 인식과 당시 참고가격을 같은 TX에 결합한다. 평가 기록은 법정 면제/정보 제공·자금 사용 승인이 아니다. */
@Service
@DependsOn("bitcoinKrwPriceSchemaInitializer")
public class BitcoinDepositValuationService {
    private static final String POLICY="BTC_PENDING_KRW_REFERENCE_V1";
    private final BitcoinKrwPriceService prices;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public BitcoinDepositValuationService(BitcoinKrwPriceService prices,JdbcTemplate jdbc,AuditService audit){this.prices=prices;this.jdbc=jdbc;this.audit=audit;}
    /** 보류 인식 전에 현재 위치를 고정한다. 외부 HTTP 없이 DB만 읽으며 잠금은 호출자의 자산 기록 TX가 끝날 때까지 유지한다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Long captureCurrentPrice(){
        try{return prices.current().attemptId();}
        catch(ResponseStatusException unavailable){
            // 정상적인 가격 부재는 자금 사용을 보류할 이유다. 실제 들어온 자산의 수량 기록을 생략하거나 가격0으로 채우지 않는다.
            if(unavailable.getStatusCode().value()!=503)throw unavailable;return null;
        }
    }
    /** 실제 보류 원본의 DB 시각을 평가 기준으로 쓴다. 나중에 확보한 새 가격으로 원래 입금을 다시 평가하지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordRecognized(UUID receipt,Long priceId){
        var deposit=jdbc.queryForMap("SELECT p.recorded_at,r.amount FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE p.receipt_id=?",receipt);
        Instant reference=((Timestamp)deposit.get("recorded_at")).toInstant();String status="UNVALUED",reason="PRICE_UNAVAILABLE";BigDecimal value=null;
        if(priceId!=null){
            var rows=jdbc.queryForList("""
                SELECT a.started_at,r.price,r.trade_at,r.provider_at,r.recorded_at FROM btc_krw_price_results r
                 JOIN btc_krw_price_attempts a ON a.id=r.attempt_id JOIN btc_krw_price_heads h ON h.attempt_id=a.id
                 WHERE r.attempt_id=? AND r.status='SUCCESS' FOR SHARE OF h
                """,priceId);
            if(rows.size()!=1)throw new IllegalStateException("보류 인식 전에 확인한 현재 가격 원본이 달라졌습니다");var source=rows.getFirst();
            Instant start=((Timestamp)source.get("started_at")).toInstant(),stored=((Timestamp)source.get("recorded_at")).toInstant(),trade=((Timestamp)source.get("trade_at")).toInstant(),provider=((Timestamp)source.get("provider_at")).toInstant();
            // 조회 때는 신선했어도 인식 시점에 허용 창을 벗어날 수 있다. 관측한 원본 참조는 보존하되 환산액은 만들지 않는다.
            boolean usable=!start.isAfter(reference)&&!stored.isAfter(reference)&&!start.isBefore(reference.minusSeconds(120))
                &&!trade.isBefore(reference.minusSeconds(120))&&!provider.isAfter(reference.plusSeconds(10));
            reason="PRICE_NOT_USABLE_AT_REFERENCE";
            if(usable){status="VALUED";reason="PRICE_AVAILABLE";value=((BigDecimal)deposit.get("amount")).multiply((BigDecimal)source.get("price"));}
        }
        jdbc.update("INSERT INTO bitcoin_deposit_valuations(receipt_id,reference_at,policy_id,status,reason,price_attempt_id,krw_value) VALUES(?,?,?,?,?,?,?)",
            receipt,Timestamp.from(reference),POLICY,status,reason,priceId,value);
        audit.record(null,"BTC_DEPOSIT_VALUATION_CAPTURED","bitcoin-deposit:"+receipt,"SUCCESS","STATUS:"+status+":PRICE:"+priceId+":REASON:"+reason);
    }
}
