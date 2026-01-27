package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 토큰 갱신 응답 DTO
 * Refresh Token Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "토큰 갱신 응답")
public class RefreshTokenResponse {
    @Schema(description = "새 Access Token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String accessToken;
    
    @Schema(description = "새 Refresh Token", example = "abc123def456...")
    private String refreshToken;
    
    @Schema(description = "성공 메시지", example = "Token refreshed successfully")
    private String message;
}
