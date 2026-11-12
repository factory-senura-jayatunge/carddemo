package org.carddemo.batch.transactionreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The legacy-mode writer against the original CBTRN03C: every expected report in
 * {@code src/test/resources/tranrept/} was written by the program through the GnuCOBOL build
 * ({@code src/test/golden/gen_tranrept_goldens.py}), never by this writer. The rows are made from
 * the program's own input file with the sample reference files, in the file's order, as the
 * legacy ORDER BY returns them. Failures report offsets and line numbers, never the content.
 */
@SpecItem({"BR-TRPT-002", "BR-TRPT-003", "BR-TRPT-004", "BR-TRPT-005", "BR-TRPT-006", "BR-TRPT-007",
    "NUM-TRPT-001", "NUM-TRPT-002", "NUM-TRPT-003", "FLOW-TRPT-001", "DEF-TRPT-001", "DEF-TRPT-004",
    "DEF-TRPT-007"})
class TransactionReportGoldenTest {
    static final Path REPO = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    static final Path SAMPLE_DIR = REPO.resolve("app/data/ASCII");
    static final List<String> SAME = List.of("sample-posttran", "case-main", "case-allin", "case-lastout",
            "case-empty", "case-reversed");

    record Report(byte[] bytes, long details) { }

    static Report legacy(String name) {
        String parm = new String(resource(name, "dateparm.txt", false), StandardCharsets.ISO_8859_1);
        String start = TransactionReportWriter.pad(parm, 21).substring(0, 10);
        String end = TransactionReportWriter.pad(parm, 21).substring(11, 21);
        var xref = byKey(LegacyFileReader.read(SAMPLE_DIR.resolve("cardxref.txt"), 50).stream()
                .map(l -> CardXrefRecord.parse(l.record())).toList(), CardXrefRecord::cardNum);
        var types = byKey(LegacyFileReader.read(SAMPLE_DIR.resolve("trantype.txt"), 60).stream()
                .map(l -> TranTypeRecord.parse(l.record())).toList(), TranTypeRecord::typeCd);
        var categories = byKey(LegacyFileReader.read(SAMPLE_DIR.resolve("trancatg.txt"), 60).stream()
                .map(l -> TranCategoryRecord.parse(l.record())).toList(), c -> c.typeCd() + c.catCd());
        var out = new ByteArrayOutputStream();
        var writer = new TransactionReportWriter(out, start, end, false, false);
        for (byte[] data : split(resource(name, "tranfile.txt.gz", true), 350)) {
            TransactionRecord t = TransactionRecord.parse(data);
            String procDate = t.procTs().substring(0, 10);
            boolean inRange = procDate.compareTo(start) >= 0 && procDate.compareTo(end) <= 0;
            // The program looks nothing up for the record that ends the report.
            ReportRow row = inRange
                    ? TransactionReportTasklet.row(procDate, t.tranId(), t.cardNum(), acct(xref.get(t.cardNum())),
                            t.typeCd(), desc(types.get(t.typeCd())), t.catCd(),
                            category(categories.get(t.typeCd() + t.catCd())), t.source(), t.amount())
                    : new ReportRow(procDate, t.tranId(), t.cardNum(), "", "", "", "", "", "", t.amount());
            if (!writer.accept(row)) {
                break;
            }
        }
        writer.finish();
        return new Report(out.toByteArray(), writer.details());
    }

    private static String acct(CardXrefRecord x) {
        return x == null ? null : x.acctId();
    }

    private static String desc(TranTypeRecord t) {
        return t == null ? null : t.description();
    }

