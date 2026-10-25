package dustin.cex.domains.travelrule;

import static dustin.cex.domains.travelrule.CodeVaspTransport.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** BTC 거래의 송신 사업자를 검색한다. 접수 성공·검색 결과·고객 정보 확인·금융 허가는 서로 다른 단계다. */
@Component
public class CodeVaspTransactionClient {
    public enum Status { FOUND, NOT_FOUND, PROCESSING, UNKNOWN_REQUEST, AMBIGUOUS }
    public record Analysis(String entityId,String result){}
    public record Result(UUID requestId,Status status,String entityId,List<Analysis> analysis){public Result{analysis=List.copyOf(analysis);}}
    public record Request(UUID requestId,String txid,String beneficiaryAddress){
        public Request{
            // 주소 소유와 실제 출력 일치는 상위 입금 원본에서 확인해야 한다. 여기서는 노드가 준 BTC 해시의 표현을 바꾸지 않는다.
            if(requestId==null||txid==null||!txid.matches("[0-9a-f]{64}")||beneficiaryAddress==null||!beneficiaryAddress.matches("[A-Za-z0-9]{14,120}"))
                throw new IllegalArgumentException("입금 원본의 거래 식별자와 수신 주소를 확인하세요");
        }
        @Override public String toString(){return "CodeVaspTransactionRequest[입금 원문 생략]";}
    }
    private final CodeVaspTransport transport;
    public CodeVaspTransactionClient(CodeVaspTransport transport){this.transport=transport;}

    /** 반환은 공급사가 해당 요청 ID를 접수했다는 의미다. 타임아웃은 미접수를 증명하지 않는다. */
    public void submit(Request request){
        Objects.requireNonNull(request,"입금 조회 요청이 필요합니다");
        var response=transport.searchTransaction(request.requestId(),request.txid(),request.beneficiaryAddress());
        if(!"SUCCESS".equals(text(response,"result",32))||!request.requestId().toString().equals(text(response,"requestId",36)))throw unavailable();
    }

    public Result result(UUID requestId){
        Objects.requireNonNull(requestId,"입금 조회 식별자가 필요합니다");
        var response=transport.transactionResult(requestId);
        if(!requestId.toString().equals(text(response,"requestId",36)))throw unavailable();
        String outcome=text(response,"result",16);Status status;
        if(outcome.equals("valid"))status=Status.FOUND;
        else if(outcome.equals("invalid"))status=switch(text(response,"reasonType",64)){
            case "NOT_FOUND_TXID"->Status.NOT_FOUND;
            case "PROCESSING"->Status.PROCESSING;
            case "LACK_OF_INFORMATION"->Status.UNKNOWN_REQUEST;
            case "ALLIANCE_DATA_NON_UNIQUE_RESULT"->Status.AMBIGUOUS;
            default->throw unavailable();
        };
        else throw unavailable();
        String entity=null;
        if(status==Status.FOUND)entity=text(response,"vaspEntityId",128);
        else if(response.hasNonNull("vaspEntityId")&&!response.path("vaspEntityId").asString().isEmpty())throw unavailable();
        var rows=response.path("analysisResult");var analysis=new ArrayList<Analysis>();var entities=new HashSet<String>();
        if(response.has("analysisResult")){
            if(!rows.isArray()||rows.size()>5000)throw unavailable();
            for(var row:rows){
                String id=text(row,"entityId",128),result=text(row,"result",32);
                // 공식 필드 표의 열거값만 받는다. 문서 예제의 NOT_FOUND_ADDRESS를 TXID 결과의 호환 별칭으로 추측하지 않는다.
                if(!entities.add(id)||!Set.of("FOUND","NOT_FOUND_TXID","SYSTEM_NOT_AVAILABLE").contains(result))throw unavailable();
                analysis.add(new Analysis(id,result));
            }
        }
        var found=analysis.stream().filter(row->row.result().equals("FOUND")).toList();
        // valid라는 문자열만으로 사업자를 확정하지 않는다. 한 곳의 일치 근거가 필요하며 상충·부분 결과를 성공으로 승격하지 않는다.
        if(status==Status.FOUND&&(found.size()!=1||!found.getFirst().entityId().equals(entity)))throw unavailable();
        if(status==Status.NOT_FOUND&&!found.isEmpty())throw unavailable();
        return new Result(requestId,status,entity,analysis);
    }
}
