package org.carddemo.platform.legacyformat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyCaseTest {

    @Test
    void onlyTheLettersAToZChange() {
        assertThat(LegacyCase.upper("Abc xyz1")).isEqualTo("ABC XYZ1");
        assertThat(LegacyCase.upper("0123456789 -_.")).isEqualTo("0123456789 -_.");
        assertThat(LegacyCase.upper("ABCDEFGH")).isEqualTo("ABCDEFGH");
        // The neighbours of a and z in ASCII stay.
        assertThat(LegacyCase.upper("`az{@AZ[")).isEqualTo("`AZ{@AZ[");
        assertThat(LegacyCase.upper("")).isEmpty();
    }

    @Test
    void everyOtherCharacterOfTheLegacyRangeIsKept() {
        StringBuilder in = new StringBuilder();
        StringBuilder out = new StringBuilder();
        for (char c = 0; c <= 0xFF; c++) {
            in.append(c);
            out.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        assertThat(LegacyCase.upper(in.toString())).isEqualTo(out.toString()).hasSize(256);
    }

    @Test
    void lettersOutsideAToZAreNotUpperCased() {
        // Java's toUpperCase gives É, SS, Ÿ (U+0178) and Μ (U+039C) for these.
        assertThat(LegacyCase.upper("é")).isEqualTo("é");
        assertThat(LegacyCase.upper("ß")).isEqualTo("ß").hasSize(1);
        assertThat(LegacyCase.upper("ÿ")).isEqualTo("ÿ");
        assertThat(LegacyCase.upper("µ")).isEqualTo("µ");
        assertThat(LegacyCase.upper("éa\u0000ÿz")).isEqualTo("éA\u0000ÿZ");
    }

    @Test
    void charactersAboveTheLegacyRangePassThrough() {
        assertThat(LegacyCase.upper("ı i \uD83D\uDE00 ﬀ")).isEqualTo("ı I \uD83D\uDE00 ﬀ");
    }
}
