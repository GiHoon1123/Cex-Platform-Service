package dustin.cex.domains.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리기
 * Global Exception Handler
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException e) {
        Map<String, String> error = new HashMap<>();
        error.put("error", e.getMessage());
        
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = e.getMessage().toLowerCase();
        
        if (message.contains("invalid") || message.contains("expired") || message.contains("token")) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (message.contains("not found")) {
            status = HttpStatus.NOT_FOUND;
        } else if (message.contains("already exists")) {
            status = HttpStatus.BAD_REQUEST;
        }
        
        return ResponseEntity.status(status).body(error);
    }
}
