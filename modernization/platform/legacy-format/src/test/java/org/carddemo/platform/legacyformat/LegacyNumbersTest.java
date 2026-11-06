package org.carddemo.platform.legacyformat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegacyNumbersTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "194.00|+0000000194.00",
        "0.00|+0000000000.00",
        "-25.50|-0000000025.50",
        "1234567890.12|+1234567890.12",
        "-9999999999.99|-9999999999.99",
        "12345678901.23|+2345678901.23"
    })
    void editSignedIsTheBillPayBalanceDisplay(String value, String expected) {
        assertThat(LegacyNumbers.editSigned(new BigDecimal(value), 10, 2)).isEqualTo(expected);
    }

    @Test
    void editSignedWithoutDecimals() {
        assertThat(LegacyNumbers.editSigned(new BigDecimal("-42"), 6, 0)).isEqualTo("-000042");
    }

    @ParameterizedTest(name = "truncate({0}, {1}) = {2}")
    @CsvSource(delimiter = '|', value = {
        "1234567890.12|9|234567890.12",
        "-1234567890.12|9|-234567890.12",
        "1000000000.00|9|0.00",
        "999999999.99|9|999999999.99",
        "194.00|9|194.00",
        "123456789012.5|10|3456789012.5"
    })
    void truncateDropsHighOrderDigitsAndKeepsSign(String value, int digits, String expected) {
        assertThat(LegacyNumbers.truncate(new BigDecimal(value), digits)).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void truncateToPictureDropsDecimalsWithoutRounding() {
        assertThat(LegacyNumbers.truncate(new BigDecimal("-12.349"), Pic.parse("S9(09)V99")))
                .isEqualTo(new BigDecimal("-12.34"));
        assertThat(LegacyNumbers.truncate(new BigDecimal("-12.349"), Pic.parse("9(09)V99")))
                .isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void fitsChecksDigitsDecimalsAndSign() {
        Pic p = Pic.parse("S9(10)V99");
        assertThat(LegacyNumbers.fits(new BigDecimal("9999999999.99"), p)).isTrue();
        assertThat(LegacyNumbers.fits(new BigDecimal("10000000000.00"), p)).isFalse();
        assertThat(LegacyNumbers.fits(new BigDecimal("1.001"), p)).isFalse();
        assertThat(LegacyNumbers.fits(new BigDecimal("1.0100"), p)).isTrue();
        assertThat(LegacyNumbers.fits(new BigDecimal("-1"), Pic.parse("9(3)"))).isFalse();
    }

    @Test
    void zonedDecodesEveryOverpunch() {
        Pic p = Pic.parse("S9(03)V99");
        String pos = "{ABCDEFGHI";
        String neg = "}JKLMNOPQR";
        for (int d = 0; d < 10; d++) {
            assertThat(Zoned.decode("0012" + pos.charAt(d), p)).isEqualTo(new BigDecimal("1.2" + d));
            assertThat(Zoned.decode("0012" + neg.charAt(d), p)).isEqualTo(new BigDecimal("-1.2" + d));
            assertThat(Zoned.decode("0012" + d, p)).isEqualTo(new BigDecimal("1.2" + d));
        }
        assertThat(Zoned.isNegativeZero("0000}")).isTrue();
        assertThat(Zoned.isNegativeZero("0000{")).isFalse();
    }

    @Test
    void zonedStrictEncodeRefusesWhatDoesNotFit() {
        assertThat(Zoned.encode(new BigDecimal("-25.50"), Pic.parse("S9(09)V99"))).isEqualTo("0000000255}");
        assertThat(Zoned.encode(BigDecimal.ZERO, Pic.parse("S9(09)V99"))).isEqualTo("0000000000{");
        assertThatThrownBy(() -> Zoned.encode(new BigDecimal("1000000000"), Pic.parse("S9(09)V99")))
                .isInstanceOf(LegacyFormatException.class);
    }

    @Test
    void packedAndBinaryRoundTrip() {
        Pic p = Pic.parse("S9(09)V99");
        for (String v : new String[] {"0.00", "-0.01", "123456789.99", "-123456789.99", "42.10"}) {
            BigDecimal d = new BigDecimal(v);
            assertThat(Packed.decode(Packed.encode(d, p), p)).isEqualTo(d);
            assertThat(Binary.decode(Binary.encode(d, p), p)).isEqualTo(d);
        }
        assertThat(HexFormat.of().withUpperCase().formatHex(Packed.encode(new BigDecimal("-25.50"), p)))
                .isEqualTo("00000002550D");
        assertThat(Packed.length(p)).isEqualTo(6);
        assertThat(Binary.length(p)).isEqualTo(8);
    }

    @Test
    void decodeErrorsNeverShowTheContent() {
        assertThatThrownBy(() -> Zoned.decode("4111X1", Pic.parse("9(6)")))
                .isInstanceOf(LegacyFormatException.class)
                .message().doesNotContain("4111");
        assertThatThrownBy(() -> Zoned.decode("41112", Pic.parse("9(6)")))
                .message().doesNotContain("4111");
    }
}
