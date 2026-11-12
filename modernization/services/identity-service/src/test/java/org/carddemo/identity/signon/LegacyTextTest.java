package org.carddemo.identity.signon;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.LegacyCase;
import org.junit.jupiter.api.Test;

class LegacyTextTest {

    @Test
    @SpecItem("NUM-SGN-002")
    void signOnUpperCasesOnlyLowerCaseAsciiLetters() {
        assertThat(LegacyCase.upper("Abc xyz1")).isEqualTo("ABC XYZ1");
        assertThat(LegacyCase.upper("0123456789 -_.")).isEqualTo("0123456789 -_.");
        assertThat(LegacyCase.upper("ABCDEFGH")).isEqualTo("ABCDEFGH");
        // The neighbours of a-z and A-Z in ASCII stay as they are.
        assertThat(LegacyCase.upper("`az{@AZ[")).isEqualTo("`AZ{@AZ[");
        assertThat(LegacyCase.upper("ßéü")).as("letters outside a-z").isEqualTo("ßéü");
        assertThat(LegacyCase.upper("")).isEmpty();
    }
}
