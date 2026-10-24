package dustin.cex.domains.mail;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 상태 조회만 공개한다. 수신 주소·암호문·토큰·메일 원문을 반환하는 경로는 두지 않는다. */
@RestController
@RequestMapping("/api/admin/operations/customer-mail")
public class CustomerMailOperationsController {
    private final CustomerMailOperations service;
    public CustomerMailOperationsController(CustomerMailOperations service){this.service=service;}
    @GetMapping public CustomerMailOperations.Page attention(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) Instant afterAt,@RequestParam(required=false) UUID afterId){return service.attention(userId,sessionId,cursor(afterAt,afterId));}
    @GetMapping("/{id}") public CustomerMailOperations.Item job(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.ownJob(userId,sessionId,id);}
    @GetMapping("/{id}/attempts") public CustomerMailOperations.Events attempts(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(required=false) Instant afterAt,@RequestParam(required=false) UUID afterId){return service.events(userId,sessionId,id,cursor(afterAt,afterId));}
    private CustomerMailOperations.Cursor cursor(Instant at,UUID id){return at==null&&id==null?null:new CustomerMailOperations.Cursor(at,id);}
}
