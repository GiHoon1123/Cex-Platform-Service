package dustin.cex.domains.bitcoin;

import dustin.cex.shared.model.ExactAmount;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * 업무 계층이 DB에 귀속을 확정한 수탁 입력과 Core의 코인 선택 제외 상태를 대조한다.
 * 이 컴포넌트는 입력을 배정하거나 고객 잔액을 변경하지 않는다. 호출자가 영속 배정과 시도 기록을
 * 먼저 확정해야 하며, 이 관측 결과만으로 서명·환급·지급 완료를 허가하면 안 된다.
 * 전체 지갑 해제·서명·방송 기능은 제공하지 않는다.
 */
@Component
public class BitcoinPayoutInputLocks {
    // 관측 시점의 잠금 여부다. 기존 잠금의 소유자와 영속 여부는 Core 조회 응답에 없으므로 증명하지 않는다.
    // 참조 목록을 복사해 호출자가 사후 변경하지 못하게 하고 로그에 수탁 입력을 노출하지 않는다.
    public record Observation(String network,String wallet,List<String> inputs,boolean locked,Instant observedAt) {
        public Observation {inputs=List.copyOf(inputs);}
        @Override public String toString(){return "BitcoinInputLockObservation[입력 원본 생략]";}
    }
    private record Point(String txid,int vout) {
        String reference(){return txid+":"+vout;}
        Map<String,Object> rpc(){return Map.of("txid",txid,"vout",vout);}
    }
    private final BitcoinSettings settings;
    private final BitcoinRpcClient rpc;
    public BitcoinPayoutInputLocks(BitcoinSettings settings,BitcoinRpcClient rpc){this.settings=settings;this.rpc=rpc;}
    /** 호출 업무가 먼저 영속 배정/시도를 확정한다. RPC 응답을 잃으면 같은 입력의 실제 잠금 상태로 재확인한다. */
    public Observation ensure(String network,String wallet,List<BitcoinPayoutClient.Input> inputs) {
        var points=validate(network,wallet,inputs);rpc.ready();
        for(var input:inputs) {
            var output=rpc.call("gettxout",List.of(input.txid(),input.vout(),true));
            var script=output.path("scriptPubKey");BigDecimal value=BitcoinAmounts.fromNode(output.path("value"));
            if(!BitcoinRpcClient.isFalse(output,"coinbase")||integer(output,"confirmations")<6
                ||value.compareTo(new BigDecimal(input.amount()))!=0||!input.scriptPubKey().equals(BitcoinRpcClient.text(script,"hex"))
                ||!input.address().equals(BitcoinRpcClient.text(script,"address")))throw BitcoinRpcClient.unavailable();
            var owner=rpc.call("getaddressinfo",List.of(input.address()));
            if(!input.address().equals(BitcoinRpcClient.text(owner,"address"))||!input.scriptPubKey().equals(BitcoinRpcClient.text(owner,"scriptPubKey"))
                ||!BitcoinRpcClient.isTrue(owner,"ismine")||!BitcoinRpcClient.isTrue(owner,"solvable"))throw BitcoinRpcClient.unavailable();
        }
        // 이미 잠긴 입력을 해제했다 다시 잠그면 그 사이 자동 코인 선택에 노출된다.
        // 따라서 기존 잠금은 유지하며, 새 잠금 요청에만 영속 저장을 명시한다.
        Set<Point> before=locked();var missing=points.stream().filter(p->!before.contains(p)).toList();
        if(!missing.isEmpty()) {
            try {
                // 기본 메모리 잠금과 구분해 wallet DB에 저장한다. 다른 지갑이나 다른 입력을 숨겨 넣지 않는다.
                var result=rpc.call("lockunspent",List.of(false,missing.stream().map(Point::rpc).toList(),true));
                if(!result.isBoolean()||!result.booleanValue())throw BitcoinRpcClient.unavailable();
            }catch(RuntimeException uncertain){
                // 성공으로 추측하지 않는다. 아래 실제 상태 조회가 실패하거나 한 입력이라도 빠지면 미해결을 전파한다.
            }
        }
        if(!locked().containsAll(points))throw BitcoinRpcClient.unavailable();return observed(points,true);
    }
    /** 미서명/실행 미확정 종료를 DB가 증명한 자기 배정만 전달한다. 빈 목록으로 전체 지갑을 해제하지 않는다. */
    public Observation release(String network,String wallet,List<BitcoinPayoutClient.Input> inputs) {
        var points=validate(network,wallet,inputs);rpc.ready();Set<Point> before=locked();var active=points.stream().filter(before::contains).toList();
        if(!active.isEmpty()) {
            try {
                var result=rpc.call("lockunspent",List.of(true,active.stream().map(Point::rpc).toList()));
                if(!result.isBoolean()||!result.booleanValue())throw BitcoinRpcClient.unavailable();
            }catch(RuntimeException uncertain){
                // 응답 유실을 전체 해제나 DB 배정 삭제로 바꾸지 않는다. 실제 해제가 관측돼야 완료할 수 있다.
            }
        }
        if(locked().stream().anyMatch(points::contains))throw BitcoinRpcClient.unavailable();return observed(points,false);
    }
    private List<Point> validate(String network,String wallet,List<BitcoinPayoutClient.Input> inputs) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("수탁 입력 잠금은 DB 트랜잭션 밖에서 실행하세요");
        if(!settings.network.id.equals(network)||!settings.wallet.equals(wallet)||inputs==null||inputs.isEmpty()||inputs.size()>BitcoinPayoutClient.MAX_INPUTS)throw BitcoinRpcClient.unavailable();
        var points=new TreeMap<String,Point>();
        for(var input:inputs) {
            if(input==null||input.txid()==null||!input.txid().matches("[0-9a-f]{64}")||input.vout()<0||input.address()==null||!input.address().matches("[A-Za-z0-9]{14,100}")
                ||input.scriptPubKey()==null||!input.scriptPubKey().matches("0014[0-9a-f]{40}"))throw BitcoinRpcClient.unavailable();
            try {if(ExactAmount.requirePositive(new BigDecimal(input.amount()),"수탁 입력 금액").setScale(8,RoundingMode.UNNECESSARY).compareTo(new BigDecimal("21000000"))>0)throw BitcoinRpcClient.unavailable();}
            catch(RuntimeException invalid){throw BitcoinRpcClient.unavailable();}
            var point=new Point(input.txid(),input.vout());if(points.put(point.reference(),point)!=null)throw BitcoinRpcClient.unavailable();
        }
        return List.copyOf(points.values());
    }
    // 목록은 페이지가 없으므로 본문과 원소 수를 모두 제한한다. 중복/잘못된 outpoint를 무시하지 않는다.
    private Set<Point> locked() {
        var values=rpc.call("listlockunspent",List.of(),1048576);if(!values.isArray()||values.size()>10000)throw BitcoinRpcClient.unavailable();
        var points=new HashSet<Point>();
        for(var value:values) {
            String id=BitcoinRpcClient.text(value,"txid");long vout=integer(value,"vout");
            if(!id.matches("[0-9a-f]{64}")||vout>Integer.MAX_VALUE||!points.add(new Point(id,(int)vout)))throw BitcoinRpcClient.unavailable();
        }
        return points;
    }
    private Observation observed(List<Point> points,boolean locked){return new Observation(settings.network.id,settings.wallet,points.stream().map(Point::reference).toList(),locked,Instant.now());}
    private static long integer(JsonNode node,String name){var value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw BitcoinRpcClient.unavailable();return value.longValue();}
}
