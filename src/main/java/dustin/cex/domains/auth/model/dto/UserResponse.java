package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 응답 DTO (비밀번호 제외)
 * User Response DTO (without password)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "사용자 정보 응답 (비밀번호 제외)")
public class UserResponse {
    @Schema(description = "사용자 ID", example = "1")
    private Long id;
    
    @Schema(description = "이메일 주소", example = "user@example.com")
    private String email;
    
    @Schema(description = "사용자명", example = "johndoe")
    private String username;
    
    @Schema(description = "계정 생성 시간")
    private LocalDateTime createdAt;
    
    @Schema(description = "계정 정보 수정 시간")
    private LocalDateTime updatedAt;
}
