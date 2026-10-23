package dustin.cex.domains.cdd;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;

/** 고객 원문을 응답·로그·오류 메시지에 되돌리지 않는다. 작은 정형 신고만 받고 중복 필드도 거부한다. */
@RestController
public class CddController {
    private final CddService service;
    private final ObjectMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public CddController(CddService service) {this.service=service;}
    @PostMapping(value="/api/kyc/customer-profile",consumes="application/json")
    public CddService.Status declare(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) throws java.io.IOException {
        byte[] body=request.getInputStream().readNBytes(8193);
        if(body.length>8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        CustomerDeclaration declaration;
        try {
            var node=json.readTree(body);
            if(!node.isObject() || !node.path("beneficialOwnerSelf").isBoolean() || !node.path("expectedMonthlyQuote").isNumber()) throw CustomerDeclaration.invalid();
            for(String field:java.util.List.of("fullName","dateOfBirth","nationality","residenceCountry","address","occupation","purpose","sourceOfFunds","pep"))
                if(!node.path(field).isString()) throw CustomerDeclaration.invalid();
            declaration=json.treeToValue(node,CustomerDeclaration.class);
        }
        catch(RuntimeException e) {throw CustomerDeclaration.invalid();}
        return service.submit(userId,sessionId,key,declaration);
    }
    @GetMapping("/api/kyc/customer-profile") public CddService.Status own(@RequestAttribute long userId,@RequestAttribute UUID sessionId) {return service.own(userId,sessionId);}
    @GetMapping("/api/admin/kyc/customer-profiles/{id}") public CddService.Status admin(@PathVariable long id) {return service.status(id);}
}
