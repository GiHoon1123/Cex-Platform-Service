package dustin.cex.domains.auth.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** 서명·발급자·수신자·용도를 검증한 뒤에도 영속 세션과 현재 권한을 별도로 확인한다. */
@Service
public class JwtService {
    private final SecretKey key;
    private final SecureRandom random=new SecureRandom();
    public JwtService(@Value("${jwt.secret}") String secret) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalArgumentException("JWT 비밀키는 32바이트 이상이어야 합니다");
        key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public String generateAccessToken(Long userId,UUID sessionId) {
        Instant now=Instant.now();
        return Jwts.builder().issuer("cex-platform").audience().add("cex-api").and()
            .subject(userId.toString()).claim("type","access").claim("sid",sessionId.toString())
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(900)))
            .signWith(key,Jwts.SIG.HS256).compact();
    }
    public Claims verifyAccessToken(String token) {
        var signed=Jwts.parser().verifyWith(key)
            .requireIssuer("cex-platform").requireAudience("cex-api").require("type","access")
            .build().parseSignedClaims(token);
        if(!"HS256".equals(signed.getHeader().getAlgorithm())) throw new IllegalArgumentException("허용하지 않는 서명 알고리즘");
        var claims=signed.getPayload();
        if(claims.getExpiration()==null || claims.getIssuedAt()==null
                || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(900))
                || claims.getIssuedAt().toInstant().isAfter(Instant.now()))
            throw new IllegalArgumentException("잘못된 토큰 수명");
        if(Long.parseLong(claims.getSubject())<=0) throw new IllegalArgumentException("잘못된 사용자");
        UUID.fromString(claims.get("sid",String.class));
        return claims;
    }
    public String generateRefreshToken() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String hashRefreshToken(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
