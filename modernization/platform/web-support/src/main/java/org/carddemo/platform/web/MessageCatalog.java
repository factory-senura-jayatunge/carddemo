package org.carddemo.platform.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Every text the APIs and screens of the converted units show, keyed by catalogue key. The texts
 * are part of the equivalence contract: legacy texts are copied character for character from the
 * COBOL, trailing dots and spaces included. {@link #toJson()} is the source of the web client's
 * generated copy.
 */
public final class MessageCatalog {

    // Shared by every service.
    public static final Message UNAUTHENTICATED = target("UNAUTHENTICATED", "Please sign on to use this function ...");
    public static final Message ACCESS_DENIED = target("ACCESS_DENIED", "You are not authorized to use this function ...");
    public static final Message CSRF_REJECTED = target("CSRF_REJECTED", "The request could not be verified. Please try again ...");
    public static final Message INVALID_REQUEST = target("INVALID_REQUEST", "The request is not valid ...");
    public static final Message NOT_FOUND = target("NOT_FOUND", "The requested resource does not exist ...");
    public static final Message METHOD_NOT_ALLOWED = target("METHOD_NOT_ALLOWED", "The request method is not supported ...");
    public static final Message INTERNAL_ERROR = target("INTERNAL_ERROR", "Unexpected error. Please try again later ...");
    public static final Message INVALID_KEY = error("INVALID_KEY", "Invalid key pressed. Please see below...", "CSMSG01Y");

    // Bill Pay (COBIL00C).
    public static final Message ACCT_ID_REQUIRED = error("ACCT_ID_REQUIRED", "Acct ID can NOT be empty...", "VAL-BILL-001");
    public static final Message ACCOUNT_NOT_FOUND = error("ACCOUNT_NOT_FOUND", "Account ID NOT found...", "VAL-BILL-003");
    public static final Message ACCOUNT_LOOKUP_FAILED = error("ACCOUNT_LOOKUP_FAILED", "Unable to lookup Account...", "ERR-BILL-001");
    public static final Message CONFIRM_PAYMENT = error("CONFIRM_PAYMENT", "Confirm to make a bill payment...", "VAL-BILL-005");
    public static final Message NOTHING_TO_PAY = error("NOTHING_TO_PAY", "You have nothing to pay...", "VAL-BILL-004");
    public static final Message INVALID_CONFIRM = error("INVALID_CONFIRM", "Invalid value. Valid values are (Y/N)...", "VAL-BILL-002");
    public static final Message XREF_LOOKUP_FAILED = error("XREF_LOOKUP_FAILED", "Unable to lookup XREF AIX file...", "ERR-BILL-001");
    public static final Message TRAN_LOOKUP_FAILED = error("TRAN_LOOKUP_FAILED", "Unable to lookup Transaction...", "ERR-BILL-001");
    public static final Message TRAN_ID_EXISTS = error("TRAN_ID_EXISTS", "Tran ID already exist...", "ERR-BILL-001");
    public static final Message TRAN_ADD_FAILED = error("TRAN_ADD_FAILED", "Unable to Add Bill pay Transaction...", "ERR-BILL-001");
    public static final Message ACCOUNT_UPDATE_FAILED = error("ACCOUNT_UPDATE_FAILED", "Unable to Update Account...", "ERR-BILL-001");
    public static final Message PAYMENT_SUCCESSFUL = new Message("PAYMENT_SUCCESSFUL", "PAYMENT_SUCCESSFUL",
            "Payment successful.  Your Transaction ID is {tranId}.", Severity.SUCCESS, "BR-BILL-003");
    public static final Message IDEMPOTENCY_KEY_REQUIRED = target("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
    public static final Message IDEMPOTENCY_KEY_REUSED = target("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used for another payment");
    public static final Message BATCH_WINDOW = target("BATCH_WINDOW",
            "Bill payment is not available while the batch cycle runs. Try again later...");

    // Sign-on and password change (COSGN00C, D-04, D-05, D-SEC-07).
    public static final Message USER_ID_REQUIRED = error("USER_ID_REQUIRED", "Please enter User ID ...", "VAL-SGN-001");
    public static final Message PASSWORD_REQUIRED = error("PASSWORD_REQUIRED", "Please enter Password ...", "VAL-SGN-002");
    public static final Message INVALID_INPUT_USER_ID = new Message("INVALID_INPUT_USER_ID", "INVALID_INPUT",
            "Please enter User ID ...", Severity.ERROR, "VAL-SGN-001");
    public static final Message INVALID_INPUT_PASSWORD = new Message("INVALID_INPUT_PASSWORD", "INVALID_INPUT",
            "Please enter Password ...", Severity.ERROR, "VAL-SGN-002");
    public static final Message UNABLE_TO_VERIFY = error("UNABLE_TO_VERIFY", "Unable to verify the User ...", "ERR-SGN-001");
    public static final Message USER_NOT_FOUND = error("USER_NOT_FOUND", "User not found. Try again ...", "VAL-SGN-003");
    public static final Message WRONG_PASSWORD = error("WRONG_PASSWORD", "Wrong Password. Try again ...", "VAL-SGN-004");
    public static final Message SIGN_ON_FAILED = error("SIGN_ON_FAILED", "Wrong User ID or Password. Try again ...", "DEF-SGN-003");
    public static final Message USER_TYPE_NOT_ALLOWED = error("USER_TYPE_NOT_ALLOWED",
            "User not authorized. Contact your administrator ...", "DEF-SGN-005");
    public static final Message PASSWORD_CHANGE_REQUIRED = new Message("PASSWORD_CHANGE_REQUIRED", "PASSWORD_CHANGE_REQUIRED",
            "Password change required. Enter a new Password ...", Severity.INFO, "DEF-SGN-011");
    public static final Message NEW_PASSWORD_REQUIRED = error("NEW_PASSWORD_REQUIRED", "Please enter new Password ...", "D-SEC-07");
    public static final Message NEW_PASSWORD_LENGTH_LEGACY = new Message("NEW_PASSWORD_LENGTH_LEGACY", "NEW_PASSWORD_LENGTH",
            "New Password must be 1 to 8 characters ...", Severity.ERROR, "D-SEC-07");
    public static final Message NEW_PASSWORD_LENGTH_FIXED = new Message("NEW_PASSWORD_LENGTH_FIXED", "NEW_PASSWORD_LENGTH",
            "New Password must be 8 to 64 characters ...", Severity.ERROR, "D-SEC-07");
    public static final Message NEW_PASSWORD_SAME = error("NEW_PASSWORD_SAME", "New Password must differ from the current one ...", "D-SEC-07");
    public static final Message NEW_PASSWORD_IS_USER_ID = error("NEW_PASSWORD_IS_USER_ID", "New Password must not be the User ID ...", "D-SEC-07");
    public static final Message NEW_PASSWORD_MISMATCH = error("NEW_PASSWORD_MISMATCH", "New Password and confirmation do not match ...", "D-SEC-07");
    public static final Message SESSION_EXPIRED = target("SESSION_EXPIRED", "Your session has ended. Please sign on again ...");
    public static final Message SIGNED_OFF = new Message("SIGNED_OFF", "SIGNED_OFF",
            "Thank you for using CardDemo application...", Severity.INFO, "CSMSG01Y");

    // Main menu (COMEN01C) and admin menu stub.
    public static final Message OPTION_COMING_SOON = new Message("OPTION_COMING_SOON", "OPTION_COMING_SOON",
            "This option {name} is coming soon ...", Severity.SUCCESS, "DEF-MENU-004");
    public static final Message OPTION_NOT_INSTALLED = error("OPTION_NOT_INSTALLED", "This option {name} is not installed...", "BR-MENU-004");
    public static final Message INVALID_OPTION = error("INVALID_OPTION", "Please enter a valid option number...", "VAL-MENU-001");

    private static final Map<String, Message> BY_KEY = index(List.of(
            UNAUTHENTICATED, ACCESS_DENIED, CSRF_REJECTED, INVALID_REQUEST, NOT_FOUND, METHOD_NOT_ALLOWED, INTERNAL_ERROR,
            INVALID_KEY,
            ACCT_ID_REQUIRED, ACCOUNT_NOT_FOUND, ACCOUNT_LOOKUP_FAILED, CONFIRM_PAYMENT, NOTHING_TO_PAY, INVALID_CONFIRM,
            XREF_LOOKUP_FAILED, TRAN_LOOKUP_FAILED, TRAN_ID_EXISTS, TRAN_ADD_FAILED, ACCOUNT_UPDATE_FAILED,
            PAYMENT_SUCCESSFUL, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_REUSED, BATCH_WINDOW,
            USER_ID_REQUIRED, PASSWORD_REQUIRED, INVALID_INPUT_USER_ID, INVALID_INPUT_PASSWORD, UNABLE_TO_VERIFY,
            USER_NOT_FOUND, WRONG_PASSWORD, SIGN_ON_FAILED, USER_TYPE_NOT_ALLOWED, PASSWORD_CHANGE_REQUIRED,
            NEW_PASSWORD_REQUIRED, NEW_PASSWORD_LENGTH_LEGACY, NEW_PASSWORD_LENGTH_FIXED, NEW_PASSWORD_SAME,
            NEW_PASSWORD_IS_USER_ID, NEW_PASSWORD_MISMATCH, SESSION_EXPIRED, SIGNED_OFF,
            OPTION_COMING_SOON, OPTION_NOT_INSTALLED, INVALID_OPTION));

    private MessageCatalog() {
    }

    public static Map<String, Message> all() {
        return BY_KEY;
    }

    public static Message get(String key) {
        Message message = BY_KEY.get(key);
        if (message == null) {
            throw new NoSuchElementException("no catalogue message " + key);
        }
        return message;
    }

    /**
     * {@code {"<key>": {"code", "text", "severity", "params", "source"}, ...}} in catalogue order,
     * two-space indented with a trailing newline, so the committed copy diffs cleanly.
     */
    public static String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Message m : BY_KEY.values()) {
            sb.append("  ").append(Json.quote(m.key())).append(": {")
                    .append("\"code\": ").append(Json.quote(m.code()))
                    .append(", \"text\": ").append(Json.quote(m.text()))
                    .append(", \"severity\": ").append(Json.quote(m.severity().key()))
                    .append(", \"params\": [");
            List<String> params = m.params();
            for (int p = 0; p < params.size(); p++) {
                sb.append(p == 0 ? "" : ", ").append(Json.quote(params.get(p)));
            }
            sb.append("], \"source\": ").append(Json.quote(m.source())).append('}')
                    .append(++i < BY_KEY.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }

    private static Message error(String code, String text, String source) {
        return new Message(code, code, text, Severity.ERROR, source);
    }

    private static Message target(String code, String text) {
        return new Message(code, code, text, Severity.ERROR, "target");
    }

    private static Map<String, Message> index(List<Message> messages) {
        Map<String, Message> map = new LinkedHashMap<>();
        for (Message m : messages) {
            if (map.put(m.key(), m) != null) {
                throw new IllegalStateException("duplicate catalogue key " + m.key());
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
