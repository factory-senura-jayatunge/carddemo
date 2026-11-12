package org.carddemo.batch.statement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.carddemo.batch.statement.LegacyStatementRenderer.WorkRecord;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;

/**
 * The golden cases of {@code src/test/resources/statement/}, which
 * {@code src/test/golden/gen_statement_goldens.py} wrote from the CBSTM03A harness. Comparisons
 * report lengths, hashes, offsets and record numbers only: the outputs hold names, addresses and
 * account numbers.
 */
final class StatementGoldens {

    static final Path REPO = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    static final Path SAMPLE_DIR = REPO.resolve("app/data/ASCII");

    private static final byte[] TEXT_START =
            ("*".repeat(31) + "START OF STATEMENT" + "*".repeat(31)).getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] HTML_START = "<!DOCTYPE html>".getBytes(StandardCharsets.ISO_8859_1);

    record Inputs(List<CardXrefRecord> xref, Map<String, CustomerRecord> customers,
            Map<String, AccountRecord> accounts, List<WorkRecord> workFile) {

        Inputs withWorkFile(List<WorkRecord> other) {
            return new Inputs(xref, customers, accounts, other);
        }

        LegacyStatementRenderer.Rendered render() {
            return LegacyStatementRenderer.renderToBytes(xref, customers, accounts, workFile);
        }

        @Override
        public String toString() {
            return "Inputs[" + xref.size() + " cross-references, " + workFile.size() + " work records]";
        }
    }

    private StatementGoldens() {
    }

    /** A synthetic case: the fixed-record files run_harness.py --data took. */
    static Inputs synthetic(String name) {
        List<CardXrefRecord> xref = split(resource(name, "CARDXREF.ps.gz"), 50).stream()
                .map(CardXrefRecord::parse).toList();
        Map<String, CustomerRecord> customers = byKey(split(resource(name, "CUSTDATA.ps.gz"), 500).stream()
                .map(CustomerRecord::parse).toList(), CustomerRecord::custId);
        Map<String, AccountRecord> accounts = byKey(split(resource(name, "ACCTDATA.ps.gz"), 300).stream()
                .map(AccountRecord::parse).toList(), AccountRecord::acctId);
        List<TransactionRecord> master = split(resource(name, "TRANSACT.ps.gz"), 350).stream()
                .map(TransactionRecord::parse).toList();
        return new Inputs(inKeyOrder(xref), customers, accounts, LegacyStatementRenderer.workFile(master));
    }

    /**
     * The harness default: the sample files after POSTTRAN, rebuilt from {@code app/data/ASCII} and
     * the two POSTTRAN results the case commits (the posted daily transactions and the accounts).
     */
    static Inputs sampleAfterPosttran() {
        List<CardXrefRecord> xref = LegacyFileReader.read(SAMPLE_DIR.resolve("cardxref.txt"), 50).stream()
                .map(l -> CardXrefRecord.parse(l.record())).toList();
        Map<String, CustomerRecord> customers = byKey(LegacyFileReader.read(SAMPLE_DIR.resolve("custdata.txt"), 500)
                .stream().map(l -> CustomerRecord.parse(l.record())).toList(), CustomerRecord::custId);
        Map<String, AccountRecord> accounts;
        Set<String> posted;
        try (InputStream acct = open("sample-posttran", "acctdata-after-posttran.txt");
                InputStream ids = open("sample-posttran", "posted-tran-ids.txt")) {
            accounts = byKey(LegacyFileReader.read(acct, 300, "acctdata-after-posttran.txt").stream()
                    .map(l -> AccountRecord.parse(l.record())).toList(), AccountRecord::acctId);
            posted = new String(ids.readAllBytes(), StandardCharsets.ISO_8859_1).lines().collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<TransactionRecord> master = LegacyFileReader.read(SAMPLE_DIR.resolve("dailytran.txt"), 350).stream()
                .map(l -> TransactionRecord.parseDaily(l.record())).filter(t -> posted.contains(t.tranId())).toList();
        if (master.size() != posted.size()) {
            throw new IllegalStateException(posted.size() + " posted IDs, " + master.size() + " found in dailytran.txt");
        }
        return new Inputs(inKeyOrder(xref), customers, accounts, LegacyStatementRenderer.workFile(master));
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

    static byte[] expectedOutput(String name, String file) {
        return resource(name, file + ".gz");
    }

    /**
     * Null when {@code actual} equals the expected file of {@code expected} (lengths, SHA-256 and,
     * when {@code bytes} is given, every byte); otherwise where they part. {@code prefix} is
     * {@code text} or {@code html}.
     */
    static String difference(Properties expected, String prefix, int recordLength, byte[] bytes, byte[] actual) {
        long length = Long.parseLong(expected.getProperty(prefix + ".length"));
        String sha = expected.getProperty(prefix + ".sha256");
        if (bytes != null) {
            if (bytes.length != length || !sha256(bytes).equals(sha)) {
                return prefix + ": the committed output does not match its expected.properties";
            }
            int at = Arrays.mismatch(bytes, actual);
            if (at < 0) {
                return null;
            }
            return prefix + ": " + where(bytes, actual, at, recordLength);
        }
        if (actual.length == length && sha256(actual).equals(sha)) {
            return null;
        }
        List<byte[]> statements = statements(actual, recordLength, prefix.equals("text") ? TEXT_START : HTML_START);
        int expectedStatements = Integer.parseInt(expected.getProperty("statements"));
        StringBuilder sb = new StringBuilder(prefix + ": " + actual.length + " bytes, SHA-256 " + sha256(actual)
                + "; expected " + length + " bytes, SHA-256 " + sha + "; " + statements.size() + " statements, expected "
                + expectedStatements);
        long record = 1;
        for (int i = 0; i < statements.size(); i++) {
            String want = expected.getProperty(prefix + ".statement." + (i + 1));
            byte[] s = statements.get(i);
            if (want == null || !want.equals(sha256(s))) {
                sb.append("; first differing statement ").append(i + 1).append(" (records ").append(record)
                        .append(" to ").append(record + s.length / recordLength - 1).append(")");
                break;
            }
            record += s.length / recordLength;
        }
        return sb.toString();
    }

    private static String where(byte[] expected, byte[] actual, int at, int recordLength) {
        String lengths = expected.length == actual.length ? "" : " (" + actual.length + " bytes, expected "
                + expected.length + ")";
        byte[] start = recordLength == 80 ? TEXT_START : HTML_START;
        int record = at / recordLength;
        int statement = 0;
        int firstOfStatement = 0;
        for (int r = 0; r <= record && (r + 1) * recordLength <= expected.length; r++) {
            if (Arrays.equals(expected, r * recordLength, r * recordLength + start.length, start, 0, start.length)) {
                statement++;
                firstOfStatement = r;
            }
        }
        return "first difference at byte offset " + at + ": record " + (record + 1) + ", column "
                + (at % recordLength + 1) + ", statement " + statement + " line " + (record - firstOfStatement + 1)
                + lengths;
    }

    static List<byte[]> statements(byte[] data, int recordLength, byte[] start) {
        List<byte[]> out = new ArrayList<>();
        int from = 0;
        for (int r = recordLength; r < data.length; r += recordLength) {
            if (Arrays.equals(data, r, Math.min(r + start.length, data.length), start, 0, start.length)) {
                out.add(Arrays.copyOfRange(data, from, r));
                from = r;
            }
        }
        if (data.length > 0) {
            out.add(Arrays.copyOfRange(data, from, data.length));
        }
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A sequential read of a KSDS returns its records in key order. */
    private static List<CardXrefRecord> inKeyOrder(List<CardXrefRecord> xref) {
        return xref.stream().sorted(Comparator.comparing(CardXrefRecord::cardNum)).toList();
    }

    private static <T> Map<String, T> byKey(List<T> records, Function<T, String> key) {
        Map<String, T> out = new LinkedHashMap<>();
        for (T r : records) {
            if (out.put(key.apply(r), r) != null) {
                throw new IllegalStateException("duplicate key in a golden input file");
            }
        }
        return out;
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

    private static byte[] resource(String name, String file) {
        try (InputStream in = new GZIPInputStream(open(name, file))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String name, String file) {
        InputStream in = StatementGoldens.class.getResourceAsStream("/statement/" + name + "/" + file);
        if (in == null) {
            throw new IllegalStateException("no test resource statement/" + name + "/" + file);
        }
        return in;
    }
}
