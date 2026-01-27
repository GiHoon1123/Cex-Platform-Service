package dustin.cex.domains.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 서비스
 * JWT Service for token generation and verification
 */
@Service
public class JwtService {
    
    private final SecretKey secretKey;
    private final SecureRandom secureRandom;
    
    public JwtService(@Value("${jwt.secret:cex-engine-jwt-secret-key-2024-production}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * Access Token 발급 (1시간 만료)
     * Generate Access Token (1 hour expiration)
     */
    public String generateAccessToken(Long userId, String email) {
        Instant now = Instant.now();
        Instant expiration = now.plus(1, ChronoUnit.HOURS);
        
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }
    
    /**
     * Refresh Token 생성 (랜덤 문자열)
     * Generate Refresh Token (random string)
     */
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
    
    /**
     * Refresh Token 해싱 (DB 저장용)
     * Hash Refresh Token (for database storage)
     */
    public String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash refresh token", e);
        }
    }
    
    /**
     * Access Token 검증 및 Claims 추출
     * Verify Access Token and extract Claims
     */
    public Claims verifyAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new RuntimeException("Invalid or expired token", e);
        }
    }
    
    /**
     * Access Token에서 사용자 ID 추출
     * Extract user ID from Access Token
     */
    public Long extractUserId(String token) {
        Claims claims = verifyAccessToken(token);
        return Long.parseLong(claims.getSubject());
    }
}
