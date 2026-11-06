package org.carddemo.identity.signon;

/** Texts the sign-on and password screens show. The legacy ones are exact (COSGN00C section 8.3). */
public final class Messages {

    public static final String USER_ID_REQUIRED = "Please enter User ID ...";
    public static final String PASSWORD_REQUIRED = "Please enter Password ...";
    public static final String USER_NOT_FOUND = "User not found. Try again ...";
    public static final String WRONG_PASSWORD = "Wrong Password. Try again ...";
    public static final String UNABLE_TO_VERIFY = "Unable to verify the User ...";
    public static final String SIGN_OFF = "Thank you for using CardDemo application...";

    /** Fixed mode of DEF-SGN-003: unknown user, wrong password and locked user read the same. */
    public static final String NEUTRAL = "Wrong User ID or Password. Try again ...";

    public static final String USER_TYPE_NOT_ALLOWED = "User not authorized. Contact your administrator ...";
    public static final String PASSWORD_CHANGE_REQUIRED = "Password change required. Enter a new Password ...";
    public static final String NEW_PASSWORD_REQUIRED = "Please enter new Password ...";
    public static final String NEW_PASSWORD_LENGTH_LEGACY = "New Password must be 1 to 8 characters ...";
    public static final String NEW_PASSWORD_LENGTH_FIXED = "New Password must be 8 to 64 characters ...";
    public static final String NEW_PASSWORD_SAME = "New Password must differ from the current one ...";
    public static final String NEW_PASSWORD_IS_USER_ID = "New Password must not be the User ID ...";
    public static final String USER_ID_MISMATCH = "User ID does not match the signed-on user ...";

    public static final String SESSION_EXPIRED = "Your session has expired. Please sign on again ...";
    public static final String ACCESS_DENIED = "You are not authorized to use this function ...";
    public static final String REQUEST_NOT_VERIFIED = "Your request could not be verified. Please sign on again ...";

    private Messages() {
    }
}
