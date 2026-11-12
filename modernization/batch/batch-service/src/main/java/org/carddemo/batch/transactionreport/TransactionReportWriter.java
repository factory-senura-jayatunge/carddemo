package org.carddemo.batch.transactionreport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.platform.legacyformat.Latin1;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.NumericEdit;
import org.carddemo.platform.legacyformat.Pic;

/**
 * The TRANREPT report of CBTRN03C (programs/CBTRN03C.md sections 4.2, 6, 7 and 12): 133-character
 * lines, each followed by LF, in ISO-8859-1. Rows arrive in report order; the writer keeps the
 * program's line counter, page breaks, card or account breaks and totals.
 */
final class TransactionReportWriter {

    static final int LINE = 133;
    /** WS-PAGE-SIZE. */
    static final int PAGE_SIZE = 20;
    /** S9(09)V99 keeps 11 digits: NUM-TRPT-001 drops the high-order ones and keeps the sign. */
    private static final long TOTAL_MODULUS = 100_000_000_000L;
    private static final Pic AMOUNT = Pic.signed(9, 2);
    private static final NumericEdit DETAIL_AMOUNT = NumericEdit.of("-ZZZ,ZZZ,ZZZ.ZZ");
    private static final NumericEdit TOTAL_AMOUNT = NumericEdit.of("+ZZZ,ZZZ,ZZZ.ZZ");
    private static final String RULE = "-".repeat(LINE);

    private final OutputStream out;
    private final String startDate;
    private final String endDate;
    private final boolean skipOutOfRange;
    private final boolean breakOnAccount;

    private boolean firstTime = true;
    private boolean stopped;
    private boolean anyRow;
    private long lineCounter;
    private String currentKey;
    private long pageTotal;
    private long accountTotal;
    private long grandTotal;
    private long details;
    private long lines;

    /**
     * @param skipOutOfRange DEF-TRPT-001 fixed: skip a row outside the range and always write the
     *        end-of-file totals; legacy: the first such row ends the report without totals
     * @param breakOnAccount DEF-TRPT-004 fixed: the "Account Total" break is on the account ID;
     *        legacy: on the card number. The rows must come in the matching order.
     */
    TransactionReportWriter(OutputStream out, String startDate, String endDate, boolean skipOutOfRange,
            boolean breakOnAccount) {
        this.out = out;
        this.startDate = startDate;
        this.endDate = endDate;
        this.skipOutOfRange = skipOutOfRange;
        this.breakOnAccount = breakOnAccount;
        // WS-CURR-CARD-NUM starts as spaces (BR-TRPT-004).
        this.currentKey = " ".repeat(breakOnAccount ? 11 : 16);
    }

    /** Writes one row's lines; false once the report has ended (DEF-TRPT-001 legacy). */
    boolean accept(ReportRow row) {
        if (stopped) {
            return false;
        }
        // BR-TRPT-002 compares the 10-character texts.
        String date = pad(row.procDate(), 10);
        if (date.compareTo(pad(startDate, 10)) < 0 || date.compareTo(pad(endDate, 10)) > 0) {
            if (!skipOutOfRange) {
                stopped = true;
                return false;
            }
            return true;
        }
        anyRow = true;
        String key = breakOnAccount ? row.acctId() : row.cardNum();
        if (!key.equals(currentKey)) {
            if (!firstTime) {
                accountTotals();
            }
            currentKey = key;
        }
        if (firstTime) {
            firstTime = false;
            headers();
        }
        if (lineCounter % PAGE_SIZE == 0) {
            pageTotals();
            headers();
        }
        long cents = cents(row.amount());
        pageTotal = add(pageTotal, cents);
        accountTotal = add(accountTotal, cents);
        write(pad(row.tranId(), 16) + " " + pad(row.acctId(), 11) + " " + pad(row.typeCd(), 2) + "-"
                + pad(row.typeDesc(), 50).substring(0, 15) + " " + pad(row.catCd(), 4) + "-"
                + pad(row.catDesc(), 50).substring(0, 29) + " " + pad(row.source(), 10) + "    "
                + DETAIL_AMOUNT.format(row.amount(), AMOUNT));
        lineCounter++;
        details++;
        return true;
    }

    /**
     * End of input (BR-TRPT-006). In legacy mode the range test also runs on the record the
     * end-of-file read leaves in the work area: after an empty input that is the initial record,
     * below any start date, and after a stop the report has ended; neither gets totals.
     */
    void finish() {
        if (!skipOutOfRange && (stopped || !anyRow)) {
            return;
        }
        if (!firstTime) {
            accountTotals();
        }
        pageTotals();
        write(pad("Grand Total" + ".".repeat(86) + TOTAL_AMOUNT.format(BigDecimal.valueOf(grandTotal, 2), AMOUNT),
                LINE));
    }

    long details() {
        return details;
    }

    long lines() {
        return lines;
    }

    private void headers() {
        write("DALYREPT" + " ".repeat(30) + pad("Daily Transaction Report", 41) + "Date Range: " + pad(startDate, 10)
                + " to " + pad(endDate, 10));
        write("");
        write(pad("Transaction ID", 17) + pad("Account ID", 12) + pad("Transaction Type", 19)
                + pad("Tran Category", 35) + pad("Tran Source", 14) + " " + pad("        Amount", 16));
        write(RULE);
        lineCounter += 4;
    }

    private void accountTotals() {
        write("Account Total" + ".".repeat(84) + total(accountTotal));
        accountTotal = 0;
        write(RULE);
        lineCounter += 2;
    }

    private void pageTotals() {
        write("Page Total " + ".".repeat(86) + total(pageTotal));
        grandTotal = add(grandTotal, pageTotal);
        pageTotal = 0;
        write(RULE);
        lineCounter += 2;
    }

    private static String total(long cents) {
        return TOTAL_AMOUNT.format(BigDecimal.valueOf(cents, 2), AMOUNT);
    }

    private static long cents(BigDecimal amount) {
        try {
            long cents = amount.movePointRight(2).longValueExact();
            if (Math.abs(cents) >= TOTAL_MODULUS) {
                throw new ArithmeticException();
            }
            return cents;
        } catch (ArithmeticException e) {
            throw new CatalogueFailure("REPORT_LAYOUT", "transaction amount");
        }
    }

    /** ADD without ON SIZE ERROR into S9(09)V99 (NUM-TRPT-001). */
    private static long add(long total, long cents) {
        long sum = total + cents;
        return Long.signum(sum) * (Math.abs(sum) % TOTAL_MODULUS);
    }

    private void write(String text) {
        String line = pad(text, LINE);
        byte[] bytes;
        try {
            bytes = Latin1.encode(line, "TRANREPT line");
        } catch (LegacyFormatException e) {
            throw new CatalogueFailure("REPORT_LAYOUT", "report line");
        }
        try {
            out.write(bytes);
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lines++;
    }

    /** A MOVE into X(n): cut or padded with spaces on the right. */
    static String pad(String text, int length) {
        String value = text == null ? "" : text;
        return value.length() >= length ? value.substring(0, length) : value + " ".repeat(length - value.length());
    }
}
