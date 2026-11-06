package org.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;

class BillPayNumbersTest {

    @Test
    @SpecItem("NUM-BILL-001")
    void theBalanceIsShownInTheLegacyEdit() {
        assertThat(BillPayService.display(new BigDecimal("194.00"))).isEqualTo("+0000000194.00");
        assertThat(BillPayService.display(new BigDecimal("0.00"))).isEqualTo("+0000000000.00");
        assertThat(BillPayService.display(new BigDecimal("-25.50"))).isEqualTo("-0000000025.50");
        assertThat(BillPayService.display(new BigDecimal("1234567890.12"))).isEqualTo("+1234567890.12");
        assertThat(BillPayService.display(new BigDecimal("-0.05"))).isEqualTo("-0000000000.05");
    }

    @Test
    @SpecItem("NUM-BILL-002")
    void theAmountKeepsNineIntegerDigits() {
        assertThat(LegacyNumbers.truncate(new BigDecimal("1234567890.12"), 9)).isEqualByComparingTo("234567890.12");
        assertThat(LegacyNumbers.truncate(new BigDecimal("194.00"), 9)).isEqualByComparingTo("194.00");
        assertThat(LegacyNumbers.truncate(new BigDecimal("1000000000.00"), 9)).isEqualByComparingTo("0.00");
    }

    @Test
    @SpecItem("NUM-BILL-003")
    void timestampsHaveNoFractionOfASecond() {
        assertThat(BillPayService.wholeSeconds(LocalDateTime.of(2026, 11, 6, 9, 30, 15, 654_321_000)))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 30, 15));
    }

    @Test
    void amountsTravelAsTwoDecimalStrings() {
        assertThat(BillPayService.plain(new BigDecimal("194"))).isEqualTo("194.00");
        assertThat(BillPayService.plain(new BigDecimal("-25.5"))).isEqualTo("-25.50");
    }
}
