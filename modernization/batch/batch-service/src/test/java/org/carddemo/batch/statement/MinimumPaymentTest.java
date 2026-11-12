package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The minimum payment vectors of batch.md, "Minimum payment" (all ten rows, as amended 2026-11-11:
 * {@code C = 40.00, I = 30.00} is 30.40, and {@code C = 40.00, I = 50.00} is the cap case), with the
 * default floor 25.00 and percentage 1.00.
 */
class MinimumPaymentTest {

    static final BigDecimal FLOOR = new BigDecimal("25.00");
    static final BigDecimal PERCENT = new BigDecimal("1.00");

    @ParameterizedTest(name = "C={0} I={1} -> {2}")
    @CsvSource({
            "-10.00, 0, 0.00",
            "0.00, 0, 0.00",
            "20.00, 0, 20.00",
            "194.00, 0, 25.00",
            "3000.00, 0, 30.00",
            "3000.00, 37.50, 67.50",
            "1234.56, 0, 25.00",
            "9876.55, 0, 98.77",
            "40.00, 30.00, 30.40",
            "40.00, 50.00, 40.00"})
    void theVectors(String closing, String interest, String minimum) {
        assertThat(MinimumPayment.of(new BigDecimal(closing), new BigDecimal(interest), FLOOR, PERCENT))
                .isEqualByComparingTo(minimum).hasScaleOf(2);
    }

    /** {@code I = max(0, interest)}: a negative period interest adds nothing and takes nothing away. */
    @ParameterizedTest(name = "C={0} I={1} -> {2}")
    @CsvSource({"3000.00, -12.00, 30.00", "40.00, -5.00, 25.00"})
    void negativeInterestCountsAsZero(String closing, String interest, String minimum) {
        assertThat(MinimumPayment.of(new BigDecimal(closing), new BigDecimal(interest), FLOOR, PERCENT))
                .isEqualByComparingTo(minimum);
    }

    @ParameterizedTest(name = "F={0} P={1}: C=1000.00 -> {2}")
    @CsvSource({"0.00, 2.50, 25.00", "100.00, 1.00, 100.00", "0.00, 100, 1000.00"})
    void theFloorAndPercentageAreSettings(String floor, String percent, String minimum) {
        assertThat(MinimumPayment.of(new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal(floor),
                new BigDecimal(percent))).isEqualByComparingTo(minimum);
    }
}
