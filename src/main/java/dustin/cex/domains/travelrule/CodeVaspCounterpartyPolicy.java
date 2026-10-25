package dustin.cex.domains.travelrule;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 기술 회원 목록과 별도로 운영에서 검토한 정보 제공 상대만 허용한다. 설정은 실사·계약·이중 검토의 증명 자체가 아니다. */
@Component
public class CodeVaspCounterpartyPolicy {
    // 공개 설정에는 개인키·고객 원문·실사 문서 자체를 넣지 않는다. 참조와 지문으로 승인된 운영 증적을 연결한다.
    public record Approval(String entityId,String legalName,String reviewReference,String evidenceSha256,Instant validFrom,Instant validUntil){}
    public record Selection(String policyHash,Approval approval,CodeVaspDirectoryClient.Vasp peer,CodeVaspDirectoryClient.Key key){}
    private final Map<String,Approval> approvals;
    private final String policyHash;
    public CodeVaspCounterpartyPolicy(@Value("${codevasp.approved-counterparties:[]}") String document){
        try{
            if(document==null||document.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException();
            var json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(1024).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var list=json.readTree(document);if(!list.isArray()||list.size()>1000)throw new IllegalArgumentException();
            var parsed=new TreeMap<String,Approval>();
            for(var item:list){
                if(!item.isObject()||item.size()!=6)throw new IllegalArgumentException();
                for(String field:item.propertyNames())if(!Set.of("entityId","legalName","reviewReference","evidenceSha256","validFrom","validUntil").contains(field))throw new IllegalArgumentException();
                String entity=field(item,"entityId",128),name=field(item,"legalName",256),reference=field(item,"reviewReference",128),hash=field(item,"evidenceSha256",64);
                if(!entity.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!reference.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
                Instant from=Instant.parse(field(item,"validFrom",40)),until=Instant.parse(field(item,"validUntil",40));
                if(!until.isAfter(from))throw new IllegalArgumentException();
                if(parsed.put(entity,new Approval(entity,name,reference,hash,from,until))!=null)throw new IllegalArgumentException();
            }
            // 입력 순서/공백이 달라도 같은 의미면 같은 정책 지문이다. 이름·기간·증적 참조 변경은 새 지문이 된다.
            approvals=Map.copyOf(parsed);String canonical=json.writeValueAsString(new ArrayList<>(parsed.values()));
            policyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception invalid){throw new IllegalArgumentException("이전거래 상대 허용 설정의 형식과 증적·유효기간을 확인하세요");}
    }
    /** 현재 디렉터리의 정확한 사업자 법인명과 현재 유효한 키를 선택한다. 과거 허용·현재 키를 서로 대신 쓰지 않는다. */
    public Selection select(CodeVaspDirectoryClient.Directory directory,String entityId){return select(directory,entityId,Instant.now());}
    Selection select(CodeVaspDirectoryClient.Directory directory,String entityId,Instant now){
        var approval=approvals.get(entityId);
        if(approval==null||now.isBefore(approval.validFrom())||!now.isBefore(approval.validUntil())||directory==null)throw held();
        var peers=directory.vasps().stream().filter(p->entityId.equals(p.entityId())).toList();if(peers.size()!=1)throw held();var peer=peers.getFirst();
        if(!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())||!peer.reachable()||!approval.legalName().equals(peer.legalName()))throw held();
        // 동시에 유효한 키가 여러 개면 만료가 늦은 키, 같은 만료면 공개키 문자열 순서로 결정해 실행 재현성을 확보한다.
        var key=peer.keys().stream().filter(k->k.expiresAt()!=null&&k.expiresAt().isAfter(now)&&CodeVaspSettings.key(k.publicKey(),32))
            .max(Comparator.comparing(CodeVaspDirectoryClient.Key::expiresAt).thenComparing(CodeVaspDirectoryClient.Key::publicKey)).orElseThrow(CodeVaspCounterpartyPolicy::held);
        return new Selection(policyHash,approval,peer,key);
    }
    public String hash(){return policyHash;}
    private static String field(JsonNode item,String field,int max){var node=item.path(field);if(!node.isString())throw new IllegalArgumentException();String value=node.asString();if(value.isBlank()||value.length()>max||!value.equals(value.strip())||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw new IllegalArgumentException();return value;}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"현재 정보 제공 상대의 허용 근거를 확인할 수 없습니다");}
    @Override public String toString(){return "CodeVaspCounterpartyPolicy[설정 원문 생략]";}
}
