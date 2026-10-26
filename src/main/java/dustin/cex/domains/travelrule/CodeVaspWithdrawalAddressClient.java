package dustin.cex.domains.travelrule;

import static dustin.cex.domains.travelrule.CodeVaspTransport.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** 이름·생일을 보내기 전에 선택한 국내 CODE 사업자의 주소 소속 응답을 확인한다. 동일인이나 지급 허가는 아니다. */
@Component
public class CodeVaspWithdrawalAddressClient {
    public record Request(String address,CodeVaspDirectoryClient.Vasp peer,CodeVaspDirectoryClient.Key key) {
        public Request {
            if(address==null||!address.matches("[A-Za-z0-9]{14,120}")||peer==null||!peer.reachable()
                    ||!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())
                    ||peer.entityId()==null||!peer.entityId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    ||key==null||key.publicKey()==null||!CodeVaspSettings.key(key.publicKey(),32)
                    ||key.expiresAt()==null||!key.expiresAt().isAfter(Instant.now())||peer.keys()==null||!peer.keys().contains(key))
                throw new IllegalArgumentException("고정 BTC 지급 주소와 현재 허용된 국내 사업자 키를 확인하세요");
        }
        @Override public String toString(){return "WithdrawalAddressRequest[지급지 생략]";}
    }
    public record Result(String status,String reason,String entityId) {
        @Override public String toString(){return "WithdrawalAddressResult[조회 자료 생략]";}
    }
    private final CodeVaspTransport transport;
    private final CodeVaspCounterpartyPolicy policy;
    public CodeVaspWithdrawalAddressClient(CodeVaspTransport transport,CodeVaspCounterpartyPolicy policy){this.transport=transport;this.policy=policy;}

    public Result verify(Request request) {
        Objects.requireNonNull(request,"지급 주소 소속 조회 원본이 필요합니다");
        try {
            var now=Instant.now();var selected=policy.select(new CodeVaspDirectoryClient.Directory(now,now,List.of(request.peer())),request.peer().entityId());
            if(!selected.key().equals(request.key()))throw unavailable();
        }catch(RuntimeException denied){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}
        var response=transport.verifyWithdrawalAddress(request);
        try {
            for(String field:response.propertyNames())if(!Set.of("result","reasonType","reasonMsg","beneficiaryVaspEntityId").contains(field))throw unavailable();
            String result=text(response,"result",16);
            if(result.equals("valid")) {
                String entity=text(response,"beneficiaryVaspEntityId",128);
                if(!entity.equals(request.peer().entityId())||response.hasNonNull("reasonType")
                    &&(!response.path("reasonType").isString()||!response.path("reasonType").asText().isEmpty()))throw unavailable();
                return new Result("VALID",null,entity);
            }
            if(!result.equals("invalid"))throw unavailable();
            String reason=text(response,"reasonType",64);
            if(!Set.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN").contains(reason))throw unavailable();
            // 다른 사업자를 추천하는 응답으로 개인정보 제공 상대를 바꾸지 않는다. 새 선택은 새 업무 검증 대상이다.
            if(response.hasNonNull("beneficiaryVaspEntityId")&&(!response.path("beneficiaryVaspEntityId").isString()
                ||!Set.of("",request.peer().entityId()).contains(response.path("beneficiaryVaspEntityId").asText())))throw unavailable();
            return new Result("INVALID",reason,null);
        }catch(RuntimeException invalidReply){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}
    }
}
