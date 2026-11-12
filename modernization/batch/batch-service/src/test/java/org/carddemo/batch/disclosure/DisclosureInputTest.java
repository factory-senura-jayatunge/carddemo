package org.carddemo.batch.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisclosureInputTest {
    @TempDir Path dir;

    private static String record(String group, String category) {
        return new String(new DiscGroupRecord(group, "01", category, new BigDecimal("15.00")).toBytes(),
                StandardCharsets.ISO_8859_1);
    }

    private Path file(String text) throws Exception {
        return Files.writeString(dir.resolve("2022-07-06.txt"), text, StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    @SpecItem({"SE-JDISC-003", "NUM-JDISC-001"})
    void exactRecordsAndLineEndings(String ending) throws Exception {
        String first = record("DEFAULT", "0001");
        String second = record("ZEROAPR", "0002");
        byte[] before = (first + ending + second + ending).getBytes(StandardCharsets.ISO_8859_1);
        Path source = file(new String(before, StandardCharsets.ISO_8859_1));
        var records = DisclosureInput.read(source);
        assertThat(records).hasSize(2);
        assertThat(records.getFirst().toBytes()).isEqualTo(first.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(Files.readAllBytes(source)).isEqualTo(before);
    }

    @Test
    void sampleHas51FullyRepresentableRecords() {
        Path sample = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app/data/ASCII/discgrp.txt");
        assertThat(DisclosureInput.read(sample)).hasSize(51);
    }

    @Test
    void acceptsFinalRecordWithoutLineEnding() throws Exception {
        assertThat(DisclosureInput.read(file(record("DEFAULT", "0001")))).hasSize(1);
    }

    @Test
    @SpecItem("VAL-JDISC-001")
    void rejectsDuplicateAtItsLine() throws Exception {
        String row = record("DEFAULT", "0001");
        Path source = file(row + "\n" + row);
        assertThatThrownBy(() -> DisclosureInput.read(source))
                .isInstanceOf(CatalogueFailure.class).hasMessageContaining("VAL-JDISC-001: discgrp line 2");
    }

    @Test
    @SpecItem("VAL-JDISC-001")
    void rejectsDescendingKeyAtItsLine() throws Exception {
        Path source = file(record("ZEROAPR", "0001") + "\n" + record("DEFAULT", "0001"));
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("VAL-JDISC-001: discgrp line 2");
    }

    @Test
    void rejectsNoDefault() throws Exception {
        Path source = file(record("ZEROAPR", "0001"));
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("DEFAULT_GROUP_MISSING");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 49, 51})
    @SpecItem({"SE-JDISC-002", "NUM-JDISC-001"})
    void rejectsWrongLength(int length) throws Exception {
        Path source = file(" ".repeat(length) + "\n");
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("line 1");
    }

    @Test
    void rejectsFillerThatCannotBeReexported() throws Exception {
        String row = record("DEFAULT", "0001");
        Path source = file(row.substring(0, 49) + "X");
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("FILLER_CONSTANT: discgrp line 1");
    }

    @Test
    void rejectsInvalidRateWithoutLeakingRecord() throws Exception {
        String row = record("DEFAULT", "0001");
        Path source = file(row.substring(0, 16) + "SECRET" + row.substring(22));
        assertThatThrownBy(() -> DisclosureInput.read(source))
                .hasMessageNotContaining("SECRET").hasMessageContaining("RECORD_LAYOUT", "line 1");
    }

    @Test
    void rejectsNegativeZeroThatCannotBeReexported() throws Exception {
        String row = record("DEFAULT", "0001");
        Path source = file(row.substring(0, 16) + "00000}" + row.substring(22));
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("NEGATIVE_ZERO", "line 1");
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> DisclosureInput.read(dir.resolve("missing.txt")))
                .hasMessage("INBOUND_FILE_MISSING: discgrp");
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        Path source = file("");
        assertThatThrownBy(() -> DisclosureInput.read(source)).hasMessageContaining("DEFAULT_GROUP_MISSING");
    }
}
