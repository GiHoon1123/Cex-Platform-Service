package dustin.cex.domains.auth.exception;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 정해진 실패 종류만 상태 코드로 옮기고 입력 비밀번호/토큰은 응답에 넣지 않는다. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String,String>> handleAuthException(AuthException e){
        HttpStatus status=switch(e.kind()){
            case INVALID_SIGNUP->HttpStatus.BAD_REQUEST;
            case INVALID_CREDENTIALS,INVALID_TOKEN->HttpStatus.UNAUTHORIZED;
            case MFA_REQUIRED->HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND->HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of("error",e.getMessage()));
    }
}
