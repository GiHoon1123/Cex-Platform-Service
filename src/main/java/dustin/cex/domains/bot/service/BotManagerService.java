package dustin.cex.domains.bot.service;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.bot.model.BotConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

/** 봇 재시작은 계정·잔액을 만들거나 초기화하지 않는다. 자금 준비는 별도 통제된 절차다. */
@Service @RequiredArgsConstructor
public class BotManagerService {
    private final BotConfig botConfig;
    private final UserRepository users;
    private final Map<Long,User> botUsers=new HashMap<>();
    @PostConstruct public void initializeBots() {
        if(botConfig.getBotCount()<0 || botConfig.getBotCount()>20) throw new IllegalArgumentException("Bot count must be between 0 and 20");
        for(long id=1;id<=botConfig.getBotCount();id++) {
            // 자동 가입이나 공통 비밀번호를 제공하지 않는다. 운영자가 계정·자금을
            // 준비하지 않았다면 시작을 막아 의도치 않은 자산 발행을 방지한다.
            String email="bot"+id+"@bot.com";
            botUsers.put(id,users.findByEmail(email).orElseThrow(()->new IllegalStateException("Provision bot account first: "+email)));
        }
    }
    public User getBotUser(Long botId) {return botUsers.get(botId);}
}
