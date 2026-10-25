package dustin.cex.domains.travelrule;

import static dustin.cex.domains.travelrule.CodeVaspTransport.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import org.springframework.stereotype.Component;

/** 검증된 전송 결과를 기술 사업자 목록으로 해석한다. 목록 포함을 법적/금융 승인으로 바꾸지 않는다. */
@Component
public class CodeVaspDirectoryClient {
    public record Key(String publicKey,Instant expiresAt){}
    public record Vasp(String entityId,String name,String legalName,String country,String alliance,boolean reachable,List<Key> keys){public Vasp{keys=List.copyOf(keys);}}
    public record Directory(Instant readStartedAt,Instant readFinishedAt,List<Vasp> vasps){public Directory{vasps=List.copyOf(vasps);}}
    // 공식 유효기한은 UTC이며 문서 예제에는 시차 표기가 없다. JVM의 로컬 시간대를 추측해 사용하지 않는다.
    private static final DateTimeFormatter KEY_EXPIRY=new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .optionalStart().appendOffsetId().optionalEnd().parseDefaulting(ChronoField.OFFSET_SECONDS,0).toFormatter();
    private final CodeVaspTransport transport;
    public CodeVaspDirectoryClient(CodeVaspTransport transport){this.transport=transport;}
    public Directory read(){
        Instant started=Instant.now();
        var response=transport.directory();
        try{
            var rows=response.path("vasps");if(!rows.isArray()||rows.size()>5000)throw unavailable();
            var vasps=new ArrayList<Vasp>();var ids=new HashSet<String>();
            for(var row:rows){
                String id=text(row,"vaspEntityId",128),name=text(row,"vaspName",128),legal=text(row,"vaspLegalName",256),country=text(row,"countryOfRegistration",2),alliance=text(row,"allianceName",64),health=text(row,"health",4);
                if(!ids.add(id)||!country.matches("[A-Z]{2}")||!(health.equals("up")||health.equals("down")))throw unavailable();
                var sourceKeys=row.path("pubkeys");if(!sourceKeys.isArray()||sourceKeys.size()>100)throw unavailable();
                var keys=new ArrayList<Key>();var seen=new HashSet<String>();
                for(var entry:sourceKeys){String value=text(entry,"pubkey",44);if(!CodeVaspSettings.key(value,32)||!seen.add(value))throw unavailable();keys.add(new Key(value,keyExpiry(text(entry,"expiresAt",64))));}
                // 중단 사업자/기한 지난 키도 원문 상태대로 유지한다. 조회에서 조용히 제거하거나 승인으로 변환하지 않는다.
                vasps.add(new Vasp(id,name,legal,country,alliance,health.equals("up"),keys));
            }
            return new Directory(started,Instant.now(),vasps);
        }catch(RuntimeException failure){throw unavailable();}
    }
    private static Instant keyExpiry(String value){var time=OffsetDateTime.parse(value,KEY_EXPIRY);if(!time.getOffset().equals(ZoneOffset.UTC))throw unavailable();return time.toInstant();}
}
