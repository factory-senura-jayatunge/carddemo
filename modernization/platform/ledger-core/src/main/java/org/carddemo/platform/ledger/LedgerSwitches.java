package org.carddemo.platform.ledger;

import org.carddemo.platform.fidelity.FidelitySwitch;

/** The fidelity switches ledger-core reads. Every writer of master transactions shares them. */
public final class LedgerSwitches {

    /** D-59: how a new master transaction ID is chosen. */
    public static final FidelitySwitch BR_TRNA_003 = FidelitySwitch.of("BR-TRNA-003",
            "highest master transaction ID + 1, under the application lock carddemo.tran_id",
            "NEXT VALUE FOR core.tran_id_seq");

    private LedgerSwitches() {
    }
}
