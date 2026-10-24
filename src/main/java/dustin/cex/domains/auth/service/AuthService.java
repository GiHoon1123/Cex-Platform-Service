package dustin.cex.domains.auth.service;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.auth.exception.AuthException;
import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.security.AuditService;

/** 세션 교체·회수와 성공 감사는 함께 커밋한다. 거부 감사는 업무 롤백 후 별도로 기록한다. */
@Service
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final JwtService jwt;
    private final PasswordService passwords;
    private final CustomerPasswordPolicy policy;
    private final AuditService audit;
    private final dustin.cex.domains.security.CustomerMfaPolicy mfa;
    private final TransactionTemplate tx;
    public AuthService(UserRepository users,RefreshTokenRepository tokens,JwtService jwt,PasswordService passwords,
            AuditService audit,PlatformTransactionManager manager,CustomerPasswordPolicy policy,dustin.cex.domains.security.CustomerMfaPolicy mfa) {
        this.policy=policy;this.mfa=mfa;
        this.users=users;this.tokens=tokens;this.jwt=jwt;this.passwords=passwords;this.audit=audit;
        tx=new TransactionTemplate(manager);
    }
    public SignupResponse signup(SignupRequest request) {
        return attempt(null,"SIGNUP",()->tx.execute(status->{
            if(!policy.accepts(request.getPassword()))throw new AuthException(AuthException.Kind.INVALID_SIGNUP);
            if(users.existsByEmail(request.getEmail())) throw new AuthException(AuthException.Kind.INVALID_SIGNUP);
            var user=users.saveAndFlush(User.builder().email(request.getEmail())
                .passwordHash(passwords.hash(request.getPassword())).username(request.getUsername()).build());
            audit.record(user.getId(),"SIGNUP","user:"+user.getId(),"SUCCESS","CUSTOMER");
            return SignupResponse.builder().user(response(user)).message("User created successfully").build();
        }));
    }
    public SigninResponse signin(SigninRequest request) {
        var result=tx.execute(status->{
            // 사용자 잠금 아래 실패 횟수를 확인하고 기록하여 동시 시도의 한도 우회를 막는다.
            var user=users.lockByEmail(request.getEmail()).orElse(null);
            if(user==null) {
                audit.record(null,"SIGNIN","authentication","DENIED","INVALID_CREDENTIALS");return null;
            }
            String target="user:"+user.getId();
            if(!user.isAuthenticationAllowed() || audit.tooManyFailures(user.getId(),"SIGNIN",target)) {
                audit.record(user.getId(),"SIGNIN",target,"DENIED","ACCOUNT_UNAVAILABLE");return null;
            }
            if(!passwords.verify(request.getPassword(),user.getPasswordHash())) {
                audit.record(user.getId(),"SIGNIN",target,"DENIED","WRONG_PASSWORD");return null;
            }
            boolean customerMfa=user.getRole()==UserRole.CUSTOMER&&mfa.required(user.getId());
            // 고객은 비밀번호만 아는 사람의 재로그인으로 기존 강한 세션이 회수되지 않아야 한다.
            // 대기는 하나만 남기고 실제 패스키 성공 시 CustomerMfaService가 전체 세션을 교체한다.
            if(customerMfa)tokens.revokePendingByUserId(user.getId(),LocalDateTime.now());
            else tokens.revokeAllByUserId(user.getId(),LocalDateTime.now());
            var pair=create(user,null,null);
            audit.record(user.getId(),"SIGNIN",target,"SUCCESS",customerMfa?"MFA_PENDING_CREATED":"SESSION_REPLACED");
            boolean requiresMfa=user.getRole()!=UserRole.CUSTOMER||customerMfa;
            return SigninResponse.builder().user(response(user)).accessToken(pair.access()).refreshToken(pair.refresh())
                .mfaRequired(requiresMfa).message(requiresMfa?"MFA_REQUIRED":"Login successful").build();
        });
        // 거부 감사까지 커밋된 다음 예외로 바꾼다. 예외로 감사 기록까지 롤백하지 않는다.
        if(result==null) throw new AuthException(AuthException.Kind.INVALID_CREDENTIALS);return result;
    }
    public RefreshTokenResponse refreshAccessToken(String raw) {
        return attempt(null,"REFRESH",()->tx.execute(status->{
            String hash=jwt.hashRefreshToken(raw);
            var owner=tokens.findOwnerByHash(hash).orElseThrow(()->new AuthException(AuthException.Kind.INVALID_TOKEN));
            var user=users.lockById(owner).orElseThrow(()->new AuthException(AuthException.Kind.INVALID_TOKEN));
            var token=tokens.lockByHash(hash).orElseThrow(()->new AuthException(AuthException.Kind.INVALID_TOKEN));
            if(!user.isAuthenticationAllowed() || token.getRevoked() || !token.getExpiresAt().isAfter(LocalDateTime.now()))
                throw new AuthException(AuthException.Kind.INVALID_TOKEN);
            // 동일 갱신 토큰의 동시 사용은 행 잠금 뒤 재검사하여 하나만 성공시킨다.
            token.setRevoked(true); tokens.saveAndFlush(token);
            // 갱신은 최초 서명 시각을 연장하지 않는다. 등록된 고객과 운영자의 대기 세션은 갱신할 수 없다.
            if((user.getRole()!=UserRole.CUSTOMER||mfa.required(user.getId())) && (!token.hasVerifiedMfa()
                    || !token.getMfaVerifiedAt().plusHours(12).isAfter(LocalDateTime.now())))
                throw new AuthException(AuthException.Kind.MFA_REQUIRED);
            if(user.getRole()==UserRole.CUSTOMER&&!mfa.satisfied(user.getId(),token))throw new AuthException(AuthException.Kind.MFA_REQUIRED);
            var pair=create(user,token.getMfaVerifiedAt(),token.getMfaCredentialFingerprint());
            audit.record(user.getId(),"REFRESH","user:"+user.getId(),"SUCCESS","SESSION_ROTATED");
            return RefreshTokenResponse.builder().accessToken(pair.access()).refreshToken(pair.refresh())
                .message("Token refreshed successfully").build();
        }));
    }
    public void logout(long userId,UUID sessionId,String raw) {
        attempt(userId,"LOGOUT",()->tx.execute(status->{
            users.lockById(userId).orElseThrow(()->new AuthException(AuthException.Kind.INVALID_TOKEN));
            var token=tokens.lockByHash(jwt.hashRefreshToken(raw)).orElseThrow(()->new AuthException(AuthException.Kind.INVALID_TOKEN));
            if(token.getUserId()!=userId || !token.getSessionId().equals(sessionId) || token.getRevoked())
                throw new AuthException(AuthException.Kind.INVALID_TOKEN);
            token.setRevoked(true);tokens.save(token);
            audit.record(userId,"LOGOUT","user:"+userId,"SUCCESS","SESSION_REVOKED");
            return null;
        }));
    }
    private record Pair(String access,String refresh) {}
    private Pair create(User user,LocalDateTime verifiedAt,String fingerprint) {
        long userId=user.getId();
        // 서명이 필요한 계정은 비밀번호 단계에 5분 세션만 받는다. 최초 등록 전 고객은 기존 기본 수명이다.
        LocalDateTime expires=user.getRole()==UserRole.CUSTOMER&&!mfa.required(userId) ? LocalDateTime.now().plusDays(7)
            : verifiedAt==null ? LocalDateTime.now().plusMinutes(5) : verifiedAt.plusHours(12);
        String raw=jwt.generateRefreshToken();
        var token=tokens.save(RefreshToken.builder().userId(userId).tokenHash(jwt.hashRefreshToken(raw))
            .mfaVerifiedAt(verifiedAt).mfaCredentialFingerprint(fingerprint).expiresAt(expires).build());
        return new Pair(jwt.generateAccessToken(userId,token.getSessionId()),raw);
    }
    private <T> T attempt(Long actor,String action,java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch(AuthException denied) { audit.denied(actor,action,"authentication","INVALID_CREDENTIALS");throw denied; }
    }
    public UserResponse getUserInfo(Long userId) {
        return response(users.findById(userId).orElseThrow(()->new AuthException(AuthException.Kind.USER_NOT_FOUND)));
    }
    private UserResponse response(User user) {
        return UserResponse.builder().id(user.getId()).email(user.getEmail()).username(user.getUsername())
            .createdAt(user.getCreatedAt()).updatedAt(user.getUpdatedAt()).build();
    }
}
