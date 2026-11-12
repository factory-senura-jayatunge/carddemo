package org.carddemo.batch.statement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.carddemo.platform.legacyformat.Latin1;
import org.carddemo.platform.legacyformat.LegacyNumbers;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.NumericEdit;
import org.carddemo.platform.legacyformat.Pic;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;

/**
 * The legacy renderer of {@code statementJob} (CBSTM03A, DEF-STMT-005 legacy): the text statement
 * file of 80-byte records (NUM-STMT-001) and the HTML statement file of 100-byte records
 * (NUM-STMT-005, NUM-STMT-006), byte for byte as the program writes them (CBSTM03A section 15), the
 * records concatenated without line ends (NUM-STMT-007).
 *
 * <p>The inputs are those of CREASTMT STEP040: the cross-reference in key order (BR-STMT-001), the
 * customers and accounts by key (BR-STMT-004), and the TRXFL work file in card and transaction ID
 * order, which {@link #workFile} builds from the master as STEP010 sorts it. Every input is checked
 * before the first byte is written, so a refused run leaves both outputs empty: a work file beyond
 * the program's table (DEF-STMT-001 legacy, {@link LegacyStatementException#STATEMENT_TABLE_LIMIT})
 * and a missing customer or account, where the program abends after the statements before it.
 *
 * <p>Kept as the program has them: the first-word names and addresses and the truncated
 * descriptions (DEF-STMT-002), the HTML lines cut at a double space or {@code *} (DEF-STMT-003), the
 * balance edited with nine integer digits (DEF-STMT-004), unescaped HTML (DEF-STMT-007), and the
 * total that keeps only nine integer digits after each addition (NUM-STMT-004). An empty work file,
 * on which the program abends, gives statements without transactions (DEF-STMT-010, fixed in both
 * modes).
 */
public final class LegacyStatementRenderer {

    /** {@code WS-CARD-TBL OCCURS 51 TIMES} (app/cbl/CBSTM03A.CBL:226). */
    public static final int MAX_CARDS = 51;
    /** {@code WS-TRAN-TBL OCCURS 10 TIMES} (app/cbl/CBSTM03A.CBL:228). */
    public static final int MAX_TRANSACTIONS_PER_CARD = 10;
    public static final int TEXT_RECORD_LENGTH = 80;
    public static final int HTML_RECORD_LENGTH = 100;

    /** STEP010 {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}: Latin-1 characters compare as their bytes. */
    public static final Comparator<WorkRecord> KEY_ORDER =
            Comparator.comparing(WorkRecord::cardNum).thenComparing(WorkRecord::tranId);

    private static final Pic ACCT_CURR_BAL = Pic.signed(10, 2);
    private static final Pic TRNX_AMT = Pic.signed(9, 2);
    private static final NumericEdit ST_CURR_BAL = NumericEdit.of("9(9).99-");
    private static final NumericEdit ST_TRANAMT = NumericEdit.of("Z(9).99-");

