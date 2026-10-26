package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.valuation.BitcoinKrwPriceService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 고객 출금 요청 시점의 지급액 참고평가를 원본과 같은 커밋에 보존한다. 나중의 현재가로 소급 계산하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
@Transactional(propagation=Propagation.MANDATORY)
public class BitcoinWithdrawalValuations {
    public static final String POLICY="BTC_WITHDRAWAL_REQUEST_KRW_REFERENCE_V1";
    private final BitcoinKrwPriceService prices;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public BitcoinWithdrawalValuations(BitcoinKrwPriceService prices,JdbcTemplate jdbc,AuditService audit){this.prices=prices;this.jdbc=jdbc;this.audit=audit;}

    /** 현재 가격 머리를 같은 금융 TX가 끝날 때까지 고정한다. 외부 가격 조회/갱신은 이미 끝나 있어야 한다. */
    public BitcoinKrwPriceService.Snapshot capture(){
        var source=prices.current();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::timestamptz<=clock_timestamp()",Boolean.class,
                java.sql.Timestamp.from(source.providerAt()))))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"신청 시점 이전의 BTC 원화 참고가격을 확인하세요");
        return source;
    }

    public void record(UUID id,long owner,BitcoinKrwPriceService.Snapshot source) {
        // 원본의 실제 생성 시각과 순 지급액을 읽는다. API 입력의 환산 금액·수수료 포함 총액을 신뢰하지 않는다.
        var original=jdbc.queryForMap("SELECT amount,created_at FROM bitcoin_withdrawals WHERE id=? AND user_id=?",id,owner);
        BigDecimal amount=(BigDecimal)original.get("amount");
        BigDecimal value=amount.multiply(source.price());
        jdbc.update("""
            INSERT INTO bitcoin_withdrawal_valuations(withdrawal_id,user_id,policy,reference_at,price_attempt_id,
             source_hash,amount,price,krw_value) VALUES(?,?,?,?,?,?,?,?,?)
            """,id,owner,POLICY,original.get("created_at"),source.attemptId(),source.payloadHash(),amount,source.price(),value);
        audit.record(owner,"BTC_WITHDRAWAL_VALUED","bitcoin-withdrawal:"+id,"SUCCESS",
            "PRICE:"+source.attemptId()+":HASH:"+source.payloadHash());
    }
}
