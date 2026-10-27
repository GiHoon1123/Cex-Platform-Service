package dustin.cex.domains.market;

import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.trade.model.entity.Trade;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 확정 금융 결과를 감시 전용 불변 입력으로 복사하되, 결과 트랜잭션과 분리해 성공시키지 않는다. */
@Component
@DependsOn("marketSurveillanceSchemaInitializer")
public class MarketSurveillanceInputs {
    private final JdbcTemplate jdbc;
    public MarketSurveillanceInputs(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void trade(String eventId,Trade trade){
        requireTransaction();
        jdbc.update("""
            INSERT INTO market_activity_events(source_event_id,event_kind,event_at,buyer_id,seller_id,buy_order_id,sell_order_id,base_asset,quote_asset,price,filled_amount)
            VALUES(?,'TRADE',?,?,?,?,?,?,?,?,?)
            """,eventId,Timestamp.valueOf(trade.getCreatedAt()),trade.getBuyerId(),trade.getSellerId(),trade.getBuyOrderId(),trade.getSellOrderId(),trade.getBaseMint(),trade.getQuoteMint(),trade.getPrice(),trade.getAmount());
    }

    public void cancelled(String eventId,Instant eventAt,Order order){
        requireTransaction();
        jdbc.update("""
            INSERT INTO market_activity_events(source_event_id,event_kind,event_at,user_id,order_id,base_asset,quote_asset,side,price,original_amount,filled_amount)
            VALUES(?,'CANCEL',?,?,?,?,?,?,?,?,?)
            """,eventId,Timestamp.from(eventAt),order.getUserId(),order.getId(),order.getBaseMint(),order.getQuoteMint(),order.getOrderType(),order.getPrice(),order.getAmount(),order.getFilledAmount());
    }

    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("시장 감시 입력에는 금융 결과 트랜잭션이 필요합니다");
    }
}
