package dustin.cex.domains.auth.middleware;

import dustin.cex.domains.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 인증 필터
 * JWT Authentication Filter
 * Rust 엔진의 AuthenticatedUser extractor와 동일한 역할
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private final JwtService jwtService;
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        
        // Swagger UI 및 공개 API는 필터 통과
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        if (path.startsWith("/swagger-ui") || 
            path.startsWith("/swagger-ui.html") ||
            path.startsWith("/api-docs") || 
            path.startsWith("/v3/api-docs") ||
            path.startsWith("/swagger-resources") ||
            path.startsWith("/webjars") ||
            // Auth API 중 인증 불필요한 것들만 제외
            path.startsWith("/api/auth/signup") ||
            path.startsWith("/api/auth/signin") ||
            path.startsWith("/api/auth/refresh") ||
            path.startsWith("/api/auth/logout") ||
            // 정산 API는 인증 불필요 (대량 처리 성능 최적화)
            path.startsWith("/api/settlement")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 인증이 필요한 API 처리
        // Authorization 헤더에서 토큰 추출
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json; charset=UTF-8");
            response.getWriter().write("{\"error\":\"Missing or invalid authorization header\"}");
            response.getWriter().flush();
            return;
        }
        
        String token = authHeader.substring(7); // "Bearer " 제거
        
        try {
            // JWT 토큰 검증
            var claims = jwtService.verifyAccessToken(token);
            
            // 사용자 정보를 Request Attribute에 저장 (Controller에서 사용)
            Long userId = Long.parseLong(claims.getSubject());
            String email = claims.get("email", String.class);
            
            request.setAttribute("userId", userId);
            request.setAttribute("email", email);
            
            // 다음 필터 또는 컨트롤러로 전달
            filterChain.doFilter(request, response);
        } catch (RuntimeException e) {
            // 토큰 검증 실패
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json; charset=UTF-8");
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                response.getWriter().write("{\"error\":\"Invalid or expired token: " + errorMsg + "\"}");
                response.getWriter().flush();
            }
        } catch (Exception e) {
            // 기타 예외
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json; charset=UTF-8");
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                response.getWriter().write("{\"error\":\"Internal server error: " + errorMsg + "\"}");
                response.getWriter().flush();
            }
        }
    }
}
