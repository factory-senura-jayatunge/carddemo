package org.carddemo.platform.testsupport;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitches;

/** The application's switches with {@link FidelityOverrides} on top, read on every call. */
final class OverridableFidelitySwitches implements FidelitySwitches {

    private final FidelitySwitches delegate;

    OverridableFidelitySwitches(FidelitySwitches delegate) {
        this.delegate = delegate;
    }

    @Override
    public FidelityMode mode(String itemId) {
        // The delegate rejects undeclared switches, overridden or not.
        FidelityMode configured = delegate.mode(itemId);
        return FidelityOverrides.get(itemId).orElse(configured);
    }

    @Override
    public Map<String, FidelityMode> effectiveModes() {
        Map<String, FidelityMode> modes = new LinkedHashMap<>(delegate.effectiveModes());
        modes.replaceAll((id, mode) -> FidelityOverrides.get(id).orElse(mode));
        return modes;
    }

    @Override
    public Collection<FidelitySwitch> declared() {
        return delegate.declared();
    }

    @Override
    public FidelitySwitches with(FidelitySwitch fidelitySwitch, FidelityMode mode) {
        return new OverridableFidelitySwitches(delegate.with(fidelitySwitch, mode));
    }
}
