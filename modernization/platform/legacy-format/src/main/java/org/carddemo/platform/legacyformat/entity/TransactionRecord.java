package org.carddemo.platform.legacyformat.entity;

import java.math.BigDecimal;

import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordBuilder;
import org.carddemo.platform.legacyformat.record.RecordLayout;

/**
 * ENT-TRANSACTION, and ENT-DAILY-TRAN, which has the same positions under
 * {@code DALYTRAN-} names. Timestamps stay as the writer wrote them;
 * {@code legacyFiller} is the 20-byte FILLER.
 */
public record TransactionRecord(
        String tranId,
        String typeCd,
        String catCd,
        String source,
        String description,
        BigDecimal amount,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String cardNum,
        String origTs,
        String procTs,
        String legacyFiller) {

    public static TransactionRecord parse(byte[] data) {
        return parse(Layouts.TRANSACTION, "TRAN-", "TRAN-ID", data);
    }

    public static TransactionRecord parseDaily(byte[] data) {
        return parse(Layouts.DAILY_TRAN, "DALYTRAN-", "DALYTRAN-ID", data);
    }

    private static TransactionRecord parse(RecordLayout layout, String prefix, String idField, byte[] data) {
        FixedRecord r = FixedRecord.of(layout, data);
        return new TransactionRecord(
                r.raw(idField),
                r.text(prefix + "TYPE-CD"),
                r.digits(prefix + "CAT-CD"),
                r.text(prefix + "SOURCE"),
                r.text(prefix + "DESC"),
                r.decimal(prefix + "AMT"),
                r.digits(prefix + "MERCHANT-ID"),
                r.text(prefix + "MERCHANT-NAME"),
                r.text(prefix + "MERCHANT-CITY"),
                r.text(prefix + "MERCHANT-ZIP"),
                r.raw(prefix + "CARD-NUM"),
                r.raw(prefix + "ORIG-TS"),
                r.raw(prefix + "PROC-TS"),
                r.raw(prefix.equals("TRAN-") ? "TRAN-FILLER" : "DALYTRAN-FILLER"));
    }

    public byte[] toBytes() {
        return build(Layouts.TRANSACTION, "TRAN-", "TRAN-ID", "TRAN-FILLER");
    }

    public byte[] toDailyBytes() {
        return build(Layouts.DAILY_TRAN, "DALYTRAN-", "DALYTRAN-ID", "DALYTRAN-FILLER");
    }

    private byte[] build(RecordLayout layout, String prefix, String idField, String fillerField) {
        return RecordBuilder.of(layout)
                .text(idField, tranId)
                .text(prefix + "TYPE-CD", typeCd)
                .digits(prefix + "CAT-CD", catCd)
                .text(prefix + "SOURCE", source)
                .text(prefix + "DESC", description)
                .decimal(prefix + "AMT", amount)
                .digits(prefix + "MERCHANT-ID", merchantId)
                .text(prefix + "MERCHANT-NAME", merchantName)
                .text(prefix + "MERCHANT-CITY", merchantCity)
                .text(prefix + "MERCHANT-ZIP", merchantZip)
                .text(prefix + "CARD-NUM", cardNum)
                .text(prefix + "ORIG-TS", origTs)
                .text(prefix + "PROC-TS", procTs)
                .raw(fillerField, legacyFiller)
                .build();
    }

    @Override
    public String toString() {
        return "TransactionRecord[" + tranId + ", card " + Masking.lastFour(cardNum) + "]";
    }
}
