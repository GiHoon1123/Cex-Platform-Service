package dustin.cex.domains.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 감사 제출 묶음 생성과 저장된 묶음의 무결성 확인만 제공한다. */
@RestController
@RequestMapping("/api/admin/audit-evidence")
public class AuditEvidenceController {
    private final AuditEvidenceService service;
    public AuditEvidenceController(AuditEvidenceService service){this.service=service;}
    public record ExportRequest(@NotNull Instant rangeStart,@NotNull Instant rangeEnd,@Pattern(regexp="[A-Z0-9_]{1,64}") String actionFilter){}
    @PostMapping public AuditEvidenceService.Export export(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody ExportRequest body){return service.export(userId,sessionId,body.rangeStart(),body.rangeEnd(),body.actionFilter());}
    @GetMapping("/{id}/verification") public AuditEvidenceService.Verification verify(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.verify(userId,sessionId,id);}
}
