package org.carddemo.batch.statement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * One fixed-mode statement as a PDF (batch.md, "PDF"; DEF-STMT-005 fixed): US Letter, the header
 * (statement date, period, account ID, page {@code n of m}) on every page; on the first page the
 * account holder's name and address, the credit limit, the opening balance, the three activity
 * totals, the adjustment line when {@code A != 0}, the closing balance and the minimum payment;
 * then the period's transactions with the processing date, the full description, the card's last
 * 4 digits and the amount. Every value prints in full, wrapped and never cut (D-44, D-75); there
 * is no FICO score and no full card number (D-10).
 *
 * <p>The text is an embedded subset of Noto Sans. A character the font lacks prints as U+FFFD, and
 * the control characters, U+00A0 and U+00AD print as {@code ‹U+XXXX›} (data-model.md, display rule
 * 8). The stored text repertoire is U+0000 to U+00FF, so the single guillemets cannot come from the
 * data and the placeholder is never ambiguous.
 *
 * <p>The same statement gives the same bytes: the creation date is the statement date, the
 * producer is fixed, no modification date or XMP metadata is written, and the document ID is
 * derived from the run and account IDs. Only the PDF creation API is used; no PDF is parsed.
 */
final class StatementPdf {

    static final String PRODUCER = "CardDemo statementJob";
    static final String ADJUSTMENT = "Other balance adjustments";
    static final String NO_TRANSACTIONS = "No transactions in this period.";

    private static final byte[] REGULAR = font("NotoSans-Regular.ttf");
    private static final byte[] BOLD = font("NotoSans-Bold.ttf");
    // Parsed once: the lookups only read their tables after the parse, so threads can share them.
    private static final CmapLookup REGULAR_GLYPHS = glyphs(REGULAR);
    private static final CmapLookup BOLD_GLYPHS = glyphs(BOLD);

    private static final float LEFT = 54;
    private static final float RIGHT = PDRectangle.LETTER.getWidth() - 54;
    private static final float TOP = PDRectangle.LETTER.getHeight() - 54;
    private static final float BOTTOM = 54;
    private static final float BODY_TOP = TOP - 62;
    private static final float DESCRIPTION_X = LEFT + 72;
    private static final float CARD_X = RIGHT - 160;
    private static final float DESCRIPTION_WIDTH = CARD_X - DESCRIPTION_X - 12;
    private static final float VALUE_RIGHT = LEFT + 330;
    private static final float SIZE = 9;
    private static final float LEADING = 12;

    /** One row of the period, in statement order. */
    record Line(LocalDate procDate, String description, String cardLast4, BigDecimal amount) {
        Line {
            Objects.requireNonNull(procDate, "procDate");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
            if (cardLast4 == null || cardLast4.length() != 4) {
                throw new IllegalArgumentException("a statement line shows exactly the card's last 4 digits");
            }
        }
    }

