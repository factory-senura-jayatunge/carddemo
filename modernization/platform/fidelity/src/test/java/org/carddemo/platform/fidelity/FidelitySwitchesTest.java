package org.carddemo.platform.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FidelitySwitchesTest {

    static final FidelitySwitch DEF_BILL_010 = FidelitySwitch.of("DEF-BILL-010", "positive amount, balance only", "posted");
    static final FidelitySwitch NUM_BILL_002 = FidelitySwitch.of("NUM-BILL-002", "truncate", "reject");

    @Test
    void unsetSwitchIsLegacy() {
        FidelitySwitches switches = FidelitySwitches.allLegacy(DEF_BILL_010, NUM_BILL_002);
        assertThat(switches.mode(DEF_BILL_010)).isEqualTo(FidelityMode.LEGACY);
        assertThat(switches.isLegacy(NUM_BILL_002)).isTrue();
        assertThat(switches.isFixed(NUM_BILL_002)).isFalse();
    }

    @Test
    void configuredKeysAreMatchedInEveryFormRelaxedBindingProduces() {
        for (String key : List.of("DEF-BILL-010", "def-bill-010", "def.bill.010", "DEF_BILL_010", "[DEF-BILL-010]")) {
            FidelitySwitches switches = FidelitySwitches.of(List.of(DEF_BILL_010), Map.of(key, FidelityMode.FIXED));
            assertThat(switches.isFixed(DEF_BILL_010)).as(key).isTrue();
        }
    }

    @Test
    void configuredKeyWithoutDeclarationFailsSoATypoCannotKeepLegacySilently() {
        assertThatThrownBy(() -> FidelitySwitches.of(List.of(DEF_BILL_010), Map.of("DEF-BILL-011", FidelityMode.FIXED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEF-BILL-011");
    }

    @Test
    void readingAnUndeclaredSwitchFails() {
        FidelitySwitches switches = FidelitySwitches.allLegacy(DEF_BILL_010);
        assertThatThrownBy(() -> switches.mode(NUM_BILL_002)).hasMessageContaining("NUM-BILL-002");
    }

    @Test
    void conflictingDeclarationsOfOneItemFail() {
        FidelitySwitch other = FidelitySwitch.of("DEF-BILL-010", "something else", "posted");
        assertThatThrownBy(() -> FidelitySwitches.allLegacy(DEF_BILL_010, other)).hasMessageContaining("declared twice");
        assertThat(FidelitySwitches.allLegacy(DEF_BILL_010, DEF_BILL_010).declared()).hasSize(1);
    }

    @Test
    void withSetsOneSwitchAndLeavesTheOriginalUnchanged() {
        FidelitySwitches legacy = FidelitySwitches.allLegacy(DEF_BILL_010, NUM_BILL_002);
        FidelitySwitches fixed = legacy.with(DEF_BILL_010, FidelityMode.FIXED);
        assertThat(fixed.isFixed(DEF_BILL_010)).isTrue();
        assertThat(fixed.isLegacy(NUM_BILL_002)).isTrue();
        assertThat(legacy.isLegacy(DEF_BILL_010)).isTrue();
        assertThat(fixed.effectiveModes()).containsExactly(
                Map.entry("DEF-BILL-010", FidelityMode.FIXED), Map.entry("NUM-BILL-002", FidelityMode.LEGACY));
    }

    @Test
    void modeValuesAreCaseInsensitiveAndAnythingElseIsRejected() {
        assertThat(FidelityMode.parse(" Fixed ")).isEqualTo(FidelityMode.FIXED);
        assertThat(FidelityMode.parse("LEGACY")).isEqualTo(FidelityMode.LEGACY);
        assertThatThrownBy(() -> FidelityMode.parse("on")).hasMessageContaining("legacy or fixed");
    }

    @Test
    void switchIdsMustBeSpecItemIds() {
        assertThatThrownBy(() -> FidelitySwitch.of("BILL-010", "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SpecItemIds.isValid("Q-BILL-002")).isTrue();
        assertThat(SpecItemIds.isValid("INV-006")).isTrue();
        assertThat(SpecItemIds.isValid("PINV-001")).isTrue();
        assertThat(SpecItemIds.isValid("DEF-JCTLM-002")).isTrue();
        assertThat(SpecItemIds.isValid("DEF-BILL-10")).isFalse();
        assertThat(SpecItemIds.isValid("XYZ-BILL-001")).isFalse();
        assertThat(SpecItemIds.isValid("DEF-B-001")).isFalse();
    }
}
