package dustin.cex.domains.settlement.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementFailure;
import dustin.cex.domains.settlement.trade.service.TradeSettlementService;

/**
 * 거래 정산 실패 기록 테스트
 * TradeSettlementService.recordFailure / trade_settlement_failures 테이블 검증
 *
 * 목적:
 * - 정산 각 단계(1~4)에서 발생한 실패를 개별 행으로 기록·조회하는지 검증
 * - 3단계는 사용자별 실패이므로 userId 필수, 1·2·4단계는 userId null 허용
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
    "bot.bot1-email=bot1@bot.com",
    "bot.bot1-password=botpassword",
    "bot.bot2-email=bot2@bot.com",
    "bot.bot2-password=botpassword",
    "bot.binance-ws-url=wss://test",
    "bot.binance-symbol=SOLUSDT",
    "bot.orderbook-depth=200",
    "bot.order-quantity=1.0"
})
@org.springframework.context.annotation.Import(dustin.cex.config.TestConfig.class)
class TradeSettlementFailureTest {

    @Autowired
    private TradeSettlementService tradeSettlementService;

    /**
     * 3단계(사용자별 정산)에서 두 사용자 실패 기록 후, 해당 날짜로 조회 시 2건 반환·내용 일치
     */
    @Test
    @DisplayName("recordFailure 후 getFailuresByDate로 조회")
    void recordAndGetFailures() {
        LocalDate date = LocalDate.of(2026, 1, 28);
        tradeSettlementService.recordFailure(date, 3, 1001L, "User balance not found");
        tradeSettlementService.recordFailure(date, 3, 1002L, "Timeout");

        List<TradeSettlementFailure> list = tradeSettlementService.getFailuresByDate(date);
        assertThat(list).hasSize(2);
        assertThat(list).allMatch(f -> f.getSettlementDate().equals(date) && f.getStep() == 3);
        assertThat(list.stream().map(TradeSettlementFailure::getUserId).toList())
                .containsExactlyInAnyOrder(1001L, 1002L);
        assertThat(list.stream().map(TradeSettlementFailure::getErrorMessage))
                .anyMatch(m -> m != null && m.contains("balance"));
        assertThat(list.stream().map(TradeSettlementFailure::getErrorMessage))
                .anyMatch(m -> m != null && m.contains("Timeout"));
    }

    /**
     * 실패 기록이 없는 날짜로 조회 시 빈 리스트 반환 (API 응답·대시보드에서 사용)
     */
    @Test
    @DisplayName("실패 기록 없는 날짜 조회 시 빈 리스트")
    void getFailuresByDateEmpty() {
        LocalDate date = LocalDate.of(2099, 12, 31);
        List<TradeSettlementFailure> list = tradeSettlementService.getFailuresByDate(date);
        assertThat(list).isEmpty();
    }

    /**
     * 1·2·4단계는 "전체" 단위 실패이므로 사용자 개념 없음
     * userId=null로 기록해도 저장·조회 정상 동작하는지 검증
     */
    @Test
    @DisplayName("1·2·4단계 실패 기록 시 userId null 허용")
    void recordFailureStepWithoutUserId() {
        LocalDate date = LocalDate.of(2026, 2, 1);
        tradeSettlementService.recordFailure(date, 1, null, "스냅샷 실패");
        tradeSettlementService.recordFailure(date, 2, null, "정산 집계 실패");
        tradeSettlementService.recordFailure(date, 4, null, "검증 실패");

        List<TradeSettlementFailure> list = tradeSettlementService.getFailuresByDate(date);
        assertThat(list).hasSize(3);
        assertThat(list).allMatch(f -> f.getUserId() == null);
        assertThat(list.stream().map(TradeSettlementFailure::getStep).toList())
                .containsExactlyInAnyOrder(1, 2, 4);
    }

    /**
     * 에러 메시지를 못 얻은 경우(예: 타임아웃) null로 기록해도 NPE 없이 저장
     */
    @Test
    @DisplayName("recordFailure 시 errorMessage null이어도 예외 없음")
    void recordFailureWithNullErrorMessage() {
        LocalDate date = LocalDate.of(2026, 2, 2);
        tradeSettlementService.recordFailure(date, 3, 2000L, null);
        List<TradeSettlementFailure> list = tradeSettlementService.getFailuresByDate(date);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getErrorMessage()).isNull();
        assertThat(list.get(0).getUserId()).isEqualTo(2000L);
    }
}
