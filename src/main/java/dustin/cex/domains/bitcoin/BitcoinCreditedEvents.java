package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.model.ExactAmount;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static dustin.cex.domains.ledger.LedgerService.p;

/** 같은 Kafka 수신 TX에서 이미 권한을 확정한 입금 결과만 원장/잔액/감사에 반영한다. */
@Service
@DependsOn({"bitcoinCreditSchemaInitializer","ethereumSchemaInitializer"})
public class BitcoinCreditedEvents {
    private static final Set<String> FIELDS=Set.of("event_id","engine_id","event_type","deposit_id","user_id","asset","amount","timestamp");
    private final JdbcTemplate jdbc;private final UserRepository users;private final UserBalanceRepository balances;private final LedgerService ledger;private final AuditService audit;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256).maxNumberLength(20).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public BitcoinCreditedEvents(JdbcTemplate jdbc,UserRepository users,UserBalanceRepository balances,LedgerService ledger,AuditService audit){this.jdbc=jdbc;this.users=users;this.balances=balances;this.ledger=ledger;this.audit=audit;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(String message){
        var event=json.readTree(message);
        // 엔진은 금액을 문자열로 직렬화한다. 숫자형/지수형/필드 중복을 관대하게 받아 계약 변화를 숨기지 않는다.
        if(event==null||!event.isObject()||!event.propertyNames().equals(FIELDS)||!"deposit_credited".equals(text(event,"event_type"))
            ||!event.path("user_id").isIntegralNumber()||!event.path("user_id").canConvertToLong()||event.path("user_id").longValue()<=0)throw new IllegalArgumentException("입금 확정 이벤트 형식이 다릅니다");
        UUID receipt=canonical(text(event,"deposit_id")),engine=canonical(text(event,"engine_id"));String eventId=text(event,"event_id"),asset=text(event,"asset"),amountText=text(event,"amount");
        int scale="BTC".equals(asset)?8:"ETH".equals(asset)?18:-1;
        if(!eventId.matches(java.util.regex.Pattern.quote(engine.toString())+":[1-9][0-9]*:0")||scale<0||amountText.length()>50||!amountText.matches("[0-9]+(?:\\.[0-9]{1,"+scale+"})?"))throw new IllegalArgumentException("입금 확정 원본 형식이 다릅니다");
        BigDecimal amount=ExactAmount.requirePositive(new BigDecimal(amountText),"입금 확정 금액");String time=text(event,"timestamp");Instant.parse(time);
        String authorization="BTC".equals(asset)?"SELECT user_id,asset,amount FROM bitcoin_credit_authorizations WHERE receipt_id=?":"SELECT user_id,asset,amount FROM ethereum_credit_authorizations WHERE deposit_id=?";
        var originals=jdbc.queryForList(authorization,receipt);if(originals.size()!=1)throw new IllegalStateException("입금의 확정 실행 권한이 없습니다");
        var original=originals.getFirst();long owner=((Number)original.get("user_id")).longValue();
        users.lockById(owner).orElseThrow(()->new IllegalStateException("입금 소유자가 없습니다"));
        if(owner!=event.path("user_id").longValue()||!asset.equals(original.get("asset"))||amount.compareTo((BigDecimal)original.get("amount"))!=0)throw new IllegalStateException("엔진 결과와 입금 경제적 원본이 다릅니다");
        String credited="BTC".equals(asset)?"SELECT EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=?)":"SELECT EXISTS(SELECT 1 FROM ethereum_credited_deposits WHERE deposit_id=?)";
        if(Boolean.TRUE.equals(jdbc.queryForObject(credited,Boolean.class,receipt)))throw new IllegalStateException("같은 입금에 다른 확정 이벤트가 연결됐습니다");
        // 기존 예약 잔액과 다른 자산을 보존하고 이 입금만 더한다. 현재 거래 제한은 이미 발생한 금융 결과를 지우는 근거가 아니다.
        var balance=balances.findByUserIdAndMintAddressForUpdate(owner,asset).orElseGet(()->UserBalance.builder().userId(owner).mintAddress(asset).build());
        balance.setAvailable(ExactAmount.requireNonNegative(balance.getAvailable().add(amount),"반영 후 사용 가능 잔액"));balances.saveAndFlush(balance);
        String operation="engine:"+eventId;ledger.record(operation,asset+"_DEPOSIT_CREDIT",List.of(p(owner,"PENDING",asset,amount.negate()),p(owner,"AVAILABLE",asset,amount)));
        // 엔진 시각의 나노초를 DB timestamp의 마이크로초로 반올림하지 않는다. 원래 문자열을 보존해 독립 대사도 같은 바이트 의미를 확인한다.
        if("BTC".equals(asset))jdbc.update("INSERT INTO bitcoin_credited_deposits(receipt_id,event_id,engine_id,event_time,payload_hash,operation_key) VALUES(?,?,?,?,?,?)",receipt,eventId,engine,time,BitcoinCreditBasis.digest(message),operation);
        else jdbc.update("INSERT INTO ethereum_credited_deposits(deposit_id,event_id,engine_id,event_time,payload_hash,operation_key) VALUES(?,?,?,?,?,?)",receipt,eventId,engine,time,BitcoinCreditBasis.digest(message),operation);
        audit.record(null,asset+"_DEPOSIT_CREDITED",asset.toLowerCase()+"-deposit:"+receipt,"SUCCESS","EVENT:"+eventId);
    }
    private static String text(JsonNode node,String field){var value=node.path(field);if(!value.isTextual())throw new IllegalArgumentException("입금 이벤트의 문자열 필드를 확인하세요");return value.textValue();}
    private static UUID canonical(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value)||id.equals(new UUID(0,0)))throw new IllegalArgumentException("입금 이벤트의 안정 식별자를 확인하세요");return id;}
}
