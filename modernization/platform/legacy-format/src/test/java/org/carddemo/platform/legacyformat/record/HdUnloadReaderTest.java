package org.carddemo.platform.legacyformat.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.HdUnloadReader.Root;
import org.carddemo.platform.legacyformat.record.HdUnloadReader.Segment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pending-authorization layouts and the HD unload reader against what
 * {@code src/test/golden/gen_pauth.py} parses from the copybooks and decodes from the EBCDIC
 * sample ({@code golden/pauth-hd-unload.json}). Failures name records and fields, never values.
 */
class HdUnloadReaderTest {

    private static final Map<String, RecordLayout> LAYOUTS = Map.of(
            "PAUTSUM0", Layouts.PENDING_AUTH_SUMMARY, "PAUTDTL1", Layouts.PENDING_AUTH_DETAIL);
    private static final Map<String, RecordLayout> BY_COPYBOOK = Map.of(
            "CIPAUSMY", Layouts.PENDING_AUTH_SUMMARY, "CIPAUDTY", Layouts.PENDING_AUTH_DETAIL);

    private static JsonNode golden;
    private static Path sample;

    @BeforeAll
    static void locate() throws IOException {
        try (InputStream in = HdUnloadReaderTest.class.getResourceAsStream("/golden/pauth-hd-unload.json")) {
            golden = JsonMapper.builder().build().readTree(in);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("app/app-authorization-ims-db2-mq"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above the module").isNotNull();
        sample = dir.resolve("app/app-authorization-ims-db2-mq/data/EBCDIC")
                .resolve(golden.get("sample").get("file").asString());
    }

    @Test
    void theSampleHoldsTheSegmentDataAtFixedOffset39() throws IOException {
        List<byte[]> records = rawRecords();
        byte[] summary = records.get(1);
        assertThat(Arrays.copyOfRange(summary, 39, 45)).as("record 2 offsets 39-44")
                .containsExactly(0x00, 0x00, 0x00, 0x00, 0x00, 0x1C);
        FixedRecord at39 = FixedRecord.of(Layouts.PENDING_AUTH_SUMMARY,
                Arrays.copyOfRange(summary, 39, 139), HdUnloadReader.EBCDIC);
        assertThat(at39.decimal("PA-ACCT-ID")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(at39.isAllDigits("PA-CUST-ID")).isTrue();
        FixedRecord at40 = FixedRecord.of(Layouts.PENDING_AUTH_SUMMARY,
                Arrays.copyOfRange(summary, 40, 140), HdUnloadReader.EBCDIC);
        assertThatThrownBy(() -> at40.decimal("PA-ACCT-ID")).as("record 2 at offset 40")
                .isInstanceOf(LegacyFormatException.class);

        byte[] detail = records.get(2);
        assertThat(Arrays.copyOfRange(detail, 39, 42)).as("record 3 offsets 39-41")
                .containsExactly(0x76, 0x69, 0x9C);
        assertThat(new String(detail, 47, 6, HdUnloadReader.EBCDIC)).as("record 3 offsets 47-52").isEqualTo("231027");
        FixedRecord d = FixedRecord.of(Layouts.PENDING_AUTH_DETAIL,
                Arrays.copyOfRange(detail, 39, 239), HdUnloadReader.EBCDIC);
        int yyddd = 99999 - d.decimal("PA-AUTH-DATE-9C").intValueExact();
        assertThat(yyddd).isEqualTo(23300);
        assertThat(LocalDate.ofYearDay(2000 + yyddd / 1000, yyddd % 1000))
                .isEqualTo(LocalDate.parse("20" + d.raw("PA-AUTH-ORIG-DATE"), DateTimeFormatter.BASIC_ISO_DATE));

        byte[] last = records.get(224);
        assertThat(new String(last, 10, 8, HdUnloadReader.EBCDIC)).as("record 225 segment").isEqualTo("PAUTSUM0");
        assertThat(Arrays.copyOfRange(last, 39, 45)).as("record 225 offsets 39-44")
                .containsOnly(0x40);
        assertThat(records.get(225).length).as("record 226 is the trailer").isEqualTo(88);
        List<Root> roots = HdUnloadReader.roots(HdUnloadReader.read(sample, LAYOUTS), "PAUTSUM0");
        assertThat(roots.getLast().segment().recordNumber()).isEqualTo(225);
        assertThat(roots.getLast().children()).isEmpty();
        assertThat(roots.stream().filter(r -> !r.segment().data().allBytes("PA-ACCT-ID", 0x40)).count()).isEqualTo(21);
        assertThat(roots.stream().filter(r -> !r.segment().data().allBytes("PA-ACCT-ID", 0x40))
                .mapToInt(r -> r.children().size()).sum()).isEqualTo(202);
    }

    @Test
    void layoutsMatchTheSegmentCopybooks() {        for (String copybook : golden.get("layouts").propertyNames()) {
            JsonNode cb = golden.get("layouts").get(copybook);
            RecordLayout layout = BY_COPYBOOK.get(copybook);
            assertThat(layout).as(copybook).isNotNull();
            assertThat(layout.length()).as(copybook).isEqualTo(cb.get("length").asInt());
            List<String> expected = new ArrayList<>();
            for (JsonNode f : cb.get("fields")) {
                String type = switch (f.get("usage").asString()) {
                    case "COMP-3" -> "PACKED";
                    case "COMP" -> "BINARY";
                    default -> f.get("numeric").asBoolean() ? (f.get("signed").asBoolean() ? "ZONED" : "DIGITS") : "TEXT";
                };
                expected.add(f.get("name").asString() + "@" + f.get("offset").asInt() + "+" + f.get("length").asInt()
                        + " " + type + (f.get("numeric").asBoolean()
                                ? " s=" + f.get("signed").asBoolean() + " scale=" + f.get("scale").asInt() : ""));
            }
            List<String> actual = new ArrayList<>();
            int covered = 0;
            for (FieldSpec f : layout.fields()) {
                assertThat(f.offset()).as("%s.%s starts where the previous field ends", copybook, f.name()).isEqualTo(covered);
                covered = f.end();
                if (f.type() == FieldType.FILLER) {
                    continue;
                }
                actual.add(f.name() + "@" + f.offset() + "+" + f.length() + " " + f.type()
                        + (f.pic() != null ? " s=" + f.pic().signed() + " scale=" + f.pic().scale() : ""));
            }
            assertThat(actual).as(copybook).containsExactlyElementsOf(expected);
        }
        assertThat(golden.get("segments").get("PAUTSUM0").asString()).isEqualTo("CIPAUSMY");
        assertThat(golden.get("segments").get("PAUTDTL1").asString()).isEqualTo("CIPAUDTY");
    }

    @Test
    void theSampleDecodesAsTheIndependentModelDoes() {
        JsonNode exp = golden.get("sample");
        List<Segment> segments = HdUnloadReader.read(sample, LAYOUTS);
        assertThat(segments).hasSize(exp.get("records").asInt() - 2);
        Map<String, Long> names = segments.stream().collect(Collectors.groupingBy(Segment::name, Collectors.counting()));
        for (String name : exp.get("segments").propertyNames()) {
            assertThat(names.get(name)).as(name).isEqualTo(exp.get("segments").get(name).asLong());
        }

        List<Root> roots = HdUnloadReader.roots(segments, "PAUTSUM0");
        List<Integer> blank = new ArrayList<>();
        List<Root> kept = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).segment().data().allBytes("PA-ACCT-ID", 0x40)) {
                blank.add(i);
                assertThat(roots.get(i).children()).hasSize(exp.get("blankRoots").get(blank.size() - 1).get("children").asInt());
            } else {
                kept.add(roots.get(i));
            }
        }
        List<Integer> blankPositions = new ArrayList<>();
        for (JsonNode n : exp.get("blankRoots")) {
            blankPositions.add(n.get("position").asInt());
        }
        assertThat(blank).containsExactlyElementsOf(blankPositions);
        assertThat(blank).as("the blank-key root is the last root and has no children").containsExactly(roots.size() - 1);
        assertThat(roots.getLast().children()).isEmpty();
        List<String> expectedRoots = new ArrayList<>();
        for (JsonNode n : exp.get("roots")) {
            expectedRoots.add(n.get("acctId").asLong() + ":" + n.get("children").asInt());
        }
        assertThat(kept.stream().map(r -> r.segment().data().decimal("PA-ACCT-ID").longValueExact()
                + ":" + r.children().size()).toList()).containsExactlyElementsOf(expectedRoots);

        List<FixedRecord> summaries = kept.stream().map(r -> r.segment().data()).toList();
        List<FixedRecord> details = kept.stream().flatMap(r -> r.children().stream()).map(Segment::data).toList();
        assertSums(summaries, exp.get("summarySums"));
        assertSums(details, exp.get("detailSums"));

        TreeSet<String> cards = details.stream().map(d -> d.raw("PA-CARD-NUM")).collect(Collectors.toCollection(TreeSet::new));
        assertThat(cards.size()).isEqualTo(exp.get("distinctCards").asInt());
        CRC32 crc = new CRC32();
        crc.update(String.join("\n", cards).getBytes(StandardCharsets.US_ASCII));
        assertThat(String.format("%08x", crc.getValue())).isEqualTo(exp.get("cardsCrc32").asString());

        assertCounts(details, d -> d.raw("PA-AUTH-RESP-CODE"), exp.get("respCodes"));
        assertCounts(details, d -> d.raw("PA-MATCH-STATUS"), exp.get("matchStatus"));
        assertCounts(details, d -> d.raw("PA-AUTH-FRAUD"), exp.get("fraud"));
        List<Integer> dates = details.stream()
                .map(d -> 99999 - d.decimal("PA-AUTH-DATE-9C").intValueExact()).sorted().toList();
        assertThat(dates.getFirst()).isEqualTo(exp.get("keyDateYyddd").get("min").asInt());
        assertThat(dates.getLast()).isEqualTo(exp.get("keyDateYyddd").get("max").asInt());

        // Each summary carries the totals of its own details (all approved in the sample).
        for (Root r : kept) {
            FixedRecord s = r.segment().data();
            BigDecimal approved = r.children().stream().map(c -> c.data().decimal("PA-APPROVED-AMT"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(s.decimal("PA-APPROVED-AUTH-CNT").intValueExact()).as(r.toString()).isEqualTo(r.children().size());
            assertThat(s.decimal("PA-APPROVED-AUTH-AMT")).as(r.toString()).isEqualByComparingTo(approved);
            assertThat(s.isAllDigits("PA-CUST-ID")).as(r.toString()).isTrue();
        }
        assertThat(details).allSatisfy(d -> assertThat(d.isAllDigits("PA-PROCESSING-CODE")).isTrue());
    }

    private static void assertSums(List<FixedRecord> rows, JsonNode sums) {
        for (String field : sums.propertyNames()) {
            BigDecimal sum = rows.stream().map(r -> r.decimal(field)).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as(field).isEqualByComparingTo(new BigDecimal(sums.get(field).asString()));
        }
    }

    private static void assertCounts(List<FixedRecord> rows, Function<FixedRecord, String> key, JsonNode counts) {
        Map<String, Long> actual = rows.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
        Map<String, Long> expected = new HashMap<>();
        for (String k : counts.propertyNames()) {
            expected.put(k, counts.get(k).asLong());
        }
        assertThat(actual).isEqualTo(expected);
    }

    /** The sample's records by its own descriptor walk, independent of the reader. */
    private static List<byte[]> rawRecords() throws IOException {
        byte[] all = Files.readAllBytes(sample);
        List<byte[]> out = new ArrayList<>();
        for (int pos = 0; pos < all.length; ) {
            int len = ((all[pos] & 0xFF) << 8) | (all[pos + 1] & 0xFF);
            out.add(Arrays.copyOfRange(all, pos, pos + len));
            pos += len;
        }
        return out;
    }

    @Test
    void everySegmentRecordHasThePrefixPayloadAndTrailingBytePositions() throws IOException {
        List<byte[]> records = rawRecords();
        assertThat(records).hasSize(golden.get("sample").get("records").asInt());
        assertThat(records.getFirst().length).isEqualTo(88);
        assertThat(records.getLast().length).isEqualTo(88);
        int checked = 0;
        for (int i = 1; i < records.size() - 1; i++) {
            byte[] r = records.get(i);
            String name = new String(r, 10, 8, HdUnloadReader.EBCDIC);
            RecordLayout layout = LAYOUTS.get(name);
            assertThat(layout).as("record %d has a known segment name", i + 1).isNotNull();
            assertThat(((r[8] & 0xFF) << 8) | (r[9] & 0xFF)).as("record %d segment length", i + 1).isEqualTo(layout.length());
            assertThat(r.length).as("record %d: 39-byte prefix, payload, 1 byte", i + 1).isEqualTo(39 + layout.length() + 1);
            assertThat(r[r.length - 1]).as("record %d trailing byte", i + 1).isZero();
            checked++;
        }
        assertThat(checked).isEqualTo(224);
        assertThat(HdUnloadReader.read(sample, LAYOUTS)).hasSize(224);
    }

    /** Whether a payload decodes by its copybook's types: packed signs and digits, digit-only 9(n) fields. */
    private static boolean decodesByType(FixedRecord r) {
        try {
            for (FieldSpec f : r.layout().fields()) {
                if (f.type() == FieldType.PACKED || f.type() == FieldType.BINARY) {
                    r.decimal(f.name());
                } else if (f.type() == FieldType.DIGITS && !r.isAllDigits(f.name())) {
                    return false;
                }
            }
            return true;
        } catch (LegacyFormatException e) {
            return false;
        }
    }

    @Test
    void readingTheLastBytesOfEachRecordAsThePayloadFailsTheCopybookTypes() throws IOException {
        int atOffset39 = 0;
        int asLastBytes = 0;
        int segments = 0;
        for (byte[] r : rawRecords()) {
            RecordLayout layout = LAYOUTS.get(r.length > 39 ? new String(r, 10, 8, HdUnloadReader.EBCDIC) : "");
            if (layout == null || (layout == Layouts.PENDING_AUTH_SUMMARY && r[39] == 0x40 && r[40] == 0x40)) {
                continue;
            }
            segments++;
            byte[] payload = Arrays.copyOfRange(r, 39, 39 + layout.length());
            byte[] last = Arrays.copyOfRange(r, r.length - layout.length(), r.length);
            atOffset39 += decodesByType(FixedRecord.of(layout, payload, HdUnloadReader.EBCDIC)) ? 1 : 0;
            asLastBytes += decodesByType(FixedRecord.of(layout, last, HdUnloadReader.EBCDIC)) ? 1 : 0;
        }
        assertThat(segments).isEqualTo(223);
        assertThat(atOffset39).isEqualTo(segments);
        assertThat(asLastBytes).as("the last 100 or 200 bytes are not the payload").isZero();
    }

    /** The ASCII sample files of the base application, which hold the same accounts and cards. */
    @Test
    void everyDetailsCardBelongsToItsRootsAccountAndCustomerInTheAsciiSamples() {
        Path ascii = sample.getParent().getParent().getParent().getParent().resolve("data/ASCII");
        Map<String, FixedRecord> xref = new HashMap<>();
        for (LegacyFileReader.SourceLine line : LegacyFileReader.read(ascii.resolve("cardxref.txt"), Layouts.CARD_XREF.length())) {
            FixedRecord x = line.view(Layouts.CARD_XREF);
            xref.put(x.raw("XREF-CARD-NUM"), x);
        }
        java.util.Set<Long> accounts = new java.util.HashSet<>();
        for (LegacyFileReader.SourceLine line : LegacyFileReader.read(ascii.resolve("acctdata.txt"), Layouts.ACCOUNT.length())) {
            accounts.add(Long.parseLong(line.view(Layouts.ACCOUNT).digits("ACCT-ID")));
        }
        int roots = 0;
        int matched = 0;
        int mismatched = 0;
        for (Root root : HdUnloadReader.roots(HdUnloadReader.read(sample, LAYOUTS), "PAUTSUM0")) {
            FixedRecord s = root.segment().data();
            if (s.allBytes("PA-ACCT-ID", 0x40)) {
                continue;
            }
            roots++;
            long acct = s.decimal("PA-ACCT-ID").longValueExact();
            assertThat(accounts.contains(acct)).as(root + " account is in acctdata").isTrue();
            for (Segment d : root.children()) {
                FixedRecord x = xref.get(d.data().raw("PA-CARD-NUM"));
                if (x != null && Long.parseLong(x.digits("XREF-ACCT-ID")) == acct
                        && x.digits("XREF-CUST-ID").equals(s.digits("PA-CUST-ID"))) {
                    matched++;
                } else {
                    mismatched++;
                }
            }
        }
        assertThat(roots).isEqualTo(21);
        assertThat(mismatched).as("details whose card is not the root's account and customer").isZero();
        assertThat(matched).isEqualTo(202);
    }

    // Synthetic files: a header, the given segment records, a trailer.

    private static byte[] record(int length, byte[] body) {
        byte[] r = new byte[length];
        r[0] = (byte) (length >> 8);
        r[1] = (byte) length;
        System.arraycopy(body, 0, r, 4, Math.min(body.length, length - 4));
        return r;
    }

    private static byte[] segment(String name, int segmentLength, int recordLength) {
        byte[] body = new byte[recordLength - 4];
        body[4] = (byte) (segmentLength >> 8);
        body[5] = (byte) segmentLength;
        byte[] n = String.format("%-8s", name).getBytes(HdUnloadReader.EBCDIC);
        System.arraycopy(n, 0, body, 6, 8);
        Arrays.fill(body, 35, recordLength - 5, (byte) 0x40);
        return record(recordLength, body);
    }

    private static byte[] file(byte[]... segments) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(record(88, new byte[0]));
        parts.addAll(List.of(segments));
        parts.add(record(88, new byte[0]));
        int total = parts.stream().mapToInt(p -> p.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static List<Segment> read(byte[] file) {
        return HdUnloadReader.read(file, "test.dat", LAYOUTS);
    }

    @Test
    void segmentDataStartsAtOffset39AndIsFollowedByOneByte() {
        byte[] sum = segment("PAUTSUM0", 100, 140);
        sum[39] = 0x12;
        sum[138] = (byte) 0xC1;
        Segment s = read(file(sum)).getFirst();
        assertThat(s.recordNumber()).isEqualTo(2);
        assertThat(s.data().bytes()[0]).isEqualTo((byte) 0x12);
        assertThat(s.data().raw("FILLER-1")).endsWith("A");
        assertThat(s.data().charset()).isEqualTo(HdUnloadReader.EBCDIC);
    }

    @Test
    void anythingElseFailsTheRead() {
        assertThatThrownBy(() -> read(file(segment("PAUTXXX0", 100, 140))))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("record 2: unexpected segment name");
        assertThatThrownBy(() -> read(file(segment("PAUTSUM0", 100, 141))))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("record 2");
        assertThatThrownBy(() -> read(file(segment("PAUTSUM0", 99, 140))))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("segment length 99");
        assertThatThrownBy(() -> read(file(segment("PAUTDTL1", 200, 140))))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("PAUTDTL1");
        byte[] trailing = segment("PAUTSUM0", 100, 140);
        trailing[139] = (byte) 0x40;
        assertThatThrownBy(() -> read(file(trailing))).isInstanceOf(LegacyFormatException.class)
                .hasMessageContaining("record 2: the byte after the segment data");
        byte[] ok = file(segment("PAUTSUM0", 100, 140));
        assertThatThrownBy(() -> read(Arrays.copyOf(ok, ok.length - 1)))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("the file ends");
        byte[] spanned = ok.clone();
        spanned[88 + 2] = 1;
        assertThatThrownBy(() -> read(spanned)).isInstanceOf(LegacyFormatException.class)
                .hasMessageContaining("record descriptor");
        assertThatThrownBy(() -> read(Arrays.copyOfRange(ok, 88, ok.length)))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("header and trailer");
        assertThat(read(file())).isEmpty();
    }

    @Test
    void aChildBelongsToTheRootBeforeItAndNeverPrecedesTheFirstRoot() {
        List<Segment> segs = read(file(segment("PAUTSUM0", 100, 140), segment("PAUTDTL1", 200, 240),
                segment("PAUTDTL1", 200, 240), segment("PAUTSUM0", 100, 140)));
        List<Root> roots = HdUnloadReader.roots(segs, "PAUTSUM0");
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).children()).extracting(Segment::recordNumber).containsExactly(3, 4);
        assertThat(roots.get(1).children()).isEmpty();
        List<Segment> orphan = read(file(segment("PAUTDTL1", 200, 240), segment("PAUTSUM0", 100, 140)));
        assertThatThrownBy(() -> HdUnloadReader.roots(orphan, "PAUTSUM0"))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("record 2: PAUTDTL1 before the first");
    }
}
