package org.carddemo.authorization.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmountTest {

    @Test
    @DisplayName("BR-AUTH-002 (D-32): invalid amounts are declined 9000 and stored as 0.00")
    @SpecItem({"BR-AUTH-002", "DEF-AUTH-007", "NUM-AUTH-001"})
    void invalid() {
        String[] invalid = {"0", "0.00", ".0", "-5.00", "5.00-", "12.345", "abc", "", "   ", "12345678901.00",
            "+1234567890.12", "1,000.00", "5.00CR", "5.00DB", "1e3", "+", ".", "+.", "50 .00", "\t50.00", "５0.00"};
        for (String s : invalid) {
            Amount a = Amount.parse(s);
            assertThat(a.valid()).as("'%s'", s).isFalse();
            assertThat(a.value()).as("'%s'", s).hasToString("0.00");
        }
    }

    @Test
    @DisplayName("BR-AUTH-002: valid amounts, spaces at both ends removed")
    @SpecItem({"BR-AUTH-002", "NUM-AUTH-001"})
    void valid() {
        String[][] cases = {{" 50 ", "50.00"}, {"+50", "50.00"}, {".5", "0.50"}, {"50.", "50.00"},
            {"0.01", "0.01"}, {"9999999999.99", "9999999999.99"}, {"+999999999.99", "999999999.99"},
            {"  1234567890.1  ", "1234567890.10"}, {"007", "7.00"}};
        for (String[] c : cases) {
            Amount a = Amount.parse(c[0]);
            assertThat(a.valid()).as("'%s'", c[0]).isTrue();
            assertThat(a.value()).as("'%s'", c[0]).hasToString(c[1]);
        }
    }
}
