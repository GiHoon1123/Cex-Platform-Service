package dustin.cex.domains.auth.exception;

/**
 * 인증 관련 예외
 * Authentication-related exception
 */
public class AuthException extends RuntimeException {
    
    public AuthException(String message) {
        super(message);
    }
    
    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
