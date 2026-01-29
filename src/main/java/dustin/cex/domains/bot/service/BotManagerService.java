package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.model.dto.SignupRequest;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.auth.service.AuthService;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.bot.model.BotConfig;
import dustin.cex.shared.services.BalanceSyncService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 봇 관리자 서비스
 * Bot Manager Service
 * 
 * 역할:
 * - 봇 계정 생성/확인 (서버 시작 시)
 * - 봇 자산 설정 (무한대에 가까운 SOL/USDT 제공)
 * - 봇 주문 생성/취소 관리
 * 
 * 처리 흐름:
 * 1. 서버 시작 시 봇 계정 확인 (없으면 생성)
 * 2. 봇 자산 설정 (1,000,000,000 SOL, 1,000,000,000 USDT)
 * 3. 봇 주문 생성/취소 API 제공
 * 
 * 봇 계정:
 * - bot1@bot.com ~ botN@bot.com: 여러 봇 생성 가능
 * - 각 봇은 BotConfig에서 설정한 자산을 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotManagerService {
    
    private final BotConfig botConfig;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final BalanceSyncService balanceSyncService;
    private final UserBalanceRepository userBalanceRepository;
    
    /**
     * 봇 사용자 정보 맵 (봇 ID -> User)
     * Bot user info map (bot ID -> User)
     */
    private final Map<Long, User> botUsers = new HashMap<>();
    
    /**
     * 봇 1 (매수 전용) 사용자 정보 (하위 호환성)
     * Bot 1 (Buy only) user info (backward compatibility)
     */
    private User bot1User;
    
    /**
     * 봇 2 (매도 전용) 사용자 정보 (하위 호환성)
     * Bot 2 (Sell only) user info (backward compatibility)
     */
    private User bot2User;
    
    /**
     * 서버 시작 시 봇 계정 초기화
     * Initialize bot accounts on server startup
     * 
     * @PostConstruct로 서버 시작 시 자동 실행
     */
    @PostConstruct
    @Transactional
    public void initializeBots() {
        log.info("[BotManagerService] 봇 계정 초기화 시작... (봇 수: {})", botConfig.getBotCount());
        
        try {
            int botCount = botConfig.getBotCount();
            
            // 1. 봇 계정 확인/생성
            for (long i = 1; i <= botCount; i++) {
                String email = String.format("bot%d@bot.com", i);
                String password = "123123"; // 모든 봇 동일한 비밀번호
                
                User botUser = ensureBotAccount(email, password);
                botUsers.put(i, botUser);
                
                // 하위 호환성을 위해 bot1, bot2 저장
                if (i == 1) {
                    bot1User = botUser;
                } else if (i == 2) {
                    bot2User = botUser;
                }
            }
            
            log.info("[BotManagerService] 봇 계정 초기화 완료: {}명", botCount);
            
            // 2. 봇 잔고 설정 (엔진에 동기화)
            setBotBalances();
            
        } catch (Exception e) {
            log.error("[BotManagerService] 봇 계정 초기화 실패", e);
            throw new RuntimeException("봇 계정 초기화 실패", e);
        }
    }
    
    /**
     * 봇 계정 확인/생성
     * Ensure bot account exists
     * 
     * 계정이 있으면 반환, 없으면 생성 후 반환
     * 
     * @param email 봇 이메일
     * @param password 봇 비밀번호
     * @return 봇 사용자 정보
     */
    private User ensureBotAccount(String email, String password) {
        // 계정이 이미 있는지 확인
        Optional<User> existingUser = userRepository.findByEmail(email);
        
        if (existingUser.isPresent()) {
            // 계정이 이미 존재함
            // log.info("[BotManagerService] 봇 계정 이미 존재: {}", email);
            return existingUser.get();
        }
        
        // 계정이 없으면 생성
        // log.info("[BotManagerService] 봇 계정 생성 중: {}", email);
        SignupRequest signupRequest = new SignupRequest();
        signupRequest.setEmail(email);
        signupRequest.setPassword(password);
        signupRequest.setUsername(email); // username도 email과 동일하게
        
        var signupResponse = authService.signup(signupRequest);
        // log.info("[BotManagerService] 봇 계정 생성 완료: {} (userId={})", 
        //          email, signupResponse.getUser().getId());
        
        // User 엔티티로 변환
        User user = User.builder()
                .id(signupResponse.getUser().getId())
                .email(signupResponse.getUser().getEmail())
                .username(signupResponse.getUser().getUsername())
                .createdAt(signupResponse.getUser().getCreatedAt())
                .updatedAt(signupResponse.getUser().getUpdatedAt())
                .build();
        
        return user;
    }
    
    /**
     * 봇 잔고 설정
     * Set bot balances
     * 
     * 봇 계정에 무한대에 가까운 SOL/USDT를 엔진에 동기화합니다.
     * 각 봇은 BotConfig에서 설정한 자산을 사용합니다.
     * 
     * 설정할 잔고:
     * - 각 봇: 1,000,000,000 SOL, 1,000,000,000 USDT
     * - 매수 봇: USDT 필요
     * - 매도 봇: SOL 필요
     */
    private void setBotBalances() {
        try {
            BigDecimal hugeBalance = BigDecimal.valueOf(1_000_000_000L);
            
            log.info("[BotManagerService] 봇 잔고 설정 시작...");
            
            // 모든 봇에 SOL과 USDT 모두 설정 (매수/매도 모두 가능하도록)
            for (Map.Entry<Long, User> entry : botUsers.entrySet()) {
                Long botId = entry.getKey();
                User botUser = entry.getValue();
                String baseMint = botConfig.getBotBaseMint(botId);
                
                // 1. Rust 엔진에 잔고 동기화
                balanceSyncService.syncDeposit(botUser.getId(), baseMint, hugeBalance);
                balanceSyncService.syncDeposit(botUser.getId(), "USDT", hugeBalance);
                
                // 2. Java DB의 user_balances 테이블에도 초기 잔고 생성
                createOrUpdateBalance(botUser.getId(), baseMint, hugeBalance);
                createOrUpdateBalance(botUser.getId(), "USDT", hugeBalance);
                
                log.debug("[BotManagerService] Bot{} 잔고 설정 완료: {}={}, USDT={}", 
                         botId, baseMint, hugeBalance, hugeBalance);
            }
            
            log.info("[BotManagerService] 봇 잔고 설정 완료: {}명", botUsers.size());
        } catch (Exception e) {
            log.error("[BotManagerService] 봇 잔고 설정 실패", e);
            // 봇 잔고 설정 실패해도 서버는 계속 실행 (경고만)
        }
    }
    
    /**
     * 봇 사용자 ID 가져오기
     * Get bot user ID
     * 
     * @param botId 봇 ID (1부터 시작)
     * @return 봇 사용자 ID (없으면 null)
     */
    public Long getBotUserId(Long botId) {
        User botUser = botUsers.get(botId);
        return botUser != null ? botUser.getId() : null;
    }
    
    /**
     * 봇 사용자 정보 가져오기
     * Get bot user info
     * 
     * @param botId 봇 ID (1부터 시작)
     * @return 봇 사용자 정보 (없으면 null)
     */
    public User getBotUser(Long botId) {
        return botUsers.get(botId);
    }
    
    /**
     * 잔고 생성 또는 업데이트
     * Create or update balance
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param amount 잔고 금액
     */
    private void createOrUpdateBalance(Long userId, String mint, BigDecimal amount) {
        try {
            var balanceOpt = userBalanceRepository.findByUserIdAndMintAddress(userId, mint);
            
            if (balanceOpt.isPresent()) {
                // 기존 잔고 업데이트
                UserBalance balance = balanceOpt.get();
                balance.setAvailable(amount);
                balance.setLocked(BigDecimal.ZERO);
                userBalanceRepository.save(balance);
                log.debug("[BotManagerService] 잔고 업데이트: userId={}, mint={}, amount={}", userId, mint, amount);
            } else {
                // 새 잔고 생성
                UserBalance balance = UserBalance.builder()
                        .userId(userId)
                        .mintAddress(mint)
                        .available(amount)
                        .locked(BigDecimal.ZERO)
                        .build();
                userBalanceRepository.save(balance);
                log.debug("[BotManagerService] 잔고 생성: userId={}, mint={}, amount={}", userId, mint, amount);
            }
        } catch (Exception e) {
            log.error("[BotManagerService] 잔고 생성/업데이트 실패: userId={}, mint={}, amount={}", userId, mint, amount, e);
            throw e;
        }
    }
    
    /**
     * 모든 봇 사용자 ID 목록 가져오기
     * Get all bot user IDs
     * 
     * @return 봇 사용자 ID 목록
     */
    public List<Long> getAllBotUserIds() {
        return botUsers.values().stream()
                .map(User::getId)
                .collect(Collectors.toList());
    }
    
    /**
     * 봇 1 (매수 전용) 사용자 ID 가져오기 (하위 호환성)
     * Get bot 1 (buy only) user ID (backward compatibility)
     * 
     * @return 봇 1 사용자 ID (없으면 null)
     */
    public Long getBot1UserId() {
        return getBotUserId(1L);
    }
    
    /**
     * 봇 2 (매도 전용) 사용자 ID 가져오기 (하위 호환성)
     * Get bot 2 (sell only) user ID (backward compatibility)
     * 
     * @return 봇 2 사용자 ID (없으면 null)
     */
    public Long getBot2UserId() {
        return getBotUserId(2L);
    }
    
    /**
     * 봇 1 (매수 전용) 사용자 정보 가져오기 (하위 호환성)
     * Get bot 1 (buy only) user info (backward compatibility)
     * 
     * @return 봇 1 사용자 정보 (없으면 null)
     */
    public User getBot1User() {
        return getBotUser(1L);
    }
    
    /**
     * 봇 2 (매도 전용) 사용자 정보 가져오기 (하위 호환성)
     * Get bot 2 (sell only) user info (backward compatibility)
     * 
     * @return 봇 2 사용자 정보 (없으면 null)
     */
    public User getBot2User() {
        return getBotUser(2L);
    }
}
