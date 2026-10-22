package dustin.cex.domains.auth.service;

import de.mkammerer.argon2.Argon2Factory;
import org.springframework.stereotype.Service;

/** 설치된 Argon2 구현을 로그인과 민감 작업 재인증에 함께 사용한다. */
@Service
public class PasswordService {
    public String hash(String password) {
        char[] chars=password.toCharArray();
        try { return Argon2Factory.create().hash(10,65536,1,chars); }
        finally { java.util.Arrays.fill(chars,'\0'); }
    }
    public boolean verify(String password,String hash) {
        if(password==null || password.length()>256) return false;
        char[] chars=password.toCharArray();
        try { return Argon2Factory.create().verify(hash,chars); }
        catch(IllegalArgumentException failure) { return false; }
        finally { java.util.Arrays.fill(chars,'\0'); }
    }
}
