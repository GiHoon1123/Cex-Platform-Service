package dustin.cex.domains.mail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 메일 스캐너의 GET으로 검증하지 않는다. 로그인한 고객의 명시적 POST만 토큰을 소모한다. */
@RestController
@RequestMapping("/api/customer/email")
public class CustomerEmailController {
    // 비밀 토큰의 형식은 서비스에서 검사한다. 기본 바인딩 오류의 거부 값 로그에 원문이 남지 않게 한다.
    public record Confirmation(@NotNull UUID challengeId,String token){@Override public String toString(){return "Confirmation[토큰 생략]";}}
    private final CustomerEmailVerification service;
    public CustomerEmailController(CustomerEmailVerification service){this.service=service;}
    @PostMapping("/challenges") public CustomerEmailVerification.Receipt request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key){return service.request(userId,sessionId,key);}
    @PostMapping("/confirmations") public CustomerEmailVerification.Status confirm(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody Confirmation body){return service.confirm(userId,sessionId,body.challengeId(),body.token());}
    @GetMapping public CustomerEmailVerification.Status status(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.status(userId,sessionId);}
}
