package dustin.cex.domains.edd;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** PNG 한 장만 받는다. 원본은 격리 보관하고 운영자에게는 새 픽셀로 다시 만든 열람본만 반환한다. */
@Component
public class EvidenceImage {
    public static final int MAX_INPUT=1024*1024,MAX_VIEW=4*1024*1024;
    private final Semaphore decoders=new Semaphore(2);
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    /** 길이를 미리 보내지 않은 요청도 상한보다 한 바이트만 더 읽고 거부한다. 임시 파일을 만들지 않는다. */
    public byte[] read(InputStream input) {
        try {byte[] bytes=input.readNBytes(MAX_INPUT+1);if(bytes.length==0 || bytes.length>MAX_INPUT) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);return bytes;}
        catch(IOException e) {throw denied(HttpStatus.BAD_REQUEST);}
    }
    public byte[] render(byte[] original) {
        // 체결 코어와 무관한 플랫폼의 이미지 작업만 제한한다. 포화되면 기다리지 않고 재시도를 요구한다.
        if(!decoders.tryAcquire()) throw denied(HttpStatus.TOO_MANY_REQUESTS);
        try {return decode(original);} finally {decoders.release();}
    }
    private byte[] decode(byte[] original) {
        if(original.length>MAX_INPUT || original.length<PNG.length || !Arrays.equals(PNG,Arrays.copyOf(original,PNG.length))) throw denied(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // 메모리 전용 입력은 ImageIO의 기본 디스크 캐시 경로로 고객 원문을 흘리지 않는다.
        var readers=ImageIO.getImageReadersByFormatName("PNG");var reader=readers.next();
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
            // 헤더의 크기를 픽셀 할당 전에 검사한다. 압축 파일 크기만 제한하면 작은 이미지 폭탄을 놓친다.
            if(width<1 || height<1 || width>4096 || height>4096 || (long)width*height>4_000_000) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);
            var decoded=reader.read(0);var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            var graphics=clean.createGraphics();try {graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,width,height);graphics.drawImage(decoded,0,0,null);} finally {graphics.dispose();decoded.flush();}
            // 새 이미지에는 원본 메타데이터·파일 뒤 추가 바이트를 넘기지 않는다. 악성 코드 검사 완료를 뜻하지 않는다.
            var output=new LimitedOutput();try(var memory=new MemoryCacheImageOutputStream(output)) {
                if(!ImageIO.write(clean,"PNG",memory)) throw denied(HttpStatus.BAD_REQUEST);
                memory.flush();return output.toByteArray();
            } finally {clean.flush();}
        } catch(IOException|IllegalArgumentException e) {throw denied(HttpStatus.BAD_REQUEST);} finally {reader.dispose();}
    }
    /** 출력 압축률이 낮아도 정해진 열람본 상한에서 중단해 메모리가 계속 커지지 않게 한다. */
    private static class LimitedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] b,int off,int len) {if((long)count+len>MAX_VIEW) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);super.write(b,off,len);}
        @Override public synchronized void write(int b) {if(count>=MAX_VIEW) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);super.write(b);}
    }
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"크기 제한 안의 정상 PNG 한 장이 필요합니다");}
}
