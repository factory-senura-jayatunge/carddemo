package org.carddemo.platform.legacyformat.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.junit.jupiter.api.Test;

class RecordIoTest {

    private static final String CARD = "4111222233334444";

    private static List<LegacyFileReader.SourceLine> read(String content, int length) throws IOException {
        return LegacyFileReader.read(new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)), length,
                "test.txt");
    }

    @Test
    void readerAcceptsLfCrlfAndAMissingFinalNewline() throws IOException {
        List<LegacyFileReader.SourceLine> lines = read("AB\r\nCD\nE", 3);
        assertThat(lines).hasSize(3);
        assertThat(lines).extracting(LegacyFileReader.SourceLine::crlf).containsExactly(true, false, false);
        assertThat(new String(lines.get(2).record(), StandardCharsets.ISO_8859_1)).isEqualTo("E  ");
        assertThat(lines).allMatch(LegacyFileReader.SourceLine::padded);
    }

    @Test
    void readerErrorsNameTheLineButNotTheContent() {
        assertThatThrownBy(() -> read(CARD + "XX\n", 16))
                .isInstanceOf(LegacyFormatException.class)
                .hasMessageContaining("test.txt line 1")
                .message().doesNotContain(CARD);
        assertThatThrownBy(() -> read("A\n\nB\n", 1)).hasMessageContaining("line 2 is empty");
    }

    @Test
    void builderStartsFromTheLayoutFillAndIsStrict() {
        byte[] r = RecordBuilder.of(Layouts.TCAT_BAL)
                .digits("TRANCAT-ACCT-ID", "00000000001")
                .text("TRANCAT-TYPE-CD", "02")
                .digits("TRANCAT-CD", 2)
                .decimal("TRAN-CAT-BAL", new BigDecimal("-194.00"))
                .build();
        assertThat(new String(r, StandardCharsets.ISO_8859_1))
                .isEqualTo("00000000001" + "02" + "0002" + "0000001940}" + "0".repeat(22));

        RecordBuilder b = RecordBuilder.of(Layouts.CARD);
        assertThatThrownBy(() -> b.text("CARD-NUM", CARD + "5"))
                .isInstanceOf(LegacyFormatException.class)
                .hasMessageContaining("CARD-RECORD.CARD-NUM (position 1, length 16)")
                .message().doesNotContain(CARD);
        assertThatThrownBy(() -> b.digits("CARD-ACCT-ID", "1234567890X"))
                .message().doesNotContain("1234567890");
        assertThatThrownBy(() -> RecordBuilder.of(Layouts.ACCOUNT).decimal("ACCT-CURR-BAL", new BigDecimal("1E10")))
                .hasMessageContaining("ACCT-CURR-BAL");
    }

    @Test
    void fixedRecordErrorsNameTheFieldButNotTheContent() {
        String rec = CARD + "0000000000X" + " ".repeat(150 - 27);
        FixedRecord r = FixedRecord.of(Layouts.CARD, rec);
        assertThatThrownBy(() -> r.digits("CARD-ACCT-ID"))
                .hasMessageContaining("CARD-ACCT-ID (position 17, length 11)")
                .message().doesNotContain("0000000000X");
        assertThatThrownBy(() -> FixedRecord.of(Layouts.CARD, CARD)).message().doesNotContain(CARD);
        assertThat(r.toString()).doesNotContain(CARD);
    }

    @Test
    void writerUsesLf() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LegacyFileWriter.write(out, List.of("AB".getBytes(StandardCharsets.ISO_8859_1),
                "CD".getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(out.toString(StandardCharsets.ISO_8859_1)).isEqualTo("AB\nCD\n");
    }
}