    /**
     * @param name the account holder's full name
     * @param address the address lines as printed, in order
     * @param figures the account's figures; {@code periodEnd} is the statement date
     */
    record Statement(long runId, String acctId, String name, List<String> address, StatementFigures figures,
            List<Line> lines) {
        Statement {
            Objects.requireNonNull(acctId, "acctId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(figures, "figures");
            address = List.copyOf(address);
            lines = List.copyOf(lines);
        }
    }

    private StatementPdf() {
    }

    static byte[] render(Statement statement) {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = PDType0Font.load(doc, new ByteArrayInputStream(REGULAR), true);
            PDFont bold = PDType0Font.load(doc, new ByteArrayInputStream(BOLD), true);
            Layout layout = new Layout(regular, bold);
            summary(layout, statement);
            transactions(layout, statement);
            int total = layout.pages.size();
            for (int n = 1; n <= total; n++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    header(content, regular, bold, statement, n, total);
                    for (Op op : layout.pages.get(n - 1)) {
                        op.draw(content);
                    }
                }
            }
            describe(doc, statement);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A value as it prints: control and invisible characters as placeholders, unknown ones as U+FFFD. */
    static String printable(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0xA0) || c == 0xAD) {
                out.append('\u2039').append(String.format(Locale.ROOT, "U+%04X", c)).append('\u203A');
            } else if (REGULAR_GLYPHS.getGlyphId(c) == 0 || BOLD_GLYPHS.getGlyphId(c) == 0) {
                out.append('\uFFFD');
            } else {
                out.appendCodePoint(c);
            }
        });
        return out.toString();
    }

    /** An amount with its sign and thousands separators, never rounded. */
    static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return format.format(amount);
    }

    /** The card as it prints: its last 4 digits only. */
    static String maskedCard(String last4) {
        return "****" + last4;
    }

    private static void header(PDPageContentStream content, PDFont regular, PDFont bold, Statement s, int page,
            int pages) throws IOException {
        StatementFigures f = s.figures();
        show(content, bold, 14, LEFT, TOP - 14, "Account statement");
        String pageText = "Page " + page + " of " + pages;
        show(content, regular, SIZE, RIGHT - width(regular, SIZE, pageText), TOP - 14, pageText);
        show(content, regular, SIZE, LEFT, TOP - 32, printable("Account " + s.acctId() + "    Statement date "
                + f.periodEnd() + "    Period " + f.periodStart() + " to " + f.periodEnd()));
        content.setLineWidth(0.5f);
        content.moveTo(LEFT, TOP - 42);
        content.lineTo(RIGHT, TOP - 42);
        content.stroke();
    }

    private static void summary(Layout l, Statement s) throws IOException {
        for (String line : wrap(l.bold, 11, RIGHT - LEFT, printable(trailingSpacesOff(s.name())))) {
            l.text(l.bold, 11, LEFT, line);
            l.down(14);
        }
        for (String address : s.address()) {
            for (String line : wrap(l.regular, 10, RIGHT - LEFT, printable(trailingSpacesOff(address)))) {
                l.text(l.regular, 10, LEFT, line);
                l.down(13);
            }
        }
        l.down(12);
        StatementFigures f = s.figures();
        l.amount(l.regular, "Credit limit", f.creditLimit());
        l.amount(l.regular, "Opening balance", f.opening());
        l.amount(l.regular, "Payments and credits", f.paymentsAndCredits());
        l.amount(l.regular, "Purchases and other debits", f.purchasesAndDebits());
        l.amount(l.regular, "Interest", f.interest());
        if (f.adjusted()) {
            l.amount(l.regular, ADJUSTMENT, f.adjustment());
        }
        l.amount(l.bold, "Closing balance", f.closing());
        l.amount(l.bold, "Minimum payment", f.minimumPayment());
        l.down(16);
    }

    private static void transactions(Layout l, Statement s) throws IOException {
        l.text(l.bold, 11, LEFT, "Transactions");
        l.down(16);
        if (s.lines().isEmpty()) {
            l.text(l.regular, 10, LEFT, NO_TRANSACTIONS);
            l.down(13);
            return;
        }
        columns(l);
        for (Line line : s.lines()) {
            List<String> description = wrap(l.regular, SIZE, DESCRIPTION_WIDTH,
                    printable(trailingSpacesOff(line.description())));
            if (!l.fits(description.size() * LEADING)) {
                l.newPage();
                l.text(l.bold, 11, LEFT, "Transactions (continued)");
                l.down(16);
                columns(l);
            }
            // Drawn in reading order, so a text extraction gives one transaction per line.
            l.text(l.regular, SIZE, LEFT, line.procDate().toString());
            l.text(l.regular, SIZE, DESCRIPTION_X, description.get(0));
            l.text(l.regular, SIZE, CARD_X, printable(maskedCard(line.cardLast4())));
            l.right(l.regular, SIZE, RIGHT, money(line.amount()));
            l.down(LEADING);
            for (String part : description.subList(1, description.size())) {
                l.text(l.regular, SIZE, DESCRIPTION_X, part);
                l.down(LEADING);
            }
        }
    }

    private static void columns(Layout l) throws IOException {
        l.text(l.bold, SIZE, LEFT, "Date");
        l.text(l.bold, SIZE, DESCRIPTION_X, "Description");
        l.text(l.bold, SIZE, CARD_X, "Card");
        l.right(l.bold, SIZE, RIGHT, "Amount");
        l.down(4);
        l.rule();
        l.down(LEADING);
    }

    private static void describe(PDDocument doc, Statement s) {
        LocalDate date = s.figures().periodEnd();
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setProducer(PRODUCER);
        info.setTitle("Statement " + s.acctId() + " " + date);
        Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        created.clear();
        created.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        info.setCreationDate(created);
        // Without an ID in the trailer, the writer derives one from the wall clock.
        byte[] id = Arrays.copyOf(sha256("carddemo statement " + s.runId() + " " + s.acctId()), 16);
        COSArray ids = new COSArray();
        ids.add(new COSString(id));
        ids.add(new COSString(id));
        doc.getDocument().getTrailer().setItem(COSName.ID, ids);
    }

    /**
     * Greedy word wrap at single spaces; a word wider than the line is broken between characters.
     * Every character of {@code text} is kept, except one space at each break.
     */
    static List<String> wrap(PDFont font, float size, float width, String text) throws IOException {
        List<String> words = new ArrayList<>();
        String current = null;
        for (String word : text.split(" ", -1)) {
            String candidate = current == null ? word : current + " " + word;
            if (current == null || width(font, size, candidate) <= width) {
                current = candidate;
            } else {
                words.add(current);
                current = word;
            }
        }
        words.add(current);
        List<String> lines = new ArrayList<>();
        for (String line : words) {
            String rest = line;
            while (width(font, size, rest) > width) {
                int end = rest.offsetByCodePoints(0, 1);
                while (end < rest.length()) {
                    int next = rest.offsetByCodePoints(end, 1);
                    if (width(font, size, rest.substring(0, next)) > width) {
                        break;
                    }
                    end = next;
                }
                lines.add(rest.substring(0, end));
                rest = rest.substring(end);
            }
            lines.add(rest);
        }
        return lines;
    }

    /** Only the padding blanks go: a trailing control character is data and stays visible. */
    private static String trailingSpacesOff(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    private static void show(PDPageContentStream content, PDFont font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    @FunctionalInterface
    private interface Op {
        void draw(PDPageContentStream content) throws IOException;
    }

    /** The body's pages as drawing steps, so the header can name the page count. */
    private static final class Layout {

        final PDFont regular;
        final PDFont bold;
        final List<List<Op>> pages = new ArrayList<>();
        private List<Op> page;
        private float y;

        Layout(PDFont regular, PDFont bold) {
            this.regular = regular;
            this.bold = bold;
            newPage();
        }

        void newPage() {
            page = new ArrayList<>();
            pages.add(page);
            y = BODY_TOP;
        }

        boolean fits(float height) {
            return y - height >= BOTTOM;
        }

        void down(float by) {
            y -= by;
        }

        void text(PDFont font, float size, float x, String text) {
            float at = y;
            page.add(content -> show(content, font, size, x, at, text));
        }

        void right(PDFont font, float size, float right, String text) throws IOException {
            text(font, size, right - width(font, size, text), text);
        }

        void amount(PDFont font, String label, BigDecimal value) throws IOException {
            text(font, 10, LEFT, label);
            right(font, 10, VALUE_RIGHT, money(value));
            down(14);
        }

        void rule() {
            float at = y;
            page.add(content -> {
                content.setLineWidth(0.5f);
                content.moveTo(LEFT, at);
                content.lineTo(RIGHT, at);
                content.stroke();
            });
        }
    }

    private static byte[] font(String name) {
        try (InputStream in = StatementPdf.class.getResourceAsStream("fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("the bundled font " + name + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CmapLookup glyphs(byte[] font) {
        try {
            return new TTFParser().parse(new RandomAccessReadBuffer(font)).getUnicodeCmapLookup();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
