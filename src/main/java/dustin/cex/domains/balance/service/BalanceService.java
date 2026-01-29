package dustin.cex.domains.balance.service;

import dustin.cex.domains.balance.model.dto.BalanceResponse;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 잔고 서비스
 * Balance Service
 * 
 * 역할:
 * - 잔고 조회 비즈니스 로직 처리
 * - 사용자별 잔고 정보 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {
    
    private final UserBalanceRepository userBalanceRepository;
    
    /**
     * 특정 자산 잔고 조회
     * Get balance for specific asset
     * 
     * @param userId 사용자 ID
     * @param mintAddress 자산 종류 (예: "SOL")
     * @return 잔고 정보 (없으면 null)
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(Long userId, String mintAddress) {
        Optional<UserBalance> balanceOpt = userBalanceRepository.findByUserIdAndMintAddress(
                userId, mintAddress);
        
        if (balanceOpt.isEmpty()) {
            return null;
        }
        
        UserBalance balance = balanceOpt.get();
        BigDecimal total = balance.getAvailable().add(balance.getLocked());
        
        return BalanceResponse.builder()
                .userId(balance.getUserId())
                .mintAddress(balance.getMintAddress())
                .available(balance.getAvailable())
                .locked(balance.getLocked())
                .total(total)
                .build();
    }
    
    /**
     * 모든 잔고 조회
     * Get all balances for user
     * 
     * @param userId 사용자 ID
     * @return 잔고 목록
     */
    @Transactional(readOnly = true)
    public List<BalanceResponse> getAllBalances(Long userId) {
        List<UserBalance> balances = userBalanceRepository.findByUserId(userId);
        
        List<BalanceResponse> result = new ArrayList<>();
        for (UserBalance balance : balances) {
            BigDecimal total = balance.getAvailable().add(balance.getLocked());
            result.add(BalanceResponse.builder()
                    .userId(balance.getUserId())
                    .mintAddress(balance.getMintAddress())
                    .available(balance.getAvailable())
                    .locked(balance.getLocked())
                    .total(total)
                    .build());
        }
        return result;
    }
}
