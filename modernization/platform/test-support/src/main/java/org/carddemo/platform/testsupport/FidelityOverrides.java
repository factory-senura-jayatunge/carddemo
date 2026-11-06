package org.carddemo.platform.testsupport;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItemIds;

/**
 * Switch modes a test sets for the running application, over the configured ones. Tests in one
 * JVM run one at a time (surefire's default), so one global set suffices.
 */
public final class FidelityOverrides {

    private static volatile Map<String, FidelityMode> overrides = Map.of();
    private static volatile FidelityMode current;

    private FidelityOverrides() {
    }

    public static void set(Collection<String> itemIds, FidelityMode mode) {
        Map<String, FidelityMode> map = new LinkedHashMap<>();
        itemIds.forEach(id -> map.put(SpecItemIds.normalizeKey(id), mode));
        overrides = Map.copyOf(map);
        current = mode;
    }

    public static void clear() {
        overrides = Map.of();
        current = null;
    }

    public static Optional<FidelityMode> get(String itemId) {
        return Optional.ofNullable(overrides.get(SpecItemIds.normalizeKey(itemId)));
    }

    static Map<String, FidelityMode> all() {
        return overrides;
    }

    public static FidelityMode current() {
        FidelityMode mode = current;
        if (mode == null) {
            throw new IllegalStateException("no fidelity mode is set; annotate the test class with @BothModes");
        }
        return mode;
    }
}
