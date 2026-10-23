package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.User;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/**
 * 자료 요청과 세 종류의 내용 검토를 한 시점의 불변 목록으로 묶는다. 원문·이름·금액·외부 확인 완료는 넣지 않는다.
 * 원본 전체 메타데이터를 보존하고 응답만 페이지로 나눠, 숨은 페이지의 추가 사실도 승인 해시에 반영한다.
 */
@Component
public class EddDossier {
    public static final String POLICY="EDD_DOSSIER_V1";
    public static final String ASSURANCE="METADATA_SNAPSHOT_ONLY_EXTERNAL_VERIFICATION_UNRESOLVED";
    public static final int PAGE_SIZE=25;
    public record Summary(UUID id,String digest,int itemCount,int pageCount,String policy,String assurance) {}
    public record Page(Summary summary,int page,List<JsonNode> items) {}
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public EddDossier(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    // 원문 승인 내용 전체를 복사하지 않고 필요한 목록 필드만 명시한다. 체결 금액·관찰 암호문도 제외한다.
    private static final String BUNDLE="""
WITH scope AS (SELECT ?::uuid AS case_id,?::bigint AS user_id), records AS (
SELECT 'EVIDENCE' AS kind,r.id,jsonb_build_object('kind','EVIDENCE','id',r.id,'assessmentId',r.assessment_id,'purpose',r.purpose,
 'expiresAt',to_char(r.expires_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'evidenceIds',CASE WHEN e.id IS NULL THEN '[]'::jsonb ELSE jsonb_build_array(e.id) END,
 'status',CASE WHEN e.id IS NOT NULL THEN 'RECEIVED_UNVERIFIED' WHEN r.expires_at>statement_timestamp() THEN 'REQUESTED' ELSE 'EXPIRED' END,
 'contentHash',e.original_digest,'evidenceKeyId',e.key_id) AS item
 FROM edd_evidence_requests r JOIN scope s ON s.case_id=r.case_id AND s.user_id=r.user_id
 LEFT JOIN edd_evidence e ON e.request_id=r.id AND e.user_id=r.user_id
 UNION ALL
SELECT 'ADDRESS',r.id,jsonb_build_object('kind','ADDRESS','id',r.id,'assessmentId',r.payload::jsonb->'assessmentId',
 'caseRevision',r.payload::jsonb->'caseRevision','policy',r.payload::jsonb->'policy','expiresAt',to_char(r.expires_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'status',CASE WHEN d.approved THEN 'APPROVED' WHEN d.approved=false THEN 'REJECTED' WHEN r.expires_at>statement_timestamp() THEN 'PENDING' ELSE 'EXPIRED' END,
 'evidenceIds',jsonb_build_array(r.payload::jsonb->'evidenceId'),'payloadHash',r.payload_hash,'reasons',r.payload::jsonb->'reasons',
 'evidenceKeyId',r.payload::jsonb->'evidenceKeyId','cddKeyId',r.payload::jsonb->'cddKeyId','assurance','CONTENT_OBSERVATIONS_ONLY')
 FROM edd_address_reviews r JOIN scope s ON s.case_id=r.case_id AND s.user_id=r.user_id LEFT JOIN edd_address_decisions d ON d.review_id=r.id
 UNION ALL
SELECT 'FUNDS',r.id,jsonb_build_object('kind','FUNDS','id',r.id,'assessmentId',r.payload::jsonb->'assessmentId',
 'caseRevision',r.payload::jsonb->'caseRevision','policy',r.payload::jsonb->'policy','expiresAt',to_char(r.expires_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'status',CASE WHEN d.approved THEN 'APPROVED' WHEN d.approved=false THEN 'REJECTED' WHEN r.expires_at>statement_timestamp() THEN 'PENDING' ELSE 'EXPIRED' END,
 'evidenceIds',jsonb_build_array(r.payload::jsonb->'originEvidence',r.payload::jsonb->'receiptEvidence'),'payloadHash',r.payload_hash,'reasons',r.payload::jsonb->'reasons',
 'evidenceKeyId',r.payload::jsonb->'evidenceKeyId','cddKeyId',r.payload::jsonb->'cddKeyId','assurance',r.payload::jsonb->>'assurance')
 FROM edd_funds_reviews r JOIN scope s ON s.case_id=r.case_id AND s.user_id=r.user_id LEFT JOIN edd_funds_decisions d ON d.review_id=r.id
 UNION ALL
SELECT 'WEALTH',r.id,jsonb_build_object('kind','WEALTH','id',r.id,'assessmentId',r.payload::jsonb->'assessmentId',
 'caseRevision',r.payload::jsonb->'caseRevision','policy',r.payload::jsonb->'policy','expiresAt',to_char(r.expires_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'status',CASE WHEN d.approved THEN 'APPROVED' WHEN d.approved=false THEN 'REJECTED' WHEN r.expires_at>statement_timestamp() THEN 'PENDING' ELSE 'EXPIRED' END,
 'evidenceIds',jsonb_build_array(r.payload::jsonb->'openingEvidence',r.payload::jsonb->'changesEvidence',r.payload::jsonb->'closingEvidence'),'payloadHash',r.payload_hash,'reasons',r.payload::jsonb->'reasons',
 'evidenceKeyId',r.payload::jsonb->'evidenceKeyId','cddKeyId',r.payload::jsonb->'cddKeyId','assurance',r.payload::jsonb->>'assurance')
 FROM edd_wealth_reviews r JOIN scope s ON s.case_id=r.case_id AND s.user_id=r.user_id LEFT JOIN edd_wealth_decisions d ON d.review_id=r.id
), bundle AS (SELECT coalesce(jsonb_agg(item ORDER BY kind,id),'[]'::jsonb)::text AS items FROM records)         """;
    /** 호출 서비스가 고객·사건을 잠근 같은 트랜잭션에서만 만든다. 요청·서명 결합·감사 실패 시 함께 취소된다. */
    public Summary capture(UUID caseId,long user) {
        transaction();if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM edd_cases WHERE id=? AND user_id=?)",Boolean.class,caseId,user)))throw denied(HttpStatus.CONFLICT);UUID id=UUID.randomUUID();
        jdbc.update(BUNDLE+"INSERT INTO edd_case_dossiers(id,case_id,user_id,items,digest,item_count) SELECT ?,?,?,items,encode(sha256(convert_to(items,'UTF8')),'hex'),jsonb_array_length(items::jsonb) FROM bundle",caseId,user,id,caseId,user);
        return read(id);
    }
    /** 페이지의 일부가 아니라 전체 목록 해시를 다시 계산한다. 새 미결 검토도 옛 결정을 무효화한다. */
    public String liveDigest(UUID caseId,long user) {
        transaction();return jdbc.queryForObject(BUNDLE+"SELECT encode(sha256(convert_to(items,'UTF8')),'hex') FROM bundle",String.class,caseId,user);
    }
    public void verify(Summary expected,UUID caseId,long user) {
        transaction();if(expected==null||!POLICY.equals(expected.policy())||!ASSURANCE.equals(expected.assurance())||!expected.equals(read(expected.id())))throw denied(HttpStatus.CONFLICT);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM edd_case_dossiers WHERE id=? AND case_id=? AND user_id=?)",Boolean.class,expected.id(),caseId,user)))throw denied(HttpStatus.CONFLICT);
    }
    public Page page(Summary summary,int page) {
        transaction();if(page<0||page>=summary.pageCount())throw denied(HttpStatus.BAD_REQUEST);
        var items=jdbc.queryForList("SELECT value::text AS item FROM edd_case_dossiers d CROSS JOIN LATERAL jsonb_array_elements(d.items::jsonb) WITH ORDINALITY e(value,n) WHERE d.id=? AND e.n>? AND e.n<=? ORDER BY e.n",summary.id(),(long)page*PAGE_SIZE,(long)(page+1)*PAGE_SIZE).stream().map(r->json.readTree((String)r.get("item"))).toList();
        return new Page(summary,page,items);
    }
    /** 응답 제공과 필수 감사가 커밋돼야 열람 영수증이 남는다. 사람이 실제 읽었다는 자동 증명은 아니다. */
    public void viewed(UUID report,Summary summary,int page,User actor,UUID sid) {
        transaction();jdbc.update("INSERT INTO edd_case_dossier_views(id,report_id,dossier_id,actor_id,actor_person,actor_version,session_id,page,digest) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),report,summary.id(),actor.getId(),actor.getOperatorPersonId(),actor.getVersion(),sid,page,summary.digest());
    }
    public void requireAllPages(UUID report,Summary summary,User actor,UUID sid) {
        transaction();long count=jdbc.queryForObject("SELECT count(DISTINCT page) FROM edd_case_dossier_views WHERE report_id=? AND dossier_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND digest=? AND page>=0 AND page<? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,report,summary.id(),actor.getId(),actor.getOperatorPersonId(),actor.getVersion(),sid,summary.digest(),summary.pageCount());
        if(count!=summary.pageCount())throw denied(HttpStatus.CONFLICT);
    }
    private Summary read(UUID id) {
        var rows=jdbc.queryForList("SELECT digest,item_count,encode(sha256(convert_to(items,'UTF8')),'hex') AS actual FROM edd_case_dossiers WHERE id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.CONFLICT);var r=rows.getFirst();if(!r.get("digest").equals(r.get("actual")))throw new IllegalStateException("검토 자료 묶음 해시 검증 실패");
        int count=((Number)r.get("item_count")).intValue();return new Summary(id,(String)r.get("digest"),count,Math.max(1,(count+PAGE_SIZE-1)/PAGE_SIZE),POLICY,ASSURANCE);
    }
    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("자료 묶음에는 업무 트랜잭션이 필요합니다");}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 자료 묶음과 모든 페이지의 본인 열람을 확인하세요");}
}
