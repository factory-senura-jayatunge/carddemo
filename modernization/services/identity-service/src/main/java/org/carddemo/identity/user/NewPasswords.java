package org.carddemo.identity.user;

import org.carddemo.identity.signon.LegacyText;
import org.carddemo.platform.legacyformat.LegacyCase;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;

/**
 * The new-password rule of D-SEC-07, shared by the password change and user administration.
 * Both take the typed value without its trailing pad ({@link LegacyText#rtrim}).
 */
public final class NewPasswords {

    static final int FIXED_MIN = 8;
    static final int FIXED_MAX = 64;
    static final int LEGACY_MAX = 8;

    private NewPasswords() {
    }

    /** The length text the password fails, or null: 1 to 8 legacy; 8 to 64 printable code points, fixed. */
    public static Message lengthProblem(String trimmed, boolean caseSensitive) {
        int length = trimmed.codePointCount(0, trimmed.length());
        if (caseSensitive) {
            boolean printable = trimmed.codePoints().noneMatch(Character::isISOControl);
            boolean ok = length >= FIXED_MIN && length <= FIXED_MAX && printable;
            return ok ? null : MessageCatalog.NEW_PASSWORD_LENGTH_FIXED;
        }
        return length > LEGACY_MAX ? MessageCatalog.NEW_PASSWORD_LENGTH_LEGACY : null;
    }

    /** The user ID typed in another case of {@code a} to {@code z} only, as user IDs are compared (D-53). */
    public static boolean isUserId(String trimmed, String userId) {
        return LegacyCase.upper(trimmed).equals(userId);
    }
}
