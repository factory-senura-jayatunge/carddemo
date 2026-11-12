package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;

/** The interest arithmetic of CBACT04C (COMPUTE without ROUNDED into PIC S9(09)V99) and the PARM's width. */
class InterestArithmeticTest {

    private static BigDecimal monthly(String balance, String rate) {
        return CalculateInterestTasklet.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate));
    }

    @Test
    @SpecItem({"BR-INTC-001", "NUM-INTC-002"})
    void theMonthlyInterestIsTruncatedTowardZeroToCents() {
        // The examples of BR-INTC-001.
        assertThat(monthly("1164.87", "15.00")).isEqualTo("14.56");
        assertThat(monthly("-1.00", "15.00")).isEqualTo("-0.01");
        assertThat(monthly("-0.50", "15.00")).isEqualTo("0.00");
        assertThat(monthly("333.33", "18.00")).isEqualTo("4.99");
        assertThat(monthly("0.00", "18.00")).isEqualTo("0.00");
    }

    @Test
    @SpecItem("NUM-INTC-003")
    void aResultPastNineIntegerDigitsKeepsItsLowOrderDigitsAndSign() {
        // 999,999,999.99 x 9,999.99 / 1200 = 8,333,324,999.91...
        assertThat(monthly("999999999.99", "9999.99")).isEqualTo("333324999.91");
        assertThat(monthly("-999999999.99", "9999.99")).isEqualTo("-333324999.91");
        assertThat(CalculateInterestTasklet.cut(new BigDecimal("999999999.99").add(new BigDecimal("0.02"))))
                .isEqualTo("0.01");
        assertThat(CalculateInterestTasklet.cut(new BigDecimal("1000000000.00"))).isEqualTo("0.00");
    }

    @Test
    @SpecItem("NUM-INTC-004")
    void theParmGivesTenCharactersPaddedWithSpaces() {
        assertThat(new InterestProperties("2022071800").parmDate()).isEqualTo("2022071800");
        assertThat(new InterestProperties("20220718").parmDate()).isEqualTo("20220718  ");
        assertThat(new InterestProperties("2022071800EXTRA").parmDate()).isEqualTo("2022071800");
        assertThatThrownBy(() -> new InterestProperties("")).isInstanceOf(IllegalArgumentException.class);
    }
}
