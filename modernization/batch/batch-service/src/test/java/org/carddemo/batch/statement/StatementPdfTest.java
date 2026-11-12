package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * One fixed-mode statement PDF (batch.md, "PDF"; DEF-STMT-005 fixed), read back with
 * {@link PDFTextStripper} as the ruling asks: every value in full and never cut, the adjustment
 * line only when {@code A != 0}, an empty period that still prints, no FICO score and card numbers
 * only as {@code ****1234}, placeholders for invisible characters, and the same bytes from the
 * same statement. The data is made up here.
 */
class StatementPdfTest {

    private static final LocalDate DATE = LocalDate.of(2101, 6, 1);
    private static final BigDecimal FLOOR = new BigDecimal("25.00");
    private static final BigDecimal PERCENT = new BigDecimal("1.00");

    private static StatementPdf.Line line(String date, String description, String last4, String amount) {
        return new StatementPdf.Line(LocalDate.parse(date), description, last4, new BigDecimal(amount));
    }

    private static StatementPdf.Statement statement(long runId, String name, List<String> address, String closing,
            Optional<StatementFigures.Previous> previous, List<StatementPdf.Line> lines, List<Boolean> interest) {
        List<StatementFigures.Line> figureLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            figureLines.add(new StatementFigures.Line(lines.get(i).amount(), interest.get(i), lines.get(i).procDate()));
        }
        StatementFigures figures = StatementFigures.of(DATE, new BigDecimal(closing), new BigDecimal("5000.00"),
                previous, figureLines, FLOOR, PERCENT);
        return new StatementPdf.Statement(runId, "00000000001", name, address, figures, lines);
    }

    private static StatementPdf.Statement simple(long runId) {
        return statement(runId, "Ann Q Example", List.of("1 Test Street", "Apt 2", "Springfield IL USA 62701"),
                "57.00", Optional.empty(),
                List.of(line("2101-05-30", "Purchase at a test shop", "4444", "100.00"),
                        line("2101-05-31", "Payment, thank you", "4444", "-50.00"),
                        line("2101-06-01", "Interest charge", "4444", "10.00"),
                        line("2101-06-01", "Interest reversal", "4444", "-3.00")),
                List.of(false, false, true, true));
    }

    private static String text(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static int occurrences(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    /** Every word of {@code value} appears, in order: a wrapped value is cut only at a space. */
    private static void assertWordsInOrder(String text, String value) {
        int at = 0;
        for (String word : value.split(" ")) {
            int found = text.indexOf(word, at);
            assertThat(found).as("word %s of the value after offset %d", word, at).isGreaterThanOrEqualTo(0);
            at = found + word.length();
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void theSameStatementGivesTheSameBytesAndTheMetadataIsFixed(FidelityMode mode) throws IOException {
        byte[] first = StatementPdf.render(simple(7));
        byte[] second = StatementPdf.render(simple(7));

        assertThat(second).isEqualTo(first);
        assertThat(StatementPdf.render(simple(8))).as("the document ID names the run").isNotEqualTo(first);
        try (PDDocument doc = Loader.loadPDF(first)) {
            assertThat(doc.getDocumentInformation().getProducer()).isEqualTo(StatementPdf.PRODUCER);
            assertThat(doc.getDocumentInformation().getCreationDate().toInstant())
                    .isEqualTo(DATE.atStartOfDay(ZoneOffset.UTC).toInstant());
            assertThat(doc.getDocumentInformation().getModificationDate()).isNull();
            COSArray ids = doc.getDocument().getTrailer().getCOSArray(COSName.ID);
            byte[] expected = Arrays.copyOf(sha256("carddemo statement 7 00000000001"), 16);
            assertThat(((COSString) ids.get(0)).getBytes()).isEqualTo(expected);
            assertThat(((COSString) ids.get(1)).getBytes()).isEqualTo(expected);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(612f);
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isEqualTo(792f);
        }
    }

    /** The header, the summary with the four totals, and the lines; a negative interest row lowers interest. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "BR-STMT-005", "BR-STMT-006"})
    void theStatementShowsItsHeaderSummaryAndLines(FidelityMode mode) {
        String text = text(StatementPdf.render(simple(7)));

        assertThat(text).contains("Account 00000000001", "Statement date 2101-06-01",
                "Period 2101-05-30 to 2101-06-01", "Page 1 of 1");
        assertThat(text).contains("Ann Q Example", "1 Test Street", "Apt 2", "Springfield IL USA 62701");
        assertThat(flat(text)).contains("Credit limit 5,000.00", "Opening balance 0.00",
                "Payments and credits -50.00", "Purchases and other debits 100.00", "Interest 7.00",
                "Closing balance 57.00", "Minimum payment 25.00");
        assertThat(text).doesNotContain(StatementPdf.ADJUSTMENT, StatementPdf.NO_TRANSACTIONS);
        assertThat(flat(text)).contains("2101-05-30 Purchase at a test shop ****4444 100.00",
                "2101-05-31 Payment, thank you ****4444 -50.00", "2101-06-01 Interest reversal ****4444 -3.00");
        assertThat(text.indexOf("Purchase at a test shop")).isLessThan(text.indexOf("Payment, thank you"));
    }

    /** No FICO score is printed and a card shows only its last 4 digits; a line cannot even hold more. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-008"})
    void thereIsNoFicoScoreAndCardsShowOnlyTheirLastFourDigits(FidelityMode mode) {
        String text = text(StatementPdf.render(simple(7)));

        assertThat(text).doesNotContainIgnoringCase("FICO");
        assertThat(text.replace("00000000001", "")).as("no card number").doesNotContainPattern("\\d{13,}");
        assertThat(occurrences(text, "****4444")).isEqualTo(4);
        assertThatThrownBy(() -> line("2101-06-01", "x", "4111222233334444", "1.00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Full values (D-44): a 100-character description, long names and address lines, and a
     * balance of a billion or more, wrapped where needed and never cut; a script the font covers
     * prints as itself.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-002", "DEF-STMT-003", "DEF-STMT-004", "BR-STMT-005", "BR-STMT-006",
            "BR-STMT-008"})
    void everyValuePrintsInFull(FidelityMode mode) {
        String description = "A description of exactly one hundred characters that the legacy text record cut to forty"
                + "-nine chars.";
        assertThat(description).hasSize(100);
        String name = "Maximiliana Theodora  Wolfeschlegelsteinhausen-Bergerdorff Жанна";
        String street = "12  Main Street, Building with a name that is longer than the fifty characters of a line";
        StatementPdf.Statement s = statement(7, name, List.of(street, "Springfield IL USA 62701"), "1234567890.12",
                Optional.empty(), List.of(line("2101-06-01", description, "4444", "1234567890.12")),
                List.of(false));

        String text = text(StatementPdf.render(s));

        assertWordsInOrder(text, description);
        assertWordsInOrder(text, name.replace("  ", " "));
        assertWordsInOrder(text, street.replace("  ", " "));
        assertThat(text).contains("Жанна", "1,234,567,890.12");
    }

    /** Markup characters are text: {@code <}, {@code >} and {@code &} print as themselves, never as markup or entities. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-007"})
    void markupCharactersPrintAsThemselves(FidelityMode mode) {
        String markup = "<b>Tom & Jerry</b> <i>&amp;</i>";
        StatementPdf.Statement s = statement(7, "Ann <Q> & Example", List.of("1 <Test> & Street"), "1.00",
                Optional.empty(), List.of(line("2101-06-01", markup, "4444", "1.00")), List.of(false));

        String text = text(StatementPdf.render(s));

        assertThat(text).contains(markup, "Ann <Q> & Example", "1 <Test> & Street");
    }

    /** Control characters, NBSP and SHY print as placeholders; a character no font glyph has, as U+FFFD. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void invisibleAndUnknownCharactersPrintVisibly(FidelityMode mode) {
        assertThat(StatementPdf.printable("a\u0001b\u007Fc\u0085d\u00A0e\u00ADf")).isEqualTo(
                "a\u2039U+0001\u203Ab\u2039U+007F\u203Ac\u2039U+0085\u203Ad\u2039U+00A0\u203Ae\u2039U+00AD\u203Af");
        assertThat(StatementPdf.printable("caf\u00E9 \u4E00")).isEqualTo("caf\u00E9 \uFFFD");

        StatementPdf.Statement s = statement(7, "Ann\u0001Lee", List.of("1 Test\u00A0Street"), "1.00",
                Optional.empty(), List.of(line("2101-06-01", "tab\there \u4E00", "4444", "1.00")), List.of(false));
        String text = text(StatementPdf.render(s));

        assertThat(text).contains("Ann\u2039U+0001\u203ALee", "1 Test\u2039U+00A0\u203AStreet",
                "tab\u2039U+0009\u203Ahere \uFFFD");
    }

    /** A period without transactions still prints, with its figures. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-010"})
    void anEmptyPeriodStillPrints(FidelityMode mode) {
        StatementPdf.Statement s = statement(7, "Ann Example", List.of("1 Test Street"), "42.00",
                Optional.of(new StatementFigures.Previous(new BigDecimal("42.00"), DATE.minusDays(1))), List.of(),
                List.of());

        String text = text(StatementPdf.render(s));

        assertThat(text).contains(StatementPdf.NO_TRANSACTIONS, "Period 2101-06-01 to 2101-06-01", "Page 1 of 1");
        assertThat(flat(text)).contains("Opening balance 42.00", "Closing balance 42.00", "Minimum payment 25.00");
        assertThat(text).doesNotContain(StatementPdf.ADJUSTMENT, "****");
    }

    /** {@code A = C - (O + N)} other than 0 prints its own line, so the printed total adds up. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void anAdjustmentPrintsItsOwnLine(FidelityMode mode) {
        StatementPdf.Statement s = statement(7, "Ann Example", List.of("1 Test Street"), "112.34",
                Optional.of(new StatementFigures.Previous(new BigDecimal("50.00"), DATE.minusDays(30))),
                List.of(line("2101-05-15", "Purchase", "4444", "50.00")), List.of(false));
        assertThat(s.figures().adjustment()).isEqualByComparingTo("12.34");

        String text = flat(text(StatementPdf.render(s)));

        assertThat(text).contains("Opening balance 50.00", "Purchases and other debits 50.00",
                StatementPdf.ADJUSTMENT + " 12.34", "Closing balance 112.34");
    }

    /**
     * No table limit (DEF-STMT-001 and DEF-JSTMT-005 fixed): more than 10 lines on one card spill
     * onto further pages, every page repeats the header with {@code n of m}, and every line prints once.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void manyLinesSpillOntoFurtherPagesWithTheHeaderOnEach(FidelityMode mode) throws IOException {
        List<StatementPdf.Line> lines = new ArrayList<>();
        List<Boolean> interest = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            lines.add(line("2101-05-31", "Purchase number " + i + " end", "4444", "1.00"));
            interest.add(false);
        }
        byte[] pdf = StatementPdf.render(statement(7, "Ann Example", List.of("1 Test Street"), "120.00",
                Optional.empty(), lines, interest));

        String text = text(pdf);
        int pages;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pages = doc.getNumberOfPages();
        }
        assertThat(pages).isGreaterThan(1);
        for (int n = 1; n <= pages; n++) {
            assertThat(text).contains("Page " + n + " of " + pages);
        }
        assertThat(occurrences(text, "Account 00000000001")).isEqualTo(pages);
        assertThat(occurrences(text, "****4444")).isEqualTo(120);
        for (int i = 1; i <= 120; i++) {
            assertThat(occurrences(text, "Purchase number " + i + " end")).as("line %d", i).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void moneyIsNeverRounded(FidelityMode mode) {
        assertThat(StatementPdf.money(new BigDecimal("-1234.50"))).isEqualTo("-1,234.50");
        assertThat(StatementPdf.money(new BigDecimal("0.00"))).isEqualTo("0.00");
        assertThat(StatementPdf.money(new BigDecimal("9999999999.99"))).isEqualTo("9,999,999,999.99");
        assertThatThrownBy(() -> StatementPdf.money(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
        assertThat(StatementPdf.maskedCard("1234")).isEqualTo("****1234");
    }
}
