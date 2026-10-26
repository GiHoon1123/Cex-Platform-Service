package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 현재 고객의 출금 견적 원본만 확정한다. 노드는 잠금 밖에서 읽고 견적 확정으로 자산을 예약하거나 지급하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinWithdrawalQuotes {
    public static final String ASSURANCE="QUOTE_ONLY_FUNDS_NOT_RESERVED";
    // 원본의 고객 번호는 자바스크립트 정수 범위 손실을 피하도록 문자열로 공개한다. 해시는 서명이나 지급 권한이 아니다.
    public record Payload(UUID id,String customerId,long customerVersion,String network,String asset,String address,String scriptPubKey,String scriptType,
        String amount,String feeRate,String fee,String total,String policy,Instant createdAt,Instant expiresAt){@Override public String toString(){return "BitcoinWithdrawalQuotePayload[원본 생략]";}}
    public record Quote(Payload payload,String payloadHash,String state,String assurance){}
    private record Start(long version,Quote existing){}
    private final JdbcTemplate jdbc;private final CustomerSessionPolicy sessions;private final CustomerFinancialEligibility eligibility;
    private final BitcoinDestinationClient destinations;private final AuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
    public BitcoinWithdrawalQuotes(JdbcTemplate jdbc,CustomerSessionPolicy sessions,CustomerFinancialEligibility eligibility,BitcoinDestinationClient destinations,AuditService audit,ObjectMapper json,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.sessions=sessions;this.eligibility=eligibility;this.destinations=destinations;this.audit=audit;this.json=json;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Quote prepare(long actor,UUID sid,UUID key,String address,String amount){
        independent();
        try{
            if(sid==null)throw denied(HttpStatus.FORBIDDEN);
            if(key==null||key.equals(new UUID(0,0)))throw denied(HttpStatus.BAD_REQUEST);
            final BitcoinWithdrawalAmounts.Amounts money;
            try{money=BitcoinWithdrawalAmounts.quote(amount);}catch(IllegalArgumentException invalid){throw denied(HttpStatus.BAD_REQUEST);}
            if(address==null||address.isEmpty()||address.length()>100||address.chars().anyMatch(c->c<33||c>126))throw denied(HttpStatus.BAD_REQUEST);
            String inputHash=digest(json.writeValueAsString(List.of("btc-withdrawal-quote-input-v1",actor,sid,address,money.amount().toPlainString())));
            Start start=tx.execute(t->{
                var customer=sessions.require(actor,sid);var old=existing(actor,sid,key,inputHash);if(old!=null)return new Start(customer.getVersion(),old);
                if(!eligibility.permitted(customer))throw denied(HttpStatus.FORBIDDEN);return new Start(customer.getVersion(),null);
            });
            if(start.existing()!=null)return start.existing();
            // 동일 요청의 반복 조회는 위에서 끝난다. 실제 주소 판독 동안 사용자/금융 행 잠금을 보유하지 않는다.
            var destination=destinations.validate(address);
            return tx.execute(t->{
                var customer=sessions.require(actor,sid);var old=existing(actor,sid,key,inputHash);if(old!=null)return old;
                // 노드 판독 사이 계정·고객 확인·위험 상태가 달라졌으면 이전 검사 결과로 새 견적을 만들지 않는다.
                if(customer.getVersion()!=start.version()||!eligibility.permitted(customer))throw denied(HttpStatus.CONFLICT);
                Timestamp at=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class);UUID id=UUID.randomUUID();Instant expires=at.toInstant().plusSeconds(120);
                var payload=new Payload(id,Long.toString(actor),customer.getVersion(),destination.network(),"BTC",destination.address(),destination.scriptPubKey(),destination.scriptType(),
                    money.amount().toPlainString(),BitcoinWithdrawalAmounts.RATE.toPlainString(),money.fee().toPlainString(),money.total().toPlainString(),BitcoinWithdrawalAmounts.POLICY,at.toInstant(),expires);
                String raw=json.writeValueAsString(payload),hash=digest(raw);
                jdbc.update("INSERT INTO bitcoin_withdrawal_quotes(id,user_id,user_version,session_id,request_key,input_hash,network,amount,fee,total,policy,payload,payload_hash,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,actor,customer.getVersion(),sid,key,inputHash,destination.network(),money.amount(),money.fee(),money.total(),BitcoinWithdrawalAmounts.POLICY,raw,hash,at,Timestamp.from(expires));
                audit.record(actor,"BTC_WITHDRAWAL_QUOTED","bitcoin-withdrawal-quote:"+id,"SUCCESS",hash);return read(actor,id);
            });
        }catch(ResponseStatusException failure){
            if(failure.getStatusCode().is4xxClientError())audit.denied(actor,"BTC_WITHDRAWAL_QUOTE_REQUEST","customer:"+actor,"POLICY_"+failure.getStatusCode().value());
            else audit.record(actor,"BTC_WITHDRAWAL_QUOTE_FAILED","customer:"+actor,"FAILURE","SOURCE_UNAVAILABLE");
            throw failure;
        }
    }
    public Quote own(long actor,UUID sid,UUID id){
        independent();
        try{return tx.execute(t->{sessions.require(actor,sid);if(id==null)throw denied(HttpStatus.BAD_REQUEST);var quote=read(actor,id);audit.record(actor,"BTC_WITHDRAWAL_QUOTE_READ","bitcoin-withdrawal-quote:"+id,"SUCCESS",quote.payloadHash());return quote;});}
        catch(ResponseStatusException failure){audit.denied(actor,"BTC_WITHDRAWAL_QUOTE_QUERY","restricted","POLICY_"+failure.getStatusCode().value());throw failure;}
    }
    /** 이미 확정된 요청의 조회에는 과거의 KYC 상태를 새 금융 권한으로 재해석하지 않는다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Quote historicalForWithdrawal(long actor,UUID sid,UUID id){
        sessions.require(actor,sid);
        if(id==null)throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT session_id FROM bitcoin_withdrawal_quotes WHERE id=? AND user_id=?",UUID.class,id,actor);
        // 과거 요청의 조회·취소는 갱신한 본인 세션에서도 허용한다. 신규 예약의 원래 세션 검사는 아래 별도 경계다.
        if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND);
        return read(actor,id);
    }
    /** 출금 승인/예약의 상위 트랜잭션에서 현재 원본과 자격을 다시 검사한다. 과거 조회의 허용 범위와 구분한다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Quote requireForWithdrawal(long actor,UUID sid,UUID id){
        var customer=sessions.require(actor,sid);
        if(id==null)throw denied(HttpStatus.BAD_REQUEST);
        var quote=read(actor,id);
        var bound=jdbc.queryForObject("SELECT session_id FROM bitcoin_withdrawal_quotes WHERE id=? AND user_id=?",UUID.class,id,actor);
        if(!sid.equals(bound)||!"PRICED".equals(quote.state())||customer.getVersion()!=quote.payload().customerVersion()
            ||!eligibility.permitted(customer))throw denied(HttpStatus.CONFLICT);
        return quote;
    }
    // 같은 키는 최초 원본의 재조회다. 만료됐어도 연장하지 않으며 다른 세션이나 변경 입력으로 재사용할 수 없다.
    private Quote existing(long actor,UUID sid,UUID key,String hash){
        var rows=jdbc.queryForList("SELECT id,session_id,input_hash FROM bitcoin_withdrawal_quotes WHERE user_id=? AND request_key=?",actor,key);if(rows.isEmpty())return null;var row=rows.getFirst();
        if(!sid.equals(row.get("session_id"))||!hash.equals(row.get("input_hash")))throw denied(HttpStatus.CONFLICT);
        var quote=read(actor,(UUID)row.get("id"));audit.record(actor,"BTC_WITHDRAWAL_QUOTE_REPLAYED","bitcoin-withdrawal-quote:"+quote.payload().id(),"SUCCESS",quote.payloadHash());return quote;
    }
    // 과거 견적 열람은 현재 신규 거래 자격과 구분한다. 호출부는 현재 본인 세션을 먼저 확인해야 한다.
    private Quote read(long actor,UUID id){
        var rows=jdbc.queryForList("SELECT *,expires_at>clock_timestamp() fresh FROM bitcoin_withdrawal_quotes WHERE user_id=? AND id=?",actor,id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload"),hash=(String)row.get("payload_hash");
        if(!digest(raw).equals(hash))throw new IllegalStateException("출금 견적 원본의 지문이 다릅니다");
        try{
            Payload payload=json.readerFor(Payload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
            var amounts=BitcoinWithdrawalAmounts.quote(payload.amount());
            // 공개 해시를 다시 계산한 것만으로 잘못된 요율/소유 범위를 승인하지 않는다. 원본 열과 금액 정책도 대조한다.
            if(!id.equals(payload.id())||!Long.toString(actor).equals(payload.customerId())||payload.customerVersion()!=((Number)row.get("user_version")).longValue()
                ||!payload.network().equals(row.get("network"))||!"BTC".equals(payload.asset())||!BitcoinWithdrawalAmounts.POLICY.equals(payload.policy())||!payload.policy().equals(row.get("policy"))
                ||!BitcoinWithdrawalAmounts.RATE.toPlainString().equals(payload.feeRate())||!amounts.amount().toPlainString().equals(payload.amount())||!amounts.fee().toPlainString().equals(payload.fee())||!amounts.total().toPlainString().equals(payload.total())
                ||amounts.amount().compareTo((java.math.BigDecimal)row.get("amount"))!=0||amounts.fee().compareTo((java.math.BigDecimal)row.get("fee"))!=0||amounts.total().compareTo((java.math.BigDecimal)row.get("total"))!=0
                ||!payload.createdAt().equals(((Timestamp)row.get("created_at")).toInstant())||!payload.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException();
            return new Quote(payload,hash,Boolean.TRUE.equals(row.get("fresh"))?"PRICED":"EXPIRED",ASSURANCE);
        }catch(RuntimeException corrupted){throw new IllegalStateException("출금 견적 원본의 소유 범위와 금액을 확인하세요");}
    }
    static String digest(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("표준 지문 계산을 확인하세요",impossible);}}
    private static void independent(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("출금 견적은 독립 트랜잭션으로 실행하세요");}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 고객과 정확한 출금 견적 원본을 확인하세요");}
}
