package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-CARD-XREF as the 50-byte record holds it. */
public record CardXrefRecord(String cardNum, String custId, String acctId) {

    public static CardXrefRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.CARD_XREF, data);
        return new CardXrefRecord(r.raw("XREF-CARD-NUM"), r.digits("XREF-CUST-ID"), r.digits("XREF-ACCT-ID"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.CARD_XREF)
                .text("XREF-CARD-NUM", cardNum)
                .digits("XREF-CUST-ID", custId)
                .digits("XREF-ACCT-ID", acctId)
                .build();
    }

    @Override
    public String toString() {
        return "CardXrefRecord[" + Masking.lastFour(cardNum) + "]";
    }
}
