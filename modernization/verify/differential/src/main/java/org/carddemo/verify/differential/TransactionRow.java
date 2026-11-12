package org.carddemo.verify.differential;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.carddemo.platform.legacyformat.LegacyTimestamps;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;

/**
 * A {@code core.transaction} row of a cycle and its 350-byte TRAN-RECORD. The timestamps go back
 * to the layout their legacy writer used: CBTRN02C copies DALYTRAN-ORIG-TS (microseconds, the
 * only layout the posting accepts) and writes TRAN-PROC-TS in the DB2 layout; CBACT04C writes
 * both in the DB2 layout. A NULL {@code legacy_filler} is the layout's 20 spaces, which both
 * programs write.
 */
record TransactionRow(String tranId, String typeCd, String catCd, String source, String description,
        BigDecimal amount, String merchantId, String merchantName, String merchantCity, String merchantZip,
        String cardNum, LocalDateTime origTs, LocalDateTime procTs, String legacyFiller, String origin) {

    static final String COLUMNS = "tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,"
            + " merchant_city, merchant_zip, card_num, orig_ts, proc_ts, legacy_filler, origin";

    static TransactionRow of(ResultSet rs) throws SQLException {
        return new TransactionRow(rs.getString("tran_id"), rs.getString("type_cd"), rs.getString("cat_cd"),
                rs.getString("source"), rs.getString("description"), rs.getBigDecimal("amount"),
                rs.getString("merchant_id"), rs.getString("merchant_name"), rs.getString("merchant_city"),
                rs.getString("merchant_zip"), rs.getString("card_num"),
                rs.getObject("orig_ts", LocalDateTime.class), rs.getObject("proc_ts", LocalDateTime.class),
                rs.getString("legacy_filler"), rs.getString("origin"));
    }

    TransactionRecord toRecord() {
        String orig = switch (origin) {
            case "POSTED", "ONLINE_ADD" -> LegacyTimestamps.isoMicros(origTs);
            case "INTEREST" -> LegacyTimestamps.posting(origTs);
            default -> throw new ExportException("a transaction of origin " + origin
                    + " is not written by POSTTRAN or INTCALC");
        };
        return new TransactionRecord(tranId, typeCd, catCd, source, description, amount, merchantId, merchantName,
                merchantCity, merchantZip, cardNum, orig, LegacyTimestamps.posting(procTs), legacyFiller);
    }
}
