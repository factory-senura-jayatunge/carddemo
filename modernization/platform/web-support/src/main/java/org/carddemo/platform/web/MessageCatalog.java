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
    public static final Message CSRF_REJECTED = target("CSRF_REJECTED", "Your request could not be verified. Reload the page and try again ...");
    public static final Message INVALID_REQUEST = target("INVALID_REQUEST", "The request is not valid ...");
    public static final Message NOT_FOUND = target("NOT_FOUND", "The requested resource does not exist ...");
    public static final Message METHOD_NOT_ALLOWED = target("METHOD_NOT_ALLOWED", "The request method is not supported ...");
    public static final Message INTERNAL_ERROR = target("INTERNAL_ERROR", "An unexpected error occurred. Please try again later ...");
    public static final Message INVALID_KEY = error("INVALID_KEY", "Invalid key pressed. Please see below...", "CSMSG01Y");
    public static final Message RECORD_CHANGED = error("RECORD_CHANGED", "Record changed by some one else. Please review", "D-17");
    public static final Message PRECONDITION_REQUIRED = target("PRECONDITION_REQUIRED", "If-Match header is required");
    public static final Message INVALID_IF_MATCH = target("INVALID_IF_MATCH", "If-Match header is not a version of this record");
    public static final Message INVALID_PAGE_REQUEST = target("INVALID_PAGE_REQUEST", "Use only one of start, after and before");
    public static final Message INVALID_PAGE_SIZE = target("INVALID_PAGE_SIZE", "Page size must be between 1 and {max}");
    public static final Message INVALID_CURSOR = target("INVALID_CURSOR", "The page position is not valid");
    public static final Message INVALID_UNMASK_FIELD = target("INVALID_UNMASK_FIELD", "Unmask one or more of: {fields}");

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
    /** No ID is left in the range; the unit's write-failure text, as no ID wraps (NUM-BILL-005). */
    public static final Message TRAN_ID_EXHAUSTED = error("TRAN_ID_EXHAUSTED", "Unable to Add Bill pay Transaction...",
            "NUM-BILL-005");
    public static final Message ACCOUNT_UPDATE_FAILED = error("ACCOUNT_UPDATE_FAILED", "Unable to Update Account...", "ERR-BILL-001");
    public static final Message PAYMENT_SUCCESSFUL = new Message("PAYMENT_SUCCESSFUL", "PAYMENT_SUCCESSFUL",
            "Payment successful.  Your Transaction ID is {tranId}.", Severity.SUCCESS, "BR-BILL-003");
    public static final Message IDEMPOTENCY_KEY_REQUIRED = target("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
    public static final Message IDEMPOTENCY_KEY_REUSED = target("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used for another payment");
    public static final Message BATCH_WINDOW = target("BATCH_WINDOW",
            "Bill payment is not available while the batch cycle runs. Try again later...");

    // Account and card units (COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC). Keys that would
    // clash with Bill Pay's are variants: their own key, Bill Pay's API code. Read-error and
    // not-found texts follow D-58 (no file names, no response codes).
    public static final Message UNMASK_AUDIT_FAILED = target("UNMASK_AUDIT_FAILED", "Unable to record the unmask request");
    public static final Message ACVW_ACCT_ID_REQUIRED = variant("ACVW_ACCT_ID_REQUIRED", "ACCT_ID_REQUIRED",
            "No input received", "VAL-ACVW-001");
    public static final Message ACVW_INVALID_ACCT_ID = variant("ACVW_INVALID_ACCT_ID", "INVALID_ACCT_ID",
            "Account Filter must  be a non-zero 11 digit number", "VAL-ACVW-002");
    public static final Message ACUP_ACCT_ID_REQUIRED = variant("ACUP_ACCT_ID_REQUIRED", "ACCT_ID_REQUIRED",
            "Account number not provided", "VAL-ACUP-001");
    public static final Message ACUP_INVALID_ACCT_ID = variant("ACUP_INVALID_ACCT_ID", "INVALID_ACCT_ID",
            "Account Number if supplied must be a 11 digit Non-Zero Number", "VAL-ACUP-002");
    public static final Message CARD_KEY_NO_INPUT = variant("CARD_KEY_NO_INPUT", "NO_INPUT",
            "No input received", "VAL-CRDV-005");
    public static final Message CARD_KEY_ACCT_ID_REQUIRED = variant("CARD_KEY_ACCT_ID_REQUIRED", "ACCT_ID_REQUIRED",
            "Account number not provided", "VAL-CRDV-001");
    public static final Message CARD_KEY_INVALID_ACCT_ID = variant("CARD_KEY_INVALID_ACCT_ID", "INVALID_ACCT_ID",
            "Account number must be a non zero 11 digit number", "VAL-CRDV-002");
    public static final Message CARD_NUM_REQUIRED = error("CARD_NUM_REQUIRED", "Card number not provided", "VAL-CRDV-003");
    public static final Message INVALID_CARD_NUM = error("INVALID_CARD_NUM", "Card number must be a non zero 16 digit number",
            "VAL-CRDV-004");
    public static final Message ACCOUNT_ID_NOT_FOUND = variant("ACCOUNT_ID_NOT_FOUND", "ACCOUNT_NOT_FOUND",
            "Account {acctId} not found.", "VAL-ACVW-004");
    public static final Message CUSTOMER_NOT_FOUND = error("CUSTOMER_NOT_FOUND", "Customer {custId} not found.", "VAL-ACVW-005");
    public static final Message NO_CARD_LINKED = new Message("NO_CARD_LINKED", "NO_CARD_LINKED",
            "No card is linked to this account.", Severity.INFO, "D-61");
    public static final Message ACCOUNT_DATA_READ_FAILED = variant("ACCOUNT_DATA_READ_FAILED", "ACCOUNT_LOOKUP_FAILED",
            "Error reading account data", "ERR-ACVW-001");
    public static final Message CUSTOMER_LOOKUP_FAILED = error("CUSTOMER_LOOKUP_FAILED", "Error reading customer data",
            "ERR-ACVW-001");
    public static final Message XREF_DATA_READ_FAILED = variant("XREF_DATA_READ_FAILED", "XREF_LOOKUP_FAILED",
            "Error reading card cross-reference data", "ERR-ACVW-001");
    public static final Message ACCOUNT_VIEW_PROMPT = info("ACCOUNT_VIEW_PROMPT", "Enter or update id of account to display",
            "FLOW-ACVW-011");
    public static final Message ACCOUNT_UPDATE_PROMPT = info("ACCOUNT_UPDATE_PROMPT", "Enter or update id of account to update",
            "BR-ACUP-007");
    public static final Message ACCOUNT_UPDATE_DETAILS = info("ACCOUNT_UPDATE_DETAILS", "Update account details presented above.",
            "BR-ACUP-007");
    public static final Message FIELD_NOT_EDITABLE = target("FIELD_NOT_EDITABLE", "{label} cannot be changed on this screen.");
    /** For a name the screen does not show: no part of an unknown name is returned. */
    public static final Message FIELD_NOT_EDITABLE_UNKNOWN = new Message("FIELD_NOT_EDITABLE_UNKNOWN", "FIELD_NOT_EDITABLE",
            "This field cannot be changed on this screen.", Severity.ERROR, "target");
    public static final Message NO_CHANGE = error("NO_CHANGE", "No change detected with respect to values fetched.", "BR-ACUP-102");
    public static final Message CHANGES_VALIDATED = info("CHANGES_VALIDATED", "Changes validated.Press F5 to save", "BR-ACUP-107");
    public static final Message CHANGES_COMMITTED = new Message("CHANGES_COMMITTED", "CHANGES_COMMITTED",
            "Changes committed to database", Severity.SUCCESS, "BR-ACUP-007");
    public static final Message CHANGES_UNSUCCESSFUL = info("CHANGES_UNSUCCESSFUL", "Changes unsuccessful. Please try again",
            "ERR-ACUP-004");
    public static final Message ACCOUNT_LOCK_FAILED = error("ACCOUNT_LOCK_FAILED", "Could not lock account record for update",
            "ERR-ACUP-004");
    public static final Message CUSTOMER_LOCK_FAILED = error("CUSTOMER_LOCK_FAILED", "Could not lock customer record for update",
            "ERR-ACUP-004");
    public static final Message ACCOUNT_SAVE_FAILED = variant("ACCOUNT_SAVE_FAILED", "ACCOUNT_UPDATE_FAILED",
            "Update of record failed", "ERR-ACUP-004");
    public static final Message ACCOUNT_UPDATE_BATCH_WINDOW = new Message("ACCOUNT_UPDATE_BATCH_WINDOW", "BATCH_WINDOW",
            "Account update is not available during the batch cycle. Try again later...", Severity.ERROR, "target");
    public static final Message INVALID_ACCT_FILTER = error("INVALID_ACCT_FILTER",
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "VAL-CRDL-001");
    public static final Message INVALID_CARD_FILTER = error("INVALID_CARD_FILTER",
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", "VAL-CRDL-002");
    public static final Message CARD_LIST_INVALID_CURSOR = new Message("CARD_LIST_INVALID_CURSOR", "INVALID_CURSOR",
            "Paging position is no longer valid. Start again from page 1", Severity.ERROR, "target");
    public static final Message SELECT_ONE_RECORD = error("SELECT_ONE_RECORD", "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE",
            "VAL-CRDL-004");
    public static final Message INVALID_ACTION_CODE = error("INVALID_ACTION_CODE", "INVALID ACTION CODE", "VAL-CRDL-005");
    public static final Message NO_RECORDS_FOUND = info("NO_RECORDS_FOUND", "NO RECORDS FOUND FOR THIS SEARCH CONDITION.",
            "BR-CRDL-004");
    public static final Message NO_MORE_RECORDS = info("NO_MORE_RECORDS", "NO MORE RECORDS TO SHOW", "BR-CRDL-005");
    public static final Message NO_MORE_PAGES = info("NO_MORE_PAGES", "NO MORE PAGES TO DISPLAY", "BR-CRDL-005");
    public static final Message NO_PREVIOUS_PAGES = info("NO_PREVIOUS_PAGES", "NO PREVIOUS PAGES TO DISPLAY", "BR-CRDL-006");
    public static final Message CARD_LIST_PROMPT = info("CARD_LIST_PROMPT", "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD",
            "BR-CRDL-008");
    public static final Message CARD_LIST_FAILED = error("CARD_LIST_FAILED", "Error reading card data", "ERR-CRDL-001");
    public static final Message CARD_NOT_FOUND = error("CARD_NOT_FOUND", "Did not find cards for this search condition",
            "VAL-CRDV-007");
    public static final Message CARD_LOOKUP_FAILED = error("CARD_LOOKUP_FAILED", "Error reading card data", "ERR-CRDV-001");
    public static final Message CARD_KEYS_PROMPT = info("CARD_KEYS_PROMPT", "Please enter Account and Card Number", "BR-CRDV-005");
    public static final Message CARD_DISPLAYED = info("CARD_DISPLAYED", "   Displaying requested details", "BR-CRDV-005");
    public static final Message CARD_UPDATE_SHOWN = info("CARD_UPDATE_SHOWN", "Details of selected card shown above",
            "FLOW-CRDU-014");
    public static final Message CARD_UPDATE_DETAILS = info("CARD_UPDATE_DETAILS", "Update card details presented above.",
            "FLOW-CRDU-014");
    public static final Message CARD_LOCK_FAILED = error("CARD_LOCK_FAILED", "Could not lock record for update", "ERR-CRDU-001");
    public static final Message CARD_UPDATE_FAILED = error("CARD_UPDATE_FAILED", "Update of record failed", "ERR-CRDU-001");
    public static final Message CARD_UPDATE_BATCH_WINDOW = new Message("CARD_UPDATE_BATCH_WINDOW", "BATCH_WINDOW",
            "Card update is not available while the batch cycle runs. Try again later...", Severity.ERROR, "target");
    // Pending authorizations (COPAUS0C). The not-found and read-error texts are the
    // architect's D-58 rulings: the legacy texts without the response codes and IMS status.
    public static final Message ACCOUNT_ID_REQUIRED = error("ACCOUNT_ID_REQUIRED", "Please enter Acct Id...", "VAL-AUSM-001");
    public static final Message ACCOUNT_ID_NOT_NUMERIC = error("ACCOUNT_ID_NOT_NUMERIC", "Acct Id must be Numeric ...",
            "VAL-AUSM-002");
    public static final Message AUSM_ACCOUNT_NOT_FOUND = variant("AUSM_ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_FOUND",
            "Account:{acctId} not found in ACCT file.", "ERR-AUSM-002");
    public static final Message AUTH_XREF_NOT_FOUND = error("AUTH_XREF_NOT_FOUND", "Account:{acctId} not found in XREF file.",
            "ERR-AUSM-001");
    public static final Message AUTH_CUSTOMER_NOT_FOUND = error("AUTH_CUSTOMER_NOT_FOUND",
            "Customer:{custId} not found in CUST file.", "ERR-AUSM-003");
    public static final Message AUTH_XREF_READ_FAILED = error("AUTH_XREF_READ_FAILED", " System error while reading XREF file.",
            "ERR-AUSM-001");
    public static final Message AUTH_ACCOUNT_READ_FAILED = error("AUTH_ACCOUNT_READ_FAILED",
            " System error while reading ACCT file.", "ERR-AUSM-002");
    public static final Message AUTH_CUSTOMER_READ_FAILED = error("AUTH_CUSTOMER_READ_FAILED",
            " System error while reading CUST file.", "ERR-AUSM-003");
    public static final Message AUTH_SUMMARY_READ_FAILED = error("AUTH_SUMMARY_READ_FAILED",
            " System error while reading AUTH Summary", "ERR-AUSM-004");
    public static final Message AUTH_DETAIL_READ_FAILED = error("AUTH_DETAIL_READ_FAILED",
            " System error while reading AUTH Details", "ERR-AUSM-005");
    /** The list has one page size, the screen's five rows (BR-AUSM-003). */
    public static final Message AUSM_INVALID_PAGE_SIZE = new Message("AUSM_INVALID_PAGE_SIZE", "INVALID_PAGE_SIZE",
            "Page size must be 5", Severity.ERROR, "target");
    public static final Message AUSM_INVALID_PAGE_REQUEST = new Message("AUSM_INVALID_PAGE_REQUEST", "INVALID_PAGE_REQUEST",
            "Use only one of after and before", Severity.ERROR, "target");
    public static final Message AUTH_LIST_TOP = error("AUTH_LIST_TOP", "You are already at the top of the page...",
            "VAL-AUSM-005");
    public static final Message AUTH_LIST_BOTTOM = error("AUTH_LIST_BOTTOM", "You are already at the bottom of the page...",
            "VAL-AUSM-005");
    // Authorization detail and fraud marking (COPAUS1C, COPAUS2C). The read-error texts are D-58's,
    // without the IMS status; the detail read keeps the case of COPAUS1C, not COPAUS0C's.
    public static final Message INVALID_AUTH_ID = target("INVALID_AUTH_ID", "Authorization ID must be a positive number");
    public static final Message AUTHORIZATION_NOT_FOUND = target("AUTHORIZATION_NOT_FOUND", "Authorization not found...");
    public static final Message AUDT_DETAIL_READ_FAILED = variant("AUDT_DETAIL_READ_FAILED", "AUTH_DETAIL_READ_FAILED",
            " System error while reading Auth Details", "ERR-AUDT-002");
    public static final Message AUTH_NEXT_READ_FAILED = error("AUTH_NEXT_READ_FAILED",
            " System error while reading next Auth", "ERR-AUDT-003");
    public static final Message INVALID_FRAUD_ACTION = target("INVALID_FRAUD_ACTION", "Fraud action must be F or R");
    public static final Message FRAUD_UPDATE_FAILED = error("FRAUD_UPDATE_FAILED",
            " System error while FRAUD Tagging, ROLLBACK", "ERR-AUDT-006");
    public static final Message AUTH_MARKED_FRAUD = info("AUTH_MARKED_FRAUD", "AUTH MARKED FRAUD...", "BR-AUDT-008");
    public static final Message AUTH_FRAUD_REMOVED = info("AUTH_FRAUD_REMOVED", "AUTH FRAUD REMOVED...", "BR-AUDT-008");
    public static final Message AUTH_LAST = error("AUTH_LAST", "Already at the last Authorization...", "VAL-AUDT-003");

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
    public static final Message USER_ID_MISMATCH = target("USER_ID_MISMATCH", "User ID does not match the signed-on user ...");
    public static final Message TOO_MANY_ATTEMPTS = target("TOO_MANY_ATTEMPTS", "Too many sign-on attempts. Try again later ...");
    public static final Message SESSION_EXPIRED = target("SESSION_EXPIRED", "Your session has ended. Please sign on again ...");
    public static final Message SIGNED_OFF = new Message("SIGNED_OFF", "SIGNED_OFF",
            "Thank you for using CardDemo application...", Severity.INFO, "CSMSG01Y");

    // Main menu (COMEN01C) and admin menu stub.
    public static final Message OPTION_COMING_SOON = new Message("OPTION_COMING_SOON", "OPTION_COMING_SOON",
            "This option {name} is coming soon ...", Severity.SUCCESS, "DEF-MENU-004");
    public static final Message OPTION_NOT_INSTALLED = error("OPTION_NOT_INSTALLED", "This option {name} is not installed...", "BR-MENU-004");
    public static final Message INVALID_OPTION = error("INVALID_OPTION", "Please enter a valid option number...", "VAL-MENU-001");

    // Transaction list and view (COTRN00C, COTRN01C). The list's cursor and page-size problems are
    // CARD_LIST_INVALID_CURSOR and INVALID_PAGE_SIZE; the view's read error is Bill Pay's
    // TRAN_LOOKUP_FAILED, whose COBOL text COTRN01C shares character for character.
    public static final Message INVALID_SEARCH_TRAN_ID = error("INVALID_SEARCH_TRAN_ID", "Tran ID must be Numeric ...",
            "VAL-TRNL-001");
    public static final Message TRAN_LIST_FAILED = error("TRAN_LIST_FAILED", "Unable to lookup transaction...", "ERR-TRNL-001");
    /** STARTBR NOTFND: no row at all from the key, kept although it says "top" (DEF-TRNL-004). */
    public static final Message TRAN_LIST_NO_ROWS = info("TRAN_LIST_NO_ROWS", "You are at the top of the page...",
            "DEF-TRNL-004");
    public static final Message TRAN_LIST_BOTTOM = info("TRAN_LIST_BOTTOM", "You have reached the bottom of the page...",
            "VAL-TRNL-005");
    public static final Message TRAN_LIST_TOP = info("TRAN_LIST_TOP", "You have reached the top of the page...",
            "VAL-TRNL-005");
    /** Decided in the client from hasPrevious and hasNext, with no call (VAL-TRNL-004). */
    public static final Message TRAN_LIST_ALREADY_TOP = info("TRAN_LIST_ALREADY_TOP",
            "You are already at the top of the page...", "VAL-TRNL-004");
    public static final Message TRAN_LIST_ALREADY_BOTTOM = info("TRAN_LIST_ALREADY_BOTTOM",
            "You are already at the bottom of the page...", "VAL-TRNL-004");
    /** A selection code other than S, with the 3270 shortcuts on; checked in the client (VAL-TRNL-002). */
    public static final Message TRAN_INVALID_SELECTION = error("TRAN_INVALID_SELECTION", "Invalid selection. Valid value is S",
            "VAL-TRNL-002");
    public static final Message TRAN_ID_REQUIRED = error("TRAN_ID_REQUIRED", "Tran ID can NOT be empty...", "VAL-TRNV-001");
    public static final Message TRAN_NOT_FOUND = error("TRAN_NOT_FOUND", "Transaction ID NOT found...", "VAL-TRNV-002");

    // Transaction add (COTRN02C). Texts that clash with an earlier unit's are variants: their own
    // key, the contract's API code. The account-not-found, confirm, lookup, ID-exists and
    // Idempotency-Key-required texts are Bill Pay's, character for character.
    public static final Message KEY_REQUIRED = error("KEY_REQUIRED", "Account or Card Number must be entered...", "VAL-TRNA-001");
    public static final Message TRNA_INVALID_ACCT_ID = variant("TRNA_INVALID_ACCT_ID", "INVALID_ACCT_ID",
            "Account ID must be Numeric...", "VAL-TRNA-002");
    public static final Message TRNA_INVALID_CARD_NUM = variant("TRNA_INVALID_CARD_NUM", "INVALID_CARD_NUM",
            "Card Number must be Numeric...", "VAL-TRNA-003");
    public static final Message TRNA_XREF_LOOKUP_FAILED = variant("TRNA_XREF_LOOKUP_FAILED", "XREF_LOOKUP_FAILED",
            "Unable to lookup Acct in XREF AIX file...", "VAL-TRNA-004");
    public static final Message CARD_CHOICE_REQUIRED = error("CARD_CHOICE_REQUIRED",
            "Account has more than one card. Enter the Card Number...", "DEF-TRNA-004");
    public static final Message CARD_ACCOUNT_MISMATCH = error("CARD_ACCOUNT_MISMATCH",
            "Card Number does not belong to this Account...", "DEF-TRNA-005");
    public static final Message TRNA_CARD_NOT_FOUND = variant("TRNA_CARD_NOT_FOUND", "CARD_NOT_FOUND",
            "Card Number NOT found...", "VAL-TRNA-005");
    public static final Message TRNA_CARD_LOOKUP_FAILED = variant("TRNA_CARD_LOOKUP_FAILED", "CARD_LOOKUP_FAILED",
            "Unable to lookup Card # in XREF file...", "VAL-TRNA-005");
    public static final Message FIELD_REQUIRED = error("FIELD_REQUIRED", "{label} can NOT be empty...", "VAL-TRNA-006");
    /** The FormRules {@code latin1} and {@code maxLength} texts, for the fields the service checks itself. */
    public static final Message TRNA_UNSUPPORTED_CHARACTER = variant("TRNA_UNSUPPORTED_CHARACTER", "UNSUPPORTED_CHARACTER",
            "{label} contains a character that is not supported.", "VAL-TRNA-006");
    public static final Message TRNA_TOO_LONG = variant("TRNA_TOO_LONG", "TOO_LONG",
            "{label} must not be longer than {maxLength} characters.", "NUM-TRNA-005");
    public static final Message TYPE_NOT_NUMERIC = error("TYPE_NOT_NUMERIC", "Type CD must be Numeric...", "VAL-TRNA-007");
    public static final Message CATEGORY_NOT_NUMERIC = error("CATEGORY_NOT_NUMERIC", "Category CD must be Numeric...",
            "VAL-TRNA-007");
    public static final Message TYPE_NOT_FOUND = error("TYPE_NOT_FOUND", "Type CD is not a valid transaction type...",
            "DEF-TRNA-002");
    public static final Message CATEGORY_NOT_FOUND = error("CATEGORY_NOT_FOUND", "Category CD is not valid for this Type CD...",
            "DEF-TRNA-002");
    public static final Message REFERENCE_LOOKUP_FAILED = error("REFERENCE_LOOKUP_FAILED",
            "Unable to lookup Transaction Type or Category...", "DEF-TRNA-002");
    public static final Message INVALID_AMOUNT = error("INVALID_AMOUNT", "Amount should be in format -99999999.99", "VAL-TRNA-008");
    public static final Message INVALID_ORIG_DATE_FORMAT = error("INVALID_ORIG_DATE_FORMAT",
            "Orig Date should be in format YYYY-MM-DD", "VAL-TRNA-009");
    public static final Message INVALID_ORIG_DATE = error("INVALID_ORIG_DATE", "Orig Date - Not a valid date...", "VAL-TRNA-010");
    public static final Message MERCHANT_ID_NOT_NUMERIC = error("MERCHANT_ID_NOT_NUMERIC", "Merchant ID must be Numeric...",
            "VAL-TRNA-011");
    /** Red, as the legacy prompt (BR-TRNA-002). */
    public static final Message CONFIRM_ADD_TRANSACTION = error("CONFIRM_ADD_TRANSACTION", "Confirm to add this transaction...",
            "BR-TRNA-002");
    public static final Message TRANSACTION_ADDED = new Message("TRANSACTION_ADDED", "TRANSACTION_ADDED",
            "Transaction added successfully.  Your Tran ID is {tranId}.", Severity.SUCCESS, "BR-TRNA-005");
    public static final Message TRANSACTION_POSTING_HINT = info("TRANSACTION_POSTING_HINT",
            "The transaction will be posted in the next batch cycle.", "D-27");
    public static final Message TRAN_IDEMPOTENCY_KEY_REUSED = target("TRAN_IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was used for another transaction");
    public static final Message TRAN_ADD_BATCH_WINDOW = new Message("TRAN_ADD_BATCH_WINDOW", "BATCH_WINDOW",
            "Transaction add is not available during the batch cycle. Try again later...", Severity.ERROR, "target");
    public static final Message NO_TRANSACTION = error("NO_TRANSACTION", "Transaction ID NOT found...", "DEF-TRNA-015");
    public static final Message ADD_FAILED = error("ADD_FAILED", "Unable to Add Transaction...", "ERR-TRNA-001");

    // Transaction reports (CORPT00C).
    public static final Message REPORT_TYPE_REQUIRED = error("REPORT_TYPE_REQUIRED", "Select a report type to print report...",
            "VAL-RPTS-001");
    public static final Message START_MONTH_REQUIRED = error("START_MONTH_REQUIRED", "Start Date - Month can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message START_DAY_REQUIRED = error("START_DAY_REQUIRED", "Start Date - Day can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message START_YEAR_REQUIRED = error("START_YEAR_REQUIRED", "Start Date - Year can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message END_MONTH_REQUIRED = error("END_MONTH_REQUIRED", "End Date - Month can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message END_DAY_REQUIRED = error("END_DAY_REQUIRED", "End Date - Day can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message END_YEAR_REQUIRED = error("END_YEAR_REQUIRED", "End Date - Year can NOT be empty...",
            "VAL-RPTS-002");
    public static final Message START_MONTH_INVALID = error("START_MONTH_INVALID", "Start Date - Not a valid Month...",
            "VAL-RPTS-003");
    public static final Message START_DAY_INVALID = error("START_DAY_INVALID", "Start Date - Not a valid Day...",
            "VAL-RPTS-003");
    public static final Message START_YEAR_INVALID = error("START_YEAR_INVALID", "Start Date - Not a valid Year...",
            "VAL-RPTS-003");
    public static final Message END_MONTH_INVALID = error("END_MONTH_INVALID", "End Date - Not a valid Month...",
            "VAL-RPTS-003");
    public static final Message END_DAY_INVALID = error("END_DAY_INVALID", "End Date - Not a valid Day...", "VAL-RPTS-003");
    public static final Message END_YEAR_INVALID = error("END_YEAR_INVALID", "End Date - Not a valid Year...", "VAL-RPTS-003");
    public static final Message START_DATE_INVALID = error("START_DATE_INVALID", "Start Date - Not a valid date...",
            "VAL-RPTS-004");
    public static final Message END_DATE_INVALID = error("END_DATE_INVALID", "End Date - Not a valid date...", "VAL-RPTS-004");
    public static final Message DATE_RANGE_INVALID = error("DATE_RANGE_INVALID", "Start Date can NOT be after End Date...",
            "DEF-RPTS-001");
    public static final Message CONFIRM_REQUIRED = error("CONFIRM_REQUIRED", "Please confirm to print the {name} report...",
            "BR-RPTS-005");
    /** {@code value} is the first character of the confirm field only, which is one character, as in legacy. */
    public static final Message CONFIRM_INVALID = error("CONFIRM_INVALID", "\"{value}\" is not a valid value to confirm...",
            "VAL-RPTS-005");
    public static final Message REPORT_SUBMITTED = new Message("REPORT_SUBMITTED", "REPORT_SUBMITTED",
            "{name} report submitted for printing ...", Severity.SUCCESS, "BR-RPTS-008");
    public static final Message REPORT_TRIGGER_FAILED = error("REPORT_TRIGGER_FAILED",
            "Unable to start the report. Try again later...", "ERR-RPTS-001");
    public static final Message INVALID_RUN_ID = target("INVALID_RUN_ID", "Report run ID must be a positive number...");
    public static final Message REPORT_RUN_NOT_FOUND = target("REPORT_RUN_NOT_FOUND", "Report run not found...");
    public static final Message REPORT_NOT_READY = error("REPORT_NOT_READY", "The report is not ready yet...", "D-48");
    public static final Message REPORT_FILE_MISSING = error("REPORT_FILE_MISSING", "The report file is not available...", "D-48");
    /** Shown by the page from the run's {@code stalled} flag; no endpoint returns it. */
    public static final Message REPORT_RUN_STALLED = target("REPORT_RUN_STALLED",
            "The report run has stopped responding. Contact operations...");

    // User administration (COUSR00C to COUSR03C). Page-only texts (the prompts, the list's
    // already-at-top/bottom and invalid-selection texts) are shown by the web app, never returned.
    public static final Message USER_FIRST_NAME_EMPTY = variant("USER_FIRST_NAME_EMPTY", "FIRST_NAME_EMPTY",
            "First Name can NOT be empty...", "VAL-USRA-001");
    public static final Message USER_LAST_NAME_EMPTY = variant("USER_LAST_NAME_EMPTY", "LAST_NAME_EMPTY",
            "Last Name can NOT be empty...", "VAL-USRA-001");
    public static final Message USER_ID_EMPTY = error("USER_ID_EMPTY", "User ID can NOT be empty...", "VAL-USRA-001");
    public static final Message USER_PASSWORD_EMPTY = variant("USER_PASSWORD_EMPTY", "PASSWORD_EMPTY",
            "Password can NOT be empty...", "VAL-USRA-001");
    public static final Message USER_TYPE_EMPTY = error("USER_TYPE_EMPTY", "User Type can NOT be empty...", "VAL-USRA-001");
    public static final Message USER_ADDED = new Message("USER_ADDED", "USER_ADDED",
            "User {userId} has been added ...", Severity.SUCCESS, "BR-USRA-002");
    public static final Message USER_ID_EXISTS = error("USER_ID_EXISTS", "User ID already exist...", "VAL-USRA-002");
    public static final Message USER_ADD_FAILED = error("USER_ADD_FAILED", "Unable to Add User...", "ERR-USRA-001");
    public static final Message USER_ID_NOT_FOUND = error("USER_ID_NOT_FOUND", "User ID NOT found...", "VAL-USRU-003");
    public static final Message USER_LOOKUP_FAILED = error("USER_LOOKUP_FAILED", "Unable to lookup User...", "ERR-USRU-001");
    public static final Message USER_UPDATE_PROMPT = info("USER_UPDATE_PROMPT", "Press PF5 key to save your updates ...",
            "BR-USRU-001");
    public static final Message USER_NO_CHANGE = new Message("USER_NO_CHANGE", "NO_CHANGE",
            "Please modify to update ...", Severity.ERROR, "BR-USRU-004");
    public static final Message USER_UPDATED = new Message("USER_UPDATED", "USER_UPDATED",
            "User {userId} has been updated ...", Severity.SUCCESS, "BR-USRU-003");
    public static final Message USER_UPDATE_FAILED = error("USER_UPDATE_FAILED", "Unable to Update User...", "ERR-USRU-002");
    public static final Message USER_DELETE_FAILED = error("USER_DELETE_FAILED", "Unable to Update User...", "ERR-USRD-002");
    public static final Message USER_DELETE_PROMPT = info("USER_DELETE_PROMPT", "Press PF5 key to delete this user ...",
            "BR-USRD-001");
    public static final Message USER_DELETED = new Message("USER_DELETED", "USER_DELETED",
            "User {userId} has been deleted ...", Severity.SUCCESS, "BR-USRD-004");
    public static final Message USER_LIST_BOTTOM = info("USER_LIST_BOTTOM", "You have reached the bottom of the page...",
            "ERR-USRL-002");
    public static final Message USER_LIST_TOP = info("USER_LIST_TOP", "You have reached the top of the page...", "ERR-USRL-002");
    /** An empty list or search: ERR-USRL-001's decided fix shows the top-of-page message (D-75). */
    public static final Message USER_LIST_NONE = info("USER_LIST_NONE", "You are at the top of the page...", "ERR-USRL-001");
    public static final Message USER_LIST_ALREADY_TOP = info("USER_LIST_ALREADY_TOP",
            "You are already at the top of the page...", "VAL-USRL-003");
    public static final Message USER_LIST_ALREADY_BOTTOM = info("USER_LIST_ALREADY_BOTTOM",
            "You are already at the bottom of the page...", "VAL-USRL-004");
    public static final Message USER_INVALID_SELECTION = error("USER_INVALID_SELECTION",
            "Invalid selection. Valid values are U and D", "VAL-USRL-001");
    public static final Message USER_ID_INVALID = target("USER_ID_INVALID", "User ID must not contain spaces...");
    public static final Message USER_TYPE_INVALID = target("USER_TYPE_INVALID", "User Type must be A or U...");
    public static final Message USER_CANNOT_DELETE_SELF = variant("USER_CANNOT_DELETE_SELF", "CANNOT_DELETE_SELF",
            "You cannot delete your own User ID...", "target");
    public static final Message USER_LAST_ADMIN = variant("USER_LAST_ADMIN", "LAST_ADMIN",
            "At least one administrator must remain...", "target");
    public static final Message USER_ADMIN_BATCH_WINDOW = new Message("USER_ADMIN_BATCH_WINDOW", "BATCH_WINDOW",
            "User changes are not available during the batch cycle. Try again later...", Severity.ERROR, "target");
    /** The FormRules {@code latin1} and {@code maxLength} texts, for the fields the service checks itself. */
    public static final Message USER_UNSUPPORTED_CHARACTER = variant("USER_UNSUPPORTED_CHARACTER", "UNSUPPORTED_CHARACTER",
            "{label} contains a character that is not supported.", "target");
    public static final Message USER_TOO_LONG = variant("USER_TOO_LONG", "TOO_LONG",
            "{label} must not be longer than {maxLength} characters.", "target");

    private static final Map<String, Message> BY_KEY = index(List.of(
            UNAUTHENTICATED, ACCESS_DENIED, CSRF_REJECTED, INVALID_REQUEST, NOT_FOUND, METHOD_NOT_ALLOWED, INTERNAL_ERROR,
            INVALID_KEY, RECORD_CHANGED, PRECONDITION_REQUIRED, INVALID_IF_MATCH, INVALID_PAGE_REQUEST, INVALID_PAGE_SIZE,
            INVALID_CURSOR, INVALID_UNMASK_FIELD,
            ACCT_ID_REQUIRED, ACCOUNT_NOT_FOUND, ACCOUNT_LOOKUP_FAILED, CONFIRM_PAYMENT, NOTHING_TO_PAY, INVALID_CONFIRM,
            XREF_LOOKUP_FAILED, TRAN_LOOKUP_FAILED, TRAN_ID_EXISTS, TRAN_ADD_FAILED, TRAN_ID_EXHAUSTED,
            ACCOUNT_UPDATE_FAILED, PAYMENT_SUCCESSFUL, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_REUSED, BATCH_WINDOW,
            USER_ID_REQUIRED, PASSWORD_REQUIRED, INVALID_INPUT_USER_ID, INVALID_INPUT_PASSWORD, UNABLE_TO_VERIFY,
            USER_NOT_FOUND, WRONG_PASSWORD, SIGN_ON_FAILED, USER_TYPE_NOT_ALLOWED, PASSWORD_CHANGE_REQUIRED,
            NEW_PASSWORD_REQUIRED, NEW_PASSWORD_LENGTH_LEGACY, NEW_PASSWORD_LENGTH_FIXED, NEW_PASSWORD_SAME,
            NEW_PASSWORD_IS_USER_ID, NEW_PASSWORD_MISMATCH, USER_ID_MISMATCH, TOO_MANY_ATTEMPTS, SESSION_EXPIRED, SIGNED_OFF,
            OPTION_COMING_SOON, OPTION_NOT_INSTALLED, INVALID_OPTION,
            UNMASK_AUDIT_FAILED, ACVW_ACCT_ID_REQUIRED, ACVW_INVALID_ACCT_ID, ACUP_ACCT_ID_REQUIRED, ACUP_INVALID_ACCT_ID,
            CARD_KEY_NO_INPUT, CARD_KEY_ACCT_ID_REQUIRED, CARD_KEY_INVALID_ACCT_ID, CARD_NUM_REQUIRED, INVALID_CARD_NUM,
            ACCOUNT_ID_NOT_FOUND, CUSTOMER_NOT_FOUND, NO_CARD_LINKED, ACCOUNT_DATA_READ_FAILED, CUSTOMER_LOOKUP_FAILED,
            XREF_DATA_READ_FAILED, ACCOUNT_VIEW_PROMPT, ACCOUNT_UPDATE_PROMPT, ACCOUNT_UPDATE_DETAILS, FIELD_NOT_EDITABLE,
            FIELD_NOT_EDITABLE_UNKNOWN, NO_CHANGE, CHANGES_VALIDATED, CHANGES_COMMITTED, CHANGES_UNSUCCESSFUL, ACCOUNT_LOCK_FAILED, CUSTOMER_LOCK_FAILED,
            ACCOUNT_SAVE_FAILED, ACCOUNT_UPDATE_BATCH_WINDOW, INVALID_ACCT_FILTER, INVALID_CARD_FILTER,
            CARD_LIST_INVALID_CURSOR, SELECT_ONE_RECORD, INVALID_ACTION_CODE, NO_RECORDS_FOUND, NO_MORE_RECORDS,
            NO_MORE_PAGES, NO_PREVIOUS_PAGES, CARD_LIST_PROMPT, CARD_LIST_FAILED, CARD_NOT_FOUND, CARD_LOOKUP_FAILED,
            CARD_KEYS_PROMPT, CARD_DISPLAYED, CARD_UPDATE_SHOWN, CARD_UPDATE_DETAILS, CARD_LOCK_FAILED, CARD_UPDATE_FAILED,
            CARD_UPDATE_BATCH_WINDOW,
            ACCOUNT_ID_REQUIRED, ACCOUNT_ID_NOT_NUMERIC, AUSM_ACCOUNT_NOT_FOUND, AUTH_XREF_NOT_FOUND,
            AUTH_CUSTOMER_NOT_FOUND, AUTH_XREF_READ_FAILED, AUTH_ACCOUNT_READ_FAILED, AUTH_CUSTOMER_READ_FAILED,
            AUTH_SUMMARY_READ_FAILED, AUTH_DETAIL_READ_FAILED, AUSM_INVALID_PAGE_SIZE, AUSM_INVALID_PAGE_REQUEST,
            AUTH_LIST_TOP, AUTH_LIST_BOTTOM,
            INVALID_AUTH_ID, AUTHORIZATION_NOT_FOUND, AUDT_DETAIL_READ_FAILED, AUTH_NEXT_READ_FAILED,
            INVALID_FRAUD_ACTION, FRAUD_UPDATE_FAILED, AUTH_MARKED_FRAUD, AUTH_FRAUD_REMOVED, AUTH_LAST,
            INVALID_SEARCH_TRAN_ID, TRAN_LIST_FAILED, TRAN_LIST_NO_ROWS, TRAN_LIST_BOTTOM, TRAN_LIST_TOP,
            TRAN_LIST_ALREADY_TOP, TRAN_LIST_ALREADY_BOTTOM, TRAN_INVALID_SELECTION, TRAN_ID_REQUIRED, TRAN_NOT_FOUND,
            KEY_REQUIRED, TRNA_INVALID_ACCT_ID, TRNA_INVALID_CARD_NUM, TRNA_XREF_LOOKUP_FAILED, CARD_CHOICE_REQUIRED,
            CARD_ACCOUNT_MISMATCH, TRNA_CARD_NOT_FOUND, TRNA_CARD_LOOKUP_FAILED, FIELD_REQUIRED, TRNA_UNSUPPORTED_CHARACTER,
            TRNA_TOO_LONG, TYPE_NOT_NUMERIC, CATEGORY_NOT_NUMERIC, TYPE_NOT_FOUND, CATEGORY_NOT_FOUND,
            REFERENCE_LOOKUP_FAILED, INVALID_AMOUNT, INVALID_ORIG_DATE_FORMAT, INVALID_ORIG_DATE, MERCHANT_ID_NOT_NUMERIC,
            CONFIRM_ADD_TRANSACTION, TRANSACTION_ADDED, TRANSACTION_POSTING_HINT, TRAN_IDEMPOTENCY_KEY_REUSED,
            TRAN_ADD_BATCH_WINDOW, NO_TRANSACTION, ADD_FAILED,
            REPORT_TYPE_REQUIRED, START_MONTH_REQUIRED, START_DAY_REQUIRED, START_YEAR_REQUIRED, END_MONTH_REQUIRED,
            END_DAY_REQUIRED, END_YEAR_REQUIRED, START_MONTH_INVALID, START_DAY_INVALID, START_YEAR_INVALID,
            END_MONTH_INVALID, END_DAY_INVALID, END_YEAR_INVALID, START_DATE_INVALID, END_DATE_INVALID,
            DATE_RANGE_INVALID, CONFIRM_REQUIRED, CONFIRM_INVALID, REPORT_SUBMITTED, REPORT_TRIGGER_FAILED,
            INVALID_RUN_ID, REPORT_RUN_NOT_FOUND, REPORT_NOT_READY, REPORT_FILE_MISSING, REPORT_RUN_STALLED,
            USER_FIRST_NAME_EMPTY, USER_LAST_NAME_EMPTY, USER_ID_EMPTY, USER_PASSWORD_EMPTY, USER_TYPE_EMPTY, USER_ADDED,
            USER_ID_EXISTS, USER_ADD_FAILED, USER_ID_NOT_FOUND, USER_LOOKUP_FAILED, USER_UPDATE_PROMPT, USER_NO_CHANGE,
            USER_UPDATED, USER_UPDATE_FAILED, USER_DELETE_FAILED, USER_DELETE_PROMPT, USER_DELETED, USER_LIST_BOTTOM,
            USER_LIST_TOP, USER_LIST_NONE, USER_LIST_ALREADY_TOP, USER_LIST_ALREADY_BOTTOM, USER_INVALID_SELECTION,
            USER_ID_INVALID, USER_TYPE_INVALID, USER_CANNOT_DELETE_SELF, USER_LAST_ADMIN, USER_ADMIN_BATCH_WINDOW,
            USER_UNSUPPORTED_CHARACTER, USER_TOO_LONG));

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

    private static Message variant(String key, String code, String text, String source) {
        return new Message(key, code, text, Severity.ERROR, source);
    }

    private static Message info(String code, String text, String source) {
        return new Message(code, code, text, Severity.INFO, source);
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
