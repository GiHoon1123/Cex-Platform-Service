package dustin.cex.domains.mail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 선택 알림만 설정한다. 서비스 이용에 필요한 금융 통제를 변경하는 API가 아니다. */
@RestController
@RequestMapping("/api/customer/email/preferences")
public class CustomerServiceMailController {
    public record Choice(@NotNull Boolean enabled) {}
    private final CustomerServiceMail service;
    public CustomerServiceMailController(CustomerServiceMail service){this.service=service;}
    @GetMapping public CustomerServiceMail.Preference own(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.own(userId,sessionId);}
    @PutMapping public CustomerServiceMail.Preference choose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Choice body){return service.choose(userId,sessionId,key,body.enabled());}
}
