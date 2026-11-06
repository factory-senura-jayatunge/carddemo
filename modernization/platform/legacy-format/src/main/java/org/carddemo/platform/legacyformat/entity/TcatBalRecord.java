package org.carddemo.platform.legacyformat.entity;

import java.math.BigDecimal;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-TCAT-BAL as the 50-byte record holds it; {@code legacyFiller} is the 22-byte FILLER. */
public record TcatBalRecord(String acctId, String typeCd, String catCd, BigDecimal balance, String legacyFiller) {

    public static TcatBalRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.TCAT_BAL, data);
        return new TcatBalRecord(r.digits("TRANCAT-ACCT-ID"), r.raw("TRANCAT-TYPE-CD"), r.digits("TRANCAT-CD"),
                r.decimal("TRAN-CAT-BAL"), r.raw("TCATBAL-FILLER"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.TCAT_BAL)
                .digits("TRANCAT-ACCT-ID", acctId)
                .text("TRANCAT-TYPE-CD", typeCd)
                .digits("TRANCAT-CD", catCd)
                .decimal("TRAN-CAT-BAL", balance)
                .raw("TCATBAL-FILLER", legacyFiller)
                .build();
    }

    @Override
    public String toString() {
        return "TcatBalRecord[" + acctId + "/" + typeCd + "/" + catCd + "]";
    }
}
