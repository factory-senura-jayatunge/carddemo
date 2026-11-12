package org.carddemo.transaction;

import java.util.Optional;
import java.util.regex.Pattern;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpStatus;

/**
 * The typed transaction IDs: the list's Search Tran ID (VAL-TRNL-001) and the view's Tran ID
 * (BR-TRNV-001). In both only trailing spaces are removed, which the 3270 field adds; a leading or
 * inner space is part of the value. Digit strings shorter than 16 are padded on the left with
 * {@code 0} (DEF-TRNL-006, DEF-TRNV-003).
 */
final class TransactionKeys {

    /** {@code TRAN-ID PIC X(16)}. */
    static final int LENGTH = 16;

    static final String SEARCH_FIELD = "searchTranId";
    static final String VIEW_FIELD = "tranId";

    private static final Pattern KEY = Pattern.compile("[0-9]{16}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,16}");
    private static final Pattern PRINTABLE = Pattern.compile("[\\x20-\\x7E]*");

    private TransactionKeys() {
    }

    /** A cursor ({@code after}, {@code before}): exactly 16 digits; a key no row has is still valid (DEF-TRNL-013). */
    static boolean isCursorKey(String key) {
        return KEY.matcher(key).matches();
    }

    /**
     * The list's start key, 16 characters, or null when the field is empty. Anything but 1 to 16
     * digits is {@code 400 INVALID_SEARCH_TRAN_ID} (VAL-TRNL-001).
     */
    static String searchStart(String typed) {
        String value = TransactionFormats.trimTrailing(typed);
        if (value.isEmpty()) {
            return null;
        }
        if (!DIGITS.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_SEARCH_TRAN_ID, SEARCH_FIELD);
        }
        return pad(value);
    }

    /** True when the search field holds anything but spaces. */
    static boolean isSearchGiven(String typed) {
        return typed != null && !TransactionFormats.trimTrailing(typed).isEmpty();
    }

    /**
     * The view's key: blank is {@code 400 TRAN_ID_REQUIRED} (VAL-TRNV-001). Empty means no row can
     * match, so the caller answers not found without a read: a value longer than the
     * {@code CHAR(16)} column, or one with a character no 3270 field can hold, which the cast to
     * {@code CHAR} could turn into another value.
     */
    static Optional<String> viewKey(String typed) {
        String value = TransactionFormats.trimTrailing(typed);
        if (value.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.TRAN_ID_REQUIRED, VIEW_FIELD);
        }
        if (value.length() > LENGTH || !PRINTABLE.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(DIGITS.matcher(value).matches() ? pad(value) : value);
    }

    private static String pad(String digits) {
        return "0".repeat(LENGTH - digits.length()) + digits;
    }
}
