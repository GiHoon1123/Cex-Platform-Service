package dustin.cex.domains.auth.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dustin.cex.domains.auth.model.dto.LogoutRequest;
import dustin.cex.domains.auth.model.dto.RefreshTokenRequest;
import dustin.cex.domains.auth.model.dto.RefreshTokenResponse;
import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.auth.model.dto.SigninResponse;
import dustin.cex.domains.auth.model.dto.SignupRequest;
import dustin.cex.domains.auth.model.dto.SignupResponse;
import dustin.cex.domains.auth.model.dto.UserResponse;
import dustin.cex.domains.auth.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/**
 * 인증 컨트롤러
 * Auth Controller
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication API endpoints")
public class AuthController {
    
    private final AuthService authService;
    
    /**
     * 회원가입
     * Signup
     */
    @Operation(
            summary = "회원가입",
            description = "새로운 사용자 계정을 생성합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "User created successfully",
                    content = @Content(schema = @Schema(implementation = SignupResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request (email already exists)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error"
            )
    })
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * 로그인
     * Signin
     */
    @Operation(
            summary = "로그인",
            description = "이메일과 비밀번호로 로그인하여 Access Token과 Refresh Token을 발급받습니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @Content(schema = @Schema(implementation = SigninResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid email or password"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error"
            )
    })
    @PostMapping("/signin")
    public ResponseEntity<SigninResponse> signin(@Valid @RequestBody SigninRequest request) {
        SigninResponse response = authService.signin(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 토큰 갱신
     * Refresh token
     */
    @Operation(
            summary = "토큰 갱신",
            description = "Refresh Token을 사용하여 새로운 Access Token과 Refresh Token을 발급받습니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Token refreshed successfully",
                    content = @Content(schema = @Schema(implementation = RefreshTokenResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or expired refresh token"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error"
            )
    })
    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenResponse response = authService.refreshAccessToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }
    
    /**
     * 로그아웃
     * Logout
     */
    @Operation(
            summary = "로그아웃",
            description = "Refresh Token을 무효화하여 로그아웃합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Logout successful"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid token"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error"
            )
    })
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }
    
    /**
     * 사용자 정보 조회
     * Get current user info
     */
    @Operation(
            summary = "사용자 정보 조회",
            description = "현재 로그인한 사용자의 정보를 조회합니다",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "User info retrieved successfully",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error"
            )
    })
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(HttpServletRequest request) {
        // JWT 필터에서 설정한 사용자 정보 가져오기
        Long userId = (Long) request.getAttribute("userId");
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        UserResponse response = authService.getUserInfo(userId);
        return ResponseEntity.ok(response);
    }
    
}
