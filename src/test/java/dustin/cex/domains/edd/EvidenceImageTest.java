package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** 인코더가 만드는 정상 표본과 변조한 PNG 헤더로 바이트·픽셀·형식 경계를 검증한다. */
class EvidenceImageTest {
    final EvidenceImage images=new EvidenceImage();
    byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"PNG",out);return out.toByteArray();}
    @Test void boundedReadDoesNotTrustAvailableOrDeclaredLength() {
        var input=new ByteArrayInputStream(new byte[EvidenceImage.MAX_INPUT+100]);assertEquals(413,assertThrows(ResponseStatusException.class,()->images.read(input)).getStatusCode().value());assertEquals(99,input.available());
    }
    @Test void exactInputLimitCanBeReadAndEmptyInputCannot() {assertEquals(EvidenceImage.MAX_INPUT,images.read(new ByteArrayInputStream(new byte[EvidenceImage.MAX_INPUT])).length);assertThrows(ResponseStatusException.class,()->images.read(new ByteArrayInputStream(new byte[0])));}
    @ParameterizedTest @ValueSource(strings={"<svg/>","%PDF-1.7","GIF89a","","<html>자료</html>"})
    void contentTypeCannotMakeForeignBytesIntoPng(String content) {assertEquals(415,assertThrows(ResponseStatusException.class,()->images.render(content.getBytes())).getStatusCode().value());}
    @Test void truncatedPngIsRejectedAndPermitIsReleased() throws Exception {
        byte[] png=png();for(int i=0;i<4;i++)assertThrows(ResponseStatusException.class,()->images.render(Arrays.copyOf(png,28)));assertNotNull(images.render(png));
    }
    @ParameterizedTest @ValueSource(ints={0,4097,100000,2000000000})
    void dimensionsAreCheckedBeforePixelAllocation(int width) throws Exception {
        byte[] bytes=png();ByteBuffer.wrap(bytes,16,4).putInt(width);var crc=new CRC32();crc.update(bytes,12,17);ByteBuffer.wrap(bytes,29,4).putInt((int)crc.getValue());assertThrows(ResponseStatusException.class,()->images.render(bytes));
    }
    @Test void totalPixelsHaveIndependentLimit() throws Exception {
        byte[] bytes=png();ByteBuffer.wrap(bytes,16,8).putInt(3000).putInt(2000);var crc=new CRC32();crc.update(bytes,12,17);ByteBuffer.wrap(bytes,29,4).putInt((int)crc.getValue());assertEquals(413,assertThrows(ResponseStatusException.class,()->images.render(bytes)).getStatusCode().value());
    }
    @Test void trailingBytesAreNotReturnedAsReviewImage() throws Exception {
        byte[] png=png(),raw=Arrays.copyOf(png,png.length+20);Arrays.fill(raw,png.length,raw.length,(byte)65);assertArrayEquals(images.render(png),images.render(raw));assertNotNull(ImageIO.read(new ByteArrayInputStream(images.render(raw))));
    }
}