    private static final String ST_LINE0 = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
    private static final String DASHES = "-".repeat(80);
    private static final String ST_LINE6 = " ".repeat(33) + "Basic Details " + " ".repeat(33);
    private static final String ST_LINE11 = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);
    private static final String ST_LINE13 = "Tran ID         " + pad("Tran Details    ", 51) + "  Tran Amount";
    private static final String ST_LINE15 = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    private static final String TRS = "<tr>";
    private static final String TRE = "</tr>";
    private static final String TDE = "</td>";
    private static final List<String> HTML_HEAD = List.of("<!DOCTYPE html>", "<html lang=\"en\">", "<head>",
            "<meta charset=\"utf-8\">", "<title>HTML Table Layout</title>", "</head>", "<body style=\"margin:0px;\">",
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">");
    private static final String HTML_L10 = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
    private static final String HTML_L15 = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
    private static final String HTML_L22_35 = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
    private static final String P16 = "<p style=\"font-size:16px\">";
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    private LegacyStatementRenderer() {
    }

    /**
     * One TRXFL record as the program keeps it in its table: the key and the two fields of
     * {@code TRNX-REST} that are printed (BR-STMT-010). The card number and transaction ID are the
     * 16-character fields as stored, the description at most 100 characters.
     */
    public record WorkRecord(String cardNum, String tranId, String description, BigDecimal amount) {

        public WorkRecord {
            Objects.requireNonNull(amount, "amount");
            if (cardNum == null || cardNum.length() != 16 || tranId == null || tranId.length() != 16) {
                throw new IllegalArgumentException("card number and transaction ID must be 16 characters");
            }
            if (description == null || description.length() > 100) {
                throw new IllegalArgumentException("description must be at most 100 characters");
            }
            if (!LegacyNumbers.fits(amount, TRNX_AMT)) {
                throw new IllegalArgumentException("amount does not fit " + TRNX_AMT);
            }
        }

        public static WorkRecord of(TransactionRecord t) {
            return new WorkRecord(t.cardNum(), t.tranId(), t.description(), t.amount());
        }

        @Override
        public String toString() {
            return "WorkRecord[" + tranId + ", card " + Masking.lastFour(cardNum) + "]";
        }
    }

    /**
     * What a run wrote: statements, the transactions listed on them, the work-file transactions of
     * cards outside the cross-reference, which no statement lists (BR-STMT-009), and the records
     * of each file.
     */
    public record Result(int statements, int listed, int unlisted, long textRecords, long htmlRecords) {
    }

    /** The two files of a run in memory, for tests and small runs. */
    public record Rendered(byte[] text, byte[] html, Result result) {

        @Override
        public String toString() {
            return "Rendered[" + result + "]";
        }
    }

    /** The TRXFL work file of CREASTMT STEP010 and STEP020: the master in card and transaction ID order. */
    public static List<WorkRecord> workFile(Collection<TransactionRecord> master) {
        return master.stream().map(WorkRecord::of).sorted(KEY_ORDER).toList();
    }

    public static Rendered renderToBytes(List<CardXrefRecord> xref, Map<String, CustomerRecord> customersById,
            Map<String, AccountRecord> accountsById, List<WorkRecord> workFile) {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        ByteArrayOutputStream html = new ByteArrayOutputStream();
        try {
            Result result = render(xref, customersById, accountsById, workFile, text, html);
            return new Rendered(text.toByteArray(), html.toByteArray(), result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the statements, one per cross-reference record in the order given (BR-STMT-001).
     *
     * @param xref the cross-reference in key order, as a sequential read of CARDXREF returns it
     * @param customersById customers by {@code CUST-ID} (9 digits)
     * @param accountsById accounts by {@code ACCT-ID} (11 digits)
     * @param workFile TRXFL, in ascending card number and transaction ID order ({@link #workFile})
     * @throws LegacyStatementException before any byte is written, when the work file does not fit
     *         the table or a customer or account is missing
     * @throws IllegalArgumentException when the work file is not in strictly ascending key order,
     *         which the TRXFL KSDS guarantees
     */
    public static Result render(List<CardXrefRecord> xref, Map<String, CustomerRecord> customersById,
            Map<String, AccountRecord> accountsById, List<WorkRecord> workFile, OutputStream text,
            OutputStream html) throws IOException {
        Map<String, List<WorkRecord>> table = load(workFile);
        List<Statement> statements = lookUp(xref, customersById, accountsById);
        Sink textSink = new Sink(text, TEXT_RECORD_LENGTH);
        Sink htmlSink = new Sink(html, HTML_RECORD_LENGTH);
        int listed = 0;
        Set<String> printedCards = new HashSet<>();
        for (Statement s : statements) {
            List<WorkRecord> transactions = table.getOrDefault(s.xref().cardNum(), List.of());
            writeStatement(s, transactions, textSink, htmlSink);
            listed += transactions.size();
            printedCards.add(s.xref().cardNum());
        }
        int unlisted = 0;
        for (Map.Entry<String, List<WorkRecord>> slot : table.entrySet()) {
            if (!printedCards.contains(slot.getKey())) {
                unlisted += slot.getValue().size();
            }
        }
        return new Result(statements.size(), listed, unlisted, textSink.records, htmlSink.records);
    }

    private record Statement(CardXrefRecord xref, CustomerRecord customer, AccountRecord account) {
    }

    /**
     * FLOW-STMT-003: one card slot per run of equal card numbers, its transactions in file order.
     * DEF-STMT-001 legacy: a card beyond slot 51 or a transaction beyond slot 10 of its card is
     * refused, whether or not the card is in the cross-reference (BR-STMT-009), instead of the
     * program's storage overlay.
     */
    private static Map<String, List<WorkRecord>> load(List<WorkRecord> workFile) {
        Map<String, List<WorkRecord>> slots = new LinkedHashMap<>();
        WorkRecord previous = null;
        int most = 0;
        for (int i = 0; i < workFile.size(); i++) {
            WorkRecord r = workFile.get(i);
            if (previous != null && KEY_ORDER.compare(previous, r) >= 0) {
                throw new IllegalArgumentException("work file record " + (i + 1)
                        + " is not above the one before it in card and transaction ID order");
            }
            List<WorkRecord> slot = slots.computeIfAbsent(r.cardNum(), k -> new ArrayList<>());
            slot.add(r);
            most = Math.max(most, slot.size());
            previous = r;
        }
        if (slots.size() > MAX_CARDS || most > MAX_TRANSACTIONS_PER_CARD) {
            throw LegacyStatementException.tableLimit(slots.size(), most);
        }
        return slots;
    }

    private static List<Statement> lookUp(List<CardXrefRecord> xref, Map<String, CustomerRecord> customersById,
            Map<String, AccountRecord> accountsById) {
        List<Statement> out = new ArrayList<>(xref.size());
        for (int i = 0; i < xref.size(); i++) {
            CardXrefRecord x = xref.get(i);
            CustomerRecord customer = customersById.get(x.custId());
            if (customer == null) {
                throw LegacyStatementException.notFound(LegacyStatementException.STATEMENT_CUSTOMER_NOT_FOUND,
                        "customer", i + 1);
            }
            AccountRecord account = accountsById.get(x.acctId());
            if (account == null) {
                throw LegacyStatementException.notFound(LegacyStatementException.STATEMENT_ACCOUNT_NOT_FOUND,
                        "account", i + 1);
            }
            out.add(new Statement(x, customer, account));
        }
        return out;
    }

    private static void writeStatement(Statement s, List<WorkRecord> transactions, Sink text, Sink html)
            throws IOException {
        CustomerRecord c = s.customer();
        String name = name(c);
        String add1 = pad(c.addrLine1(), 50);
        String add2 = pad(c.addrLine2(), 50);
        String add3 = addressLine3(c);
        String acctId = pad(s.account().acctId(), 20);
        String balance = ST_CURR_BAL.format(s.account().currBal(), ACCT_CURR_BAL);
        String fico = pad(String.format("%03d", c.ficoScore()), 20);

        text.put(ST_LINE0);
        text.put(name);
        text.put(add1);
        text.put(add2);
        text.put(add3);
        text.put(DASHES);
        text.put(ST_LINE6);
        text.put(DASHES);
        text.put("Account ID         :" + acctId);
        text.put("Current Balance    :" + balance);
        text.put("FICO Score         :" + fico);
        text.put(DASHES);
        text.put(ST_LINE11);
        text.put(DASHES);
        text.put(ST_LINE13);
        text.put(DASHES);

        html.putAll(HTML_HEAD);
        html.putAll(TRS, HTML_L10, "<h3>Statement for Account Number: " + acctId + "</h3>", TDE, TRE);
        html.putAll(TRS, HTML_L15, P16 + "Bank of XYZ</p>", "<p>410 Terry Ave N</p>", "<p>Seattle WA 99999</p>",
                TDE, TRE);
        html.putAll(TRS, HTML_L22_35);
        html.put(P16 + cutAt(pad(name, 50), "  ") + "  </p>");
        html.put("<p>" + cutAt(add1, "  ") + "  </p>");
        html.put("<p>" + cutAt(add2, "  ") + "  </p>");
        html.put("<p>" + cutAt(add3, "  ") + "  </p>");
        html.putAll(TDE, TRE, TRS, HTML_L30_42, P16 + "Basic Details</p>", TDE, TRE, TRS, HTML_L22_35);
        html.put("<p>Account ID         : " + cutAt(acctId, "*") + "</p>");
        html.put("<p>Current Balance    : " + cutAt(balance, "*") + "</p>");
        html.put("<p>FICO Score         : " + cutAt(fico, "*") + "</p>");
        html.putAll(TDE, TRE, TRS, HTML_L30_42, P16 + "Transaction Summary</p>", TDE, TRE);
        html.putAll(TRS, HTML_L47, P16 + "Tran ID</p>", TDE, HTML_L50, P16 + "Tran Details</p>", TDE, HTML_L53,
                P16 + "Amount</p>", TDE, TRE);

        BigDecimal total = BigDecimal.ZERO;
        for (WorkRecord t : transactions) {
            String desc = pad(t.description(), 49);
            String amount = ST_TRANAMT.format(t.amount(), TRNX_AMT);
            text.put(t.tranId() + " " + desc + "$" + amount);
            html.putAll(TRS, HTML_L58, "<p>" + cutAt(t.tranId(), "*") + "</p>", TDE);
            html.putAll(HTML_L61, "<p>" + cutAt(desc, "*") + "</p>", TDE);
            html.putAll(HTML_L64, "<p>" + cutAt(amount, "*") + "</p>", TDE, TRE);
            total = LegacyNumbers.truncate(total.add(t.amount()), TRNX_AMT);
        }

        text.put(DASHES);
        text.put("Total EXP:" + " ".repeat(56) + "$" + ST_TRANAMT.format(total, TRNX_AMT));
        text.put(ST_LINE15);
        html.putAll(TRS, HTML_L10, "<h3>End of Statement</h3>", TDE, TRE, "</table>", "</body>", "</html>");
    }

    /** BR-STMT-005: the first word of each name part, each followed by one space, in 75 characters. */
    private static String name(CustomerRecord c) {
        return pad(firstWord(c.firstName(), 25) + " " + firstWord(c.middleName(), 25) + " "
                + firstWord(c.lastName(), 25) + " ", 75);
    }

    /** BR-STMT-006: the first word of city, state, country and ZIP, each followed by one space. */
    private static String addressLine3(CustomerRecord c) {
        return pad(firstWord(c.addrLine3(), 50) + " " + firstWord(c.stateCd(), 2) + " "
                + firstWord(c.countryCd(), 3) + " " + firstWord(c.zip(), 10) + " ", 80);
    }

    /** {@code STRING field DELIMITED BY ' '} of the field as its record holds it. */
    private static String firstWord(String value, int length) {
        return cutAt(pad(value, length), " ");
    }

    /** {@code STRING value DELIMITED BY delimiter}: up to the first occurrence, or the whole value. */
    private static String cutAt(String value, String delimiter) {
        int at = value.indexOf(delimiter);
        return at < 0 ? value : value.substring(0, at);
    }

    /** MOVE to an {@code X(length)} item: cut on the right or padded with spaces. */
    private static String pad(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }

    /** One output file of fixed records, each padded with spaces to the record length. */
    private static final class Sink {

        private final OutputStream out;
        private final int length;
        private long records;

        Sink(OutputStream out, int length) {
            this.out = out;
            this.length = length;
        }

        void put(String line) throws IOException {
            if (line.length() > length) {
                throw new IllegalStateException("a statement line has " + line.length() + " characters, the record "
                        + length);
            }
            out.write(Latin1.encode(pad(line, length), "statement record " + (records + 1)));
            records++;
        }

        void putAll(String... lines) throws IOException {
            for (String line : lines) {
                put(line);
            }
        }

        void putAll(List<String> lines) throws IOException {
            for (String line : lines) {
                put(line);
            }
        }
    }
}
