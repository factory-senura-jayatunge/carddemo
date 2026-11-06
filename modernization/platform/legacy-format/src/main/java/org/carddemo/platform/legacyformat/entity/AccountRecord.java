package org.carddemo.platform.legacyformat.entity;

import java.math.BigDecimal;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/**
 * ENT-ACCOUNT as the 300-byte record holds it. Dates stay text here; the
 * loader decides whether they parse. {@code legacyFiller} is the 178-byte
 * FILLER, kept for byte-exact export ({@code null} exports spaces).
 */
public record AccountRecord(
        String acctId,
        String activeStatus,
        BigDecimal currBal,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        BigDecimal currCycCredit,
        BigDecimal currCycDebit,
        String addrZip,
        String groupId,
        String legacyFiller) {

    public static AccountRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.ACCOUNT, data);
        return new AccountRecord(
                r.digits("ACCT-ID"),
                r.text("ACCT-ACTIVE-STATUS"),
                r.decimal("ACCT-CURR-BAL"),
                r.decimal("ACCT-CREDIT-LIMIT"),
                r.decimal("ACCT-CASH-CREDIT-LIMIT"),
                r.text("ACCT-OPEN-DATE"),
                r.text("ACCT-EXPIRAION-DATE"),
                r.text("ACCT-REISSUE-DATE"),
                r.decimal("ACCT-CURR-CYC-CREDIT"),
                r.decimal("ACCT-CURR-CYC-DEBIT"),
                r.text("ACCT-ADDR-ZIP"),
                r.text("ACCT-GROUP-ID"),
                r.raw("ACCT-FILLER"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.ACCOUNT)
                .digits("ACCT-ID", acctId)
                .text("ACCT-ACTIVE-STATUS", activeStatus)
                .decimal("ACCT-CURR-BAL", currBal)
                .decimal("ACCT-CREDIT-LIMIT", creditLimit)
                .decimal("ACCT-CASH-CREDIT-LIMIT", cashCreditLimit)
                .text("ACCT-OPEN-DATE", openDate)
                .text("ACCT-EXPIRAION-DATE", expirationDate)
                .text("ACCT-REISSUE-DATE", reissueDate)
                .decimal("ACCT-CURR-CYC-CREDIT", currCycCredit)
                .decimal("ACCT-CURR-CYC-DEBIT", currCycDebit)
                .text("ACCT-ADDR-ZIP", addrZip)
                .text("ACCT-GROUP-ID", groupId)
                .raw("ACCT-FILLER", legacyFiller)
                .build();
    }

    @Override
    public String toString() {
        return "AccountRecord[" + acctId + "]";
    }
}
