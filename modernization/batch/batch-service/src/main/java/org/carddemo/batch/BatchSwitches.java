package org.carddemo.batch;

import java.util.List;

import org.carddemo.platform.fidelity.FidelitySwitch;

/** The spec items batch-service switches between legacy and fixed behaviour. */
public final class BatchSwitches {

    /**
     * D-11, D-DATA-01. INTCALC picks its rates by group, so its legacy comparison
     * needs the accounts as the legacy file holds them.
     */
    public static final FidelitySwitch DEF_ACUP_002 = FidelitySwitch.of("DEF-ACUP-002",
            "dataLoadJob leaves the group ID (A000000000) at the ZIP position and group_id blank",
            "dataLoadJob moves the group ID from the ZIP position to group_id and leaves addr_zip empty");

    public static final List<FidelitySwitch> ALL = List.of(DEF_ACUP_002);

    private BatchSwitches() {
    }
}
