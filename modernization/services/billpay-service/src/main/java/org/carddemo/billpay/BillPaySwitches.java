package org.carddemo.billpay;

import org.carddemo.platform.fidelity.FidelitySwitch;

/** The fidelity switches Bill Pay reads itself; BR-TRNA-003 belongs to ledger-core. */
public final class BillPaySwitches {

    /** D-28: how the payment is stored and posted. */
    public static final FidelitySwitch DEF_BILL_010 = FidelitySwitch.of("DEF-BILL-010",
            "amount stored positive; only curr_bal changes",
            "amount stored negative and posted like POSTTRAN: balance, curr_cyc_debit and category 02/0002");

    private BillPaySwitches() {
    }
}
