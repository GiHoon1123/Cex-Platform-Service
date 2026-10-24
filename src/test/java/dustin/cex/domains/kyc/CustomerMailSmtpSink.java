package dustin.cex.domains.kyc;
import com.icegreen.greenmail.util.*;
import java.nio.file.*;
import java.util.*;

/** 프로세스 검증이 소유하는 SMTPS 수신함이다. 외부 전달 없이 메시지 식별자만 파일로 공개한다. */
public class CustomerMailSmtpSink {
    public static void main(String[] args) throws Exception {
        // 검증 수신함도 SMTP 원문을 디버그 로그에 복사하지 않는다.
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        Path directory=Path.of(args[0]);var server=new GreenMail(new ServerSetup(0,"127.0.0.1",ServerSetup.PROTOCOL_SMTPS));
        server.start();server.setUser("customer@process.test.invalid","sandbox-only");Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Files.writeString(directory.resolve("port"),Integer.toString(server.getSmtps().getPort()));
        var json=new tools.jackson.databind.ObjectMapper();
        while(true){
            // 주소/토큰/메일 본문은 증적 파일에 쓰지 않는다. 임시 파일을 원자적으로 교체해 부분 읽기도 피한다.
            var ids=new ArrayList<String>();for(var m:server.getReceivedMessages())ids.add(m.getMessageID());
            String raw=json.writeValueAsString(ids);
            Files.writeString(directory.resolve("messages.next"),raw);Files.move(directory.resolve("messages.next"),directory.resolve("messages.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            Thread.sleep(50);
        }
    }
}
