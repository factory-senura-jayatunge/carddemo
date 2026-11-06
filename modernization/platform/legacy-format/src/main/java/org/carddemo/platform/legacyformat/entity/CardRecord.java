package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-CARD as the 150-byte record holds it; {@code legacyFiller} is the 59-byte FILLER. */
public record CardRecord(
        String cardNum,
        String acctId,
        String cvvCd,
        String embossedName,
        String expirationDate,
        String activeStatus,
        String legacyFiller) {

    public static CardRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.CARD, data);
        return new CardRecord(
                r.raw("CARD-NUM"),
                r.digits("CARD-ACCT-ID"),
                r.digits("CARD-CVV-CD"),
                r.text("CARD-EMBOSSED-NAME"),
                r.text("CARD-EXPIRAION-DATE"),
                r.text("CARD-ACTIVE-STATUS"),
                r.raw("CARD-FILLER"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.CARD)
                .text("CARD-NUM", cardNum)
                .digits("CARD-ACCT-ID", acctId)
                .digits("CARD-CVV-CD", cvvCd)
                .text("CARD-EMBOSSED-NAME", embossedName)
                .text("CARD-EXPIRAION-DATE", expirationDate)
                .text("CARD-ACTIVE-STATUS", activeStatus)
                .raw("CARD-FILLER", legacyFiller)
                .build();
    }

    @Override
    public String toString() {
        return "CardRecord[" + Masking.lastFour(cardNum) + "]";
    }
}
