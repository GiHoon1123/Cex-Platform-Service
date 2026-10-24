package dustin.cex.domains.edd;

import org.springframework.web.bind.annotation.*;

/** 현재 로그인 고객 본인의 안전한 업무 상태만 제공한다. 다른 고객 ID나 내부 판단 원문을 받지 않는다. */
@RestController
public class CustomerRelationshipController {
    private final RelationshipExitService service;
    public CustomerRelationshipController(RelationshipExitService service) {this.service=service;}
    @GetMapping("/api/customer/relationship")
    public RelationshipExitService.CustomerStatus status(@RequestAttribute long userId) {return service.customerStatus(userId);}
}
