package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * The 350-byte records, compared with records composed by hand from CVTRA05Y as the spec's
 * reference models compose them (posttran_from_spec: the daily record's first 304 bytes, the
 * processing timestamp, 20 spaces; intcalc_from_spec: both timestamps in the DB2 layout).
 */
class TransactionRowTest {

    private static final String CARD = "CARDNUMBER000001";
    private static final LocalDateTime ORIG = LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000);
    private static final LocalDateTime PROC = LocalDateTime.of(2022, 7, 6, 10, 11, 12, 340_000_000);

    @Test
    void aPostedRowIsTheDailyRecordWithTheProcessingTimestamp() {
        TransactionRow row = new TransactionRow("0000000000683580", "01", "0001", "POS TERM", "Purchase at shop",
                new BigDecimal("50.47"), "800000001", "Shop name", "City", "12345", CARD, ORIG, PROC, null, "POSTED");
        String expected = "0000000000683580" + "01" + "0001" + pad("POS TERM", 10) + pad("Purchase at shop", 100)
                + "0000000504G" + "800000001" + pad("Shop name", 50) + pad("City", 50) + pad("12345", 10) + CARD
                + "2022-06-10 19:27:53.123456" + "2022-07-06-10.11.12.340000" + " ".repeat(20);
        assertThat(text(row)).isEqualTo(expected);
    }

    @Test
    void anInterestRowHasBothTimestampsInTheDb2Layout() {
        TransactionRow row = new TransactionRow("2022071800000001", "01", "0005", "System",
                "Int. for a/c 00000000001", new BigDecimal("-12.30"), "000000000", "", "", "", CARD, PROC, PROC, null,
                "INTEREST");
        String expected = "2022071800000001" + "01" + "0005" + pad("System", 10) + pad("Int. for a/c 00000000001", 100)
                + "0000000123}" + "000000000" + " ".repeat(110) + CARD
                + "2022-07-06-10.11.12.340000" + "2022-07-06-10.11.12.340000" + " ".repeat(20);
        assertThat(text(row)).isEqualTo(expected);
    }

    @Test
    void aStoredFillerIsKept() {
        TransactionRow row = new TransactionRow("0000000000683580", "01", "0001", "POS TERM", "x", BigDecimal.ONE,
                "800000001", "", "", "", CARD, ORIG, PROC, "FILLER-FROM-ONLINE01", "ONLINE_ADD");
        assertThat(text(row)).endsWith("FILLER-FROM-ONLINE01");
    }

    @Test
    void rowsNoCycleJobWritesAreRefused() {
        for (String origin : new String[] {"LOADED", "BILL_PAY"}) {
            TransactionRow row = new TransactionRow("0000000000683580", "01", "0001", "s", "d", BigDecimal.ONE,
                    "800000001", "", "", "", CARD, ORIG, PROC, null, origin);
            assertThatThrownBy(row::toRecord).isInstanceOf(ExportException.class).hasMessageNotContaining(CARD);
        }
    }

    private static String text(TransactionRow row) {
        byte[] bytes = row.toRecord().toBytes();
        assertThat(bytes).hasSize(350);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(width - s.length());
    }
}
