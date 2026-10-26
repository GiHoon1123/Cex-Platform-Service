package dustin.cex.domains.bitcoin;

import static dustin.cex.domains.ledger.LedgerService.p;

import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.CustomerWithdrawalAuthorization;
import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 고객이 서명한 정확한 출금 견적의 총액만 플랫폼 원장/잔액에 예약한다. 엔진 전송은 커밋 뒤에 시작한다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalRequests {
    public record Receipt(UUID withdrawalId, String state, String network, String address,
                          String amount, String fee, String total, String policy, String quoteHash) {
        @Override public String toString() { return "BitcoinWithdrawalReceipt[지급지와 금액 생략]"; }
    }

    private final JdbcTemplate jdbc;
    private final BitcoinWithdrawalQuotes quotes;
    private final CustomerWithdrawalAuthorization authorization;
    private final UserBalanceRepository balances;
    private final LedgerService ledger;
    private final AuditService audit;
    private final BitcoinWithdrawalDispatches dispatch;
    private final BitcoinWithdrawalValuations valuations;
    private final TransactionTemplate tx;

    public BitcoinWithdrawalRequests(JdbcTemplate jdbc, BitcoinWithdrawalQuotes quotes,
            CustomerWithdrawalAuthorization authorization, UserBalanceRepository balances,
            LedgerService ledger, AuditService audit, BitcoinWithdrawalDispatches dispatch,
            BitcoinWithdrawalValuations valuations,PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.quotes = quotes;
        this.authorization = authorization;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
        this.dispatch = dispatch;
        this.valuations = valuations;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public CustomerWithdrawalAuthorization.Options options(long actor, UUID sid, UUID quoteId) {
        independent();
        try { return tx.execute(t -> authorization.prepare(actor, sid, quoteId)); }
        catch (ResponseStatusException denied) {
            if (denied.getStatusCode().is4xxClientError()) audit.denied(actor, "BTC_WITHDRAWAL_OPTIONS",
                "bitcoin-withdrawal:" + quoteId, "POLICY_" + denied.getStatusCode().value());
            throw denied;
        }
    }

    public Receipt submit(long actor, UUID sid, UUID quoteId, CustomerWithdrawalAuthorization.Proof proof) {
        independent();
        try {
            tx.executeWithoutResult(t -> reserve(actor, sid, quoteId, proof));
            // 같은 UUID의 재시도와 응답 유실은 보존한 금융 원본을 다시 확인한다. HTTP 성공으로 반환을 확정하지 않는다.
            dispatch.execute(quoteId);
            return status(actor, sid, quoteId);
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().is4xxClientError()) audit.denied(actor, "BTC_WITHDRAWAL_REQUEST",
                "bitcoin-withdrawal:" + quoteId, "POLICY_" + denied.getStatusCode().value());
            throw denied;
        }
    }

    public Receipt status(long actor, UUID sid, UUID quoteId) {
        independent();
        return tx.execute(t -> {
            var quote = quotes.historicalForWithdrawal(actor, sid, quoteId);
            var originals = jdbc.queryForList("SELECT id FROM bitcoin_withdrawals WHERE id=? AND user_id=?",
                quoteId, actor);
            if (originals.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "출금 원본이 없습니다");
            var events = jdbc.queryForList("SELECT event_type FROM bitcoin_withdrawal_reservation_events WHERE withdrawal_id=?", String.class, quoteId);
            String state = events.isEmpty() ? "PENDING_ENGINE" :
                events.getFirst().equals("withdrawal_reserved") ? "RESERVED" : "REJECTED";
            if(!state.equals("REJECTED")&&Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations WHERE withdrawal_id=?)",Boolean.class,quoteId)))
                state=Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events WHERE withdrawal_id=?)",Boolean.class,quoteId))
                    ?"CANCELLED":"CANCELLATION_PENDING";
            // 내부 심사 의견은 노출하지 않는다. 실행 확정은 체인 완료와 구분하고 고객 취소 가능 여부만 정확히 알린다.
            if(state.equals("RESERVED")&&Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=?)",Boolean.class,quoteId)))state="PAYOUT_COMMITTED";
            audit.record(actor, "BTC_WITHDRAWAL_READ", "bitcoin-withdrawal:" + quoteId, "SUCCESS", state);
            var p = quote.payload();
            return new Receipt(quoteId, state, p.network(), p.address(), p.amount(), p.fee(),
                p.total(), p.policy(), quote.payloadHash());
        });
    }

    private void reserve(long actor, UUID sid, UUID quoteId, CustomerWithdrawalAuthorization.Proof proof) {
        // 본인 세션을 잠근 뒤 같은 원본이 이미 있으면 서명을 다시 사용하지 않는다. 조회에도 현재 세션 검사는 남는다.
        quotes.historicalForWithdrawal(actor, sid, quoteId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawals WHERE id=? AND user_id=? AND session_id=?)",
                Boolean.class, quoteId, actor, sid))) {
            return;
        }
        var quote = quotes.requireForWithdrawal(actor, sid, quoteId);
        BigDecimal gross = ExactAmount.requirePositive(new BigDecimal(quote.payload().total()), "출금 총 차감액");
        var balance = balances.findByUserIdAndMintAddressForUpdate(actor, "BTC")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "사용 가능 BTC 잔액을 확인하세요"));
        BigDecimal available = ExactAmount.requireNonNegative(balance.getAvailable(), "사용 가능 BTC 잔액");
        BigDecimal locked = ExactAmount.requireNonNegative(balance.getLocked(), "잠긴 BTC 잔액");
        if (available.compareTo(gross) < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "사용 가능 BTC 잔액을 확인하세요");
        // 신청 전에 이미 관찰된 서버 가격만 고정한다. 가격 부재를 나중의 심사 시세로 보충하지 않는다.
        var valuation=valuations.capture();
        // 부족한 자산에서 서명 카운터를 소비하지 않는다. 자산 확인 뒤 모든 변화는 같은 TX에서만 확정한다.
        authorization.authorize(actor, sid, quoteId, proof);
        balance.setAvailable(ExactAmount.requireNonNegative(available.subtract(gross), "예약 후 가용 BTC"));
        balance.setLocked(ExactAmount.requireNonNegative(locked.add(gross), "예약 후 잠긴 BTC"));
        balances.saveAndFlush(balance);
        String operation = "withdrawal-reserve:" + quoteId;
        ledger.record(operation, "BTC_WITHDRAWAL_RESERVE", List.of(
            p(actor, "AVAILABLE", "BTC", gross.negate()), p(actor, "LOCKED", "BTC", gross)));
        var source = quote.payload();
        jdbc.update("""
            INSERT INTO bitcoin_withdrawals(id,user_id,session_id,network,asset,amount,fee,total,quote_hash,operation_key)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """, quoteId, actor, sid, source.network(), "BTC", new BigDecimal(source.amount()),
            new BigDecimal(source.fee()), gross, quote.payloadHash(), operation);
        valuations.record(quoteId,actor,valuation);
        audit.record(actor, "BTC_WITHDRAWAL_REQUESTED", "bitcoin-withdrawal:" + quoteId,
            "SUCCESS", quote.payloadHash());
    }

    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("고객 출금 요청은 독립 금융 트랜잭션을 사용하세요");
    }
}
