package org.carddemo.identity.signon;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;

class LegacyTextTest {

    @Test
    @SpecItem("NUM-SGN-002")
    void upperChangesOnlyLowerCaseAsciiLetters() {
        assertThat(LegacyText.upper("Abc xyz1")).isEqualTo("ABC XYZ1");
        assertThat(LegacyText.upper("0123456789 -_.")).isEqualTo("0123456789 -_.");
        assertThat(LegacyText.upper("ABCDEFGH")).isEqualTo("ABCDEFGH");
        // The neighbours of a-z and A-Z in ASCII stay as they are.
        assertThat(LegacyText.upper("`az{@AZ[")).isEqualTo("`AZ{@AZ[");
        assertThat(LegacyText.upper("ßéü")).as("letters outside a-z").isEqualTo("ßéü");
        assertThat(LegacyText.upper("")).isEmpty();
    }
}
