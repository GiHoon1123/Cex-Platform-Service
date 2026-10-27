package dustin.cex.domains.ethereum;

import static dustin.cex.domains.ledger.LedgerService.p;

import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.EthereumWithdrawalAuthorization;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 고객이 서명한 ETH 견적의 총액만 원장과 실행 잔액에 예약하고 커밋 뒤 엔진에 전달한다. */
@Service
public class EthereumWithdrawalRequests {
    public record Receipt(UUID withdrawalId, String state, String network, String address,
                          String amount, String fee, String total, String policy, String quoteHash) {}

    private final JdbcTemplate jdbc;
    private final EthereumWithdrawalQuotes quotes;
    private final EthereumWithdrawalAuthorization authorization;
    private final UserBalanceRepository balances;
    private final LedgerService ledger;
    private final AuditService audit;
    private final EthereumWithdrawalDispatches dispatches;
    private final TransactionTemplate tx;

    public EthereumWithdrawalRequests(JdbcTemplate jdbc, EthereumWithdrawalQuotes quotes,
            EthereumWithdrawalAuthorization authorization, UserBalanceRepository balances,
            LedgerService ledger, AuditService audit, EthereumWithdrawalDispatches dispatches,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.quotes = quotes;
        this.authorization = authorization;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
        this.dispatches = dispatches;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public EthereumWithdrawalAuthorization.Options options(long actor, UUID session, UUID quote) {
        independent();
        return tx.execute(status -> authorization.prepare(actor, session, quote));
    }

    public Receipt submit(long actor, UUID session, UUID quoteId, EthereumWithdrawalAuthorization.Proof proof) {
        independent();
        tx.executeWithoutResult(status -> reserve(actor, session, quoteId, proof));
        dispatches.execute(quoteId);
        return status(actor, session, quoteId);
    }

    public Receipt status(long actor, UUID session, UUID id) {
        independent();
        return tx.execute(status -> {
            var quote = quotes.historical(actor, session, id);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_withdrawals WHERE id=? AND user_id=?)", Boolean.class, id, actor))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var events = jdbc.queryForList("SELECT event_type FROM ethereum_withdrawal_reservation_events WHERE withdrawal_id=?", String.class, id);
            String state = events.isEmpty() ? "PENDING_ENGINE" : events.getFirst().equals("withdrawal_reserved") ? "RESERVED" : "REJECTED";
            var p = quote.payload();
            return new Receipt(id, state, p.network(), p.address(), p.amount(), p.fee(), p.total(), p.policy(), quote.payloadHash());
        });
    }

    private void reserve(long actor, UUID session, UUID id, EthereumWithdrawalAuthorization.Proof proof) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_withdrawals WHERE id=? AND user_id=? AND session_id=?)", Boolean.class, id, actor, session))) return;
        var quote = quotes.require(actor, session, id);
        BigDecimal total = ExactAmount.requirePositive(new BigDecimal(quote.payload().total()), "ETH 출금 총액");
        var balance = balances.findByUserIdAndMintAddressForUpdate(actor, "ETH").orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "사용 가능 ETH 잔액을 확인하세요"));
        if (balance.getAvailable().compareTo(total) < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "사용 가능 ETH 잔액을 확인하세요");
        authorization.authorize(actor, session, id, proof);
        balance.setAvailable(ExactAmount.requireNonNegative(balance.getAvailable().subtract(total), "예약 후 가용 ETH"));
        balance.setLocked(ExactAmount.requireNonNegative(balance.getLocked().add(total), "예약 후 잠긴 ETH"));
        balances.saveAndFlush(balance);
        String operation = "withdrawal-reserve:" + id;
        ledger.record(operation, "ETH_WITHDRAWAL_RESERVE", List.of(p(actor, "AVAILABLE", "ETH", total.negate()), p(actor, "LOCKED", "ETH", total)));
        var p = quote.payload();
        jdbc.update("INSERT INTO ethereum_withdrawals(id,user_id,session_id,network,asset,address,amount,fee,total,quote_hash,operation_key) VALUES(?,?,?,?, 'ETH',?,?,?,?,?,?)",
                id, actor, session, p.network(), p.address(), new BigDecimal(p.amount()), new BigDecimal(p.fee()), total, quote.payloadHash(), operation);
        audit.record(actor, "ETH_WITHDRAWAL_REQUESTED", "ethereum-withdrawal:" + id, "SUCCESS", quote.payloadHash());
    }

    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ETH 출금 요청은 독립 트랜잭션을 사용하세요");
    }
}
