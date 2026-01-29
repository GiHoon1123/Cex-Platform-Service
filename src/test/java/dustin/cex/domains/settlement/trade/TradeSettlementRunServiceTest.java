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

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementRun;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementRunRepository;
import dustin.cex.domains.settlement.trade.service.TradeSettlementRunService;

/**
 * 거래 정산 실행 런 서비스 테스트
 * TradeSettlementRunService / trade_settlement_runs 테이블 검증
 *
 * 목적:
 * - 일일 정산 실행 시 "실패 지점부터 재실행"을 위해 런(run) 기록이 올바르게 생성·갱신되는지 검증
 * - startRun → updateCompletedStep → completeRun / failRun 라이프사이클 및 엣지 케이스
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
class TradeSettlementRunServiceTest {

    @Autowired
    private TradeSettlementRunService tradeSettlementRunService;

    @Autowired
    private TradeSettlementRunRepository tradeSettlementRunRepository;

    /**
     * 정상 플로우: 런 시작 → 1~4단계 순차 완료 → SUCCESS 저장
     * 재실행 시 completed_step 기준으로 어디서부터 이어갈지 판단하는 데 사용
     */
    @Test
    @DisplayName("런 시작 -> 단계 업데이트 -> 완료 시 SUCCESS 저장")
    void runLifecycleSuccess() {
        LocalDate date = LocalDate.of(2026, 1, 28);
        TradeSettlementRun run = tradeSettlementRunService.startRun(date);
        assertThat(run.getId()).isNotNull();
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getCompletedStep()).isEqualTo(0);

        tradeSettlementRunService.updateCompletedStep(run.getId(), 1);
        tradeSettlementRunService.updateCompletedStep(run.getId(), 2);
        tradeSettlementRunService.updateCompletedStep(run.getId(), 3);
        tradeSettlementRunService.updateCompletedStep(run.getId(), 4);
        tradeSettlementRunService.completeRun(run.getId());

        TradeSettlementRun saved = tradeSettlementRunRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getCompletedStep()).isEqualTo(4);
    }

    /**
     * 실패 플로우: 3단계(사용자별 정산)에서 일부 사용자만 실패한 경우
     * failedUserIds를 JSON으로 저장해 나중에 해당 사용자만 재처리할 수 있도록 함
     */
    @Test
    @DisplayName("런 실패 시 FAILED + failedUserIds 저장")
    void runLifecycleFail() {
        LocalDate date = LocalDate.of(2026, 1, 29);
        TradeSettlementRun run = tradeSettlementRunService.startRun(date);
        tradeSettlementRunService.updateCompletedStep(run.getId(), 1);
        tradeSettlementRunService.updateCompletedStep(run.getId(), 2);
        tradeSettlementRunService.failRun(run.getId(), "사용자별 정산 미완료", List.of(101L, 102L));

        TradeSettlementRun saved = tradeSettlementRunRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getErrorMessage()).contains("미완료");
        assertThat(saved.getFailedUserIdsJson()).isNotNull();
        List<Long> parsed = tradeSettlementRunService.parseFailedUserIds(saved.getFailedUserIdsJson());
        assertThat(parsed).containsExactlyInAnyOrder(101L, 102L);
    }

    /**
     * 1·2·4단계처럼 "사용자 단위"가 아닌 실패는 failedUserIds가 없음
     * null/빈 리스트 전달 시 JSON 필드는 null로 두고 NPE 없이 동작하는지 검증
     */
    @Test
    @DisplayName("failRun 시 failedUserIds null/빈 리스트면 JSON 미설정, NPE 없음")
    void failRunWithNullOrEmptyFailedUserIds() {
        LocalDate date = LocalDate.of(2026, 1, 30);
        TradeSettlementRun run = tradeSettlementRunService.startRun(date);
        tradeSettlementRunService.failRun(run.getId(), "2단계 실패", null);

        TradeSettlementRun saved = tradeSettlementRunRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getErrorMessage()).isEqualTo("2단계 실패");
        assertThat(saved.getFailedUserIdsJson()).isNull();

        TradeSettlementRun run2 = tradeSettlementRunService.startRun(date.plusDays(1));
        tradeSettlementRunService.failRun(run2.getId(), "4단계 실패", List.of());
        TradeSettlementRun saved2 = tradeSettlementRunRepository.findById(run2.getId()).orElseThrow();
        assertThat(saved2.getStatus()).isEqualTo("FAILED");
        assertThat(saved2.getFailedUserIdsJson()).isNull();
    }

    /**
     * 잘못된 runId로 update/complete/fail 호출해도 예외 없이 스킵
     * (이미 삭제된 런이거나 타임아웃 등으로 ID만 남은 경우 대비)
     */
    @Test
    @DisplayName("존재하지 않는 runId로 updateCompletedStep 호출 시 예외 없이 스킵")
    void updateCompletedStepWithInvalidRunId() {
        tradeSettlementRunService.updateCompletedStep(999999L, 1);
        tradeSettlementRunService.completeRun(999999L);
        tradeSettlementRunService.failRun(999999L, "err", List.of(1L));
        assertThat(tradeSettlementRunRepository.findById(999999L)).isEmpty();
    }

    /**
     * DB에 저장된 failedUserIdsJson이 손상되었거나 비어 있을 때
     * parseFailedUserIds가 빈 리스트를 반환해 후속 로직이 깨지지 않도록 검증
     */
    @Test
    @DisplayName("parseFailedUserIds: 잘못된 JSON이면 빈 리스트 반환")
    void parseFailedUserIdsInvalidJson() {
        List<Long> empty = tradeSettlementRunService.parseFailedUserIds(null);
        assertThat(empty).isEmpty();
        assertThat(tradeSettlementRunService.parseFailedUserIds("")).isEmpty();
        assertThat(tradeSettlementRunService.parseFailedUserIds("not json")).isEmpty();
        assertThat(tradeSettlementRunService.parseFailedUserIds("[1,2")).isEmpty();
    }
}
