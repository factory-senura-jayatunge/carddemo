package org.carddemo.platform.fidelity;

import java.util.Locale;
import java.util.regex.Pattern;

/** Syntax of the item IDs the natural-language spec defines (docs/modernization/spec/format.md). */
public final class SpecItemIds {

    private static final Pattern UNIT_ITEM =
            Pattern.compile("^(BR|VAL|FLOW|SE|ERR|NUM|DEF|Q)-[A-Z][A-Z0-9]{2,5}-\\d{3}$");
    private static final Pattern INVARIANT = Pattern.compile("^P?INV-\\d{3}$");

    private SpecItemIds() {
    }

    public static boolean isValid(String id) {
        return id != null && (UNIT_ITEM.matcher(id).matches() || INVARIANT.matcher(id).matches());
    }

    public static String require(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("'" + id + "' is not a spec item ID"
                    + " (KIND-UNIT-NNN with KIND in BR VAL FLOW SE ERR NUM DEF Q, or INV-NNN, PINV-NNN)");
        }
        return id;
    }

    /**
     * Maps a configuration key back to the item ID. Spring's relaxed binding and environment
     * variables can deliver {@code DEF-BILL-010} as {@code def-bill-010}, {@code def.bill.010}
     * or {@code DEF_BILL_010}.
     */
    public static String normalizeKey(String key) {
        String k = key.trim();
        if (k.startsWith("[") && k.endsWith("]")) {
            k = k.substring(1, k.length() - 1);
        }
        return k.toUpperCase(Locale.ROOT).replace('.', '-').replace('_', '-');
    }
}
