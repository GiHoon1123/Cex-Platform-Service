package dustin.cex.domains.ethereum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.bitcoin.BitcoinWithdrawalEvents;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** ETH 엔진 예약 결과가 BTC 표로 섞이지 않고 ETH 원본과 금액을 대조하는지 검증한다. */
class EthereumWithdrawalEventTest {
    @Test
    void confirmedReservationUsesEthereumSourceAndCreatesNoSecondBalanceMove() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserBalanceRepository balances = mock(UserBalanceRepository.class);
        LedgerService ledger = mock(LedgerService.class);
        AuditService audit = mock(AuditService.class);
        UUID engine = UUID.randomUUID();
        UUID withdrawal = UUID.randomUUID();
        when(users.lockById(7L)).thenReturn(Optional.of(mock(User.class)));
        when(jdbc.queryForList(eq("SELECT user_id,asset,total FROM ethereum_withdrawals WHERE id=?"), eq(withdrawal)))
                .thenReturn(List.of(Map.of("user_id", 7L, "asset", "ETH", "total", new BigDecimal("1.0001"))));
        when(jdbc.queryForObject(contains("ethereum_withdrawal_reservation_events"), eq(Boolean.class), eq(withdrawal))).thenReturn(false);
        var events = new BitcoinWithdrawalEvents(jdbc, users, balances, ledger, audit);
        String raw = "{\"event_id\":\"" + engine + ":1:0\",\"engine_id\":\"" + engine
                + "\",\"event_type\":\"withdrawal_reserved\",\"withdrawal_id\":\"" + withdrawal
                + "\",\"user_id\":7,\"asset\":\"ETH\",\"amount\":\"1.0001\",\"timestamp\":\"2026-10-27T00:00:00Z\"}";

        assertDoesNotThrow(() -> events.apply(raw));
        verify(jdbc).update(startsWith("INSERT INTO ethereum_withdrawal_reservation_events"),
                eq(withdrawal), eq(engine + ":1:0"), eq(engine), eq("withdrawal_reserved"),
                eq("2026-10-27T00:00:00Z"), anyString(), eq("engine:" + engine + ":1:0"));
        verifyNoInteractions(balances);
    }

    @Test
    void ethereumCompletionIsClosedWithoutCanonicalChainConfirmation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UUID engine = UUID.randomUUID();
        UUID withdrawal = UUID.randomUUID();
        when(users.lockById(7L)).thenReturn(Optional.of(mock(User.class)));
        when(jdbc.queryForList(eq("SELECT user_id,asset,total FROM ethereum_withdrawals WHERE id=?"), eq(withdrawal)))
                .thenReturn(List.of(Map.of("user_id", 7L, "asset", "ETH", "total", new BigDecimal("1.0001"))));
        var events = new BitcoinWithdrawalEvents(jdbc, users, mock(UserBalanceRepository.class), mock(LedgerService.class), mock(AuditService.class));
        String raw = "{\"event_id\":\"" + engine + ":2:0\",\"engine_id\":\"" + engine
                + "\",\"event_type\":\"withdrawal_completed\",\"withdrawal_id\":\"" + withdrawal
                + "\",\"user_id\":7,\"asset\":\"ETH\",\"amount\":\"1.0001\",\"timestamp\":\"2026-10-27T00:00:00Z\"}";

        assertThrows(IllegalStateException.class, () -> events.apply(raw));
    }
}
