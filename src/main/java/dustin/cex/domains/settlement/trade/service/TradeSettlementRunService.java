package dustin.cex.domains.settlement.trade.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementRun;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 실행 런 서비스
 * Trade Settlement Run Service
 *
 * 역할: 정산 실행 시 run 생성·completed_step 업데이트 (단계별 재실행 스토리)
 * 테이블: trade_settlement_runs (거래 정산 도메인)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSettlementRunService {

    private final TradeSettlementRunRepository tradeSettlementRunRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TradeSettlementRun startRun(LocalDate settlementDate) {
        TradeSettlementRun run = TradeSettlementRun.builder()
                .settlementDate(settlementDate)
                .runAt(LocalDateTime.now())
                .completedStep(0)
                .status("RUNNING")
                .build();
        TradeSettlementRun saved = tradeSettlementRunRepository.save(run);
        log.info("[TradeSettlementRunService] 런 시작: date={}, runId={}", settlementDate, saved.getId());
        return saved;
    }

    @Transactional
    public void updateCompletedStep(Long runId, int step) {
        tradeSettlementRunRepository.findById(runId).ifPresent(run -> {
            run.setCompletedStep(step);
            tradeSettlementRunRepository.save(run);
        });
    }

    @Transactional
    public void completeRun(Long runId) {
        tradeSettlementRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus("SUCCESS");
            run.setCompletedStep(4);
            tradeSettlementRunRepository.save(run);
            log.info("[TradeSettlementRunService] 런 완료: runId={}", runId);
        });
    }

    @Transactional
    public void failRun(Long runId, String errorMessage, List<Long> failedUserIds) {
        tradeSettlementRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus("FAILED");
            run.setErrorMessage(errorMessage);
            if (failedUserIds != null && !failedUserIds.isEmpty()) {
                try {
                    run.setFailedUserIdsJson(objectMapper.writeValueAsString(failedUserIds));
                } catch (JsonProcessingException e) {
                    log.warn("[TradeSettlementRunService] failedUserIds JSON 직렬화 실패", e);
                }
            }
            tradeSettlementRunRepository.save(run);
            log.info("[TradeSettlementRunService] 런 실패: runId={}, error={}", runId, errorMessage);
        });
    }

    public List<Long> parseFailedUserIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("[TradeSettlementRunService] failedUserIds JSON 파싱 실패: {}", json, e);
            return List.of();
        }
    }
}
