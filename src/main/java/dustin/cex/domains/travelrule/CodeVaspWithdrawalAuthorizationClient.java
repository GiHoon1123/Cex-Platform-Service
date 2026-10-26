package dustin.cex.domains.travelrule;

import static dustin.cex.domains.travelrule.CodeVaspTransport.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 국내 CODE 개인 출금의 이전 승인 응답을 해석한다. 상대의 확인은 플랫폼의 지급 승인·실제 체인 전송이 아니다. */
@Component
public class CodeVaspWithdrawalAuthorizationClient {
    public record Request(UUID transferId,BigDecimal amount,BigDecimal krwValue,boolean thresholdExceeded,
            String originatorAccount,String originatorName,LocalDate originatorBirth,String address,String beneficiaryName,
            CodeVaspDirectoryClient.Vasp peer,CodeVaspDirectoryClient.Key key) {
        public Request {
            if(transferId==null||transferId.version()!=4||amount==null||krwValue==null||amount.signum()<=0
                    ||amount.compareTo(new BigDecimal("21000000"))>0||krwValue.signum()<=0||krwValue.precision()>32
                    ||krwValue.compareTo(new BigDecimal("1000000000000000000000000"))>0
                    ||krwValue.scale() < -24||krwValue.scale()>18||amount.scale()>18
                    ||!account(originatorAccount)||!account(address)||!name(originatorName)||!name(beneficiaryName)
                    ||originatorBirth==null||originatorBirth.isAfter(LocalDate.now(ZoneOffset.UTC))||originatorBirth.isBefore(LocalDate.of(1900,1,1))
                    ||peer==null||!peer.reachable()||!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())
                    ||peer.entityId()==null||!peer.entityId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    ||key==null||key.publicKey()==null||!CodeVaspSettings.key(key.publicKey(),32)||key.expiresAt()==null
                    ||!key.expiresAt().isAfter(Instant.now())||peer.keys()==null||!peer.keys().contains(key))throw invalid();
            try {amount=amount.setScale(8,RoundingMode.UNNECESSARY);krwValue=krwValue.setScale(8,RoundingMode.UNNECESSARY);}
            catch(ArithmeticException inexact){throw invalid();}
        }
        private static boolean account(String value){return value!=null&&value.matches("[A-Za-z0-9]{14,120}");}
        private static boolean name(String value){return value!=null&&!value.isBlank()&&value.length()<=256&&value.equals(value.strip())
            &&value.codePoints().noneMatch(c->Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
        private static IllegalArgumentException invalid(){return new IllegalArgumentException("정확한 BTC 지급액·평가·개인 신원·현재 국내 사업자 키를 확인하세요");}
        @Override public String toString(){return "WithdrawalTravelRuleRequest[개인정보·지급지 생략]";}
    }
    public record Data(UUID transferId,CodeVaspTransferDataClient.Person originator,
            CodeVaspTransferDataClient.Person beneficiary,String ciphertextHash) {
        @Override public String toString(){return "WithdrawalTravelRuleData[개인정보 생략]";}
    }
    public record Result(String status,String reason,Data data) {
        @Override public String toString(){return "WithdrawalTravelRuleResult[개인정보 생략]";}
    }
    private final CodeVaspTransport transport;
    private final CodeVaspCounterpartyPolicy policy;
    public CodeVaspWithdrawalAuthorizationClient(CodeVaspTransport transport,CodeVaspCounterpartyPolicy policy){this.transport=transport;this.policy=policy;}

    public Result authorize(Request request) {
        Objects.requireNonNull(request,"출금 이전 승인 요청이 필요합니다");
        try {
            var now=Instant.now();var selected=policy.select(new CodeVaspDirectoryClient.Directory(now,now,List.of(request.peer())),request.peer().entityId());
            if(!selected.key().equals(request.key()))throw unavailable();
        }catch(RuntimeException denied){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}
        var response=transport.authorizeWithdrawal(request);
        try {
            if(!request.transferId().toString().equals(text(response,"transferId",36)))throw unavailable();
            for(String field:response.propertyNames())if(!Set.of("result","reasonType","reasonMsg","transferId","beneficiaryVasp","payload").contains(field))throw unavailable();
            String result=text(response,"result",16);
            if(result.equals("denied")) {
                String reason=text(response,"reasonType",64);
                if(!Set.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","INPUT_NAME_MISMATCHED","DOB_MISMATCHED","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN").contains(reason))throw unavailable();
                return new Result("DENIED",reason,null);
            }
            if(!result.equals("verified"))throw unavailable();
            if(response.hasNonNull("reasonType")&&(!response.path("reasonType").isString()||!response.path("reasonType").asText().isEmpty()))throw unavailable();
            var decoded=transport.decrypt(response,request.key());var ivms=decoded.identity().path("ivms101");
            if(!ivms.isObject()||!ivms.path("OriginatingVASP").isObject()||!ivms.path("BeneficiaryVASP").isObject())throw unavailable();
            var sender=CodeVaspTransferDataClient.person(ivms.path("Originator"),"originatorPersons",true);
            var recipient=CodeVaspTransferDataClient.person(ivms.path("Beneficiary"),"beneficiaryPersons",false);
            // 소액의 플래그와 관계없이 회신의 실제 이름·원래 계정·지급지를 확인한다. verified 문자열만으로 통과하지 않는다.
            if(sender.accounts().size()!=1||!sender.accounts().getFirst().equals(request.originatorAccount())
                    ||!sender.primaryName().equals(request.originatorName())||sender.secondaryName()!=null
                    ||!sender.dateOfBirth().equals(request.originatorBirth())||recipient.accounts().size()!=1
                    ||!recipient.accounts().getFirst().equals(request.address())||!recipient.primaryName().equals(request.beneficiaryName())
                    ||recipient.secondaryName()!=null)throw unavailable();
            return new Result("VERIFIED",null,new Data(request.transferId(),sender,recipient,decoded.ciphertextHash()));
        }catch(RuntimeException invalidReply){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}
    }
}
