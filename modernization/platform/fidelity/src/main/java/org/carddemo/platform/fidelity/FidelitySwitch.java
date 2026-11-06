package org.carddemo.platform.fidelity;

import java.util.Objects;

/**
 * One defect-decide spec item that has a legacy and a fixed behaviour. Declare each switch once as
 * a constant named after its item ID and branch on that constant through {@link FidelitySwitches}.
 *
 * @param itemId the spec item ID, for example {@code DEF-BILL-010}
 * @param legacy what the legacy mode does (the COBOL behaviour)
 * @param fixed what the fixed mode does
 */
public record FidelitySwitch(String itemId, String legacy, String fixed) {

    public FidelitySwitch {
        SpecItemIds.require(itemId);
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(fixed, "fixed");
    }

    public static FidelitySwitch of(String itemId, String legacy, String fixed) {
        return new FidelitySwitch(itemId, legacy, fixed);
    }

    @Override
    public String toString() {
        return itemId;
    }
}
