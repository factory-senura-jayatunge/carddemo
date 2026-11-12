package org.carddemo.batch.categorylisting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@BothModes("DEF-JPCAT-001")
@SpecItem({"BR-JPCAT-001", "NUM-JPCAT-001", "DEF-JPCAT-001", "DEF-JPCAT-002"})
class CategoryBalanceLineTest {
    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-12.34", "0.00", "999999999.99", "-999999999.99"})
    void preservesEveryColumnAndMagnitude(String amount) {
        BigDecimal balance = new BigDecimal(amount);
        boolean fixed = FidelityOverrides.current() == FidelityMode.FIXED;
        byte[] line = CategoryBalanceLine.format("00000000001", "01", "0001", balance, fixed);
        assertThat(line).hasSize(41);
        String text = new String(line, StandardCharsets.ISO_8859_1);
        assertThat(text.substring(0, 20)).isEqualTo("00000000001 01 0001 ");
        assertThat(new BigDecimal(text.substring(20, 32))).isEqualByComparingTo(balance.abs());
        assertThat(text.charAt(32)).isEqualTo(fixed && balance.signum() < 0 ? '-' : ' ');
        assertThat(text.substring(33)).isEqualTo("       \n");
        assertThat(text.substring(20, 32)).matches("[0-9]{9}\\.[0-9]{2}");
    }

    @Test
    void onlyTheNegativeSignColumnDiffersAcrossModes() {
        byte[] legacy = CategoryBalanceLine.format("00000000001", "01", "0001", new BigDecimal("-12.34"), false);
        byte[] fixed = CategoryBalanceLine.format("00000000001", "01", "0001", new BigDecimal("-12.34"), true);
        for (int i = 0; i < legacy.length; i++) {
            if (i == 32) assertThat(fixed[i]).isEqualTo((byte) '-');
            else assertThat(fixed[i]).isEqualTo(legacy[i]);
        }
    }

    @Test
    void outOfPictureAndFractionalCentValuesFailWithoutIdentifiers() {
        for (String amount : new String[] {"1000000000.00", "0.001"}) {
            assertThatThrownBy(() -> CategoryBalanceLine.format(
                    "12345678901", "01", "0001", new BigDecimal(amount), true))
                    .hasMessageNotContaining("12345678901").hasMessageContaining("LISTING_LAYOUT");
        }
    }
}
