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
                .requestMatchers(HttpMethod.POST,"/api/kyc/webhook").permitAll()
                // 개인정보 검토는 지정된 POST 한 경로만 운영자·승인자에게 연다. 감사자의 최소 이력 조회 권한과 구분한다.
                .requestMatchers(HttpMethod.POST,"/api/admin/deposits/BTC/*/origin-transfer/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                // 감사자는 최소 이력만 읽고, 원본·메모 열람과 담당자 의견 작성은 역할별 정확한 경로로 구분한다.
                .requestMatchers(HttpMethod.GET,"/api/admin/deposits/BTC/*/monitoring-assessments","/api/admin/deposits/BTC/*/monitoring-assessments/*/observations").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/deposits/BTC/*/monitoring-assessments/*/inspect","/api/admin/deposits/BTC/*/monitoring-assessments/*/observations/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/deposits/BTC/*/monitoring-assessments/*/observations").access(audited(audit,sessions,"OPERATOR"))
                // 거래 조사 사건은 자동 탐지에서만 생성한다. 현재 운영 역할에는 최소 목록·신호 이력 조회만 허용한다.
                .requestMatchers(HttpMethod.GET,"/api/admin/monitoring-cases","/api/admin/monitoring-cases/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                // 탐지 원본의 명시적 처리 요청도 현재 운영 업무다. 감사자에게 작업 생성 권한을 부여하지 않는다.
                .requestMatchers(HttpMethod.POST,"/api/admin/deposits/BTC/*/monitoring-requests").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/deposits/BTC/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.GET,"/api/customer/deposits/BTC","/api/customer/deposits/BTC/*").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/customer/deposit-addresses/BTC").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/customer/deposit-addresses/BTC").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/customer/email/challenges","/api/customer/email/confirmations").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/customer/email","/api/customer/email/preferences").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.PUT,"/api/customer/email/preferences").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/customer/security/email/options","/api/customer/security/email/requests","/api/customer/security/email/confirmations").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/customer/security/password","/api/customer/security/password/options").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/customer/security/sessions","/api/customer/security/password-changes","/api/customer/security/email-change-history").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.DELETE,"/api/customer/security/sessions/*").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/operations/customer-mail","/api/admin/operations/customer-mail/*","/api/admin/operations/customer-mail/*/attempts").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operations/customer-mail/*/retry-requests").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.GET,"/api/admin/operations/customer-mail-retries/*").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operations/customer-mail-retries/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operations/customer-mail-retries/*/approve/options","/api/admin/operations/customer-mail-retries/*/reject/options","/api/admin/operations/customer-mail-retries/*/approve","/api/admin/operations/customer-mail-retries/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/kyc/session","/api/kyc/sessions/*/reconcile","/api/kyc/customer-profile").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/kyc/session","/api/kyc/customer-profile").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/kyc/customers/*","/api/admin/kyc/customer-profiles/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/wealth-reviews/context").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/wealth-reviews").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/wealth-reviews/*/inspect","/api/admin/edd/wealth-reviews/*/approve/options",
                    "/api/admin/edd/wealth-reviews/*/reject/options","/api/admin/edd/wealth-reviews/*/approve","/api/admin/edd/wealth-reviews/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/wealth-reviews").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/funds-reviews/context").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/funds-reviews").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/funds-reviews/*/inspect","/api/admin/edd/funds-reviews/*/approve/options",
                    "/api/admin/edd/funds-reviews/*/reject/options","/api/admin/edd/funds-reviews/*/approve","/api/admin/edd/funds-reviews/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/funds-reviews").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/evidence/*/address-review/context").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/evidence/*/address-reviews").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/address-reviews/*/inspect","/api/admin/edd/address-reviews/*/approve/options",
                    "/api/admin/edd/address-reviews/*/reject/options","/api/admin/edd/address-reviews/*/approve","/api/admin/edd/address-reviews/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/address-reviews").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/kyc/edd-evidence/requests/*").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/kyc/edd-evidence/requests").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/customers/*/evidence-requests").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/evidence-requests").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/evidence/*/view").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/aml-sources","/api/admin/edd/customers/*/aml-investigations").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/aml-sources/*/inspect","/api/admin/edd/aml-investigations/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/aml-sources/*/investigations").access(audited(audit,sessions,"OPERATOR"))
                // 비공개 보고 검토는 일반 EDD·감사 역할에 포괄적으로 열지 않는다. 실제 배정은 서비스가 검사한다.
                // 전사 점검은 승인 역할의 최소 메타데이터 조회다. 원문과 인계 권한은 기존 개별 검사에 남긴다.
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/exit-supervision","/api/admin/compliance/exit-approval-supervision","/api/admin/compliance/customer-review-supervision","/api/admin/compliance/customer-review-attention").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/reporting-supervision").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/reporting-orphans","/api/admin/compliance/reviews/*/custody","/api/admin/compliance/reviews/*/custody-history","/api/admin/compliance/custody-requests/*").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/custody-requests","/api/admin/compliance/reviews/*/custody-context","/api/admin/compliance/custody-requests/*/history").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/custody-requests/*/inspect","/api/admin/compliance/custody-requests/*/approve/options",
                    "/api/admin/compliance/custody-requests/*/reject/options","/api/admin/compliance/custody-requests/*/approve","/api/admin/compliance/custody-requests/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/reporting-queue","/api/admin/compliance/reviews/*/followups").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/reviews/*/followups","/api/admin/compliance/followups/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                // 철회는 원래 판단의 삭제가 아니라 별도 독립 승인이다. 원문은 지정 인물이 서비스에서 다시 검사한다.
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/exit-rescissions").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/exit-rescissions","/api/admin/compliance/exit-rescissions/*").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/exit-rescissions/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/exit-rescissions/*/approve/options","/api/admin/compliance/exit-rescissions/*/reject/options",
                    "/api/admin/compliance/exit-rescissions/*/approve","/api/admin/compliance/exit-rescissions/*/reject").access(audited(audit,sessions,"APPROVER"))
                // 관계 종료 개시도 별도 목적의 독립 승인이다. 보고 원문 권한과 합치지 않는다.
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/relationship-exits").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/relationship-exits").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/relationship-exits/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/relationship-exits/*/approve/options","/api/admin/compliance/relationship-exits/*/reject/options",
                    "/api/admin/compliance/relationship-exits/*/approve","/api/admin/compliance/relationship-exits/*/reject").access(audited(audit,sessions,"APPROVER"))
                // 고객 업무 인계는 별도 목적의 보안키 승인으로만 확정한다.
                .requestMatchers(HttpMethod.GET,"/api/customer/relationship/review-requests/*/final","/api/customer/relationship/review-notices").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST,"/api/customer/relationship/review-requests/*/final/acknowledgments").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/customer-final-requests/*").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-review-requests/*/final-proposals").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-final-requests/*/inspect","/api/admin/compliance/customer-final-requests/*/approve/options","/api/admin/compliance/customer-final-requests/*/reject/options","/api/admin/compliance/customer-final-requests/*/approve","/api/admin/compliance/customer-final-requests/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/customer-review-work","/api/admin/compliance/customer-review-orphans","/api/admin/compliance/customer-review-requests/*/custody","/api/admin/compliance/customer-custody-requests/*").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-custody-requests").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-custody-requests/*/inspect","/api/admin/compliance/customer-custody-requests/*/approve/options","/api/admin/compliance/customer-custody-requests/*/reject/options","/api/admin/compliance/customer-custody-requests/*/approve","/api/admin/compliance/customer-custody-requests/*/reject").access(audited(audit,sessions,"APPROVER"))
                // 고객 사유는 현재 종료 대상에만 접수하고 원문은 지정 운영 인물에게만 제공한다.
                .requestMatchers(HttpMethod.POST,"/api/customer/relationship/review-requests").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/customer/relationship/review-requests","/api/customer/relationship/review-requests/*","/api/customer/relationship/review-requests/*/replies").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/customer-review-requests/*/context").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/customer-review-requests/*/notes","/api/admin/compliance/customer-review-requests/*/replies").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-review-requests/*/notes","/api/admin/compliance/customer-review-notes/*/inspect","/api/admin/compliance/customer-review-requests/*/replies").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/customer-review-requests/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/customer/relationship").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/reviews").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.GET,"/api/admin/compliance/reviews").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/reviews/*/inspect").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/compliance/reviews/*/approve/options","/api/admin/compliance/reviews/*/reject/options",
                    "/api/admin/compliance/reviews/*/approve","/api/admin/compliance/reviews/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*/aml-resolution").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/customers/*/reports").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/reports/*/inspect").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/reports/*/dossier").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/edd/reports/*/approve/options","/api/admin/edd/reports/*/reject/options",
                    "/api/admin/edd/reports/*/approve","/api/admin/edd/reports/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/edd/customers/*","/api/admin/edd/customers/*/reports").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.GET,"/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/auth/logout").authenticated()
                // 비밀번호 단계의 고객 대기 세션도 서명 검증은 할 수 있다. 역할·실제 등록 자격은 서비스가 확인한다.
                .requestMatchers(HttpMethod.POST,"/api/auth/customer-mfa/register/options","/api/auth/customer-mfa/register",
                    "/api/auth/customer-mfa/authenticate/options","/api/auth/customer-mfa/authenticate").authenticated()
                // 코드 관리는 현재 고객 서명을, 분실 키 복구는 비밀번호 대기 세션과 저장 코드를 서비스에서 요구한다.
                .requestMatchers(HttpMethod.GET,"/api/auth/customer-recovery/status","/api/auth/customer-recovery/changes").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/auth/customer-recovery/code/options","/api/auth/customer-recovery/code").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.POST,"/api/auth/customer-recovery/register/options","/api/auth/customer-recovery/register").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/auth/customer-mfa/credentials","/api/auth/customer-mfa/changes").access(audited(audit,sessions,"CUSTOMER"))
                .requestMatchers(HttpMethod.DELETE,"/api/auth/customer-mfa/credentials/*").access(audited(audit,sessions,"CUSTOMER"))
                // 미검증 운영 세션도 보안키 등록·인증은 할 수 있지만 고객·운영 업무 권한은 없다.
                .requestMatchers(HttpMethod.POST,"/api/auth/operator-mfa/register/options","/api/auth/operator-mfa/register",
                    "/api/auth/operator-mfa/authenticate/options","/api/auth/operator-mfa/authenticate").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/auth/operator-mfa/credentials").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.DELETE,"/api/auth/operator-mfa/credentials/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operator-security/role-requests").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operator-security/role-requests/*/approve/options",
                    "/api/admin/operator-security/role-requests/*/reject/options","/api/admin/operator-security/role-requests/*/approve",
                    "/api/admin/operator-security/role-requests/*/reject").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/operator-security/role-requests","/api/admin/operator-security/role-targets/*")
                    .access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operator-security/recoveries","/api/admin/operator-security/access-reviews").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operator-security/requests/*/approve","/api/admin/operator-security/requests/*/reject","/api/admin/operator-security/requests/*/approve/options","/api/admin/operator-security/requests/*/reject/options").access(audited(audit,sessions,"APPROVER"))
                .requestMatchers(HttpMethod.POST,"/api/admin/operator-security/operators/*/revoke").access(audited(audit,sessions,"OPERATOR","APPROVER"))
                .requestMatchers(HttpMethod.GET,"/api/admin/operator-security/requests","/api/admin/operator-security/operators/*").access(audited(audit,sessions,"OPERATOR","APPROVER","AUDITOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/trading-restrictions").access(audited(audit,sessions,"OPERATOR"))
                .requestMatchers(HttpMethod.POST,"/api/admin/requests/*/approve","/api/admin/requests/*/reject","/api/admin/requests/*/approve/options","/api/admin/requests/*/reject/options").access(audited(audit,sessions,"APPROVER"))
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
