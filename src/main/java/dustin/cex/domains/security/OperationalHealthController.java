package dustin.cex.domains.security;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 로드밸런서용 최소 생존·준비 상태와 인증된 운영 적체 집계를 분리한다. */
@RestController
public class OperationalHealthController {
    private final OperationalHealthService service;
    public OperationalHealthController(OperationalHealthService service){this.service=service;}
    @GetMapping("/api/health/live") public OperationalHealthService.Health live(){return service.live();}
    @GetMapping("/api/health/ready") public ResponseEntity<OperationalHealthService.Health> ready(){var health=service.ready();return ResponseEntity.status("UP".equals(health.status())?HttpStatus.OK:HttpStatus.SERVICE_UNAVAILABLE).body(health);}
    @GetMapping("/api/admin/operations/status") public OperationalHealthService.Status status(){return service.status();}
}
