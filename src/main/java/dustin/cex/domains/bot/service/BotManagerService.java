package dustin.cex.domains.bot.service;

import dustin.cex.domains.auth.model.dto.SignupRequest;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.auth.service.AuthService;
import dustin.cex.domains.bot.model.BotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Optional;

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
 * - bot1@bot.com: 매수 전용 봇
 * - bot2@bot.com: 매도 전용 봇
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotManagerService {
    
    private final BotConfig botConfig;
    private final AuthService authService;
    private final UserRepository userRepository;
    
    /**
     * 봇 1 (매수 전용) 사용자 정보
     * Bot 1 (Buy only) user info
     */
    private User bot1User;
    
    /**
     * 봇 2 (매도 전용) 사용자 정보
     * Bot 2 (Sell only) user info
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
        log.info("[BotManagerService] 봇 계정 초기화 시작...");
        
        try {
            // 1. 봇 계정 확인/생성
            bot1User = ensureBotAccount(botConfig.getBot1Email(), botConfig.getBot1Password());
            bot2User = ensureBotAccount(botConfig.getBot2Email(), botConfig.getBot2Password());
            
            log.info("[BotManagerService] 봇 계정 초기화 완료:");
            log.info("  - Bot1 (매수 전용): {} (userId={})", bot1User.getEmail(), bot1User.getId());
            log.info("  - Bot2 (매도 전용): {} (userId={})", bot2User.getEmail(), bot2User.getId());
            
            // 2. 봇 잔고 설정 (향후 구현)
            // TODO: user_balances 테이블 생성 후 구현
            // setBotBalances();
            
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
            log.info("[BotManagerService] 봇 계정 이미 존재: {}", email);
            return existingUser.get();
        }
        
        // 계정이 없으면 생성
        log.info("[BotManagerService] 봇 계정 생성 중: {}", email);
        SignupRequest signupRequest = new SignupRequest();
        signupRequest.setEmail(email);
        signupRequest.setPassword(password);
        signupRequest.setUsername(email); // username도 email과 동일하게
        
        var signupResponse = authService.signup(signupRequest);
        log.info("[BotManagerService] 봇 계정 생성 완료: {} (userId={})", 
                 email, signupResponse.getUser().getId());
        
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
     * 봇 계정에 무한대에 가까운 SOL/USDT를 설정합니다.
     * 
     * 주의: user_balances 테이블이 생성된 후에만 동작합니다.
     * 
     * 설정할 잔고:
     * - Bot 1: 1,000,000,000 SOL, 1,000,000,000 USDT
     * - Bot 2: 1,000,000,000 SOL, 1,000,000,000 USDT
     */
    private void setBotBalances() {
        // TODO: user_balances 테이블 생성 후 구현
        // BigDecimal hugeBalance = BigDecimal.valueOf(1_000_000_000L);
        // 
        // // Bot 1 자산 설정
        // setBotBalance(bot1User.getId(), "SOL", hugeBalance);
        // setBotBalance(bot1User.getId(), "USDT", hugeBalance);
        // 
        // // Bot 2 자산 설정
        // setBotBalance(bot2User.getId(), "SOL", hugeBalance);
        // setBotBalance(bot2User.getId(), "USDT", hugeBalance);
        
        log.warn("[BotManagerService] 봇 잔고 설정은 아직 구현되지 않았습니다 (user_balances 테이블 필요)");
    }
    
    /**
     * 봇 1 (매수 전용) 사용자 ID 가져오기
     * Get bot 1 (buy only) user ID
     * 
     * @return 봇 1 사용자 ID (없으면 null)
     */
    public Long getBot1UserId() {
        return bot1User != null ? bot1User.getId() : null;
    }
    
    /**
     * 봇 2 (매도 전용) 사용자 ID 가져오기
     * Get bot 2 (sell only) user ID
     * 
     * @return 봇 2 사용자 ID (없으면 null)
     */
    public Long getBot2UserId() {
        return bot2User != null ? bot2User.getId() : null;
    }
    
    /**
     * 봇 1 (매수 전용) 사용자 정보 가져오기
     * Get bot 1 (buy only) user info
     * 
     * @return 봇 1 사용자 정보 (없으면 null)
     */
    public User getBot1User() {
        return bot1User;
    }
    
    /**
     * 봇 2 (매도 전용) 사용자 정보 가져오기
     * Get bot 2 (sell only) user info
     * 
     * @return 봇 2 사용자 정보 (없으면 null)
     */
    public User getBot2User() {
        return bot2User;
    }
}
