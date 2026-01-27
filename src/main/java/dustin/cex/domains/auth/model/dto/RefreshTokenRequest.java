package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 토큰 갱신 요청 DTO
 * Refresh Token Request DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "토큰 갱신 요청")
public class RefreshTokenRequest {
    
    @Schema(description = "Refresh Token", example = "abc123def456...", required = true)
    @NotBlank(message = "Refresh Token은 필수입니다")
    private String refreshToken;
}
