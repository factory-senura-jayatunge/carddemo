package org.carddemo.platform.fidelity;

import java.util.Locale;

/** The behaviour chosen for one defect-decide spec item. */
public enum FidelityMode {
    /** The COBOL behaviour, defect included. The default until the ledger records a decision. */
    LEGACY,
    /** The corrected behaviour the spec item describes. */
    FIXED;

    public static FidelityMode parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("fidelity mode is null; expected legacy or fixed");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "legacy" -> LEGACY;
            case "fixed" -> FIXED;
            default -> throw new IllegalArgumentException(
                    "fidelity mode '" + value + "' is not legacy or fixed");
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
