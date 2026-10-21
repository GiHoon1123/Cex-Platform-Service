package dustin.cex.shared.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dustin.cex.shared.http.EngineHttpClient;

/**
 * 시작 훅을 제거해도 명시적 잔액 증감의 위임 계약이 유지되는지 확인한다.
 * 엔진 HTTP 클라이언트를 대체하므로 실제 잔액은 변경하지 않는다.
 * 이 테스트는 입출금의 영속성·멱등성까지 보장하는 테스트가 아니다.
 */
class BalanceSyncServiceTest {

    private EngineHttpClient engineHttpClient;
    private BalanceSyncService service;

    @BeforeEach
    void createService() {
        engineHttpClient = mock(EngineHttpClient.class);
        service = new BalanceSyncService(engineHttpClient);
    }

    @Test
    @DisplayName("명시적 입금 요청은 같은 금액을 한 번 전달한다")
    void explicitDepositSendsOnePositiveDelta() {
        // BigDecimal은 금융 금액의 십진수 정밀도를 유지한다. double에서 만들지 않는다.
        var amount = new BigDecimal("12.34567890");
        when(engineHttpClient.syncBalance(101L, "USDT", amount)).thenReturn(true);

        service.syncDeposit(101L, "USDT", amount);

        verify(engineHttpClient).syncBalance(101L, "USDT", amount);
        verifyNoMoreInteractions(engineHttpClient);
    }

    @Test
    @DisplayName("명시적 출금 요청은 부호를 바꿔 한 번 전달한다")
    void explicitWithdrawalSendsOneNegativeDelta() {
        var amount = new BigDecimal("0.12500000");
        // negate()는 원래 금액을 변경하지 않고 부호가 반대인 BigDecimal을 반환한다.
        when(engineHttpClient.syncBalance(101L, "SOL", amount.negate())).thenReturn(true);

        service.syncWithdrawal(101L, "SOL", amount);

        verify(engineHttpClient).syncBalance(101L, "SOL", amount.negate());
        verifyNoMoreInteractions(engineHttpClient);
    }

    @Test
    @DisplayName("엔진 실패 응답을 성공으로 숨기거나 자동 재시도하지 않는다")
    void rejectedDeltaPropagatesFailureWithoutRetry() {
        var amount = new BigDecimal("10.00");
        when(engineHttpClient.syncBalance(101L, "USDT", amount)).thenReturn(false);

        assertThatThrownBy(() -> service.syncDeposit(101L, "USDT", amount))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("잔고 동기화 실패");

        verify(engineHttpClient).syncBalance(101L, "USDT", amount);
        verifyNoMoreInteractions(engineHttpClient);
    }

    @Test
    @DisplayName("통신 예외는 원인을 보존하고 자동 재시도하지 않는다")
    void transportFailurePropagatesCauseWithoutRetry() {
        var amount = new BigDecimal("10.00");
        var failure = new IllegalStateException("engine unavailable");
        when(engineHttpClient.syncBalance(101L, "USDT", amount)).thenThrow(failure);

        // 응답이 없다는 사실만으로 엔진 미반영을 단정할 수 없다.
        // 거래 ID가 없는 delta를 재전송해 중복 반영하는 동작이 없는지도 확인한다.
        assertThatThrownBy(() -> service.syncDeposit(101L, "USDT", amount))
                .isInstanceOf(RuntimeException.class)
                .hasCause(failure);

        verify(engineHttpClient).syncBalance(101L, "USDT", amount);
        verifyNoMoreInteractions(engineHttpClient);
    }
}
