package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 회원가입 응답 DTO
 * Signup Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "회원가입 응답")
public class SignupResponse {
    @Schema(description = "사용자 정보 (비밀번호 제외)")
    private UserResponse user;
    
    @Schema(description = "성공 메시지", example = "User created successfully")
    private String message;
}
