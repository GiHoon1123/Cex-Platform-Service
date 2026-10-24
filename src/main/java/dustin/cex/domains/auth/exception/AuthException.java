package dustin.cex.domains.auth.exception;

/** 인증 실패 종류를 명시한다. 오류 문구의 단어로 HTTP 상태를 추측하지 않는다. */
public class AuthException extends RuntimeException {
    public enum Kind { INVALID_SIGNUP, INVALID_CREDENTIALS, INVALID_TOKEN, MFA_REQUIRED, USER_NOT_FOUND }
    private final Kind kind;
    public AuthException(Kind kind){
        super(switch(kind){case INVALID_SIGNUP->"Invalid signup";case INVALID_CREDENTIALS->"Invalid credentials";case INVALID_TOKEN->"Invalid token";case MFA_REQUIRED->"MFA required";case USER_NOT_FOUND->"User not found";});
        this.kind=kind;
    }
    public Kind kind(){return kind;}
}
