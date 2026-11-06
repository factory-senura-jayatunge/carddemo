package org.carddemo.platform.fidelity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The one place code reads a fidelity switch ({@code carddemo.fidelity.<ITEM-ID>: legacy|fixed}).
 * Every switch must be declared in a {@link FidelitySwitchCatalog}. Its mode comes from an explicit
 * property, else from the decision ledger ({@code ledger/fidelity.toml}), else it is
 * {@link FidelityMode#LEGACY}. A configured key that no catalog declares is rejected, so a typo
 * cannot silently leave the legacy behaviour in place.
 */
public interface FidelitySwitches {

    /** The mode of a declared switch; fails for an item no catalog declares. */
    FidelityMode mode(String itemId);

    default boolean fixed(String itemId) {
        return mode(itemId) == FidelityMode.FIXED;
    }

    default FidelityMode mode(FidelitySwitch fidelitySwitch) {
        return mode(fidelitySwitch.itemId());
    }

    default boolean isFixed(FidelitySwitch fidelitySwitch) {
        return mode(fidelitySwitch) == FidelityMode.FIXED;
    }

    default boolean isLegacy(FidelitySwitch fidelitySwitch) {
        return mode(fidelitySwitch) == FidelityMode.LEGACY;
    }

    /** Every declared switch with its effective mode, ordered by item ID. */
    Map<String, FidelityMode> effectiveModes();

    Collection<FidelitySwitch> declared();

    /** A copy with one switch set, for tests that run an item in both modes. */
    FidelitySwitches with(FidelitySwitch fidelitySwitch, FidelityMode mode);

    /**
     * @param declared every switch the running code may read
     * @param configured item ID (any key form {@link SpecItemIds#normalizeKey} accepts) to mode
     */
    static FidelitySwitches of(Collection<FidelitySwitch> declared, Map<String, FidelityMode> configured) {
        return DeclaredFidelitySwitches.of(declared, configured);
    }

    static FidelitySwitches allLegacy(Collection<FidelitySwitch> declared) {
        return of(declared, Map.of());
    }

    static FidelitySwitches allLegacy(FidelitySwitch... declared) {
        return allLegacy(List.of(declared));
    }
}
