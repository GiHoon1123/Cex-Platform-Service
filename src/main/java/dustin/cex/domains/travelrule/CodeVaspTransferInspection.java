package dustin.cex.domains.travelrule;

import dustin.cex.domains.bitcoin.BitcoinDepositOperatorAccess;
import dustin.cex.domains.bitcoin.BitcoinSettings;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.security.AuditService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 운영자·승인자의 현재 추가 인증 후 검증한 비교 자료를 보여주고 접근 증적을 원자적으로 남긴다. 금융 효과는 없다. */
@Service
public class CodeVaspTransferInspection {
    public record Person(String primaryName,String secondaryName,LocalDate dateOfBirth){
        @Override public String toString(){return "InspectedTransferPerson[개인정보 생략]";}
    }
    public record Inspection(UUID id,UUID receiptId,UUID attemptId,CodeVaspTransferEvidence.Expected expected,
        Person originator,Person beneficiary,List<String> beneficiaryAccounts,CodeVaspTransferDetailAssessment.Decision comparison,String assurance){
        public Inspection{beneficiaryAccounts=List.copyOf(beneficiaryAccounts);}
        @Override public String toString(){return "TransferInspection[개인정보 생략]";}
    }
    private final BitcoinDepositOperatorAccess access;
    private final BitcoinSettings settings;
    private final CodeVaspDirectoryService directory;
    private final CodeVaspTransferEvidence evidence;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CodeVaspTransferInspection(BitcoinDepositOperatorAccess access,BitcoinSettings settings,CodeVaspDirectoryService directory,
        CodeVaspTransferEvidence evidence,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.access=access;this.settings=settings;this.directory=directory;this.evidence=evidence;this.jdbc=jdbc;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Inspection inspect(long actor,UUID sid,UUID receipt,UUID attempt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("개인정보 검토는 독립 트랜잭션으로 시작하세요");
        try{return tx.execute(t->{
            // 감사자의 최소 이력 권한을 원문 접근으로 확대하지 않는다. 복호화 전에 현재 역할·세션·대상 관계를 확인한다.
            var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);
            if(attempt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수신 시도 식별자를 확인하세요");
            var verified=evidence.load(receipt,attempt,allowed.customer().getId(),directory.configuredSourceId());UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO codevasp_transfer_inspections(id,attempt_id,receipt_id,actor_id,actor_person,actor_version,session_id,key_id,request_mac,response_mac)
                 VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,attempt,receipt,actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid,verified.keyId(),verified.requestMac(),verified.responseMac());
            audit.record(actor,"CODE_TRANSFER_EVIDENCE_INSPECTED","codevasp-inspection:"+id,"SUCCESS","ATTEMPT:"+attempt+":RECEIPT:"+receipt+":STATUS:"+verified.comparison().status()+":REASON:"+verified.comparison().reason());
            return new Inspection(id,receipt,attempt,verified.expected(),person(verified.received().originator()),person(verified.received().beneficiary()),verified.received().beneficiary().accounts(),verified.comparison(),"INSPECTION_ONLY_NOT_FINANCIAL_APPROVAL");
        });}catch(ResponseStatusException denied){audit.denied(actor,"CODE_TRANSFER_EVIDENCE_INSPECTION","restricted","POLICY_OR_SOURCE_"+denied.getStatusCode().value());throw denied;}
    }
    private static Person person(CodeVaspTransferDataClient.Person p){return new Person(p.primaryName(),p.secondaryName(),p.dateOfBirth());}
}
