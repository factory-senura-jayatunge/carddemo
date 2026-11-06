package org.carddemo.identity.fidelity;

import java.util.List;

import org.carddemo.platform.fidelity.FidelitySwitch;

/** The spec items identity-service switches between legacy and fixed behaviour. */
public final class IdentitySwitches {

    /** D-04: password case, and the forced change of users that must change their password. */
    public static final FidelitySwitch DEF_SGN_011 = FidelitySwitch.of("DEF-SGN-011",
            "the typed password is upper-cased before the check; 1 to 8 characters; must_change_password ignored",
            "case-sensitive password of 8 to 64 characters; must_change_password forces a change before sign-on");

    /** D-05: attempt limit and lockout. */
    public static final FidelitySwitch DEF_SGN_002 = FidelitySwitch.of("DEF-SGN-002",
            "no attempt counting",
            "5 failures lock the user for 15 minutes; failed attempts are rate limited per client address");

    /** D-05: messages that reveal whether a user ID exists. */
    public static final FidelitySwitch DEF_SGN_003 = FidelitySwitch.of("DEF-SGN-003",
            "'User not found' and 'Wrong Password' messages",
            "one neutral message for unknown user, wrong password and locked user");

    /** D-02: user types other than A and U. */
    public static final FidelitySwitch DEF_SGN_005 = FidelitySwitch.of("DEF-SGN-005",
            "any type other than A signs on as USER",
            "types other than A and U are refused");

    /**
     * D-30. Not read here: identity-service reports its mode to the web app in
     * GET /api/features, so it is declared once, in this service.
     */
    public static final FidelitySwitch DEF_BILL_014 = FidelitySwitch.of("DEF-BILL-014",
            "Account ID stays editable on the confirmation step",
            "Account ID read-only once the balance is shown, until Clear or a finished payment");

    public static final List<FidelitySwitch> ALL =
            List.of(DEF_SGN_011, DEF_SGN_002, DEF_SGN_003, DEF_SGN_005, DEF_BILL_014);

    private IdentitySwitches() {
    }
}
