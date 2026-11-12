package org.carddemo.identity.user;

import org.carddemo.platform.legacyformat.LegacyCase;
import org.carddemo.platform.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The canonical user ID (BR-SGN-001, D-53, data-model.md "Upper-casing"): the 3270 pad and
 * low-values at the end and spaces at the start removed, at most 8 characters of U+0000 to U+00FF,
 * no space inside, and only {@code a} to {@code z} upper-cased.
 */
public final class UserIds {

    public static final int MAX_LENGTH = 8;
    static final String LABEL = "User ID";

    private UserIds() {
    }

    /** The ID of an add, a fetch, a save or a delete; blank gives {@code USER_ID_EMPTY}. */
    public static String canonical(String raw, String field) {
        String s = strip(raw);
        if (s.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.USER_ID_EMPTY, field);
        }
        checkText(s, field);
        if (s.indexOf(' ') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.USER_ID_INVALID, field);
        }
        return LegacyCase.upper(s);
    }

    /** The search prefix of the list in the same form; blank is no search (empty string). */
    public static String searchPrefix(String raw, String field) {
        String s = strip(raw);
        if (s.isEmpty()) {
            return "";
        }
        checkText(s, field);
        return LegacyCase.upper(s);
    }

    static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\0')) {
            end--;
        }
        int start = 0;
        while (start < end && raw.charAt(start) == ' ') {
            start++;
        }
        return raw.substring(start, end);
    }

    private static void checkText(String s, String field) {
        if (!UserText.latin1(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.UNSUPPORTED_CHARACTER, field, LABEL);
        }
        if (s.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.TOO_LONG, field, LABEL,
                    Integer.toString(MAX_LENGTH));
        }
    }
}
