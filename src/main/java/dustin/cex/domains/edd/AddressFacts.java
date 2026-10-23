package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CustomerDeclaration;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 문서에서 사람이 읽어 적은 사실이다. 발급기관 확인·거주 사실의 진실성 검증을 수행한 값이 아니다. */
public record AddressFacts(DocumentType documentType,String holderName,String address,String issuer,LocalDate issuedOn) {
    public enum DocumentType { UTILITY_BILL, BANK_STATEMENT, GOVERNMENT_RESIDENCE }
    public enum Reason { NAME_MISMATCH, ADDRESS_MISMATCH, DOCUMENT_OUTDATED, FUTURE_ISSUE_DATE }
    public AddressFacts validated() {
        if(documentType==null || issuedOn==null || issuedOn.isBefore(LocalDate.of(1900,1,1)) || issuedOn.isAfter(LocalDate.of(2100,12,31)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"주소 문서에서 읽은 항목을 확인하세요");
        return new AddressFacts(documentType,CustomerDeclaration.name(holderName),CustomerDeclaration.address(address),CustomerDeclaration.name(issuer),issuedOn);
    }
    /** 날짜 범위는 초기 제품 정책이다. 외국 거래소의 3개월 기준이나 법정 기간과 동일하다고 설명하지 않는다. */
    public List<Reason> compare(String declaredName,String declaredAddress,LocalDate today) {
        var reasons=new ArrayList<Reason>();
        if(!holderName.equals(declaredName)) reasons.add(Reason.NAME_MISMATCH);
        if(!address.equals(declaredAddress)) reasons.add(Reason.ADDRESS_MISMATCH);
        if(issuedOn.isBefore(today.minusDays(90))) reasons.add(Reason.DOCUMENT_OUTDATED);
        if(issuedOn.isAfter(today)) reasons.add(Reason.FUTURE_ISSUE_DATE);
        return List.copyOf(reasons);
    }
    @Override public String toString() {return "AddressFacts[개인정보 생략]";}
}
