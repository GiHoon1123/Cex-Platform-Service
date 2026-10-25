package dustin.cex.domains.travelrule;

import static dustin.cex.domains.travelrule.CodeVaspTransport.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 국내 CODE 개인 수취의 정보 교환 계약만 해석한다. 정보 제공 상대 허용은 전송 전에 검사하며 현재 디렉터리·고객 KYC·금융 허가는 호출 업무가 별도 검증한다. */
@Component
public class CodeVaspTransferDataClient {
    // 호출 업무가 확정한 공개 거래 원본·수취인 이름·현재 상대 키를 하나로 묶는다. 생성자 검사는 법적 실사나 고객 승인을 대신하지 않는다.
    public record Request(CodeVaspTransactionClient.Request query,BigDecimal expectedAddressAmount,String recipientName,
            CodeVaspDirectoryClient.Vasp peer,CodeVaspDirectoryClient.Key key){
        public Request{
            if(query==null||expectedAddressAmount==null||expectedAddressAmount.signum()<=0||expectedAddressAmount.compareTo(new BigDecimal("21000000"))>0
                ||recipientName==null||recipientName.isBlank()||recipientName.length()>256||!recipientName.equals(recipientName.strip())
                ||recipientName.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff))
                ||peer==null||!peer.reachable()||!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())
                ||peer.entityId()==null||!peer.entityId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||key==null
                ||key.publicKey()==null||!CodeVaspSettings.key(key.publicKey(),32)||key.expiresAt()==null||!key.expiresAt().isAfter(Instant.now())||!peer.keys().contains(key))throw invalid();
            try{expectedAddressAmount=expectedAddressAmount.setScale(8,RoundingMode.UNNECESSARY);}catch(ArithmeticException inexact){throw invalid();}
        }
        private static IllegalArgumentException invalid(){return new IllegalArgumentException("검증된 입금·수취인과 현재 상대 사업자 키를 확인하세요");}
        @Override public String toString(){return "TransferDataRequest[개인정보 생략]";}
    }
    public record Person(String primaryName,String secondaryName,LocalDate dateOfBirth,List<String> accounts){
        public Person{accounts=List.copyOf(accounts);}
        @Override public String toString(){return "TransferPerson[개인정보 생략]";}
    }
    public record Data(UUID transferId,String originEntityId,BigDecimal amount,Person originator,Person beneficiary,String ciphertextHash){
        @Override public String toString(){return "TransferData[개인정보 생략]";}
    }
    public record Result(String status,String reason,Data data){
        @Override public String toString(){return "TransferDataResult[개인정보 생략]";}
    }
    private final CodeVaspTransport transport;
    private final CodeVaspCounterpartyPolicy policy;
    public CodeVaspTransferDataClient(CodeVaspTransport transport,CodeVaspCounterpartyPolicy policy){this.transport=transport;this.policy=policy;}
    // 외부 PUT와 복호화는 DB 잠금 밖에서 실행한다. 호출 전 미전송만 증명할 수 있으며 응답 해석 실패도 재전송 허가가 아니다.
    public Result exchange(Request request){
        Objects.requireNonNull(request,"정보 교환 요청이 필요합니다");
        // 실제 개인정보 전송 경계에서도 운영 허용을 검사한다. 이것은 호출자가 제공한 목록의 최신성·진위를 증명하지 않는다.
        // 같은 내부 호출을 다른 업무에서 재사용해도 미검토 상대/만료 정책으로 이름을 내보낼 수 없게 한다.
        try{
            var now=Instant.now();var selected=policy.select(new CodeVaspDirectoryClient.Directory(now,now,List.of(request.peer())),request.peer().entityId());
            if(!selected.key().equals(request.key()))throw unavailable();
        }catch(RuntimeException denied){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}
        var response=transport.transferData(request);
        try{
            if(!request.query().requestId().toString().equals(text(response,"transferId",36)))throw unavailable();
            for(String field:response.propertyNames())if(!Set.of("result","reasonType","reasonMsg","transferId","currency","amount","historicalCost","tradePrice","tradeCurrency","isExceedingThreshold","payload").contains(field))throw unavailable();
            String outcome=text(response,"result",16);
            if(outcome.equals("error")){
                String reason=text(response,"reasonType",64);
                if(!Set.of("NOT_FOUND_TXID","LACK_OF_INFORMATION","UNKNOWN","UNKNOWN_TRANSFER_ID").contains(reason))throw unavailable();
                return new Result("ERROR",reason,null);
            }
            if(!outcome.equals("normal")||!"BTC".equalsIgnoreCase(text(response,"currency",8)))throw unavailable();
            String value=text(response,"amount",64);if(!value.matches("(?:0|[1-9][0-9]{0,7})(?:[.][0-9]{1,18})?"))throw unavailable();
            BigDecimal amount=new BigDecimal(value).setScale(8,RoundingMode.UNNECESSARY);
            if(amount.compareTo(request.expectedAddressAmount())!=0)throw unavailable();
            // 공개 결합 필드가 다르면 개인정보를 복호화하기 전에 중단한다.
            var reply=transport.decryptTransfer(request,response);
            var ivms=reply.identity().path("ivms101");
            if(!ivms.isObject()||!ivms.path("OriginatingVASP").isObject())throw unavailable();
            Person originator=person(ivms.path("Originator"),"originatorPersons",true),beneficiary=person(ivms.path("Beneficiary"),"beneficiaryPersons",false);
            if(beneficiary.accounts().size()!=1||!beneficiary.accounts().getFirst().equals(request.query().beneficiaryAddress()))throw unavailable();
            // 이름/생일의 동일인 판정은 현재 KYC를 잠근 업무에서 한다. 파싱 성공을 신원/자금 승인으로 바꾸지 않는다.
            return new Result("NORMAL",null,new Data(request.query().requestId(),request.peer().entityId(),amount,originator,beneficiary,reply.ciphertextHash()));
        }catch(RuntimeException invalidReply){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}
    }
    // 현재 업무는 개인 한 명의 법적 이름과 송신인 생일만 해석한다. 법인·여러 당사자·누락 정보는 추측해 채우지 않는다.
    private static Person person(JsonNode party,String field,boolean birthRequired){
        var people=party.path(field);var accounts=party.path("accountNumber");
        if(!party.isObject()||!people.isArray()||people.size()!=1||!accounts.isArray()||accounts.isEmpty()||accounts.size()>100)throw unavailable();
        var wrapper=people.get(0);if(!wrapper.isObject()||wrapper.hasNonNull("legalPerson"))throw unavailable();var person=wrapper.path("naturalPerson");
        if(!person.isObject())throw unavailable();var names=person.path("name").path("nameIdentifier");
        if(!names.isArray()||names.isEmpty()||names.size()>16)throw unavailable();JsonNode legal=null;
        for(var name:names)if("LEGL".equalsIgnoreCase(text(name,"nameIdentifierType",8))){if(legal!=null)throw unavailable();legal=name;}
        if(legal==null)throw unavailable();String primary=text(legal,"primaryIdentifier",256),secondary=null;
        if(legal.hasNonNull("secondaryIdentifier"))secondary=text(legal,"secondaryIdentifier",256);
        LocalDate birth=null;var details=person.path("dateAndPlaceOfBirth");
        if(birthRequired||person.has("dateAndPlaceOfBirth")){
            birth=LocalDate.parse(text(details,"dateOfBirth",10));if(birth.isAfter(LocalDate.now(ZoneOffset.UTC))||birth.isBefore(LocalDate.of(1900,1,1)))throw unavailable();
        }
        var addresses=new ArrayList<String>();var unique=new HashSet<String>();
        for(var account:accounts){if(!account.isString()||!account.asString().matches("[A-Za-z0-9]{14,120}")||!unique.add(account.asString()))throw unavailable();addresses.add(account.asString());}
        return new Person(primary,secondary,birth,addresses);
    }
}
