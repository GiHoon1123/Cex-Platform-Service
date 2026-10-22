package dustin.cex.domains.auth.middleware;

import dustin.cex.domains.auth.service.JwtService;
import dustin.cex.domains.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증만 수행한다. 경로별 인가는 표준 보안 체인에서 별도로 결정한다. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final SessionService sessions;
    private final AuditService audit;
    public JwtAuthenticationFilter(JwtService jwt,SessionService sessions,AuditService audit) {
        this.jwt=jwt;this.sessions=sessions;this.audit=audit;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException {
        req.setAttribute("requestId",UUID.randomUUID());
        // 토큰과 기기 등록 응답을 브라우저·중간 프록시 캐시에 남기지 않는다.
        if(req.getRequestURI().startsWith("/api/auth/") || req.getRequestURI().startsWith("/api/admin/operator-security/")) res.setHeader("Cache-Control","no-store");
        String header=req.getHeader("Authorization");
        if(header!=null) {
            try {
                if(!header.startsWith("Bearer ") || header.length()>4096) throw new IllegalArgumentException("유효하지 않은 인증");
                var claims=jwt.verifyAccessToken(header.substring(7));
                long userId=Long.parseLong(claims.getSubject()); UUID sid=UUID.fromString(claims.get("sid",String.class));
                var user=sessions.authenticate(userId,sid);
                var auth=UsernamePasswordAuthenticationToken.authenticated(userId,null,
                    List.of(new SimpleGrantedAuthority("ROLE_"+(user.getRole()==dustin.cex.domains.auth.model.entity.UserRole.CUSTOMER || sessions.hasOperatorMfa(sid)
                        ? user.getRole().name() : "MFA_PENDING"))));
                SecurityContextHolder.getContext().setAuthentication(auth);
                req.setAttribute("userId",userId);req.setAttribute("sessionId",sid);
            } catch(io.jsonwebtoken.JwtException | IllegalArgumentException failure) {
                audit.denied(null,"AUTHENTICATION","http","INVALID_CREDENTIALS");
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"unauthorized\"}");return;
            }
        }
        // 컨트롤러 예외를 인증 오류로 바꾸지 않는다. DB 장애도 401로 숨기지 않는다.
        chain.doFilter(req,res);
    }
}
