package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CustomerDeclaration;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * 공급사의 오탐 표시만으로 거래를 허용하지 않는다. 이 클래스는 종결에 필요한 자료의 좁은 형식 조건만 검사한다.
 * 모든 후보 보존·현재 조사·독립 승인·현재 신원과 기한은 호출 서비스가 별도로 확인한다.
 */
public final class AmlFalsePositiveRules {
    private AmlFalsePositiveRules() {}
    public record Subject(String name,LocalDate birth,String country) {
        @Override public String toString() {return "Subject[개인정보 생략]";}
    }
    public record Key(String node,String hit) {}
    public record Candidate(Key key,int screening) {}
    public record Check(String failure,Subject subject,List<Candidate> candidates) {
        public Check {candidates=List.copyOf(candidates);}
        public boolean valid() {return failure==null;}
        @Override public String toString() {return "Check[개인정보 생략]";}
    }
    private static Check denied(String reason) {return new Check(reason,null,List.of());}
    public static Check inspect(JsonNode report,boolean resolved) {
        try {
            var identities=report.path("identity");if(!identities.isArray()||identities.isEmpty())return denied("IDENTITY_INCOMPLETE");
            Subject subject=null;
            for(var identity:identities) {
                if(!"Approved".equals(identity.path("status").asText()))return denied("IDENTITY_INCOMPLETE");
                var value=subject(identity,false);if(subject!=null&&!subject.equals(value))return denied("IDENTITY_INCOMPLETE");subject=value;
            }
            var nodes=report.path("screenings");if(!nodes.isArray()||nodes.isEmpty())return denied("SCREENING_INCOMPLETE");
            var nodeIds=new HashSet<String>();var keys=new HashSet<Key>();var candidates=new ArrayList<Candidate>();int index=0;
            for(var node:nodes) {
                String id=node.path("node_id").asText();
                if(!node.path("node_id").isString()||id.isBlank()||id.length()>150||!nodeIds.add(id))return denied("SCREENING_INCOMPLETE");
                if(!"person".equals(node.path("entity_type").asText())||!subject.equals(subject(node.path("screened_data"),true)))return denied("SCREENED_PERSON_MISMATCH");
                var hits=node.path("hits");var warnings=node.path("warnings");var count=node.path("total_hits");var score=node.path("score");
                if(!hits.isArray()||!count.isIntegralNumber()||!count.canConvertToInt()||count.intValue()!=hits.size()
                    ||!warnings.isArray()||!warnings.isEmpty()||!score.isNumber()||score.decimalValue().signum()<0||score.decimalValue().compareTo(java.math.BigDecimal.valueOf(100))>0)
                    return denied("SCREENING_INCOMPLETE");
                if(!Set.of("Approved","In Review","Declined").contains(node.path("status").asText()))return denied("SCREENING_INCOMPLETE");
                if(resolved&&(!"Approved".equals(node.path("status").asText())||score.decimalValue().signum()!=0))return denied("PROVIDER_NOT_CLEARED");
                for(var hit:hits) {
                    String hitId=hit.path("id").asText();var key=new Key(id,hitId);
                    if(!hit.path("id").isString()||hitId.isBlank()||hitId.length()>300||!keys.add(key))return denied("CANDIDATE_AMBIGUOUS");
                    if("Confirmed Match".equals(hit.path("review_status").asText()))return denied("CONFIRMED_MATCH_REQUIRES_ESCALATION");
                    if(resolved) {
                        if(!"False Positive".equals(hit.path("review_status").asText()))return denied("PROVIDER_NOT_CLEARED");
                        // 연도만 있거나 날짜가 누락되면 다른 생일이라고 추측하지 않는다. 실제 완전한 날짜가 모두 달라야 한다.
                        var dates=hit.path("properties").path("birthDate");if(!dates.isArray()||dates.isEmpty())return denied("BIRTH_DATE_NOT_DISTINCT");
                        for(var date:dates) {
                            if(!date.isString()||!date.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return denied("BIRTH_DATE_NOT_DISTINCT");
                            var birth=LocalDate.parse(date.asText());
                            if(birth.equals(subject.birth())||birth.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)))return denied("BIRTH_DATE_NOT_DISTINCT");
                        }
                    }
                    candidates.add(new Candidate(key,index));
                }
                index++;
            }
            if(candidates.isEmpty())return denied("CANDIDATE_NOT_PRESERVED");
            return new Check(null,subject,candidates);
        } catch(RuntimeException e) {return denied("SCREENING_INCOMPLETE");}
    }
    private static Subject subject(JsonNode value,boolean alpha2) {
        if(!value.path("full_name").isString()||!value.path("date_of_birth").isString()||!value.path("nationality").isString())throw new IllegalArgumentException();
        String country=value.path("nationality").asText();
        if(alpha2) {if(!Arrays.asList(Locale.getISOCountries()).contains(country))throw new IllegalArgumentException();country=new Locale.Builder().setRegion(country).build().getISO3Country();}
        return new Subject(CustomerDeclaration.name(value.path("full_name").asText()),LocalDate.parse(value.path("date_of_birth").asText()),CustomerDeclaration.country(country));
    }
}
