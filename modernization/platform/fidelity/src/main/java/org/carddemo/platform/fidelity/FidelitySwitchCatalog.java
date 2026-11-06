package org.carddemo.platform.fidelity;

import java.util.Collection;
import java.util.List;

/** The switches one service or library declares. Expose it as a bean; the auto-configuration collects every catalog. */
@FunctionalInterface
public interface FidelitySwitchCatalog {

    Collection<FidelitySwitch> switches();

    static FidelitySwitchCatalog of(FidelitySwitch... switches) {
        List<FidelitySwitch> list = List.of(switches);
        return () -> list;
    }
}
