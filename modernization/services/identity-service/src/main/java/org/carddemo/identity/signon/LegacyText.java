package org.carddemo.identity.signon;

/** Text rules of the 8-character BMS fields of the sign-on screen. */
public final class LegacyText {

    private LegacyText() {
    }

    /** COBOL FUNCTION UPPER-CASE: only a-z change (NUM-SGN-002). */
    public static String upper(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return b.toString();
    }

    /** Spaces or low-values, as the screen sends an empty field (VAL-SGN-001, VAL-SGN-002). */
    public static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /** Trailing spaces are field padding: {@code PASS} and {@code PASS    } are the same value. */
    public static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\0')) {
            end--;
        }
        return s.substring(0, end);
    }
}
