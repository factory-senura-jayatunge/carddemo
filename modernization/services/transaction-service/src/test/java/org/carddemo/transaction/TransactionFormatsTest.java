package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransactionFormatsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "0.00|+00000000.00",
        "50.47|+00000050.47",
        "-91.90|-00000091.90",
        "99999999.99|+99999999.99",
        "-99999999.99|-99999999.99",
        "100000000.00|+100000000.00",
        "-123456789.01|-123456789.01",
        "999999999.99|+999999999.99"
    })
    void amountDisplayWidensOnlyFromNineDigits(String value, String expected) {
        assertThat(TransactionFormats.amountDisplay(new BigDecimal(value))).isEqualTo(expected);
    }

    @Test
    void amountIsThePlainDecimal() {
        assertThat(TransactionFormats.amount(new BigDecimal("-91.90"))).isEqualTo("-91.90");
        assertThat(TransactionFormats.amount(new BigDecimal("123456789.01"))).isEqualTo("123456789.01");
        assertThat(TransactionFormats.amount(new BigDecimal("0"))).isEqualTo("0.00");
    }

    @Test
    void listDateIsMonthDayYear() {
        assertThat(TransactionFormats.listDate(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123456000)))
                .isEqualTo("06/10/22");
        assertThat(TransactionFormats.listDate(null)).isEmpty();
    }

    @Test
    void timestampIsTheStoredLayout() {
        assertThat(TransactionFormats.timestamp(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123456000)))
                .isEqualTo("2022-06-10 19:27:53.123456");
        assertThat(TransactionFormats.timestamp(null)).isEmpty();
    }

    @Test
    void listDescriptionIsTheFirst26CharactersWithoutTrailingSpaces() {
        assertThat(TransactionFormats.listDescription("Purchase at Abshire-Lowe and Co"))
                .isEqualTo("Purchase at Abshire-Lowe a");
        assertThat(TransactionFormats.listDescription("Purchase at Abshire-Lowe  x")).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(TransactionFormats.listDescription("  short  ")).isEqualTo("  short");
        assertThat(TransactionFormats.listDescription(null)).isEmpty();
    }

    @Test
    void onlyTrailingSpacesArePadding() {
        assertThat(TransactionFormats.trimTrailing("ab  ")).isEqualTo("ab");
        assertThat(TransactionFormats.trimTrailing(" ab")).isEqualTo(" ab");
        assertThat(TransactionFormats.trimTrailing("ab\u00A0")).isEqualTo("ab\u00A0");
        assertThat(TransactionFormats.trimTrailing("ab\t")).isEqualTo("ab\t");
        assertThat(TransactionFormats.trimTrailing("    ")).isEmpty();
        assertThat(TransactionFormats.trimTrailing(null)).isEmpty();
    }
}
