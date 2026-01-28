package dustin.cex.domains.auth.service;

import java.time.LocalDateTime;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.exception.AuthException;
import dustin.cex.domains.auth.model.dto.RefreshTokenRequest;
import dustin.cex.domains.auth.model.dto.RefreshTokenResponse;
import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.auth.model.dto.SigninResponse;
import dustin.cex.domains.auth.model.dto.SignupRequest;
import dustin.cex.domains.auth.model.dto.SignupResponse;
import dustin.cex.domains.auth.model.dto.UserResponse;
import dustin.cex.domains.auth.model.entity.RefreshToken;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.RefreshTokenRepository;
import dustin.cex.domains.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 인증 서비스
 * Auth Service - handles authentication business logic
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    
    private static final Argon2 ARGON2 = Argon2Factory.create();
    private static final int REFRESH_TOKEN_EXPIRATION_DAYS = 7;
    
    /**
     * 회원가입
     * Signup
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // 이메일 중복 확인
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthException("Email already exists: " + request.getEmail());
        }
        
        // 비밀번호 해싱
        String passwordHash = hashPassword(request.getPassword());
        
        // 사용자 생성
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordHash)
                .username(request.getUsername())
                .build();
        
        user = userRepository.save(user);
        
        // 응답 생성
        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
        
        return SignupResponse.builder()
                .user(userResponse)
                .message("User created successfully")
                .build();
    }
    
    /**
     * 로그인
     * Signin
     */
    @Transactional
    public SigninResponse signin(SigninRequest request) {
        // 사용자 조회
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AuthException("Invalid email or password"));
        
        // 비밀번호 검증
        if (!verifyPassword(request.getPassword(), user.getPasswordHash())) {
            throw new AuthException("Invalid email or password");
        }
        
        // 이전 Refresh Token들 무효화
        refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
        
        // 새 Refresh Token 생성 및 저장
        String refreshToken = createRefreshToken(user.getId());
        
        // Access Token 생성
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        
        // 응답 생성
        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
        
        return SigninResponse.builder()
                .user(userResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .message("Login successful")
                .build();
    }
    
    /**
     * Refresh Token 생성 및 DB 저장
     * Create and store refresh token
     */
    @Transactional
    public String createRefreshToken(Long userId) {
        // Refresh Token 생성
        String refreshToken = jwtService.generateRefreshToken();
        
        // 토큰 해싱
        String tokenHash = jwtService.hashRefreshToken(refreshToken);
        
        // 만료 시간 설정 (7일)
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(REFRESH_TOKEN_EXPIRATION_DAYS);
        
        // DB에 저장
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .revoked(false)
                .build();
        
        refreshTokenRepository.save(token);
        
        // 원본 토큰 반환 (해싱 전)
        return refreshToken;
    }
    
    /**
     * Refresh Token 검증 및 새 Access Token 발급
     * Verify refresh token and issue new access token
     */
    @Transactional
    public RefreshTokenResponse refreshAccessToken(String refreshToken) {
        // Refresh Token 해싱
        String tokenHash = jwtService.hashRefreshToken(refreshToken);
        
        // DB에서 조회
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException("Invalid or expired refresh token"));
        
        // 토큰 유효성 검증
        if (storedToken.getRevoked()) {
            throw new AuthException("Invalid or expired refresh token");
        }
        
        if (storedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException("Invalid or expired refresh token");
        }
        
        // 사용자 정보 조회
        User user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new AuthException("User not found"));
        
        // 새 Access Token 생성
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        
        // 기존 Refresh Token 무효화
        storedToken.setRevoked(true);
        storedToken.setUpdatedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedToken);
        
        // 새 Refresh Token 생성 (Rotation - 보안 강화)
        String newRefreshToken = createRefreshToken(user.getId());
        
        return RefreshTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken)
                .message("Token refreshed successfully")
                .build();
    }
    
    /**
     * 로그아웃 - Refresh Token 무효화
     * Logout - Revoke refresh token
     */
    @Transactional
    public void logout(String refreshToken) {
        // Refresh Token 해싱
        String tokenHash = jwtService.hashRefreshToken(refreshToken);
        
        // 토큰 무효화
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException("Invalid token"));
        
        storedToken.setRevoked(true);
        storedToken.setUpdatedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedToken);
    }
    
    /**
     * 사용자의 모든 Refresh Token 무효화 (모든 기기에서 로그아웃)
     * Revoke all refresh tokens for user (logout from all devices)
     */
    @Transactional
    public void logoutAllDevices(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
    }
    
    /**
     * 사용자 정보 조회
     * Get user info
     */
    public UserResponse getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
    
    /**
     * 비밀번호 해싱
     * Hash password
     */
    private String hashPassword(String password) {
        try {
            return ARGON2.hash(10, 65536, 1, password.toCharArray());
        } catch (Exception e) {
            throw new AuthException("Failed to hash password: " + e.getMessage());
        }
    }
    
    /**
     * 비밀번호 검증
     * Verify password
     */
    private boolean verifyPassword(String password, String passwordHash) {
        try {
            return ARGON2.verify(passwordHash, password.toCharArray());
        } catch (Exception e) {
            return false;
        }
    }
}
