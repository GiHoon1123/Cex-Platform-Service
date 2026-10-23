package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** 주소 별칭·철자 유사도 추측 없이 사람이 적은 값의 정확한 대조와 날짜 경계를 검증한다. */
class AddressFactsTest {
    final LocalDate today=LocalDate.of(2026,10,23);
    AddressFacts facts(String name,String address,LocalDate date) {return new AddressFacts(AddressFacts.DocumentType.UTILITY_BILL,name,address,"가상 발급기관",date);}
    @Test void normalizesUnicodeSpacesAndNameCaseButKeepsAddressPunctuation() {
        var f=facts("  Test   User ","  가상\u00a0주소  1-2  ",today).validated();assertEquals("TEST USER",f.holderName());assertEquals("가상 주소 1-2",f.address());assertTrue(f.compare("TEST USER","가상 주소 1-2",today).isEmpty());assertFalse(f.toString().contains("가상"));
    }
    @ParameterizedTest @ValueSource(ints={0,1,89,90})
    void acceptsFreshnessBoundaryAsPolicyNotLegalPeriod(int days) {assertTrue(facts("고객","주소",today.minusDays(days)).validated().compare("고객","주소",today).isEmpty());}
    @Test void recordsEveryMismatchAndOldDateInsteadOfPretendingToVerify() {assertEquals(List.of(AddressFacts.Reason.NAME_MISMATCH,AddressFacts.Reason.ADDRESS_MISMATCH,AddressFacts.Reason.DOCUMENT_OUTDATED),facts("다른고객","다른주소",today.minusDays(91)).validated().compare("고객","주소",today));}
    @Test void futureDateHasIndependentReason() {assertEquals(List.of(AddressFacts.Reason.FUTURE_ISSUE_DATE),facts("고객","주소",today.plusDays(1)).validated().compare("고객","주소",today));}
    @ParameterizedTest @ValueSource(strings={"","가상\n주소","가상\u202e주소"})
    void rejectsEmptyOrHiddenControls(String address) {assertThrows(ResponseStatusException.class,()->facts("고객",address,today).validated());}
    @Test void differentAddressPunctuationIsNotSilentlyMerged() {assertEquals(List.of(AddressFacts.Reason.ADDRESS_MISMATCH),facts("고객","주소 12",today).validated().compare("고객","주소 1-2",today));}
    @Test void requiresDocumentTypeIssuerNameAndBoundedDate() {
        assertThrows(ResponseStatusException.class,()->new AddressFacts(null,"고객","주소","기관",today).validated());
        assertThrows(ResponseStatusException.class,()->facts("고객","주소",null).validated());
        assertThrows(ResponseStatusException.class,()->new AddressFacts(AddressFacts.DocumentType.BANK_STATEMENT,"고객","주소","",today).validated());
        assertThrows(ResponseStatusException.class,()->facts("고객","주소",today.withYear(1800)).validated());
        assertThrows(ResponseStatusException.class,()->facts("고객","주소",today.withYear(2200)).validated());
    }
}
