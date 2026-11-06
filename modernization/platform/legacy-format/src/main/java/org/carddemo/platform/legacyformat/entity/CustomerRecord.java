package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/**
 * ENT-CUSTOMER as the 500-byte record holds it. Every field but the ID is
 * personal data, so {@link #toString()} shows only the ID.
 */
public record CustomerRecord(
        String custId,
        String firstName,
        String middleName,
        String lastName,
        String addrLine1,
        String addrLine2,
        String addrLine3,
        String stateCd,
        String countryCd,
        String zip,
        String phone1,
        String phone2,
        String ssn,
        String govtIssuedId,
        String dob,
        String eftAccountId,
        String priCardHolderInd,
        int ficoScore) {

    public static CustomerRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.CUSTOMER, data);
        return new CustomerRecord(
                r.digits("CUST-ID"),
                r.text("CUST-FIRST-NAME"),
                r.text("CUST-MIDDLE-NAME"),
                r.text("CUST-LAST-NAME"),
                r.text("CUST-ADDR-LINE-1"),
                r.text("CUST-ADDR-LINE-2"),
                r.text("CUST-ADDR-LINE-3"),
                r.text("CUST-ADDR-STATE-CD"),
                r.text("CUST-ADDR-COUNTRY-CD"),
                r.text("CUST-ADDR-ZIP"),
                r.text("CUST-PHONE-NUM-1"),
                r.text("CUST-PHONE-NUM-2"),
                r.digits("CUST-SSN"),
                r.text("CUST-GOVT-ISSUED-ID"),
                r.text("CUST-DOB-YYYY-MM-DD"),
                r.text("CUST-EFT-ACCOUNT-ID"),
                r.text("CUST-PRI-CARD-HOLDER-IND"),
                Integer.parseInt(r.digits("CUST-FICO-CREDIT-SCORE")));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.CUSTOMER)
                .digits("CUST-ID", custId)
                .text("CUST-FIRST-NAME", firstName)
                .text("CUST-MIDDLE-NAME", middleName)
                .text("CUST-LAST-NAME", lastName)
                .text("CUST-ADDR-LINE-1", addrLine1)
                .text("CUST-ADDR-LINE-2", addrLine2)
                .text("CUST-ADDR-LINE-3", addrLine3)
                .text("CUST-ADDR-STATE-CD", stateCd)
                .text("CUST-ADDR-COUNTRY-CD", countryCd)
                .text("CUST-ADDR-ZIP", zip)
                .text("CUST-PHONE-NUM-1", phone1)
                .text("CUST-PHONE-NUM-2", phone2)
                .digits("CUST-SSN", ssn)
                .text("CUST-GOVT-ISSUED-ID", govtIssuedId)
                .text("CUST-DOB-YYYY-MM-DD", dob)
                .text("CUST-EFT-ACCOUNT-ID", eftAccountId)
                .text("CUST-PRI-CARD-HOLDER-IND", priCardHolderInd)
                .digits("CUST-FICO-CREDIT-SCORE", ficoScore)
                .build();
    }

    @Override
    public String toString() {
        return "CustomerRecord[" + custId + "]";
    }
}
