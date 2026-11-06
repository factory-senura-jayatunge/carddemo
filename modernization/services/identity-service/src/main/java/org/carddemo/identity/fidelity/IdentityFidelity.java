package org.carddemo.identity.fidelity;

import static org.carddemo.identity.fidelity.IdentitySwitches.DEF_BILL_014;
import static org.carddemo.identity.fidelity.IdentitySwitches.DEF_SGN_002;
import static org.carddemo.identity.fidelity.IdentitySwitches.DEF_SGN_003;
import static org.carddemo.identity.fidelity.IdentitySwitches.DEF_SGN_005;
import static org.carddemo.identity.fidelity.IdentitySwitches.DEF_SGN_011;

import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.stereotype.Component;

/** The identity switches, named by what they decide. */
@Component
public class IdentityFidelity {

    private final FidelitySwitches switches;

    public IdentityFidelity(FidelitySwitches switches) {
        this.switches = switches;
    }

    /** DEF-SGN-011 fixed: passwords are compared and stored as typed; forced change applies. */
    public boolean caseSensitivePasswords() {
        return switches.isFixed(DEF_SGN_011);
    }

    /** DEF-SGN-002 fixed. */
    public boolean lockout() {
        return switches.isFixed(DEF_SGN_002);
    }

    /** DEF-SGN-003 fixed. */
    public boolean neutralMessages() {
        return switches.isFixed(DEF_SGN_003);
    }

    /** DEF-SGN-005 fixed. */
    public boolean onlyKnownUserTypes() {
        return switches.isFixed(DEF_SGN_005);
    }

    public FidelityMode billPayAccountLock() {
        return switches.mode(DEF_BILL_014);
    }
}
