package org.carddemo.platform.legacyformat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.LegacyFileWriter;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sample files of {@code app/data/ASCII} through the layouts and entity
 * records: counts, key sets, control totals and signed-field sums agree with
 * what {@code tests/oracle.py} decodes ({@code golden/sample-expectations.json}),
 * and every file re-exports byte for byte after line-ending normalization and
 * padding of short lines. Failures name files, lines and fields, never values.
 */
class SampleDataTest {

    record SampleFile(String name, RecordLayout layout, Function<byte[], byte[]> roundTrip) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<SampleFile> FILES = List.of(
            new SampleFile("acctdata.txt", Layouts.ACCOUNT, b -> AccountRecord.parse(b).toBytes()),
            new SampleFile("custdata.txt", Layouts.CUSTOMER, b -> CustomerRecord.parse(b).toBytes()),
            new SampleFile("carddata.txt", Layouts.CARD, b -> CardRecord.parse(b).toBytes()),
            new SampleFile("cardxref.txt", Layouts.CARD_XREF, b -> CardXrefRecord.parse(b).toBytes()),
            new SampleFile("tcatbal.txt", Layouts.TCAT_BAL, b -> TcatBalRecord.parse(b).toBytes()),
            new SampleFile("trantype.txt", Layouts.TRAN_TYPE, b -> TranTypeRecord.parse(b).toBytes()),
            new SampleFile("trancatg.txt", Layouts.TRAN_CATEGORY, b -> TranCategoryRecord.parse(b).toBytes()),
            new SampleFile("discgrp.txt", Layouts.DISC_GROUP, b -> DiscGroupRecord.parse(b).toBytes()),
            new SampleFile("dailytran.txt", Layouts.DAILY_TRAN, b -> TransactionRecord.parseDaily(b).toDailyBytes()));

    private static Path dataDir;
    private static JsonNode expectations;

    @BeforeAll
    static void locate() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("app/data/ASCII"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with app/data/ASCII above the module").isNotNull();
        dataDir = dir.resolve("app/data/ASCII");
        try (InputStream in = SampleDataTest.class.getResourceAsStream("/golden/sample-expectations.json")) {
            expectations = JsonMapper.builder().build().readTree(in);
        }
    }

    static Stream<Arguments> files() {
        return FILES.stream().map(Arguments::of);
    }

