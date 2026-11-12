package org.carddemo.platform.legacyformat.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.carddemo.platform.legacyformat.Latin1;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.junit.jupiter.api.Test;

/** Every byte value of a text field is a character and back; nothing outside U+00FF is written as '?'. */
class TextRepertoireTest {

    private static final RecordLayout LAYOUT = RecordLayout.builder("T-RECORD", 260)
            .text("T-ALL", 256)
            .text("T-SHORT", 4)
            .build();

    private static String allCharacters() {
        StringBuilder sb = new StringBuilder();
        for (char c = 0; c <= 0xFF; c++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void everyByteValueRoundTripsThroughATextField() {
        String all = allCharacters();
        byte[] record = RecordBuilder.of(LAYOUT).text("T-ALL", all).text("T-SHORT", "\u0080\u009f").build();

        for (int i = 0; i <= 0xFF; i++) {
            assertThat(record[i] & 0xFF).as("byte %d", i).isEqualTo(i);
        }
        FixedRecord back = FixedRecord.of(LAYOUT, record);
        assertThat(back.raw("T-ALL")).isEqualTo(all);
        assertThat(back.text("T-SHORT")).isEqualTo("\u0080\u009f");
        assertThat(FixedRecord.of(LAYOUT, back.raw("T-ALL") + back.raw("T-SHORT")).bytes()).isEqualTo(record);
    }

    @Test
    void aCharacterAboveU00ffIsAnErrorThatNamesTheFieldAndNotTheValue() {
        assertThatThrownBy(() -> RecordBuilder.of(LAYOUT).text("T-SHORT", "ab\u20acd"))
                .isInstanceOf(LegacyFormatException.class)
                .hasMessage("T-RECORD.T-SHORT (position 257, length 4): value has a character outside ISO-8859-1"
                        + " at position 3")
                .message().doesNotContain("ab");
        // An emoji is a surrogate pair: one character, refused as a whole.
        assertThatThrownBy(() -> RecordBuilder.of(LAYOUT).text("T-SHORT", "\ud83d\ude00"))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("T-RECORD.T-SHORT");
        assertThatThrownBy(() -> RecordBuilder.of(LAYOUT).raw("T-SHORT", "\u0100   "))
                .isInstanceOf(LegacyFormatException.class).hasMessageContaining("position 1");
    }

    @Test
    void aRecordGivenAsTextIsEncodedStrictly() {
        String text = " ".repeat(259) + "\u0153";
        assertThatThrownBy(() -> FixedRecord.of(LAYOUT, text)).isInstanceOf(LegacyFormatException.class)
                .hasMessage("T-RECORD has a character outside ISO-8859-1 at position 260");
        assertThat(Latin1.firstUnmappable(allCharacters())).isEqualTo(-1);
        assertThat(Latin1.encode(allCharacters(), "x")).hasSize(256);
    }
}
