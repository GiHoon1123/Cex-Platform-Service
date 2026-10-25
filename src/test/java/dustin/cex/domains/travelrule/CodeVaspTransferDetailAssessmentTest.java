package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 비교 정책은 원문을 추측해 보정하지 않는다. 일관성 분류와 동일인·금융 허가를 구분한다. */
class CodeVaspTransferDetailAssessmentTest {
    static final String NAME="Alice Smith", ADDRESS="bcrt1qexamplecustomeraddress";
    static final LocalDate BIRTH=LocalDate.of(1990,1,1);
    CodeVaspTransferDataClient.Person person(String name,String secondary,LocalDate birth,List<String> accounts){return new CodeVaspTransferDataClient.Person(name,secondary,birth,accounts);}
    CodeVaspTransferDataClient.Person person(){return person(NAME,null,BIRTH,List.of(ADDRESS));}
    CodeVaspTransferDataClient.Data data(CodeVaspTransferDataClient.Person sender,CodeVaspTransferDataClient.Person receiver){return new CodeVaspTransferDataClient.Data(UUID.randomUUID(),"origin",BigDecimal.ONE,sender,receiver,"a".repeat(64));}
    CodeVaspTransferDetailAssessment.Decision compare(CodeVaspTransferDataClient.Person sender,CodeVaspTransferDataClient.Person receiver){return CodeVaspTransferDetailAssessment.compare(NAME,BIRTH,ADDRESS,data(sender,receiver));}
    void review(String reason,CodeVaspTransferDataClient.Person sender,CodeVaspTransferDataClient.Person receiver){assertEquals(new CodeVaspTransferDetailAssessment.Decision("REVIEW_REQUIRED",reason),compare(sender,receiver));}
    @Test void exactDetailsAreConsistentWithoutFinancialPermission(){assertEquals(new CodeVaspTransferDetailAssessment.Decision("CONSISTENT","DETAILS_CONSISTENT"),compare(person(),person()));assertFalse(compare(person(),person()).toString().contains(NAME));}
    @Test void canonicallyEquivalentUnicodeDoesNotCreateFalseMismatch(){String name="가상 고객";var party=person(Normalizer.normalize(name,Normalizer.Form.NFD),null,BIRTH,List.of(ADDRESS));assertEquals("CONSISTENT",CodeVaspTransferDetailAssessment.compare(name,BIRTH,ADDRESS,data(party,party)).status());}
    @Test void surroundingWhitespaceIsRemoved(){var p=person("  "+NAME+"\t",null,BIRTH,List.of(ADDRESS));assertEquals("CONSISTENT",compare(p,p).status());}
    @Test void caseChangesAreNotGuessed(){review("BENEFICIARY_NAME_MISMATCH",person(),person("ALICE SMITH",null,BIRTH,List.of(ADDRESS)));}
    @Test void compatibilityCharactersAreNotFolded(){review("BENEFICIARY_NAME_MISMATCH",person(),person("Ａlice Smith",null,BIRTH,List.of(ADDRESS)));}
    @Test void visuallySimilarOtherAlphabetIsNotAccepted(){review("BENEFICIARY_NAME_MISMATCH",person(),person("Аlice Smith",null,BIRTH,List.of(ADDRESS)));}
    @Test void internalWhitespaceIsNotDeleted(){review("BENEFICIARY_NAME_MISMATCH",person(),person("AliceSmith",null,BIRTH,List.of(ADDRESS)));}
    @Test void nameOrderIsNotGuessed(){review("BENEFICIARY_NAME_MISMATCH",person(),person("Smith Alice",null,BIRTH,List.of(ADDRESS)));}
    @Test void splitBeneficiaryNameRequiresReview(){review("BENEFICIARY_STRUCTURED_NAME",person(),person("Smith","Alice",BIRTH,List.of(ADDRESS)));}
    @Test void splitOriginatorNameRequiresReview(){review("ORIGINATOR_STRUCTURED_NAME",person("Smith","Alice",BIRTH,List.of(ADDRESS)),person());}
    @Test void missingBeneficiaryBirthRequiresReview(){review("BENEFICIARY_BIRTH_DATE_MISSING",person(),person(NAME,null,null,List.of(ADDRESS)));}
    @Test void differentBeneficiaryBirthRequiresReview(){review("BENEFICIARY_BIRTH_DATE_MISMATCH",person(),person(NAME,null,BIRTH.plusDays(1),List.of(ADDRESS)));}
    @Test void missingOriginatorBirthRequiresReview(){review("ORIGINATOR_BIRTH_DATE_MISSING",person(NAME,null,null,List.of(ADDRESS)),person());}
    @Test void differentOriginatorBirthRequiresReview(){review("ORIGINATOR_BIRTH_DATE_MISMATCH",person(NAME,null,BIRTH.plusDays(1),List.of(ADDRESS)),person());}
    @Test void differentOriginatorNameRequiresReview(){review("ORIGINATOR_NAME_MISMATCH",person("Another Person",null,BIRTH,List.of(ADDRESS)),person());}
    @Test void beneficiaryAccountMustBeExactlyTheRequestedOne(){review("BENEFICIARY_ACCOUNT_MISMATCH",person(),person(NAME,null,BIRTH,List.of("bcrt1qanothercustomeraddress")));}
    @Test void extraBeneficiaryAccountsAreNotSilentlySelected(){review("BENEFICIARY_ACCOUNT_MISMATCH",person(),person(NAME,null,BIRTH,List.of(ADDRESS,"bcrt1qanothercustomeraddress")));}
    @Test void incompletePartiesAreNotConsistent(){assertEquals(new CodeVaspTransferDetailAssessment.Decision("REVIEW_REQUIRED","PARTIES_INCOMPLETE"),CodeVaspTransferDetailAssessment.compare(NAME,BIRTH,ADDRESS,data(null,person())));}
}