    private static List<SourceLine> read(SampleFile f) {
        return LegacyFileReader.read(dataDir.resolve(f.name()), f.layout().length());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void countsKeysAndTotalsMatchTheOracle(SampleFile f) {
        JsonNode exp = expectations.get(f.name());
        List<SourceLine> lines = read(f);
        assertThat(lines).hasSize(exp.get("count").asInt());
        assertThat(lines.stream().filter(SourceLine::padded).count()).isEqualTo(exp.get("padded").asLong());

        if (exp.has("keyLength")) {
            int keyLength = exp.get("keyLength").asInt();
            List<String> keys = lines.stream()
                    .map(l -> new String(l.record(), 0, keyLength, StandardCharsets.ISO_8859_1)).toList();
            List<String> sorted = keys.stream().sorted().toList();
            assertThat(keys.equals(sorted)).as("in key order").isEqualTo(exp.get("inKeyOrder").asBoolean());
            assertThat(Set.copyOf(keys)).hasSize(exp.get("distinctKeys").asInt());
            CRC32 crc = new CRC32();
            crc.update(String.join("\n", sorted).getBytes(StandardCharsets.ISO_8859_1));
            assertThat(String.format("%08x", crc.getValue())).as("CRC-32 of the sorted keys")
                    .isEqualTo(exp.get("keysCrc32").asString());
            if (exp.has("firstKey")) {
                assertThat(sorted.getFirst()).as("first key").isEqualTo(exp.get("firstKey").asString());
                assertThat(sorted.getLast()).as("last key").isEqualTo(exp.get("lastKey").asString());
            }
        }

        for (String field : exp.get("sums").propertyNames()) {
            BigDecimal sum = lines.stream().map(l -> l.view(f.layout()).decimal(field))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as(field).isEqualByComparingTo(new BigDecimal(exp.get("sums").get(field).asString()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void everyRecordRoundTripsThroughItsEntity(SampleFile f) {
        List<Integer> mismatches = new ArrayList<>();
        for (SourceLine l : read(f)) {
            if (!Arrays.equals(f.roundTrip().apply(l.record()), l.record())) {
                mismatches.add(l.lineNumber());
            }
        }
        assertThat(mismatches).as("lines that do not round-trip").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void reExportIsByteExactAfterNormalization(SampleFile f) throws IOException {
        List<byte[]> exported = new ArrayList<>();
        for (SourceLine l : read(f)) {
            exported.add(f.roundTrip().apply(l.record()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LegacyFileWriter.write(out, exported);
        assertThat(out.toByteArray()).isEqualTo(normalized(dataDir.resolve(f.name()), f.layout().length()));
    }

    /** The original file with LF endings, a final LF, and short lines padded with spaces. */
    private static byte[] normalized(Path file, int length) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(line).append(" ".repeat(Math.max(0, length - line.length()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void unnamedFillersHoldTheirConstant(SampleFile f) {
        for (FieldSpec spec : f.layout().fields()) {
            if (spec.type() != FieldType.FILLER) {
                continue;
            }
            String expected = String.valueOf(spec.fill()).repeat(spec.length());
            List<Integer> bad = read(f).stream()
                    .filter(l -> !l.view(f.layout()).raw(spec).equals(expected))
                    .map(SourceLine::lineNumber).toList();
            assertThat(bad).as("%s lines whose %s is not all '%s'", f.name(), spec.name(), spec.fill()).isEmpty();
        }
    }

    @Test
    void lineEndingsAreMixedInSomeFiles() {
        Map<String, Set<Boolean>> endings = FILES.stream().collect(Collectors.toMap(SampleFile::name,
                f -> read(f).stream().map(SourceLine::crlf).collect(Collectors.toSet())));
        assertThat(endings.get("tcatbal.txt")).containsExactlyInAnyOrder(true, false);
        assertThat(endings.get("acctdata.txt")).containsExactly(false);
    }

    @Test
    void accountZipHoldsTheGroupIdAndGroupIdIsBlank() {
        // D-11: positions 103-112 carry A000000000 in every sample account; the loader moves it to group_id.
        List<AccountRecord> accounts = read(FILES.get(0)).stream().map(l -> AccountRecord.parse(l.record())).toList();
        assertThat(accounts).extracting(AccountRecord::addrZip).containsOnly("A000000000");
        assertThat(accounts).extracting(AccountRecord::groupId).containsOnly("");
        assertThat(accounts).allSatisfy(a -> assertThat(a.toString()).doesNotContain("A000000000"));
    }

    @Test
    void crossReferenceAgreesWithTheCardFile() {
        Map<String, String> cardToAcct = read(FILES.get(2)).stream().map(l -> CardRecord.parse(l.record()))
                .collect(Collectors.toMap(CardRecord::cardNum, CardRecord::acctId));
        Map<String, String> xrefToAcct = read(FILES.get(3)).stream().map(l -> CardXrefRecord.parse(l.record()))
                .collect(Collectors.toMap(CardXrefRecord::cardNum, CardXrefRecord::acctId));
        assertThat(xrefToAcct.equals(cardToAcct)).as("same card-to-account pairs").isTrue();
        Set<String> accounts = read(FILES.get(0)).stream().map(l -> AccountRecord.parse(l.record()).acctId())
                .collect(Collectors.toSet());
        assertThat(accounts.containsAll(cardToAcct.values())).isTrue();
        Set<String> tcatAccounts = read(FILES.get(4)).stream().map(l -> TcatBalRecord.parse(l.record()).acctId())
                .collect(Collectors.toSet());
        assertThat(accounts.containsAll(tcatAccounts)).isTrue();
        Set<String> groups = read(FILES.get(7)).stream().map(l -> DiscGroupRecord.parse(l.record()).groupId())
                .collect(Collectors.toSet());
        assertThat(groups).contains("DEFAULT", "A000000000");
    }

    @Test
    void toStringNeverShowsPersonalData() {
        SourceLine card = read(FILES.get(2)).get(0);
        CardRecord c = CardRecord.parse(card.record());
        assertThat(c.toString()).doesNotContain(c.cardNum()).doesNotContain(c.embossedName()).endsWith(
                c.cardNum().substring(12) + "]");
        CustomerRecord cust = CustomerRecord.parse(read(FILES.get(1)).get(0).record());
        assertThat(cust.toString()).doesNotContain(cust.ssn()).doesNotContain(cust.lastName());
        TransactionRecord t = TransactionRecord.parseDaily(read(FILES.get(8)).get(0).record());
        assertThat(t.toString()).doesNotContain(t.cardNum());
        assertThat(FixedRecord.of(Layouts.CARD, card.record()).toString()).doesNotContain(c.cardNum());
        assertThat(card.toString()).doesNotContain(c.cardNum());
    }

    @Test
    void userSecurityRecordDropsThePassword() {
        String raw = String.format("%-8s%-20s%-20s%-8s%-1s%-23s", "USER0001", "FIRST", "LAST", "PASSWORD", "U", "");
        UserSecurityRecord u = UserSecurityRecord.parse(raw.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(u).isEqualTo(new UserSecurityRecord("USER0001", "FIRST", "LAST", "U"));
        assertThat(u.toString()).doesNotContain("PASSWORD").doesNotContain("FIRST");
    }
}
