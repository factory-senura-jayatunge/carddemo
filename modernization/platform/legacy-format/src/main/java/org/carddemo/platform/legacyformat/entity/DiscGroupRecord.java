package org.carddemo.platform.legacyformat.entity;

import java.math.BigDecimal;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-DISC-GROUP as the 50-byte record holds it; {@code intRate} is the annual percent. */
public record DiscGroupRecord(String groupId, String typeCd, String catCd, BigDecimal intRate) {

    public static DiscGroupRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.DISC_GROUP, data);
        return new DiscGroupRecord(r.text("DIS-ACCT-GROUP-ID"), r.raw("DIS-TRAN-TYPE-CD"), r.digits("DIS-TRAN-CAT-CD"),
                r.decimal("DIS-INT-RATE"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.DISC_GROUP)
                .text("DIS-ACCT-GROUP-ID", groupId)
                .text("DIS-TRAN-TYPE-CD", typeCd)
                .digits("DIS-TRAN-CAT-CD", catCd)
                .decimal("DIS-INT-RATE", intRate)
                .build();
    }
}
