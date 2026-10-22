package dustin.cex.domains.security;

import dustin.cex.domains.auth.middleware.JwtAuthenticationFilter;
import dustin.cex.domains.auth.service.JwtService;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.*;
import org.springframework.security.web.*;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 로그인 화면이나 리다이렉트 없이 JSON API의 인증·인가만 제공한다. */
@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http,JwtService jwt,SessionService sessions,AuditService audit) throws Exception {
        // 브라우저가 자동 전송하는 쿠키 인증을 사용하지 않는다. Bearer 헤더 전용 API의 설정이다.
        // 쿠키 세션을 도입하는 시점에는 이 조건과 CSRF 정책을 다시 설계해야 한다.
        http.csrf(c->c.disable()).cors(c->{})
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
            .requestCache(c->c.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{
                audit.denied(null,"AUTHENTICATION","http","AUTHENTICATION_REQUIRED");
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"error\":\"unauthorized\"}");
            }).accessDeniedHandler((req,res,ex)->{
                res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"error\":\"forbidden\"}");
            }))
            .addFilterBefore(new JwtAuthenticationFilter(jwt,sessions,audit),UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a->a
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST,"/api/auth/signup","/api/auth/signin","/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/market/**","/ws/market/BTC/USDT").permitAll()
                .requestMatchers("/swagger-ui/**","/swagger-ui.html","/api-docs/**","/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/auth/logout").authenticated()
                // 미검증 운영 세션도 보안키 등록·인증은 할 수 있지만 고객·운영 업무 권한은 없다.
                .requestMatchers(HttpMethod.POST,"/api/auth/operator-mfa/register/options","/api/auth/operator-mfa/register",
                    "/api/auth/operator-mfa/authenticate/options","/api/auth/operator-mfa/authenticate").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/admin/trading-restrictions").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/requests/*/approve","/api/admin/requests/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/requests","/api/admin/audit","/api/admin/customers/*",
                    "/api/settlement/trade/revenue/daily","/api/settlement/trade/dates-with-trades",
                    "/api/settlement/trade/revenue/monthly","/api/settlement/trade/revenue/by-pair",
                    "/api/settlement/trade/validation-status/*","/api/settlement/trade/failures",
                    "/api/settlement/trade/report/daily/*","/api/settlement/trade/report/monthly/*/*",
                    "/api/settlement/trade/report/user/*/*","/api/settlement/trade/details/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.GET,"/api/cex/trades").access(audited(audit,sessions,"AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/cex/orders").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.DELETE,"/api/cex/orders/*").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/cex/orders/my","/api/cex/orders/*","/api/cex/trades/my",
                    "/api/cex/balances","/api/cex/balances/*","/api/cex/positions","/api/cex/positions/*").access(audited(audit,sessions,"CUSTOMER"))
                // 새 엔드포인트는 명시적으로 권한을 정하기 전까지 열리지 않는다.
                .anyRequest().access(audited(audit,sessions)));
        return http.build();
    }
    private AuthorizationManager<RequestAuthorizationContext> audited(AuditService audit,SessionService sessions,String... roles) {
        return (supplier,context)->{
            var auth=supplier.get();
            boolean allowed=auth!=null && auth.isAuthenticated() && java.util.Arrays.stream(roles)
                .anyMatch(role->auth.getAuthorities().stream().anyMatch(g->g.getAuthority().equals("ROLE_"+role)));
            Long actor=auth!=null && auth.getPrincipal() instanceof Long id ? id : null;
            // 운영 변경에는 최근 5분 안의 사용자 확인이 필요하다. 고객 주문에는 이 조건을 적용하지 않는다.
            if(allowed && auth.getAuthorities().stream().noneMatch(g->g.getAuthority().equals("ROLE_CUSTOMER"))
                    && !context.getRequest().getMethod().equals("GET"))
                allowed=context.getRequest().getAttribute("sessionId") instanceof java.util.UUID sid && sessions.hasFreshOperatorMfa(sid);
            String path=context.getRequest().getRequestURI();
            String target=roles.length>0 && path.length()<=150 ? "http:"+path : "unmapped";
            audit.record(actor,"HTTP_AUTHORIZATION",target,allowed?"ALLOWED":"DENIED",context.getRequest().getMethod());
            return new AuthorizationDecision(allowed);
        };
    }
}
