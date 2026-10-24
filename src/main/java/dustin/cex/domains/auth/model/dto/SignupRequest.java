package dustin.cex.domains.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 회원가입 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "회원가입 요청")
public class SignupRequest {
    
    @Schema(description = "이메일 주소", example = "user@example.com", required = true)
    @NotBlank(message = "이메일은 필수입니다")
    @Email(message = "유효한 이메일 형식이 아닙니다")
    private String email;
    
    @Schema(description = "새 비밀번호: 15개 코드 포인트 이상, 최대 256 UTF-16 코드 단위", required = true)
    // 비밀 입력 정책은 서비스에서 검증해 거부된 원문이 바인딩 로그에 남지 않게 한다.
    @lombok.ToString.Exclude
    private String password;
    
    @Schema(description = "사용자명 (선택사항)", example = "johndoe")
    @Size(max = 100, message = "사용자명은 최대 100자까지 가능합니다")
    private String username;
}
