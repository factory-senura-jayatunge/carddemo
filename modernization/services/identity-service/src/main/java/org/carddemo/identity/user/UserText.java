package org.carddemo.identity.user;

/** Text rules of the user-admin fields (data-model.md, Text repertoire and width). */
public final class UserText {

    private UserText() {
    }

    /**
     * True when every character is in U+0000 to U+00FF, the 256 characters a legacy {@code X(n)}
     * field holds. A surrogate pair is above U+00FF, so an emoji fails as one character.
     */
    public static boolean latin1(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > '\u00FF') {
                return false;
            }
        }
        return true;
    }

    /** Not entered: nothing, or only spaces, low-values and other whitespace (VAL-USRA-001). */
    public static boolean blank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\0' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /** The value as stored: trailing U+0020 is the field pad; everything else is kept. */
    public static String withoutTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
