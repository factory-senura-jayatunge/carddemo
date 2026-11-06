package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;

/** ENT-TRAN-CATEGORY as the 60-byte record holds it. */
public record TranCategoryRecord(String typeCd, String catCd, String description) {

    public static TranCategoryRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.TRAN_CATEGORY, data);
        return new TranCategoryRecord(r.raw("TRAN-TYPE-CD"), r.digits("TRAN-CAT-CD"), r.text("TRAN-CAT-TYPE-DESC"));
    }

    public byte[] toBytes() {
        return RecordBuilder.of(Layouts.TRAN_CATEGORY)
                .text("TRAN-TYPE-CD", typeCd)
                .digits("TRAN-CAT-CD", catCd)
                .text("TRAN-CAT-TYPE-DESC", description)
                .build();
    }
}