    private static String category(TranCategoryRecord c) {
        return c == null ? null : c.description();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample-posttran", "case-main", "case-allin", "case-lastout", "case-empty",
        "case-reversed"})
    void legacyModeIsByteIdenticalToTheProgram(String name) {
        Properties expected = expected(name);
        assertThat(expected.getProperty("java")).isEqualTo("same");
        assertThat(expected.getProperty("cobol.rc")).isEqualTo("0");
        byte[] want = expected.getProperty("length").equals("0") ? new byte[0] : resource(name, "TRANREPT.txt.gz", true);
        assertThat(want.length).isEqualTo(Integer.parseInt(expected.getProperty("length")));
        assertThat(sha256(want)).as("the committed report matches its expected.properties")
                .isEqualTo(expected.getProperty("sha256"));
        byte[] actual = legacy(name).bytes();
        assertThat(difference(want, actual)).isNull();
    }

    @Test
    void theSampleIsTheSpecsRun() {
        Properties expected = expected("sample-posttran");
        // programs/CBTRN03C.md BR-TRPT-006: the sample run has 453 lines and a grand total of 77,954.70.
        assertThat(expected.getProperty("lines")).isEqualTo("453");
        byte[] report = resource("sample-posttran", "TRANREPT.txt.gz", true);
        String last = new String(report, report.length - 134, 133, StandardCharsets.ISO_8859_1);
        assertThat(last).startsWith("Grand Total").contains("+     77,954.70");
    }

    @ParameterizedTest
    @CsvSource({"case-badcat, REPORT_REFERENCE_MISSING: transaction category",
        "case-badcard, REPORT_REFERENCE_MISSING: card cross-reference"})
    void aLookupThatAbendsTheProgramFailsTheReport(String name, String failure) {
        Properties expected = expected(name);
        assertThat(expected.getProperty("java")).isEqualTo(failure);
        assertThat(expected.getProperty("cobol.rc")).isNotEqualTo("0");
        assertThatThrownBy(() -> legacy(name)).hasMessage(failure);
    }

    @Test
    void everyCaseIsUsedOrExplained() {
        for (String name : List.of("case-blankdates", "case-blankcard")) {
            assertThat(expected(name).getProperty("java")).startsWith("not-compared: ");
        }
        assertThat(SAME).hasSize(6);
    }

    /** Null when equal; otherwise the first differing line and column, without the content. */
    static String difference(byte[] expected, byte[] actual) {
        int at = Arrays.mismatch(expected, actual);
        if (at < 0) {
            return null;
        }
        return "first difference at byte offset " + at + ": line " + (at / 134 + 1) + ", column " + (at % 134 + 1)
                + (expected.length == actual.length ? "" : " (" + actual.length + " bytes, expected "
                        + expected.length + ")");
    }

    static Properties expected(String name) {
        Properties p = new Properties();
        try (InputStream in = open(name, "expected.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Map<String, T> byKey(List<T> records, Function<T, String> key) {
        return records.stream().collect(Collectors.toMap(key, r -> r, (a, b) -> {
            throw new IllegalStateException("duplicate key in a sample reference file");
        }));
    }

    private static List<byte[]> split(byte[] data, int lrecl) {
        if (data.length % lrecl != 0) {
            throw new IllegalStateException(data.length + " bytes is not a multiple of " + lrecl);
        }
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < data.length; i += lrecl) {
            out.add(Arrays.copyOfRange(data, i, i + lrecl));
        }
        return out;
    }

    private static byte[] resource(String name, String file, boolean gzip) {
        try (InputStream raw = open(name, file); InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = in.readAllBytes();
            if (!gzip) {
                // dateparm.txt is a text line; the DATEPARM record is the line without its end.
                String text = new String(bytes, StandardCharsets.ISO_8859_1);
                return text.replaceFirst("\r?\n$", "").getBytes(StandardCharsets.ISO_8859_1);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String name, String file) {
        InputStream in = TransactionReportGoldenTest.class.getResourceAsStream("/tranrept/" + name + "/" + file);
        if (in == null) {
            throw new IllegalStateException("no test resource tranrept/" + name + "/" + file
                    + "; run src/test/golden/gen_tranrept_goldens.py");
        }
        return in;
    }
}
