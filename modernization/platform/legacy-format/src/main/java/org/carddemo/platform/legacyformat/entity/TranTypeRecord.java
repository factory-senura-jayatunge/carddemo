package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-TRAN-TYPE as the 60-byte record holds it. */
public record TranTypeRecord(String typeCd, String description) {

    public static TranTypeRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.TRAN_TYPE, data);
        return new TranTypeRecord(r.raw("TRAN-TYPE"), r.text("TRAN-TYPE-DESC"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.TRAN_TYPE)
                .text("TRAN-TYPE", typeCd)
                .text("TRAN-TYPE-DESC", description)
                .build();
    }
}
