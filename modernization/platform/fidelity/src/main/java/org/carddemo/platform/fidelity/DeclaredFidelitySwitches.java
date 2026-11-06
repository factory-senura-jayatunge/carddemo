package org.carddemo.platform.fidelity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class DeclaredFidelitySwitches implements FidelitySwitches {

    private final Map<String, FidelitySwitch> declared;
    private final Map<String, FidelityMode> modes;

    private DeclaredFidelitySwitches(Map<String, FidelitySwitch> declared, Map<String, FidelityMode> modes) {
        this.declared = declared;
        this.modes = modes;
    }

    static DeclaredFidelitySwitches of(Collection<FidelitySwitch> declared, Map<String, FidelityMode> configured) {
        Map<String, FidelitySwitch> byId = new TreeMap<>();
        for (FidelitySwitch s : declared) {
            FidelitySwitch previous = byId.putIfAbsent(s.itemId(), s);
            if (previous != null && !previous.equals(s)) {
                throw new IllegalStateException("fidelity switch " + s.itemId() + " is declared twice with different texts");
            }
        }
        Map<String, FidelityMode> modes = new TreeMap<>();
        List<String> unknown = new ArrayList<>();
        configured.forEach((key, mode) -> {
            String id = SpecItemIds.normalizeKey(key);
            if (!byId.containsKey(id)) {
                unknown.add(key);
            } else {
                modes.put(id, Objects.requireNonNull(mode, () -> "mode of " + key));
            }
        });
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("carddemo.fidelity names switches that no FidelitySwitchCatalog declares: "
                    + unknown + "; declared: " + byId.keySet());
        }
        return new DeclaredFidelitySwitches(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(modes));
    }

    @Override
    public FidelityMode mode(String itemId) {
        String id = SpecItemIds.normalizeKey(itemId);
        if (!declared.containsKey(id)) {
            throw new IllegalStateException("fidelity switch " + itemId
                    + " is read but not declared in any FidelitySwitchCatalog");
        }
        return modes.getOrDefault(id, FidelityMode.LEGACY);
    }

    @Override
    public FidelitySwitches with(FidelitySwitch fidelitySwitch, FidelityMode mode) {
        mode(fidelitySwitch);
        Map<String, FidelityMode> copy = new TreeMap<>(modes);
        copy.put(fidelitySwitch.itemId(), Objects.requireNonNull(mode, "mode"));
        return new DeclaredFidelitySwitches(declared, Collections.unmodifiableMap(copy));
    }

    @Override
    public Map<String, FidelityMode> effectiveModes() {
        Map<String, FidelityMode> result = new LinkedHashMap<>();
        declared.keySet().forEach(id -> result.put(id, modes.getOrDefault(id, FidelityMode.LEGACY)));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Collection<FidelitySwitch> declared() {
        return declared.values();
    }

    @Override
    public String toString() {
        return "FidelitySwitches" + effectiveModes();
    }
}
