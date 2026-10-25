package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실사 허용 설정과 기술 디렉터리의 현재 일치를 확인한다. 실제 사업자 계약/실사를 수행하는 시험은 아니다. */
class CodeVaspCounterpartyPolicyTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final Instant now=Instant.parse("2026-10-25T00:00:00Z");
    final String publicKey=Base64.getEncoder().encodeToString(new byte[32]);
    String entry(String entity){return JSON.writeValueAsString(JSON.createObjectNode().put("entityId",entity).put("legalName","가상 사업자").put("reviewReference","DD-2026-001").put("evidenceSha256","a".repeat(64)).put("validFrom",now.minusSeconds(10).toString()).put("validUntil",now.plusSeconds(10).toString()));}
    CodeVaspCounterpartyPolicy policy(){return new CodeVaspCounterpartyPolicy("["+entry("peer")+"]");}
    CodeVaspDirectoryClient.Vasp peer(){return new CodeVaspDirectoryClient.Vasp("peer","표시 이름","가상 사업자","KR","CODE",true,List.of(new CodeVaspDirectoryClient.Key(publicKey,now.plusSeconds(60))));}
    CodeVaspDirectoryClient.Directory directory(CodeVaspDirectoryClient.Vasp... peers){return new CodeVaspDirectoryClient.Directory(now,now,List.of(peers));}
    @Test void absentApprovalCannotUseTechnicallyReachableMember(){assertThrows(ResponseStatusException.class,()->new CodeVaspCounterpartyPolicy("[]").select(directory(peer()),"peer",now));}
    @Test void currentExactBusinessAndKeyProduceBoundSelection(){var selection=policy().select(directory(peer()),"peer",now);assertEquals("DD-2026-001",selection.approval().reviewReference());assertEquals(publicKey,selection.key().publicKey());assertEquals(policy().hash(),selection.policyHash());}
    @Test void approvalPeriodHasInclusiveStartAndExclusiveEnd(){var p=policy();assertDoesNotThrow(()->p.select(directory(peer()),"peer",now.minusSeconds(10)));assertThrows(ResponseStatusException.class,()->p.select(directory(peer()),"peer",now.minusSeconds(11)));assertThrows(ResponseStatusException.class,()->p.select(directory(peer()),"peer",now.plusSeconds(10)));}
    @Test void changedNameCountryAllianceHealthOrDuplicateMemberCannotBorrowApproval(){var p=peer();for(var wrong:List.of(new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),"다른 법인",p.country(),p.alliance(),true,p.keys()),new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),p.legalName(),"US",p.alliance(),true,p.keys()),new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),p.legalName(),p.country(),"GTR",true,p.keys()),new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),p.legalName(),p.country(),p.alliance(),false,p.keys())))assertThrows(ResponseStatusException.class,()->policy().select(directory(wrong),"peer",now));assertThrows(ResponseStatusException.class,()->policy().select(directory(p,p),"peer",now));assertThrows(ResponseStatusException.class,()->policy().select(directory(),"peer",now));}
    @Test void expiredOrInvalidKeysCannotBeSelected(){var p=peer();for(var key:List.of(new CodeVaspDirectoryClient.Key(publicKey,now),new CodeVaspDirectoryClient.Key("bad",now.plusSeconds(10))))assertThrows(ResponseStatusException.class,()->policy().select(directory(new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),p.legalName(),p.country(),p.alliance(),true,List.of(key))),"peer",now));}
    @Test void keyChoiceIsIndependentOfSourceArrayOrder(){var p=peer();var a=p.keys().getFirst();var b=new CodeVaspDirectoryClient.Key(Base64.getEncoder().encodeToString("b".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)),now.plusSeconds(120));for(var keys:List.of(List.of(a,b),List.of(b,a)))assertEquals(b,policy().select(directory(new CodeVaspDirectoryClient.Vasp(p.entityId(),p.name(),p.legalName(),p.country(),p.alliance(),true,keys)),"peer",now).key());}
    @Test void sameSemanticPolicyKeepsHashAndChangesInvalidateIt(){String a=entry("peer"),b=entry("other");assertEquals(new CodeVaspCounterpartyPolicy("["+a+","+b+"]").hash(),new CodeVaspCounterpartyPolicy("[ "+b+", "+a+" ]").hash());for(String changed:List.of(a.replace("DD-2026-001","DD-2026-002"),a.replace("a".repeat(64),"b".repeat(64)),a.replace("가상 사업자","변경 사업자")))assertNotEquals(policy().hash(),new CodeVaspCounterpartyPolicy("["+changed+"]").hash());}
    @Test void malformedOrAmbiguousPolicyFailsStartupWithoutEcho(){String a=entry("peer");for(String bad:List.of("{}","["+a+","+a+"]","["+a+"] {}","["+a.replace("\"peer\"","\"peer\",\"entityId\":\"other\"")+"]","["+a.replace("DD-2026-001","비밀 원문")+"]","["+a.replace("a".repeat(64),"bad")+"]","["+a.substring(0,a.length()-1)+",\"extra\":true}]","["+a.replace(now.plusSeconds(10).toString(),now.minusSeconds(10).toString())+"]"," ".repeat(65537))){var failure=assertThrows(IllegalArgumentException.class,()->new CodeVaspCounterpartyPolicy(bad));assertNull(failure.getCause());assertFalse(failure.toString().contains("비밀 원문"));}}
    @Test void policyDescriptionDoesNotEchoOperationalEvidence(){assertFalse(policy().toString().contains("DD-2026-001"));}
}
