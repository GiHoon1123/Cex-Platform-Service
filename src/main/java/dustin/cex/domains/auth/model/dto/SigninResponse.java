package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 로그인 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "로그인 응답")
public class SigninResponse {
    @Schema(description = "사용자 정보 (비밀번호 제외)")
    private UserResponse user;
    
    @Schema(description = "JWT Access Token (짧은 수명)", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    @lombok.ToString.Exclude
    private String accessToken;
    
    @Schema(description = "Refresh Token (긴 수명, DB에 저장)", example = "abc123def456...")
    @lombok.ToString.Exclude
    private String refreshToken;
    
    @Schema(description = "성공 메시지", example = "Login successful")
    private String message;

    // 참이면 반환된 토큰은 보안키 인증 전용이며 운영 업무에는 사용할 수 없다.
    private boolean mfaRequired;
}
